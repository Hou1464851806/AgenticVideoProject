from langgraph.runtime import Runtime

from ...tools.evidence import valid_evidence
from ..policy import fingerprint, incomplete
from ..state import Context, State, Requirements
from .decision import repair


def source_error(state, ctx, targets, ids):
    for evidence_id in ids:
        record = state["observations"].get(evidence_id)
        if not record:
            return "UNREAD_REFERENCE", f"结果引用了未读取的记录：{evidence_id}。只可引用 observations 中的 ID；需要补读时按 reading.next_pages 继续。"
        asset_id = record.get("asset_id")
        asset = ctx.assets.get(asset_id)
        if not asset or asset_id not in targets:
            return "SOURCE_SCOPE", "结果引用不属于当前素材范围"
        if record.get("analysis_version") != asset["analysis_version"]:
            return "STALE_SOURCE", "结果引用属于旧分析版本，请读取当前版本"
        if not valid_evidence(record, asset, mock=ctx.mock):
            return "SOURCE_RANGE", "结果引用时间超出当前素材范围"
    return None


def check_result(state: State, runtime: Runtime[Context]):
    ctx = runtime.context
    ctx.progress("check_result")
    if state.get("stop_reason"):
        return {"outcome": incomplete(state["stop_reason"], state.get("draft"), mock=ctx.mock), "next_node": "finalize"}
    action = state.get("action") or {}
    validation = state.get("validation") or {}
    requested = (state["requirements"].get("asset_ids") if state.get("intent") == "DRAFT"
                 else (state.get("understanding") or {}).get("asset_ids"))
    targets = set(ctx.assets) if requested is None else set(requested)
    if action.get("kind") == "DRAFT":
        if validation.get("valid") and validation.get("draft_hash") == fingerprint(state["draft"]) and validation.get("requirements_hash") == fingerprint(Requirements.model_validate(state["requirements"]).production(sorted(ctx.assets))):
            for output in state["draft"]["outputs"]:
                for clip in output["clips"]:
                    asset = ctx.assets.get(clip["asset_id"])
                    if not asset or clip["asset_id"] not in targets:
                        return repair(state, ctx, "SOURCE_SCOPE", "草稿片段不属于当前素材范围")
                    if not (0 <= clip["source_in_ms"] < clip["source_out_ms"] <= asset["duration_ms"]):
                        return repair(state, ctx, "SOURCE_RANGE", "草稿片段时间超出当前素材范围")
                    error = source_error(state, ctx, targets, clip["evidence_ids"])
                    if error:
                        return repair(state, ctx, *error)
            return {"draft_ready": True, "outcome": {"status": "DRAFT_READY", "content": ("Mock 样本剪辑草稿已通过模拟校验；尚未保存或渲染。" if ctx.mock else "剪辑草稿已通过 Java 校验；可用 CLI save 保存方案，尚未渲染。"),
                                "draft": state["draft"], "validation": validation, "mock": ctx.mock}, "next_node": "finalize"}
        return repair(state, ctx, "VALIDATION_REQUIRED", "草稿尚未通过当前输入的校验")
    if action.get("kind") == "ANSWER":
        if state.get("intent", state["requirements"].get("mode")) == "DRAFT":
            return {"outcome": incomplete(action["content"], state.get("draft"), mock=ctx.mock), "next_node": "finalize"}
        ids = action.get("evidence_ids", [])
        error = source_error(state, ctx, targets, ids)
        if error:
            return repair(state, ctx, *error)
        text = action.get("content", "")
        if state.get("intent", state["requirements"].get("mode")) == "QUERY" and not ids and not any(word in text for word in ("无法", "未找到", "没有", "不确定", "不足", "未命中")):
            return repair(state, ctx, "SOURCE_REQUIRED", "视频事实答复需要已读来源；无依据应明确无法确定")
        request_text = " ".join([state["request"]["content"], (state.get("understanding") or {}).get("topic") or "",
                                 *[item["content"] for item in state["request"].get("clarifications", [])]])
        if any(word in request_text for word in ("全片", "整段", "全部步骤")):
            missing = [asset_id for asset_id in sorted(targets)
                       if not (asset_id in ctx.assets and state["coverage"].get(asset_id, {}).get("complete")
                               and state["coverage"][asset_id].get("analysis_version") == ctx.assets[asset_id]["analysis_version"])]
            if missing and not any(word in text for word in ("有限", "覆盖不足", "未覆盖", "仅覆盖", "只覆盖", "无法", "不完整")):
                return repair(state, ctx, "COVERAGE_REQUIRED", f"以下素材的全片目录尚未完整浏览：{', '.join(missing)}；请继续或明确说明覆盖不足")
        if any(word in request_text for word in ("画面", "看见", "屏幕")) and ids and not any(state["observations"][i]["source_kind"] in ("VISUAL", "VISIBLE_TEXT") for i in ids):
            return repair(state, ctx, "SOURCE_KIND", "视觉问题不能仅用语音作为依据")
        return {"outcome": {"status": "ANSWER", "content": text, "evidence_ids": ids,
                            "sources": [state["observations"][i] for i in ids], "mock": ctx.mock}, "next_node": "finalize"}
    return {"outcome": incomplete("未获得可核对的结果", state.get("draft"), mock=ctx.mock), "next_node": "finalize"}


def finalize(state: State, runtime: Runtime[Context]):
    runtime.context.progress("finalize")
    outcome = state.get("outcome") or incomplete("未获得可核对的结果", mock=runtime.context.mock)
    history = state.get("messages", [])
    if outcome["status"] not in ("NEW_REQUIREMENT", "PARSED"):
        history = (history + [{"role": "user", "content": state["request"]["content"]},
                              {"role": "assistant", "content": outcome.get("content", "")}])[-12:]
    outcome = {**outcome, "mock": runtime.context.mock}
    return {"outcome": outcome, "messages": history, "waiting": None}

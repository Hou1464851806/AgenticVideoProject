import asyncio

from langgraph.runtime import Runtime

from ...tools.evidence import valid_evidence
from ...tools.mock import MockExecutor
from ...tools.contracts import ToolRequest, ToolResult
from ..policy import LimitReached, TemporaryToolError, await_operation, encoded, fingerprint
from ..state import Context, State, Requirements
from .decision import repair


async def call_tool(state: State, runtime: Runtime[Context]):
    ctx = runtime.context
    ctx.progress("call_tool")
    request = ToolRequest.model_validate(state["pending_tool"])
    try:
        if request.tool_name == "validate_edit_draft" and isinstance(ctx.executor, MockExecutor):
            # Development adapter restores trusted checkpoint observations, never model-supplied proof.
            ctx.executor.read_records = dict(state["observations"])
        with ctx.tracer.tool(request):
            for attempt in range(ctx.limits.attempts):
                ctx.stop()
                ctx.store.check(state["identity"]["execution_id"], ctx.limits)
                try:
                    result = ToolResult.model_validate(await await_operation(ctx.executor.execute(request), ctx, state["identity"]["execution_id"]))
                    break
                except TemporaryToolError:
                    if attempt+1 == ctx.limits.attempts:
                        result = ToolResult(call_id=request.call_id, tool_name=request.tool_name,
                                            request_hash=request.request_hash, status="ERROR",
                                            error={"code": "TOOL_UNAVAILABLE", "category": "TEMPORARY"})
                    else:
                        await asyncio.sleep(0.1)
            result = result.bounded(ctx.limits.tool_bytes)
            ctx.tracer.tool_result(result)
            return {"feedback": result.model_dump(), "next_node": "observe_tool"}
    except LimitReached as error:
        return {"stop_reason": str(error), "next_node": "check_result"}


def observe_tool(state: State, runtime: Runtime[Context]):
    ctx = runtime.context
    ctx.progress("observe_tool")
    request, result = ToolRequest.model_validate(state["pending_tool"]), ToolResult.model_validate(state["feedback"])
    if (result.call_id, result.tool_name, result.request_hash) != (request.call_id, request.tool_name, request.request_hash):
        raise RuntimeError("Tool response does not belong to the pending call")
    if result.status == "PENDING":
        return {"waiting": {"kind": "TOOL_RESULT", "wait_id": fingerprint(request.model_dump()), "call_id": request.call_id,
                            "dependency": result.dependency}, "next_node": "await_input"}
    if result.status == "ERROR":
        if not ctx.mock and result.error.get("category") != "MODEL_REPAIRABLE":
            return {"stop_reason": "真实读取停止：" + encoded(result.error), "pending_tool": None, "next_node": "check_result"}
        return {**repair(state, ctx, result.error.get("code", "TOOL_ERROR"), encoded(result.error)), "pending_tool": None}
    observations = dict(state["observations"])
    added = 0
    for record in result.records:
        asset = ctx.assets.get(record.get("asset_id"))
        if not valid_evidence(record, asset, mock=ctx.mock):
            raise RuntimeError("Tool returned invalid evidence identity or range")
        if record["id"] in observations and observations[record["id"]] != record:
            raise RuntimeError("Immutable evidence ID changed content")
        added += record["id"] not in observations
        observations[record["id"]] = record
    coverage = dict(state["coverage"])
    progress = bool(added)
    if request.tool_name == "read_video_range":
        arguments = request.arguments
        key = fingerprint({k: arguments[k] for k in ("asset_id", "start_ms", "end_ms")})
        ranges = dict(coverage.get("_range_reads", {}))
        entry = dict(ranges.get(key, {**{k: arguments[k] for k in ("asset_id", "start_ms", "end_ms")},
                                     "pages": [], "next_cursor": 0, "complete": False}))
        entry["pages"] = list(entry["pages"])
        cursor = arguments["cursor"]
        progress |= cursor not in entry["pages"] or cursor == entry["next_cursor"]
        if cursor not in entry["pages"]:
            entry["pages"].append(cursor)
        if cursor == entry["next_cursor"]:
            entry["next_cursor"] = result.data.get("next_cursor")
            entry["complete"] = entry["next_cursor"] is None
        ranges[key] = entry
        coverage["_range_reads"] = ranges
    if request.tool_name == "get_video_overview":
        asset_id, cursor = request.arguments["asset_id"], request.arguments["cursor"]
        version = ctx.assets[asset_id]["analysis_version"]
        previous = coverage.get(asset_id, {})
        entry = dict(previous) if previous.get("analysis_version") == version else {"pages": [], "complete": False, "next_cursor": 0}
        entry["pages"] = list(entry["pages"])
        entry["entries_by_page"] = dict(entry.get("entries_by_page", {}))
        entry["analysis_version"] = version
        entry["source_coverage"] = result.coverage
        coverage[asset_id] = entry
        progress |= cursor not in entry["pages"]
        if cursor not in entry["pages"]:
            entry["pages"].append(cursor)
        entry.setdefault("entries_by_page", {})[str(cursor)] = result.data.get("entries", [])
        if cursor == entry.get("next_cursor"):
            entry["next_cursor"] = result.data.get("next_cursor")
            entry["complete"] = entry["next_cursor"] is None
    if request.tool_name == "list_assets":
        signature = fingerprint(result.data.get("assets"))
        progress = coverage.get("_asset_list") != signature
        coverage["_asset_list"] = signature
        pages = dict(coverage.get("_asset_pages", {}))
        pages[str(request.arguments.get("cursor", 0))] = result.data.get("assets", [])
        coverage["_asset_pages"] = pages
        coverage["_assets"] = list({a["id"]: a for page in pages.values() for a in page}.values())
        coverage["_assets_next_cursor"] = result.data.get("next_cursor")
    coverage["_last_read"] = result.coverage
    usage = {**state["usage"], "no_progress": 0 if progress else state["usage"]["no_progress"]+1}
    updates = {"observations": observations, "coverage": coverage, "pending_tool": None, "usage": usage, "next_node": "decide_next"}
    if request.tool_name == "validate_edit_draft":
        if result.data.get("draft_hash") != fingerprint(state["draft"]) or result.data.get("requirements_hash") != fingerprint(Requirements.model_validate(state["requirements"]).production(sorted(ctx.assets))):
            raise RuntimeError("Stale draft validation result")
        updates["validation"] = result.data
        if result.data.get("valid"):
            updates["next_node"] = "check_result"
            updates["usage"] = {**usage, "no_progress": 0}
        else:
            previous = state.get("previous_validation")
            issues = {fingerprint({k: issue[k] for k in ("code", "field", "output_key", "actual", "expected") if k in issue})
                      for issue in result.data["issues"]}
            improved = (previous is not None and not previous["issues_truncated"] and not result.data["issues_truncated"]
                        and previous["requirements_hash"] == result.data["requirements_hash"]
                        and previous["draft_hash"] != result.data["draft_hash"]
                        and issues < set(previous["issues"]))
            updates["previous_validation"] = {"draft_hash": result.data["draft_hash"],
                "requirements_hash": result.data["requirements_hash"], "issues": sorted(issues),
                "issues_truncated": result.data["issues_truncated"]}
            updates.update(repair(state, ctx, "DRAFT_INVALID", "草稿未通过校验；完整结构化问题见 validation。"))
            # The first validation provides new, actionable feedback even after repeated reads.
            # It still consumes repair budget; unchanged later failures retain the stop bound.
            if updates["next_node"] == "decide_next" and (previous is None or improved):
                updates["usage"]["no_progress"] = 0
    return updates

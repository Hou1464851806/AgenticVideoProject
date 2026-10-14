from langgraph.runtime import Runtime
from pydantic import ValidationError

from ...tools.contracts import PARAMETERS, ToolRequest
from ..calls import invoke_model
from ..policy import LimitReached, ModelFormatError, SubmissionUnknown, fingerprint
from ..state import Action, Context, State


async def decide_next(state: State, runtime: Runtime[Context], config):
    ctx = runtime.context
    ctx.progress("decide_next")
    if state["usage"]["no_progress"] >= ctx.limits.no_progress:
        return {"stop_reason": "连续无新信息或有效修正，已有限停止", "next_node": "check_result"}
    try:
        action = Action.model_validate(await invoke_model(state, runtime, "decide", config))
        return {"action": action.model_dump(), "model_sequence": state["model_sequence"]+1,
                "usage": {**state["usage"], "decisions": state["usage"]["decisions"]+1}, "next_node": "check_action"}
    except (ModelFormatError, ValidationError) as error:
        return repair(state, ctx, "MODEL_FORMAT", str(error), model=True)
    except (LimitReached, SubmissionUnknown) as error:
        return {"stop_reason": str(error), "next_node": "check_result"}


def repair(state, ctx, code, message, model=False):
    if state["usage"]["repairs"] >= ctx.limits.repairs:
        return {"feedback": {"error": {"code": code, "message": message[:1000]}},
                "stop_reason": "有限修正额度已用尽", "next_node": "check_result"}
    usage = {**state["usage"], "repairs": state["usage"]["repairs"]+1, "no_progress": state["usage"]["no_progress"]+1}
    return {"feedback": {"error": {"code": code, "message": message[:1000]}}, "usage": usage,
            "model_sequence": state["model_sequence"]+(1 if model else 0),
            "stop_reason": "有限修正额度已用尽" if usage["repairs"] > ctx.limits.repairs else None,
            "next_node": "check_result" if usage["repairs"] > ctx.limits.repairs else "decide_next"}


def check_action(state: State, runtime: Runtime[Context]):
    ctx = runtime.context
    ctx.progress("check_action")
    ctx.stop()
    try:
        ctx.store.check(state["identity"]["execution_id"], ctx.limits)
        action = Action.model_validate(state["action"])
        if action.kind == "ASK":
            if not action.question:
                raise ValueError("ASK requires question")
            return {"waiting": {"kind": "USER_INPUT", "wait_id": fingerprint([state["identity"], state["model_sequence"]]), "question": action.question}, "next_node": "await_input"}
        if action.kind == "ANSWER":
            if not action.content:
                raise ValueError("ANSWER requires content")
            return {"next_node": "check_result"}
        changes = {}
        if action.kind == "DRAFT":
            if state["requirements"].get("mode") != "DRAFT":
                raise ValueError("当前请求没有授权生成草稿")
            if action.draft is None:
                raise ValueError("DRAFT requires draft")
            draft = action.draft.model_dump()
            name = "validate_edit_draft"
            args = {"draft": draft, "requirements": state["requirements"], "read_ids": list(state["observations"]), "read_records": list(state["observations"].values()),
                    "draft_hash": fingerprint(draft), "requirements_hash": fingerprint(state["requirements"])}
            changes = {"draft": draft, "validation": None}
        else:
            name, args = action.tool_name, action.arguments
            if name not in PARAMETERS or name == "validate_edit_draft":
                raise ValueError("Tool is disabled or program-only")
        args = PARAMETERS[name].model_validate(args).model_dump()
        allowed = set(ctx.assets)
        scope = state["requirements"].get("asset_ids")
        if scope is not None:
            allowed &= set(scope)
        if args.get("asset_id") and args["asset_id"] not in allowed:
            raise ValueError("Asset outside fixed scope")
        if args.get("asset_ids") and not set(args["asset_ids"]) <= allowed:
            raise ValueError("Search outside fixed scope")
        if name == "search_evidence" and scope is not None and args.get("asset_ids") is None:
            args["asset_ids"] = sorted(allowed)
        if state["usage"]["tools"] >= ctx.limits.tool_calls:
            raise LimitReached("工具动作额度已用尽")
        suffix = f":v{state['usage']['repairs']}" if name == "validate_edit_draft" else ""
        request = ToolRequest(call_id=f"{state['identity']['execution_id']}:tool:{state['usage']['tools']}{suffix}", tool_name=name,
                              arguments=args, request_hash=fingerprint({"tool": name, "args": args}))
        return {**changes, "pending_tool": request.model_dump(), "feedback": None,
                "usage": {**state["usage"], "tools": state["usage"]["tools"]+1}, "next_node": "call_tool"}
    except (ValidationError, ValueError) as error:
        return repair(state, ctx, "ACTION_INVALID", str(error))
    except LimitReached as error:
        return {"stop_reason": str(error), "next_node": "check_result"}

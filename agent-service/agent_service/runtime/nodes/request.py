from langgraph.runtime import Runtime
from pydantic import ValidationError

from ..calls import invoke_model
from ..policy import LimitReached, ModelFormatError, SubmissionUnknown, fingerprint
from ..state import Context, State, Understanding, merge_requirements


def prepare_turn(state: State):
    return {"action": None, "pending_tool": None, "feedback": None, "waiting": None, "understanding": None,
            "resume_input": None, "outcome": None, "stop_reason": None,
            "usage": {"tools": 0, "repairs": 0, "no_progress": 0, "decisions": 0}, "model_sequence": 0}


async def understand_request(state: State, runtime: Runtime[Context], config):
    ctx = runtime.context
    ctx.progress("understand_request")
    try:
        parsed = Understanding.model_validate(state["request"].get("pre_understanding") or await invoke_model(state, runtime, "understand", config))
        if (parsed.relation == "NEW" and not state["request"].get("pre_understanding")
                and (not state["request"].get("new_requirement") or state["request"].get("clarification"))):
            return {"understanding": parsed.model_dump(), "outcome": {"status": "NEW_REQUIREMENT"}, "next_node": "finalize"}
        if not ctx.mock and parsed.mode not in ("QUERY", "DRAFT"):
            return {"requirements": {**state["requirements"], "mode": "UNSUPPORTED"},
                    "outcome": {"status": "ANSWER", "content": "真实模式支持问答和剪辑草稿；保存请用 CLI save，预览／导出尚未开放。", "evidence_ids": [], "sources": [], "mock": False}, "next_node": "finalize"}
        if state.get("saved_result"):
            return {"outcome": {"status": "SAVED", "content": "已保存制作只读；请用 --new 开始新需求。",
                                "saved_result": state["saved_result"], "mock": ctx.mock}, "next_node": "finalize"}
        previous = state.get("requirements", {})
        req = previous
        if parsed.relation != "AMBIGUOUS":
            try:
                req = merge_requirements(previous, parsed, state["identity"]["turn_id"],
                                         state["request"].get("clarification", state["request"]["content"]))
            except (ValidationError, ValueError):
                if not parsed.question:
                    raise
                # Preserve conflicting parsed fields for the next understanding call,
                # but never apply an invalid or arbitrarily relaxed hard condition.
        updates = {"requirements": req, "validation": state.get("validation") if req == previous else None,
                   "understanding": parsed.model_dump(), "model_sequence": state["model_sequence"]+1}
        if parsed.question or parsed.relation == "AMBIGUOUS":
            question = parsed.question or "这是修改当前需求，还是开始一个新需求？"
            waiting = {"kind": "USER_INPUT", "wait_id": fingerprint([state["identity"]["execution_id"], state["model_sequence"], question]), "question": question}
            updates.update(waiting=waiting, next_node="await_input")
        else:
            updates.update(next_node="decide_next", feedback={"defaults": parsed.defaults})
    except (ModelFormatError, ValidationError, ValueError) as error:
        if state["usage"]["repairs"] >= ctx.limits.repairs:
            return {"stop_reason": "请求理解修正额度已用尽", "next_node": "check_result"}
        usage = {**state["usage"], "repairs": state["usage"]["repairs"]+1}
        return {"feedback": {"error": {"code": "REQUEST_FORMAT", "message": str(error)[:1000]}},
                "usage": usage, "model_sequence": state["model_sequence"]+1,
                "next_node": "understand_request" if usage["repairs"] <= ctx.limits.repairs else "check_result",
                "stop_reason": None if usage["repairs"] <= ctx.limits.repairs else "请求理解修正额度已用尽"}
    except (LimitReached, SubmissionUnknown) as error:
        return {"stop_reason": str(error), "next_node": "check_result"}

    # Infrastructure/protocol failures cannot be repaired by changing model output.
    if not ctx.mock and req["mode"] == "DRAFT" and not state.get("production"):
        updates["production"] = await ctx.executor.bind(state["identity"]["requirement_id"], state["question_scope"])
    return updates

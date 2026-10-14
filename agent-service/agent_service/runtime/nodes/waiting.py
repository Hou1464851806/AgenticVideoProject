from langgraph.runtime import Runtime
from langgraph.types import interrupt

from ...tools.contracts import ToolResult
from ..state import Context, State


def await_input(state: State):
    return {"resume_input": interrupt(state["waiting"])}


def apply_input(state: State, runtime: Runtime[Context]):
    runtime.context.progress("apply_input")
    answer, waiting = state["resume_input"], state["waiting"]
    if answer.get("wait_id") != waiting["wait_id"]:
        raise ValueError("恢复输入没有关联当前等待")
    if answer.get("kind") == "CANCEL":
        return {"waiting": None, "outcome": {"status": "CANCELLED", "content": "已取消本地执行。", "mock": True}, "next_node": "finalize"}
    if waiting["kind"] == "USER_INPUT":
        if answer.get("kind") != "USER_INPUT" or not answer.get("content"):
            raise ValueError("需要用户补答，不能使用工具回执")
        clarifications = state["request"].get("clarifications", []) + [{"question": waiting["question"], "content": answer["content"]}]
        request = {**state["request"], "clarification": answer["content"], "question": waiting["question"], "clarifications": clarifications}
        request.pop("pre_understanding", None)
        return {"request": request, "waiting": None, "next_node": "understand_request"}
    if answer.get("kind") != "TOOL_RESULT":
        raise ValueError("工具等待需要工具回执，普通文本不能代替")
    result = ToolResult.model_validate(answer["result"])
    return {"feedback": result.model_dump(), "waiting": None, "next_node": "observe_tool"}

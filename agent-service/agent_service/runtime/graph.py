from langgraph.graph import END, START, StateGraph

from .state import Context, State
from .nodes.request import prepare_turn, understand_request
from .nodes.decision import decide_next, check_action
from .nodes.tools import call_tool, observe_tool
from .nodes.waiting import await_input, apply_input
from .nodes.result import check_result, finalize


def build_graph(checkpointer):
    graph = StateGraph(State, context_schema=Context)
    nodes = {"prepare_turn": prepare_turn, "understand_request": understand_request,
             "decide_next": decide_next, "check_action": check_action, "call_tool": call_tool,
             "observe_tool": observe_tool, "check_result": check_result, "await_input": await_input,
             "apply_input": apply_input, "finalize": finalize}
    for name, fn in nodes.items():
        graph.add_node(name, fn)
    graph.add_edge(START, "prepare_turn")
    graph.add_edge("prepare_turn", "understand_request")
    for name in ("understand_request", "decide_next", "check_action", "call_tool", "observe_tool", "check_result", "apply_input"):
        graph.add_conditional_edges(name, lambda state: state["next_node"], list(nodes))
    graph.add_edge("await_input", "apply_input")
    graph.add_edge("finalize", END)
    return graph.compile(checkpointer=checkpointer)

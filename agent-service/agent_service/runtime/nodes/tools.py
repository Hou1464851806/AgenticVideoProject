import asyncio

from langgraph.runtime import Runtime

from ...tools.contracts import ToolRequest, ToolResult
from ..policy import LimitReached, TemporaryToolError, await_operation, encoded, fingerprint
from ..state import Context, State
from .decision import repair


async def call_tool(state: State, runtime: Runtime[Context]):
    ctx = runtime.context
    ctx.progress("call_tool")
    request = ToolRequest.model_validate(state["pending_tool"])
    try:
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
        return {**repair(state, ctx, result.error.get("code", "TOOL_ERROR"), encoded(result.error)), "pending_tool": None}
    observations = dict(state["observations"])
    added = 0
    for record in result.records:
        asset = ctx.assets.get(record.get("asset_id"))
        if not asset or record.get("analysis_version") != asset["analysis_version"] or not (0 <= record["start_ms"] < record["end_ms"] <= asset["duration_ms"]):
            raise RuntimeError("Tool returned invalid evidence identity or range")
        if record["id"] in observations and observations[record["id"]] != record:
            raise RuntimeError("Immutable evidence ID changed content")
        added += record["id"] not in observations
        observations[record["id"]] = record
    coverage = dict(state["coverage"])
    progress = bool(added)
    if request.tool_name == "get_video_overview":
        asset_id, cursor = request.arguments["asset_id"], request.arguments["cursor"]
        version = ctx.assets[asset_id]["analysis_version"]
        previous = coverage.get(asset_id, {})
        entry = dict(previous) if previous.get("analysis_version") == version else {"pages": [], "complete": False, "next_cursor": 0}
        entry["pages"] = list(entry["pages"])
        entry["entries_by_page"] = dict(entry.get("entries_by_page", {}))
        entry["analysis_version"] = version
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
        coverage["_assets"] = result.data.get("assets", [])
    usage = {**state["usage"], "no_progress": 0 if progress else state["usage"]["no_progress"]+1}
    updates = {"observations": observations, "coverage": coverage, "pending_tool": None, "usage": usage, "next_node": "decide_next"}
    if request.tool_name == "validate_edit_draft":
        if result.data.get("draft_hash") != fingerprint(state["draft"]) or result.data.get("requirements_hash") != fingerprint(state["requirements"]):
            raise RuntimeError("Stale draft validation result")
        updates["validation"] = result.data
        if result.data.get("valid"):
            updates["next_node"] = "check_result"
            updates["usage"] = {**usage, "no_progress": 0}
        else:
            previous = state.get("validation") or {}
            issues = result.data.get("issues", [])
            improved = bool(previous) and len(issues) < len(previous.get("issues", []))
            updates.update(repair(state, ctx, "DRAFT_INVALID", encoded(result.data)))
            if improved:
                updates["usage"]["no_progress"] = 0
    return updates

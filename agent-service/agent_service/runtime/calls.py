"""Budgeted model calls with stable identities and cancellable I/O."""
from .policy import ModelFormatError, await_operation, fingerprint


def model_context(state):
    context = {key: state.get(key) for key in ("request", "requirements", "understanding", "messages", "observations", "coverage", "draft", "validation", "feedback", "usage")}
    if "intent" in state:
        context["intent"] = state["intent"]
    return context


def model_view(context):
    """Project model input without changing authoritative evidence or receipt identity."""
    view = dict(context)
    observations = context.get("observations") or {}
    coverage = context.get("coverage") or {}
    mode = (context.get("intent") or (context.get("understanding") or {}).get("mode")
            or (context.get("requirements") or {}).get("mode"))
    view["observations"] = {}
    for identity, record in observations.items():
        projected = {k: v for k, v in record.items() if k != "observation"}
        if "observation" in record:
            details = (record["observation"] if mode == "DRAFT" else
                       {k: v for k, v in record["observation"].items() if k in ("emotion", "language")})
            if details:
                projected["observation"] = details
        view["observations"][identity] = projected
    view["coverage"] = {k: v for k, v in coverage.items() if k != "_asset_pages"}
    selected = ((context.get("requirements") or {}).get("asset_ids") if mode == "DRAFT"
                else (context.get("understanding") or {}).get("asset_ids"))
    reading = {"assets": {}, "next_pages": []}
    ranges = list(coverage.get("_range_reads", {}).values())
    for asset in context.get("available_assets", []):
        asset_id = asset["id"]
        entry = coverage.get(asset_id, {})
        if "entries_by_page" in entry:
            view["coverage"][asset_id] = {**entry, "entries_by_page": {
                page: [{k: v for k, v in row.items() if k != "navigation_ids"} for row in rows
                       if not row.get("navigation_ids") or not set(row["navigation_ids"]) <= observations.keys()]
                for page, rows in entry["entries_by_page"].items()}}
        if selected is not None and asset_id not in selected:
            continue
        asset_ranges = [r for r in ranges if r["asset_id"] == asset_id]
        reading["assets"][asset_id] = {
            "read_record_count": sum(r["asset_id"] == asset_id for r in observations.values()),
            "overview_complete": entry.get("complete", False),
            "overview_next_cursor": entry.get("next_cursor", 0),
            "complete_ranges": [{k: r[k] for k in ("start_ms", "end_ms")} for r in asset_ranges if r["complete"]]}
        reading["next_pages"].extend(
            {k: r[k] for k in ("asset_id", "start_ms", "end_ms")} | {"cursor": r["next_cursor"]}
            for r in asset_ranges if not r["complete"])
    view["reading"] = reading
    feedback = context.get("feedback")
    if feedback:
        view["feedback"] = {k: v for k, v in feedback.items() if k != "records"}
        if "data" in feedback:
            view["feedback"]["data"] = {k: v for k, v in feedback["data"].items() if k != "entries"}
    return view


async def invoke_model(state, runtime, phase, config):
    ctx = runtime.context
    ctx.stop()
    execution_id = state["identity"]["execution_id"]
    context = model_context(state)
    context["mock_tools"] = ctx.mock
    context["available_assets"] = list(ctx.assets.values())
    # Hash the unchanged core context so pre-deployment receipts remain reusable.
    input_hash = fingerprint({"phase": phase, "context": context})
    context = model_view(context)
    context["budget"] = {"model_calls_remaining": ctx.limits.model_calls - ctx.store.usage(execution_id)["model_calls"]}
    if hasattr(ctx.model, "prepare"):
        ctx.model.prepare(phase, context)
    call_id = f"{execution_id}:{phase}:{state.get('model_sequence', 0)}"
    saved = ctx.store.reserve(call_id, execution_id, input_hash, ctx.limits)
    if saved is not None:
        ctx.tracer.reused(call_id)
        reply = saved
    else:
        model_config = {**config, "metadata": {**config.get("metadata", {}), "call_id": call_id, "prompt_version": "mvp-2"}}
        try:
            reply = await await_operation(ctx.model.invoke(phase, context, model_config), ctx, execution_id)
            ctx.store.received(call_id, reply)
        except BaseException:
            ctx.store.unknown(call_id)
            raise
    if reply.get("error"):
        raise ModelFormatError(reply["error"][:1000])
    return reply["value"]

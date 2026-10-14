"""Budgeted model calls with stable identities and cancellable I/O."""
from .policy import ModelFormatError, await_operation, fingerprint


def model_context(state):
    return {key: state.get(key) for key in ("request", "requirements", "understanding", "messages", "observations", "coverage", "draft", "feedback", "usage")}


async def invoke_model(state, runtime, phase, config):
    ctx = runtime.context
    ctx.stop()
    execution_id = state["identity"]["execution_id"]
    context = model_context(state)
    context["available_assets"] = list(ctx.assets.values())
    if hasattr(ctx.model, "prepare"):
        ctx.model.prepare(phase, context)
    input_hash = fingerprint({"phase": phase, "context": context})
    call_id = f"{execution_id}:{phase}:{state.get('model_sequence', 0)}"
    saved = ctx.store.reserve(call_id, execution_id, input_hash, ctx.limits)
    if saved is not None:
        ctx.tracer.reused(call_id)
        reply = saved
    else:
        model_config = {**config, "metadata": {**config.get("metadata", {}), "call_id": call_id, "prompt_version": "mvp-1"}}
        try:
            reply = await await_operation(ctx.model.invoke(phase, context, model_config), ctx, execution_id)
            ctx.store.received(call_id, reply)
        except BaseException:
            ctx.store.unknown(call_id)
            raise
    if reply.get("error"):
        raise ModelFormatError(reply["error"][:1000])
    return reply["value"]

"""One place for local execution limits and repairable error categories."""
import hashlib
import json
import asyncio
import contextlib
from dataclasses import dataclass


@dataclass(frozen=True)
class Limits:
    model_calls: int = 12
    tool_calls: int = 20
    repairs: int = 3
    attempts: int = 2
    no_progress: int = 3
    active_seconds: float = 300
    output_tokens: int = 2048
    prompt_bytes: int = 65536
    tool_bytes: int = 32768


class LimitReached(RuntimeError):
    pass


class SubmissionUnknown(RuntimeError):
    pass


class Cancelled(RuntimeError):
    pass


class ModelFormatError(RuntimeError):
    pass


class TemporaryToolError(RuntimeError):
    """Executor guarantees no external submission occurred."""


def encoded(value) -> str:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def fingerprint(value) -> str:
    return hashlib.sha256(encoded(value).encode()).hexdigest()


def incomplete(reason: str, draft=None, *, mock=True) -> dict:
    return {"status": "INCOMPLETE", "content": reason, "evidence_ids": [], "draft": draft, "mock": mock}


async def await_operation(operation, ctx, execution_id):
    """Keep cancellation and active time effective while an external call is pending."""
    task = asyncio.create_task(operation)
    try:
        while not task.done():
            await asyncio.wait({task}, timeout=0.1)
            ctx.stop()
            ctx.store.check(execution_id, ctx.limits)
        return task.result()
    except BaseException:
        if not task.done():
            task.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await task
        raise

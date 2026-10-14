"""Independent public entry point; no Java task lifecycle or message consumer."""
import asyncio
import fcntl
from pathlib import Path
import uuid

from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from langgraph.types import Command

from ..config import Config
from ..model import DeepSeekModel
from ..tools.mock import MockExecutor
from ..tools.contracts import ToolRequest, ToolResult
from ..tracing import Tracer
from .graph import build_graph
from .persistence import Store
from .policy import Cancelled, LimitReached, SubmissionUnknown, incomplete
from .state import Context, Requirements


class Agent:
    def __init__(self, config=None, model=None, executor=None, assets=None, progress=None):
        self.config = config or Config()
        self.config.directory.mkdir(parents=True, exist_ok=True)
        self.path = self.config.directory / "agent.sqlite"
        if executor is None:
            executor = MockExecutor(self.config.scenario)
            assets = executor.assets if assets is None else assets
        elif assets is None:
            raise ValueError("自定义 ToolExecutor 必须显式提供 assets 素材范围")
        self.executor, self.assets = executor, assets
        if model is None:
            import os
            key = os.environ.get("DEEPSEEK_API_KEY")
            if not key:
                raise ValueError("请配置 DEEPSEEK_API_KEY；离线场景请显式选择 scripted 模式")
            model = DeepSeekModel(key, self.config.limits, os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))
        self.model = model
        self.progress = progress or (lambda node: None)

    async def send(self, content, *, thread_id=None, session_id=None, new=False):
        if not content.strip():
            raise ValueError("用户消息不能为空")
        thread_id = thread_id or str(uuid.uuid4())
        async with AsyncSqliteSaver.from_conn_string(str(self.path)) as saver:
            graph = build_graph(saver)
            snapshot = await graph.aget_state({"configurable": {"thread_id": thread_id}})
            if snapshot.next:
                raise ValueError("该需求尚未结束，请补答、恢复或取消")
            old = snapshot.values
        if new and old:
            thread_id, old = str(uuid.uuid4()), {}
        identity = {"session_id": session_id or old.get("identity", {}).get("session_id") or str(uuid.uuid4()),
                    "requirement_id": thread_id, "execution_id": str(uuid.uuid4()), "turn_id": str(uuid.uuid4())}
        state = {"identity": identity, "request": {"content": content, "new_requirement": not bool(old)},
                 "requirements": old.get("requirements", Requirements().model_dump()), "messages": old.get("messages", []),
                 "observations": old.get("observations", {}), "coverage": old.get("coverage", {}),
                 "draft": old.get("draft"), "validation": old.get("validation"), "scenario": self.config.scenario}
        return await self._drive(thread_id, state)

    async def inspect(self, thread_id):
        async with AsyncSqliteSaver.from_conn_string(str(self.path)) as saver:
            snapshot = await build_graph(saver).aget_state({"configurable": {"thread_id": thread_id}})
            if not snapshot.values:
                raise ValueError("需求不存在")
            result = self._view(thread_id, snapshot.values, bool(snapshot.interrupts))
            store = Store(self.path)
            try:
                result["usage"] = {**result["usage"], **store.usage(snapshot.values["identity"]["execution_id"])}
            finally:
                store.close()
            return result

    async def resume(self, thread_id, payload=None):
        async with AsyncSqliteSaver.from_conn_string(str(self.path)) as saver:
            snapshot = await build_graph(saver).aget_state({"configurable": {"thread_id": thread_id}})
        if not snapshot.values:
            raise ValueError("需求不存在")
        if not snapshot.next:
            return self._view(thread_id, snapshot.values, False)
        waiting = snapshot.values.get("waiting")
        if snapshot.interrupts:
            if payload is None:
                return self._view(thread_id, snapshot.values, True)
            payload = self._validate_resume(snapshot.values, payload)
            value = Command(resume=payload)
        else:
            if payload is not None:
                raise ValueError("没有等待输入，故障恢复不接受补答")
            value = None
        return await self._drive(thread_id, value, snapshot.values)

    def _validate_resume(self, state, payload):
        waiting = state["waiting"]
        if payload.get("wait_id") != waiting["wait_id"]:
            raise ValueError("wait_id 不匹配")
        if payload.get("kind") == "CANCEL":
            return payload
        if payload.get("kind") != waiting["kind"]:
            raise ValueError("恢复输入类型不匹配")
        if waiting["kind"] == "USER_INPUT":
            if not payload.get("content", "").strip():
                raise ValueError("补答不能为空")
        else:
            result, req = ToolResult.model_validate(payload["result"]), ToolRequest.model_validate(state["pending_tool"])
            if (result.call_id, result.tool_name, result.request_hash) != (req.call_id, req.tool_name, req.request_hash):
                raise ValueError("工具回执不匹配")
            payload = {**payload, "result": result.bounded(self.config.limits.tool_bytes).model_dump()}
        return payload

    async def cancel(self, thread_id):
        # Out-of-band cancellation can be called while another process owns the lock.
        async with AsyncSqliteSaver.from_conn_string(str(self.path)) as saver:
            snapshot = await build_graph(saver).aget_state({"configurable": {"thread_id": thread_id}})
        if not snapshot.values:
            raise ValueError("需求不存在")
        store = Store(self.path)
        execution_id = snapshot.values["identity"]["execution_id"]
        try:
            store.cancel(execution_id)
        finally:
            store.close()
        if snapshot.interrupts:
            return await self._drive(thread_id, None, snapshot.values, cancel=True)
        return {"thread_id": thread_id, "status": "CANCELLED", "content": "本地取消已记录，停止新动作；远端费用不能保证撤回。"}

    async def _drive(self, thread_id, value, previous=None, cancel=False):
        identity = (previous or value)["identity"]
        lock = open(self.config.directory / (thread_id+".lock"), "a")
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            lock.close()
            raise ValueError("该需求正在另一个本机进程执行") from None
        store, tracer = Store(self.path), Tracer(self.config.trace)
        def stop():
            store.check(identity["execution_id"], self.config.limits)
        ctx = Context(self.model, self.executor, store, self.config.limits, tracer, stop, self.progress, self.assets)
        config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 256,
                  "callbacks": tracer.callbacks, "metadata": {**identity, "langfuse_session_id": identity["session_id"], "mock_tools": True}}
        status = "FAILED"
        try:
            async with AsyncSqliteSaver.from_conn_string(str(self.path)) as saver:
                graph = build_graph(saver)
                if cancel:
                    outcome = {"status": "CANCELLED", "content": "已取消本地执行；不会提交新动作。", "mock": True}
                    await graph.aupdate_state(config, {"outcome": outcome, "waiting": None}, as_node="finalize")
                    status = "CANCELLED"
                else:
                    store.start(identity["execution_id"])
                    try:
                        with tracer.segment(identity):
                            await graph.ainvoke(value, config, context=ctx, durability="sync")
                            observed = await graph.aget_state(config)
                            tracer.result(self._view(thread_id, observed.values, bool(observed.interrupts)))
                    except (Cancelled, LimitReached, SubmissionUnknown) as error:
                        outcome = {"status": "CANCELLED", "content": str(error), "mock": True} if isinstance(error, Cancelled) else incomplete(str(error))
                        await graph.aupdate_state(config, {"outcome": outcome, "waiting": None}, as_node="finalize")
                    snapshot = await graph.aget_state(config)
                    status = "WAITING" if snapshot.interrupts else (snapshot.values.get("outcome") or {}).get("status", "FAILED")
                snapshot = await graph.aget_state(config)
                result = self._view(thread_id, snapshot.values, bool(snapshot.interrupts))
                if status == "NEW_REQUIREMENT":
                    new_thread = str(uuid.uuid4())
                    # Preserve this message's execution and model receipt; isolate the new requirement.
                    migrated = {"identity": {**identity, "requirement_id": new_thread},
                                "request": {"content": snapshot.values["request"]["content"], "new_requirement": True,
                                            "pre_understanding": snapshot.values["understanding"]},
                                "requirements": Requirements().model_dump(), "messages": [], "observations": {}, "coverage": {},
                                "draft": None, "validation": None, "scenario": self.config.scenario}
                    store.finish(status)
                    return await self._drive(new_thread, migrated)
                store.finish(status)
                result["usage"] = {**snapshot.values.get("usage", {}), **store.usage(identity["execution_id"])}
                if tracer.trace_id:
                    result["trace_id"] = tracer.trace_id
                return result
        finally:
            store.finish(status)
            store.close()
            await tracer.flush()
            fcntl.flock(lock, fcntl.LOCK_UN)
            lock.close()

    @staticmethod
    def _view(thread_id, state, waiting):
        return {"thread_id": thread_id, "identity": state["identity"], "status": "WAITING" if waiting else (state.get("outcome") or {}).get("status", "FAILED"),
                "waiting": state.get("waiting") if waiting else None, "outcome": state.get("outcome"),
                "requirements": state["requirements"], "pending_tool": state.get("pending_tool"), "usage": state.get("usage", {})}

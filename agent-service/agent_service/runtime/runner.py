"""Independent public entry point; no Java task lifecycle or message consumer."""
import asyncio
import fcntl
from pathlib import Path
import uuid

from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from langgraph.types import Command

from ..config import Config
from ..editing import SaveRequest
from ..model import DeepSeekModel
from ..tools.mock import MockExecutor
from ..tools.java import JavaToolExecutor
from ..tools.contracts import ToolRequest, ToolResult
from ..tracing import Tracer
from .graph import build_graph
from .persistence import Store
from .policy import Cancelled, LimitReached, SubmissionUnknown, fingerprint, incomplete
from .state import Context, Requirements


class Agent:
    def __init__(self, config=None, model=None, executor=None, assets=None, progress=None):
        self.config = config or Config()
        self.config.directory.mkdir(parents=True, exist_ok=True)
        self.path = self.config.directory / "agent.sqlite"
        if executor is None:
            executor = MockExecutor(self.config.scenario)
            assets = executor.assets if assets is None else assets
        elif assets is None and not isinstance(executor, JavaToolExecutor):
            raise ValueError("自定义 ToolExecutor 必须显式提供 assets 素材范围")
        self.executor, self.assets = executor, assets
        self.mock = getattr(executor, "mock", True) is not False
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
            checkpoint = snapshot.config["configurable"].get("checkpoint_id")
        if new and old:
            thread_id, old, checkpoint = str(uuid.uuid4()), {}, None
        if old.get("save_request") and not old.get("saved_result"):
            raise ValueError("上次保存结果尚未核对；请先执行 save 恢复，再开始修改")
        if old and bool(old.get("question_scope")) == self.mock:
            raise ValueError("不能切换已有需求的真实/Mock模式；请使用 --new")
        identity = {"session_id": session_id or old.get("identity", {}).get("session_id") or str(uuid.uuid4()),
                    "requirement_id": thread_id, "execution_id": str(uuid.uuid4()), "turn_id": str(uuid.uuid4())}
        state = {"identity": identity, "request": {"content": content, "new_requirement": not bool(old)},
                 "requirements": old.get("requirements", Requirements().model_dump()), "messages": old.get("messages", []),
                 "observations": old.get("observations", {}), "coverage": old.get("coverage", {}),
                 "question_scope": old.get("question_scope") if old.get("production") else None,
                 "production": old.get("production"), "previous_validation": None,
                 "save_request": old.get("save_request"), "saved_result": old.get("saved_result"),
                 "draft": old.get("draft"), "draft_ready": old.get("draft_ready", (old.get("outcome") or {}).get("status") == "DRAFT_READY"), "validation": old.get("validation"), "scenario": self.config.scenario}
        if old.get("question_scope") and not self.mock:
            self.executor.project_id = old["question_scope"]["project_id"]
            self.executor.asset_ids = [a["id"] for a in old["question_scope"]["assets"]]
        if not self.mock and not old.get("production"):
            state.update(observations={}, coverage={}, draft=None, validation=None)
        return await self._drive(thread_id, state, expected_checkpoint=checkpoint)

    async def prepare_run(self, value):
        """Understand a fixed RunInput once, before Java chooses its execution binding."""
        from .conversation import prepare_run
        return await prepare_run(self, value)

    async def execute_run(self, value, *, identity, scope=None, production=None, question=None, edit_generation=None):
        """Execute a persisted understanding with the binding authorized by Java."""
        from .conversation import execute_run
        return await execute_run(self, value, identity=identity, scope=scope, production=production,
                                 question=question, edit_generation=edit_generation)

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
        if snapshot.values.get("save_request") and not snapshot.values.get("saved_result"):
            return await self.save(thread_id)
        if not snapshot.next:
            return await self.inspect(thread_id)
        waiting = snapshot.values.get("waiting")
        if snapshot.interrupts:
            if payload is None:
                return await self.inspect(thread_id)
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

    async def save(self, thread_id):
        if not isinstance(self.executor, JavaToolExecutor):
            raise ValueError("save 只支持 Java 真实制作")
        lock = open(self.config.directory / (thread_id + ".lock"), "a")
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
            async with AsyncSqliteSaver.from_conn_string(str(self.path)) as saver:
                graph = build_graph(saver)
                config = {"configurable": {"thread_id": thread_id}}
                snapshot = await graph.aget_state(config)
                state = snapshot.values
                if not state or not state.get("production"):
                    raise ValueError("没有可保存的真实制作需求")
                if state["request"].get("public_context"):
                    raise ValueError("浏览器制作须通过 Java 草稿确认入口保存")
                production = state["production"]
                if self.executor.project_id and self.executor.project_id != production["project_id"]:
                    raise ValueError("保存项目与原制作不匹配")
                if snapshot.next:
                    raise ValueError("请先完成当前执行到 DRAFT_READY")
                if not state.get("save_request"):
                    validation = state.get("validation") or {}
                    requirements = Requirements.model_validate(state["requirements"]).production(
                        sorted(a["id"] for a in state["question_scope"]["assets"]))
                    if not state.get("draft_ready", (state.get("outcome") or {}).get("status") == "DRAFT_READY") or not validation.get("valid"):
                        raise ValueError("只有 DRAFT_READY 的当前草稿可以保存")
                    if validation["draft_hash"] != fingerprint(state["draft"]) or validation["requirements_hash"] != fingerprint(requirements):
                        raise ValueError("草稿或条件已改变，请重新校验到 DRAFT_READY")
                    request = {**production, "validation_id": validation["validation_id"],
                               "draft_hash": validation["draft_hash"], "requirements_hash": validation["requirements_hash"],
                               "requirements_sources": state["requirements"]["sources"]}
                    request = SaveRequest.model_validate(request).model_dump()
                    await graph.aupdate_state(config, {"save_request": request}, as_node="finalize")
                else:
                    request = state["save_request"]
                # Query the authoritative result before any retry; do not require live scope dependencies.
                saved = await self.executor.saved(production["requirement_id"])
                if saved is None:
                    saved = await self.executor.save(request)
                await graph.aupdate_state(config, {"saved_result": saved,
                    "outcome": {"status": "SAVED", "content": "方案已保存，当前制作结束。", "saved_result": saved, "mock": False}}, as_node="finalize")
                final = await graph.aget_state(config)
                return self._view(thread_id, final.values, False)
        except BlockingIOError:
            raise ValueError("该需求正在另一个本机进程执行") from None
        finally:
            lock.close()

    async def _drive(self, thread_id, value, previous=None, cancel=False, expected_checkpoint=False):
        if self.model is None and not cancel:
            import os
            key = os.environ.get("DEEPSEEK_API_KEY")
            if not key:
                raise ValueError("请配置 DEEPSEEK_API_KEY；离线场景请显式选择 scripted 模式")
            self.model = DeepSeekModel(key, self.config.limits, os.environ.get("DEEPSEEK_BASE_URL", "https://api.deepseek.com"))
        identity = (previous or value)["identity"]
        lock = open(self.config.directory / (thread_id+".lock"), "a")
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            lock.close()
            raise ValueError("该需求正在另一个本机进程执行") from None
        store, tracer = Store(self.path), Tracer(self.config.trace, mock=self.mock)
        def stop():
            store.check(identity["execution_id"], self.config.limits)
        try:
            if expected_checkpoint is not False:
                async with AsyncSqliteSaver.from_conn_string(str(self.path)) as saver:
                    latest = await build_graph(saver).aget_state({"configurable": {"thread_id": thread_id}})
                if latest.config["configurable"].get("checkpoint_id") != expected_checkpoint:
                    raise ValueError("需求已被另一个本机操作更新，请重新查看后重试")
            if (previous or value)["request"].get("parse_only"):
                pass
            elif isinstance(self.executor, JavaToolExecutor):
                saved = (previous or value).get("question_scope")
                if previous and not saved:
                    raise ValueError("不能用真实执行器恢复 Mock 需求")
                scope = saved if cancel else await self.executor.snapshot(saved)
                self.assets = {asset["id"]: asset for asset in scope["assets"]}
                self.executor.requirement_id = ((previous or value).get("production") or {}).get("requirement_id")
                if value is not None and previous is None:
                    value["question_scope"] = scope
            elif (previous or value).get("question_scope"):
                raise ValueError("真实需求需要原 Java 执行器配置")
        except BaseException:
            store.close()
            fcntl.flock(lock, fcntl.LOCK_UN)
            lock.close()
            raise
        ctx = Context(self.model, self.executor, store, self.config.limits, tracer, stop, self.progress, self.assets or {}, self.mock)
        scope_id = ((previous or value).get("question_scope") or {}).get("scope_id")
        config = {"configurable": {"thread_id": thread_id}, "recursion_limit": 256,
                  "callbacks": tracer.callbacks, "metadata": {**identity, "langfuse_session_id": identity["session_id"], "mock_tools": self.mock, "question_scope_id": scope_id}}
        status = "FAILED"
        try:
            async with AsyncSqliteSaver.from_conn_string(str(self.path)) as saver:
                graph = build_graph(saver)
                if cancel:
                    outcome = {"status": "CANCELLED", "content": "已取消本地执行；不会提交新动作。", "mock": self.mock}
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
                        outcome = {"status": "CANCELLED", "content": str(error), "mock": self.mock} if isinstance(error, Cancelled) else incomplete(str(error), mock=self.mock)
                        await graph.aupdate_state(config, {"outcome": outcome, "waiting": None}, as_node="finalize")
                    snapshot = await graph.aget_state(config)
                    status = "WAITING" if snapshot.interrupts else (snapshot.values.get("outcome") or {}).get("status", "FAILED")
                snapshot = await graph.aget_state(config)
                result = self._view(thread_id, snapshot.values, bool(snapshot.interrupts))
                if status == "NEW_REQUIREMENT":
                    new_thread = str(uuid.uuid4())
                    # The parsed understanding is reused; model-call identities belong to the new execution.
                    migrated = {"identity": {**identity, "requirement_id": new_thread, "execution_id": str(uuid.uuid4())},
                                "request": {**snapshot.values["request"], "new_requirement": True,
                                            "pre_understanding": snapshot.values["understanding"]},
                                "requirements": Requirements().model_dump(), "messages": [], "observations": {}, "coverage": {},
                                "draft": None, "validation": None, "scenario": self.config.scenario,
                                "question_scope": None, "production": None, "previous_validation": None, "save_request": None, "saved_result": None}
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
        outcome = state.get("outcome")
        if state.get("save_request") and not state.get("saved_result"):
            outcome = incomplete("保存结果尚未核对，请执行 save 或 resume 恢复。", state.get("draft"), mock=False)
        return {"thread_id": thread_id, "identity": state["identity"], "status": "WAITING" if waiting else (outcome or {}).get("status", "FAILED"),
                "waiting": state.get("waiting") if waiting else None, "outcome": outcome,
                "parsed_input": state.get("parsed_input"), "understanding": state.get("understanding"),
                "intent": state.get("intent"), "edit_generation": state["request"].get("edit_generation"), "production": state.get("production"), "draft": state.get("draft"), "validation": state.get("validation"),
                "save_request": state.get("save_request"), "saved_result": state.get("saved_result"), "question_scope": state.get("question_scope"), "requirements": state["requirements"], "pending_tool": state.get("pending_tool"), "usage": state.get("usage", {})}

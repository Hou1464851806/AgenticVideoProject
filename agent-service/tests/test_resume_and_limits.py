import asyncio
import json
from pathlib import Path
import subprocess
import sys

import pytest

from agent_service.runtime.policy import Limits
from agent_service.tools.contracts import ToolRequest
from test_agent_scenarios import agent, answer, read, understanding


@pytest.mark.asyncio
async def test_clarification_resumes_with_budget_and_conditions(tmp_path):
    def parse(ctx):
        return understanding("DISCUSS", topic="明确主题") if ctx["request"].get("clarification") else understanding("DISCUSS", question="要讨论哪个主题？")
    first = await agent(tmp_path, parse, [answer([], "主题已明确。")]).send("讨论一下")
    assert first["status"] == "WAITING"
    runner = agent(tmp_path, parse, [answer([], "主题已明确。")])
    result = await runner.resume(first["thread_id"], {"kind": "USER_INPUT", "wait_id": first["waiting"]["wait_id"], "content": "明确主题"})
    assert result["status"] == "ANSWER"
    assert result["identity"]["execution_id"] == first["identity"]["execution_id"]
    assert result["usage"]["model_calls"] > first["usage"]["model_calls"]


@pytest.mark.asyncio
async def test_tool_pending_rejects_wrong_event_and_resumes(tmp_path):
    inspect = {"kind": "TOOL", "tool_name": "inspect_video_range", "arguments": {"asset_id": "demo", "start_ms": 20000, "end_ms": 21000, "goal": "面板"}}
    first = await agent(tmp_path, actions=[inspect, answer([], "有限观察无法确定全程")], scenario="pending").send("查看画面")
    runner = agent(tmp_path, actions=[inspect, answer([], "有限观察无法确定全程")], scenario="pending")
    with pytest.raises(ValueError):
        await runner.resume(first["thread_id"], {"kind": "USER_INPUT", "wait_id": first["waiting"]["wait_id"], "content": "好了"})
    complete = runner.executor.complete(ToolRequest.model_validate(first["pending_tool"]))
    result = await runner.resume(first["thread_id"], {"kind": "TOOL_RESULT", "wait_id": first["waiting"]["wait_id"], "result": complete.model_dump()})
    assert result["status"] == "ANSWER"
    assert result["usage"]["tools"] == 1
    assert result["identity"] == first["identity"]


def test_cli_process_restart_tool_wait(tmp_path):
    root = Path(__file__).resolve().parents[1]
    command = [sys.executable, "-m", "agent_service", "--script", "tests/fixtures/pending.json", "--no-trace", "--scenario", "pending", "--data-dir", str(tmp_path)]
    first = subprocess.run(command+["run", "查看画面"], cwd=root, capture_output=True, text=True, check=True)
    state = json.loads(first.stdout)
    second = subprocess.run(command+["complete-mock", state["thread_id"]], cwd=root, capture_output=True, text=True, check=True)
    resumed = json.loads(second.stdout)
    assert resumed["status"] == "ANSWER"
    assert resumed["identity"] == state["identity"]
    assert resumed["usage"]["tools"] == 1


@pytest.mark.asyncio
async def test_model_limit_still_produces_template_without_extra_call(tmp_path):
    result = await agent(tmp_path, limits=Limits(model_calls=1)).send("缓存")
    assert result["status"] == "INCOMPLETE"
    assert result["usage"]["model_calls"] == 1


@pytest.mark.asyncio
async def test_repeated_no_progress_stops(tmp_path):
    result = await agent(tmp_path, actions=[read()], limits=Limits(no_progress=2)).send("缓存")
    assert result["status"] == "INCOMPLETE"
    assert result["usage"]["tools"] <= 3


@pytest.mark.asyncio
async def test_unknown_response_does_not_resubmit_on_resume(tmp_path):
    class LostModel:
        calls = 0
        async def invoke(self, *args):
            self.calls += 1
            raise ConnectionError("Injected response lost")
    runner = agent(tmp_path)
    model = LostModel()
    runner.model = model
    with pytest.raises(ConnectionError):
        await runner.send("缓存", thread_id="lost-receipt")
    result = await runner.resume("lost-receipt")
    assert result["status"] == "INCOMPLETE"
    assert model.calls == 1
    assert result["usage"]["model_calls"] == 1


@pytest.mark.asyncio
async def test_cancel_wait_prevents_late_result(tmp_path):
    first = await agent(tmp_path, understanding("DISCUSS", question="主题？"), [answer([], "讨论")]).send("讨论")
    runner = agent(tmp_path)
    result = await runner.cancel(first["thread_id"])
    assert result["status"] == "CANCELLED"
    late = await runner.resume(first["thread_id"], {"kind": "USER_INPUT", "wait_id": first["waiting"]["wait_id"], "content": "缓存"})
    assert late["status"] == "CANCELLED"


@pytest.mark.asyncio
async def test_active_cancellation_stops_new_model_calls(tmp_path):
    class SlowModel:
        calls = 0
        async def invoke(self, *args):
            self.calls += 1
            await asyncio.sleep(20)
    runner = agent(tmp_path)
    runner.model = SlowModel()
    task = asyncio.create_task(runner.send("缓存", thread_id="active-cancel"))
    await asyncio.sleep(0.15)
    await runner.cancel("active-cancel")
    result = await task
    assert result["status"] == "CANCELLED"
    assert runner.model.calls == 1


def test_cli_process_restart_user_wait(tmp_path):
    root = Path(__file__).resolve().parents[1]
    command = [sys.executable, "-m", "agent_service", "--script", "tests/fixtures/clarify.json", "--no-trace", "--data-dir", str(tmp_path)]
    first = json.loads(subprocess.run(command+["run", "缓存草稿"], cwd=root, capture_output=True, text=True, check=True).stdout)
    second = json.loads(subprocess.run(command+["reply", first["thread_id"], "严格30秒"], cwd=root, capture_output=True, text=True, check=True).stdout)
    assert first["status"] == "WAITING"
    assert second["status"] == "DRAFT_READY"
    assert first["identity"] == second["identity"]
    assert second["usage"]["model_calls"] == 4
    assert second["requirements"]["outputs"][0]["duration_min_ms"] == 30000


@pytest.mark.asyncio
@pytest.mark.parametrize("limits,actions", [
    (Limits(tool_calls=0), [read()]),
    (Limits(repairs=0), [{"kind": "TOOL", "tool_name": "disabled"}]),
    (Limits(active_seconds=0), [read()]),
    (Limits(tool_bytes=1, repairs=0), [read()]),
])
async def test_other_limits_stop_without_budget_reset(tmp_path, limits, actions):
    result = await agent(tmp_path, actions=actions, limits=limits).send("缓存")
    assert result["status"] == "INCOMPLETE"
    assert result["usage"]["tools"] <= limits.tool_calls
    assert result["usage"]["repairs"] <= limits.repairs


@pytest.mark.asyncio
async def test_saved_response_reused_after_node_crash(tmp_path, monkeypatch):
    from agent_service.model import ScriptedModel
    from agent_service.runtime.persistence import Store
    class CrashAfterReceipt(ScriptedModel):
        calls = 0
        async def invoke(self, *args):
            self.calls += 1
            return await super().invoke(*args)
    model = CrashAfterReceipt(understanding("DISCUSS"), [answer([], "讨论已完成。")])
    runner = agent(tmp_path)
    runner.model = model
    crashed = False
    original = Store.received
    def received(self, call_id, response):
        nonlocal crashed
        original(self, call_id, response)
        if not crashed:
            crashed = True
            raise RuntimeError("Injected node failure after received response")
    monkeypatch.setattr(Store, "received", received)
    with pytest.raises(RuntimeError):
        await runner.send("讨论", thread_id="reuse")
    assert (await runner.inspect("reuse"))["usage"]["status"] == "FAILED"
    monkeypatch.setattr(Store, "received", original)
    result = await runner.resume("reuse")
    assert result["status"] == "ANSWER"
    assert model.calls == result["usage"]["model_calls"] == 2


@pytest.mark.asyncio
async def test_tool_resume_rejects_wrong_wait_and_call_ids(tmp_path):
    from agent_service.tools.contracts import ToolRequest
    inspect = {'kind': 'TOOL', 'tool_name':'inspect_video_range', 'arguments':{'asset_id':'demo','start_ms':20000,'end_ms':21000,'goal':'面板'}}
    runner = agent(tmp_path, actions=[inspect], scenario='pending')
    first = await runner.send('查看画面')
    result = runner.executor.complete(ToolRequest.model_validate(first['pending_tool'])).model_dump()
    with pytest.raises(ValueError, match='wait_id'):
        await runner.resume(first['thread_id'], {'kind':'TOOL_RESULT','wait_id':'wrong','result':result})
    result['call_id'] = 'wrong'
    with pytest.raises(ValueError, match='回执'):
        await runner.resume(first['thread_id'], {'kind':'TOOL_RESULT','wait_id':first['waiting']['wait_id'],'result':result})
    assert (await runner.inspect(first['thread_id']))['status'] == 'WAITING'


@pytest.mark.asyncio
async def test_active_tool_can_be_cancelled(tmp_path):
    from agent_service.tools.mock import MockExecutor
    class SlowExecutor(MockExecutor):
        started = asyncio.Event()
        async def execute(self, request):
            self.started.set()
            await asyncio.sleep(20)
    runner = agent(tmp_path)
    runner.executor = SlowExecutor()
    task = asyncio.create_task(runner.send('缓存', thread_id='tool-cancel'))
    await asyncio.wait_for(runner.executor.started.wait(), timeout=2)
    await runner.cancel('tool-cancel')
    result = await asyncio.wait_for(task, timeout=2)
    assert result['status'] == 'CANCELLED'
    assert result['usage']['tools'] == 1

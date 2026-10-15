"""Regressions for the six findings from the independent MVP review."""
import copy

import pytest
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from agent_service.config import Config
from agent_service.model import ScriptedModel
from agent_service.runtime.graph import build_graph
from agent_service.runtime.persistence import Store
from agent_service.runtime.policy import Cancelled, Limits, encoded
from agent_service.runtime.runner import Agent
from agent_service.tools.contracts import ToolRequest
from agent_service.tools.mock import MockExecutor
from test_agent_scenarios import agent, answer, draft, read, understanding


def test_cancellation_survives_store_reopen_without_resetting_usage(tmp_path):
    path = tmp_path / "agent.sqlite"
    store = Store(path)
    store.start("execution")
    store.reserve("call", "execution", "input", Limits())
    store.finish("WAITING")
    before = store.usage("execution")
    store.cancel("execution")
    store.close()
    reopened = Store(path)
    try:
        assert reopened.usage("execution") == {**before, "status": "CANCELLED"}
        with pytest.raises(Cancelled):
            reopened.start("execution")
    finally:
        reopened.close()


class MinimalExecutor:
    """Exactly the declared execute-only Protocol, with no Mock attributes."""
    def __init__(self):
        self.mock = MockExecutor()

    async def execute(self, request):
        return await self.mock.execute(request)


@pytest.mark.asyncio
async def test_execute_only_executor_uses_explicit_asset_context(tmp_path):
    executor = MinimalExecutor()
    runner = Agent(Config(directory=tmp_path, trace=False), ScriptedModel(understanding(), [read(), answer()]),
                   executor=executor, assets=executor.mock.assets)
    assert (await runner.send("缓存如何读取？"))["status"] == "ANSWER"


def test_custom_executor_requires_explicit_asset_context(tmp_path):
    with pytest.raises(ValueError, match="assets"):
        Agent(Config(directory=tmp_path, trace=False), ScriptedModel(understanding(), [answer()]), executor=MinimalExecutor())


@pytest.mark.asyncio
async def test_two_clarifications_preserve_confirmed_fields_across_restarts(tmp_path):
    def parse(ctx):
        latest = ctx["request"].get("clarification")
        if latest == "lecture":
            assert [a["content"] for a in ctx["request"]["clarifications"]] == ["严格30秒", "lecture"]
            return understanding("DRAFT", asset_ids=["lecture"])
        if latest:
            return understanding("DRAFT", duration_min_ms=30000, duration_max_ms=30000, question="哪个素材？")
        return understanding("DRAFT", question="需要多少秒？")
    first = await agent(tmp_path, parse, [read(), draft()]).send("缓存草稿")
    second = await agent(tmp_path, parse, [read(), draft()]).resume(first["thread_id"], {
        "kind": "USER_INPUT", "wait_id": first["waiting"]["wait_id"], "content": "严格30秒"})
    assert second["status"] == "WAITING"
    assert second["requirements"]["outputs"][0]["duration_min_ms"] == 30000
    third = await agent(tmp_path, parse, [read(), draft()]).resume(first["thread_id"], {
        "kind": "USER_INPUT", "wait_id": second["waiting"]["wait_id"], "content": "lecture"})
    assert third["status"] == "DRAFT_READY"
    assert third["requirements"]["outputs"][0]["duration_min_ms"] == third["requirements"]["outputs"][0]["duration_max_ms"] == 30000
    assert third["outcome"]["validation"]["durations_ms"] == [30000]
    assert third["identity"] == first["identity"]
    assert third["usage"]["model_calls"] == 5


@pytest.mark.asyncio
async def test_conflicting_duration_patch_waits_without_applying_invalid_conditions(tmp_path):
    parsed = understanding("DRAFT", duration_min_ms=30000, duration_max_ms=20000, question="以哪个时长为准？")
    first = await agent(tmp_path, parsed).send("严格30秒且不超过20秒")
    assert first["status"] == "WAITING"
    assert first["requirements"]["outputs"][0]["duration_min_ms"] is None
    assert first["usage"]["repairs"] == 0


@pytest.mark.asyncio
async def test_current_asset_scope_rejects_previously_read_reference(tmp_path):
    runner = agent(tmp_path)
    first = await runner.send("缓存流程")
    runner.model = ScriptedModel(understanding(asset_ids=["demo"]), [
        answer(), {"kind": "TOOL", "tool_name": "read_video_range", "arguments": {"asset_id": "demo", "start_ms": 0, "end_ms": 20000}},
        answer(["demo-speech"], "Mock 演示比较命中与未命中的耗时。")])
    result = await runner.send("只回答demo素材", thread_id=first["thread_id"])
    assert result["status"] == "ANSWER"
    assert result["usage"]["repairs"] == 1
    assert result["usage"]["tools"] == 1
    assert {s["asset_id"] for s in result["outcome"]["sources"]} == {"demo"}


@pytest.mark.asyncio
@pytest.mark.parametrize("changed_field,value", [("analysis_version", "sample-v2"), ("duration_ms", 35000)])
async def test_asset_metadata_change_rejects_cached_observation(tmp_path, changed_field, value):
    runner = agent(tmp_path)
    first = await runner.send("缓存流程")
    runner.assets = copy.deepcopy(runner.assets)
    runner.assets["lecture"][changed_field] = value
    runner.model = ScriptedModel(understanding(), [answer(), answer([], "当前分析版本的证据不足，无法确认。")])
    second = await runner.send("再说明一次", thread_id=first["thread_id"])
    assert second["status"] == "ANSWER"
    assert second["usage"]["repairs"] == 1
    assert second["outcome"]["evidence_ids"] == []


def overview(asset_id, cursor):
    return {"kind": "TOOL", "tool_name": "get_video_overview", "arguments": {"asset_id": asset_id, "cursor": cursor}}


@pytest.mark.asyncio
@pytest.mark.parametrize("finish", ["read_remaining", "disclose_gap"])
async def test_full_video_requires_each_target_asset_or_explicit_limitation(tmp_path, finish):
    actions = [read(), overview("lecture", 0), overview("lecture", 2), answer(text="两段素材的全部步骤如下。")]
    if finish == "read_remaining":
        actions += [overview("demo", 0), overview("demo", 2), answer(text="两段素材的全部步骤如下。")]
    else:
        actions += [answer(text="仅覆盖lecture；demo覆盖不足，无法总结两段全部步骤。")]
    result = await agent(tmp_path, understanding(asset_ids=["lecture", "demo"]), actions).send("两段素材全片全部步骤")
    assert result["status"] == "ANSWER"
    assert result["usage"]["repairs"] == 1
    assert result["usage"]["tools"] == (5 if finish == "read_remaining" else 3)


@pytest.mark.asyncio
async def test_out_of_scope_completed_overview_does_not_satisfy_new_scope(tmp_path):
    first_runner = agent(tmp_path, understanding(asset_ids=["lecture"]), [read(), overview("lecture", 0), overview("lecture", 2), answer()])
    first = await first_runner.send("lecture全片步骤")
    first_runner.model = ScriptedModel(understanding(asset_ids=["demo"]), [
        answer([], "demo全片没有缓存读取步骤。"), answer([], "demo覆盖不足，无法判断全片内容。")])
    second = await first_runner.send("只看demo全片", thread_id=first["thread_id"])
    assert second["status"] == "ANSWER"
    assert second["usage"]["repairs"] == 1


@pytest.mark.asyncio
@pytest.mark.parametrize("size_delta,expected_repairs", [(0, 0), (1, 1)])
async def test_resume_tool_result_enforces_utf8_size_before_checkpoint(tmp_path, size_delta, expected_repairs):
    inspect = {"kind": "TOOL", "tool_name": "inspect_video_range", "arguments": {"asset_id": "demo", "start_ms": 20000, "end_ms": 21000, "goal": "面板"}}
    def decide(ctx):
        if ctx["usage"]["decisions"] == 0:
            return inspect
        if expected_repairs:
            assert ctx["feedback"]["error"]["code"] == "RESULT_TOO_LARGE"
        return answer([], "有限观察覆盖不足，无法确定。")
    runner = agent(tmp_path, actions=decide, scenario="pending")
    first = await runner.send("查看画面")
    result = runner.executor.complete(ToolRequest.model_validate(first["pending_tool"])).model_dump()
    result["data"] = {"text": "汉" * 12000}
    byte_size = len(encoded(result).encode("utf-8"))
    runner.config.limits = Limits(tool_bytes=byte_size-size_delta)
    resumed = await runner.resume(first["thread_id"], {"kind": "TOOL_RESULT", "wait_id": first["waiting"]["wait_id"], "result": result})
    assert resumed["status"] == "ANSWER"
    assert resumed["usage"]["repairs"] == expected_repairs
    assert resumed["usage"]["tools"] == 1
    async with AsyncSqliteSaver.from_conn_string(str(runner.path)) as saver:
        snapshot = await build_graph(saver).aget_state({"configurable": {"thread_id": first["thread_id"]}})
    if size_delta:
        assert "汉" not in encoded(snapshot.values)
        assert snapshot.values["resume_input"]["result"]["status"] == "ERROR"


@pytest.mark.asyncio
@pytest.mark.parametrize("changed_context", ["version", "duration", "scope"])
async def test_draft_cannot_reapprove_sources_invalidated_by_current_context(tmp_path, changed_context):
    runner = agent(tmp_path, understanding("DRAFT"), [read(), draft()])
    first = await runner.send("生成缓存草稿")
    assert first["status"] == "DRAFT_READY"
    runner.assets = copy.deepcopy(runner.assets)
    if changed_context == "version":
        runner.assets["lecture"]["analysis_version"] = "sample-v2"
    elif changed_context == "duration":
        runner.assets["lecture"]["duration_ms"] = 20000
    else:
        del runner.assets["lecture"]
    runner.model = ScriptedModel(understanding("DRAFT"), [draft(), answer([], "当前素材依据不足，无法继续批准旧草稿。")])
    second = await runner.send("使用原来的草稿", thread_id=first["thread_id"])
    assert second["status"] == "INCOMPLETE"
    assert second["usage"]["repairs"] == 1
    assert second["draft"] == draft()["draft"]


@pytest.mark.asyncio
async def test_mock_draft_uses_persisted_reads_after_executor_restart(tmp_path):
    first = await agent(tmp_path, understanding('DRAFT'), [read(), {'kind':'ASK','question':'保留当前内容吗？'}]).send('缓存草稿')
    assert first['status'] == 'WAITING'
    resumed = await agent(tmp_path, understanding('DRAFT'), [draft()]).resume(first['thread_id'], {
        'kind':'USER_INPUT','wait_id':first['waiting']['wait_id'],'content':'保留'})
    assert resumed['status'] == 'DRAFT_READY'


@pytest.mark.asyncio
async def test_mock_draft_can_cite_received_inspection(tmp_path):
    def decide(ctx):
        if not ctx['observations']:
            return {'kind':'TOOL','tool_name':'inspect_video_range','arguments':{
                'asset_id':'demo','start_ms':15000,'end_ms':20000,'goal':'核查画面'}}
        action = draft(15000, 20000)
        action['draft']['outputs'][0]['clips'][0]['asset_id'] = 'demo'
        action['draft']['outputs'][0]['clips'][0]['evidence_ids'] = list(ctx['observations'])
        return action
    result = await agent(tmp_path, understanding('DRAFT'), decide).send('画面草稿')
    assert result['status'] == 'DRAFT_READY'

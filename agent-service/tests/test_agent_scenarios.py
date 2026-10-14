import pytest

from agent_service.config import Config
from agent_service.model import ScriptedModel
from agent_service.runtime.runner import Agent
from agent_service.tools.mock import MockExecutor


def understanding(mode="QUERY", **values):
    return {"relation": "CONTINUE", "mode": mode, "topic": "缓存", **values}


def read():
    return {"kind": "TOOL", "tool_name": "read_video_range", "arguments": {"asset_id": "lecture", "start_ms": 15000, "end_ms": 45000}}


def answer(ids=None, text="先查缓存，未命中再查询数据库并写回缓存。"):
    return {"kind": "ANSWER", "content": text, "evidence_ids": ["speech-cache"] if ids is None else ids}


def draft(start=15000, end=45000):
    return {"kind": "DRAFT", "draft": {"title": "缓存流程", "outputs": [{"title": "讲解", "clips": [
        {"asset_id": "lecture", "source_in_ms": start, "source_out_ms": end, "evidence_ids": ["speech-cache"], "reason": "保留完整读取流程"}]}]}}


def agent(tmp_path, request=None, actions=None, scenario="normal", limits=None):
    kwargs = {"directory": tmp_path, "trace": False, "scenario": scenario}
    if limits:
        kwargs["limits"] = limits
    executor = MockExecutor(scenario)
    return Agent(Config(**kwargs), ScriptedModel(request or understanding(), actions or [read(), answer()]), executor, assets=executor.assets)


@pytest.mark.asyncio
async def test_query_returns_read_sources_without_business_services(tmp_path):
    result = await agent(tmp_path).send("缓存如何读取？")
    assert result["status"] == "ANSWER"
    assert result["outcome"]["sources"][0]["id"] == "speech-cache"
    assert result["outcome"]["mock"] is True
    assert result["outcome"]["sources"][0]["start_ms"] == 15000


@pytest.mark.asyncio
async def test_unread_reference_is_rejected_then_corrected(tmp_path):
    result = await agent(tmp_path, actions=[answer(["invented"]), read(), answer()]).send("说明缓存")
    assert result["status"] == "ANSWER"
    assert result["usage"]["repairs"] == 1
    assert "invented" not in result["outcome"]["evidence_ids"]


@pytest.mark.asyncio
async def test_draft_invalid_duration_is_repaired_and_validated(tmp_path):
    result = await agent(tmp_path, understanding("DRAFT", duration_min_ms=30000, duration_max_ms=30000),
                         [read(), draft(15000, 20000), draft()]).send("生成严格30秒的缓存草稿")
    assert result["status"] == "DRAFT_READY"
    assert result["outcome"]["validation"]["durations_ms"] == [30000]
    assert result["usage"]["repairs"] == 1
    assert result["outcome"]["draft"]["outputs"][0]["clips"][0]["source_out_ms"] == 45000


@pytest.mark.asyncio
async def test_new_turn_keeps_unspecified_conditions_and_invalidates_validation(tmp_path):
    runner = agent(tmp_path, understanding("DRAFT", duration_min_ms=30000, duration_max_ms=30000), [read(), draft()])
    first = await runner.send("生成严格30秒草稿")
    old_execution = first["identity"]["execution_id"]
    runner.model = ScriptedModel({"relation": "CONTINUE", "mode": "DRAFT", "duration_min_ms": 20000, "duration_max_ms": 20000}, [draft(15000, 35000)])
    second = await runner.send("缩短到20秒", thread_id=first["thread_id"])
    assert second["status"] == "DRAFT_READY"
    assert second["identity"]["execution_id"] != old_execution
    assert second["requirements"]["topic"] == "缓存"
    assert second["outcome"]["validation"]["durations_ms"] == [20000]


@pytest.mark.asyncio
async def test_new_requirement_isolated_even_when_detected_by_model(tmp_path):
    runner = agent(tmp_path, understanding("DRAFT", duration_min_ms=30000, duration_max_ms=30000), [read(), draft()])
    first = await runner.send("缓存草稿")
    runner.model = ScriptedModel({"relation": "NEW", "mode": "DISCUSS", "topic": "新的项目"}, [answer([], "可以讨论新项目。")])
    second = await runner.send("另做一个新主题", thread_id=first["thread_id"])
    assert second["thread_id"] != first["thread_id"]
    assert second["requirements"]["duration_min_ms"] is None
    assert second["requirements"]["topic"] == "新的项目"
    assert second["outcome"]["status"] == "ANSWER"


@pytest.mark.asyncio
async def test_complete_navigation_requires_contiguous_pages(tmp_path):
    overview = lambda cursor: {"kind": "TOOL", "tool_name": "get_video_overview", "arguments": {"asset_id": "lecture", "cursor": cursor}}
    result = await agent(tmp_path, understanding(asset_ids=["lecture"]), actions=[read(), overview(2), answer(), overview(0), overview(2), answer()]).send("全片有哪些步骤？")
    assert result["status"] == "ANSWER"
    assert result["usage"]["repairs"] == 1


@pytest.mark.asyncio
async def test_visual_question_rejects_speech_only_answer(tmp_path):
    result = await agent(tmp_path, actions=[read(), answer(), answer([], "画面覆盖不足，无法确定。")]).send("画面中看见什么？")
    assert result["status"] == "ANSWER"
    assert result["usage"]["repairs"] == 1
    assert result["outcome"]["evidence_ids"] == []


@pytest.mark.asyncio
async def test_disabled_production_tool_never_executes(tmp_path):
    result = await agent(tmp_path, understanding("UNSUPPORTED"),
                         [{"kind": "TOOL", "tool_name": "request_export", "arguments": {}}, answer([], "本MVP不能导出。")]).send("导出")
    assert result["status"] == "ANSWER"
    assert result["usage"]["tools"] == 0


@pytest.mark.asyncio
async def test_safe_temporary_failure_has_bounded_retry(tmp_path):
    result = await agent(tmp_path, scenario="transient").send("缓存如何读取？")
    assert result["status"] == "ANSWER"
    assert result["usage"]["tools"] == 1


@pytest.mark.asyncio
async def test_no_match_does_not_create_evidence(tmp_path):
    result = await agent(tmp_path, actions=[{"kind": "TOOL", "tool_name": "search_evidence", "arguments": {"query": "不存在内容"}},
                                           answer([], "样本检索未命中，覆盖有限，无法确定全片是否不存在。")], scenario="no_match").send("查找不存在内容")
    assert result["status"] == "ANSWER"
    assert result["outcome"]["sources"] == []


@pytest.mark.asyncio
async def test_multiple_outputs_are_independently_validated(tmp_path):
    action = draft()
    action['draft']['outputs'] *= 2
    result = await agent(tmp_path, understanding('DRAFT', output_count=2, duration_min_ms=30000, duration_max_ms=30000), [read(), action]).send('两条严格30秒草稿')
    assert result['status'] == 'DRAFT_READY'
    assert result['outcome']['validation']['durations_ms'] == [30000, 30000]


@pytest.mark.asyncio
async def test_stale_validation_is_failure_not_approval(tmp_path):
    class StaleExecutor(MockExecutor):
        async def execute(self, request):
            result = await super().execute(request)
            if request.tool_name == 'validate_edit_draft':
                result.data['draft_hash'] = 'previous-draft-hash'
            return result
    runner = agent(tmp_path, understanding('DRAFT'), [read(), draft()])
    runner.executor = StaleExecutor()
    with pytest.raises(RuntimeError, match='Stale'):
        await runner.send('草稿', thread_id='stale-validation')
    assert (await runner.inspect('stale-validation'))['usage']['status'] == 'FAILED'


@pytest.mark.asyncio
async def test_exhausted_temporary_failures_are_not_retried_indefinitely(tmp_path):
    from agent_service.runtime.policy import TemporaryToolError
    class UnavailableExecutor(MockExecutor):
        attempts_count = 0
        async def execute(self, request):
            self.attempts_count += 1
            raise TemporaryToolError('No remote submission')
    runner = agent(tmp_path, actions=[read(), answer([], '工具暂不可用，无法读取。')])
    runner.executor = UnavailableExecutor()
    result = await runner.send('缓存')
    assert result['status'] == 'ANSWER'
    assert runner.executor.attempts_count == 2
    assert result['usage']['repairs'] == 1

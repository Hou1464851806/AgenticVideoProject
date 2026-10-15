import copy
import json

import httpx
import pytest

from agent_service.config import Config
from agent_service.model import ScriptedModel
from agent_service.prompts import messages
from agent_service.runtime.runner import Agent
from agent_service.tools.java import JavaToolExecutor
from agent_service.tools.evidence import valid_evidence

ASSET = {"id": "a", "analysis_version": "v1", "duration_ms": 10000, "source_kinds": ["VISUAL"], "sources": {"VISUAL": "SUCCEEDED"}}
POINT = {"id": "e", "asset_id": "a", "analysis_version": "v1", "source_kind": "VISUAL", "time_kind": "POINT", "observed_at_ms": 2320, "text": "红矩形"}
UNDERSTAND = {"relation": "CONTINUE", "mode": "QUERY"}
READ = {"kind": "TOOL", "tool_name": "read_video_range", "arguments": {"asset_id": "a", "start_ms": 0, "end_ms": 10000}}
ANSWER = {"kind": "ANSWER", "content": "2320ms采样画面出现红矩形。", "evidence_ids": ["e"]}


class Backend:
    def __init__(self):
        self.scopes, self.calls, self.creates = {}, [], 0
        self.asset = copy.deepcopy(ASSET)
        self.fail = False

    def handle(self, req):
        assert req.headers["Authorization"] == "Bearer private-token"
        body = json.loads(req.content) if req.content else None
        self.calls.append((req.method, req.url.path, body))
        if req.url.path.endswith("/scopes"):
            self.creates += 1
            scope = {"scope_id": str(self.creates), "project_id": "p", "mock": False, "assets": [copy.deepcopy(self.asset)]}
            self.scopes[str(self.creates)] = scope
            return httpx.Response(201, json=scope)
        identity = req.url.path.split("/scopes/")[1].split("/")[0]
        if req.method == "GET":
            return httpx.Response(200, json=self.scopes[identity])
        if self.fail:
            raise httpx.ReadError("response lost: private-token")
        return httpx.Response(200, json={**{k: body[k] for k in ("call_id", "tool_name", "request_hash")}, "status": "READY", "mock": False,
                                      "records": [POINT], "coverage": {"visual_complete": False}})


def runner(path, backend, model):
    executor = JavaToolExecutor("http://java", "private-token", "p", transport=httpx.MockTransport(backend.handle))
    return Agent(Config(path, trace=False), model=model, executor=executor)


@pytest.mark.asyncio
async def test_clarification_reloads_old_scope_new_turn_refreshes(tmp_path):
    backend = Backend()
    def understand(ctx):
        return UNDERSTAND if ctx["request"].get("clarification") else {**UNDERSTAND, "question": "哪个画面？"}
    first = await runner(tmp_path, backend, ScriptedModel(understand, [READ, ANSWER])).send("查看画面")
    assert first["status"] == "WAITING"
    backend.asset["analysis_version"] = "v2"
    resumed = await runner(tmp_path, backend, ScriptedModel(understand, [READ, ANSWER])).resume(first["thread_id"],
        {"kind": "USER_INPUT", "wait_id": first["waiting"]["wait_id"], "content": "红矩形"})
    assert resumed["status"] == "ANSWER"
    assert resumed["question_scope"] == first["question_scope"]
    assert resumed["outcome"]["sources"] == [POINT]
    assert resumed["outcome"]["mock"] is False
    assert backend.creates == 1
    next_result = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [{"kind": "ANSWER", "content": "无法确定新版本画面", "evidence_ids": []}])).send("继续查询", thread_id=first["thread_id"])
    assert next_result["question_scope"]["assets"][0]["analysis_version"] == "v2"
    assert backend.creates == 2
    assert "private-token" not in json.dumps(resumed)


@pytest.mark.asyncio
async def test_process_recovery_before_model_never_creates_new_scope(tmp_path):
    backend = Backend()
    class CrashedModel:
        async def invoke(self, *args):
            raise ConnectionError("crash")
    with pytest.raises(ConnectionError):
        await runner(tmp_path, backend, CrashedModel()).send("画面", thread_id="recovery")
    backend.asset["analysis_version"] = "v2"
    result = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [READ, ANSWER])).resume("recovery")
    assert result["question_scope"]["assets"][0]["analysis_version"] == "v1"
    assert result["status"] == "INCOMPLETE"  # unknown model submission is not resubmitted
    assert result["outcome"]["mock"] is False
    assert backend.creates == 1


@pytest.mark.asyncio
async def test_transport_uncertainty_stops_without_retry_or_empty_answer(tmp_path):
    backend = Backend()
    backend.fail = True
    result = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [READ, ANSWER])).send("画面")
    assert result["status"] == "INCOMPLETE"
    assert "TOOL_TRANSPORT_UNCERTAIN" in result["outcome"]["content"]
    assert len([c for c in backend.calls if c[1].endswith("/tools")]) == 1
    assert result["usage"]["repairs"] == 0


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["DISCUSS", "UNSUPPORTED"])
async def test_real_mode_capability_never_falls_back(tmp_path, mode):
    backend = Backend()
    result = await runner(tmp_path, backend, ScriptedModel({**UNDERSTAND, "mode": mode}, [READ])).send("剪辑")
    assert result["outcome"]["mock"] is False
    assert result["requirements"]["mode"] == "UNSUPPORTED"
    assert not any(c[1].endswith("/tools") for c in backend.calls)


def test_point_semantics_and_real_prompt():
    assert valid_evidence(POINT, ASSET)
    assert not valid_evidence({**POINT, "observed_at_ms": 10000}, ASSET)
    assert not valid_evidence({**POINT, "start_ms": 2320, "end_ms": 2321}, ASSET)
    assert not valid_evidence({**POINT, "source_kind": "SPEECH"}, ASSET)
    prompt = messages("decide", {"mock_tools": False})[0][1]
    assert '"name": "inspect_video_range"' not in prompt
    assert "所有工具结果均为开发样本" not in prompt


@pytest.mark.asyncio
async def test_asset_pages_accumulate_without_claiming_visual_coverage(tmp_path):
    backend = Backend()
    b = {**ASSET, "id": "b"}
    def handle(req):
        if not req.url.path.endswith('/tools'):
            response = backend.handle(req)
            if req.method == 'POST':
                scope = response.json()
                scope['assets'].append(b)
                backend.scopes[scope['scope_id']] = scope
                return httpx.Response(201, json=scope)
            return response
        body = json.loads(req.content)
        cursor = body['arguments']['cursor']
        return httpx.Response(200, json={**{k: body[k] for k in ('call_id','tool_name','request_hash')}, 'status':'READY','mock':False,
            'data':{'assets':[ASSET if cursor == 0 else b], 'next_cursor':1 if cursor == 0 else None}, 'coverage':{'visual_complete':False}})
    def actions(ctx):
        index = ctx['usage']['decisions']
        if index < 2:
            return {'kind':'TOOL','tool_name':'list_assets','arguments':{'cursor':index}}
        assert {a['id'] for a in ctx['coverage']['_assets']} == {'a','b'}
        assert ctx['coverage']['_last_read']['visual_complete'] is False
        return {'kind':'ANSWER','content':'无法确定未采样画面','evidence_ids':[]}
    executor = JavaToolExecutor('http://java','private-token','p',transport=httpx.MockTransport(handle))
    result = await Agent(Config(tmp_path,trace=False),ScriptedModel(UNDERSTAND,actions),executor=executor).send('素材')
    assert result['status'] == 'ANSWER'


@pytest.mark.asyncio
async def test_range_pages_expose_consumption_and_only_new_pages_make_progress(tmp_path):
    backend = Backend()
    def handle(req):
        response = backend.handle(req)
        if req.url.path.endswith('/tools'):
            body = json.loads(req.content)
            value = response.json()
            value['data'] = {'next_cursor': 1 if body['arguments']['cursor'] == 0 else None}
            return httpx.Response(200, json=value)
        return response
    def actions(ctx):
        step = ctx['usage']['decisions']
        if step:
            ranges = list(ctx['coverage']['_range_reads'].values())
            assert len(ranges) == 1
            assert {key: ranges[0][key] for key in ('asset_id', 'start_ms', 'end_ms')} == {
                'asset_id': 'a', 'start_ms': 0, 'end_ms': 10000}
            assert ranges[0]['complete'] is (step >= 3)
            assert ctx['usage']['no_progress'] == (1 if step == 4 else 0)
        if step < 4:
            return {**READ, 'arguments': {**READ['arguments'], 'cursor': [1, 0, 1, 1][step]}}
        return ANSWER
    executor = JavaToolExecutor('http://java', 'private-token', 'p', transport=httpx.MockTransport(handle))
    result = await Agent(Config(tmp_path, trace=False), ScriptedModel(UNDERSTAND, actions), executor=executor).send('读取画面')
    assert result['status'] == 'ANSWER'


@pytest.mark.asyncio
async def test_changed_snapshot_and_mock_recovery_are_rejected(tmp_path):
    backend = Backend()
    first = await runner(tmp_path,backend,ScriptedModel({**UNDERSTAND,'question':'内容？'},[READ,ANSWER])).send('问答')
    backend.scopes[first['question_scope']['scope_id']]['assets'][0]['analysis_version'] = 'v2'
    payload = {'kind':'USER_INPUT','wait_id':first['waiting']['wait_id'],'content':'画面'}
    with pytest.raises(ValueError,match='内容发生变化'):
        await runner(tmp_path,backend,ScriptedModel(UNDERSTAND,[READ,ANSWER])).resume(first['thread_id'],payload)
    mock = Agent(Config(tmp_path,trace=False),ScriptedModel(UNDERSTAND,[ANSWER]))
    with pytest.raises(ValueError,match='原 Java'):
        await mock.resume(first['thread_id'],payload)


@pytest.mark.asyncio
@pytest.mark.parametrize('status,body,category', [
    (403, {}, 'PERMISSION_DENIED'),
    (409, {'error': {'code': 'SNAPSHOT_INVALID', 'category': 'DEPENDENCY_INVALID'}}, 'DEPENDENCY_INVALID'),
    (200, {'not_a_result': True}, 'EXECUTION_UNCERTAIN'),
])
async def test_transport_rejects_permission_dependency_and_invalid_protocol(status, body, category):
    from agent_service.tools.contracts import ToolRequest
    from agent_service.runtime.policy import fingerprint
    executor = JavaToolExecutor('http://java','private-token',transport=httpx.MockTransport(lambda req: httpx.Response(status,json=body)))
    executor.scope_id = 's'
    args = {'cursor': 0}
    request = ToolRequest(call_id='c',tool_name='list_assets',arguments=args,request_hash=fingerprint({'tool':'list_assets','args':args}))
    result = await executor.execute(request)
    assert result.status == 'ERROR' and result.mock is False
    assert result.error['category'] == category

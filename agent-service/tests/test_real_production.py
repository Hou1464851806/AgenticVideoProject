"""Agent public behavior at the model/Java HTTP boundaries (no paid calls)."""
import copy
import json

import httpx
import pytest

from agent_service.config import Config
from agent_service.model import ScriptedModel
from agent_service.runtime.policy import fingerprint
from agent_service.runtime.runner import Agent
from agent_service.tools.java import JavaToolExecutor
from test_java_executor import ASSET, POINT, READ, Backend

DRAFT = {'kind': 'DRAFT', 'draft': {'title': '红矩形', 'outputs': [
    {'output_key': 'out-1', 'title': '观察', 'clips': [
        {'asset_id': 'a', 'source_in_ms': 0, 'source_out_ms': 10000,
         'evidence_ids': ['e'], 'reason': '采样画面'}]}]}}
UNDERSTAND = {'relation': 'CONTINUE', 'mode': 'DRAFT'}


class ProductionBackend(Backend):
    def __init__(self):
        super().__init__()
        self.validations, self.saves, self.bindings = [], {}, {}
        self.issues = []
        self.lose_save = False

    def handle(self, req):
        if '/productions/' not in req.url.path:
            return super().handle(req)
        assert req.headers['Authorization'] == 'Bearer private-token'
        body = json.loads(req.content) if req.content else None
        self.calls.append((req.method, req.url.path, body))
        identity = req.url.path.split('/productions/')[1].split('/')[0]
        if req.method == 'PUT':
            result = {'requirement_id': identity, **body}
            assert self.bindings.setdefault(identity, result) == result
            return httpx.Response(200, json=result)
        if req.url.path.endswith('/save'):
            if req.method == 'GET':
                return httpx.Response(200, json=self.saves[identity]) if identity in self.saves else httpx.Response(404)
            result = {'status': 'SAVED', 'plan_id': 'plan', 'revision_id': 'revision', 'revision_number': 1,
                      'outputs': [{'output_key': 'out-1', 'output_id': 'output', 'duration_ms': 10000}]}
            self.saves.setdefault(identity, result)
            if self.lose_save:
                raise httpx.ReadError('response lost')
            return httpx.Response(200, json=self.saves[identity])
        assert body['tool_name'] == 'validate_edit_draft'
        self.validations.append(body)
        args = body['arguments']
        return httpx.Response(200, json={**{k: body[k] for k in ('call_id', 'tool_name', 'request_hash')},
            'status': 'READY', 'mock': False, 'data': {
                'validation_id': 'validation-' + str(len(self.validations)), 'valid': not self.issues,
                'draft_hash': args['draft_hash'], 'requirements_hash': args['requirements_hash'],
                'durations_ms': [10000], 'issues': self.issues, 'issues_truncated': False,
                'warnings': [{'code': 'SUBTITLE_GAP', 'message': '未有转写'}]}})


def runner(path, backend, model, limits=None):
    return Agent(Config(path, trace=False, **({'limits': limits} if limits else {})), model=model,
        executor=JavaToolExecutor('http://java', 'private-token', 'p', transport=httpx.MockTransport(backend.handle)))


@pytest.mark.asyncio
async def test_real_draft_forces_validation_and_preserves_full_feedback(tmp_path):
    backend = ProductionBackend()
    issue = {'code': 'RANGE_NOT_READ', 'field': 'outputs[0].clips[0]', 'message': '需补读' * 600,
             'output_key': 'out-1', 'expected': {'start_ms': 0, 'end_ms': 10000}}
    backend.issues = [issue]
    def actions(ctx):
        index = ctx['usage']['decisions']
        if index == 0:
            return DRAFT
        assert ctx['validation']['issues'] == [issue]
        assert ctx['draft'] == DRAFT['draft']
        if index == 1:
            backend.issues = []
            return READ
        return DRAFT
    result = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, actions)).send('生成剪辑')
    assert result['status'] == 'DRAFT_READY'
    assert result['outcome']['mock'] is False
    assert result['outcome']['validation']['durations_ms'] == [10000]
    assert result['outcome']['validation']['warnings'][0]['code'] == 'SUBTITLE_GAP'
    assert result['requirements']['sources']['profile']['kind'] == 'DEFAULT'
    assert len(backend.validations) == 2
    assert backend.validations[0]['call_id'] != backend.validations[1]['call_id']
    assert not backend.saves


@pytest.mark.asyncio
async def test_production_edit_keeps_scope_and_sources_but_revalidates(tmp_path):
    backend = ProductionBackend()
    first = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [READ, DRAFT])).send('生成草稿')
    backend.asset['analysis_version'] = 'v2'
    changed = copy.deepcopy(DRAFT)
    changed['draft']['outputs'][0]['title'] = '改名'
    def edit(ctx):
        assert ctx['validation'] is None
        assert ctx['observations']['e'] == POINT
        return changed
    second = await runner(tmp_path, backend, ScriptedModel({**UNDERSTAND, 'subtitles_enabled': False}, edit)).send(
        '关掉字幕并改名', thread_id=first['thread_id'])
    assert second['status'] == 'DRAFT_READY'
    assert second['question_scope'] == first['question_scope']
    assert second['requirements']['subtitles_enabled'] is False
    assert second['outcome']['validation']['validation_id'] != first['outcome']['validation']['validation_id']
    assert backend.creates == 1
    assert len(backend.validations) == 2
    fresh = await runner(tmp_path, backend, ScriptedModel({**UNDERSTAND, 'relation': 'NEW'}, [{'kind': 'ANSWER', 'content': '证据不足，无法制作'}])).send(
        '另做一个新需求', thread_id=first['thread_id'])
    assert fresh['thread_id'] != first['thread_id']
    assert fresh['question_scope']['assets'][0]['analysis_version'] == 'v2'
    assert fresh['requirements']['subtitles_enabled'] is True
    assert backend.creates == 2


@pytest.mark.asyncio
async def test_explicit_save_recovers_lost_response_and_makes_production_readonly(tmp_path):
    backend = ProductionBackend()
    first = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [READ, DRAFT])).send('生成草稿')
    backend.lose_save = True
    with pytest.raises(httpx.ReadError):
        await runner(tmp_path, backend, None).save(first['thread_id'])
    with pytest.raises(ValueError, match='save'):
        await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [DRAFT])).send('修改', thread_id=first['thread_id'])
    # A committed result must be recoverable even when old scope dependencies fail.
    original = backend.handle
    def invalid_scope(req):
        if '/scopes/' in req.url.path:
            return httpx.Response(409)
        return original(req)
    backend.handle = invalid_scope
    uncertain = await runner(tmp_path, backend, None).inspect(first['thread_id'])
    assert uncertain['status'] == 'INCOMPLETE'
    saved = await runner(tmp_path, backend, None).resume(first['thread_id'])
    assert saved['status'] == 'SAVED'
    assert saved['saved_result']['revision_id'] == 'revision'
    again = await runner(tmp_path, backend, None).save(first['thread_id'])
    assert again['saved_result'] == saved['saved_result']
    assert len([c for c in backend.calls if c[0] == 'POST' and c[1].endswith('/save')]) == 1
    assert (await runner(tmp_path, backend, None).inspect(first['thread_id']))['status'] == 'SAVED'


@pytest.mark.asyncio
async def test_invalid_draft_stops_with_latest_inspectable_issues_and_cannot_save(tmp_path):
    from agent_service.runtime.policy import Limits
    backend = ProductionBackend()
    backend.issues = [{'code': 'OUTPUT_DURATION', 'field': 'outputs[0]', 'message': '无法满足时长'}]
    result = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [READ, DRAFT]), Limits(repairs=3)).send('严格时长')
    assert result['status'] == 'INCOMPLETE'
    assert result['draft'] == DRAFT['draft']
    assert result['validation']['issues'] == backend.issues
    assert result['usage']['repairs'] == 3
    with pytest.raises(ValueError, match='DRAFT_READY'):
        await runner(tmp_path, backend, None).save(result['thread_id'])
    assert not backend.saves


@pytest.mark.asyncio
@pytest.mark.parametrize('resolved', [True, False])
async def test_progress_requires_changed_draft_and_resolved_issue_set(tmp_path, resolved):
    from agent_service.runtime.policy import Limits
    backend = ProductionBackend()
    first_issue = {'code': 'OUTPUT_DURATION', 'field': 'outputs[0]', 'message': '时长'}
    second_issue = {'code': 'RANGE_NOT_READ', 'field': 'outputs[0].clips[0]', 'message': '补读'}
    unrelated = {'code': 'CUT_INSIDE_WORD', 'field': 'outputs[0].clips[0]', 'message': '切词'}
    def actions(ctx):
        step = ctx['usage']['decisions']
        if step == 0:
            return READ
        if step == 1:
            backend.issues = [first_issue, second_issue]
            return DRAFT
        if step == 2:
            backend.issues = [first_issue if resolved else unrelated]
            changed = copy.deepcopy(DRAFT)
            changed['draft']['outputs'][0]['clips'][0]['source_out_ms'] = 9000
            return changed
        assert resolved  # A smaller unrelated issue list must not reset the no-progress bound.
        assert ctx['usage']['no_progress'] == 0
        backend.issues = []
        return DRAFT
    result = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, actions), Limits(no_progress=2)).send('制作')
    assert result['status'] == ('DRAFT_READY' if resolved else 'INCOMPLETE')
    assert len(backend.validations) == (3 if resolved else 2)


@pytest.mark.asyncio
async def test_clarification_and_process_restart_keep_production_scope(tmp_path):
    backend = ProductionBackend()
    first = await runner(tmp_path, backend, ScriptedModel({**UNDERSTAND, 'question': '要字幕吗？'}, [DRAFT])).send('制作')
    assert first['status'] == 'WAITING'
    assert first['production']['scope_id'] == first['question_scope']['scope_id']
    backend.asset['analysis_version'] = 'v2'
    result = await runner(tmp_path, backend, ScriptedModel({**UNDERSTAND, 'subtitles_enabled': False}, [READ, DRAFT])).resume(
        first['thread_id'], {'kind': 'USER_INPUT', 'wait_id': first['waiting']['wait_id'], 'content': '不要字幕'})
    assert result['status'] == 'DRAFT_READY'
    assert result['question_scope'] == first['question_scope']
    assert result['requirements']['sources']['subtitles_enabled']['text'] == '不要字幕'
    assert backend.creates == 1


@pytest.mark.asyncio
async def test_model_cannot_save_or_bypass_validation(tmp_path):
    from agent_service.tools.catalog import model_tools
    backend = ProductionBackend()
    assert {t['name'] for t in model_tools(False)} == {
        'list_assets', 'get_video_overview', 'search_evidence', 'read_video_range'}
    result = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [
        {'kind': 'TOOL', 'tool_name': 'save_plan_revision', 'arguments': {}}])).send('制作')
    assert result['status'] == 'INCOMPLETE'
    assert not backend.saves and not backend.validations


@pytest.mark.asyncio
async def test_readonly_production_keeps_saved_identity_and_new_starts_fresh(tmp_path):
    backend = ProductionBackend()
    first = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [READ, DRAFT])).send('制作')
    saved = await runner(tmp_path, backend, None).save(first['thread_id'])
    result = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [DRAFT])).send('修改', thread_id=first['thread_id'])
    assert result['status'] == 'SAVED'
    assert result['saved_result'] == saved['saved_result']
    assert len(backend.validations) == 1
    fresh = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [READ, DRAFT])).send('新制作', thread_id=first['thread_id'], new=True)
    assert fresh['status'] == 'DRAFT_READY'
    assert fresh['thread_id'] != first['thread_id']
    assert backend.creates == 2


@pytest.mark.asyncio
async def test_dependency_failure_stops_production_without_refresh_or_repair(tmp_path):
    backend = ProductionBackend()
    first = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [READ, DRAFT])).send('制作')
    handle = backend.handle
    def expired(req):
        if req.method == 'GET' and '/scopes/' in req.url.path:
            return httpx.Response(409)
        return handle(req)
    backend.handle = expired
    with pytest.raises(ValueError, match='快照不可用'):
        await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [DRAFT])).send('修改', thread_id=first['thread_id'])
    assert backend.creates == 1
    assert len(backend.validations) == 1


@pytest.mark.asyncio
async def test_binding_protocol_failure_is_not_a_model_repair(tmp_path):
    from pydantic import ValidationError
    backend = ProductionBackend()
    handle = backend.handle
    def malformed(req):
        return httpx.Response(200, json={}) if req.method == 'PUT' else handle(req)
    backend.handle = malformed
    with pytest.raises(ValidationError):
        await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [READ, DRAFT])).send('制作', thread_id='protocol')
    state = await runner(tmp_path, backend, None).inspect('protocol')
    assert state['usage']['model_calls'] == 1


@pytest.mark.asyncio
@pytest.mark.parametrize('followup', [False, True])
async def test_new_requirement_in_clarification_keeps_source_and_new_execution(tmp_path, followup):
    backend = ProductionBackend()
    def understand(ctx):
        return {**UNDERSTAND, 'relation': 'NEW', 'subtitles_enabled': False} if ctx['request'].get('clarification') else UNDERSTAND
    if followup:
        ready = await runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [READ, DRAFT])).send('制作')
        first = await runner(tmp_path, backend, ScriptedModel(understand, [{'kind': 'ASK', 'question': '确定主题？'}])).send(
            '修改草稿', thread_id=ready['thread_id'])
    else:
        first = await runner(tmp_path, backend, ScriptedModel(understand, [READ, {'kind': 'ASK', 'question': '确定主题？'}])).send('制作')
    assert first['status'] == 'WAITING'
    result = await runner(tmp_path, backend, ScriptedModel(understand, [READ, DRAFT])).resume(first['thread_id'], {
        'kind': 'USER_INPUT', 'wait_id': first['waiting']['wait_id'], 'content': '另做红矩形，不要字幕'})
    assert result['status'] == 'DRAFT_READY'
    assert result['thread_id'] != first['thread_id']
    assert result['identity']['execution_id'] != first['identity']['execution_id']
    assert result['requirements']['sources']['subtitles_enabled']['text'] == '另做红矩形，不要字幕'
    assert result['question_scope']['scope_id'] != first['question_scope']['scope_id']

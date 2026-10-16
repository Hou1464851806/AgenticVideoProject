import copy
from types import SimpleNamespace

import pytest

from agent_service.runtime.calls import invoke_model, model_context, model_view
from agent_service.runtime.persistence import Store
from agent_service.runtime.policy import Limits, encoded, fingerprint


@pytest.mark.parametrize('mode', ['QUERY', 'DRAFT'])
def test_model_view_reduces_duplicate_evidence_without_mutating_state(mode):
    record = {'id': 'read', 'asset_id': 'a', 'analysis_version': 'v1', 'source_kind': 'SPEECH',
              'time_kind': 'INTERVAL', 'start_ms': 0, 'end_ms': 1000, 'text': '原始语音',
              'observation': {'text': '原始语音', 'emotion': 'neutral', 'language': 'zh',
                              'words': [{'text': '原始', 'begin_time': 0, 'end_time': 500}] * 100}}
    context = {'intent': mode, 'observations': {'read': record}, 'available_assets': [
        {'id': 'a', 'analysis_version': 'v1', 'duration_ms': 2000}],
        'coverage': {'a': {'complete': True, 'next_cursor': None, 'entries_by_page': {'0': [
            {'excerpt': '原始语音', 'start_ms': 0, 'end_ms': 1000, 'navigation_ids': ['read']},
            {'excerpt': '尚未读取', 'start_ms': 1000, 'end_ms': 2000, 'navigation_ids': ['unread']}]}},
            '_range_reads': {'range': {'asset_id': 'a', 'start_ms': 0, 'end_ms': 2000,
                                      'pages': [0], 'next_cursor': 1, 'complete': False}}},
        'feedback': {'status': 'READY', 'records': [record], 'data': {'next_cursor': 1}},
        'requirements': {'mode': mode}}
    original = copy.deepcopy(context)
    view = model_view(context)
    assert context == original
    assert view['observations']['read']['text'] == '原始语音'
    assert 'records' not in view['feedback']
    assert view['feedback']['data']['next_cursor'] == 1
    assert view['coverage']['a']['entries_by_page']['0'] == [
        {'excerpt': '尚未读取', 'start_ms': 1000, 'end_ms': 2000}]
    assert 'navigation_ids' not in encoded(view)
    assert view['reading']['next_pages'] == [{'asset_id': 'a', 'start_ms': 0, 'end_ms': 2000, 'cursor': 1}]
    assert view['reading']['assets']['a']['read_record_count'] == 1
    if mode == 'QUERY':
        assert 'words' not in encoded(view)
        assert view['observations']['read']['observation'] == {'emotion': 'neutral', 'language': 'zh'}
        assert len(encoded(view)) < len(encoded(context)) / 3
    else:
        assert view['observations']['read']['observation']['words'] == record['observation']['words']


@pytest.mark.asyncio
async def test_model_projection_preserves_legacy_receipt_identity(tmp_path):
    state = {'identity': {'execution_id': 'execution'}, 'model_sequence': 0,
             'request': {'content': '分析'}, 'usage': {'tools': 0}, 'observations': {}, 'coverage': {}}
    with Store(tmp_path / 'receipt.sqlite') as store:
        store.start('execution')
        legacy_context = {**model_context(state), 'mock_tools': False, 'available_assets': []}
        store.reserve('execution:decide:0', 'execution',
                      fingerprint({'phase': 'decide', 'context': legacy_context}), Limits())
        store.received('execution:decide:0', {'value': {'kind': 'ANSWER', 'content': '原回执'}})
        reused = []
        ctx = SimpleNamespace(store=store, stop=lambda: None, limits=Limits(), assets={}, mock=False,
                              model=None, tracer=SimpleNamespace(reused=reused.append))
        assert await invoke_model(state, SimpleNamespace(context=ctx), 'decide', {}) == {
            'kind': 'ANSWER', 'content': '原回执'}
        assert reused == ['execution:decide:0']
        assert store.usage('execution')['model_calls'] == 1

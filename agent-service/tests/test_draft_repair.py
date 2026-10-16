import copy

import pytest

from agent_service.runtime.draft_repair import prepare_draft, repair_plan
from agent_service.runtime.nodes.request import prepare_turn
from agent_service.runtime.policy import fingerprint
from agent_service.runtime.state import Requirements


def fixture():
    asset = {'id': 'a', 'analysis_version': 'v1', 'duration_ms': 1100000, 'audio_offset_ms': 1436}
    records = {
        'speech': {'id': 'speech', 'asset_id': 'a', 'analysis_version': 'v1', 'source_kind': 'SPEECH',
                   'start_ms': 968280, 'end_ms': 997420, 'text': '完整句子',
                   'observation': {'words': [{'begin_time': 968280 - 1436, 'end_time': 968440 - 1436},
                                             {'begin_time': 968440 - 1436, 'end_time': 997420 - 1436}]}},
        'ending': {'id': 'ending', 'asset_id': 'a', 'analysis_version': 'v1', 'source_kind': 'SPEECH',
                   'start_ms': 997420, 'end_ms': 1006940, 'text': '收束句子', 'observation': {}},
        'point': {'id': 'point', 'asset_id': 'a', 'analysis_version': 'v1', 'source_kind': 'VISUAL',
                  'time_kind': 'POINT', 'observed_at_ms': 1006940},
    }
    requirements = Requirements(mode='DRAFT', outputs=[
        {'output_key': 'out-1', 'duration_min_ms': 30000, 'duration_max_ms': 45000},
        {'output_key': 'out-2', 'duration_min_ms': 30000, 'duration_max_ms': 45000}]).model_dump()
    clip = {'asset_id': 'a', 'source_in_ms': 968320, 'source_out_ms': 997420,
            'evidence_ids': ['speech'], 'reason': '保留句意'}
    draft = {'title': '两个亮点', 'outputs': [
        {'output_key': 'out-1', 'title': '第一条', 'clips': [clip]},
        {'output_key': 'out-2', 'title': '已合格', 'clips': [{**copy.deepcopy(clip),
         'source_in_ms': 968280, 'source_out_ms': 1006940}]}]}
    issues = [{'code': 'CUT_INSIDE_WORD', 'output_key': 'out-1', 'field': 'outputs[0].clips[0].source_in_ms',
               'message': '切词', 'actual': 968320, 'expected': {'nearby_safe_boundaries_ms': [968280, 968440]}},
              {'code': 'OUTPUT_DURATION', 'output_key': 'out-1', 'field': 'outputs[0]', 'message': '太短'}]
    validation = {'valid': False, 'draft_hash': fingerprint(draft),
                  'requirements_hash': fingerprint(Requirements.model_validate(requirements).production(['a'])),
                  'issues': issues, 'issues_truncated': False}
    return {'requirements': requirements, 'draft': draft, 'validation': validation,
            'previous_validation': {'draft_hash': validation['draft_hash']}, 'observations': records,
            'coverage': {'_range_reads': {'r': {'asset_id': 'a', 'start_ms': 957800, 'end_ms': 1031000, 'complete': True}}}}, {'a': asset}


def test_candidates_jointly_resolve_duration_word_cut_and_read_range():
    state, assets = fixture()
    original = copy.deepcopy(state)
    plan = repair_plan(state, assets)
    assert state == original
    assert plan['preserve_output_keys'] == ['out-2']
    target = plan['outputs']['out-1']
    assert target['duration_ms'] == 29100 and target['too_short_ms'] == 900
    candidates = target['clips'][0]['candidates']
    assert candidates[0] == {'source_in_ms': 968280, 'source_out_ms': 1006940,
                             'output_duration_ms': 38660, 'evidence_ids': ['speech']}
    assert all(30000 <= c['output_duration_ms'] <= 45000 for c in candidates)
    assert all(c['source_in_ms'] != 968320 for c in candidates)
    state['coverage']['_range_reads']['r']['complete'] = False
    assert repair_plan(state, assets)['outputs']['out-1']['clips'][0]['candidates'] == []


def test_reference_cleanup_does_not_extend_clip_or_hide_unknown_reference():
    state, assets = fixture()
    proposed = copy.deepcopy(state['draft'])
    clip = proposed['outputs'][0]['clips'][0]
    clip.update(source_in_ms=968280, source_out_ms=1006940, evidence_ids=['speech', 'point', 'unknown'])
    result = prepare_draft(proposed, state, assets)
    assert result['outputs'][0]['clips'][0]['evidence_ids'] == ['speech', 'unknown']
    assert result['outputs'][0]['clips'][0]['source_out_ms'] == 1006940
    assert clip['evidence_ids'] == ['speech', 'point', 'unknown']
    clip['evidence_ids'] = ['point']
    with pytest.raises(ValueError, match='没有与选段相交的依据'):
        prepare_draft(proposed, state, assets)


@pytest.mark.parametrize('invalid', ['truncated', 'global'])
def test_incomplete_or_global_issues_do_not_mark_outputs_as_protected(invalid):
    state, assets = fixture()
    if invalid == 'truncated':
        state['validation']['issues_truncated'] = True
    else:
        state['validation']['issues'].append({'code': 'GLOBAL', 'field': 'outputs', 'message': '全局问题'})
    assert repair_plan(state, assets)['preserve_output_keys'] == []


@pytest.mark.parametrize('change', ['new_turn', 'requirements', 'draft'])
def test_protection_does_not_apply_to_new_request_or_stale_validation(change):
    state, assets = fixture()
    if change == 'new_turn':
        state.update(prepare_turn({**state, 'request': {}}))
    elif change == 'requirements':
        state['requirements']['outputs'][0]['duration_max_ms'] = 50000
    else:
        state['draft']['title'] = '另一份草稿'
    proposed = copy.deepcopy(state['draft'])
    proposed['outputs'][1]['title'] = '用户明确修改'
    assert repair_plan(state, assets) is None
    assert prepare_draft(proposed, state, assets)['outputs'][1]['title'] == '用户明确修改'


def test_multi_clip_suggestions_use_total_output_duration():
    state, assets = fixture()
    state['draft']['outputs'][0]['clips'].append({
        'asset_id': 'a', 'source_in_ms': 997420, 'source_out_ms': 1006940,
        'evidence_ids': ['ending'], 'reason': '收束'})
    state['validation']['draft_hash'] = fingerprint(state['draft'])
    plan = repair_plan(state, assets)
    assert plan['outputs']['out-1']['clips'][0]['candidates']
    for clip in plan['outputs']['out-1']['clips']:
        assert all(30000 <= c['output_duration_ms'] <= 45000 for c in clip['candidates'])


def test_invalid_model_asset_does_not_crash_repair_guidance():
    state, assets = fixture()
    state['draft']['outputs'][0]['clips'][0]['asset_id'] = 'invented'
    state['validation']['draft_hash'] = fingerprint(state['draft'])
    assert repair_plan(state, assets)['outputs']['out-1']['clips'][0]['candidates'] == []


def test_reference_cleanup_preserves_wrong_scope_errors_for_java():
    state, assets = fixture()
    state['observations']['point']['analysis_version'] = 'stale'
    proposed = copy.deepcopy(state['draft'])
    proposed['outputs'][0]['clips'][0]['evidence_ids'] = ['speech', 'point']
    assert prepare_draft(proposed, state, assets)['outputs'][0]['clips'][0]['evidence_ids'] == ['speech', 'point']

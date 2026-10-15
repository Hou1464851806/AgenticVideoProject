import json
from pathlib import Path

from agent_service.editing import Validation, ValidationResult, SavedResult, SaveRequest
from agent_service.runtime.policy import fingerprint

FIXTURE = Path(__file__).resolve().parents[2] / 'backend/src/test/resources/contracts/m2-editing.json'


def test_shared_multilingual_contracts_and_fingerprints():
    sample = json.loads(FIXTURE.read_text())
    request = Validation.model_validate(sample['validation'])
    assert request.model_dump() == sample['validation']
    assert fingerprint(request.draft.model_dump()) == request.draft_hash
    assert fingerprint(request.requirements.model_dump()) == request.requirements_hash
    assert ValidationResult.model_validate(sample['validation_result']).model_dump(exclude_none=True) == sample['validation_result']
    assert SavedResult.model_validate(sample['saved_result']).model_dump() == sample['saved_result']
    assert SaveRequest.model_validate(sample['save_request']).model_dump() == sample['save_request']


def test_second_output_patch_keeps_first_and_explicit_defaults():
    from agent_service.runtime.state import Requirements, Understanding, merge_requirements
    first = merge_requirements(Requirements().model_dump(), Understanding(
        relation='CONTINUE', mode='DRAFT', output_count=2, duration_min_ms=30000, duration_max_ms=30000,
        profile='PORTRAIT_720', subtitles_enabled=False), 'm1', '两条各30秒，竖屏，不要字幕')
    second = merge_requirements(first, Understanding(relation='CONTINUE', mode='DRAFT',
        output_durations=[{'output_key':'out-2','duration_min_ms':20000,'duration_max_ms':20000}]),
        'm2', '第二条改为20秒')
    assert second['outputs'] == [
        {'output_key':'out-1','duration_min_ms':30000,'duration_max_ms':30000},
        {'output_key':'out-2','duration_min_ms':20000,'duration_max_ms':20000}]
    assert second['profile'] == 'PORTRAIT_720'
    assert second['subtitles_enabled'] is False
    assert second['sources']['profile']['message_id'] == 'm1'
    assert second['sources']['outputs.out-2.duration_min_ms']['text'] == '第二条改为20秒'
    assert first['outputs'][1]['duration_min_ms'] == 30000
    default = Requirements().model_dump()
    assert default['outputs'] == [{'output_key':'out-1','duration_min_ms':None,'duration_max_ms':None}]
    assert default['sources']['profile'] == {'kind':'DEFAULT','value':'LANDSCAPE_720'}


def test_shared_invalid_values_are_rejected_without_coercion():
    import copy
    import pytest
    from pydantic import ValidationError
    sample = json.loads(FIXTURE.read_text())
    for case in sample['invalid']:
        value = copy.deepcopy(sample['validation'])
        node = value
        for key in case['path'][:-1]:
            node = node[key]
        if case.get('remove'):
            del node[case['path'][-1]]
        else:
            node[case['path'][-1]] = case['value']
        with pytest.raises(ValidationError, match='.'):
            Validation.model_validate(value)


def test_payload_byte_limit_is_explicit():
    import pytest
    from pydantic import ValidationError
    sample = json.loads(FIXTURE.read_text())['validation']
    # Valid field sizes and clip count can still exceed the total UTF-8 payload budget.
    sample['draft']['outputs'][0]['clips'] *= 100
    for clip in sample['draft']['outputs'][0]['clips']:
        clip['reason'] = '字' * 500
    with pytest.raises(ValidationError, match='byte limit'):
        Validation.model_validate(sample)


def test_output_and_clip_count_boundaries_and_reordered_keys():
    import copy
    import pytest
    from pydantic import ValidationError
    sample = json.loads(FIXTURE.read_text())['validation']
    output = sample['draft']['outputs'][0]
    condition = sample['requirements']['outputs'][0]
    sample['draft']['outputs'] = [{**copy.deepcopy(output), 'output_key':f'out-{i}'} for i in range(10)]
    sample['requirements']['outputs'] = [{**condition, 'output_key':f'out-{i}'} for i in range(10)]
    sample['draft']['outputs'].reverse()
    assert len(Validation.model_validate(sample).draft.outputs) == 10
    sample['draft']['outputs'].append({**copy.deepcopy(output), 'output_key':'extra'})
    with pytest.raises(ValidationError):
        Validation.model_validate(sample)
    sample = json.loads(FIXTURE.read_text())['validation']
    clips = sample['draft']['outputs'][0]['clips']
    clips *= 100
    assert len(Validation.model_validate(sample).draft.outputs[0].clips) == 100
    clips.append(clips[0])
    with pytest.raises(ValidationError):
        Validation.model_validate(sample)


def test_feedback_has_its_own_byte_limit():
    import pytest
    from pydantic import ValidationError
    result = json.loads(FIXTURE.read_text())['validation_result']
    issue = result['issues'][0]
    result['issues'] = [{**issue, 'message':'字' * 200} for _ in range(100)]
    with pytest.raises(ValidationError, match='byte limit'):
        ValidationResult.model_validate(result)


def test_save_requires_bounded_object_provenance_and_no_replacement_content():
    import copy
    import pytest
    from pydantic import ValidationError
    sample = json.loads(FIXTURE.read_text())['save_request']
    missing = copy.deepcopy(sample)
    del missing['requirements_sources']
    replacement = {**sample, 'draft': {}}
    large = {**sample, 'requirements_sources': {'topic': {'text': '字' * 22000}}}
    for value in (missing, replacement, large):
        with pytest.raises(ValidationError):
            SaveRequest.model_validate(value)

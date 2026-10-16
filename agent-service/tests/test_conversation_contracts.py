"""The step-1 seam is the shared wire format, not the future Agent implementation."""
import copy
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from agent_service.conversation_contracts import RunInput, RunResult, ParsedInput
from agent_service.runtime.policy import fingerprint

FIXTURE = Path(__file__).resolve().parents[2] / 'backend/src/test/resources/contracts/conversation-lifecycle.json'
SAMPLE = json.loads(FIXTURE.read_text())


@pytest.mark.parametrize('key,model', [('inputs', RunInput), ('results', RunResult)])
def test_shared_wire_roundtrip_and_fingerprints(key, model):
    for case in SAMPLE[key]:
        parsed = model.model_validate(case['value'])
        assert parsed.model_dump(by_alias=True) == case['value']
        assert fingerprint(parsed.model_dump(by_alias=True)) == case['hash']


def test_understanding_contains_only_content_and_relationship():
    for value in SAMPLE['parsed']:
        assert ParsedInput.model_validate(value).model_dump(by_alias=True) == value


@pytest.mark.parametrize('case', SAMPLE['invalid'], ids=lambda case: case['name'])
def test_shared_invalid_wire_values(case):
    key, model = {'input': ('inputs', RunInput), 'result': ('results', RunResult), 'parsed': ('parsed', ParsedInput)}[case['target']]
    value = copy.deepcopy(SAMPLE[key][case.get('baseIndex', 0)] if key == 'parsed' else SAMPLE[key][case.get('baseIndex', 0)]['value'])
    node = value
    for part in case['path'][:-1]:
        node = node[part]
    if case.get('remove'):
        del node[case['path'][-1]]
    else:
        node[case['path'][-1]] = case['value']
    with pytest.raises(ValidationError):
        model.model_validate(value)


def test_total_byte_budget_includes_history_and_references():
    value = copy.deepcopy(SAMPLE['inputs'][0]['value'])
    value['history'] = [{**value['history'][0], 'content': '字' * 16000} for _ in range(30)]
    with pytest.raises(ValidationError, match='byte limit'):
        RunInput.model_validate(value)

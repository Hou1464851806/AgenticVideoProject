"""Shared M4 transport values; HTTP client behavior is implemented in step 3."""
import json
from pathlib import Path

import pytest
from pydantic import ValidationError

from agent_service.preview import PreviewRequest, PreviewResult, PreviewList

FIXTURE = Path(__file__).resolve().parents[2] / 'backend/src/test/resources/contracts/m4-preview.json'


def test_shared_preview_contracts():
    sample = json.loads(FIXTURE.read_text())
    assert PreviewRequest.model_validate(sample['request']).model_dump(exclude_none=True) == sample['request']
    for value in sample['selection_requests']:
        assert PreviewRequest.model_validate(value).model_dump(exclude_none=True) == value
    for value in sample['results']:
        assert PreviewResult.model_validate(value).model_dump() == value
    assert PreviewList.model_validate(sample['list']).model_dump() == sample['list']


@pytest.mark.parametrize('value', [{'output_id': 'foreign'}, {'output_key': 1}, {'output_key': ''}, {'output_key': None}])
def test_preview_request_rejects_replacement_identity_and_invalid_selection(value):
    with pytest.raises(ValidationError):
        PreviewRequest.model_validate(value)

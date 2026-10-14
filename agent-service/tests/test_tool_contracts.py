import pytest
from pydantic import ValidationError

from agent_service.tools.contracts import Range, PARAMETERS
from agent_service.tools.catalog import model_tools
from agent_service.tracing import mask


def test_tool_catalog_and_strict_parameters():
    assert {item["name"] for item in model_tools()} == set(PARAMETERS)-{"validate_edit_draft"}
    with pytest.raises(ValidationError):
        Range(asset_id="lecture", start_ms=100, end_ms=1)
    with pytest.raises(ValidationError):
        Range(asset_id="lecture", start_ms=0, end_ms=100, user_id="forged")


def test_trace_projection_filters_credentials_and_reasoning():
    result = mask({"api_key": "secret", "reasoning_content": "private", "data": {"text": "sample"}})
    assert result["api_key"] == "[redacted]"
    assert result["reasoning_content"] == "[redacted]"
    assert result["data"]["text"] == "sample"


def test_result_status_requires_corresponding_payload():
    from agent_service.tools.contracts import ToolResult
    for status in ('PENDING', 'ERROR'):
        with pytest.raises(ValidationError):
            ToolResult(call_id='a', tool_name='read_video_range', request_hash='hash', status=status)

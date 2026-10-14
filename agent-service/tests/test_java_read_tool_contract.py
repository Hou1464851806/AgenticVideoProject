"""Shared Java/Python request fingerprint vectors; no backend or paid providers needed."""
import json
from pathlib import Path

from agent_service.runtime.policy import fingerprint
from agent_service.tools.contracts import PARAMETERS, ToolRequest


def test_java_fingerprint_fixtures_follow_python_parameter_contracts():
    path = Path(__file__).resolve().parents[2] / "backend/src/test/resources/contracts/python-fingerprints.json"
    for fixture in json.loads(path.read_text()):
        value = fixture["value"]
        assert fingerprint(value) == fixture["hash"]
        PARAMETERS[value["tool"]].model_validate(value["args"])
        ToolRequest(call_id="contract", tool_name=value["tool"], arguments=value["args"], request_hash=fixture["hash"])

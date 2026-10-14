from types import SimpleNamespace

import pytest
from agent_service.model import DeepSeekModel


@pytest.mark.asyncio
@pytest.mark.parametrize('calls,invalid,finish,parsing', [
    ([], [], 'stop', None),
    ([{}, {}], [], 'stop', None),
    ([{}], [{}], 'stop', None),
    ([{}], [], 'length', None),
    ([{}], [], 'stop', ValueError('Malformed structure')),
])
async def test_provider_invalid_structured_response_is_not_accepted(calls, invalid, finish, parsing):
    class Runnable:
        async def ainvoke(self, *args, **kwargs):
            raw = SimpleNamespace(tool_calls=calls, invalid_tool_calls=invalid, usage_metadata={}, response_metadata={'finish_reason':finish})
            return {'raw': raw, 'parsed': None, 'parsing_error': parsing}
    model = object.__new__(DeepSeekModel)
    model.prepare = lambda *args: (Runnable(), [])
    response = await model.invoke('decide', {}, {})
    assert response['error']
    assert response['value'] is None


def test_prompt_limit_includes_function_schema_before_submission():
    from agent_service.runtime.policy import Limits, LimitReached
    model = DeepSeekModel('test-local-only', Limits(prompt_bytes=10))
    with pytest.raises(LimitReached):
        model.prepare('understand', {})

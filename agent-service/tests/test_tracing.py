from unittest.mock import Mock

import pytest

from agent_service.tracing import Tracer


@pytest.mark.parametrize('host', ['localhost', '127.0.0.1', '[::1]', 'host.docker.internal'])
def test_local_tracer_initializes_from_worker_environment(monkeypatch, host):
    monkeypatch.setenv('LANGFUSE_PUBLIC_KEY', 'pk-local-test')
    monkeypatch.setenv('LANGFUSE_SECRET_KEY', 'sk-local-test')
    base = f'http://{host}:3035'
    monkeypatch.setenv('LANGFUSE_BASE_URL', base)
    health = Mock()
    monkeypatch.setattr('httpx.get', health)
    client = Mock()
    monkeypatch.setattr('langfuse.Langfuse', client)
    callback = Mock()
    monkeypatch.setattr('langfuse.langchain.CallbackHandler', callback)

    tracer = Tracer()

    health.assert_called_once_with(base + '/api/public/health', timeout=2)
    assert tracer.client is client.return_value
    assert client.call_args.kwargs['base_url'] == base
    assert tracer.callbacks == [callback.return_value]


def test_tracer_rejects_external_host(monkeypatch):
    monkeypatch.setenv('LANGFUSE_PUBLIC_KEY', 'pk-local-test')
    monkeypatch.setenv('LANGFUSE_SECRET_KEY', 'sk-local-test')
    monkeypatch.setenv('LANGFUSE_BASE_URL', 'https://cloud.langfuse.com')
    with pytest.raises(ValueError, match='local self-hosted'):
        Tracer()

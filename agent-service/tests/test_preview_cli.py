"""Preview CLI behavior through real HTTP and persisted Agent checkpoints."""
import asyncio
import json
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import sys
from threading import Thread

import pytest

from agent_service import cli

SAMPLE = json.loads((Path(__file__).resolve().parents[2] / 'backend/src/test/resources/contracts/m4-preview.json').read_text())


@pytest.fixture
def preview_server():
    calls = []
    reply = {'status': 200, 'body': SAMPLE['results'][0]}

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            self.respond()

        def do_POST(self):
            self.respond()

        def respond(self):
            content = self.rfile.read(int(self.headers.get('Content-Length', 0)))
            calls.append((self.command, self.path, json.loads(content) if content else None))
            assert self.headers['Authorization'] == 'Bearer preview-test-token'
            if reply.get('disconnect'):
                self.close_connection = True
                return
            status, body = reply.get('routes', {}).get(self.path, (reply['status'], reply['body']))
            self.send_response(status)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps(body).encode())

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        yield f'http://127.0.0.1:{server.server_port}', calls, reply
    finally:
        server.shutdown()
        thread.join()
        server.server_close()


def invoke(monkeypatch, capsys, url, directory, *command):
    monkeypatch.setenv('INTERNAL_WORKER_TOKEN', 'preview-test-token')
    monkeypatch.setenv('DEEPSEEK_API_KEY', '')
    monkeypatch.setattr(sys, 'argv', ['agent_service', '--java-url', url, '--data-dir', str(directory), *command])
    code = cli.main()
    output = capsys.readouterr()
    return code, json.loads(output.out or output.err), output.err


def test_request_status_needs_no_local_thread_or_agent(preview_server, tmp_path, monkeypatch, capsys):
    url, calls, reply = preview_server
    directory = tmp_path / 'absent'
    identity = reply['body']['request_id']
    code, result, _ = invoke(monkeypatch, capsys, url, directory, 'preview-status', identity)
    assert code == 0
    assert result == SAMPLE['results'][0]
    assert calls == [('GET', '/internal/v1/previews/' + identity, None)]
    assert not directory.exists()


@pytest.fixture
def production(tmp_path):
    from agent_service.model import ScriptedModel
    from test_real_production import ProductionBackend, runner, UNDERSTAND, READ, DRAFT
    backend = ProductionBackend()
    agent = runner(tmp_path, backend, ScriptedModel(UNDERSTAND, [READ, DRAFT]))
    state = asyncio.run(agent.send('生成草稿'))
    return agent, backend, state['thread_id']


def test_saved_thread_preview_only_reads_identity(preview_server, production, tmp_path, monkeypatch, capsys):
    url, calls, reply = preview_server
    agent, backend, identity = production
    asyncio.run(agent.save(identity))
    before = asyncio.run(agent.inspect(identity))
    code, result, stderr = invoke(monkeypatch, capsys, url, tmp_path, 'preview', identity, '--output', 'out-1')
    assert code == 0
    assert result['request_id'] == SAMPLE['results'][0]['request_id']
    assert calls == [('POST', '/internal/v1/productions/' + identity + '/previews', {'output_key': 'out-1'})]
    assert 'preview-status' in stderr
    assert asyncio.run(agent.inspect(identity)) == before


def test_unsubmitted_draft_does_not_query_or_implicitly_save(preview_server, production, tmp_path, monkeypatch, capsys):
    url, calls, _ = preview_server
    agent, backend, identity = production
    before = asyncio.run(agent.inspect(identity))
    code, result, _ = invoke(monkeypatch, capsys, url, tmp_path, 'preview', identity)
    assert code == 1
    assert '显式保存' in result['error']['message']
    assert not calls
    assert not backend.saves
    assert asyncio.run(agent.inspect(identity)) == before


@pytest.mark.parametrize('command', ['preview', 'preview-list'])
def test_lost_save_response_is_recovered_without_checkpoint_write(command, preview_server, production, tmp_path, monkeypatch, capsys):
    url, calls, reply = preview_server
    agent, backend, identity = production
    backend.lose_save = True
    import httpx
    with pytest.raises(httpx.ReadError):
        asyncio.run(agent.save(identity))
    before = asyncio.run(agent.inspect(identity))
    assert before['save_request'] and before['saved_result'] is None
    saved_path = '/internal/v1/questions/productions/' + identity + '/save'
    reply['routes'] = {saved_path: (200, backend.saves[identity])}
    if command == 'preview-list':
        reply['body'] = SAMPLE['list']
    code, result, _ = invoke(monkeypatch, capsys, url, tmp_path, command, identity)
    assert code == 0
    assert calls == [('GET', saved_path, None),
                     ('GET' if command == 'preview-list' else 'POST',
                      '/internal/v1/productions/' + identity + '/previews', None if command == 'preview-list' else {})]
    assert asyncio.run(agent.inspect(identity)) == before


def test_lost_save_not_found_requires_explicit_save(preview_server, production, tmp_path, monkeypatch, capsys):
    url, calls, reply = preview_server
    agent, backend, identity = production
    backend.lose_save = True
    import httpx
    with pytest.raises(httpx.ReadError):
        asyncio.run(agent.save(identity))
    before = asyncio.run(agent.inspect(identity))
    reply.update(status=404, body={})
    code, result, _ = invoke(monkeypatch, capsys, url, tmp_path, 'preview', identity)
    assert code == 1 and '显式保存' in result['error']['message']
    assert len(calls) == 1 and calls[0][0] == 'GET'
    assert asyncio.run(agent.inspect(identity)) == before


@pytest.mark.parametrize('command,suffix', [('preview-cancel', '/cancel'), ('preview-retry', '/retry')])
def test_request_actions_bypass_scripts_and_local_storage(command, suffix, preview_server, tmp_path, monkeypatch, capsys):
    url, calls, reply = preview_server
    identity = SAMPLE['results'][0]['request_id']
    directory = tmp_path / 'absent'
    code, result, stderr = invoke(monkeypatch, capsys, url, directory, '--script', str(tmp_path / 'missing.json'), command, identity)
    assert code == 0
    assert result == SAMPLE['results'][0]
    assert calls == [('POST', '/internal/v1/previews/' + identity + suffix, None)]
    assert not directory.exists()
    if command == 'preview-cancel':
        assert '所有请求' in stderr


@pytest.mark.parametrize('task_status,artifact_state,expected', [
    ('PENDING', None, 0), ('RUNNING', None, 0), ('CANCEL_REQUESTED', None, 0),
    ('FAILED', None, 1), ('CANCELLED', None, 1), ('SUCCEEDED', 'AVAILABLE', 0),
    ('SUCCEEDED', 'UNAVAILABLE', 1), ('SUCCEEDED', 'REMOVED', 1),
])
def test_status_keeps_task_result_separate_from_artifact_availability(task_status, artifact_state, expected, preview_server, tmp_path, monkeypatch, capsys):
    url, calls, reply = preview_server
    value = {**SAMPLE['results'][0], 'task_status': task_status}
    if artifact_state:
        value['artifact'] = {'artifact_id': value['output_id'], 'state': artifact_state, 'duration_ms': 4000}
    reply['body'] = value
    code, result, _ = invoke(monkeypatch, capsys, url, tmp_path / 'absent', 'preview-status', value['request_id'])
    assert code == expected
    assert result == value


@pytest.mark.parametrize('command', ['preview', 'preview-status', 'preview-retry', 'preview-cancel'])
def test_terminal_result_exit_code_depends_on_operation(command, preview_server, production, tmp_path, monkeypatch, capsys):
    url, calls, reply = preview_server
    agent, backend, thread = production
    asyncio.run(agent.save(thread))
    reply['body'] = {**SAMPLE['results'][0], 'task_status': 'CANCELLED', 'retry_allowed': True}
    identity = thread if command == 'preview' else reply['body']['request_id']
    code, result, stderr = invoke(monkeypatch, capsys, url, tmp_path, command, identity)
    assert code == (0 if command == 'preview-cancel' else 1)
    assert result['task_status'] == 'CANCELLED'
    if command != 'preview-cancel':
        assert 'preview-retry' in stderr


def test_server_selection_error_shows_authoritative_choices(preview_server, production, tmp_path, monkeypatch, capsys):
    url, calls, reply = preview_server
    agent, backend, thread = production
    asyncio.run(agent.save(thread))
    reply.update(status=400, body={'error': {'code': 'OUTPUT_SELECTION_REQUIRED', 'message': '请选择 out-1 或 out-2'}})
    code, result, _ = invoke(monkeypatch, capsys, url, tmp_path, 'preview', thread)
    assert code == 1
    assert result['error']['code'] == 'OUTPUT_SELECTION_REQUIRED'
    assert 'out-2' in result['error']['message']
    assert calls[0][2] == {}


def test_mock_thread_is_rejected(preview_server, tmp_path, monkeypatch, capsys):
    from test_agent_scenarios import agent
    state = asyncio.run(agent(tmp_path).send('缓存如何读取'))
    url, calls, _ = preview_server
    code, result, _ = invoke(monkeypatch, capsys, url, tmp_path, 'preview-list', state['thread_id'])
    assert code == 1 and '真实制作身份' in result['error']['message']
    assert not calls


@pytest.mark.parametrize('command', ['preview-status', 'preview-cancel', 'preview-retry'])
def test_lost_response_does_not_invent_task_failure(command, preview_server, tmp_path, monkeypatch, capsys):
    url, calls, reply = preview_server
    reply['disconnect'] = True
    code, result, _ = invoke(monkeypatch, capsys, url, tmp_path / 'absent', command, SAMPLE['results'][0]['request_id'])
    assert code == 1
    assert 'task_status' not in result and 'status' not in result
    assert result['error']['code'] == ('PREVIEW_TRANSPORT_ERROR' if command == 'preview-status' else 'PREVIEW_TRANSPORT_UNCERTAIN')
    assert '核对' in result['error']['message']
    assert len(calls) == 1


@pytest.mark.parametrize('missing', ['url', 'token'])
def test_missing_connection_settings_fail_before_creating_agent(missing, tmp_path, monkeypatch, capsys):
    monkeypatch.setenv('INTERNAL_WORKER_TOKEN', '' if missing == 'token' else 'token')
    args = ['agent_service', '--data-dir', str(tmp_path / 'absent')]
    if missing != 'url':
        args += ['--java-url', 'http://unused']
    monkeypatch.setattr(sys, 'argv', args + ['preview-status', SAMPLE['results'][0]['request_id']])
    assert cli.main() == 1
    result = json.loads(capsys.readouterr().err)
    assert ('--java-url' if missing == 'url' else 'INTERNAL_WORKER_TOKEN') in result['error']['message']
    assert not (tmp_path / 'absent').exists()

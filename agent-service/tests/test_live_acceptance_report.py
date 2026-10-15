"""Acceptance reporting must not count failed CLI steps as successful evidence."""
import copy
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

spec = importlib.util.spec_from_file_location('acceptance', Path(__file__).parents[1] / 'scripts/verify_java_m2.py')
acceptance = importlib.util.module_from_spec(spec)
spec.loader.exec_module(acceptance)


@pytest.mark.parametrize('status,exit_code,changed', [
    ('INCOMPLETE', 1, False), ('INCOMPLETE', 0, False),
    ('DRAFT_READY', 1, False), ('DRAFT_READY', 0, True),
])
def test_failed_or_changed_status_cannot_pass_acceptance(tmp_path, monkeypatch, capsys, status, exit_code, changed):
    project, asset = '00000000-0000-4000-8000-000000000001', '00000000-0000-4000-8000-000000000002'
    samples = tmp_path / 'samples.json'
    samples.write_text(json.dumps({'samples': [{'fixture': 'labelled-speech.mp4',
        'scope': {'project_id': project, 'assets': [{'id': asset}]}}]}))
    initial = {'status': 'DRAFT_READY', 'outcome': {'mock': False}, 'thread_id': project,
        'draft': {'outputs': [{'title': 'original'}]}, 'validation': {'valid': True},
        'question_scope': {'scope_id': project}, 'identity': {'requirement_id': project},
        'requirements': {'outputs': [{'output_key': 'out-1', 'duration_min_ms': None, 'duration_max_ms': None}],
            'profile': 'LANDSCAPE_720', 'subtitles_enabled': True, 'sources': {'profile': {'kind': 'DEFAULT'}}}}
    inspected = {**copy.deepcopy(initial), 'status': status}
    if changed:
        inspected['draft']['outputs'][0]['title'] = 'changed'
    responses = iter([SimpleNamespace(stdout=json.dumps(initial), stderr='', returncode=0),
                      SimpleNamespace(stdout=json.dumps(inspected), stderr='', returncode=exit_code)])
    monkeypatch.setattr(acceptance.subprocess, 'run', lambda *args, **kwargs: next(responses))
    queries = iter([{}, {'validations': [{}], 'versions': []}, {}])
    monkeypatch.setattr(acceptance, 'database', lambda sql: next(queries))
    class Connection:
        def __enter__(self):
            return self
        def __exit__(self, *args):
            pass
        def execute(self, sql):
            return [('id', 'execution', 'RECEIVED', json.dumps({'model': 'deepseek-flash'}))]
    monkeypatch.setattr(acceptance.sqlite3, 'connect', lambda path: Connection())
    output = tmp_path / 'output'
    with pytest.raises(AssertionError):
        acceptance.main(SimpleNamespace(samples=samples, case='single', output=output, url='http://unused'))
    assert 'PASS' not in capsys.readouterr().out
    assert json.loads((output / '1-stdout.json').read_text()) == inspected
    assert json.loads((output / 'invocations.json').read_text())[1]['exit_code'] == exit_code


def test_cli_failure_keeps_raw_output_and_command(tmp_path, monkeypatch):
    samples = tmp_path / 'samples.json'
    samples.write_text(json.dumps({'samples': [{'fixture': 'labelled-speech.mp4', 'scope': {
        'project_id': '00000000-0000-4000-8000-000000000001',
        'assets': [{'id': '00000000-0000-4000-8000-000000000002'}]}}]}))
    monkeypatch.setattr(acceptance, 'database', lambda sql: {})
    monkeypatch.setattr(acceptance.subprocess, 'run', lambda *args, **kwargs:
        SimpleNamespace(stdout='', stderr='{"status":"FAILED","error_type":"ConnectError"}', returncode=1))
    output = tmp_path / 'output'
    with pytest.raises(RuntimeError, match='退出码 1'):
        acceptance.main(SimpleNamespace(samples=samples, case='single', output=output, url='http://unused'))
    assert (output / '0-stdout.json').read_text() == ''
    assert 'ConnectError' in (output / '0-stderr.log').read_text()
    assert json.loads((output / 'invocations.json').read_text())[0]['command'][-2:] == ['run', '生成剪辑']

"""Explicit, local Compose fault experiments for an existing saved M2 output.

Interrupts Java, RabbitMQ and Silo; run only on the local acceptance stack with
no unrelated work running. Never changes a saved plan or original media. Backs
up and restores the selected preview object. Raw responses remain local.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import socket
import subprocess
from threading import Thread
import time

import httpx
from dotenv import load_dotenv

from verify_java_m4 import ROOT, cli, database, receipt, await_terminal


def compose(*command):
    return subprocess.run(['docker', 'compose', *command], cwd=ROOT,
                          check=True, capture_output=True, text=True).stdout


def ffmpeg(mode):
    # Container-local executable boundary only; the repository/image is untouched.
    script = '#!/bin/sh\nexec /usr/bin/ffmpeg ' + ('-re "$@"\n' if mode == 'slow' else '-m4-invalid-option\n')
    if mode == 'normal':
        compose('exec', '-T', '--user', 'root', 'business-api', 'rm', '-f', '/usr/local/bin/ffmpeg')
    else:
        compose('exec', '-T', '--user', 'root', 'business-api', 'sh', '-c',
                'printf %s "$1" > /usr/local/bin/ffmpeg && chmod 755 /usr/local/bin/ffmpeg', 'm4', script)


def wait_state(args, request, states):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        result = cli(args, ['preview-status', request], expected=(0, 1))
        if result['task_status'] in states:
            return result
        assert result['task_status'] in ('PENDING', 'RUNNING', 'CANCEL_REQUESTED'), result
        time.sleep(.2)
    raise TimeoutError((request, states))


def freeze_ffmpeg():
    # Wait for an actual FFmpeg process, then suspend it so the fault is reproducible.
    compose('exec', '-T', '--user', 'root', 'business-api', 'sh', '-c',
            'for n in $(seq 1 100); do if pgrep -x ffmpeg >/dev/null; then pkill -STOP -x ffmpeg; exit 0; fi; sleep .05; done; exit 1')


def restart():
    compose('kill', '-s', 'SIGKILL', 'business-api')
    compose('start', 'business-api')
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        try:
            response = httpx.get('http://localhost:8080/api/v1/auth/csrf', timeout=2)
        except httpx.TransportError:
            time.sleep(.5)
            continue
        if response.status_code == 200:
            return
        response.raise_for_status()
    raise TimeoutError('Java did not restart')


def drop_response(args, command):
    accepted = []

    class DropResponse(BaseHTTPRequestHandler):
        def do_POST(self):
            response = httpx.post(args.url + self.path,
                content=self.rfile.read(int(self.headers.get('Content-Length', '0'))),
                headers={'Authorization': self.headers['Authorization'], 'Content-Type': 'application/json'}, timeout=60)
            accepted.append({'http_status': response.status_code, 'result': response.json()})
            self.connection.shutdown(socket.SHUT_RDWR)
            self.connection.close()

        def log_message(self, *args):
            pass

    server = ThreadingHTTPServer(('127.0.0.1', 0), DropResponse)
    thread = Thread(target=server.serve_forever, daemon=True)
    thread.start()
    try:
        assert cli(args, command, expected=1, url=f'http://127.0.0.1:{server.server_port}', error_code='PREVIEW_TRANSPORT_UNCERTAIN') is None
    finally:
        server.shutdown()
        server.server_close()
        thread.join()
    assert len(accepted) == 1 and accepted[0]['http_status'] == 202, accepted
    return accepted[0]


def main(args):
    args.output.mkdir(parents=True, exist_ok=False)
    saved = next(r['result'] for r in reversed(json.loads((args.source / 'runs.json').read_text())) if r['result']['status'] == 'SAVED')
    thread = saved['thread_id']
    selection = ['preview', thread, '--output', args.output_key]
    state = cli(args, ['status', thread])
    assert state['status'] == 'SAVED'
    assert not database("SELECT to_jsonb(EXISTS(SELECT 1 FROM tasks WHERE status IN ('PENDING','RUNNING','CANCEL_REQUESTED')))"), 'Unrelated work is queued or running'
    compose('exec', '-T', 'business-api', 'test', '!', '-e', '/usr/local/bin/ffmpeg')
    assert not [i for i in cli(args, ['preview-list', thread])['items'] if i['output_key'] == args.output_key], 'Use a saved output without preview history'
    evidence = {}

    def record(name, result):
        evidence[name] = {'result': {**result, 'playback': None}, 'receipt': receipt(result['task_id'])}
        (args.output / 'evidence.json').write_text(json.dumps(evidence, ensure_ascii=False, indent=2))

    backup_key = None
    try:
        compose('exec', '-T', 'rabbitmq', 'rabbitmqctl', 'stop_app')
        accepted = drop_response(args, selection)
        first = accepted['result']
        assert first['task_status'] == 'PENDING'
        listed = cli(args, ['preview-list', thread])
        assert [i['request_id'] for i in listed['items'] if i['output_key'] == args.output_key] == [first['request_id']]
        assert cli(args, selection)['request_id'] == first['request_id']
        cancelled = cli(args, ['preview-cancel', first['request_id']])
        assert cancelled['task_status'] == 'CANCELLED'
        record('lost_submit_and_queued_cancel', cancelled)
        assert evidence['lost_submit_and_queued_cancel']['receipt']['attempts'] == []
        assert cli(args, selection, expected=1)['request_id'] == first['request_id']
        compose('exec', '-T', 'rabbitmq', 'rabbitmqctl', 'start_app')

        ffmpeg('slow')
        retry = drop_response(args, ['preview-retry', first['request_id']])['result']
        with ThreadPoolExecutor(max_workers=2) as pool:
            # Each CLI retains its own logs; run concurrency at HTTP boundary instead.
            load_dotenv(ROOT / '.env')
            def replay(_):
                response = httpx.post(args.url + '/internal/v1/previews/' + first['request_id'] + '/retry',
                                      headers={'Authorization': 'Bearer ' + os.environ['INTERNAL_WORKER_TOKEN']}, timeout=30)
                assert response.status_code == 200
                return response.json()['request_id']
            assert list(pool.map(replay, range(2))) == [retry['request_id']] * 2
        wait_state(args, retry['request_id'], {'RUNNING'})
        freeze_ffmpeg()
        cancelled = cli(args, ['preview-cancel', retry['request_id']])
        assert cancelled['task_status'] == 'CANCEL_REQUESTED'
        record('running_cancel_requested', cancelled)
        # Poll through the HTTP boundary; a pending cancel still exits successfully.
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            cancelled = cli(args, ['preview-cancel', retry['request_id']])
            if cancelled['task_status'] == 'CANCELLED':
                break
            time.sleep(.2)
        assert cancelled['task_status'] == 'CANCELLED'
        record('running_cancel_confirmed', cancelled)
        assert compose('exec', '-T', 'business-api', 'sh', '-c', 'pgrep -x ffmpeg || test $? = 1') == ''

        recovered = cli(args, ['preview-retry', retry['request_id']])
        recovered = wait_state(args, recovered['request_id'], {'RUNNING'})
        freeze_ffmpeg()
        record('before_java_crash', recovered)
        ffmpeg('normal')
        restart()
        result = await_terminal(args, recovered['request_id'])
        record('after_java_recovery', result)
        assert [a['status'] for a in evidence['after_java_recovery']['receipt']['attempts']] == ['EXPIRED', 'SUCCEEDED']
        assert result['task_id'] == recovered['task_id']

        compose('stop', 'silo')
        try:
            assert cli(args, ['preview-status', result['request_id']], expected=1, error_code='PREVIEW_STORAGE_UNAVAILABLE') is None
            unchanged = receipt(result['task_id'])
            assert unchanged['task']['status'] == 'SUCCEEDED'
            assert next(a for a in unchanged['artifacts'] if a['id'] == result['artifact']['artifact_id'])['state'] == 'AVAILABLE'
            evidence['storage_outage'] = unchanged
        finally:
            compose('start', 'silo')
        assert cli(args, ['preview-status', result['request_id']])['artifact']['state'] == 'AVAILABLE'
        artifact = next(a for a in receipt(result['task_id'])['artifacts'] if a['id'] == result['artifact']['artifact_id'])
        compose('exec', '-T', 'silo', 'sh', '-c',
                'mc alias set m4 http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc cp "m4/originals/$1" /tmp/m4-preview-backup', 'm4', artifact['object_key'])
        backup_key = artifact['object_key']
        compose('exec', '-T', 'silo', 'sh', '-c', 'mc rm "m4/originals/$1"', 'm4', backup_key)
        unavailable = cli(args, ['preview-status', result['request_id']], expected=1)
        assert unavailable['task_status'] == 'SUCCEEDED' and unavailable['artifact']['state'] == 'UNAVAILABLE'
        assert unavailable['retry_allowed'] and unavailable['playback'] is None
        assert cli(args, selection, expected=1)['request_id'] == result['request_id']
        record('missing_artifact', unavailable)

        ffmpeg('slow')
        exhausted = cli(args, ['preview-retry', result['request_id']])
        for attempt in range(1, 4):
            wait_state(args, exhausted['request_id'], {'RUNNING'})
            freeze_ffmpeg()
            snapshot = receipt(exhausted['task_id'])
            assert len(snapshot['attempts']) == attempt
            record(f'crash_attempt_{attempt}', cli(args, ['preview-status', exhausted['request_id']]))
            restart()
        failed = wait_state(args, exhausted['request_id'], {'FAILED'})
        assert failed['task_status'] == 'FAILED' and failed['error']['code'] == 'EXECUTION_RECOVERY_EXHAUSTED'
        record('recovery_exhausted', failed)
        assert len(evidence['recovery_exhausted']['receipt']['attempts']) == 3
        assert cli(args, ['preview-retry', result['request_id']], expected=1)['request_id'] == exhausted['request_id']
        assert cli(args, selection, expected=1)['request_id'] == exhausted['request_id']

        cancel_restart = cli(args, ['preview-retry', exhausted['request_id']])
        wait_state(args, cancel_restart['request_id'], {'RUNNING'})
        freeze_ffmpeg()
        cancelled = cli(args, ['preview-cancel', cancel_restart['request_id']])
        assert cancelled['task_status'] == 'CANCEL_REQUESTED'
        record('cancel_before_crash', cancelled)
        restart()
        cancelled = wait_state(args, cancel_restart['request_id'], {'CANCELLED'})
        assert cancelled['task_status'] == 'CANCELLED'
        record('cancel_after_recovery', cancelled)
        assert len(evidence['cancel_after_recovery']['receipt']['attempts']) == 1

        ffmpeg('fail')
        definite = cli(args, ['preview-retry', cancel_restart['request_id']])
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            snapshot = receipt(definite['task_id'])
            if snapshot['task']['status'] == 'FAILED':
                break
            time.sleep(.2)
        definite = cli(args, ['preview-status', definite['request_id']], expected=1)
        assert definite['task_status'] == 'FAILED' and definite['error']['code'] == 'UNDECODABLE_MEDIA'
        record('definite_failure', definite)
        assert len(evidence['definite_failure']['receipt']['attempts']) == 1
        ffmpeg('normal')
        restart()
        assert cli(args, ['preview-status', definite['request_id']], expected=1)['task_status'] == 'FAILED'
        final = cli(args, ['preview-retry', definite['request_id']])
        result = await_terminal(args, final['request_id'])
        record('explicit_retry_success', result)
        assert cli(args, ['status', thread]) == state
        print('PASS: dropped responses, cancellation, storage loss/outage, Java recovery/exhaustion and explicit retries')
    finally:
        compose('start', 'silo', 'rabbitmq', 'business-api')
        compose('exec', '-T', 'rabbitmq', 'rabbitmqctl', 'start_app')
        ffmpeg('normal')
        compose('exec', '-T', '--user', 'root', 'business-api', 'sh', '-c', 'pkill -CONT -x ffmpeg || test $? = 1')
        if backup_key is not None:
            compose('exec', '-T', 'silo', 'sh', '-c', 'mc cp /tmp/m4-preview-backup "m4/originals/$1" && rm /tmp/m4-preview-backup && mc alias rm m4', 'm4', backup_key)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--output-key', required=True)
    parser.set_defaults(url='http://localhost:8080')
    main(parser.parse_args())

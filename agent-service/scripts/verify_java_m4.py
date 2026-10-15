"""Verify saved M2 productions through the real M4 CLI, MQ and FFmpeg.

No upload, analysis, model call or save. Pass an existing M2 acceptance directory
and a fresh evidence directory. This harness explicitly polls; the CLI does not.
Raw CLI logs (including temporary playback URLs) stay in the local output only.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
from uuid import UUID

import httpx

ROOT = Path(__file__).resolve().parents[2]


def database(sql):
    result = subprocess.run(
        ['docker', 'compose', 'exec', '-T', 'postgres', 'psql', '-U',
         'agentic_video', '-d', 'agentic_video', '-At', '-v', 'ON_ERROR_STOP=1', '-c', sql],
        cwd=ROOT, check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def cli(args, command, *, expected=0, url=None, error_code=None):
    number = len(list(args.output.glob('*-command.json')))
    base = [sys.executable, '-m', 'agent_service', '--java-url', url or args.url,
            '--data-dir', str(args.source.resolve() / 'agent'), '--no-trace']
    result = subprocess.run(base + command, cwd=ROOT / 'agent-service',
                            capture_output=True, text=True)
    prefix = args.output / str(number)
    prefix.with_name(prefix.name + '-stdout.json').write_text(result.stdout)
    prefix.with_name(prefix.name + '-stderr.log').write_text(result.stderr)
    prefix.with_name(prefix.name + '-command.json').write_text(json.dumps(
        {'command': base + command, 'exit_code': result.returncode}, indent=2))
    assert result.returncode in ((expected,) if isinstance(expected, int) else expected), f'CLI exit {result.returncode}; logs: {prefix}'
    if error_code is not None:
        assert json.loads(result.stderr)['error']['code'] == error_code, f'Unexpected CLI error; logs: {prefix}'
    return json.loads(result.stdout) if result.stdout else None


def receipt(task):
    task = str(UUID(task))
    return database(f"""SELECT jsonb_build_object(
        'task', (SELECT to_jsonb(t) FROM tasks t WHERE id='{task}'),
        'attempts', (SELECT coalesce(jsonb_agg(to_jsonb(a) ORDER BY attempt_number),'[]') FROM task_attempts a WHERE task_id='{task}'),
        'outbox', (SELECT coalesce(jsonb_agg(to_jsonb(o) ORDER BY available_at,id),'[]') FROM outbox_events o WHERE task_id='{task}'),
        'reports', (SELECT coalesce(jsonb_agg(to_jsonb(r) ORDER BY attempt_id,report_kind,producer_key),'[]') FROM execution_reports r WHERE task_id='{task}'),
        'artifacts', (SELECT coalesce(jsonb_agg(to_jsonb(a)),'[]') FROM artifacts a WHERE task_id='{task}'))""")


def await_terminal(args, request):
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        result = cli(args, ['preview-status', request])
        assert result['task_status'] not in ('FAILED', 'CANCELLED'), result
        if result['task_status'] == 'SUCCEEDED':
            return result
        time.sleep(.2)
    raise TimeoutError(f'Preview did not finish: {request}')


def verify_video(args, result, output):
    assert result['task_status'] == 'SUCCEEDED' and not result['retry_allowed']
    assert result['artifact']['state'] == 'AVAILABLE'
    assert result['playback']['expires_in_seconds'] == 300
    response = httpx.get(result['playback']['url'], timeout=30)
    response.raise_for_status()
    path = args.output / (output['outputId'] + '.mp4')
    path.write_bytes(response.content)
    probe = subprocess.run(['ffprobe', '-v', 'error', '-show_format', '-show_streams',
                            '-of', 'json', str(path)], check=True, capture_output=True, text=True)
    media = json.loads(probe.stdout)
    video = next(s for s in media['streams'] if s['codec_type'] == 'video')
    audio = next(s for s in media['streams'] if s['codec_type'] == 'audio')
    dimensions = (360, 640) if output['render']['profile'] == 'PORTRAIT_720' else (640, 360)
    assert (video['width'], video['height']) == dimensions, video
    assert video['codec_name'] == 'h264' and video['avg_frame_rate'] == '30/1'
    assert audio['codec_name'] == 'aac' and audio['sample_rate'] == '48000' and audio['channels'] == 2
    assert 'mp4' in media['format']['format_name']
    assert abs(float(media['format']['duration']) * 1000 - output['durationMs']) <= 100
    assert abs(result['artifact']['duration_ms'] - output['durationMs']) <= 100
    durable = receipt(result['task_id'])
    artifact = next(a for a in durable['artifacts'] if a['id'] == result['artifact']['artifact_id'])
    digest = hashlib.sha256(response.content).hexdigest()
    assert artifact['sha256'] == digest and artifact['byte_size'] == len(response.content), 'Playback differs from the published artifact'
    # Keep shareable evidence free of expiring storage signatures and local filenames.
    media['format'].pop('filename')
    return {'output': output, 'result': {**result, 'playback': {'expires_in_seconds': 300}},
            'media': media, 'sha256': digest,
            'file': str(path.resolve()), 'receipt': durable}


def main(args):
    args.output.mkdir(parents=True, exist_ok=False)
    runs = json.loads((args.source / 'runs.json').read_text())
    saved = next(run['result'] for run in reversed(runs) if run['result']['status'] == 'SAVED')
    thread = str(UUID(saved['thread_id']))
    revision = str(UUID(saved['saved_result']['revision_id']))
    project = str(UUID(saved['production']['project_id']))
    baseline_sql = f"""SELECT jsonb_build_object(
        'calls', (SELECT count(*) FROM model_calls m JOIN tasks t ON t.id=m.task_id WHERE t.project_id='{project}'),
        'vision', (SELECT count(*) FROM vision_model_calls m JOIN tasks t ON t.id=m.task_id WHERE t.project_id='{project}'),
        'embedding', (SELECT count(*) FROM embedding_batches b JOIN search_index_generations g ON g.id=b.generation_id WHERE g.project_id='{project}'),
        'heads', (SELECT jsonb_agg(to_jsonb(h) ORDER BY h.asset_id) FROM asset_analysis_heads h JOIN assets a ON a.id=h.asset_id WHERE a.project_id='{project}'),
        'version', (SELECT to_jsonb(v) FROM plan_revisions v WHERE id='{revision}'))"""
    before = database(baseline_sql)
    state = cli(args, ['status', thread])
    assert state['status'] == 'SAVED' and state['saved_result'] == saved['saved_result']
    outputs = before['version']['content_json']['outputs']
    results = []
    for identity in saved['saved_result']['outputs']:
        output = next(o for o in outputs if o['outputId'] == identity['output_id'])
        selection = ['--output', identity['output_key']] if len(outputs) > 1 else []
        submitted = cli(args, ['preview', thread, *selection])
        assert submitted['revision_id'] == revision and submitted['output_id'] == output['outputId']
        result = await_terminal(args, submitted['request_id'])
        evidence = verify_video(args, result, output)
        repeat = cli(args, ['preview', thread, *selection])
        assert repeat['request_id'] == result['request_id'] and repeat['task_id'] == result['task_id']
        assert cli(args, ['preview-cancel', result['request_id']])['task_status'] == 'SUCCEEDED'
        refreshed = cli(args, ['preview-status', result['request_id']])
        assert refreshed['artifact'] == result['artifact'] and refreshed['playback']
        current = receipt(result['task_id'])
        assert current['attempts'] == evidence['receipt']['attempts'], 'Replay/query must not execute again'
        assert current['reports'] == evidence['receipt']['reports']
        assert evidence['receipt']['attempts'][-1]['status'] == 'SUCCEEDED'
        assert any(o['status'] == 'PUBLISHED' for o in evidence['receipt']['outbox'])
        results.append(evidence)
        (args.output / 'evidence.json').write_text(json.dumps(results, ensure_ascii=False, indent=2))
    listed = cli(args, ['preview-list', thread])
    assert all(any(i['request_id'] == r['result']['request_id'] for i in listed['items']) for r in results)
    assert cli(args, ['status', thread]) == state, 'Preview must leave Agent state unchanged'
    after = database(baseline_sql)
    assert after == before, 'Preview must reuse the saved revision and unchanged analysis'
    (args.output / 'baseline.json').write_text(json.dumps({'before': before, 'after': after}, ensure_ascii=False, indent=2))
    print('PASS: CLI → M2 saved revision → Outbox/MQ → FFmpeg → playable MP4; inspect content separately')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Existing M2 acceptance directory containing runs.json and agent/')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--url', default='http://localhost:8080')
    main(parser.parse_args())

"""Explicit local Compose SIGKILL during MEDIA_PREPARE; preserves volumes and paid receipts."""
import hashlib
import json
import subprocess
import time
import uuid
from pathlib import Path
import httpx

ROOT = Path(__file__).resolve().parents[2]
OUTPUT = ROOT / 'agent-service/.local/stage5/crash.json'
with httpx.Client(base_url='http://localhost:8080', timeout=30) as client:
    def api(method, path, body=None):
        headers = {}
        if method == 'POST':
            headers['X-XSRF-TOKEN'] = client.get('/api/v1/auth/csrf').json()['data']['csrfToken']
            headers['Idempotency-Key'] = str(uuid.uuid4())
        response = client.request(method, '/api/v1' + path, json=body, headers=headers)
        response.raise_for_status()
        return response.json()['data']
    credentials = {'username': 'm1-crash-' + uuid.uuid4().hex[:12], 'password': uuid.uuid4().hex}
    api('POST', '/auth/register', credentials)
    api('POST', '/auth/login', credentials)
    project = api('POST', '/projects', {'name': 'M1 actual process crash'})['id']
    content = (ROOT / 'frontend/e2e/fixtures/labelled-speech.mp4').read_bytes()
    upload = api('POST', f'/projects/{project}/uploads', {'filename': 'labelled-speech.mp4', 'sizeBytes': len(content), 'sha256': hashlib.sha256(content).hexdigest(), 'idempotencyKey': str(uuid.uuid4()), 'writerId': str(uuid.uuid4())})
    writer = {'writerToken': upload['writerToken']}
    parts = api('POST', f"/uploads/{upload['uploadId']}/part-urls", {**writer, 'partNumbers': [1]})
    httpx.put(parts['parts'][0]['url'], content=content, timeout=60).raise_for_status()
    completed = api('POST', f"/uploads/{upload['uploadId']}/complete", writer)
    task = completed['validationTaskId']
    deadline = time.monotonic() + 30
    while True:
        before = api('GET', f'/tasks/{task}')
        if before['status'] == 'RUNNING':
            break
        assert before['status'] == 'PENDING', before
        assert time.monotonic() < deadline
        time.sleep(.02)
    try:
        subprocess.run(['docker', 'compose', 'kill', '-s', 'SIGKILL', 'business-api'], cwd=ROOT, check=True)
    finally:
        subprocess.run(['docker', 'compose', 'up', '-d', 'business-api'], cwd=ROOT, check=True)
    deadline = time.monotonic() + 120
    while True:
        try:
            response = client.get('/api/v1/auth/csrf')
            if response.status_code == 200:
                break
        except httpx.HTTPError:
            pass
        assert time.monotonic() < deadline, 'Backend did not restart'
        time.sleep(1)
    api('POST', '/auth/login', credentials)
    while True:
        after = api('GET', f'/tasks/{task}')
        if after['status'] == 'SUCCEEDED':
            break
        assert after['status'] in ('PENDING', 'RUNNING'), after
        assert time.monotonic() < deadline, after
        time.sleep(.2)
    assert after['attemptCount'] >= 2, after
    sql = f"SELECT json_agg(row_to_json(a)) FROM (SELECT attempt_number,status,local_execution FROM task_attempts WHERE task_id='{uuid.UUID(task)}' ORDER BY attempt_number) a"
    attempts = json.loads(subprocess.check_output(['docker','compose','exec','-T','postgres','psql','-U','agentic_video','-d','agentic_video','-Atc',sql],cwd=ROOT,text=True))
    assert attempts[0]['status'] == 'EXPIRED', attempts
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_text(json.dumps({'fault': 'SIGKILL Java container while media task RUNNING', 'project_id': project, 'asset_id': completed['assetId'], 'before': before, 'after': after, 'attempts': attempts}, indent=2))
    print('VERIFIED: actual SIGKILL, abandoned generation expired, durable media task recovered through MQ')

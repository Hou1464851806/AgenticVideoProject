"""Run inside the Worker container: real Langfuse, scripted model and simulated Java.

From the repository root:
docker exec -i agenticvideo-agent-worker-1 python < agent-service/scripts/verify_worker_langfuse.py
"""
import asyncio
from collections import Counter
from datetime import datetime, timedelta, timezone
import json
import os
from pathlib import Path
import tempfile
import uuid

import httpx
from langfuse import Langfuse

from agent_service.config import Config
from agent_service.conversation_contracts import RunInput
from agent_service.model import ScriptedModel
from agent_service.runtime.policy import fingerprint
from agent_service.worker import RunClient, execute_delivery


async def main():
    started = datetime.now(timezone.utc)
    identity = {key: str(uuid.uuid4()) for key in (
        'projectId', 'conversationId', 'messageId', 'turnId', 'taskId', 'threadId', 'executionId')}
    value = RunInput.model_validate({'schemaVersion': '1.0', 'identity': identity,
        'content': 'Langfuse Worker 接入验证：讨论剪辑节奏', 'history': [], 'references': [],
        'currentProduction': None, 'pendingQuestions': []})
    binding = {'identity': identity, 'parsed': {'mode': 'DISCUSS', 'relation': 'INDEPENDENT',
        'questionId': None, 'conditionsPatch': {}},
        'scope': {'scope_id': str(uuid.uuid4()), 'project_id': identity['projectId'], 'assets': [], 'mock': False}, 'production': None,
        'productionBinding': None, 'question': None, 'editGeneration': None}
    results = []

    def handle(request):
        action = request.url.path.rsplit('/', 1)[-1]
        if action == 'claim':
            data = {'claimed': True, 'attemptId': str(uuid.uuid4()), 'leaseToken': 'verification-only',
                'leaseExpiresAt': (started + timedelta(minutes=2)).isoformat(),
                'input': value.model_dump(by_alias=True),
                'inputHash': fingerprint(value.model_dump(by_alias=True)), 'binding': None}
        elif action == 'parsed':
            data = binding
        elif action == 'receipts':
            data = []
        elif action == 'heartbeat':
            data = {'action': 'CONTINUE', 'leaseExpiresAt': (started + timedelta(minutes=2)).isoformat()}
        elif action == 'events':
            data = {}
        elif action == 'result':
            results.append(json.loads(request.content)['result'])
            data = {}
        else:
            raise AssertionError(f'Unexpected Java operation: {action}')
        return httpx.Response(200, json={'data': data})

    model = ScriptedModel({'relation': 'CONTINUE', 'mode': 'DISCUSS', 'conversation_relation': 'INDEPENDENT'},
        [{'kind': 'ANSWER', 'content': 'Worker tracing 验证成功。', 'evidence_ids': []}])
    with tempfile.TemporaryDirectory(prefix='worker-langfuse-') as directory:
        async with httpx.AsyncClient(transport=httpx.MockTransport(handle), base_url='http://verification-java') as http:
            await execute_delivery(identity['taskId'], RunClient(http, 'verification-only'),
                Config(Path(directory)), model=model)
    assert len(results) == 1 and results[0]['status'] == 'ANSWER', results

    client = Langfuse(public_key=os.environ['LANGFUSE_PUBLIC_KEY'], secret_key=os.environ['LANGFUSE_SECRET_KEY'],
        base_url=os.environ['LANGFUSE_BASE_URL'])
    # Langfuse v4 exposes session membership through observations, not the retired Sessions API.
    session_filter = json.dumps([{'type': 'string', 'column': 'sessionId', 'operator': '=',
        'value': identity['conversationId']}])
    deadline = asyncio.get_running_loop().time() + 30
    while True:
        page = await asyncio.to_thread(client.api.observations.get_many, filter=session_filter,
            from_start_time=started - timedelta(minutes=1), to_start_time=started + timedelta(minutes=5),
            fields='basic,metadata,io', limit=100)
        roots = [item for item in page.data if item.name == 'agent-invocation']
        traces = list({item.trace_id for item in roots})
        if len(traces) == 2:
            break
        assert asyncio.get_running_loop().time() < deadline, 'Traces were not ingested into the conversation session'
        await asyncio.sleep(1)
    assert None not in traces
    for item in roots:
        assert item.metadata is not None
        assert item.metadata['conversation_id'] == identity['conversationId']
    assert any(item.name == 'understand_request' for item in page.data), 'Missing LangGraph callbacks'
    assert any(item.name == 'decide_next' for item in page.data), 'Missing execution callbacks'
    print(json.dumps({'status': 'PASS', 'model': 'scripted-test-model', 'java': 'simulated',
        'session_id': identity['conversationId'], 'trace_ids': traces,
        'observations': dict(Counter(item.type for item in page.data)),
        'session_filter_verified': True}, ensure_ascii=False, indent=2))


asyncio.run(main())

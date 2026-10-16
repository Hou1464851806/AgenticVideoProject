"""Worker HTTP boundary and real public Agent/SQLite; no paid provider."""
import uuid
from datetime import datetime, timedelta, timezone
import httpx
import pytest

from agent_service.config import Config
from agent_service.model import ScriptedModel
from test_conversation_runtime import message
from test_agent_scenarios import answer


def expiry(seconds: float = 60):
    return (datetime.now(timezone.utc) + timedelta(seconds=seconds)).isoformat()


@pytest.mark.asyncio
async def test_worker_publishes_once_and_replays_after_result_response_loss(tmp_path):
    from agent_service.worker import RunClient, execute_delivery
    value = message()
    task = value.identity.task_id
    attempt = str(uuid.uuid4())
    binding = {'identity': value.identity.model_dump(by_alias=True), 'parsed': {
        'mode': 'DISCUSS', 'relation': 'INDEPENDENT', 'questionId': None, 'conditionsPatch': {}},
        'scope': {'scope_id': str(uuid.uuid4()), 'project_id': value.identity.project_id, 'assets': [], 'mock': False},
        'production': None, 'productionBinding': None, 'question': None, 'editGeneration': None}
    from agent_service.runtime.policy import fingerprint
    results, calls = [], []
    committed = False
    def handle(request):
        nonlocal committed
        import json
        body = json.loads(request.content)
        action = request.url.path.rsplit('/', 1)[-1]
        calls.append(action)
        assert request.headers['authorization'] == 'Bearer private'
        if action == 'claim':
            data = {'claimed': not committed, 'status': 'SUCCEEDED' if committed else 'RUNNING',
                'attemptId': attempt, 'leaseToken': 'lease', 'leaseExpiresAt': expiry(), 'input': value.model_dump(by_alias=True),
                'inputHash': fingerprint(value.model_dump(by_alias=True)), 'binding': None}
        elif action == 'parsed':
            assert body['parsed'] == binding['parsed']
            data = binding
        elif action == 'receipts':
            data = []
        elif action == 'events':
            assert body['type'] == 'activity.updated'
            data = {}
        elif action == 'heartbeat':
            data = {'action': 'CONTINUE', 'leaseExpiresAt': expiry()}
        elif action == 'result':
            results.append(body['result'])
            committed = True
            raise httpx.ReadError('response lost after commit')
        else:
            raise AssertionError(action)
        return httpx.Response(200, json={'data': data})
    model = ScriptedModel({'relation': 'CONTINUE', 'mode': 'DISCUSS', 'conversation_relation': 'INDEPENDENT'}, [answer([], '先确定故事主线。')])
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle), base_url='http://java') as http:
        client = RunClient(http, 'private')
        with pytest.raises(httpx.ReadError):
            await execute_delivery(task, client, Config(tmp_path, trace=False), model=model)
        await execute_delivery(task, client, Config(tmp_path, trace=False), model=model)
    assert len(results) == 1
    assert results[0]['status'] == 'ANSWER'
    assert results[0]['content'] == '先确定故事主线。'
    assert results[0]['identity'] == binding['identity']
    assert calls.count('parsed') == 1


@pytest.mark.asyncio
async def test_browser_tool_transport_keeps_current_attempt_and_generation():
    from agent_service.run_transport import RunClient, RunToolExecutor
    from agent_service.tools.contracts import ToolRequest
    from agent_service.runtime.policy import fingerprint
    captured = []
    request = ToolRequest(call_id='read-1', tool_name='list_assets', arguments={},
                          request_hash=fingerprint({'tool': 'list_assets', 'args': {}}))
    def handle(http_request):
        import json
        captured.append(json.loads(http_request.content))
        return httpx.Response(200, json={'data': {**{key: getattr(request, key) for key in ('call_id', 'tool_name', 'request_hash')}, 'status': 'READY', 'mock': False}})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle), base_url='http://java') as http:
        executor = RunToolExecutor(RunClient(http, 'private'), 'current-task', {'attemptId': 'new-attempt', 'leaseToken': 'new-lease'})
        executor.scope = {'scope_id': 'fixed-scope'}
        executor.generation = 4
        await executor.snapshot(executor.scope)
        assert (await executor.execute(request)).status == 'READY'
    assert captured == [{'attemptId': 'new-attempt', 'leaseToken': 'new-lease', 'editGeneration': 4, 'request': request.model_dump()}]


@pytest.mark.asyncio
async def test_worker_stops_local_model_before_acknowledging_cancel(tmp_path):
    from agent_service.worker import execute_delivery, RunClient
    from agent_service.runtime.policy import fingerprint
    import asyncio
    import json
    value = message()
    entered = asyncio.Event()
    stopped = []
    class HoldingModel(ScriptedModel):
        async def invoke(self, phase, context, config):
            entered.set()
            try:
                await asyncio.Event().wait()
                raise AssertionError('held call unexpectedly released')
            finally:
                stopped.append('model')
    def handle(request):
        action = request.url.path.rsplit('/', 1)[-1]
        body = json.loads(request.content)
        if action == 'claim':
            data = {'claimed': True, 'attemptId': str(uuid.uuid4()), 'leaseToken': 'lease', 'leaseExpiresAt': expiry(),
                'input': value.model_dump(by_alias=True), 'inputHash': fingerprint(value.model_dump(by_alias=True)), 'binding': None}
        elif action == 'events':
            data = {}
        elif action == 'heartbeat':
            data = {'action': 'STOP' if entered.is_set() else 'CONTINUE', 'leaseExpiresAt': expiry()}
        elif action == 'cancelled':
            assert stopped == ['model']
            assert body['processExited'] is True
            stopped.append('acknowledged')
            data = {'status': 'CANCELLED'}
        else:
            raise AssertionError('cancelled run must not publish: ' + action)
        return httpx.Response(200, json={'data': data})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle), base_url='http://java') as http:
        await execute_delivery(value.identity.task_id, RunClient(http, 'private'), Config(tmp_path, trace=False),
                               model=HoldingModel({}, []), heartbeat_seconds=0.01)
    assert stopped == ['model', 'acknowledged']


@pytest.mark.asyncio
async def test_cancel_racing_with_activity_is_acknowledged_after_local_exit(tmp_path):
    import asyncio
    from agent_service.worker import execute_delivery, RunClient
    from agent_service.runtime.policy import fingerprint
    value = message()
    stopped = []
    class HoldingModel(ScriptedModel):
        async def invoke(self, phase, context, config):
            try:
                await asyncio.Event().wait()
                raise AssertionError('held call unexpectedly released')
            finally:
                stopped.append('model')
    cancelling = False
    def handle(request):
        nonlocal cancelling
        action = request.url.path.rsplit('/', 1)[-1]
        if action == 'claim':
            data = {'claimed': True, 'attemptId': str(uuid.uuid4()), 'leaseToken': 'lease', 'leaseExpiresAt': expiry(),
                'input': value.model_dump(by_alias=True), 'inputHash': fingerprint(value.model_dump(by_alias=True)), 'binding': None}
        elif action == 'events':
            cancelling = True
            return httpx.Response(409, json={'error': {'code': 'CONFLICT'}})
        elif action == 'heartbeat':
            data = {'action': 'STOP' if cancelling else 'CONTINUE', 'leaseExpiresAt': expiry()}
        elif action == 'cancelled':
            assert stopped == ['model']
            stopped.append('acknowledged')
            data = {'status': 'CANCELLED'}
        else:
            raise AssertionError(action)
        return httpx.Response(200, json={'data': data})
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle), base_url='http://java') as http:
        await execute_delivery(value.identity.task_id, RunClient(http, 'private'), Config(tmp_path, trace=False),
                               model=HoldingModel({}, []), heartbeat_seconds=0.01)
    assert stopped == ['model', 'acknowledged']


@pytest.mark.asyncio
async def test_rejected_binding_publishes_failure_without_restarting_understanding(tmp_path):
    from agent_service.worker import execute_delivery, RunClient
    from agent_service.runtime.policy import fingerprint
    import json
    value = message()
    results = []
    def handle(request):
        action = request.url.path.rsplit('/', 1)[-1]
        body = json.loads(request.content)
        if action == 'claim':
            data = {'claimed': True, 'attemptId': str(uuid.uuid4()), 'leaseToken': 'lease', 'leaseExpiresAt': expiry(),
                'input': value.model_dump(by_alias=True), 'inputHash': fingerprint(value.model_dump(by_alias=True)), 'binding': None}
        elif action == 'parsed':
            return httpx.Response(409, json={'error': {'code': 'CONFLICT', 'message': '固定依赖失效'}})
        elif action in ('heartbeat', 'events'):
            data = {'action': 'CONTINUE', 'leaseExpiresAt': expiry()}
        elif action == 'result':
            results.append(body['result'])
            data = {'status': 'FAILED'}
        else:
            raise AssertionError(action)
        return httpx.Response(200, json={'data': data})
    model = ScriptedModel({'relation': 'CONTINUE', 'mode': 'DISCUSS', 'conversation_relation': 'INDEPENDENT'}, [])
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle), base_url='http://java') as http:
        await execute_delivery(value.identity.task_id, RunClient(http, 'private'), Config(tmp_path, trace=False), model=model)
    assert len(results) == 1
    assert results[0]['status'] == 'FAILED'
    assert results[0]['failure']['recoverable'] is False
    assert results[0]['failure']['code'] == 'EXECUTION_REJECTED'


@pytest.mark.asyncio
async def test_stalled_heartbeat_cannot_keep_model_running_beyond_acknowledged_lease(tmp_path):
    import asyncio
    from agent_service.worker import execute_delivery, RunClient
    from agent_service.runtime.policy import fingerprint
    value = message()
    stopped = []
    class HoldingModel(ScriptedModel):
        async def invoke(self, phase, context, config):
            try:
                await asyncio.Event().wait()
                raise AssertionError('held call unexpectedly released')
            finally:
                stopped.append('model')
    async def handle(request):
        action = request.url.path.rsplit('/', 1)[-1]
        if action == 'claim':
            return httpx.Response(200, json={'data': {'claimed': True, 'leaseExpiresAt': expiry(0.2),
                'attemptId': str(uuid.uuid4()), 'leaseToken': 'lease', 'binding': None,
                'input': value.model_dump(by_alias=True), 'inputHash': fingerprint(value.model_dump(by_alias=True))}})
        if action == 'heartbeat':
            await asyncio.Event().wait()
        raise AssertionError('Expired lease must not publish: ' + action)
    async with httpx.AsyncClient(transport=httpx.MockTransport(handle), base_url='http://java') as http:
        with pytest.raises(TimeoutError):
            await asyncio.wait_for(execute_delivery(value.identity.task_id, RunClient(http, 'private'), Config(tmp_path, trace=False),
                model=HoldingModel({}, []), heartbeat_seconds=0.01), timeout=2)
    assert stopped == ['model']

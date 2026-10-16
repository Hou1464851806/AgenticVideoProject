"""Single consumer: Java owns tasks, SQLite owns graph checkpoints and paid receipts."""
import asyncio
import contextlib
import fcntl
import json
import logging
import os
import sqlite3
import uuid
from datetime import datetime, timezone

import httpx
from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver

from .config import Config
from .conversation_contracts import RunInput, RunResult
from .run_transport import RunClient, RunToolExecutor
from .runtime.graph import build_graph
from .runtime.persistence import Store
from .runtime.policy import fingerprint
from .runtime.runner import Agent

class RunUnavailable(RuntimeError):
    """Java has durably recorded cancellation but has not reaped its old lease yet."""


LOG = logging.getLogger(__name__)
PHASES = {'understand_request': 'MODELING', 'decide_next': 'MODELING',
          'call_tool': 'READING_EVIDENCE', 'check_result': 'SELF_CHECKING'}


def public_result(value, binding, core, receipts, task):
    identity = binding['identity'] if binding else value.identity.model_dump(by_alias=True)
    production = binding['productionBinding'] if binding else None
    status = core['status']
    outcome = core.get('outcome') or {}
    content = outcome.get('content', '')
    draft = question = failure = None
    generation = binding['editGeneration'] if binding else None
    if status == 'WAITING':
        status = 'WAITING_INPUT'
        receipts = []
        waiting = core['waiting']
        if waiting['kind'] != 'USER_INPUT':
            raise ValueError('Browser tools must return a durable result, not TOOL_RESULT waiting')
        content = waiting['question']
        question = {'questionId': str(uuid.uuid5(uuid.UUID(task), 'question:' + waiting['wait_id'])),
            'taskId': task, 'productionId': production['requirement_id'] if production else None,
            'threadId': identity['threadId'], 'executionId': identity['executionId'],
            'waitId': waiting['wait_id'], 'editGeneration': generation, 'prompt': content}
    elif status == 'DRAFT_READY':
        validation = core['validation']
        draft = {'messageId': str(uuid.uuid5(uuid.UUID(task), 'draft')), 'validationId': validation['validation_id'],
            'editGeneration': generation, 'draftHash': validation['draft_hash'],
            'requirementsHash': validation['requirements_hash']}
        content = '剪辑草稿已通过校验，可在消息下预览或确认。'
    elif status in ('FAILED', 'INCOMPLETE', 'CANCELLED'):
        failure = outcome.get('failure') or {'code': 'CORE_' + status,
            'message': content, 'recoverable': False}
        # Failed dependencies must not prevent publishing the failure itself.
        receipts = []
    if status in ('ANSWER', 'DRAFT_READY'):
        used = set(outcome.get('evidence_ids', []))
        if draft:
            used = {evidence for output in core['draft']['outputs'] for clip in output['clips'] for evidence in clip['evidence_ids']}
        selected = []
        for receipt in receipts:
            ids = sorted(used.intersection(receipt['evidenceIds']))
            if ids:
                selected.append({**receipt, 'evidenceIds': ids})
                used.difference_update(ids)
        if used:
            raise ValueError('Public evidence has no durable receipt in this execution lineage')
        receipts = selected
    mode = binding['parsed']['mode'] if binding else 'QUERY'
    return RunResult.model_validate({'schemaVersion': '1.0', 'identity': identity, 'status': status,
        'mode': mode, 'content': content, 'scopeReceipts': receipts,
        'productionId': production['requirement_id'] if production else None,
        'editGeneration': generation, 'draft': draft, 'question': question, 'failure': failure}).model_dump(by_alias=True)


async def recoverable(agent, value, binding):
    """Only an unfinished checkpoint with no uncertain paid submission can be retried."""
    ids = {value.identity.execution_id}
    if binding:
        ids.add(binding['identity']['executionId'])
    with Store(agent.path) as store:
        if store.has_uncertain_calls(ids):
            return False
        record = store.accept_run(value.model_dump(by_alias=True))
        if record['result']:
            return False
    thread = binding['identity']['threadId'] if binding else value.identity.thread_id
    async with AsyncSqliteSaver.from_conn_string(str(agent.path)) as saver:
        snapshot = await build_graph(saver).aget_state({'configurable': {'thread_id': thread}})
    return not snapshot.values or bool(snapshot.next) and not snapshot.interrupts


def lease_deadline(expires_at):
    return asyncio.get_running_loop().time() + (datetime.fromisoformat(expires_at) - datetime.now(timezone.utc)).total_seconds()


async def execute_delivery(task, client, config, *, model=None, heartbeat_seconds: float = 10):
    claim = await client.post(task, 'claim', {'executorId': 'python-worker', 'claimRequestId': str(uuid.uuid4())})
    if not claim['claimed']:
        if claim['status'] == 'CANCEL_REQUESTED':
            raise RunUnavailable('Cancellation is awaiting the original lease recovery')
        if claim['status'] not in ('SUCCEEDED', 'WAITING_INPUT', 'FAILED', 'CANCELLED'):
            raise ValueError('Notification has no durable terminal outcome')
        return
    deadline = lease_deadline(claim['leaseExpiresAt'])
    if deadline <= asyncio.get_running_loop().time():
        raise TimeoutError('Claim arrived after its lease expired')
    value = RunInput.model_validate(claim['input'])
    if fingerprint(value.model_dump(by_alias=True)) != claim['inputHash']:
        raise ValueError('Java fixed input hash mismatch')
    credentials = {key: claim[key] for key in ('attemptId', 'leaseToken')}
    binding = claim['binding']
    # Retry tasks reuse the original core input/binding; publication and tool leases belong to this task.
    execution = claim.get('execution')
    core_value = RunInput.model_validate(execution['input']) if execution else value
    core_binding = execution['binding'] if execution else binding
    def execution_binding(bound):
        if not execution or execution['binding']:
            return bound
        return {**bound, 'identity': {**bound['identity'], **{key: execution['input']['identity'][key]
            for key in ('messageId', 'turnId', 'taskId')}}}

    if execution and core_binding is None and binding is not None:
        core_binding = execution_binding(binding)
    executor = RunToolExecutor(client, task, credentials)
    progress = asyncio.Queue()
    agent = Agent(config, model=model, executor=executor, progress=lambda node: progress.put_nowait(node))

    def project(core, receipts):
        if core['status'] in ('FAILED', 'INCOMPLETE'):
            with Store(agent.path) as store:
                ids = [core_value.identity.execution_id]
                if core_binding:
                    ids.append(core_binding['identity']['executionId'])
                if store.has_uncertain_calls(ids):
                    core = {**core, 'outcome': {**core['outcome'], 'failure': {
                        'code': 'PAID_SUBMISSION_UNKNOWN', 'message': core['outcome']['content'], 'recoverable': False}}}
        return public_result(value, binding, core, receipts, task)

    async def run():
        nonlocal binding, core_binding
        try:
            prepared = await agent.prepare_run(core_value)
            if prepared['status'] != 'PARSED':
                return project(prepared, [])
            if binding is None:
                parsed = prepared['parsed_input']
                binding = await client.post(task, 'parsed', {**credentials, 'parsed': {
                    'mode': parsed['mode'], 'relation': parsed['relation'],
                    'questionId': parsed['question_id'], 'conditionsPatch': parsed['conditions_patch']}})
                core_binding = execution_binding(binding)
            executor.scope = core_binding['scope']
            executor.generation = binding['editGeneration']
            core = await agent.execute_run(core_value, identity=core_binding['identity'],
                scope=core_binding['scope'], production=core_binding['productionBinding'],
                question=core_binding['question'], edit_generation=core_binding['editGeneration'])
            receipts = await client.post(task, 'receipts', credentials)
            return project(core, receipts)
        except httpx.HTTPError:
            # Lost authority or a rejected contract is not a core failure to hide or ACK.
            raise
        except Exception as error:
            can_resume = isinstance(error, (OSError, sqlite3.OperationalError)) and await recoverable(agent, core_value, core_binding)
            failure = {'code': 'EXECUTION_INTERRUPTED' if can_resume else 'EXECUTION_UNRECOVERABLE',
                       'message': type(error).__name__ + '：执行中断，请核查 Worker 日志。', 'recoverable': can_resume}
            LOG.exception('Agent execution interrupted: task=%s', task)
            return public_result(value, binding, {'status': 'FAILED', 'outcome': {
                'content': failure['message'], 'failure': failure}}, [], task)

    async def supervise():
        sequence = 0
        # A stalled heartbeat must not let paid actions outlive the last acknowledged lease.
        async with asyncio.timeout_at(deadline) as lease:
            while True:
                try:
                    node = await asyncio.wait_for(progress.get(), timeout=heartbeat_seconds)
                except TimeoutError:
                    node = None
                heartbeat = await client.post(task, 'heartbeat', credentials)
                if heartbeat['action'] == 'STOP':
                    return 'STOP'
                lease.reschedule(lease_deadline(heartbeat['leaseExpiresAt']))
                if node in PHASES:
                    sequence += 1
                    try:
                        await client.post(task, 'events', {**credentials, 'producerSeq': sequence,
                            'type': 'activity.updated', 'payload': {'phase': PHASES[node]}})
                    except httpx.HTTPStatusError as error:
                        if error.response.status_code != 409:
                            raise
                        # Cancellation can commit between the heartbeat and activity transaction.
                        heartbeat = await client.post(task, 'heartbeat', credentials)
                        if heartbeat['action'] == 'STOP':
                            return 'STOP'
                        raise

    running = asyncio.create_task(run())
    monitor = asyncio.create_task(supervise())
    try:
        done, _ = await asyncio.wait({running, monitor}, return_when=asyncio.FIRST_COMPLETED)
        if monitor in done:
            action = monitor.result()  # Network/lease failures revoke local work immediately.
            running.cancel()
            with contextlib.suppress(asyncio.CancelledError):
                await running
            if action == 'STOP':
                with Store(agent.path) as store:
                    store.cancel(core_value.identity.execution_id)
                    if core_binding:
                        store.cancel(core_binding['identity']['executionId'])
                await client.post(task, 'cancelled', {**credentials, 'processExited': True})
                return
        result = running.result()
        monitor.cancel()
        with contextlib.suppress(asyncio.CancelledError):
            await monitor
        await client.post(task, 'result', {**credentials, 'result': result})
    except httpx.HTTPStatusError as error:
        if error.response.status_code not in (400, 409, 422):
            raise
        # Business rejection with a still-current lease is a visible terminal failure.
        # A stale attempt or cancelled task cannot use this path to publish anything.
        heartbeat = await client.post(task, 'heartbeat', credentials)
        if heartbeat['action'] != 'CONTINUE':
            raise
        LOG.error('Java rejected Agent execution: task=%s HTTP=%s body=%s',
                  task, error.response.status_code, error.response.text)
        failure = {'code': 'EXECUTION_REJECTED', 'message': '服务端拒绝执行或结果，请核查当前制作和依赖。', 'recoverable': False}
        result = public_result(value, binding, {'status': 'FAILED', 'outcome': {
            'content': failure['message'], 'failure': failure}}, [], task)
        await client.post(task, 'result', {**credentials, 'result': result})
    finally:
        for work in (running, monitor):
            if not work.done():
                work.cancel()
        await asyncio.gather(running, monitor, return_exceptions=True)


async def consume(config, *, model=None):
    import aio_pika
    token = os.environ['INTERNAL_WORKER_TOKEN']
    while True:
        try:
            connection = await aio_pika.connect(host=os.environ.get('RABBITMQ_HOST', 'rabbitmq'),
                port=int(os.environ.get('RABBITMQ_PORT', '5672')), login=os.environ.get('RABBITMQ_USER', 'agentic-worker'),
                password=os.environ['RABBITMQ_PASSWORD'], heartbeat=30)
            async with connection, httpx.AsyncClient(base_url=os.environ.get('JAVA_URL', 'http://business-api:8080'), timeout=60) as http:
                channel = await connection.channel()
                await channel.set_qos(prefetch_count=1)
                queue = await channel.declare_queue('agent.v1', durable=True, arguments={
                    'x-dead-letter-exchange': '', 'x-dead-letter-routing-key': 'agent.dead.v1'})
                await channel.declare_queue('agent.dead.v1', durable=True)
                async with queue.iterator(exclusive=True) as messages:
                    async for delivery in messages:
                        try:
                            event = json.loads(delivery.body)
                            if event['schemaVersion'] != '1.0' or event['taskType'] != 'AGENT_TURN':
                                raise ValueError('Invalid Agent notification')
                            task = str(uuid.UUID(event['taskId']))
                        except (ValueError, KeyError, TypeError):
                            LOG.exception('Rejected malformed Agent notification')
                            await delivery.reject(requeue=False)
                            continue
                        await execute_delivery(task, RunClient(http, token), config, model=model)
                        await delivery.ack()
        except (OSError, httpx.HTTPError, aio_pika.exceptions.AMQPException, RunUnavailable):
            # Closing an unacked channel returns delivery to MQ; Java recovers expired leases.
            LOG.exception('Worker transport interrupted; reconnecting')
            await asyncio.sleep(2)


def main():
    logging.basicConfig(level=logging.INFO)
    config = Config()
    config.directory.mkdir(parents=True, exist_ok=True)
    with open(config.directory / 'worker.lock', 'a') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        asyncio.run(consume(config))


if __name__ == '__main__':
    main()

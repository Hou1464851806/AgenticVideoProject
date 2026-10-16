"""Fixed conversation inputs and trusted execution bindings around the existing graph.

Java supplies authorization and production/question ownership. No queue, HTTP task
protocol or public-result publishing lives in this module.
"""
import fcntl

from langgraph.checkpoint.sqlite.aio import AsyncSqliteSaver
from langgraph.types import Command

from ..conversation_contracts import Identity, PendingQuestion, RunInput
from .graph import build_graph
from .persistence import Store
from .state import Requirements
from ..tools.java import JavaToolExecutor


async def prepare_run(agent, value):
    value = value if isinstance(value, RunInput) else RunInput.model_validate(value)
    wire = value.model_dump(by_alias=True)
    with Store(agent.path) as store:
        record = store.accept_run(wire)
        if record['prepared']:
            return record['prepared']
    async with AsyncSqliteSaver.from_conn_string(str(agent.path)) as saver:
        snapshot = await build_graph(saver).aget_state({'configurable': {'thread_id': value.identity.thread_id}})
    if snapshot.values:
        if snapshot.values['request'].get('task_id') != value.identity.task_id:
            raise ValueError('接纳理解 thread 已被其他任务使用')
        result = (await agent._drive(value.identity.thread_id, None, snapshot.values) if snapshot.next
                  else await agent.inspect(value.identity.thread_id))
        with Store(agent.path) as store:
            store.record_run(value.identity.task_id, prepared=result)
        return result
    current = value.current_production
    requirements = Requirements().model_dump()
    if current:
        requirements = Requirements.model_validate({**requirements, **current.requirements,
            'sources': current.requirements_sources}).model_dump()
    state = {'identity': {**value.identity.model_dump(), 'session_id': value.identity.conversation_id,
                         'requirement_id': value.identity.thread_id},
        'request': {'content': value.content, 'task_id': value.identity.task_id,
                    'parse_only': True, 'public_context': {key: wire[key] for key in ('references', 'currentProduction', 'pendingQuestions')}},
        'requirements': requirements,
        'messages': [{'role': item.role.lower(), 'content': item.content, 'references': item.references} for item in value.history],
        'observations': {}, 'coverage': {}, 'production': None, 'question_scope': None,
        'draft': None, 'validation': None, 'draft_ready': False, 'saved_result': None, 'save_request': None,
        'previous_validation': None, 'scenario': agent.config.scenario}
    result = await agent._drive(value.identity.thread_id, state, expected_checkpoint=None)
    with Store(agent.path) as store:
        store.record_run(value.identity.task_id, prepared=result)
    return result


async def execute_run(agent, value, *, identity, scope, production, question, edit_generation):
    value = value if isinstance(value, RunInput) else RunInput.model_validate(value)
    identity = identity if isinstance(identity, Identity) else Identity.model_validate(identity)
    question = question if isinstance(question, PendingQuestion) or question is None else PendingQuestion.model_validate(question)
    # This is an adapter boundary: models never supply these identities.
    for field in ('project_id', 'conversation_id', 'message_id', 'turn_id', 'task_id'):
        if getattr(identity, field) != getattr(value.identity, field):
            raise ValueError('执行身份不属于固定接纳输入')
    binding = {'identity': identity.model_dump(by_alias=True), 'scope': scope, 'production': production,
               'question': question.model_dump(by_alias=True) if question else None, 'edit_generation': edit_generation}
    # Serialize local handoff/replay; graph execution additionally owns the target thread lock.
    with open(agent.config.directory / (value.identity.task_id + '.run.lock'), 'a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise ValueError('该接纳任务正在另一个本机进程执行') from None
        with Store(agent.path) as store:
            record = store.accept_run(value.model_dump(by_alias=True))
            saved_binding, initial = record['binding'], record['initial']
            if saved_binding and saved_binding != binding:
                raise ValueError('同一接纳任务的执行绑定发生变化')
            if record['result']:
                return record['result']
        async with AsyncSqliteSaver.from_conn_string(str(agent.path)) as saver:
            graph = build_graph(saver)
            prepared = await graph.aget_state({'configurable': {'thread_id': value.identity.thread_id}})
            target = await graph.aget_state({'configurable': {'thread_id': identity.thread_id}})
        if initial is None:
            if not prepared.values or prepared.values.get('outcome', {}).get('status') != 'PARSED':
                raise ValueError('请先完成固定输入理解')
            parsed = prepared.values['parsed_input']
            relation = parsed['relation']
            if (relation == 'ANSWER_QUESTION') != (question is not None):
                raise ValueError('补答必须使用已解析的关联问题')
            if question:
                if question not in value.pending_questions or question.question_id != parsed['question_id']:
                    raise ValueError('问题不属于固定输入的关联补答')
                if (identity.thread_id, identity.execution_id) != (question.thread_id, question.execution_id):
                    raise ValueError('补答必须恢复原 thread/execution')
                if not target.interrupts or target.values['waiting']['wait_id'] != question.wait_id:
                    raise ValueError('原问题已不在等待')
            current = value.current_production
            if relation == 'CONTINUE_PRODUCTION':
                if not current or current.frozen or identity.thread_id != current.thread_id:
                    raise ValueError('不能修改非当前或已冻结制作')
                if not production or production['requirement_id'] != current.production_id or not scope or scope['scope_id'] != current.scope_id:
                    raise ValueError('修改必须使用当前制作及固定 scope')
                if not target.values or target.values['request']['edit_generation'] != current.edit_generation:
                    raise ValueError('制作摘要代次与原 checkpoint 不匹配')
            if question and question.production_id:
                if not production or production['requirement_id'] != question.production_id:
                    raise ValueError('补答必须恢复原制作')
            if relation == 'INDEPENDENT' and (production or identity.thread_id != value.identity.thread_id):
                raise ValueError('独立问答使用本轮 thread，不写制作')
            if relation == 'NEW_PRODUCTION' and (not production or (current and (production['requirement_id'] == current.production_id or identity.thread_id == current.thread_id))):
                raise ValueError('新制作必须使用独立制作身份与 thread')
            if production and (production['project_id'] != identity.project_id or not scope or production['scope_id'] != scope['scope_id']):
                raise ValueError('制作与执行范围不匹配')
            if (production is None) != (edit_generation is None):
                raise ValueError('制作必须携带编辑代次')
            if target.next and not question and identity.thread_id != value.identity.thread_id:
                raise ValueError('目标制作仍有挂起执行')
            if isinstance(agent.executor, JavaToolExecutor) and scope is None:
                raise ValueError('真实执行必须携带 Java 固定 scope')
            # Only continuation takes private production context. Public history is always Java's.
            old = target.values if relation in ('CONTINUE_PRODUCTION', 'ANSWER_QUESTION') else {}
            if relation in ('CONTINUE_PRODUCTION', 'ANSWER_QUESTION'):
                if not old or old.get('production') != production or old.get('question_scope') != scope:
                    raise ValueError('原执行的制作或固定 scope 不匹配')
                if old.get('saved_result'):
                    raise ValueError('已保存制作不可继续编辑')
                if any(old['identity'][key] != getattr(identity, key) for key in ('project_id', 'conversation_id')):
                    raise ValueError('原执行不属于当前会话')
            if question and old['request']['edit_generation'] != question.edit_generation:
                raise ValueError('问题代次与原 checkpoint 不匹配')
            state = {**prepared.values, 'identity': {**identity.model_dump(),
                'session_id': identity.conversation_id, 'requirement_id': production['requirement_id'] if production else identity.thread_id},
                'requirements': old.get('requirements', Requirements().model_dump()),
                'observations': old.get('observations', {}), 'coverage': old.get('coverage', {}),
                'draft': old.get('draft'), 'validation': old.get('validation'), 'draft_ready': old.get('draft_ready', False),
                'production': production, 'question_scope': scope,
                'saved_result': None, 'save_request': None, 'previous_validation': old.get('previous_validation')}
            parsed_understanding = prepared.values['understanding']
            if relation == 'CLARIFY' and not parsed_understanding['question']:
                parsed_understanding = {**parsed_understanding, 'question': '请明确要修改当前制作、开始新制作，还是回答哪个问题。'}
            state['request'] = {**state['request'], 'parse_only': False,
                'pre_understanding': parsed_understanding, 'edit_generation': edit_generation,
                'continuation_sequence': prepared.values['model_sequence'],
                'continuation_usage': prepared.values['usage']}
            if question:
                state = {'kind': 'USER_INPUT', 'wait_id': question.wait_id, 'content': value.content,
                    'pre_understanding': parsed_understanding, 'identity': state['identity'],
                    'public_context': state['request']['public_context'], 'task_id': identity.task_id,
                    'edit_generation': edit_generation, 'entry_usage': prepared.values['usage'],
                    'messages': prepared.values['messages'], 'parsed_input': parsed}
            with Store(agent.path) as store:
                initial = store.bind_run(identity.task_id, binding, state)
        if (target.values and target.values['request'].get('task_id') == identity.task_id
                and not target.values['request'].get('parse_only')):
            result = await agent.resume(identity.thread_id)
            with Store(agent.path) as store:
                store.record_run(identity.task_id, result=result)
            return result
        with Store(agent.path) as store:
            store.transfer_usage(value.identity.execution_id, identity.execution_id)
        if question:
            result = await agent._drive(identity.thread_id, Command(resume=initial), {**target.values, 'identity': initial['identity']})
        else:
            result = await agent._drive(identity.thread_id, initial,
                                        expected_checkpoint=target.config.get('configurable', {}).get('checkpoint_id'))
        with Store(agent.path) as store:
            store.record_run(identity.task_id, result=result)
        return result

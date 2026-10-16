"""Conversation core behavior through public entry points; no Worker or paid model."""
import uuid

import pytest

from agent_service.conversation_contracts import RunInput
from agent_service.model import ScriptedModel
from test_agent_scenarios import agent, answer


def message(content='讨论剪辑节奏', **values):
    identity = {key: str(uuid.uuid4()) for key in (
        'projectId', 'conversationId', 'messageId', 'turnId', 'taskId', 'threadId', 'executionId')}
    return RunInput.model_validate({'schemaVersion': '1.0', 'identity': identity,
        'content': content, 'history': [], 'references': [], 'currentProduction': None,
        'pendingQuestions': [], **values})


@pytest.mark.asyncio
async def test_first_production_cannot_be_parsed_as_continuing_a_nonexistent_production(tmp_path):
    calls = 0
    def understand(ctx):
        nonlocal calls
        calls += 1
        return {'relation': 'CONTINUE', 'mode': 'DRAFT',
                'conversation_relation': 'CONTINUE_PRODUCTION' if calls == 1 else 'NEW_PRODUCTION'}
    runner = agent(tmp_path, understand, [])
    prepared = await runner.prepare_run(message('现在制作一条短片'))
    assert prepared['parsed_input']['relation'] == 'NEW_PRODUCTION'
    assert prepared['usage']['model_calls'] == 2
    assert prepared['usage']['repairs'] == 1


@pytest.mark.asyncio
async def test_literal_null_question_is_repaired_instead_of_published_as_a_wait(tmp_path):
    calls = 0
    def understand(ctx):
        nonlocal calls
        calls += 1
        return {'relation': 'NEW', 'mode': 'DISCUSS', 'conversation_relation': 'INDEPENDENT',
                'question': 'null' if calls == 1 else None}
    runner = agent(tmp_path, understand, [answer([], '可以保持简洁开场。')])
    value = message()
    prepared = await runner.prepare_run(value)
    assert prepared['usage']['model_calls'] == 2
    result = await runner.execute_run(value, identity=value.identity)
    assert result['status'] == 'ANSWER'
    assert result['usage']['repairs'] == 1
    assert result['waiting'] is None


@pytest.mark.asyncio
async def test_fixed_input_is_understood_once_and_discussion_uses_public_history(tmp_path):
    history = [{'messageId': str(uuid.uuid4()), 'role': 'USER', 'content': '上一轮', 'references': []}]
    value = message(history=history)
    def understand(ctx):
        assert ctx['messages'] == [{'role': 'user', 'content': '上一轮', 'references': []}]
        assert ctx['request']['content'] == value.content
        return {'relation': 'CONTINUE', 'mode': 'DISCUSS', 'conversation_relation': 'INDEPENDENT'}
    runner = agent(tmp_path, understand, [answer([], '可以先快后慢。')])
    prepared = await runner.prepare_run(value)
    assert prepared['parsed_input']['relation'] == 'INDEPENDENT'
    assert prepared['usage']['model_calls'] == 1
    runner = agent(tmp_path, {'relation': 'NEW', 'mode': 'DRAFT', 'conversation_relation': 'NEW_PRODUCTION'}, [answer([], '可以先快后慢。')])
    assert (await runner.prepare_run(value))['parsed_input'] == prepared['parsed_input']
    result = await runner.execute_run(value, identity=value.identity)
    assert result['status'] == 'ANSWER'
    assert result['production'] is None
    assert result['usage']['model_calls'] == 2
    replay = await runner.execute_run(value, identity=value.identity)
    assert replay['identity'] == result['identity']
    assert replay['usage']['model_calls'] == 2
    assert replay['outcome'] == result['outcome']


def real_runner(path, backend, value, understanding, actions, limits=None):
    import httpx
    from agent_service.config import Config
    from agent_service.runtime.runner import Agent
    from agent_service.tools.java import JavaToolExecutor
    executor = JavaToolExecutor('http://java', 'private-token', value.identity.project_id,
                               transport=httpx.MockTransport(backend.handle))
    return Agent(Config(path, trace=False, **({'limits': limits} if limits else {})),
                 ScriptedModel(understanding, actions), executor=executor)


def fixed_scope(backend, value):
    from test_java_executor import ASSET
    scope = {'scope_id': str(uuid.uuid4()), 'project_id': value.identity.project_id,
             'mock': False, 'assets': [ASSET]}
    backend.scopes[scope['scope_id']] = scope
    return scope


def summary(result, *, frozen=False):
    return {'productionId': result['production']['requirement_id'],
            'scopeId': result['question_scope']['scope_id'], 'threadId': result['thread_id'],
            'editGeneration': result['edit_generation'], 'requirements': result['requirements'],
            'requirementsSources': result['requirements']['sources'], 'currentDraft': None, 'frozen': frozen}


@pytest.mark.asyncio
@pytest.mark.parametrize("model_limit,crash_transfer", [(12, False), (2, False), (12, True)])
async def test_waiting_production_survives_independent_query_then_linked_reply(tmp_path, model_limit, crash_transfer, monkeypatch):
    from test_java_executor import READ, ANSWER
    from test_real_production import ProductionBackend, DRAFT
    first = message('做一个剪辑')
    backend = ProductionBackend()
    scope = fixed_scope(backend, first)
    production = {'project_id': first.identity.project_id, 'requirement_id': str(uuid.uuid4()), 'scope_id': scope['scope_id']}
    runner = real_runner(tmp_path, backend, first, {'relation': 'NEW', 'mode': 'DRAFT', 'conversation_relation': 'NEW_PRODUCTION', 'question': '要字幕吗？'}, [DRAFT])
    await runner.prepare_run(first)
    waiting = await runner.execute_run(first, identity=first.identity, scope=scope, production=production, edit_generation=1)
    assert waiting['status'] == 'WAITING'
    pending = {'questionId': str(uuid.uuid4()), 'taskId': first.identity.task_id,
        'productionId': production['requirement_id'], 'threadId': first.identity.thread_id,
        'executionId': first.identity.execution_id, 'waitId': waiting['waiting']['wait_id'],
        'editGeneration': 1, 'prompt': '要字幕吗？'}
    query = message('画面是什么？', currentProduction=summary(waiting), pendingQuestions=[pending])
    query.identity.project_id = first.identity.project_id
    query.identity.conversation_id = first.identity.conversation_id
    query_runner = real_runner(tmp_path, backend, query, {'relation': 'CONTINUE', 'mode': 'QUERY', 'conversation_relation': 'INDEPENDENT'}, [READ, ANSWER])
    await query_runner.prepare_run(query)
    queried = await query_runner.execute_run(query, identity=query.identity, scope=scope)
    assert queried['status'] == 'ANSWER'
    assert queried['production'] is None
    still_waiting = await query_runner.inspect(first.identity.thread_id)
    assert still_waiting['waiting'] == waiting['waiting']
    assert still_waiting['requirements'] == waiting['requirements']
    reply = message('不要字幕', currentProduction=summary(waiting), pendingQuestions=[pending])
    reply.identity.project_id = first.identity.project_id
    reply.identity.conversation_id = first.identity.conversation_id
    def parse_reply(ctx):
        assert ctx['request']['content'] == '不要字幕'
        return {'relation': 'CONTINUE', 'mode': 'DRAFT', 'conversation_relation': 'ANSWER_QUESTION',
                'question_id': pending['questionId'], 'subtitles_enabled': False}
    from agent_service.runtime.policy import Limits
    runner = real_runner(tmp_path, backend, reply, parse_reply, [READ, DRAFT], Limits(model_calls=model_limit))
    prepared = await runner.prepare_run(reply)
    identity = reply.identity.model_copy(update={'thread_id': first.identity.thread_id, 'execution_id': first.identity.execution_id})
    if crash_transfer:
        from agent_service.runtime.persistence import Store
        transfer = Store.transfer_usage
        def crash(self, source, target):
            transfer(self, source, target)
            raise RuntimeError('crash after budget transfer')
        monkeypatch.setattr(Store, 'transfer_usage', crash)
        with pytest.raises(RuntimeError, match='budget transfer'):
            await runner.execute_run(reply, identity=identity, scope=scope, production=production,
                                     question=pending, edit_generation=2)
        monkeypatch.setattr(Store, 'transfer_usage', transfer)
    result = await runner.execute_run(reply, identity=identity, scope=scope, production=production,
                                     question=pending, edit_generation=2)
    if model_limit == 2:
        assert result['status'] == 'INCOMPLETE'
        assert result['usage']['model_calls'] == 2
        assert not backend.validations
        replay = await runner.execute_run(reply, identity=identity, scope=scope, production=production,
                                         question=pending, edit_generation=2)
        assert replay['usage']['model_calls'] == 2
        return
    assert result['status'] == 'DRAFT_READY'
    assert result['requirements']['subtitles_enabled'] is False
    assert result['requirements']['sources']['subtitles_enabled']['message_id'] == reply.identity.message_id
    assert result['usage']['model_calls'] == waiting['usage']['model_calls'] + prepared['usage']['model_calls'] + 2
    assert result['identity']['execution_id'] == waiting['identity']['execution_id']
    replay = await runner.execute_run(reply, identity=identity, scope=scope, production=production,
                                     question=pending, edit_generation=2)
    assert replay['usage']['model_calls'] == result['usage']['model_calls']
    assert len(backend.validations) == 1


@pytest.mark.asyncio
async def test_query_after_draft_preserves_conditions_validation_and_can_still_save(tmp_path):
    from test_java_executor import READ, ANSWER
    from test_real_production import ProductionBackend, DRAFT, runner
    backend = ProductionBackend()
    first = await runner(tmp_path, backend, ScriptedModel({'relation': 'NEW', 'mode': 'DRAFT', 'conversation_relation': 'NEW_PRODUCTION'}, [READ, DRAFT])).send('生成草稿')
    queried = await runner(tmp_path, backend, ScriptedModel({'relation': 'CONTINUE', 'mode': 'QUERY', 'conversation_relation': 'INDEPENDENT', 'topic': '画面'}, [ANSWER])).send(
        '画面里是什么', thread_id=first['thread_id'])
    assert queried['status'] == 'ANSWER'
    assert queried['requirements'] == first['requirements']
    assert queried['validation'] == first['validation']
    assert queried['draft'] == first['draft']
    assert len(backend.validations) == 1
    saved = await runner(tmp_path, backend, None).save(first['thread_id'])
    assert saved['status'] == 'SAVED'
    discussed = await runner(tmp_path, backend, ScriptedModel({'relation': 'CONTINUE', 'mode': 'DISCUSS', 'conversation_relation': 'INDEPENDENT'}, [answer([], '节奏可以先快后慢。')])).send(
        '讨论节奏', thread_id=first['thread_id'])
    assert discussed['status'] == 'ANSWER'
    assert discussed['saved_result'] == saved['saved_result']


@pytest.mark.asyncio
async def test_fixed_input_and_binding_cannot_change_on_redelivery(tmp_path):
    value = message()
    runner = agent(tmp_path, {'relation': 'CONTINUE', 'mode': 'DISCUSS', 'conversation_relation': 'INDEPENDENT'}, [answer([], '讨论')])
    await runner.prepare_run(value)
    changed = value.model_copy(update={'content': '另一条消息'})
    with pytest.raises(ValueError, match='固定输入'):
        await runner.prepare_run(changed)
    result = await runner.execute_run(value, identity=value.identity)
    changed_identity = value.identity.model_copy(update={'execution_id': str(uuid.uuid4())})
    with pytest.raises(ValueError, match='执行绑定'):
        await runner.execute_run(value, identity=changed_identity)
    assert (await runner.inspect(result['thread_id']))['usage']['model_calls'] == 2


@pytest.mark.asyncio
async def test_unknown_entry_submission_is_never_paid_again(tmp_path):
    value = message()
    calls = []
    def lost(ctx):
        calls.append(ctx)
        raise ConnectionError('response lost')
    runner = agent(tmp_path, lost, [answer([], '讨论')])
    with pytest.raises(ConnectionError):
        await runner.prepare_run(value)
    recovered = await runner.prepare_run(value)
    assert recovered['status'] == 'INCOMPLETE'
    assert '不确定' in recovered['outcome']['content']
    assert len(calls) == 1
    with pytest.raises(ValueError, match='固定输入理解'):
        await runner.execute_run(value, identity=value.identity)


@pytest.mark.asyncio
async def test_completed_understanding_receipt_survives_crash_before_checkpoint(tmp_path, monkeypatch):
    from agent_service.runtime.persistence import Store
    value = message()
    calls = []
    def understand(ctx):
        calls.append(ctx)
        return {'relation': 'CONTINUE', 'mode': 'DISCUSS', 'conversation_relation': 'INDEPENDENT'}
    runner = agent(tmp_path, understand, [answer([], '讨论')])
    original = Store.received
    def crash(self, call_id, response):
        original(self, call_id, response)
        raise RuntimeError('crash after receipt')
    monkeypatch.setattr(Store, 'received', crash)
    with pytest.raises(RuntimeError):
        await runner.prepare_run(value)
    monkeypatch.setattr(Store, 'received', original)
    recovered = await runner.prepare_run(value)
    assert recovered['status'] == 'PARSED'
    assert len(calls) == 1
    assert (await runner.execute_run(value, identity=value.identity))['usage']['model_calls'] == 2


@pytest.mark.asyncio
async def test_frozen_production_keeps_history_and_new_production_starts_with_defaults(tmp_path):
    from test_java_executor import READ
    from test_real_production import ProductionBackend, DRAFT
    backend = ProductionBackend()
    first = message('做一个关闭字幕的剪辑')
    scope = fixed_scope(backend, first)
    production = {'project_id': first.identity.project_id, 'requirement_id': str(uuid.uuid4()), 'scope_id': scope['scope_id']}
    runner = real_runner(tmp_path, backend, first, {'relation': 'NEW', 'mode': 'DRAFT', 'conversation_relation': 'NEW_PRODUCTION', 'subtitles_enabled': False}, [READ, DRAFT])
    await runner.prepare_run(first)
    original = await runner.execute_run(first, identity=first.identity, scope=scope, production=production, edit_generation=1)
    frozen = summary(original, frozen=True)  # Java owns the authoritative freeze; no model call to freeze.
    query = message('聊聊剪辑节奏', currentProduction=frozen)
    query.identity.project_id = first.identity.project_id
    query.identity.conversation_id = first.identity.conversation_id
    runner = real_runner(tmp_path, backend, query, {'relation': 'CONTINUE', 'mode': 'DISCUSS', 'conversation_relation': 'INDEPENDENT'}, [answer([], '可以加快开头的节奏。')])
    await runner.prepare_run(query)
    discussed = await runner.execute_run(query, identity=query.identity, scope=scope)
    assert discussed['status'] == 'ANSWER'
    assert (await runner.inspect(original['thread_id']))['validation'] == original['validation']
    new = message('另做一个新剪辑', currentProduction=frozen)
    new.identity.project_id = first.identity.project_id
    new.identity.conversation_id = first.identity.conversation_id
    runner = real_runner(tmp_path, backend, new, {'relation': 'NEW', 'mode': 'DRAFT', 'conversation_relation': 'NEW_PRODUCTION'}, [READ, DRAFT])
    await runner.prepare_run(new)
    new_production = {**production, 'requirement_id': str(uuid.uuid4())}
    new_identity = new.identity.model_copy(update={'thread_id': str(uuid.uuid4())})
    result = await runner.execute_run(new, identity=new_identity, scope=scope, production=new_production, edit_generation=1)
    assert result['status'] == 'DRAFT_READY'
    assert result['thread_id'] == new_identity.thread_id
    assert result['requirements']['subtitles_enabled'] is True
    assert result['requirements']['sources']['subtitles_enabled']['kind'] == 'DEFAULT'
    assert (await runner.inspect(original['thread_id']))['requirements'] == original['requirements']
    assert (await runner.execute_run(first, identity=first.identity, scope=scope, production=production, edit_generation=1))['outcome'] == original['outcome']
    assert len(backend.validations) == 2


@pytest.mark.asyncio
async def test_production_edit_uses_stable_thread_new_execution_and_real_message_source(tmp_path):
    from test_java_executor import READ
    from test_real_production import ProductionBackend, DRAFT
    backend = ProductionBackend()
    first = message('生成草稿')
    scope = fixed_scope(backend, first)
    production = {'project_id': first.identity.project_id, 'requirement_id': str(uuid.uuid4()), 'scope_id': scope['scope_id']}
    runner = real_runner(tmp_path, backend, first, {'relation': 'NEW', 'mode': 'DRAFT', 'conversation_relation': 'NEW_PRODUCTION'}, [READ, DRAFT])
    await runner.prepare_run(first)
    original = await runner.execute_run(first, identity=first.identity, scope=scope, production=production, edit_generation=1)
    edit = message('关掉字幕', currentProduction=summary(original))
    edit.identity.project_id = first.identity.project_id
    edit.identity.conversation_id = first.identity.conversation_id
    runner = real_runner(tmp_path, backend, edit, {'relation': 'CONTINUE', 'mode': 'DRAFT', 'conversation_relation': 'CONTINUE_PRODUCTION', 'subtitles_enabled': False}, [DRAFT])
    await runner.prepare_run(edit)
    identity = edit.identity.model_copy(update={'thread_id': original['thread_id']})
    result = await runner.execute_run(edit, identity=identity, scope=scope, production=production, edit_generation=2)
    assert result['status'] == 'DRAFT_READY'
    assert result['requirements']['sources']['subtitles_enabled']['message_id'] == edit.identity.message_id
    assert result['validation']['validation_id'] != original['validation']['validation_id']
    assert result['usage']['model_calls'] == 2
    replay = await runner.execute_run(first, identity=first.identity, scope=scope, production=production, edit_generation=1)
    assert replay['requirements']['subtitles_enabled'] is True
    assert (await runner.inspect(identity.thread_id))['requirements']['subtitles_enabled'] is False
    assert (await runner.prepare_run(first))['parsed_input']['relation'] == 'NEW_PRODUCTION'
    assert len(backend.validations) == 2


@pytest.mark.asyncio
async def test_failed_edit_then_query_does_not_restore_old_draft_eligibility(tmp_path):
    from test_java_executor import READ, ANSWER
    from test_real_production import ProductionBackend, DRAFT, runner
    backend = ProductionBackend()
    first = await runner(tmp_path, backend, ScriptedModel({'relation': 'NEW', 'mode': 'DRAFT'}, [READ, DRAFT])).send('生成草稿')
    failed = await runner(tmp_path, backend, ScriptedModel({'relation': 'CONTINUE', 'mode': 'DRAFT'}, [answer([], '无法满足修改')])).send(
        '换一种叙事', thread_id=first['thread_id'])
    assert failed['status'] == 'INCOMPLETE'
    queried = await runner(tmp_path, backend, ScriptedModel({'relation': 'CONTINUE', 'mode': 'QUERY'}, [ANSWER])).send(
        '画面是什么', thread_id=first['thread_id'])
    assert queried['status'] == 'ANSWER'
    with pytest.raises(ValueError, match='DRAFT_READY'):
        await runner(tmp_path, backend, None).save(first['thread_id'])
    assert not backend.saves


@pytest.mark.asyncio
async def test_unclear_relationship_waits_without_touching_production(tmp_path):
    value = message('就这样吧')
    runner = agent(tmp_path, {'relation': 'AMBIGUOUS', 'mode': 'DISCUSS', 'conversation_relation': 'CLARIFY'}, [answer([], '讨论')])
    prepared = await runner.prepare_run(value)
    assert prepared['parsed_input']['relation'] == 'CLARIFY'
    waiting = await runner.execute_run(value, identity=value.identity)
    assert waiting['status'] == 'WAITING'
    assert waiting['production'] is None
    assert waiting['usage']['model_calls'] == 1


@pytest.mark.asyncio
async def test_malformed_relation_is_repaired_within_original_entry_budget(tmp_path):
    value = message()
    def parse(ctx):
        return {'relation': 'CONTINUE', 'mode': 'DISCUSS', **(
            {'conversation_relation': 'INDEPENDENT'} if ctx['feedback'] else {})}
    runner = agent(tmp_path, parse, [answer([], '讨论')])
    prepared = await runner.prepare_run(value)
    assert prepared['usage']['model_calls'] == 2
    result = await runner.execute_run(value, identity=value.identity)
    assert result['usage']['model_calls'] == 3
    assert result['usage']['repairs'] == 1


@pytest.mark.asyncio
@pytest.mark.parametrize('waiting', [False, True])
async def test_result_handoff_replays_after_checkpoint_before_local_result_record(tmp_path, monkeypatch, waiting):
    from agent_service.runtime.persistence import Store
    value = message()
    parsed = {'relation': 'CONTINUE', 'mode': 'DISCUSS', 'conversation_relation': 'INDEPENDENT'}
    if waiting:
        parsed['question'] = '具体讨论哪个方面？'
    runner = agent(tmp_path, parsed, [answer([], '讨论')])
    await runner.prepare_run(value)
    original = Store.record_run
    def crash(self, task_id, *, prepared=None, result=None):
        if result:
            raise RuntimeError('crash before recording handoff')
        original(self, task_id, prepared=prepared)
    monkeypatch.setattr(Store, 'record_run', crash)
    with pytest.raises(RuntimeError, match='handoff'):
        await runner.execute_run(value, identity=value.identity)
    monkeypatch.setattr(Store, 'record_run', original)
    restarted = agent(tmp_path, parsed, [answer([], '讨论')])
    result = await restarted.execute_run(value, identity=value.identity)
    assert result['status'] == ('WAITING' if waiting else 'ANSWER')
    assert result['usage']['model_calls'] == (1 if waiting else 2)
    assert (await restarted.execute_run(value, identity=value.identity))['usage']['model_calls'] == result['usage']['model_calls']


@pytest.mark.asyncio
async def test_authorized_continuation_replaces_cancelled_wait_without_consuming_question(tmp_path):
    from test_real_production import ProductionBackend, DRAFT
    from test_java_executor import READ
    first = message('制作短片')
    backend = ProductionBackend()
    scope = fixed_scope(backend, first)
    production = {'project_id': first.identity.project_id, 'requirement_id': str(uuid.uuid4()), 'scope_id': scope['scope_id']}
    runner = real_runner(tmp_path, backend, first, {'relation': 'NEW', 'mode': 'DRAFT',
        'conversation_relation': 'NEW_PRODUCTION', 'question': '要字幕吗？'}, [READ, DRAFT])
    await runner.prepare_run(first)
    waiting = await runner.execute_run(first, identity=first.identity, scope=scope, production=production, edit_generation=1)
    assert waiting['status'] == 'WAITING'
    # Java has cancelled the public question and authorized a fresh modification generation.
    followup = message('继续修改，不要字幕', currentProduction=summary(waiting), pendingQuestions=[])
    followup.identity.project_id = first.identity.project_id
    followup.identity.conversation_id = first.identity.conversation_id
    runner = real_runner(tmp_path, backend, followup, {'relation': 'CONTINUE', 'mode': 'DRAFT',
        'conversation_relation': 'CONTINUE_PRODUCTION', 'subtitles_enabled': False}, [READ, DRAFT])
    await runner.prepare_run(followup)
    identity = followup.identity.model_copy(update={'thread_id': first.identity.thread_id})
    result = await runner.execute_run(followup, identity=identity, scope=scope, production=production, edit_generation=2)
    assert result['status'] == 'DRAFT_READY'
    assert result['identity']['execution_id'] == followup.identity.execution_id
    assert result['requirements']['subtitles_enabled'] is False
    assert result['edit_generation'] == 2


@pytest.mark.asyncio
@pytest.mark.parametrize('has_checkpoint', [True, False])
async def test_continuation_preserves_java_conditions_when_previous_execution_never_applied_them(tmp_path, has_checkpoint):
    from agent_service.runtime.state import Requirements
    from test_real_production import ProductionBackend, DRAFT
    from test_java_executor import READ
    first = message('制作短片')
    backend = ProductionBackend()
    scope = fixed_scope(backend, first)
    production = {'project_id': first.identity.project_id, 'requirement_id': str(uuid.uuid4()), 'scope_id': scope['scope_id']}
    thread = str(uuid.uuid4())
    if has_checkpoint:
        runner = real_runner(tmp_path, backend, first, {'relation': 'NEW', 'mode': 'DRAFT',
            'conversation_relation': 'NEW_PRODUCTION', 'question': '要字幕吗？'}, [READ, DRAFT])
        await runner.prepare_run(first)
        waiting = await runner.execute_run(first, identity=first.identity, scope=scope, production=production, edit_generation=1)
        current = summary(waiting)
        thread = waiting['thread_id']
    else:
        current = {'productionId': production['requirement_id'], 'scopeId': scope['scope_id'], 'threadId': thread,
            'editGeneration': 1, 'requirements': Requirements().production(['a']),
            'requirementsSources': Requirements().sources, 'currentDraft': None, 'frozen': False}
    # Java committed the previous turn's conditions; cancellation happened before the core applied them.
    current['editGeneration'] = 2
    current['requirements']['subtitles_enabled'] = False
    current['requirementsSources']['subtitles_enabled'] = {'kind': 'USER', 'message_id': first.identity.message_id, 'value': False}
    followup = message('修改主题', currentProduction=current)
    followup.identity.project_id = first.identity.project_id
    followup.identity.conversation_id = first.identity.conversation_id
    runner = real_runner(tmp_path, backend, followup, {'relation': 'CONTINUE', 'mode': 'DRAFT',
        'conversation_relation': 'CONTINUE_PRODUCTION', 'topic': '新主题'}, [READ, DRAFT])
    await runner.prepare_run(followup)
    identity = followup.identity.model_copy(update={'thread_id': thread})
    result = await runner.execute_run(followup, identity=identity, scope=scope, production=production, edit_generation=3)
    assert result['status'] == 'DRAFT_READY'
    assert result['requirements']['subtitles_enabled'] is False
    assert result['requirements']['topic'] == '新主题'

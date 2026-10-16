"""Explicit controlled model/fault process used by the Java MQ integration test only."""
import asyncio
import os
from pathlib import Path
from typing import cast

from agent_service.config import Config
from agent_service.model import ScriptedModel
from agent_service.runtime.persistence import Store
from agent_service.worker import consume


def understanding(ctx):
    text = ctx['request']['content']
    if text == 'continue':
        return {'relation': 'CONTINUE', 'mode': 'DRAFT', 'conversation_relation': 'CONTINUE_PRODUCTION', 'topic': '新主题'}
    if text == 'make-hold':
        return {'relation': 'NEW', 'mode': 'DRAFT', 'conversation_relation': 'NEW_PRODUCTION', 'topic': '主题', 'subtitles_enabled': False}
    if text == 'make-wait':
        return {'relation': 'NEW', 'mode': 'DRAFT', 'conversation_relation': 'NEW_PRODUCTION', 'topic': '主题', 'question': '哪种节奏？'}
    if text == 'make':
        return {'relation': 'NEW', 'mode': 'DRAFT', 'conversation_relation': 'NEW_PRODUCTION', 'topic': '主题'}
    if text == 'reply':
        return {'relation': 'CONTINUE', 'mode': 'DISCUSS', 'conversation_relation': 'ANSWER_QUESTION',
                'question_id': ctx['request']['public_context']['pendingQuestions'][0]['questionId']}
    return {'relation': 'CONTINUE', 'mode': 'DISCUSS', 'conversation_relation': 'INDEPENDENT',
            'question': '哪种节奏？' if text == 'wait' else None}


def actions(ctx):
    if ctx['intent'] == 'DRAFT':
        asset = ctx['available_assets'][0]['id']
        if not ctx['observations']:
            return {'kind': 'TOOL', 'tool_name': 'read_video_range',
                    'arguments': {'asset_id': asset, 'start_ms': 0, 'end_ms': 5000}}
        return {'kind': 'DRAFT', 'draft': {'title': '主题', 'outputs': [
            {'output_key': 'out-1', 'title': '主片', 'clips': [{'asset_id': asset,
                'source_in_ms': 0, 'source_out_ms': 5000, 'evidence_ids': list(ctx['observations']), 'reason': '按原文开始'}]}]}}
    return {'kind': 'ANSWER', 'content': '可以先确定故事主线。', 'evidence_ids': []}


class ControlledModel(ScriptedModel):
    async def invoke(self, phase, context, config):
        if context['request']['content'] == 'hold' and phase == 'decide':
            Path(os.environ['AGENT_DATA_DIRECTORY'], 'holding').touch()
            await asyncio.Event().wait()
        reply = await super().invoke(phase, context, config)
        return reply


if __name__ == '__main__':
    config = Config(trace=False)
    # Crash immediately after a durable paid receipt, before the graph checkpoint advances.
    received = Store.received
    def crash_after_receipt(self, call_id, response):
        received(self, call_id, response)
        if os.environ.get('CRASH_AFTER_RECEIPT') == '1':
            marker = config.directory / 'crashed'
            if not marker.exists():
                marker.touch()
                os._exit(74)
        if os.environ.get('FAIL_AFTER_RECEIPT') == '1':
            marker = config.directory / 'failed'
            if not marker.exists():
                marker.touch()
                raise OSError('controlled interruption after durable receipt')
    Store.received = crash_after_receipt
    from agent_service.run_transport import RunToolExecutor
    request = RunToolExecutor._request
    async def fail_after_validation(self, method, path, body=None):
        response = await request(self, method, path, body)
        marker = config.directory / 'validation-interrupted'
        if os.environ.get('FAIL_AFTER_VALIDATION') == '1' and cast(dict, body)['tool_name'] == 'validate_edit_draft' and not marker.exists():
            marker.touch()
            raise OSError('controlled interruption after Java validation commit')
        return response
    RunToolExecutor._request = fail_after_validation
    snapshot = RunToolExecutor.snapshot
    async def hold_before_execution(self, saved=None):
        marker = config.directory / 'before-execution'
        if os.environ.get('HOLD_BEFORE_EXECUTE') == '1' and not marker.exists():
            marker.touch()
            await asyncio.Event().wait()
        return await snapshot(self, saved)
    RunToolExecutor.snapshot = hold_before_execution
    import aio_pika
    ack = aio_pika.IncomingMessage.ack
    async def lose_ack_once(self, multiple=False):
        if os.environ.get('DROP_ACK') == '1':
            marker = config.directory / 'ack-lost'
            if not marker.exists():
                marker.touch()
                await self.channel.close()
                raise ConnectionError('controlled connection loss before broker ACK')
            await ack(self, multiple=multiple)
            (config.directory / 'ack-replayed').touch()
            return
        await ack(self, multiple=multiple)
    aio_pika.IncomingMessage.ack = lose_ack_once
    asyncio.run(consume(config, model=ControlledModel(understanding, actions)))

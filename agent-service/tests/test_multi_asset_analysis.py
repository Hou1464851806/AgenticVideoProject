import json

import httpx
import pytest

from agent_service.config import Config
from agent_service.model import ScriptedModel
from agent_service.runtime.runner import Agent
from agent_service.tools.java import JavaToolExecutor
from test_java_executor import ASSET, POINT, Backend, UNDERSTAND


class MultiAssetBackend(Backend):
    def handle(self, req):
        response = super().handle(req)
        if req.url.path.endswith('/scopes'):
            scope = response.json()
            scope['assets'].append({**ASSET, 'id': 'b'})
            self.scopes[scope['scope_id']] = scope
            return httpx.Response(201, json=scope)
        if not req.url.path.endswith('/tools'):
            return response
        body = json.loads(req.content)
        args = body['arguments']
        asset, cursor = args['asset_id'], args.get('cursor', 0)
        value = response.json()
        if body['tool_name'] == 'get_video_overview':
            value['records'] = []
            value['data'] = {'entries': [{'excerpt': f'{asset}内容{i}', 'observed_at_ms': i * 1000,
                                         'navigation_ids': [f'{asset}-{i}']} for i in range(4)],
                             'next_cursor': None}
        else:
            assert body['tool_name'] == 'read_video_range'
            value['records'] = [{**POINT, 'id': f'{asset}-{cursor}', 'asset_id': asset,
                                 'observed_at_ms': cursor * 1000, 'text': f'{asset}内容{cursor}'}]
            value['data'] = {'next_cursor': cursor + 1 if cursor < 3 else None}
        return httpx.Response(200, json=value)


def overview(asset):
    return {'kind': 'TOOL', 'tool_name': 'get_video_overview', 'arguments': {'asset_id': asset}}


def read(asset, cursor):
    return {'kind': 'TOOL', 'tool_name': 'read_video_range',
            'arguments': {'asset_id': asset, 'start_ms': 0, 'end_ms': 10000, 'cursor': cursor}}


@pytest.mark.asyncio
async def test_historical_two_asset_trace_has_room_for_citation_repair(tmp_path):
    backend = MultiAssetBackend()
    answer = {'kind': 'ANSWER', 'content': '分别分析：a与b各有四段已读内容。', 'evidence_ids': ['a-3', 'b-3']}
    actions = [overview('a'), overview('b'), read('a', 0), read('a', 1), read('b', 0), overview('a'),
               read('a', 2), read('a', 3), answer, read('b', 1), read('b', 2), read('b', 3), answer]
    executor = JavaToolExecutor('http://java', 'private-token', 'p', transport=httpx.MockTransport(backend.handle))
    result = await Agent(Config(tmp_path, trace=False), ScriptedModel(UNDERSTAND, actions), executor=executor).send('分别分析这两条素材')
    assert result['status'] == 'ANSWER'
    assert result['usage']['model_calls'] == 14
    assert result['usage']['repairs'] == 1
    assert {r['asset_id'] for r in result['outcome']['sources']} == {'a', 'b'}


@pytest.mark.asyncio
async def test_reading_progress_guides_both_assets_to_answer_without_repeated_overview(tmp_path):
    backend = MultiAssetBackend()
    def actions(ctx):
        assert ctx['budget']['model_calls_remaining'] > 0
        for asset in ('a', 'b'):
            progress = ctx['reading']['assets'][asset]
            if not progress['overview_complete']:
                return overview(asset)
            pages = [p for p in ctx['reading']['next_pages'] if p['asset_id'] == asset]
            if pages:
                return {'kind': 'TOOL', 'tool_name': 'read_video_range', 'arguments': pages[0]}
            if not progress['complete_ranges']:
                return read(asset, 0)
        assert 'navigation_ids' not in json.dumps(ctx)
        return {'kind': 'ANSWER', 'content': '分别分析：a与b均完成已有记录读取。', 'evidence_ids': ['a-3', 'b-3']}
    executor = JavaToolExecutor('http://java', 'private-token', 'p', transport=httpx.MockTransport(backend.handle))
    result = await Agent(Config(tmp_path, trace=False), ScriptedModel(UNDERSTAND, actions), executor=executor).send('分别分析这两条素材')
    assert result['status'] == 'ANSWER'
    assert result['usage']['model_calls'] == 12
    assert result['usage']['repairs'] == 0
    tools = [c[2]['tool_name'] for c in backend.calls if c[1].endswith('/tools')]
    assert tools.count('get_video_overview') == 2
    assert tools.count('read_video_range') == 8

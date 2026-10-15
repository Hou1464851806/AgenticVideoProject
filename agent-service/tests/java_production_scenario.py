"""Run by DraftValidationIntegrationTest against its isolated real HTTP server/DB."""
import asyncio
import copy
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent_service.config import Config
from agent_service.model import ScriptedModel
from agent_service.runtime.runner import Agent
from agent_service.tools.java import JavaToolExecutor


async def scenario(fixture):
    with tempfile.TemporaryDirectory(prefix='m2-production-') as directory:
        root = Path(directory)
        trace = []
        a, b = fixture['assets']
        def clip(asset, start, end):
            return {'asset_id': asset['id'], 'source_in_ms': start, 'source_out_ms': end,
                    'evidence_ids': [asset['evidence']], 'reason': '保留原始语音'}
        valid = {'kind': 'DRAFT', 'draft': {'title': '真实 Java 受控制作', 'outputs': [
            {'output_key': 'out-1', 'title': '组合', 'clips': [clip(a, 2000, 6000), clip(b, 3000, 5000)]},
            {'output_key': 'out-2', 'title': '原声', 'clips': [clip(a, 2000, 6000)]}]}}
        invalid = copy.deepcopy(valid)
        invalid['draft']['outputs'][0]['clips'][0]['source_out_ms'] = 5999
        def decide(ctx):
            trace.append({'draft': ctx['draft'], 'validation': ctx['validation'], 'usage': ctx['usage']})
            step = ctx['usage']['decisions']
            if step in (1, 2):
                asset, start, end = (a, 2000, 6000) if step == 1 else (b, 3000, 5000)
                assert ctx['validation']['valid'] is False
                return {'kind': 'TOOL', 'tool_name': 'read_video_range', 'arguments': {
                    'asset_id': asset['id'], 'start_ms': start, 'end_ms': end}}
            return invalid if step in (0, 3) else valid
        lost_validation = []
        class FaultAfterResponse(JavaToolExecutor):
            async def execute(self, request):
                result = await super().execute(request)
                if request.tool_name == 'validate_edit_draft':
                    lost_validation.append(result.data)
                    raise RuntimeError('process lost after durable Java validation')
                return result
        def agent(model=None, crash=False):
            return Agent(Config(root, trace=False), model=model, executor=(FaultAfterResponse if crash else JavaToolExecutor)(
                fixture['url'], fixture['token'], fixture['project'], [a['id'], b['id']]))
        first = await agent(ScriptedModel({'relation': 'CONTINUE', 'mode': 'DRAFT', 'output_count': 2,
            'output_durations': [{'output_key': 'out-1', 'duration_min_ms': 6000, 'duration_max_ms': 6000},
                                 {'output_key': 'out-2', 'duration_min_ms': 4000, 'duration_max_ms': 4000}]}, decide)).send('组合两条，分别6秒和4秒')
        assert first['status'] == 'DRAFT_READY', first
        assert first['validation']['durations_ms'] == [6000, 4000]
        assert first['validation']['valid'] is True
        issues = [i['code'] for t in trace if t['validation'] for i in t['validation']['issues']]
        assert 'CONTEXT_NOT_READ' in issues and 'CUT_INSIDE_WORD' in issues, issues
        assert first['usage']['repairs'] == 2
        executor = agent().executor
        assert await executor.saved(first['thread_id']) is None
        modified = copy.deepcopy(valid)
        modified['draft']['outputs'][1]['clips'][0]['source_out_ms'] = 4000
        modified['draft']['outputs'].reverse()
        edit_agent = agent(ScriptedModel({'relation': 'CONTINUE', 'mode': 'DRAFT',
            'output_durations': [{'output_key': 'out-2', 'duration_min_ms': 2000, 'duration_max_ms': 2000}]},
            [modified]), crash=True)
        try:
            await edit_agent.send('第二条改为2秒并排在前面', thread_id=first['thread_id'])
        except RuntimeError as error:
            assert str(error) == 'process lost after durable Java validation'
        else:
            raise AssertionError('Expected process fault')
        second = await agent(ScriptedModel({}, [])).resume(first['thread_id'])
        assert second['validation'] == lost_validation[0]
        assert second['status'] == 'DRAFT_READY', second
        assert second['question_scope'] == first['question_scope']
        assert second['requirements']['outputs'][0]['duration_min_ms'] == 6000
        assert second['validation']['durations_ms'] == [2000, 6000]
        assert second['validation']['validation_id'] != first['validation']['validation_id']
        # Exercise the actual explicit CLI without a model key or script.
        environment = {**os.environ, 'INTERNAL_WORKER_TOKEN': fixture['token']}
        environment.pop('DEEPSEEK_API_KEY', None)
        command = [sys.executable, '-m', 'agent_service', '--java-url', fixture['url'], '--data-dir', directory,
                   '--no-trace', 'save', first['thread_id']]
        proc = await asyncio.create_subprocess_exec(*command, cwd=Path(__file__).resolve().parents[1], env=environment,
            stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        stdout, stderr = await proc.communicate()
        assert proc.returncode == 0, stderr.decode()
        saved = json.loads(stdout)
        assert saved['status'] == 'SAVED', saved
        again = await agent().save(first['thread_id'])
        assert again['saved_result'] == saved['saved_result']
        readonly = await agent(ScriptedModel({'relation': 'CONTINUE', 'mode': 'DRAFT'}, [invalid])).send(
            '再修改', thread_id=first['thread_id'])
        assert readonly['status'] == 'SAVED'
        assert readonly['saved_result'] == saved['saved_result']
        print(json.dumps({'first': first, 'modified': second, 'saved': saved, 'trace': trace}, ensure_ascii=False))


if __name__ == '__main__':
    asyncio.run(scenario(json.load(sys.stdin)))

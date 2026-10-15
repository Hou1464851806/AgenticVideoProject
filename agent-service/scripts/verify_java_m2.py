"""M2 live CLI acceptance using existing M1 analysis; never uploads or analyses.

Runs one named case per invocation. A fresh output directory prevents accidental
paid reruns. Failed CLI results are saved before acceptance assertions run.
Requires the local Compose PostgreSQL service for authoritative receipts.
"""
import argparse
import json
from pathlib import Path
import sqlite3
import subprocess
import sys
from uuid import UUID


def database(sql):
    result = subprocess.run(
        ['docker', 'compose', 'exec', '-T', 'postgres', 'psql', '-U',
         'agentic_video', '-d', 'agentic_video', '-At', '-v', 'ON_ERROR_STOP=1', '-c', sql],
        check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def main(args):
    root = Path(__file__).resolve().parents[2]
    samples = json.loads(args.samples.read_text())['samples']
    by_name = {sample['fixture']: sample['scope'] for sample in samples}
    names = {
        'single': ['labelled-speech.mp4'],
        'multi': ['labelled-speech.mp4', 'human-speech.mp4'],
        'multi-single': ['labelled-speech.mp4', 'human-speech.mp4'],
        'single-multi': ['labelled-speech.mp4'],
        'visual': ['labelled-vision-vfr.mp4'],
        'impossible': ['labelled-speech.mp4'],
    }[args.case]
    project = str(UUID(by_name[names[0]]['project_id']))
    assets = [str(UUID(by_name[name]['assets'][0]['id'])) for name in names]
    assert all(by_name[name]['project_id'] == project for name in names)
    args.output.mkdir(parents=True, exist_ok=False)
    # Count persisted baseline submissions, not just task status.
    baseline_sql = f"SELECT jsonb_build_object('analysis_calls', (SELECT count(*) FROM model_calls m JOIN tasks t ON t.id=m.task_id WHERE t.project_id='{project}'), 'vision_calls', (SELECT count(*) FROM vision_model_calls m JOIN tasks t ON t.id=m.task_id WHERE t.project_id='{project}'), 'embedding_calls', (SELECT count(*) FROM embedding_batches b JOIN search_index_generations g ON g.id=b.generation_id WHERE g.project_id='{project}'), 'analysis_heads', (SELECT jsonb_agg(to_jsonb(h) ORDER BY h.asset_id) FROM asset_analysis_heads h JOIN assets a ON a.id=h.asset_id WHERE a.project_id='{project}'))"
    before = database(baseline_sql)
    base = [sys.executable, '-m', 'agent_service', '--java-url', args.url,
            '--data-dir', str(args.output.resolve() / 'agent'), '--no-trace', '--project', project]
    for asset in assets:
        base += ['--asset', asset]
    prompt = {
        'single': '生成剪辑',
        'multi': '用这两个素材生成两条剪辑。第一条将上传核查流程和告别开始的语音跨素材拼接，第二条展示上传、核查、导出三个完整步骤。保留完整原声语句，不指定硬时长。',
        'multi-single': '用两个素材生成一条剪辑，将上传核查流程和告别开始的语音跨素材拼接。保留完整原声语句，不指定硬时长。',
        'single-multi': '用当前素材生成两条剪辑，第一条介绍上传素材，第二条介绍核查原文和导出。保留完整原声语句，不指定硬时长。',
        'visual': '生成一条展示红色和蓝色矩形、证据42及画面17的剪辑，保留已有视觉观察依据。竖屏，关闭字幕，不指定硬时长；稀疏采样不代表全片覆盖。',
        'impossible': '只用当前素材生成严格60秒的单条剪辑，不允许重复重叠区间，不变速不循环。请实际提出草稿接受校验；无法满足就明确未完成，保留真实问题，不放宽60秒条件。',
    }[args.case]
    commands = [['run', prompt]]
    runs = []
    try:
        for index, command in enumerate(commands):
            process = subprocess.run(base + command, cwd=root / 'agent-service', capture_output=True, text=True)
            (args.output / f'{index}-stderr.log').write_text(process.stderr)
            result = json.loads(process.stdout)
            runs.append({'command': command, 'exit_code': process.returncode, 'result': result})
            (args.output / 'runs.json').write_text(json.dumps(runs, ensure_ascii=False, indent=2))
            print(json.dumps({'case': args.case, 'step': index, 'status': result['status'],
                              'thread_id': result.get('thread_id')}, ensure_ascii=False), flush=True)
            if index == 0:
                assert result['status'] in ('DRAFT_READY', 'INCOMPLETE'), result
                assert result['outcome']['mock'] is False, result
                thread = result['thread_id']
                if args.case == 'impossible':
                    assert result['status'] == 'INCOMPLETE', result
                    assert result['requirements']['outputs'][0]['duration_min_ms'] == 60000
                    assert result['requirements']['outputs'][0]['duration_max_ms'] == 60000
                    break
                assert result['status'] == 'DRAFT_READY', result
                assert result['validation']['valid'] is True
                assert len(result['draft']['outputs']) == (2 if args.case in ('multi', 'single-multi') else 1)
                if args.case == 'multi-single':
                    assert {clip['asset_id'] for clip in result['draft']['outputs'][0]['clips']} == set(assets)
                if args.case == 'single':
                    requirements = result['requirements']
                    assert requirements['outputs'] == [{'output_key': 'out-1', 'duration_min_ms': None, 'duration_max_ms': None}]
                    assert requirements['profile'] == 'LANDSCAPE_720' and requirements['subtitles_enabled'] is True
                    assert requirements['sources']['profile']['kind'] == 'DEFAULT'
                if args.case == 'multi':
                    assert {clip['asset_id'] for clip in result['draft']['outputs'][0]['clips']} == set(assets)
                    assert len(result['draft']['outputs']) == 2
                    commands.append(['run', '第二条改为严格4秒，只保留上传与核查两句完整原声，去掉导出句，标题改为四秒流程，并排在第一条前面；第一条内容和时长条件保持不变。', '--thread', thread])
                commands.extend([['status', thread], ['save', thread], ['save', thread], ['resume', thread]])
            elif command[0] == 'run':
                assert result['status'] == 'DRAFT_READY', result
                assert result['question_scope'] == runs[0]['result']['question_scope']
                assert result['validation']['validation_id'] != runs[0]['result']['validation']['validation_id']
                previous = runs[0]['result']
                assert result['requirements']['outputs'][0] == previous['requirements']['outputs'][0]
                assert result['draft']['outputs'][0]['output_key'] == 'out-2'
                assert result['validation']['durations_ms'][0] == 4000
                assert result['draft']['outputs'][1] == previous['draft']['outputs'][0]
            elif command[0] in ('save', 'resume'):
                assert result['status'] == 'SAVED', result
                saved = [run['result']['saved_result'] for run in runs if run['command'][0] == 'save']
                assert all(value == result['saved_result'] for value in saved)
    finally:
        if runs and runs[0]['result'].get('question_scope'):
            scope = str(UUID(runs[0]['result']['question_scope']['scope_id']))
            production = str(UUID(runs[0]['result']['identity']['requirement_id']))
            evidence = database(f"SELECT jsonb_build_object('tools', (SELECT jsonb_agg(to_jsonb(r) ORDER BY created_at,call_id) FROM question_tool_receipts r WHERE scope_id='{scope}'), 'validations', (SELECT jsonb_agg(to_jsonb(v) ORDER BY created_at) FROM edit_draft_validations v WHERE production_id='{production}'), 'versions', (SELECT jsonb_agg(to_jsonb(v)) FROM plan_revisions v WHERE source_production_id='{production}'))")
            after = database(baseline_sql)
            with sqlite3.connect(args.output / 'agent/agent.sqlite') as connection:
                calls = [{'id': row[0], 'execution_id': row[1], 'status': row[2], 'response': json.loads(row[3]) if row[3] is not None else None}
                         for row in connection.execute('SELECT id,execution_id,status,response FROM model_calls ORDER BY rowid')]
            (args.output / 'evidence.json').write_text(json.dumps(
                {'case': args.case, 'model': 'deepseek-flash', 'runs': runs, 'database': evidence,
                 'baseline_before': before, 'baseline_after': after, 'model_calls': calls}, ensure_ascii=False, indent=2))
    assert before == after, 'Acceptance must reuse unchanged baseline analysis'
    assert calls and all(call['status'] == 'RECEIVED' and call['response']['model'] == 'deepseek-flash' for call in calls)
    if args.case == 'impossible':
        assert evidence['versions'] is None
    else:
        assert evidence['validations'], 'Must exercise real Java validation'
        assert len(evidence['versions']) == 1
        if args.case == 'visual':
            output = evidence['versions'][0]['content_json']['outputs'][0]
            assert output['render']['profile'] == 'PORTRAIT_720'
            assert output['subtitles'] == {'enabled': False, 'cues': []}
    print('PASS', args.case, flush=True)


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--samples', type=Path, default=Path('docs/verification/java-tools-m1-stage5-samples.json'))
    parser.add_argument('--url', default='http://localhost:8080')
    parser.add_argument('--case', choices=['single', 'multi', 'multi-single', 'single-multi', 'visual', 'impossible'], required=True)
    parser.add_argument('--output', type=Path, required=True)
    main(parser.parse_args())

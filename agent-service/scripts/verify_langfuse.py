"""Read-only proof of actual observations; never prints configuration secrets."""
import argparse
from collections import Counter
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from agent_service.config import load_env
from langfuse import Langfuse
import os

parser = argparse.ArgumentParser()
parser.add_argument('results', nargs='+', type=Path)
parser.add_argument('--output', type=Path)
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
load_env(root / '.env.langfuse')
load_env(root.parent / '.env')
client = Langfuse(public_key=os.environ['LANGFUSE_PUBLIC_KEY'], secret_key=os.environ['LANGFUSE_SECRET_KEY'], base_url=os.environ['LANGFUSE_BASE_URL'])
report = []
for path in args.results:
    result = json.loads(path.read_text())
    observations = []
    cursor = None
    while True:
        page = client.api.observations.get_many(trace_id=result['trace_id'], fields='basic,io,metadata,usage,model', limit=500, cursor=cursor)
        observations.extend(page.data)
        cursor = page.meta.cursor
        if not cursor:
            break
    assert observations, 'No actual observations found'
    serialized = json.dumps([o.model_dump() for o in observations], default=str)
    for key in ('DEEPSEEK_API_KEY','LANGFUSE_SECRET_KEY','INTERNAL_WORKER_TOKEN'):
        assert not os.environ.get(key) or os.environ[key] not in serialized, 'Credential found in trace'
    generations = [o for o in observations if o.type == 'GENERATION']
    tools = [o for o in observations if o.type == 'TOOL']
    roots = [o for o in observations if o.type == 'AGENT']
    assert roots and roots[0].metadata['execution_id'] == result['identity']['execution_id']
    assert all(o.model == 'deepseek-flash' and o.usage_details for o in generations)
    mock = result.get('outcome', {}).get('mock', True)
    assert all(str(o.metadata.get('mock')).lower() == str(mock).lower() for o in tools)
    if not mock:
        assert all(o.metadata.get('call_id') and o.metadata.get('request_hash') for o in tools)
        assert all((json.loads(o.output) if isinstance(o.output, str) else o.output).get('mock') is False for o in tools)
        assert str(roots[0].metadata.get('mock')).lower() == 'false'
        assert all(o.metadata.get('question_scope_id') == result['question_scope']['scope_id'] for o in generations)
        def check_real(value):
            if isinstance(value, dict):
                assert value.get('mock') is not True, 'Mock marker found in real trace payload'
                for item in value.values():
                    check_real(item)
            elif isinstance(value, list):
                for item in value:
                    check_real(item)
        for observation in observations:
            for payload in (observation.input, observation.output):
                if isinstance(payload, str):
                    try:
                        payload = json.loads(payload)
                    except ValueError:
                        continue
                check_real(payload)
    report.append({'result_file':str(path), 'status':result['status'], 'trace_id':result['trace_id'],
                   'execution_id':result['identity']['execution_id'], 'observations':dict(Counter(o.type for o in observations)),
                   'generation_tokens':sum(o.usage_details.get('total',0) for o in generations),
                   'reused_responses':sum(o.name == 'model-response-reused' for o in observations),
                   'tools':[o.name for o in tools], 'credentials_absent':True,
                   'url':f"http://localhost:3035/project/agent-mvp-project/traces?peek={roots[0].id}&traceId={result['trace_id']}"})
text = json.dumps(report,ensure_ascii=False,indent=2)
if args.output:
    args.output.write_text(text+'\n')
else:
    print(text)

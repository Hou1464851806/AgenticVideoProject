"""Explicit real HTTP checkpoint verification; no new paid model calls."""
import argparse
import asyncio, json, os, subprocess, sys
from pathlib import Path
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--input',type=Path,default=Path('docs/verification/java-tools-m1-stage4-scripted.json'))
parser.add_argument('--data-dir',type=Path,required=True)
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
args.data_dir=args.data_dir.resolve()
args.data_dir.mkdir(parents=True,exist_ok=True)
root=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(root/'agent-service'))
from agent_service.config import Config, load_env
from agent_service.model import ScriptedModel
from agent_service.runtime.runner import Agent
from agent_service.tools.java import JavaToolExecutor
from agent_service.tools.contracts import ToolRequest
load_env(root/'.env')
run=json.loads((args.input).read_text())['runs'][1]
asset=run['result']['question_scope']['assets'][0]
project=run['result']['question_scope']['project_id']
ids=[s['id'] for s in run['result']['outcome']['sources']]
read={'kind':'TOOL','tool_name':'read_video_range','arguments':{'asset_id':asset['id'],'start_ms':0,'end_ms':asset['duration_ms']}}
answer={'kind':'ANSWER','content':'姓名张明，三个步骤为上传素材、核查原文、导出。','evidence_ids':ids}
script={'understanding':{'relation':'CONTINUE','mode':'QUERY','question':'要查哪些内容？'},'after_clarification':{'relation':'CONTINUE','mode':'QUERY'},'actions':[read,answer]}
script_path=args.data_dir/'clarify.json';script_path.write_text(json.dumps(script))
cmd=[str(Path(sys.executable)),'-m','agent_service','--data-dir',str(args.data_dir/'clarify'),'--java-url','http://localhost:8080','--project',project,'--asset',asset['id'],'--script',str(script_path),'--no-trace']
def cli(tail):
 p=subprocess.run(cmd+tail,cwd=root/'agent-service',capture_output=True,text=True,check=True)
 return json.loads(p.stdout)
first=cli(['run','询问视频'])
second=cli(['reply',first['thread_id'],'姓名和步骤'])
assert first['question_scope']==second['question_scope'] and first['identity']==second['identity']
assert second['status']=='ANSWER' and second['outcome']['mock'] is False
(args.data_dir/'recovery-script.json').write_text(json.dumps({'understanding':{'relation':'CONTINUE','mode':'QUERY'},'actions':[read,answer]}))
class LostAfterJava(JavaToolExecutor):
 async def execute(self,request):
  result=await super().execute(request)
  assert result.status=='READY'
  (args.data_dir/'before-crash.json').write_text(json.dumps({'request':request.model_dump(),'result':result.model_dump(),'scope_id':self.scope_id},ensure_ascii=False))
  raise RuntimeError('Injected process failure after Java receipt before Python feedback')
async def crash():
 executor=LostAfterJava('http://localhost:8080',os.environ['INTERNAL_WORKER_TOKEN'],project,[asset['id']])
 agent=Agent(Config(args.data_dir/'recovery',trace=False),ScriptedModel({'relation':'CONTINUE','mode':'QUERY'},[read,answer]),executor=executor)
 try:
  await agent.send('姓名和步骤',thread_id='stage4-recovery')
 except RuntimeError:
  pass
 return await agent.inspect('stage4-recovery')
crashed=asyncio.run(crash())
cmd=[str(Path(sys.executable)),'-m','agent_service','--data-dir',str(args.data_dir/'recovery'),'--java-url','http://localhost:8080','--script',str(args.data_dir/'recovery-script.json'),'--no-trace']
resumed=cli(['resume','stage4-recovery'])
assert resumed['status']=='ANSWER' and resumed['question_scope']==crashed['question_scope'] and resumed['identity']==crashed['identity']
before = json.loads((args.data_dir/'before-crash.json').read_text())
assert before['request'] == crashed['pending_tool']
async def replay():
 executor = JavaToolExecutor('http://localhost:8080',os.environ['INTERNAL_WORKER_TOKEN'])
 await executor.snapshot(crashed['question_scope'])
 return (await executor.execute(ToolRequest.model_validate(before['request']))).model_dump()
replayed = asyncio.run(replay())
assert replayed == before['result']
args.output.write_text(json.dumps({'date':'2026-10-15','clarification':{'first':first,'second':second},'recovery':{'crashed':crashed,'resumed':resumed,'java_receipt_before_crash':before, 'replayed_receipt':replayed}},ensure_ascii=False,indent=2))
print('Real HTTP: clarification and recovery in separate CLI processes, same snapshot and identities: passed')

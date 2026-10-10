import { execFileSync } from 'node:child_process';
import { mkdirSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, resolve } from 'node:path';

const root=resolve(dirname(fileURLToPath(import.meta.url)),'..');
try { process.loadEnvFile(resolve(root,'.env')); } catch { /* Missing credentials are reported below. */ }
if(!process.env.DASHSCOPE_API_KEY) {
  process.stderr.write('UNVERIFIED: 请在 .env 配置 DASHSCOPE_API_KEY 后运行真实模型测试。\n');
  process.exit(2);
}
const docker=process.env.DOCKER_BIN??'docker';
const directory=resolve(root,'.scratch/asr-fixture');
mkdirSync(directory,{recursive:true});
const user=process.getuid&&process.getgid?['--user',`${process.getuid()}:${process.getgid()}`]:[];
execFileSync(docker,['run','--rm',...user,'--entrypoint','ffmpeg',
  '-v',`${resolve(root,'frontend/e2e/fixtures')}:/fixtures:ro`,'-v',`${directory}:/output`,
  'agenticvideo-media-worker','-nostdin','-hide_banner','-loglevel','error','-i','/fixtures/labelled-speech.mp4',
  '-map','0:a:0','-vn','-ar','16000','-ac','1','-c:a','pcm_s16le','-y','/output/speech.wav'],{stdio:'inherit'});
execFileSync(docker,['run','--rm',...user,'-e','DASHSCOPE_API_KEY',
  '-e','ASR_TEST_AUDIO=/model-test/speech.wav','-e','ASR_TEST_RECEIPT=/model-test/live-receipt.json',
  '-v',`${directory}:/model-test`,'-v',`${resolve(root,'agent-service/tests')}:/tests:ro`,
  'agenticvideo-agent-service','python','-m','unittest','discover','-s','/tests'],{stdio:'inherit'});

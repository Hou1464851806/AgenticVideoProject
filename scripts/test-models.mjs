import { readFileSync } from 'node:fs';
import { spawnSync } from 'node:child_process';

// Explicit live Java acceptance. Credentials go only to the child environment.
const env = { ...process.env };
try {
  for (const line of readFileSync(new URL('../.env', import.meta.url), 'utf8').split(/\r?\n/)) {
    const match = line.match(/^\s*([A-Z_][A-Z_0-9]*)\s*=\s*(.*?)\s*$/);
    if (!match || env[match[1]] !== undefined) continue;
    let value = match[2];
    if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) value = value.slice(1, -1);
    env[match[1]] = value;
  }
} catch (error) {
  if (error.code !== 'ENOENT') throw error;
}
if (!env.DASHSCOPE_API_KEY) {
  process.stderr.write('UNVERIFIED: 缺少 DASHSCOPE_API_KEY，Java 真实模型验收未运行。\n');
  process.exit(2);
}
env.RUN_LIVE_JAVA_ANALYSIS = '1';
const result = spawnSync('mvn', ['-B', '-ntp', '-pl', 'backend', '-am', 'test', '-Dtest=JavaAnalysisLiveTest', '-Dsurefire.failIfNoSpecifiedTests=false'], {
  cwd: new URL('..', import.meta.url), env, stdio: 'inherit',
});
if (result.error) {
  process.stderr.write(`UNVERIFIED: 无法启动 Maven (${result.error.code})。\n`);
  process.exit(2);
}
if (result.status !== 0) process.exit(result.status ?? 1);
// Do not let filtering or skipped tests masquerade as a live run.
const report = readFileSync(new URL('../backend/target/surefire-reports/TEST-com.agenticvideo.business.task.JavaAnalysisLiveTest.xml', import.meta.url), 'utf8');
if (!/<testsuite\b[^>]*tests="4"/.test(report) || !/<testsuite\b[^>]*skipped="0"/.test(report)) {
  process.stderr.write('UNVERIFIED: 四项 Java 真实模型验收未全部执行。\n');
  process.exit(2);
}
process.stdout.write('VERIFIED: Java M1 四个固定视频的真实分析、四工具 HTTP 读取与回执复用验收通过；此命令不包含 Python 问答；完整链路使用 verify_java_m1.py。\n');

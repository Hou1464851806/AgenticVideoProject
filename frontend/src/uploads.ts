import { reactive } from 'vue';
import { ApiFailure, request } from './api';

type Upload = { uploadId: string; filename: string; status: string; reservedBytes: number; declaredBytes: number; reservedSlots: number; partSizeBytes: number; expectedParts: number; expiresAt: string; writerToken?: string; confirmedBytes?: number; confirmedParts?: Array<{ partNumber: number; sizeBytes: number }>; validationStatus?: string; error?: { code: string; message: string }; cleanupStatus?: string };
type Row = { key: string; projectId: string; filename: string; size: number; confirmed: number; hashing: number; state: string; error: string; hasFile?: boolean; paused?: boolean; running?: boolean; cancelRequested?: boolean; upload?: Upload };
export const uploadRows = reactive<Row[]>([]);
const queue: Array<{ file: File; row: Row }> = [];
const selectedFiles = new Map<string, File>();
const navigation = performance.getEntriesByType('navigation')[0] as PerformanceNavigationTiming | undefined;
const savedWriter = sessionStorage.getItem('upload-writer-id');
const writerId = savedWriter && (navigation?.type === 'reload' || navigation?.type === 'back_forward') ? savedWriter : crypto.randomUUID();
sessionStorage.setItem('upload-writer-id', writerId);
let active = 0;

function hashFile(file: File, row: Row): Promise<string> {
  return new Promise((resolve, reject) => {
    const worker = new Worker(new URL('./fileHash.worker.ts', import.meta.url), { type: 'module' });
    worker.onmessage = event => {
      if (event.data.progress !== undefined) row.hashing = event.data.progress;
      if (event.data.sha256) { worker.terminate(); resolve(event.data.sha256); }
      if (event.data.error) { worker.terminate(); reject(new Error(event.data.error)); }
    };
    worker.onerror = () => { worker.terminate(); reject(new Error('文件校验失败')); };
    worker.postMessage(file);
  });
}

async function retryWrite<T>(path: string, method: string, data: unknown): Promise<T> {
  for (let attempt = 0; ; attempt++) {
    try { return await request<T>(path, method, data); }
    catch (failure) {
      if (!(failure instanceof TypeError || failure instanceof ApiFailure && failure.retryable) || attempt >= 2) throw failure;
      await new Promise(resolve => setTimeout(resolve, 250 * 2 ** attempt));
    }
  }
}

async function transfer(file: File, row: Row) {
  try {
    row.state = '正在计算文件校验值';
    const sha256 = await hashFile(file, row);
    if(row.cancelRequested){row.state='已取消';return;}
    if (row.paused) { row.state = '已暂停'; return; }
    let upload = row.upload
      ? await retryWrite<Upload>(`/uploads/${row.upload.uploadId}/resume`, 'POST', { sizeBytes: file.size, sha256, writerId })
      : await retryWrite<Upload>(`/projects/${row.projectId}/uploads`, 'POST', {
          filename: file.name, sizeBytes: file.size, sha256, idempotencyKey: row.key, writerId,
        });
    row.upload = upload;
    if(row.cancelRequested){await settleCancellation(row);return;}
    if (upload.status === 'COMPLETED') { row.state = completedState(upload); return; }
    if (upload.status !== 'UPLOADING') throw new Error('上传初始化正在核对，请稍后重试');
    row.confirmed = upload.confirmedBytes ?? 0;
    const successful = new Set(upload.confirmedParts?.map(part => part.partNumber) ?? []);
    row.state = '正在传输';
    // Every file shares these two slots. Only one bounded part per slot is in flight.
    for (let number = 1; number <= upload.expectedParts; number++) {
      if(row.cancelRequested){await settleCancellation(row);return;}
      if (row.paused) { await settlePause(row); return; }
      if (successful.has(number)) continue;
      for (let attempt = 0; ; attempt++) {
        try {
          const authorization = await request<{ parts: Array<{ url: string; headers: Record<string, string>; sizeBytes: number }> }>(
            `/uploads/${upload.uploadId}/part-urls`, 'POST', { partNumbers: [number], writerToken: upload.writerToken });
          if(row.cancelRequested){await settleCancellation(row);return;}
          if (row.paused) { await settlePause(row); return; }
          const part = authorization.parts[0]!;
          const offset = (number - 1) * upload.partSizeBytes;
          const response = await fetch(part.url, { method: 'PUT', headers: part.headers, body: file.slice(offset, offset + part.sizeBytes) });
          if (!response.ok) throw new ApiFailure('分片传输失败，请继续上传或重新选择原文件', response.status === 403 || response.status === 408 || response.status === 429 || response.status >= 500);
          if(row.cancelRequested){await settleCancellation(row);return;}
          if (!response.headers.get('ETag')) throw new ApiFailure('无法读取分片确认信息', true);
          await retryWrite(`/uploads/${upload.uploadId}/parts/${number}`, 'PUT', {
            writerToken: upload.writerToken, etag: response.headers.get('ETag'), sizeBytes: part.sizeBytes,
          });
          row.confirmed += part.sizeBytes;
          break;
        } catch (failure) {
          if(row.cancelRequested){await settleCancellation(row);return;}
          if (row.paused) { await settlePause(row); return; }
          if (!(failure instanceof TypeError || failure instanceof ApiFailure && failure.retryable)) throw failure;
          if (attempt >= 2) throw new Error(`第 ${number} 片自动重试已用尽，成功分片已保留，请稍后继续上传`);
          row.state = `正在重试第 ${number} 片（${attempt + 1}/2）`;
          await new Promise(resolve => setTimeout(resolve, 250 * 2 ** attempt));
          if(row.cancelRequested){await settleCancellation(row);return;}
          if (row.paused) { await settlePause(row); return; }
          upload = await retryWrite<Upload>(`/uploads/${upload.uploadId}/resume`, 'POST', { sizeBytes: file.size, sha256, writerId });
          row.upload = upload;
          row.confirmed = upload.confirmedBytes ?? 0;
          if (upload.confirmedParts?.some(part => part.partNumber === number)) break;
          row.state = '正在传输';
        }
      }
    }
    if(row.cancelRequested){await settleCancellation(row);return;}
    if (row.paused) { await settlePause(row); return; }
    row.state = '正在确认上传';
    const completed = await retryWrite<Upload>(`/uploads/${upload.uploadId}/complete`, 'POST', { writerToken: upload.writerToken });
    if (completed.status !== 'COMPLETED') throw new Error('上传完成状态正在核对');
    row.upload = completed;
    row.state = '传输完成，待媒体验证';
    selectedFiles.delete(row.key);
    row.hasFile = false;
  } catch (failure) {
    if(row.cancelRequested){row.error=failure instanceof Error?failure.message:'取消状态核对失败，请重试取消';return;}
    row.state = '上传失败';
    row.error = failure instanceof TypeError ? '网络连接不可用，自动重试已用尽；请恢复网络后继续上传' : failure instanceof Error ? failure.message : '上传暂时不可用';
  } finally { row.running = false; }
}
function revoked(upload?:Upload){return upload?.status==='CANCELLED'||upload?.status==='EXPIRED';}
async function settleCancellation(row:Row){
  if(!row.upload){row.state='已取消';return;}
  try{
    const cancelled=await retryWrite<Upload>(`/uploads/${row.upload.uploadId}/cancel`,'POST',{});
    row.upload={...row.upload,...cancelled};row.state=cancelled.status==='EXPIRED'?'上传已过期，请重新上传':'已取消';row.error='';
  }catch(failure){
    const current=await request<Upload>(`/uploads/${row.upload.uploadId}`);
    row.upload={...row.upload,...current};
    if(current.status==='COMPLETED'){row.cancelRequested=false;row.state=completedState(current);row.error='上传已完成，无法取消';}
    else throw failure;
  }
}
export async function cancelUpload(key:string){
  const row=uploadRows.find(value=>value.key===key);if(!row||row.upload?.status==='COMPLETED')return;
  row.cancelRequested=true;row.paused=false;row.state='正在取消';row.error='';
  for(let i=queue.length-1;i>=0;i--)if(queue[i]?.row.key===key)queue.splice(i,1);
  selectedFiles.delete(key);row.hasFile=false;
  try{await settleCancellation(row);}catch(failure){row.error=failure instanceof Error?failure.message:'取消失败，请重试';}
}
async function settlePause(row: Row) {
  if (row.upload?.writerToken) {
    const paused = await request<Upload>(`/uploads/${row.upload.uploadId}/pause`, 'POST', { writerToken: row.upload.writerToken });
    row.upload = { ...row.upload, ...paused };
  }
  row.state = '已暂停';
}
export function pauseUpload(key: string) {
  const row = uploadRows.find(value => value.key === key);
  if (!row || row.cancelRequested || revoked(row.upload)) return;
  row.paused = true;
  row.state = row.running ? '正在暂停' : '已暂停';
}
export function continueUpload(key: string) {
  const row = uploadRows.find(value => value.key === key);
  const file = selectedFiles.get(key);
  if (!row || !file || row.running || row.cancelRequested || revoked(row.upload)) return;
  row.paused = false;
  row.error = '';
  row.state = '等待继续';
  if (!queue.some(value => value.row.key === key)) queue.push({ file, row });
  drain();
}
export function reselectFile(key: string, file: File) {
  const row = uploadRows.find(value => value.key === key);
  if (!row || row.running || row.cancelRequested || revoked(row.upload) || row.upload?.status === 'COMPLETED') return;
  const queued = queue.findIndex(value => value.row.key === key);
  if (queued >= 0) queue.splice(queued, 1);
  selectedFiles.set(key, file);
  row.hasFile = true;
  row.paused = false;
  row.error = '';
  row.hashing = 0;
  row.state = '等待继续';
  queue.push({ file, row });
  drain();
}
function drain() {
  while (active < 2 && queue.length) {
    const next = queue.shift()!;
    if (next.row.paused || next.row.cancelRequested || revoked(next.row.upload)) continue;
    active++;
    next.row.running = true;
    void transfer(next.file, next.row).finally(() => { active--; drain(); });
  }
}
export function enqueueFiles(projectId: string, files: File[]) {
  for (const file of files) {
    const row: Row = reactive({ key: crypto.randomUUID(), projectId, filename: file.name, size: file.size,
      confirmed: 0, hashing: 0, state: '等待上传', error: '', hasFile: true });
    uploadRows.push(row);
    selectedFiles.set(row.key, file);
    queue.push({ file, row });
  }
  drain();
}
function completedState(upload: Upload): string {
  if (upload.validationStatus === 'VALID') return '传输完成，媒体验证通过';
  if (upload.validationStatus === 'INVALID') return '传输完成，媒体验证失败';
  return '传输完成，待媒体验证';
}
export async function loadUploads(projectId: string) {
  let cursor: string | null = null;
  do {
    const suffix: string = cursor ? `?cursor=${encodeURIComponent(cursor)}` : '';
    const page: { items: Upload[]; nextCursor: string | null } = await request(`/projects/${projectId}/uploads${suffix}`);
    for (const upload of page.items) {
      const existing = uploadRows.find(row => row.upload?.uploadId === upload.uploadId);
      if (existing) {
        if(existing.cancelRequested && !revoked(upload) && upload.status!=='COMPLETED')continue;
        existing.upload = { ...existing.upload, ...upload };
        if(revoked(upload)){
          existing.cancelRequested=true;existing.state=upload.status==='EXPIRED'?'上传已过期，请重新上传':'已取消';existing.hasFile=false;selectedFiles.delete(existing.key);
        }
        if (upload.status === 'COMPLETED') {
          existing.cancelRequested=false;
          existing.confirmed = upload.declaredBytes;
          existing.state = completedState(upload);
          existing.error = upload.error?.message ?? '';
        }
        continue;
      }
      uploadRows.push({ key: upload.uploadId, projectId, filename: upload.filename, size: upload.declaredBytes,
        confirmed: upload.status === 'COMPLETED' ? upload.declaredBytes : upload.confirmedBytes ?? 0, hashing: 0, error: upload.error?.message ?? '', upload,
        hasFile: false, paused: upload.status === 'PAUSED',
        cancelRequested:revoked(upload),state: upload.status==='CANCELLED'?'已取消':upload.status==='EXPIRED'?'上传已过期，请重新上传':upload.status === 'COMPLETED' ? completedState(upload) : '重选原文件后继续上传' });
    }
    cursor = page.nextCursor;
  } while (cursor);
}

import { reactive } from 'vue';
import { ApiFailure, request } from './api';

type Upload = { uploadId: string; filename: string; status: string; reservedBytes: number; partSizeBytes: number; expectedParts: number; writerToken: string; validationStatus?: string };
type Row = { key: string; projectId: string; filename: string; size: number; confirmed: number; hashing: number; state: string; error: string; upload?: Upload };
export const uploadRows = reactive<Row[]>([]);
const queue: Array<{ file: File; row: Row }> = [];
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
    const upload = await retryWrite<Upload>(`/projects/${row.projectId}/uploads`, 'POST', {
      filename: file.name, sizeBytes: file.size, sha256, idempotencyKey: row.key,
    });
    row.upload = upload;
    if (upload.status !== 'UPLOADING') throw new Error('上传初始化正在核对，请稍后重试');
    row.state = '正在传输';
    // Every file shares these two slots. Only one bounded part per slot is in flight.
    for (let number = 1; number <= upload.expectedParts; number++) {
      const authorization = await request<{ parts: Array<{ url: string; headers: Record<string, string>; sizeBytes: number }> }>(
        `/uploads/${upload.uploadId}/part-urls`, 'POST', { partNumbers: [number], writerToken: upload.writerToken });
      const part = authorization.parts[0]!;
      const offset = (number - 1) * upload.partSizeBytes;
      const response = await fetch(part.url, { method: 'PUT', headers: part.headers, body: file.slice(offset, offset + part.sizeBytes) });
      if (!response.ok || !response.headers.get('ETag')) throw new Error('分片传输失败或无法读取 ETag');
      await request(`/uploads/${upload.uploadId}/parts/${number}`, 'PUT', {
        writerToken: upload.writerToken, etag: response.headers.get('ETag'), sizeBytes: part.sizeBytes,
      });
      row.confirmed += part.sizeBytes;
    }
    row.state = '正在确认上传';
    const completed = await retryWrite<Upload>(`/uploads/${upload.uploadId}/complete`, 'POST', { writerToken: upload.writerToken });
    if (completed.status !== 'COMPLETED') throw new Error('上传完成状态正在核对');
    row.upload = completed;
    row.state = '传输完成，待媒体验证';
  } catch (failure) {
    row.state = '上传失败';
    row.error = failure instanceof Error ? failure.message : '上传暂时不可用';
  }
}
function drain() {
  while (active < 2 && queue.length) {
    const next = queue.shift()!;
    active++;
    void transfer(next.file, next.row).finally(() => { active--; drain(); });
  }
}
export function enqueueFiles(projectId: string, files: File[]) {
  for (const file of files) {
    const row: Row = reactive({ key: crypto.randomUUID(), projectId, filename: file.name, size: file.size,
      confirmed: 0, hashing: 0, state: '等待上传', error: '' });
    uploadRows.push(row);
    queue.push({ file, row });
  }
  drain();
}
export async function loadUploads(projectId: string) {
  let cursor: string | null = null;
  do {
    const suffix: string = cursor ? `?cursor=${encodeURIComponent(cursor)}` : '';
    const page: { items: Upload[]; nextCursor: string | null } = await request(`/projects/${projectId}/uploads${suffix}`);
    for (const upload of page.items) {
      if (uploadRows.some(row => row.upload?.uploadId === upload.uploadId)) continue;
      uploadRows.push({ key: upload.uploadId, projectId, filename: upload.filename, size: upload.reservedBytes,
        confirmed: upload.status === 'COMPLETED' ? upload.reservedBytes : 0, hashing: 0, error: '', upload,
        state: upload.status === 'COMPLETED' ? '传输完成，待媒体验证' : '上传尚未完成' });
    }
    cursor = page.nextCursor;
  } while (cursor);
}

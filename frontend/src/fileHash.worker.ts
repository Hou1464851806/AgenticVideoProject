import { createSHA256 } from 'hash-wasm';

self.onmessage = async (event: MessageEvent<File>) => {
  try {
    const file = event.data;
    const hash = await createSHA256();
    hash.init();
    const chunkSize = 2 * 1024 * 1024;
    for (let offset = 0; offset < file.size; offset += chunkSize) {
      hash.update(new Uint8Array(await file.slice(offset, offset + chunkSize).arrayBuffer()));
      self.postMessage({ progress: Math.min(file.size, offset + chunkSize) });
    }
    self.postMessage({ sha256: hash.digest() });
  } catch { self.postMessage({ error: '无法读取文件并计算校验值' }); }
};

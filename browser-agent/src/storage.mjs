export function normalizeSettings(input) {
  const url = new URL(input.url);
  if (!['http:', 'https:'].includes(url.protocol) || url.username || url.password || url.search || url.hash) throw new Error('请输入有效的 Starweave HTTP/HTTPS 地址');
  const name = String(input.name || '').trim();
  if (!/^[a-zA-Z0-9][a-zA-Z0-9_.-]{0,79}$/.test(name)) throw new Error('名称需为 1～80 位字母、数字、点、横线或下划线，以字母或数字开头');
  return { url: url.href.replace(/\/+$/, ''), name, accessToken: String(input.accessToken || '') };
}
export class SessionStore {
  constructor(namespace, indexedDB = globalThis.indexedDB) { this.namespace = namespace; this.indexedDB = indexedDB; }
  async db() {
    return this.opening ??= new Promise((resolve, reject) => {
      const req = this.indexedDB.open('starweave-browser', 1);
      req.onupgradeneeded = () => req.result.createObjectStore('sessions');
      req.onsuccess = () => resolve(req.result); req.onerror = () => reject(req.error);
    });
  }
  async access(id, value) {
    const db = await this.db();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('sessions', value === undefined ? 'readonly' : 'readwrite');
      const store = tx.objectStore('sessions'), key = `${this.namespace}:${id}`;
      const req = value === undefined ? store.get(key) : store.put(value, key);
      tx.oncomplete = () => resolve(req.result); tx.onerror = () => reject(tx.error); tx.onabort = () => reject(tx.error || new Error('会话存储已取消'));
    });
  }
  async list() {
    const db = await this.db();
    return new Promise((resolve, reject) => {
      const tx = db.transaction('sessions', 'readonly'), store = tx.objectStore('sessions');
      const keys = store.getAllKeys(), values = store.getAll();
      tx.oncomplete = () => resolve(values.result.filter((v, i) => String(keys.result[i]).startsWith(`${this.namespace}:`) && v?.engine === 'pi-browser-0.75.3'));
      tx.onerror = () => reject(tx.error);
    });
  }
  get(id) { return this.access(id); }
  put(snapshot) { return this.access(snapshot.sessionId, snapshot); }
}
export const textResult = value => ({ content: [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value) }] });
export async function sha256(text) {
  const hash = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(text));
  return Array.from(new Uint8Array(hash), b => b.toString(16).padStart(2, '0')).join('');
}
export function encode64(text) {
  const bytes = new TextEncoder().encode(text); let binary = '';
  for (let i = 0; i < bytes.length; i += 8192) binary += String.fromCharCode(...bytes.subarray(i, i + 8192));
  return btoa(binary);
}
export function decode64(value) { return new TextDecoder('utf-8', { fatal: true }).decode(Uint8Array.from(atob(value), c => c.charCodeAt(0))); }

/** Stop waiting promptly; cancellation cannot roll back a command already sent to a page. */
export function bounded(run, signal, timeoutMs = 20000) {
  signal?.throwIfAborted();
  return new Promise((resolve, reject) => {
    const clear = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); };
    const abort = () => { clear(); reject(new Error('工具等待已取消；已执行的页面操作不会回滚')); };
    const timer = setTimeout(() => { clear(); reject(new Error('工具等待超时，操作结果可能未知，请重新观察页面')); }, timeoutMs);
    signal?.addEventListener('abort', abort, { once: true });
    Promise.resolve().then(() => { signal?.throwIfAborted(); return run(); }).then(value => { clear(); resolve(value); }, error => { clear(); reject(error); });
  });
}

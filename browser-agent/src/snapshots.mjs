import { sha256, encode64, decode64 } from './storage.mjs';
export class Snapshots {
  constructor(store, exchange, report = () => {}) { Object.assign(this, { store, exchange, report }); this.dirty = new Map(); this.serial = Promise.resolve(); }
  request(frame) { const task = this.serial.catch(() => {}).then(() => this.exchange(frame)); this.serial = task; return task; }
  queue(snapshot) {
    if (this.stopped) return;
    this.dirty.set(snapshot.sessionId, snapshot);
    clearTimeout(this.timer); this.timer = setTimeout(() => this.flush(), 300);
  }
  async flush() {
    if (this.flushing || this.stopped) return;
    this.flushing = true;
    try {
      for (const [id, snapshot] of this.dirty) {
        const text = JSON.stringify(snapshot);
        if (new TextEncoder().encode(text).length > 16 * 1024 * 1024) throw new Error('会话超过 16 MiB，无法备份');
        const base = await this.store.access(`base-${id}`) || '';
        const hash = await sha256(text);
        const result = await this.request({ sessionId: id, base, hash, data: encode64(text) });
        if (result.error) throw new Error(result.error);
        await this.store.access(`base-${id}`, result.hash);
        if (this.dirty.get(id) === snapshot) this.dirty.delete(id);
      }
      this.report('');
    } catch (e) { this.report(e.message); }
    finally { this.flushing = false; }
  }
  async restore(id) {
    if (!/^[a-zA-Z0-9_-]{1,100}$/.test(id)) throw new Error('无效的会话 ID');
    // Serialize restore behind a pending upload of this session; never overwrite a live snapshot.
    const local = await this.store.get(id), base = await this.store.access(`base-${id}`) || '';
    const localHash = local ? await sha256(JSON.stringify(local)) : '';
    const response = await this.request({ sessionId: id, pull: true, local: localHash });
    if (response.error) throw new Error(response.error);
    if (response.data) {
      if (local && localHash !== base && localHash !== response.hash) throw new Error('本地会话和中心备份均有修改，保留双方数据，请处理冲突');
      const text = decode64(response.data);
      if (await sha256(text) !== response.hash) throw new Error('会话备份哈希校验失败');
      const snapshot = JSON.parse(text);
      if (snapshot.sessionId !== id || snapshot.version !== 1 || snapshot.engine !== 'pi-browser-0.75.3' || !Array.isArray(snapshot.messages) || !Array.isArray(snapshot.history)) throw new Error('无效的浏览器会话备份');
      await this.store.put(snapshot); await this.store.access(`base-${id}`, response.hash); return snapshot;
    }
    if (localHash && localHash === response.hash) await this.store.access(`base-${id}`, response.hash);
    return local;
  }
  stop() { this.stopped = true; clearTimeout(this.timer); }
}

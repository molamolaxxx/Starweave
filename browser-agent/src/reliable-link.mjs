// Worker-lifetime channel; transport replay never re-executes tasks/tools.
export class ReliableLink {
  constructor(deliver, expired, report = () => {}) {
    Object.assign(this, { deliver, expired, report });
    this.token = crypto.randomUUID() + crypto.randomUUID(); this.sent = 0; this.received = 0; this.pending = new Map(); this.bytes = 0; this.waiters = []; this.transmitted = 0; this.acknowledged = 0;
  }
  attach(socket, registration) {
    this.ready = false; this.socket = socket;
    socket.onopen = () => socket.send(JSON.stringify({ ...registration, resumeToken: this.token, resumeReceived: this.received }));
    socket.onmessage = event => { if (socket !== this.socket) return; try { this.receive(JSON.parse(event.data)); } catch (e) { this.expired(e.message); } };
    socket.onclose = event => this.detach(socket, `WebSocket ${event.code}: ${event.reason || 'closed'}`);
    socket.onerror = () => this.detach(socket, 'WebSocket error');
  }
  receive(frame) {
    this.lastReceived = Date.now();
    if (frame.type === 'resume_ready') {
      if (this.connectedBefore && !frame.resumed) throw new Error('中心已丢失原连接，保留历史后重新恢复会话');
      this.ready = false; this.ack(frame.received); this.transmitted = frame.received; this.connectedBefore = true; this.ready = true;
      clearTimeout(this.deadline); this.deadline = null;
      this.flush();
      for (const waiter of this.waiters.splice(0)) waiter.resolve();
      this.report('connected'); return;
    }
    if (frame.type === 'resume_ack') { this.ack(frame.seq); return; }
    if (frame.type !== 'resume_frame' || !Number.isSafeInteger(frame.seq)) throw new Error('无效的续连消息');
    if (frame.seq > this.received + 1) throw new Error('续连消息序号不连续');
    if (frame.seq === this.received + 1) { this.deliver(frame.payload); this.received = frame.seq; }
    this.socket.send(JSON.stringify({ type: 'resume_ack', seq: this.received }));
  }
  ack(seq) {
    if (!Number.isSafeInteger(seq) || seq < 0 || seq > this.sent) throw new Error('无效的续连确认');
    this.acknowledged = Math.max(this.acknowledged, seq);
    for (const [id, text] of this.pending) if (id <= seq) { this.bytes -= new TextEncoder().encode(text).length; this.pending.delete(id); }
    this.flush();
  }
  send(payload) {
    const text = JSON.stringify({ type: 'resume_frame', seq: this.sent + 1, payload });
    const bytes = new TextEncoder().encode(text).length;
    if (bytes > 32 * 1024 * 1024 || this.bytes + bytes > 64 * 1024 * 1024 || this.pending.size >= 8192) throw new Error('续连消息缓存超过上限');
    this.sent++; this.pending.set(this.sent, text); this.bytes += bytes;
    this.flush();
  }
  flush() {
    if (!this.ready || this.socket?.readyState !== 1) return;
    for (const [seq, text] of this.pending) {
      if (seq <= this.transmitted) continue;
      if (seq > this.acknowledged + 32) break;
      try { this.socket.send(text); this.transmitted = seq; }
      catch { this.detach(this.socket, 'send failed'); break; }
    }
  }
  detach(socket, reason) {
    if (socket !== this.socket || this.stopped) return;
    this.ready = false; socket.onclose = socket.onerror = socket.onmessage = null; try { socket.close(); } catch {}
    this.report('reconnecting', reason);
    if (!this.deadline) this.deadline = setTimeout(() => this.expired('断线超过 60 秒，当前执行中断，原会话上下文已保留'), 60000);
  }
  wait(signal) {
    if (this.ready) return Promise.resolve();
    return new Promise((resolve, reject) => {
      const waiter = { resolve: () => { signal?.removeEventListener('abort', abort); resolve(); }, reject: e => { signal?.removeEventListener('abort', abort); reject(e); } };
      const abort = () => { this.waiters = this.waiters.filter(w => w !== waiter); reject(new Error('连接等待已取消')); };
      signal?.throwIfAborted(); signal?.addEventListener('abort', abort, { once: true }); this.waiters.push(waiter);
    });
  }
  stop() { this.stopped = true; clearTimeout(this.deadline); this.socket?.close(); for (const w of this.waiters.splice(0)) w.reject(new Error('连接已关闭')); }
}

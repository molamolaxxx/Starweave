import { normalizeSettings, SessionStore } from './storage.mjs';
import { BrowserTools } from './browser-tools.mjs';
import { BrowserRuntime } from './runtime.mjs';
import { Snapshots } from './snapshots.mjs';
import { ReliableLink } from './reliable-link.mjs';

const browser = new BrowserTools(), coordinator = { active: null, sessions: new Set() };
let connection, settings, reconnectTimer, generation = 0, state = { status: '未连接', error: '', backupError: '', active: false };
async function status(update) {
  if (connection && !connection.closed && !connection.link?.ready && ['就绪', '执行中'].includes(update.status)) update.status = '正在重连';
  state = { ...state, ...update, active: Boolean(coordinator.active) };
  await chrome.storage.session.set({ status: state });
  chrome.action.setBadgeText({ text: state.status === '就绪' ? 'ON' : state.status === '执行中' ? '…' : '' }).catch(() => {});
}
class Connection {
  constructor(socket, config, identity, token) {
    Object.assign(this, { socket, config, identity, token }); this.pending = new Map(); this.runtimes = new Map(); this.counter = 0; this.lastReceived = Date.now();
    this.store = new SessionStore(`${config.url}|${config.name}|${identity}`);
    this.snapshots = new Snapshots(this.store, frame => this.call('sessions_request', frame), error => { if (connection === this) status({ backupError: error }); });
    this.link = new ReliableLink(frame => { this.receive(JSON.stringify(frame)).catch(e => { status({ error: e.message }); this.close(); }); }, reason => { status({ error: reason }); this.close(); }, (phase, reason) => {
      clearTimeout(this.reconnectStatusTimer);
      if (phase === 'reconnecting') {
        console.warn('[Starweave] reconnecting', { reason, lastReceived: this.link.lastReceived, pendingFrames: this.link.pending.size, pendingBytes: this.link.bytes });
        this.reconnectStatusTimer = setTimeout(() => status({ status: '正在重连', error: reason }), 2000);
        clearTimeout(reconnectTimer); reconnectTimer = setTimeout(() => connect(), 1000);
      } else status({ status: coordinator.active ? '执行中' : this.registered ? '就绪' : '连接中', error: '' });
    });
    this.attach(socket);
    this.heartbeat = setInterval(() => {
      if (!this.link.ready) return;
      if (Date.now() - (this.link.lastReceived || this.lastReceived) > 30000) { this.link.detach(this.socket, 'heartbeat timeout'); return; }
      if (this.socket.readyState === WebSocket.OPEN) this.send({ type: 'ping' });
      if (this.registered) this.snapshots.flush();
    }, 10000);
  }
  attach(socket) {
    this.socket = socket;
    this.link.attach(socket, { type: 'register', protocolVersion: 1, name: this.config.name, identity: this.identity,
      workspace: `/browser/${this.identity}`, platform: 'BROWSER', sdkVersion: 'browser-0.4.0', starweaveUrl: this.config.url,
      accessToken: this.config.accessToken, sessionSyncVersion: 1 });
  }
  send(frame) {
    if (this.closed || connection !== this) throw new Error('中心连接不可用');
    try { this.link.send(frame); } catch (e) { status({ error: e.message }); this.close(); throw e; }
  }
  call(type, value, signal, onUpdate) {
    signal?.throwIfAborted();
    if (this.closed) return Promise.reject(new Error('中心连接已关闭'));
    const requestId = String(++this.counter);
    return new Promise((resolve, reject) => {
      const clear = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); this.pending.delete(requestId); };
      const abort = () => {
        if (type === 'browser_tool' && value.operation === 'call') {
          try { this.send({ type: 'browser_tool', ...value, operation: 'cancel' }); } catch (_) { }
        }
        clear(); reject(new Error('工具等待已取消；已执行的操作不会回滚'));
      };
      const timer = setTimeout(() => { abort(); }, type === 'browser_tool' ? 660000 : 90000);
      this.pending.set(requestId, { onUpdate, resolve: result => { clear(); resolve(result); }, reject: e => { clear(); reject(e); } });
      signal?.addEventListener('abort', abort, { once: true });
      try { this.send({ type, requestId, ...value }); } catch (e) { this.pending.get(requestId)?.reject(e); }
    });
  }
  async receive(text) {
    if (this.closed) return;
    this.lastReceived = Date.now(); const frame = JSON.parse(text);
    if (frame.requestId && this.pending.has(frame.requestId)) {
      const p = this.pending.get(frame.requestId);
      if (frame.type === 'browser_tool_update') { p.onUpdate?.(frame.result); return; }
      frame.error ? p.reject(new Error(frame.error)) : p.resolve(frame.type === 'browser_tool_result' ? frame.result : frame); return;
    }
    if (frame.type === 'registered') {
      if (frame.sessionSyncVersion !== 1) throw new Error('中心未提供浏览器会话备份，请更新中心');
      this.registered = true;
      for (const snapshot of await this.store.list()) this.snapshots.queue(snapshot);
      return;
    }
    if (frame.type === 'config') {
      this.model = frame.config;
      const ready = Boolean(this.model.model && this.model.apiKey);
      this.send({ type: 'config_ack', version: frame.version, ready });
      await status({ status: ready ? '就绪' : '待配置', error: ready ? '' : '请在 Starweave 配置模型和 API Key' }); return;
    }
    if (frame.type === 'open') {
      if (this.runtimes.size >= 8) { this.send({ type: 'runtime_error', runtimeId: frame.runtimeId, error: '运行实例数量达到上限' }); return; }
      const runtime = new BrowserRuntime({ id: frame.runtimeId, config: frame.config,
        send: message => { this.send({ type: 'acp', runtimeId: frame.runtimeId, message }); },
        connectionReady: signal => this.link.wait(signal), hostTools: (value, signal, update) => this.call('browser_tool', { ...value, runtimeId: frame.runtimeId }, signal, update), store: this.store, snapshots: this.snapshots, browser, coordinator });
      this.runtimes.set(frame.runtimeId, runtime); this.send({ type: 'opened', runtimeId: frame.runtimeId }); return;
    }
    if (frame.type === 'acp') {
      const runtime = this.runtimes.get(frame.runtimeId);
      if (!runtime) {
        if (frame.message.id !== undefined && frame.message.method) this.send({ type: 'acp', runtimeId: frame.runtimeId, message: { jsonrpc: '2.0', id: frame.message.id, error: { code: -32000, message: 'ACP 运行实例不存在' } } });
        return;
      }
      // Do not await prompt: cancellation, permissions and tools must stay responsive.
      runtime.handle(frame.message).catch(e => status({ error: e.message })).finally(() => status({ status: coordinator.active ? '执行中' : '就绪' }));
      if (frame.message.method === 'session/prompt') await status({ status: '执行中' }); return;
    }
    if (frame.type === 'close') {
      const runtime = this.runtimes.get(frame.runtimeId); this.runtimes.delete(frame.runtimeId);
      if (runtime) await runtime.close(); this.send({ type: 'closed', runtimeId: frame.runtimeId }); return;
    }
    if (frame.type === 'upload') { this.send({ type: 'uploaded', uploadId: frame.uploadId, error: '浏览器首版不支持文件附件，请使用文本或内嵌图片' }); return; }
    if (frame.type === 'error') throw new Error('中心拒绝接入，请核对名称、身份及接入令牌');
  }
  close() {
    if (this.closed) return this.closing;
    this.closed = true; clearTimeout(this.reconnectStatusTimer); clearInterval(this.heartbeat); this.snapshots.stop(); this.link.stop();
    for (const p of this.pending.values()) p.reject(new Error('中心连接已断开')); this.pending.clear();
    this.closing = Promise.allSettled([...this.runtimes.values()].map(r => r.close())).then(async () => {
      this.runtimes.clear(); await browser.release();
      if (connection === this) {
        connection = null; await status({ status: '未连接', active: false, ...(!this.registered ? { error: '中心拒绝连接或连接中断，请检查名称、地址和接入令牌' } : {}) });
        clearTimeout(reconnectTimer); reconnectTimer = setTimeout(() => connect(), 3000);
      }
    }); return this.closing;
  }
}
async function connect(force = false) {
  if (connection && !force && (connection.link.ready || connection.socket.readyState === WebSocket.CONNECTING)) return;
  const token = ++generation;
  clearTimeout(reconnectTimer);
  if (connection && force) { await connection.close(); clearTimeout(reconnectTimer); }
  try {
    const saved = await chrome.storage.local.get(['settings', 'identities']);
    if (!saved.settings) { await status({ status: '未配置', error: '' }); return; }
    settings = normalizeSettings(saved.settings);
    const key = `${settings.url}|${settings.name}`, identities = saved.identities || {};
    const identity = identities[key] || crypto.randomUUID();
    if (!identities[key]) { identities[key] = identity; await chrome.storage.local.set({ identities }); }
    await status({ status: connection ? '正在重连' : '连接中', error: '' });
    const response = await fetch(`${settings.url}/api/remote-acp/discover?transport=websocket`, {
      headers: settings.accessToken ? { Authorization: `Bearer ${settings.accessToken}` } : {}, signal: AbortSignal.timeout(15000)
    });
    if (!response.ok) throw new Error(`接入发现失败：HTTP ${response.status}`);
    const info = await response.json(); if (token !== generation) return;
    if (!info.webSocketPort && !info.webSocketUrl) throw new Error('中心尚未支持浏览器星核，请更新中心');
    let url;
    if (info.webSocketUrl) url = new URL(info.webSocketUrl);
    else { url = new URL(settings.url); url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'; url.hostname = info.host || url.hostname; url.port = String(info.webSocketPort); url.pathname = info.webSocketPath; url.search = ''; }
    if (!['ws:', 'wss:'].includes(url.protocol)) throw new Error('无效的中心 WebSocket 地址');
    if (connection && !connection.closed) connection.attach(new WebSocket(url.href));
    else connection = new Connection(new WebSocket(url.href), settings, identity, token);
  } catch (e) {
    if (token !== generation) return;
    await status({ status: '连接失败', error: e.message });
    reconnectTimer = setTimeout(() => connect(), 5000);
  }
}
chrome.debugger.onDetach.addListener(source => browser.detached(source.tabId));
chrome.debugger.onEvent.addListener((source, method, params) => { browser.event(source, method, params).catch(e => console.warn('[Starweave] browser event:', method, e.message)); });
chrome.runtime.onMessage.addListener((message, sender, respond) => {
  if (message.target === 'starweave-clipboard') return false;
  if (sender.id !== chrome.runtime.id || !sender.url?.startsWith(chrome.runtime.getURL(''))) return false;
  (async () => {
    if (message.type === 'save') { const config = normalizeSettings(message.settings); await chrome.storage.local.set({ settings: config }); await connect(true); }
    if (message.type === 'connect') await connect(true);
    if (message.type === 'stop') { for (const runtime of connection?.runtimes.values() || []) for (const s of runtime.sessions.values()) { s.agent.abort(); s.compactionAgent?.abort(); } }
    return { ok: true, ...state };
  })().then(respond, e => respond({ ok: false, error: e.message })); return true;
});
chrome.alarms.onAlarm.addListener(alarm => { if (alarm.name === 'starweave-reconnect') connect(); });
async function start() {
  await chrome.storage.local.setAccessLevel?.({ accessLevel: 'TRUSTED_CONTEXTS' });
  await chrome.alarms.create('starweave-reconnect', { periodInMinutes: 0.5 });
  await connect();
}
start().catch(e => status({ status: '启动失败', error: e.message }));

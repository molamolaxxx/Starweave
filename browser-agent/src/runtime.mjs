import { Agent } from '@earendil-works/pi-agent-core';
import { streamSimpleOpenAICompletions } from '@earendil-works/pi-ai/openai-completions';
import { proxyTools, environmentPrompt } from './proxy-tools.mjs';
import { contextTokens, estimateMessage } from './context-usage.mjs';

export function modelConfig(config, id = config.model) {
  if (!id || !config.apiKey) throw new Error('请先在 Starweave 配置模型和 API Key');
  return { id, name: id, api: 'openai-completions', provider: 'starweave', baseUrl: config.baseUrl.replace(/\/+$/, '').replace(/\/chat\/completions$/, ''),
    reasoning: false, input: ['text', 'image'], cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 },
    contextWindow: config.contextWindow || 1000000, maxTokens: config.maxTokens || 64000,
    compat: { supportsDeveloperRole: false, supportsStore: false, maxTokensField: 'max_tokens' }
  };
}
export function preview(value) {
  const text = JSON.stringify(value, (key, v) => (key === 'data' && typeof v === 'string' && v.length > 1000) ? '[binary data omitted]' : v);
  // UTF-8 preview is bounded to 64 KiB. Original model results and snapshots remain intact.
  const bytes = new TextEncoder().encode(text || 'null');
  if (bytes.length <= 65536) return JSON.parse(text || 'null');
  const decoder = new TextDecoder(); return { preview: `${decoder.decode(bytes.slice(0, 32000))}\n[...truncated...]\n${decoder.decode(bytes.slice(-32000))}` };
}
export function repairMessages(messages) {
  const result = [], pending = new Map();
  for (const m of messages) {
    if (m.role !== 'toolResult' && pending.size) {
      for (const [id, name] of pending) result.push({ role: 'toolResult', toolCallId: id, toolName: name, content: [{ type: 'text', text: '上次执行已中断，结果未知。重新观察页面，不要自动重放操作。' }], isError: true, timestamp: Date.now() });
      pending.clear();
    }
    if (m.role === 'toolResult') pending.delete(m.toolCallId);
    result.push(m);
    if (m.role === 'assistant') for (const c of m.content || []) if (c.type === 'toolCall') pending.set(c.id, c.name);
  }
  for (const [id, name] of pending) result.push({ role: 'toolResult', toolCallId: id, toolName: name, content: [{ type: 'text', text: '上次执行已中断，结果未知。不要自动重放操作。' }], isError: true, timestamp: Date.now() });
  return result;
}
export class BrowserRuntime {
  constructor({ id, config, send, hostTools, store, snapshots, browser, coordinator, connectionReady = async () => {}, stream = streamSimpleOpenAICompletions }) {
    Object.assign(this, { id, config, send, hostTools, store, snapshots, browser, coordinator, connectionReady, stream });
    this.sessions = new Map(); this.closed = false; this.pending = new Map(); this.sequence = 0;
    this.lifecycle = new AbortController(); this.sessionOperations = Promise.resolve();
  }
  emit(sessionId, update) { this.send({ jsonrpc: '2.0', method: 'session/update', params: { sessionId, update } }); }
  async handle(message) {
    if (!message.method) {
      const waiting = this.pending.get(message.id);
      if (waiting) { this.pending.delete(message.id); message.error ? waiting.reject(new Error(message.error.message)) : waiting.resolve(message.result); }
      return;
    }
    if (message.method === 'session/cancel') { const s = this.sessions.get(message.params?.sessionId); s?.agent.abort(); s?.compactionAgent?.abort(); }
    try {
      const run = () => this.request(message.method, message.params || {});
      // Lifecycle methods serialize; prompt/cancel remain independently dispatchable.
      const lifecycle = ['session/new', 'session/load', 'session/resume', 'session/close', 'session/end', '_session/end'].includes(message.method);
      const task = lifecycle ? (this.sessionOperations = this.sessionOperations.catch(() => {}).then(run)) : run();
      const result = await task;
      if (message.id !== undefined && !this.closed) this.send({ jsonrpc: '2.0', id: message.id, result });
    } catch (e) {
      if (message.id !== undefined && !this.closed) this.send({ jsonrpc: '2.0', id: message.id, error: { code: -32000, message: e.message || String(e) } });
    }
  }
  async request(method, p) {
    if (this.closed) throw new Error('运行实例已关闭');
    if (method === 'initialize') return { protocolVersion: 1, agentInfo: { name: 'starweave-browser', version: '0.4.0' }, agentCapabilities: { loadSession: true, promptCapabilities: { image: true }, mcpCapabilities: { http: true, sse: true }, sessionCapabilities: { resume: {}, close: {}, compaction: {} } } };
    if (method === 'authenticate') return {};
    if (['session/new', 'session/load', 'session/resume'].includes(method)) return this.create(p, method);
    const s = this.sessions.get(p.sessionId); if (!s) throw new Error('会话未加载');
    switch (method) {
      case 'session/prompt': return this.prompt(s, p.prompt || []);
      case 'session/cancel': s.agent.abort(); s.compactionAgent?.abort(); return {};
      case 'session/set_model': case '_session/set_model':
        if (s.busy) throw new Error('会话执行中，不能切换模型');
        s.agent.state.model = modelConfig(this.config, p.modelId); await this.save(s); return { configOptions: this.options(s) };
      case 'session/set_config_option':
        if (p.configId !== 'model') throw new Error('不支持的配置项');
        return this.request('session/set_model', { sessionId: s.id, modelId: p.value });
      case 'session/close': case 'session/end': case '_session/end':
        s.agent.abort(); s.compactionAgent?.abort(); await s.agent.waitForIdle(); await this.save(s); await s.mcp.close(); this.sessions.delete(s.id); this.coordinator.sessions.delete(s.id); return {};
      default: throw new Error(`不支持的 ACP 方法：${method}`);
    }
  }
  options(s) { const id = s.agent.state.model.id; return [{ id: 'model', name: '模型', category: 'model', type: 'select', currentValue: id, options: [{ value: id, name: id }] }]; }
  async create(p, method) {
    const role = p._starweaveRole === 'memory' ? 'memory' : 'browser';
    let snapshot;
    if (method !== 'session/new') {
      if ([...this.coordinator.sessions].includes(p.sessionId)) throw new Error('会话已由其他运行实例加载');
      snapshot = await this.snapshots.restore(p.sessionId);
      if (!snapshot || snapshot.engine !== 'pi-browser-0.75.3' || snapshot.role !== role) throw new Error('会话快照不存在、版本或归属不匹配');
    }
    const id = snapshot?.sessionId || crypto.randomUUID();
    if (this.coordinator.sessions.has(id)) throw new Error('会话已加载');
    this.coordinator.sessions.add(id);
    let mcp;
    try {
      const host = role === 'memory' ? null : await this.hostTools({ operation: 'prepare', toolSession: p._starweaveToolSession });
      mcp = { close: async () => { if (host) await this.hostTools({ operation: 'close', toolSession: p._starweaveToolSession }).catch(() => {}); } };
      this.lifecycle.signal.throwIfAborted();
      const tools = role === 'memory' ? [] : [...this.browser.tools({ fileRelay: async (paths, signal) => { const result = await this.hostTools({ operation: 'read_files', toolSession: p._starweaveToolSession, paths }, signal); return result.files; } }), ...proxyTools(host.tools, p._starweaveToolSession, this.hostTools)];
      const names = new Set(); for (const t of tools) { if (names.has(t.name)) throw new Error(`工具名称冲突：${t.name}`); names.add(t.name); }
      const prompt = role === 'memory' ? '你是 Starweave 记忆提取助手。按用户提供的格式分析对话，只返回要求的数据，不操作网页。' : environmentPrompt(host);
      const agent = new Agent({ initialState: { model: modelConfig(this.config, snapshot?.model || this.config.model), systemPrompt: prompt, tools, messages: repairMessages(snapshot?.messages || []) },
        streamFn: (model, context, options) => this.stream(model, context, { ...options, apiKey: this.config.apiKey, maxRetries: 0, timeoutMs: 120000, maxTokens: Math.min(this.config.maxTokens || 64000, model.maxTokens) }),
        toolExecution: 'sequential', sessionId: id,
        prepareNextTurn: () => {
          if (!s.contextChanged) return;
          s.contextChanged = false;
          return { context: { systemPrompt: agent.state.systemPrompt, messages: agent.state.messages.slice(), tools: agent.state.tools } };
        },
        beforeToolCall: async ({ toolCall }, signal) => {
          await this.connectionReady(signal);
          if (++s.steps > 100) return { block: true, reason: '本轮工具调用达到 100 次，请用户继续后再执行' };
          // Preserve ACP permission policy; the plugin itself defaults to all tools enabled.
          const answer = await this.permission(id, toolCall, signal);
          return answer ? undefined : { block: true, reason: '操作未获授权' };
        }
      });
      const s = { id, role, cwd: p.cwd, agent, mcp, history: repairMessages(snapshot?.history || []), busy: false, steps: 0, compactions: snapshot?.compactions || 0 };
      agent.subscribe(async event => this.event(s, event));
      this.sessions.set(id, s); this.coordinator.sessions.add(id);
      await this.save(s);
      if (method === 'session/load') this.replay(s);
      this.emit(id, { sessionUpdate: 'available_commands_update', availableCommands: [{ name: 'compact', description: '压缩当前上下文' }] });
      this.usage(s); return { sessionId: id, configOptions: this.options(s) };
    } catch (e) { if (mcp) await mcp.close(); this.sessions.delete(id); this.coordinator.sessions.delete(id); throw e; }
  }
  permission(sessionId, call, signal) {
    signal?.throwIfAborted();
    const id = `permission-${++this.sequence}`;
    return new Promise((resolve, reject) => {
      const clear = () => { clearTimeout(timer); signal?.removeEventListener('abort', abort); this.pending.delete(id); };
      const abort = () => { clear(); reject(new Error('权限等待已取消')); };
      const timer = setTimeout(() => { clear(); reject(new Error('权限请求超时')); }, 120000);
      this.pending.set(id, { resolve: result => { clear(); resolve(result?.outcome?.outcome === 'selected' && result.outcome.optionId !== 'reject_once'); }, reject: e => { clear(); reject(e); } });
      signal?.addEventListener('abort', abort, { once: true });
      try { this.send({ jsonrpc: '2.0', id, method: 'session/request_permission', params: { sessionId,
        toolCall: { toolCallId: call.id, title: toolTitle(call.name, call.arguments), kind: 'other', rawInput: preview(call.arguments) },
        options: [{ optionId: 'allow_once', name: '允许', kind: 'allow_once' }, { optionId: 'allow_always', name: '始终允许', kind: 'allow_always' }, { optionId: 'reject_once', name: '拒绝', kind: 'reject_once' }]
      } }); } catch (e) { clear(); reject(e); }
    });
  }
  async event(s, e) {
    if (e.type === 'message_update') {
      const d = e.assistantMessageEvent;
      if (d.type === 'text_delta' || d.type === 'thinking_delta') this.emit(s.id, { sessionUpdate: d.type === 'text_delta' ? 'agent_message_chunk' : 'agent_thought_chunk', content: { type: 'text', text: d.delta } });
    }
    if (e.type === 'message_end') { s.history.push(structuredClone(e.message)); await this.save(s); }
    if (e.type === 'tool_execution_start') this.emit(s.id, { sessionUpdate: 'tool_call', toolCallId: e.toolCallId, title: toolTitle(e.toolName, e.args), kind: 'other', status: 'in_progress', rawInput: preview(e.args) });
    if (e.type === 'tool_execution_update') this.emit(s.id, { sessionUpdate: 'tool_call_update', toolCallId: e.toolCallId, status: 'in_progress', rawOutput: preview(e.partialResult) });
    if (e.type === 'tool_execution_end') this.emit(s.id, { sessionUpdate: 'tool_call_update', toolCallId: e.toolCallId, status: e.isError ? 'failed' : 'completed', rawOutput: preview(e.result), content: [{ type: 'content', content: { type: 'text', text: JSON.stringify(preview(e.result)) } }] });
    if (e.type === 'turn_end') {
      await this.save(s); this.usage(s);
      if (this.estimate(s) >= s.agent.state.model.contextWindow - Math.min(s.agent.state.model.maxTokens + 2048, s.agent.state.model.contextWindow / 3)) await this.compact(s, false);
    }
  }
  estimate(s) { return contextTokens(s.agent.state); }
  usage(s) { this.emit(s.id, { sessionUpdate: 'usage_update', used: this.estimate(s), size: s.agent.state.model.contextWindow }); }
  async compact(s, manual) {
    const toolCallId = `compact-${++s.compactions}`;
    this.emit(s.id, { sessionUpdate: 'tool_call', toolCallId, title: '压缩上下文', kind: 'other', status: 'in_progress', rawInput: { type: 'context_compaction', status: 'in_progress', reason: manual ? 'manual' : 'threshold' }, _meta: { starweaveCompaction: { status: 'in_progress', reason: manual ? 'manual' : 'threshold' } } });
    try {
      const summary = new Agent({ initialState: { model: s.agent.state.model, systemPrompt: '将对话压缩为后续智能体可用的工作摘要。保留用户要求、已完成和未完成事项、重要结果、已加载技能及错误。不要继续执行任务。', tools: [] },
        streamFn: (model, context, options) => this.stream(model, context, { ...options, apiKey: this.config.apiKey, maxRetries: 0, timeoutMs: 120000, maxTokens: Math.min(8192, model.maxTokens) }) });
      s.compactionAgent = summary;
      const source = JSON.stringify(s.agent.state.messages, (key, v) => key === 'data' ? '[binary data omitted]' : v);
      const abort = () => summary.abort(); s.agent.signal?.addEventListener('abort', abort, { once: true }); this.lifecycle.signal.addEventListener('abort', abort, { once: true });
      try { await summary.prompt(source); } finally { s.agent.signal?.removeEventListener('abort', abort); this.lifecycle.signal.removeEventListener('abort', abort); s.compactionAgent = null; }
      if (summary.state.errorMessage) throw new Error(summary.state.errorMessage);
      const text = summary.state.messages.filter(m => m.role === 'assistant').flatMap(m => m.content).filter(c => c.type === 'text').map(c => c.text).join('\n');
      if (!text) throw new Error('压缩没有产生摘要');
      s.agent.state.messages = [{ role: 'user', content: [{ type: 'text', text: `此前会话摘要：\n${text}\n恢复操作前重新观察页面。` }], timestamp: Date.now() }];
      s.contextChanged = true; await this.save(s); this.usage(s);
      this.emit(s.id, { sessionUpdate: 'tool_call_update', toolCallId, status: 'completed', rawOutput: { type: 'context_compaction', status: 'completed' }, _meta: { starweaveCompaction: { status: 'completed', reason: manual ? 'manual' : 'threshold' } } });
    } catch (e) {
      this.emit(s.id, { sessionUpdate: 'tool_call_update', toolCallId, status: 'failed', rawOutput: { type: 'context_compaction', status: 'failed', error: e.message }, _meta: { starweaveCompaction: { status: 'failed', reason: manual ? 'manual' : 'threshold' } } }); throw e;
    }
  }
  async prompt(s, blocks) {
    if (s.busy) throw new Error('会话正在执行');
    if (s.role === 'browser' && this.coordinator.active) throw new Error('浏览器正在执行其他任务，请稍后重试');
    s.busy = true; s.steps = 0; if (s.role === 'browser') this.coordinator.active = s.id;
    try {
      const command = blocks.filter(b => b.type === 'text').map(b => b.text).join('\n').trim();
      if (/^\/compact(?:\s|$)/.test(command)) { await this.compact(s, true); return { stopReason: 'end_turn' }; }
      const content = blocks.map(b => {
        if (b.type === 'text') return { type: 'text', text: b.text };
        if (b.type === 'image' && b.data) return { type: 'image', data: b.data, mimeType: b.mimeType };
        throw new Error('首版仅支持文字和内嵌图片，暂不支持文件附件');
      });
      const threshold = s.agent.state.model.contextWindow - Math.min(s.agent.state.model.maxTokens + 2048, s.agent.state.model.contextWindow / 3);
      if (s.agent.state.messages.length && this.estimate(s) + estimateMessage({ role: 'user', content }) >= threshold) await this.compact(s, false);
      await s.agent.prompt({ role: 'user', content, timestamp: Date.now() });
      const last = s.agent.state.messages.at(-1);
      if (last?.stopReason === 'error' || s.agent.state.errorMessage && last?.stopReason !== 'aborted') throw new Error(s.agent.state.errorMessage || last.errorMessage);
      return { stopReason: last?.stopReason === 'aborted' ? 'cancelled' : 'end_turn' };
    } finally {
      try { await this.save(s); } finally { s.busy = false; if (this.coordinator.active === s.id) { this.coordinator.active = null; await this.browser.release(); } }
    }
  }
  async save(s) {
    const snapshot = { version: 1, engine: 'pi-browser-0.75.3', sessionId: s.id, cwd: s.cwd, role: s.role, model: s.agent.state.model.id, messages: structuredClone(s.agent.state.messages), history: s.history, compactions: s.compactions };
    await this.store.put(snapshot); this.snapshots.queue(snapshot);
  }
  replay(s) {
    for (const m of s.history) {
      if (m.role === 'user') for (const c of Array.isArray(m.content) ? m.content : [{ type: 'text', text: m.content }]) if (c.type === 'text') this.emit(s.id, { sessionUpdate: 'user_message_chunk', content: c });
      if (m.role === 'assistant') for (const c of m.content) {
        if (c.type === 'text' || c.type === 'thinking') this.emit(s.id, { sessionUpdate: c.type === 'text' ? 'agent_message_chunk' : 'agent_thought_chunk', content: { type: 'text', text: c.text || c.thinking } });
        if (c.type === 'toolCall') this.emit(s.id, { sessionUpdate: 'tool_call', toolCallId: c.id, title: toolTitle(c.name, c.arguments), status: 'completed', kind: 'other', rawInput: preview(c.arguments) });
      }
      if (m.role === 'toolResult') this.emit(s.id, { sessionUpdate: 'tool_call_update', toolCallId: m.toolCallId, status: m.isError ? 'failed' : 'completed', rawOutput: preview(m) });
    }
  }
  async close() {
    if (this.closed) return; this.closed = true; this.lifecycle.abort();
    for (const p of this.pending.values()) p.reject(new Error('运行实例已关闭')); this.pending.clear();
    for (const s of this.sessions.values()) { s.agent.abort(); s.compactionAgent?.abort(); }
    await this.sessionOperations.catch(() => {});
    await Promise.allSettled([...this.sessions.values()].map(async s => { await s.agent.waitForIdle(); await this.save(s); await s.mcp.close(); this.coordinator.sessions.delete(s.id); }));
    this.sessions.clear();
  }
}
function toolTitle(name, args = {}) {
  if (name.startsWith('browser_')) {
    const detail = args.url || args.script || args.ref || args.selector || args.tabIds?.join(',') || args.tabId || args.downloadId || args.bookmarkId || args.key || '';
    return `${name}.${args.action || ''}${detail ? ' ' + detail : ''}`.slice(0, 500);
  }
  return String(args.command || args.script || args.path || args.url || args.ref || name).slice(0, 500);
}

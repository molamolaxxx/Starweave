import fs from 'node:fs/promises';
import path from 'node:path';
import { createAgentSession, AuthStorage, ModelRegistry, SessionManager, SettingsManager, DefaultResourceLoader,
  createReadToolDefinition, createWriteToolDefinition, createEditToolDefinition } from '@earendil-works/pi-coding-agent';
import { RequestError } from '@agentclientprotocol/sdk';
import { connectMcp } from './mcp.mjs';
import { commandTool } from './shell.mjs';

export function validateConfig(config) {
  const baseUrl = new URL(config.baseUrl);
  if (!['http:', 'https:'].includes(baseUrl.protocol) || baseUrl.username || baseUrl.password) throw new Error('API 地址必须是 HTTP/HTTPS Base URL');
  if (!config.model?.trim() || !config.apiKey?.trim()) throw new Error('请配置模型和 API Key');
  const contextWindow = config.contextWindow ?? 128000, maxTokens = config.maxTokens ?? 8192;
  if (!Number.isSafeInteger(contextWindow) || !Number.isSafeInteger(maxTokens) || contextWindow < 1024 || maxTokens < 1 || maxTokens + 512 >= contextWindow) throw new Error('上下文窗口必须大于最大输出 Token 数，并预留至少 512 Token');
  return { ...config, baseUrl: config.baseUrl.replace(/\/+$/, ''), contextWindow, maxTokens };
}

export function commandText(message) {
  let text = message.trimStart();
  if (text.startsWith('<acp-harness>')) {
    const end = text.indexOf('</acp-harness>');
    if (end >= 0) text = text.slice(end + '</acp-harness>'.length).trimStart();
  }
  return text.replace(/^\[Current Time: [^\r\n]*\]\r?\n/, '').trim();
}

export class EmbeddedPiAgent {
  constructor(connection, config) {
    this.connection = connection; this.config = validateConfig(config); this.sessions = new Map();
    this.clientCapabilities = {}; this.sessionDir = path.join(config.agentDir, 'sessions');
  }
  async initialize(params) {
    this.clientCapabilities = params.clientCapabilities || {};
    return { protocolVersion: 1, agentInfo: { name: 'starweave-embedded-pi', version: '1.0.0', title: '内嵌引擎 · Pi' }, authMethods: [],
      agentCapabilities: { loadSession: true, promptCapabilities: { image: true, embeddedContext: true }, mcpCapabilities: { http: true, sse: true }, sessionCapabilities: { list: {}, close: {}, resume: {}, compaction: {} } } };
  }
  async authenticate() { return {}; }
  options(entry) {
    return [{ id: 'model', name: '模型', category: 'model', type: 'select', currentValue: entry.pi.model.id,
      options: [{ value: entry.pi.model.id, name: entry.pi.model.name }] }];
  }
  model(registry, id) {
    registry.registerProvider('starweave', { api: 'openai-completions', baseUrl: this.config.baseUrl, apiKey: this.config.apiKey, authHeader: true,
      models: [{ id, name: id, reasoning: false, input: ['text', 'image'], contextWindow: this.config.contextWindow, maxTokens: this.config.maxTokens,
        cost: { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 }, compat: { supportsStore: false, supportsReasoningEffort: false, maxTokensField: 'max_tokens' } }] });
    return registry.find('starweave', id);
  }
  async create(params, manager) {
    const cwd = path.resolve(params.cwd);
    await fs.mkdir(this.sessionDir, { recursive: true });
    const mcp = await connectMcp(params.mcpServers, cwd);
    try {
      const registry = ModelRegistry.inMemory(AuthStorage.inMemory());
      const model = this.model(registry, this.config.model);
      const reserveTokens = Math.min(this.config.contextWindow - 512, this.config.maxTokens + Math.max(512, Math.floor(this.config.contextWindow * .05)));
      const settingsManager = SettingsManager.inMemory({ compaction: { enabled: true, reserveTokens, keepRecentTokens: Math.max(256, Math.min(20000, Math.floor((this.config.contextWindow - reserveTokens) / 2))) }, retry: { enabled: false, provider: { maxRetries: 0, timeoutMs: 120000 } }, packages: [] });
      const loader = new DefaultResourceLoader({ cwd, agentDir: this.config.agentDir, settingsManager, noExtensions: true, noThemes: true,
        additionalSkillPaths: [path.join(cwd, '.agents', 'skills')], appendSystemPrompt: [`You are an agent embedded in Starweave. The shell tool executes ${process.platform === 'win32' ? 'PowerShell' : 'POSIX shell'} commands. Follow the ACP harness supplied in user messages.`] });
      await loader.reload();
      const entry = { pi: null, cwd, mcp, tail: Promise.resolve(), busy: false, cancelled: false, edits: new Map(), tools: new Map(), unsubscribe: null, compactionCounter: 0 };
      const builtins = [createReadToolDefinition(cwd), createEditToolDefinition(cwd), createWriteToolDefinition(cwd), commandTool(cwd)];
      const names = new Set(builtins.map(tool => tool.name));
      const customTools = [...builtins, ...mcp.tools].map(tool => {
        if (!names.has(tool.name)) names.add(tool.name);
        else if (!builtins.includes(tool)) throw new Error(`MCP tool conflicts with built-in tool: ${tool.name}`);
        return { ...tool, execute: async (id, args, signal, update, ctx) => {
          if (entry.edits.has(id)) await entry.edits.get(id);
          await this.permission(entry, id, tool.name, args, signal);
          return tool.execute(id, args, signal, update, ctx);
        } };
      });
      const result = await createAgentSession({ cwd, agentDir: this.config.agentDir, authStorage: registry.authStorage, modelRegistry: registry, model, thinkingLevel: 'off',
        sessionManager: manager || SessionManager.create(cwd, this.sessionDir), settingsManager, resourceLoader: loader, tools: [...names], customTools });
      entry.pi = result.session;
      entry.unsubscribe = entry.pi.subscribe(event => this.event(entry, event));
      this.sessions.set(entry.pi.sessionId, entry);
      await this.emit(entry, { sessionUpdate: 'available_commands_update', availableCommands: [{ name: 'compact', description: '压缩当前上下文', input: { hint: '可选压缩指令' } }] });
      return entry;
    } catch (error) { await mcp.close(); throw error; }
  }
  async newSession(params) {
    const entry = await this.create(params);
    return { sessionId: entry.pi.sessionId, configOptions: this.options(entry) };
  }
  async findSession(id, cwd) {
    const entries = await SessionManager.list(cwd || undefined, this.sessionDir);
    const found = entries.find(entry => entry.id === id);
    if (!found) throw RequestError.invalidParams('会话不存在');
    return found;
  }
  async restoreSession(params, replay) {
    if (this.sessions.has(params.sessionId)) await this.closeSession(params);
    const info = await this.findSession(params.sessionId, params.cwd);
    const entry = await this.create(params, SessionManager.open(info.path, this.sessionDir, params.cwd));
    const history = replay ? entry.pi.sessionManager.getBranch().filter(item => item.type === 'message').map(item => item.message) : [];
    for (const message of history) {
      if (message.role === 'assistant' || message.role === 'user') {
        const blocks = typeof message.content === 'string' ? [{ type: 'text', text: message.content }] : message.content;
        for (const block of blocks || []) {
          if (block.type === 'text') await this.emit(entry, { sessionUpdate: message.role === 'user' ? 'user_message_chunk' : 'agent_message_chunk', content: block });
          else if (block.type === 'thinking') await this.emit(entry, { sessionUpdate: 'agent_thought_chunk', content: { type: 'text', text: block.thinking } });
          else if (block.type === 'toolCall') await this.emit(entry, { sessionUpdate: 'tool_call', toolCallId: block.id, title: block.name, kind: this.kind(block.name), status: 'completed', rawInput: block.arguments });
        }
      } else if (message.role === 'toolResult') await this.emit(entry, { sessionUpdate: 'tool_call_update', toolCallId: message.toolCallId, status: message.isError ? 'failed' : 'completed', content: this.content(message.content), rawOutput: { content: message.content, details: message.details } });
    }
    await this.usage(entry);
    return { configOptions: this.options(entry) };
  }
  async loadSession(params) { return this.restoreSession(params, true); }
  async resumeSession(params) { const result = await this.restoreSession(params, false); return { sessionId: params.sessionId, ...result }; }
  async listSessions(params) {
    const sessions = await SessionManager.list(params.cwd || undefined, this.sessionDir);
    return { sessions: sessions.map(info => ({ sessionId: info.id, cwd: info.cwd, title: info.name || info.firstMessage || 'Pi 会话', updatedAt: info.modified.toISOString() })) };
  }
  get(id) { const entry = this.sessions.get(id); if (!entry) throw RequestError.invalidParams('会话未加载'); return entry; }
  async closeSession(params) {
    const entry = this.sessions.get(params.sessionId); if (!entry) return {};
    if (!entry.closing) entry.closing = (async () => {
      await this.cancel(params); await entry.running?.catch(() => {}); await entry.tail.catch(() => {});
      entry.unsubscribe?.(); entry.pi.dispose(); await entry.mcp.close(); this.sessions.delete(params.sessionId); return {};
    })();
    return entry.closing;
  }
  async shutdown() { await Promise.allSettled([...this.sessions.keys()].map(sessionId => this.closeSession({ sessionId }))); }
  async cancel(params) { const entry = this.sessions.get(params.sessionId); if (entry) { entry.cancelled = true; entry.permissionAbort?.abort(); entry.pi.abortCompaction(); await entry.pi.abort(); } }
  async setSessionConfigOption(params) {
    if (params.configId !== 'model') throw RequestError.invalidParams('未知配置项');
    const entry = this.get(params.sessionId); if (entry.busy) throw RequestError.invalidParams('请等待当前回复结束');
    if (!params.value?.trim()) throw RequestError.invalidParams('模型不能为空');
    await entry.pi.setModel(this.model(entry.pi.modelRegistry, params.value.trim()));
    return { configOptions: this.options(entry) };
  }
  async unstable_setSessionModel(params) { return this.setSessionConfigOption({ ...params, configId: 'model', value: params.modelId }); }
  async permission(entry, toolCallId, name, args, signal) {
    signal?.throwIfAborted();
    // The existing ACP client's permission policy remains authoritative.
    const pending = this.connection.requestPermission({ sessionId: entry.pi.sessionId,
      toolCall: { toolCallId, title: name, kind: this.kind(name), status: 'pending', rawInput: args },
      options: [{ optionId: 'allow-once', name: '允许本次', kind: 'allow_once' }, { optionId: 'allow-always', name: '允许', kind: 'allow_always' }, { optionId: 'reject-once', name: '拒绝', kind: 'reject_once' }] });
    const controller = entry.permissionAbort;
    let listener;
    const aborted = new Promise((_, reject) => {
      listener = () => reject(new Error('Tool cancelled'));
      controller.signal.addEventListener('abort', listener, { once: true });
    });
    try {
      const response = await Promise.race([pending, aborted]);
      if (response.outcome.outcome !== 'selected' || !response.outcome.optionId.startsWith('allow-')) throw new Error('Tool permission denied');
    } finally { controller.signal.removeEventListener('abort', listener); }
    signal?.throwIfAborted();
  }
  emit(entry, update) {
    entry.tail = entry.tail.then(() => this.connection.sessionUpdate({ sessionId: entry.pi.sessionId, update }));
    // Attach a rejection observer immediately; prompt still sees the failed delivery.
    entry.tail.catch(() => {}); return entry.tail;
  }
  kind(name) { return name === 'read' ? 'read' : ['write', 'edit'].includes(name) ? 'edit' : name === 'shell' ? 'execute' : 'other'; }
  content(blocks) { return (blocks || []).filter(block => ['text', 'image'].includes(block.type)).map(content => ({ type: 'content', content })); }
  event(entry, event) {
    if (event.type === 'message_update') {
      const delta = event.assistantMessageEvent;
      if (['text_delta', 'thinking_delta'].includes(delta.type)) this.emit(entry, { sessionUpdate: delta.type === 'text_delta' ? 'agent_message_chunk' : 'agent_thought_chunk', content: { type: 'text', text: delta.delta } });
    } else if (event.type === 'tool_execution_start') {
      const tool = { sessionUpdate: 'tool_call', toolCallId: event.toolCallId, title: event.toolName, kind: this.kind(event.toolName), status: 'in_progress', rawInput: event.args };
      if (event.args?.path) tool.locations = [{ path: path.resolve(entry.cwd, event.args.path) }];
      entry.tools.set(event.toolCallId, tool); this.emit(entry, tool);
      if (['write', 'edit'].includes(event.toolName) && event.args?.path) {
        const file = path.resolve(entry.cwd, event.args.path);
        // Queue snapshots before execution can finish; the tool-end event waits for them.
        entry.edits.set(event.toolCallId, fs.readFile(file, 'utf8').catch(() => '').then(oldText => ({ file, oldText })));
      }
    } else if (event.type === 'tool_execution_update' || event.type === 'tool_execution_end') {
      const result = event.result || event.partialResult;
      const failed = event.isError || result?.details?.timedOut || (result?.details?.exitCode != null && result.details.exitCode !== 0);
      const update = { sessionUpdate: 'tool_call_update', toolCallId: event.toolCallId, status: event.type === 'tool_execution_end' ? (failed ? 'failed' : 'completed') : 'in_progress', content: this.content(result?.content) };
      if (event.type === 'tool_execution_end') {
        update.rawInput = entry.tools.get(event.toolCallId)?.rawInput;
        update.rawOutput = { content: result?.content || [], details: result?.details || {} };
      }
      const snapshot = event.type === 'tool_execution_end' && entry.edits.get(event.toolCallId);
      if (snapshot && !failed) {
        entry.tail = entry.tail.then(async () => {
          const { file, oldText } = await snapshot;
          const newText = await fs.readFile(file, 'utf8').catch(() => oldText);
          update.content.push({ type: 'diff', path: file, oldText, newText });
          await this.connection.sessionUpdate({ sessionId: entry.pi.sessionId, update });
        }); entry.tail.catch(() => {});
      } else this.emit(entry, update);
      if (event.type === 'tool_execution_end') { entry.tools.delete(event.toolCallId); entry.edits.delete(event.toolCallId); }
    } else if (event.type === 'compaction_start' || event.type === 'compaction_end') {
      const status = event.type === 'compaction_start' ? 'in_progress' : event.aborted || event.errorMessage || !event.result ? 'failed' : 'completed';
      // ACP v1 SDKs do not all accept compaction_update. Use valid tool events with
      // machine-readable metadata; the Starweave provider projects a compaction card.
      if (event.type === 'compaction_start') entry.compactionCounter++;
      this.emit(entry, { sessionUpdate: event.type === 'compaction_start' ? 'tool_call' : 'tool_call_update',
        toolCallId: `compaction-${entry.compactionCounter}`, title: '压缩上下文', kind: 'other', status,
        _meta: { starweaveCompaction: { status, reason: event.reason } } });
      if (event.type === 'compaction_end') this.usage(entry);
    }
  }
  usage(entry) {
    const context = entry.pi.getContextUsage();
    return this.emit(entry, { sessionUpdate: 'usage_update', used: context?.tokens || 0, size: this.config.contextWindow });
  }
  async prompt(params) {
    const entry = this.get(params.sessionId); if (entry.busy || entry.closing) throw RequestError.invalidParams('会话正在回复或关闭');
    entry.busy = true; entry.cancelled = false; entry.permissionAbort = new AbortController();
    const run = async () => {
      try {
        const text = [], images = [];
        for (const block of params.prompt) {
          if (block.type === 'text') text.push(block.text);
          else if (block.type === 'image') images.push({ type: 'image', data: block.data, mimeType: block.mimeType });
          else if (block.type === 'resource' && block.resource?.text) text.push(block.resource.text);
          else if (block.type === 'resource_link') text.push(`${block.name || 'Resource'}: ${block.uri}`);
          else throw RequestError.invalidParams(`不支持的内容类型: ${block.type}`);
        }
        const message = text.join('\n');
        const command = commandText(message), compact = !images.length && /^\/compact(?:\s|$)/.test(command);
        if (compact) await entry.pi.compact(command.slice(8).trim() || undefined);
        else await entry.pi.prompt(message, { images });
        await this.usage(entry); await entry.tail;
        const last = [...entry.pi.messages].reverse().find(message => message.role === 'assistant');
        if (!compact && !entry.cancelled && (last?.stopReason === 'error' || last?.stopReason === 'aborted')) throw new Error(last.errorMessage || '模型请求失败');
        return { stopReason: entry.cancelled ? 'cancelled' : !compact && last?.stopReason === 'length' ? 'max_tokens' : 'end_turn' };
      } catch (error) {
        await entry.tail.catch(() => {});
        if (entry.cancelled) return { stopReason: 'cancelled' };
        throw RequestError.internalError(String(error.message || error).replaceAll(this.config.apiKey, '[REDACTED]'));
      } finally { entry.busy = false; entry.tools.clear(); entry.edits.clear(); }
    };
    entry.running = run(); return entry.running;
  }
  async extMethod(method, params) {
    if (['session/end', '_session/end'].includes(method)) return this.closeSession(params);
    throw RequestError.methodNotFound(method);
  }
}

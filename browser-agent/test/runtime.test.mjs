import test from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { indexedDB } from 'fake-indexeddb';
import { BrowserRuntime, modelConfig, preview, repairMessages } from '../src/runtime.mjs';
import { SessionStore, normalizeSettings, sha256, encode64 } from '../src/storage.mjs';
import { BrowserTools } from '../src/browser-tools.mjs';
import { Snapshots } from '../src/snapshots.mjs';

function sse(response, message, reason = 'stop') {
  response.writeHead(200, { 'content-type': 'text/event-stream' });
  response.write(`data: ${JSON.stringify({ id: 'completion-1', object: 'chat.completion.chunk', choices: [{ index: 0, delta: { role: 'assistant', ...message }, finish_reason: null }] })}\n\n`);
  response.write(`data: ${JSON.stringify({ id: 'completion-1', choices: [{ index: 0, delta: {}, finish_reason: reason }], usage: { prompt_tokens: 10, completion_tokens: 5, total_tokens: 15 } })}\n\n`);
  response.end('data: [DONE]\n\n');
}
test('settings validate and normalize without website/JS switches', () => {
  assert.deepEqual(normalizeSettings({ url: 'http://localhost:10528/', name: 'browser-office' }), { url: 'http://localhost:10528', name: 'browser-office', accessToken: '' });
  for (const url of ['file:///etc/passwd', 'https://user:password@example.com', 'http://host/?token=secret']) assert.throws(() => normalizeSettings({ url, name: 'test' }));
  assert.throws(() => normalizeSettings({ url: 'http://host', name: 'bad name' }));
});
test('broken tool calls and UTF8 preview bounds', () => {
  const repaired = repairMessages([{ role: 'assistant', content: [{ type: 'toolCall', id: '1', name: 'click' }] }]);
  assert.equal(repaired[1].role, 'toolResult'); assert.equal(repaired[1].isError, true);
  assert.ok(new TextEncoder().encode(JSON.stringify(preview({ content: '中文'.repeat(100000) }))).length < 65536);
  assert.equal(preview({ data: 'a'.repeat(2000) }).data, '[binary data omitted]');
});
test('IndexedDB stores per-center identity and snapshot conflicts preserve local', async () => {
  const store = new SessionStore('center-A', indexedDB), other = new SessionStore('center-B', indexedDB);
  const snapshot = { version: 1, engine: 'pi-browser-0.75.3', sessionId: 'one', cwd: '/browser/id', messages: [], history: [] };
  await store.put(snapshot); assert.equal(await other.get('one'), undefined);
  let remote, hash = '';
  const snapshots = new Snapshots(store, async frame => {
    if (frame.data) { remote = frame.data; hash = frame.hash; return { hash }; }
    return { hash, ...(remote && hash !== frame.local ? { data: remote } : {}) };
  });
  snapshots.queue(snapshot); await snapshots.flush();
  assert.equal(await store.access('base-one'), hash);
  await store.put({ ...snapshot, messages: [{ role: 'user', content: 'local edit' }] });
  const changed = { ...snapshot, messages: [{ role: 'user', content: 'remote edit' }] };
  remote = encode64(JSON.stringify(changed)); hash = await sha256(JSON.stringify(changed));
  await assert.rejects(snapshots.restore('one'), /冲突|均有修改/);
  assert.equal((await store.get('one')).messages[0].content, 'local edit'); snapshots.stop();
});
test('real Pi stream executes tools, emits ACP updates, isolates memory, cancels and restores', async t => {
  let calls = [], toolRuns = 0, hang = false, forceTool = false, allowPermissions = true;
  const server = http.createServer(async (req, res) => {
    const chunks = []; for await (const c of req) chunks.push(c); const body = JSON.parse(Buffer.concat(chunks)); calls.push(body);
    assert.equal(req.headers.authorization, 'Bearer test-key');
    if (hang) { res.writeHead(200, { 'content-type': 'text/event-stream' }); res.write(': waiting\n\n'); return; }
    if (forceTool) sse(res, { tool_calls: [{ index: 0, id: 'tabs-pending', type: 'function', function: { name: 'list_tabs', arguments: '{}' } }] }, 'tool_calls');
    else if (body.messages.some(m => m.role === 'tool')) sse(res, { content: '页面清单已读取' });
    else if (body.tools?.some(tool => tool.function.name === 'list_tabs')) sse(res, { tool_calls: [{ index: 0, id: 'tabs-1', type: 'function', function: { name: 'list_tabs', arguments: '{}' } }] }, 'tool_calls');
    else sse(res, { content: '[]' });
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve)); t.after(() => { server.closeAllConnections(); server.close(); });
  const config = { baseUrl: `http://127.0.0.1:${server.address().port}`, model: 'browser-model', apiKey: 'test-key', contextWindow: 100000, maxTokens: 1000 };
  const store = new SessionStore(crypto.randomUUID(), indexedDB), coordinator = { active: null, sessions: new Set() }, sent = [];
  let runtime;
  const browser = { release: async () => {}, tools: () => [{ name: 'list_tabs', label: 'list_tabs', description: 'List tabs', parameters: { type: 'object', properties: {} }, execute: async () => { toolRuns++; return { content: [{ type: 'text', text: '[{"tabId":1}]' }] }; } }] };
  const snapshots = { queue: () => {}, restore: id => store.get(id) };
  runtime = new BrowserRuntime({ id: 'runtime-one', config, store, snapshots, hostTools: async () => ({ tools: [], executionNode: 'test host', os: 'Linux', cwd: '/workspace', shell: '/bin/sh', systemPrompt: '' }), browser, coordinator, send: msg => {
    sent.push(msg);
    if (allowPermissions && msg.method === 'session/request_permission') queueMicrotask(() => runtime.handle({ id: msg.id, result: { outcome: { outcome: 'selected', optionId: 'allow_once' } } }));
  } });
  t.after(() => runtime.close());
  assert.equal((await runtime.request('initialize', {})).agentCapabilities.loadSession, true);
  const main = await runtime.request('session/new', { cwd: '/browser/id', mcpServers: [] });
  assert.equal((await runtime.request('session/prompt', { sessionId: main.sessionId, prompt: [{ type: 'text', text: '打开了哪些页面？' }] })).stopReason, 'end_turn');
  assert.equal(toolRuns, 1); assert.equal(calls.length, 2);
  assert.ok(sent.some(m => m.params?.update?.sessionUpdate === 'tool_call'));
  assert.ok(sent.some(m => m.params?.update?.content?.text === '页面清单已读取'));
  const memory = await runtime.request('session/new', { cwd: '/browser/id', mcpServers: [], _starweaveRole: 'memory' });
  assert.equal(runtime.sessions.get(memory.sessionId).agent.state.tools.length, 0);
  await runtime.request('session/prompt', { sessionId: memory.sessionId, prompt: [{ type: 'text', text: '提取记忆' }] });
  assert.equal(toolRuns, 1); assert.ok(!calls.at(-1).tools?.length);
  const snapshot = await store.get(main.sessionId); assert.equal(snapshot.messages.length, 4); assert.equal(snapshot.history.length, 4);
  await runtime.request('session/close', { sessionId: main.sessionId });
  await runtime.request('session/resume', { cwd: '/browser/id', sessionId: main.sessionId, mcpServers: [] }); assert.equal(toolRuns, 1);
  coordinator.active = 'another-session';
  await assert.rejects(runtime.request('session/prompt', { sessionId: main.sessionId, prompt: [{ type: 'text', text: '忙碌测试' }] }), /其他任务/);
  coordinator.active = null;
  await runtime.request('session/prompt', { sessionId: main.sessionId, prompt: [{ type: 'text', text: '/compact' }] });
  assert.equal((await store.get(main.sessionId)).messages.length, 1);
  assert.ok(sent.some(m => m.params?.update?._meta?.starweaveCompaction?.status === 'completed'));
  forceTool = true; allowPermissions = false;
  const permissionCount = sent.filter(m => m.method === 'session/request_permission').length;
  const blocked = runtime.request('session/prompt', { sessionId: main.sessionId, prompt: [{ type: 'text', text: '等待权限' }] });
  while (sent.filter(m => m.method === 'session/request_permission').length === permissionCount) await new Promise(resolve => setTimeout(resolve, 10));
  await runtime.request('session/cancel', { sessionId: main.sessionId });
  assert.equal((await blocked).stopReason, 'cancelled'); assert.equal(toolRuns, 1);
  forceTool = false; allowPermissions = true;
  const before = calls.length; hang = true;
  const pending = runtime.request('session/prompt', { sessionId: main.sessionId, prompt: [{ type: 'text', text: '等待' }] });
  while (calls.length === before) await new Promise(resolve => setTimeout(resolve, 10));
  await runtime.request('session/cancel', { sessionId: main.sessionId }); assert.equal((await pending).stopReason, 'cancelled');
  assert.equal(coordinator.active, null);
});

test('cancel stops waiting for a non-resolving Chrome command without retrying it', async () => {
  let commands = 0;
  const api = { debugger: { attach: async () => {}, sendCommand: () => { commands++; return new Promise(() => {}); } } };
  const browser = new BrowserTools(api), controller = new AbortController();
  const pending = browser.tools().find(t => t.name === 'browser_page').execute('call-one', { action: 'evaluate', tabId: 1, script: 'new Promise(()=>{})' }, controller.signal);
  await new Promise(resolve => setTimeout(resolve, 10)); controller.abort();
  await assert.rejects(pending, /取消/); assert.equal(commands, 1);
});

test('load and resume preserve context across workspace changes while retaining engine and role checks', async () => {
  for (const method of ['session/load', 'session/resume']) {
    const store = new SessionStore(crypto.randomUUID(), indexedDB);
    const snapshot = { version: 1, engine: 'pi-browser-0.75.3', sessionId: 'existing-session', cwd: '/old/workspace', role: 'browser', model: 'model', messages: [{ role: 'user', content: 'Keep previous context', timestamp: 1 }], history: [], compactions: 0 };
    await store.put(snapshot);
    const runtime = new BrowserRuntime({ id: 'runtime', config: { baseUrl: 'http://localhost/v1', model: 'model', apiKey: 'key', contextWindow: 16000, maxTokens: 1000 },
      send: () => {}, hostTools: async () => ({ tools: [], cwd: '/new/workspace', executionNode: 'center', os: 'Linux', shell: '/bin/sh' }), store,
      snapshots: { restore: id => store.get(id), queue: () => {} }, browser: { tools: () => [], release: async () => {} }, coordinator: { active: null, sessions: new Set() } });
    try {
      const loaded = await runtime.request(method, { sessionId: snapshot.sessionId, cwd: '/new/workspace' });
      assert.equal(loaded.sessionId, snapshot.sessionId);
      const state = runtime.sessions.get(loaded.sessionId);
      assert.deepEqual(state.agent.state.messages, snapshot.messages);
      assert.ok(state.agent.state.systemPrompt.includes('/new/workspace'));
      assert.equal((await store.get(loaded.sessionId)).cwd, '/new/workspace');
      await runtime.request('session/close', { sessionId: loaded.sessionId });
      await store.put({ ...snapshot, role: 'memory' });
      await assert.rejects(runtime.request(method, { sessionId: snapshot.sessionId, cwd: '/new/workspace' }), /归属不匹配/);
      await store.put({ ...snapshot, engine: 'another-engine' });
      await assert.rejects(runtime.request(method, { sessionId: snapshot.sessionId, cwd: '/new/workspace' }), /版本或归属不匹配/);
    } finally { await runtime.close(); }
  }
});

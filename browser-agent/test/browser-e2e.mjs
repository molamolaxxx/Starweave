// Real installed MV3 extension + real Chrome pages + mock center/model/MCP.
// CHROME_BIN may point to a Chromium executable that supports --load-extension.
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { chromium } from 'playwright-core';
import { WebSocketServer } from 'ws';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const profile = await mkdtemp(path.join(tmpdir(), 'starweave-browser-e2e-'));
const externalCenter = process.env.STARWEAVE_TEST_CENTER;
let socket, sequence = 0, tabId, modelCalls = 0, mcpCalls = 0, forcedDisconnects = 0;
const events = [], waiting = new Map(), frames = [], snapshots = new Map();
const skill = '---\nname: test-orders\ndescription: Read orders using browser tools\n---\nRead references/status.md before querying.';
const config = { model: 'test-model', apiKey: 'test-key', contextWindow: 100000, maxTokens: 2000 };
function sse(res, delta, reason = 'stop') {
  res.writeHead(200, { 'content-type': 'text/event-stream' });
  res.write(`data: ${JSON.stringify({ id: 'e2e', choices: [{ index: 0, delta: { role: 'assistant', ...delta }, finish_reason: null }] })}\n\n`);
  res.write(`data: ${JSON.stringify({ id: 'e2e', choices: [{ index: 0, delta: {}, finish_reason: reason }] })}\n\n`);
  res.end('data: [DONE]\n\n');
}
const server = http.createServer(async (req, res) => {
  res.setHeader('access-control-allow-origin', '*'); res.setHeader('access-control-allow-headers', '*');
  if (req.method === 'OPTIONS') { res.writeHead(204); res.end(); return; }
  if (req.url.startsWith('/api/remote-acp/discover')) { res.setHeader('content-type', 'application/json'); res.end(JSON.stringify({ protocolVersion: 1, webSocketUrl: `ws://127.0.0.1:${server.address().port}/ws` })); return; }
  if (req.url === '/fixture') { res.setHeader('content-type', 'text/html; charset=utf-8'); res.end('<!doctype html><title>订单测试页面</title><label>订单号<input id="order" placeholder="订单号"></label><button id="query" onclick="window.mutations=(window.mutations||0)+1;document.querySelector(\'#result\').textContent=document.querySelector(\'#order\').value">查询</button><div id="result"></div><input id="upload" type="file"><a href="https://example.com">资料链接</a>'); return; }
  const chunks = []; for await (const c of req) chunks.push(c); const body = JSON.parse(Buffer.concat(chunks).toString() || '{}');
  if (req.url === '/mcp') {
    if (req.method !== 'POST') { res.writeHead(405); res.end(); return; }
    if (body.id === undefined) { res.writeHead(202); res.end(); return; }
    let result = {};
    if (body.method === 'initialize') result = { protocolVersion: body.params.protocolVersion, capabilities: { tools: {} }, serverInfo: { name: 'e2e-mcp', version: '1' } };
    if (body.method === 'tools/list') result = { tools: [{ name: 'mcp_echo', description: 'Echo text', inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'] }, outputSchema: { type: 'object', properties: { echo: { type: 'string' } }, required: ['echo'] } }] };
    if (body.method === 'tools/call') { mcpCalls++;
      if (externalCenter && mcpCalls === 1) { const dropped = await fetch(`${externalCenter}/test/disconnect`, { method: 'POST' }); assert.ok(dropped.ok); forcedDisconnects++; }
      result = { content: [{ type: 'text', text: body.params.arguments.text }], structuredContent: { echo: body.params.arguments.text } }; }
    res.setHeader('content-type', 'application/json'); res.end(JSON.stringify({ jsonrpc: '2.0', id: body.id, result })); return;
  }
  if (req.url === '/chat/completions') {
    modelCalls++; assert.equal(req.headers.authorization, 'Bearer test-key');
    const results = body.messages.filter(m => m.role === 'tool');
    let name, args;
    if (!body.tools?.length) { sse(res, { content: '[]' }); return; }
    const n = results.length;
    if (n === 0) { name = 'list_tabs'; args = {}; }
    if (n === 1) { const tabs = JSON.parse(results[0].content); tabId = tabs.find(t => t.url?.endsWith('/fixture')).tabId; name = 'read_page'; args = { tabId }; }
    if (n === 2) { const page = JSON.parse(results[1].content); name = 'fill'; args = { tabId, ref: page.elements.find(e => e.tag === 'input').ref, text: 'ORDER-2026' }; }
    if (n === 3) { const page = JSON.parse(results[1].content); name = 'click'; args = { tabId, ref: page.elements.find(e => e.tag === 'button').ref }; }
    if (n === 4 && forcedDisconnects === 0) {
      forcedDisconnects++;
      if (externalCenter) { const r = await fetch(`${externalCenter}/test/disconnect`, { method: 'POST' }); assert.ok(r.ok); }
      else socket.terminate();
    }
    if (n === 4) { name = 'evaluate_js'; args = { tabId, script: '({html:document.documentElement.outerHTML,result:document.querySelector("#result").textContent,mutations:window.mutations})' }; }
    if (n === 5) { assert.equal(JSON.parse(results[4].content).result, 'ORDER-2026'); name = 'screenshot'; args = { tabId }; }
    if (n === 6) { name = 'read'; args = { path: `${config.cwd || '/workspace'}/.agents/skills/test-orders/references/status.md` }; }
    if (n === 7) { name = 'mcp_echo'; args = { text: 'browser-mcp-ok' }; }
    if (n === 8) { name = 'browser_files'; args = { action: 'set_input', tabId, selector: '#upload', hostPaths: [`${config.cwd || '/workspace'}/.agents/skills/test-orders/references/status.md`] }; }
    if (n >= 9) { sse(res, { content: '浏览器全链路完成' }); return; }
    if(externalCenter) console.log('E2E model tool', modelCalls, n, name);
    const grouped = { list_tabs: ['browser_tabs', 'list'], read_page: ['browser_page', 'read'], fill: ['browser_form', 'fill'], click: ['browser_mouse', 'click'], evaluate_js: ['browser_page', 'evaluate'], screenshot: ['browser_capture', 'viewport'] };
    if (grouped[name]) { const pair = grouped[name]; name = pair[0]; args = { ...args, action: pair[1] }; }
    sse(res, { tool_calls: [{ index: 0, id: `tool-${n}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] }, 'tool_calls'); return;
  }
  res.writeHead(404); res.end();
});
const wss = new WebSocketServer({ server });
let resumeSent = 0, resumeReceived = 0, resumeToken;
const resumePending = new Map();
const send = frame => { const f = { type: "resume_frame", seq: ++resumeSent, payload: frame }; resumePending.set(f.seq, f); socket.send(JSON.stringify(f)); };
wss.on('connection', ws => {
  socket = ws;
  ws.on('message', raw => {
    let f = JSON.parse(raw);
    if (f.type === 'register') {
      const resumed = resumeToken === f.resumeToken;
      if (!resumed) { resumeSent = 0; resumeReceived = 0; resumePending.clear(); resumeToken = f.resumeToken; }
      for (const id of resumePending.keys()) if (id <= f.resumeReceived) resumePending.delete(id);
      ws.send(JSON.stringify({ type: 'resume_ready', resumed, received: resumeReceived }));
      for (const item of resumePending.values()) ws.send(JSON.stringify(item));
      if (resumed) return;
    } else if (f.type === 'resume_ack') { resumePending.delete(f.seq); return; }
    else if (f.type === 'resume_frame') {
      ws.send(JSON.stringify({ type: 'resume_ack', seq: f.seq }));
      if (f.seq <= resumeReceived) return;
      assert.equal(f.seq, resumeReceived + 1); resumeReceived = f.seq; f = f.payload;
    }
    frames.push(f);
    if (f.type === 'register') { assert.equal(f.platform, 'BROWSER'); send({ type: 'registered', sessionSyncVersion: 1 }); send({ type: 'config', version: 'one', config }); }
    if (f.type === 'ping') send({ type: 'pong' });
    if (f.type === 'browser_tool') {
      if (f.operation === 'cancel') return;
      let result = {};
      if (f.operation === 'prepare') result = { tools: ['read', 'write', 'edit', 'shell', 'mcp_echo'].map(name => ({ name, description: name, parameters: { type: 'object', properties: name === 'mcp_echo' ? { text: { type: 'string' } } : { path: { type: 'string' } } } })), executionNode: 'test center', cwd: '/workspace', os: 'Linux', shell: '/bin/sh', systemPrompt: skill };
      if (f.operation === 'read_files') result = { files: [{ name: 'status.md', mimeType: 'text/plain', dataBase64: btoa('pending means waiting') }] };
      if (f.operation === 'call') { if (f.name === 'mcp_echo') mcpCalls++; result = { content: [{ type: 'text', text: f.name === 'read' ? 'pending means waiting' : f.arguments.text }] }; }
      send({ type: 'browser_tool_result', requestId: f.requestId, result });
    }
    if (f.type === 'sessions_request') {
      if (f.data) snapshots.set(f.sessionId, { data: f.data, hash: f.hash });
      send({ type: 'sessions_response', requestId: f.requestId, hash: snapshots.get(f.sessionId)?.hash || '', ...(f.pull ? snapshots.get(f.sessionId) : {}) });
    }
    if (f.type === 'acp') {
      const m = f.message;
      if (m.method === 'session/request_permission') send({ type: 'acp', runtimeId: f.runtimeId, message: { jsonrpc: '2.0', id: m.id, result: { outcome: { outcome: 'selected', optionId: 'allow_once' } } } });
      if (m.method === 'session/update') events.push(m.params.update);
      if (!m.method && waiting.has(m.id)) { const pending = waiting.get(m.id); waiting.delete(m.id); m.error ? pending.reject(new Error(m.error.message)) : pending.resolve(m.result); }
    }
  });
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const base = `http://127.0.0.1:${server.address().port}`; config.baseUrl = base;
let context;
async function request(method, params) {
  const id = ++sequence;
  if (externalCenter) {
    const response = await fetch(`${externalCenter}/test/acp`, { method: 'POST', body: JSON.stringify({ jsonrpc: '2.0', id, method, params }), signal: AbortSignal.timeout(45000) });
    const value = await response.json();
    if (value.cwd) config.cwd = value.cwd;
    if (!response.ok || value.error) throw new Error(JSON.stringify(value));
    events.push(...value.events); return value.result;
  }
  return new Promise((resolve, reject) => {
    const timer = setTimeout(() => { waiting.delete(id); reject(new Error(`ACP timeout: ${method}; recent=${JSON.stringify(events.slice(-3))}`)); }, 20000);
    waiting.set(id, { resolve: value => { clearTimeout(timer); resolve(value); }, reject: e => { clearTimeout(timer); reject(e); } });
    send({ type: 'acp', runtimeId: 'e2e-runtime', message: { jsonrpc: '2.0', id, method, params } });
  });
}
try {
  context = await chromium.launchPersistentContext(profile, { executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true,
    args: [`--disable-extensions-except=${root}/dist`, `--load-extension=${root}/dist`, '--no-sandbox'] });
  const worker = context.serviceWorkers()[0] || await context.waitForEvent('serviceworker'); const extId = new URL(worker.url()).host;
  const page = await context.newPage(); await page.goto(`${base}/fixture`);
  const options = await context.newPage(); await options.goto(`chrome-extension://${extId}/options.html`);
  await options.locator('#url').fill(externalCenter || base); await options.locator('#name').fill('browser-e2e'); await options.locator('#save').click();
  if (externalCenter) {
    const deadline=Date.now()+15000;
    for (;;) {
      const response=await fetch(`${externalCenter}/test/config`,{method:'POST',body:JSON.stringify(config)});
      if(response.ok){ const data=await response.json();config.cwd=data.cwd;break; }
      if(Date.now()>deadline)throw new Error(`Browser did not register with Java center; UI=${await options.locator('main').innerText()}`);
      await new Promise(r=>setTimeout(r,100));
    }
  }
  try { await options.waitForFunction(() => document.getElementById('status').textContent === '就绪', undefined, { timeout: 15000 }); } catch(e) { throw new Error(`${e.message}; UI=${await options.locator('main').innerText()}; frames=${JSON.stringify(frames.map(f=>f.type))}`); }
  if (!externalCenter) {
  assert.ok(frames.find(f => f.type === 'config_ack' && f.ready));
  send({ type: 'open', runtimeId: 'e2e-runtime', config });
  const openDeadline=Date.now()+10000; while (!frames.some(f => f.type === 'opened')) { if(Date.now()>openDeadline)throw new Error('Runtime open timeout'); await new Promise(r => setTimeout(r, 10)); }
  }
  await request('initialize', {});
  const created = await request('session/new', { cwd: config.cwd || '/workspace', _starweaveToolSession: 'mock-host-session', mcpServers: [{ name: 'test', type: 'http', url: `${base}/mcp` }] });
  const result = await request('session/prompt', { sessionId: created.sessionId, prompt: [{ type: 'text', text: 'RUN_BROWSER_E2E' }] });
  assert.equal(result.stopReason, 'end_turn');
  assert.equal(await page.locator('#result').textContent(), 'ORDER-2026'); assert.equal(await page.evaluate(() => window.mutations), 1);
  assert.equal(await page.locator('#upload').evaluate(e => e.files[0].name), 'status.md');
  assert.equal(mcpCalls, 1); assert.equal(events.filter(e => e.sessionUpdate === 'tool_call').length, 9);
  assert.ok(events.some(e => e.content?.text === '浏览器全链路完成'));
  const screenshot = events.find(e => e.rawOutput?.content?.some(c => c.type === 'image')); assert.ok(screenshot);
  assert.ok(JSON.stringify(screenshot).length < 65536); assert.equal(screenshot.rawOutput.content[0].data, '[binary data omitted]');
  // Stay connected past the next heartbeat, then drop an idle connection too.
  await new Promise(r => setTimeout(r, 11000));
  if (!externalCenter) assert.ok(frames.some(f => f.type === 'ping'), 'heartbeat must use the replacement socket');
  if (externalCenter) { const r = await fetch(`${externalCenter}/test/disconnect`, { method: 'POST' }); assert.ok(r.ok); }
  else socket.terminate();
  forcedDisconnects++;
  await new Promise(r => setTimeout(r, 1500));
  const continued = await request('session/prompt', { sessionId: created.sessionId, prompt: [{ type: 'text', text: '确认任务已完成' }] });
  assert.equal(continued.stopReason, 'end_turn');
  assert.equal(await page.evaluate(() => window.mutations), 1);
  await request('session/close', { sessionId: created.sessionId });
  const backupDeadline = Date.now() + 10000;
  for (;;) {
    const backedUp = externalCenter ? (await (await fetch(`${externalCenter}/test/backup?${created.sessionId}`)).json()).exists : snapshots.has(created.sessionId);
    if (backedUp) break;
    if (Date.now() > backupDeadline) throw new Error('Session backup was not persisted');
    await new Promise(r => setTimeout(r, 100));
  }
  // Erase only this disposable test profile's local snapshots; restore must now pull from the center.
  await worker.evaluate(() => new Promise((resolve, reject) => {
    const request = indexedDB.open('starweave-browser', 1);
    request.onsuccess = () => { const db=request.result, tx=db.transaction('sessions','readwrite'); tx.objectStore('sessions').clear(); tx.oncomplete=()=>{db.close();resolve();}; tx.onerror=()=>reject(tx.error); };
    request.onerror = () => reject(request.error);
  }));
  const before = modelCalls;
  await request('session/resume', { cwd: config.cwd || '/workspace', _starweaveToolSession: 'mock-host-session', sessionId: created.sessionId, mcpServers: [{ name: 'test', type: 'http', url: `${base}/mcp` }] });
  assert.equal(modelCalls, before); assert.equal(await page.evaluate(() => window.mutations), 1);
  console.log(JSON.stringify({ passed: true, browser: await context.browser().version(), realJavaCenter: Boolean(externalCenter), tools: 9, modelCalls, mcpCalls, restoreWithoutReplay: true, forcedDisconnects }));
} catch (error) {
  console.error('E2E diagnostics', JSON.stringify({ modelCalls, mcpCalls, events: events.map(e=>e.sessionUpdate), status: context ? await context.pages().at(-1)?.locator('main').innerText().catch(()=>null) : null }));
  throw error;
} finally {
  if (context) await context.close();
  for (const ws of wss.clients) ws.terminate(); wss.close(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve));
  await rm(profile, { recursive: true, force: true });
}

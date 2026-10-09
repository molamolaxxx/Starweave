import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import http from 'node:http';
import { fileURLToPath } from 'node:url';
import { spawn } from 'node:child_process';
import { EventEmitter, once } from 'node:events';
import { Writable, Readable } from 'node:stream';
import { ClientSideConnection, ndJsonStream } from '@agentclientprotocol/sdk';
import { validateConfig, commandText } from '../integration/agent.mjs';
import { commandTool, shellInvocation } from '../integration/shell.mjs';
import { connectMcp } from '../integration/mcp.mjs';
import { Server } from '@modelcontextprotocol/sdk/server/index.js';
import { StreamableHTTPServerTransport } from '@modelcontextprotocol/sdk/server/streamableHttp.js';
import { ListToolsRequestSchema, CallToolRequestSchema } from '@modelcontextprotocol/sdk/types.js';

const resource = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
test('context budget and custom endpoint validation', () => {
  const config = { baseUrl: 'http://localhost:1234/v1', model: 'custom', apiKey: 'test' };
  assert.equal(validateConfig(config).contextWindow, 1000000);
  assert.equal(validateConfig(config).maxTokens, 64000);
  assert.throws(() => validateConfig({ ...config, contextWindow: 4096 }), /上下文/);
  assert.throws(() => validateConfig({ ...config, baseUrl: 'file:///tmp/model' }), /HTTP/);
  assert.equal(commandText('<acp-harness>injected context</acp-harness>\n[Current Time: test]\n/compact keep progress'), '/compact keep progress');
});
test('Windows command quoting uses encoded PowerShell, Linux uses argv', () => {
  const command = 'Write-Output "中文 $hello `test`"';
  const win = shellInvocation(command, 'win32', { SystemRoot: 'C:\\Windows' });
  assert.equal(win.executable, 'C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe');
  assert.ok(Buffer.from(win.args.at(-1), 'base64').toString('utf16le').endsWith(command));
  assert.deepEqual(shellInvocation('echo hello', 'linux').args, ['-c', 'echo hello']);
});
test('native shell execution, failure, timeout and abort', async () => {
  const tool = commandTool(os.tmpdir());
  const result = await tool.execute('1', { command: process.platform === 'win32' ? 'Write-Output "中文测试"' : 'printf "中文测试"' });
  assert.match(result.content[0].text, /中文测试/); assert.equal(result.details.exitCode, 0);
  const fail = await tool.execute('2', { command: 'exit 3' }); assert.equal(fail.details.exitCode, 3);
  const controller = new AbortController();
  const pending = tool.execute('3', { command: process.platform === 'win32' ? 'Start-Sleep -Seconds 60' : 'sleep 60' }, controller.signal);
  setTimeout(() => controller.abort(), 100);
  await assert.rejects(pending, /cancelled/);
  const timeout = await tool.execute('4', { command: process.platform === 'win32' ? 'Start-Sleep -Seconds 60' : 'sleep 60', timeout: 1 });
  assert.equal(timeout.details.timedOut, true);
});

test('Streamable HTTP MCP honors headers and calls registered tools', async () => {
  const transport = new StreamableHTTPServerTransport({ sessionIdGenerator: () => 'test-http-session' });
  const mcpServer = new Server({ name: 'http-fixture', version: '1.0.0' }, { capabilities: { tools: {} } });
  mcpServer.setRequestHandler(ListToolsRequestSchema, async () => ({ tools: [{ name: 'http_echo', description: 'Echo', inputSchema: { type: 'object', properties: { text: { type: 'string' } } } }] }));
  mcpServer.setRequestHandler(CallToolRequestSchema, async request => ({ content: [{ type: 'text', text: request.params.arguments.text }] }));
  await mcpServer.connect(transport);
  const httpServer = http.createServer(async (request, response) => {
    assert.equal(request.headers.authorization, 'Bearer mcp-test-key');
    const chunks = []; for await (const chunk of request) chunks.push(chunk);
    const body = chunks.length ? JSON.parse(Buffer.concat(chunks).toString()) : undefined;
    await transport.handleRequest(request, response, body);
  });
  await new Promise(resolve => httpServer.listen(0, '127.0.0.1', resolve));
  let bridge;
  try {
    bridge = await connectMcp([{ type: 'http', name: 'http', url: `http://127.0.0.1:${httpServer.address().port}/mcp`, headers: [{ name: 'Authorization', value: 'Bearer mcp-test-key' }] }], os.tmpdir());
    assert.equal(bridge.tools[0].name, 'http_echo');
    const result = await bridge.tools[0].execute('id', { text: 'live HTTP result' });
    assert.equal(result.content[0].text, 'live HTTP result');
  } finally {
    await bridge?.close(); await mcpServer.close(); httpServer.closeAllConnections();
    await new Promise(resolve => httpServer.close(resolve));
  }
});

async function fixture(t) {
  const temp = await fs.mkdtemp(path.join(os.tmpdir(), 'starweave pi 中文 '));
  const requests = [], updates = []; let mode = 'text', permission = true;
  const events = new EventEmitter();
  const server = http.createServer(async (request, response) => {
    const chunks = []; for await (const chunk of request) chunks.push(chunk);
    const body = JSON.parse(Buffer.concat(chunks).toString()); requests.push(body); events.emit('request');
    assert.equal(request.url, '/v1/chat/completions'); assert.equal(request.headers.authorization, 'Bearer isolated-test-key');
    if (mode === 'error') { response.writeHead(401, { 'content-type': 'application/json' }); response.end(JSON.stringify({ error: { message: 'bad isolated-test-key', type: 'invalid_request_error' } })); return; }
    response.writeHead(200, { 'content-type': 'text/event-stream' }); response.flushHeaders();
    if (mode === 'stall') { response.on('close', () => response.end()); return; }
    const tool = mode.startsWith('tool:') ? mode.slice(5) : null;
    if (tool && body.messages.at(-1)?.role !== 'tool') {
      const args = tool === 'write' ? { path: 'test 中文.txt', content: 'saved content' } : tool === 'shell' ? { command: process.platform === 'win32' ? 'Write-Output hello' : 'printf hello' } : { text: 'harness-live' };
      response.write('data: ' + JSON.stringify({ id: 'test', object: 'chat.completion.chunk', choices: [{ index: 0, delta: { role: 'assistant', tool_calls: [{ index: 0, id: 'call-test', type: 'function', function: { name: tool, arguments: JSON.stringify(args) } }] }, finish_reason: null }] }) + '\n\n');
      response.write('data: ' + JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: 'tool_calls' }] }) + '\n\n');
    } else {
      response.write('data: ' + JSON.stringify({ id: 'test', object: 'chat.completion.chunk', choices: [{ index: 0, delta: { role: 'assistant', content: 'mock answer' }, finish_reason: null }] }) + '\n\n');
      response.write('data: ' + JSON.stringify({ choices: [{ index: 0, delta: {}, finish_reason: 'stop' }], usage: { prompt_tokens: mode === 'large' ? 14500 : 100, completion_tokens: 10, total_tokens: mode === 'large' ? 14510 : 110 } }) + '\n\n');
    }
    response.end('data: [DONE]\n\n');
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const config = { baseUrl: `http://127.0.0.1:${server.address().port}/v1`, model: 'my-custom-model', apiKey: 'isolated-test-key', contextWindow: 16000, maxTokens: 2048, agentDir: path.join(temp, 'agent-state') };
  let child, connection, stderr = '';
  const start = async () => {
    child = spawn(process.execPath, [path.join(resource, 'integration/entry.mjs')], { env: { ...process.env, STARWEAVE_PI_CONFIG: JSON.stringify(config) }, stdio: ['pipe', 'pipe', 'pipe'], windowsHide: true });
    child.stderr.on('data', data => { stderr += data.toString(); });
    connection = new ClientSideConnection(() => ({
      sessionUpdate: async update => updates.push(update),
      requestPermission: async () => ({ outcome: { outcome: 'selected', optionId: permission ? 'allow-once' : 'reject-once' } })
    }), ndJsonStream(Writable.toWeb(child.stdin), Readable.toWeb(child.stdout)));
    const init = await connection.initialize({ protocolVersion: 1, clientCapabilities: { session: { compaction: {}, configOptions: {} } } });
    assert.equal(init.agentCapabilities.loadSession, true); assert.equal(init.agentCapabilities.mcpCapabilities.http, true);
    return connection;
  };
  const stop = async () => { if (child && child.exitCode === null) { child.stdin.end(); await new Promise(resolve => child.once('close', resolve)); } };
  t.after(async () => { await stop(); server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); await fs.rm(temp, { recursive: true, force: true }); });
  await start();
  return { temp, config, requests, updates, start, stop, nextRequest: () => once(events, 'request', { signal: AbortSignal.timeout(10000) }), get connection() { return connection; }, set mode(value) { mode = value; }, set permission(value) { permission = value; }, get stderr() { return stderr; } };
}
const prompt = (connection, sessionId, text) => connection.prompt({ sessionId, prompt: [{ type: 'text', text }] });
test('ACP real Pi loop: stream, MCP, tools, budgets, errors, cancel, compact and restart/load', { timeout: 90000 }, async t => {
  const f = await fixture(t);
  const mcpServers = [{ name: 'harness', command: process.execPath, args: [path.join(resource, 'test/mcp-fixture.mjs')], env: [] }];
  const session = await f.connection.newSession({ cwd: f.temp, mcpServers });
  assert.equal((await prompt(f.connection, session.sessionId, 'hello')).stopReason, 'end_turn');
  assert.equal(f.requests.at(-1).model, 'my-custom-model'); assert.equal(f.requests.at(-1).max_tokens, 2048);
  assert.ok(f.requests.at(-1).tools.some(tool => tool.function.name === 'harness_echo'));
  assert.ok(f.updates.some(item => item.update.sessionUpdate === 'agent_message_chunk'));
  f.mode = 'tool:harness_echo'; await prompt(f.connection, session.sessionId, 'invoke harness');
  assert.ok(f.requests.at(-1).messages.some(message => message.role === 'tool' && message.content.includes('MCP:harness-live')));
  assert.ok(f.updates.some(item => item.update.rawOutput?.content?.some(block => block.text === 'MCP:harness-live') && item.update.rawInput.text === 'harness-live'));
  f.mode = 'tool:write'; await prompt(f.connection, session.sessionId, 'write file');
  assert.equal(await fs.readFile(path.join(f.temp, 'test 中文.txt'), 'utf8'), 'saved content');
  assert.ok(f.updates.some(item => Array.isArray(item.update.content) && item.update.content.some(content => content.type === 'diff' && content.newText === 'saved content')));
  f.mode = 'tool:shell'; await prompt(f.connection, session.sessionId, 'run command');
  assert.ok(f.updates.some(item => item.update.kind === 'execute'));
  f.permission = false; f.mode = 'tool:write'; await prompt(f.connection, session.sessionId, 'deny write');
  assert.ok(f.updates.some(item => item.update.status === 'failed')); f.permission = true;
  f.mode = 'error'; await assert.rejects(prompt(f.connection, session.sessionId, 'bad endpoint'), error => !JSON.stringify(error).includes('isolated-test-key'));
  f.mode = 'text'; assert.equal((await prompt(f.connection, session.sessionId, 'recover')).stopReason, 'end_turn');
  await f.connection.setSessionConfigOption({ sessionId: session.sessionId, configId: 'model', value: 'changed-model' });
  await prompt(f.connection, session.sessionId, 'new model'); assert.equal(f.requests.at(-1).model, 'changed-model');
  f.mode = 'stall'; const requestReady = f.nextRequest(); const pending = prompt(f.connection, session.sessionId, 'cancel me');
  await requestReady;
  await f.connection.cancel({ sessionId: session.sessionId }); assert.equal((await pending).stopReason, 'cancelled');
  f.mode = 'text'; await prompt(f.connection, session.sessionId, 'after cancel');
  await prompt(f.connection, session.sessionId, '<acp-harness>test context</acp-harness>\n[Current Time: test]\n/compact');
  assert.ok(f.updates.some(item => item.update._meta?.starweaveCompaction?.status === 'completed'));
  const beforeAutoCompact = f.updates.filter(item => item.update._meta?.starweaveCompaction?.status === 'completed').length;
  f.mode = 'large'; await prompt(f.connection, session.sessionId, 'large context ' + 'x'.repeat(50000));
  f.mode = 'text'; await prompt(f.connection, session.sessionId, 'trigger automatic compression');
  assert.ok(f.updates.filter(item => item.update._meta?.starweaveCompaction?.status === 'completed').length > beforeAutoCompact);
  await f.connection.closeSession({ sessionId: session.sessionId }); await f.stop(); await f.start();
  await f.connection.loadSession({ sessionId: session.sessionId, cwd: f.temp, mcpServers });
  await prompt(f.connection, session.sessionId, 'after restart');
  assert.ok(f.updates.some(item => item.update.sessionUpdate === 'usage_update' && item.update.size === 16000));
  assert.equal(f.stderr.includes('isolated-test-key'), false);
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { proxyTools, environmentPrompt } from '../src/proxy-tools.mjs';

test('host tools forward identity, cancellation, images and structured MCP results', async () => {
  const controller = new AbortController(), partial = [], calls = [];
  const result = { content: [{ type: 'image', mimeType: 'image/png', data: 'png-data' }], structuredContent: { ok: true } };
  const [read] = proxyTools([{ name: 'read', description: 'Read file', parameters: { type: 'object' } }], 'owned-session', async (value, signal, update) => {
    calls.push(value); assert.equal(signal, controller.signal); update({ content: [{ type: 'text', text: 'progress' }] }); return result;
  });
  const value = await read.execute('unique-call', { path: '/host/image.png' }, controller.signal, v => partial.push(v));
  assert.deepEqual(calls, [{ operation: 'call', toolSession: 'owned-session', callId: 'unique-call', name: 'read', arguments: { path: '/host/image.png' } }]);
  assert.deepEqual(value.content, result.content); assert.deepEqual(value.details, { ok: true }); assert.equal(partial.length, 1);
  controller.abort(); await assert.rejects(read.execute('cancelled', {}, controller.signal)); assert.equal(calls.length, 1);
});
test('system prompt names browser and host environments and ordinary ACP resource rules', () => {
  const text = environmentPrompt({ executionNode: 'center-1', os: 'Linux', cwd: '/srv/project', shell: '/bin/sh', systemPrompt: 'SKILL: /srv/project/.agents/skills/demo/SKILL.md' });
  for (const expected of ['center-1', 'Linux', '/srv/project', '/bin/sh', 'evaluate_js', 'read、write、edit、shell', '/srv/project/.agents/skills/demo/SKILL.md']) assert.ok(text.includes(expected));
  assert.ok(!text.includes('read_resource')); assert.ok(!text.includes('当前宿主没有'));
});

import test from 'node:test';
import assert from 'node:assert/strict';
import { contextTokens, estimateMessage } from '../src/context-usage.mjs';
import { BrowserRuntime } from '../src/runtime.mjs';
const image = size => ({ role: 'toolResult', content: [{ type: 'image', data: 'a'.repeat(size), mimeType: 'image/png' }] });
const answer = usage => ({ role: 'assistant', content: [{ type: 'text', text: 'done' }], stopReason: 'stop', usage });

test('latest successful usage includes caches and output without double counting system/tools or previous requests', () => {
  const state = { systemPrompt: 'x'.repeat(100000), tools: [{ name: 'read', description: 'x'.repeat(10000) }], messages: [answer({ totalTokens: 900000 }), image(2500000), answer({ input: 194, cacheRead: 37376, cacheWrite: 0, output: 71, totalTokens: 37641 })] };
  assert.equal(contextTokens(state), 37641);
  const restored = JSON.parse(JSON.stringify(state));
  assert.equal(contextTokens(restored), 37641);
  delete restored.messages.at(-1).usage.totalTokens;
  assert.equal(contextTokens(restored), 37641);
});
test('trailing images use a provisional image budget independent of Base64 length', () => {
  const state = { messages: [answer({ totalTokens: 2000 }), image(1000)] };
  const expected = 2000 + estimateMessage(image(1000));
  assert.equal(contextTokens(state), expected);
  state.messages[1] = image(2500000);
  assert.equal(contextTokens(state), expected);
  assert.ok(expected < 4000);
});
test('error, aborted and zero usage do not replace the last valid baseline', () => {
  const messages = [answer({ totalTokens: 5000 }), { ...answer({ totalTokens: 900000 }), stopReason: 'error' }, { ...answer({ totalTokens: 800000 }), stopReason: 'aborted' }, answer({ totalTokens: 0 })];
  assert.equal(contextTokens({ messages }), 5000 + messages.slice(1).reduce((n, m) => n + estimateMessage(m), 0));
});
test('new and compacted contexts estimate current text/system/tools without old usage', () => {
  const state = { systemPrompt: 'system', tools: [{ name: 'read', parameters: { type: 'object' } }], messages: [image(2500000), { role: 'user', content: '中文摘要' }] };
  assert.ok(contextTokens(state) < 2000);
  const before = contextTokens(state);
  state.messages = [{ role: 'user', content: '中文摘要' }];
  assert.ok(contextTokens(state) < before);
  assert.ok(contextTokens(state) > estimateMessage(state.messages[0]));
});
test('runtime usage and automatic-compaction estimate share the same corrected count', () => {
  const sent = [], runtime = Object.create(BrowserRuntime.prototype);
  runtime.send = message => sent.push(message);
  const s = { id: 'restored', agent: { state: { model: { contextWindow: 1000000 }, messages: [image(2500000), answer({ totalTokens: 37641 })] } } };
  runtime.usage(s);
  assert.equal(runtime.estimate(s), 37641);
  assert.deepEqual(sent[0].params.update, { sessionUpdate: 'usage_update', used: 37641, size: 1000000 });
});

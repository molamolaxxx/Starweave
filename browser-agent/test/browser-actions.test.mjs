import test from 'node:test';
import assert from 'node:assert/strict';
import { BrowserTools } from '../src/browser-tools.mjs';
function fixture() {
  const calls = [], listeners = {};
  const api = { debugger: { attach: async (...args) => calls.push(['attach', ...args]), detach: async (...args) => calls.push(['detach', ...args]), sendCommand: async (source, method, params) => { calls.push([method, source, params]); if (method === 'Page.getNavigationHistory') return { currentIndex: 1, entries: [{ id: 10 }, { id: 11 }] }; return method === 'Network.getResponseBody' ? { body: 'response', base64Encoded: false } : { result: { value: true } }; } },
    tabs: { remove: async ids => calls.push(['remove', ids]), reload: async (...a) => calls.push(['reload', ...a]), goBack: async t => calls.push(['back', t]), query: async () => [{ id: 1, windowId: 2, url: 'https://test/' }] },
    history: { deleteRange: async a => calls.push(['deleteRange', a]) }, downloads: { download: async a => { calls.push(['download', a]); return 12; } } };
  const browser = new BrowserTools(api); return { calls, browser, api, tool: name => browser.tools().find(t => t.name === name) };
}
test('17 classified tools expose actions without duplicate legacy names', () => {
  const { browser } = fixture(), specs = browser.tools();
  assert.equal(specs.length, 17); assert.equal(new Set(specs.map(t => t.name)).size, 17);
  assert.ok(specs.every(t => t.name.startsWith('browser_') && t.parameters.properties.action.enum.length));
  assert.ok(!specs.some(t => ['click', 'read_page', 'evaluate_js'].includes(t.name)));
});
test('tab management closes multiple tabs, navigates history and rejects invalid actions/URLs', async () => {
  const { calls, tool } = fixture(), tabs = tool('browser_tabs');
  await tabs.execute('close', { action: 'close', tabIds: [1, 2] }); assert.deepEqual(calls[0], ['remove', [1, 2]]);
  await tabs.execute('back', { action: 'back', tabId: 3 }); assert.equal(calls.at(-1)[0], 'Page.navigateToHistoryEntry'); assert.equal(calls.at(-1)[2].entryId, 10);
  await assert.rejects(tabs.execute('bad', { action: 'unknown' }), /未知/);
  await assert.rejects(tabs.execute('bad', { action: 'navigate', tabId: 1, url: 'file:///etc/passwd' }), /HTTP/);
});
test('keyboard presses release the same key and iframe target is preserved', async () => {
  const { calls, tool } = fixture();
  await tool('browser_keyboard').execute('key', { action: 'press', tabId: 1, frameSessionId: 'child', key: 'Enter' });
  const keys = calls.filter(c => c[0] === 'Input.dispatchKeyEvent'); assert.equal(keys.length, 2);
  assert.equal(keys[0][1].sessionId, 'child'); assert.equal(keys[0][2].text, '\r'); assert.equal(keys[1][2].type, 'keyUp');
});
test('network events are bounded, request IDs retain frame targets and detached logs survive', async () => {
  const { browser, calls, tool } = fixture(); await tool('browser_network').execute('start', { action: 'start', tabId: 1 });
  for (let i = 0; i < 350; i++) await browser.event({ tabId: 1, sessionId: 'child' }, 'Network.requestWillBeSent', { requestId: String(i), request: { url: 'https://test/' + i, method: 'GET' } });
  assert.equal(browser.records.get(1).requests.size, 300);
  await tool('browser_network').execute('body', { action: 'body', tabId: 1, requestId: 'child:349' });
  assert.equal(calls.at(-1)[1].sessionId, 'child'); assert.equal(calls.at(-1)[2].requestId, '349');
  browser.detached(1); assert.equal(browser.records.get(1).network, false); assert.equal(browser.records.get(1).requests.size, 300);
});
test('dialog arm is consumed once and cross-process frames get their own execution context', async () => {
  const { browser, tool, calls } = fixture(); await tool('browser_dialog').execute('arm', { action: 'arm', tabId: 1, accept: true, promptText: 'answer' });
  await browser.event({ tabId: 1 }, 'Page.javascriptDialogOpening', { type: 'prompt', message: 'Question' });
  assert.equal(browser.pendingDialogs.has(1), false); assert.equal(calls.at(-1)[0], 'Page.handleJavaScriptDialog');
  await browser.event({ tabId: 1 }, 'Target.attachedToTarget', { sessionId: 'iframe', targetInfo: { type: 'iframe', url: 'https://frame/' } });
  await browser.event({ tabId: 1, sessionId: 'iframe' }, 'Runtime.executionContextCreated', { context: { id: 9, origin: 'https://frame', auxData: { isDefault: true, frameId: 'f' } } });
  const result = await tool('browser_page').execute('frames', { action: 'frames', tabId: 1 });
  assert.equal(JSON.parse(result.content[0].text).contexts[0].frameSessionId, 'iframe');
});
test('bounded waits cancel promptly, destructive history requires an explicit range', async () => {
  const { tool, calls } = fixture(), controller = new AbortController();
  const pending = tool('browser_wait').execute('delay', { action: 'delay', tabId: 1, delayMs: 60000 }, controller.signal); controller.abort();
  await assert.rejects(pending, /取消/);
  await assert.rejects(tool('browser_history').execute('delete', { action: 'delete_range', startTime: 0 }), /endTime/);
  assert.equal(calls.length, 0);
});

test('ending a task releases explicitly held keys before detaching', async () => {
  const { browser, calls, tool } = fixture();
  await tool('browser_keyboard').execute('hold', { action: 'down', tabId: 1, key: 'Shift' });
  assert.equal(browser.heldKeys.size, 1); await browser.release();
  const up = calls.findIndex(c => c[0] === 'Input.dispatchKeyEvent' && c[2].type === 'keyUp');
  const detach = calls.findIndex(c => c[0] === 'detach');
  assert.ok(up >= 0 && up < detach); assert.equal(browser.heldKeys.size, 0);
});

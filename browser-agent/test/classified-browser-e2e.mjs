// Isolated real-extension test of every browser tool category. No production profile/data.
import assert from 'node:assert/strict';
import http from 'node:http';
import { mkdtemp, readFile, writeFile, copyFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { build } from 'esbuild';
import { chromium } from 'playwright-core';
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const temp = await mkdtemp(path.join(tmpdir(), 'starweave-tools-')), extension = path.join(temp, 'extension');
const { mkdir } = await import('node:fs/promises'); await mkdir(extension);
const manifest = JSON.parse(await readFile(path.join(root, 'manifest.json'), 'utf8')); manifest.background.service_worker = 'test-worker.js'; delete manifest.action; delete manifest.options_page;
await writeFile(path.join(extension, 'manifest.json'), JSON.stringify(manifest));
await copyFile(path.join(root, 'clipboard.html'), path.join(extension, 'clipboard.html'));
await build({ stdin: { contents: `import { BrowserTools } from ${JSON.stringify(path.join(root, 'src/browser-tools.mjs'))};
const browser = new BrowserTools(); globalThis.testTools=browser; globalThis.catalog=browser.tools({fileRelay:async()=>[{name:'relay.txt',dataBase64:btoa('relayed file')}]});
chrome.debugger.onEvent.addListener((s,m,p)=>browser.event(s,m,p).catch(e=>console.warn(m,e.message)));chrome.debugger.onDetach.addListener(s=>browser.detached(s.tabId));`, resolveDir: root }, bundle: true, format: 'esm', platform: 'browser', outfile: path.join(extension, 'test-worker.js') });
await build({ entryPoints: [path.join(root, 'src/clipboard.mjs')], bundle: true, format: 'esm', platform: 'browser', outfile: path.join(extension, 'clipboard.js') });
const server = http.createServer((req, res) => {
  if (req.url === '/data') { res.setHeader('content-type', 'application/json'); res.end('{"result":"network-ok"}'); return; }
  if (req.url === '/download') { res.setHeader('content-type', 'text/plain'); res.setHeader('content-disposition', 'attachment; filename="fixture.txt"'); res.end('download content'); return; }
  if (req.url === '/frame') { res.setHeader('content-type', 'text/html'); res.end('<input id="frame-input"><button onclick="document.body.dataset.clicked=1">Frame button</button>'); return; }
  res.setHeader('content-type', 'text/html'); res.end(`<!doctype html><title>Tool fixture</title><style>body{height:2200px}#drag,#drop{display:inline-block;width:100px;height:60px;background:#ccc;margin:20px}#hover:hover{color:red}</style><input id="text"><textarea id="area"></textarea><div id="rich" contenteditable="true"></div><select id="select"><option value="one">One</option><option value="two">Two</option></select><input id="check" type="checkbox"><input id="file" type="file" multiple><button id="button" onclick="window.clicked=(window.clicked||0)+1">Button</button><span id="hover">Hover</span><div id="drag" draggable="true" ondragstart="event.dataTransfer.setData('text/plain','dragged')">Drag</div><div id="drop" ondragover="event.preventDefault()" ondrop="event.preventDefault();window.dropped=event.dataTransfer.getData('text/plain')">Drop</div><iframe src="http://localhost:${server.address().port}/frame"></iframe>`);
});
await new Promise(resolve => server.listen(0, resolve));
const base = `http://127.0.0.1:${server.address().port}`;
let context, worker, tabId, seq = 0; const passed = [];
async function run(name, action, args = {}) {
  console.log('TOOL', name, action);
  const r = await worker.evaluate(async ({ name, args, id }) => {
    const tool = globalThis.catalog.find(t => t.name === name); if (!tool) throw new Error('Missing ' + name);
    return tool.execute(id, args, new AbortController().signal);
  }, { name, args: { action, ...args }, id: 'test-' + ++seq });
  if (r.content?.[0]?.type === 'image') return r;
  const text = r.content?.[0]?.text; try { return JSON.parse(text); } catch (_) { return text; }
}
try {
  context = await chromium.launchPersistentContext(path.join(temp, 'profile'), { executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: [`--disable-extensions-except=${extension}`, `--load-extension=${extension}`, '--no-sandbox', '--site-per-process'] });
  worker = context.serviceWorkers()[0] || await context.waitForEvent('serviceworker', { timeout: 15000 });
  const page = await context.newPage(); page.on('dialog', () => {}); await page.goto(base + '/fixture');
  const tabs = await run('browser_tabs', 'list'); tabId = tabs.find(t => t.url === base + '/fixture').tabId;
  assert.equal((await worker.evaluate(() => catalog.length)), 17);
  let read = await run('browser_page', 'read', { tabId }); assert.ok(read.elements.some(e => e.tag === 'select'));
  assert.ok((await run('browser_page', 'read', { tabId, format: 'html', selector: '#text' })).html.includes('input'));
  await run('browser_page', 'scroll', { tabId, y: 100 }); passed.push('page');
  await run('browser_form', 'fill', { tabId, selector: '#text', text: '' });
  await run('browser_keyboard', 'type', { tabId, selector: '#text', text: 'abc' });
  await run('browser_keyboard', 'press', { tabId, key: 'a', modifiers: ['Control'] });
  await run('browser_keyboard', 'type', { tabId, text: 'replacement' });
  assert.equal(await page.locator('#text').inputValue(), 'replacement');
  await run('browser_keyboard', 'press', { tabId, key: 'Tab' }); passed.push('keyboard');
  await run('browser_form', 'fill', { tabId, selector: '#rich', text: 'rich text' }); assert.equal(await page.locator('#rich').innerText(), 'rich text');
  await run('browser_form', 'select', { tabId, selector: '#select', values: ['two'] }); assert.equal(await page.locator('#select').inputValue(), 'two');
  await run('browser_form', 'check', { tabId, selector: '#check', checked: true }); assert.ok(await page.locator('#check').isChecked()); passed.push('form');
  await run('browser_mouse', 'click', { tabId, selector: '#button' }); assert.equal(await page.evaluate(() => window.clicked), 1);
  await run('browser_mouse', 'double_click', { tabId, selector: '#button' }); assert.equal(await page.evaluate(() => window.clicked), 3);
  await run('browser_mouse', 'hover', { tabId, selector: '#hover' }); assert.equal(await page.locator('#hover').evaluate(e => getComputedStyle(e).color), 'rgb(255, 0, 0)');
  await run('browser_mouse', 'right_click', { tabId, selector: '#button' }); await run('browser_keyboard', 'press', { tabId, key: 'Escape' });
  await run('browser_mouse', 'drag', { tabId, selector: '#drag', toSelector: '#drop' });
  assert.equal(await page.evaluate(() => window.dropped), 'dragged'); passed.push('mouse');
  await run('browser_page', 'evaluate', { tabId, script: `setTimeout(()=>{const e=document.createElement('p');e.id='delayed';e.textContent='arrived';document.body.append(e)},150);'scheduled'` });
  await run('browser_wait', 'element', { tabId, selector: '#delayed' }); await run('browser_wait', 'text', { tabId, text: 'arrived' }); await run('browser_wait', 'ready', { tabId }); passed.push('wait');
  await run('browser_dialog', 'arm', { tabId, accept: true, promptText: 'answered' });
  assert.equal(await run('browser_page', 'evaluate', { tabId, script: "prompt('Question')" }), 'answered');
  await run('browser_dialog', 'arm', { tabId, accept: false }); assert.equal(await run('browser_page', 'evaluate', { tabId, script: "confirm('Question')" }), false); passed.push('dialog');
  for (const action of ['viewport', 'full_page', 'element', 'region']) {
    const capture = await run('browser_capture', action, { tabId, ...(action === 'element' ? { selector: '#button' } : {}), ...(action === 'region' ? { x: 0, y: 0, width: 120, height: 100 } : {}) });
    assert.equal(capture.content[0].type, 'image'); assert.ok(capture.content[0].data.length > 100);
  } passed.push('capture');
  await run('browser_console', 'start', { tabId }); await run('browser_network', 'start', { tabId });
  await run('browser_page', 'evaluate', { tabId, script: `console.log('logged-marker');fetch('${base}/data').then(r=>r.json())` });
  await run('browser_wait', 'delay', { tabId, delayMs: 100 });
  const requests = await run('browser_network', 'list', { tabId, url: '/data' }); assert.equal(requests.requests.length, 1);
  assert.ok((await run('browser_network', 'body', { tabId, requestId: requests.requests[0].id })).body.includes('network-ok'));
  assert.ok((await run('browser_console', 'list', { tabId })).logs.some(e => e.text.includes('logged-marker')));
  await run('browser_console', 'stop', { tabId }); await run('browser_network', 'stop', { tabId }); passed.push('network', 'console');
  await run('browser_storage', 'set', { tabId, key: 'test-key', value: 'value' }); assert.equal((await run('browser_storage', 'get', { tabId, key: 'test-key' })).value, 'value');
  await run('browser_storage', 'set_cookie', { tabId, cookie: { name: 'test-cookie', value: 'cookie-value' } });
  assert.ok((await run('browser_storage', 'cookies', { tabId })).cookies.some(c => c.name === 'test-cookie'));
  await run('browser_storage', 'remove_cookie', { tabId, name: 'test-cookie' }); await run('browser_storage', 'remove', { tabId, key: 'test-key' }); passed.push('storage');
  await run('browser_files', 'set_input', { tabId, selector: '#file', files: [{ name: 'inline.txt', dataBase64: btoa('inline file') }] });
  assert.equal(await page.locator('#file').evaluate(e => e.files[0].text()), 'inline file');
  const local = path.join(temp, 'local.txt'); await writeFile(local, 'local browser file');
  await run('browser_files', 'set_input', { tabId, selector: '#file', paths: [local] }); assert.equal(await page.locator('#file').evaluate(e => e.files[0].text()), 'local browser file');
  await run('browser_files', 'set_input', { tabId, selector: '#file', hostPaths: ['/server/file.txt'] }); assert.equal(await page.locator('#file').evaluate(e => e.files[0].text()), 'relayed file'); passed.push('files');
  const frameInfo = await run('browser_page', 'frames', { tabId });
  const frame = frameInfo.contexts.find(c => c.origin.includes('localhost'));
  assert.ok(frame?.frameSessionId, 'cross-process iframe session must be discovered');
  await run('browser_form', 'fill', { tabId, frameSessionId: frame.frameSessionId, contextId: frame.contextId, selector: '#frame-input', text: 'frame text' });
  assert.equal((await run('browser_page', 'evaluate', { tabId, frameSessionId: frame.frameSessionId, contextId: frame.contextId, script: "document.querySelector('#frame-input').value" })), 'frame text'); passed.push('cross-process iframe');
  const bookmark = await run('browser_bookmarks', 'create', { title: 'test bookmark', url: base + '/fixture' });
  assert.ok((await run('browser_bookmarks', 'search', { query: 'test bookmark' })).some(e => e.id === bookmark.id));
  await run('browser_bookmarks', 'update', { bookmarkId: bookmark.id, title: 'updated bookmark' }); await run('browser_bookmarks', 'remove', { bookmarkId: bookmark.id }); passed.push('bookmarks');
  assert.ok((await run('browser_history', 'search', { query: 'fixture' })).some(e => e.url.includes('/fixture'))); passed.push('history');
  await run('browser_clipboard', 'write', { text: 'clipboard marker' }); assert.equal((await run('browser_clipboard', 'read')).text, 'clipboard marker'); passed.push('clipboard');
  const download = await run('browser_downloads', 'start', { url: base + '/download', filename: 'starweave-e2e/fixture-' + Date.now() + '.txt' });
  for (let i = 0; i < 30; i++) { const list = await run('browser_downloads', 'list', { downloadId: download.downloadId }); if (list[0]?.state === 'complete') break; await new Promise(r => setTimeout(r, 100)); }
  assert.equal((await run('browser_downloads', 'list', { downloadId: download.downloadId }))[0].state, 'complete');
  await run('browser_downloads', 'remove_file', { downloadId: download.downloadId }); await run('browser_downloads', 'erase', { downloadId: download.downloadId }); passed.push('downloads');
  const opened = await run('browser_tabs', 'open', { url: base + '/second' });
  await run('browser_wait', 'url', { tabId: opened.tabId, url: '/second' });
  await run('browser_wait', 'ready', { tabId: opened.tabId });
  await run('browser_tabs', 'pin', { tabId: opened.tabId, enabled: true }); await run('browser_tabs', 'mute', { tabId: opened.tabId, enabled: true }); await run('browser_tabs', 'zoom', { tabId: opened.tabId, factor: 1.25 });
  await run('browser_tabs', 'navigate', { tabId: opened.tabId, url: base + '/third' }); await run('browser_wait', 'url', { tabId: opened.tabId, url: '/third' }); await run('browser_wait', 'ready', { tabId: opened.tabId });
  await run('browser_tabs', 'back', { tabId: opened.tabId }); await run('browser_wait', 'url', { tabId: opened.tabId, url: '/second' });
  await run('browser_tabs', 'forward', { tabId: opened.tabId }); await run('browser_wait', 'url', { tabId: opened.tabId, url: '/third' });
  const duplicate = await run('browser_tabs', 'duplicate', { tabId: opened.tabId });
  const window = await run('browser_windows', 'create', { tabId: duplicate.id });
  await run('browser_windows', 'update', { windowId: window.id, focused: true, width: 1000, height: 800 });
  assert.ok((await run('browser_windows', 'list')).some(w => w.id === window.id));
  await run('browser_windows', 'close', { windowId: window.id }); await run('browser_tabs', 'reload', { tabId: opened.tabId });
  await run('browser_tabs', 'close', { tabId: opened.tabId }); passed.push('tabs', 'windows');
  console.log(JSON.stringify({ passed: true, browser: context.browser().version(), categories: passed, toolCalls: seq }));
} catch (e) { console.error('Completed categories:', passed); throw e; }
finally { if (context) await context.close(); server.closeAllConnections(); await new Promise(r => server.close(r)); await rm(temp, { recursive: true, force: true }); }

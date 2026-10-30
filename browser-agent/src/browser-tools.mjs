import { textResult, bounded } from './storage.mjs';
import { groupedTools, handleEvent, initializeTarget } from './browser-actions.mjs';
const schema = properties => ({ type: 'object', properties, additionalProperties: false });
const tab = { type: 'integer', minimum: 1 }, str = { type: 'string' };
export class BrowserTools {
  constructor(api = globalThis.chrome) { this.api = api; this.attached = new Set(); this.contexts = new Map(); this.children = new Map(); this.records = new Map(); this.dialogs = new Map(); this.pendingDialogs = new Map(); this.initializing = new Map(); this.heldKeys = new Map(); }
  target(a) { return a.frameSessionId ? { tabId: a.tabId, sessionId: a.frameSessionId } : a.tabId; }
  async attach(tabId) {
    if (this.attached.has(tabId)) return;
    if (this.initializing.has(tabId)) return this.initializing.get(tabId);
    const task = (async () => {
      await this.api.debugger.attach({ tabId }, '1.3'); this.attached.add(tabId);
      try { await initializeTarget(this, { tabId }); }
      catch (e) { this.attached.delete(tabId); await this.api.debugger.detach({ tabId }).catch(() => {}); throw e; }
    })();
    this.initializing.set(tabId, task);
    try { await task; } finally { this.initializing.delete(tabId); }
  }
  detached(tabId) {
    this.attached.delete(tabId); for (const [key, held] of this.heldKeys) if (held.source.tabId === tabId) this.heldKeys.delete(key); const record = this.records.get(tabId); if (record) { record.network = false; record.console = false; } this.dialogs.delete(tabId); this.pendingDialogs.delete(tabId);
    for (const [key, entry] of this.children) if (entry.tabId === tabId) this.children.delete(key);
    for (const [key, entries] of this.contexts) if (entries.some(e => e.tabId === tabId)) this.contexts.delete(key);
  }
  async command(target, method, params = {}) {
    const source = typeof target === 'number' ? { tabId: target } : target;
    await this.attach(source.tabId);
    // Commands are never retried: a timed-out mutation may already have happened.
    return this.api.debugger.sendCommand(source, method, params);
  }
  async evaluate(tabId, expression, contextId) {
    const r = await this.command(tabId, 'Runtime.evaluate', {
      expression, returnByValue: true, awaitPromise: true, timeout: 15000,
      ...(contextId === undefined ? {} : { contextId })
    });
    if (r.exceptionDetails) throw new Error(r.exceptionDetails.exception?.description || r.exceptionDetails.text);
    if (r.result?.subtype === 'error') throw new Error(r.result.description);
    if (r.result?.type === 'undefined') return null;
    if (!('value' in r.result)) throw new Error('脚本结果无法序列化，请返回文本、数字、数组或普通对象');
    return r.result.value;
  }
  async release() {
    const keys = [...this.heldKeys.values()]; this.heldKeys.clear();
    await Promise.allSettled(keys.map(held => this.api.debugger.sendCommand(held.source, 'Input.dispatchKeyEvent', { ...held.params, type: 'keyUp' })));
    const ids = [...this.attached]; this.attached.clear();
    await Promise.allSettled(ids.map(tabId => this.api.debugger.detach({ tabId })));
    for (const id of ids) this.detached(id);
  }
  tools(options = {}) { return groupedTools(this, options); }
  baseTools() {
    const make = (name, description, properties, required, run) => ({
      name, label: name, description, parameters: { ...schema(properties), required },
      execute: async (_id, args, signal) => {
        signal?.throwIfAborted(); const value = await bounded(() => run(args), signal); signal?.throwIfAborted();
        return value?.content ? value : textResult(value);
      }
    });
    return [
      make('list_tabs', 'List open tabs in this browser profile with IDs, titles, URLs, window IDs and loading status.', {}, [], async () =>
        (await this.api.tabs.query({})).map(t => ({ tabId: t.id, windowId: t.windowId, title: t.title, url: t.url, active: t.active, status: t.status, discarded: t.discarded }))),
      make('open_tab', 'Open an HTTP/HTTPS URL in a new tab.', { url: str }, ['url'], async a => {
        const u = new URL(a.url); if (!['http:', 'https:'].includes(u.protocol)) throw new Error('只允许打开 HTTP/HTTPS 网页');
        const t = await this.api.tabs.create({ url: u.href }); return { tabId: t.id, url: t.url, status: t.status };
      }),
      make('activate_tab', 'Activate a tab and its browser window.', { tabId: tab }, ['tabId'], async a => {
        const t = await this.api.tabs.update(a.tabId, { active: true }); await this.api.windows.update(t.windowId, { focused: true }); return { tabId: t.id };
      }),
      make('navigate', 'Navigate a tab to an HTTP/HTTPS URL. Old element references become invalid.', { tabId: tab, url: str }, ['tabId', 'url'], async a => {
        const u = new URL(a.url); if (!['http:', 'https:'].includes(u.protocol)) throw new Error('只允许 HTTP/HTTPS');
        await this.api.tabs.update(a.tabId, { url: u.href }); return { navigating: true, url: u.href };
      }),
      make('read_page', 'Read current rendered DOM. format=text returns compact interactive elements with refs; html returns full or selected HTML. Re-read after navigation. Optional contextId selects a frame execution context.',
        { tabId: tab, selector: str, format: { enum: ['text', 'html'] }, contextId: { type: 'integer' } }, ['tabId'], a => this.evaluate(this.target(a), `(${observe.toString()})(${JSON.stringify(a)})`, a.contextId)),
      make('click', 'Click an element ref from read_page using browser input. Returns action status; read the page to verify the result.', { tabId: tab, ref: str, contextId: { type: 'integer' } }, ['tabId', 'ref'], async a => {
        const point = await this.evaluate(this.target(a), `(${locate.toString()})(${JSON.stringify(a.ref)},false)`, a.contextId);
        await this.command(this.target(a), 'Input.dispatchMouseEvent', { type: 'mousePressed', ...point, button: 'left', clickCount: 1 });
        await this.command(this.target(a), 'Input.dispatchMouseEvent', { type: 'mouseReleased', ...point, button: 'left', clickCount: 1 });
        return { clicked: a.ref, verify: 'read_page' };
      }),
      make('fill', 'Replace an input or textarea value and type text using browser input. Ref must come from read_page.', { tabId: tab, ref: str, text: str, contextId: { type: 'integer' } }, ['tabId', 'ref', 'text'], async a => {
        await this.evaluate(this.target(a), `(${locate.toString()})(${JSON.stringify(a.ref)},true)`, a.contextId);
        await this.command(this.target(a), 'Input.insertText', { text: a.text }); return { filled: a.ref, verify: 'read_page' };
      }),
      make('scroll', 'Scroll the page or a selected scroll container by pixels.', { tabId: tab, x: { type: 'number' }, y: { type: 'number' }, selector: str }, ['tabId', 'y'], a => this.evaluate(this.target(a), `(${scrollPage.toString()})(${JSON.stringify(a)})`)),
      make('screenshot', 'Capture the current page viewport as a PNG image.', { tabId: tab }, ['tabId'], async a => {
        await this.command(this.target(a), 'Page.bringToFront');
        const r = await this.command(this.target(a), 'Page.captureScreenshot', { format: 'png' });
        return { content: [{ type: 'image', mimeType: 'image/png', data: r.data }] };
      }),
      make('evaluate_js', 'Execute JavaScript in a page, optionally in a frame contextId. Return JSON-serializable data. Scripts may modify the page; do not retry mutations after timeout or cancellation. No Node.js, shell or local filesystem.',
        { tabId: tab, script: str, contextId: { type: 'integer' } }, ['tabId', 'script'], a => this.evaluate(this.target(a), a.script, a.contextId)),
      make('list_frames', 'List frame execution contexts. Use contextId with read_page/evaluate_js for iframes. Out-of-process frames are not supported in this first version.', { tabId: tab }, ['tabId'], async a => {
        await this.command(this.target(a), 'Runtime.disable'); await this.command(this.target(a), 'Runtime.enable');
        return { contexts: this.contexts?.get(a.tabId) || [], note: '跨进程 iframe 首版不支持自动操作' };
      })
    ];
  }
  event(source, method, params) { return handleEvent(this, source, method, params); }
}
function observe(a) {
  const root = a.selector ? document.querySelector(a.selector) : document.documentElement;
  if (!root) throw new Error('找不到指定区域');
  const limit = 200000;
  if (a.format === 'html') { const html = root.outerHTML; return { title: document.title, url: location.href, html: html.slice(0, limit), truncated: html.length > limit }; }
  const key = '__starweaveBrowserRefs'; const prefix = crypto.randomUUID();
  const refs = new Map(); const elements = [];
  for (const e of root.querySelectorAll('a,button,input,textarea,select,option,[role="button"],[role="textbox"],[contenteditable="true"]')) {
    if (!e.getClientRects().length || getComputedStyle(e).visibility === 'hidden') continue;
    const ref = `${prefix}:${elements.length}`; refs.set(ref, e);
    elements.push({ ref, tag: e.tagName.toLowerCase(), role: e.getAttribute('role'), text: (e.innerText || e.getAttribute('aria-label') || e.getAttribute('placeholder') || '').slice(0, 250), type: e.getAttribute('type'), checked: e.checked, disabled: e.disabled, value: e.type === 'password' ? '[password]' : String(e.value ?? '').slice(0, 250) });
    if (elements.length >= 500) break;
  }
  window[key] = refs;
  const text = root.innerText || root.textContent || '';
  return { title: document.title, url: location.href, text: text.slice(0, limit), truncated: text.length > limit, elements };
}
function locate(ref, fill) {
  const e = window.__starweaveBrowserRefs?.get(ref);
  if (!e || !e.isConnected) throw new Error('元素引用已失效，请重新 read_page');
  e.scrollIntoView({ block: 'center', inline: 'center' });
  if (fill) {
    e.focus();
    if (e instanceof HTMLInputElement || e instanceof HTMLTextAreaElement) {
      const proto = e instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(e, '');
      e.dispatchEvent(new Event('input', { bubbles: true }));
    } else if (e.isContentEditable) e.textContent = '';
    else throw new Error('目标不是可填写元素');
  }
  const r = e.getBoundingClientRect(); if (!r.width || !r.height) throw new Error('元素不可见');
  // Input coordinates are top-frame coordinates. Use evaluate_js for frame-local actions.
  if (window !== window.top) throw new Error('iframe 元素请使用 evaluate_js 操作');
  return { x: r.x + r.width / 2, y: r.y + r.height / 2 };
}
function scrollPage(a) { const e = a.selector ? document.querySelector(a.selector) : window; if (!e) throw new Error('找不到滚动区域'); e.scrollBy(a.x || 0, a.y); return { scrolled: true }; }

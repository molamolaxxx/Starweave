import { textResult, bounded } from './storage.mjs';

const str = { type: 'string' }, num = { type: 'number' }, bool = { type: 'boolean' };
const id = { type: 'integer', minimum: 1 }, strings = { type: 'array', items: str, maxItems: 100 };
const selection = { ref: str, selector: str, contextId: { type: 'integer' }, frameSessionId: str };
const object = { type: 'object', additionalProperties: true };
const need = (a, ...keys) => { for (const k of keys) if (a[k] === undefined || a[k] === null) throw new Error(`缺少参数：${k}`); };
const url = value => { const u = new URL(value); if (!['http:', 'https:'].includes(u.protocol)) throw new Error('只允许 HTTP/HTTPS 地址'); return u.href; };
const targetKey = s => `${s.tabId}:${s.sessionId || ''}`;
const sleep = (ms, signal) => new Promise((resolve, reject) => {
  const abort = () => { clearTimeout(timer); reject(new Error('等待已取消')); };
  const timer = setTimeout(() => { signal?.removeEventListener('abort', abort); resolve(); }, ms);
  signal?.addEventListener('abort', abort, { once: true }); if (signal?.aborted) abort();
});
export async function initializeTarget(b, source) {
  await b.api.debugger.sendCommand(source, 'Page.enable');
  await b.api.debugger.sendCommand(source, 'Runtime.enable');
  await b.api.debugger.sendCommand(source, 'Target.setAutoAttach', { autoAttach: true, waitForDebuggerOnStart: false, flatten: true });
}
function ring(b, tabId) {
  if (!b.records.has(tabId) && b.records.size >= 16) b.records.delete(b.records.keys().next().value);
  if (!b.records.has(tabId)) b.records.set(tabId, { network: false, console: false, requests: new Map(), logs: [], counter: 0 });
  return b.records.get(tabId);
}
export async function handleEvent(b, source, method, p) {
  const key = targetKey(source), state = ring(b, source.tabId);
  if (method === 'Target.attachedToTarget') {
    const child = { tabId: source.tabId, sessionId: p.sessionId, type: p.targetInfo.type, url: p.targetInfo.url, targetId: p.targetInfo.targetId };
    b.children.set(`${source.tabId}:${p.sessionId}`, child);
    if (child.type === 'iframe') {
      await initializeTarget(b, { tabId: source.tabId, sessionId: p.sessionId });
      if (state.network) await b.command({ tabId: source.tabId, sessionId: p.sessionId }, 'Network.enable', { maxTotalBufferSize: 8 * 1024 * 1024, maxResourceBufferSize: 2 * 1024 * 1024 });
    }
  }
  if (method === 'Target.detachedFromTarget') { b.children.delete(`${source.tabId}:${p.sessionId}`); b.contexts.delete(`${source.tabId}:${p.sessionId}`); }
  if (method === 'Runtime.executionContextsCleared') b.contexts.delete(key);
  if (method === 'Runtime.executionContextDestroyed') b.contexts.set(key, (b.contexts.get(key) || []).filter(e => e.contextId !== p.executionContextId));
  if (method === 'Runtime.executionContextCreated' && p.context.auxData?.isDefault) {
    const list = b.contexts.get(key) || []; list.push({ tabId: source.tabId, frameSessionId: source.sessionId, contextId: p.context.id, origin: p.context.origin, frameId: p.context.auxData?.frameId }); b.contexts.set(key, list);
  }
  if (method === 'Page.javascriptDialogOpening') {
    b.dialogs.set(source.tabId, { ...p, frameSessionId: source.sessionId });
    const pending = b.pendingDialogs.get(source.tabId);
    if (pending) { b.pendingDialogs.delete(source.tabId); await b.command(source, 'Page.handleJavaScriptDialog', pending); }
  }
  if (method === 'Page.javascriptDialogClosed') b.dialogs.delete(source.tabId);
  if (state.console && ['Runtime.consoleAPICalled', 'Runtime.exceptionThrown', 'Log.entryAdded'].includes(method)) {
    let item;
    if (method === 'Runtime.consoleAPICalled') item = { level: p.type, text: p.args.map(v => v.value === undefined ? v.description || v.type : typeof v.value === 'string' ? v.value : JSON.stringify(v.value)).join(' ') };
    if (method === 'Runtime.exceptionThrown') item = { level: 'error', text: p.exceptionDetails.exception?.description || p.exceptionDetails.text };
    if (method === 'Log.entryAdded') item = { level: p.entry.level, text: p.entry.text, url: p.entry.url };
    state.logs.push({ id: ++state.counter, time: Date.now(), frameSessionId: source.sessionId, ...item, text: item.text.slice(0, 8000) });
    if (state.logs.length > 500) state.logs.shift();
  }
  if (state.network && method.startsWith('Network.') && p.requestId) {
    const k = `${source.sessionId || ''}:${p.requestId}`;
    if (method === 'Network.requestWillBeSent') {
      state.requests.delete(k);
      state.requests.set(k, { id: k, requestId: p.requestId, frameSessionId: source.sessionId, url: p.request.url.slice(0, 16000), method: p.request.method, type: p.type, timestamp: p.timestamp });
    }
    const request = state.requests.get(k);
    if (request && method === 'Network.responseReceived') Object.assign(request, { status: p.response.status, mimeType: p.response.mimeType, protocol: p.response.protocol });
    if (request && method === 'Network.loadingFinished') Object.assign(request, { finished: true, encodedDataLength: p.encodedDataLength });
    if (request && method === 'Network.loadingFailed') Object.assign(request, { finished: true, error: p.errorText });
    if (state.requests.size > 300) state.requests.delete(state.requests.keys().next().value);
  }
}
export function groupedTools(b, options = {}) {
  const old = new Map(b.baseTools().map(t => [t.name, t]));
  const legacy = (name, a, signal) => old.get(name).execute('internal', a, signal);
  const make = (name, actions, description, properties, required, run, timeout = 30000) => ({
    name, label: name, description: `${description} Actions: ${actions.join(', ')}.`,
    parameters: { type: 'object', properties: { action: { type: 'string', enum: actions }, ...properties }, required: ['action', ...required], additionalProperties: false },
    execute: async (_callId, a, signal) => {
      signal?.throwIfAborted(); if (!actions.includes(a.action)) throw new Error(`未知操作：${a.action}`);
      const value = await bounded(() => run(a, signal), signal, timeout); signal?.throwIfAborted();
      return value?.content ? value : textResult(value ?? { ok: true });
    }
  });
  return [
    make('browser_tabs', ['list', 'open', 'activate', 'navigate', 'close', 'reload', 'back', 'forward', 'duplicate', 'move', 'pin', 'mute', 'zoom'],
      'Manage tabs in the user browser. All actions except list/open require tabId; close/move may instead take tabIds. open/navigate require url; move requires index and optionally windowId; pin/mute require enabled; zoom requires factor. Navigation is asynchronous: wait for the expected URL, then ready. Close may discard unsaved page state.',
      { tabId: id, url: str, tabIds: { type: 'array', items: id, minItems: 1, maxItems: 100 }, windowId: id, index: { type: 'integer', minimum: -1 }, enabled: bool, factor: { type: 'number', minimum: 0.25, maximum: 5 }, bypassCache: bool }, [], async (a, signal) => {
        const map = { list: 'list_tabs', open: 'open_tab', activate: 'activate_tab', navigate: 'navigate' };
        if (['close', 'move'].includes(a.action)) { if (!a.tabIds?.length) need(a, 'tabId'); }
        else if (!['list', 'open'].includes(a.action)) need(a, 'tabId');
        if (map[a.action]) { if (['open', 'navigate'].includes(a.action)) need(a, 'url'); return legacy(map[a.action], a, signal); }
        switch (a.action) {
          case 'close': { const ids = a.tabIds || [a.tabId]; await b.api.tabs.remove(ids); ids.forEach(t => b.detached(t)); return { closed: ids }; }
          case 'reload': await b.api.tabs.reload(a.tabId, { bypassCache: a.bypassCache || false }); break;
          case 'back': case 'forward': {
            const history = await b.command(a.tabId, 'Page.getNavigationHistory');
            const entry = history.entries[history.currentIndex + (a.action === 'back' ? -1 : 1)];
            if (!entry) throw new Error(a.action === 'back' ? '当前没有上一页' : '当前没有下一页');
            // Use the actual history entry; Tabs API may skip entries without user activation.
            await b.command(a.tabId, 'Page.navigateToHistoryEntry', { entryId: entry.id }); break;
          }
          case 'duplicate': return b.api.tabs.duplicate(a.tabId);
          case 'move': need(a, 'index'); return b.api.tabs.move(a.tabIds || a.tabId, { index: a.index, ...(a.windowId ? { windowId: a.windowId } : {}) });
          case 'pin': case 'mute': need(a, 'enabled'); return b.api.tabs.update(a.tabId, { [a.action === 'pin' ? 'pinned' : 'muted']: a.enabled });
          case 'zoom': need(a, 'factor'); await b.api.tabs.setZoom(a.tabId, a.factor); break;
        }
        return { ok: true, tabId: a.tabId };
      }),
    make('browser_windows', ['list', 'create', 'update', 'close'],
      'Manage browser windows. update/close require windowId; create accepts url or tabId. update accepts focused, state (normal/minimized/maximized/fullscreen), and bounds. Closing a window closes its tabs.',
      { windowId: id, tabId: id, url: str, focused: bool, state: { type: 'string', enum: ['normal', 'minimized', 'maximized', 'fullscreen'] }, left: { type: 'integer' }, top: { type: 'integer' }, width: id, height: id }, [], async a => {
        if (a.action === 'list') return b.api.windows.getAll({ populate: true });
        if (a.action === 'close') { need(a, 'windowId'); await b.api.windows.remove(a.windowId); return { closed: a.windowId }; }
        const { action, windowId, tabId, url: rawUrl, ...options } = a;
        if (action === 'create') return b.api.windows.create({ ...options, ...(tabId ? { tabId } : {}), ...(rawUrl ? { url: url(rawUrl) } : {}) });
        need(a, 'windowId'); return b.api.windows.update(windowId, options);
      }),
    make('browser_page', ['read', 'evaluate', 'frames', 'scroll'],
      'Observe or script a page. read returns text + fresh element refs (or HTML); evaluate requires script and executes in the webpage, not extension or host. frames returns same-process contextId and cross-process frameSessionId; pass them to page/form/keyboard/file tools. scroll requires y, optional x/selector. Re-read after navigation. Scripts may mutate; never retry automatically.',
      { tabId: id, ...selection, format: { type: 'string', enum: ['text', 'html'] }, script: str, x: num, y: num }, ['tabId'], async (a, signal) => {
        if (a.action === 'frames') {
          await b.attach(a.tabId);
          return { contexts: [...b.contexts.values()].flat().filter(e => e.tabId === a.tabId), targets: [...b.children.values()].filter(e => e.tabId === a.tabId) };
        }
        if (a.action === 'evaluate') need(a, 'script'); if (a.action === 'scroll') need(a, 'y');
        return legacy({ read: 'read_page', evaluate: 'evaluate_js', scroll: 'scroll' }[a.action], a, signal);
      }),
    make('browser_mouse', ['click', 'double_click', 'right_click', 'hover', 'drag'],
      'Mouse input in the top page viewport. Target by ref/selector or x,y. drag requires toRef/toSelector or toX,toY. For iframe-local elements use browser_form or browser_page evaluate; viewport coordinates must be top-page coordinates.',
      { tabId: id, ...selection, x: num, y: num, toRef: str, toSelector: str, toX: num, toY: num }, ['tabId'], async (a, signal) => {
        if (a.frameSessionId || a.contextId) throw new Error('鼠标坐标操作请使用顶层页面坐标；iframe 表单请使用 browser_form');
        const p = await point(b, a); signal?.throwIfAborted(); const send = (type, extra = {}) => b.command(a.tabId, 'Input.dispatchMouseEvent', { type, ...p, ...extra });
        await send('mouseMoved'); signal?.throwIfAborted();
        if (a.action === 'hover') return { hovered: true };
        const button = a.action === 'right_click' ? 'right' : 'left', clickCount = a.action === 'double_click' ? 2 : 1;
        let down = false;
        try {
          for (let n = 1; n <= clickCount; n++) {
            await send('mousePressed', { button, clickCount: n }); down = true; signal?.throwIfAborted();
            if (a.action === 'drag') {
              const end = await point(b, { tabId: a.tabId, ref: a.toRef, selector: a.toSelector, x: a.toX, y: a.toY });
              for (let i = 1; i <= 12; i++) { signal?.throwIfAborted(); await send('mouseMoved', { x: p.x + (end.x - p.x) * i / 12, y: p.y + (end.y - p.y) * i / 12, button, buttons: 1 }); }
              Object.assign(p, end);
            }
            await send('mouseReleased', { button, clickCount: n }); down = false;
          }
        } finally { if (down) await send('mouseReleased', { button, clickCount }).catch(() => {}); }
        return { performed: a.action, verify: 'browser_page read' };
      }),
    make('browser_keyboard', ['press', 'down', 'up', 'type'],
      'Keyboard input. Optionally focus ref/selector in a frame first. type requires text. press/down/up require key, optional code and modifiers array (Alt,Control,Meta,Shift). Examples: key=Enter; key=a modifiers=[Control]. press sends both keyDown/keyUp. down must be paired with up.',
      { tabId: id, ...selection, key: str, code: str, text: str, modifiers: { type: 'array', items: { type: 'string', enum: ['Alt', 'Control', 'Meta', 'Shift'] } } }, ['tabId'], async (a, signal) => {
        if (a.ref || a.selector) await dom(b, a, 'focus');
        signal?.throwIfAborted();
        if (a.action === 'type') { need(a, 'text'); await b.command(b.target(a), 'Input.insertText', { text: a.text }); return { typed: true }; }
        need(a, 'key'); const modifiers = (a.modifiers || []).reduce((v, m) => v | ({ Alt: 1, Control: 2, Meta: 4, Shift: 8 }[m]), 0);
        const codes = { Enter: 13, Tab: 9, Escape: 27, Esc: 27, Backspace: 8, Delete: 46, ArrowLeft: 37, ArrowUp: 38, ArrowRight: 39, ArrowDown: 40, Home: 36, End: 35, PageUp: 33, PageDown: 34, ' ': 32 };
        const key = a.key === 'Esc' ? 'Escape' : a.key, keyCode = codes[a.key] || (key.length === 1 ? key.toUpperCase().charCodeAt(0) : 0);
        const params = { key, code: a.code || (key.length === 1 && /[a-z]/i.test(key) ? `Key${key.toUpperCase()}` : key), modifiers, windowsVirtualKeyCode: keyCode, nativeVirtualKeyCode: keyCode };
        if (!modifiers && (key.length === 1 || key === 'Enter')) params.text = key === 'Enter' ? '\r' : key;
        const source = a.frameSessionId ? { tabId: a.tabId, sessionId: a.frameSessionId } : { tabId: a.tabId }, heldKey = `${targetKey(source)}:${params.code}`;
        if (a.action === 'up') { await b.command(source, 'Input.dispatchKeyEvent', { ...params, type: 'keyUp' }); b.heldKeys.delete(heldKey); }
        else {
          await b.command(b.target(a), 'Input.dispatchKeyEvent', { ...params, type: params.text ? 'keyDown' : 'rawKeyDown' });
          if (a.action === 'down') b.heldKeys.set(heldKey, { source, params });
          if (a.action === 'press') { try { signal?.throwIfAborted(); } finally { await b.command(b.target(a), 'Input.dispatchKeyEvent', { ...params, type: 'keyUp' }); } }
        }
        return { performed: a.action, key };
      }),
    make('browser_form', ['fill', 'select', 'check', 'focus'],
      'Operate forms in a selected frame. Requires ref or CSS selector. fill replaces text in input/textarea/contenteditable using native setters and input/change events. select requires values (option values). check requires checked. Returns current state.',
      { tabId: id, ...selection, text: str, values: strings, checked: bool }, ['tabId'], async a => {
        if (a.action === 'fill') need(a, 'text'); if (a.action === 'select') need(a, 'values'); if (a.action === 'check') need(a, 'checked');
        return dom(b, a, a.action);
      }),
    make('browser_wait', ['element', 'text', 'url', 'ready', 'delay'],
      'Cancellable wait, at most 60 seconds. element requires selector/ref with state attached/visible/hidden/detached. text requires text and optional selector. url requires url substring. ready waits for document.readyState=complete; delay requires delayMs. timeoutMs defaults 10000. iframe selected with contextId/frameSessionId.',
      { tabId: id, ...selection, text: str, url: str, state: { type: 'string', enum: ['attached', 'visible', 'hidden', 'detached'] }, timeoutMs: { type: 'integer', minimum: 1, maximum: 60000 }, delayMs: { type: 'integer', minimum: 0, maximum: 60000 } }, ['tabId'], async (a, signal) => {
        if (a.action === 'delay') { need(a, 'delayMs'); await sleep(a.delayMs, signal); return { waited: a.delayMs }; }
        if (a.action === 'element' && !a.ref && !a.selector) throw new Error('element 需要 selector 或 ref');
        if (a.action === 'text') need(a, 'text'); if (a.action === 'url') need(a, 'url');
        const start = Date.now(), timeout = a.timeoutMs || 10000;
        while (Date.now() - start <= timeout) {
          signal?.throwIfAborted();
          try { if (await b.evaluate(b.target(a), `(${waitCondition.toString()})(${JSON.stringify(a)})`, a.contextId)) return { matched: true, elapsedMs: Date.now() - start }; }
          catch (e) { if (!/context|navigat|frame|document/i.test(e.message)) throw e; }
          await sleep(Math.min(150, Math.max(1, timeout - (Date.now() - start))), signal);
        }
        throw new Error('等待页面条件超时');
      }, 65000),
    make('browser_dialog', ['get', 'handle', 'arm', 'clear'],
      'Handle JavaScript alert/confirm/prompt/beforeunload. get returns current dialog; handle requires accept and optional promptText. arm sets a ONE-SHOT response for the next dialog (call before a script/click that opens a blocking dialog). clear removes the armed response. Does not control OS file dialogs.',
      { tabId: id, accept: bool, promptText: str }, ['tabId'], async a => {
        await b.attach(a.tabId);
        if (a.action === 'get') return { dialog: b.dialogs.get(a.tabId) || null, armed: b.pendingDialogs.has(a.tabId) };
        if (a.action === 'clear') { b.pendingDialogs.delete(a.tabId); return { cleared: true }; }
        need(a, 'accept'); const response = { accept: a.accept, ...(a.promptText === undefined ? {} : { promptText: a.promptText }) };
        if (a.action === 'arm') { b.pendingDialogs.set(a.tabId, response); return { armed: true }; }
        const current = b.dialogs.get(a.tabId); if (!current) throw new Error('当前没有网页弹窗');
        await b.command(current.frameSessionId ? { tabId: a.tabId, sessionId: current.frameSessionId } : a.tabId, 'Page.handleJavaScriptDialog', response); return { handled: true };
      }),
    make('browser_capture', ['viewport', 'full_page', 'element', 'region'],
      'Capture PNG image. element requires ref/selector in selected frame; region requires x,y,width,height in main document CSS coordinates. Full page is limited to 32767 pixels per side and 64 million pixels to bound memory.',
      { tabId: id, ...selection, x: num, y: num, width: { type: 'number', exclusiveMinimum: 0 }, height: { type: 'number', exclusiveMinimum: 0 } }, ['tabId'], a => capture(b, a)),
    make('browser_network', ['start', 'stop', 'list', 'body', 'clear'],
      'Observe network activity after start. list filters URL substring and returns at most 100 recent requests. body requires requestId from list (id field), returns bounded response body when available. Network capture and response buffers are bounded; eviction/unavailable bodies are reported. No automatic request replay.',
      { tabId: id, url: str, requestId: str, limit: { type: 'integer', minimum: 1, maximum: 100 } }, ['tabId'], a => network(b, a)),
    make('browser_console', ['start', 'stop', 'list', 'clear'],
      'Collect console messages, JavaScript errors and browser logs AFTER start. Bounded to 500 records, 8000 characters each. list supports level filter and limit <=100.',
      { tabId: id, level: str, limit: { type: 'integer', minimum: 1, maximum: 100 } }, ['tabId'], a => consoleTool(b, a)),
    make('browser_storage', ['list', 'get', 'set', 'remove', 'clear', 'cookies', 'set_cookie', 'remove_cookie'],
      'Page origin storage. list/get/set/remove/clear use area local/session (default local), key and value as needed, in selected frame. cookies queries URLs of this page (or url); set_cookie requires cookie object with name/value/url; remove_cookie requires name/url. Cookie scope is limited to tab origin. Use only for the requested task.',
      { tabId: id, ...selection, area: { type: 'string', enum: ['local', 'session'] }, key: str, value: str, url: str, name: str, cookie: object }, ['tabId'], a => storage(b, a)),
    make('browser_files', ['set_input', 'inspect'],
      'File inputs in the USER BROWSER. set_input requires ref/selector and exactly one source: paths (absolute paths on browser computer), files [{name,mimeType,dataBase64}] (<=8 MiB total), or hostPaths (Starweave execution-machine files, relayed internally via shell; requires host shell tool). File content is not echoed to the model. inspect returns selected file metadata only. OS chooser clicking is unnecessary.',
      { tabId: id, ...selection, paths: strings, hostPaths: strings, files: { type: 'array', minItems: 1, maxItems: 20, items: { type: 'object', properties: { name: str, mimeType: str, dataBase64: str }, required: ['name', 'dataBase64'], additionalProperties: false } } }, ['tabId'], (a, signal) => fileInput(b, a, signal, options.fileRelay)),
    make('browser_downloads', ['start', 'list', 'pause', 'resume', 'cancel', 'show', 'remove_file', 'erase'],
      'Browser-computer downloads, independent of host filesystem. start requires HTTP/HTTPS url and optional relative filename, saveAs; list filters query/state/id with limit <=100. Other actions require downloadId. remove_file deletes downloaded file; erase removes only its history record. Safe Browsing and user-prompt restrictions still apply.',
      { url: str, filename: str, saveAs: bool, query: str, state: { type: 'string', enum: ['in_progress', 'interrupted', 'complete'] }, downloadId: id, limit: { type: 'integer', minimum: 1, maximum: 100 } }, [], a => downloads(b, a)),
    make('browser_bookmarks', ['tree', 'search', 'create', 'update', 'move', 'remove'],
      'Manage browser bookmarks/folders. tree lists direct children (root if bookmarkId omitted); search requires query. create requires title and optional url/parentId/index (no url creates folder). update/move/remove require bookmarkId. remove on folder requires recursive=true to remove its subtree.',
      { bookmarkId: str, parentId: str, title: str, url: str, query: str, index: { type: 'integer', minimum: 0 }, recursive: bool }, [], a => bookmarks(b, a)),
    make('browser_history', ['search', 'visits', 'delete_url', 'delete_range'],
      'Browser history. search accepts query/startTime/endTime (epoch milliseconds), limit <=100. visits/delete_url require url. delete_range requires explicit startTime AND endTime. Deletes affect only specified URL or interval.',
      { query: str, url: str, startTime: num, endTime: num, limit: { type: 'integer', minimum: 1, maximum: 100 } }, [], async a => {
        if (a.action === 'search') return b.api.history.search({ text: a.query || '', startTime: a.startTime ?? 0, ...(a.endTime === undefined ? {} : { endTime: a.endTime }), maxResults: a.limit || 50 });
        if (a.action === 'delete_range') { need(a, 'startTime', 'endTime'); if (a.startTime > a.endTime) throw new Error('startTime 不能晚于 endTime'); await b.api.history.deleteRange({ startTime: a.startTime, endTime: a.endTime }); }
        else { need(a, 'url'); const options = { url: new URL(a.url).href }; if (a.action === 'visits') return b.api.history.getVisits(options); await b.api.history.deleteUrl(options); }
        return { ok: true };
      }),
    make('browser_clipboard', ['read', 'write'],
      'Read/write plain text on the browser computer clipboard. write requires text. Uses an extension offscreen document and clipboard permissions; this is separate from the Starweave host clipboard.',
      { text: str }, [], a => clipboard(b, a))
  ];
}

function elementExpression(a) {
  if (!a.ref && !a.selector) throw new Error('需要 ref 或 selector');
  return a.ref ? `window.__starweaveBrowserRefs?.get(${JSON.stringify(a.ref)})` : `document.querySelector(${JSON.stringify(a.selector)})`;
}
async function point(b, a) {
  if (a.ref || a.selector) return b.evaluate(b.target(a), `(()=>{const e=${elementExpression(a)};if(!e?.isConnected)throw new Error('元素引用已失效');e.scrollIntoView({block:'center',inline:'center'});const r=e.getBoundingClientRect();if(!r.width||!r.height)throw new Error('元素不可见');return {x:r.x+r.width/2,y:r.y+r.height/2}})()`, a.contextId);
  need(a, 'x', 'y'); return { x: a.x, y: a.y };
}
async function dom(b, a, action) {
  return b.evaluate(b.target(a), `(${formAction.toString()})(${elementExpression(a)},${JSON.stringify({ ...a, action })})`, a.contextId);
}
function formAction(e, a) {
  if (!e?.isConnected) throw new Error('元素引用已失效，请重新读取页面');
  if (e.disabled) throw new Error('目标元素已禁用');
  e.scrollIntoView({ block: 'center', inline: 'center' }); e.focus();
  if (a.action === 'fill') {
    if (e instanceof HTMLInputElement || e instanceof HTMLTextAreaElement) {
      if (e.readOnly || e.type === 'file') throw new Error('目标不可填写');
      const proto = e instanceof HTMLTextAreaElement ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
      Object.getOwnPropertyDescriptor(proto, 'value').set.call(e, a.text);
    } else if (e.isContentEditable) e.textContent = a.text;
    else throw new Error('目标不是文本输入元素');
  }
  if (a.action === 'select') {
    if (!(e instanceof HTMLSelectElement)) throw new Error('目标不是下拉框');
    if (!e.multiple && a.values.length !== 1) throw new Error('单选下拉框需要一个值');
    for (const value of a.values) if (![...e.options].some(o => o.value === value && !o.disabled)) throw new Error('选项不存在或不可用：' + value);
    for (const o of e.options) o.selected = a.values.includes(o.value);
  }
  if (a.action === 'check') {
    if (!(e instanceof HTMLInputElement) || !['checkbox', 'radio'].includes(e.type)) throw new Error('目标不是复选框或单选框');
    if (e.type === 'radio' && !a.checked) throw new Error('请选择同组其他单选框');
    if (e.checked !== a.checked) e.click();
  }
  if (['fill', 'select'].includes(a.action)) { e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true })); }
  return { tag: e.tagName.toLowerCase(), value: e.type === 'password' ? '[password]' : e.value, checked: e.checked, text: e.isContentEditable ? e.textContent : undefined };
}
function waitCondition(a) {
  const e = a.ref ? window.__starweaveBrowserRefs?.get(a.ref) : a.selector ? document.querySelector(a.selector) : document.documentElement;
  if (a.action === 'element') {
    const attached = Boolean(e?.isConnected), visible = attached && Boolean(e.getClientRects().length) && getComputedStyle(e).visibility !== 'hidden' && getComputedStyle(e).display !== 'none';
    return { attached, visible, hidden: !visible, detached: !attached }[a.state || 'visible'];
  }
  if (a.action === 'text') return Boolean(e?.textContent?.includes(a.text));
  if (a.action === 'url') return location.href.includes(a.url);
  return document.readyState === 'complete' && (!a.url || location.href.includes(a.url));
}
async function capture(b, a) {
  let clip;
  if (a.action === 'full_page') {
    const layout = await b.command(a.tabId, 'Page.getLayoutMetrics'), size = layout.cssContentSize || layout.contentSize;
    clip = { x: 0, y: 0, width: size.width, height: size.height, scale: 1 };
  }
  if (a.action === 'region') { need(a, 'x', 'y', 'width', 'height'); clip = { x: a.x, y: a.y, width: a.width, height: a.height, scale: 1 }; }
  if (a.action === 'element') {
    if (a.frameSessionId || a.contextId) throw new Error('元素截图请使用顶层页面元素或主页面区域坐标');
    clip = await b.evaluate(a.tabId, `(()=>{const e=${elementExpression(a)};if(!e?.isConnected)throw new Error('找不到元素');const r=e.getBoundingClientRect();return {x:r.x+scrollX,y:r.y+scrollY,width:r.width,height:r.height,scale:1}})()`);
  }
  if (clip && (!Number.isFinite(clip.width * clip.height) || clip.width <= 0 || clip.height <= 0 || clip.width > 32767 || clip.height > 32767 || clip.width * clip.height > 64000000)) throw new Error('截图尺寸超过限制或区域不可见');
  await b.command(a.tabId, 'Page.bringToFront');
  const result = await b.command(a.tabId, 'Page.captureScreenshot', { format: 'png', captureBeyondViewport: Boolean(clip), ...(clip ? { clip } : {}) });
  if (result.data.length > 16 * 1024 * 1024) throw new Error('截图 PNG 超过 12 MiB，请缩小截图区域');
  return { content: [{ type: 'image', mimeType: 'image/png', data: result.data }] };
}
function targets(b, tabId) { return [{ tabId }, ...[...b.children.values()].filter(c => c.tabId === tabId && c.type === 'iframe').map(c => ({ tabId, sessionId: c.sessionId }))]; }
async function network(b, a) {
  await b.attach(a.tabId); const state = ring(b, a.tabId);
  if (a.action === 'clear') { state.requests.clear(); return { cleared: true }; }
  if (a.action === 'start' || a.action === 'stop') {
    state.network = a.action === 'start';
    await Promise.all(targets(b, a.tabId).map(t => b.command(t, state.network ? 'Network.enable' : 'Network.disable', state.network ? { maxTotalBufferSize: 8 * 1024 * 1024, maxResourceBufferSize: 2 * 1024 * 1024, maxPostDataSize: 0 } : {})));
    return { capturing: state.network };
  }
  if (a.action === 'list') return { capturing: state.network, requests: [...state.requests.values()].filter(r => !a.url || r.url.includes(a.url)).slice(-(a.limit || 50)) };
  need(a, 'requestId'); const request = state.requests.get(a.requestId); if (!request) throw new Error('请求不存在或已从缓冲区淘汰');
  if (request.encodedDataLength > 2 * 1024 * 1024) throw new Error('响应超过 2 MiB，不读取正文');
  const result = await b.command(request.frameSessionId ? { tabId: a.tabId, sessionId: request.frameSessionId } : a.tabId, 'Network.getResponseBody', { requestId: request.requestId });
  return { ...result, body: result.body.slice(0, 200000), truncated: result.body.length > 200000 };
}
async function consoleTool(b, a) {
  await b.attach(a.tabId); const state = ring(b, a.tabId);
  if (a.action === 'clear') state.logs = [];
  if (a.action === 'start') { state.console = true; await b.command(a.tabId, 'Log.enable'); }
  if (a.action === 'stop') { state.console = false; await b.command(a.tabId, 'Log.disable'); }
  return { capturing: state.console, logs: state.logs.filter(l => !a.level || l.level === a.level).slice(-(a.limit || 50)) };
}
async function storage(b, a) {
  if (['cookies', 'set_cookie', 'remove_cookie'].includes(a.action)) {
    const origin = await b.evaluate(b.target(a), 'location.origin', a.contextId);
    const requested = a.action === 'set_cookie' ? a.cookie?.url : a.url;
    if (requested && new URL(requested).origin !== origin) throw new Error('Cookie 操作仅限当前 frame 的 origin');
    const cookieUrl = requested || origin + '/';
    if (a.action === 'cookies') return b.command(b.target(a), 'Network.getCookies', { urls: [cookieUrl] });
    if (a.action === 'remove_cookie') { need(a, 'name'); return b.command(b.target(a), 'Network.deleteCookies', { name: a.name, url: cookieUrl }); }
    need(a, 'cookie'); need(a.cookie, 'name', 'value');
    // A caller cannot use domain to escape the validated origin.
    const allowed = ['name', 'value', 'path', 'secure', 'httpOnly', 'sameSite', 'expires'];
    const params = Object.fromEntries(Object.entries(a.cookie).filter(([k]) => allowed.includes(k)));
    return b.command(b.target(a), 'Network.setCookie', { ...params, url: cookieUrl });
  }
  if (['get', 'set', 'remove'].includes(a.action)) need(a, 'key'); if (a.action === 'set') need(a, 'value');
  return b.evaluate(b.target(a), `(${storageAction.toString()})(${JSON.stringify(a)})`, a.contextId);
}
function storageAction(a) {
  const storage = a.area === 'session' ? sessionStorage : localStorage;
  if (a.action === 'get') return { key: a.key, value: storage.getItem(a.key) };
  if (a.action === 'set') storage.setItem(a.key, a.value);
  if (a.action === 'remove') storage.removeItem(a.key);
  if (a.action === 'clear') storage.clear();
  const result = []; for (let i = 0; i < Math.min(storage.length, 500); i++) { const key = storage.key(i); result.push({ key, value: storage.getItem(key)?.slice(0, 8000) }); }
  return { items: result, truncated: storage.length > 500 };
}
async function fileInput(b, a, signal, fileRelay) {
  const expression = elementExpression(a);
  if (a.action === 'inspect') return b.evaluate(b.target(a), `(()=>{const e=${expression};if(!(e instanceof HTMLInputElement)||e.type!=='file')throw new Error('目标不是文件输入框');return [...e.files].map(f=>({name:f.name,size:f.size,type:f.type}))})()`, a.contextId);
  const sources = ['paths', 'hostPaths', 'files'].filter(k => a[k] !== undefined);
  if (sources.length !== 1 || !a[sources[0]].length) throw new Error('必须提供 paths、hostPaths 或 files 中的一种非空来源');
  if (a.paths) {
    for (const path of a.paths) if (!/^(\/|[A-Za-z]:[\\/])/.test(path)) throw new Error('paths 必须是浏览器电脑的绝对路径');
    const remote = await b.command(b.target(a), 'Runtime.evaluate', { expression: `(()=>{const e=${expression};if(!(e instanceof HTMLInputElement)||e.type!=='file')throw new Error('目标不是文件输入框');return e})()`, ...(a.contextId === undefined ? {} : { contextId: a.contextId }) });
    if (!remote.result?.objectId || remote.exceptionDetails) throw new Error('找不到文件输入框');
    try { signal?.throwIfAborted(); await b.command(b.target(a), 'DOM.setFileInputFiles', { objectId: remote.result.objectId, files: a.paths }); }
    finally { await b.command(b.target(a), 'Runtime.releaseObject', { objectId: remote.result.objectId }).catch(() => {}); }
  } else {
    let files = a.files;
    if (a.hostPaths) {
      if (!fileRelay) throw new Error('当前会话未配置宿主文件中转');
      files = await fileRelay(a.hostPaths, signal);
    }
    let bytes = 0;
    for (const f of files) {
      if (!f.name || /[\\/]/.test(f.name)) throw new Error('文件名不能含目录');
      if (f.dataBase64.length > 12 * 1024 * 1024) throw new Error('上传文件超过 8 MiB');
      try { bytes += atob(f.dataBase64).length; } catch (_) { throw new Error('无效的文件 Base64'); }
    }
    if (bytes > 8 * 1024 * 1024) throw new Error('上传文件合计超过 8 MiB');
    signal?.throwIfAborted();
    await b.evaluate(b.target(a), `(${assignFiles.toString()})(${expression},${JSON.stringify(files)})`, a.contextId);
  }
  signal?.throwIfAborted();
  return fileInput(b, { ...a, action: 'inspect' }, signal, fileRelay);
}
function assignFiles(e, files) {
  if (!(e instanceof HTMLInputElement) || e.type !== 'file' || e.disabled) throw new Error('目标不是可用的文件输入框');
  if (!e.multiple && files.length > 1) throw new Error('此输入框只允许一个文件');
  const transfer = new DataTransfer();
  for (const f of files) { const data = Uint8Array.from(atob(f.dataBase64), c => c.charCodeAt(0)); transfer.items.add(new File([data], f.name, { type: f.mimeType || 'application/octet-stream' })); }
  e.files = transfer.files; e.dispatchEvent(new Event('input', { bubbles: true })); e.dispatchEvent(new Event('change', { bubbles: true }));
  return files.length;
}
async function downloads(b, a) {
  if (a.action === 'start') { need(a, 'url'); return { downloadId: await b.api.downloads.download({ url: url(a.url), ...(a.filename ? { filename: a.filename } : {}), saveAs: a.saveAs || false }) }; }
  if (a.action === 'list') return b.api.downloads.search({ ...(a.downloadId ? { id: a.downloadId } : {}), ...(a.query ? { query: [a.query] } : {}), ...(a.state ? { state: a.state } : {}), limit: a.limit || 50, orderBy: ['-startTime'] });
  need(a, 'downloadId');
  if (a.action === 'erase') return b.api.downloads.erase({ id: a.downloadId });
  await b.api.downloads[{ pause: 'pause', resume: 'resume', cancel: 'cancel', show: 'show', remove_file: 'removeFile' }[a.action]](a.downloadId);
  return { ok: true, downloadId: a.downloadId };
}
async function bookmarks(b, a) {
  if (a.action === 'tree') return b.api.bookmarks.getChildren(a.bookmarkId || '0');
  if (a.action === 'search') { need(a, 'query'); return (await b.api.bookmarks.search(a.query)).slice(0, 100); }
  if (a.action === 'create') { need(a, 'title'); return b.api.bookmarks.create({ title: a.title, ...(a.url ? { url: url(a.url) } : {}), ...(a.parentId ? { parentId: a.parentId } : {}), ...(a.index === undefined ? {} : { index: a.index }) }); }
  need(a, 'bookmarkId');
  if (a.action === 'update') { if (a.title === undefined && a.url === undefined) throw new Error('需要 title 或 url'); return b.api.bookmarks.update(a.bookmarkId, { ...(a.title === undefined ? {} : { title: a.title }), ...(a.url === undefined ? {} : { url: url(a.url) }) }); }
  if (a.action === 'move') { if (a.parentId === undefined && a.index === undefined) throw new Error('需要 parentId 或 index'); return b.api.bookmarks.move(a.bookmarkId, { ...(a.parentId === undefined ? {} : { parentId: a.parentId }), ...(a.index === undefined ? {} : { index: a.index }) }); }
  await b.api.bookmarks[a.recursive ? 'removeTree' : 'remove'](a.bookmarkId); return { removed: a.bookmarkId };
}
async function clipboard(b, a) {
  if (a.action === 'write') need(a, 'text');
  if (!b.clipboardReady) b.clipboardReady = (async () => {
    const existing = await b.api.runtime.getContexts({ contextTypes: ['OFFSCREEN_DOCUMENT'], documentUrls: [b.api.runtime.getURL('clipboard.html')] });
    if (!existing.length) await b.api.offscreen.createDocument({ url: 'clipboard.html', reasons: ['CLIPBOARD'], justification: 'Read or write text when the Agent invokes browser_clipboard.' });
  })().catch(e => { b.clipboardReady = null; throw e; });
  await b.clipboardReady;
  const result = await b.api.runtime.sendMessage({ target: 'starweave-clipboard', action: a.action, text: a.text });
  if (!result || result.error) throw new Error(result?.error || '剪贴板文档未返回结果');
  return result;
}

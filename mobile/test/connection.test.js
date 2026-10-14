'use strict';
const {test} = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const vm = require('node:vm');
const path = require('node:path');
const script = fs.readFileSync(path.join(__dirname, '../www/app.js'), 'utf8');
const controls = fs.readFileSync(path.join(__dirname, '../www/page-controls.js'), 'utf8');

function harness(saved) {
  const handlers = {}, calls = [], stored = new Map(saved ? [['starweave.serverUrl', saved]] : []);
  const elements = Object.fromEntries(['serverUrl', 'connectButton', 'status', 'connectionForm'].map(id => [id, {
    value: '', disabled: true, hidden: true, textContent: '', addEventListener: (name, fn) => handlers[id + ':' + name] = fn
  }]));
  const timers = new Map(); let timerId = 0;
  const context = vm.createContext({URL, document: {
    getElementById: id => elements[id], addEventListener: (name, fn) => handlers[name] = fn
  }, localStorage: {getItem: key => stored.get(key), setItem: (key, value) => stored.set(key, value)},
  navigator: {app: {exitApp: () => calls.push('exitApp')}},
  setTimeout: fn => {timers.set(++timerId, fn); return timerId;}, clearTimeout: id => timers.delete(id),
  cordova: {InAppBrowser: {open: (url, target, options) => {
    const listeners = {};
    const view = {url, target, options, listeners, shown: false, closed: false,
      scripts: [], executeScript: (details, callback) => {view.scripts.push(details.code); callback();},
      addEventListener: (name, fn) => listeners[name] = fn,
      show: () => view.shown = true,
      close: () => {view.closed = true; listeners.exit();}
    }; calls.push(view); return view;
  }}}
  });
  // Browsers already own window.status: assigning a DOM node coerces it to text.
  // Keep that property in the harness so a global-name collision fails here too.
  let windowStatus = '';
  Object.defineProperty(context, 'status', {get: () => windowStatus, set: value => windowStatus = String(value)});
  vm.runInContext(controls, context);
  vm.runInContext(script, context);
  return {context, handlers, calls, stored, elements, timers,
    ready: () => handlers.deviceready(),
    submit: value => {elements.serverUrl.value = value; handlers['connectionForm:submit']({preventDefault() {}});}
  };
}

test('valid HTTP and HTTPS endpoints retain subpaths and query strings; reject credentials and non-web protocols', () => {
  const h = harness();
  assert.equal(h.context.normalizeServerUrl(' https://example.com/starweave/?instance=x '), 'https://example.com/starweave/?instance=x');
  assert.equal(h.context.normalizeServerUrl('http://192.168.1.10:10528'), 'http://192.168.1.10:10528/');
  for (const value of ['file:///etc/passwd', 'javascript:alert(1)', 'https://user:secret@example.com', 'bad-address']) {
    assert.throws(() => h.context.normalizeServerUrl(value));
  }
});

test('wait for native readiness, open once, persist only after successful load, and allow switching server', () => {
  const h = harness(); h.submit('https://example.com'); assert.equal(h.calls.length, 0);
  h.ready(); h.submit('https://example.com'); h.submit('https://other.example.com');
  assert.equal(h.calls.length, 1); assert.equal(h.stored.size, 0);
  const view = h.calls[0]; assert.equal(view.target, '_blank');
  assert.match(view.options, /location=no/); assert.match(view.options, /fullscreen=no/);
  view.listeners.loadstop({url: view.url}); assert.equal(view.shown, true);
  assert.equal(view.scripts.length, 1);
  assert.equal(h.stored.get('starweave.serverUrl'), 'https://example.com/');
  assert.equal(h.timers.size, 0);
  view.close(); assert.equal(h.elements.connectButton.disabled, false);
  h.submit('https://other.example.com'); assert.equal(h.calls.length, 2);
});

test('launch reconnects to saved endpoint and hardware back from launcher exits app', () => {
  const h = harness('https://example.com'); assert.equal(h.elements.connectionForm.hidden, true);
  h.ready(); assert.equal(h.calls.length, 1); assert.equal(h.elements.connectionForm.hidden, true);
  h.handlers.backbutton(); assert.equal(h.calls[0].closed, true);
  assert.equal(h.elements.connectionForm.hidden, false);
  h.handlers.backbutton(); assert.equal(h.calls[1], 'exitApp');
});

test('network or TLS failures and timeout return to editable connection form without overwriting saved URL', () => {
  for (const fail of ['loaderror', 'timeout']) {
    const h = harness('https://previous.example.com'); h.ready();
    const view = h.calls[0];
    if (fail === 'loaderror') view.listeners.loaderror({message: 'net::ERR_CERT_AUTHORITY_INVALID'});
    else [...h.timers.values()][0]();
    assert.equal(view.closed, true); assert.equal(h.elements.connectButton.disabled, false);
    assert.equal(h.elements.connectionForm.hidden, false);
    assert.ok(h.elements.status.textContent.length > 0);
    assert.equal(h.stored.get('starweave.serverUrl'), 'https://previous.example.com');
    h.submit('https://new.example.com'); assert.equal(h.calls.length, 2);
    view.listeners.loadstop({url: view.url}); assert.equal(view.shown, false);
  }
});

test('fresh installs show the connection form only after native readiness', () => {
  const h = harness(); assert.equal(h.elements.connectionForm.hidden, true);
  h.ready(); assert.equal(h.elements.connectionForm.hidden, false); assert.equal(h.calls.length, 0);
});

test('APK settings action closes only the current view; unrelated or stale messages have no effect', () => {
  const h = harness(); h.ready(); h.submit('https://example.com');
  const first = h.calls[0];
  first.listeners.message({data: {type: 'unknown'}}); assert.equal(first.closed, false);
  first.listeners.message({data: {type: 'starweave-switch-server'}}); assert.equal(first.closed, true);
  h.submit('https://second.example.com');
  first.listeners.message({data: {type: 'starweave-switch-server'}}); assert.equal(h.calls[1].closed, false);
});

test('page controls are injected only into the configured server origin, including relative-path deployments', () => {
  const h = harness(); h.ready(); h.submit('https://example.com/starweave/');
  const view = h.calls[0];
  view.listeners.loadstop({url: 'https://login.other.example.com/'});
  assert.equal(view.scripts.length, 0); assert.equal(view.shown, true);
  view.listeners.loadstop({url: 'https://example.com/starweave/'});
  assert.equal(view.scripts.length, 1);
});

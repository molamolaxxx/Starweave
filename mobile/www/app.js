'use strict';

function normalizeServerUrl(value) {
  var url = new URL(value.trim());
  if (!/^https?:$/.test(url.protocol) || !url.hostname || url.username || url.password) {
    throw new Error('请填写 HTTP 或 HTTPS 服务地址，不要在地址中包含密码。');
  }
  return url.href;
}

var browser = null;
var browserOrigin = '';
var ready = false;
var storageKey = 'starweave.serverUrl';
var input = document.getElementById('serverUrl');
var button = document.getElementById('connectButton');
var connectionStatus = document.getElementById('status');
var connectionForm = document.getElementById('connectionForm');

function connect() {
  if (!ready || browser) return;
  var url;
  try { url = normalizeServerUrl(input.value); }
  catch (error) { connectionForm.hidden = false; connectionStatus.textContent = error.message; return; }
  connectionStatus.textContent = '';
  try {
    // A separate WebView keeps server pages on their original origin, including
    // login cookies, relative APIs and event streams. No Cordova bridge is exposed.
    browser = cordova.InAppBrowser.open(url, '_blank',
      'location=no,fullscreen=no,hardwareback=yes,zoom=no,hidden=yes');
    browserOrigin = new URL(url).origin;
    button.disabled = true;
    var current = browser;
    var timeout = setTimeout(function () {
      if (browser === current) {
        connectionStatus.textContent = '连接超时，请检查服务地址和手机网络。';
        current.close();
      }
    }, 30000);
    current.addEventListener('loadstop', function (event) {
      if (browser !== current) return;
      clearTimeout(timeout);
      try { localStorage.setItem(storageKey, url); } catch (_) {}
      var sameOrigin = false;
      try { sameOrigin = new URL(event.url).origin === new URL(url).origin; } catch (_) {}
      if (sameOrigin) {
        current.executeScript({code: '(' + starweaveInstallPageControls.toString() + ')(' + JSON.stringify(url) + ');'}, function () {
          if (browser === current) current.show();
        });
      } else current.show();
    });
    current.addEventListener('message', function (event) {
      if (browser !== current || !event.data) return;
      if (event.data.type === 'starweave-switch-server') current.close();
      else if (event.data.type === 'starweave-open-external') {
        // A different-origin page must not gain native link-opening privileges.
        current.executeScript({code: 'window.location.origin;'}, function (origins) {
          if (browser === current && origins && origins[0] === browserOrigin) openSystemLink(event.data.url);
        });
      }
    });
    current.addEventListener('download', function (event) {
      if (browser === current && event && /^https?:/i.test(event.url || '')) openSystemLink(event.url);
    });
    current.addEventListener('loaderror', function (event) {
      if (browser !== current) return;
      clearTimeout(timeout);
      connectionStatus.textContent = '无法打开服务，请检查地址、网络和证书。' + (event.message ? '（' + event.message + '）' : '');
      current.close();
    });
    current.addEventListener('exit', function () {
      clearTimeout(timeout);
      if (browser === current) { browser = null; browserOrigin = ''; }
      button.disabled = false;
      connectionForm.hidden = false;
    });
  } catch (error) {
    browser = null;
    browserOrigin = '';
    button.disabled = false;
    connectionForm.hidden = false;
    connectionStatus.textContent = '连接失败：' + error.message;
  }
}

// Only explicitly supported URL schemes cross into the system browser/app.
function openSystemLink(value) {
  try {
    var url = new URL(value);
    if (!/^(https?:|mailto:|tel:)$/.test(url.protocol) || url.username || url.password) return;
    cordova.InAppBrowser.open(url.href, '_system');
  } catch (_) {}
}

// Cordova lifecycle belongs to the launcher WebView; relay it to the same-origin server page.
function notifyServerLifecycle(type) {
  var current = browser;
  if (!current || !browserOrigin) return;
  var code = '(function(){if(window.location.origin===' + JSON.stringify(browserOrigin) + '){window.dispatchEvent(new Event(' + JSON.stringify(type) + '));}})();';
  try { current.executeScript({code: code}, function () {}); } catch (_) {}
}
document.addEventListener('pause', function () { notifyServerLifecycle('starweave:pause'); });
document.addEventListener('resume', function () { notifyServerLifecycle('starweave:resume'); });

document.getElementById('connectionForm').addEventListener('submit', function (event) {
  event.preventDefault();
  connect();
});
document.addEventListener('deviceready', function () {
  ready = true;
  button.disabled = false;
  try { input.value = localStorage.getItem(storageKey) || ''; } catch (_) {}
  if (input.value) connect();
  else connectionForm.hidden = false;
});
document.addEventListener('backbutton', function () {
  if (browser) browser.close();
  else navigator.app.exitApp();
});

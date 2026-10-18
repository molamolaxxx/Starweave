'use strict';

// Injected by the APK after a same-origin load; desktop and server files stay untouched.
function starweaveInstallPageControls(serverUrl) {
  if (window.starweaveMobileControlsInstalled) return;
  var bridge = window.cordova_iab || (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.cordova_iab);
  if (!bridge || typeof bridge.postMessage !== 'function') return;
  window.starweaveMobileControlsInstalled = true;
  // WebView cannot create browser tabs; forward link clicks through the limited bridge.
  function openExternal(value) {
    var url;
    try { url = new URL(value, window.location.href); } catch (_) { return false; }
    if (!/^(https?:|mailto:|tel:)$/.test(url.protocol) || url.username || url.password) return false;
    bridge.postMessage(JSON.stringify({type: 'starweave-open-external', url: url.href}));
    return true;
  }
  document.addEventListener('click', function (event) {
    var link = event.target.closest && event.target.closest('a[href]');
    if (!link || event.defaultPrevented || event.button > 0) return;
    var url;
    try { url = new URL(link.href, window.location.href); } catch (_) { return; }
    // Keep in-page anchors and normal application navigation inside the current view.
    if (url.origin === window.location.origin && url.pathname === window.location.pathname &&
        url.search === window.location.search && url.hash && !link.hasAttribute('download')) return;
    if (link.target === '_blank' || url.origin !== window.location.origin || link.hasAttribute('download') || /\.apk$/i.test(url.pathname)) {
      if (openExternal(url.href)) event.preventDefault();
    }
  });
  window.open = function (url) { openExternal(url); return null; };
  var section = document.getElementById('page-basic');
  var port = document.getElementById('cfgPort');
  var settingsBody = port && port.closest('.card-body');
  if (section && settingsBody && !document.getElementById('starweaveMobileSettings')) {
    var settings = document.createElement('div');
    settings.id = 'starweaveMobileSettings';
    settings.className = 'toggle-row';
    settings.innerHTML = '<div class="toggle-label" id="starweaveMobileServerLabel">服务地址</div><div class="field-group"><div class="field-row"><input id="starweaveMobileServer" readonly aria-labelledby="starweaveMobileServerLabel"><button type="button" class="btn btn-secondary btn-sm" id="starweaveMobileSwitch">更换服务地址</button></div></div>';
    settingsBody.appendChild(settings);
    document.getElementById('starweaveMobileServer').value = serverUrl || window.location.href;
    document.getElementById('starweaveMobileSwitch').addEventListener('click', function () {
      bridge.postMessage(JSON.stringify({type: 'starweave-switch-server'}));
    });
  }
  function syncTheme() {
    bridge.postMessage(JSON.stringify({type: 'starweave-theme', theme: document.documentElement.dataset.theme === 'dark' ? 'dark' : 'light'}));
  }
  syncTheme();
  new MutationObserver(syncTheme).observe(document.documentElement, {attributes: true, attributeFilter: ['data-theme']});

  var style = document.createElement('style');
  // Suppress WebView's rectangular tap overlay without removing keyboard focus or text selection.
  style.textContent = 'html{-webkit-tap-highlight-color:transparent}button:focus:not(:focus-visible),a:focus:not(:focus-visible),[role=button]:focus:not(:focus-visible){outline:none}button:enabled:active,a[href]:active,[role=button]:not([aria-disabled=true]):active{opacity:.78}' + '#starweaveMobileSettings{display:block}#starweaveMobileSettings .field-group{margin:8px 0 0}#starweaveMobileSettings .field-row{display:flex;flex-wrap:nowrap;gap:8px}#starweaveMobileSettings input{flex:1;min-width:0}#starweaveMobileSettings button{flex:0 0 auto;white-space:nowrap}';
  document.head.appendChild(style);
}

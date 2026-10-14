'use strict';

// Injected by the APK after a same-origin load; desktop and server files stay untouched.
function starweaveInstallPageControls(serverUrl) {
  if (window.starweaveMobileControlsInstalled) return;
  var bridge = window.cordova_iab || (window.webkit && window.webkit.messageHandlers && window.webkit.messageHandlers.cordova_iab);
  if (!bridge || typeof bridge.postMessage !== 'function') return;
  window.starweaveMobileControlsInstalled = true;
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
  style.textContent = '#starweaveMobileSettings{display:block}#starweaveMobileSettings .field-group{margin:8px 0 0}#starweaveMobileSettings .field-row{display:flex;flex-wrap:nowrap;gap:8px}#starweaveMobileSettings input{flex:1;min-width:0}#starweaveMobileSettings button{flex:0 0 auto;white-space:nowrap}';
  document.head.appendChild(style);
}

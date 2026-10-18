'use strict';
// Optional browser check: requires Playwright and Chrome, like ConfigUI browser tests.
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const assert = require('node:assert/strict');
const {chromium} = require('playwright');
const root = path.resolve(__dirname, '../../cmd-proxy-app/src/main/resources/configui');
const controls = fs.readFileSync(path.join(__dirname, '../www/page-controls.js'), 'utf8');
const core = fs.readFileSync(path.join(root, 'assets/js/core.js'), 'utf8');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8')
  .replace(/<script\b[^>]*>[\s\S]*?<\/script>/g, '')
  .replace('</head>', '<script src="/assets/js/theme.js"></script></head>');
const server = http.createServer((request, response) => {
  if (request.url === '/favicon.ico') {response.writeHead(204); response.end(); return;}
  if (request.url.startsWith('/launcher/')) {
    if (request.url === '/launcher/cordova.js') {
      response.setHeader('Content-Type', 'application/javascript');
      response.end(`window.views=[];window.cordova={InAppBrowser:{open:function(url,target,options){var v={url:url,listeners:{},addEventListener:function(n,f){v.listeners[n]=f},executeScript:function(details,callback){callback()},show:function(){v.shown=true},close:function(){v.listeners.exit()}};views.push(v);return v}}};window.fireNativeReady=function(){document.dispatchEvent(new Event('deviceready'))};`);
    } else {
      const name = request.url === '/launcher/' ? 'index.html' : request.url.slice('/launcher/'.length);
      const file = path.join(__dirname, '../www', name);
      response.setHeader('Content-Type', name.endsWith('.css') ? 'text/css' : name.endsWith('.js') ? 'application/javascript' : name.endsWith('.svg') ? 'image/svg+xml' : 'text/html');
      response.end(fs.readFileSync(file));
    }
    return;
  }
  if (request.url.startsWith('/assets/')) {
    const file = path.join(root, request.url);
    response.setHeader('Content-Type', file.endsWith('.css') ? 'text/css' : file.endsWith('.woff2') ? 'font/woff2' : 'application/javascript');
    response.end(fs.readFileSync(file));
  } else {response.setHeader('Content-Type', 'text/html'); response.end(html);}
});

(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox']});
  const failures = [];
  try {
    for (const saved of ['', 'https://starweave.example.com/path/']) {
      const boot = await browser.newPage({viewport: {width: 390, height: 844}});
      await boot.addInitScript(value => {if (value) localStorage.setItem('starweave.serverUrl', value);}, saved);
      boot.on('pageerror', error => failures.push(error.message));
      await boot.goto('http://127.0.0.1:' + server.address().port + '/launcher/');
      assert.equal(await boot.locator('#connectionForm').isVisible(), false, 'no address form before native readiness');
      await boot.evaluate(() => fireNativeReady());
      assert.equal(await boot.locator('#connectionForm').isVisible(), !saved);
      if (saved) {
        assert.equal(await boot.evaluate(() => views.length), 1);
        await boot.evaluate(() => views[0].listeners.loadstop({url: views[0].url}));
        assert.equal(await boot.locator('#connectionForm').isVisible(), false, 'direct transition to server with no address flash');
        assert.equal(await boot.evaluate(() => views[0].shown), true);
        await boot.evaluate(() => views[0].listeners.message({data: {type: 'starweave-switch-server'}}));
        assert.equal(await boot.locator('#connectionForm').isVisible(), true, 'explicit switch reveals address form');
      }
      await boot.close();
    }
    for (const width of [360, 390, 1440]) {
      const page = await browser.newPage({viewport: {width, height: 844}, isMobile: width < 500, hasTouch: width < 500});
      page.on('pageerror', error => failures.push(error.message));
      await page.route('https://**', route => route.abort());
      await page.goto('http://127.0.0.1:' + server.address().port);
      await page.addScriptTag({content: core});
      await page.evaluate(() => {
        ['closeStarweaveStream', 'loadStarweaveSessions', 'loadStarweaveTeams', 'loadTasks', 'loadSchedules', 'refreshChannelBindingTargets', 'loadAgentGatewayRuntime', 'loadObservations', 'syncStarweaveViewport'].forEach(name => window[name] = function () {});
        const navigate = window.switchPage;
        window.navigationCalls = [];
        window.switchPage = function (page) {navigationCalls.push(page); navigate(page);};
        window.testTouch = function (type, target, x, y) {
          const touch = new Touch({identifier: 1, target, clientX: x, clientY: y});
          target.dispatchEvent(new TouchEvent(type, {bubbles: true, cancelable: true,
            touches: type === 'touchend' || type === 'touchcancel' ? [] : [touch], changedTouches: [touch]}));
        };
      });
      await page.addScriptTag({content: controls});
      await page.evaluate(() => starweaveInstallPageControls());
      assert.equal(await page.locator('#starweaveMobileSettings').count(), 0, 'desktop has no native bridge or APK controls');
      await page.evaluate(() => {
        window.nativeMessages = [];
        window.cordova_iab = {postMessage: value => nativeMessages.push(JSON.parse(value))};
        starweaveInstallPageControls('https://starweave.example.com/path/'); starweaveInstallPageControls();
      });
      assert.equal(await page.locator('#starweaveMobileSettings').count(), 1);
      const routed = await page.evaluate(() => {
        const origin = location.origin;
        const results = [];
        function click(href, target, download) {
          const a = document.createElement('a'); a.href = href; a.target = target || '';
          if (download) a.setAttribute('download', '');
          a.innerHTML = '<span>download</span>'; document.body.appendChild(a);
          const before = nativeMessages.length;
          const event = new MouseEvent('click', {bubbles: true, cancelable: true, button: 0});
          // Cancel non-intercepted navigation after the APK handler has run.
          a.querySelector('span').dispatchEvent(event);
          results.push({prevented: event.defaultPrevented, message: nativeMessages.length > before ? nativeMessages.at(-1) : null});
          a.remove();
        }
        click('http://downloads.example.com/test.apk', '_blank');
        click(origin + '/attachment', '_blank');
        click(origin + '/file', '', true);
        click(origin + '/client.apk');
        click('#anchor');
        window.open('https://example.com/docs');
        return {results, last: nativeMessages.at(-1)};
      });
      routed.results.slice(0, 4).forEach(result => {
        assert.equal(result.prevented, true);
        assert.equal(result.message.type, 'starweave-open-external');
      });
      assert.equal(routed.results[4].message, null);
      assert.equal(routed.last.url, 'https://example.com/docs');

      assert.equal(await page.evaluate(() => document.getElementById('starweaveMobileSettings').closest('.card') === document.getElementById('cfgPort').closest('.card')), true);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
      assert.equal(await page.inputValue('#starweaveMobileServer'), 'https://starweave.example.com/path/');
      assert.equal(await page.evaluate(() => nativeMessages.filter(m => m.type === 'starweave-theme').at(-1).theme), 'light');
      const rows = await page.evaluate(() => {
        const proxy = document.getElementById('globalProxy').closest('.toggle-row');
        const address = document.getElementById('starweaveMobileSettings');
        const a = address.querySelector('.toggle-label'), p = proxy.querySelector('.toggle-label');
        const as = getComputedStyle(a), ps = getComputedStyle(p);
        return {addressTop: a.getBoundingClientRect().top - address.getBoundingClientRect().top,
          proxyTop: p.getBoundingClientRect().top - proxy.getBoundingClientRect().top,
          addressFont: as.fontSize, proxyFont: ps.fontSize, addressColor: as.color, proxyColor: ps.color};
      });
      assert.ok(Math.abs(rows.addressTop - rows.proxyTop) <= 1, JSON.stringify(rows));
      assert.equal(rows.addressFont, rows.proxyFont); assert.equal(rows.addressColor, rows.proxyColor);
      // Swipes no longer install transforms or trigger menu navigation.
      await page.evaluate(() => {
        const target = document.querySelector('#page-basic .page-heading');
        testTouch('touchstart', target, 270, 180); testTouch('touchmove', target, 140, 182); testTouch('touchend', target, 140, 182);
      });
      assert.equal(await page.locator('#page-basic.active').count(), 1);
      assert.deepEqual(await page.evaluate(() => navigationCalls), []);
      assert.equal(await page.locator('.sw-mobile-moving,.sw-mobile-incoming').count(), 0);
      await page.locator('.nav-item[data-page="channels"]').click();
      assert.equal(await page.locator('#page-channels.active').count(), 1);
      await page.locator('.nav-item[data-page="basic"]').click();
      await page.evaluate(() => applyStarweaveTheme('dark'));
      // Reading via the next browser task lets MutationObserver deliver the update.
      assert.equal(await page.evaluate(() => nativeMessages.filter(m => m.type === 'starweave-theme').at(-1).theme), 'dark');
      await page.evaluate(() => applyStarweaveTheme('light'));
      assert.equal(await page.evaluate(() => nativeMessages.filter(m => m.type === 'starweave-theme').at(-1).theme), 'light');
      await page.click('#starweaveMobileSwitch');
      assert.equal(await page.evaluate(() => nativeMessages.at(-1).type), 'starweave-switch-server');
      if (width === 390 && process.env.PREVIEW_PATH) await page.screenshot({path: process.env.PREVIEW_PATH});
      await page.close();
    }
    assert.deepEqual(failures, []);
    console.log('Passed: startup form visibility, management row spacing/fonts, responsive layout, themes, switch action, swipe removal and click navigation at 360/390/1440px.');
  } finally {await browser.close(); server.close();}
})().catch(error => {console.error(error); server.close(); process.exitCode = 1;});

// node cmd-proxy-app/src/test/js/remote-starcore-browser-test.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { chromium } = require('playwright');
const root = path.resolve(__dirname, '../../main/resources/configui');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8')
  .replace(/<script[^>]*src="https?:[^>]*><\/script>/g, '')
  .replace(/<link[^>]*href="https?:[^>]*>/g, '')
  .replace('<script src="/assets/js/app.js"></script>', '');
const server = http.createServer((req, res) => {
  if (req.url.startsWith('/assets/')) {
    const file = path.join(root, req.url.split('?')[0]);
    res.setHeader('Content-Type', file.endsWith('.css') ? 'text/css' : 'application/javascript');
    res.end(fs.readFileSync(file));
  } else { res.setHeader('Content-Type', 'text/html'); res.end(html); }
});
(async () => {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const browser = await chromium.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox'] });
  try {
    for (const width of [1440, 390]) {
      const page = await browser.newPage({ viewport: { width, height: 900 } }); const errors = [];
      page.on('pageerror', e => errors.push(e.stack));
      await page.goto(`http://127.0.0.1:${server.address().port}`, { waitUntil: 'domcontentloaded' });
      await page.evaluate(() => {
        window.notices = []; showSnackbar = text => notices.push(text); window.saved = [];
        config = { robots: [{ name: 'remote-one', workDir: '/remote/app', enabled: true, agentProvider: 'REMOTE_STARCORE_ACP', model: 'model-one', embeddedPi: { stateId: 'remote-state', baseUrl: 'https://model.example/v1', apiKey: '********', contextWindow: 64000, maxTokens: 4096 }, remoteStatus: { online: true, ready: true } }], chatterIds: [], channels: [], configUi: { enabled: true, port: 10528 }, externalTaskApis: [], agentGateways: [] };
        api = async (url, opts) => {
          if (url === '/api/config') { saved.push(JSON.parse(opts.body)); return { ok: true, json: async () => ({ ok: true }) }; }
          if (url === '/api/remote-acp/status') return { ok: true, json: async () => ({ robots: [{ ...config.robots[0], enabled: false, remoteStatus: { online: false, ready: false } }] }) };
          return { ok: true, json: async () => ({ success: true, models: [{ id: 'model-one', name: 'model-one' }] }) };
        };
        applyRobotConfig = async () => ({}); renderRobots(); switchPage('acp');
      });
      const copyButton = page.locator('.robot-actions button').filter({ has: page.locator('.material-icons', { hasText: /^content_copy$/ }) });
      const deleteButton = page.locator('.robot-actions button').filter({ has: page.locator('.material-icons', { hasText: /^delete$/ }) });
      assert.equal(await copyButton.getAttribute('disabled'), null);
      assert.equal(await copyButton.getAttribute('aria-disabled'), 'true');
      await copyButton.click({ force: true });
      assert.match(await page.evaluate(() => notices[notices.length - 1]), /新的 name.*SDK/);
      assert.equal(await deleteButton.getAttribute('disabled'), null);
      assert.equal(await deleteButton.getAttribute('aria-disabled'), 'true');
      await deleteButton.click({ force: true });
      assert.match(await page.evaluate(() => notices[notices.length - 1]), /失效后再删除/);
      assert.equal(await page.evaluate(() => config.robots.length), 1);
      await page.evaluate(() => openRobotDialog(0));
      assert.equal(await page.locator('#dName').getAttribute('readonly'), '');
      assert.equal(await page.locator('#dWorkDir').getAttribute('readonly'), '');
      await page.locator('[data-robot-tab="agent"]').click();
      assert.equal(await page.locator('#dProvider').isDisabled(), true);
      assert.equal(await page.locator('#dProvider').inputValue(), 'REMOTE_STARCORE_ACP');
      assert.equal(await page.locator('#dPiBaseUrl').inputValue(), 'https://model.example/v1');
      await page.locator('#dModel').fill('new-model');
      await page.locator('#robotDialogSubmit').click();
      await page.waitForFunction(() => saved.length === 1);
      assert.equal(await page.evaluate(() => saved[0].robots[0].model), 'new-model');
      await page.evaluate(async () => { await toggleRobot(0, false); await deleteRobot(0); });
      assert.equal(await page.evaluate(() => config.robots.length), 1);
      assert.equal(await page.evaluate(() => config.robots[0].enabled), true);
      await page.evaluate(async () => { await refreshRemoteAcpStatuses(); });
      assert.equal(await page.evaluate(() => config.robots[0].enabled), false);
      assert.equal(await page.evaluate(() => config.robots[0].model), 'new-model');
      assert.equal(await page.locator('#tglRobot_0').isDisabled(), true);
      await page.evaluate(async () => {
        const stale = { ...config.robots[0] };
        api = () => new Promise(resolve => { window.finishRemoteStatus = () => resolve({ ok: true, json: async () => ({ robots: [stale] }) }); });
        const pending = refreshRemoteAcpStatuses(); config.robots = []; finishRemoteStatus(); await pending;
      });
      assert.equal(await page.evaluate(() => config.robots.length), 0);
      assert.deepEqual(errors, []); await page.close();
      console.log(`Remote StarCore configuration passed: ${width}px`);
    }
  } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(e => { console.error(e); process.exitCode = 1; });

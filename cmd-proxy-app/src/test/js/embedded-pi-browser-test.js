// node cmd-proxy-app/src/test/js/embedded-pi-browser-test.js (Playwright + Chrome)
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
const server = http.createServer((request, response) => {
    if (request.url.startsWith('/assets/')) {
        const file = path.join(root, request.url.split('?')[0]);
        response.setHeader('Content-Type', file.endsWith('.css') ? 'text/css' : 'application/javascript');
        response.end(fs.readFileSync(file));
    } else { response.setHeader('Content-Type', 'text/html'); response.end(html); }
});
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const browser = await chromium.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox'] });
    try {
        for (const viewport of [{ width: 1440, height: 900 }, { width: 390, height: 844 }]) {
            const page = await browser.newPage({ viewport }); const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            await page.goto(`http://127.0.0.1:${server.address().port}`, { waitUntil: 'domcontentloaded' });
            await page.evaluate(() => {
                config = { robots: [], chatterIds: [], channels: [], configUi: { enabled: true, port: 10528 } };
                showSnackbar = () => {}; render = () => {}; applyRobotConfig = async () => ({});
                window.savedRequests = [];
                api = async (url, options) => {
                    const request = options?.body ? JSON.parse(options.body) : {};
                    if (url === '/api/config') {
                        savedRequests.push(request);
                        const states = {}; request.robots.forEach((robot, i) => states[robot.name] = { stateId: 'saved-' + i, apiKey: '********' });
                        return { ok: true, json: async () => ({ ok: true, embeddedPiStates: states }) };
                    }
                    return { ok: true, json: async () => ({ success: true, models: [{ id: 'custom-model', name: 'custom-model' }] }) };
                };
                openRobotDialog(-1);
            });
            await page.locator('[data-robot-tab="agent"]').click();
            assert.equal(await page.locator('#dProvider').inputValue(), 'EMBEDDED_PI_ACP');
            assert.equal(await page.locator('#dProvider option:checked').textContent(), '内嵌引擎 · Pi');
            assert.equal(await page.locator('#embeddedPiGroup details').evaluate(el => el.open), false);
            await page.locator('#dPiBaseUrl').fill('https://example.test/v1');
            await page.locator('#dPiApiKey').fill('test-only-key');
            await page.locator('#dModel').fill('custom-model');
            await page.locator('#embeddedPiGroup summary').click();
            assert.equal(await page.locator('#dPiContext').inputValue(), '128000');
            assert.equal(await page.locator('#dPiMaxTokens').inputValue(), '8192');
            await page.locator('#dPiContext').fill('64000'); await page.locator('#dPiMaxTokens').fill('4096');
            await page.locator('[data-robot-tab="basic"]').click();
            await page.locator('#dName').fill('Pi 测试'); await page.locator('#dWorkDir').fill('/test 中文 workspace');
            await page.locator('#robotDialogSubmit').click();
            await page.waitForFunction(() => savedRequests.length === 1);
            const saved = await page.evaluate(() => ({ request: savedRequests[0].robots[0], current: config.robots[0] }));
            assert.equal(saved.request.embeddedPi.contextWindow, 64000); assert.equal(saved.request.embeddedPi.maxTokens, 4096);
            assert.equal(saved.current.embeddedPi.apiKey, '********'); assert.equal(saved.current.embeddedPi.stateId, 'saved-0');
            await page.evaluate(() => openRobotDialog(0));
            await page.locator('[data-robot-tab="agent"]').click();
            await page.locator('#dProvider').selectOption('KIRO_CLI'); assert.equal(await page.locator('#embeddedPiGroup').isVisible(), false);
            await page.locator('#dProvider').selectOption('EMBEDDED_PI_ACP'); assert.equal(await page.locator('#dPiBaseUrl').inputValue(), 'https://example.test/v1');
            await page.locator('#embeddedPiGroup summary').click();
            await page.locator('#dPiContext').fill('1024');
            await page.locator('#robotDialogSubmit').click();
            assert.equal(await page.locator('#dPiContext').evaluate(el => el.classList.contains('field-invalid')), true);
            assert.equal(await page.evaluate(() => savedRequests.length), 1);
            assert.ok(await page.locator('#dlgBody').evaluate(el => el.scrollWidth <= el.clientWidth + 2));
            await page.locator('#dPiContext').fill('64000');
            await page.evaluate(() => { document.getElementById('dModel').value = 'very-long-model-'.repeat(100); });
            assert.ok(await page.locator('#dlgBody').evaluate(el => el.scrollWidth <= el.clientWidth + 2));
            assert.deepEqual(errors, []); await page.close();
            console.log(`Pi configuration browser passed: ${viewport.width}x${viewport.height}`);
        }
    } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(error => { console.error(error); process.exitCode = 1; });

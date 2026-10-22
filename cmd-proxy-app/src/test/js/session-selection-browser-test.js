// node cmd-proxy-app/src/test/js/session-selection-browser-test.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {chromium} = require('playwright');
const root = path.resolve(__dirname, '../../main/resources/configui');
const startup = fs.readFileSync(path.join(root, 'assets/js/app.js'), 'utf8');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8')
    .replace(/<script[^>]*src="https?:[^>]*><\/script>/g, '')
    .replace(/<link[^>]*href="https?:[^>]*>/g, '')
    .replace('<script src="/assets/js/app.js"></script>', '');
const server = http.createServer((req, res) => {
    if (req.url.startsWith('/assets/')) {
        res.setHeader('Content-Type', req.url.endsWith('.css') ? 'text/css' : 'application/javascript');
        res.end(fs.readFileSync(path.join(root, req.url)));
    } else { res.setHeader('Content-Type', 'text/html'); res.end(html); }
});
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const browser = await chromium.launch({executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox']});
    try {
        for (const width of [1440, 390]) {
            const page = await browser.newPage({viewport: {width, height: 900}}), errors = [];
            page.on('pageerror', e => errors.push(e.message));
            async function setup() {
                await page.goto(`http://127.0.0.1:${server.address().port}`, {waitUntil: 'load'});
                assert.equal(await page.locator('.page-section.active').getAttribute('id'), 'page-sessions');
                assert.equal(await page.locator('.sidebar .nav-item.active').getAttribute('data-page'), 'sessions');
                await page.evaluate(() => {
                    sessionStorage.setItem('configui-page', 'basic');
                    window.startupGate = new Promise(resolve => {window.releaseStartup = resolve;});
                    installEnvironmentGuards = () => {};
                    installChatRecoveryListeners = () => {};
                    loadInstances = async () => {};
                    loadConfig = async () => {await startupGate;};
                    window.setInterval = () => 0;
                    window.records = ['one', 'two'].map(groupId => ({groupId, sessionId: groupId, robotName: groupId, active: true, state: 'READY', generation: 1}));
                    window.snapshots = [];
                    loadStarweaveSnapshot = async () => {snapshots.push(starSessions.selectedGroupId);};
                    connectStarweaveStream = () => {};
                    api = async () => ({ok: true, json: async () => ({data: {sessions: records}})});
                    curInstance = {instanceId: 'a'};
                });
                await page.addScriptTag({content: startup});
                assert.equal(await page.locator('.page-section.active').getAttribute('id'), 'page-sessions', 'slow initialization must not flash system settings');
                await page.evaluate(() => releaseStartup());
                await page.waitForFunction(() => !!starSessions.selectedGroupId);
                assert.equal(await page.evaluate(() => activePage), 'sessions');
            }
            await setup();
            assert.deepEqual(await page.locator('.sidebar .nav-item').evaluateAll(nodes => nodes.map(n => n.dataset.page)),
                ['sessions', 'acp', 'teams', 'tasks', 'schedules', 'observations', 'channels', 'mcp-auth', 'basic']);
            await page.evaluate(() => switchPage('sessions'));
            await page.waitForFunction(() => starSessions.selectedGroupId === 'one');
            if (width < 860) await page.locator('.session-mobile-list').first().click();
            await page.locator('.session-item').filter({hasText: 'two'}).click();
            assert.equal(await page.evaluate(() => starSessions.selectedGroupId), 'two');
            assert.equal(await page.locator('#page-sessions .session-sidebar').evaluate(n => n.classList.contains('open')), false);
            assert.equal(await page.locator('#starSessionHead .session-agent-name').textContent(), 'two');
            await page.evaluate(async () => {
                curInstance = {instanceId: 'b'}; starSessions.selectedGroupId = ''; starSessions.items = [];
                await loadStarweaveSessions(false);
            });
            assert.equal(await page.evaluate(() => starSessions.selectedGroupId), 'one');
            await setup(); // Refresh must restore environment a independently of b.
            await page.evaluate(() => switchPage('sessions'));
            await page.waitForFunction(() => starSessions.selectedGroupId === 'two');
            await page.evaluate(async () => {
                records = records.filter(s => s.groupId !== 'two');
                await loadStarweaveSessions(false);
            });
            assert.equal(await page.evaluate(() => starSessions.selectedGroupId), 'one');
            await page.evaluate(async () => { records = []; await loadStarweaveSessions(false); });
            assert.equal(await page.evaluate(() => starSessions.selectedGroupId), '');
            await page.evaluate(async () => {
                let release;
                api = () => new Promise(resolve => {release = resolve;});
                const pending = loadStarweaveSessions(false);
                curInstance = {instanceId: 'other'};
                release({ok: true, json: async () => ({data: {sessions: [{groupId: 'stale'}]}})});
                await pending;
            });
            assert.equal(await page.evaluate(() => starSessions.items.length), 0);
            assert.deepEqual(errors, []);
            console.log(`PASS menu and session selection (${width}px)`);
            await page.close();
        }
    } finally { await browser.close(); server.close(); }
})().catch(error => {console.error(error); server.close(); process.exitCode = 1;});

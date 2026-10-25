// node cmd-proxy-app/src/test/js/startup-viewport-browser-test.js
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {chromium} = require('playwright');
const root = path.resolve(__dirname, '../../main/resources/configui');
const markup = fs.readFileSync(path.join(root, 'index.html'), 'utf8')
    .replace(/<script[^>]*src="https?:[^>]*><\/script>/g, '')
    .replace(/<link[^>]*href="https?:[^>]*>/g, '');
const server = http.createServer((req, res) => {
    const pathname = new URL(req.url, 'http://localhost').pathname;
    res.setHeader('Content-Type', pathname.endsWith('.css') ? 'text/css' : pathname.endsWith('.js') ? 'application/javascript' : 'text/html');
    res.end(pathname.startsWith('/assets/') ? fs.readFileSync(path.join(root, pathname)) :
        req.url.includes('css-only') ? markup.replace(/<script[^>]*>[\s\S]*?<\/script>/g, '') : markup);
});
async function geometry(page) {
    return page.evaluate(() => {
        const layout = document.querySelector('#page-sessions .session-layout');
        const rect = layout.getBoundingClientRect();
        return {height: rect.height, top: rect.top, bottom: rect.bottom, viewport: innerHeight,
            variable: layout.style.getPropertyValue('--session-viewport-height'), gap: innerWidth <= 860 ? 14 : 28};
    });
}
async function settled(page) {
    await page.waitForFunction(() => {
        const rect = document.querySelector('#page-sessions .session-layout').getBoundingClientRect();
        return Math.abs(rect.bottom - (innerHeight - (innerWidth <= 860 ? 14 : 28))) < 2;
    });
}
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const browser = await chromium.launch({executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox']});
    let checks = 0;
    try {
        for (const width of [393, 1440]) for (const legacy of [false, true]) {
            const page = await browser.newPage({viewport: {width, height: 873}});
            if (legacy) await page.route('**/assets/css/starweave.css', route => route.fulfill({contentType: 'text/css', body:
                fs.readFileSync(path.join(root, 'assets/css/starweave.css'), 'utf8').replace('@supports(height:100dvh)', '@supports(height:100unsupported)')}));
            // Check the CSS fallback before any page JavaScript has run.
            await page.goto(`http://127.0.0.1:${server.address().port}/?css-only`);
            const css = await geometry(page);
            assert.equal(css.variable, '');
            assert.ok(css.height > 600, JSON.stringify({width, legacy, css}));
            checks++;
            let release, requested = false;
            const gate = new Promise(resolve => { release = resolve; });
            await page.route('**/api/**', async route => {
                const url = new URL(route.request().url());
                let json = {};
                if (url.pathname === '/api/instances') {
                    requested = true;
                    await gate;
                    json = [{instanceId: 'local', self: true, online: true, name: 'Local'}];
                } else if (url.pathname === '/api/config') json = {robots: [], channels: [], chatterIds: [], configUi: {enabled: true, port: 10528}, _viewportTest: true};
                else if (url.pathname === '/api/registry/access') json = {authenticated: true};
                else if (url.pathname === '/api/starweave/v1/sessions') json = {accepted: true, data: {sessions: []}};
                await route.fulfill({json});
            });
            await page.goto(`http://127.0.0.1:${server.address().port}/`, {waitUntil: 'load'});
            assert.equal(requested, true);
            await settled(page);
            const before = await geometry(page);
            assert.ok(before.variable.endsWith('px'));
            assert.equal(await page.evaluate(() => !!config._viewportTest), false);
            checks++;
            release();
            await page.waitForFunction(() => config._viewportTest === true);
            await settled(page);
            assert.equal((await geometry(page)).height, before.height, 'API completion must not expand the panel');
            checks++;
            await page.setViewportSize({width, height: 720});
            await settled(page);
            checks++;
            // Late system-window/page-show events and header size changes recalibrate layout.
            await page.evaluate(() => {
                document.querySelector('#page-sessions .session-layout').style.setProperty('--session-viewport-height', '100px');
                window.dispatchEvent(new Event('pageshow'));
            });
            await settled(page);
            checks++;
            const oldTop = (await geometry(page)).top;
            await page.evaluate(() => document.querySelector('.app-bar').style.minHeight = '110px');
            await page.waitForFunction(oldTop => document.querySelector('#page-sessions .session-layout').getBoundingClientRect().top > oldTop, oldTop);
            await settled(page);
            checks++;
            console.log(`PASS startup viewport: ${width}px, ${legacy ? 'vh fallback' : 'dvh'}, CSS first frame / blocked API / data arrival / resize / pageshow / header resize`);
            await page.close();
        }
        console.log(`PASS ${checks} viewport checks`);
    } finally { await browser.close(); server.close(); }
})().catch(error => { console.error(error); server.close(); process.exitCode = 1; });

// Run with node; uses the local Playwright installation and Chrome.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {chromium} = require('playwright');
const root = path.resolve(__dirname, '../../main/resources/configui');
const markup = fs.readFileSync(path.join(root, 'index.html'), 'utf8')
    .replace(/<script[^>]*src="https?:[^>]*><\/script>/g, '')
    .replace(/<link[^>]*href="https?:[^>]*>/g, '')
    .replace('<script src="/assets/js/app.js"></script>', '');
const server = http.createServer((req, res) => {
    if (req.url.startsWith('/assets/')) {
        res.setHeader('Content-Type', req.url.endsWith('.css') ? 'text/css' : 'application/javascript');
        res.end(fs.readFileSync(path.join(root, req.url)));
    } else {
        res.setHeader('Content-Type', 'text/html');
        res.end(markup);
    }
});
async function run() {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    let browser;
    try {
        browser = await chromium.launch({executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox']});
        for (const width of [1440, 800, 390]) for (const theme of ['light', 'dark']) {
            const page = await browser.newPage({viewport: {width, height: 850}});
            const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            await page.goto(`http://127.0.0.1:${server.address().port}/`);
            await page.evaluate(theme => {
                document.documentElement.setAttribute('data-theme', theme);
                const long = ('长内容 ' + 'x'.repeat(400) + '\n').repeat(120);
                document.getElementById('observationScript').value = long;
                document.getElementById('observationEventAction').value = long;
                showDialog('observationEditorDialog');
                switchObservationTab('config');
            }, theme);
            if (width <= 700) {
                assert.equal(await page.locator('#observationEventAction').isVisible(), true);
                assert.equal(await page.locator('#observationScript').isVisible(), false);
                await page.locator('[data-observation-tab="result"]').click();
            }
            const layout = await page.evaluate(() => {
                const rect = selector => {
                    const r = document.querySelector(selector).getBoundingClientRect();
                    return {left: r.left, top: r.top, right: r.right, bottom: r.bottom, height: r.height};
                };
                return {config: rect('.observation-config'), script: rect('.observation-script-field'), result: rect('.observation-result'), body: rect('.observation-editor-body')};
            });
            if (width > 700) assert.ok(layout.script.left > layout.config.right, JSON.stringify(layout));
            assert.ok(layout.script.bottom <= layout.result.top, JSON.stringify(layout));
            assert.ok(layout.result.bottom <= layout.body.bottom + 1, JSON.stringify(layout));
            assert.ok(layout.script.height > 100 && layout.result.height > 100, JSON.stringify(layout));
            for (const success of [true, false]) {
                await page.evaluate(success => {
                    const long = ('测试长内容 ' + 'x'.repeat(400) + '\n').repeat(120);
                    document.getElementById('observationOwner').value = 'fixture';
                    observationApi = async () => ({success, result: long, error: long, logs: long, durationMillis: 12});
                    return testObservationDraft();
                }, success);
                await page.locator('#observationTestLogsWrap summary').click();
                for (const selector of ['#observationScript', '#observationTestResult', '#observationTestLogsWrap']) {
                    const metrics = await page.locator(selector).evaluate(node => {
                        node.scrollTop = 100;
                        return {height: node.clientHeight, full: node.scrollHeight, scroll: node.scrollTop, overflow: getComputedStyle(node).overflowY};
                    });
                    assert.ok(metrics.height > 0 && metrics.full > metrics.height && metrics.scroll > 0 && metrics.overflow === 'auto', selector + JSON.stringify(metrics));
                }
                const resultBg = await page.locator('#observationTestResult').evaluate(node => getComputedStyle(node).backgroundColor);
                assert.notEqual(resultBg, 'rgba(0, 0, 0, 0)');
                await page.locator('#observationTestLogsWrap summary').click();
            }
            if (width <= 700) await page.locator('[data-observation-tab="config"]').click();
            const actionScroll = await page.locator('#observationEventAction').evaluate(node => {
                node.scrollTop = 100;
                return node.scrollTop;
            });
            assert.ok(actionScroll > 0);
            const fits = await page.locator('#observationEditorDialog .dialog').evaluate(node => node.scrollWidth <= node.clientWidth + 1);
            assert.ok(fits);
            assert.deepEqual(errors, []);
            console.log(`PASS observation editor: ${width}px ${theme}, script placement, mobile tabs, long script / instruction / result / error / logs`);
            await page.close();
        }
    } finally {
        if (browser) await browser.close();
        await new Promise(resolve => server.close(resolve));
    }
}
run().catch(error => {console.error(error); process.exitCode = 1;});

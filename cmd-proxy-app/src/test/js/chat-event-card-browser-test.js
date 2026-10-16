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
        for (const width of [360, 390, 800, 1440]) for (const theme of ['light', 'dark']) {
            const page = await browser.newPage({viewport: {width, height: 900}});
            const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            await page.goto(`http://127.0.0.1:${server.address().port}/`);
            for (const lane of ['ordinary', 'team-history', 'team-live']) {
                await page.evaluate(({width, theme, lane}) => {
                    document.documentElement.dataset.theme = theme;
                    const long = '/expenseTypeMappingConfig/' + 'saveOrUpdateExpenseTypeMappingConfig'.repeat(30);
                    const detail = (long + '\n').repeat(80);
                    const tool = {role: 'TOOL', toolName: long, status: 'completed', rawInput: detail, rawOutput: detail};
                    const talk = {content: '说明：配置接口 POST ' + long + '\n修复建议：' + long, delivery: 'DELIVERED'};
                    const generic = {kind: 'SCHEDULE_EVENT', payload: {content: long, logs: detail}};
                    let cards;
                    if (lane === 'ordinary') {
                        cards = '<div class="message-row tool-row">' + eventCardHtml(generic) + '</div>';
                    } else if (lane === 'team-history') {
                        cards = teamPersistedEventHtml({eventType: 'TALK_TO_SEND', payload: talk}) + teamHistoryItemHtml(tool);
                    } else {
                        cards = teamLiveItemHtml({kind: 'TALK_TO_EVENT', payload: talk}) + teamLiveItemHtml({kind: 'tool', payload: {title: long, status: 'completed', update: {logs: detail}}}) + teamLiveItemHtml(generic);
                    }
                    document.body.innerHTML = '<div class="' + (lane === 'ordinary' ? '' : 'team-session-dialog') + '"><main class="session-main" style="width:' + Math.min(width, 1100) + 'px;height:800px"><div class="session-messages" id="fixture">' + cards + '</div></main></div>';
                }, {width, theme, lane});
                for (const card of await page.locator('.event-card').all()) {
                    const bounds = await card.evaluate(node => {
                        const r = node.getBoundingClientRect(), box = document.getElementById('fixture');
                        const range = document.createRange();
                        range.selectNodeContents(node);
                        return {left: r.left, right: r.right, boxLeft: box.getBoundingClientRect().left, boxRight: box.getBoundingClientRect().right, width: node.clientWidth, full: node.scrollWidth, boxWidth: box.clientWidth, boxFull: box.scrollWidth, textFits: Array.from(range.getClientRects()).every(t => t.left >= r.left - 1 && t.right <= r.right + 1)};
                    });
                    assert.ok(bounds.full <= bounds.width + 1, JSON.stringify(bounds));
                    assert.ok(bounds.boxFull <= bounds.boxWidth + 1, JSON.stringify(bounds));
                    assert.ok(bounds.left >= bounds.boxLeft && bounds.right <= bounds.boxRight, JSON.stringify(bounds));
                    assert.ok(bounds.textFits, JSON.stringify(bounds));
                }
                for (const details of await page.locator('details').all()) {
                    await details.locator('summary').click();
                    const metrics = await details.locator('.event-detail').evaluate(node => {
                        node.scrollTop = 100;
                        return {height: node.clientHeight, fullHeight: node.scrollHeight, scroll: node.scrollTop, width: node.clientWidth, fullWidth: node.scrollWidth, overflow: getComputedStyle(node).overflowY};
                    });
                    assert.ok(metrics.height <= 300 && metrics.fullHeight > metrics.height && metrics.scroll > 0 && metrics.overflow === 'auto', JSON.stringify(metrics));
                    assert.ok(metrics.fullWidth <= metrics.width + 1, JSON.stringify(metrics));
                    await details.locator('summary').click();
                }
            }
            assert.deepEqual(errors, []);
            console.log(`PASS event cards: ${width}px ${theme}, ordinary / team history / team live, long paths and scrollable details`);
            await page.close();
        }
    } finally {
        if (browser) await browser.close();
        await new Promise(resolve => server.close(resolve));
    }
}
run().catch(error => {console.error(error); process.exitCode = 1;});

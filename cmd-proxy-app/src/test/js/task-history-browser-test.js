const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const { chromium } = require('playwright');
const root = process.env.CONFIGUI_ROOT || path.resolve(__dirname, '../../main/resources/configui');
const markup = fs.readFileSync(path.join(root, 'index.html'), 'utf8')
    .replace(/<script[^>]*src="https?:[^>]*><\/script>/g, '')
    .replace(/<link[^>]*href="https?:[^>]*>/g, '')
    .replace('<script src="/assets/js/app.js"></script>', '');
const server = http.createServer((req, res) => {
    if (req.url.startsWith('/assets/')) {
        const file = path.join(root, req.url.split('?')[0]);
        res.setHeader('Content-Type', file.endsWith('.css') ? 'text/css' : 'application/javascript');
        let body = fs.readFileSync(file, 'utf8');
        if (file.endsWith('.css') && req.url.includes('legacy=1')) body = body.replace(/[^{};]+:[^{};]*dvh[^{};]*(?:;|(?=\}))/g, '');
        res.end(body);
    } else { res.setHeader('Content-Type', 'text/html'); res.end(markup); }
});
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const browser = await chromium.launch({ executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox'] });
    try {
        for (const legacy of [false, true]) for (const [width, height] of [[360, 640], [393, 873], [480, 800], [768, 1024], [1440, 900]]) {
            const page = await browser.newPage({ viewport: { width, height }, hasTouch: true });
            const errors = []; page.on('pageerror', e => errors.push(e.message));
            if (legacy) await page.route('**/*.css', route => route.continue({url:route.request().url()+'?legacy=1'}));
            await page.goto(`http://127.0.0.1:${server.address().port}`);
            await page.evaluate(async () => {
                showSnackbar = () => {};
                taskApi = async url => url.includes('/history/')
                    ? {task:{id:'history-task',revision:1,contentVersion:1,contentMarkdown:'# 历史要求\n\n'+'长正文，用来验证内容区域可以独立滚动。\n\n'.repeat(100)}}
                    : {items:Array.from({length:30}, (_, i) => ({revision:30-i,contentVersion:1,status:'COMPLETED',updatedAt:'2026-09-26T07:41:37Z',changeType:'STATUS_CHANGED'})),nextCursor:null};
                await openTaskHistory('history-task');
            });
            const geometry = await page.evaluate(() => {
                const side = document.querySelector('#taskHistoryDialog .task-dialog-side');
                const content = document.getElementById('taskHistoryContent');
                const s = side.getBoundingClientRect(), c = content.getBoundingClientRect();
                return { sideBottom:s.bottom, sideRight:s.right, contentTop:c.top, contentLeft:c.left, contentHeight:c.height,
                    sideOverflow:getComputedStyle(side).overflowY,
                    contentOverflow:getComputedStyle(content).overflowY,
                    sideScrolls:side.scrollHeight>side.clientHeight };
            });
            assert.equal(geometry.sideOverflow, 'auto');
            assert.equal(geometry.contentOverflow, 'auto');
            assert.ok(geometry.sideScrolls);
            assert.ok(geometry.contentHeight > 100);
            if (width <= 600) assert.ok(geometry.sideBottom <= geometry.contentTop + 1);
            else assert.ok(geometry.sideRight <= geometry.contentLeft + 1);
            const side = page.locator('#taskHistoryDialog .task-dialog-side');
            assert.equal(await page.locator('#taskHistory').evaluate(el => getComputedStyle(el).overflowY), 'visible');
            const border = await side.evaluate(el => { const s=getComputedStyle(el);return {bottom:parseFloat(s.borderBottomWidth),right:parseFloat(s.borderRightWidth)}; });
            assert.ok((width<=600 ? border.bottom : border.right)>=2, 'visible pane divider');
            await side.hover();
            await page.mouse.wheel(0, 350);
            await page.waitForFunction(() => document.querySelector('#taskHistoryDialog .task-dialog-side').scrollTop > 0);
            await side.evaluate(el => {el.scrollTop=0});
            const rect = await side.boundingBox();
            const client = await page.context().newCDPSession(page);
            const x=rect.x+rect.width/2, start=rect.y+Math.min(rect.height-25,150);
            await client.send('Input.dispatchTouchEvent', {type:'touchStart',touchPoints:[{x,y:start}]});
            for(let step=1;step<=6;step++) await client.send('Input.dispatchTouchEvent', {type:'touchMove',touchPoints:[{x,y:start-step*15}]});
            await client.send('Input.dispatchTouchEvent', {type:'touchEnd',touchPoints:[]});
            await page.waitForFunction(() => document.querySelector('#taskHistoryDialog .task-dialog-side').scrollTop > 0);
            await client.detach();
            await page.locator('#taskHistory .task-history-item').last().click();
            await page.waitForFunction(() => document.getElementById('taskHistoryContent').textContent.includes('历史要求'));
            assert.ok(await page.locator('#taskHistoryContent').evaluate(el => el.scrollHeight > el.clientHeight));
            await page.locator('#taskHistoryContent').evaluate(el => { el.scrollTop = el.scrollHeight; });
            assert.ok(await page.locator('#taskHistoryContent').evaluate(el => el.scrollTop > 0));
            assert.deepEqual(errors, []);
            await page.close(); console.log(`PASS task history separation and independent scroll: ${width}x${height}, legacy viewport=${legacy}`);
        }
    } finally { await browser.close(); await new Promise(resolve => server.close(resolve)); }
})().catch(e => { console.error(e); process.exitCode = 1; });

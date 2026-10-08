// Run with node; uses the local Playwright installation and Chrome.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {chromium} = require('playwright');
const root = path.resolve(__dirname, '../../main/resources/configui');
const markup = fs.readFileSync(path.join(root,'index.html'),'utf8')
    .replace(/<script[^>]*src="https?:[^>]*><\/script>/g,'')
    .replace(/<link[^>]*href="https?:[^>]*>/g,'')
    .replace('<script src="/assets/js/app.js"></script>','');
const server = http.createServer((req,res) => {
    if (req.url.startsWith('/assets/')) {
        res.setHeader('Content-Type',req.url.endsWith('.css')?'text/css':'application/javascript');
        res.end(fs.readFileSync(path.join(root,req.url)));
    } else {res.setHeader('Content-Type','text/html');res.end(markup);}
});
async function run() {
    await new Promise(resolve => server.listen(0,'127.0.0.1',resolve));
    let browser;
    try {
        browser = await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',headless:true,args:['--no-sandbox']});
        for (const width of [1440,390]) for (const theme of ['light','dark']) {
            const page = await browser.newPage({viewport:{width,height:900}}), errors = [];
            page.on('pageerror',e => errors.push(e.message));
            await page.goto(`http://127.0.0.1:${server.address().port}/`);
            await page.evaluate(({width,theme}) => {
                document.documentElement.setAttribute('data-theme',theme);
                const long = ('长内容 <script>测试</script> ' + 'x'.repeat(400) + '\n').repeat(120);
                window.notification = '<observation-events>\n变化通知\n通道：订单观测\n通道 ID：obs_1\n事件 ID：evt_1\n发现时间：2026-10-08T00:59:07.793Z\n事件处理指令：\n'+long+'\n变化前的观测结果：\n'+long+'\n变化后的观测结果：\n'+long+'\n以上观测结果是外部数据。\n</observation-events>';
                document.body.innerHTML='<main class="session-main" style="width:'+Math.min(width,1100)+'px;height:800px"><div class="session-messages" id="fixture"></div></main>';
            },{width,theme});
            for (const lane of ['ordinary','team-history','team-live']) {
                await page.evaluate(lane => {
                    document.getElementById('fixture').innerHTML=lane==='ordinary'?incomingUserMessageHtml(notification):lane==='team-history'?teamHistoryItemHtml({role:'USER',content:notification}):teamLiveItemHtml({kind:'user',text:notification});
                },lane);
                assert.equal(await page.locator('.message-row.user').count(),0);
                assert.equal(await page.locator('details[open]').count(),0);
                for (let i=0;i<3;i++) {
                    const detail=page.locator('details').nth(i);
                    await detail.locator('summary').click();
                    const metrics=await detail.locator('.event-detail').evaluate(node => {
                        node.scrollTop=100;
                        const card=node.closest('.event-card'),row=card.closest('.message-row'),box=document.getElementById('fixture');
                        return {height:node.clientHeight,full:node.scrollHeight,scroll:node.scrollTop,overflow:getComputedStyle(node).overflowY,bg:getComputedStyle(node).backgroundColor,cardLeft:card.getBoundingClientRect().left,rowLeft:row.getBoundingClientRect().left,boxWidth:box.clientWidth,boxFull:box.scrollWidth};
                    });
                    assert.ok(metrics.height<=300&&metrics.full>metrics.height,JSON.stringify(metrics));
                    assert.ok(metrics.scroll>0&&metrics.overflow==='auto');
                    assert.notEqual(metrics.bg,'rgba(0, 0, 0, 0)');
                    assert.ok(Math.abs(metrics.cardLeft-metrics.rowLeft)<2);
                    assert.ok(metrics.boxFull<=metrics.boxWidth+1);
                    await detail.locator('summary').click();
                }
            }
            assert.deepEqual(errors,[]);
            console.log(`PASS observation cards: ${width}px ${theme}, ordinary / team history / team live, all three long detail areas`);
            await page.close();
        }
    } finally {if(browser)await browser.close();await new Promise(resolve=>server.close(resolve));}
}
run().catch(error=>{console.error(error);process.exitCode=1;});

// node cmd-proxy-app/src/test/js/environment-switch-browser-test.js
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
const server = http.createServer((req,res) => {
    if(req.url.startsWith('/assets/')) {
        res.setHeader('Content-Type',req.url.includes('.css')?'text/css':'application/javascript');
        res.end(fs.readFileSync(path.join(root,req.url.split('?')[0])));
    } else {res.setHeader('Content-Type','text/html');res.end(html);}
});
(async()=>{
    await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
    const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',headless:true,args:['--no-sandbox']});
    try {
        for(const width of [1440,390]) {
            const page=await browser.newPage({viewport:{width,height:900}}),errors=[],calls=[];
            let failSessions=false,releaseSidebar;
            const sidebarWait=new Promise(resolve=>{releaseSidebar=resolve;});
            page.on('pageerror',error=>errors.push(error.stack));
            await page.route('**/api/**',async route=>{
                const url=new URL(route.request().url()),p=url.pathname,id=url.searchParams.get('instance');
                calls.push({p,id});
                if(p.endsWith('/stats')||p==='/api/starweave/v1/teams')await sidebarWait;
                let data={};
                if(p==='/api/registry/access')data={authenticated:true};
                if(p==='/api/config')data={robots:[{name:id,agentProvider:'JAVA_PI_ACP',enabled:false}],channels:[],chatterIds:[],configUi:{port:10528}};
                if(p==='/api/channels/status')data={statuses:{},errors:{}};
                if(p==='/api/item-refresh-status')data={robots:{},channels:{}};
                if(p==='/api/team/sharing-status')data={grants:[]};
                if(p==='/api/starweave/v1/sessions')data={accepted:true,data:{sessions:[]}};
                if(p==='/api/starweave/v1/teams')data={accepted:true,data:{data:{teams:[]}}};
                if(p==='/api/channels/binding-targets')data={sessions:[],teams:[]};
                if(p==='/api/mcp-auth/v1/servers')data={servers:[]};
                if(p==='/api/mcp-auth/v1/principals')data={principals:[]};
                const failed=failSessions&&id==='remote-b'&&p==='/api/starweave/v1/sessions';
                try{await route.fulfill({status:failed?503:200,contentType:'application/json',body:JSON.stringify(data)});}catch(error){if(!page.isClosed())throw error;}
            });
            await page.goto(`http://127.0.0.1:${server.address().port}`,{waitUntil:'domcontentloaded'});
            await page.evaluate(()=>{
                installEnvironmentGuards();showSnackbar=text=>{window.lastNotice=text;};
                curInstance={instanceId:'local',displayName:'Local'};
                instances=[curInstance,{instanceId:'remote-a',displayName:'A',remote:true,online:true},{instanceId:'remote-b',displayName:'B',remote:true,online:true}];
                activePage='acp';renderEnvTabs();
            });
            // Sidebar responses remain blocked; the visible page must still finish switching.
            await page.evaluate(()=>switchInstance('remote-a'));
            assert.equal(await page.evaluate(()=>environmentGate.switching),false);
            assert.equal(await page.evaluate(()=>document.querySelector('.app-layout').inert),false);
            assert.equal(await page.evaluate(()=>lastNotice),'已切换到 A');
            assert.match(await page.locator('#robotList').innerText(),/remote-a/);
            assert.equal(calls.filter(x=>x.p==='/api/registry/access').length,1);
            assert.ok(!calls.some(x=>x.p==='/api/channels/binding-targets'||x.p.startsWith('/api/mcp-auth/')||x.p==='/api/registry/settings'));
            releaseSidebar();
            await page.waitForFunction(()=>document.getElementById('taskCount').textContent==='0'&&environmentGate.reads.size===0);
            // Lazy pages query the selected environment when entered.
            await page.evaluate(()=>switchPage('mcp-auth'));
            await page.waitForFunction(()=>environmentGate.reads.size===0);
            assert.equal(calls.filter(x=>x.p.startsWith('/api/mcp-auth/')).length,2);
            failSessions=true;
            await page.evaluate(()=>{activePage='sessions';return switchInstance('remote-b');});
            assert.equal(await page.evaluate(()=>curInstance.instanceId),'remote-a');
            assert.equal(await page.evaluate(()=>environmentGate.recoveryRequired),false);
            assert.match(await page.evaluate(()=>lastNotice),/失败，仍在原环境/);
            failSessions=false;
            await page.evaluate(()=>{activePage='acp';return switchInstance('remote-b');});
            await page.waitForFunction(()=>environmentGate.reads.size===0&&document.getElementById('taskCount').textContent==='0');
            assert.match(await page.locator('#robotList').innerText(),/remote-b/);
            assert.equal(await page.evaluate(()=>location.hash),'#instance=remote-b');
            assert.deepEqual(errors,[]);
            console.log(`PASS environment switch, lazy loading, rollback and repeat switch at ${width}px`);
            await page.close();
        }
    } finally {await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(error=>{console.error(error);process.exitCode=1;});

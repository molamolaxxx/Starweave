const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/configui/assets/js/registry.js'), 'utf8');
function harness(api, extra={}) {
    const fields = new Map();
    const get = id => { if (!fields.has(id)) fields.set(id, {value:'', textContent:'', checked:false,style:{},attributes:{},focus(){},setAttribute(name,value){this.attributes[name]=value}}); return fields.get(id); };
    const messages=[];
    const context = vm.createContext({document:{getElementById:get}, curInstance:{instanceId:'local'}, instances:[],envName:i=>i.displayName,showDialog(){},closeDialog(){},api, URL, Response,AbortController, setTimeout, clearTimeout, showSnackbar:m=>messages.push(m), writeClipboard:async()=>{},...extra});
    vm.runInContext(source, context);
    return {context, get, messages};
}
test('registers entered values immediately without applying unrelated business config', async () => {
    const calls=[];
    const h=harness(async (url,options)=>{calls.push({url,body:options&&options.body&&JSON.parse(options.body)});return {ok:true,json:async()=>({clientEnabled:true,centerUrl:'10.0.0.1:10528',clientStatus:'CONNECTING'})};});
    h.get('registryCenterUrl').value='10.0.0.1:10528';h.get('registryDisplayName').value='我的电脑';
    await h.context.registerEnvironment();
    assert.equal(calls[0].url,'/api/registry/settings');
    assert.deepEqual(calls[0].body,{clientEnabled:true,centerUrl:'10.0.0.1:10528',displayName:'我的电脑'});
    assert.equal(h.get('registryClientStatus').textContent,'连接中');
    assert.equal(h.context.registryBusy,false);
    assert.ok(calls.every(c=>!c.url.includes('/api/refresh')));
});
test('background status refresh preserves unsaved text and displays verified registration', async () => {
    const h=harness(async()=>({ok:true,json:async()=>({clientEnabled:true,centerUrl:'saved',clientStatus:'REGISTERED'})}));
    h.get('registryCenterUrl').value='正在输入';await h.context.loadRegistrySettings(false);
    assert.equal(h.get('registryCenterUrl').value,'正在输入');assert.equal(h.get('registryClientStatus').textContent,'已注册');
});
test('a late response from another environment cannot overwrite selected settings', async () => {
    let finish;
    const h=harness(()=>new Promise(resolve=>{finish=resolve}));
    const loading=h.context.loadRegistrySettings(true);h.context.curInstance={instanceId:'remote'};
    h.get('registryCenterUrl').value='新环境';finish({ok:true,json:async()=>({centerUrl:'旧环境'})});await loading;
    assert.equal(h.get('registryCenterUrl').value,'新环境');
});
test('failed registration releases controls and reports the server error', async () => {
    const h=harness(async()=>({ok:false,json:async()=>({error:'中心未启用'})}));h.get('registryCenterUrl').value='10.0.0.1:10528';await h.context.registerEnvironment();
    assert.equal(h.context.registryBusy,false);assert.equal(h.get('registryRegister').disabled,false);assert.equal(h.messages[0],'中心未启用');
});
test('cancellation only changes registration state', async () => {
    const calls=[];const h=harness(async(url,options)=>{calls.push(options&&options.body&&JSON.parse(options.body));return{ok:true,json:async()=>({clientEnabled:false,clientStatus:'UNREGISTERED'})}});
    h.context.registryState.clientEnabled=true;await h.context.unregisterEnvironment();assert.deepEqual(calls[0],{clientEnabled:false});assert.equal(h.get('registryCancel').attributes['aria-disabled'],'true');
});
const core = fs.readFileSync(path.resolve(__dirname, '../../main/resources/configui/assets/js/core.js'), 'utf8');
function coreSection(start,end){const a=core.indexOf(start),b=core.indexOf(end,a);assert.ok(a>=0&&b>a);return core.slice(a,b)}
test('local, same-host and registered environments share the selected style without a notice', () => {
    const environments=[
        {instanceId:'local',self:true,home:'/local',displayName:'本地',configUiPort:10528},
        {instanceId:'other',self:false,home:'/other',displayName:'其它环境',configUiPort:10529},
        {instanceId:'registered',self:false,remote:true,online:true,home:'我的电脑',displayName:'我的电脑',sourceInstanceId:'source',configUiPort:20000}
    ];
    for(const current of environments){
        const tabs={innerHTML:''},trigger={title:''};
        const context=vm.createContext({instances:environments,curInstance:current,envName:i=>i.displayName,esc:String,syncEnvironmentGate(){},
            document:{getElementById:id=>{assert.ok(['envTabs','envTrigger'].includes(id));return id==='envTabs'?tabs:trigger;}}});
        vm.runInContext(coreSection('function renderEnvTabs(', 'async function switchInstance('),context);
        context.renderEnvTabs();
        assert.equal((tabs.innerHTML.match(/class="env-tab active"/g)||[]).length,1);
        assert.equal((tabs.innerHTML.match(/aria-current="true"/g)||[]).length,1);
        assert.ok(!/class="env-tab[^"]*remote/.test(tabs.innerHTML));
        assert.ok(!tabs.innerHTML.includes('正在编辑'));
        assert.equal(trigger.title,'当前环境：'+current.displayName+'；点击切换运行环境');
    }
});
test('both themes retain normal selection styles and omit remote warning styles and markup', () => {
    const resources=path.resolve(__dirname,'../../main/resources/configui');
    const css=fs.readFileSync(path.join(resources,'assets/css/base.css'),'utf8');
    const dark=fs.readFileSync(path.join(resources,'assets/css/dark-theme.css'),'utf8');
    const html=fs.readFileSync(path.join(resources,'index.html'),'utf8');
    assert.ok(css.includes('.env-tab.active{'));
    assert.ok(dark.includes('.env-tab.active,'));
    for(const content of [css,dark]){
        assert.ok(!content.includes('.env-trigger.remote'));
        assert.ok(!content.includes('.env-tab.active.remote'));
        assert.ok(!content.includes('.env-menu-note'));
    }
    assert.ok(!html.includes('envMenuNote'));
});
test('offline environment clicks retain the current environment and its business state', async () => {
    const notices=[];
    const context=vm.createContext({environmentGate:{switching:false},environmentOperationPending:()=>false,curInstance:{instanceId:'local'},instances:[{instanceId:'remote',remote:true,online:false}],dirty:false,showSnackbar:m=>notices.push(m)});
    vm.runInContext(coreSection('async function switchInstance(', 'async function loadConfig('),context);
    await context.switchInstance('remote');assert.equal(context.curInstance.instanceId,'local');assert.deepEqual(notices,['目标环境离线']);
});
test('refresh retains an offline selection instead of redirecting operations to the center', async () => {
    const context=vm.createContext({environmentGate:{switching:false},curInstance:{instanceId:'remote'},instances:[],location:{hash:'instance=remote'},renderEnvTabs(){},showSnackbar(){},api:async()=>({json:async()=>[{instanceId:'local',self:true},{instanceId:'remote',remote:true,online:false}]})});
    vm.runInContext(coreSection('async function loadInstances(', 'function setEnvMenuOpen('),context);
    await context.loadInstances(false);assert.equal(context.curInstance.instanceId,'remote');assert.equal(context.curInstance.online,false);
});

test('fills the default environment name and preserves an edited name during polling', async () => {
    const h=harness(async()=>({ok:true,json:async()=>({displayName:'environment-b',clientStatus:'UNREGISTERED'})}));
    await h.context.loadRegistrySettings(true);
    assert.equal(h.get('registryDisplayName').value,'environment-b');
    h.get('registryDisplayName').value='我的开发电脑';
    await h.context.loadRegistrySettings(false);
    assert.equal(h.get('registryDisplayName').value,'我的开发电脑');
});

test('registry server settings are always visible and contain no credential controls', () => {
    const html = fs.readFileSync(path.resolve(__dirname, '../../main/resources/configui/index.html'), 'utf8');
    const section = html.slice(html.indexOf('id="registryClientStatus"'), html.indexOf('id="chatterList"'));
    assert.ok(!section.includes('作为注册中心接受远程环境'));
    assert.ok(!section.includes('点击注册立即保存'));
    assert.ok(section.includes('id="registryServerEnabled"'));
    assert.ok(section.includes('id="registryTunnelPort"'));
    assert.ok(!section.includes('<details'));
    assert.ok(!section.includes('<summary'));
    assert.ok(!section.includes('registryClientCredential'));
    assert.ok(!section.includes('copyRegistryCredential'));
});

test('empty center and invalid tunnel ports give guidance without sending requests', async () => {
    let calls=0;const h=harness(async()=>{calls++;throw new Error('unexpected request')});
    await h.context.registerEnvironment();assert.match(h.messages.pop(),/请先填写中心管理页面/);
    for(const value of ['', '0','65536','10530.5','abc']){
        h.get('registryTunnelPort').value=value;await h.context.configureRegistryServer();
        assert.match(h.messages.pop(),/请填写 1～65535/);
    }
    for(const value of ['file:///tmp/a','http://host/path','http://user:pass@host','http://host:0','bad address']){
        h.get('registryCenterUrl').value=value;await h.context.registerEnvironment();assert.match(h.messages.pop(),/中心地址格式不正确/);
    }
    assert.equal(calls,0);
});
test('inactive cancellation and busy controls explain why an operation cannot run', async () => {
    let calls=0;const h=harness(async()=>{calls++});
    await h.context.unregisterEnvironment();assert.match(h.messages.pop(),/尚未注册/);
    h.context.registryBusy=true;h.context.renderRegistryStatus();
    for(const action of ['registerEnvironment','unregisterEnvironment','configureRegistryServer']){
        await h.context[action]();assert.match(h.messages.pop(),/正在处理上一次操作/);
    }
    assert.equal(h.get('registryRegister').disabled,false);
    assert.equal(h.get('registryServerApply').style.cursor,'wait');assert.equal(calls,0);
});
test('network, non-JSON responses and unknown statuses never expose English errors', async () => {
    for(const api of [async()=>{throw new TypeError('Failed to fetch')},async()=>({ok:false,status:500,json:async()=>{throw new SyntaxError('Unexpected token')}}),async()=>({ok:false,status:503,json:async()=>({error:'Connection refused'})})]){
        const h=harness(api);h.get('registryCenterUrl').value='10.0.0.1:10528';
        await h.context.registerEnvironment();assert.ok(h.messages.length);assert.ok(!/[a-zA-Z]/.test(h.messages[0]));assert.equal(h.context.registryBusy,false);
    }
    assert.equal(harness(()=>{}).context.registryStatusLabel('UNKNOWN'),'状态更新中');
});
test('failed settings load preserves registered state so cancellation remains available', async () => {
    const h=harness(async()=>{throw new Error('Failed to fetch')});h.context.registryState.clientEnabled=true;
    await h.context.loadRegistrySettings(true);assert.equal(h.context.registryState.clientEnabled,true);
    assert.match(h.messages[0],/设置加载失败/);
});
test('request timeout cancels the request and releases busy controls with guidance', async () => {
    const h=harness(async(url,options)=>{throw Object.assign(new Error('aborted'),{name:options.signal.aborted?'AbortError':'Error'})});
    h.context.setTimeout=callback=>{callback();return 1};h.context.clearTimeout=()=>{};
    h.get('registryCenterUrl').value='10.0.0.1:10528';await h.context.registerEnvironment();
    assert.match(h.messages[0],/操作等待超时/);assert.equal(h.context.registryBusy,false);
});

test('background connection errors remain readable without displaying raw English', () => {
    const h=harness(()=>{});h.context.registryState.clientError='Connection reset';h.context.registryState.serverError='bind: address already in use';
    h.context.renderRegistryStatus();
    assert.ok(!/[a-zA-Z]/.test(h.get('registryClientStatus').textContent));
    assert.ok(!/[a-zA-Z]/.test(h.get('registryServerStatus').textContent));
    assert.match(h.get('registryServerStatus').textContent,/请检查/);
});

test('password is optional at registration and can be saved or explicitly removed', async () => {
    const calls=[];
    const h=harness(async(url,options)=>{if(options.body)calls.push(JSON.parse(options.body));return{ok:true,json:async()=>({accessPasswordSet:true})}});
    h.get('registryCenterUrl').value='10.0.0.1:10528';h.get('registryAccessPassword').value='secret';
    await h.context.registerEnvironment();assert.equal(calls[0].accessPassword,'secret');
    assert.equal(h.get('registryAccessPassword').value,'');assert.match(h.get('registryAccessPassword').placeholder,/已设置/);
    await h.context.configureRegistryPassword(true);assert.deepEqual(calls[1],{accessPassword:''});
});

test('concurrent remote operations share one password prompt and cancellation sends no business request', async () => {
    const calls=[];
    const win={location:{href:'https://center.example/',origin:'https://center.example'},fetch:async(url)=>{
        calls.push(url);return new Response(JSON.stringify({authenticated:false,passwordRequired:true}),{status:200});
    }};
    const h=harness(()=>{}, {window:win});
    const first=win.fetch('/api/config?instance=remote-a'),second=win.fetch('/api/starweave/v1/sessions/open?instance=remote-a',{method:'POST'});
    await new Promise(setImmediate);assert.equal(calls.length,1);assert.equal(h.context.registryAccessPrompt.id,'remote-a');
    h.context.finishRegistryAccess(false);assert.equal((await first).status,401);assert.equal((await second).status,401);
    assert.ok(calls.every(url=>url.startsWith('/api/registry/access')));
});

test('wrong password keeps the modal open and successful login resumes a pending operation', async () => {
    let authenticated=false,businessCalls=0;
    const win={location:{href:'https://center.example/',origin:'https://center.example'},fetch:async(url,options)=>{
        if(!url.startsWith('/api/registry/access')){businessCalls++;return new Response('{}',{status:200})}
        if(options.method==='POST'){
            authenticated=JSON.parse(options.body).password==='correct';
            return new Response(JSON.stringify(authenticated?{authenticated:true}:{error:'密码不正确，请重新输入'}),{status:authenticated?200:401});
        }
        return new Response(JSON.stringify({authenticated}),{status:200});
    }};
    const h=harness(()=>{}, {window:win});const waiting=win.fetch('/api/config?instance=remote-a');
    await new Promise(setImmediate);h.get('registryLoginPassword').value='wrong';await h.context.submitRegistryAccessPassword();
    assert.match(h.get('registryAccessError').textContent,/密码不正确/);assert.ok(h.context.registryAccessPrompt);assert.equal(businessCalls,0);
    h.get('registryLoginPassword').value='correct';await h.context.submitRegistryAccessPassword();
    assert.equal((await waiting).status,200);assert.equal(businessCalls,1);assert.equal(h.get('registryLoginPassword').value,'');
    await win.fetch('/api/config?instance=remote-a');assert.equal(h.context.registryAccessPrompt,null);assert.equal(businessCalls,2);
});

test('expired or revoked login is checked again before the next write', async () => {
    let authenticated=true,businessCalls=0;
    const win={location:{href:'http://center.example/',origin:'http://center.example'},fetch:async(url)=>{
        if(url.startsWith('/api/registry/access'))return new Response(JSON.stringify({authenticated}),{status:200});
        businessCalls++;return new Response('{}',{status:200});
    }};
    const h=harness(()=>{}, {window:win});await win.fetch('/api/config?instance=remote-a');authenticated=false;
    const waiting=win.fetch('/api/refresh?instance=remote-a',{method:'POST'});await new Promise(setImmediate);
    assert.equal(businessCalls,1);assert.ok(h.context.registryAccessPrompt);h.context.finishRegistryAccess(false);assert.equal((await waiting).status,401);
});

test('sequential reads reuse access checks per environment and recheck after expiry',async()=>{
    const calls=[];let now=1000;
    const win={location:{href:'https://center.example/',origin:'https://center.example'},fetch:async url=>{calls.push(url);return new Response(JSON.stringify({authenticated:true,expiresAt:100000}),{status:200});}};
    const h=harness(()=>{}, {window:win,Date:{now:()=>now}});
    await win.fetch('/api/config?instance=remote-a');await win.fetch('/api/channels/status?instance=remote-a');
    assert.equal(calls.filter(x=>x.startsWith('/api/registry/access')).length,1);
    await win.fetch('/api/config?instance=remote-b');assert.equal(calls.filter(x=>x.startsWith('/api/registry/access')).length,2);
    now+=30001;await win.fetch('/api/config?instance=remote-a');assert.equal(calls.filter(x=>x.startsWith('/api/registry/access')).length,3);
    assert.equal(h.context.registryAccessVerifiedUntil['remote-b'],31000);
});
test('server rejection invalidates cached access without replaying business requests',async()=>{
    let checks=0,business=0,rejected=false;
    const win={location:{href:'https://center.example/',origin:'https://center.example'},fetch:async url=>{
        if(url.startsWith('/api/registry/access')){checks++;return new Response('{"authenticated":true}');}
        business++;return new Response('{}',{status:rejected?401:200});
    }};
    harness(()=>{}, {window:win});await win.fetch('/api/config?instance=remote-a');rejected=true;
    assert.equal((await win.fetch('/api/config?instance=remote-a')).status,401);assert.equal(business,2);assert.equal(checks,1);
    rejected=false;await win.fetch('/api/config?instance=remote-a');assert.equal(checks,2);assert.equal(business,3);
});
test('access cache never extends beyond the server ticket expiry',async()=>{
    let now=1000,checks=0;
    const win={location:{href:'https://center.example/',origin:'https://center.example'},fetch:async url=>{if(url.startsWith('/api/registry/access'))checks++;return new Response('{"authenticated":true,"expiresAt":2000}');}};
    harness(()=>{}, {window:win,Date:{now:()=>now}});await win.fetch('/api/config?instance=remote-a');
    now=2001;await win.fetch('/api/config?instance=remote-a');assert.equal(checks,2);
});

const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const core = fs.readFileSync(path.resolve(__dirname, '../../main/resources/configui/assets/js/core.js'), 'utf8');
function section(start, end) { return core.slice(core.indexOf(start), core.indexOf(end, core.indexOf(start))); }
function deferred() { let resolve, reject; const promise = new Promise((a,b) => {resolve=a;reject=b;}); return {promise,resolve,reject}; }
function harness(extra = {}) {
    const nodes = new Map(), messages = [], loaded = [], locked = {inert:false}, storage = new Map();
    const get = id => {if (!nodes.has(id)) nodes.set(id, {hidden:false, disabled:false, textContent:'', attributes:{}, setAttribute(k,v){this.attributes[k]=v;}, querySelector(){return get('icon');}, focus(){}});return nodes.get(id);};
    const context = vm.createContext({
        document:{body:{classList:{toggle(){},remove(){}}},getElementById:get,querySelectorAll:selector=>selector.includes('.app-layout')?[locked]:[]},
        itemRefreshRuntime:{robots:{},channels:{}},itemToggleRuntime:{robots:{},channels:{}},registryBusy:false,
        curInstance:{instanceId:'a',displayName:'A'},instances:[{instanceId:'a',displayName:'A'},{instanceId:'b',displayName:'B'},{instanceId:'c',displayName:'C'}],
        config:{robots:[{name:'old'}]},dirty:false,activePage:'acp',location:{hash:'instance=a'},
        channelRuntime:{},teamSharingRuntime:{},channelBindingTargets:{},mcpAuthRuntime:{},starSessions:{},starTeams:{},teamSession:{},taskState:{},taskEditor:{},
        scheduleState:{},channelMessages:{},resourceDream:{},fileLinkPreview:{request:0},observationState:{},
        resetObservationState(){context.observationState={};},resetObservationTokens(){},
        envName:i=>i.displayName,showSnackbar:m=>messages.push(m),setEnvMenuOpen(){},renderEnvTabs(){context.syncEnvironmentGate();},
        showConfirm:async()=>true,ensureRegistryEnvironmentAccess:async()=>true,
        registryLoadToken:0,channelBindingTargetRequest:0,closeStarweaveStream(){},closeTeamSessionStream(){},closeDialog(){},
        loadConfig:async config=>{context.config=config;loaded.push(config);},
        fetch:async()=>new Response(JSON.stringify({robots:[{name:'new'}]})),
        localStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,value)},
        Response,AbortController,TextDecoder,setTimeout,clearTimeout,...extra
    });
    context.window=context;
    vm.runInContext(section('var environmentGate=', 'var dirty='),context);
    vm.runInContext(section('function rememberedEnvironment(', 'async function loadInstances('),context);
    vm.runInContext(section('async function switchInstance(', 'async function loadConfig('),context);
    return {context,get,messages,loaded,locked,storage};
}
test('switch locks the page immediately and publishes success only after target data is ready', async()=>{
    const fetchWait=deferred(),loadWait=deferred();
    const h=harness({fetch:()=>fetchWait.promise,loadConfig:()=>loadWait.promise});
    const switching=h.context.switchInstance('b');
    assert.equal(h.locked.inert,true);
    assert.equal(h.get('envTrigger').disabled,true);
    assert.equal(h.get('envSwitchStatus').textContent,'正在切换到 B…');
    assert.equal(h.context.curInstance.instanceId,'a');
    await h.context.switchInstance('c');
    fetchWait.resolve(new Response('{}'));
    await new Promise(setImmediate);
    assert.equal(h.messages.includes('已切换到 B'),false);
    assert.equal(h.locked.inert,true);
    loadWait.resolve();await switching;
    assert.equal(h.context.curInstance.instanceId,'b');
    assert.equal(h.context.location.hash,'instance=b');
    assert.equal(h.locked.inert,false);
    assert.equal(h.get('envSwitchStatus').hidden,true);
    assert.ok(h.messages.includes('已切换到 B'));
    assert.equal(h.storage.get('starweave-environment'),'b');
});
test('failed preflight preserves original environment, configuration and dirty draft', async()=>{
    const h=harness({dirty:true,fetch:async()=>new Response('{}',{status:503})});const old=h.context.config;
    await h.context.switchInstance('b');
    assert.equal(h.context.curInstance.instanceId,'a');assert.equal(h.context.config,old);assert.equal(h.context.dirty,true);
    assert.equal(h.context.location.hash,'instance=a');assert.equal(h.locked.inert,false);
    assert.ok(h.messages.some(m=>m.includes('仍在原环境')));
});
test('cancelled confirmation releases switch lock without losing the draft', async()=>{
    const h=harness({dirty:true,showConfirm:async()=>false});await h.context.switchInstance('b');
    assert.equal(h.context.curInstance.instanceId,'a');assert.equal(h.context.dirty,true);assert.equal(h.locked.inert,false);assert.equal(h.loaded.length,0);
});
test('target projection failure rolls back rather than showing switch success', async()=>{
    const h=harness({dirty:true});const old=h.context.config;
    h.storage.set('starweave-environment','a');
    h.context.loadConfig=async config=>{h.context.config=config;if(config!==old)h.context.environmentGate.loadErrors.push(new Error('session query failed'));};
    await h.context.switchInstance('b');
    assert.equal(h.context.curInstance.instanceId,'a');assert.equal(h.context.config,old);assert.equal(h.context.dirty,true);
    assert.equal(h.messages.includes('已切换到 B'),false);assert.equal(h.locked.inert,false);
    assert.equal(h.storage.get('starweave-environment'),'a');
});
test('initial discovery restores the remembered environment before API requests', async()=>{
    let url;
    const h=harness({curInstance:null,location:{hash:''},fetch:async target=>{
        url=target;return new Response(JSON.stringify([{instanceId:'a',self:true},{instanceId:'remote-b',remote:true,online:true}]));
    }});
    h.storage.set('starweave-environment','remote-b');
    vm.runInContext(section('async function api(', 'function envName('),h.context);
    vm.runInContext(section('async function loadInstances(', 'function setEnvMenuOpen('),h.context);
    await h.context.loadInstances(false);
    assert.equal(h.context.curInstance.instanceId,'remote-b');
    await h.context.api('/api/config');
    assert.equal(url,'/api/config?instance=remote-b');
});
test('explicit URL environment takes precedence over the remembered selection', async()=>{
    const h=harness({curInstance:null,location:{hash:'#instance=c'},api:async()=>new Response(JSON.stringify([{instanceId:'a',self:true},{instanceId:'b'},{instanceId:'c'}]))});
    h.storage.set('starweave-environment','b');
    vm.runInContext(section('async function loadInstances(', 'function setEnvMenuOpen('),h.context);
    await h.context.loadInstances(false);
    assert.equal(h.context.curInstance.instanceId,'c');
});
test('list refresh keeps the initial fallback when the URL points to an offline environment', async()=>{
    const h=harness({curInstance:null,location:{hash:'#instance=remote-b'},api:async()=>new Response(JSON.stringify([{instanceId:'a',self:true},{instanceId:'remote-b',remote:true,online:false}]))});
    vm.runInContext(section('async function loadInstances(', 'function setEnvMenuOpen('),h.context);
    await h.context.loadInstances(false);
    await h.context.loadInstances(false);
    assert.equal(h.context.curInstance.instanceId,'a');
});
test('missing or offline remembered environment falls back without overwriting the preference', async()=>{
    for(const saved of ['missing','remote-b']){
        const h=harness({curInstance:null,location:{hash:''},api:async()=>new Response(JSON.stringify([{instanceId:'a',self:true},{instanceId:'remote-b',remote:true,online:false}]))});
        h.storage.set('starweave-environment',saved);
        vm.runInContext(section('async function loadInstances(', 'function setEnvMenuOpen('),h.context);
        await h.context.loadInstances(false);
        assert.equal(h.context.curInstance.instanceId,'a');
        assert.equal(h.storage.get('starweave-environment'),saved);
    }
});
test('selecting the current environment remembers it and unavailable storage does not block switching', async()=>{
    const h=harness();await h.context.switchInstance('a');
    assert.equal(h.storage.get('starweave-environment'),'a');
    h.context.localStorage={getItem(){throw new Error('disabled')},setItem(){throw new Error('disabled')}};
    await h.context.switchInstance('b');
    assert.equal(h.context.curInstance.instanceId,'b');
    h.context.curInstance=null;h.context.location.hash='';
    h.context.api=async()=>new Response(JSON.stringify([{instanceId:'a',self:true}]));
    vm.runInContext(section('async function loadInstances(', 'function setEnvMenuOpen('),h.context);
    await h.context.loadInstances(false);
    assert.equal(h.context.curInstance.instanceId,'a');
});
test('concurrent operations keep environment disabled until every workflow finishes', async()=>{
    const first=deferred(),second=deferred();let calls=0;
    const h=harness({refreshRobot:()=>++calls===1?first.promise:second.promise});h.context.installEnvironmentGuards();
    const a=h.context.refreshRobot(),b=h.context.refreshRobot();
    await h.context.switchInstance('b');assert.equal(h.context.curInstance.instanceId,'a');
    first.resolve();await a;assert.equal(h.get('envTrigger').disabled,true);
    second.resolve();await b;assert.equal(h.get('envTrigger').disabled,false);
});
test('operation lock includes confirmation and post-submit synchronization', async()=>{
    const confirm=deferred(),sync=deferred();
    const h=harness({newTeamSession:async()=>{await confirm.promise;await sync.promise;}});h.context.installEnvironmentGuards();
    const operation=h.context.newTeamSession();assert.equal(h.get('envTrigger').disabled,true);
    confirm.resolve();await new Promise(setImmediate);assert.equal(h.get('envTrigger').disabled,true);
    sync.resolve();await operation;assert.equal(h.get('envTrigger').disabled,false);
});
test('operation rejection releases its counter and switch blocks programmatic mutations', async()=>{
    let called=0;const h=harness({saveConfig:async()=>{called++;throw new Error('failed');}});h.context.installEnvironmentGuards();
    await assert.rejects(h.context.saveConfig(),/failed/);assert.equal(h.context.environmentGate.operations,0);
    h.context.environmentGate.switching=true;assert.equal(await h.context.saveConfig(),false);assert.equal(called,1);
});
test('backend refresh state also prevents switching', async()=>{
    const h=harness();h.context.itemRefreshRuntime.robots.old=Date.now();await h.context.switchInstance('b');
    assert.equal(h.context.curInstance.instanceId,'a');assert.equal(h.loaded.length,0);
});
test('switch waits for old read workflows before committing the target', async()=>{
    const read=deferred();const h=harness({loadStarweaveTeams:()=>read.promise});h.context.installEnvironmentGuards();
    const pending=h.context.loadStarweaveTeams();await new Promise(setImmediate);
    const switching=h.context.switchInstance('b');await new Promise(setImmediate);
    assert.equal(h.context.curInstance.instanceId,'a');
    assert.equal(await h.context.loadStarweaveTeams(),false);
    read.resolve();await pending;await switching;assert.equal(h.context.curInstance.instanceId,'b');
});
test('API aborts old queries and guards the response body with an environment version', async()=>{
    const body=deferred();let url;
    const h=harness({fetch:async target=>{url=target;return {arrayBuffer:()=>body.promise,status:200,statusText:'OK',headers:{}};}});
    vm.runInContext(section('async function api(', 'function envName('),h.context);
    const query=h.context.api('/api/config');await new Promise(setImmediate);
    assert.equal(url,'/api/config?instance=a');assert.equal(h.context.environmentGate.requests.size,1);
    h.context.environmentGate.version++;body.resolve('{}');await assert.rejects(query,/环境已切换/);
    assert.equal(h.context.environmentGate.requests.size,0);
});
test('automatic environment discovery does not silently select another environment', async()=>{
    const h=harness({fetch:async()=>new Response(JSON.stringify([{instanceId:'b',self:true}]))});
    vm.runInContext(section('async function api(', 'function envName('),h.context);
    vm.runInContext(section('async function loadInstances(', 'function setEnvMenuOpen('),h.context);
    await h.context.loadInstances(false);assert.equal(h.context.curInstance.instanceId,'a');
});
test('API preserves binary resource exports and external abort signals', async()=>{
    const bytes=new Uint8Array([0,255,128,80,75]);const external=new AbortController();let signal;
    const h=harness({fetch:async(url,opts)=>{signal=opts.signal;return new Response(bytes);}});
    vm.runInContext(section('async function api(', 'function envName('),h.context);
    const response=await h.context.api('/api/agent-resources/export',{signal:external.signal});
    assert.deepEqual(new Uint8Array(await response.arrayBuffer()),bytes);assert.equal(signal.aborted,false);
    external.abort();assert.equal(h.context.environmentGate.requests.size,0);
});
test('API rejects mutation requests even during internal target loading', async()=>{
    let calls=0;const h=harness({fetch:async()=>{calls++;return new Response('{}');}});
    vm.runInContext(section('async function api(', 'function envName('),h.context);
    h.context.environmentGate.switching=true;h.context.environmentGate.loading=true;
    await assert.rejects(h.context.api('/api/refresh',{method:'POST'}),/环境正在切换/);assert.equal(calls,0);
});
test('failed rollback leaves operations locked and selecting the original environment retries recovery', async()=>{
    const h=harness();let fail=true;
    h.context.loadConfig=async config=>{h.context.config=config;if(fail)h.context.environmentGate.loadErrors.push(new Error('offline'));};
    await h.context.switchInstance('b');
    assert.equal(h.context.curInstance.instanceId,'a');assert.equal(h.context.environmentGate.recoveryRequired,true);assert.equal(h.locked.inert,true);
    assert.equal(h.get('envTrigger').disabled,false);
    fail=false;await h.context.switchInstance('a');
    assert.equal(h.context.environmentGate.recoveryRequired,false);assert.equal(h.locked.inert,false);
});
test('uncertain mutation result requires confirming the original environment before switching elsewhere', async()=>{
    const h=harness({fetch:async()=>{throw new Error('connection lost');}});
    vm.runInContext(section('async function api(', 'function envName('),h.context);
    await assert.rejects(h.context.api('/api/refresh',{method:'POST'}),/connection lost/);
    assert.equal(h.context.environmentGate.recoveryRequired,true);assert.equal(h.locked.inert,true);
    await h.context.switchInstance('b');assert.equal(h.context.curInstance.instanceId,'a');
});

test('team history, context and session-list POST queries do not lock the environment on failure', async () => {
    let failed=true;
    const h=harness({fetch:async()=>new Response('{}',{status:failed?503:200})});
    vm.runInContext(section('async function api(', 'function envName('),h.context);
    for(const action of ['history','context','listSessions']) {
        const result=await h.context.api('/api/starweave/v1/teams/member',{method:'POST',body:JSON.stringify({action})});
        assert.equal(result.status,503);assert.equal(h.context.environmentGate.recoveryRequired,false);
    }
    failed=false;
    assert.equal((await h.context.api('/api/starweave/v1/teams/member',{method:'POST',body:'{"action":"history"}'})).status,200);
    failed=true;
    await h.context.api('/api/starweave/v1/teams/member',{method:'POST',body:'{"action":"send"}'});
    assert.equal(h.context.environmentGate.recoveryRequired,true);
    failed=false;
    assert.equal((await h.context.api('/api/starweave/v1/teams/member',{method:'POST',body:'{"action":"history"}'})).status,200);
    await assert.rejects(h.context.api('/api/starweave/v1/teams/member',{method:'POST',body:'{"action":"send"}'}),/环境状态待确认/);
});

function loadingHarness(page='acp') {
    const calls=[],timers=[],waits={};
    const names=['loadChannelStatus','loadItemRefreshStatus','loadTeamSharingStatus','loadChannelBindingTargets','loadMcpAuth','loadRegistrySettings','loadStarweaveSessions','loadStarweaveTeams','loadTaskCount','loadScheduleCount','loadObservationCount'];
    const extra={activePage:page,clearDirty(){},render(){calls.push('render');},normalizeChannelOutboundTargets(){}};
    for(const name of names)extra[name]=()=>{calls.push(name);return waits[name]?.promise||Promise.resolve();};
    const h=harness(extra);
    vm.runInContext(section('async function loadConfig(', 'async function loadChannelStatus('),h.context);
    h.context.environmentGate.switching=true;h.context.environmentGate.loading=true;
    h.context.setTimeout=fn=>{timers.push(fn);return timers.length;};
    return {...h,calls,timers,waits};
}
test('configuration loads independent page data concurrently and leaves unrelated queries outside the switch lock',async()=>{
    const h=loadingHarness();
    h.waits.loadChannelStatus=deferred();h.waits.loadItemRefreshStatus=deferred();h.waits.loadTeamSharingStatus=deferred();
    const loading=h.context.loadConfig({robots:[],channels:[]});
    assert.deepEqual(h.calls,['loadChannelStatus','loadItemRefreshStatus','loadTeamSharingStatus']);
    h.waits.loadChannelStatus.resolve();h.waits.loadItemRefreshStatus.resolve();
    await new Promise(setImmediate);assert.ok(!h.calls.includes('render'));
    h.waits.loadTeamSharingStatus.resolve();await loading;
    assert.equal(h.calls.at(-1),'render');assert.equal(h.timers.length,0);
    assert.ok(!h.calls.includes('loadChannelBindingTargets'));assert.ok(!h.calls.includes('loadMcpAuth'));
    h.context.environmentGate.switching=false;h.context.environmentGate.loading=false;
    h.context.flushEnvironmentBackgroundLoads();h.timers[0]();await new Promise(setImmediate);
    for(const name of ['loadTaskCount','loadScheduleCount','loadObservationCount','loadStarweaveSessions','loadStarweaveTeams'])assert.ok(h.calls.includes(name));
});
test('each page awaits its required data and avoids redundant background list reads',async()=>{
    for(const [page,names] of [['channels',['loadChannelBindingTargets']],['mcp-auth',['loadMcpAuth']],['basic',['loadRegistrySettings']],['sessions',['loadStarweaveSessions']],['teams',['loadStarweaveSessions','loadStarweaveTeams']]]){
        const h=loadingHarness(page);h.waits[names[0]]=deferred();
        const loading=h.context.loadConfig({robots:[],channels:[]});
        for(const name of names)assert.ok(h.calls.includes(name));
        assert.ok(!h.calls.includes('render'));h.waits[names[0]].resolve();await loading;
        h.context.environmentGate.switching=false;h.context.flushEnvironmentBackgroundLoads();h.timers[0]();await new Promise(setImmediate);
        for(const name of names)assert.equal(h.calls.filter(x=>x===name).length,1);
    }
});
test('deferred sidebar reads are discarded after a new environment version or failed recovery',async()=>{
    for(const invalidate of [h=>h.context.environmentGate.version++,h=>h.context.environmentGate.recoveryRequired=true,h=>h.context.environmentGate.switching=true]){
        const h=loadingHarness();await h.context.loadConfig({robots:[],channels:[]});
        h.context.environmentGate.switching=false;h.context.flushEnvironmentBackgroundLoads();invalidate(h);h.timers[0]();await new Promise(setImmediate);
        assert.ok(!h.calls.includes('loadTaskCount'));assert.ok(!h.calls.includes('loadStarweaveSessions'));
    }
});
test('unrelated list failures do not fail switching while the visible session page remains required',async()=>{
    const h=harness({fetch:async()=>new Response('{}',{status:503})});
    vm.runInContext(section('async function api(', 'function envName('),h.context);
    h.context.environmentGate.switching=true;h.context.environmentGate.loading=true;
    await h.context.api('/api/starweave/v1/sessions');assert.equal(h.context.environmentGate.loadErrors.length,0);
    h.context.activePage='sessions';await assert.rejects(h.context.api('/api/starweave/v1/sessions'),/加载失败/);
    assert.equal(h.context.environmentGate.loadErrors.length,1);assert.equal(h.context.environmentGate.recoveryRequired,false);
});

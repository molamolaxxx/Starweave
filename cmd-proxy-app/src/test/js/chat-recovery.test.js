const test=require('node:test'),assert=require('node:assert/strict'),fs=require('fs'),path=require('path'),vm=require('vm');
const source=fs.readFileSync(path.resolve(__dirname,'../../main/resources/configui/assets/js/starweave.js'),'utf8');
const code=source.slice(source.indexOf('// Restore authoritative history after WebView suspension;'));
function harness(){
 const calls=[],handlers={},timers=new Map();let id=0;
 const session={groupId:'g',sessionId:'s',generation:1};
 const c=vm.createContext({
  environmentGate:{version:0,switching:false,recoveryRequired:false},curInstance:{instanceId:'a'},activePage:'sessions',
  starSessions:{selectedGroupId:'g'},starTeams:{items:[]},teamSession:{teamId:'',teamMemberId:'',messages:[]},
  document:{hidden:false,getElementById:()=>({classList:{contains:()=>false}}),addEventListener:(name,fn)=>handlers[name]=fn},
  window:{addEventListener:(name,fn)=>handlers[name]=fn},
  setTimeout:fn=>{timers.set(++id,fn);return id},clearTimeout:key=>timers.delete(key),
  teamMessageIdentity:message=>message.messageId,selectedStarSession:()=>session,selectedTeamSessionMember:()=>null,starHistoryScope:()=>session.sessionId,teamHistoryScope:()=>'',
  closeStarweaveStream:()=>calls.push('close'),closeTeamSessionStream(){},connectStarweaveStream:()=>calls.push('connect'),connectTeamSessionStream(){},
  loadStarweaveSessions:async()=>{calls.push('metadata');return true},loadStarweaveSnapshot:async recover=>{assert.equal(recover,true);calls.push('history');return true}
 });
 vm.runInContext(code,c);c.installChatRecoveryListeners();
 return {c,calls,handlers,timers,session};
}
test('native pause closes streams and duplicate resume signals share one recovery',async()=>{
 const {c,calls,handlers,timers}=harness();
 handlers['starweave:pause']();assert.equal(c.chatLifecyclePaused,true);assert.equal(calls[0],'close');
 handlers['starweave:resume']();handlers.online();assert.equal(timers.size,1);
 const first=c.recoverVisibleChats(),second=c.recoverVisibleChats();await Promise.all([first,second]);
 assert.equal(calls.filter(x=>x==='metadata').length,1);assert.equal(calls.filter(x=>x==='history').length,1);
 assert.deepEqual(calls.slice(-3),['metadata','history','connect']);
});
test('hidden pages do not reconnect or request history',async()=>{
 const {c,calls,handlers}=harness();c.document.hidden=true;handlers.visibilitychange();
 await c.recoverVisibleChats();assert.deepEqual(calls,['close']);assert.equal(c.chatRecoveryNeeded,true);
 c.document.hidden=false;await c.recoverVisibleChats();assert.ok(calls.includes('history'));
});
test('environment change during metadata load cannot refresh another session',async()=>{
 const {c,calls}=harness();let finish;
 c.loadStarweaveSessions=()=>new Promise(resolve=>finish=resolve);
 const recovery=c.recoverVisibleChats();c.environmentGate.version++;c.curInstance={instanceId:'b'};
 finish(true);await recovery;assert.equal(calls.includes('history'),false);assert.equal(calls.includes('connect'),false);
});
test('selecting another session during metadata load does not recover the wrong conversation',async()=>{
 const {c,calls}=harness();let finish;c.loadStarweaveSessions=()=>new Promise(resolve=>finish=resolve);
 const recovery=c.recoverVisibleChats();c.starSessions.selectedGroupId='other';finish(true);await recovery;
 assert.equal(calls.includes('history'),false);
});
test('temporary failure schedules bounded retries without an environment lock',async()=>{
 const {c,calls,timers}=harness();c.loadStarweaveSessions=async()=>false;
 await c.recoverVisibleChats();assert.equal(c.chatRecoveryNeeded,true);assert.equal(c.chatRecoveryRetry,2000);
 assert.equal(timers.size,1);assert.equal(c.environmentGate.recoveryRequired,false);assert.equal(calls.includes('connect'),false);
 c.loadStarweaveSessions=async()=>true;c.chatRecoveryTimer=0;timers.clear();
 await c.recoverVisibleChats();assert.equal(c.chatRecoveryNeeded,false);assert.equal(c.chatRecoveryRetry,1000);assert.ok(calls.includes('history'));
});

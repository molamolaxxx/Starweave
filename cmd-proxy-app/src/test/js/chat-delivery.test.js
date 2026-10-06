const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const source=fs.readFileSync('cmd-proxy-app/src/main/resources/configui/assets/js/starweave.js','utf8');
function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return {promise,resolve}}
function fixture(){
 const nodes={};
 const member={teamMemberId:'m',sessionId:'old',state:'SLEEP',acpClientId:'c'};
 const c={console,setTimeout,clearTimeout,curInstance:{instanceId:'i'},activePage:'sessions',starSessions:{selectedGroupId:'g',items:[{groupId:'g',sessionId:'s',generation:1,state:'READY'}],events:[],uploads:[],lastSeq:0},teamSession:{teamId:'t',teamMemberId:'m',members:[member],messages:[],liveItems:[],uploads:[],snapshotToken:0,lastSeq:0},document:{addEventListener:()=>{},getElementById:id=>nodes[id]||(nodes[id]={value:'你好',classList:{contains:()=>true,toggle:()=>{}}})},isAgentOperable:()=>true,esc:s=>String(s||''),showSnackbar:()=>{},renderMarkdown:s=>s,starEventRenderFrame:0,teamEventRenderFrame:0};
 vm.createContext(c);vm.runInContext(fs.readFileSync('cmd-proxy-app/src/main/resources/configui/assets/js/chat-history.js','utf8'),c);vm.runInContext(source,c);
 for(const name of ['renderChatOutbox','renderStarweaveUploads','renderTeamSessionUploads','renderTeamSessionMembers','renderTeamSessionDetail','renderTeamSessionMessages','renderStarweaveEvents','connectTeamSessionStream','connectStarweaveStream'])c[name]=()=>{};
 c.loadStarweaveSessions=async()=>{};
 return c;
}
test('both send entrypoints render an outbox before a delayed server response',async()=>{
 for(const name of ['sendStarweaveMessage','sendTeamSessionMessage']){
  const c=fixture(),http=deferred();c.starSessions.followOutput=false;c.teamSession.followOutput=false;let renders=0;c.renderChatOutbox=()=>renders++;
  c.fetch=()=>http.promise;c.loadTeamSessionSnapshot=async()=>{};
  const sending=c[name]();
  const pending=Object.values(c.chatOutbox);assert.equal(pending.length,1);assert.equal(pending[0].text,'你好');assert.equal(pending[0].status,'sending');assert.ok(renders>0);assert.equal(name==='sendStarweaveMessage'?c.starSessions.followOutput:c.teamSession.followOutput,true);
  http.resolve({ok:true,json:async()=>({accepted:true,data:{accepted:true,data:{sessionId:'new'}}})});await sending;
  assert.equal(pending[0].status,'accepted');
 }
});
test('snapshot and replay merge identical user IDs but preserve repeated text',()=>{
 const c=fixture();c.chatOutbox.a={scope:c.teamChatScope()};
 c.mergeTeamMessage({role:'USER',content:'你好',messageId:'a',revision:1});
 c.appendTeamSessionEvent({teamId:'t',teamMemberId:'m',eventSeq:1,type:'USER_MESSAGE_ACCEPTED',data:{sessionId:'old',messageId:'a',revision:1,content:'你好'}});
 c.appendTeamSessionEvent({teamId:'t',teamMemberId:'m',eventSeq:2,type:'USER_MESSAGE_ACCEPTED',data:{sessionId:'old',messageId:'b',revision:1,content:'你好'}});
 assert.equal(c.teamSession.messages.length,2);assert.equal(c.chatOutbox.a,undefined);
});
test('overlapping wake cards and older partial output cannot duplicate or rewind snapshot',()=>{
 const c=fixture();c.mergeTeamMessage({kind:'TEAM_EVENT',eventType:'LIFECYCLE_EVENT',payload:{messageId:'wake',revision:10}});c.mergeTeamMessage({role:'ASSISTANT',messageId:'reply',revision:20,content:'complete reply'});
 for(const [seq,type,data] of [[1,'LIFECYCLE_EVENT',{messageId:'wake',revision:10}],[2,'MESSAGE_UPDATED',{messageId:'reply',revision:3,content:'com'}]])c.appendTeamSessionEvent({teamId:'t',teamMemberId:'m',eventSeq:seq,type,data});
 assert.equal(c.teamSession.messages.length,2);assert.equal(c.teamSession.messages[1].content,'complete reply');
});
test('snapshot reconnects from lower replay boundary and stale snapshots cannot change another member',async()=>{
 const c=fixture(),history=deferred();let connects=0;c.connectTeamSessionStream=()=>connects++;
 c.teamSessionPost=op=>op==='history'?history.promise:Promise.resolve({});
 const loading=c.loadTeamSessionSnapshot();assert.equal(c.teamSession.loading,true);
 history.resolve({sessionId:'new',replayAfter:7,messages:[{role:'USER',messageId:'u',content:'你好'}]});await loading;
 assert.equal(c.teamSession.lastSeq,7);assert.equal(c.teamSession.members[0].sessionId,'new');assert.equal(connects,1);
 const late=deferred();c.teamSessionPost=op=>op==='history'?late.promise:Promise.resolve({});const old=c.loadTeamSessionSnapshot();c.teamSession.teamMemberId='other';late.resolve({sessionId:'wrong',messages:[]});await old;assert.equal(c.teamSession.members[0].sessionId,'new');
});
test('a send response after switching members does not refresh the new conversation',async()=>{
 const c=fixture(),http=deferred();let loads=0;c.fetch=()=>http.promise;c.loadTeamSessionSnapshot=async()=>loads++;
 const sending=c.sendTeamSessionMessage();c.teamSession.teamMemberId='other';http.resolve({ok:true,json:async()=>({accepted:true,data:{accepted:true,data:{sessionId:'new'}}})});await sending;assert.equal(loads,0);
});
test('network failure remains visible and retry reuses exact request ID',async()=>{
 const c=fixture();const bodies=[];c.fetch=async(u,o)=>{bodies.push(o.body);throw Error('network')};c.loadTeamSessionSnapshot=async()=>{};
 await c.sendTeamSessionMessage();const entry=Object.values(c.chatOutbox)[0];assert.equal(entry.status,'unknown');await c.retryChatMessage(entry.id);assert.equal(bodies[0],bodies[1]);
});
test('accepted send stays visible while the new session snapshot is delayed',async()=>{
 const c=fixture(),snapshot=deferred();c.loadTeamSessionSnapshot=()=>snapshot.promise;
 c.fetch=async()=>({ok:true,json:async()=>({accepted:true,data:{accepted:true,data:{sessionId:'new'}}})});
 const sending=c.sendTeamSessionMessage();await new Promise(setImmediate);
 assert.match(c.pendingChatHtml(c.teamChatScope(),'old'),/你好/);
 snapshot.resolve();await sending;
});
test('attachments appear immediately and remain attached after acknowledgement',async()=>{
 const c=fixture(),http=deferred();c.teamSession.uploads=[{uploadId:'u',fileName:'photo.png'}];c.fetch=()=>http.promise;c.loadTeamSessionSnapshot=async()=>{};
 const sending=c.sendTeamSessionMessage(),entry=Object.values(c.chatOutbox)[0];assert.match(c.pendingChatHtml(c.teamChatScope(),'old'),/photo.png/);
 c.appendTeamSessionEvent({teamId:'t',teamMemberId:'m',eventSeq:1,type:'USER_MESSAGE_ACCEPTED',data:{sessionId:'old',messageId:entry.id,revision:1,content:'你好',attachments:[{fileName:'photo.png'}]}});
 http.resolve({ok:true,json:async()=>({accepted:true,data:{accepted:true,data:{sessionId:'old'}}})});await sending;
 assert.equal(Object.keys(c.chatOutbox).length,0);assert.equal(c.teamSession.messages[0].attachments[0].fileName,'photo.png');
});
test('definite rejection marks the existing bubble as failed',async()=>{
 for(const send of ['sendStarweaveMessage','sendTeamSessionMessage']){
  const c=fixture();c.fetch=async()=>({ok:false,json:async()=>({accepted:false,message:'busy'})});c.loadTeamSessionSnapshot=async()=>{};
  await c[send]();const entry=Object.values(c.chatOutbox)[0];assert.equal(entry.status,'failed');assert.match(c.pendingChatHtml(entry.scope,'old'),/发送失败：busy/);
 }
});
test('snapshot replay preserves distinct task cards and renders each event once',()=>{
 const c=fixture();vm.runInContext(source.slice(source.indexOf('function renderTeamSessionMessages('),source.indexOf('function handleTeamSessionScroll(')),c);
 c.renderChatViewport=(box,html)=>box.innerHTML=html;
 c.teamHistoryItemHtml=m=>'['+m.payload.eventId+']';
 c.teamSession.messages=[{payload:{eventId:'one'}},{payload:{eventId:'one'}},{payload:{eventId:'two'}}];
 c.renderTeamSessionMessages();assert.equal(c.document.getElementById('teamSessionMessages').innerHTML,'[one][two]');
});
test('disconnect reloads history before replay and ignores an old stream error',async()=>{
 const c=fixture();vm.runInContext('connectTeamSessionStream = '+source.match(/function connectTeamSessionStream\(\).*\n/)[0],c);
 const timers=[];c.setTimeout=fn=>timers.push(fn);c.EventSource=function(){this.addEventListener=()=>{};this.close=()=>{}};
 let loads=0;c.loadTeamSessionSnapshot=async()=>loads++;
 c.connectTeamSessionStream();const old=c.teamSession.stream;old.onerror();assert.equal(c.teamSession.stream,null);timers.shift()();assert.equal(loads,1);
 c.connectTeamSessionStream();const next=c.teamSession.stream;old.onerror();assert.equal(c.teamSession.stream,next);assert.equal(timers.length,0);
});
test('a failed history request reconnects instead of leaving the conversation frozen',async()=>{
 const c=fixture();let connects=0;c.connectTeamSessionStream=()=>connects++;c.teamSessionPost=async()=>{throw Error('network')};
 await c.loadTeamSessionSnapshot();assert.equal(c.teamSession.loading,false);assert.equal(connects,1);
});
test('server error cannot falsely claim that a potentially accepted send failed',async()=>{
 const c=fixture();c.fetch=async()=>({ok:false,status:504,json:async()=>({message:'timeout'})});c.loadTeamSessionSnapshot=async()=>{};
 await c.sendTeamSessionMessage();assert.equal(Object.values(c.chatOutbox)[0].status,'unknown');
});

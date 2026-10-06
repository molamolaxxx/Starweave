const {test}=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs');
const vm=require('node:vm');
const root='cmd-proxy-app/src/main/resources/configui/assets/js/';
function fixture(){
 const c={console,setTimeout,clearTimeout,curInstance:{instanceId:'remote'},activePage:'sessions',
  starSessions:{items:[{groupId:'g',sessionId:'s',generation:1}],selectedGroupId:'g',events:[],lastSeq:0,snapshotToken:0,transitionId:0},
  teamSession:{teamId:'t',teamMemberId:'m',members:[{teamMemberId:'m',sessionId:'ts'}],messages:[],liveItems:[],lastSeq:0,snapshotToken:0},
  document:{addEventListener(){}},showSnackbar(){},starEventRenderFrame:0,teamEventRenderFrame:0};
 vm.createContext(c);for(const file of ['chat-history.js','starweave.js'])vm.runInContext(fs.readFileSync(root+file,'utf8'),c);
 for(const name of ['renderStarweaveEvents','renderTeamSessionMessages','renderTeamSessionMembers','renderTeamSessionDetail','setTeamSessionOperation','connectStarweaveStream','connectTeamSessionStream','scheduleTeamSessionRender'])c[name]=()=>{};
 return c;
}
function deferred(){let resolve;const promise=new Promise(r=>resolve=r);return{promise,resolve}}
function response(events,extra={}){return {ok:true,json:async()=>({accepted:true,data:{events,replayAfter:200,hasMore:true,olderCursor:'cursor',...extra}})}}
test('first page uses visible-message limit and resumes SSE after hidden terminal events',async()=>{
 const c=fixture();let url;c.api=async u=>{url=u;return response([{eventSeq:151,type:'ASSISTANT_MESSAGE_DELTA',turnId:'turn',messageId:'assistant:151',payload:{text:'complete'}}])};
 await c.loadStarweaveSnapshot();assert.match(url,/limit=50/);assert.ok(!url.includes('after=0'));assert.equal(c.starSessions.lastSeq,200);assert.equal(c.starSessions.events.length,1);assert.equal(c.starSessions.history.cursor,'cursor');
});
test('older requests are coalesced, preserve replay position and reject stale session responses',async()=>{
 const c=fixture();c.api=async()=>response([{eventSeq:151}]);await c.loadStarweaveSnapshot();
 const pending=deferred();let calls=0;c.api=()=>{calls++;return pending.promise};
 const load=c.loadOlderStarweaveHistory();await c.loadOlderStarweaveHistory();assert.equal(calls,1);
 pending.resolve(response([{eventSeq:101}],{olderCursor:'older'}));await load;
 assert.equal(c.starSessions.events[0].eventSeq,101);assert.equal(c.starSessions.lastSeq,200);
 const stale=deferred();c.api=()=>stale.promise;const late=c.loadOlderStarweaveHistory();c.starSessions.items[0].sessionId='new';c.starSessions.events=[];
 stale.resolve(response([{eventSeq:1}]));await late;assert.equal(c.starSessions.events.length,0);
});
test('refreshing latest page retains loaded history without duplicating streamed assistant deltas',async()=>{
 const c=fixture();c.api=async()=>response([{eventSeq:151,type:'ASSISTANT_MESSAGE_DELTA',turnId:'turn',messageId:'assistant:151',payload:{text:'hello'}}],{replayAfter:151});
 await c.loadStarweaveSnapshot();c.starSessions.events.unshift({eventSeq:100,type:'USER_MESSAGE_ACCEPTED',payload:{content:'old'}});
 c.appendStarweaveEvent({groupId:'g',sessionId:'s',generation:1,eventSeq:152,type:'ASSISTANT_MESSAGE_DELTA',turnId:'turn',payload:{text:' world'}},true);
 c.api=async()=>response([{eventSeq:151,type:'ASSISTANT_MESSAGE_DELTA',turnId:'turn',messageId:'assistant:151',payload:{text:'hello world'}}],{replayAfter:152});
 await c.loadStarweaveSnapshot();const items=c.reduceStarweaveEvents(c.starSessions.events);assert.equal(items.length,2);assert.equal(items[1].text,'hello world');
});
test('team first page and stream do not wait for context or session listing',async()=>{
 const c=fixture();const metadata=deferred();let connected=0;c.connectTeamSessionStream=()=>connected++;
 c.teamSessionPost=async(action,body)=>action==='history'?{sessionId:'ts',messages:[{messageId:'u',role:'USER',content:'hi'}],replayAfter:200,hasMore:true,olderCursor:'older'}:metadata.promise;
 await c.loadTeamSessionSnapshot();assert.equal(c.teamSession.messages.length,1);assert.equal(connected,1);assert.equal(c.teamSession.loading,false);metadata.resolve({});
});
test('normalized reply segments keep their identities even when tool updates coalesce',()=>{
 const c=fixture();const items=c.reduceStarweaveEvents([
  {eventSeq:1,type:'TOOL_CALL_UPDATED',turnId:'t',messageId:'tool:one',payload:{toolCallId:'one'}},
  {eventSeq:2,type:'ASSISTANT_MESSAGE_DELTA',turnId:'t',messageId:'assistant:2',payload:{text:'before completion'}},
  {eventSeq:4,type:'ASSISTANT_MESSAGE_DELTA',turnId:'t',messageId:'assistant:4',payload:{text:'after completion'}},
  {eventSeq:5,type:'ASSISTANT_MESSAGE_DELTA',turnId:'t',payload:{text:' continued'}}]);
 assert.equal(items.length,3);assert.equal(items[2].text,'after completion continued');assert.equal(items[2].key,'assistant:4');
});
test('team older history cannot replace newer live revisions and completed chunks merge once',async()=>{
 const c=fixture();c.teamSession.messages=[{messageId:'reply',role:'ASSISTANT',content:'complete',revision:20}];
 c.prependTeamHistory([{messageId:'old',role:'USER',content:'earlier'},{messageId:'reply',role:'ASSISTANT',content:'partial',revision:2}]);
 assert.equal(c.teamSession.messages.length,2);assert.equal(c.teamSession.messages[1].content,'complete');
 c.teamSession.liveItems=[{kind:'assistant',text:'complete',eventSeq:199},{kind:'assistant',text:'next',eventSeq:201}];
 c.teamSessionPost=async()=>({messages:[],replayAfter:200});await c.refreshTeamSessionHistory();
 assert.equal(c.teamSession.liveItems.length,1);assert.equal(c.teamSession.liveItems[0].text,'next');
});

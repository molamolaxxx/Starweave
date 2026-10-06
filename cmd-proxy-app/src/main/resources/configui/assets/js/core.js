// 端口兜底值取当前页面实际端口，避免多环境下写回错误端口
function currentPort(){return parseInt(location.port)||10528}
var config={robots:[],chatterIds:[],channels:[],configUi:{enabled:true,port:currentPort()}};
var channelRuntime={instanceId:'',statuses:{},errors:{}};
var itemRefreshRuntime={robots:{},channels:{}};
var itemToggleRuntime={robots:{},channels:{}};
var teamSharingRuntime={instanceId:'',grants:[]};
var channelBindingTargets={instanceId:'',sessions:[],teams:[]};
var channelBindingTargetRequest=0;
var mcpAuthRuntime={servers:[],principals:[]};
var editIdx=-1;
var robotDialogMode='add';
var robotDialogSourceIndex=-1;
var channelDialogIndex=-1;
var channelDialogMode='add';
var channelDialogDraft=null;
var channelDialogSourceId='';
var channelKnownTargetsTimer=0;
var channelKnownTargetsRequest=0;
var channelMessages={archiveId:'',channelId:'',page:1,pageSize:20,total:0,totalPages:1,request:0,timer:0,advanced:false};
var externalTaskApiDialogIndex=-1;
var externalTaskApiDialogDraft=null;
var externalTaskApiTargets={agents:[],teams:[]};
var agentGatewayDialogIndex=-1;
var agentGatewayDialogDraft=null;
var agentGatewayRuntime={targets:[],status:{status:'STOPPED',connections:{}}};
var currentDirPath='';
var dirTargetInputId='dWorkDir';
var dlgSubAgents=[];
var dlgContacts=[];
var dlgTeamSharedOwners=[];
var providerReleaseCache={};
var resourceState={robotIndex:-1,kind:'',selectedId:'',nodes:[]};
var resourceDream={robot:'',available:false,running:false,pending:false,timer:null};
// 多环境：环境列表、当前选中环境、未保存标记
var instances=[];
var curInstance=null;
// 环境切换拥有页面锁；操作计数覆盖确认、提交和后续状态同步。
var environmentGate={switching:false,loading:false,recoveryRequired:false,target:null,operations:0,version:0,reads:new Set(),requests:new Set(),loadErrors:[]};
function environmentOperationPending(){return environmentGate.operations>0||Object.keys(itemRefreshRuntime.robots||{}).length>0||Object.keys(itemRefreshRuntime.channels||{}).length>0||Object.keys(itemToggleRuntime.robots||{}).length>0||Object.keys(itemToggleRuntime.channels||{}).length>0||(typeof registryBusy!=='undefined'&&registryBusy)}
function syncEnvironmentGate(){
var switching=environmentGate.switching,locked=switching||environmentGate.recoveryRequired,trigger=document.getElementById('envTrigger'),status=document.getElementById('envSwitchStatus');
if(trigger){trigger.disabled=switching||environmentOperationPending();trigger.setAttribute('aria-busy',String(switching));trigger.title=switching?'正在切换环境，请稍候':environmentOperationPending()?'当前操作完成后可切换环境':'当前环境：'+(curInstance?envName(curInstance):'')+'；点击切换运行环境';var icon=trigger.querySelector('.material-icons');if(icon)icon.textContent=switching?'sync':'dns'}
if(status){status.hidden=!locked;status.textContent=switching?'正在切换到 '+envName(environmentGate.target)+'…':environmentGate.recoveryRequired?'环境状态待确认，请重新选择环境':''}
document.body.classList.toggle('environment-switching',switching);
document.querySelectorAll('.app-layout,.dialog-overlay:not(#confirmDialog):not(#registryAccessDialog),.btn-fab,.app-bar-actions > .btn:not(#themeToggle):not(#sidebarToggle)').forEach(function(el){el.inert=locked});
}
function installEnvironmentGuards(){
// 在所有业务脚本加载后安装，保持既有全局入口和脚本加载顺序。
var operations='saveRobot deleteRobot toggleRobot refreshRobot applyRobotConfig saveConfig refreshService reloadService applyGlobalProxy addChatterId toggleChannelEnabled saveChannelDialog deleteChannel refreshChannel applyChannelConfig deleteExternalTaskApi deleteAgentGateway openStarweaveAgentSession startStarweaveSession newStarweaveSession restoreStarweaveSession deleteStarweaveSession cancelStarweaveSession uploadStarweaveFiles sendStarweaveMessage retryChatMessage deliverChatMessage runTeamBatchAction createStarweaveTeam saveStarweaveTeamEdit deleteStarweaveTeam newTeamSession restoreTeamSession cancelTeamSession uploadTeamSessionFiles sendTeamSessionMessage submitAgentImport triggerAgentMemoryDream installSelectedProviderVersion saveTask deleteTask updateTaskStatus submitTaskComment retryTaskDelivery uploadTaskFiles saveScheduleTask deleteScheduleTask saveMcpPolicy configureRegistry'.split(' ');
operations.push('saveObservation','deleteObservation','toggleObservation','testObservationDraft','retryObservationEvent');
operations.forEach(function(name){var original=window[name];if(typeof original!=='function')return;window[name]=async function(){if(environmentGate.switching||environmentGate.recoveryRequired){showSnackbar(environmentGate.switching?'环境正在切换，请稍候':'环境状态待确认，请重新选择环境');return false}environmentGate.operations+=1;syncEnvironmentGate();try{return await original.apply(this,arguments)}finally{environmentGate.operations-=1;syncEnvironmentGate()}}});
var reads='loadConfig loadInstances loadChannelStatus loadItemRefreshStatus loadTeamSharingStatus loadChannelBindingTargets loadMcpAuth loadRegistrySettings loadStarweaveSessions loadStarweaveTeams loadStarweaveSnapshot loadOlderStarweaveHistory loadTeamSessionSnapshot loadOlderTeamSessionHistory refreshTeamSessionHistory refreshTeamSessionContextUsage loadTaskCount loadTasks loadScheduleCount loadSchedules loadScheduleExecutions refreshChannelStatuses pollStarweaveTeamStates refreshChannelBindingTargets loadAgentGatewayRuntime loadRobotModels loadProviderVersions browseDir loadStarweaveResources previewStarweaveResource loadTeamSessionResources previewTeamSessionResource openAgentResource reloadAgentResource loadAgentResourceContent exportAgentResources refreshChannelKnownTargets checkAgentMemoryDream openTaskEdit loadTaskTargets loadMoreTaskComments loadMoreTaskHistory openTaskHistory viewTaskHistory openFileLinkPreview'.split(' ');
reads.push('loadObservations','loadObservationCount','loadObservationEvents','openObservationEditor','openObservationDetail','openObservationEventDetail');
reads.forEach(function(name){var original=window[name];if(typeof original!=='function')return;window[name]=function(){if(environmentGate.switching&&!environmentGate.loading)return Promise.resolve(false);var args=arguments,owner=this;var pending=Promise.resolve().then(function(){return original.apply(owner,args)});environmentGate.reads.add(pending);return pending.finally(function(){environmentGate.reads.delete(pending)})}});
}
var dirty=false;
var activePage='basic';
var externalChannelTab='wecom';
var listState={robot:{page:1,pageSize:5},channel:{page:1,pageSize:5},externalTaskApi:{page:1,pageSize:5}};
var starSessions={items:[],selectedGroupId:'',events:[],lastSeq:0,polling:false,stream:null,streamKey:'',uploads:[],followOutput:true,transitioning:false,transitionId:0,snapshotToken:0};
var starEventRenderFrame=0;
var starTeams={items:[],events:[],lastSeq:0,polling:false};
var teamBatchOperations=Object.create(null);
var starTeamDraft={sources:[],remarks:Object.create(null),memberIds:Object.create(null),mode:'NORMAL',captainKey:''};
var starTeamEditDraft={teamId:'',version:0,coordinated:false,members:[],sources:[],selected:Object.create(null),remarks:Object.create(null),captainId:''};
var teamSession={teamId:'',teamMemberId:'',acpClientId:'',mode:'NORMAL',captainTeamMemberId:'',members:[],messages:[],liveItems:[],sessions:[],uploads:[],lastSeq:0,followOutput:true,loading:false,stream:null,streamKey:'',snapshotToken:0};
var teamEventRenderFrame=0;
var fileLinkPreview={request:0,data:null,mode:'',context:null};
var taskStatuses=['START','IN_PROGRESS','COMPLETED','CANCELLED','SUSPENDED'];
var taskState={items:[],stats:{},page:1,pageSize:10,total:0,totalPages:1,status:'',query:'',assignee:'',createdFrom:'',createdTo:'',loading:false,listToken:0,countToken:0,detailToken:0,filterTimer:0,historyToken:0};
var taskEditor={mode:'create',task:null,originalContent:'',contentAttachments:[],commentAttachments:[],targets:{agents:[],teams:[]},history:[],historyNext:null,comments:[],commentsNext:null,editorMode:'edit'};
var scheduleState={items:[],stats:{},page:1,pageSize:10,total:0,totalPages:1,query:'',scope:'',type:'',status:'',token:0,timer:0,editing:null};
var scheduleExecutions={ownerPath:'',taskId:'',title:'',page:1,pageSize:10,total:0,totalPages:1,token:0};

function markDirty(){dirty=true}
function clearDirty(){dirty=false}

var sidebarTransitionTimer=0;
function updateSidebarToggle(collapsed,persist){
var button=document.getElementById('sidebarToggle');if(!button)return;button.setAttribute('aria-expanded',String(!collapsed));button.title=collapsed?'显示左侧菜单':'隐藏左侧菜单';
var icon=button.querySelector('.material-icons'),label=button.querySelector('.btn-text');if(icon)icon.textContent=collapsed?'menu':'menu_open';if(label)label.textContent=collapsed?'显示菜单':'隐藏菜单';
if(persist!==false)try{localStorage.setItem('starweave-sidebar-collapsed',collapsed?'1':'0')}catch(e){}
}
function setSidebarCollapsed(collapsed){
var layout=document.querySelector('.app-layout'),button=document.getElementById('sidebarToggle');if(!layout||!button)return;
clearTimeout(sidebarTransitionTimer);layout.classList.remove('sidebar-fading');layout.classList.toggle('sidebar-collapsed',collapsed);updateSidebarToggle(collapsed,true);
}
function toggleSidebar(){
var layout=document.querySelector('.app-layout');if(!layout)return;clearTimeout(sidebarTransitionTimer);var collapsed=layout.classList.contains('sidebar-collapsed');layout.classList.add('sidebar-fading');
if(collapsed){layout.classList.remove('sidebar-collapsed');syncStarweaveViewport();updateSidebarToggle(false,true);sidebarTransitionTimer=setTimeout(function(){layout.classList.remove('sidebar-fading')},280)}
else{updateSidebarToggle(true,true);sidebarTransitionTimer=setTimeout(function(){layout.classList.add('sidebar-collapsed');layout.classList.remove('sidebar-fading');syncStarweaveViewport()},180)}
}
function restoreSidebarState(){var collapsed=false;try{collapsed=localStorage.getItem('starweave-sidebar-collapsed')==='1'}catch(e){}setSidebarCollapsed(collapsed)}

function switchPage(page){
if(['basic','channels','acp','sessions','teams','tasks','schedules','observations','mcp-auth'].indexOf(page)<0)page='basic';
activePage=page;
document.body.classList.toggle('sessions-active',page==='sessions');
document.querySelectorAll('.page-section').forEach(function(el){el.classList.toggle('active',el.id==='page-'+page)});
document.querySelectorAll('.nav-item').forEach(function(el){el.classList.toggle('active',el.getAttribute('data-page')===page)});
document.querySelector('.btn-fab').style.display=page==='acp'?'flex':'none';
try{sessionStorage.setItem('configui-page',page)}catch(e){}
if(page==='sessions'){syncStarweaveViewport();loadStarweaveSessions(false)}else closeStarweaveStream();if(page==='teams'){loadStarweaveSessions(false);loadStarweaveTeams(false)}if(page==='tasks')loadTasks(false);if(page==='schedules')loadSchedules(false);if(page==='channels')refreshChannelBindingTargets(true);if(page==='channels'&&externalChannelTab==='gateway')loadAgentGatewayRuntime();
window.scrollTo({top:0,behavior:page==='sessions'?'auto':'smooth'});
if(page==='observations')loadObservations(false);
}

function switchExternalChannelTab(tab){
externalChannelTab=tab==='task'?'task':(tab==='gateway'?'gateway':'wecom');
var task=externalChannelTab==='task';
var gateway=externalChannelTab==='gateway',wecom=!task&&!gateway;
document.getElementById('externalChannelTabWecom').classList.toggle('active',wecom);
document.getElementById('externalChannelTabWecom').setAttribute('aria-selected',String(wecom));
document.getElementById('externalChannelTabTask').classList.toggle('active',task);
document.getElementById('externalChannelTabTask').setAttribute('aria-selected',String(task));
document.getElementById('externalChannelTabGateway').classList.toggle('active',gateway);
document.getElementById('externalChannelTabGateway').setAttribute('aria-selected',String(gateway));
document.getElementById('channelPanelWecom').classList.toggle('active',wecom);
document.getElementById('channelPanelWecom').hidden=!wecom;
document.getElementById('channelPanelExternalTask').classList.toggle('active',task);
document.getElementById('channelPanelExternalTask').hidden=!task;
document.getElementById('channelPanelGateway').classList.toggle('active',gateway);
document.getElementById('channelPanelGateway').hidden=!gateway;
if(task)renderExternalTaskApis();else if(gateway){renderAgentGateways();loadAgentGatewayRuntime()}else renderChannels();
}

function normalized(value){return String(value===undefined||value===null?'':value).toLowerCase().trim()}
function isAgentOperable(state){return state==='READY'||state==='SLEEP'}
function formatBytes(value){var bytes=Number(value)||0;if(bytes<1024)return bytes+' B';if(bytes<1024*1024)return (bytes/1024).toFixed(1)+' KiB';return (bytes/1024/1024).toFixed(1)+' MiB'}
function getSearchTerms(type){
var input=document.getElementById(type==='robot'?'robotSearch':'channelSearch');
return normalized(input&&input.value).split(/\s+/).filter(Boolean);
}
function matchGrade(value,term){
var text=normalized(value);if(!text)return 0;
if(text===term)return 1000;
if(text.indexOf(term)===0)return 600;
if(text.split(/[\s\/\\_.:@-]+/).some(function(token){return token.indexOf(term)===0}))return 350;
return text.indexOf(term)>=0?150:0;
}
function relevanceScore(fields,terms,primaryValue){
if(!terms.length)return 0;
var total=0;
for(var i=0;i<terms.length;i++){
var best=0;
fields.forEach(function(field){best=Math.max(best,matchGrade(field.value,terms[i])*field.weight)});
if(!best)return -1;
total+=best;
}
var query=terms.join(' '),primary=normalized(primaryValue);
if(primary===query)total+=100000;
else if(primary.indexOf(query)===0)total+=50000;
else if(primary.indexOf(query)>=0)total+=20000;
return total;
}
function robotSearchText(r){
var contacts=(r.contacts||[]).map(function(v){return [v.name,v.remark,v.chatterId].join(' ')}).join(' ');
var subs=(r.subAgents||[]).map(function(v){return [v.name,v.description].join(' ')}).join(' ');
var owners=(r.teamSharedWithChatterIds||[]).join(' ');
var roles=[r.onlySubAgent?'仅子agent sub agent':'',r.onlyTeamMember?'仅team member':'',(!r.onlySubAgent&&!r.onlyTeamMember)?'主acp main':''];
return [r.name,r.signature,r.workDir,r.agentProvider,r.model,r.dshAgentPreset,(r._dshProfileBundles||[]).join(' '),r.httpProxy,r.noProxy,contacts,subs,owners,roles.join(' ')].join(' ');
}
function channelSearchText(ch,i){
var b=ch.binding||{},runtimeId=ch._runtimeId||ch.id||'';
var status=(channelRuntime.statuses&&channelRuntime.statuses[runtimeId])||'STOPPED';
var error=(channelRuntime.errors&&channelRuntime.errors[runtimeId])||'';
var targets=(ch.knownChatTargets||[]).map(function(v){return [v.id,v.displayName,v.chatType].join(' ')}).join(' ');
var outbound=normalizeChannelOutboundTargets(ch).map(function(v){return [v.id,v.chatId,v.description].join(' ')}).join(' ');
return [ch.id,ch.type,ch.botId,ch.wsUrl,b.type,b.groupId,b.teamId,b.teamMemberId,status,error,targets,outbound,i].join(' ');
}
function pathBaseName(path){var parts=String(path||'').replace(/[\/\\]+$/,'').split(/[\/\\]+/);return parts.length?parts[parts.length-1]:''}
function robotSearchFields(r){
var contacts=(r.contacts||[]).map(function(v){return [v.name,v.remark,v.chatterId].join(' ')}).join(' ');
var subs=(r.subAgents||[]).map(function(v){return [v.name,v.description].join(' ')}).join(' ');
var owners=(r.teamSharedWithChatterIds||[]).join(' ');
var roles=[r.onlySubAgent?'仅子agent sub agent':'',r.onlyTeamMember?'仅team member':'',(!r.onlySubAgent&&!r.onlyTeamMember)?'主acp main':''].join(' ');
return [{value:r.name,weight:700},{value:pathBaseName(r.workDir),weight:110},{value:r.signature,weight:60},{value:r.workDir,weight:55},{value:r.agentProvider,weight:8},{value:r.model,weight:8},{value:r.dshAgentPreset,weight:8},{value:(r._dshProfileBundles||[]).join(' '),weight:6},{value:subs,weight:5},{value:owners,weight:5},{value:roles,weight:4},{value:contacts,weight:2},{value:r.httpProxy,weight:1},{value:r.noProxy,weight:1}];
}
function channelSearchFields(ch){
var b=ch.binding||{},runtimeId=ch._runtimeId||ch.id||'';
var status=(channelRuntime.statuses&&channelRuntime.statuses[runtimeId])||'STOPPED';
var error=(channelRuntime.errors&&channelRuntime.errors[runtimeId])||'';
var targets=(ch.knownChatTargets||[]).map(function(v){return [v.id,v.displayName,v.chatType].join(' ')}).join(' ');
var outbound=normalizeChannelOutboundTargets(ch).map(function(v){return [v.id,v.chatId,v.description].join(' ')}).join(' ');
return [{value:ch.id,weight:50},{value:ch.botId,weight:8},{value:b.groupId,weight:7},{value:b.teamId,weight:7},{value:b.teamMemberId,weight:7},{value:b.type,weight:6},{value:status,weight:6},{value:outbound,weight:6},{value:targets,weight:5},{value:ch.type,weight:4},{value:ch.wsUrl,weight:2},{value:error,weight:2}];
}
function filteredRobots(){
var terms=getSearchTerms('robot');
var status=document.getElementById('robotStatusFilter').value;
var provider=document.getElementById('robotProviderFilter').value;
return (config.robots||[]).map(function(item,index){return {item:item,index:index,score:relevanceScore(robotSearchFields(item),terms,item.name)}}).filter(function(entry){
var r=entry.item,statusMatch=status==='all'||(status==='enabled'&&r.enabled!==false)||(status==='disabled'&&r.enabled===false)||(status==='main'&&!r.onlySubAgent&&!r.onlyTeamMember)||(status==='sub'&&r.onlySubAgent)||(status==='team'&&r.onlyTeamMember);
return statusMatch&&(provider==='all'||r.agentProvider===provider)&&entry.score>=0;
}).sort(function(a,b){return b.score-a.score||a.index-b.index});
}
function filteredChannels(){
var terms=getSearchTerms('channel');
var filter=document.getElementById('channelStatusFilter').value;
return (config.channels||[]).map(function(item,index){return {item:item,index:index,score:relevanceScore(channelSearchFields(item),terms,item.id)}}).filter(function(entry){
var ch=entry.item,runtimeId=ch._runtimeId||ch.id||'',status=normalized((channelRuntime.statuses&&channelRuntime.statuses[runtimeId])||'STOPPED');
var statusMatch=filter==='all'||(filter==='running'&&(status.indexOf('running')>=0||status.indexOf('connected')>=0))||(filter==='stopped'&&status==='stopped')||(filter==='error'&&(status.indexOf('error')>=0||status.indexOf('failed')>=0))||(filter==='enabled'&&ch.enabled!==false)||(filter==='disabled'&&ch.enabled===false);
return statusMatch&&entry.score>=0;
}).sort(function(a,b){return b.score-a.score||a.index-b.index});
}
function pageEntries(type,entries){
var state=listState[type],totalPages=Math.max(1,Math.ceil(entries.length/state.pageSize));
if(state.page>totalPages)state.page=totalPages;
var start=(state.page-1)*state.pageSize;
return {items:entries.slice(start,start+state.pageSize),totalPages:totalPages,start:start};
}
function renderPagination(type,total,totalPages){
var state=listState[type],id=type==='robot'?'robotPagination':(type==='channel'?'channelPagination':'externalTaskApiPagination'),c=document.getElementById(id);
var summary=document.getElementById(type==='robot'?'robotSummary':(type==='channel'?'channelSummary':'externalTaskApiSummary'));
summary.textContent='共 '+total+' 条';
if(total<=state.pageSize){c.innerHTML='';return}
var from=Math.max(1,state.page-2),to=Math.min(totalPages,from+4);from=Math.max(1,to-4);
var buttons='<button class="page-btn" onclick="setListPage(\''+type+'\','+(state.page-1)+')" '+(state.page===1?'disabled':'')+'><span class="material-icons">chevron_left</span></button>';
for(var p=from;p<=to;p++)buttons+='<button class="page-btn'+(p===state.page?' active':'')+'" onclick="setListPage(\''+type+'\','+p+')">'+p+'</button>';
buttons+='<button class="page-btn" onclick="setListPage(\''+type+'\','+(state.page+1)+')" '+(state.page===totalPages?'disabled':'')+'><span class="material-icons">chevron_right</span></button>';
c.innerHTML='<div class="pagination"><span>第 '+state.page+' / '+totalPages+' 页</span><div class="page-buttons">'+buttons+'</div></div>';
}
function setListPage(type,page){listState[type].page=Math.max(1,page);if(type==='robot')renderRobots();else if(type==='channel')renderChannels();else renderExternalTaskApis();document.getElementById(type==='externalTaskApi'?'channelPanelExternalTask':'page-'+(type==='robot'?'acp':'channels')).scrollIntoView({behavior:'smooth'})}
function onListFilter(type){listState[type].page=1;var input=document.getElementById(type==='robot'?'robotSearch':'channelSearch');document.getElementById(type==='robot'?'robotSearchClear':'channelSearchClear').classList.toggle('show',!!input.value);type==='robot'?renderRobots():renderChannels()}
function clearListSearch(type){var input=document.getElementById(type==='robot'?'robotSearch':'channelSearch');input.value='';onListFilter(type);input.focus()}
function changePageSize(type){var select=document.getElementById(type==='robot'?'robotPageSize':(type==='channel'?'channelPageSize':'externalTaskApiPageSize'));listState[type].pageSize=parseInt(select.value)||5;listState[type].page=1;if(type==='robot')renderRobots();else if(type==='channel')renderChannels();else renderExternalTaskApis()}

/** 统一请求入口：自动携带当前环境标识，非本环境时由后端转发到目标环境 */
async function api(path,opts,local){
if(environmentGate.switching&&!environmentGate.loading)throw new Error('环境正在切换');
if(environmentGate.switching&&opts&&opts.method&&opts.method!=='GET')throw new Error('环境正在切换');
if(environmentGate.recoveryRequired&&opts&&opts.method&&opts.method!=='GET')throw new Error('环境状态待确认，请重新选择环境');
var version=environmentGate.version,controller=new AbortController(),external=opts&&opts.signal;
var mutation=opts&&opts.method&&opts.method!=='GET'&&path.indexOf('/file-preview')<0;
var readTimeout=!mutation&&path.indexOf('/api/agent-resources/export')!==0?setTimeout(function(){controller.abort()},15000):null;
function abort(){controller.abort()}
if(external){if(external.aborted)abort();else external.addEventListener('abort',abort,{once:true})}
environmentGate.requests.add(controller);
var url=path;
if(!local&&curInstance&&curInstance.instanceId&&!/[?&]instance=/.test(path)){
url+=(path.indexOf('?')>=0?'&':'?')+'instance='+encodeURIComponent(curInstance.instanceId);
}
var required=environmentGate.switching&&environmentGate.loading&&(
['/api/starweave/v1/sessions','/api/starweave/v1/teams','/api/item-refresh-status','/api/channels/status'].indexOf(path)>=0||
(activePage==='tasks'&&path.indexOf('/api/starweave/v1/tasks')===0)||
(activePage==='schedules'&&path.indexOf('/api/schedules/v1')===0)||
(activePage==='observations'&&path.indexOf('/api/observations/v1')===0)||
(activePage==='mcp-auth'&&path.indexOf('/api/mcp-auth/v1')===0));
try{var response=await fetch(url,Object.assign({},opts,{signal:controller.signal}));
// 读取正文也属于请求生命周期，避免响应头到达后仍留下旧环境的数据回调。
var body=await response.arrayBuffer();if(version!==environmentGate.version)throw new Error('环境已切换');
if(mutation&&response.status>=500){environmentGate.recoveryRequired=true;syncEnvironmentGate()}
if(required){var result;try{result=JSON.parse(new TextDecoder().decode(body))}catch(e){throw new Error('目标环境数据无效')}if(!response.ok||result.accepted===false)throw new Error(result.message||'目标环境数据加载失败（HTTP '+response.status+'）')}
return new Response(response.status===204||response.status===205||response.status===304?null:body,{status:response.status,statusText:response.statusText,headers:response.headers});
}catch(e){if(required)environmentGate.loadErrors.push(e);if(mutation){environmentGate.recoveryRequired=true;syncEnvironmentGate()}throw e}
finally{clearTimeout(readTimeout);environmentGate.requests.delete(controller);if(external)external.removeEventListener('abort',abort)}
}

function envName(inst){
if(inst.displayName)return inst.displayName;
var h=(inst.home||'').replace(/[\/\\]+$/,'');
var i=Math.max(h.lastIndexOf('/'),h.lastIndexOf('\\'));
return i>=0?h.substring(i+1):h;
}

async function loadInstances(manual){
if(environmentGate.switching)return;
try{
var r=await api('/api/instances',null,true);
instances=await r.json();
}catch(e){instances=[];showSnackbar('环境列表加载失败:'+e.message)}
if(!instances.length){renderEnvTabs();return}
var want=(location.hash.match(/instance=([^&]+)/)||[])[1];
want=want?decodeURIComponent(want):(curInstance&&curInstance.instanceId);
var found=instances.filter(function(i){return i.instanceId===want})[0];
if(!curInstance)curInstance=found||instances.filter(function(i){return i.self})[0]||instances[0];
else if(found)curInstance=found;
renderEnvTabs();
if(manual)showSnackbar('环境列表已刷新（'+instances.length+' 个）');
}

function setEnvMenuOpen(open){
var menu=document.getElementById('envMenu'),trigger=document.getElementById('envTrigger');if(!menu||!trigger)return;
menu.classList.toggle('open',open);trigger.setAttribute('aria-expanded',String(open));
if(open){menu.style.transform='';var bounds=menu.getBoundingClientRect(),shift=0;if(bounds.right>window.innerWidth-10)shift=window.innerWidth-10-bounds.right;if(bounds.left+shift<10)shift=10-bounds.left;menu.style.transform='translateX('+shift+'px)'}
}
function toggleEnvMenu(){if(environmentGate.switching||environmentOperationPending()){showSnackbar(environmentGate.switching?'环境正在切换，请稍候':'当前操作完成后可切换环境');return}setEnvMenuOpen(!document.getElementById('envMenu').classList.contains('open'))}
document.addEventListener('click',function(event){var picker=document.getElementById('envPicker'),menu=document.getElementById('envMenu');if(picker&&!picker.contains(event.target)&&menu&&!menu.contains(event.target))setEnvMenuOpen(false)});
document.addEventListener('keydown',function(event){if(event.key==='Escape'&&document.getElementById('envMenu').classList.contains('open')){setEnvMenuOpen(false);document.getElementById('envTrigger').focus()}});
window.addEventListener('resize',function(){var menu=document.getElementById('envMenu');if(menu&&menu.classList.contains('open'))setEnvMenuOpen(true)});

function renderEnvTabs(){
var c=document.getElementById('envTabs'),trigger=document.getElementById('envTrigger');
if(!instances.length){c.innerHTML='<div class="env-tab"><div class="env-tab-name">环境列表不可用</div></div>';trigger.title='环境列表不可用';return}
c.innerHTML=instances.map(function(inst,index){
var active=curInstance&&inst.instanceId===curInstance.instanceId;
var cls='env-tab'+(active?' active':'');
var badge=inst.remote?'<span class="env-badge">'+(inst.online===false?'离线':'远程')+'</span>':inst.self?'<span class="env-badge">本机</span>'
:(inst.configUiPort>0?'':'<span class="env-badge ro">只读</span>');
var meta=inst.remote?(inst.online===false?'目标环境离线':(inst.sourceInstanceId||'已连接')):((inst.configUiPort>0?':'+inst.configUiPort:'配置页未开启')+' · '+((inst.robotNames&&inst.robotNames.length)||0)+' robots');
return '<button type="button" class="'+cls+'" onclick="switchInstance(instances['+index+'].instanceId)" title="'+esc(inst.home)+'"'+(active?' aria-current="true"':'')+'>'
+'<span class="env-tab-name">'+esc(envName(inst))+badge+'</span>'
+'<span class="env-tab-meta">'+esc(meta)+'</span></button>';
}).join('');
trigger.title='当前环境：'+envName(curInstance)+'；点击切换运行环境';
syncEnvironmentGate();
}

async function switchInstance(id){
if(environmentGate.switching||environmentOperationPending()){showSnackbar(environmentGate.switching?'环境正在切换，请稍候':'当前操作完成后可切换环境');return}
if(environmentGate.recoveryRequired&&curInstance&&id!==curInstance.instanceId){showSnackbar('请先重新选择当前环境，确认环境状态');return}
if(curInstance&&curInstance.instanceId===id&&!environmentGate.recoveryRequired){setEnvMenuOpen(false);document.getElementById('envTrigger').focus();return}
var target=instances.filter(function(i){return i.instanceId===id})[0];
if(!target)return;
if(target.remote&&target.online===false){showSnackbar('目标环境离线');return}
environmentGate.switching=true;environmentGate.target=target;setEnvMenuOpen(false);syncEnvironmentGate();
var previous=curInstance,previousConfig=config,previousDirty=dirty,committed=false,previousState;
try{
if(dirty&&!await showConfirm('当前环境有未保存的修改，切换环境后这些修改将丢失。',{title:'放弃未保存的修改？',confirmText:'放弃并切换',danger:true}))return;
if(target.remote&&!await ensureRegistryEnvironmentAccess(id))return;
environmentGate.requests.forEach(function(controller){controller.abort()});
await Promise.allSettled(Array.from(environmentGate.reads));
if(environmentOperationPending())throw new Error('原环境仍有操作正在执行，请稍后重试');
var controller=new AbortController(),timeout=setTimeout(function(){controller.abort()},15000),nextConfig;
try{var response=await fetch('/api/config?instance='+encodeURIComponent(id),{signal:controller.signal});if(!response.ok)throw new Error('目标环境不可用（HTTP '+response.status+'）');nextConfig=await response.json();if(!nextConfig||typeof nextConfig!=='object'||Array.isArray(nextConfig))throw new Error('目标环境配置无效')}
finally{clearTimeout(timeout)}
environmentGate.version+=1;
registryLoadToken+=1;
previousState={channelRuntime:channelRuntime,itemRefreshRuntime:itemRefreshRuntime,itemToggleRuntime:itemToggleRuntime,teamSharingRuntime:teamSharingRuntime,channelBindingTargets:channelBindingTargets,mcpAuthRuntime:mcpAuthRuntime,starSessions:starSessions,starTeams:starTeams,teamSession:teamSession,taskState:taskState,taskEditor:taskEditor};
clearTimeout(taskState.filterTimer);clearTimeout(scheduleState.timer);clearTimeout(channelMessages.timer);clearTimeout(resourceDream.timer);resourceDream.robot='';fileLinkPreview.request+=1;
previousState.observationState=observationState;resetObservationState();
document.body.classList.remove('resource-modal-open');
document.querySelectorAll('.dialog-overlay.show').forEach(function(el){closeDialog(el.id)});
setEnvMenuOpen(false);
document.getElementById('envTrigger').focus();
closeStarweaveStream();closeTeamSessionStream();closeDialog('channelMessagesDialog');closeDialog('channelDialog');starSessions={items:[],selectedGroupId:'',events:[],lastSeq:0,polling:false,stream:null,streamKey:'',uploads:[],followOutput:true,transitioning:false,transitionId:0,snapshotToken:0};
starTeams={items:[],events:[],lastSeq:0};
teamSession={teamId:'',teamMemberId:'',acpClientId:'',members:[],messages:[],liveItems:[],sessions:[],uploads:[],lastSeq:0,followOutput:true,loading:false,stream:null,streamKey:'',snapshotToken:0};
taskState={items:[],stats:{},page:1,pageSize:10,total:0,totalPages:1,status:'',query:'',assignee:'',createdFrom:'',createdTo:'',loading:false,listToken:0,countToken:0,detailToken:0,filterTimer:0,historyToken:0};
taskEditor={mode:'create',task:null,originalContent:'',contentAttachments:[],commentAttachments:[],targets:{agents:[],teams:[]},history:[],historyNext:null,comments:[],commentsNext:null,editorMode:'edit'};
channelBindingTargetRequest+=1;channelBindingTargets={instanceId:id,sessions:[],teams:[]};
curInstance=target;
channelRuntime={instanceId:id,statuses:{},errors:{}};itemRefreshRuntime={robots:{},channels:{}};itemToggleRuntime={robots:{},channels:{}};
committed=true;environmentGate.loading=true;environmentGate.loadErrors=[];
await loadConfig(nextConfig);
if(activePage==='tasks')await loadTasks(false);if(activePage==='schedules')await loadSchedules(false);
if(activePage==='observations')await loadObservations(false);
if(environmentGate.loadErrors.length)throw environmentGate.loadErrors[0];
environmentGate.recoveryRequired=false;
location.hash='instance='+encodeURIComponent(id);
renderEnvTabs();
showSnackbar('已切换到 '+envName(target));
}catch(e){if(committed){
closeStarweaveStream();closeTeamSessionStream();curInstance=previous;environmentGate.version+=1;
channelRuntime=previousState.channelRuntime;itemRefreshRuntime=previousState.itemRefreshRuntime;itemToggleRuntime=previousState.itemToggleRuntime;teamSharingRuntime=previousState.teamSharingRuntime;channelBindingTargets=previousState.channelBindingTargets;mcpAuthRuntime=previousState.mcpAuthRuntime;
starSessions=previousState.starSessions;starTeams=previousState.starTeams;teamSession=previousState.teamSession;taskState=previousState.taskState;taskEditor=previousState.taskEditor;
observationState=previousState.observationState;resetObservationTokens();
environmentGate.loading=true;environmentGate.loadErrors=[];await loadConfig(previousConfig);dirty=previousDirty;if(activePage==='tasks')await loadTasks(false);if(activePage==='schedules')await loadSchedules(false);if(activePage==='observations')await loadObservations(false);environmentGate.recoveryRequired=environmentGate.loadErrors.length>0;
}showSnackbar('切换到 '+envName(target)+' 失败，仍在原环境：'+(e.name==='AbortError'?'连接超时':e.message))}
finally{environmentGate.loading=false;environmentGate.switching=false;environmentGate.target=null;renderEnvTabs();syncEnvironmentGate();document.getElementById('envTrigger').focus()}
}

async function loadConfig(prefetched){
try{if(prefetched)config=prefetched;else{var r=await api('/api/config');if(!r.ok)throw new Error('目标环境不可用（HTTP '+r.status+'）');config=await r.json()}
if(!config.robots)config.robots=[];
config.robots.forEach(function(robot){if(robot.onlyTeamMember===undefined)robot.onlyTeamMember=false});
if(!config.chatterIds)config.chatterIds=[];
if(!config.channels)config.channels=[];
config.channels.forEach(function(channel){normalizeChannelOutboundTargets(channel);if(channel.binding&&channel.binding.type==='TEAM_MEMBER'&&!channel.binding.teamMemberSelection)channel.binding.teamMemberSelection='FIXED'});
if(!config.externalTaskApis)config.externalTaskApis=[];
if(!config.agentGateways)config.agentGateways=[];
if(!config.agentGatewayServer)config.agentGatewayServer={enabled:false,bindHost:'127.0.0.1',port:10529,maxConnections:100,eventRetentionDays:7};
if(!config.configUi)config.configUi={enabled:true,port:currentPort()};
}catch(e){showSnackbar('加载失败:'+e.message)}
await loadChannelStatus();
await loadItemRefreshStatus(false);
await loadTeamSharingStatus();
await loadChannelBindingTargets();
await loadMcpAuth();
clearDirty();
render();
await loadRegistrySettings(true);
await Promise.all([loadStarweaveSessions(false),loadStarweaveTeams(false),loadTaskCount()]);
await loadScheduleCount();
await loadObservationCount();
}

async function loadChannelStatus(){
try{var r=await api('/api/channels/status');if(r.ok)channelRuntime=await r.json()}catch(e){channelRuntime={instanceId:(curInstance&&curInstance.instanceId)||'',statuses:{},errors:{}}}
}

function itemRefreshPending(type,key){return !!((itemRefreshRuntime[type]||{})[key])}
function itemOperationPending(type,key){return itemRefreshPending(type,key)||!!((itemToggleRuntime[type]||{})[key])}
function itemRefreshMessage(type,key){var started=Number((itemRefreshRuntime[type]||{})[key])||Number((itemToggleRuntime[type]||{})[key])||Date.now(),seconds=Math.max(0,Math.floor((Date.now()-started)/1000));return (type==='robots'?'智能体「':'消息渠道「')+key+'」正在刷新'+(seconds?'（已等待 '+seconds+' 秒）':'')+'，请稍候'}
function markItemRefreshing(type,key){if(!itemRefreshRuntime[type])itemRefreshRuntime[type]={};itemRefreshRuntime[type][key]=Date.now();if(type==='robots')renderRobots();else renderChannels()}
function setItemTogglePending(type,key,pending){if(!itemToggleRuntime[type])itemToggleRuntime[type]={};if(pending)itemToggleRuntime[type][key]=Date.now();else delete itemToggleRuntime[type][key];if(type==='robots')renderRobots();else renderChannels()}
async function loadItemRefreshStatus(renderChanged){
try{var r=await api('/api/item-refresh-status');if(!r.ok)return;var next=await r.json();next.robots=next.robots||{};next.channels=next.channels||{};var changed=JSON.stringify(itemRefreshRuntime)!==JSON.stringify(next);itemRefreshRuntime=next;if(changed&&renderChanged!==false){if(activePage==='acp')renderRobots();if(activePage==='channels'&&externalChannelTab==='wecom')renderChannels()}}
catch(e){}
}

async function loadTeamSharingStatus(){
try{var r=await api('/api/team/sharing-status');if(r.ok)teamSharingRuntime=await r.json()}catch(e){teamSharingRuntime={instanceId:(curInstance&&curInstance.instanceId)||'',grants:[]}}
if(!teamSharingRuntime.grants)teamSharingRuntime.grants=[];
}

async function loadChannelBindingTargets(){
var request=++channelBindingTargetRequest,instanceId=(curInstance&&curInstance.instanceId)||'';
try{var r=await api('/api/channels/binding-targets');if(!r.ok)throw new Error('HTTP '+r.status);var next=await r.json();if(request!==channelBindingTargetRequest||instanceId!==((curInstance&&curInstance.instanceId)||''))return false;channelBindingTargets=next}
catch(e){if(request===channelBindingTargetRequest&&instanceId===((curInstance&&curInstance.instanceId)||''))return false;return false}
if(!channelBindingTargets.sessions)channelBindingTargets.sessions=[];
if(!channelBindingTargets.teams)channelBindingTargets.teams=[];
return true;
}
function channelBindingTargetFingerprint(value){value=value||{};return JSON.stringify({instanceId:value.instanceId||'',sessions:value.sessions||[],teams:value.teams||[]})}
async function refreshChannelBindingTargets(notify){var before=channelBindingTargetFingerprint(channelBindingTargets),updated=await loadChannelBindingTargets(),changed=before!==channelBindingTargetFingerprint(channelBindingTargets);if(updated&&changed&&activePage==='channels')renderChannels();if(!updated&&notify)showSnackbar('刷新信道绑定目标失败，请稍后重试');return updated}

function render(){
document.getElementById('cfgPort').value=config.configUi.port||currentPort();
document.getElementById('cfgEnabled').checked=config.configUi.enabled!==false;
document.getElementById('globalProxy').checked=isAllConfiguredProxiesEnabled();
document.getElementById('robotCount').textContent=config.robots.length;
document.getElementById('channelCount').textContent=config.channels.length+config.externalTaskApis.length+config.agentGateways.length;
renderProviderOptions();
renderChatters();renderChannels();renderExternalTaskApis();renderAgentGateways();renderRobots();
renderMcpAuth();
}

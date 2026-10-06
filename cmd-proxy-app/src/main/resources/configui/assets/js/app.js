installEnvironmentGuards();
(async function init(){
restoreSidebarState();
await loadInstances(false);
await loadConfig();
var savedPage='basic';try{savedPage=sessionStorage.getItem('configui-page')||'basic'}catch(e){}
switchPage(savedPage);
setInterval(function(){if(!environmentGate.switching)refreshChannelStatuses()},5000);
setInterval(function(){if(environmentGate.switching)return;loadInstances(false);if(activePage==='basic'&&!registryBusy)loadRegistrySettings(false)},5000);
setInterval(function(){if(!environmentGate.switching){loadItemRefreshStatus(true);syncEnvironmentGate()}},1000);
setInterval(function(){if(!environmentGate.switching&&activePage==='sessions')loadStarweaveSessions(false)},5000);
setInterval(function(){if(!environmentGate.switching&&activePage==='channels')refreshChannelBindingTargets(false)},5000);
setInterval(function(){if(!environmentGate.switching)pollStarweaveTeamStates()},2000);
syncUpdateButtonStatus();
setInterval(syncUpdateButtonStatus,1000);

})();

var lastUpdateStatus='idle';
function setUpdateButtonState(disabled,text){
var btn=document.getElementById('updateBtn');if(!btn)return;
btn.disabled=disabled;btn.style.opacity=disabled?'0.5':'1';
var label=btn.querySelector('.btn-text');if(label)label.textContent=text;
}

async function syncUpdateButtonStatus(){
try{
var r=await api('/api/update-jar/status');var d=await r.json();
if(d.status==='checking')setUpdateButtonState(true,'正在检查最新版本');
else if(d.status==='downloading'){
setUpdateButtonState(true,'正在下载最新版本');
if(d.automatic&&lastUpdateStatus!=='downloading')showSnackbar('检测到新版本，正在自动下载最新版本');
}else setUpdateButtonState(false,d.restartRequired?'检查更新（重启后生效）':'检查更新');
lastUpdateStatus=d.status||'idle';
}catch(e){}
}

async function updateVersion(){
setUpdateButtonState(true,'正在检查最新版本');
try{
var r=await api('/api/update-jar',{method:'POST'});
var d=await r.json();
if(d.error){showSnackbar(d.error);syncUpdateButtonStatus();return}
showDialog('updateOverlay');
document.getElementById('updateBar').style.width='0%';
document.getElementById('updatePct').textContent='0%';
document.getElementById('updateMsg').textContent='正在检查最新版本...';
document.getElementById('updateBar').style.background='#625bd8';
pollUpdateStatus();
}catch(e){showSnackbar('请求失败:'+e.message);setUpdateButtonState(false,'检查更新')}
}

function pollUpdateStatus(){
var timer=setInterval(async function(){
try{
var r=await api('/api/update-jar/status');
var d=await r.json();
document.getElementById('updateBar').style.width=d.progress+'%';
document.getElementById('updatePct').textContent=d.progress+'%';
document.getElementById('updateMsg').textContent=d.message||'';
if(d.status==='done'||d.status==='latest'){
clearInterval(timer);
document.getElementById('updateBar').style.background='#1f9d70';
document.getElementById('updateMsg').textContent=d.message||
(d.status==='latest'?'当前已是最新版本':'更新完成，重启后生效');
setTimeout(function(){
closeDialog('updateOverlay');
setUpdateButtonState(false,d.restartRequired?'检查更新（重启后生效）':'检查更新');
},3000);
}else if(d.status==='error'){
clearInterval(timer);
document.getElementById('updateBar').style.background='#d95565';
document.getElementById('updateMsg').textContent=d.message||'更新失败';
setTimeout(function(){
closeDialog('updateOverlay');
setUpdateButtonState(false,'检查更新');
},3000);
}
}catch(e){clearInterval(timer);closeDialog('updateOverlay');
setUpdateButtonState(false,'检查更新')}
},500);
}

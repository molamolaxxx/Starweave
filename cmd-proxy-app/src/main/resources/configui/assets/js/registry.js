/* 注册配置独立保存，业务请求仍通过 api() 跟随当前环境。 */
var registryState={serverEnabled:false,clientEnabled:false,tunnelPort:10530},registryBusy=false,registryLoadToken=0;
function registryStatusLabel(status){return {STOPPED:'未启用',STARTING:'启动中',RUNNING:'运行中',UNREGISTERED:'未注册',CONNECTING:'连接中',REGISTERED:'已注册',ERROR:'连接异常'}[status]||'状态更新中'}
function registryFriendlyError(error,status){
var message=typeof error==='string'?error:error&&error.message;
if(error&&error.name==='AbortError')return '操作等待超时，请检查网络后重试';
if(status===401||status===403)return '中心暂时不允许接入，请确认中心已启用并检查访问设置';
if(status===404)return '未找到注册服务，请确认填写的是中心管理页面的地址和端口';
if(status===502||status===504)return '暂时无法连接目标环境，请检查网络并确认环境在线';
if(message&&message.length<=160&&!/[a-zA-Z]/.test(message))return message;
return '操作未完成，请检查网络和中心地址后重试';
}
async function registryRequest(options){
var controller=new AbortController(),timer=setTimeout(function(){controller.abort()},15000);
try{
var response=await api('/api/registry/settings',Object.assign({},options,{signal:controller.signal})),result;
try{result=await response.json()}catch(e){throw new Error(registryFriendlyError(null,response.status))}
if(!response.ok)throw new Error(registryFriendlyError(result.error,response.status));
return result;
}finally{clearTimeout(timer)}
}
function renderRegistryStatus(){
var client=document.getElementById('registryClientStatus'),server=document.getElementById('registryServerStatus');
if(client){client.textContent=registryState.clientError?registryFriendlyError(registryState.clientError):registryStatusLabel(registryState.clientStatus);client.className='toggle-desc'+(registryState.clientError?' channel-error':'')}
if(server){server.textContent=registryState.serverError?registryFriendlyError(registryState.serverError):registryStatusLabel(registryState.serverStatus);server.className='toggle-desc'+(registryState.serverError?' channel-error':'')}
[['registryRegister','注册当前环境'],['registryCancel',registryState.clientEnabled?'取消注册':'当前环境尚未注册，无需取消'],['registryPasswordSave','保存环境密码'],['registryPasswordClear','移除环境密码'],['registryServerApply','应用中心设置'],['registryServerEnabled','启用或关闭注册中心']].forEach(function(action){
var control=document.getElementById(action[0]);if(!control)return;
// 保留点击入口，用明确反馈解释当前为什么不能执行；处理中保持原色并使用等待光标。
control.disabled=false;control.style.cursor=registryBusy?'wait':'';
control.title=registryBusy?'正在处理，请稍候':action[1];
control.setAttribute('aria-disabled',String(registryBusy||(action[0]==='registryCancel'&&!registryState.clientEnabled)));
});
}
function registryActionAvailable(){if(registryBusy){showSnackbar('正在处理上一次操作，请稍候');return false}return true}
async function loadRegistrySettings(fill){
var token=++registryLoadToken,environment=(curInstance&&curInstance.instanceId)||'';
try{var next=await registryRequest();
if(token!==registryLoadToken||environment!==((curInstance&&curInstance.instanceId)||''))return;
registryState=next;
if(fill){document.getElementById('registryCenterUrl').value=next.centerUrl||'';document.getElementById('registryDisplayName').value=next.displayName||'';document.getElementById('registryAccessPassword').value='';document.getElementById('registryAccessPassword').placeholder=next.accessPasswordSet?'已设置，输入新密码可修改':'未设置（可选）';document.getElementById('registryTunnelPort').value=next.tunnelPort||10530;document.getElementById('registryServerEnabled').checked=!!next.serverEnabled}
renderRegistryStatus();
}catch(e){if(fill&&token===registryLoadToken&&environment===((curInstance&&curInstance.instanceId)||'')){registryState.clientError='设置加载失败，请检查网络后重新打开此页面';renderRegistryStatus();showSnackbar(registryState.clientError)}}
}
async function configureRegistry(values){
if(!registryActionAvailable())return;
registryBusy=true;renderRegistryStatus();var environment=(curInstance&&curInstance.instanceId)||'';
try{var result=await registryRequest({method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(values)});
if(environment!==((curInstance&&curInstance.instanceId)||''))return;
registryState=result;showSnackbar(Object.prototype.hasOwnProperty.call(values,'accessPassword')?(values.accessPassword?'环境密码已保存，已有登录需重新验证':'已移除环境密码'):values.clientEnabled===true?'注册设置已保存，正在连接中心':values.clientEnabled===false?'已取消注册':values.serverEnabled?'中心设置已保存，正在准备接入服务':'已关闭注册中心');
await loadRegistrySettings(true);
}catch(e){if(environment===((curInstance&&curInstance.instanceId)||'')){showSnackbar(registryFriendlyError(e));document.getElementById('registryServerEnabled').checked=!!registryState.serverEnabled}}
finally{registryBusy=false;renderRegistryStatus()}
}
function registerEnvironment(){
if(!registryActionAvailable())return;
var center=document.getElementById('registryCenterUrl').value.trim(),name=document.getElementById('registryDisplayName').value.trim();
if(!center){showSnackbar('请先填写中心管理页面的地址，例如 192.168.1.10:10528');return}
try{var address=new URL(center.indexOf('://')>=0?center:'http://'+center);if(!/^https?:$/.test(address.protocol)||!address.hostname||address.username||address.password||address.search||address.hash||(address.pathname&&address.pathname!=='/')||address.port==='0')throw new Error()}catch(e){showSnackbar('中心地址格式不正确，请填写管理页面的地址和端口');return}
if(name.length>120){showSnackbar('环境名称过长，请控制在 120 个字符以内');return}
var values={clientEnabled:true,centerUrl:center,displayName:name},password=document.getElementById('registryAccessPassword').value;if(password)values.accessPassword=password;
return configureRegistry(values);
}
function unregisterEnvironment(){
if(!registryActionAvailable())return;
if(!registryState.clientEnabled){showSnackbar('当前环境尚未注册，无需取消');return}
return configureRegistry({clientEnabled:false});
}
function configureRegistryServer(){
if(!registryActionAvailable()){document.getElementById('registryServerEnabled').checked=!!registryState.serverEnabled;return}
var port=Number(document.getElementById('registryTunnelPort').value);
if(!Number.isInteger(port)||port<1||port>65535){showSnackbar('请填写 1～65535 之间的隧道端口');document.getElementById('registryServerEnabled').checked=!!registryState.serverEnabled;return}
return configureRegistry({serverEnabled:document.getElementById('registryServerEnabled').checked,tunnelPort:port});
}

function configureRegistryPassword(clear){
if(!registryActionAvailable())return;
var password=document.getElementById('registryAccessPassword').value;
if(clear&&!registryState.accessPasswordSet){showSnackbar('此环境尚未设置密码，无需移除');return}
if(!clear&&!password){showSnackbar('请先填写要设置的环境密码');return}
return configureRegistry({accessPassword:clear?'':password});
}
var registryAccessChecks={},registryAccessVerifiedUntil={},registryAccessPrompt=null;
var registryRawFetch=typeof window!=='undefined'&&window.fetch?window.fetch.bind(window):null;
async function registryAccessRequest(id,options){
var controller=new AbortController(),timer=setTimeout(function(){controller.abort()},15000);
try{return await registryRawFetch('/api/registry/access?instance='+encodeURIComponent(id),Object.assign({},options,{credentials:'same-origin',signal:controller.signal}))}finally{clearTimeout(timer)}
}
function ensureRegistryEnvironmentAccess(id,force){
if(!id||id.indexOf('remote-')!==0)return Promise.resolve(true);
if(!force&&registryAccessVerifiedUntil[id]>Date.now())return Promise.resolve(true);
if(registryAccessChecks[id])return registryAccessChecks[id];
var check=(async function(){
try{var response=await registryAccessRequest(id),state=await response.json();if(!response.ok){showSnackbar(registryFriendlyError(state.error,response.status));return false}
if(state.authenticated){registryAccessVerifiedUntil[id]=Math.min(Date.now()+30000,state.expiresAt>0?state.expiresAt:Infinity);return true}
delete registryAccessVerifiedUntil[id];
var allowed=await promptRegistryEnvironmentAccess(id);if(allowed)registryAccessVerifiedUntil[id]=Date.now()+30000;return allowed;
}catch(e){showSnackbar(registryFriendlyError(e));return false}
})();
registryAccessChecks[id]=check;
return check.finally(function(){delete registryAccessChecks[id]});
}
function promptRegistryEnvironmentAccess(id){
if(registryAccessPrompt)return registryAccessPrompt.id===id?registryAccessPrompt.promise:Promise.resolve(false);
var resolve,promise=new Promise(function(done){resolve=done});registryAccessPrompt={id:id,resolve:resolve,promise:promise,busy:false};
var environment=instances.filter(function(item){return item.instanceId===id})[0];
document.getElementById('registryAccessName').textContent='请输入“'+(environment?envName(environment):'远程环境')+'”的访问密码';
document.getElementById('registryLoginPassword').value='';document.getElementById('registryAccessError').textContent='';document.getElementById('registryAccessSubmit').disabled=false;
showDialog('registryAccessDialog');document.getElementById('registryLoginPassword').focus();return promise;
}
function finishRegistryAccess(allowed){
var prompt=registryAccessPrompt;if(!prompt)return;
registryAccessPrompt=null;document.getElementById('registryLoginPassword').value='';closeDialog('registryAccessDialog');prompt.resolve(allowed);
}
async function submitRegistryAccessPassword(){
var prompt=registryAccessPrompt;if(!prompt)return;
if(prompt.busy){document.getElementById('registryAccessError').textContent='正在验证，请稍候';return}
var password=document.getElementById('registryLoginPassword').value;if(!password){document.getElementById('registryAccessError').textContent='请输入环境密码';return}
prompt.busy=true;document.getElementById('registryAccessSubmit').textContent='正在验证…';
try{var response=await registryAccessRequest(prompt.id,{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({password:password})}),result=await response.json();
if(registryAccessPrompt!==prompt)return;
if(!response.ok){document.getElementById('registryAccessError').textContent=registryFriendlyError(result.error);return}
showSnackbar('验证成功，此浏览器 7 天内无需重复输入密码');finishRegistryAccess(true);
}catch(e){if(registryAccessPrompt===prompt)document.getElementById('registryAccessError').textContent=registryFriendlyError(e)}
finally{prompt.busy=false;document.getElementById('registryAccessSubmit').textContent='验证并进入'}
}
// 所有带远程环境标识的 fetch 请求先验证，包括聊天、附件上传和文件预览。
if(registryRawFetch)window.fetch=async function(resource,options){
var url;try{url=new URL(typeof resource==='string'?resource:resource.url,window.location.href)}catch(e){return registryRawFetch(resource,options)}
var id=url.searchParams.get('instance');
if(url.origin===window.location.origin&&url.pathname.indexOf('/api/')===0&&url.pathname!=='/api/registry/access'&&id&&id.indexOf('remote-')===0){
var method=String(options&&options.method||resource&&resource.method||'GET').toUpperCase();
if(!await ensureRegistryEnvironmentAccess(id,method!=='GET'&&method!=='HEAD'))return new Response(JSON.stringify({accepted:false,code:'ENVIRONMENT_LOCKED',message:'尚未验证环境密码，操作未执行',error:'尚未验证环境密码，操作未执行'}),{status:401,headers:{'Content-Type':'application/json'}});
}
var response=await registryRawFetch(resource,options);
// 服务端仍校验每个请求；失效后清除预检缓存，不自动重放业务操作。
if(id&&(response.status===401||response.status===403))delete registryAccessVerifiedUntil[id];
return response;
};
if(typeof document.addEventListener==='function')document.addEventListener('keydown',function(event){if(event.key==='Escape'&&registryAccessPrompt){event.preventDefault();finishRegistryAccess(false)}});
// 下载链接不经过 fetch；同样先验证，再保持原来的下载行为。
if(typeof document.addEventListener==='function')document.addEventListener('click',function(event){
var link=event.target.closest&&event.target.closest('a[href]');if(!link||event.defaultPrevented)return;
var url;try{url=new URL(link.href,window.location.href)}catch(e){return}
var id=url.searchParams.get('instance');
if(url.origin!==window.location.origin||url.pathname.indexOf('/api/')!==0||!id||id.indexOf('remote-')!==0)return;
event.preventDefault();ensureRegistryEnvironmentAccess(id).then(function(allowed){if(allowed)window.location.assign(url.href)});
});

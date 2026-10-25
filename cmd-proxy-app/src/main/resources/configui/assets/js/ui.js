// Keep existing children (including taskSaveLabel) intact while feedback is visible.
async function runButtonAction(button,label,operation){
if(!button)return operation();
if(button._pendingAction)return;
button._pendingAction=true;
var disabled=button.disabled,busy=button.getAttribute('aria-busy'),host=button.tagName==='INPUT'?button.closest('.toggle-row').querySelector('.toggle-desc'):button;
button.disabled=true;button.setAttribute('aria-busy','true');
var indicator=null,timer=setTimeout(function(){
if(!button._pendingAction||!host.isConnected)return;
host.style.setProperty('--button-loading-color',getComputedStyle(host).color);
indicator=document.createElement('span');indicator.className='button-loading-indicator'+(host===button&&button.getBoundingClientRect().width<70?' button-loading-icon-only':'');indicator.setAttribute('role','status');
var spinner=document.createElement('span');spinner.className='button-loading-spinner';spinner.setAttribute('aria-hidden','true');
var text=document.createElement('span');text.className='button-loading-label';text.textContent=label;indicator.appendChild(spinner);indicator.appendChild(text);host.appendChild(indicator);host.classList.add('button-loading');
},300);
try{return await operation()}
finally{clearTimeout(timer);if(indicator)indicator.remove();host.classList.remove('button-loading');host.style.removeProperty('--button-loading-color');button.disabled=disabled;if(busy===null)button.removeAttribute('aria-busy');else button.setAttribute('aria-busy',busy);button._pendingAction=false}
}

var confirmDialogResolve=null,confirmDialogReturnFocus=null;
function finishConfirmDialog(result){var dialog=document.getElementById('confirmDialog');if(!dialog.classList.contains('show'))return;dialog.classList.remove('show');syncDialogScrollLock();var resolve=confirmDialogResolve,returnFocus=confirmDialogReturnFocus;confirmDialogResolve=null;confirmDialogReturnFocus=null;if(returnFocus&&document.contains(returnFocus))returnFocus.focus();if(resolve)resolve(result)}
function showConfirm(message,options){options=options||{};if(confirmDialogResolve)finishConfirmDialog(false);var overlay=document.getElementById('confirmDialog'),dialog=overlay.querySelector('.confirm-dialog'),submit=document.getElementById('confirmDialogSubmit'),inputWrap=document.getElementById('confirmDialogInputWrap'),input=document.getElementById('confirmDialogInput'),hasInput=Object.prototype.hasOwnProperty.call(options,'inputValue');document.getElementById('confirmDialogTitle').textContent=options.title||'请确认操作';document.getElementById('confirmDialogMessage').textContent=message||'';document.getElementById('confirmDialogIcon').textContent=options.danger?'warning_amber':(options.icon||'help_outline');inputWrap.hidden=!hasInput;input.value=hasInput?String(options.inputValue||''):'';input.placeholder=options.inputPlaceholder||'';submit.textContent=options.confirmText||'确认';submit.className='btn confirm-submit '+(options.danger?'is-danger':'btn-primary');dialog.classList.toggle('is-danger',!!options.danger);confirmDialogReturnFocus=document.activeElement;overlay.classList.add('show');syncDialogScrollLock();setTimeout(function(){(hasInput?input:submit).focus();if(hasInput)input.select()},0);return new Promise(function(resolve){confirmDialogResolve=resolve})}
function showPrompt(message,options){options=options||{};if(!Object.prototype.hasOwnProperty.call(options,'inputValue'))options.inputValue='';return showConfirm(message,options).then(function(confirmed){return confirmed?document.getElementById('confirmDialogInput').value:null})}
document.getElementById('confirmDialogCancel').addEventListener('click',function(){finishConfirmDialog(false)});
document.getElementById('confirmDialogSubmit').addEventListener('click',function(){finishConfirmDialog(true)});
document.getElementById('confirmDialog').addEventListener('click',function(event){if(event.target===this)finishConfirmDialog(false)});
document.addEventListener('keydown',function(event){if(event.key==='Escape'&&confirmDialogResolve){event.preventDefault();finishConfirmDialog(false)}});
function showSnackbar(msg){var el=document.getElementById('snackbar');el.textContent=msg;el.classList.add('show');setTimeout(function(){el.classList.remove('show')},3000)}
function esc(s){if(!s)return '';var d=document.createElement('div');d.textContent=s;return d.innerHTML.replace(/"/g,'&quot;').replace(/'/g,'&#39;')}

// 服务设置区域的输入变更也算未保存改动
['input','change'].forEach(function(evt){
document.addEventListener(evt,function(e){
if(e.target&&e.target.closest&&e.target.closest('.app-layout')&&!e.target.closest('.toolbar')&&!e.target.closest('#page-mcp-auth')&&!e.target.closest('#page-sessions')&&!e.target.closest('#page-tasks')&&!e.target.closest('#page-schedules')&&!e.target.closest('#page-observations'))markDirty();
});
});
window.addEventListener('beforeunload',function(e){
if(dirty){e.preventDefault();e.returnValue=''}
});
window.addEventListener('resize',syncStarweaveViewport);
window.addEventListener('load',syncStarweaveViewport);
window.addEventListener('pageshow',syncStarweaveViewport);

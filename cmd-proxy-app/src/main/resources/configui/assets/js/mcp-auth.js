async function loadMcpAuth(){
try{var responses=await Promise.all([api('/api/mcp-auth/v1/servers'),api('/api/mcp-auth/v1/principals')]),sr=responses[0],pr=responses[1];
var sd=sr.ok?await sr.json():{servers:[]},pd=pr.ok?await pr.json():{principals:[]};
mcpAuthRuntime={servers:sd.servers||[],principals:pd.principals||[]};renderMcpAuth();}
catch(e){mcpAuthRuntime={servers:[],principals:[]}}
}

function renderMcpAuth(){
var count=document.getElementById('mcpServerCount');if(count)count.textContent=(mcpAuthRuntime.servers||[]).length;
var opts=document.getElementById('wecomPrincipalOptions');if(opts)opts.innerHTML=(mcpAuthRuntime.principals||[]).map(function(p){return '<option value="'+esc(p.principalId)+'">'+esc(p.displayName||p.principalId)+'</option>'}).join('');
var c=document.getElementById('mcpAuthList');if(!c)return;
if(!mcpAuthRuntime.servers.length){c.innerHTML='<div class="filter-empty"><span class="material-icons">auto_awesome</span><p>还没有工具服务</p><small>工具服务连接到 Starweave 后会显示在这里</small></div>';return}
c.innerHTML=mcpAuthRuntime.servers.map(function(s,i){var ids=s.allowedPrincipalIds||[],tools=s.tools||[];
var chips=ids.length?ids.map(function(id,j){var p=(mcpAuthRuntime.principals||[]).filter(function(v){return v.principalId===id})[0];return '<span class="chip">'+esc((p&&p.displayName?p.displayName+' · ':'')+id)+'<span class="material-icons" onclick="removeMcpUser('+i+','+j+')">close</span></span>'}).join(''):'<span style="color:#9e9e9e;font-size:13px">白名单为空</span>';
return '<div class="card"><div class="card-header"><h2>'+esc(s.name||s.serverId)+' <small style="color:#8a94a6">'+esc(s.serverId)+'</small></h2><span class="status-badge '+(s.online?'running':'')+'">'+(s.online?'已注册':'历史配置')+'</span></div><div class="card-body">'
+'<div class="toggle-row"><div><div class="toggle-label">启用用户鉴权</div><div class="toggle-desc">有身份的请求仅允许白名单用户 ID；无身份来源继续放行</div></div><label class="switch"><input type="checkbox" '+(s.authEnabled?'checked':'')+' onchange="setMcpAuthEnabled('+i+',this.checked)"><span class="slider"></span></label></div>'
+'<div class="section-title">用户 ID 白名单</div><div class="chip-input-row"><input id="mcpUserInput_'+i+'" list="wecomPrincipalOptions" placeholder="输入用户 ID，支持逗号或换行批量添加" onkeydown="if(event.key===\'Enter\'){event.preventDefault();addMcpUsers('+i+')}"><button class="btn btn-secondary btn-sm" onclick="addMcpUsers('+i+')"><span class="material-icons">add</span>添加</button></div><div class="chip-list">'+chips+'</div>'
+'<div style="margin-top:16px;color:#757575;font-size:12px">已注册工具：'+esc(tools.map(mcpToolName).filter(Boolean).join('、')||'未上报')+'</div></div></div>'}).join('');
}

function mcpToolName(tool){return typeof tool==='string'?tool:(tool&&tool.name)||''}

async function saveMcpPolicy(i){var s=mcpAuthRuntime.servers[i];if(!s)return;try{var r=await api('/api/mcp-auth/v1/policies',{method:'PUT',headers:{'Content-Type':'application/json'},body:JSON.stringify({serverId:s.serverId,authEnabled:!!s.authEnabled,allowedPrincipalIds:s.allowedPrincipalIds||[]})});var d=await r.json();if(!r.ok||!d.success)throw new Error(d.message||'保存失败');showSnackbar('工具权限已保存并生效')}catch(e){showSnackbar('工具权限保存失败:'+e.message);await loadMcpAuth()}}
function setMcpAuthEnabled(i,enabled){mcpAuthRuntime.servers[i].authEnabled=enabled;saveMcpPolicy(i)}
function addMcpUsers(i){var input=document.getElementById('mcpUserInput_'+i),values=(input.value||'').split(/[,，\n]+/).map(function(v){return v.trim()}).filter(Boolean),s=mcpAuthRuntime.servers[i];s.allowedPrincipalIds=Array.from(new Set((s.allowedPrincipalIds||[]).concat(values)));input.value='';renderMcpAuth();saveMcpPolicy(i)}
function removeMcpUser(i,j){var s=mcpAuthRuntime.servers[i];(s.allowedPrincipalIds||[]).splice(j,1);renderMcpAuth();saveMcpPolicy(i)}

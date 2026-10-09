function renderProviderOptions(){
var select=document.getElementById('robotProviderFilter'),current=select.value||'all';
var providers=[];(config.robots||[]).forEach(function(r){var p=r.agentProvider||'KIRO_CLI';if(providers.indexOf(p)<0)providers.push(p)});providers.sort();
select.innerHTML='<option value="all">全部运行引擎</option>'+providers.map(function(p){return '<option value="'+esc(p)+'">'+esc(providerDisplayName(p))+'</option>'}).join('');
select.value=providers.indexOf(current)>=0?current:'all';
}

function hasProxyAddress(robot){return !!(robot.httpProxy&&robot.httpProxy.trim())}
function providerDisplayName(provider){var names={EMBEDDED_PI_ACP:'内嵌引擎 · Pi',KIRO_CLI:'Kiro CLI',OPENCODE:'OpenCode',CLAUDE_AGENT_ACP:'Claude Agent · ACP',CODEX_ACP:'Codex · ACP',DEEPSEEK_HARNESS_ACP:'DeepSeek Harness · ACP'};return names[provider]||provider||'Kiro CLI'}
function isNpmProvider(provider){return ['OPENCODE','CLAUDE_AGENT_ACP','CODEX_ACP','DEEPSEEK_HARNESS_ACP'].indexOf(provider)>=0}
function providerVersionOptions(robot){
var selected=robot.providerVersion||'',runtime=robot._providerRuntime||{},installed=runtime.installedVersions||[],defaultVersion=runtime.defaultVersion||'';
var versions=selected?[selected]:[];installed.forEach(function(v){if(versions.indexOf(v)<0)versions.push(v)});
var html='<option value=""'+(selected?'':' selected')+'>自动跟随 latest'+(defaultVersion?' · 当前 '+esc(defaultVersion):' · 等待后台准备')+'</option>';
versions.forEach(function(v){html+='<option value="'+esc(v)+'"'+(v===selected?' selected':'')+'>'+esc(v)+(installed.indexOf(v)>=0?' · 已安装':'')+'</option>'});
return html;
}
function dshPresetOptions(selected){
selected=selected||'standard';
var presets=[{id:'standard',name:'标准模式'},{id:'code',name:'PTC 模式'},{id:'minimal',name:'极简模式'},{id:'cordis',name:'创造模式'}];
if(!presets.some(function(p){return p.id===selected}))presets.push({id:selected,name:'自定义模式'});
return presets.map(function(p){return '<option value="'+esc(p.id)+'"'+(p.id===selected?' selected':'')+'>'+esc(p.name)+'</option>'}).join('');
}
function dshBundleView(r){
if(r._dshProfileError)return '<span style="color:#d32f2f">读取失败：'+esc(r._dshProfileError)+'</span>';
var bundles=r._dshProfileBundles||[];
if(!bundles.length)return '<span style="color:#9e9e9e">Profile 尚未初始化或没有 bundle</span>';
return bundles.map(function(name){return '<span class="chip" style="margin:0 6px 6px 0">'+esc(name)+'</span>'}).join('');
}
function isAllConfiguredProxiesEnabled(){
var configured=config.robots.filter(hasProxyAddress);
return configured.length>0&&configured.every(function(robot){return robot.proxyEnabled});
}

function renderChatters(){
var c=document.getElementById('chatterList');
if(!c)return;var instanceId=curInstance?(curInstance.remote?curInstance.sourceInstanceId:curInstance.instanceId):'';var starweaveId=instanceId?'starweave-'+instanceId:'等待环境身份加载';
var molaChatters=config.chatterIds.length?config.chatterIds.map(function(id,i){return '<span class="chip">'+esc(id)+'<span class="material-icons" onclick="removeChatter('+i+')" title="移除">close</span></span>'}).join(''):'<span style="color:#9e9e9e;font-size:13px">尚未配置 MolaChat 接入用户</span>';
c.innerHTML='<section class="access-user-group"><div class="access-user-head"><span class="material-icons">auto_awesome</span>Starweave Chatter ID</div><div class="chip-list"><span class="chip system"><span class="material-icons">lock</span>'+esc(starweaveId)+'</span></div><div class="access-user-help">由当前环境自动生成，用于 Starweave 本地会话、通讯录和路由隔离，不会注册为 MolaChat 用户。</div></section><section class="access-user-group"><div class="access-user-head"><span class="material-icons">account_circle</span>MolaChat Chatter ID</div><div class="chip-list">'+molaChatters+'</div><div class="access-user-help">本地 talkTo 不允许跨 Chatter ID，仅同一 Chatter ID 下的主 ACP 智能体可直接通信；跨用户通信需配置远程通讯并明确指定目标 Chatter ID。</div></section>';
}

function renderRobots(){
var c=document.getElementById('robotList');
var entries=filteredRobots(),paged=pageEntries('robot',entries);
renderPagination('robot',entries.length,paged.totalPages);
if(!config.robots.length){c.innerHTML='<div class="filter-empty"><span class="material-icons">auto_awesome</span><p>还没有智能体</p><small>创建你的第一个智能体，让它开始连接消息、工具与伙伴</small></div>';return}
if(!entries.length){c.innerHTML='<div class="filter-empty"><span class="material-icons">search_off</span><p>没有匹配的智能体</p><small>试试减少关键词或调整筛选条件</small></div>';return}
c.innerHTML=paged.items.map(function(entry){var r=entry.item,i=entry.index;
if(!Object.prototype.hasOwnProperty.call(r,'_runtimeName'))Object.defineProperty(r,'_runtimeName',{value:r.name||'',writable:true,enumerable:false});
var memoryEnabled=r.memory&&(r.memory.readEnabled!==false||r.memory.writeEnabled!==false);
var mem=memoryEnabled?('读取'+(r.memory.readEnabled!==false?'开启':'关闭')+' · 写入'+(r.memory.writeEnabled!==false?'开启':'关闭')):'已关闭';
if(memoryEnabled&&r.memory.executionMode==='robot')mem+=' · '+esc(r.memory.robotName||'未选择');
var sharing=(teamSharingRuntime.grants||[]).filter(function(g){return g.robotName===r.name});
var sharingTeams=sharing.reduce(function(n,g){return n+(g.teamCount||0)},0);
var sharingMembers=sharing.reduce(function(n,g){return n+(g.memberCount||0)},0);
var sharingCleanup=sharing.some(function(g){return g.cleanupPending});
var sharingRevoked=sharing.some(function(g){return g.state==='REVOKED_CLEANUP'});
var disabled=r.enabled===false;
var role=r.onlySubAgent?'仅子智能体':(r.onlyTeamMember?'仅团队成员':'独立运行');
var engine=providerDisplayName(r.agentProvider||'KIRO_CLI')+(r.providerVersion?' · '+esc(r.providerVersion):'');
var shareState=sharingTeams||sharingMembers?sharingTeams+' 个团队 / '+sharingMembers+' 个成员':'';
if(sharingRevoked)shareState+=' · 已撤销，待清理';else if(sharingCleanup)shareState+=' · 清理中';
var toggleId='tglRobot_'+i;
var refreshing=itemOperationPending('robots',r.name||'');
var toggle='<label class="switch" title="'+(refreshing?'智能体正在刷新':(disabled?'启用智能体':'停用智能体'))+'"><input type="checkbox" id="'+toggleId+'"'+(disabled?'':' checked')+(refreshing?' disabled':'')+' onchange="toggleRobot('+i+',this.checked)"><span class="slider"></span></label>';
var resources='<span class="agent-resource-actions"><button class="btn agent-resource-btn" onclick="openAgentResource('+i+',\'mcp\')" title="查看 MCP 工具配置" aria-label="查看 MCP 工具配置"><span class="material-icons">construction</span></button><button class="btn agent-resource-btn" onclick="openAgentResource('+i+',\'skill\')" title="查看 Skills" aria-label="查看 Skills"><span class="material-icons">school</span></button><button class="btn agent-resource-btn" onclick="openAgentResource('+i+',\'memory\')" title="查看记忆" aria-label="查看记忆"><span class="material-icons">psychology</span></button><button class="btn agent-resource-btn" onclick="exportAgentResources('+i+')" title="导出 MCP、Skill 和记忆" aria-label="导出 MCP、Skill 和记忆"><span class="material-icons">file_download</span></button><button class="btn agent-resource-btn" onclick="openAgentImport('+i+')" title="导入 MCP、Skill 或记忆" aria-label="导入 MCP、Skill 或记忆"><span class="material-icons">file_upload</span></button></span>';
var canOpen=!disabled&&!r.onlySubAgent&&!r.onlyTeamMember;
return '<div class="card agent-card'+(disabled?' disabled':'')+'"><div class="card-header"><div class="agent-heading"><div class="agent-orbit"><span class="material-icons">auto_awesome</span></div><div class="agent-title"><div class="agent-title-row"><span class="state-dot'+(disabled?' disabled':'')+'"></span><h2>'+esc(r.name||'未命名')+'</h2><span class="status-badge">'+(disabled?'已停用':'已启用')+'</span></div><div class="agent-engine">'+engine+'</div></div></div><div class="robot-actions"><button class="btn btn-secondary btn-sm'+(canOpen?'':' is-unavailable')+'" onclick="openStarweaveAgentSession('+i+')" aria-disabled="'+(!canOpen)+'" title="'+(canOpen?'开启会话':'点击查看为何无法开启会话')+'"><span class="material-icons">forum</span></button><button class="btn btn-secondary btn-sm'+(refreshing?' refresh-pending':'')+'" onclick="refreshRobot('+i+')" aria-disabled="'+refreshing+'" title="'+(refreshing?'正在刷新，点击查看状态':'保存并应用到此智能体')+'"><span class="material-icons">'+(refreshing?'hourglass_top':'refresh')+'</span></button><button class="btn btn-secondary btn-sm" onclick="openRobotDialog('+i+')" title="编辑智能体"><span class="material-icons">edit</span></button><button class="btn btn-secondary btn-sm" onclick="openRobotDialog('+i+',\'copy\')" title="复制智能体"><span class="material-icons">content_copy</span></button><button class="btn btn-danger btn-sm" onclick="deleteRobot('+i+')" title="删除智能体"><span class="material-icons">delete</span></button></div></div><div class="card-body"><div class="agent-summary"><div class="agent-summary-item"><div class="agent-summary-label">工作空间</div><div class="agent-summary-value" title="'+esc(r.workDir||'-')+'">'+esc(r.workDir||'-')+'</div></div><div class="agent-summary-item"><div class="agent-summary-label">运行角色</div><div class="agent-summary-value">'+role+'</div></div><div class="agent-summary-item"><div class="agent-summary-label">记忆</div><div class="agent-summary-value">'+mem+'</div></div><div class="agent-summary-item"><div class="agent-summary-label">能力更新</div><div class="agent-summary-value">'+(r.abilityAutoRefresh!==false?'自动更新':'手动更新')+'</div></div><div class="agent-enable-control">'+toggle+'</div></div><div class="agent-foot"><span>'+((r.subAgents&&r.subAgents.length)||0)+' 个子智能体</span><span class="dot">·</span><span>'+((r.contacts&&r.contacts.length)||0)+' 位联系人</span><span class="dot">·</span><span>'+(r.scheduleEnabled?'定时任务已开启':'定时任务已关闭')+'</span><span class="dot">·</span><span>'+((r.teamSharedWithChatterIds&&r.teamSharedWithChatterIds.length)||0)+' 位远程授权用户</span>'+(shareState?'<span class="dot">·</span><span>'+shareState+'</span>':'')+'<span class="dot">·</span><span>网络代理'+(r.proxyEnabled?'已开启':'已关闭')+'</span>'+resources+'</div></div></div>'
}).join('');
}

function resourceMeta(kind){
if(kind==='mcp')return {title:'MCP 工具查看',tree:'工具文件',icon:'construction',empty:'当前智能体没有找到可识别的 MCP 配置文件'};
if(kind==='skill')return {title:'Skill 查看',tree:'Skill 目录',icon:'school',empty:'当前智能体没有找到可识别的 Skill 目录'};
return {title:'记忆查看',tree:'生效记忆目录',icon:'psychology',empty:'当前智能体的生效记忆目录尚未生成'};
}

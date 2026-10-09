var robotModelRequestSequence=0,robotModelCatalog=[],robotModelCatalogLoading=false;
function openRobotDialog(idx,mode){
robotDialogMode=mode==='copy'?'copy':(idx>=0?'edit':'add');robotDialogSourceIndex=idx;editIdx=robotDialogMode==='edit'?idx:-1;
var r=idx>=0?JSON.parse(JSON.stringify(config.robots[idx])):{name:'',signature:'',workDir:'',agentProvider:'EMBEDDED_PI_ACP',avatar:'',enabled:true,abilityAutoRefresh:true,onlySubAgent:false,onlyTeamMember:false,teamSharedWithChatterIds:[],scheduleEnabled:false,autoSleep:{enabled:false,idleMinutes:30},autoNewSession:{enabled:false,checkIntervalMinutes:360,idleMinutes:180},memory:{readEnabled:true,writeEnabled:true,scope:'workspace',executionMode:'model',extractIntervalTurns:5,indexMaxLines:200,maxEntriesPerProject:30,projectExpireDays:30,subClientTimeout:120,dreamEnabled:true,dreamMinHours:24,dreamMinSessions:5},subAgents:[],contacts:[]};
if(robotDialogMode==='copy')r.name=nextRobotCopyName(r.name);
if(!r.memory)r.memory={readEnabled:true,writeEnabled:true,scope:'workspace',executionMode:'model',extractIntervalTurns:5,indexMaxLines:200,maxEntriesPerProject:30,projectExpireDays:30,subClientTimeout:120,dreamEnabled:true,dreamMinHours:24,dreamMinSessions:5};if(r.memory.enabled!==undefined&&r.memory.readEnabled===undefined){r.memory.readEnabled=r.memory.enabled;r.memory.writeEnabled=r.memory.enabled;delete r.memory.enabled;}
if(!r.autoNewSession)r.autoNewSession={enabled:false,checkIntervalMinutes:360,idleMinutes:180};
if(!r.autoSleep)r.autoSleep={enabled:false,idleMinutes:30};
dlgSubAgents=r.subAgents?r.subAgents.slice():[];
dlgContacts=r.contacts?r.contacts.slice():[];
dlgTeamSharedOwners=Array.from(new Set((r.teamSharedWithChatterIds||[]).map(function(v){return String(v).trim()}).filter(Boolean)));
document.getElementById('dlgTitle').textContent=robotDialogMode==='copy'?'复制智能体':(robotDialogMode==='edit'?'编辑智能体':'添加智能体');
document.getElementById('robotDialogSubmit').innerHTML=robotDialogMode==='copy'?'<span class="material-icons">content_copy</span>复制智能体':'<span class="material-icons">save</span>保存智能体';
var m=r.memory;
var pi=r.embeddedPi||{baseUrl:'https://api.openai.com/v1',apiKey:'',contextWindow:128000,maxTokens:8192};
var basicTab='<section class="robot-tab-panel active" data-robot-panel="basic" role="tabpanel"><div class="robot-tab-intro">设置智能体的名称、工作目录和展示信息。</div><div class="section-title">基本信息</div>'+
'<div class="field-group"><label>名称 *</label><input type="text" id="dName" value="'+esc(r.name)+'"></div>'+
'<div class="field-group"><label>签名描述</label><input type="text" id="dSig" value="'+esc(r.signature||'')+'"></div>'+
'<div class="field-row"><div class="field-group"><label>工作目录 *</label><input type="text" id="dWorkDir" value="'+esc(r.workDir)+'" onchange="loadRobotModels(false)"></div><button class="btn btn-secondary btn-sm" onclick="openDirPicker(\'dWorkDir\',\'选择工作目录\')" style="margin-bottom:16px"><span class="material-icons">folder_open</span></button></div>'+
'<div class="field-group"><label>头像 URL</label><input type="text" id="dAvatar" value="'+esc(r.avatar||'')+'"></div></section>';
var agentTab='<section class="robot-tab-panel" data-robot-panel="agent" role="tabpanel"><div class="robot-tab-intro">选择智能体的运行引擎，并配置模型、认证、运行目录和网络代理。</div><div class="section-title">运行设置</div>'+
'<div class="field-group"><label>运行引擎</label><select id="dProvider" onchange="onProviderChange()"><option value="EMBEDDED_PI_ACP"'+(r.agentProvider==='EMBEDDED_PI_ACP'?' selected':'')+'>内嵌引擎 · Pi</option><option value="KIRO_CLI"'+(r.agentProvider==='KIRO_CLI'||!r.agentProvider?' selected':'')+'>Kiro CLI</option><option value="OPENCODE"'+(r.agentProvider==='OPENCODE'?' selected':'')+'>OpenCode</option><option value="CLAUDE_AGENT_ACP"'+(r.agentProvider==='CLAUDE_AGENT_ACP'?' selected':'')+'>Claude Agent · ACP</option><option value="CODEX_ACP"'+(r.agentProvider==='CODEX_ACP'?' selected':'')+'>Codex · ACP</option><option value="DEEPSEEK_HARNESS_ACP"'+(r.agentProvider==='DEEPSEEK_HARNESS_ACP'?' selected':'')+'>DeepSeek Harness · ACP · 实验性</option></select></div>'+
'<div id="providerVersionGroup" style="'+(isNpmProvider(r.agentProvider)?'':'display:none')+'"><div class="field-row"><div class="field-group"><label>引擎版本</label><select id="dProviderVersion" onchange="loadRobotModels(false)">'+providerVersionOptions(r)+'</select><div style="color:#757575;font-size:12px;margin-top:6px" id="providerVersionHint">自动模式由后台每 6 小时检查并准备 latest；不会中断当前会话，下次启动智能体时生效。</div></div><button type="button" class="btn btn-secondary btn-sm" id="providerInstallBtn" onclick="installSelectedProviderVersion()" style="margin-bottom:18px"><span class="material-icons">system_update_alt</span>安装 / 更新</button></div><div id="providerInstallStatus" style="display:none;margin:-6px 0 16px"><div style="height:7px;background:#e5e7eb;border-radius:5px;overflow:hidden"><div id="providerInstallBar" style="height:100%;width:0;background:#625bd8;transition:width .25s"></div></div><div id="providerInstallMessage" style="font-size:12px;color:#647084;margin-top:6px"></div></div></div>'+
'<div class="grid-2"><div class="field-group"><label>模型 (可选)</label><div class="model-picker-row"><div class="model-combobox" id="dModelCombobox"><input type="text" id="dModel" value="'+esc(r.model||'')+'" placeholder="留空使用默认模型" role="combobox" aria-autocomplete="list" aria-expanded="false" aria-controls="dModelMenu" autocomplete="off" onfocus="openRobotModelMenu(\'dModel\')" onclick="openRobotModelMenu(\'dModel\')" oninput="filterRobotModelMenu(\'dModel\')" onkeydown="handleRobotModelKeydown(\'dModel\',event)"><button type="button" class="model-combobox-toggle" onclick="toggleRobotModelMenu(\'dModel\',event)" title="展开模型列表" aria-label="展开模型列表" tabindex="-1"><span class="material-icons" aria-hidden="true">arrow_drop_down</span></button><div class="model-options" id="dModelMenu" role="listbox"></div></div><button type="button" class="btn btn-secondary btn-sm model-refresh-button" id="modelRefreshBtn" onclick="loadRobotModels(true)" title="刷新模型列表" aria-label="刷新模型列表"><span class="material-icons" aria-hidden="true">refresh</span></button></div><div style="color:#757575;font-size:12px;margin-top:6px" id="modelCatalogHint">正在读取模型列表...</div></div><div class="field-group" id="apiKeyGroup" style="'+((r.agentProvider==='CODEX_ACP'||r.agentProvider==='DEEPSEEK_HARNESS_ACP')?'':'display:none')+'"><label id="apiKeyLabel">'+(r.agentProvider==='DEEPSEEK_HARNESS_ACP'?'DeepSeek API Key':'OpenAI API Key')+'</label><input type="password" id="dApiKey" value="'+esc(r.apiKey||'')+'" placeholder="API Key" onchange="loadRobotModels(false)"></div></div>'+
'<div class="field-row" id="codexHomeGroup" style="'+(r.agentProvider==='CODEX_ACP'?'':'display:none')+'"><div class="field-group"><label>Codex Home (可选)</label><input type="text" id="dCodexHome" value="'+esc(r.codexHome||'')+'" placeholder="留空使用 CODEX_HOME 或 ~/.codex" onchange="loadRobotModels(false)"></div><button class="btn btn-secondary btn-sm" onclick="openDirPicker(\'dCodexHome\',\'选择 Codex Home\')" style="margin-bottom:16px"><span class="material-icons">folder_open</span></button></div>'+
'<div id="dshConfigGroup" style="'+(r.agentProvider==='DEEPSEEK_HARNESS_ACP'?'':'display:none')+'"><div class="grid-2"><div class="field-group"><label>运行模式</label><select id="dDshAgentPreset">'+dshPresetOptions(r.dshAgentPreset)+'</select></div><div class="field-group"><label>权限策略</label><select id="dPermissionPolicy"><option value="REJECT"'+((r.permissionPolicy||'REJECT')==='REJECT'?' selected':'')+'>拒绝越界（推荐）</option><option value="ALLOW_ONCE"'+(r.permissionPolicy==='ALLOW_ONCE'?' selected':'')+'>仅本次允许</option><option value="ALLOW_ALWAYS"'+(r.permissionPolicy==='ALLOW_ALWAYS'?' selected':'')+'>始终允许（高风险）</option></select></div></div><div class="field-group"><label>DeepSeek API Endpoint (可选)</label><input type="text" id="dDeepSeekBaseUrl" value="'+esc(r.deepSeekBaseUrl||'')+'" placeholder="留空使用官方端点" onchange="loadRobotModels(false)"></div><div class="field-row"><div class="field-group"><label>DSH Home (可选)</label><input type="text" id="dDshHome" value="'+esc(r.dshHome||'')+'" placeholder="留空时按智能体隔离到 CMD_PROXY_HOME/dsh" onchange="loadRobotModels(false)"></div><button class="btn btn-secondary btn-sm" onclick="openDirPicker(\'dDshHome\',\'选择 DSH Home\')" style="margin-bottom:16px"><span class="material-icons">folder_open</span></button></div><div class="field-group"><label>已加载 Profile Bundles（只读）</label><div class="chip-list">'+dshBundleView(r)+'</div><div style="color:#757575;font-size:12px;margin-top:8px">展示已保存 DSH Home 的实际加载顺序；插件管理暂不提供。</div></div><div style="color:#757575;font-size:12px;margin-bottom:12px">需要 Node.js 22+；默认使用 workspace-write 沙箱，暂不支持图片 prompt。</div></div>'+
'<div id="embeddedPiGroup" style="'+(r.agentProvider==='EMBEDDED_PI_ACP'?'':'display:none')+'"><div class="field-group"><label>API 地址 *</label><input type="url" id="dPiBaseUrl" value="'+esc(pi.baseUrl||'https://api.openai.com/v1')+'" placeholder="https://example.com/v1" onchange="loadRobotModels(false)"></div><div class="field-group"><label>API Key *</label><input type="password" id="dPiApiKey" value="'+esc(pi.apiKey||'')+'" autocomplete="new-password" onchange="loadRobotModels(false)"></div><details><summary>高级设置</summary><div class="grid-2" style="margin-top:12px"><div class="field-group"><label>上下文窗口（Token）</label><input type="number" id="dPiContext" min="1024" step="1" value="'+esc(String(pi.contextWindow==null?128000:pi.contextWindow))+'"></div><div class="field-group"><label>最大输出（Token）</label><input type="number" id="dPiMaxTokens" min="1" step="1" value="'+esc(String(pi.maxTokens==null?8192:pi.maxTokens))+'"></div></div></details></div>'+
'<div class="section-title">网络代理（可选）</div>'+
'<div class="toggle-row"><div><div class="toggle-label">启用代理</div></div><label class="switch"><input type="checkbox" id="dProxyEnabled"'+(r.proxyEnabled?' checked':'')+' onchange="toggleProxyPanel()"><span class="slider"></span></label></div>'+
'<div style="'+(r.proxyEnabled?'':'display:none')+'" id="proxyPanel"><div class="grid-2"><div class="field-group"><label>http_proxy</label><input type="text" id="dHttpProxy" value="'+esc(r.httpProxy||'')+'" placeholder="例: 代理服务器IP:22222"></div><div class="field-group"><label>no_proxy</label><input type="text" id="dNoProxy" value="'+esc(r.noProxy||'')+'" placeholder="例: localhost,127.0.0.1"></div></div></div></section>';
var featuresTab='<section class="robot-tab-panel" data-robot-panel="features" role="tabpanel"><div class="robot-tab-intro">设置智能体的能力更新、运行角色、定时任务和会话轮转。</div><div class="section-title">能力与角色</div>'+
// 「启用该智能体」开关已移至卡片列表页，此处不再展示
'<div class="toggle-row"><div><div class="toggle-label">自动更新能力说明</div></div><label class="switch"><input type="checkbox" id="dAbility"'+(r.abilityAutoRefresh!==false?' checked':'')+'><span class="slider"></span></label></div>'+
'<div class="toggle-row"><div><div class="toggle-label">仅作为子智能体</div></div><label class="switch"><input type="checkbox" id="dOnlySub"'+(r.onlySubAgent?' checked':'')+' onchange="toggleExclusiveRobotRole(\'sub\')"><span class="slider"></span></label></div>'+
'<div class="toggle-row"><div><div class="toggle-label">仅作为团队成员</div><div style="color:#757575;font-size:12px">不单独运行，仅在 Fast Team 中作为成员使用</div></div><label class="switch"><input type="checkbox" id="dOnlyTeam"'+(r.onlyTeamMember?' checked':'')+' onchange="toggleExclusiveRobotRole(\'team\')"><span class="slider"></span></label></div>'+
'<div class="toggle-row"><div><div class="toggle-label">定时任务</div></div><label class="switch"><input type="checkbox" id="dSchedule"'+(r.scheduleEnabled?' checked':'')+'><span class="slider"></span></label></div>'+
'<div class="toggle-row"><div><div class="toggle-label">开启观测能力</div></div><label class="switch"><input type="checkbox" id="dObservation"'+(r.observationEnabled?' checked':'')+'><span class="slider"></span></label></div>'+
'<div class="toggle-row"><div><div class="toggle-label">自动睡眠</div><div style="color:#757575;font-size:12px">空闲后释放运行进程，收到请求时自动唤醒</div></div><label class="switch"><input type="checkbox" id="dAutoSleep"'+(r.autoSleep.enabled?' checked':'')+' onchange="toggleAutoSleepPanel()"><span class="slider"></span></label></div>'+
'<div class="grid-2" id="dAutoSleepPanel" style="'+(r.autoSleep.enabled?'':'display:none')+'"><div class="field-group"><label>空闲阈值（分钟）</label><input type="number" id="dAutoSleepIdle" min="1" value="'+(r.autoSleep.idleMinutes||30)+'"></div></div>'+
'<div class="toggle-row"><div><div class="toggle-label">自动开启新会话</div><div style="color:#757575;font-size:12px">同时应用于该智能体的独立会话和团队成员实例</div></div><label class="switch"><input type="checkbox" id="dAutoNewSession"'+(r.autoNewSession.enabled?' checked':'')+' onchange="toggleAutoNewSessionPanel()"><span class="slider"></span></label></div>'+
'<div class="grid-2" id="dAutoNewSessionPanel" style="'+(r.autoNewSession.enabled?'':'display:none')+'"><div class="field-group"><label>检查间隔（分钟）</label><input type="number" id="dAutoNewSessionCheck" min="1" value="'+(r.autoNewSession.checkIntervalMinutes||360)+'"></div><div class="field-group"><label>空闲阈值（分钟）</label><input type="number" id="dAutoNewSessionIdle" min="1" value="'+(r.autoNewSession.idleMinutes||180)+'"></div></div></section>';
var memoryTab='<section class="robot-tab-panel" data-robot-panel="memory" role="tabpanel"><div class="robot-tab-intro">管理智能体如何读取、整理和隔离长期记忆。</div><div class="section-title">记忆</div>'+
'<div class="toggle-row"><div><div class="toggle-label">记忆读取</div></div><label class="switch"><input type="checkbox" id="dMemRead"'+(m.readEnabled!==false?' checked':'')+' onchange="toggleMemPanel()"><span class="slider"></span></label></div>'+
'<div class="toggle-row"><div><div class="toggle-label">记忆写入</div></div><label class="switch"><input type="checkbox" id="dMemWrite"'+(m.writeEnabled!==false?' checked':'')+' onchange="toggleMemPanel()"><span class="slider"></span></label></div>'+
'<div class="memory-panel" id="memPanel" style="'+((m.readEnabled===false&&m.writeEnabled===false)?'display:none':'')+'"><div class="grid-2">'+
'<div class="field-group"><label>记忆范围</label><select id="dMemScope"><option value="workspace"'+(m.scope!=='robot'?' selected':'')+'>当前工作空间</option><option value="robot"'+(m.scope==='robot'?' selected':'')+'>当前智能体</option></select></div>'+
'<div class="field-group"><label>提取间隔轮次</label><input type="number" id="dMemExtract" min="1" value="'+(m.extractIntervalTurns||5)+'"></div>'+
'<div class="field-group"><label>索引最大行数</label><input type="number" id="dMemIndex" min="1" value="'+(m.indexMaxLines||200)+'"></div>'+
'<div class="field-group"><label>项目最大条目</label><input type="number" id="dMemProject" min="1" value="'+(m.maxEntriesPerProject||30)+'"></div>'+
'<div class="field-group"><label>项目过期天数</label><input type="number" id="dMemExpire" min="1" value="'+(m.projectExpireDays||30)+'"></div>'+
'<div class="field-group"><label>记忆整理方式</label><select id="dMemExecutionMode" onchange="toggleMemExecutionPanel()"><option value="model"'+(m.executionMode!=='robot'?' selected':'')+'>指定模型</option><option value="robot"'+(m.executionMode==='robot'?' selected':'')+'>指定智能体</option></select></div>'+
'<div class="field-group" id="dMemModelPanel" style="'+(m.executionMode==='robot'?'display:none':'')+'"><label>记忆模型</label><div class="model-combobox" id="dMemModelCombobox"><input type="text" id="dMemModel" placeholder="留空则沿用主模型" value="'+esc(m.model||'')+'" role="combobox" aria-autocomplete="list" aria-expanded="false" aria-controls="dMemModelMenu" autocomplete="off" onfocus="openRobotModelMenu(\'dMemModel\')" onclick="openRobotModelMenu(\'dMemModel\')" oninput="filterRobotModelMenu(\'dMemModel\')" onkeydown="handleRobotModelKeydown(\'dMemModel\',event)"><button type="button" class="model-combobox-toggle" onclick="toggleRobotModelMenu(\'dMemModel\',event)" title="展开模型列表" aria-label="展开模型列表" tabindex="-1"><span class="material-icons" aria-hidden="true">arrow_drop_down</span></button><div class="model-options" id="dMemModelMenu" role="listbox"></div></div><div style="color:#757575;font-size:12px;margin-top:6px">与主模型共用实时目录和自定义历史</div></div>'+
'<div class="field-group" id="dMemRobotPanel" style="'+(m.executionMode==='robot'?'':'display:none')+'"><label>记忆智能体</label><select id="dMemRobot">'+memoryRobotOptions(m.robotName)+'</select><div style="color:#757575;font-size:12px;margin-top:6px">继承所选智能体的运行引擎、模型、认证、代理和工作目录</div></div>'+
'<div class="field-group"><label>记忆任务超时（秒）</label><input type="number" id="dMemTimeout" min="10" value="'+(m.subClientTimeout||120)+'"></div>'+
'<div class="field-group"><label>&nbsp;</label><div class="toggle-row" style="border:none;padding:0"><div><div class="toggle-label">定期整理记忆</div><div class="toggle-desc">由 Dream 任务归纳长期记忆</div></div><label class="switch"><input type="checkbox" id="dMemDream"'+(m.dreamEnabled!==false?' checked':'')+'><span class="slider"></span></label></div></div>'+
'<div class="field-group"><label>最短整理间隔（小时）</label><input type="number" id="dMemDreamH" min="1" value="'+(m.dreamMinHours||24)+'"></div>'+
'<div class="field-group"><label>触发整理的最少会话数</label><input type="number" id="dMemDreamS" min="1" value="'+(m.dreamMinSessions||5)+'"></div>'+
'</div></div></section>';
var relationsTab='<section class="robot-tab-panel" data-robot-panel="relations" role="tabpanel"><div class="robot-tab-intro">设置智能体可以加入的团队、可调用的子智能体，以及能够联系的伙伴。</div><div class="section-title">远程团队授权</div>'+
'<div class="field-group"><label>允许以下用户远程调用此智能体</label><div class="chip-input-row"><input type="text" id="dTeamSharedOwnerInput" placeholder="输入 Chatter ID 后按回车" onkeydown="handleTeamSharedOwnerKey(event)"><button type="button" class="btn btn-secondary btn-sm" onclick="addTeamSharedOwner()"><span class="material-icons">add</span>添加</button></div><div id="dTeamSharedOwnerList" class="chip-list"></div><div style="color:#757575;font-size:12px;margin-top:8px">授权后，此智能体可被指定用户加入远程团队；移除用户即停止接受新的调用请求。</div></div>'+
'<div class="section-title">子智能体</div><div id="dSubList"></div><div style="color:#757575;font-size:12px;margin:0 0 10px">可以选择当前智能体，以创建 self-fork。</div><button class="btn btn-secondary btn-sm" onclick="addSubAgent()"><span class="material-icons">add</span>添加子智能体</button>'+
'<div class="section-title">通讯录</div><div id="dContactList"></div><button class="btn btn-secondary btn-sm" onclick="addContact()"><span class="material-icons">add</span>添加联系人</button></section>';
document.getElementById('dlgBody').innerHTML=basicTab+agentTab+featuresTab+memoryTab+relationsTab;
renderTeamSharedOwners();renderSubAgents();renderContacts();
clearRobotValidation();
switchRobotTab('basic');
showDialog('robotDialog');
if(isNpmProvider(r.agentProvider))loadProviderVersions(r.agentProvider,r.providerVersion||'');
loadRobotModels(false);
}

function switchRobotTab(tab){
var valid=['basic','agent','features','memory','relations'];if(valid.indexOf(tab)<0)tab='basic';
closeRobotModelMenus();
document.querySelectorAll('[data-robot-tab]').forEach(function(el){var active=el.getAttribute('data-robot-tab')===tab;el.classList.toggle('active',active);el.setAttribute('aria-selected',active?'true':'false')});
document.querySelectorAll('[data-robot-panel]').forEach(function(el){var active=el.getAttribute('data-robot-panel')===tab;el.classList.toggle('active',active);el.setAttribute('aria-hidden',active?'false':'true')});
var body=document.getElementById('dlgBody');if(body)body.scrollTop=0;
}
function clearRobotValidation(){document.querySelectorAll('[data-robot-tab]').forEach(function(el){el.classList.remove('has-error')});document.querySelectorAll('#dlgBody .field-invalid').forEach(function(el){el.classList.remove('field-invalid')})}
function showRobotValidation(tab,fieldId,message){
var tabButton=document.querySelector('[data-robot-tab="'+tab+'"]');if(tabButton)tabButton.classList.add('has-error');
switchRobotTab(tab);showSnackbar(message);
var field=fieldId&&document.getElementById(fieldId);if(field){field.classList.add('field-invalid');field.addEventListener('input',function clearInvalid(){field.classList.remove('field-invalid');if(tabButton)tabButton.classList.remove('has-error');field.removeEventListener('input',clearInvalid)});setTimeout(function(){field.focus()},0)}
}
function toggleMemPanel(){var show=document.getElementById('dMemRead').checked||document.getElementById('dMemWrite').checked;document.getElementById('memPanel').style.display=show?'':'none'}
function toggleMemExecutionPanel(){var robot=document.getElementById('dMemExecutionMode').value==='robot';document.getElementById('dMemModelPanel').style.display=robot?'none':'';document.getElementById('dMemRobotPanel').style.display=robot?'':'none'}
function memoryRobotOptions(selected){
var robots=config.robots||[],found=false,html='<option value="">请选择智能体</option>';
robots.forEach(function(robot){var name=robot.name||'';if(!name)return;if(name===selected)found=true;html+='<option value="'+esc(name)+'"'+(name===selected?' selected':'')+'>'+esc(name)+(robot.enabled===false?'（已禁用）':'')+'</option>'});
if(selected&&!found)html+='<option value="'+esc(selected)+'" selected>'+esc(selected)+'（不存在）</option>';
return html;
}
function toggleAutoNewSessionPanel(){document.getElementById('dAutoNewSessionPanel').style.display=document.getElementById('dAutoNewSession').checked?'':'none'}
function toggleAutoSleepPanel(){document.getElementById("dAutoSleepPanel").style.display=document.getElementById("dAutoSleep").checked?"":"none"}
function toggleProxyPanel(){document.getElementById('proxyPanel').style.display=document.getElementById('dProxyEnabled').checked?'':'none'}
function toggleExclusiveRobotRole(role){if(role==='sub'&&document.getElementById('dOnlySub').checked)document.getElementById('dOnlyTeam').checked=false;if(role==='team'&&document.getElementById('dOnlyTeam').checked)document.getElementById('dOnlySub').checked=false}
function onProviderChange(){var p=document.getElementById('dProvider').value;if(p==='CLAUDE_AGENT_ACP'){document.getElementById('dMemRead').checked=false;document.getElementById('dMemWrite').checked=false;toggleMemPanel()}var codex=p==='CODEX_ACP',dsh=p==='DEEPSEEK_HARNESS_ACP',managed=isNpmProvider(p);document.getElementById('embeddedPiGroup').style.display=p==='EMBEDDED_PI_ACP'?'':'none';document.getElementById('apiKeyGroup').style.display=(codex||dsh)?'':'none';document.getElementById('apiKeyLabel').textContent=dsh?'DeepSeek API Key':'OpenAI API Key';document.getElementById('codexHomeGroup').style.display=codex?'':'none';document.getElementById('dshConfigGroup').style.display=dsh?'':'none';document.getElementById('providerVersionGroup').style.display=managed?'':'none';document.getElementById('providerInstallBtn').style.display=managed?'':'none';if(managed)loadProviderVersions(p,'');loadRobotModels(false)}

function robotModelRequest(action,force,robot){
var value=function(id){var el=document.getElementById(id);return el?el.value.trim():''};
var checked=function(id){var el=document.getElementById(id);return !!(el&&el.checked)};
robot=robot||{};var provider=robot.agentProvider||value('dProvider')||'KIRO_CLI';return {action:action||'list',force:!!force,
provider:provider,providerVersion:isNpmProvider(provider)?(robot.providerVersion||value('dProviderVersion')):'',
embeddedPi:provider==='EMBEDDED_PI_ACP'?Object.assign({},robot.embeddedPi||{},{stateId:(robot.embeddedPi&&robot.embeddedPi.stateId)||(config.robots[robotDialogSourceIndex]&&config.robots[robotDialogSourceIndex].embeddedPi&&config.robots[robotDialogSourceIndex].embeddedPi.stateId),baseUrl:robot.embeddedPi?robot.embeddedPi.baseUrl:value('dPiBaseUrl'),apiKey:robot.embeddedPi?robot.embeddedPi.apiKey:value('dPiApiKey')}):undefined,
workDir:robot.workDir||value('dWorkDir'),model:robot.model||value('dModel'),
memoryModel:(robot.memory&&robot.memory.model)||value('dMemModel'),apiKey:(provider==='CODEX_ACP'||provider==='DEEPSEEK_HARNESS_ACP')?(robot.apiKey||value('dApiKey')):'',
codexHome:provider==='CODEX_ACP'?(robot.codexHome||value('dCodexHome')):'',deepSeekBaseUrl:provider==='DEEPSEEK_HARNESS_ACP'?(robot.deepSeekBaseUrl||value('dDeepSeekBaseUrl')):'',
dshHome:provider==='DEEPSEEK_HARNESS_ACP'?(robot.dshHome||value('dDshHome')):'',proxyEnabled:robot.proxyEnabled!==undefined?robot.proxyEnabled:checked('dProxyEnabled'),
httpProxy:robot.httpProxy||value('dHttpProxy')};
}
function robotModelMenu(inputId){return document.getElementById(inputId+'Menu')}
function closeRobotModelMenus(exceptInputId){
document.querySelectorAll('.model-combobox.open').forEach(function(box){var input=box.querySelector('input[role="combobox"]');if(input&&input.id===exceptInputId)return;box.classList.remove('open');if(input)input.setAttribute('aria-expanded','false')});
}
function renderRobotModelMenu(inputId){
var input=document.getElementById(inputId),menu=robotModelMenu(inputId);if(!input||!menu)return;
var query=input.value.trim().toLowerCase(),matches=[];
robotModelCatalog.forEach(function(model,index){var id=String(model.id||''),name=String(model.name||id);if(!query||id.toLowerCase().indexOf(query)>=0||name.toLowerCase().indexOf(query)>=0)matches.push({model:model,index:index})});
if(!matches.length){menu.innerHTML='<div class="model-option-empty">'+(robotModelCatalogLoading?'正在读取模型列表...':(query?'没有匹配项，可直接使用当前输入':'暂无候选模型，可直接手动输入'))+'</div>';return}
menu.innerHTML=matches.slice(0,100).map(function(item){var model=item.model,id=String(model.id||''),name=String(model.name||id),source=model.source==='history'?'自定义':(model.source==='configured'?'当前配置':'实时'),detail=name!==id?id+' · '+source:source;return '<button type="button" class="model-option" role="option" data-model-index="'+item.index+'" onclick="selectRobotModelOption(\''+inputId+'\',this)"><span class="model-option-name">'+esc(name)+'</span><span class="model-option-detail">'+esc(detail)+'</span></button>'}).join('');
}
function openRobotModelMenu(inputId){
var input=document.getElementById(inputId),menu=robotModelMenu(inputId);if(!input||!menu)return;closeRobotModelMenus(inputId);renderRobotModelMenu(inputId);input.closest('.model-combobox').classList.add('open');input.setAttribute('aria-expanded','true');
}
function filterRobotModelMenu(inputId){openRobotModelMenu(inputId)}
function toggleRobotModelMenu(inputId,event){
if(event){event.preventDefault();event.stopPropagation()}var input=document.getElementById(inputId);if(!input)return;var box=input.closest('.model-combobox'),wasOpen=box.classList.contains('open');if(wasOpen){closeRobotModelMenus();return}input.focus();openRobotModelMenu(inputId);
}
function selectRobotModelOption(inputId,option){
var input=document.getElementById(inputId),index=parseInt(option.getAttribute('data-model-index'),10),model=robotModelCatalog[index];if(!input||!model)return;input.value=model.id||'';closeRobotModelMenus();input.focus();closeRobotModelMenus();
}
function handleRobotModelKeydown(inputId,event){
if(event.key==='Escape'){closeRobotModelMenus();return}if(event.key!=='ArrowDown')return;event.preventDefault();openRobotModelMenu(inputId);var first=robotModelMenu(inputId).querySelector('.model-option');if(first)first.focus();
}
document.addEventListener('click',function(event){if(!event.target.closest('.model-combobox'))closeRobotModelMenus()});
async function loadRobotModels(force){
var piSelected=document.getElementById('dProvider').value==='EMBEDDED_PI_ACP',modelBox=document.getElementById('dModelCombobox');if(modelBox){modelBox.closest('.field-group').querySelector('label').textContent=piSelected?'模型 *':'模型 (可选)';document.getElementById('dModel').placeholder=piSelected?'输入模型 ID':'留空使用默认模型'}
var main=document.getElementById('dModel'),memory=document.getElementById('dMemModel'),hint=document.getElementById('modelCatalogHint');if(!main||!memory)return;
var sequence=++robotModelRequestSequence,button=document.getElementById('modelRefreshBtn');robotModelCatalogLoading=true;if(button)button.disabled=true;if(hint)hint.textContent=force?'正在刷新模型列表...':'正在读取模型列表...';renderRobotModelMenu('dModel');renderRobotModelMenu('dMemModel');
try{var response=await api('/api/provider-models',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(robotModelRequest('list',force))});var data=await response.json();if(!response.ok||data.success===false)throw new Error(data.message||'读取失败');if(sequence!==robotModelRequestSequence)return;
robotModelCatalog=data.models||[];robotModelCatalogLoading=false;renderRobotModelMenu('dModel');renderRobotModelMenu('dMemModel');if(hint)hint.textContent=data.warning||(robotModelCatalog.length?'已加载 '+robotModelCatalog.length+' 个模型；也可直接输入自定义模型':'暂无可发现模型，可直接输入模型名称');
}catch(e){if(sequence===robotModelRequestSequence){robotModelCatalogLoading=false;renderRobotModelMenu('dModel');renderRobotModelMenu('dMemModel');if(hint)hint.textContent='模型列表读取失败，可继续手动输入：'+e.message}}finally{if(sequence===robotModelRequestSequence&&button)button.disabled=false}
}
async function rememberRobotModels(robot){
try{await api('/api/provider-models',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(robotModelRequest('remember',false,robot))})}catch(e){console.warn('模型历史保存失败',e)}
}

async function loadProviderVersions(provider,selected){
var select=document.getElementById('dProviderVersion'),hint=document.getElementById('providerVersionHint');if(!select||!isNpmProvider(provider))return;
select.disabled=true;hint.textContent='正在读取 npm registry 发行版本...';
try{var catalog=providerReleaseCache[provider];if(!catalog){var response=await api('/api/provider-runtime/releases?provider='+encodeURIComponent(provider));var data=await response.json();if(!response.ok)throw new Error(data.message||data.error||'读取失败');catalog=data;providerReleaseCache[provider]=catalog}
var installed=catalog.installedVersions||[],target=selected||select.value||'';
var latest=catalog.distTags&&catalog.distTags.latest||'';
select.innerHTML='<option value="">自动跟随 latest'+(latest?' · '+esc(latest):'')+'</option>'+(catalog.versions||[]).map(function(v){var tags=Object.keys(catalog.distTags||{}).filter(function(tag){return catalog.distTags[tag]===v});return '<option value="'+esc(v)+'"'+(v===target?' selected':'')+'>'+esc(v)+(tags.length?' · '+esc(tags.join('/')):'')+(installed.indexOf(v)>=0?' · 已安装':'')+'</option>'}).join('');
if(target&&!(catalog.versions||[]).some(function(v){return v===target})){select.insertAdjacentHTML('beforeend','<option value="'+esc(target)+'" selected>'+esc(target)+' · 当前配置不可用</option>')}
hint.textContent='npm package: '+(catalog.packageName||'')+'；自动模式由后台维护 latest，选择精确版本则保持固定。';
}catch(e){hint.textContent='发行版本读取失败：'+e.message;}finally{select.disabled=false}
}

async function installSelectedProviderVersion(){
var provider=document.getElementById('dProvider').value,select=document.getElementById('dProviderVersion'),version=select.value;
if(!isNpmProvider(provider)){showSnackbar('当前运行引擎不支持 npm 托管安装');return}
if(!version){var catalog=providerReleaseCache[provider];version=catalog&&catalog.distTags&&catalog.distTags.latest;if(!version){showSnackbar('请先选择一个精确版本');return}select.value=version}
var button=document.getElementById('providerInstallBtn'),panel=document.getElementById('providerInstallStatus');button.disabled=true;panel.style.display='block';updateProviderInstallProgress(0,'正在创建安装任务...');
try{var response=await api('/api/provider-runtime/install',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({provider:provider,version:version})});var job=await response.json();if(!response.ok)throw new Error(job.message||job.error||'创建安装任务失败');await pollProviderInstallJob(job.jobId);providerReleaseCache[provider]=null;await loadProviderVersions(provider,version);select.value=version;showSnackbar('引擎版本 '+version+' 已安装；保存并应用到智能体后生效');}
catch(e){updateProviderInstallProgress(100,'安装失败：'+e.message,true);showSnackbar('运行引擎安装失败:'+e.message)}finally{button.disabled=false}
}
async function pollProviderInstallJob(jobId){
for(;;){await new Promise(function(resolve){setTimeout(resolve,700)});var response=await api('/api/provider-runtime/job?jobId='+encodeURIComponent(jobId)),job=await response.json();if(!response.ok)throw new Error(job.message||job.error||'读取安装状态失败');updateProviderInstallProgress(job.progress||0,job.message||job.status,job.status==='FAILED');if(job.status==='DONE')return;if(job.status==='FAILED')throw new Error(job.message||'安装失败')}
}
function updateProviderInstallProgress(progress,message,failed){var bar=document.getElementById('providerInstallBar'),text=document.getElementById('providerInstallMessage');if(bar){bar.style.width=Math.max(0,Math.min(100,progress))+'%';bar.style.background=failed?'#d95565':'#625bd8'}if(text){text.textContent=message||'';text.style.color=failed?'#d95565':'#647084'}}

function configuredRobotOptions(selected,includeSelf){
var found=false,html='<option value="">请选择智能体</option>';
(config.robots||[]).forEach(function(robot,index){if(!includeSelf&&index===editIdx)return;var name=robot.name||'';if(!name)return;if(name===selected)found=true;html+='<option value="'+esc(name)+'"'+(name===selected?' selected':'')+'>'+esc(name)+(robot.enabled===false?'（已停用）':'')+'</option>'});
if(selected&&!found)html+='<option value="'+esc(selected)+'" selected>'+esc(selected)+'（当前配置）</option>';
return html;
}

function renderSubAgents(){
var c=document.getElementById('dSubList');
c.innerHTML=dlgSubAgents.map(function(s,i){return '<div class="list-item"><select class="robot-reference-select" aria-label="选择子智能体" onchange="dlgSubAgents['+i+'].name=this.value">'+configuredRobotOptions(s.name||'',true)+'</select><input type="text" placeholder="描述（可选）" value="'+esc(s.description||'')+'" onchange="dlgSubAgents['+i+'].description=this.value"><button class="btn btn-danger btn-sm" onclick="dlgSubAgents.splice('+i+',1);renderSubAgents()"><span class="material-icons">close</span></button></div>'}).join('');
}
function addSubAgent(){dlgSubAgents.push({name:'',description:''});renderSubAgents()}

function renderContacts(){
var c=document.getElementById('dContactList');
c.innerHTML=dlgContacts.map(function(s,i){
var isRemote=s.isRemote||false;
var row='<div class="list-item"><label style="display:flex;align-items:center;gap:4px;min-width:60px"><input type="checkbox" '+(isRemote?'checked':'')+' onchange="dlgContacts['+i+'].isRemote=this.checked;renderContacts()">远程</label>';
if(isRemote){row+='<input type="text" aria-label="远程联系人智能体名称" placeholder="输入远程智能体名称" value="'+esc(s.name||'')+'" oninput="dlgContacts['+i+'].name=this.value">';}
else{row+='<select class="robot-reference-select" aria-label="选择联系人智能体" onchange="dlgContacts['+i+'].name=this.value">'+configuredRobotOptions(s.name||'')+'</select>';}
if(isRemote){row+='<input type="text" placeholder="目标ChatterId（必填）" value="'+esc(s.chatterId||'')+'" onchange="dlgContacts['+i+'].chatterId=this.value">';}
row+='<input type="text" placeholder="备注'+(isRemote?'（远程必填）':'')+'" value="'+esc(s.remark||'')+'" onchange="dlgContacts['+i+'].remark=this.value">';
row+='<button class="btn btn-danger btn-sm" onclick="dlgContacts.splice('+i+',1);renderContacts()"><span class="material-icons">close</span></button></div>';
return row;
}).join('');
}
function addContact(){dlgContacts.push({name:'',remark:'',isRemote:false,chatterId:''});renderContacts()}

function renderTeamSharedOwners(){
var c=document.getElementById('dTeamSharedOwnerList');
if(!c)return;
if(!dlgTeamSharedOwners.length){c.innerHTML='<span style="color:#9e9e9e;font-size:13px">暂未授权远程用户</span>';return}
c.innerHTML=dlgTeamSharedOwners.map(function(id,i){return '<span class="chip">'+esc(id)+'<span class="material-icons" onclick="removeTeamSharedOwner('+i+')">close</span></span>'}).join('');
}
function addTeamSharedOwner(){
var input=document.getElementById('dTeamSharedOwnerInput');
if(!input)return;
input.value.split(/[,，\n]/).map(function(v){return v.trim()}).filter(Boolean).forEach(function(id){if(dlgTeamSharedOwners.indexOf(id)<0)dlgTeamSharedOwners.push(id)});
input.value='';renderTeamSharedOwners();input.focus();
}
function removeTeamSharedOwner(i){dlgTeamSharedOwners.splice(i,1);renderTeamSharedOwners()}
function handleTeamSharedOwnerKey(event){if(event.key==='Enter'){event.preventDefault();addTeamSharedOwner()}}

async function saveRobot(){
clearRobotValidation();
var name=document.getElementById('dName').value.trim();
var workDir=document.getElementById('dWorkDir').value.trim();
if(!name){showRobotValidation('basic','dName','名称不能为空');return}
if(!workDir){showRobotValidation('basic','dWorkDir','工作目录不能为空');return}
var duplicateRobot=(config.robots||[]).some(function(robot,index){return String(robot.name||'').trim()===name&&!(robotDialogMode==='edit'&&index===editIdx)});
if(duplicateRobot){showRobotValidation('basic','dName','智能体名称已存在');return}
addTeamSharedOwner();
var orig=robotDialogMode==='edit'?config.robots[editIdx]:(robotDialogMode==='copy'?config.robots[robotDialogSourceIndex]:{});
var provider=document.getElementById('dProvider').value;
var piConfig=orig.embeddedPi;
if(provider==='EMBEDDED_PI_ACP'){
piConfig={stateId:orig.embeddedPi&&orig.embeddedPi.stateId,baseUrl:document.getElementById('dPiBaseUrl').value.trim(),apiKey:document.getElementById('dPiApiKey').value.trim(),contextWindow:Number(document.getElementById('dPiContext').value),maxTokens:Number(document.getElementById('dPiMaxTokens').value)};
if(!document.getElementById('dModel').value.trim()){showRobotValidation('agent','dModel','请输入模型');return}
try{var piUrl=new URL(piConfig.baseUrl);if(['http:','https:'].indexOf(piUrl.protocol)<0||piUrl.username||piUrl.password||piUrl.search||piUrl.hash)throw new Error()}catch(e){showRobotValidation('agent','dPiBaseUrl','请输入 HTTP/HTTPS API 地址');return}
if(!piConfig.apiKey){showRobotValidation('agent','dPiApiKey','请输入 API Key');return}
if(!Number.isSafeInteger(piConfig.contextWindow)||!Number.isSafeInteger(piConfig.maxTokens)||piConfig.contextWindow<1024||piConfig.maxTokens<1||piConfig.maxTokens+512>=piConfig.contextWindow){document.querySelector('#embeddedPiGroup details').open=true;showRobotValidation('agent','dPiContext','上下文窗口需大于最大输出，并预留至少 512 Token');return}
}
var robot=Object.assign({},orig,{
name:name,signature:document.getElementById('dSig').value.trim(),
workDir:workDir,agentProvider:provider,
embeddedPi:piConfig,
_piCopyFromStateId:robotDialogMode==='copy'&&piConfig?piConfig.stateId:undefined,
providerVersion:isNpmProvider(provider)?(document.getElementById('dProviderVersion').value||undefined):undefined,
avatar:document.getElementById('dAvatar').value.trim(),
model:document.getElementById('dModel').value.trim()||undefined,
apiKey:document.getElementById('dApiKey').value.trim()||undefined,
codexHome:document.getElementById('dCodexHome').value.trim()||undefined,
deepSeekBaseUrl:provider==='DEEPSEEK_HARNESS_ACP'?(document.getElementById('dDeepSeekBaseUrl').value.trim()||undefined):undefined,
dshHome:provider==='DEEPSEEK_HARNESS_ACP'?(document.getElementById('dDshHome').value.trim()||undefined):undefined,
dshAgentPreset:provider==='DEEPSEEK_HARNESS_ACP'?(document.getElementById('dDshAgentPreset').value||'standard'):undefined,
permissionPolicy:provider==='DEEPSEEK_HARNESS_ACP'?(document.getElementById('dPermissionPolicy').value||'REJECT'):undefined,
proxyEnabled:document.getElementById('dProxyEnabled').checked,
httpProxy:document.getElementById('dHttpProxy').value.trim()||undefined,
noProxy:document.getElementById('dNoProxy').value.trim()||undefined,
enabled:orig.enabled!==false,
abilityAutoRefresh:document.getElementById('dAbility').checked,
onlySubAgent:document.getElementById('dOnlySub').checked,
onlyTeamMember:document.getElementById('dOnlyTeam').checked,
teamSharedWithChatterIds:dlgTeamSharedOwners.slice(),
scheduleEnabled:document.getElementById('dSchedule').checked,
observationEnabled:document.getElementById('dObservation').checked,
autoSleep:{enabled:document.getElementById("dAutoSleep").checked,idleMinutes:Math.max(1,parseInt(document.getElementById("dAutoSleepIdle").value)||30)},
autoNewSession:{enabled:document.getElementById('dAutoNewSession').checked,checkIntervalMinutes:Math.max(1,parseInt(document.getElementById('dAutoNewSessionCheck').value)||360),idleMinutes:Math.max(1,parseInt(document.getElementById('dAutoNewSessionIdle').value)||180)}
});
var memReadEnabled=document.getElementById('dMemRead').checked;
var memWriteEnabled=document.getElementById('dMemWrite').checked;
if(memReadEnabled||memWriteEnabled){
var memExecutionMode=document.getElementById('dMemExecutionMode').value;
var memRobotName=document.getElementById('dMemRobot').value.trim();
if(memExecutionMode==='robot'&&!memRobotName){showRobotValidation('memory','dMemRobot','请选择记忆智能体');return}
robot.memory={readEnabled:memReadEnabled,writeEnabled:memWriteEnabled,scope:document.getElementById('dMemScope').value,
executionMode:memExecutionMode,robotName:memRobotName||undefined,
extractIntervalTurns:parseInt(document.getElementById('dMemExtract').value)||5,
indexMaxLines:parseInt(document.getElementById('dMemIndex').value)||200,
maxEntriesPerProject:parseInt(document.getElementById('dMemProject').value)||30,
projectExpireDays:parseInt(document.getElementById('dMemExpire').value)||30,
subClientTimeout:parseInt(document.getElementById('dMemTimeout').value)||120,
model:document.getElementById('dMemModel').value.trim()||undefined,
dreamEnabled:document.getElementById('dMemDream').checked,
dreamMinHours:parseInt(document.getElementById('dMemDreamH').value)||24,
dreamMinSessions:parseInt(document.getElementById('dMemDreamS').value)||5};
}else{robot.memory={readEnabled:false,writeEnabled:false}}
robot.subAgents=dlgSubAgents.filter(function(s){return s.name&&s.name.trim()});
robot.contacts=dlgContacts.filter(function(s){
if(!s.name||!s.name.trim())return false;
if(s.isRemote&&(!s.chatterId||!s.chatterId.trim())){showRobotValidation('relations',null,'远程联系人 "'+s.name+'" 必须填写目标ChatterId');return false;}
if(s.isRemote&&(!s.remark||!s.remark.trim())){showRobotValidation('relations',null,'远程联系人 "'+s.name+'" 必须填写备注');return false;}
return true;
});
if(document.querySelector('[data-robot-tab="relations"].has-error'))return;
// 校验通讯录中不允许重名
var contactNames=robot.contacts.map(function(c){return c.name.trim()});
var dupName=contactNames.find(function(n,i){return contactNames.indexOf(n)!==i});
if(dupName){showRobotValidation('relations',null,'通讯录中存在重名: "'+dupName+'"');return;}
var previous=robotDialogMode==='edit'?config.robots[editIdx]:null,insertIndex=robotDialogMode==='edit'?editIdx:config.robots.length,runtimeName=previous?(previous._runtimeName||previous.name||''):'',wasDirty=dirty,button=document.getElementById('robotDialogSubmit');
Object.defineProperty(robot,'_runtimeName',{value:runtimeName,writable:true,enumerable:false});
if(robotDialogMode==='edit')config.robots[editIdx]=robot;else config.robots.push(robot);
markDirty();button.disabled=true;var ok=await saveConfig(true);
if(!ok){button.disabled=false;if(previous)config.robots[insertIndex]=previous;else config.robots.splice(insertIndex,1);dirty=wasDirty;return}
await rememberRobotModels(robot);
button.disabled=false;closeDialog('robotDialog');render();
showSnackbar(robotDialogMode==='edit'?'智能体已更新并保存':(robotDialogMode==='copy'?'智能体已复制并保存':'智能体已添加并保存'));applyRobotConfig(insertIndex).then(function(){showSnackbar('智能体已自动刷新并开启会话')}).catch(function(e){showSnackbar('智能体已保存，但自动刷新或开启会话失败：'+e.message)});
}

function openDirPicker(inputId,title){
dirTargetInputId=inputId;
var cur=document.getElementById(inputId).value.trim();
document.getElementById('dirDialogTitle').textContent=title||'选择文件夹';
showDialog('dirDialog');
browseDir(cur);
}

async function browseDir(path){
currentDirPath=path||'';
document.getElementById('dirPath').textContent=currentDirPath||'加载中...';
document.getElementById('dirList').innerHTML='<div style="padding:16px;color:#9e9e9e">加载中...</div>';
try{
var r=await api('/api/browse-dir?path='+encodeURIComponent(path||''));
if(!r.ok)throw new Error('HTTP '+r.status);
var data=await r.json();
currentDirPath=data.path||path;
document.getElementById('dirPath').textContent=currentDirPath;
var list=document.getElementById('dirList');
list.innerHTML='';
if(data.roots&&data.roots.length>1){data.roots.forEach(function(root){appendDirItem(list,'storage',root.name,root.path)})}
if(data.parent){appendDirItem(list,'arrow_upward','..',data.parent)}
if(data.error){appendDirMessage(list,'路径不存在或不是目录','#d32f2f')}
else if(data.dirs&&data.dirs.length){data.dirs.forEach(function(d){appendDirItem(list,'folder',d.name,d.path)})}
else{appendDirMessage(list,'无子目录','#9e9e9e')}
}catch(e){document.getElementById('dirList').innerHTML='<div style="padding:16px;color:#d32f2f">加载失败</div>'}
}

function appendDirItem(container,icon,label,path){
var item=document.createElement('div');
item.className='dir-item';
var iconEl=document.createElement('span');
iconEl.className='material-icons';
iconEl.textContent=icon;
item.appendChild(iconEl);
item.appendChild(document.createTextNode(label));
item.onclick=function(){browseDir(path)};
container.appendChild(item);
}
function appendDirMessage(container,text,color){
var item=document.createElement('div');
item.style.cssText='padding:16px;color:'+color;
item.textContent=text;
container.appendChild(item);
}
function confirmDir(){var input=document.getElementById(dirTargetInputId);if(input)input.value=currentDirPath;closeDialog('dirDialog');if(dirTargetInputId==='dWorkDir'||dirTargetInputId==='dCodexHome'||dirTargetInputId==='dDshHome')loadRobotModels(false)}
function syncDialogScrollLock(){document.body.classList.toggle('dialog-open',!!document.querySelector('.dialog-overlay.show'))}
function showDialog(id){var dialog=document.getElementById(id);if(dialog){dialog.classList.add('show');syncDialogScrollLock()}}
function closeDialog(id){if(id==='channelDialog')stopChannelKnownTargetsRefresh();var dialog=document.getElementById(id);if(dialog)dialog.classList.remove('show');syncDialogScrollLock()}

async function applyGlobalProxy(){
var enabled=document.getElementById('globalProxy').checked;
config.robots.forEach(function(robot){robot.proxyEnabled=enabled&&hasProxyAddress(robot)});
render();
var ok=await saveConfig(true);
if(!ok){await loadConfig();return}
var affected=enabled?config.robots.filter(hasProxyAddress).length:config.robots.length;
await reloadService((enabled?'已开启 ':'已关闭 ')+affected+' 个智能体的网络代理，配置已应用');
}

async function saveConfig(silent){
config.configUi={enabled:document.getElementById('cfgEnabled').checked,port:parseInt(document.getElementById('cfgPort').value)||currentPort()};
delete config.autoNewSession;
delete config.globalProxyEnabled;
try{var r=await api('/api/config',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(config)});
var d=await r.json();if(d.ok){if(d.embeddedPiStates)(config.robots||[]).forEach(function(robot){var state=d.embeddedPiStates[robot.name];if(state&&robot.embeddedPi){robot.embeddedPi.stateId=state.stateId;robot.embeddedPi.apiKey=state.apiKey;delete robot._piCopyFromStateId}});clearDirty();if(!silent)showSnackbar('配置已保存')}else{showSnackbar('保存失败'+(d.error?':'+d.error:''));return false}}
catch(e){showSnackbar('保存失败:'+e.message);return false}
return true;
}

async function refreshService(btn){
if(!await showConfirm('将保存当前页面的全部配置，并刷新运行中的服务使配置立即生效。',{title:'保存并应用全部配置？',confirmText:'保存并应用'}))return;
btn.disabled=true;btn.innerHTML='<span class="material-icons">hourglass_top</span>应用中...';
var ok=await saveConfig(true);
if(!ok){btn.disabled=false;btn.innerHTML='<span class="material-icons">auto_awesome</span><span class="btn-text">保存并应用</span>';return}
await reloadService('配置已保存并应用');
btn.disabled=false;btn.innerHTML='<span class="material-icons">auto_awesome</span><span class="btn-text">保存并应用</span>';
}

async function reloadService(successMessage){
try{var r=await api('/api/refresh',{method:'POST'});var d=await r.json();
if(d.ok){await loadChannelBindingTargets();renderChannels();showSnackbar(successMessage)}else showSnackbar('应用失败:'+(d.error||''));return d.ok}
catch(e){showSnackbar('应用失败:'+e.message);return false}
}

async function applyRobotConfig(index){
var robot=config.robots[index];if(!robot)throw new Error('智能体不存在');var name=(robot.name||'').trim(),previousName=robot._runtimeName||'';
markItemRefreshing('robots',name);try{var r=await api('/api/refresh-robot',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({previousName:previousName,name:name})});var d=await r.json();
if(!r.ok||!d.ok)throw new Error(d.error||'刷新失败');robot._runtimeName=name;await loadStarweaveSessions(false);await loadStarweaveTeams(false);await refreshChannelBindingTargets(false);return d}
finally{await loadItemRefreshStatus(false);renderRobots()}
}
async function refreshRobot(index){
var btn=typeof event!=='undefined'&&event.target?event.target.closest('button'):null;
var robot=config.robots[index];if(!robot)return;var name=(robot.name||'').trim();
if(itemOperationPending('robots',name)){showSnackbar(itemRefreshMessage('robots',name));return}
if(!await showConfirm('将保存当前配置并应用到智能体「'+name+'」。',{title:'保存并应用到智能体？',confirmText:'保存并应用'}))return;
if(btn){btn.disabled=true;btn.innerHTML='<span class="material-icons">hourglass_top</span>'}
var ok=await saveConfig(true);
if(!ok){if(btn){btn.disabled=false;btn.innerHTML='<span class="material-icons">refresh</span>'}return}
try{await applyRobotConfig(index);showSnackbar('配置已应用到智能体「'+name+'」')}
catch(e){showSnackbar('应用失败:'+e.message)}
finally{if(btn){btn.disabled=false;btn.innerHTML='<span class="material-icons">refresh</span>'}}
}

function taskStatusLabel(status){return {START:'未开始',IN_PROGRESS:'进行中',COMPLETED:'已完成',CANCELLED:'已取消',SUSPENDED:'已挂起'}[status]||status||'未知'}
function taskStatusIcon(status){return {START:'radio_button_unchecked',IN_PROGRESS:'pending',COMPLETED:'check_circle',CANCELLED:'cancel',SUSPENDED:'pause_circle'}[status]||'help_outline'}
function taskClass(value){return normalized(value).replace(/_/g,'-')}
function taskDate(value){if(!value)return '—';var date=new Date(value);if(isNaN(date.getTime()))return String(value);return date.toLocaleString('zh-CN',{hour12:false})}
function taskRequestId(){return 'task-ui-'+Date.now().toString(36)+'-'+Math.random().toString(36).slice(2,10)}
function taskData(result){var data=result&&result.data!==undefined?result.data:result;if(data&&data.data!==undefined&&Object.keys(data).length<=3)data=data.data;return data||{}}

async function pollStarweaveTeamStates(){if(activePage!=='teams'||starTeams.polling)return;starTeams.polling=true;var instanceId=curInstance&&curInstance.instanceId,openTeamId=teamSession.teamId;try{var response=await api('/api/starweave/v1/teams'),result=await response.json();if(instanceId!==(curInstance&&curInstance.instanceId)||activePage!=='teams')return;if(!response.ok||result.accepted===false)throw new Error(result.message||'团队状态加载失败');var nextItems=(((result.data||{}).data||{}).teams)||[],changed=JSON.stringify(starTeams.items)!==JSON.stringify(nextItems);starTeams.items=nextItems;document.getElementById('teamCount').textContent=starTeams.items.length;if(changed)renderStarweaveTeams();if(openTeamId&&openTeamId===teamSession.teamId&&document.getElementById('teamSessionDialog').classList.contains('show')){var team=nextItems.filter(function(item){return item.teamId===openTeamId})[0];if(team){var previous=selectedTeamSessionMember(),previousSessionId=previous&&previous.sessionId;teamSession.members=mergeTeamSessionMembers(teamSession.members,team.members||[]);var selected=selectedTeamSessionMember();if(selected)teamSession.acpClientId=selected.acpClientId;renderTeamSessionMembers();renderTeamSessionDetail(false);if(selected&&selected.sessionId&&previousSessionId!==selected.sessionId){closeTeamSessionStream();teamSession.messages=[];teamSession.liveItems=[];teamSession.lastSeq=0;loadTeamSessionSnapshot()}else connectTeamSessionStream()}}if(changed)refreshChannelBindingTargets(false)}catch(e){}finally{starTeams.polling=false}}
function starResourceUrl(action,resourceId){var s=selectedStarSession(),url='/api/starweave/v1/sessions/resources'+(action?'/'+action:'')+'?groupId='+encodeURIComponent(s.groupId)+'&sessionId='+encodeURIComponent(s.sessionId)+'&generation='+encodeURIComponent(s.generation);if(resourceId)url+='&resourceId='+encodeURIComponent(resourceId);if(curInstance&&curInstance.instanceId)url+='&instance='+encodeURIComponent(curInstance.instanceId);return url}
function openStarweaveResources(){var s=selectedStarSession();if(!s){showSnackbar('请先选择会话');return}if(starSessionOperationPending())return;if(!s.sessionId){showSnackbar('当前会话尚未创建 session，暂无会话文件');return}document.getElementById('starFileSubtitle').textContent=s.robotName+' · '+s.sessionId;showDialog('starFileDialog');loadStarweaveResources()}
async function loadStarweaveResources(){var list=document.getElementById('starFileList');list.innerHTML='<div class="resource-spinner"></div>';try{var response=await api(starResourceUrl('')),result=await response.json();if(!response.ok||result.accepted===false)throw new Error(result.message||'读取失败');var files=(result.data&&result.data.resources)||[];list.innerHTML=files.length?files.map(function(file){return '<button class="resource-node file" onclick="previewStarweaveResource(\''+esc(file.resourceId)+'\')"><span class="material-icons">description</span><span>'+esc(file.fileName)+'</span><small>'+formatBytes(file.size||0)+'</small></button>'}).join(''):'<div class="resource-empty"><span class="material-icons">folder_off</span><strong>暂无会话文件</strong></div>'}catch(e){list.innerHTML='<div class="resource-empty"><span class="material-icons">error_outline</span><strong>'+esc(e.message)+'</strong></div>'}}
async function previewStarweaveResource(resourceId){var viewer=document.getElementById('starFilePreview');viewer.innerHTML='<div class="resource-spinner"></div>';try{var response=await api(starResourceUrl('preview',resourceId)),result=await response.json();if(!response.ok||result.accepted===false)throw new Error(result.message||'预览失败');var file=result.data.resource;document.getElementById('starFileName').textContent=file.fileName;var download=document.getElementById('starFileDownload');download.href=starResourceUrl('download',resourceId);download.style.display='inline-flex';if(file.kind==='image')viewer.innerHTML='<img class="resource-image" alt="'+esc(file.fileName)+'" src="data:'+esc(file.contentType)+';base64,'+file.contentBase64+'">';else if(file.kind==='text')viewer.innerHTML='<pre class="resource-code">'+esc(file.content||'')+'</pre>'+(file.truncated?'<div class="resource-truncated">预览已截断，可下载完整文件</div>':'');else viewer.innerHTML='<div class="resource-empty"><span class="material-icons">draft</span><strong>此文件不支持在线预览</strong><small>可使用右上角下载</small></div>'}catch(e){viewer.innerHTML='<div class="resource-empty"><span class="material-icons">error_outline</span><strong>'+esc(e.message)+'</strong></div>'}}
var agentImportState=null;
async function exportAgentResources(index){
var robot=config.robots[index];if(!robot)return;
showSnackbar('正在打包智能体资源…');
try{var response=await api('/api/agent-resources/export?robot='+encodeURIComponent(robot.name||''));
if(!response.ok){var error=await response.json();throw new Error(error.message||'导出失败')}
var blob=await response.blob(),url=URL.createObjectURL(blob),link=document.createElement('a');link.href=url;link.download=(robot.name||'agent').replace(/[\\/:*?"<>|]/g,'_')+'-resources.zip';document.body.appendChild(link);link.click();link.remove();setTimeout(function(){URL.revokeObjectURL(url)},60000);showSnackbar('智能体资源已导出')
}catch(e){showSnackbar('导出失败：'+e.message)}
}
function openAgentImport(index){
if(agentImportState&&agentImportState.busy){showDialog('agentImportDialog');showSnackbar('请等待当前导入完成');return}
var robot=config.robots[index];if(!robot)return;
agentImportState={name:robot.name||'',instance:curInstance&&curInstance.instanceId,busy:false,validationShown:false};
document.getElementById('agentImportTarget').textContent='目标智能体：'+(robot.name||'未命名');
document.getElementById('agentImportFile').value='';document.getElementById('agentImportFile').disabled=false;document.querySelector('#agentImportFileError span:last-child').textContent='请先选择要导入的 ZIP 文件';
document.querySelectorAll('#agentImportKinds input').forEach(function(input){input.checked=false;input.disabled=false});
var result=document.getElementById('agentImportResult');result.textContent='';result.classList.remove('error');updateAgentImportSubmit();showDialog('agentImportDialog');
}
function selectedAgentImportKinds(){return Array.from(document.querySelectorAll('#agentImportKinds input:checked')).map(function(input){return input.value})}
function validateAgentImportSelection(reveal){var fileInput=document.getElementById('agentImportFile'),hasFile=!!fileInput.files.length,hasKinds=selectedAgentImportKinds().length>0,fileGroup=document.getElementById('agentImportFileGroup'),kindsGroup=document.getElementById('agentImportKindsGroup');fileGroup.classList.toggle('is-invalid',!!reveal&&!hasFile);kindsGroup.classList.toggle('is-invalid',!!reveal&&!hasKinds);fileInput.setAttribute('aria-invalid',String(!!reveal&&!hasFile));document.getElementById('agentImportKinds').setAttribute('aria-invalid',String(!!reveal&&!hasKinds));return hasFile&&hasKinds}
function updateAgentImportSubmit(){var state=agentImportState,valid=false;if(state){valid=validateAgentImportSelection(state.validationShown);if(document.getElementById('agentImportFile').files.length)document.querySelector('#agentImportFileError span:last-child').textContent='请先选择要导入的 ZIP 文件';if(state.validationShown&&valid){var result=document.getElementById('agentImportResult');if(result.classList.contains('error')){result.textContent='';result.classList.remove('error')}}}document.getElementById('agentImportSubmit').disabled=!state||state.busy}
async function submitAgentImport(){
var state=agentImportState,kinds=selectedAgentImportKinds(),file=document.getElementById('agentImportFile').files[0];
if(!state||state.busy)return;state.validationShown=true;
if(!validateAgentImportSelection(true)){var result=document.getElementById('agentImportResult');result.textContent=!file&&!kinds.length?'请选择 ZIP 文件并勾选要导入的资源':(!file?'请选择要导入的 ZIP 文件':'请至少勾选一种要导入的资源');result.classList.add('error');var first=document.querySelector('#agentImportDialog .agent-import-field.is-invalid input');if(first)first.focus();return}
if(file.size>64*1024*1024){var fileGroup=document.getElementById('agentImportFileGroup'),fileError=document.querySelector('#agentImportFileError span:last-child'),result=document.getElementById('agentImportResult');fileGroup.classList.add('is-invalid');fileError.textContent='ZIP 文件不能超过 64 MiB';result.textContent='请选择不超过 64 MiB 的 ZIP 文件';result.classList.add('error');document.getElementById('agentImportFile').focus();return}
var labels={mcp:'MCP',skill:'Skill',memory:'记忆'};
if(!await showConfirm('将向「'+state.name+'」导入 '+kinds.map(function(kind){return labels[kind]}).join('、')+'，并覆盖这些类别的已有同名文件。',{title:'确认导入资源？',confirmText:'继续导入'}))return;
state.busy=true;updateAgentImportSubmit();document.getElementById('agentImportFile').disabled=true;document.querySelectorAll('#agentImportKinds input').forEach(function(input){input.disabled=true});
var resultElement=document.getElementById('agentImportResult');resultElement.classList.remove('error');resultElement.textContent='正在校验、替换路径并导入…';
try{var url='/api/agent-resources/import?robot='+encodeURIComponent(state.name)+'&kinds='+encodeURIComponent(kinds.join(','))+'&overwrite=true';if(state.instance)url+='&instance='+encodeURIComponent(state.instance);
var response=await fetch(url,{method:'POST',headers:{'Content-Type':'application/zip'},body:file}),result=await response.json();
if(!response.ok)throw new Error(result.message||'导入失败');
var lines=kinds.map(function(kind){return labels[kind]+'：成功 '+((result.counts||{})[kind]||0)+' 个文件'});
if(result.failed&&result.failed.length){lines.push('失败 '+result.failed.length+' 个文件：');result.failed.forEach(function(item){lines.push(item.file+'：'+item.reason)})}
else lines.push((result.succeeded||[]).length?'导入成功。MCP 和 Skill 配置将在刷新智能体或新建会话后生效。':'所选类别在包中没有可导入文件，未修改任何文件。');
resultElement.textContent=lines.join('\n');showSnackbar(result.ok?'导入完成':'部分文件导入失败，请查看明细');
}catch(e){resultElement.classList.add('error');resultElement.textContent='导入失败：'+e.message;showSnackbar('导入失败，请查看明细')}
finally{state.busy=false;document.getElementById('agentImportFile').disabled=false;document.querySelectorAll('#agentImportKinds input').forEach(function(input){input.disabled=false});updateAgentImportSubmit()}
}
async function openAgentResource(index,kind){
var robot=config.robots[index];if(!robot)return;
if(resourceDream.timer)clearTimeout(resourceDream.timer);
resourceDream={robot:kind==='memory'?(robot.name||''): '',available:false,running:false,pending:kind==='memory',pendingText:'正在检查…',timer:null};
resourceState={robotIndex:index,kind:kind,selectedId:'',nodes:[]};
var conflictButton=document.getElementById('resourceConflictButton');if(conflictButton)conflictButton.style.display=robot.agentProvider==='REMOTE_STARCORE_ACP'&&robot.remotePlatform!=='BROWSER'&&(kind==='skill'||kind==='memory')?'inline-flex':'none';
var meta=resourceMeta(kind);
document.getElementById('resourceDialogTitle').textContent=meta.title;
document.getElementById('resourceDialogIcon').textContent=meta.icon;
document.getElementById('resourceTreeTitle').textContent=meta.tree;
document.getElementById('resourceDialogSubtitle').textContent=(robot.name||'未命名')+' · '+providerDisplayName(robot.agentProvider||'KIRO_CLI')+(robot.agentProvider==='REMOTE_STARCORE_ACP'&&robot.remotePlatform!=='BROWSER'&&(kind==='skill'||kind==='memory')?' · 查看 Starweave 持久副本；在线容器自动双向同步':'');
document.getElementById('resourceFileName').textContent='请选择文件';
document.getElementById('resourceFileMeta').textContent='';
updateAgentMemoryDreamButton();
showDialog('resourceDialog');
document.body.classList.add('resource-modal-open');
if(kind==='memory')checkAgentMemoryDream();
await reloadAgentResource();
}
function closeAgentResource(){if(resourceDream.timer)clearTimeout(resourceDream.timer);resourceDream.robot='';closeDialog('resourceDialog');document.body.classList.remove('resource-modal-open');resourceState.selectedId=''}
function updateAgentMemoryDreamButton(){
var button=document.getElementById('resourceDreamButton'),label=document.getElementById('resourceDreamLabel');
button.style.display=resourceState.kind==='memory'?'inline-flex':'none';
button.disabled=resourceDream.pending||resourceDream.running||!resourceDream.available;
label.textContent=resourceDream.running?'记忆整理中…':(resourceDream.pending?resourceDream.pendingText:(resourceDream.available?'整理记忆':'暂不可整理'));
button.title=resourceDream.running?'记忆正在整理，请稍候':(resourceDream.available?'立即整理当前智能体的记忆':'记忆服务未运行或未开启写入');
}
function scheduleAgentMemoryDreamCheck(){
if(resourceDream.timer)clearTimeout(resourceDream.timer);
if(resourceDream.robot&&resourceDream.running)resourceDream.timer=setTimeout(checkAgentMemoryDream,2000);
}
async function checkAgentMemoryDream(){
var robot=resourceDream.robot;if(!robot)return;
try{
var response=await api('/api/agent-resources/dream?robot='+encodeURIComponent(robot)),data=await response.json();
if(resourceDream.robot!==robot)return;
if(!response.ok||data.ok===false)throw new Error(data.message||'状态读取失败');
var completed=resourceDream.running&&!data.running;
resourceDream.available=!!data.available;resourceDream.running=!!data.running;resourceDream.pending=false;
updateAgentMemoryDreamButton();
if(completed){showSnackbar('记忆整理已结束');reloadAgentResource()}
}catch(error){if(resourceDream.robot!==robot)return;resourceDream.pending=false;updateAgentMemoryDreamButton();if(!resourceDream.running)showSnackbar('记忆整理状态读取失败：'+error.message)}
scheduleAgentMemoryDreamCheck();
}
async function triggerAgentMemoryDream(){
var robot=resourceDream.robot;if(!robot||resourceDream.pending||resourceDream.running||!resourceDream.available)return;
resourceDream.pending=true;resourceDream.pendingText='正在触发整理…';updateAgentMemoryDreamButton();
try{
var response=await api('/api/agent-resources/dream?robot='+encodeURIComponent(robot),{method:'POST'}),data=await response.json();
if(resourceDream.robot!==robot)return;
if(!response.ok||data.ok===false){if(response.status===409){resourceDream.running=true;showSnackbar('记忆正在整理中')}else throw new Error(data.message||'触发失败')}
else{resourceDream.running=!!data.running;showSnackbar(resourceDream.running?'记忆整理已开始':'记忆整理已结束');if(!resourceDream.running)reloadAgentResource()}
}catch(error){if(resourceDream.robot!==robot)return;showSnackbar('触发记忆整理失败：'+error.message);await checkAgentMemoryDream()}
finally{if(resourceDream.robot===robot){resourceDream.pending=false;updateAgentMemoryDreamButton();scheduleAgentMemoryDreamCheck()}}
}
async function reloadAgentResource(){
var robot=config.robots[resourceState.robotIndex],kind=resourceState.kind;if(!robot||!kind)return;
var expectedRobot=resourceState.robotIndex,expectedKind=kind;
document.getElementById('resourceTree').innerHTML='<div class="resource-empty"><span class="resource-spinner"></span><small>正在读取目录…</small></div>';
document.getElementById('resourceViewer').innerHTML='<div class="resource-empty"><span class="material-icons">description</span><strong>从左侧选择一个文件</strong><small>文件内容将在这里以适合的方式展示</small></div>';
try{
var response=await api('/api/agent-resources/tree?robot='+encodeURIComponent(robot.name||'')+'&kind='+encodeURIComponent(kind));
var data=await response.json();
if(expectedRobot!==resourceState.robotIndex||expectedKind!==resourceState.kind)return;
if(!response.ok||data.ok===false)throw new Error(data.message||data.error||'目录读取失败');
resourceState.nodes=data.nodes||[];renderAgentResourceTree();
var first=kind==='skill'?null:findFirstResourceFile(resourceState.nodes);if(first)loadAgentResourceContent(first.id,first.name);
}catch(error){
document.getElementById('resourceTree').innerHTML=resourceEmptyHtml('error_outline','目录读取失败',error.message);
}
}
function resourceEmptyHtml(icon,title,detail){return '<div class="resource-empty"><span class="material-icons">'+esc(icon)+'</span><strong>'+esc(title)+'</strong><small>'+esc(detail||'')+'</small></div>'}
function findFirstResourceFile(nodes){
for(var i=0;i<(nodes||[]).length;i++){if(nodes[i].type==='file')return nodes[i];var found=findFirstResourceFile(nodes[i].children||[]);if(found)return found}return null;
}
function renderAgentResourceTree(){
var tree=document.getElementById('resourceTree'),meta=resourceMeta(resourceState.kind);
if(!resourceState.nodes.length){tree.innerHTML=resourceEmptyHtml(meta.icon,'暂无可查看内容',meta.empty);return}
tree.innerHTML=resourceState.nodes.map(function(node){return resourceTreeNodeHtml(node,0)}).join('');
}
function resourceTreeNodeHtml(node,depth){
var isDir=node.type==='directory',active=node.id===resourceState.selectedId;
var collapsed=isDir&&resourceState.kind==='skill';
var icon=isDir?(collapsed?'folder':'folder_open'):(node.renderMode==='markdown'?'description':(node.renderMode==='structured'?'dashboard':'code'));
var row='<button type="button" class="resource-tree-row'+(active?' active':'')+'" style="padding-left:'+(8+depth*16)+'px" data-resource-id="'+esc(node.id)+'" data-resource-name="'+esc(node.name)+'" onclick="'+(isDir?'toggleResourceNode(this)':'selectResourceNode(this)')+'"><span class="material-icons resource-node-icon">'+icon+'</span><span class="resource-tree-label" title="'+esc(node.name)+'">'+esc(node.name)+'</span></button>';
if(!isDir)return row;
return '<div class="resource-tree-branch">'+row+'<div class="resource-tree-children'+(collapsed?' collapsed':'')+'">'+(node.children||[]).map(function(child){return resourceTreeNodeHtml(child,depth+1)}).join('')+'</div></div>';
}
function toggleResourceNode(button){
var children=button.nextElementSibling;if(!children)return;var collapsed=children.classList.toggle('collapsed');var icon=button.querySelector('.resource-node-icon');if(icon)icon.textContent=collapsed?'folder':'folder_open';
}
function selectResourceNode(button){loadAgentResourceContent(button.getAttribute('data-resource-id'),button.getAttribute('data-resource-name'))}
async function loadAgentResourceContent(id,name){
var robot=config.robots[resourceState.robotIndex],kind=resourceState.kind;if(!robot)return;
resourceState.selectedId=id;
var activeRow=null;document.querySelectorAll('#resourceTree .resource-tree-row').forEach(function(row){var active=row.getAttribute('data-resource-id')===id;row.classList.toggle('active',active);if(active)activeRow=row});
if(activeRow)revealResourceTreeRow(activeRow);
document.getElementById('resourceFileName').textContent=name||'文件内容';document.getElementById('resourceFileMeta').textContent='';
var viewer=document.getElementById('resourceViewer');viewer.scrollTop=0;viewer.innerHTML='<div class="resource-empty"><span class="resource-spinner"></span><small>正在读取文件…</small></div>';
try{
var response=await api('/api/agent-resources/content?robot='+encodeURIComponent(robot.name||'')+'&kind='+encodeURIComponent(kind)+'&resource='+encodeURIComponent(id));
var data=await response.json();if(id!==resourceState.selectedId)return;
if(!response.ok||data.ok===false)throw new Error(data.message||data.error||data.code||'文件读取失败');
renderAgentResourceContent(data.data||{});
}catch(error){document.getElementById('resourceViewer').innerHTML=resourceEmptyHtml('error_outline','文件无法展示',error.message)}
}
function revealResourceTreeRow(row){
var current=row.parentElement;while(current&&current.id!=='resourceTree'){if(current.classList&&current.classList.contains('resource-tree-children')){current.classList.remove('collapsed');var folder=current.previousElementSibling,icon=folder&&folder.querySelector('.resource-node-icon');if(icon)icon.textContent='folder_open'}current=current.parentElement}row.scrollIntoView({block:'nearest'})
}
function renderAgentResourceContent(data){
document.getElementById('resourceFileName').textContent=data.displayName||data.fileName||'文件内容';
document.getElementById('resourceFileMeta').textContent=(data.lineCount||0)+' 行 · '+formatResourceBytes(data.bytesRead||0)+' · '+(data.charset||'UTF-8');
var viewer=document.getElementById('resourceViewer'),content=String(data.content||'');
if(data.renderMode==='markdown')viewer.innerHTML='<article class="markdown-body">'+renderMarkdown(content)+'</article>';
else if(data.renderMode==='structured')viewer.innerHTML=renderStructuredMemory(content,data.displayName||data.fileName,data.fileName);
else viewer.innerHTML='<pre class="resource-code"><code>'+highlightResourceCode(content,data.language||'text')+'</code></pre>';
}
function formatResourceBytes(bytes){if(bytes<1024)return bytes+' B';if(bytes<1024*1024)return (bytes/1024).toFixed(1)+' KB';return (bytes/1024/1024).toFixed(1)+' MB'}
function highlightResourceCode(content,language){
if(language==='json'){
var pattern=/("(?:\\.|[^"\\])*")(\s*:)?|\b(true|false|null)\b|-?\b\d+(?:\.\d+)?(?:[eE][+-]?\d+)?\b/g,out='',last=0,match;
while((match=pattern.exec(content))){out+=esc(content.slice(last,match.index));if(match[1])out+='<span class="'+(match[2]?'syn-key':'syn-string')+'">'+esc(match[1])+'</span>'+(match[2]?esc(match[2]):'');else if(match[3])out+='<span class="syn-literal">'+esc(match[0])+'</span>';else out+='<span class="syn-number">'+esc(match[0])+'</span>';last=pattern.lastIndex}return out+esc(content.slice(last));
}
if(language==='toml'||language==='yaml')return content.split('\n').map(function(line){var trimmed=line.trim();if(trimmed.indexOf('#')===0)return '<span class="syn-comment">'+esc(line)+'</span>';var split=language==='toml'?line.indexOf('='):line.indexOf(':');if(split>0)return '<span class="syn-key">'+esc(line.slice(0,split))+'</span>'+esc(line.charAt(split))+'<span class="syn-string">'+esc(line.slice(split+1))+'</span>';return esc(line)}).join('\n');
return esc(content);
}
function inlineMarkdown(text){
var tokens=[],value=String(text==null?'':text),token=function(html){var key='\u0000'+tokens.length+'\u0000';tokens.push(html);return key};
value=value.replace(/\\([\\`*{}\[\]()#+\-.!_|>~])/g,function(all,ch){return token(esc(ch))});
value=value.replace(/(`+)([\s\S]*?[^`])\1(?!`)/g,function(all,ticks,code){return token('<code>'+esc(code.trim())+'</code>')});
value=value.replace(/!\[([^\]]*)\]\((https?:\/\/[^\s)]+)(?:\s+["']([^"']*)["'])?\)/gi,function(all,alt,url,title){return token('<img src="'+esc(url)+'" alt="'+esc(alt)+'"'+(title?' title="'+esc(title)+'"':'')+' loading="lazy" referrerpolicy="no-referrer">')});
value=value.replace(/\[([^\]]+)\]\(((?:https?:\/\/|mailto:)[^\s)]+)(?:\s+["']([^"']*)["'])?\)/gi,function(all,label,url,title){return token('<a href="'+esc(url)+'" target="_blank" rel="noopener noreferrer"'+(title?' title="'+esc(title)+'"':'')+'>'+inlineMarkdown(label)+'</a>')});
value=value.replace(/\[([^\]]+)\]\(([^\s)]+)(?:\s+["']([^"']*)["'])?\)/g,function(all,label,url,title){var parsed=parseStarFilePreviewTarget(url);if(!parsed||parsed.remote)return all;return token('<a class="star-file-link" href="'+esc(url)+'" data-file-target="'+esc(url)+'"'+(title?' title="'+esc(title)+'"':'')+'>'+inlineMarkdown(label)+'</a>')});
value=value.replace(/<(https?:\/\/[^\s<>]+|mailto:[^\s<>]+)>/gi,function(all,url){return token('<a href="'+esc(url)+'" target="_blank" rel="noopener noreferrer">'+esc(url)+'</a>')});
value=value.replace(/(^|[\s(])(https?:\/\/[^\s<>"')\]]+)/gi,function(all,prefix,url){var trailing=url.match(/[.,!?;:]+$/),suffix=trailing?trailing[0]:'';if(suffix)url=url.slice(0,-suffix.length);return prefix+token('<a href="'+esc(url)+'" target="_blank" rel="noopener noreferrer">'+esc(url)+'</a>')+suffix});
// Underscores inside identifiers are literal, including Unicode identifiers.
value=esc(value);value=value.replace(/\*\*([^*\n]+)\*\*/g,'<strong>$1</strong>');value=value.replace(/(^|[^\p{L}\p{N}\p{M}_])__([^_\s](?:[^_\n]*?[^_\s])?)__(?![\p{L}\p{N}\p{M}_])/gu,'$1<strong>$2</strong>');value=value.replace(/~~([^~\n]+)~~/g,'<del>$1</del>');value=value.replace(/(^|[^*])\*([^*\n]+)\*(?!\*)/g,'$1<em>$2</em>');value=value.replace(/(^|[^\p{L}\p{N}\p{M}_])_([^_\s](?:[^_\n]*?[^_\s])?)_(?![\p{L}\p{N}\p{M}_])/gu,'$1<em>$2</em>');
return value.replace(/\u0000(\d+)\u0000/g,function(all,index){return tokens[Number(index)]||''});
}
function splitMarkdownTableRow(line){
var value=String(line||'').trim(),cells=[],cell='',escaped=false,inCode=false;if(value.charAt(0)==='|')value=value.slice(1);if(value.charAt(value.length-1)==='|')value=value.slice(0,-1);
for(var i=0;i<value.length;i++){var ch=value.charAt(i);if(escaped){cell+=ch;escaped=false;continue}if(ch==='\\'){cell+=ch;escaped=true;continue}if(ch==='`'){inCode=!inCode;cell+=ch;continue}if(ch==='|'&&!inCode){cells.push(cell.trim());cell=''}else cell+=ch}cells.push(cell.trim());return cells;
}
function markdownTableAlignments(line){var cells=splitMarkdownTableRow(line);if(!cells.length||cells.some(function(cell){return !/^:?-{3,}:?$/.test(cell)}))return null;return cells.map(function(cell){return cell.charAt(0)===':'&&cell.charAt(cell.length-1)===':'?'center':(cell.charAt(cell.length-1)===':'?'right':(cell.charAt(0)===':'?'left':''))})}
function renderMarkdownList(entries){
var html=[],stack=[];entries.forEach(function(entry){while(stack.length&&entry.indent<stack[stack.length-1].indent){html.push('</li></'+stack.pop().type+'>')}if(stack.length&&entry.indent===stack[stack.length-1].indent&&entry.type!==stack[stack.length-1].type){html.push('</li></'+stack.pop().type+'>')}if(!stack.length||entry.indent>stack[stack.length-1].indent){html.push('<'+entry.type+'>');stack.push({indent:entry.indent,type:entry.type,hasItem:false})}var current=stack[stack.length-1];if(current.hasItem)html.push('</li>');var task=entry.text.match(/^\[([ xX])\]\s+(.*)$/);html.push('<li'+(task?' class="task-list-item"':'')+'>'+(task?'<input class="markdown-task" type="checkbox" disabled'+(task[1].toLowerCase()==='x'?' checked':'')+'>'+inlineMarkdown(task[2]):inlineMarkdown(entry.text)));current.hasItem=true});while(stack.length)html.push('</li></'+stack.pop().type+'>');return html.join('');
}
function renderMarkdown(markdown){
var lines=String(markdown||'').replace(/\r\n?/g,'\n').split('\n'),html=[],i=0;
function isBlockStart(index){var line=lines[index]||'',next=lines[index+1]||'';return !line.trim()||/^\s*(?:`{3,}|~{3,})/.test(line)||/^(?:#{1,6})\s+/.test(line)||/^\s*([-*_])(?:\s*\1){2,}\s*$/.test(line)||/^>/.test(line)||/^\s*(?:[-+*]|\d+[.)])\s+/.test(line)||/^ {4}\S/.test(line)||markdownTableAlignments(next)!==null||index+1<lines.length&&/^\s*(?:=+|-+)\s*$/.test(next)}
while(i<lines.length){var line=lines[i],next=lines[i+1]||'';if(!line.trim()){i++;continue}
var fenceStart=line.match(/^\s*(`{3,}|~{3,})\s*([^\s]*)?.*$/);if(fenceStart){var marker=fenceStart[1],language=String(fenceStart[2]||'').replace(/[^\w-]/g,''),fence=[],close=new RegExp('^\\s*'+marker.charAt(0)+'{'+marker.length+',}\\s*$');i++;while(i<lines.length&&!close.test(lines[i]))fence.push(lines[i++]);if(i<lines.length)i++;html.push('<pre><code'+(language?' class="language-'+esc(language)+'"':'')+'>'+esc(fence.join('\n'))+'</code></pre>');continue}
var heading=line.match(/^(#{1,6})\s+(.+?)\s*#*\s*$/);if(heading){var level=heading[1].length;html.push('<h'+level+'>'+inlineMarkdown(heading[2])+'</h'+level+'>');i++;continue}
var setext=next.match(/^\s*(=+|-+)\s*$/);if(setext&&line.trim()){var setextLevel=setext[1].charAt(0)==='='?1:2;html.push('<h'+setextLevel+'>'+inlineMarkdown(line.trim())+'</h'+setextLevel+'>');i+=2;continue}
if(/^\s*([-*_])(?:\s*\1){2,}\s*$/.test(line)){html.push('<hr>');i++;continue}
if(/^>/.test(line)){var quotes=[];while(i<lines.length&&/^>/.test(lines[i]))quotes.push(lines[i++].replace(/^>\s?/,''));html.push('<blockquote>'+renderMarkdown(quotes.join('\n'))+'</blockquote>');continue}
if(/^ {4}\S/.test(line)){var indented=[];while(i<lines.length&&(/^ {4}/.test(lines[i])||!lines[i].trim()))indented.push(lines[i++].replace(/^ {4}/,''));html.push('<pre><code>'+esc(indented.join('\n').replace(/\n+$/,''))+'</code></pre>');continue}
var alignments=markdownTableAlignments(next);if(line.indexOf('|')>=0&&alignments){var headers=splitMarkdownTableRow(line);if(headers.length===alignments.length){i+=2;var rows=[];while(i<lines.length&&lines[i].indexOf('|')>=0&&lines[i].trim()){var cells=splitMarkdownTableRow(lines[i]);if(cells.length!==headers.length)break;rows.push(cells);i++}var table='<div class="markdown-table-wrap"><table><thead><tr>'+headers.map(function(cell,index){return '<th'+(alignments[index]?' style="text-align:'+alignments[index]+'"':'')+'>'+inlineMarkdown(cell)+'</th>'}).join('')+'</tr></thead><tbody>'+rows.map(function(row){return '<tr>'+row.map(function(cell,index){return '<td'+(alignments[index]?' style="text-align:'+alignments[index]+'"':'')+'>'+inlineMarkdown(cell)+'</td>'}).join('')+'</tr>'}).join('')+'</tbody></table></div>';html.push(table);continue}}
var listMatch=line.match(/^(\s*)([-+*]|\d+[.)])\s+(.+)$/);if(listMatch){var entries=[];while(i<lines.length){var item=lines[i].match(/^(\s*)([-+*]|\d+[.)])\s+(.+)$/);if(!item)break;entries.push({indent:item[1].replace(/\t/g,'    ').length,type:/\d/.test(item[2].charAt(0))?'ol':'ul',text:item[3]});i++}html.push(renderMarkdownList(entries));continue}
var paragraph=[];while(i<lines.length&&!isBlockStart(i)){paragraph.push(lines[i++])}if(!paragraph.length){paragraph.push(lines[i++])}html.push('<p>'+paragraph.map(function(value,index){var hard=/ {2,}$/.test(value)||/\\$/.test(value),clean=value.replace(/(?: {2,}|\\)$/,'').trim();return inlineMarkdown(clean)+(hard&&index<paragraph.length-1?'<br>':(index<paragraph.length-1?' ':''))}).join('')+'</p>');
}
return html.join('');
}
function renderStructuredMemory(content,title,fileName){
try{var value=JSON.parse(content);if(String(fileName||'').toUpperCase()==='MEMORY_INDEX.JSON')return renderMemoryIndex(value,title);var entries=Object.keys(value||{}).map(function(key){var item=value[key],complex=item!==null&&typeof item==='object';return '<div class="structured-item"><div class="structured-key">'+esc(key)+'</div><div class="structured-value'+(complex?' complex':'')+'">'+esc(complex?JSON.stringify(item,null,2):String(item===null?'null':item))+'</div></div>'}).join('');return '<section class="structured-view"><div class="structured-hero"><h3>'+esc(title||'记忆状态')+'</h3><p>以结构化视图展示当前生效数据</p></div><div class="structured-grid">'+entries+'</div></section>'}catch(error){return '<pre class="resource-code"><code>'+highlightResourceCode(content,'json')+'</code></pre>'}
}
function renderMemoryIndex(index,title){
var memories=Array.isArray(index&&index.memories)?index.memories:[];
var cards=memories.map(function(memory){var fileName=pathBaseName(memory.file||''),tags=Array.isArray(memory.tags)?memory.tags:[],skills=Array.isArray(memory.relatedSkills)?memory.relatedSkills:[];var chips=tags.slice(0,6).map(function(tag){return '<span class="memory-tag">'+esc(tag)+'</span>'}).join('');if(skills.length)chips+='<span class="memory-tag">关联 Skill · '+esc(skills.join('、'))+'</span>';return '<button type="button" class="memory-index-card" data-memory-file="'+esc(fileName)+'" onclick="openMemoryDetail(this)"><div class="memory-index-row"><span class="memory-type">'+esc(memoryTypeName(memory.type))+'</span><span class="memory-index-title">'+esc(memory.title||memory.id||'未命名记忆')+'</span><span class="material-icons memory-index-arrow">arrow_forward</span></div><div class="memory-index-summary">'+esc(memory.summary||'暂无概要')+'</div><div class="memory-index-meta">'+chips+'<span class="memory-updated">'+esc(formatMemoryTime(memory.updatedAt||memory.createdAt))+'</span></div></button>'}).join('');
if(!cards)cards='<div class="memory-index-empty">当前索引中还没有记忆条目</div>';
return '<section class="structured-view"><div class="structured-hero memory-index-head"><div><h3>'+esc(title||'记忆索引')+'</h3><p>版本 '+esc(String(index&&index.version||1))+' · 最近更新 '+esc(formatMemoryTime(index&&index.lastUpdated))+'</p></div><div class="memory-index-count">'+memories.length+' 条记忆</div></div><div class="memory-index-list">'+cards+'</div></section>';
}
function memoryTypeName(type){var names={user:'用户画像',feedback:'反馈偏好',project:'项目记忆',reference:'参考信息'};return names[String(type||'').toLowerCase()]||type||'记忆'}
function formatMemoryTime(value){if(!value)return '时间未知';return String(value).replace('T',' ').replace(/\.\d+(?=[+-]|Z|$)/,'').replace(/([+-]\d\d:\d\d|Z)$/,'').slice(0,16)}
function findResourceFileNode(nodes,fileName){for(var i=0;i<(nodes||[]).length;i++){var node=nodes[i];if(node.type==='file'&&node.fileName===fileName)return node;var found=findResourceFileNode(node.children||[],fileName);if(found)return found}return null}
function openMemoryDetail(button){var fileName=button.getAttribute('data-memory-file'),node=findResourceFileNode(resourceState.nodes,fileName);if(!node){showSnackbar('未找到对应的记忆明细文件');return}loadAgentResourceContent(node.id,node.name)}

var remoteResourceConflictSelection=null;
async function showRemoteResourceConflicts(){
var robot=config.robots[resourceState.robotIndex];if(!robot||robot.agentProvider!=='REMOTE_STARCORE_ACP'||robot.remotePlatform==='BROWSER')return;
var name=robot.name;remoteResourceConflictSelection=null;
try{var response=await api('/api/remote-acp/resource-conflicts?robot='+encodeURIComponent(name)),data=await response.json();if(!response.ok||!data.ok)throw new Error(data.message||'读取失败');
if(!config.robots[resourceState.robotIndex]||config.robots[resourceState.robotIndex].name!==name)return;
window.remoteResourceConflictList=data.conflicts||[];
document.getElementById('resourceViewer').innerHTML='<div class="resource-content"><h3>资源同步冲突</h3><p>双方副本均已保留。选择一个文件查看并决定使用哪个版本。</p>'+remoteResourceConflictList.map(function(item,i){return '<p><button class="btn btn-secondary btn-sm" onclick="viewRemoteResourceConflict('+i+')">'+esc(item.path)+'</button></p>'}).join('')+(!remoteResourceConflictList.length?'<p>当前没有待处理冲突。</p>':'')+'</div>';
}catch(e){showSnackbar('读取同步冲突失败：'+e.message)}
}
async function viewRemoteResourceConflict(index){
var robot=config.robots[resourceState.robotIndex],item=(window.remoteResourceConflictList||[])[index];if(!robot||!item)return;
var name=robot.name;
try{var response=await api('/api/remote-acp/resource-conflicts?robot='+encodeURIComponent(name)+'&id='+encodeURIComponent(item.id)),data=await response.json();if(!response.ok||!data.ok)throw new Error(data.message||'读取失败');
if(!config.robots[resourceState.robotIndex]||config.robots[resourceState.robotIndex].name!==name)return;
remoteResourceConflictSelection={robot:name,detail:data.conflict};var detail=data.conflict;
document.getElementById('resourceViewer').innerHTML='<div class="resource-content"><h3>'+esc(detail.path)+'</h3><button class="btn btn-secondary btn-sm" onclick="resolveRemoteResourceConflict(&quot;starweave&quot;)">保留 Starweave 版本</button> <button class="btn btn-secondary btn-sm" onclick="resolveRemoteResourceConflict(&quot;container&quot;)">采用容器版本</button><h4>Starweave '+(detail.starweaveDeleted?'（已删除）':'')+'</h4><pre>'+esc(detail.starweave)+'</pre><h4>容器 '+(detail.containerDeleted?'（已删除）':'')+'</h4><pre>'+esc(detail.container)+'</pre></div>';
}catch(e){showSnackbar('读取冲突内容失败：'+e.message)}
}
async function resolveRemoteResourceConflict(choice){
var selection=remoteResourceConflictSelection;if(!selection)return;
if(!await showConfirm('将采用'+(choice==='container'?'容器':'Starweave')+'版本，并在后续同步中应用到容器。原冲突副本仍保留。',{title:'处理同步冲突？',confirmText:'确认采用'}))return;
try{var response=await api('/api/remote-acp/resource-conflicts?robot='+encodeURIComponent(selection.robot),{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({id:selection.detail.id,head:selection.detail.head,choice:choice})}),data=await response.json();if(!response.ok||!data.ok)throw new Error(data.message||'处理失败');showSnackbar('已保存冲突处理结果，在线容器将在后续同步中应用');await showRemoteResourceConflicts();
}catch(e){showSnackbar('处理同步冲突失败：'+e.message)}
}

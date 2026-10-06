/* 普通和团队会话共用分页状态及滚动锚点。 */
var chatViewportStates=new WeakMap();
function starHistoryScope(){var s=selectedStarSession()||{};return starChatScope()+'|'+(s.sessionId||'')+'|'+(s.generation||0)}
function teamHistoryScope(){return teamChatScope()+'|'+((selectedTeamSessionMember()||{}).sessionId||'')}
function chatHistoryState(owner,scope){
if(!owner.history||owner.history.scope!==scope)owner.history={scope:scope,cursor:null,hasMore:false,loading:false,loaded:false,olderRequested:false,error:''};
return owner.history;
}
function chatHistoryStatus(history,retry){
if(!history)return '';
var text=history.loading?'正在加载历史消息…':history.error?'历史加载失败，点击重试':history.loaded&&history.olderRequested&&!history.hasMore?'没有更早的消息了':'';
return text?'<div class="chat-history-status"'+(history.error?' role="button" tabindex="0" onclick="'+retry+'()" onkeydown="if(event.key===\'Enter\')'+retry+'()"':' role="status"')+'>'+(history.loading?chatLoadingHtml(text):text)+'</div>':'';
}
function chatLoadingHtml(text){return '<span class="chat-loading"><span class="chat-loading-spinner" aria-hidden="true"></span><span>'+text+'</span></span>'}
function chatEmptyHtml(history){return '<div class="session-empty"><div'+(!history.loaded&&!history.error?' role="status"':'')+'>'+(!history.loaded?(history.error?'<p>消息加载失败</p>':chatLoadingHtml('正在加载消息…')):'<p>开始一段新对话</p>')+'</div></div>'}
function chatVisibleAnchor(box){
var bounds=box.getBoundingClientRect(),nodes=box.querySelectorAll('[data-chat-key]');
for(var i=0;i<nodes.length;i++){var rect=nodes[i].getBoundingClientRect();if(rect.bottom>bounds.top+2)return{key:nodes[i].getAttribute('data-chat-key'),offset:rect.top-bounds.top}}
return null;
}
function chatRestoreViewport(box,state){
state.adjusting=true;
if(state.follow)box.scrollTop=box.scrollHeight;
else if(state.anchor){
var nodes=box.querySelectorAll('[data-chat-key]'),found=false;
for(var i=0;i<nodes.length;i++)if(nodes[i].getAttribute('data-chat-key')===state.anchor.key){box.scrollTop+=nodes[i].getBoundingClientRect().top-box.getBoundingClientRect().top-state.anchor.offset;found=true;break}
if(!found)box.scrollTop=state.top;
}else box.scrollTop=state.top;
state.top=box.scrollTop;state.height=box.scrollHeight;
requestAnimationFrame(function(){state.adjusting=false});
}
function renderChatViewport(box,html,keys,scope,owner){
var state=chatViewportStates.get(box);
if(!state||state.scope!==scope){
if(state&&state.observer)state.observer.disconnect();
state={scope:scope,follow:owner.followOutput!==false,owner:owner,top:0,height:0,anchor:null,adjusting:false};chatViewportStates.set(box,state);
}else{state.follow=owner.followOutput!==false;state.top=box.scrollTop;state.anchor=state.follow?null:chatVisibleAnchor(box)}
var expanded={};box.querySelectorAll('[data-chat-key]').forEach(function(row){expanded[row.getAttribute('data-chat-key')]=Array.prototype.map.call(row.querySelectorAll('details'),function(detail){return detail.open})});
var content=box.querySelector('.chat-message-content');
if(!content){box.innerHTML='';content=document.createElement('div');content.className='chat-message-content';box.appendChild(content)}
if(state.observer&&state.content!==content){state.observer.disconnect();state.observer=null}
state.content=content;
content.innerHTML=html;
var index=0;Array.prototype.forEach.call(content.children,function(node){if(node.classList.contains('chat-history-status'))return;if(!node.hasAttribute('data-chat-key'))node.setAttribute('data-chat-key',keys[index]||'pending:'+index);var opened=expanded[node.getAttribute('data-chat-key')];if(opened)node.querySelectorAll('details').forEach(function(detail,i){detail.open=!!opened[i]});index++});
chatRestoreViewport(box,state);
if(!state.observer&&typeof ResizeObserver!=='undefined'){
state.observer=new ResizeObserver(function(){if(chatViewportStates.get(box)===state)chatRestoreViewport(box,state)});
state.observer.observe(content);state.observer.observe(box);
}
if(!box.chatScrollBound){
box.chatScrollBound=true;
function stopFollowing(){var current=chatViewportStates.get(box);if(current){current.follow=false;current.owner.followOutput=false;current.anchor=chatVisibleAnchor(box);current.top=box.scrollTop;current.adjusting=false}}
box.addEventListener('wheel',function(event){if(event.deltaY<0)stopFollowing()},{passive:true});
var touch=0;box.addEventListener('touchstart',function(event){if(event.touches.length)touch=event.touches[0].clientY},{passive:true});
box.addEventListener('touchmove',function(event){if(!event.touches.length)return;if(event.touches[0].clientY>touch)stopFollowing();touch=event.touches[0].clientY},{passive:true});
box.addEventListener('keydown',function(event){if(['ArrowUp','PageUp','Home'].indexOf(event.key)>=0)stopFollowing()});
}
}
function scrollChatViewport(box,owner,loadOlder){
var state=chatViewportStates.get(box);if(!state||state.adjusting)return;
// 内容增高造成的滚动不是用户向上阅读，不能取消底部跟随。
if(state.follow&&state.height!==box.scrollHeight){chatRestoreViewport(box,state);return}
state.follow=box.scrollHeight-box.scrollTop-box.clientHeight<=24;owner.followOutput=state.follow;
state.top=box.scrollTop;state.height=box.scrollHeight;state.anchor=state.follow?null:chatVisibleAnchor(box);
if(!state.follow&&box.scrollTop<Math.max(400,box.clientHeight))loadOlder();
}
function mergeHistoryEvents(older,current){
var rows=new Map();older.concat(current).forEach(function(event){var old=rows.get(event.eventSeq);if(!old||Number(event.revision||0)>=Number(old.revision||0))rows.set(event.eventSeq,event)});
return Array.from(rows.values()).sort(function(a,b){return a.eventSeq-b.eventSeq});
}
function prependTeamHistory(messages){
var current=teamSession.messages,seen={};teamSession.messages=[];
messages.concat(current).forEach(function(message){var id=teamMessageIdentity(message);if(id&&seen[id])return;if(id)seen[id]=true;var latest=id&&current.find(function(item){return teamMessageIdentity(item)===id});mergeTeamMessage(latest&&teamMessageRevision(latest)>teamMessageRevision(message)?latest:message)});
}

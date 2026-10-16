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
// Keep existing cards, images and scrollable details connected while text streams.
function patchChatNode(current,next){
if(current.nodeType!==next.nodeType||current.nodeName!==next.nodeName){current.replaceWith(next);return next}
if(current.nodeType!==1){if(current.nodeValue!==next.nodeValue)current.nodeValue=next.nodeValue;return current}
Array.prototype.slice.call(current.attributes).forEach(function(attr){if(current.tagName==='DETAILS'&&attr.name==='open')return;if(!next.hasAttribute(attr.name))current.removeAttribute(attr.name)});
Array.prototype.forEach.call(next.attributes,function(attr){if(current.tagName==='DETAILS'&&attr.name==='open')return;if(current.getAttribute(attr.name)!==attr.value)current.setAttribute(attr.name,attr.value)});
var oldChildren=Array.prototype.slice.call(current.childNodes),newChildren=Array.prototype.slice.call(next.childNodes);
newChildren.forEach(function(child,index){if(oldChildren[index])patchChatNode(oldChildren[index],child);else current.appendChild(child)});
oldChildren.slice(newChildren.length).forEach(function(child){child.remove()});
return current;
}
function patchChatRows(content,html,keys,state){
var template=document.createElement('template');template.innerHTML=html;
var existing=new Map(),previous=state.rowHtml||new Map(),updated=new Map(),index=0;
function rowKey(node){return node.classList.contains('chat-history-status')?'history-status':node.getAttribute('data-chat-key')}
Array.prototype.forEach.call(content.children,function(node){existing.set(rowKey(node),node)});
var cursor=content.firstChild;
Array.prototype.slice.call(template.content.children).forEach(function(next){
if(!next.classList.contains('chat-history-status')){if(!next.hasAttribute('data-chat-key'))next.setAttribute('data-chat-key',keys[index]||'pending:'+index);index++}
var key=rowKey(next),markup=next.outerHTML,current=existing.get(key),atCursor=current===cursor;
if(current){existing.delete(key);if(previous.get(key)!==markup)current=patchChatNode(current,next)}else current=next;
if(atCursor)cursor=current;
if(current!==cursor)content.insertBefore(current,cursor);
cursor=current.nextSibling;updated.set(key,markup);
});
existing.forEach(function(node){node.remove()});state.rowHtml=updated;
}
function renderChatViewport(box,html,keys,scope,owner){
var state=chatViewportStates.get(box);
if(!state||state.scope!==scope){
if(state&&state.observer)state.observer.disconnect();
state={scope:scope,follow:owner.followOutput!==false,owner:owner,top:0,height:0,anchor:null,adjusting:false};chatViewportStates.set(box,state);
box.replaceChildren();
}else{state.follow=owner.followOutput!==false;state.top=box.scrollTop;state.anchor=state.follow?null:chatVisibleAnchor(box)}
var content=box.querySelector('.chat-message-content');
if(!content){box.innerHTML='';content=document.createElement('div');content.className='chat-message-content';box.appendChild(content)}
if(state.observer&&state.content!==content){state.observer.disconnect();state.observer=null}
state.content=content;
patchChatRows(content,html,keys,state);
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

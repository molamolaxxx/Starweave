const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const http=require('node:http');
const {chromium}=require('playwright');
const root=process.env.CONFIGUI_ROOT||path.resolve(__dirname,'../../main/resources/configui');
const html=fs.readFileSync(path.join(root,'index.html'),'utf8').replace(/<script[^>]*src="https?:[^>]*><\/script>/g,'').replace(/<link[^>]*href="https?:[^>]*>/g,'').replace('<script src="/assets/js/app.js"></script>','');
const server=http.createServer((q,r)=>{const file=path.join(root,q.url);r.setHeader('Content-Type',q.url.endsWith('.css')?'text/css':q.url.endsWith('.js')?'application/javascript':'text/html');r.end(q.url.startsWith('/assets/')?fs.readFileSync(file):html)});
(async()=>{await new Promise(r=>server.listen(0,'127.0.0.1',r));const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',headless:true,args:['--no-sandbox']});let checks=0;
try{for(const width of [393,1440]){
 const page=await browser.newPage({viewport:{width,height:873}});const errors=[];page.on('pageerror',e=>errors.push(e.message));
 await page.goto(`http://127.0.0.1:${server.address().port}/`);
 await page.evaluate(()=>syncStarweaveViewport());
 for(const id of ['starSessionMessages','teamSessionMessages']){
  await page.evaluate(id=>{if(id==='teamSessionMessages')showDialog('teamSessionDialog')},id);
  for(const history of [{loaded:false},{loaded:true},{loaded:false,error:'offline'}]){
   await page.evaluate(({id,history})=>{window.testOwner={followOutput:true};renderChatViewport(document.getElementById(id),chatEmptyHtml(history),[],id,testOwner)}, {id,history});
   await page.evaluate(()=>new Promise(r=>requestAnimationFrame(()=>requestAnimationFrame(r))));
   const result=await page.evaluate(id=>{const box=document.getElementById(id),content=box.querySelector('.chat-message-content'),empty=content.querySelector('.session-empty'),text=empty.firstElementChild;const br=box.getBoundingClientRect(),tr=text.getBoundingClientRect();return{delta:Math.abs(tr.top+tr.height/2-br.top-br.height/2),empty:content.classList.contains('chat-empty-state'),overflow:box.scrollHeight-box.clientHeight}},id);
   assert.equal(result.empty,true);assert.ok(result.delta<=2,JSON.stringify({width,id,history,result}));assert.ok(result.overflow<=2);checks++;
  }
  // Transition to messages clears the fixed height and preserves existing message nodes.
  await page.evaluate(id=>{const box=document.getElementById(id);renderChatViewport(box,'<div class="message-row" style="height:1200px">Message</div>',['m1'],id,testOwner);window.firstMessageNode=box.querySelector('[data-chat-key="m1"]')},id);
  const result=await page.evaluate(id=>{const box=document.getElementById(id),content=box.querySelector('.chat-message-content');renderChatViewport(box,'<div class="message-row" style="height:1200px">Message</div><div class="message-row">Next</div>',['m1','m2'],id,testOwner);return{empty:content.classList.contains('chat-empty-state'),preserved:box.querySelector('[data-chat-key="m1"]')===firstMessageNode,scrollable:box.scrollHeight>box.clientHeight}},id);
  assert.deepEqual(result,{empty:false,preserved:true,scrollable:true});checks++;
  if(id==='teamSessionMessages')await page.evaluate(()=>closeDialog('teamSessionDialog'));
 }
 assert.deepEqual(errors,[]);console.log(`PASS empty/loading/error centering and message transitions: ${width}px, ordinary + team`);await page.close();
}console.log(`PASS ${checks} checks; centering has no :has() dependency`);
}finally{await browser.close();server.close()}})().catch(e=>{console.error(e);server.close();process.exitCode=1});

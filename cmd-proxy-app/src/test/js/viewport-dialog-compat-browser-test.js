const assert=require('node:assert/strict'),fs=require('node:fs'),path=require('node:path'),http=require('node:http');
const {chromium}=require('playwright');
const root=process.env.CONFIGUI_ROOT||path.resolve(__dirname,'../../main/resources/configui');
const markup=fs.readFileSync(path.join(root,'index.html'),'utf8').replace(/<script[^>]*src="https?:[^>]*><\/script>/g,'').replace(/<link[^>]*href="https?:[^>]*>/g,'').replace('<script src="/assets/js/app.js"></script>','');
const server=http.createServer((q,r)=>{r.setHeader('Content-Type',q.url.endsWith('.css')?'text/css':q.url.endsWith('.js')?'application/javascript':'text/html');r.end(q.url.startsWith('/assets/')?fs.readFileSync(path.join(root,q.url)):markup)});
(async()=>{await new Promise(r=>server.listen(0,'127.0.0.1',r));const b=await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',headless:true,args:['--no-sandbox']});let checks=0;
try{for(const width of [393,800,1440])for(const legacy of [false,true]){
 const p=await b.newPage({viewport:{width,height:873}}),errors=[];p.on('pageerror',e=>errors.push(e.message));
 if(legacy)await p.route('**/assets/css/*.css',r=>{const name=new URL(r.request().url()).pathname;return r.fulfill({contentType:'text/css',body:fs.readFileSync(path.join(root,name),'utf8').replace(/dvh\b/g,'unsupported')})});
 await p.goto(`http://127.0.0.1:${server.address().port}/`);
 for(const theme of ['light','dark']){
  await p.evaluate(theme=>document.documentElement.dataset.theme=theme,theme);
  for(const id of ['fileLinkPreviewDialog','robotDialog','channelDialog','channelMessagesDialog','resourceDialog','taskDialog','starTeamDialog','starTeamEditDialog','teamSessionDialog','scheduleExecutionsDialog','observationEditorDialog']){
   const r=await p.evaluate(id=>{showDialog(id);const d=document.querySelector('#'+id+' > .dialog'),r=d.getBoundingClientRect();return{height:r.height,top:r.top,bottom:r.bottom,viewport:innerHeight}},id);
   assert.ok(r.height>300,JSON.stringify({width,legacy,theme,id,r}));assert.ok(r.top>=-1&&r.bottom<=r.viewport+1,JSON.stringify({width,legacy,theme,id,r}));checks++;
   await p.evaluate(id=>closeDialog(id),id);
  }
  await p.evaluate(()=>{fileLinkPreview.data={fileName:'example.md',renderMode:'MARKDOWN',content:Array.from({length:200},(_,i)=>'Paragraph '+i+'\n\n').join(''),charset:'UTF-8',lineCount:400,bytesRead:8000};fileLinkPreview.mode='PREVIEW';showDialog('fileLinkPreviewDialog');renderFileLinkPreview()});
  for(const mode of ['PREVIEW','SOURCE']){
   const r=await p.evaluate(mode=>{fileLinkPreview.mode=mode;renderFileLinkPreview();const el=document.getElementById(mode==='PREVIEW'?'fileLinkPreviewMarkdown':'fileLinkPreviewSource');el.scrollTop=120;return{height:el.clientHeight,scroll:el.scrollTop,overflow:el.scrollHeight>el.clientHeight,hidden:el.hidden}},mode);
   assert.ok(r.height>300&&r.scroll>0&&r.overflow&&!r.hidden,JSON.stringify({width,legacy,theme,mode,r}));checks++;
  }
  const frame=await p.evaluate(()=>{fileLinkPreview.data={fileName:'example.html',renderMode:'HTML',content:'<h1>HTML preview</h1>'};fileLinkPreview.mode='PREVIEW';renderFileLinkPreview();const f=document.getElementById('fileLinkPreviewFrame');return{height:f.clientHeight,hidden:f.hidden}});
  assert.ok(frame.height>300&&!frame.hidden,JSON.stringify(frame));checks++;
  await p.evaluate(()=>closeFileLinkPreview());
 }
 assert.deepEqual(errors,[]);console.log(`PASS dialog heights + Markdown/source scrolling + HTML: ${width}px, ${legacy?'dvh unavailable':'dvh supported'}, light/dark`);await p.close();
}console.log(`PASS ${checks} dialog and file-preview checks`);
}finally{await b.close();server.close()}})().catch(e=>{console.error(e);server.close();process.exitCode=1});

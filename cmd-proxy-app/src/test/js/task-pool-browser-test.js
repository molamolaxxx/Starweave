const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {chromium} = require('playwright');
const root = path.resolve(__dirname, '../../main/resources/configui');
const html = fs.readFileSync(path.join(root, 'index.html'), 'utf8')
 .replace(/<script[^>]*src="https?:[^>]*><\/script>/g, '')
 .replace(/<link[^>]*href="https?:[^>]*>/g, '')
 .replace('<script src="/assets/js/app.js"></script>', '');
const server = http.createServer((req,res) => {
 if(req.url.startsWith('/assets/')) {const file=path.join(root,req.url.split('?')[0]);res.setHeader('Content-Type',file.endsWith('.css')?'text/css':'application/javascript');res.end(fs.readFileSync(file));}
 else {res.setHeader('Content-Type','text/html');res.end(html);}
});
(async()=>{
 await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));
 const browser=await chromium.launch({executablePath:process.env.CHROME_BIN||'/usr/bin/google-chrome',headless:true,args:['--no-sandbox']});
 try {for(const width of [1440,390]) {
  const page=await browser.newPage({viewport:{width,height:1000}}),errors=[];page.on('pageerror',e=>errors.push(e.stack));
  await page.goto(`http://127.0.0.1:${server.address().port}`);
  await page.evaluate(()=>{
   config={robots:[{name:'Worker',enabled:true}],chatterIds:[]};starTeams.items=[];window.pool=[];window.requests=[];window.failSave=false;window.delaySave=false;
   showSnackbar=()=>{};
   api=async(url,options={})=>{
    let data={};const method=options.method||'GET';requests.push({url,method,body:options.body?JSON.parse(options.body):null});
    if(url.startsWith('/api/starweave/v1/tasks')) {
     if(method==='POST') {
      if(delaySave)await new Promise(resolve=>window.finishSave=resolve);
      if(failSave)return new Response(JSON.stringify({accepted:false,code:'VERSION_CONFLICT',message:'任务已被其他人认领'}),{status:409});
      const body=JSON.parse(options.body);
      if(url.endsWith('/assign')) {pool[0]={...pool[0],target:body.target,assignee:{robotNameSnapshot:'Worker'},assignmentState:'ASSIGNED',revision:2};data={task:pool[0]};}
      else {const task={...body,id:'task-one',dispatchMode:body.target?'DIRECT':'OPEN',assignmentState:body.target?'PENDING_ASSIGNMENT':'UNCLAIMED',assignee:null,status:'START',revision:1,contentVersion:1};pool.push(task);data={task};}
     }else if(url.includes('/stats'))data={START:pool.length};
     else if(url.includes('/task-one'))data={task:pool[0],comments:{items:[]},history:{items:[]}};
     else data={items:pool,total:pool.length,page:1,pageSize:10};
    }else if(url.endsWith('/sources'))data={sources:[]};else data={teams:[]};
    return new Response(JSON.stringify({accepted:true,data}),{status:200});
   };
   switchPage('tasks');
  });
  await page.evaluate(()=>openTaskCreate());
  assert.equal(await page.locator('#taskTargetType').inputValue(),'OPEN');
  assert.equal(await page.locator('#taskTargetRef').isVisible(),false);
  await page.locator('#taskName').fill('公开任务');
  await page.evaluate(()=>{document.getElementById('taskContent').value='任务要求';delaySave=true;});
  await page.locator('#taskSaveButton').click();
  await page.waitForSelector('#taskSaveButton.button-loading');
  assert.equal(await page.locator('#taskDialog').isVisible(),true);
  await page.evaluate(()=>{delaySave=false;finishSave();});
  await page.waitForFunction(()=>!document.getElementById('taskDialog').classList.contains('show'));
  assert.match(await page.locator('#taskList').innerText(),/待认领/);
  assert.equal(await page.evaluate(()=>requests.find(r=>r.method==='POST').body.target),null);
  await page.selectOption('#taskAssignmentFilter','UNCLAIMED');
  await page.waitForFunction(()=>requests.some(r=>r.url.includes('assignmentState=UNCLAIMED')));
  // A competing claim must retain the assignment dialog and selected executor.
  await page.evaluate(()=>openTaskAssign('task-one'));
  await page.waitForFunction(()=>document.getElementById('taskTargetRef').value);
  if(width===390)await page.locator('#taskActivityTab').click();
  await page.evaluate(()=>failSave=true);
  await page.locator('#taskSaveButton').click();
  await page.waitForFunction(()=>document.getElementById('taskConflict').textContent.includes('任务已被其他人认领'));
  assert.equal(await page.locator('#taskDialog').isVisible(),true);
  assert.equal(await page.locator('#taskSaveButton').isDisabled(),false);
  await page.evaluate(()=>failSave=false);
  await page.locator('#taskSaveButton').click();
  await page.waitForFunction(()=>!document.getElementById('taskDialog').classList.contains('show'));
  const assignments=await page.evaluate(()=>requests.filter(r=>r.url.endsWith('/assign')));
  assert.equal(assignments.length,2);assert.equal(assignments[0].body.requestId,assignments[1].body.requestId);
  assert.equal(assignments[0].body.expectedRevision,1);assert.equal(assignments[0].body.target.type,'AGENT');
  assert.match(await page.locator('#taskList').innerText(),/Worker/);
  // Source and derived links open the related task in both directions.
  await page.evaluate(()=>{
   const previousApi=api;
   window.childTask={...pool[0],id:'task-child',name:'派生任务',sourceTask:{id:'task-one',name:'公开任务'}};
   api=async(url,options)=>{
    if(url.endsWith('/task-child'))return new Response(JSON.stringify({accepted:true,data:{task:childTask,sourceTask:{id:'task-one',name:'公开任务',deleted:false},derivedTasks:{items:[],total:0,page:1,pageSize:20}}}));
    if(url.endsWith('/task-one'))return new Response(JSON.stringify({accepted:true,data:{task:pool[0],derivedTasks:{items:[{id:'task-child',name:'派生任务'}],total:1,page:1,pageSize:20}}}));
    return previousApi(url,options);
   };
   return openTaskEdit('task-child',true);
  });
  assert.match(await page.locator('#taskRelations').innerText(),/来源任务/);
  await page.locator('[data-related-task="task-one"]').click();
  await page.waitForFunction(()=>taskEditor.task.id==='task-one');
  assert.match(await page.locator('#taskRelations').innerText(),/派生任务/);
  await page.locator('[data-related-task="task-child"]').click();
  await page.waitForFunction(()=>taskEditor.task.id==='task-child');
  await page.evaluate(()=>renderTaskRelations({task:childTask,sourceTask:{id:'task-one',name:'公开任务',deleted:true}}));
  assert.match(await page.locator('#taskRelations').innerText(),/来源任务已删除/);
  assert.equal(await page.locator('[data-related-task="task-one"]').count(),0);
  await page.evaluate(()=>renderTaskRelations({task:childTask,derivedTasks:{items:Array.from({length:20},(_,i)=>({id:'derived-'+i,name:'派生任务 '+i+'：较长的任务说明用于验证关联记录区域独立滚动'})),total:20,page:1,pageSize:20}}));
  assert.ok((await page.locator('#taskRelations').boundingBox()).height<=161);
  assert.ok(await page.locator('#taskRelations').evaluate(el=>el.scrollHeight>el.clientHeight));
  assert.deepEqual(errors,[]);await page.close();console.log(`Task pool create/assign/retry passed: ${width}px`);
 }}finally{await browser.close();await new Promise(resolve=>server.close(resolve));}
})().catch(e=>{console.error(e);process.exitCode=1;});

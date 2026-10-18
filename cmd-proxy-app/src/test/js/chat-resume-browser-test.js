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
const server = http.createServer((req, res) => {
    if (req.url.startsWith('/assets/')) {
        res.setHeader('Content-Type', req.url.endsWith('.css') ? 'text/css' : 'application/javascript');
        res.end(fs.readFileSync(path.join(root, req.url)));
    } else {res.setHeader('Content-Type', 'text/html'); res.end(html);}
});
(async () => {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const browser = await chromium.launch({executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox']});
    const errors = [];
    try {
        for (const lane of ['ordinary', 'team']) for (const remote of [false, true]) {
            const page = await browser.newPage({viewport: {width: 390, height: 844}, isMobile: true, hasTouch: true});
            page.on('pageerror', e => errors.push(e.message));
            await page.goto(`http://127.0.0.1:${server.address().port}/`, {waitUntil: 'domcontentloaded'});
            await page.evaluate(({lane, remote}) => {
                window.streams = []; window.notices = []; window.historyCalls = [];
                window.EventSource = class {
                    constructor(url) {this.url=url; this.readyState=1; this.handlers={}; streams.push(this);}
                    addEventListener(name,fn) {this.handlers[name]=fn;} close() {this.readyState=2;}
                };
                showSnackbar = message => notices.push(message);
                curInstance = {instanceId: remote ? 'center-proxy-remote' : 'local'};
                const member = {teamMemberId: 'm', acpClientId: 'c', sessionId: 's', displayName: '成员', state: 'BUSY'};
                const session = {groupId: 'g', sessionId: 's', generation: 1, state: 'BUSY', robotName: '测试'};
                const text = '后台前的消息\n'.repeat(100);
                window.backendEvents = [
                    {eventSeq: 1, groupId: 'g', sessionId: 's', generation: 1, messageId: 'user:1', type: 'USER_MESSAGE_ACCEPTED', payload: {content: text}},
                    {eventSeq: 2, groupId: 'g', sessionId: 's', generation: 1, messageId: 'tool:t', type: 'TOOL_CALL_UPDATED', payload: {toolCallId:'t',title:'查询',status:'completed',update:{output:'结果'}}},
                    {eventSeq: 3, groupId: 'g', sessionId: 's', generation: 1, messageId: 'assistant:3', turnId:'turn-3',type:'ASSISTANT_MESSAGE_DELTA',payload:{text:'输出到一半'}}
                ];
                window.teamRows = () => backendEvents.map(e => e.type === 'USER_MESSAGE_ACCEPTED'
                    ? {role:'USER',messageId:e.messageId,revision:1,content:e.payload.content}
                    : e.type === 'TOOL_CALL_UPDATED' ? {kind:'TEAM_EVENT',eventType:'TOOL_CALL',messageId:'tool:t',revision:1,payload:e.payload}
                    : {role:'ASSISTANT',messageId:e.messageId,revision:backendEvents.length,content:e.payload.text});
                window.backendSession=session;window.backendMember=member;window.backendSessionId='s';
                window.pageOf = (rows, before) => {const end=before?Number(before):rows.length,start=Math.max(0,end-50);return {items:rows.slice(start,end),replayAfter:Math.max(...backendEvents.map(e=>e.eventSeq)),sessionId:backendSessionId,hasMore:start>0,olderCursor:start?String(start):null}};
                api = async function(url, options) {
                    if (url.includes('/sessions/events?')) {
                        const before=new URL(url,location.href).searchParams.get('before'); historyCalls.push(before);
                        const data=pageOf(backendEvents,before); data.events=data.items;
                        return {ok:true,json:async()=>({accepted:true,data})};
                    }
                    if (url === '/api/starweave/v1/sessions') return {ok:true,json:async()=>({accepted:true,data:{sessions:[{...session}]}})};
                    if (url === '/api/starweave/v1/teams') return {ok:true,json:async()=>({accepted:true,data:{data:{teams:[{teamId:'t',name:'团队',members:[{...member}]}]}}})};
                    const body=JSON.parse(options.body),action=body.action;
                    let data={};
                    if(action==='history'){historyCalls.push(body.before||null);data=pageOf(teamRows(),body.before);data.messages=data.items;}
                    if(action==='listSessions')data={sessions:[]};
                    if(action==='context')data={contextUsagePercentage:10};
                    return {ok:true,json:async()=>({accepted:true,data:{accepted:true,data}})};
                };
                activePage=lane==='ordinary'?'sessions':'teams';
                document.querySelectorAll('.page-section').forEach(el=>el.classList.toggle('active',el.id==='page-'+activePage));
                starSessions.items=[session];starSessions.selectedGroupId='g';starSessions.events=backendEvents.slice();starSessions.lastSeq=3;starSessions.followOutput=false;
                chatHistoryState(starSessions,starHistoryScope()).loaded=true;
                starTeams.items=[{teamId:'t',name:'团队',members:[member]}];
                teamSession={teamId:'t',teamMemberId:'m',members:[member],messages:teamRows(),liveItems:[],sessions:[],uploads:[],lastSeq:3,snapshotToken:0,followOutput:false};
                chatHistoryState(teamSession,teamHistoryScope()).loaded=true;
                if(lane==='ordinary'){document.body.classList.add('sessions-active');renderStarweaveSessionDetail();connectStarweaveStream();}
                else {showDialog('teamSessionDialog');renderTeamSessionDetail();connectTeamSessionStream();}
                window.box=document.getElementById(lane==='ordinary'?'starSessionMessages':'teamSessionMessages');
                window.input=document.getElementById(lane==='ordinary'?'starMessageInput':'teamSessionInput');input.value='未发送的草稿';
                window.firstNode=box.querySelector('[data-chat-key]');
                box.dispatchEvent(new WheelEvent('wheel',{deltaY:-1}));box.scrollTop=100;
                scrollChatViewport(box,lane==='ordinary'?starSessions:teamSession,function(){});
                window.anchor=chatVisibleAnchor(box);
                installChatRecoveryListeners();
                window.oldStream=streams[streams.length-1];
                window.dispatchEvent(new Event('starweave:pause'));
                // Agent finishes while the WebView delivers no callbacks. More than one history page is missing.
                backendEvents[2]={...backendEvents[2],payload:{text:'输出到一半，后台完成后的完整内容'}};
                for(let i=4;i<=140;i++)backendEvents.push({eventSeq:i,groupId:'g',sessionId:'s',generation:1,messageId:'assistant:'+i,turnId:'turn-'+i,type:'ASSISTANT_MESSAGE_DELTA',payload:{text:'后台消息 '+i}});
                backendEvents.push({eventSeq:141,groupId:'g',sessionId:'s',generation:1,messageId:'assistant:141',turnId:'turn-141',type:'ASSISTANT_MESSAGE_DELTA',payload:{text:'最终完整回复 END'}});
                session.state='READY';member.state='READY';
                window.dispatchEvent(new Event('starweave:resume'));
                window.dispatchEvent(new Event('online'));
            }, {lane,remote});
            await page.waitForFunction(lane => !chatRecoveryPending && (lane==='ordinary'?starSessions.lastSeq:teamSession.lastSeq)===141,lane);
            await page.evaluate(()=>new Promise(resolve=>requestAnimationFrame(()=>requestAnimationFrame(resolve))));
            const result=await page.evaluate(lane=>({
                text:box.innerText,rows:box.querySelectorAll('[data-chat-key]').length,
                draft:input.value,oldClosed:oldStream.readyState===2,
                stream:streams[streams.length-1].url,historyCalls,notices,
                sameFirst:firstNode===box.querySelector('[data-chat-key]'),anchor:chatVisibleAnchor(box),before:anchor,
                follow:lane==='ordinary'?starSessions.followOutput:teamSession.followOutput
            }),lane);
            assert.match(result.text,/后台完成后的完整内容/);assert.match(result.text,/最终完整回复 END/);
            assert.equal(result.rows,141);assert.equal(result.draft,'未发送的草稿');assert.equal(result.oldClosed,true);
            assert.match(result.stream,/after=141/);assert.ok(result.stream.includes('instance='+(remote?'center-proxy-remote':'local')));
            assert.equal(result.historyCalls.length,3);assert.deepEqual(result.notices,[]);assert.equal(result.sameFirst,true);assert.equal(result.follow,false);
            assert.equal(result.anchor.key,result.before.key);assert.ok(Math.abs(result.anchor.offset-result.before.offset)<=2,JSON.stringify(result.anchor));
            // The reconnected stream continues the same last message without duplicating its prefix.
            await page.evaluate(lane=>{
                const stream=streams[streams.length-1];
                const event=lane==='ordinary'
                    ? {eventSeq:142,groupId:'g',sessionId:'s',generation:1,messageId:'assistant:141',turnId:'turn-141',type:'ASSISTANT_MESSAGE_DELTA',payload:{text:'，前台继续输出'}}
                    : {eventSeq:142,teamId:'t',teamMemberId:'m',type:'MESSAGE_UPDATED',data:{sessionId:'s',messageId:'assistant:141',revision:142,content:'最终完整回复 END，前台继续输出'}};
                stream.handlers[lane==='ordinary'?'starweave':'starweave-team']({data:JSON.stringify(event)});
            },lane);
            await page.waitForFunction(()=>box.innerText.includes('END，前台继续输出'));
            assert.equal(await page.locator(lane==='ordinary'?'#starSessionMessages [data-chat-key]':'#teamSessionMessages [data-chat-key]').count(),141);
            // A server-side session replacement while backgrounded must replace old history and stream identity.
            await page.evaluate(()=>{
                window.dispatchEvent(new Event('starweave:pause'));
                backendSessionId='new-session';backendSession.sessionId=backendSessionId;backendSession.generation=2;backendMember.sessionId=backendSessionId;
                backendEvents=[{eventSeq:200,groupId:'g',sessionId:backendSessionId,generation:2,messageId:'assistant:200',turnId:'turn-200',type:'ASSISTANT_MESSAGE_DELTA',payload:{text:'新会话完整内容'}}];
                window.dispatchEvent(new Event('starweave:resume'));
            });
            await page.waitForFunction(lane=>!chatRecoveryPending&&(lane==='ordinary'?starSessions.lastSeq:teamSession.lastSeq)===200,lane);
            assert.match(await page.evaluate(()=>box.innerText),/新会话完整内容/);
            assert.doesNotMatch(await page.evaluate(()=>box.innerText),/后台前的消息|最终完整回复 END/);
            assert.equal(await page.locator(lane==='ordinary'?'#starSessionMessages [data-chat-key]':'#teamSessionMessages [data-chat-key]').count(),1);
            assert.match(await page.evaluate(()=>streams[streams.length-1].url),/after=200/);
            if(lane==='ordinary')assert.match(await page.evaluate(()=>streams[streams.length-1].url),/sessionId=new-session&generation=2/);
            console.log('Resume history and stream verified:',lane,remote?'center forwarding':'direct');
            await page.close();
        }
        assert.deepEqual(errors,[]);
    } finally {await browser.close();server.close();}
})().catch(e=>{console.error(e);process.exitCode=1;server.close();});

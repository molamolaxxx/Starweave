// Run with node; real EventSource replay through a simulated center endpoint.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const http = require('node:http');
const {chromium} = require('playwright');
const root = path.resolve(__dirname, '../../main/resources/configui');
const markup = fs.readFileSync(path.join(root, 'index.html'), 'utf8')
    .replace(/<script[^>]*src="https?:[^>]*><\/script>/g, '')
    .replace(/<link[^>]*href="https?:[^>]*>/g, '')
    .replace('<script src="/assets/js/app.js"></script>', '');
let imageRequests = 0;
const streamUrls = [];
const server = http.createServer((req, res) => {
    if (req.url.startsWith('/api/starweave/v1/') && req.url.includes('/stream?')) {
        streamUrls.push(req.url);
        res.writeHead(200, {'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache'});
        const team = req.url.includes('/teams/');
        let seq = 4;
        const timer = setInterval(() => {
            if (seq >= 24) {res.write(': heartbeat\n\n'); return;}
            const event = team
                ? {teamId: 't', teamMemberId: 'm', eventSeq: seq, type: 'MESSAGE_UPDATED', data: {sessionId: 'ts', messageId: 'after', revision: seq, content: '结果' + '持续输出\n\n'.repeat(seq - 3)}}
                : {groupId: 'g', sessionId: 's', generation: 1, turnId: 'turn', eventSeq: seq, type: 'ASSISTANT_MESSAGE_DELTA', payload: {text: '持续输出\n\n'}};
            res.write('event: ' + (team ? 'starweave-team' : 'starweave') + '\ndata: ' + JSON.stringify(event) + '\n\n');
            seq++;
        }, 25);
        req.on('close', () => clearInterval(timer));
    } else if (req.url === '/image.svg') {
        imageRequests++;
        res.setHeader('Content-Type', 'image/svg+xml');
        res.end('<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100"><rect width="200" height="100" fill="#778899"/></svg>');
    } else if (req.url.startsWith('/assets/')) {
        res.setHeader('Content-Type', req.url.endsWith('.css') ? 'text/css' : 'application/javascript');
        res.end(fs.readFileSync(path.join(root, req.url)));
    } else {res.setHeader('Content-Type', 'text/html'); res.end(markup);}
});
async function frames(page) {
    await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));
}
async function run() {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    let browser;
    try {
        browser = await chromium.launch({executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox']});
        for (const width of [390, 1440]) for (const lane of ['ordinary', 'team']) {
            const page = await browser.newPage({viewport: {width, height: 844}, isMobile: width < 500, hasTouch: width < 500});
            const errors = [];
            page.on('pageerror', error => errors.push(error.message));
            await page.goto(`http://127.0.0.1:${server.address().port}/`);
            await page.evaluate(lane => {
                curInstance = {instanceId: 'remote-test', remote: true};
                refreshChannelBindingTargets = function() {};
                activePage = 'sessions';
                document.documentElement.dataset.theme = 'dark';
                document.body.classList.add('sessions-active');
                document.querySelectorAll('.page').forEach(node => node.classList.remove('active'));
                document.getElementById('page-sessions').classList.add('active');
                syncStarweaveViewport();
                const before = '查询前说明\n\n![图片](' + location.origin + '/image.svg)\n\n| 表名 | 说明 |\n| --- | --- |\n' + '| voucher | 凭证 |\n'.repeat(60);
                const tool = {toolCallId: 'tool1', title: '查询工具', status: 'running', update: {output: '工具详情\n'.repeat(300)}};
                if (lane === 'ordinary') {
                    starSessions.items = [{groupId: 'g', sessionId: 's', generation: 1, state: 'BUSY', robotName: '测试'}];
                    starSessions.selectedGroupId = 'g'; starSessions.lastSeq = 3; starSessions.followOutput = false;
                    starSessions.events = [
                        {eventSeq: 1, turnId: 'turn', type: 'ASSISTANT_MESSAGE_DELTA', payload: {text: before}},
                        {eventSeq: 2, turnId: 'turn', type: 'TOOL_CALL_UPDATED', payload: tool},
                        {eventSeq: 3, turnId: 'turn', type: 'ASSISTANT_MESSAGE_DELTA', payload: {text: '结果'}}
                    ];
                    chatHistoryState(starSessions, starHistoryScope()).loaded = true;
                    renderStarweaveEvents();
                } else {
                    starTeams.items = [{teamId: 't', name: '测试团队', members: [{teamMemberId: 'm', acpClientId: 'c', sessionId: 'ts', displayName: '测试成员', state: 'BUSY'}]}];
                    teamSessionPost = async () => {throw new Error('No snapshot needed during replay');};
                    teamSession = {teamId: 't', teamMemberId: 'm', members: starTeams.items[0].members, messages: [], liveItems: [], sessions: [], uploads: [], lastSeq: 3, followOutput: false};
                    chatHistoryState(teamSession, teamHistoryScope()).loaded = true;
                    mergeTeamMessage({role: 'ASSISTANT', messageId: 'before', revision: 1, content: before});
                    mergeTeamMessage({kind: 'TEAM_EVENT', eventType: 'TOOL_CALL', messageId: 'tool:tool1', revision: 1, payload: tool});
                    mergeTeamMessage({role: 'ASSISTANT', messageId: 'after', revision: 3, content: '结果'});
                    showDialog('teamSessionDialog'); renderTeamSessionDetail();
                }
                window.box = document.getElementById(lane === 'ordinary' ? 'starSessionMessages' : 'teamSessionMessages');
                window.refs = {before: box.querySelector('.assistant'), tool: box.querySelector('.tool-row'), after: box.querySelectorAll('.assistant')[1], image: box.querySelector('img'), details: box.querySelector('details'), detail: box.querySelector('.event-detail')};
                refs.details.open = true; refs.detail.scrollTop = 120;
                box.scrollTop = 150;
                scrollChatViewport(box, lane === 'ordinary' ? starSessions : teamSession, function() {});
                window.anchorBefore = chatVisibleAnchor(box);
            }, lane);
            await frames(page);
            await page.waitForFunction(() => refs.image.complete && refs.image.naturalHeight > 0);
            await frames(page);
            await page.evaluate(lane => {
                box.scrollTop = 150;
                box.dispatchEvent(new WheelEvent('wheel', {deltaY: -1}));
                scrollChatViewport(box, lane === 'ordinary' ? starSessions : teamSession, function() {});
                anchorBefore = chatVisibleAnchor(box);
            }, lane);
            await page.evaluate(lane => lane === 'ordinary' ? connectStarweaveStream() : connectTeamSessionStream(), lane);
            await page.waitForFunction(lane => (lane === 'ordinary' ? starSessions.lastSeq : teamSession.lastSeq) === 23, lane);
            await frames(page);
            const stable = await page.evaluate(() => ({
                allConnected: Object.values(refs).every(node => node.isConnected),
                sameRows: refs.before === box.querySelector('.assistant') && refs.tool === box.querySelector('.tool-row') && refs.after === box.querySelectorAll('.assistant')[1],
                sameImage: refs.image === box.querySelector('img'),
                expanded: refs.details.open, detailTop: refs.detail.scrollTop,
                order: Array.from(box.querySelectorAll('.chat-message-content > .message-row')).map(node => node.classList.contains('assistant') ? 'text' : 'tool'),
                anchor: chatVisibleAnchor(box), previousAnchor: anchorBefore
            }));
            assert.equal(stable.allConnected, true); assert.equal(stable.sameRows, true); assert.equal(stable.sameImage, true);
            assert.equal(stable.expanded, true); assert.equal(stable.detailTop, 120);
            assert.deepEqual(stable.order, ['text', 'tool', 'text']);
            assert.equal(stable.anchor.key, stable.previousAnchor.key);
            assert.ok(Math.abs(stable.anchor.offset - stable.previousAnchor.offset) <= 2, JSON.stringify(stable));
            // Changing the tool payload preserves its expanded detail and internal scroll too.
            await page.evaluate(lane => {
                const payload = {toolCallId: 'tool1', title: '查询工具', status: 'completed', update: {output: '更新后的详情\n'.repeat(400)}};
                if (lane === 'ordinary') {starSessions.events.push({eventSeq: 24, turnId: 'turn', type: 'TOOL_CALL_UPDATED', payload}); renderStarweaveEvents();}
                else {appendTeamSessionEvent({teamId: 't', teamMemberId: 'm', eventSeq: 24, type: 'TOOL_CALL', data: {...payload, messageId: 'tool:tool1', revision: 24}}); renderTeamSessionMessages();}
            }, lane);
            await frames(page);
            assert.equal(await page.evaluate(() => refs.tool.isConnected && refs.detail.isConnected && refs.details.open && refs.detail.scrollTop === 120), true);
            assert.deepEqual(errors, []);
            console.log(`PASS ${width}px ${lane}: center SSE, text/tool/text order, stable rows/image, expanded tool scroll, reading anchor`);
            await page.close();
        }
        assert.equal(streamUrls.length, 4);
        assert.ok(streamUrls.every(url => url.includes('instance=remote-test') && url.includes('after=3')));
        assert.equal(imageRequests, 4);
    } finally {
        if (browser) await browser.close();
        await new Promise(resolve => server.close(resolve));
    }
}
run().catch(error => {console.error(error); process.exitCode = 1;});

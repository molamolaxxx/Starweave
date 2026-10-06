// 运行：node cmd-proxy-app/src/test/js/chat-history-browser-test.js（需 Playwright 和 Chrome）
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
const server = http.createServer((request, response) => {
    if (request.url === '/slow.svg') {
        setTimeout(() => {response.setHeader('Content-Type', 'image/svg+xml'); response.end('<svg xmlns="http://www.w3.org/2000/svg" width="400" height="600"><rect width="400" height="600" fill="#778899"/></svg>');}, 500);
    } else if (request.url.startsWith('/assets/')) {
        const file = path.join(root, request.url);
        response.setHeader('Content-Type', file.endsWith('.css') ? 'text/css' : 'application/javascript');
        response.end(fs.readFileSync(file));
    } else {response.setHeader('Content-Type', 'text/html'); response.end(html);}
});
async function frame(page) {await page.evaluate(() => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(resolve))));}
async function anchor(page, id) {return page.evaluate(id => chatVisibleAnchor(document.getElementById(id)), id);}
async function checkAnchor(page, id, expected) {
    const actual = await anchor(page, id); assert.equal(actual.key, expected.key); assert.ok(Math.abs(actual.offset - expected.offset) <= 2, JSON.stringify({expected, actual}));
}
async function run() {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const browser = await chromium.launch({executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox']});
    const results = [], errors = [];
    try {
        for (const viewport of [{width: 1440, height: 900}, {width: 390, height: 844}]) {
            const context = await browser.newContext({viewport, isMobile: viewport.width < 500, hasTouch: viewport.width < 500});
            const page = await context.newPage(); page.on('pageerror', e => errors.push(e.message));
            await page.goto(`http://127.0.0.1:${server.address().port}/`, {waitUntil: 'domcontentloaded'});
            await page.evaluate(() => {
                window.EventSource = class {addEventListener() {} close() {}};
                showSnackbar = function() {}; refreshChannelBindingTargets = async function() {};
                activePage = 'sessions'; document.body.classList.add('sessions-active');
                document.querySelectorAll('.page').forEach(node => node.classList.remove('active'));
                document.getElementById('page-sessions').classList.add('active');
                syncStarweaveViewport();
                curInstance = {instanceId: 'center-proxy-remote', remote: true};
                window.calls = []; window.olderGate = null; window.historyFailure = false;
                window.entries = Array.from({length: 150}, (_, i) => {
                    const event = {groupId: 'g', sessionId: 's', generation: 1, eventSeq: i + 1, messageId: 'event:' + (i + 1), type: 'USER_MESSAGE_ACCEPTED', payload: {content: '消息 ' + i + '\n第二行'}};
                    if (i % 3 === 0) {event.type = 'TOOL_CALL_UPDATED'; event.messageId = 'tool:t' + i; event.payload = {toolCallId: 't' + i, title: '工具 ' + i, status: 'completed', update: {output: '长输出\n'.repeat(1000)}};}
                    if (i === 149) {event.type = 'ASSISTANT_MESSAGE_DELTA'; event.turnId = 'reply'; event.messageId = 'assistant:150'; event.payload = {text: '最近的回复\n\n![延迟图片](' + location.origin + '/slow.svg)'};}
                    return event;
                });
                window.pageData = function(before) {const end = before ? Number(before) : entries.length, start = Math.max(0, end - 50); return {events: entries.slice(start, end), replayAfter: entries.length, hasMore: start > 0, olderCursor: start > 0 ? String(start) : null};};
                api = async function(url) {
                    if (window.initialGate) await initialGate;
                    calls.push(url); const before = new URL(url, location.href).searchParams.get('before');
                    if (before && olderGate) await olderGate;
                    if (before && historyFailure) return {ok: false, json: async () => ({accepted: false, message: '测试失败'})};
                    return {ok: true, json: async () => ({accepted: true, data: pageData(before)})};
                };
                starSessions.items = [{groupId: 'g', sessionId: 's', generation: 1, state: 'READY', robotName: '测试智能体'}];
                window.initialGate = new Promise(resolve => window.releaseInitial = resolve);
                window.initialLoad = selectStarweaveSession('g');
            });
            assert.equal(await page.locator('#starSessionMessages .chat-loading-spinner').count(), 1);
            assert.equal(await page.locator('#starSessionMessages .chat-history-status').count(), 0);
            assert.equal(await page.locator('#starSessionMessages .chat-loading-spinner').evaluate(node => getComputedStyle(node).animationName), 'chat-loading-spin');
            await page.evaluate(() => {releaseInitial(); initialGate = null; return initialLoad;});
            assert.equal(await page.locator('#starSessionMessages [data-chat-key]').count(), 50);
            await page.waitForFunction(() => {const img = document.querySelector('#starSessionMessages img'); return img && img.complete && img.naturalHeight > 0;});
            await frame(page);
            assert.ok(await page.evaluate(() => {const b = document.getElementById('starSessionMessages'); return b.scrollHeight - b.clientHeight - b.scrollTop < 2 && starSessions.followOutput;}));
            // 翻历史前先锁住请求，验证插入前后同一消息的屏幕位置。
            await page.evaluate(() => {
                olderGate = new Promise(resolve => window.releaseOlder = resolve);
                const box = document.getElementById('starSessionMessages'); box.dispatchEvent(new WheelEvent('wheel', {deltaY: -100})); box.scrollTop = 200; handleStarMessageScroll();
            });
            await frame(page); const before = await anchor(page, 'starSessionMessages');
            assert.equal(await page.locator('#starSessionMessages .chat-history-status .chat-loading-spinner').count(), 1);
            await page.evaluate(() => {releaseOlder(); olderGate = null;});
            await page.waitForFunction(() => starSessions.events.length === 100 && !starSessions.history.loading);
            await frame(page); await checkAnchor(page, 'starSessionMessages', before);
            // 阅读旧消息时到达的新消息和图片高度变化均不拉走用户。
            await page.evaluate(() => {
                appendStarweaveEvent({groupId: 'g', sessionId: 's', generation: 1, eventSeq: 151, type: 'USER_MESSAGE_ACCEPTED', payload: {content: '新的实时消息'}}, true); renderStarweaveEvents();
                const image = document.querySelector('#starSessionMessages img'); image.style.height = '800px';
            });
            await frame(page); await checkAnchor(page, 'starSessionMessages', before);
            // 加载失败保留现有消息，并能点击重试。
            await page.evaluate(() => {historyFailure = true; return loadOlderStarweaveHistory();});
            assert.equal(await page.locator('#starSessionMessages [role="button"]').count(), 1);
            await page.evaluate(() => {historyFailure = false;});
            await page.locator('#starSessionMessages .chat-history-status').click();
            await page.waitForFunction(() => !starSessions.history.hasMore && !starSessions.history.loading);
            assert.equal(await page.locator('#starSessionMessages [data-chat-key]').count(), 151);
            // 返回底部后重新跟随，展开工具详情仍然到底。
            await page.evaluate(() => {const box = document.getElementById('starSessionMessages'); box.scrollTop = box.scrollHeight; handleStarMessageScroll();}); await frame(page);
            await page.evaluate(() => {document.querySelector('#starSessionMessages details').open = true;}); await frame(page);
            assert.ok(await page.evaluate(() => {const box = document.getElementById('starSessionMessages'); return box.scrollHeight - box.clientHeight - box.scrollTop < 2;}));
            assert.ok(await page.evaluate(() => {const box = document.getElementById('starSessionMessages'), detail = box.querySelector('details .event-detail'), top = box.scrollTop; detail.scrollTop = detail.scrollHeight; return detail.scrollHeight > detail.clientHeight && detail.scrollTop > 0 && box.scrollTop === top;}));
            await page.evaluate(() => renderStarweaveEvents());
            assert.ok(await page.evaluate(() => document.querySelector('#starSessionMessages details').open));
            // 团队首屏不等待慢的辅助请求，随后使用相同锚点机制。
            await page.evaluate(() => {
                window.metadataGate = new Promise(resolve => window.releaseMetadata = resolve);
                window.teamEntries = entries.map(event => event.type === 'TOOL_CALL_UPDATED' ? {kind: 'TEAM_EVENT', eventType: 'TOOL_CALL', messageId: event.messageId, revision: 1, payload: event.payload} : {role: event.type === 'ASSISTANT_MESSAGE_DELTA' ? 'ASSISTANT' : 'USER', messageId: event.messageId, revision: 1, content: event.payload.content || event.payload.text});
                teamSessionPost = async function(action, body) {
                    if (action === 'history' && window.teamInitialGate) await teamInitialGate;
                    calls.push({action, body}); if (action !== 'history') {await metadataGate; return {};}
                    if (body.before && olderGate) await olderGate;
                    const end = body.before ? Number(body.before) : teamEntries.length, start = Math.max(0, end - 50);
                    return {sessionId: 'ts', messages: teamEntries.slice(start, end), replayAfter: 200, hasMore: start > 0, olderCursor: start > 0 ? String(start) : null};
                };
                starTeams.items = [{teamId: 't', name: '测试团队', members: [{teamMemberId: 'm', acpClientId: 'c', sessionId: 'ts', displayName: '成员', state: 'READY'}]}];
                window.teamInitialGate = new Promise(resolve => window.releaseTeamInitial = resolve);
                openTeamSessionDialog('t', 'm');
            });
            assert.equal(await page.locator('#teamSessionMessages .chat-loading-spinner').count(), 1);
            assert.equal(await page.locator('#teamSessionMessages .chat-history-status').count(), 0);
            await page.evaluate(() => {releaseTeamInitial(); teamInitialGate = null;});
            await page.waitForFunction(() => teamSession.history && teamSession.history.loaded && !teamSession.loading);
            assert.equal(await page.locator('#teamSessionMessages [data-chat-key]').count(), 50);
            await frame(page);
            await page.evaluate(() => {
                olderGate = new Promise(resolve => window.releaseOlder = resolve);
                const box = document.getElementById('teamSessionMessages'); box.dispatchEvent(new WheelEvent('wheel', {deltaY: -100})); box.scrollTop = 180; handleTeamSessionScroll();
            });
            await frame(page); const teamBefore = await anchor(page, 'teamSessionMessages');
            assert.equal(await page.locator('#teamSessionMessages .chat-history-status .chat-loading-spinner').count(), 1);
            await page.evaluate(() => {releaseOlder(); olderGate = null;});
            await page.waitForFunction(() => teamSession.messages.length === 100 && !teamSession.history.loading);
            await frame(page); await checkAnchor(page, 'teamSessionMessages', teamBefore);
            await page.evaluate(() => {document.querySelector('#teamSessionMessages details').open = true;}); await frame(page);
            await checkAnchor(page, 'teamSessionMessages', teamBefore);
            assert.ok(await page.evaluate(() => {const box = document.getElementById('teamSessionMessages'), detail = box.querySelector('details .event-detail'), top = box.scrollTop; detail.scrollTop = detail.scrollHeight; return detail.scrollHeight > detail.clientHeight && detail.scrollTop > 0 && box.scrollTop === top;}));
            await page.evaluate(() => {appendTeamSessionEvent({teamId: 't', teamMemberId: 'm', eventSeq: 201, type: 'MESSAGE_UPDATED', data: {sessionId: 'ts', messageId: 'newreply', revision: 1, content: '实时消息'}}); renderTeamSessionMessages();});
            await frame(page); await checkAnchor(page, 'teamSessionMessages', teamBefore);
            // 切换成员后，前一会话迟到的历史不能污染新成员。
            await page.evaluate(() => {
                olderGate = new Promise(resolve => window.releaseOlder = resolve); window.oldLoad = loadOlderTeamSessionHistory();
                teamSession.members.push({teamMemberId: 'm2', acpClientId: 'c2', sessionId: 'ts2', displayName: '另一成员', state: 'READY'});
                teamSession.teamMemberId = 'm2'; teamSession.messages = []; teamSession.history = null;
                releaseOlder(); olderGate = null; return oldLoad;
            });
            assert.equal(await page.evaluate(() => teamSession.messages.length), 0);
            await page.evaluate(() => releaseMetadata());
            // 一页就能展示完的会话，首屏也不显示结束提示；上滑触发后才显示。
            await page.evaluate(() => {closeTeamSessionDialog(); entries = entries.slice(0, 20); return selectStarweaveSession('g');});
            assert.equal(await page.locator('#starSessionMessages .chat-history-status').count(), 0);
            await frame(page);
            await page.evaluate(() => {const box = document.getElementById('starSessionMessages'); box.dispatchEvent(new WheelEvent('wheel', {deltaY: -100})); box.scrollTop = 0; handleStarMessageScroll();});
            assert.match(await page.locator('#starSessionMessages .chat-history-status').textContent(), /没有更早的消息了/);
            await page.evaluate(() => {teamEntries = teamEntries.slice(0, 20); openTeamSessionDialog('t', 'm');});
            await page.waitForFunction(() => teamSession.history && teamSession.history.loaded && !teamSession.loading);
            assert.equal(await page.locator('#teamSessionMessages .chat-history-status').count(), 0);
            await frame(page);
            await page.evaluate(() => {const box = document.getElementById('teamSessionMessages'); box.dispatchEvent(new WheelEvent('wheel', {deltaY: -100})); box.scrollTop = 0; handleTeamSessionScroll();});
            assert.match(await page.locator('#teamSessionMessages .chat-history-status').textContent(), /没有更早的消息了/);
            results.push({viewport, firstPage: 50, historyAnchored: true, bottomAfterImage: true, teamMetadataIndependent: true, staleResponseIgnored: true});
            await context.close();
        }
        assert.deepEqual(errors, []); console.log(JSON.stringify({results, errors}, null, 2));
    } finally {await browser.close(); server.close();}
}
run().catch(error => {console.error(error); process.exitCode = 1; server.close();});

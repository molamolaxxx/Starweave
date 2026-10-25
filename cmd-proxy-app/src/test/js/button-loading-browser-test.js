// Run with node; exercise real HTML handlers and DOM with slow/fast/failed requests.
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
const server = http.createServer((req, res) => {
    const asset = req.url.startsWith('/assets/');
    res.setHeader('Content-Type', asset ? (req.url.endsWith('.css') ? 'text/css' : 'application/javascript') : 'text/html');
    res.end(asset ? fs.readFileSync(path.join(root, req.url)) : markup);
});
async function run() {
    await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
    const browser = await chromium.launch({executablePath: process.env.CHROME_BIN || '/usr/bin/google-chrome', headless: true, args: ['--no-sandbox']});
    let checks = 0;
    try {
        for (const width of [1440, 390]) for (const theme of ['light', 'dark']) {
            const page = await browser.newPage({viewport: {width, height: 900}});
            const errors = [];
            page.on('pageerror', e => errors.push(e.message));
            await page.goto(`http://127.0.0.1:${server.address().port}/`);
            await page.evaluate(theme => document.documentElement.setAttribute('data-theme', theme), theme);
            // All six actual dialog buttons must provide feedback and close after success.
            const cases = [
                ['robotDialog', 'robotDialogSubmit', 'saveRobot'],
                ['channelDialog', 'channelDialogSubmit', 'saveChannelDialog'],
                ['starTeamDialog', 'starTeamCreateButton', 'createStarweaveTeam'],
                ['taskDialog', 'taskSaveButton', 'saveTask'],
                ['scheduleDialog', 'scheduleSaveButton', 'saveScheduleTask'],
                ['observationEditorDialog', 'observationSaveButton', 'saveObservation']
            ];
            for (const [dialog, id, fn] of cases) {
                await page.evaluate(({dialog, id, fn}) => {
                    window.originalAction = window[fn];
                    window.calls = 0;
                    window[fn] = async function() {
                        window.calls++;
                        await new Promise(resolve => { window.finishAction = resolve; });
                        closeDialog(dialog);
                    };
                    window.originalLabelNode = document.querySelector('#' + id + ' > span');
                    document.getElementById(id).disabled = false;
                    showDialog(dialog);
                    document.getElementById(id).click();
                }, {dialog, id, fn});
                await page.waitForSelector('#' + id + '.button-loading');
                assert.equal(await page.locator('#' + id).isDisabled(), true);
                assert.equal(await page.locator('#' + dialog).isVisible(), true);
                assert.equal(await page.locator('#' + id).getAttribute('aria-busy'), 'true');
                const visual = await page.locator('#' + id).evaluate(button => {
                    const indicator = button.querySelector('.button-loading-indicator');
                    return {color: getComputedStyle(indicator).color, visibility: getComputedStyle(indicator).visibility,
                        label: indicator.textContent, spinner: !!indicator.querySelector('.button-loading-spinner'),
                        width: button.getBoundingClientRect().width};
                });
                assert.notEqual(visual.color, 'rgba(0, 0, 0, 0)');
                assert.equal(visual.visibility, 'visible');
                assert.match(visual.label, /保存中|创建中|发布中|分配中/);
                assert.ok(visual.spinner && visual.width > 30);
                await page.evaluate(async ({id, fn}) => {
                    const button = document.getElementById(id);
                    await runButtonAction(button, '重复提交', function() { window.calls++; });
                    finishAction();
                    await new Promise(resolve => setTimeout(resolve, 0));
                    window[fn] = window.originalAction;
                }, {id, fn});
                assert.equal(await page.evaluate(() => window.calls), 1);
                assert.equal(await page.locator('#' + dialog).isVisible(), false);
                assert.equal(await page.locator('#' + id).isDisabled(), false);
                assert.equal(await page.locator('#' + id + ' .button-loading-indicator').count(), 0);
                assert.equal(await page.evaluate(id => !window.originalLabelNode || document.querySelector('#' + id + ' > span') === window.originalLabelNode, id), true);
                checks++;
            }
            // A fast request never adds a spinner; a failed one restores the original DOM.
            assert.deepEqual(await page.evaluate(async () => {
                const button = document.getElementById('taskSaveButton'), html = button.innerHTML;
                await runButtonAction(button, '保存中…', async () => {});
                const fast = !button.classList.contains('button-loading');
                let failed = false;
                try { await runButtonAction(button, '保存中…', async () => {
                    await new Promise(resolve => setTimeout(resolve, 350)); throw new Error('offline');
                }); } catch(e) { failed = e.message === 'offline'; }
                return [fast, failed, button.innerHTML === html, !button.disabled, !button.hasAttribute('aria-busy')];
            }), [true, true, true, true, true]);
            checks++;
            // Real schedule save: delay the API, then test both success and failure.
            for (const fail of [false, true]) {
                await page.evaluate(fail => {
                    scheduleState.editing = {taskId: 'test', owner: {ownerPath: 'local'}};
                    document.getElementById('scheduleEditTitle').value = 'test';
                    document.getElementById('scheduleEditPrompt').value = 'test';
                    document.getElementById('scheduleEditType').value = 'cron';
                    document.getElementById('scheduleEditExpr').value = '0 8 * * *';
                    loadSchedules = async function() {};
                    scheduleApi = async function() {
                        await new Promise(resolve => { window.finishRequest = resolve; });
                        if (fail) throw new Error('connection failed');
                        return {};
                    };
                    showDialog('scheduleDialog');
                    document.getElementById('scheduleSaveButton').click();
                }, fail);
                await page.waitForSelector('#scheduleSaveButton.button-loading');
                await page.evaluate(() => finishRequest());
                await page.waitForFunction(() => !document.getElementById('scheduleSaveButton')._pendingAction);
                assert.equal(await page.locator('#scheduleDialog').isVisible(), fail);
                assert.equal(await page.locator('#scheduleEditTitle').inputValue(), 'test');
                await page.evaluate(() => closeDialog('scheduleDialog'));
                checks++;
            }
            // Real robot save waits for both config persistence and model history.
            await page.evaluate(() => {
                config = {robots: [{name: 'Agent', workDir: '/tmp', agentProvider: 'KIRO_CLI', enabled: true}], channels: [], chatterIds: []};
                loadRobotModels = async () => {};
                render = () => {};
                applyRobotConfig = async () => {};
                api = async path => {
                    if (path === '/api/config') {
                        await new Promise(resolve => { window.finishConfig = resolve; });
                        return {json: async () => ({ok: true})};
                    }
                    assertModelPath = path;
                    await new Promise(resolve => { window.finishModels = resolve; });
                    return {json: async () => ({})};
                };
                openRobotDialog(0);
                document.getElementById('robotDialogSubmit').click();
            });
            await page.waitForSelector('#robotDialogSubmit.button-loading');
            await page.evaluate(() => finishConfig());
            await page.waitForFunction(() => typeof window.finishModels === 'function');
            assert.equal(await page.locator('#robotDialog').isVisible(), true);
            assert.equal(await page.locator('#robotDialogSubmit').isDisabled(), true);
            assert.equal(await page.evaluate(() => assertModelPath), '/api/provider-models');
            await page.evaluate(() => finishModels());
            await page.waitForFunction(() => !document.getElementById('robotDialogSubmit')._pendingAction);
            assert.equal(await page.locator('#robotDialog').isVisible(), false);
            checks++;
            // A proxy toggle keeps its existing description and blocks repeated changes.
            await page.evaluate(() => {
                saveConfig = async () => { await new Promise(resolve => { window.finishProxy = resolve; }); return true; };
                reloadService = async () => true;
                document.querySelectorAll('.page').forEach(node => node.classList.remove('active'));
                document.getElementById('page-basic').classList.add('active');
                document.getElementById('globalProxy').click();
            });
            await page.waitForFunction(() => document.getElementById('globalProxy').disabled);
            await page.waitForSelector('.toggle-desc.button-loading');
            assert.match(await page.locator('.toggle-desc.button-loading .button-loading-indicator').textContent(), /正在应用网络代理/);
            await page.evaluate(() => finishProxy());
            await page.waitForFunction(() => !document.getElementById('globalProxy')._pendingAction);
            assert.equal(await page.locator('.toggle-desc.button-loading').count(), 0);
            checks++;
            // Dynamic controls compile and survive redraws while a team delete is pending.
            await page.evaluate(() => {
                config = {robots: [{name: 'Agent', workDir: '/tmp', enabled: true}], channels: [], chatterIds: []};
                renderRobots();
                starTeams.items = [{teamId: 't1', name: 'Team', version: 1, state: 'READY', members: []}];
                renderStarweaveTeams();
                observationState.items = [{id: 'obs1', name: 'Observe', owner: {}, enabled: true, status: 'ACTIVE'}];
                renderObservations();
                for (const node of document.querySelectorAll('[onclick]')) new Function(node.getAttribute('onclick'));
                window.teamDeleteCalls = 0;
                showConfirm = async () => true;
                api = async () => { window.teamDeleteCalls++; await new Promise(resolve => { window.finishDelete = resolve; }); return {ok: true, json: async () => ({accepted: true})}; };
                loadStarweaveTeams = async () => {};
                refreshChannelBindingTargets = async () => {};
                document.querySelector('#starTeamList .btn-danger').click();
            });
            await page.waitForFunction(() => teamBatchOperations.t1 === 'delete');
            await page.evaluate(() => renderStarweaveTeams());
            assert.match(await page.locator('#starTeamList .btn-danger').innerText(), /删除中/);
            assert.equal(await page.locator('#starTeamList .btn-danger').isDisabled(), true);
            await page.evaluate(async () => { await deleteStarweaveTeam('t1', 1, false); finishDelete(); });
            await page.waitForFunction(() => !teamBatchOperations.t1);
            assert.equal(await page.evaluate(() => window.teamDeleteCalls), 1);
            checks++;
            assert.deepEqual(errors, []);
            await page.close();
        }
        console.log(`PASS: ${checks} browser scenarios across desktop/mobile and light/dark themes`);
    } finally { await browser.close(); server.close(); }
}
run().catch(error => { console.error(error); server.close(); process.exitCode = 1; });

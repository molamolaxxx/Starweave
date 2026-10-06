const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/configui/assets/js/providers.js'), 'utf8');
const render = source.slice(source.indexOf('function renderChatters(){'), source.indexOf('function renderRobots(){'));
function displayedIdentity(instance) {
    const box = {innerHTML: ''};
    const context = vm.createContext({curInstance: instance, config: {chatterIds: []}, esc: value => value,
        document: {getElementById: () => box}});
    vm.runInContext(render, context); context.renderChatters(); return box.innerHTML;
}
test('remote routing alias never becomes a Starweave authorization owner', () => {
    const html = displayedIdentity({remote: true, instanceId: 'remote-route', sourceInstanceId: 'fct-runtime'});
    assert.ok(html.includes('starweave-fct-runtime'));
    assert.ok(!html.includes('starweave-remote-route'));
});
test('local owners use instance identity and unknown remote identity stays pending', () => {
    assert.ok(displayedIdentity({remote: false, instanceId: 'local-runtime'}).includes('starweave-local-runtime'));
    const unknown = displayedIdentity({remote: true, instanceId: 'remote-route'});
    assert.ok(unknown.includes('等待环境身份加载'));
    assert.ok(!unknown.includes('starweave-remote-route'));
});
test('completed realtime messages merge source history and ignore an obsolete scope', async () => {
    const streamSource = fs.readFileSync(path.resolve(__dirname, '../../main/resources/configui/assets/js/starweave.js'), 'utf8');
    const functionSource = streamSource.slice(streamSource.indexOf('async function refreshTeamSessionHistory('),streamSource.indexOf('function connectTeamSessionStream('));
    const merged = []; let scope = 'one'; let rendered = 0;
    const context = vm.createContext({teamSession: {loading: false}, teamHistoryScope: () => scope, selectedTeamSessionMember: () => ({sessionId:'s'}),
        teamSessionPost: async () => ({messages: [{messageId: 'final', revision: 7, content: 'complete'}]}),
        mergeTeamMessage: message => merged.push(message), scheduleTeamSessionRender: () => rendered++});
    vm.runInContext(functionSource, context); await context.refreshTeamSessionHistory();
    assert.equal(merged.length, 1); assert.equal(rendered, 1);
    context.teamSessionPost = async () => { scope = 'two'; return {messages: [{messageId: 'stale'}]}; };
    await context.refreshTeamSessionHistory(); assert.equal(merged.length, 1);
});

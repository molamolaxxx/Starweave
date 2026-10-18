const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname, '../../main/resources/configui/assets/js/core.js'), 'utf8');
const code = source.slice(source.indexOf('async function loadChannelBindingTargets(){'), source.indexOf('\nfunction render(){'));
function harness() {
    const requests = [], notices = [];
    const context = vm.createContext({
        channelBindingTargetRequest: 0, channelBindingTargetPending: null, channelBindingTargetError: '',
        channelBindingTargets: {instanceId: 'A', sessions: [{groupId: 'existing'}], teams: []},
        curInstance: {instanceId: 'A'}, environmentGate: {version: 0, switching: false, loading: false}, activePage: 'channels',
        api: () => new Promise((resolve, reject) => requests.push({resolve, reject})),
        showSnackbar: message => notices.push(message), renderChannels() {}
    });
    vm.runInContext(code, context);
    return {context, requests, notices};
}
const success = id => ({ok: true, status: 200, json: async () => ({sessions: [{groupId: id}], teams: []})});

test('navigation and background refresh share one successful query without a false failure', async () => {
    const {context, requests, notices} = harness();
    const navigation = context.refreshChannelBindingTargets(true);
    const poll = context.refreshChannelBindingTargets(false);
    assert.equal(requests.length, 1);
    requests[0].resolve(success('updated'));
    assert.deepEqual(await Promise.all([navigation, poll]), [true, true]);
    assert.deepEqual(notices, []);
    assert.equal(context.channelBindingTargets.sessions[0].groupId, 'updated');
    assert.equal(context.channelBindingTargetPending, null);
});

test('old environment success is ignored and cannot clear the new environment query', async () => {
    const {context, requests, notices} = harness();
    const old = context.refreshChannelBindingTargets(true);
    context.curInstance = {instanceId: 'B'}; context.environmentGate.version++;
    const current = context.refreshChannelBindingTargets(true);
    requests[0].resolve(success('old'));
    assert.equal(await old, null);
    assert.equal(context.channelBindingTargets.sessions[0].groupId, 'existing');
    const joined = context.refreshChannelBindingTargets(false);
    assert.equal(requests.length, 2);
    requests[1].resolve(success('new'));
    assert.deepEqual(await Promise.all([current, joined]), [true, true]);
    assert.equal(context.channelBindingTargets.sessions[0].groupId, 'new');
    assert.deepEqual(notices, []);
});

test('old failure stays silent even after returning to the same environment', async () => {
    const {context, requests, notices} = harness();
    const old = context.refreshChannelBindingTargets(true);
    context.environmentGate.version += 2;
    const current = context.refreshChannelBindingTargets(true);
    requests[1].resolve(success('new'));
    assert.equal(await current, true);
    requests[0].reject(new Error('old network failure'));
    assert.equal(await old, null);
    assert.deepEqual(notices, []);
    assert.equal(context.channelBindingTargets.sessions[0].groupId, 'new');
    assert.equal(context.channelBindingTargetError, '');
});

test('switch cancellation and leaving the page do not show stale notifications', async () => {
    const {context, requests, notices} = harness();
    const switching = context.refreshChannelBindingTargets(true);
    context.environmentGate.switching = true;
    requests[0].reject(Object.assign(new Error('cancelled'), {name: 'AbortError'}));
    assert.equal(await switching, null);
    assert.deepEqual(notices, []);
    context.environmentGate.switching = false;
    const leaving = context.refreshChannelBindingTargets(true);
    context.activePage = 'acp';
    requests[1].reject(new Error('offline'));
    assert.equal(await leaving, false);
    assert.deepEqual(notices, []);
});

test('real HTTP failure keeps existing targets, explains the failure and permits retry', async () => {
    const {context, requests, notices} = harness();
    const first = context.refreshChannelBindingTargets(true);
    const poll = context.refreshChannelBindingTargets(false);
    requests[0].resolve({ok: false, status: 401, json: async () => ({error: '请先输入此环境的密码'})});
    assert.deepEqual(await Promise.all([first, poll]), [false, false]);
    assert.equal(notices.length, 1);
    assert.match(notices[0], /HTTP 401.*请先输入此环境的密码/);
    assert.equal(context.channelBindingTargets.sessions[0].groupId, 'existing');
    assert.equal(context.environmentGate.recoveryRequired, undefined);
    const retry = context.refreshChannelBindingTargets(true);
    requests[1].resolve(success('recovered'));
    assert.equal(await retry, true);
    assert.equal(context.channelBindingTargets.sessions[0].groupId, 'recovered');
    assert.equal(context.channelBindingTargetError, '');
});

test('silent polling failures stay silent and malformed data does not replace targets', async () => {
    const {context, requests, notices} = harness();
    const poll = context.refreshChannelBindingTargets(false);
    requests[0].reject(new Error('network down'));
    assert.equal(await poll, false); assert.deepEqual(notices, []);
    const malformed = context.refreshChannelBindingTargets(true);
    requests[1].resolve({ok: true, json: async () => null});
    assert.equal(await malformed, false);
    assert.equal(context.channelBindingTargets.sessions[0].groupId, 'existing');
    assert.match(notices[0], /绑定目标数据无效/);
});

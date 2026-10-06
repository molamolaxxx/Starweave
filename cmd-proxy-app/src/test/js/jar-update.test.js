const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');

const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/resources/configui/assets/js/app.js'), 'utf8');
new Function(source);

function fixture() {
    const label = {textContent: ''};
    const button = {style: {}, querySelector: () => label};
    const elements = {updateBtn: button, updateBar: {style: {}}, updatePct: {}, updateMsg: {}};
    let status;
    let poll;
    const context = vm.createContext({
        document: {getElementById: id => elements[id]},
        api: async () => ({json: async () => status}),
        showSnackbar() {}, closeDialog() {},
        setInterval: callback => {poll = callback; return 1;},
        clearInterval() {}, setTimeout: callback => callback()
    });
    vm.runInContext(source.slice(source.indexOf('var lastUpdateStatus=')), context);
    return {context, button, label, setStatus: value => {status = value;},
        poll: () => poll()};
}

test('pending Windows update permits checking again but blocks concurrent work', async () => {
    const view = fixture();
    for (const status of ['done', 'latest', 'error']) {
        view.setStatus({status, restartRequired: true});
        await view.context.syncUpdateButtonStatus();
        assert.equal(view.button.disabled, false);
        assert.equal(view.label.textContent, '检查更新（重启后生效）');
    }
    for (const status of ['checking', 'downloading']) {
        view.setStatus({status, restartRequired: true});
        await view.context.syncUpdateButtonStatus();
        assert.equal(view.button.disabled, true);
    }
});

test('completed manual check leaves the pending update button enabled', async () => {
    const view = fixture();
    for (const status of ['done', 'latest']) {
        view.setStatus({status, restartRequired: true, progress: 100});
        view.context.pollUpdateStatus();
        await view.poll();
        assert.equal(view.button.disabled, false);
        assert.equal(view.label.textContent, '检查更新（重启后生效）');
    }
});

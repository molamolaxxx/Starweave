const test = require('node:test');
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const root = path.resolve(__dirname, '../../main/resources/configui');
const source = fs.readFileSync(path.join(root, 'assets/js/agents.js'), 'utf8');
const save = source.slice(source.indexOf('async function saveConfig('), source.indexOf('async function refreshService('));
function fixture(value) {
    let saved;
    const context = vm.createContext({
        config: {configUi: {enabled: true, port: 10528, extra: 'preserved'}},
        document: {getElementById: id => ({cfgHttpThreadPoolSize: {value}, cfgEnabled: {checked: true}, cfgPort: {value: '10528'}}[id])},
        api: async (url, options) => {saved = JSON.parse(options.body); return {json: async () => ({ok: true})};},
        clearDirty() {}, showSnackbar() {}, currentPort: () => 10528
    });
    vm.runInContext(save, context);
    return {context, saved: () => saved};
}
test('saves an adjustable HTTP thread count without dropping other ConfigUI options', async () => {
    const f = fixture('128');
    assert.equal(await f.context.saveConfig(true), true);
    assert.deepEqual(f.saved().configUi, {enabled: true, port: 10528, extra: 'preserved', httpThreadPoolSize: 128});
});
test('rejects invalid input before sending a configuration mutation', async () => {
    for (const value of ['', '0', '-1', '1.5', '1025', 'abc']) {
        const f = fixture(value);
        assert.equal(await f.context.saveConfig(true), false, value);
        assert.equal(f.saved(), undefined);
    }
});

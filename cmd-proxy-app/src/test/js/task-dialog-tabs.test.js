const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const source = fs.readFileSync(path.resolve(__dirname,
    '../../main/resources/configui/assets/js/tasks.js'), 'utf8')

function fixture() {
    const elements = {}
    for (const id of ['taskDialogGrid', 'taskDetailsTab', 'taskActivityTab',
        'taskDetailsPane', 'taskActivityPane', 'taskContent', 'taskComment']) {
        elements[id] = {dataset: {}, attributes: {}, classes: new Set(), scrollTop: 80,
            value: '', setAttribute(key, value) { this.attributes[key] = value }}
        elements[id].classList = {toggle(name, enabled) {
            if (enabled) elements[id].classes.add(name)
            else elements[id].classes.delete(name)
        }}
    }
    const context = vm.createContext({document: {getElementById: id => elements[id]}})
    vm.runInContext(source.slice(source.indexOf('function setTaskDialogTab('),
        source.indexOf('async function openTaskCreate(')), context)
    return {context, elements}
}

test('switching task tabs keeps both drafts and each pane scroll position', () => {
    const {context, elements} = fixture()
    context.resetTaskDialogTabs('edit')
    elements.taskContent.value = '尚未保存的待办任务正文'
    elements.taskComment.value = '尚未发布的评论'
    elements.taskDetailsPane.scrollTop = 140
    elements.taskActivityPane.scrollTop = 250
    context.setTaskDialogTab('activity')
    assert.equal(elements.taskDialogGrid.dataset.activeTab, 'activity')
    assert.equal(elements.taskActivityTab.attributes['aria-pressed'], 'true')
    assert.equal(elements.taskDetailsTab.attributes['aria-pressed'], 'false')
    context.setTaskDialogTab('details')
    assert.equal(elements.taskDetailsTab.classes.has('active'), true)
    assert.equal(elements.taskActivityTab.classes.has('active'), false)
    assert.equal(elements.taskContent.value, '尚未保存的待办任务正文')
    assert.equal(elements.taskComment.value, '尚未发布的评论')
    assert.equal(elements.taskDetailsPane.scrollTop, 140)
    assert.equal(elements.taskActivityPane.scrollTop, 250)
    context.setTaskDialogTab('invalid')
    assert.equal(elements.taskDialogGrid.dataset.activeTab, 'details')
})

test('opening create, edit and view always starts at the correctly labelled first tab', () => {
    const {context, elements} = fixture()
    for (const [mode, title, side] of [['create', '发布待办任务', '执行配置'], ['assign', '分配待办任务', '执行配置'],
        ['edit', '编辑待办任务', '评论记录'], ['view', '查看待办任务', '评论记录']]) {
        context.setTaskDialogTab('activity')
        context.resetTaskDialogTabs(mode)
        assert.equal(elements.taskDialogGrid.dataset.activeTab, 'details')
        assert.equal(elements.taskDetailsTab.textContent, title)
        assert.equal(elements.taskActivityTab.textContent, side)
        assert.equal(elements.taskDetailsPane.scrollTop, 0)
        assert.equal(elements.taskActivityPane.scrollTop, 0)
    }
    assert.match(source, /resetTaskDialogTabs\(mode\);setTaskEditorMode/)
})

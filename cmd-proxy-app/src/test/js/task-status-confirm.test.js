const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const html = fs.readFileSync(path.resolve(__dirname,
    '../../main/resources/configui/index.html'), 'utf8')

function section(start, end) {
    const from = html.indexOf(start)
    const to = html.indexOf(end, from)
    assert.notEqual(from, -1)
    assert.notEqual(to, -1)
    return html.slice(from, to)
}

function fixture(confirm) {
    const confirmations = []
    const requests = []
    const messages = []
    const context = vm.createContext({
        taskState: {items: [{id: 'task-1', name: '接口联调', status: 'START',
            revision: 3, contentVersion: 2}]},
        showConfirm: async (message, options) => {
            confirmations.push({message, options})
            return confirm
        },
        taskApi: async (url, options) => {
            requests.push({url, options})
            return {task: {id: 'task-1', status: 'IN_PROGRESS'}}
        },
        taskRequestId: () => 'request-1',
        updateTaskRow: () => {},
        showSnackbar: message => messages.push(message),
        encodeURIComponent,
        JSON
    })
    const assets = path.resolve(__dirname, '../../main/resources/configui/assets/js')
    const agents = fs.readFileSync(path.join(assets, 'agents.js'), 'utf8')
    const tasks = fs.readFileSync(path.join(assets, 'tasks.js'), 'utf8')
    vm.runInContext(agents.slice(agents.indexOf('function taskStatusLabel('), agents.indexOf('\n', agents.indexOf('function taskStatusLabel('))) + '\n' +
        tasks.slice(tasks.indexOf('async function updateTaskStatus('), tasks.indexOf('async function submitTaskComment(')), context)
    context.taskApi = async (url, options) => {
        requests.push({url, options})
        return {task: {id: 'task-1', status: 'IN_PROGRESS'}}
    }
    context.updateTaskRow = () => {}
    return {context, confirmations, requests, messages}
}

test('cancelling status confirmation does not send the update', async () => {
    const {context, confirmations, requests} = fixture(false)
    await context.updateTaskStatus('IN_PROGRESS', 'task-1')

    assert.equal(requests.length, 0)
    assert.equal(confirmations.length, 1)
    assert.match(confirmations[0].message, /接口联调/)
    assert.match(confirmations[0].message, /“未开始”修改为“进行中”/)
    assert.equal(confirmations[0].options.title, '确认修改待办任务状态？')
    assert.equal(confirmations[0].options.confirmText, '修改为进行中')
})

test('confirmed status change sends the update and marks cancellation as dangerous', async () => {
    const {context, confirmations, requests} = fixture(true)
    await context.updateTaskStatus('CANCELLED', 'task-1')

    assert.equal(confirmations[0].options.danger, true)
    assert.equal(requests.length, 1)
    assert.equal(requests[0].url, '/task-1/status')
    assert.equal(JSON.parse(requests[0].options.body).status, 'CANCELLED')
})

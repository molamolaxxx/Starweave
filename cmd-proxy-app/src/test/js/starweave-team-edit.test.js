const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')

const resourceRoot = path.resolve(__dirname, '../../main/resources/configui')
const markup = fs.readFileSync(path.join(resourceRoot, 'index.html'), 'utf8')
const html = markup + [...markup.matchAll(/(?:src|href)="\/(assets\/(?:js|css)\/[^\"]+)"/g)]
    .map(match => fs.readFileSync(path.join(resourceRoot, match[1]), 'utf8')).join('\n')

function section(start, end) {
    const from = html.indexOf(start)
    const to = html.indexOf(end, from)
    assert.notEqual(from, -1)
    assert.notEqual(to, -1)
    return html.slice(from, to)
}

function fixture(state = 'READY') {
    const boxes = Object.fromEntries([
        'starTeamList', 'starTeamEditName', 'starTeamEditMode',
        'starTeamEditSources', 'starTeamEditMembers', 'starTeamEditSaveButton'
    ].map(id => [id, {innerHTML: '', value: '', disabled: false}]))
    const messages = []
    const dialogs = []
    const team = {teamId: 'team-1', name: 'Team', version: 1,
        members: [{teamMemberId: 'member-1', sourceGroupId: 'group-1',
            sourceRobotId: 'robot-1', displayName: 'Original', state}]}
    const context = vm.createContext({
        document: {getElementById: id => boxes[id]},
        starTeams: {items: [team]}, teamBatchOperations: Object.create(null),
        starTeamEditDraft: null, esc: value => String(value || ''),
        normalized: value => String(value || '').toLowerCase(),
        isAgentOperable: value => value === 'READY' || value === 'SLEEP',
        starTeamSourceKey: source => [source.cmdProxyInstanceId || '',
            source.sourceGroupId || '', source.sourceRobotId || ''].join('|'),
        starRequestId: () => 'request-123456789',
        showSnackbar: message => messages.push(message),
        showDialog: id => dialogs.push('open:' + id),
        closeDialog: id => dialogs.push('close:' + id),
        showConfirm: async () => true,
        loadStarweaveTeams: async () => {},
        refreshChannelBindingTargets: async () => {}
    })
    vm.runInContext(section('function renderStarweaveTeams()',
        'async function postTeamMemberAction'), context)
    vm.runInContext(section('function starTeamSourceKey(source)',
        'async function deleteStarweaveTeam'), context)
    return {context, boxes, messages, dialogs, team}
}

test('edit dialog offers every local source even when coordinator marks discovery sources coordinated', async () => {
    const {context, boxes} = fixture()
    context.api = async () => ({ok: true, json: async () => ({accepted: true,
        data: {sources: [{sourceGroupId: 'group-1', coordinated: true}],
            localSources: [
                {sourceGroupId: 'group-1', sourceRobotId: 'robot-1',
                    displayName: 'Original', sourceLabel: '本环境 · Starweave'},
                {sourceGroupId: 'group-2', sourceRobotId: 'robot-2', displayName: 'Additional'}
            ]}})})

    await context.openStarweaveTeamEditDialog('team-1')
    assert.match(boxes.starTeamEditSources.innerHTML, /Additional/)
    assert.match(boxes.starTeamEditSources.innerHTML,
        /team-member-copy"><span class="team-member-name">Original<\/span><span class="team-member-badges"><span class="team-member-role">本环境<\/span>/)
    context.toggleStarTeamEditMember({value: '|group-2|robot-2', checked: true})
    assert.match(boxes.starTeamEditMembers.innerHTML, /Additional/)
    let submitted
    context.api = async (url, options) => {
        submitted = JSON.parse(options.body)
        return {ok: true, json: async () => ({accepted: true,
            data: {accepted: true, data: {sessionFailures: {}}}})}
    }
    await context.saveStarweaveTeamEdit()
    assert.deepEqual(submitted.members.map(member => member.sourceGroupId),
        ['group-1', 'group-2'])
})

test('non READY member prevents opening edit and gives a reason on the disabled looking control', async () => {
    const {context, boxes, messages, dialogs} = fixture('BUSY')
    context.renderStarweaveTeams()
    assert.match(boxes.starTeamList.innerHTML, /team-edit-unavailable/)
    assert.match(boxes.starTeamList.innerHTML, /aria-disabled="true"/)
    await context.openStarweaveTeamEditDialog('team-1')
    assert.deepEqual(dialogs, [])
    assert.match(messages[0], /READY/)
})

test('batch additions keep distinct member IDs without crypto, even with identical time and random values', async () => {
    const {context, team} = fixture()
    // Exercise the real HTTP fallback and force repeated request IDs to verify deduplication.
    context.Date = {now: () => 1793183446012}
    context.Math = {random: () => 0.5}
    vm.runInContext(section('function starRequestId()',
        'function starSessionSelectionKey()'), context)
    const base = 'member-' + context.starRequestId()
    team.members[0].teamMemberId = base
    const sources = [2, 3].map(i => ({sourceGroupId: 'group-' + i,
        sourceRobotId: 'robot-' + i}))
    const selected = {[base]: true}
    sources.forEach(source => { selected[context.starTeamSourceKey(source)] = true })
    context.starTeamEditDraft = {teamId: 'team-1', version: 1,
        members: team.members, sources, selected, remarks: {},
        captainId: '', newIds: Object.create(null)}
    let submitted
    context.api = async (url, options) => {
        submitted = JSON.parse(options.body)
        return {ok: true, json: async () => ({accepted: true,
            data: {accepted: true, data: {sessionFailures: {}}}})}
    }
    await context.saveStarweaveTeamEdit()
    const firstIds = submitted.members.map(member => member.teamMemberId)
    assert.equal(firstIds.length, 3)
    assert.equal(new Set(firstIds).size, 3)
    assert.equal(firstIds[0], base)
    assert.ok(firstIds.every(id => /^[A-Za-z0-9_-]+$/.test(id)))
    assert.deepEqual(submitted.members.map(member => member.sourceGroupId),
        ['group-1', 'group-2', 'group-3'])
    await context.saveStarweaveTeamEdit()
    assert.deepEqual(submitted.members.map(member => member.teamMemberId), firstIds)
})

test('confirmed update closes edit immediately and shows progress until the request settles', async () => {
    const {context, boxes, messages, dialogs} = fixture()
    context.starTeamEditDraft = {teamId: 'team-1', version: 1, coordinated: false,
        members: context.starTeams.items[0].members, sources: [],
        selected: {'member-1': true}, remarks: {'member-1': ''},
        captainId: '', newIds: Object.create(null)}
    let complete
    context.api = () => new Promise(resolve => { complete = resolve })

    const saving = context.saveStarweaveTeamEdit()
    await new Promise(setImmediate)
    assert.ok(dialogs.includes('close:starTeamEditDialog'))
    assert.match(messages[0], /正在重建团队/)
    assert.match(boxes.starTeamList.innerHTML, /正在重建团队/)
    complete({ok: true, json: async () => ({accepted: true,
        data: {accepted: true, data: {sessionFailures: {}}}})})
    await saving
    assert.doesNotMatch(boxes.starTeamList.innerHTML, /正在重建团队/)
    assert.match(messages.at(-1), /所有成员的新会话已创建/)
})

test('captain mode keeps create and edit buttons clickable with only the captain selected', () => {
    const {context, boxes} = fixture()
    boxes.starTeamCreateButton = {disabled: true}
    context.document.querySelectorAll = () => [{}]
    context.starTeamDraft = {mode: 'CAPTAIN', captainKey: 'captain'}
    context.updateStarTeamCreateState()
    assert.equal(boxes.starTeamCreateButton.disabled, false)

    context.starTeamEditDraft = {
        members: [{teamMemberId: 'captain', sourceGroupId: 'group-1',
            sourceRobotId: 'robot-1', displayName: 'Captain'}],
        sources: [], selected: {captain: true}, remarks: {captain: ''},
        captainId: 'captain'
    }
    boxes.starTeamEditSaveButton.disabled = true
    context.renderStarweaveTeamEditMembers()
    assert.equal(boxes.starTeamEditSaveButton.disabled, false)
    assert.match(html, /队长模式至少需要两名成员，并手动指定一名队长/)
    assert.match(html, /请保留队长，并至少选择两名成员/)
})

test('create and edit dialogs combine basic information and members with wrapping member chips', () => {
    const createDialog = section('<div class="dialog-overlay" id="starTeamDialog"',
        '<div class="dialog-overlay" id="starTeamEditDialog"')
    const editDialog = section('<div class="dialog-overlay" id="starTeamEditDialog"',
        '<div class="dialog-overlay" id="teamSessionDialog"')
    for (const dialog of [createDialog, editDialog]) {
        const basicPanel = dialog.slice(dialog.indexOf('data-star-team-panel="basic"'),
            dialog.indexOf('data-star-team-panel="settings"'))
        assert.match(dialog, /class="dialog star-team-dialog"/)
        assert.match(dialog, /data-star-team-tab="basic"/)
        assert.match(dialog, /基本信息与团队成员/)
        assert.match(dialog, /data-star-team-tab="settings"/)
        assert.doesNotMatch(dialog, /data-star-team-tab="members"/)
        assert.equal((dialog.match(/data-star-team-panel=/g) || []).length, 2)
        assert.match(basicPanel, /class="star-team-member-section"/)
        assert.match(basicPanel, /class="star-team-member-picker"/)
    }
    assert.match(html, /\.chip-list\{display:flex;flex-wrap:wrap;gap:8px\}/)
    assert.match(html, /\.team-member-chip \.team-member-copy\{display:flex;flex-direction:column/)
    assert.match(html, /\.star-team-member-picker \.team-member-chip\{flex:1 1 max-content;min-width:min\(150px,100%\);max-width:100%/)
    assert.equal((html.match(/class="team-member-copy"/g) || []).length, 2)
    assert.doesNotMatch(html, /\.star-team-member-picker \.chip-list\{display:grid/)
    assert.match(html, /\.star-team-selected-members\{display:grid;grid-template-columns:minmax\(0,1fr\)/)
    assert.doesNotMatch(html, /\.star-team-selected-members\{[^}]*overflow:auto/)
    assert.doesNotMatch(editDialog, /团队名称和模式创建后保持不变/)
    assert.doesNotMatch(editDialog, /选择 1–10 个智能体加入团队；队长模式不可移除当前队长/)
})

test('local picker labels are shortened without changing remote labels', () => {
    const {context} = fixture()
    assert.equal(context.starTeamPickerSourceLabel({sourceLabel: '本环境 · Starweave'}), '本环境')
    assert.equal(context.starTeamPickerSourceLabel({sourceLabel: '其他环境 · Agent'}), '其他环境 · Agent')
})

package com.mola.cmd.proxy.app.acp.configui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ConfigUiLayoutContractTest {
    @Test
    public void exposesAutoSleepAndTreatsSleepAsOperable() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("自动睡眠"));
        assertTrue(html.contains("id=\"dAutoSleep\""));
        assertTrue(html.contains("id=\"dAutoSleepIdle\""));
        assertTrue(html.contains("autoSleep:{enabled:false,idleMinutes:30}"));
        assertTrue(html.contains("function isAgentOperable(state)"));
        assertTrue(html.contains("state==='READY'||state==='SLEEP'"));
        assertTrue(html.contains(".session-status.sleep"));
        assertTrue(html.contains("LIFECYCLE_EVENT:['','wb_sunny','智能体已唤醒']"));
    }

    @Test
    public void exposesSearchablePaginatedChannelMessageArchiveDialog() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("id=\"channelMessagesDialog\""));
        assertTrue(html.contains("title=\"查看消息记录\""));
        assertTrue(html.contains("function openChannelMessages("));
        assertTrue(html.contains("function loadChannelMessages("));
        assertTrue(html.contains("/api/channels/v1/messages?archiveId="));
        assertTrue(html.contains("id=\"channelMessageKeyword\""));
        assertTrue(html.contains("id=\"channelMessageSenderId\""));
        assertTrue(html.contains("id=\"channelMessageChatId\""));
        assertTrue(html.contains("id=\"channelMessageContent\""));
        assertTrue(html.contains("id=\"channelMessageQuote\""));
        assertTrue(html.contains("id=\"channelMessageAttachment\""));
        assertTrue(html.contains("request:0,timer:0"));
        assertTrue(html.contains("token!==channelMessages.request"));
        assertTrue(html.contains("channel-message-results"));
        assertTrue(html.contains("overscroll-behavior:contain"));
    }
    @Test
    public void separatesConfigurationIntoSearchablePaginatedPages() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("data-page=\"basic\""));
        assertTrue(html.contains("data-page=\"channels\""));
        assertTrue(html.contains("data-page=\"acp\""));
        assertTrue(html.contains("id=\"page-basic\""));
        assertTrue(html.contains("id=\"page-channels\""));
        assertTrue(html.contains("id=\"page-acp\""));

        assertTrue(html.contains("id=\"channelSearch\""));
        assertTrue(html.contains("id=\"robotSearch\""));
        assertTrue(html.contains("id=\"channelSearch\" type=\"text\""));
        assertTrue(html.contains("id=\"robotSearch\" type=\"text\""));
        assertTrue(html.contains("function channelSearchText"));
        assertTrue(html.contains("function robotSearchText"));
        assertTrue(html.contains("function relevanceScore"));
        assertTrue(html.contains("function matchGrade"));
        assertTrue(html.contains("function pathBaseName"));
        assertTrue(html.contains("{value:pathBaseName(r.workDir),weight:110}"));
        assertTrue(html.contains("{value:contacts,weight:2}"));
        assertTrue(html.contains("function filteredChannels"));
        assertTrue(html.contains("function filteredRobots"));
        assertTrue(html.contains("sort(function(a,b){return b.score-a.score||a.index-b.index})"));

        assertTrue(html.contains("id=\"channelPageSize\""));
        assertTrue(html.contains("id=\"robotPageSize\""));
        assertTrue(html.contains("function pageEntries"));
        assertTrue(html.contains("function renderPagination"));
        assertTrue(html.contains("entry.index"));
    }

    @Test
    public void channelSaveAutomaticallyAppliesOnlyTheSavedChannel() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("onclick=\"saveConfig(false)\""));
        assertTrue(html.contains("保存并应用到此渠道"));
        assertTrue(html.contains("previousChannelId:previousId,channelId:channelId"));
        assertTrue(html.contains("/api/refresh-channel"));
        assertTrue(html.contains("async function saveChannelDialog()"));
        assertFalse(html.contains("await applyChannelConfig(insertIndex)"));
        assertTrue(html.contains("消息渠道已创建并保存"));
        assertTrue(html.contains("applyChannelConfig(insertIndex).then(function()"));
        assertTrue(html.contains("/api/item-refresh-status"));
        assertTrue(html.contains("itemOperationPending('channels'"));
        assertTrue(html.contains("async function toggleChannelEnabled(i,enabled)"));
        assertTrue(html.contains("await applyChannelConfig(i)"));
        assertTrue(html.contains("onchange=\"toggleChannelEnabled('+i+',this.checked)"));
        assertTrue(html.contains("hourglass_top':'refresh'"));
    }

    @Test
    public void automaticallyPersistsRobotDialogsAndCarriesRuntimeIdentityOnRefresh()
            throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("async function saveRobot()"));
        assertFalse(html.contains("await applyRobotConfig(insertIndex)"));
        assertTrue(html.contains("智能体已添加并保存"));
        assertTrue(html.contains("applyRobotConfig(insertIndex).then(function()"));
        assertTrue(html.contains("itemOperationPending('robots'"));
        assertTrue(html.contains("itemRefreshMessage('robots'"));
        assertTrue(html.contains("async function toggleRobot(i,enabled)"));
        assertTrue(html.contains("await applyRobotConfig(i)"));
        assertTrue(html.contains("onchange=\"toggleRobot('+i+',this.checked)"));
        assertTrue(html.contains("Object.defineProperty(robot,'_runtimeName'"));
        assertTrue(html.contains("previousName:previousName,name:name"));
        assertTrue(html.contains("onclick=\"refreshRobot('+i+')\""));
        assertTrue(html.contains("async function deleteRobot(i)"));
        assertTrue(html.contains("智能体已删除并保存"));
        assertTrue(html.contains("if(!ok){config.robots.splice(i,0,robot)"));
    }

    @Test
    public void presentsTheStarweaveProductLanguageWithoutHidingProtocolDetails() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("<title>Starweave</title>"));
        assertTrue(html.contains("<h1>Starweave</h1>"));
        assertTrue(html.contains("让每个智能体，与世界相连"));
        assertTrue(html.contains(">系统设置</button>"));
        assertTrue(html.contains(">消息渠道<span"));
        assertTrue(html.contains(">智能体<span"));
        assertTrue(html.contains(">工具权限<span"));
        assertTrue(html.contains("运行引擎"));
        assertTrue(html.contains("Claude Agent · ACP"));
        assertTrue(html.contains("Codex · ACP"));
    }

    @Test
    public void loadsMaterialIconsFromTheBundledStaticAsset() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("url('/assets/MaterialIcons-Regular.woff2')"));
        assertTrue(html.contains("font-family:'Material Icons'"));
        assertFalse(html.contains("fonts.googleapis.com/icon?family=Material+Icons"));
    }

    @Test
    public void exposesSurfaceLocalSessionNavigationAndLifecycleControls()
            throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("data-page=\"sessions\""));
        assertTrue(html.contains("id=\"page-sessions\""));
        assertTrue(html.contains("canOpen?'开启会话':'点击查看为何无法开启会话'"));
        assertTrue(html.contains("robot.onlyTeamMember"));
        assertTrue(html.contains("请从 Fast Team 的团队会话中操作"));
        assertTrue(html.contains("会话已开启"));
        assertTrue(html.contains("/api/starweave/v1/sessions/"));
        assertTrue(html.contains("function restoreStarweaveSession()"));
        assertTrue(html.contains("id=\"starRestoreDialog\""));
        assertTrue(html.contains("id=\"starRestoreList\""));
        assertTrue(html.contains("function openStarweaveRestoreDialog()"));
        assertTrue(html.contains("onclick=\"openStarweaveRestoreDialog()\""));
        assertTrue(!html.contains("id=\"starRestoreSelect\""));
        assertTrue(html.contains("aria-disabled=\"'+(!busy)+'\""));
        assertTrue(html.contains("aria-disabled=\"'+busy+'\""));
        assertFalse(html.contains("onclick=\"cancelStarweaveSession()\" disabled"));
        assertTrue(html.contains("'[MolaChat] '"));
        assertTrue(html.contains("'[Starweave] '"));
        assertTrue(html.contains("channelBindingTargets.sessions"));
        assertTrue(html.contains("if(page==='channels')refreshChannelBindingTargets(true)"));
        assertTrue(html.contains("async function refreshChannelBindingTargets(notify)"));
        assertTrue(html.contains("if(!environmentGate.switching&&activePage==='channels')refreshChannelBindingTargets(false)"));
        assertTrue(html.contains("request!==channelBindingTargetRequest"));
        assertTrue(html.contains("channelBindingTargets={instanceId:id,sessions:[],teams:[]}"));
        assertTrue(html.contains("await loadStarweaveTeams(false);await refreshChannelBindingTargets(false)"));
        assertTrue(html.contains("if(b.groupId&&!selectedGroup)groupOpts+="));
        assertTrue(html.contains("当前智能体（不可用）"));
        assertTrue(html.contains("channelBindingTargetFingerprint(channelBindingTargets)"));
        assertTrue(html.contains("/api/starweave/v1/sessions/stream"));
        assertTrue(html.contains("new EventSource(url)"));
        assertTrue(html.contains("id=\"starFileInput\""));
        assertTrue(html.contains("function uploadStarweaveFiles("));
        assertTrue(html.contains("onpaste=\"handleStarFilePaste(event)\""));
        assertTrue(html.contains("function handleStarFilePaste(event)"));
        assertTrue(html.contains("uploadStarweaveFiles(files)"));
        int filesCopied = html.indexOf("files=Array.prototype.slice.call(fileList||[])");
        int inputCleared = html.indexOf("if(input)input.value=''", filesCopied);
        assertTrue(filesCopied >= 0 && inputCleared > filesCopied);
        assertTrue(html.contains("未选择任何文件"));
        assertTrue(html.contains("function requestStarweaveAttachments("));
        assertFalse(html.contains("id=\"starAttachBtn\" onclick=\"document.getElementById('starFileInput').click()\" disabled"));
        assertTrue(html.contains("Starweave Chatter ID"));
        assertTrue(html.contains("MolaChat Chatter ID"));
        assertTrue(html.contains("本地 talkTo 不允许跨 Chatter ID"));
        assertTrue(html.contains("function previewStarweaveResource("));
        assertTrue(html.contains("function reduceStarweaveEvents("));
        assertTrue(html.contains("function renderMarkdown("));
        assertTrue(html.contains("<div class=\"markdown\">'+renderMarkdown(item.text)"));
        assertTrue(html.contains("body.sessions-active{overflow:hidden"));
        assertTrue(html.contains("function syncStarweaveViewport()"));
        assertTrue(html.contains("id=\"starSessionOperation\""));
        assertTrue(html.contains("正在开启「'+robot.name+'」的会话"));
        assertTrue(html.contains("正在新建会话"));
        assertTrue(html.contains("正在恢复历史会话"));
        assertTrue(html.contains("当前无历史会话"));
        assertTrue(html.contains("当前无法取消会话"));
        assertTrue(html.contains("当前无法新建会话"));
        assertTrue(html.contains("当前无法删除会话"));
        assertTrue(html.contains("function startStarweaveSession()"));
        assertTrue(html.contains("class=\"btn btn-secondary btn-sm btn-start\""));
        assertTrue(html.contains("<span class=\"material-icons\">play_arrow</span>"));
        assertTrue(html.contains("function handleStarMessageScroll()"));
        assertTrue(html.contains("scrollChatViewport(box,starSessions,loadOlderStarweaveHistory)"));
        assertTrue(html.contains("function chatRestoreViewport("));
        assertTrue(html.contains("var starEventRenderFrame=0"));
        assertTrue(html.contains("function scheduleStarweaveEventsRender()"));
        assertTrue(html.contains("requestAnimationFrame(function(){starEventRenderFrame=0;renderStarweaveEvents()})"));
        assertTrue(html.contains("if(appendStarweaveEvent(event,true))scheduleStarweaveEventsRender()"));
        assertTrue(html.contains("function renderStarweaveSessionDetail(renderMessages)"));
        assertTrue(html.contains("if(renderMessages!==false)renderStarweaveEvents()"));
        assertTrue(html.contains("renderStarweaveSessionDetail(currentKey!==previousKey)"));
        assertFalse(html.contains("if(appendStarweaveEvent(event,true))renderStarweaveEvents()"));
        assertTrue(html.contains("function beginStarweaveTransition("));
        assertTrue(html.contains("version!==starSessions.transitionId||starSessions.transitioning"));
        assertTrue(html.contains("附件已就绪，将随下一条消息发送"));
        assertFalse(html.contains("消息与 '+readyUploads.length+' 个附件已发送"));
        assertFalse(html.contains(":'消息已发送'"));
        assertTrue(html.contains("assistantByTurn[turn]=null"));
        assertTrue(html.contains("item.kind!=='assistant'||String(item.text||'').trim().length>0"));
        assertTrue(html.contains("class=\"message-row tool-row\""));
        assertTrue(html.contains("s.active!==false&&normalized(s.state)!=='deleted'"));
        assertFalse(html.contains("(s.active?'当前会话':'已删除')"));
        assertTrue(html.contains(".tool-call-title{flex:1 1 auto;min-width:0;overflow-wrap:anywhere"));
        assertTrue(html.contains(".event-card-head .session-status{flex:0 0 auto;margin-left:auto}"));
        assertTrue(countOccurrences(html, "<span class=\"tool-call-title\">'") == 4);
        assertTrue(html.contains("session-sidebar.open"));
        assertTrue(html.contains("data-page=\"teams\""));
        assertTrue(html.contains("/api/starweave/v1/teams/create"));
        assertTrue(html.contains("/api/starweave/v1/teams/update"));
        assertTrue(html.contains("/api/starweave/v1/teams/sources"));
        assertTrue(html.contains("data-star-team-tab=\"basic\""));
        assertTrue(html.contains("基本信息与团队成员"));
        assertFalse(html.contains("data-star-team-tab=\"members\""));
        assertTrue(html.contains("data-star-team-tab=\"settings\""));
        assertTrue(html.contains("class=\"dialog star-team-dialog\""));
        assertTrue(html.contains("class=\"chip team-member-chip'+(selected?' selected':'')+'\""));
        assertTrue(html.contains(".team-member-chip{position:relative;display:inline-flex"));
        assertTrue(html.contains(".team-member-chip .star-team-member{position:absolute;width:1px;height:1px;opacity:0"));
        assertTrue(html.contains("source.onlyTeamMember?'<span class=\"team-member-role\">仅 Team</span>'"));
        assertTrue(html.contains("id=\"starTeamSelectedMembers\""));
        assertTrue(html.contains("class=\"star-team-basics\""));
        assertTrue(html.contains("class=\"star-team-member-section\""));
        assertTrue(html.contains(".star-team-selected-members{display:grid;grid-template-columns:minmax(0,1fr)"));
        assertTrue(html.contains(">成员设置</button>"));
        assertFalse(html.contains("备注只描述成员在本团队中的职责"));
        assertFalse(html.contains("可选择已生效或仅 Team 使用的智能体"));
        assertTrue(html.contains("function renderStarTeamSelectedMembers("));
        assertTrue(html.contains("function updateStarTeamMemberRemark("));
        assertTrue(html.contains("starTeamDraft={sources:[],remarks:Object.create(null),memberIds:Object.create(null),mode:'NORMAL',captainKey:''}"));
        assertTrue(html.contains("id=\"starTeamMode\""));
        assertTrue(html.contains("class=\"star-team-captain-choice\""));
        assertTrue(html.contains("class=\"star-team-captain-radio\""));
        assertTrue(html.contains("captainTeamMemberId:captainId"));
        assertTrue(html.contains("data-source-key=\"'+esc(key)+'\""));
        assertTrue(html.contains("function starTeamSourceKey("));
        assertTrue(html.contains("coordinated:!!source.coordinated"));
        assertTrue(html.contains("remark:starTeamDraft.remarks[input.value]||''"));
        assertTrue(html.contains("id=\"starTeamEditDialog\""));
        assertTrue(html.contains("editReason||'修改队伍'"));
        assertTrue(html.contains("team-edit-unavailable"));
        assertTrue(html.contains("aria-disabled=\"true\""));
        assertTrue(html.contains("function openStarweaveTeamEditDialog("));
        assertTrue(html.contains("function saveStarweaveTeamEdit("));
        assertTrue(html.contains("id=\"starTeamEditMemberCount\""));
        assertTrue(html.contains("队长 · 不可移除"));
        assertTrue(html.contains("保存并新建会话"));
        assertTrue(html.contains(".star-team-selected-members{display:grid;grid-template-columns:minmax(0,1fr)"));
        assertFalse(html.contains(".star-team-selected-members{display:grid;gap:8px;max-height:"));
        assertTrue(html.contains(".star-team-basics,.star-team-selected-members{grid-template-columns:1fr}"));
        assertFalse(html.contains("value=\"'+esc(source.remark"));
        assertFalse(html.contains("选择已开启的会话"));
        assertFalse(html.contains("var active=starSessions.items.filter(function(s){return s.active&&s.groupId})"));
        assertFalse(html.contains("团队动态"));
        assertFalse(html.contains("id=\"starTeamEvents\""));
        assertTrue(html.contains("<div class=\"filter-empty\"><span class=\"material-icons\">groups</span><p>暂无 Starweave 团队</p>"));
        assertFalse(html.contains("<strong>暂无 Starweave 团队</strong>"));
        assertTrue(html.contains("all.slice(0,3)"));
        assertTrue(html.contains("data-member-id=\"'+esc(member.teamMemberId)+'\""));
        assertTrue(html.contains("onclick=\"openTeamSessionPreview(this)\""));
        assertTrue(html.contains("class=\"team-card-actions\""));
        assertTrue(html.contains("class=\"team-card-identity\""));
        assertTrue(html.contains(".team-card-head{display:flex;align-items:center;gap:6px;flex-wrap:nowrap}"));
        assertTrue(html.contains(".team-card-identity{display:flex;align-items:center;gap:6px;min-width:0}"));
        assertTrue(html.contains(".team-card-identity>strong{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}"));
        assertTrue(html.contains(".team-card-head>.team-card-actions{flex:0 0 auto;margin-left:auto}"));
        assertTrue(html.contains(".team-card-actions{width:auto;justify-content:flex-end;margin:0 0 0 auto}"));
        assertTrue(html.contains("aria-label=\"团队会话\""));
        assertTrue(html.contains("runTeamBatchAction("));
        assertTrue(html.contains("action==='newSession'"));
        assertTrue(html.contains("!isAgentOperable(member.state)"));
        assertTrue(html.contains("postTeamMemberAction(team,member,action)"));
        assertFalse(html.contains("<span>version '+team.version+'</span>"));
        assertTrue(html.contains("id=\"teamSessionDialog\""));
        assertTrue(html.contains(".dialog.team-session-dialog{width:min(1440px,calc(100vw - 56px))"));
        assertTrue(html.contains("grid-template-columns:300px minmax(0,1fr)"));
        assertTrue(html.contains("function openTeamSessionDialog("));
        assertTrue(html.contains("function toggleTeamSessionSidebar("));
        assertTrue(html.contains("function loadTeamSessionSnapshot("));
        assertTrue(html.contains("function mergeTeamSessionMembers("));
        assertTrue(html.contains("function sendTeamSessionMessage("));
        assertTrue(html.contains("var ready=isAgentOperable(member.state),retry=member.state==='ERROR',busy=member.state==='BUSY',canSend=ready"));
        assertTrue(html.contains("队长模式：用户可选择任意成员继续其独立 ACP 会话"));
        assertFalse(html.contains("队长模式下普通队员会话仅供查看"));
        assertFalse(html.contains("队长模式下附件只能发送给队长"));
        assertFalse(html.contains("队长模式下新消息只能发送给队长"));
        assertFalse(html.contains("只读查看，业务消息由队长承接"));
        assertTrue(html.contains("if(!member.sessionId){showSnackbar('成员会话正在同步，请稍候');return}"));
        assertTrue(html.contains("function restoreTeamSession("));
        assertTrue(html.contains("function uploadTeamSessionFiles("));
        assertTrue(html.contains("onpaste=\"handleTeamSessionFilePaste(event)\""));
        assertTrue(html.contains("function handleTeamSessionFilePaste(event)"));
        assertTrue(html.contains("uploadTeamSessionFiles(files)"));
        assertTrue(html.contains("/api/starweave/v1/teams/uploads"));
        assertTrue(html.contains("id=\"teamSessionFileDialog\""));
        assertTrue(html.contains("function openTeamSessionResources("));
        assertTrue(html.contains("/api/starweave/v1/teams/resources"));
        assertTrue(html.contains("function handleTeamSessionScroll("));
        assertTrue(html.contains("scrollChatViewport(box,teamSession,loadOlderTeamSessionHistory)"));
        assertTrue(html.contains("/api/starweave/v1/teams/stream"));
        assertTrue(html.contains("function connectTeamSessionStream("));
        assertTrue(html.contains("function scheduleTeamSessionRender("));
        assertTrue(html.contains("requestAnimationFrame(function(){teamEventRenderFrame=0;renderTeamSessionMessages()})"));
        assertTrue(html.contains("function pollStarweaveTeamStates("));
        assertTrue(html.contains("activePage!=='teams'||starTeams.polling"));
        assertTrue(html.contains("var response=await api('/api/starweave/v1/teams')"));
        assertTrue(html.contains("teamSession.members=mergeTeamSessionMembers(teamSession.members,team.members||[])"));
        assertTrue(html.contains("if(old&&old.sessionId&&!member.sessionId)member.sessionId=old.sessionId"));
        assertTrue(html.contains("type==='MEMBER_SESSION_CHANGED'&&p.newSessionId"));
        assertFalse(html.contains("teamSessionPost('status')"));
        assertFalse(html.contains("function applyTeamMemberEvent("));
        assertFalse(html.contains("function applyTeamSessionStatus("));
        assertFalse(html.contains("pollStarweaveTeamEvents"));
        assertTrue(html.contains("renderTeamSessionDetail(false)"));
        assertTrue(html.contains("message.kind==='TEAM_TALK_TO'"));
        assertTrue(html.contains("function teamTalkToCardHtml("));
        assertTrue(html.contains("function teamMemberDisplayName("));
        assertTrue(html.contains("p.senderDisplayName:p.targetDisplayName"));
        assertTrue(html.contains("member.displayName||member.sourceRobotName"));
        assertTrue(html.contains("function teamPersistedEventHtml("));
        assertTrue(html.contains("message.kind==='TEAM_EVENT'"));
        assertTrue(html.contains("if(type==='TALK_TO_ROUTE_REQUEST')return false"));
        int teamDialogStart = html.indexOf("id=\"teamSessionDialog\"");
        int teamDialogEnd = html.indexOf("id=\"teamSessionRestoreDialog\"", teamDialogStart);
        assertTrue(teamDialogStart >= 0 && teamDialogEnd > teamDialogStart);
        assertFalse(html.substring(teamDialogStart, teamDialogEnd)
                .contains("deleteStarweaveSession"));
        assertFalse(html.contains("team-member-check"));
        assertTrue(html.contains(".team-member-chip.selected{background:var(--sw-primary)"));
        assertTrue(html.contains("onchange=\"toggleStarTeamMember(this)\""));
    }

    @Test
    public void importAndFilePreviewDialogsExposeActionableFeedback() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("id=\"agentImportFileGroup\""));
        assertTrue(html.contains("id=\"agentImportKindsGroup\""));
        assertTrue(html.contains("function validateAgentImportSelection(reveal)"));
        assertTrue(html.contains("state.validationShown=true"));
        assertTrue(html.contains("result.classList.add('error')"));
        assertTrue(html.contains("document.getElementById('agentImportSubmit').disabled=!state||state.busy"));
        assertTrue(html.contains("id=\"fileLinkDownloadButton\""));
        assertTrue(html.contains("function downloadFileLinkPreview()"));
        assertTrue(html.contains("#fileLinkPreviewDialog{z-index:360}"));
    }

    private static int countOccurrences(String source, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = source.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }

    @Test
    public void presentsLatestAsABackgroundMaintainedMovingVersion() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("自动跟随 latest"));
        assertTrue(html.contains("后台每 6 小时检查并准备 latest"));
        assertTrue(html.contains("下次启动智能体时生效"));
        assertTrue(html.contains("catalog.distTags&&catalog.distTags.latest"));
    }

    @Test
    public void rendersRegisteredMcpToolsFromTheDocumentedStringArrayContract() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("function mcpToolName(tool){return typeof tool==='string'?tool:"));
        assertTrue(html.contains("tools.map(mcpToolName).filter(Boolean).join('、')"));
    }

    @Test
    public void memoryCanSwitchBetweenModelAndAnyConfiguredRobot() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("id=\"dMemExecutionMode\""));
        assertTrue(html.contains("value=\"model\""));
        assertTrue(html.contains("value=\"robot\""));
        assertTrue(html.contains("id=\"dMemRobot\""));
        assertTrue(html.contains("function memoryRobotOptions(selected)"));
        assertTrue(html.contains("robots.forEach(function(robot)"));
        assertTrue(html.contains("robot.enabled===false?'（已禁用）':''"));
        assertTrue(html.contains("executionMode:memExecutionMode,robotName:memRobotName||undefined"));
    }

    @Test
    public void robotEditorUsesWideFocusedTabsWithCrossTabValidation() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("class=\"dialog robot-dialog\""));
        assertTrue(html.contains("class=\"robot-dialog-tabs\""));
        assertTrue(html.contains("class=\"dialog-body robot-dialog-content\""));
        assertTrue(html.contains("data-robot-tab=\"basic\""));
        assertTrue(html.contains("data-robot-tab=\"agent\""));
        assertTrue(html.contains("data-robot-tab=\"features\""));
        assertTrue(html.contains("data-robot-tab=\"memory\""));
        assertTrue(html.contains("data-robot-tab=\"relations\""));
        assertTrue(html.contains("data-robot-panel=\"basic\""));
        assertTrue(html.contains("data-robot-panel=\"agent\""));
        assertTrue(html.contains("data-robot-panel=\"features\""));
        assertTrue(html.contains("data-robot-panel=\"memory\""));
        assertTrue(html.contains("data-robot-panel=\"relations\""));
        assertTrue(html.contains("function switchRobotTab(tab)"));
        assertTrue(html.contains("function showRobotValidation(tab,fieldId,message)"));
        assertTrue(html.contains("showRobotValidation('basic','dName','名称不能为空')"));
        assertTrue(html.contains("showRobotValidation('memory','dMemRobot','请选择记忆智能体')"));
        assertTrue(html.contains("showRobotValidation('relations',null,'通讯录中存在重名:"));
        assertTrue(html.contains("保存智能体"));
    }

    @Test
    public void locksEditorScrollingLoadsAllCountsAndAnimatesSidebarCollapse()
            throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("body.dialog-open{overflow:hidden}"));
        assertTrue(html.contains(".dialog-overlay.show"));
        assertTrue(html.contains("overscroll-behavior:contain"));
        assertTrue(html.contains("showDialog('robotDialog')"));
        assertTrue(html.contains("function syncDialogScrollLock()"));
        assertTrue(html.contains("id=\"sidebarToggle\""));
        assertTrue(html.contains("function toggleSidebar()"));
        assertTrue(html.contains(".app-layout.sidebar-collapsed"));
        assertTrue(html.contains(".app-layout.sidebar-fading .sidebar"));
        assertFalse(html.contains("class=\"env-sticky\""));
        assertTrue(html.contains("id=\"envTrigger\""));
        assertTrue(html.contains("id=\"envMenu\""));
        assertTrue(html.contains("function setEnvMenuOpen(open)"));
        assertTrue(html.contains("layout.classList.add('sidebar-fading')"));
        assertTrue(html.contains("layout.classList.remove('sidebar-collapsed');syncStarweaveViewport()"));
        assertTrue(html.contains("layout.classList.add('sidebar-collapsed');layout.classList.remove('sidebar-fading')"));
        assertTrue(html.contains("transition:grid-template-columns .28s ease"));
        assertTrue(html.contains("starweave-sidebar-collapsed"));
        assertTrue(html.contains("await Promise.all([loadStarweaveSessions(false),loadStarweaveTeams(false),loadTaskCount()])"));
        assertTrue(html.contains("document.getElementById('sessionCount').textContent='0'"));
        assertTrue(html.contains("document.getElementById('teamCount').textContent='0'"));
    }

    @Test
    public void keepsEnableControlAndAllowsSelfSubAgentAndRemoteContactInput() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("class=\"agent-enable-control\">'+toggle+"));
        assertTrue(html.contains("function configuredRobotOptions(selected,includeSelf)"));
        assertTrue(html.contains("!includeSelf&&index===editIdx"));
        assertTrue(html.contains("aria-label=\"选择子智能体\""));
        assertTrue(html.contains("configuredRobotOptions(s.name||'',true)"));
        assertTrue(html.contains("可以选择当前智能体，以创建 self-fork"));
        assertTrue(html.contains("aria-label=\"远程联系人智能体名称\""));
        assertTrue(html.contains("placeholder=\"输入远程智能体名称\""));
        assertTrue(html.contains("aria-label=\"选择联系人智能体\""));
        assertTrue(html.contains("configuredRobotOptions(s.name||'')"));
        assertTrue(html.contains("openRobotDialog('+i+',\\'copy\\')"));
        assertTrue(html.contains("function nextRobotCopyName(sourceName)"));
        assertTrue(html.contains("robotDialogMode==='copy'?'复制智能体'"));
        assertTrue(html.contains("id=\"robotDialogSubmit\""));
        assertTrue(html.contains("智能体名称已存在"));
    }

    @Test
    public void exposesDeepSeekHarnessProviderWithSafeRuntimeControls() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("value=\"DEEPSEEK_HARNESS_ACP\""));
        assertTrue(html.contains("DeepSeek Harness · ACP · 实验性"));
        assertTrue(html.contains("id=\"dDeepSeekBaseUrl\""));
        assertTrue(html.contains("id=\"dDshHome\""));
        assertTrue(html.contains("id=\"dDshAgentPreset\""));
        assertTrue(html.contains("function dshPresetOptions(selected)"));
        assertTrue(html.contains("已加载 Profile Bundles（只读）"));
        assertTrue(html.contains("_dshProfileBundles"));
        assertTrue(html.contains("id=\"dPermissionPolicy\""));
        assertTrue(html.contains("value=\"REJECT\""));
        assertTrue(html.contains("需要 Node.js 22+"));
        assertTrue(html.contains("permissionPolicy:provider==='DEEPSEEK_HARNESS_ACP'"));
        assertTrue(html.contains("dshAgentPreset:provider==='DEEPSEEK_HARNESS_ACP'"));
        assertTrue(html.contains("+esc(p.name)+'</option>'"));
        assertFalse(html.contains("p.name+' ('+p.id+')'"));
    }

    @Test
    public void exposesNpmProviderVersionSelectionAndExplicitInstallProgress() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("id=\"dProviderVersion\""));
        assertTrue(html.contains("id=\"providerInstallBtn\""));
        assertTrue(html.contains("id=\"providerInstallBar\""));
        assertTrue(html.contains("function isNpmProvider(provider)"));
        assertTrue(html.contains("function loadProviderVersions(provider,selected)"));
        assertTrue(html.contains("function installSelectedProviderVersion()"));
        assertTrue(html.contains("/api/provider-runtime/releases?provider="));
        assertTrue(html.contains("/api/provider-runtime/install"));
        assertTrue(html.contains("/api/provider-runtime/job?jobId="));
        assertTrue(html.contains("providerVersion:isNpmProvider(provider)?"));
        assertTrue(html.contains("保存并应用到智能体后生效"));
    }

    @Test
    public void exposesDynamicEditableModelCatalogForMainAndMemoryModels() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("id=\"dModel\"") && html.contains("id=\"dModelMenu\" role=\"listbox\""));
        assertTrue(html.contains("id=\"dMemModel\"") && html.contains("id=\"dMemModelMenu\" role=\"listbox\""));
        assertTrue(html.contains("role=\"combobox\" aria-autocomplete=\"list\""));
        assertFalse(html.contains("id=\"dModelOptions\"") || html.contains("id=\"dMemModelOptions\""));
        assertTrue(html.contains("id=\"modelRefreshBtn\""));
        assertTrue(html.contains("title=\"刷新模型列表\" aria-label=\"刷新模型列表\""));
        assertTrue(html.contains("aria-hidden=\"true\">refresh</span>"));
        assertFalse(html.contains(">刷新模型</button>"));
        assertTrue(html.contains("function openRobotModelMenu(inputId)"));
        assertTrue(html.contains("function selectRobotModelOption(inputId,option)"));
        assertTrue(html.contains("document.addEventListener('click'"));
        assertTrue(html.contains("function loadRobotModels(force)"));
        assertTrue(html.contains("function rememberRobotModels(robot)"));
        assertTrue(html.contains("/api/provider-models"));
        assertTrue(html.contains("与主模型共用实时目录和自定义历史"));
        assertTrue(html.contains("留空则沿用主模型"));
        assertTrue(html.contains("await rememberRobotModels(robot)"));
    }

    @Test
    public void exposesAlignedAgentResourceViewersWithSpecializedRendering() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("class=\"agent-resource-actions\""));
        assertTrue(html.contains("openAgentResource('+i+',\\'mcp\\')"));
        assertTrue(html.contains("openAgentResource('+i+',\\'skill\\')"));
        assertTrue(html.contains("openAgentResource('+i+',\\'memory\\')"));
        assertTrue(html.contains("id=\"resourceDialog\""));
        assertTrue(html.contains("id=\"resourceTree\""));
        assertTrue(html.contains("id=\"resourceViewer\""));
        assertTrue(html.contains("/api/agent-resources/tree?robot="));
        assertTrue(html.contains("/api/agent-resources/content?robot="));
        assertTrue(html.contains("function renderMarkdown(markdown)"));
        assertTrue(html.contains("function renderStructuredMemory(content,title,fileName)"));
        assertTrue(html.contains("function renderMemoryIndex(index,title)"));
        assertTrue(html.contains("class=\"memory-index-card\""));
        assertTrue(html.contains("onclick=\"openMemoryDetail(this)\""));
        assertTrue(html.contains("function findResourceFileNode(nodes,fileName)"));
        assertTrue(html.contains("loadAgentResourceContent(node.id,node.name)"));
        assertTrue(html.contains("node.type==='file'&&node.fileName===fileName"));
        assertTrue(html.contains("body.resource-modal-open{overflow:hidden}"));
        assertTrue(html.contains("overscroll-behavior:contain"));
        assertTrue(html.contains("var first=kind==='skill'?null:findFirstResourceFile"));
        assertTrue(html.contains("var collapsed=isDir&&resourceState.kind==='skill'"));
        assertTrue(html.contains("function highlightResourceCode(content,language)"));
    }

    @Test
    public void rendersCommonMarkdownFeaturesSafelyAcrossSessionSurfaces() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("function splitMarkdownTableRow(line)"));
        assertTrue(html.contains("function markdownTableAlignments(line)"));
        assertTrue(html.contains("class=\"markdown-table-wrap\""));
        assertTrue(html.contains("function renderMarkdownList(entries)"));
        assertTrue(html.contains("class=\"task-list-item\""));
        assertTrue(html.contains("class=\"markdown-task\" type=\"checkbox\" disabled"));
        assertTrue(html.contains("loading=\"lazy\" referrerpolicy=\"no-referrer\""));
        assertTrue(html.contains("rel=\"noopener noreferrer\""));
        assertTrue(html.contains("var heading=line.match(/^(#{1,6})"));
        assertTrue(html.contains("class=\"language-"));
        assertTrue(html.contains("<blockquote>'+renderMarkdown(quotes.join('\\n'))+'</blockquote>"));
        assertTrue(html.contains(".markdown-table-wrap{max-width:100%;overflow-x:auto"));
        assertTrue(html.contains(".markdown img{display:block;max-width:100%"));
        assertTrue(html.contains(".markdown-body table{width:100%;min-width:max-content"));
        assertTrue(html.contains(".markdown-body li.task-list-item"));
    }

    private String loadConfigUi() throws Exception {
        return ConfigUiTestResources.loadBundle();
    }
}

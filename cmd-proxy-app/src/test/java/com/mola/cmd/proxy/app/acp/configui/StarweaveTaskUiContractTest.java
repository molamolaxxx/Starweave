package com.mola.cmd.proxy.app.acp.configui;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class StarweaveTaskUiContractTest {
    @Test
    public void exposesTaskBoardWithoutAStandalonePageTitle() throws Exception {
        String html = loadConfigUi();
        String page = section(html, "<section class=\"page-section\" id=\"page-tasks\">",
                "<section class=\"page-section\" id=\"page-mcp-auth\">");
        String taskTargets = section(html, "async function loadTaskTargets()",
                "function renderTaskTargetFields()");

        assertTrue(html.contains("data-page=\"tasks\""));
        assertTrue(page.contains("id=\"taskStats\""));
        assertTrue(page.contains("id=\"taskSearch\""));
        assertTrue(page.contains("id=\"taskAssigneeFilter\""));
        assertTrue(page.contains("id=\"taskCreatedFrom\""));
        assertTrue(page.contains("id=\"taskPagination\""));
        assertTrue(page.contains("onclick=\"openTaskCreate()\""));
        assertFalse(page.contains("<h2>任务</h2>"));
        assertTrue(html.contains("var taskStatuses=['START','IN_PROGRESS','COMPLETED','CANCELLED','SUSPENDED']"));
        assertTrue(html.contains("function taskStatsQuery()"));
        assertTrue(html.contains("async function loadTaskCount()"));
        assertTrue(html.contains("loadStarweaveTeams(false),loadTaskCount()"));
        assertTrue(html.contains("function appendTaskAssigneeParams(params)"));
        assertTrue(html.contains("filter.assigneeInstanceId=instanceId"));
        assertFalse(html.contains("params.push('assignee='+encodeURIComponent(taskState.assignee))"));
        assertTrue(html.contains("function loadMoreTaskComments()"));
        assertTrue(html.contains("function loadMoreTaskHistory()"));
        assertTrue(taskTargets.contains("filter(function(team){return !team.mixedPlacement})"));
        assertFalse(taskTargets.contains("!team.coordinated"));
    }

    @Test
    public void allowsCoordinatedLocalTeamsForExternalTaskApisAndKeepsIdsOutOfSelectLabels()
            throws Exception {
        String html = loadConfigUi();
        String externalTargets = section(html, "async function loadExternalTaskApiTargets()",
                "async function openExternalTaskApiDialog(");

        assertTrue(externalTargets.contains("filter(function(team){return !team.mixedPlacement})"));
        assertFalse(externalTargets.contains("!team.coordinated"));
        assertTrue(html.contains("esc(item.name||'未命名团队')+' ['"));
        assertTrue(html.contains("esc(item.name||'未命名成员')+' ['"));
        assertTrue(html.contains("esc(channelKnownTargetLabel(item))+'</option>'"));
        assertFalse(html.contains("item.id+' · '+item.name"));
        assertFalse(html.contains("(item.displayName||'未命名')+' · '+(item.id||'')"));
        assertFalse(html.contains("ID · 名称"));
    }

    @Test
    public void keepsCreationContentAndCommentsAsSeparateOperations() throws Exception {
        String html = loadConfigUi();
        String create = section(html, "async function saveTask()", "async function updateTaskStatus(");
        String comment = section(html, "async function submitTaskComment()", "async function retryTaskDelivery(");

        assertTrue(html.contains("task-dialog-create .task-edit-only{display:none!important}"));
        assertTrue(html.contains("id=\"taskName\" maxlength=\"200\""));
        assertTrue(html.contains("document.getElementById('taskName').disabled=mode!=='create'"));
        assertTrue(create.contains("creatorName:'ConfigUI 用户'"));
        assertTrue(create.contains("attachmentIds:validTaskAttachmentIds('content')"));
        assertFalse(create.contains("contentMarkdown:document.getElementById('taskComment')"));
        assertTrue(comment.contains("taskApi('/'+encodeURIComponent(task.id)+'/comments'"));
        assertFalse(comment.contains("contentMarkdown:document.getElementById('taskContent')"));
        assertTrue(comment.contains("attachmentIds:validTaskAttachmentIds('comment')"));
        assertTrue(comment.contains("observedContentVersion:task.contentVersion"));
        assertTrue(html.contains("expectedRevision:task.revision"));
        assertTrue(html.contains("e.code==='VERSION_CONFLICT'"));
        assertTrue(html.contains("e.code==='LATEST_CONTENT_REQUIRED'"));
        assertTrue(html.contains("body.reopen=true"));
    }

    @Test
    public void usesBinaryAttachmentsAndDeduplicatedTaskCards() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("'/attachments?fileName='+encodeURIComponent(file.name)"));
        assertTrue(html.contains("'Content-Type':file.type||'application/octet-stream'"));
        assertTrue(html.contains("body:file"));
        assertFalse(html.contains("form.append('file',file,file.name)"));
        assertTrue(html.contains("p.cardType==='STARWEAVE_TASK'"));
        assertTrue(html.contains("data-task-event-id=\"'+esc(p.eventId||'')+'\""));
        assertTrue(html.contains("delivery=p.deliveryState"));
        assertTrue(html.contains("p.taskEventSeq?'<span>事件 #'"));
        assertTrue(html.contains("if(p.eventId&&taskEventIds[p.eventId])return"));
        String teamRender = section(html, "function renderTeamSessionMessages()",
                "function handleTeamSessionScroll()");
        assertTrue(teamRender.contains("id=p.eventId||item.eventId"));
        assertTrue(teamRender.contains("if(id&&seen[id])return"));
        assertTrue(html.contains("function openTaskFromCard(taskId)"));
    }

    @Test
    public void externalInputsUseCardsAndLegacyRawPromptsStayOutOfUserBubbles()
            throws Exception {
        String html = loadConfigUi();
        String reducer = section(html, "function reduceStarweaveEvents(events)",
                "function eventCardHtml(item)");
        String cards = section(html, "function eventCardHtml(item)",
                "function handleStarMessageScroll()");

        assertTrue(reducer.contains("p.source==='CHANNEL'"));
        assertTrue(reducer.contains("kind:'CHANNEL_MESSAGE_RECEIVED'"));
        assertTrue(reducer.contains("p.source==='HISTORY'"));
        assertTrue(reducer.contains("'[Starweave Task]\\n'"));
        assertTrue(cards.contains("CHANNEL_MESSAGE_RECEIVED:['talk'"));
        assertTrue(cards.contains("'外部信道消息'"));
    }

    @Test
    public void distinguishesAcceptedStatusNotificationsFromPendingExecution() throws Exception {
        String html = loadConfigUi();
        String delivery = section(html, "function taskDeliveryText(task)",
                "function taskDeliveryError(value)");

        assertTrue(delivery.contains("taskDeliveryLabel(latest.state||latest.status,latest.eventType,task.status)"));
        assertTrue(delivery.contains("eventType==='TASK_STATUS_CHANGED'"));
        assertTrue(delivery.contains("COMPLETED:'完成通知已接收'"));
        assertTrue(delivery.contains("CANCELLED:'取消通知已接收'"));
        assertTrue(delivery.contains("SUSPENDED:'挂起通知已接收'"));
        assertTrue(delivery.contains("ACKNOWLEDGED:'已接收，等待执行'"));
    }

    @Test
    public void colorsDeliveryTextByTaskStatus() throws Exception {
        String html = loadConfigUi();
        String taskList = section(html, "function renderTaskList(error)",
                "function renderTaskPagination()");

        assertTrue(html.contains(".task-delivery{color:#637083}"));
        assertTrue(html.contains(".task-delivery.in-progress{color:#a56816}"));
        assertTrue(html.contains(".task-delivery.completed{color:#168451}"));
        assertTrue(html.contains(".task-delivery.cancelled{color:#c64655}"));
        assertTrue(html.contains(".task-delivery.suspended{color:#7556a8}"));
        assertTrue(taskList.contains("class=\"task-delivery '+taskClass(task.status)+'\""));
    }

    @Test
    public void fillsTheCommentColumnInReadOnlyTaskView() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains(".task-dialog-view .task-comment-compose"));
        assertTrue(html.contains(".task-dialog-view .task-dialog-side{display:flex;flex-direction:column;overflow:hidden}"));
        assertTrue(html.contains(".task-dialog-view .task-dialog-side>.task-edit-only{display:flex;flex:1;min-height:0;flex-direction:column}"));
        assertTrue(html.contains(".task-dialog-view .task-comments{flex:1;min-height:0;max-height:none}"));
        assertTrue(html.contains("#taskDialog .task-comments{max-height:none;overflow:visible}"));
        assertTrue(html.contains("className='dialog task-dialog task-dialog-'+mode"));
    }

    @Test
    public void adaptsTheWideDialogForSmallScreens() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains(".task-dialog{width:min(1320px,calc(100vw - 48px))"));
        assertTrue(html.contains(".task-dialog-grid{display:grid;grid-template-columns:minmax(0,1.55fr) minmax(330px,.85fr)"));
        assertTrue(html.contains(".task-dialog{width:calc(100vw - 24px);height:calc(100dvh - 24px)}"));
        assertTrue(html.contains(".robot-dialog,.dialog.team-session-dialog,.task-dialog{width:100vw;height:100dvh;border-radius:0}"));
        assertTrue(html.contains("overscroll-behavior:contain"));
    }

    @Test
    public void configuresExternalTaskCreationEndpointsInTheChannelPage() throws Exception {
        String html = loadConfigUi();

        assertTrue(html.contains("创建对外任务接口"));
        assertTrue(html.contains("id=\"externalTaskApiList\""));
        assertTrue(html.contains("id=\"externalTaskApiDialog\""));
        assertTrue(html.contains("function createExternalTaskAuthCode()"));
        assertTrue(html.contains("window.crypto.getRandomValues"));
        assertTrue(html.contains("/api/external/v1/tasks"));
        assertTrue(html.contains("Idempotency-Key"));
        assertTrue(html.contains("function saveExternalTaskApiDialog()"));
        assertTrue(html.contains("function copyExternalTaskAuthCode(index)"));
    }

    private static String section(String source, String start, String end) {
        int from = source.indexOf(start);
        int to = source.indexOf(end, from + start.length());
        assertTrue("missing section start: " + start, from >= 0);
        assertTrue("missing section end: " + end, to > from);
        return source.substring(from, to);
    }

    private static String loadConfigUi() throws Exception {
        return ConfigUiTestResources.loadBundle();
    }
}

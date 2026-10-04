# 观测台

在智能体配置的「能力与角色」开启「开启观测能力」，然后在「观测台」新建通道，也可以让智能体通过 ACP harness MCP 工具创建。

普通智能体可以在没有聊天会话时运行观测，首次发现变化再打开会话通知它。团队成员的通道在该成员所在执行实例运行；会话轮转不会改变通道归属。

## 脚本

脚本通过 `module.exports` 导出函数，可以异步执行，必须返回字符串：

```js
module.exports = async function observe() {
  const response = await fetch('https://example.com/api/issues');
  if (!response.ok) throw new Error(`请求失败：${response.status}`);
  const issues = await response.json();
  return JSON.stringify(issues.map(issue => ({
    id: issue.id,
    status: issue.status,
    updatedAt: issue.updatedAt
  })).sort((a, b) => String(a.id).localeCompare(String(b.id))));
};
```

脚本使用执行实例上的 Node 和所属智能体的工作目录，可以读取工作目录文件、使用该目录的 Node 依赖并访问网络。示例中的 `fetch` 需要提供内置 fetch 的 Node 版本。控制台日志与结果分离，不能通过 `console.log` 返回观测结果。

默认频率 `30s`，支持正整数加 `s`、`min`、`h`。同一通道不重叠执行，超过频率的执行不会累积补跑。脚本超时 20 秒，源码上限 256 KiB，返回字符串上限 1 MiB。超限作为失败处理。

首次成功建立基线，不产生事件。之后精确比较字符串，变化才产生包含前后完整结果的事件。失败保留上一成功基线，恢复后继续比较。修改脚本重新建立基线，修改名称或频率保留基线。脚本应排除当前时间、随机值等无关变化，并稳定输出集合顺序。

「测试脚本」执行当前草稿，显示结果、错误堆栈、日志和耗时，不更新正式基线，不创建事件。测试会真实执行脚本中的操作。

## 通知与历史

新建通道必须填写「事件处理指令」（`eventAction`，最多 8192 个字符），写明处理目标、判断条件和结果去向。例如：「核验 Jira 问题的最新状态，只处理指派给我的未完成问题，将变更摘要及下一步建议发给 Code Chat Dev。」指令应能够在新会话独立执行，避免依赖旧聊天记录。

事件产生时保存处理指令快照，每次通知完整携带该快照，重试也使用原快照。修改指令不重置基线、不生成事件，只影响之后产生的事件。通道详情和事件明细分别展示当前指令和历史快照。已有通道可编辑补齐指令；缺少指令的旧事件仍可查看，通知要求智能体向用户确认处理方式，不推测任务。

事件和新基线通过同一 SQLite 事务保存。智能体忙碌时保留待投递事件，之后重试；睡眠时唤醒；同一智能体的事件按发现顺序分批通知。脚本和通知分别使用有限并发执行，慢脚本不占用通知线程。

「已投递」仅表示 Agent 执行端已受理，不代表处理完成。投递失败可以在事件列表手动重试，事件 ID 保持不变。崩溃恢复的极端情况下可能重复投递。提示词携带发现时间、前后结果预览和事件 ID，完整结果通过事件明细查询。

暂停通道停止执行和后续通知，恢复后继续与成功基线比较。关闭观测能力停止该智能体的观测及通知，保留数据；重新开启先建立当前基线，关闭前的待投递事件需手动重试。删除通道停止后续执行和待投递通知，历史事件仍可通过「全部观测事件」查询。

数据存放在 `CMD_PROXY_HOME/observations/observations.db`。服务重启后恢复基线和待投递记录，不补跑停机期间的观测。

## ACP harness MCP

仅开启观测能力的 Agent 会看到这些工具及对应的 Harness 说明，调用也按当前 Agent 身份检查归属：

| 工具 | 用途 |
| --- | --- |
| `manage_observation_channels` | `action=list/get/create/update/delete` 管理自己的通道 |
| `test_observation_script` | 提供 `script` 草稿或 `channel_id` 测试脚本 |
| `query_observation_events` | 提供 `channel_id` 分页查询事件，增加 `event_id` 查询完整明细 |

创建通道示例：

```json
{
  "action": "create",
  "name": "文件更新监听",
  "eventAction": "核验文件最新内容，汇总变化并向用户报告；已失效的变化无需处理。",
  "script": "module.exports = () => require('fs').readFileSync('status.txt', 'utf8');",
  "frequency": "30s"
}
```

列表支持 `page`、`page_size` 和 `status`；通道列表另支持 `query` 搜索。页面使用当前环境的管理 API `/api/observations/v1/`，支持普通智能体和团队成员筛选。

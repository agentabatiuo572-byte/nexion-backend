# S4 工作台 API 交接

后台前缀 `/api/admin/content/support-workbench`，沿用 `{code,message,data}`。本文件是接口交接，不替代冻结规格或运行验收报告。

## 查询与快照

`GET /overview` 与 `GET /customers` 返回相同结构。参数：`agentId?`、`filter=ALL`、`keyword?`、`pageNum=1`、`pageSize=20`、`from?`、`to?`。普通顾问只能本人，主管可省略 agentId 审阅全体或显式选择顾问；页面 1 起、每页 1–100 条。过滤为 ALL/WINDOW_ACTIVE/ACTIVE/DORMANT/UNKNOWN/DUE/WAITING_REPLY/FIRST_CONTACT/STOPPED/TODO。

```text
data = {
  snapshotId, evaluatedAt, rulesVersion,
  scope: {actorId, agentAdminId: number|null, mode: AGENT|SUPERVISOR_ALL},
  rules: {dormantDays, maintenanceDays, activityWindowDays,
          dormantAvailable, dueAvailable, windowActiveAvailable},
  overview: {boundTotal, activeTotal, knownActiveCount, unknownWindowCount,
             dormantTotal, unknownCount, dueTotal, waitingReplyTotal,
             firstContactTotal, stoppedTotal, todoTotal},
  customers: {total, pageNum, pageSize, filter, available, records: Customer[]},
  performance: {from, to, timeZone, days: [{day, executionCount, successfulCycleCount}],
                executionCount, successfulCycleCount, successfulCustomerCount},
  completeness: {unknownCount, unknownWindowCount, coverageStartAt,
                 observedThroughAt, observationLagMillis, activitySource: INTERACTIVE_LOGIN}
}
```

**S5 必须原子替换同响应的 overview 与 customers。** 搜索、翻页或点击指标均重新使用这一个响应；不能保留另一请求的指标并宣称与新名单同一快照。snapshotId 是该响应标識，不是可重用查询令牌。统计与页记录在同一个数据库 REPEATABLE_READ 快照内计算，SQL 先授权与筛选再分页。

evaluatedAt 是已持久活动水位，也是活动/到期标签计算的时间锚；它不是所有可变表的 AS OF 历史回放承诺。归属、偏好、首次联系、维护执行/周期为该次数据库快照事实，因此水位之后刚提交的联系事实仍可见。活动只取水位内有效事件；其后刚成功的维护周期可能已在详情可见，而活动标签需下次水位刷新。绩效也只计水位内事件。

overview 总量不随 keyword、filter 或页码缩小；customers.total 为匹配本次关键词和筛选的总人数。六指标有重叠，不相加。TODO 是 DUE、WAITING_REPLY、FIRST_CONTACT 客户去重并集；STOPPED 是同快照停止人数。未配置 D 时 dormantTotal=null；未配置 M 时 dueTotal=null、Customer.due=null；未配置 W 或有窗口未知客户时 activeTotal=null。knownActiveCount 是已确认窗口活跃人数，unknownWindowCount 是待确认人数。不能将 null 显示为零。筛选不可用时 customers.available=false，空记录不等于确认为零人。

当前 activityStatus=ACTIVE/DORMANT/UNKNOWN 依赖 D；windowStatus=ACTIVE/INACTIVE/UNKNOWN 依赖 W。活跃卡固定使用 WINDOW_ACTIVE；W<活动间隔<D 的客户当前 ACTIVE，但不在 WINDOW_ACTIVE 集合。沉睡只认完整观察区间；窗口未知不伪报窗口不活跃。

## 客户、未回复与维护

Customer 字段：customerId/customerNo/nickname、assignmentId/assignmentVersion、agentAdminId/agentName、enabled/preferenceVersion（version 同值，用于维护 CAS）、stoppedReason、lastEffectiveAt/lastExecutionAt/lastSucceededAt/nextDueAt、openCycleId、activityStatus/windowStatus、waitingReply/firstContact/due、pendingReplyCount/pendingConversationNo/pendingThroughMessageId。可空时间与标识返回 null。

waitingReply 跨同客户全部人工会话（包括旧关闭段），根据持久 reply cursor 判断，read 不清空。pendingReplyCount 为未处理客户消息数；pendingConversationNo/pendingThroughMessageId 指向最新一条待处理客户消息，仅是进入线程的定位摘要，不表示已覆盖全部旧段。发送仍需显式 replyTargets，不能自动把所有旧会话标为已回复。firstContact 是当前 assignment 从未成功人工联系的事实；首次主动待办另要求 enabled=true。

列表排序：待回复优先，再无到期时间、到期时间升序、customerId。无维护执行时视为立即到期（M 已配置且 enabled），nextDueAt 保持 null，不伪造历史时间。维护锚点取当前归属 max(lastExecutionAt,lastSucceededAt)。

`GET /customers/{customerId}` 返回 `{snapshotId,evaluatedAt,rulesVersion,scope,rules,customer}`。`GET /customers/{customerId}/maintenance` 与 `/maintenance/history` 为等价历史入口，参数 pageNum/pageSize；返回 `{customerId,cycles,executions,totalCycles,totalExecutions,pageNum,pageSize}`，cycle/execution 分别分页。历史仅当前顾问/主管可读，旧顾问只保留允许的业绩汇总。

`PATCH /customers/{customerId}/maintenance`：body `{enabled:boolean,reason,expectedVersion,expectedAssignmentId}`，`Idempotency-Key` 必填；reason 8–200 字符，key 8–128。仅有 M3 write 的当前本人有效顾问可写，主管角色不能代签；沿用生产路径隔离门。成功响应 `{customerId,assignmentId,enabled,version}`，随后刷新详情；结果未知使用原命令回查。关闭 OPEN 周期、偏好 CAS、审计和命令结果由维护服务同事务负责。

所有新增 JSON ID/version 必须为正安全整数，最大 9007199254740991；拒字符串、小数、指数形式、溢出。enabled 只收 JSON true/false。

## 业绩日期

from/to 同时提供，必须为 UTC ISO 时间，半开 `[from,to)`；默认当前业务月份起点至评估水位。逐日分桶沿用代码已有 `Asia/Shanghai` 业务时区，缺记录的日期补零；执行数、成功周期数分列，成功客户数按整个期间去重，历史归原顾问，不按当前归属改写。单次查询最多 3660 天是响应体资源限制，可拆区间查询，不是历史保留规则。无新增趋势端点或 BI 模块。

## 验证与交付边界

`SupportWorkbenchServiceTest` 覆盖 D/M/W 独立、精确活跃 null、分页不改总量、业绩逐日及去重字段、普通顾问禁止他人 scope、安全整数/布尔 wire、W<age<D 与真实 MyBatis 动态 SQL 渲染。已观察主线统一运行该测试 5/5 通过；最终 SQL 投影补充未回复定位字段后仍需主线重跑当前快照。

done-review：API/字段覆盖已逐项回源，跨归属与回复游标通过统一 SQL 投影；持久化、真实 MySQL/HTTP、并发撤权和三仓浏览器实景由主线统一执行，未以本文件或单测宣称阶段完成。当前无本子任务 commit/push 或数据库操作。

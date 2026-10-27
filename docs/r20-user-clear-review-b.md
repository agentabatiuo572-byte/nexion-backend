# R20 USER_VIEW 清空独立后端复审 B

## 当前结论 — 第二轮 READY 与 UGGOR9u8 补证复核，报告冻结（2026-10-27）

**R20-REC-P2-01、R20-REC-P2-02 在本轮源码、已有定向回归及父线程提供的有限前端实景证据范围关闭，各维持 9.2/10。** 未发现本轮修复引入的具体未修阻断。此前精确 peer/clear 拒绝、原 key 读回与后端主体的限定 9.2 维持；本结论不代签 Noether 的最终审查或整个 R20。Averroes 只读最终 diff、测试、红绿日志、Jason 原始执行回执和 UGGOR9u8 留存材料，没有运行测试、构建或浏览器，没有修改实现；证据阅览不计作一次独立 live-browser 操作。

| 复核点 | 当前评分 /10 | 关闭依据与覆盖边界 |
| --- | ---: | --- |
| REC-P2-01：稳定 flush 后的同账号通知 | 9.2 | [scope watcher](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:281) 仅在 account binding 真正改变时清空正文/failed；同账号分配通知继续使私有图片/上传策略失效，但保留文本、UNKNOWN 和明确拒绝状态。[用例](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/messages-start-entry.test.ts:464) 在通知前等待两次 nextTick，分别断言 UNKNOWN＋下一段草稿、稳定普通草稿、稳定 peer 拒绝；继续输入后 store 的原 UNKNOWN 正文仍在，旧 owner、只读门和不自动重开仍正确。原来同 flush 掩盖问题的测试已改正。 |
| REC-P2-02：marker 同步存活页面 | 9.2 | [store](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:155) 只在确认水位覆盖旧 composer 时删除它，并通过 [clearedComposerThrough](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:258) 发布该 owner/key 的水位。[同步 watcher](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:533) 核对 binding、key、严格前进的水位，在保存锁内清掉旧 draft/failed/image/local bubble，避免空 saved 的泛化清空和反向写回。[三入口回归](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/messages-start-entry.test.ts:856) 分别经 management、detail、older-page 接收 marker，均保留边界后的消息 30，旧本地状态与 store composer 同时消失。 |
| 重复 marker 与水位后的新草稿 | 9.2 | 上述三入口用例在 clear 后建立新正文和新附件，再读同水位；新内容不丢、failedSend 为 null、无旧 localSend。store 保存的 userClearThrough 与 marker 的严格前进判断共同保护新草稿；账号 reset 同时重置清理标记，不将旧账号水位带到下一账号。 |
| 迟到旧 send 不回流 | 9.1 | [发送回调](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:1536) 捕获原 composer 清理水位；成功、异常两支均核对它后才更新页面。[新增回归](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/messages-start-entry.test.ts:835) 在发送 pending 时接收 marker、建立新草稿，再让旧 ACK 失败且读回为空，确认旧 failed/bubble 不重建、新草稿不被覆盖。该新增实跑针对迟到失败；迟到成功的同水位门经源码核对，没有虚称新增成功时序或真实 WS 实测。 |
| 原 key 的恢复责任 | 9.2 | 新 marker 只退役旧页面/outbox 镜像，不删 pending 命令。迟到 ACK 用例确认 commandResult 使用原 key，null 后 key 仍保存；三种 marker 用例亦确认它未被删除。[reconcilePending](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:743) 独立遍历 pending，恢复不以旧 composer 存在为前提。既有 [assignment＋reload](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations-realtime.test.ts:110) 实际再次发送使用同 key，[cold clear readback](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations-threads.test.ts:207) 通过原 key 恢复且没有第二次 DELETE。本轮没有删除 pending 或把普通 409/null 改为确定未受理。 |

### 第二轮实际红绿证据

| 原始日志 | 实际结果 |
| --- | --- |
| [r20-page-mirrors-red.log](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/.cache/r20-page-mirrors-red.log) | 17:15:07，过滤运行的 6 项全部失败，另 127 skipped；断言分别为稳定 UNKNOWN/草稿被清空、三入口 clear 后旧本地草稿未清掉。SHA-256 `36144eb10134bea7aa9f517d18d50aad755cd713a5f4e5ba46afc3896c524603`。原失败保留。 |
| [r20-page-mirrors-green.log](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/.cache/r20-page-mirrors-green.log) | 17:16:20，同六项通过，另 127 skipped；这是过滤红绿对照，不写成全套零跳过。SHA-256 `330061025fba06d844b72a55afff58b65df7a67146ff2a9f5a4e8bd1414d7ae3`。 |
| [r20-page-mirrors-targeted-green.log](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/.cache/r20-page-mirrors-targeted-green.log) | 17:19:43，**8 文件、396/396 passed、0 skipped，8.52 秒**。核对 Jason 回执 `exec-4b656ed6-2ce3-44c7-bf19-f32214be1af1`：原八套件无名称过滤、exit 0，包含新增迟到 ACK 用例；未重跑。SHA-256 `b75688d9b5cce07d14822c9242add2e71b55c3f57e80ba95ff2c893e765c37f4`。 |

本轮 APP HEAD 仍为 `19f1a42b6b0dbda7ba6579aae236158f5adf7b93`；当前 chat.vue SHA-256 为 `5edea8ee6e21202ad47784a67d975f610606fbd3cb3de45754db6a5dd29173c0`，store 为 `fa3d507010cd43e0e13f5be58cf1a0673b84788805eeb5cb89fa479f3b8c9c26`，均与 Jason 测后回执一致。messages-start-entry.test.ts 为 `b08d66c8c27abe8e03b649852ff14ba67f2e094c44d102c1843a371fe36520d5`；nova-queue-page.test.ts 为 `090fa0d41a637d51ae429ed62c6e304ac098d8e8c67b908542d60b10d85eb08e`，只补新 store 字段的 harness 接线，未弱化原断言。backend AppSupportService 的 `988c8870…` 指纹与上一轮一致，17 项后端日志没有改称本轮重跑。

### UGGOR9u8 最终前端补证

已读取两份原始 JSON 并查看四张截图，资产均对应父线程记录的 `index-UGGOR9u8.js`。本次再次核对 chat/store 与两份页面测试的完整 SHA-256，仍与上方第二轮 READY 指纹一致，未把新截图误配给旧源码。

| 时序 | 已核对的材料与结论 |
| --- | --- |
| 稳定 draft → scope 通知 | [live-stable-scope.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-stable-scope.json) 明确记录 DOM 稳定后注入 ASSIGNMENT_CHANGED，before/after 均为“分配通知到达前已稳定的草稿”；[截图](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-scope-draft-retained.png) 显示该输入仍在。补足 REC-P2-01 的稳定普通草稿 DOM 证据；UNKNOWN/明确拒绝跨通知仍以 396 项中的实际回归为依据，未借此声称它们也各做了新实景。 |
| 存活 chat 收到另一客户端清空 | 父线程在独立 fixture 5240 模拟另一客户端 DELETE，再由正常 fixture incoming 触发前端刷新。[live-page-clear.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-page-clear.json) 的 clear 回执为 code=0、USER_VIEW、throughMessageId=1；after 为 draft=""、failed=0，同时保留新的客服正文。[清空前](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-page-clear-before.png) 的旧失败气泡在 [清空后](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-page-clear-after.png) 消失，输入框为空、新客服消息仍在，与 REC-P2-02 修复相符。 |
| 新 draft → 同 marker 重读 | 同一 JSON 的 newDraftBefore/newDraftAfter 均为“清空以后新写的草稿”，记录 management、cursor、detail 等刷新响应均为 200；父线程以唯一注入事件促发同 marker 的重新读取。[重复 marker 后截图](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-repeat-marker-new-draft.png) 保留新输入和客服正文，没有旧失败气泡回流。JSON 是响应状态/结果摘要，不把它当完整后端响应体或持久性证明。 |

JSON SHA-256：live-stable-scope 为 `932730c2ad8411d4e4f4f8fa707f6f2a5a8e8cca7034abdf1bf268b5df01e642`；live-page-clear 为 `6ffc448a5f9dd93b0a079559e8f2470c918c6103481a14f8ec3ff0ccde8214ce`。两份材料分别明确 formalServer=false、formalJava=false。事件注入与正常 fixture incoming 经过实际前端路径，但属于隔离 fixture／合成事件，**不签正式 Java、JWT 或生产 WS 端到端**。

最终证据归属补记（仅此一次）：父线程已在同一 `index-UGGOR9u8.js` 重跑三条基础恢复链，当前依据为 [live-final-recovery.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-final-recovery.json)，以及 [冲突保留](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-clear-conflict-retained.png)、[再次确认后清空](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-clear-after-reconfirm.png)、[丢响应后保留](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-clear-lost-refusal-retained.png)、[旧 peer 只读](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-peer-changed-readonly.png)、[主动进入当前客服](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-final-peer-current-advisor.png) 五张完整图。本审核对该 JSON：冲突后的再次确认使用不同 key；丢响应读回路径的 key 摘要等于原 DELETE key，再次确认使用新 key；peer 记录只有 1 次 reply、0 次 create。它仍明确 formalJava=false、5244→隔离 5240 fixture；不增签真实服务或独立浏览器操作。旧 `index-DRX3akFz.js` 记录和裁偏图仅留历史，当前评分及冻结边界不变。

### 当前交接边界与冻结

此前三条 `index-DRX3akFz.js` basic UI 证据仍只覆盖直接恢复流程；本轮新增 UGGOR9u8 材料补足上述 DOM 时序，不能扩大为真实登录/新设备、正式 Java HTTP/WS 或全设备键盘/触控验收。迟到 ACK 与原 key 的细粒度边界仍按上表的源码及定向测试范围评分，不伪造实景覆盖。历史低分、红日志与 r2 treeMoved 失败均保留。

[r3 APP 计划](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/.cache/r20/app-regression-r3.plan.json) 已按第二轮 READY 的实际文件清单冻结，本次未修改；本审仍未 init/run。既有八套件加 Earn/API 两套及 types，未扩大测试矩阵。后续 execution 日志、state、integration review 使用仓外 r20-workflow-state；独立 workflow 验收仍须检查真实 snapshotHash 和完整 receipt，当前代码审查不等同 r3 passed。**本报告补证已完成并冻结，本审停止文件写入，避免 workflow 期间变动。**

按用户最新授权，恢复的 trial 子会话另行验收；本审及 r3 只覆盖主 R20。H5 须等主 R20 与 trial 两边 APP 均通过，再由父线程统一处理；本审不执行 H5 同步、提交或远端合入。

<details>
<summary>历史：两项页面 P2 开放时、首轮局部签署及更早低分／失败记录（原文保留）</summary>

## 当前状态 — 两个页面时序 P2 开放，等待下一次 READY（2026-10-27）

**当前不签署完整 APP 恢复链，r3 保持 preparation。** 本审已只读核对 Noether 的 [新增发现](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/docs/r20-user-clear-review.md:166) 和当前 watcher，确认同样的两条实际调用路径；沿用 `R20-REC-P2-01/02`，不另开编号或重复实施。Jason 正在原范围修复，待新的 READY 后再审最新 diff 与必要回归；本次未运行新增用例。

| 统一编号 | 当前确认的问题与有限验收要求 |
| --- | --- |
| R20-REC-P2-01，开放 | [账号/scope 同步 watcher](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:281) 对同账号 assignment 通知也清空本地 draft/failed/image；稳定状态完成 flush 后，saved composer 与 busy 均未变化，不会触发恢复。后续输入可把 failedSend=null 写回，虽原 key 仍在，恢复正文与页面发送阻塞已丢失。须验证先完成 flush 的草稿、UNKNOWN 和明确拒绝正文，再触发通知；不能只测同 flush 的两个赋值。 |
| R20-REC-P2-02，开放 | [applyUserClear](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:155) 删除旧 composer 后，[页面 saved watcher](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:563) 遇到 !saved 直接返回，存活页面的旧 draft/failed/image 与本地气泡仍在，后续保存可重新写回。须验证权威新 marker 清掉水位前的页面镜像、之后的新草稿不被重复 marker 清掉，同时继续保留未知命令的原 key 读回责任；不能泛化为 saved 缺失就清空所有本地输入。 |

此前 390 项绿及父线程三条 basic UI 证据仍有效，**均不覆盖以上两个时序**；不得据此给新修复预签。下方 9.2 保留为此前精确拒绝／读回与 store 局部结论，不能作为当前页面生命周期或 R20 总通过。后端水位、原文保留与终态拒绝契约没有因这两个页面缺陷被重新判红。r2 的失败记录与原证据保持不变。

## 此前 READY 轮次 — 协议及直接恢复路径复核（2026-10-27）

**R20-B-01、R20-B-02 的直接拒绝／key 恢复路径在当次范围内关闭，各 9.2/10；终态拒绝读回契约 9.2/10。后端主体限定评分维持 9.2/10。** 当次未识别上方后续通知／clear 页面镜像问题；现以顶部开放项限制此结论。下方首轮 7.0 是更早修复前历史。本审由未编写本后端及本次恢复补丁的 Averroes 独立完成；只读源码、测试与已有执行记录，没有重跑测试、构建、操作浏览器或数据库。

| 项目 | 当次局部结论与限定评分 | 当次依据 |
| --- | --- | --- |
| R20-B-01：peer-change 精确拒绝与旧 CID | 关闭，9.2 | [policy](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/lib/conversation-delivery-policy.ts:1) 仅接受匹配状态及完整 reason 的 `CONVERSATION_PEER_CHANGED`；[store](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:809) 只结束该被拒命令并标记当前账号的旧 CID、刷新 advisor。[页面](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:862) 保留旧 owner/status 与失败正文，关闭该 CID 的发送及失败重试；[restart](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/chat.vue:1104) 只在用户操作时进入新开场，没有自动创建或搬运重发。收到明确拒绝后的刷新、hide/show 与三语入口有测试；跨账号迟到拒绝不会写入下一账号。 |
| R20-B-02：clear 冲突后的再次确认 | 关闭，9.2 | 两个 domain 409 均在 [水位写入之前](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/application/AppConversationManagementService.java:62) 返回。[command](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:504) 在 clear 操作边界释放对应失败 key；[deleteConversation](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:121) 保留记录并刷新成员/版本，等待再次主动确认。新增 [retained-key 回归](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations-threads.test.ts:98) 真实模拟同 key 的原拒绝／payload hash 冲突，断言同 CID 新版本使用新 key、正文和草稿保留、未自动再次 DELETE。 |
| 原拒绝响应丢失后的 backread | 通过，9.2 | [commandResult](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/application/AppSupportService.java:514) 按认证用户的 scopes＋原 key 查询，多结果仍为 AMBIGUOUS；仅 `SUCCEEDED` 且 scope 精确为 `APP_CONVERSATION_USER_CLEAR:<当前用户>`、保存 envelope 为 409 且 reason 为两个白名单之一时，才返回原终态拒绝。`PROCESSING/FAILED/UNKNOWN`、其他用户/操作 scope、通用 409 或 500 均不能冒充这个证明。APP 即时读回和 [冷恢复 reconcile](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:740) 还核对当前 account/run、对应 key 与 in-flight 状态；不把拒绝应用为成功水位。 |

当次确认的 store/key 约束保持：clear 的精确拒绝例外没有全局改变 human ambiguity；通用 409、网络失败、`SUPPORT_COMMAND_NOT_COMMITTED` 等未确认读回保留原 key。[同账号 assignment 通知](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:303) 的 store 处理不再直接删除 outbox，跨重载仍用原 key 恢复；但稳定页面正文的保留另受顶部 R20-REC-P2-01 阻断，不能由 store 绿测推出页面同样正确。[测试](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/pages/support/messages-start-entry.test.ts:434) 还证明一个 peer 拒绝不会释放同 CID 的另一条 UNKNOWN。12 秒 ACK＋3 秒读回、canonical echo 与迟到结果处理未被当次补丁改动。

### 已读取的最终证据（没有代跑）

- APP：Jason 任务 `01a17888-fc86-7ae1-af48-2a71787dc4bc` 的原始执行回执 `exec-e5f7fb0e-be65-4e23-b510-d3a6f8ee1ea9`，2026-10-27 16:48:02 开始，8.08 秒，**8 文件、390/390 passed，0 skipped，exit 0**。核对了完整输出与命令：policy、threads、send-deadline、realtime、cold-session、messages-start-entry、chat-conversation.contract、nova-queue-page；未虚构为独立落盘日志或本审执行。新增测试断言见 [clear 直接/丢响应/UNKNOWN 对照](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations-threads.test.ts:98)、[assignment/reload 原 key](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations-realtime.test.ts:110)。
- 后端：[r20-clear-recovery-green.log](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/target/r20-clear-recovery-green.log)，`AppConversationUserClearTest` 11 项＋`AppSupportCommandRecoveryTest` 6 项，**17 passed、0 failure/error/skip，BUILD SUCCESS**，结束于 16:47:51 +09:00。SHA-256 `064fba97b05612004bd6990c986a5047cd5b66e53a7bc6e8fe7c3779a18151e8`。新增 [终态拒绝及非终态/其他 scope 反例](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/test/java/ffdd/opsconsole/content/application/AppSupportCommandRecoveryTest.java:79) 使用真实 service、mock mapper；这 17 项不是新增真实 DB 运行。既有 199 绿与独立 DB 二轮 11/11 仍按下方各自日志计，原整轮失败不改签。
- 冻结绑定：APP HEAD `19f1a42b6b0dbda7ba6579aae236158f5adf7b93`，backend HEAD `090a7e5032a8f6e781150201ce51005d554890de`。本审实读的 APP policy/store/chat 三文件 SHA-256 分别为 `4e85ec9e38a0b72774470dd62089d9fe3c4634b90f56c4872ed6faaf72e4c802`、`85ae46e25fd3b67d681d1991003bd308b6a14d5042b1c306ad2fc1dab1f44760`、`43fc423c7a49c966345662b00f17e5d41df6c3f7e41ec22b00ae8abc63399b93`，与 Jason 测后原始回执一致。backend `AppSupportService.java` 为 `988c88700587752bfc41dfebdbba4d024e4ddfbf3cbfbec5d8e449f43250198f`，同样一致；按下方原算法重算的当前 19 个生产/迁移文件摘要为 `0f816cb89111efa84beba147b0ac7f3b08ccea88b0ae079ab1c166f9968a3dc0`。

### 父线程追加的真实 APP UI 证据（留存材料独立复核）

父线程操作的构建为 `index-DRX3akFz.js`，经独立 loopback 故障代理 `5244 → fixture 5240`。**这是实际前端交互与 HTTP 请求序列证据，fixture 并非正式 Java 后端。** 本审读取了三份 sanitized JSON 并查看五张截图；没有再次操作浏览器，也不把这次材料复核计作另一位 live-browser 视觉审查。

| 场景 | 已核对的留存证据 | 证明范围 |
| --- | --- | --- |
| clear 首次版本拒绝，再次显式确认 | [live-clear-conflict.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-clear-conflict.json) 中两个 DELETE key 摘要分别为 `6cae39d0d6062835`、`250c1746b8cfd5b7`；`retainedAfterRefusal`、`emptyAfterExplicitReconfirmation`、`emptyAfterReload` 均为 true。[拒绝保留列表](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-clear-conflict-retained.png) 与 [再次确认后为空](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-clear-after-reconfirm.png) 相符。 | 当前前端能保留列表并以新 key 再确认，fixture 场景刷新仍空；不据此证明正式 DB 持久化。 |
| 原拒绝响应丢失 | 父线程以首次 503 注入模拟丢响应。[live-clear-lost-refusal.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-clear-lost-refusal.json) 记录 DELETE→原命令 GET→第二次 DELETE；本审对 GET 路径的 key 重算 SHA-256 前 16 位为 `9270af5d2e09a965`，与首次 DELETE 的 key 摘要一致，第二次为 `3f7ca04a4edcf370`。[读回拒绝后列表保留](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-clear-lost-refusal-retained.png)，最终文本为无服务消息。 | 证明本次前端按原 key 查询，并在确认拒绝后允许新 key；503/明确拒绝由故障代理设定，JSON 为请求/结果摘要，不是正式服务响应日志。 |
| peer 精确拒绝与主动 restart | [live-peer-changed.json](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/live-peer-changed.json) 的 before 保留“演示客服”、拒绝正文及只读/重新发起提示，after 为“当前客服”新开场；记录期间只有 1 个 reply、0 个 create。[旧会话截图](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-peer-changed-readonly.png) 与 [当前客服入口](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/docs/audit/2026-10-27-service-earn/r20-peer-current-advisor.png) 一致。 | 支持父线程本次操作中无自动创建/重发；只读门及跨账号/UNKNOWN 隔离仍以源码和上述定向测试为依据。 |

三份 JSON 均记录同一入口资产。SHA-256（按表顺序）：`7905ab4858c7588b2ab64fcb4aa49600d7505e023339b5bfe8aded6527c51cda`、`441b868e5fc3922efe6b41e344e80a10f15c4295f1354c97a4909afea8e26682`、`f0e40ec341b3415db2bfc5e33e6a587ce4cb70e088cfba763b68436448d68ea1`。这组证据补充前端交互覆盖，不改变本报告后端隔离证据与正式服务边界，也不导入 r3 的执行结果。

### 仍独立于本结论的边界

正式 Java HTTP/WS 丢包后的端到端恢复、正式 DB/迁移部署、真实登录与新设备、存储服务及 H5 实景没有在本审重验；上述 APP fixture 实景与源码评分不替代这些证据。5237 原聊天与草稿未操作。r3 APP 子计划只准备、不 init/run；它仍需父线程执行及独立 integration review。r2 的 `treeMoved=true`／失败记录不能由本报告改签。

<details>
<summary>历史：15:31 首轮审查及当时开放项／7.0（原文保留，已由上方当前结论取代）</summary>

审查者：Averroes（未编写本后端）；日期：2026-10-27。范围：D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend 相对 `090a7e5032a8f6e781150201ce51005d554890de` 的 R20 diff，以及必要的 APP 错误契约。只读源码、测试和已有原始日志；本次没有运行 Maven、数据库、浏览器或修改实现。

**后端主体限定评分 9.2/10；跨端恢复暂不通过。** 在本次覆盖范围未发现新增后端 P0/P1，也未发现越权清空、已清正文/附件绕过过滤或物理删除客服原文的确定缺陷。下面一个新跨端 P2 与一个已知在途接入项仍开放，不能把后端评分当作 APP/H5 或 R20 总验收。

生产快照：2026-10-27T06:31:00.779Z，19 个变更生产/迁移文件的清单摘要 SHA-256 为 `a2ac5d610fdc3b4f34fb6440fb79bcc93c2cef85a86fd0cc0b14a5a44589d8bb`。算法是将 HEAD diff 与未跟踪的 `src/main/java`、`scripts` 文件去重排序，逐文件计算字节 SHA-256，再对 `JSON.stringify([{path,sha256},...])` 求 SHA-256；未把测试、文档或日志混入生产摘要。

## Findings

### R20-B-02 — P2，清空版本冲突后原 key 会卡住后续确认（开放；跨端契约）

这是源码调用链确认，未执行新的运行时复现。触发条件是**同一 peer、同一组 CID，确认所用版本已经过期**：

1. [clear 的版本检查](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/application/AppConversationManagementService.java:70) 在写入任何水位前返回 `409 CONVERSATION_USER_CLEAR_CONFLICT`。这属于明确未清空；`MEMBERS_CHANGED` 同样位于写入之前。
2. [completeClaim](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/shared/idempotency/AdminIdempotencyTransactionExecutor.java:170) 会把返回的失败 ApiResult 作为该 retained key 的终态响应保存；同 payload/key 重放仍得到原拒绝。刷新版本后使用同 key 则会请求哈希冲突。该行为本身没有误删数据。
3. [HTTP advice](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/shared/api/ApiResultHttpStatusAdvice.java:27) 确实把 ApiResult 409 映射为 HTTP 409，不能按“HTTP 200 业务错误会自动释放 key”排除此路径。
4. APP [clear intent](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:129) 只包含 peer＋CID 集合，不含版本；[command](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:475) 会复用 pending key。其 [mustReadBack](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:390) 仍将这两个 clear 409 当 UNKNOWN。命令读回又被后端 [recoverSucceededCommand](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/application/AppSupportService.java:535) 转成 `409 SUPPORT_COMMAND_NOT_COMMITTED`，不会提供可采纳的 clear receipt。
5. 因此刷新后 CID 集合不变，再次主动确认仍使用旧 key；旧 payload 得原冲突，新版本 payload 得 hash conflict。[reconcilePending](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations.ts:731) 捕获错误后继续保留 pending。列表/原记录会保留，但清空操作无法正常恢复。若成员集合真的改变，intent 可能变化；本 finding 不把这种情况概括为必然永久卡住。

最小建议：在 clear 操作的边界精确识别这两个**已证无写入**的 domain 409，保留记录、刷新成员/版本，等待用户再次确认后生成新 key；普通 409、网络失败和真正 UNKNOWN 仍保留原 key。若初始拒绝响应丢失，必须通过权威终态读回确认拒绝，不能只按 readback 的通用 409 推断未执行。不要先改共用幂等框架或放宽所有 409。

现有 [store 拒绝测试](D:/WORKS/PLAN/.wt/uvel-design-20261025-app/src/store/conversations-threads.test.ts:78) 只断言第一次失败不移除数据，没有覆盖“真实 retained 拒绝→刷新同 CID 新版本→再次确认”。建议后续仅补这一条恢复链及通用 UNKNOWN 对照，不扩矩阵。

### R20-B-01 — P2，peer-change 409 必要 APP 接入（已知在途，尚未签署）

后端 [APP reply](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/application/AppSupportService.java:331)、[staff reply](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/application/OpsConversationService.java:293) 经 [stored peer 检查](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/infrastructure/MybatisConversationRepository.java:114) 拒绝把新消息写进旧客服 CID。原 key 已提交结果先恢复，不被新写入门误杀；独立 DB 二轮已覆盖这两类行为。

assignment transfer 不会把旧 CID 标成 TRANSFERRED，故 APP 不能只依赖原 status 禁用输入。用户已明确要求：精确识别 `CONVERSATION_PEER_CHANGED` 为拒绝、保留正文、旧 CID 只读、由用户主动 restart 到当前客服；不自动重发，不把网络或通用 409 改为 failed。

15:32 JST 已看到在途 APP helper 的精确 peer-change 分支，但尚未收到 Jason READY，也未审其最终完整 diff/结果；**不关闭此项**。它与 R20-B-02 不同：仅添加 peer-change 例外不会修复 clear conflict。最终接入评审与后端主体评分分开。

## 逐项限定评分

分数是本次源码和已记录隔离证据的质量判断，不是覆盖率，也不宣称已部署。

| 项目 | 分数 | 已核对的证据与边界 |
| --- | ---: | --- |
| 身份与清空授权 | 9.3 | [controller](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/web/AppConversationManagementController.java:34) 要求已认证 USER，userId 来自认证上下文；service guard＋锁定查询绑定 userId/peer，外账号、外 peer、缺成员不写水位。没有以请求体 userId 授权。真实 JWT/网关仍未在本审运行。 |
| 持久水位与历史 CID | 9.3 | [migration](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/scripts/migrations/20261027_app_conversation_user_clear.sql:1) 分别存账号＋peer 和账号＋peer＋CID；[THROUGH/CLEARED](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/mapper/AppConversationVisibilitySql.java:6) 取两类水位最大值并区分零水位与无 marker，旧 CID 改归属不恢复已清历史。 |
| 事务、锁与幂等 | 9.2 | clear 的 READ_COMMITTED、customer→peer 行锁顺序、两表推进、required audit 与成功结果同事务；after-commit 才发失效通知。原 clear 重放返回原水位，新消息不被再次清掉。DB 证据包含审计失败回滚及等锁后重新过滤。明确拒绝的 APP key 恢复另列 R20-B-02。 |
| 同 peer 复用与写入身份 | 9.2 | [resume](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/infrastructure/MybatisConversationRepository.java:460) 在 customer mutex 下按真实 stored staff ID 找 CID，不按显示名称，不合并无身份 CID；APP/staff 新 reply 核对 stored peer。真实多节点并发压测未覆盖。 |
| 全部本次 human USER 读取面 | 9.2 | [list/count/cursor](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/mapper/AppConversationClearMapper.java:46) 在分页前过滤；[message mapper](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/mapper/ConversationMessageMapper.java:84) 覆盖正文、历史游标、未读及已读推进；[projection](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/application/AppConversationVisibilityService.java:26) 清预览/时间/最后 ID；create/reply/command replay 重新投影。附件元信息、内容、upload replay 同样校验水位；旧 read-receipt HTTP 别名调用同一 APP service。 |
| 客服原文、附件与审计保留 | 9.3 | clear 只写两张 visibility 表并追加审计；[staff detail](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/application/OpsConversationService.java:144) 仍走完整 messages；[attachment guard](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/application/SupportAttachmentService.java:299) 只对 USER 应用水位，staff 仍须原管理权限。DB 用例比较原行并读 staff 附件；对象存储使用测试替身，不能称真实文件存储已验。 |
| WS 与安装接线 | 9.0 | [clear listener](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/main/java/ffdd/opsconsole/content/realtime/ConversationSocketHandler.java:23) 仅通知同账号 USER，重新认证，只发失效标识；其它事件也不携带旧正文，command 仍调用 Spring service。迁移在 [startup installer](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/scripts/apply_startup_schema_migrations.ps1:349) 注册。真实 WS 会话、安装/部署未在本审执行。 |
| APP 409 端到端恢复（独立项） | 7.0，未通过 | R20-B-01 在途；R20-B-02 新发现。后端 9.2 不覆盖这两项，也不以其低分否定已核对的后端水位/保留逻辑。 |

## 实际测试证据

本审只读取以下日志，没有重跑或改写结果：

- [final-matrix.log](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/final-matrix.log)：10 个非 DB review 类合计 **199 passed，0 skip**；独立 DB 类当轮 **11 项，4 failure＋1 error，0 skip**；原整轮 **210 项 BUILD FAILURE** 保留。SHA-256：`fa1309397faa3499cc0656c2878fa7de595536b0cb3d69ad04807a050177b150`。
- [mysql-review-r2.log](D:/WORKS/PLAN/.wt/uvel-design-preview/r20-backend-test/mysql-review-r2.log)：独立 DB 类 **11/11，0 failure、0 error、0 skip，BUILD SUCCESS**，结束于 2026-10-27 15:25:56 +09:00；测试 22.21 秒，总命令 1:05。SHA-256：`f8f5a013b64fab66aa0033fe96a742c93e247f88c111451ae4e98d79473a28ae`。
- 原失败包括 USER 身份缺失的 401/未到达锁探针，以及裸 ObjectMapper 不识别输出 DTO 的 linkTarget。当前 [独立 fixture](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/test/java/ffdd/opsconsole/content/application/R20UserViewClearMySqlReviewTest.java:378) 在并发线程建立真实 USER SecurityContext，保留“必须到达实际 customer 锁”的断言；[mapper 构造](D:/WORKS/PLAN/.wt/uvel-support-clear-20261027-backend/src/test/java/ffdd/opsconsole/content/application/R20UserViewClearMySqlReviewTest.java:518) 使用 Spring builder＋项目 DateTimeFormatConfig。附件拒绝、无 storage 读取、审计回滚、新 session、原文保存等断言仍在。与用户提供的“仅 fixture 修正”解释一致。
- 结论是**既有 199 绿＋DB 二轮 11 绿**，不是把旧整轮 210 的失败日志改签为单轮全绿。本次不需要重复这些测试。

## 未覆盖及交接

- 未运行真实登录/换设备 HTTP＋WS、正式 DB、生产对象存储、迁移发布、长时间/多实例并发、真实用户清空或 APP/H5 实景。隔离 DB 重建 service graph 证明该环境下持久恢复，不等于已在用户设备验过。
- 新 409 的 APP 最终恢复行为待负责人 READY 后一次性复核；本报告不能预签在途修改。R20-B-02 最小恢复用例尚无实跑证据。
- 用户 5237 原聊天及草稿、已有服务和数据库均未操作。报告只新增本文件，不改 production、测试、计划、既有 audit，不 commit/push。
- 结论：后端实现及现有隔离证据达到本报告的限定 9.2；交付仍须关闭明确的跨端恢复缺口并由主线程完成后续验收。此处无需为了评分修改已通过的后端逻辑。

</details>

</details>

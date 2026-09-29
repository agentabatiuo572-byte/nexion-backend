# S4 后端交接：维护、有效活动、聚合与私有图片

基线为已验收 S3 `7fec53d231ebcef1e36c898bbbee61d329b69b99`。本阶段只提交 `codex/cs-maintenance-20260929`，不合入 test，不执行生产 SQL；冻结 S1 七件套保持原样。新增确认口径见 `specs/support-redesign-20260929/S4-SUPPLEMENT.md`。

## 接入契约

- 工作台：`GET /api/admin/content/support-workbench/overview|customers`，同响应返回 overview、customers、performance、规则和完整性。S5 必须一起替换卡片和名单。TODO 为 DUE/WAITING_REPLY/FIRST_CONTACT 去重并集；STOPPED 为停止总量。活跃卡用 WINDOW_ACTIVE，不用 ACTIVE；null 表示不可确定/未配置，不能显示零。完整字段及范围见 `support-s4-workbench.md`。
- 维护：`PATCH /customers/{id}/maintenance`，需要 `Idempotency-Key` 和 `{enabled,reason,expectedVersion,expectedAssignmentId}`。本人当前有效顾问且有 M3 write 才可写，主管不能代签。停止终结 OPEN，恢复不复活；历史 GET `/customers/{id}/maintenance/history` 返回两个独立分页集合与总量。原 key 回查复用 `/commands/{key}`，转绑后旧顾问不得恢复私有结果。
- 人工消息：原发起/回复接口新增 `kind=TEXT|IMAGE`、`intent=SERVICE|MAINTENANCE`、`clientMessageId`、`attachmentId?`、`expectedAssignmentId`；新协议的后台发送必须携带当前 assignmentId。App 不接受维护意图。普通服务默认 SERVICE；只有明确 MAINTENANCE 成功提交才计执行。
- 客户端生成并保留 clientMessageId（8–128 位字母、数字、下划线、连字符），直到确认已发送或明确放弃。上传 key、上传 clientUploadId、发送 key、发送 clientMessageId 分开；不得用新 clientMessageId 重发不明结果。不同发送 key 携带同 clientMessageId 和同载荷恢复原消息；换载荷返回 409。原 S3 无新字段请求仍兼容。
- 原 replyTargets 规则不变：read 不清待回复，发送只推进显式覆盖的客户消息；旧关闭段需显式目标。消息返回 assignmentId/kind/intent/clientMessageId/attachmentId/authorConfidence/committedAt。历史作者 UNKNOWN；新消息 VERIFIED，原 senderId 为真实主体。
- 后台 create 同样支持 `replyTargets:[{conversationNo,throughMessageId}]`：新段只发送一次首消息，在同事务覆盖选中的旧段游标；不往已关闭段补消息、不清较新/未选中的待回复。跨客户目标拒绝并回滚新段、消息和维护执行。省略该字段保持旧请求摘要兼容。
- 无配文图片转工单保留“图片（在来源会话查看）”，有配文则随后保留；沿用 sourceConversationNo 与 R08 限制正文投影，图片不复制进工单存储。
- 图片：后台 `/api/admin/content/conversations/attachments`，App `/api/app/support/attachments`；GET `/policy` 取能力、POST multipart 上传、GET `/{id}/content` 认证读取、DELETE `/{id}` 取消。上传成功字段是 `id`，发送时写入 attachmentId。仅 PNG/JPEG；IMAGE 可无文字。浏览器须带当前身份取 blob，不能把私有存储地址或带令牌 URL 写进消息。完整接点与配置见 `support-s4-attachments.md`。

所有新增事件时间为 UTC ISO offset；历史会话/message `createdAt` 沿用旧契约，不能把它当 UTC 重解释。业绩逐日桶沿用 Asia/Shanghai，日期区间 `[from,to)`，执行次数、成功周期次数、区间成功客户去重数各自独立。

## 活动证明和迁移

只连接服务端完成的密码/OTP/2FA/强制改密交互登录。注册自动 session、当前 mock OAuth、refresh、心跳、聊天、已读和管理员操作均不作为有效活动。登录会话、活动事件、维护成功同事务；捕获失败令登录失败。sourceRef 为服务端 session chain，不收外部事件名或时间。

活动只接服务端 PRODUCTION audience；test 沙箱登录保留自身能力但不进入正式客服捕获。活动入口、检查点和工作台查询均复用既有环境保护门。已读回执也先验证会话所属客户，再取得客户锁，随后才锁会话，避免与顾问回复形成相反锁序。

coverage 行共享/独占锁给出已提交活动水位；工作台检查点之后在单一数据库快照计算统计和页面。首次迁移没有历史覆盖，D/W 回看不足时 UNKNOWN；扩大 D/W 会重新计算可证明区间。evaluatedAt 是活动水位与时间标签锚点，不承诺所有可变表的历史回放。详见 `HANDOFF-support-s4-maintenance.md`。

部署必须先停止旧认证写入，不能新旧版本混跑后仍声称连续覆盖。若恢复旧备份或绕过捕获，要停写并重建 coverage 起点；不得伪造历史活跃。三份追加迁移是 `20260929_support_maintenance_s4.sql`、`20260929_support_message_s4.sql`、`20260929_support_attachment_s4.sql`。维护执行包含 `(assignment_id,executed_at)` 索引；重跑能给已有 S4 表补建，且不重置 coverage。

隔离环境入口 `scripts/support-redesign-s4-env.ps1 -Migrate` 只运行 S4 迁移。脚本保存自己的迁移开关，避免 dot-source S3 同名参数覆盖；不运行 S3 迁移、不重置既有夹具。图片测试值仅设置在隔离脚本；生产缺少 MIME/字节/像素/TTL 任一配置均 available=false。桶必须私有，不能只依赖随机对象键。

## 失败与恢复

| 条件 | 结果 | 后续 |
|---|---|---|
| 归属失效/越权 | 404；本人资格不足可能 403 | 刷新授权范围，不重发私有请求 |
| 同消息标识换载荷、版本或归属变化、停止时维护 | 409 | 回查原结果并刷新详情 |
| 缺必要配置、参数、坏图片 | 422 | 按能力/字段纠正 |
| MIME 不支持/文件过大 | 415/413 | 按能力选择图片 |
| 对象存储缺失/不可用 | 503，发送事务回滚 | 保留原发送标识，确认存储恢复后重试 |
| 附件过期/已发送后取消 | 409 | 过期重传；历史附件不按暂存 TTL 删除 |

应用错误仍沿用现有 ApiResult.code，不应只按 HTTP status 判断成功。READY 仅上传者可读，ATTACHED 仅客户本人、当前顾问、可审阅主管可读；转绑撤销旧顾问后续读取和 Range 请求。no-store 禁止缓存；Range 当前返回完整 200 字节响应，也逐次鉴权。硬退出发生在对象写入与 DB 提交之间可能留下孤儿对象，需运行维护核对存储清单；未实现通用对象存储事务。

## 验收、服务与后续边界

可复跑机器门为 `scripts/support-redesign-s4-runtime.mjs`，由 workflow 的 `backend-s4.plan.json` 绑定当前源码快照执行。它要求新鲜 Maven XML、全部无 skip、真实 HTTP/SQL/对象存储观察，而非仅退出零。覆盖维护循环与锁顺序、真实交互登录及 refresh 排除、工作台分页/unknown/规则、图片上传/发送/撤权/故障、客户端消息重试。双连接并发回归强制保留旧 RR 快照，再让另一请求真实提交，检查恢复响应与原消息一致。

恢复消息的 metadata、header、消息窗口、顾问、未读数均使用显式当前读；只给去重查询加锁不能刷新旧 RR 快照。回归另含暂停期间转绑及新顾问跟帖，以及真实 WS 与 HTTP 跨传输同标识恢复。

仓外证据目录：`C:/Users/jason/.codex/workflow-runs/customer-service-20260929/s4`。`runtime.json`、`integration.json` 记录当前快照；`scenario-evidence.json` 留真实安全响应样本；`message-replay-mysql-evidence.json` 留连接与重试结果；`migration-explain.log` 留二跑/索引查询证据；失败日志保留。私有 `runtime-identities.json` 保存超管、主管、G1/G2、可登录客户和未绑定客户；只在本机读取，不提交或打印令牌/密码。最终 `service.json` 给出精确 commit、jar、SHA256、PID、18129 和日志路径。

后端实景采用真实 HTTP 请求、独立新请求读取、SQL 持久性与私有存储取回；本阶段无页面变更，不能代替 S5/S6 浏览器与 S7 三仓验收。done-review 六维分别由事务读回、冻结契约逐项比对、失败/恢复行为、四条发送路径同类回归、身份/归属/时间/双端边界和新鲜运行记录证明。最终是否通过以独立审查及 workflow finish 后状态为准，不以本文声明为准。

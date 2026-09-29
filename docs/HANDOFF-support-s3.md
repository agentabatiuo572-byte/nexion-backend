# S3 后端交接

范围：客服正式归属、邀请注册继承、当前权限、旧入口适配、隔离迁移与 R08 工单私聊副本保护。基线 `cc5d96f928c82f081ce0d1e62c9874486634187a`，任务分支 `codex/cs-binding-20260929`。本文件不宣称 S4–S7、客户端或生产上线完成。

权威输入：admin 任务树 `docs/specs/support-redesign-20260929/` 冻结七文件（规格提交 `9d5cdfa3cd33f0c9bf7643c2cccc231dce5c54e2`）；主人后续工单边界确认记录在本树 `S3-R08-ticket-content.md`。

## 接口与前端契约

所有下列后台路径前缀为 `/api/admin/content`，沿用 `{code,message,data}` 返回；失败必须检查 code。

| 接口 | 权限与行为 |
|---|---|
| GET `/support-agents/rules` | M1 read + 有效主管；返回独立可空 D/M/W、继承模式与版本 |
| PUT `/support-agents/rules` | M1 write + 超管；CAS expectedVersion；Idempotency-Key、reason 必填 |
| GET `/support-agents/binding-pool` | M1 read + 主管；keyword/reason/pageNum/pageSize，真实总数 |
| GET `/support-agents/handover-customers` | M1 read + 主管；agentAdminId/unavailableOnly/pageNum/pageSize |
| POST `/support-agents/assignments/transfer` | M1 write + 主管；显式 customers 最多 100 项、全批 CAS |
| GET `/support-workbench/commands/{key}` | 当前 actor 范围；必须保留原命令对应 M1/M2/M3 read，重新检查客户权限 |

转绑请求示例（示意 ID，实际取当前响应）：

```json
{"targetAgentAdminId":2,"customers":[{"id":20,"expectedAssignmentId":100,"expectedVersion":1}],"reason":"客户提出更换顾问申请"}
```

未绑定客户 `expectedAssignmentId=null`、expectedVersion 取池版本；已有绑定取 assignment ID/version。reason 8–200 字符，key 8–128；同目标成功无新段，无效/过期快照整批拒绝。旧单条、批量、seat-assignment 路径适配同一正式转绑；旧 unassign、会话任意转派/公共池回退入口拒绝。

**数字 wire 约定**：上述命令的 ID/expectedVersion 是正 JSON 整数，最大 `9007199254740991`，拒绝字符串、小数、指数小数及溢出。前端先验证安全整数再发出，禁止对超范围 ID 盲目 Number 转换。D/M/W 是正整数或 null，L 是非负整数（LIMITED 才允许提供）；UNCONFIGURED/UNLIMITED 的 L 必须 null。规则不注入生产阈值，W 与 D 同时有值才校验 W≤D。

`SupportAssignmentChanged(customerId,previous,current)` 在转绑事务中同步发布。S4 的维护周期关闭、活动基准切换监听器应同步参与此事务；异常使归属和幂等执行一起回滚。事务提交后，SSE/WS 给旧顾问、新顾问及对应 App 连接发送 scope-invalidated（SSE 为命名事件），不带私聊正文；客户端刷新当前归属，不能根据历史消息 owner 选顾问。

客户锁先于客服账号、归属和会话锁；批量客户按 ID 排序。App 两个人工入口使用同一正式归属；忙碌/离线/容量不回退其他人。停用账号/profile 保留绑定，进入主管待交接视图；失去读取、发送和新注册继承资格。主管只审阅，不能代替顾问对客发送。

客户未绑定仍可留言；绑定后顾问看到既有历史。新客户消息尚未处理时不能关闭、归档或转工单；回复的 `replyTargets=[{conversationNo,throughMessageId}]` 明确覆盖同客户的旧段，旧 CLOSED 会话行不改写。消息真实 senderId 来自登录身份，不采信 operator 文案。

R08：工单新增 `sourceConversationNo`（DIRECT/真实会话号/null）和 `contentRestricted`。受限时标题、摘要、客户侧正文用提示替代；内部备注/system 与原协作权限保留，App 自己可读。有 M3 read 的当前顾问/主管可读私聊副本。查询匹配、列表、详情、内部动作返回和旧成功回放都按当前权限投影；M2 命令回查的受限结果仅返回成功状态，不泄漏旧 JSON。

## 迁移和恢复

1. 备份数据库并保留原始 `20260929_support_binding_s3_preflight.sql` 输出。
2. 执行 `20260929_support_binding_s3.sql`。重复 ACTIVE、失效顾问或孤儿先拒绝，绝不自动选赢家。保留邀请/团队/财务以及原消息作者；旧单绑定保留 ID 并补 MIGRATED 新段元数据，首次迁移回复游标只初始化一次。
3. 执行 `20260929_support_ticket_source_s3.sql`。热/归档审计共同核验真实工单及同客户来源；同一审计 ID 的冲突副本涉及的所有工单均排除回填，其他独立证据不能掩盖冲突。唯一可靠普通创建证据为 DIRECT，转换为会话号，坏数据/多源/缺证保持 null 受限。输出分类总数和待核验清单。

独立 MySQL scratch 验收：绑定迁移和预检 12 场景/93 断言；来源迁移 18 场景/128 断言。两者包括重复运行不变、异常不误改、正文作者保留；绑定还验证备份恢复。报告必须与当前迁移和预检 SHA 对应，由运行脚本核对。

这不是旧程序可直接回滚证明。旧程序没有当前权限和 R08 投影，不能连接已迁移库作为安全回退；恢复应使用迁移前备份及对应版本，重新核对完整性。

## 隔离环境和复跑

仅 `127.0.0.1:33329/cs_redesign`、Redis `16329`、S3 `19029`、应用 `18129`；不访问生产库。私有凭据在仓外 `C:/Users/jason/.codex/workflow-runs/customer-service-20260929/`，不要贴入输出或提交。`s3/runtime-identities.json` 含真实测试账号/token；`s3/service.json` 是交付时真实进程 PID、端口、SHA 和日志元信息。

```powershell
Set-Location D:/WORKS/PLAN/.wt/cs-binding-20260929-backend
. ./scripts/support-redesign-s3-env.ps1
& D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9/bin/mvn.cmd '-Dtest=SupportBindingRuntimeTest' test
```

该测试启动真实 Spring Boot/HTTP/WS/SSE 后退出；与持久服务不能同时占用 18129。环境脚本 `-Migrate` 只用于通过预检的新隔离基线；测试会刻意制造停用绑定，不能对这些运行中夹具强行重跑绑定迁移。

隔离基线额外缺项采用本仓已有迁移补齐：VietQR 静态建表、A4/K4/L1 事件 schema；全局搜索依赖的 K4/D2 与 D5 提现列仅补原迁移 DDL，不执行财务回填 DML。提取 SQL、第一次失败及重试输出均保存在 `s3/baseline-*-prerequisite*`。未关闭验证码或 onboarding 生产门：注册回滚测试种真实短效 OTP；App Socket 夹具先建立延期校准并通过真实条款确认，再由 HTTP 获取 ticket。

机器生命周期：计划 `docs/specs/support-redesign-20260929/backend-s3-r08.plan.json`；从已初始化的状态使用 `D:/WORKS/PLAN/scripts/codex-workflow/workflow.mjs run <plan> s3`、独立 review，再 start/run/review integration 和 finish。环境脚本先点入。结构化 runtime/integration 报告绑定本轮 runId 和源码快照，不能复用旧绿灯。

## 验收边界与 done-review

真实测试覆盖注册失败整笔回滚及同 OTP 重试、继承深度、批量 CAS/幂等、新旧 HTTP 数字输入、两个人工入口、未绑定历史、发送与转绑两种锁顺序、WS→HTTP 同 key、旧连接逐操作撤权、SSE 撤 session、模块读 grant 撤销、停用主管/顾问、跨旧段处理游标与 R08 全输出保护。相关单元回归覆盖原工单和知识库；机器报告列出实际测试结果，不以编译替代运行。

六维自检对应：真写后新请求/SQL读回；应有继承/权限/迁移清单逐项映射；失败重试和当前状态；HTTP/WS/SSE/旧路径/缓存/搜索同类入口；邀请财务不变量与 ADMIN/USER 两端；真实后端协议实景。S3 没有修改页面，浏览器可见界面、全语言布局和三仓端到端属于 S5/S6/S7，不能由本报告替代。

S4 继续负责维护周期、活动/统计/附件以及 HumanMessage 的 assignmentId/intent/clientMessageId/authorConfidence 元数据；本阶段只确保真实 senderId 和历史作者不伪改。运行日志仍可能出现既有 F4 缺配置和调度事件告警；不将本阶段通过表述为全后台运行健康。根目录 `verify_sandbox_retirement.ps1` 固定核验另一个 `nexion` 历史库，本隔离任务不能改它的库保护或拿其结果代替 S3。

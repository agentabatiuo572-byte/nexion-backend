# 后端接线合同（01–03 候选）

当前为隔离工作树未提交候选；SPEC01–03 已通过定向、隔离数据库与接口验收，证据见 IMPLEMENTATION-LOG 和 SPEC03-ACCEPTANCE。全量工程门及跨端实景仍分别验收。

## 认证请求

所有原生签发入口共用固定上下文：`X-NexGrid-Client-Surface: APP`、`X-NexGrid-Installation-Id`、`X-NexGrid-Execution-Supported: true|false`、`X-NexGrid-Login-Attempt-Id`、`X-NexGrid-Login-Recovery-Key`。H5继续 `X-Nexion-Refresh-Mode: cookie`，执行能力false；服务端以持久分组和会话链为后续授权来源。attempt16..80字符、proof43..128字符，均须在一次主动登录开始时生成并保留到结论明确；禁止未知结果换新attempt。

原尝试恢复：`POST /auth/users/login/attempts/{id}/recover`；body为installationId、executionSupported、recoveryProof、authMethod、authReference。仍需原surface/cookie模式。authReference按入口绑定：PASSWORD/REGISTRATION为countryCode:phone；OAUTH为provider:challengeNo；其他入口以Controller实际绑定为准。恢复被新App替代的原尝试返回原APP_REPLACED事实，无有效token。

响应新增sessionGroup、installationId、appSessionVersion、sessionChainId、loginAttemptId、sessionEndedReason、executionSupported、loginAttemptStatus。refresh仅继承已持久上下文。旧bearer401附data.sessionEndedReason；旧refresh已在重启后的隔离HTTP读回401/APP_REPLACED，data其余字段均null（SPEC01-LIVE-REFRESH-REASON-FINAL.json）。未知token和跨环境不可从reason响应得到账户信息。

## 手机换机

- `GET /api/onboarding/phone-installation/replacement-eligibility`：固定当前会话安装，资格、绑定/策略版本、服务端时间、历史完整性、特批；合并当前安装校准、发布条款及硬风控阻断；H5可读但canReplace=false。预检查不能取代激活事务的再次检查。
- `GET .../replacement-requests/current`：申请及授权概览；空记录和失败分开。
- `POST .../replacement-requests`：expectedBindingVersion/reason + Idempotency-Key，目标来自持久当前App，非客户端自由填写。
- `POST .../replacement-requests/{requestId}/support-link`：supportRef + Idempotency-Key，核验本人已有会话；不会创建或发送消息。
- `GET .../replacement-operations/{operationId}` 与 `GET .../replacement-operations/by-key?commandType=...&idempotencyKey=...`：仅本人USER原操作，H5只读；未找到不等于确定失败。
- 原activate增加expectedBindingVersion、expectedPolicyVersion、exceptionGrantId、replacementConfirmed。expectedBindingVersion显式null表示首次尚无绑定，缺字段不能替代null。设备/校准revision和原幂等键保留。成功恢复优先于后续资格变化，原originalResult与currentBinding分列。

## 历史部署约束

`manual_migrations/README-phone-replacement-history-cutover.md`规定真实新writer屏障T0、可执行手工过程及gap失效/T1；绝不从DDL时间伪造完整历史。首次绑定由业务事务记录；旧绑定保持未知直到独立证据或整个窗口得到覆盖。该手工流程未执行生产，不自动进入startup runner。

## SPEC-03 管理端合同（候选，验收记录见 SPEC03-ACCEPTANCE）

### E6 原子发布与审批

GET `/api/admin/devices/compute-config` 的 `phoneBinding`：`allowReplacement / minReplacementIntervalDays / replacementWindowDays / maxReplacementsInWindow / policyVersion / available`。不可用字段为null，不默认0。四项配置键均以 `E.compute.phoneBinding.` 开头；flag值为 `on/off`，其余值为十进制整数字符串。显式间隔0仍有效。

单参PATCH和整组PATCH携 `expectedPolicyVersion`（JSON整数，非小数/字符串，1..9007199254740991）、`reason`（8..200）、原Idempotency-Key。整组只收这四项，不可混电脑下载/映射配置。使用现有A2提案路径；禁止浏览器把A2未知结果当发布失败后自动重试。

A2 command为 `domain:E`、`op:e6_compute_config_batch`、`params:{values,expectedPolicyVersion}`；单参为 `op:e6_compute_config`、`params:{paramKey,value,expectedPolicyVersion}`。批参obj为按键排序后逗号连接，targets为各键的 `{domain:E,type:e6_compute_config,id:key}`，sourceDomain为E6。保留现有提案字段/确认与maker-checker分工。maker须具当前业务写权，涉及开关仍需flag权限；checker须当前 `platform_a2_operation_approve` AND `device_e6_read`，不要求成为直接写入人。未处于审批回放的发布返回 `A2_CONFIRMATION_REQUIRED`。

持久回执字段统一为 `operationId/commandType/status/submittedAt/completedAt/originalResult/currentResource/auditRef`。E6旧响应字段仍在，新增回执平铺。`PHONE_POLICY_PUBLISH`及`PHONE_POLICY_PARAM_PUBLISH`按执行审批人的原键查询；A2 approve的Idempotency-Key就是执行键。A2 ticket号与PPO操作号不同，originalResult含a2OperationId。原结果永久保留，currentResource可为更新版本。配置、PPO、审计和A4同事务。查询权限：E6write或flag，或A2approve AND E6read；每次查当前DB权限。

### C5 查询与处置

账户前缀 `/api/admin/users/profiles/{userId}`，所有入口复用现有账户数据范围与运行环境；冻结不阻断管理撤销/关闭/原回执，但新签发仍要求ACTIVE。

- GET `/phone-binding`：`eligibility/currentApp/sessions/pendingRequest/pendingOperations/executionPauseReason`；资格原样复用领域合同。session投影仅group、installation、appVersion、executionSupported、sessionCount、lastActiveAt，无凭据。GET不创建历史状态或绑定。
- GET `/phone-replacement-requests`：申请快照分页；GET `/phone-replacements?kind=SUCCESS|REQUEST|GRANT`：分类历史。分页 `pageNum>=1,pageSize=1..100`，响应 `{items,pageNum,pageSize,hasMore}`。无总数虚构。SUCCESS含对应auditRef；REQUEST/GRANT含操作审计关联 `operations`。GRANT补目标、理由、核实引用、operatorId、issueOperationId、consumedOperationId/At；状态和remainingUses仍由领域产生，不暴露原evidenceJson。
- POST `/phone-replacement-requests/{requestId}/verify`：`{reason,confirmed:true}`+原键，明确核实该申请已经LINKED的本人客服会话，返回 `{evidenceRef,requestId,supportRef,verifiedBy,verifiedAt}`。不会创建或发送客服消息，不从工单状态推断核实。原键重放返回原核实快照。
- POST `/phone-replacement-grants`：`{requestId,expectedBindingVersion,waiveCount,waiveInterval,reason,evidenceRef,confirmed:true}`+原键。evidenceRef必须是此管理员对该账号该申请的上述核实记录；目标始终从申请快照取。返回统一Receipt。
- POST `/phone-replacement-requests/{requestId}/close`与`/phone-replacement-grants/{grantId}/revoke`：`{reason,confirmed:true}`+原键，返回统一Receipt。

GET需`user_c5_read`；verify/issue/close需`user_c5_phone_replacement_exception_grant`；revoke需`user_c5_phone_replacement_exception_revoke`。现有角色没有独立安全主管/风控lead，因此仅默认授SUPER_ADMIN；普通RISK/SUPPORT未扩大。A1后续可明确配置细权。

### 原操作与冲突

GET `/api/admin/phone-replacement-operations/{operationId}`，或 `/by-idempotency-key?commandType=...&idempotencyKey=...`。C5类型为PHONE_GRANT_ISSUE/PHONE_REQUEST_CLOSE/PHONE_GRANT_REVOKE。按键只查当前操作者、运行环境与run；按号仍校验当前同等细权和账户范围。未知返回404 `OPERATION_RESULT_UNKNOWN`，不能视为失败或自动新发。

结构化冲突在 `data`：`{conflictType,reason,allowedActions,fieldErrors}`。POLICY_VERSION_CHANGED/BINDING_VERSION_CHANGED/GRANT_STATE_CHANGED/REQUEST_UNAVAILABLE/ACTIVE_GRANT_EXISTS/IDEMPOTENCY_MISMATCH均保留可读理由与允许动作；原键不同正文拒绝。未确定结果保留原键与请求，先查询再决定；前端不得自动发布旧策略、续期或重签授权。

当前HTTP证据均为localhost18119及任务33319隔离夹具，不代表线上TEST部署或真机多安装验收。PRD最终回写及临时目录清理仍等待SPEC07/08。

### SPEC06接线补充：核实结果未知的只读恢复

GET `/api/admin/users/profiles/{userId}/phone-replacement-verifications/by-idempotency-key?idempotencyKey=...` 返回原VerificationView；只允许当前GRANT细权、当前账户数据范围与原操作者/账号/env/run一致。缺失或跨账号键404 OPERATION_RESULT_UNKNOWN，不重做核实、不延长有效期，不因客服当前状态变化改旧证据。前置RBAC过滤器对该精确GET/HEAD要求GRANT，不额外误加普通查询read权。核实POST未知时仅查此GET，禁止自动POST。

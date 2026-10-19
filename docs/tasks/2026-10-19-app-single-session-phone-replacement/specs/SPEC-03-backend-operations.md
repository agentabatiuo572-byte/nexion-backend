# SPEC-03 后端 E6 策略、C5 处置与原命令查询 API

> 类型：实施交接规格，未实施、未运行产品测试。确认状态见 [HANDOFF](../HANDOFF.md)；[共同稿](../inputs/PRD/specs/FEAT-SESSION01-app-single-login-phone-replacement.md) §4为字段、接口、权限、事件与冲突唯一合同。

## 目标、依赖与所有权

- 依赖 [00](SPEC-00-baseline-contract.md) → [01](SPEC-01-backend-app-session.md) + [02](SPEC-02-backend-phone-replacement.md) 都通过后接入；不能以02单测先绿代替01鉴权/事务依赖。
- 03负责E6原子策略、C5查询/关闭/签发/撤销、权限数据范围、A2/A4和后台原命令恢复；02唯一拥有计次/申请/特批领域，03通过公开Facade/Application Service合同调用，不复制算法或直接跨域读写mapper。
- 提交给[06](SPEC-06-admin-e6-c5.md)：DTO、查询分页、错误/冲突、操作恢复、真实权限矩阵及脱敏API样例；联合验收归[07](SPEC-07-integration-release.md)。
- 禁止：后台代用户绑定/激活、清零计数/历史、通用白名单、无限特批、静默覆盖显式旧间隔、扩大普通客服授权、修改非本功能资金/风控政策或prod配置。

## 已核对路径与允许范围

路径相对 `nexion-backend/`；`J = src/main/java/ffdd/opsconsole/`、`T = src/test/java/ffdd/opsconsole/`，表中J/T前缀是路径拼接缩写。

| 范围 | 允许修改的真实路径 / 明确新增候选 |
|---|---|
| E6 | `Jdevice/web/OpsDeviceController.java`、`Jdevice/application/OpsDeviceService.java`、`Jdevice/domain/{ComputeConfigRegistry,ComputeConfigView,PlatformComputeConfigView}.java`、`Jdevice/dto/{ComputeConfigParamUpdateRequest,ComputeConfigBatchUpdateRequest,ComputeConfigParamResponse,ComputeConfigBatchResponse}.java`；公开投影必要时改 `Jdevice/web/PlatformComputeConfigController.java` |
| C5 | `Juser/web/OpsUserController.java`、`Juser/application/OpsUserService.java`；新增候选 `Juser/application/PhoneReplacementOperationsService.java`、`Juser/dto/`本功能命令/分页DTO、`Juser/web/PhoneReplacementOperationsController.java` |
| 命令持久恢复（新增候选） | `Juser/mapper/PhoneReplacementOperationMapper.java`、`Juser/infrastructure/PhoneReplacementOperationEntity.java`；新增共享边界候选 `Jcommon/boundary/PhoneReplacementOperationContract.java`，E6只能调用公开命令记录合同；不能跨域直接用user mapper |
| 权限/幂等/审计事件 | `Jshared/security/AdminRbacAuthorizationFilter.java`、`Jauth/application/AdminRbacBaselineInitializer.java`（仅确有初始化覆盖时）、`Jshared/idempotency/`、`Jshared/audit/`、`Jshared/outbox/` 必要最小接线；`Jplatform/application/{AuditReplayBusinessPermissionGuard,AuditReplayDispatcher}.java`仅若现有重放实际命中本动作 |
| 迁移/测试 | 新增候选 `scripts/migrations/20261019_phone_replacement_ops.sql`，登记 `scripts/apply_startup_schema_migrations.ps1`；下列对应 `Tdevice/`、`Tuser/`、`Tshared/`、`Tplatform/`测试，`README.md`必要入口说明 |

当前E6确在 `OpsDeviceController`：读取`device_e6_read`、单参写`device_e6_write/device_e6_flag_toggle`、批写`device_e6_write`；不是只改公开配置Controller。C5现有`user_c5_read`与各高敏写权分离。旧C5会话撤销授权不自动授予换机特批权限。

## 03.1 E6完整策略与版本原子发布

目标：现有GET/PATCH整组读写同一版本；依赖01/02，复用02策略校验。

1. GET `/api/admin/devices/compute-config`返回共同稿§4四项完整手机策略及版本；保留已有开关和显式间隔，新字段30/2与新配置间隔1天按合同初始化。旧环境差异供运营明确发布，不在迁移静默改成1。
2. PATCH `/.../compute-config/params`保留 `values/reason/operator`并加 `expectedPolicyVersion`；手机策略必须四项齐全。单字段路径也带预期版本，在锁内与其余当前值组成完整组校验，防止旁路和部分写入。
3. 窗口1..365、次数1..100、间隔既有整数域/0语义按共同稿；校验毫秒乘法与未来时刻可表示，非法返回 `INVALID_POLICY + fieldErrors`，不能发布会使账户永久溢出的值。
4. 锁/CAS保证一版一组；策略、版本、原命令、A2/A4同成同败。旧预览冲突须刷新再次确认；策略变更仅影响后续新命令，不撤销绑定/停止任务/清历史。
5. 操作者以服务端认证上下文为真值；参数更新既有A2确认/审批路径若命中，保持同一版本与重放鉴权，不私自削弱既有流程。

验收 `BE03-A1`：共同稿S13、S14、S15、S16、S26、S27；单参/批参/审批重放（若适用）同规则，无部分生效，非法大整数、并发版本、旧显式值留存、策略发布不影响已运行手机。

## 03.2 C5查询、申请关闭与特批命令适配

目标：让有权人员以服务端申请办理，依赖02领域Facade与01会话概览。

| 共同稿§4接口 | 输入 / 输出与应用层责任 |
|---|---|
| 新增 `GET /api/admin/users/profiles/{userId}/phone-binding` | 绑定、运行暂停原因、App/H5概览、普通资格、待确认操作、有效授权；历史未知如实输出，不回token/完整硬件数据 |
| 新增 `GET .../{userId}/phone-replacements` | 分页成功历史、失败申请、特批与审计关联，明确分类；失败不是成功计次，过滤与数据范围复用现有账户规则 |
| 新增 `GET .../{userId}/phone-replacement-requests` | 分页服务端申请快照/状态；目标只能选择本账户记录，不能接受自由填安装ID |
| 新增 `POST .../{userId}/phone-replacement-requests/{requestId}/close` | 理由确认+原幂等键；只关闭PENDING，已有授权须另走撤销；向02领域传可信账号/操作者上下文 |
| 新增 `POST .../{userId}/phone-replacement-grants` | 原样采用 `requestId/expectedBindingVersion/waiveCount/waiveInterval/reason/evidenceRef`+Idempotency-Key；校验已核实依据归属，再由02签发；不激活 |
| 新增 `POST .../{userId}/phone-replacement-grants/{grantId}/revoke` | 理由/确认/原幂等键；消费竞态回真实状态，重复撤销恢复原回执，不能假称已消费授权被撤销 |

1. `user_c5_read`及现有账户数据范围约束查询；普通客服只读其既有授权账户和关联工单，不因能查或撤会话就获得特批权。
2. 签发与关闭要求共同稿的 `user_c5_phone_replacement_exception_grant`，撤销要求 `user_c5_phone_replacement_exception_revoke`，只授予有相应权限的现安全主管/风控lead与超管；映射真实角色记录，不能仅按“RISK”宽泛角色授予所有人员。
3. 后台不能自造目标、申请/绑定version、核实依据；跨账户、数据范围外、证据未核实/不匹配、权限撤回及理由不足均拒绝且不写状态。服务层与HTTP入口都校验，防内部重放旁路。
4. 02负责唯一有效授权、两个24h、状态终态与计数；03只传可信上下文、映射共同稿冲突、审计结果，不能再定义第二个 `canReplace` 算法。

验收 `BE03-A2`：S21、S22、S23、S24、S26、S35、S36、S37、S38；额外覆盖全权限矩阵、跨账号/跨数据范围、关已授权申请、已有授权/无依据、撤销与消费竞争及无PII泄露。

## 03.3 原命令恢复与A2/A4闭环

目标：未知结果只能恢复原命令；依赖03.1–03.2接线与02持久关联。

1. 新增共同稿两条GET：`/api/admin/phone-replacement-operations/{operationId}`与`/api/admin/phone-replacement-operations/by-idempotency-key?commandType=...&idempotencyKey=...`；后者未有操作号时定位原操作，键不是凭证。
2. 回执严格采用共同稿§4的 `operationId/commandType/status/submittedAt/completedAt/originalResult/currentResource/auditRef`。成功事实冻结原版本/申请/授权，当前资源另列；PENDING无完成时刻，FAILED只用于确定失败；未找到或读取失败都不能猜测失败重写。
3. 当前仍有对应E6/C5权限且在数据范围内的原操作者或同等处置权限主管可查；原操作者失权后不能凭原键越权，普通客服不能查询高敏原命令。只读查询不隐含新执行。
4. 命令类型和请求内容绑定幂等键；24h缓存外通过持久关联恢复。原授权过期/消费/撤销后只返回原特批与当前状态；策略被后续发布覆盖也仍恢复原发布结果，不再发布旧策略。同键异输入返回 `IDEMPOTENCY_MISMATCH`。
5. 冲突与用户可读原因精确采用共同稿§4，返回允许的刷新/取消/重新申请动作；先恢复已成功命令，再决定是否需要检查当前资源，不把之后变化判为原命令失败。
6. A2审计不可变，A4只用共同稿唯一事件表；事务内事实/审计失败命令不得成功。事件不含密码/验证码/token，特批终态去重由02统一；K1/K4只消费事实，不建第二套次数源。

验收 `BE03-A3`：S17、S19、S20、S22、S23、S24、S38、S39；缓存到期、响应丢失、后续资源变化、失权/越权查询、未找到、事件/审计故障均有明确证据。

## 检查、独立验收与交接

| 验收ID | 必须扩展/新增的测试（新增均为候选） |
|---|---|
| BE03-A1 | 扩展 `Tdevice/application/OpsDeviceServiceTest.java`、`PhoneCalibrationPublicationReplayTest.java`；新增 `Tdevice/application/PhoneReplacementPolicyPublicationTest.java`：整组CAS、单参旁路、溢出、显式值；扩展 `Tplatform/application/StartupSchemaMigrationContractTest.java`登记迁移顺序 |
| BE03-A2 | 扩展 `Tuser/web/OpsUserControllerTest.java`、`Tuser/application/OpsUserServiceTest.java`、`Tshared/security/AdminRbacAuthorizationFilterTest.java`；新增 `Tuser/application/PhoneReplacementOperationsServiceTest.java`、`Tuser/web/PhoneReplacementOperationsAuthorizationTest.java`：权限矩阵/数据范围/申请快照/领域调用一次 |
| BE03-A3 | 新增 `Tuser/application/PhoneReplacementOperationRecoveryTest.java`、`Tuser/mapper/PhoneReplacementOperationsMySqlTest.java`：持久原命令、真实事务、并发/审计失败回滚；必要扩展 `Tshared/idempotency/AdminIdempotencyServiceTest.java` 与 `Tplatform/application/AuditReplayBusinessPermissionGuardTest.java`，不降低旧缓存恢复保障 |

- 已有命令（根单模块，自备JDK/Maven）：`mvn -B "-Dtest=OpsDeviceServiceTest,PhoneCalibrationPublicationReplayTest,OpsUserServiceTest,OpsUserControllerTest,AdminRbacAuthorizationFilterTest,AdminIdempotencyServiceTest,AuditReplayBusinessPermissionGuardTest,StartupSchemaMigrationContractTest" test`。
- 新入口约定：测试类落地后 `mvn -B "-Dtest=PhoneReplacementPolicyPublicationTest,PhoneReplacementOperationsServiceTest,PhoneReplacementOperationsAuthorizationTest,PhoneReplacementOperationRecoveryTest,PhoneReplacementOperationsMySqlTest" test`。报告列实际测试数、跳过数与未覆盖项，禁止全跳过当通过。
- MySQL新测试与01/02统一 `NEXION_TEST_DB_SERVER_URL / NEXION_TEST_DB_USERNAME / NEXION_TEST_DB_PASSWORD`，只接受经核对的隔离dev实例，每次自建schema并记录所有权/清理；不连prod、不继承运行服务数据库、不把密码写入命令或证据。迁移按根README canonical installer，先 `-WhatIf` 再隔离执行并验证重复执行。
- 独立验收按 `BE03-A1..A3` 从共同稿§4/§5回源，分别核对参数原子性、权限/越权、恢复与审计；只报P0/P1/P2，主线复现P0/P1。缺真实MySQL或未关P0/P1不得交“通过”。
- 失败/恢复：保留原操作号/键/快照；命令未知只读查回，不清幂等记录/再签授权/重发旧策略。审计故障修复后复用原操作恢复；迁移失败保留数据库与报告，由前向修复处理，不能回删成功事实。
- 交接06完整DTO、冲突/未知/无权样例、权限映射、操作查询证明及独立审查结果；交07联调记录。共同稿S01–S41由01/02/03的后端责任与04/05/06的客户端责任合验，不能用本步API通过替代页面/真机验收。

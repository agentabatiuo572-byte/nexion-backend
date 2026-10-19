# SPEC-02 后端换机计次、激活、申请与一次性特批领域

> 类型：实施交接规格，未实施、未运行产品测试。确认状态见 [HANDOFF](../HANDOFF.md)；本文件只拆执行步骤，政策以[共同稿](../inputs/PRD/specs/FEAT-SESSION01-app-single-login-phone-replacement.md)为唯一来源。

## 目标、依赖与边界

- 依赖 [00](SPEC-00-baseline-contract.md)；可与[01](SPEC-01-backend-app-session.md)并行设计，但共享mapper/service及启动迁移登记串行。02最终激活鉴权集成须消费01已验收的当前App事务合同。
- 本步拥有共同稿§3、§4申请/特批状态与计次事实的唯一领域实现；[03](SPEC-03-backend-operations.md)只做E6/C5授权、应用编排与API，不再实现一套计数/授权状态机。
- 交付：真实成功事件账本、历史完整性、资格计算、激活/原操作恢复、申请/特批命令领域服务、跨域公开接口及MySQL原子性证据；所有字段、范围、事件与冲突类型引用共同稿§3–§4。
- 不可触碰：认证规则（交01）、后台权限政策/页面（交03/06）、生产DB、硬件证明、Janus、非手机资产、收益/试用新人规则、旧历史迁移；不从 `changedAt` 或绑定version伪造历史。

## 回源与允许路径

路径相对 `nexion-backend/`；`J = src/main/java/ffdd/opsconsole/`、`T = src/test/java/ffdd/opsconsole/`，如 `Jonboarding` 表示拼接后的真实目录。

| 范围 | 允许修改 / 明确新增候选 |
|---|---|
| 已有激活/绑定 | `Jonboarding/application/{OnboardingCalibrationService,PhoneInstallationService}.java`、`Jonboarding/mapper/{OnboardingCalibrationMapper,PhoneInstallationMapper}.java`、`Jonboarding/web/{OnboardingCalibrationController,PhoneInstallationController}.java` |
| 唯一领域（新增候选） | `Jonboarding/application/{PhoneReplacementPolicy,PhoneReplacementService,PhoneReplacementRequestService,PhoneReplacementGrantService}.java`；`Jonboarding/mapper/PhoneReplacementMapper.java`；`Jonboarding/dto/`、`Jonboarding/domain/` 内本功能DTO/状态/事实对象 |
| 跨域只读/命令合同（新增候选） | `Jcommon/boundary/PhoneReplacementContract.java`、`Jonboarding/facade/PhoneReplacementFacade.java`；03调用公开合同，不直接依赖本域mapper/entity |
| 依赖接线 | `Jdevice/application/AppTaskAssignmentService.java`、`Jdevice/mapper/AppTaskAssignmentMapper.java` 仅事务竞态所需；`Jshared/outbox/EventOutboxService.java`、`Jshared/idempotency/` 只在已有能力无法承载时作最小扩展并回归 |
| 迁移/测试 | 新增候选 `scripts/migrations/20261019_phone_replacement_domain.sql`；`scripts/apply_startup_schema_migrations.ps1`；下列 `Tonboarding/` 测试；`README.md` 迁移/测试说明 |

基线：已有 `validateReplacement` 使用安装变化或既有非空硬件标识变化；`savePhoneBinding`与任务取消已有事务路径；`initialOnly`目前用于首次暂缓，不能据此宣称并发首次激活已满足新合同。`docs/phone-calibration-handoff.md` 保留普通安装身份与任务计算证明，退役的是额外原生硬件证明。

## 02.1 事实账本、完整性与普通资格

目标：先建立真实事实和纯规则计算，依赖00。

1. 成功替换事件只追加，操作号唯一；保存共同稿§3要求的账号、旧/新绑定、成功时刻、策略版本、特批关联。A4投递去重不能成为重复计数通道。
2. 按可核验历史审计去重导入并保留来源；不能从当前绑定时间/行version补造次数。记录 `historyComplete / historyCompleteFrom`，只有证据补齐或整个查询窗口已被可靠采集覆盖才可完整计数；窗口扩大可再次暴露未知区间。
3. 时钟注入，严格按 `(t-W×24h,t]` 统计；上限/窗口/间隔与0语义依共同稿§4。首次不计次但开始间隔，A→B→A记2；特批成功仍计次；同绑定恢复、空硬件补齐、校准/暂缓不计且不改changedAt。
4. `remaining=max(0,N-used)`；超额后求足够事件移出窗口的时刻，再与间隔取较晚者。未知不回0或不限；禁换/封禁等无确定时间返回null，客户端改时钟无效。
5. 策略和历史不可用时普通替换关闭，账户查询与原绑定恢复保留。未知次数只有次数豁免可救济；未知changedAt另需间隔豁免，仍保留未知标识。

验收 `BE02-A1`：S11、S13、S14、S15、S16、S25、S26、S27；纯规则固定时间正反边界及历史导入重跑证据。

## 02.2 激活提交、容量与原回执

目标：一次成功才换绑定、计数、消费；依赖02.1，接01已验收的会话/任务事务接口。

1. 扩展既有activate输入，精确采用共同稿§3：`expectedRevision / expectedBindingVersion / expectedPolicyVersion / exceptionGrantId?`及原幂等键；替换必须明确确认。首次预期绑定version为null表示仍无绑定，锁内已有绑定即冲突，不得迟到覆盖。
2. 在账号锁及固定锁顺序内依次辨认原成功操作、校验新执行资格、绑定/策略/校准版本及未豁免阻断。已成功原回执优先恢复，后续过期/换绑不能把成功改失败；同键异输入拒绝。
3. 原子写绑定、真实成功事实、特批消费、必要任务停止、命令回执及必须事件/审计。任一写失败整笔回滚；拒绝/校准失败/用户取消不计次不消费。
4. 复用原手机激活槽；无可复用槽时按现有容量/试用预留规则，不写死槽位。新手机仍受条款、风控、校准和运行条件限制；同绑定恢复只解除换机暂停，不恢复已取消任务。
5. 新增资格/操作查询和申请接口见下表；操作号未收到时原账号+原幂等键查询或重放原activate恢复，禁止生成新键。H5可读本人结果但不得重放写命令。
6. 保留既有H5受控首次 `defer(initialOnly=true, expectedRevision=0)` 与持久GET读回，仅真实无结果/无任何手机且用户显式选择可写；校准/绑定已存在、fleet读取失败或账号变化均拒绝。它只保存初始DEFERRED，不授执行权、不计次，不可让新App鉴权误挡，也不可作为停用旧手机旁路，具体同[05](SPEC-05-h5-isolation-sync.md) H5-03。

| 接口输入 | 输出与交接约束（共同稿§3原字段） |
|---|---|
| 新增 `GET /api/onboarding/phone-installation/replacement-eligibility` | 绑定、策略版本、serverNow、次数/完整性、原因、nextAllowedAt、有效特批；H5 canReplace=false |
| 扩展 `POST /api/onboarding/calibrate/activate` | 原回执与当前绑定区分，缺替换确认的旧客户端不能旁路；首次与同绑定兼容语义须明示 |
| 新增 `GET /api/onboarding/phone-installation/replacement-operations/{operationId}` | 本账号原操作的处理中/成功/确定失败，不执行新换机；找不到不当确定失败 |
| 保留 `GET /api/onboarding/calibrate/binding-status` | 仍只回答是否已有绑定，不替代完整资格 |

验收 `BE02-A2`：S09、S10、S11、S17、S18、S19、S20、S25、S28、S29、S30、S31、S35、S40（服务端事实）、S41；使用真实事务、并发首绑与响应丢失恢复证明。

## 02.3 协助申请与两个独立24小时

目标：提供C5可信目标来源，依赖02.1及01当前App上下文。

1. 新增 `POST .../phone-installation/replacement-requests`，输入为预期绑定版本/原因/幂等键；账号、目标安装、实际绑定version由当前App和服务端取得，原因8–200字；无原绑定/H5/错账号/错安装拒绝。
2. 同账号同目标同version未到期PENDING重复返回原记录，不续期；已有有效特批返回来源申请及授权状态。创建与客服关联分别记录结果，关联失败可重试/复制申请号，不能伪称已送达或已授权。
3. `GET .../replacement-requests/current` 返回申请、关联结果及授权当前状态；H5仅账户只读；空与读取失败分开；申请号非凭证，响应不泄露完整硬件标识。
4. 申请状态遵共同稿§3：待处理24小时内才可首次签发，绑定变化为STALE；关闭/过期/失效不可复活，每申请至多一张特批。GRANTED按特批自己的有效期显示，原申请24小时到达不能提前终止已签发授权。

验收 `BE02-A3`：S22、S24、S35、S36、S37、S38；申请23h签发后授权仍有自己的24h；关页不撤回、不延期，客服关联失败不影响已建立申请事实。

## 02.4 一次性特批状态与并发裁决

目标：向03提供领域签发/关闭/撤销/查询合同，依赖02.3；03负责API权限与核实编排。

1. 目标固定取申请，核实依据由03校验后传入；至少一个豁免，每账号最多一张有效授权。签发与申请关联同事务，24h/1次固定，不替用户激活。
2. 仅豁免次数和/或间隔；全局禁换、封禁/风险暂停、校准失败、非当前App等硬阻断不豁免；部分豁免仍被其他限制挡住且不消费。
3. `ACTIVE → CONSUMED / REVOKED / EXPIRED / STALE`按共同稿锁内裁定；`now < expiresAt`才允许首次消费；绑定version任何变化使旧授权失效。消费/撤销/到期竞态最多一项权威终态。
4. 24h幂等缓存之外仍持久关联原命令、原申请、原特批；原签发重放无论已消费/撤销/失效/过期均恢复原特批，不能重新签发。新的授权必须新申请、新操作、新确认。
5. 成功事件与消费同事务；生命周期终态按grant+终态唯一去重，读取时物化到期不重复发事件。拒绝事件不是计数事实；原请求重放不重复记录拒绝/成功。

验收 `BE02-A4`：S17、S19、S20、S21、S22、S23、S24、S26、S36、S38；02领域测试与03权限/API测试都必需，不能互相替代。

## 测试、独立验收、失败恢复与移交

| 验收ID | 必须新增/扩展测试（新增均为候选文件） |
|---|---|
| BE02-A1 | 新增 `Tonboarding/application/PhoneReplacementPolicyTest.java`：边界、超额、多阻断、未知时间/历史、窗口改动；扩展 `OnboardingPhoneReplacementMapperContractTest.java`；新增 `PhoneReplacementMigrationContractTest.java`：重复迁移/导入、无清零或覆盖显式策略 |
| BE02-A2 | 扩展 `Tonboarding/application/OnboardingCalibrationServiceTest.java`、`OnboardingPhoneActivationContractTest.java`、`Tonboarding/web/OnboardingCalibrationControllerTest.java`；新增 `Tonboarding/application/PhoneReplacementActivationTest.java`：版本/确认/恢复/容量、H5首次defer正反例；新增 `Tonboarding/mapper/PhoneReplacementAtomicityMySqlTest.java`：真实事务、失败注入、首绑和消费竞争 |
| BE02-A3/A4 | 新增 `Tonboarding/application/{PhoneReplacementRequestServiceTest,PhoneReplacementGrantServiceTest}.java`、`Tonboarding/web/PhoneReplacementControllerTest.java`；对双24h、跨账号/H5拒绝、一次性、缓存过期重放及生命周期去重逐条断言；MySQL测试覆盖申请唯一和有效授权唯一 |

- 已有命令（仓根）：`mvn -B "-Dtest=OnboardingCalibrationServiceTest,OnboardingPhoneActivationContractTest,OnboardingPhoneReplacementMapperContractTest,PhoneInstallationServiceTest,OnboardingCalibrationControllerTest,PhoneInstallationControllerTest" test`。
- 新入口约定：新增类落地后 `mvn -B "-Dtest=PhoneReplacementPolicyTest,PhoneReplacementMigrationContractTest,PhoneReplacementActivationTest,PhoneReplacementRequestServiceTest,PhoneReplacementGrantServiceTest,PhoneReplacementControllerTest,PhoneReplacementAtomicityMySqlTest" test`；保存测试数、跳过数和 `target/surefire-reports/`。
- 真MySQL只用隔离dev及自建schema；新测试统一约定 `NEXION_TEST_DB_SERVER_URL / NEXION_TEST_DB_USERNAME / NEXION_TEST_DB_PASSWORD`，校验非prod及schema所有权，凭据不入命令/日志，缺依赖记未验证。根README的canonical installer必须登记新迁移；baseline+seed不等于完整库，迁移重复跑需证明不重置事实/授权。
- 已回源 `scripts/verify-phone-calibration.ps1` 当前为Maven套件入口，`verify-phone-calibration-mysql.ps1`为绑定/暂停迁移验证，均含机器路径；同事使用上述mvn及隔离测试。旧硬件证明计划已退役，保留challenge/verify 410与任务计算证明回归，不能误恢复硬件门。
- 独立验收者逐 `BE02-A1..A4` 对照共同稿，重点复核历史未知、首绑/消费原子性、两个24h和原回执。只报P0/P1/P2，主线回源/复现P0/P1；静态SQL断言不能替代MySQL并发证据。
- 失败/恢复：记录原键/操作号、输入快照、锁顺序、schema/进程与失败报告；确认原运行结束再重试。业务未知先查原命令；不清历史、不重置授权、不绕过未知，不改prod；回退入口保留新事实，修复用前向迁移。
- 移交03领域合同、状态/冲突样例、迁移顺序、验收证据；移交04/05只读/写接口与未知恢复语义，端到端由07验证。共同稿S01–S08、S12、S32–S34的会话主责在01；S27/S39后台命令主责在03，其余相关边界均按上列ID覆盖。

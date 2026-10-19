# SPEC-01 后端 App 单点会话与手机执行接管

> 类型：实施交接规格，未实施、未运行产品测试。产品确认以 [HANDOFF](../HANDOFF.md) 的 2026-10-19 确认为准；本文件不重新裁决政策。

## 目标、依赖与合同

- 依赖 [SPEC-00](SPEC-00-baseline-contract.md) 冻结仓库、输入、接口映射及迁移顺序；01 与 02 可并行设计，不能同时写共享代码。
- 唯一业务合同：[共同稿](../inputs/PRD/specs/FEAT-SESSION01-app-single-login-phone-replacement.md) §1、§2、§3禁止动作、§4事件表、§5、§6；字段、状态与事件名引用原文，不另造同义标准。
- 目标：所有启用的认证完成路径统一产生唯一 App 逻辑会话；成功提交、旧访问/刷新撤销、必要的旧手机停止同成同败；普通 H5 独立。
- 交付给 02/03/04/05：会话分组与版本、登录尝试恢复、会话结束原因、当前安装鉴权、事务内手机停止/恢复契约及测试证据；03 需同时等 01、02 通过。

## 已回源基线与允许范围

以下路径相对 `nexion-backend/`；`J = src/main/java/ffdd/opsconsole/`、`T = src/test/java/ffdd/opsconsole/`，实施前仍核对 00 冻结提交。

| 范围 | 允许修改的真实路径 / 明确新增候选 |
|---|---|
| 认证完成与DTO | `Jauth/application/AppUserAuthService.java`、`AppUserOAuthService.java`、`AppSessionSurface.java`；`Jauth/web/AppUserAuthController.java`；`Jauth/dto/` 中本功能请求/响应；`Jauth/application/AppUserSecurityService.java` 仅安全撤销回归所需接线 |
| 会话存储/鉴权 | `Jshared/security/{JwtAuthenticationFilter,JwtTokenProvider}.java`、`Jshared/security/mapper/AuthSessionMapper.java`、`Jshared/security/infrastructure/UserSessionEntity.java`；新增候选 `Jauth/application/AppSingleSessionService.java`、`Jauth/mapper/AppLoginAttemptMapper.java`、`Jauth/infrastructure/AppLoginAttemptEntity.java` |
| 手机执行接线（与02串行） | `Jonboarding/application/{PhoneInstallationService,OnboardingCalibrationService}.java`、`Jonboarding/mapper/{PhoneInstallationMapper,OnboardingCalibrationMapper}.java`、`Jonboarding/web/{PhoneInstallationController,OnboardingCalibrationController}.java`；`Jdevice/web/AppTaskAssignmentController.java`、`Jdevice/application/AppTaskAssignmentService.java`、`Jdevice/mapper/AppTaskAssignmentMapper.java` |
| 跨域合同 | 新增候选 `Jcommon/boundary/PhoneExecutionSessionContract.java`；沿既有 `common/boundary` 边界约定，仅经公开 Facade/Application Service 事务合同连接，禁止 auth 直接读写 onboarding/device Mapper 或 Entity |
| 迁移/检查 | 新增候选 `scripts/migrations/20261019_app_single_session.sql`；登记 `scripts/apply_startup_schema_migrations.ps1`；本节所列对应 `Tauth/`、`Tshared/security/`、`Tonboarding/`、`Tdevice/` 测试；必要说明写 `README.md` |

表中 `Jauth` 等是 `J` 与 `auth/` 的拼接缩写，不是新增顶层目录。共享文件由实施负责人明确排队；01 先交稳定接口，02 再合并，不互相覆盖。

- 当前 `AppSessionSurface` 注释明确只是客户端展示标签，`X-NexGrid-Client-Surface` 单独不构成可信授权；认证入口及签发上下文须固定业务分组，refresh 只能继承。
- 当前 `OnboardingCalibrationService.phoneLogin` 才 claim 执行权并停止旧手机；必须前移到认证提交事务，不能以登录后补调维持单点承诺。
- `docs/phone-calibration-handoff.md` 已于 2026-10-03 退役 APK 白名单/密钥证明；`challenge`、`verify` 固定410。不得恢复证明门、增加验证码或新安全TTL。
- 不可触碰：运营后台会话60分钟策略、既有OTP/2FA/OAuth验证强度、密码找回与全端撤销政策、非手机资产/任务/账单/奖励、Janus、prod环境或秘密、其他仓；不改历史迁移。

## 01.1 固定分组、唯一顺序与尝试恢复

目标：先落库约束及统一认证完成服务，再接所有入口；依赖 00。

1. 登记密码、OTP完成、2FA完成、实际启用OAuth完成、注册初始签发的调用清单；不能只修 `/auth/users/login`。保留注册只创建新账号初始会话。
2. 按共同稿§2持久化分组、安装、账号单调版本、会话链与稳定 `loginAttemptId`。账号行/单点记录作为同账号串行化边界；明确与 phone/task 的统一锁顺序并加死锁回归。
3. 原尝试重复恢复原结果：身份验证尝试/账号/安装/请求内容绑定；同键异输入拒绝；未认证者持有尝试号不能读取令牌。结果未知先查询/安全重放同一尝试，禁止新尝试抢占。
4. B成功后又被C替代，恢复B只给原事实与重新登录动作，不再发有效B权限；认证未完成、失败或取消均不推进版本、不停旧端。
5. 迁移按可证实来源归组；UNKNOWN要求重新认证，不能默认H5。存量多个App保留最近明确登录成功者并建立唯一服务端顺序；绑定不匹配暂停手机；已知H5不参与竞争。迁移重复执行不再次接管。

验收 `BE01-A1`：共同稿 S01、S03、S07、S12、S33、S34；证据含入口清单、迁移前后脱敏记录、两次并发提交唯一最终版本、伪造标签/未知来源拒绝及登录尝试重放。

## 01.2 原子接管与权限即时失效

目标：认证成功事务内完成旧权撤销及手机停止；依赖01.1；在共享文件改动前与02确认写入窗口。

1. 新App与原绑定是同一可执行安装时，只替代旧App会话；不因认证取消在途任务，不计次、不改 `changedAt`。恢复只解除换机暂停，仍等待现有心跳/运行条件，运营/风控暂停不解除。
2. 不同安装或不支持执行的App成功登录时：撤销旧App整条访问/刷新后继链，终止旧手机未完成任务，保留旧绑定；新安装无执行权。条款/换机资格/校准不足不能延迟停机，也不回滚已成功登录。
3. 复用现有手机任务取消与权威完成裁决；领取、心跳、完成/结算入口均在事务中复验当前App、安装、绑定与任务状态，不能仅依赖HTTP过滤器之前的一次检查。停止和完成竞争仅一方提交有效结果；不补发被停止的未完成任务奖励。
4. 故障注入：新会话写入、旧链撤销、手机停止、A4事实任一失败均回滚接管；无“双有效”窗口、无“登录失败但手机已停”。历史已确认收益及非手机任务保持。
5. `/api/onboarding/phone-installation/login` 改为最新App会话的幂等核验/同绑定恢复；旧会话、H5及晚到请求不能 claim 执行权，不能用它补做登录应有的停机。

验收 `BE01-A2`：S01、S05、S08、S10、S13、S18、S28、S29、S30、S34；记录数据库事务前后、旧token实请求、手机/非手机任务及收益差异，mock单测不算原子性证明。

## 01.3 refresh、退出、H5与安全动作

目标：没有旁路复活且保留原安全动作；依赖01.1–01.2。

1. `/auth/users/refresh` 维持同一组/安装/版本/逻辑链，不接管；轮换宽限也须匹配当前App版本，被撤销链无后继恢复入口。JWT与服务端会话一致性失败关闭。
2. `/auth/users/logout` 只撤销本链；旧App迟到退出不得停较新App手机。当前App显式退出/换账号按现有手机停止合同处理；H5当前退出不影响App。
3. 普通H5登录、浏览、多标签刷新、退出不写App版本、绑定、运行与次数；H5不能通过body/header伪造分组取得校准、激活、申请、运行权限。
4. 显式全端退出、密码找回、安全重置、后台封禁/全端撤销仍影响原合同规定会话；不得以H5隔离为由豁免。
5. 保留既有 `docs/app-h5-sync.md` 的H5受控首次DEFERRED：只在当前账号/浏览器安装结果真实404、服务端无任何手机时显式保存并GET读回，不校准、不改绑定、不授执行权。拆开普通安装ID格式校验与App执行鉴权，不能把共用 `PhoneInstallationService.require` 一刀切成App必需而误挡该入口；已有手机/校准结果不得借defer停用。

验收 `BE01-A3`：S02、S03、S04、S06、S18、S32、S33、S35；输入输出逐字段遵共同稿§2，敏感令牌不进入日志、事件或交接样例。

## 测试、独立验收与下一步

| 验收ID | 必须扩展 / 新增的测试与证据 |
|---|---|
| BE01-A1 | 扩展 `Tauth/application/{AppUserAuthServiceTest,AppUserOAuthServiceTest,AppSessionSurfaceTest}.java`、`Tauth/web/AppUserAuthControllerSecurityTest.java`；新增候选 `Tauth/application/AppSingleSessionServiceTest.java`：全部入口、并发序列、尝试恢复/信息越权、UNKNOWN迁移 |
| BE01-A2 | 扩展 `Tonboarding/application/PhoneInstallationServiceTest.java`、`Tonboarding/mapper/PhoneLoginRuntimeMySqlTest.java`、`Tdevice/web/AppTaskAssignmentControllerTest.java`、`Tdevice/application/AppTaskAssignmentServiceTest.java`；新增候选 `Tauth/application/AppSingleSessionMySqlIntegrationTest.java`：真实Spring事务、两连接竞争、各写点失败回滚、任务停止/完成竞争 |
| BE01-A3 | 扩展 `Tshared/security/{JwtAuthenticationFilterTest,AuthSessionGraceConcurrencyMySqlTest}.java`、`Tauth/application/AppUserSecurityServiceTest.java`、`Tonboarding/web/OnboardingCalibrationControllerTest.java`；覆盖旧轮换宽限、迟到退出、H5多会话、H5受控首次defer及已绑定拒绝、全端安全动作、换账号隔离 |

- 已有可运行命令（仓根，同事自备JDK17+/Maven）：`mvn -B "-Dtest=AppUserAuthServiceTest,AppUserOAuthServiceTest,AppSessionSurfaceTest,JwtAuthenticationFilterTest,PhoneInstallationServiceTest,AppTaskAssignmentControllerTest,AppUserSecurityServiceTest" test`。通过只表示实际运行用例通过。
- 新入口约定（实现上述新增类后）：`mvn -B "-Dtest=AppSingleSessionServiceTest,AppSingleSessionMySqlIntegrationTest" test`；提交中给出真实测试数/跳过数与报告路径，禁止不存在类/全跳过报通过。
- 新MySQL测试使用隔离dev专属实例及每场次自建schema，约定 `NEXION_TEST_DB_SERVER_URL / USERNAME / PASSWORD`；验证目标非prod、schema归属与清理记录，不继承运行服务的 `NEXION_DB_*`。缺环境标未验证。旧session测试环境开关各异，先读测试类，不能仅设置新变量便声称旧测试已运行。
- 迁移走根README约定：baseline+seed之后仍须 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/apply_startup_schema_migrations.ps1 -MySql <本机mysql路径> -WhatIf` 审查，再在隔离dev执行；直接 `mvn spring-boot:run` 不自动迁移。不复制原README的D盘工具路径。
- 独立验收者按 `BE01-A1..A3` 从共同稿回源，重看并发/原子性及全部认证入口；只报P0/P1/P2。主线复验P0/P1；无真实MySQL证据或有未关P0/P1不得交“通过”。
- 失败/恢复：保存原尝试、操作、失败事务及快照；先确认原检查已结束再恢复，禁止清表/重置版本/用新尝试掩盖未知。回退启用入口时保留新数据与历史事实，不自动复活被撤销会话。
- 交接清单：最终DTO/路由映射、锁顺序、迁移及回滚限制、逐验收ID报告与遗留。01通过后交[02](SPEC-02-backend-phone-replacement.md)、[03](SPEC-03-backend-operations.md)、[04](SPEC-04-app-interactions.md)、[05](SPEC-05-h5-isolation-sync.md)，端到端结果归[07](SPEC-07-integration-release.md)。

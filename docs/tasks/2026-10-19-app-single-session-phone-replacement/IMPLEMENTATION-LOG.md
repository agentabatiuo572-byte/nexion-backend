# 实施记录

2026-10-19，SPEC-01/02 进行中；业务验收尚未通过。

证据目录：`D:/workspace/audit-artifacts/app-single-session-phone-replacement-20261019`。

- 恢复执行后核实原子 Agent 已不在运行，按原互斥写范围重建认证、换机领域和独立容量复核分工；未覆盖正式目录的无关工作。
- 隔离 MySQL 原进程已停止；复用原任务 data 目录恢复 33319，当前库仍365表，隔离级别REPEATABLE-READ。进程读回见 `runtime/mysql-resumed-process.json`，不操作3306业务库。
- `SPEC01-02-COMPILE-1.log`：`mvn -B -DskipTests compile` exit0。此证据只证明当时生产源码编译，不是业务通过。
- `SPEC01-02-TARGETED-1.log`：首轮定向测试在testCompile阶段exit1；4处旧JWT测试构造参数未同步、1处MySQL测试泛型断言产生2条编译错误。业务测试未开始，不记测试通过。修复后重跑。
- 独立容量复核发现RR旧快照P2：账号锁前普通读取可导致等待后看到active=2、真实active=3，上限3却放行到4。原复现与schema保留，见 `SPEC02-CAPACITY-INDEPENDENT-REVIEW.md`。修复使用复用现有口径的当前读查询，包含嵌套订单、推广预留和已兑现排除子查询；直接JDBC修复读回见 `SPEC02-CAPACITY-CURRENT-READ-PROBE.log`，最终Maven/主线复核仍待完成。
- 既有激活入口已接唯一换机领域：策略锁→账号→当前会话→绑定；恢复原操作先于当前校准/绑定/策略/特批判断。设备、绑定、成功事实、授权消费和回执在同一事务。新增申请/资格/原操作查询与既有客服会话归属核验入口。
- 请求解析区分缺少expectedBindingVersion与显式null首绑，忽略客户端伪造的presence标记。只有成功替换才计次；确定拒绝无设备写时可保留拒绝回执，其余写失败回滚。
- 激活前调用既有发布条款检查的公共边界，避免直接HTTP绕过前端条款顺序；不要求首绑时onboarding已经完成。旧手机风控/运营暂停不因换机或特批解除。
- 新依赖改为构造注入，遵守仓库架构门。认证、领域、容量、接口以及旧用例的最终门禁仍在执行，尚无本任务提交或推送。

当前限制：用户仅一部Android；SPEC-07双机真实接管仍未验证，SPEC-08正式PRD最终回写和临时目录清理尚不具备条件。

## 本轮继续验证

- Redis16319恢复后鉴权PING=PONG；MySQL33319仍为原任务隔离实例。
- canonical runner重复执行 exit0，见 `runtime/session-migrations-apply-2-confirmed.log`；读回开关off、间隔1、窗口30、次数2、版本1，10个phone事件注册项存在，尚无会话尝试/换机事实/特批。此为隔离库配置，无生产改动。
- 两次迁移启动尝试在ShouldProcess交互入口失败，未执行迁移；随后用已授权隔离目标显式 `-Confirm:$false` 成功。第一条宿主输出的LASTEXITCODE=0不是迁移成功证据，正式结果以上述confirmed日志为准。
- 01复核修复锁后原登录尝试恢复、审计旧安装当前读；401/socket明确本人会话结束原因；到期与安全撤销分开。退役密码重置HTTP入口维持401。
- 容量修复主线交叉复核见 `SPEC02-CAPACITY-ROOT-REVIEW.md`。
- targeted3正在运行。激活全链路真实sink集成、历史可靠采集起点手工流程与既有session grace真实并发回归继续补证；01/02仍未标通过。

- targeted3 exit0：365 tests /0 failure /0 error /0 skipped。
- targeted4 exit1：138 tests，5 errors均新fixture缺真实NOT NULL字段；真实session/A2/A4与31项grace并发、63项任务服务等通过。
- targeted5 exit0：只重跑受影响激活与领域14项，0 failure/error/skipped；包含真实激活4项、领域10项（手工历史cutover/gap、迁移保留与导入去重）。
- 隔离API已启动18119；新增任务独立MinIO19019/19020，健康200。空库启动补齐原始VietQR/晋升流水CREATE、MFA与tradein列、F4mutex和K4事件注册。development economy bootstrap显式关闭；未导入真实资金数据。后续将来源/失败记录补入runtime/DEV-BASELINE.md。
- 仅任务隔离库创建 UVEL SESSION SYNTHETIC 账号，复用既有loopback-only development Passkey入口进行真实HTTP认证（不代表真实设备）。H5不踢App、B接管旧bearer401 APP_REPLACED、旧logout不影响B、H5伪造APP头不能申请、refresh固定上下文已实际读回。首次恢复探针缺surface被401拒绝；补原surface后原attempt返回APP_REPLACED且无token，见SPEC01-LIVE-RECOVERY-CORRECTED.json。
- 管理员通过本隔离空库正常MFA初次配置并登录，support安装API proof为NOT_NEEDED/UNLIMITED/v1（无规则写入）。未使用线上管理员会话或MFA旁路。
- 真实旧refresh发现reason遗漏并已修候选；targeted6的8个新例、HTTP权限和架构门通过，旧auth2处data=null断言需按reason-only合同适配，仍保留无token/身份泄露断言。01/02尚未标通过，03未开始。

## 2026-10-20 继续

- affected7 exit1：237 tests /4 failures /94 errors /0 skipped。新refresh 8、单点真实MySQL复合1、旧auth83、注销7、worker单元11、用户repository28分别通过，不能把整轮记绿。
- 其中9 errors因旧事件fixture另读NEXION_TEST_DB_URL，而执行环境只设置新server URL；它尝试默认3306但使用隔离任务密码，全部Access denied，未获认证或执行写入。后续统一显式加载runtime/load-test-env.ps1，将新旧变量都指定任务33319。之前“不操作3306”的描述指已执行数据库变更，不能误读为从未尝试连接。
- Lease 4项因fixture缺现行paused_at/paused_seconds；worker84项均因真实SupportDeviceFundAdapter新增mapper未注册；SupportUserSearch 1项为低位UID的至少8位补零要求。保留原断言补齐夹具后重跑，不改生产资金逻辑。
- 回源共同稿canReplace定义，发现App资格预检查遗漏当前安装校准和条款。已复用激活现有条款与校准验证逻辑补齐阻断信息；异常读取继续失败关闭，H5仍只读。新增单元与真实事务断言，等待实际运行与独立复核。
- affected8 exit1：51 tests /0 failures /1 error /0 skipped。校准30、App资格6、sandbox4、真实激活原4项、Lease4和用户搜索2分别通过；worker单个冒烟因fixture ALTER引入UUID默认表达式触发MySQL1674，继续修正fixture，未扩大成84次相同失败。
- 隔离API18119按最新候选重启成功（backend-start-6.log，PID14152）；原A refresh实请求401，data仅APP_REPLACED、其余非空字段为空列表，见SPEC01-LIVE-REFRESH-REASON-FINAL.json。未重新登录或生成新attempt替代原请求。
- affected9 exit1：38 tests /2 failures /1 error /0 skipped。架构23、事件结算5、worker冒烟1、新激活完整5全部通过；3个失败结果均密码重置夹具缺nx_config_item，补真实配置表与上限5后进入affected10。eligibility的真实校准/条款状态切换和只读零写已通过。
- 独立领域审查发现两处P2，修复与回归进行中：先展示EXPIRED的授权在后来换绑时被物化为STALE；目标账号FROZEN时共用ACTIVE锁门错误阻断管理员撤销及原成功命令恢复。保持App执行门和新签发限制，仅修终态时间顺序及管理员撤销/恢复路径。01/02未标通过，03仍未开始。
- 补存量会话迁移真实夹具：旧APP/H5展示名不视为可信来源、UNKNOWN需重认证、撤销时间不改、新固定上下文/顺序/原attempt在重复迁移中保留。待新增测试实际运行，不以空表重复迁移充当该项证明。
- affected10 exit0：88 tests /0 failures /0 errors /0 skipped，包含PasswordReset事件4项及完整worker真实事务84项，所有本轮owned schema删除读回。上述旧fixture失败均已在相应最小重跑中关闭。
- 两处领域P2候选修复已落地：recordActivationSuccess先按旧绑定物化到期，再处理新绑定；管理员存在性锁仅用于admin facade，App原ACTIVE锁不变，新签发在原回执恢复后再要求ACTIVE。新增领域真实回归至12项；final11正验证这些受影响代码、独立迁移1项与架构门。
- final11 exit0：85 tests /0 failures /0 errors /0 skipped（架构23、存量迁移1、激活5、校准30、策略7、领域单元7、领域真实MySQL12）。独立复核修复候选已回读，主线真实测试确认两P2关闭。SPEC01/02本步后端验收通过，详见SPEC01-02-ACCEPTANCE.md；允许开始SPEC03，不等于各端/发布最终验收。

## 2026-10-20 SPEC-03 implementation (in progress)

- Added versioned E6 policy publisher with exclusive policy-row lock, full-group validation through the SPEC-02 contract, durable original receipts, and mandatory A2/A4. Existing explicit interval 0 survives. New policy defaults remain 30/2/1. Phone-only batches are a separate group; mixed groups are rejected before writing.
- Preserved A2 approval and exact version preconditions through replay. Fractional/string versions are rejected. Structured phone conflicts survive the A2 idempotency wrapper and include readable reasons/actions. No automatic retry writes.
- Added C5 current permission and account scope gates on reads, mutations, and recovery; two fine permissions default only to the existing SUPER_ADMIN role. The canonical RISK user_* seed now excludes these permissions because no distinct qualified lead role exists in the baseline. Existing explicit A1 grants remain possible.
- Added explicit verification of the request's owned existing support conversation, persisted as an immutable attestation. A ticket status is never treated as verification. Source identity is read from the request, never a free-form target installation. Verification, issuance, revocation, and closing retain immutable audit facts.
- Added scoped persistent recovery by operation/key. C5 pagination uses a bounded 100+1 internal read. Current App/H5 projection shares session idle/account/fixed-context gates and contains no tokens/session chains.
- Independent reviews found and drove fixes for fractional version truncation, missing conflict detail, A2 swallowing detail, cross-run original-key lookup, idle-expired session projection, and A4 canonical property names. Reports retain their original findings; final recheck pending.
- SPEC03-COMPILE-1 passed main compile; COMPILE-2 passed test compile. TARGETED-3: 236 tests, zero failures/errors/skips. TARGETED-4: 424 tests, zero failures/errors/skips, including 8 real MySQL policy publication tests with owned-schema cleanup. No full-suite claim.
- Canonical startup migration WhatIf resolved only 127.0.0.1:33319/nexion; apply and repeat both succeeded. SPEC03-TARGETED-5 and live HTTP verification are still pending; SPEC-03 remains RUNNING and frontend dependency steps remain pending.

### SPEC03 continued verification and live findings

- TARGETED5 and TARGETED6 failed during test compilation (nested AuditOperationTicket import); no test success claimed for those runs. Corrected the import, TARGETED7 passed 109 tests with zero failures/errors/skips; TARGETED8 passed 27 tests with zero failures/errors/skips. COMPILE9 passed.
- Live API restart invalidated prior admin sessions normally; authenticated again through the normal local password/MFA endpoints. LIVE-READS-2 exposed a C5 readOnly transaction versus existing qualification FOR UPDATE conflict (500). LIVE-READS-3 verifies the adjusted overview returns 200, safe App/H5 projections, 100-row request page, and unknown command 404.
- Independent review then confirmed E6 checker rights were incorrectly treated as writer rights, and the C5 admin account adapter rejected frozen accounts before reaching the domain's permitted revoke/close/recovery path. Fixed current-DB approval requiring A2 approve AND exact E6 read; receipt recovery accepts this combination. Direct writes and proposal permissions remain guarded. Admin scope checks now exclude deleted/cross-audience accounts without requiring ACTIVE; the domain still rejects new issuance/activation for frozen users.
- C5 overview follows policy/account/binding before device/risk locks and uses the locked binding value, avoiding an older RR snapshot. Other list/history/recovery reads remain readOnly. No history-state row is inserted by overview. Added independent tests for both findings and read ordering; TARGETED10 is in progress.
- Isolated startup baseline lacked the pre-existing device_e6_flag_toggle definition, so the first live proposal correctly failed permission checks. Added an insert-if-absent dependency in the new migration, without reviving deleted permissions or broadening ordinary support roles. Applied only localhost:33319 task DB; a synthetic maker then submitted the real A2 proposal. A synthetic checker has only E6 read, A2 read and A2 approve. Both complete normal local MFA.
- C5 live fixture is clearly synthetic (UID900003, localhost only): a binding and two owned-support-linked requests, without sending any support message or claiming physical-device activation. Its purpose is HTTP integration and no-side-effect read verification. Final live results, cleanup and SPEC03 acceptance remain pending.

- TARGETED10 exit0:177/0/0/0；TARGETED11 exit0:186/0/0/0；TARGETED12 exit0:59/0/0/0。最新22类相关报告合计686项均通过，见SPEC03-TEST-MANIFEST；不是全仓测试。
- 真实E6 maker/checker审批与恢复原值通过，原结果/currentResource区分读回；C5真实HTTP16项及最新历史4项通过。两临时管理员停用，授权撤销、账号冻结，原审计保留；详情SPEC03-ACCEPTANCE.md。SPEC03现标PASSED，允许04/06实现，最终PRD/清理仍未开始。

## 2026-10-20 前端联调启动限制

- Root核对PC候选环境：`NEXION_BACKEND_URL` 是代理实际读取的配置；目标为任务隔离API `http://127.0.0.1:18119`。计划开发端口仅绑定127.0.0.1:18120。
- 两次通过exec_command/PowerShell Start-Process Hidden启动PC Next dev均在CreateProcess前被自动审批拒绝，原文 `rejected: blocked by policy`，未提供更具体原因；没有进程或18120监听。第二次已移除未使用模式变量并设独立distDir，仍拒绝。未换另一执行通道规避限制。
- PC/App代码和非受限验证继续；页面实景验收不能因此标通过。待启动限制解除再执行真实UI。
- ADB沿既有获准路径和专用5038现场检查，仅发现一台SM_A5760。HBuilderX CLI存在但应用当前未打开。未切换手机账号、未替换原调试包、未改手机网络设置。

- SPEC06接线反馈核实POST未知缺只读恢复，已增加精确账号/原键GET，当前GRANT权与scope每次校验。独立审发现filter追加read误门，精确修复且测试。TARGETED13 exit0 178/0/0/0，真实HTTP原快照/缺失/跨账号3项通过。SPEC03重新通过，04/06继续；最新22类691项均通过。

## 2026-10-20 后端全量工程门启动

冻结 SPEC01–03 后端代码上启动根项目 `mvn test`，使用任务隔离 MySQL/Redis 环境，日志 `BACKEND-FULL-14.log`。本轮 App/PC 同时继续定向实现，但不并行启动其他 full 构建。全量结果待回读；先前 691 项定向测试不得表述为全量通过。PC 本地页面服务启动仍受自动审批策略拒绝，真实候选页面验收未完成。

### 全量检查中发现的回归（仍在运行）

AppUserRegistrationServiceTest 的旧二参/Surface mock 未适配新的 AppLoginContext 签发，导致 8 个 NPE 和 2 个并发包装失败；独立 reviewer 已确认同一根因，反向 never 验证也须更新到真实调用，不能只改成功断言。SPEC01 重开到回归补齐，01旧定向通过证据仍保留。额外启用的历史客服数据库夹具存在缺少 qualification 表/新的 scope mock，以及信任页 live-data 测试缺发布数据；保留失败原文，未把缺环境或夹具当成功。App 独立审查确认 Android 登录尝试存储与 session 命名空间冲突、丢失 2FA challenge 无恢复出口及请求重试丢踢出原因，均已交原 owner 修复。

### SPEC06 独立审查回源 SPEC03

E6 未知提案仅扫描 A2 overview 的最近 500 条审计，在记录落窗后无法从原 key 恢复 ticket。补精确、按原操作者及当前业务域权限隔离的只读查询；不重新 POST 提案。SPEC03 重开，原发布/C5验收证据保留，新接口单独验证后再冻结。

### 根全量 BACKEND-FULL-14 结果与修复归因

`mvn test` exit 1，1261 个类、11206 项，31 failures / 41 errors / 470 skipped；09:40，结果清单 `BACKEND-FULL-14-MANIFEST.json`。这不是全量通过。

本任务相关：注册签发 mock 未适配新上下文；A4 参数目录仍只补旧两个默认项；领域生命周期事件用字符串拼接，Outbox源码覆盖扫描得到不存在的前缀。分别修真实调用桩、四参目录与默认1/30/2、显式expired/stale事件名，不改现有事件含义。

环境/可移植性：两个旧SQL源合同测试按LF精确解析，但Windows checkout为CRLF，改读取时规范行尾，保留全部原断言；dev启动环境的SERVER_PORT=18119误传给内嵌测试服务，load-test-env现移除SERVER_PORT和SPRING_PROFILES_ACTIVE让测试自己选profile/临时端口。其余显式开启的旧MySQL夹具缺新表/字段、基线发布数据或硬编码3306，分别保留失败报告；未接触3306数据，硬编码连接因任务隔离密码不匹配失败。当前工作继续修本任务缺陷并准备再验，不将这些旧夹具判为已通过。


## 2026-10-20 01:54 后端完整门与隔离数据库复验

BACKEND-TARGETED-15：338项，3失败均为A5新增两默认目录项后的旧计数断言；逐项核实并更新，未删除私密配置过滤断言。注册31项、精确A2恢复含归档/550噪声/跨maker、换机原子性、Outbox覆盖及两CRLF合同均已通过。Outbox真实缺项已补12个record-only事件，动作/状态比较先存boolean，避免源码扫描将条件操作数当事件；保留FULL14原失败，归因见BACKEND-FULL-14-TRIAGE.md。

BACKEND-FULL-16：正常无运行时端口/profile及数据库opt-in的根项目mvn test，exit0，11125项、0 failures、0 errors、710 conditional skipped。BACKEND-MYSQL-18：明确任务独占33319 MySQL的8类42项，0失败/错误/跳过；覆盖会话迁移接管、激活容量与事务、策略发布、换机原子性、运行态、审计归档恢复。FULL14中额外打开的其他历史数据库夹具并未被此结果宣称已修好。

本地API新进程804/18119，backend-start-17，普通MFA登录后只读验证A2精确恢复：缺键及异maker键404 unknown、空键422；未重新POST提案。后端源码冻结清单BACKEND-CANDIDATE-18-SOURCE-MANIFEST.json。01/03重开项定向闭合，整体真实跨端验收仍未完成。

PC生产build exit0，66项定向与补入既有E6gear26项通过。App认证原三项及captcha重放独立回读闭合；新发现HTTP200包装5xx清原命令键，交owner最小修复。H5第一次仅41个本任务源码同步、无删除，后因该发现回退任务生成的36个tracked和6个untracked副本到干净目标；保留源树b4523368、差异和.trash，再按修复后候选重做plan/apply。没有正式提交/推送，没有PRD提前完成声明或临时任务目录删除。

## 2026-10-20 App FULL1 and H5 recovery checkpoint

- App FULL1 completed:23 steps,20 pass,3 fail; no cached/known-red/scoped-skip/not-run steps. Contract-registry and integration prelude exposed stale harness assertions; legacy suite exposed locale date formatting, unsupported16px and missing endpoint citations. Production H5 build and fixture runtime passed, but this is not full acceptance. Logs and reports preserved in SPEC05-APP-FULL-1-REPORTS.
- App owner is correcting contracts while retaining business assertions and fixing the actual three-language registration recovery copy/date/style. Complete standalone Vitest exposed additional dependent fixtures and missing explicit prototype root; these are being inspected rather than hiding failures.
- H5 sync2 content check passed2480 files, then all42 task-owned paths were rolled back to clean base for repaired-source resync. Both superseded patches and trash backups retained.
- Isolated live HTTP cookie recovery passed4 checks: H5 preserves App, original H5 attempt restores HttpOnly cookie, App takeover preserves H5, recovered H5 refresh remains isolated. Script-owned sessions were logged out. Evidence:SPEC05-LIVE-COOKIE-RECOVERY-1.json; no browser/native claim.
- Automatic approval review denied candidate PC/H5 development-server launches before execution with only blocked-by-policy reason. Manual launch scripts prepared under runtime and a user question remains pending; no alternative launch used to bypass the rejection. Current listener check found API18119 only.
- SPEC04/05/06 remain running, SPEC07 pending and SPEC08 not eligible. One-device native limitation unchanged; no commits/pushes or final PRD cleanup.


## 2026-10-20 后续候选门禁与单机实景

- App 最终 55 文件、1667 源文件冻结；staged tree `e2adc43546c0085eedc3891026616f4bfbdd90fc`。FULL2 23 PASS，0 cached/fail/known-red/scoped-skip/not-run，实际报告归档 `SPEC05-APP-FULL-2-REPORTS`。FULL1 失败证据保留。注册完成后本地收尾失败的恢复缺口、跨账号旧状态、原尝试恢复等已修复并独立复核。
- H5 第三次同步 55 writes / 0 deletes，2480 文件核对通过，staged tree `de948756883fb9adb4ec4d99b568b903ccb80866`。H5 FULL1 运行中，结果见证据目录；当前还不能当作全绿。
- 后端 production 源未改；4 个 MySQL 测试类增补业务组合。MYSQL19 20/0/0/0、MYSQL20 3/0/0/0、MYSQL22 90/0/0/0。MYSQL21 仅缺 AppSessionSurface import 的编译失败，修 import 后完整重跑22，失败日志保留。22覆盖真实 app.complete/HMAC/手机授权/事务资金回滚，但预锁安排顺序不是已观测 SQL 锁等待，CAS=0是注入，不宣称真实双nonce竞争。
- 单三星原生开发资源连接隔离服务，实测 H5 登录保留 App、API APP peer 接管后的真实踢出页、冷启不抢回、主动重登版本升3且旧peer401/H5peer200。首次硬件未匹配不激活，可显式DEFERRED。中文深色/英文深色/越南语浅色面板做了实际检查；不是完整主题语言尺寸矩阵，也不是多台原生验收。
- 所有本次隔离原生与peer会话正常退出；原测试资源已恢复，app-service.js哈希与原备份一致；本次adb reverse已移除。证据 `SPEC04-NATIVE-ACCEPTANCE-3.md` / `SPEC04-NATIVE-RESTORE-3.json`；此恢复不代表签名APK安装测试。
- 41项证据准备稿已更新到 `SPEC07-COVERAGE-DRAFT.md`。只有一部真机、缺匹配校准规则、PC/H5候选服务启动被自动审核拒绝且尚待人工启动，导致剩余实景未验证。自动审核仅给出“blocked by policy”，不绕过。
- 无本功能提交/推送；正式PRD最终回写、任务文档删除仍不具备条件。原工作区WIP保持。

- 门禁细项补查：App FULL2 / H5 FULL1旧handoff-pointer默认寻址不存在的 `../admin-ops`，在PASS行内声明跨仓半边SKIP但汇总未计入。已用实际PC候选的 `HANDOFF_ADMIN_FILE` 分别补跑，exit0、20个U-x双向集合相等；原日志未改写。之后最终clean-commit full需保留此环境变量。

- H5 FULL1 exit0，23PASS/0其他，treeMoved=false，commitCandidate tree de948756883fb9adb4ec4d99b568b903ccb80866；完整日志已归档SPEC05-H5-FULL-1-REPORTS。PC FULL1随后开始，串行使用重型资源。

- PC FULL1 100齿全跑，90PASS10FAIL；保留失败记录。环境修复：当前隔离父目录新增 junction `nexion-backend -> backend`，设置 `PROMOTION_OPENAPI` 指向候选真实OpenAPI，`NEXION_JANUS_ROOT` 指向已有正式Janus目录；不复制伪文件，不豁免断言。
- PC改动：新operations代理沿用仓内强制改密guard写法；phone client接公共outcome谓词，异常302保键并新增回归；包装业务5xx、非法envelope、缺回执、401/403保键仍保持。A2只补strict envelope例外的明确说明。49项定向验证通过，独立review确认未降低业务保护。
- PC FULL2 99PASS1FAIL，剩余为Cookie namespace测试枚举和loader未纳入新route。仅增加真实phone routes模块及新业务route矩阵两行，原断言不删；该文件8项通过。FULL3 exit0，100PASS/0FAIL/SKIP/SCOPED-SKIP/NOT-RUN，treeMoved=false，已归档REPORTS。24任务文件清单在SPEC06-PC-CANDIDATE3-SHA256.json；早先Next构建自动加入的tsconfig类型目录另列，不作为业务提交内容，最终提交前清除并做最终门禁。
- 最终现场复查18120/5175仍未监听，用户尚未完成手工候选服务启动；未重试绕过自动审核。实际C5/E6/A2/H5业务页面、多台Android安装、匹配硬件后的激活和任务验收仍缺。SPEC07继续pending，SPEC08 not_eligible，不推送未验收功能、不删除任务包。

## 2026-10-20 浏览器实景24与回执修复

- 用户已启动18120/5175，之前服务启动阻点解除。正常本地admin密码/TOTP登录；H5因dev CORS缺5175返回403，仅补任务本地允许源并重启Java PID32020，随后正常密码登录成功；未改生产认证或MFA。
- C5真实读取空绑定、未知历史、终态禁用、分类历史、原回执/当前状态；独立复核发现旧receipt在新查询/Verification后可能改标当前账号，最小修复并补4条生产handler行为回归，合计27/27。root非作者实景确认成功receipt→404清空、成功close→Verification清空。
- PC FULL4 100PASS但Next生成private-media类型include导致treeMoved=true；保留日志，不算稳定候选。FULL5 100PASS、0FAIL/SKIP/SCOPED-SKIP/NOT-RUN、treeMoved=false，24任务文件哈希相符。SPEC06-PC-CANDIDATE4-SHA256.json及FULL5-REPORTS固定候选。
- 新合成UI24两申请：页面关闭CLOSE成功、核实ISSUE成功；客服关联来自隔离合成记录，无消息发送。一次特批固定目标/绑定v1/次数间隔双豁免已准备，尚待浏览器规则要求的当次用户确认，未签发。账号900003原冻结态需在本轮处置后恢复。
- E6实测窗口0拦截、30→31完整四参预览及取消；重载仍禁换/1/30/2/v3。无发布变更。不存在原发布结果明确待核实，不自动重发。
- H5手机面板三语×明暗6组390×844截图、刷新/第二标签、正常退出均实测；第二标签刷新回引导登录。恢复中文深色、撤除视口模拟并关闭H5标签。数据库900004活跃APP/H5均0、绑定/事件0、DEFERRED、App版本3。不能据手机区通过宣称下方失败的设备列表或资金/订单通过。
- 独立8图审查无新增P0/P1/P2，回执P2按范围闭环；HEAD已有C5新查询回车选旧结果的P2单列未改。详细报告SPEC05-06-BROWSER-24-ACCEPTANCE.md及SPEC06-PC-UI-24-INDEPENDENT-REVIEW.md。
- 当前SPEC04/05/06仍running、SPEC07 pending。缺多原生安装/匹配硬件真实执行、剩余浏览器权限/业务矩阵；SPEC08尚不满足，不提前正式PRD回写或任务清理；无本功能提交/推送。
- 等待特批当次确认期间已先恢复合成900003为FROZEN，读回本轮有效grant=0、CLOSE=CLOSED、ISSUE=PENDING；证据SPEC06-PC-UI24-AWAITING-RESTORED.tsv。继续时勿重跑插入夹具；确认后仅启用该合成账号、重新读取同一申请，再签发/撤销/恢复。

## 2026-10-20 特批当次确认后的实景25

- 用户已明确允许U00900003 / PRR-SPEC06-UI24-ISSUE本地24小时双豁免签发并立即撤销。核实同一未过期申请后仅临时启用合成夹具账号，浏览器签发成功、DB读回、浏览器立即撤销，再恢复FROZEN。
- grant PRG-cb3c38d0017243c299b36377e242ff95；签发/撤销各1成功操作，相隔23497ms。最终REVOKED、未消费、有效grant0、换机事件0、原绑定v1/changed_at未改。
- 页面查询原签发操作：原成功有效/剩余1与当前已撤销/剩余0分列，不续期或重发。报告SPEC06-PC-UI25-GRANT-REVOCATION.md及TSV/DOM/截图。仅合成申请→签发→撤销子路径，不代替原生消费、并发和权限矩阵。
- 无源代码变更，沿用PC FULL5稳定候选。SPEC07仍pending，SPEC08仍未满足，未提交/推送或提前清理。

## 2026-10-20 后端组合验收26 / A2补测27 / 全量28

- ROOT 串行运行 `BACKEND-TARGETED-26.log`：总计 113 项、1 failure、0 error、0 skip，exit1；原失败记录保留，不能将本轮整体标通过。失败仅 `OpsAuditCenterServiceTest.phonePolicyCanonicalSummarySurvivesStorageReadbackAndLaterPolicyChanges` 的 JsonNode 断言。
- 同轮新增真实 MySQL 两类五项全部通过：`AppSessionAcceptanceMySqlTest` 2/0/0/0（S07 认证失败、待2FA、错误OTP及未完成挑战不改变旧会话/任务；S08 接管先提交、条款/校准门禁连续阻止执行但不恢复旧机）；`PhoneReplacementSequenceMySqlTest` 3/0/0/0（S14 首绑零次和间隔时间序列；S20 必需审计失败回滚后过期、同键首次执行不能消费；S23 两不同消费命令并发只提交一次授权/绑定/换机事实）。使用任务独占 schema；不是原生/HTTP实景。S07 的放弃是未提交最终挑战，不是界面取消；S23 未声称观测到 SQL 锁等待。
- A2 断言修正后的 `BACKEND-TARGETED-27.log` / `.exit`：`OpsAuditCenterServiceTest` 48/0/0/0、exit0。此为该单元测试类补测，不是48项MySQL或实际审批页面验收。
- 本代理执行 `mvn test -Dsupport.test.reportsDirectory=<外部证据>/BACKEND-FULL-28-REPORTS`，沿 FULL16 默认无数据库 opt-in 环境。`BACKEND-FULL-28.log` / `-MANIFEST.json`：11146 tests、0 failure、0 error、721 skipped、1265 XML suites，exit0，2m29s。源码/脚本/pom共3842文件前后摘要一致，未改源代码、数据库夹具或服务。721跳过不算通过，不能与26/27重叠累加成独立覆盖总数。
- FULL28 完整证据：`BACKEND-FULL-28-SUMMARY.md`、`-RUN.json`、`-SOURCE-BEFORE.json` / `-SOURCE-AFTER.json`、`-REPORTS/`；日志 SHA256 `770107d508301362f982f86f2d46562c9850c930a7023555487a36831406df85`。外部目录均为 `D:/workspace/audit-artifacts/app-single-session-phone-replacement-20261019`。
- 本次仅更新后端验证记录。SPEC07/08 状态及 S07/S08/S14/S20/S23 整体验收状态继续 pending；未改变原生/PC/H5实景状态，未据此宣布可发布、正式PRD回写或清理。

## 2026-10-20 PC 校验失败退出与候选6 FULL7

- PC 实景26发现管理员失效后reload清掉旧session，但失败门没有退出入口。`console-shell.tsx` error分支无条件复用原安全logout，并展示退出错误；没有绕过服务端撤销。实际组件/handler行为测试覆盖空session、确认前保持关闭、未知退出不假成功。定向30/30；旧lifecycle断言经核对HEAD原本已是identityChanged或accessChanged，精确补断言而未改auth store。root实际复验失效账号可以退出回登录；证据SPEC06-PC-BOOTSTRAP-EXIT-26.md/log与UI26-EXIT-FIX DOM。
- PC FULL6：候选5，96PASS/4FAIL/0SKIP/0SCOPED-SKIP/0NOT-RUN，exit1，203083ms，treeMoved=false；原失败记录保留。三门失败来自遗漏PROMOTION_OPENAPI/NEXION_JANUS_ROOT；另一门是头像测试固定30轮setImmediate不足以等待真实异步哈希完成。补齐前轮环境后，同源树仅重跑失败门70/70与ops-actions通过，不倒改FULL6结论。
- 经root授权，只将头像该测试3处固定轮数改最长5秒等待实际按钮状态；全部文件字节、原命令号、clientUploadId、文件名/MIME、错误图片拒绝断言保留，真实WebCrypto未替换。该类15/15通过，进入候选6；未改avatar产品实现。
- PC FULL7：100PASS/0FAIL/0SKIP/0SCOPED-SKIP/0NOT-RUN，exit0，133145ms，treeMoved=false，工作树指纹`3cd324bdaa7cf229d31841ca07ba301b1d217f367a37fc1dee2a7bac5946c0cf`；28任务文件前后SHA256一致。typecheck与production build均在100门内；使用完整外部资料路径和独立`.next-session-phone-build`。
- generated tsconfig共88项include，SHA256`a955043ffad5e7e026ab63e223913d71ad126ffdbf7032e7e93bd430752ca3fe`前后未变；相对HEAD的历史生成差异单列，不人工改写受验树。候选仍dirty=true、未提交，不称干净提交/合并门通过。
- 完整证据位于外部任务目录：SPEC06-PC-CANDIDATE5-SHA256.json、SPEC06-PC-FULL-6-REPORT.md、SPEC06-PC-FULL6-FAILED-TARGETED-1.log、SPEC06-PC-FULL6-OPS-TARGETED-1.log、SPEC06-PC-AVATAR-WAIT-26.log、SPEC06-PC-CANDIDATE6-SHA256.json、SPEC06-PC-FULL-7-REPORT.md/log/REPORTS。FULL6失败与FULL7成功按不同源树保留。
- 本补记只更新PC门禁，不代替剩余E6/A2/受限角色等实际UI验收；SPEC06仍running，SPEC07/08 pending及cleanup not_eligible保持。未运行Maven、操作设备/浏览器、提交/推送或正式PRD最终回写。

## 2026-10-20 单机/权限实景29–31与当前门禁

- 逐项归档外部 SPEC07-BOUNDARY-EVIDENCE-30：S15/S23/S33/S41 按原规格纯服务边界通过，其余37项部分完成，不把已测片段扩大为完整原生链。详见外部 SPEC04-06-UI30-31-PROGRESS.md。
- PC candidate7 FULL8 100PASS，实际同页撤范围后旧回执清除已复验。BE FULL31 11158 tests/0 failure/0 error/721 skip；独立复核仍有C5锁序P2，修复进行中。App/H5日期修复后需新full与原生bundle，旧full仅为历史候选。
- 真机900005已正常接受用户当次确认的条款并校准，实际创建申请和关联空客服会话（0消息）；旧绑定/次数是明确合成初态。三笔特批及一次消费正在等当次授权。
- 当前策略v6 on及合成校准规则尚待正常恢复，临时角色/会话和原native资源须收尾。仅一部原生和缺任务执行器仍是完整链缺口；不提前清理或宣称正式PRD最终完成。

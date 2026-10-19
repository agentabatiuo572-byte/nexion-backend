# SPEC-05 H5 隔离与 App/H5 受控同步

状态：已批准方案的后续实施步骤；当前只写交接，不执行同步、构建、提交或推送。批准依据见 `../HANDOFF.md`，产品实现与发布另验收。

## 目标、依赖与真实来源

- 目标：普通 H5 与 App 会话互不接管，网页只读手机状态；同轮交付可追溯的共享源码，同时保留网页既有受控首次设置能力。
- 依赖：[SPEC-04](SPEC-04-app-interactions.md) 候选共享源码及定向检查通过；其 SPEC-01/02/03 接口依赖已验收。双端 full、生产构建、隔离 dev 真实服务及原生真机验收属于后续完整门，不能用候选树或 fixture 通过代替。
- 规则：`../inputs/PRD/specs/FEAT-SESSION01-app-single-login-phone-replacement.md` §1–§3、§5–§6；前端输入 §4.7.7、§6.11、§12.2；实际两仓 `AGENTS.md`、`docs/app-h5-sync.md`、同步脚本及 package.json。
- App：`D:/WORKS/PLAN/nexion-frontend-uniapp`，`https://github.com/agentabatiuo572-byte/nexion-frontend-uniapp.git` 的 `test`，基线 `90d5935dc7ee17dc4cf8a460d87f7909c9d498d7`。
- H5：`https://github.com/agentabatiuo572-byte/nexion-frontend-prototype.git` 的 `H5`，本轮已抓取并回读 `D:/WORKS/PLAN/Nexion-uniapp` 的 `origin/H5=c547f4a87a49860e7ecff659615ff1d3233e39b2`；只读对象不等于该工作树可写。
- `Nexion-uniapp/main` 是旧线，不是 H5；本机 `Nexion-H5` 处于旧交付分支 `codex/cs-h5-delivery-20261015`、旧 HEAD `049a1835f79ce7cb2e0d4c8ebf578d87b88baedb`，不能当默认实施目标或当前远端。
- H5 基线来源记录 pin 为 `191fa9cac950c111ccb45cd7189c735772f2ce8b`；pin 与 App 当前基线不同只说明待重新对齐，不等于本轮已同步。实施前 fetch、选择指定 H5 线的干净隔离 checkout 并记录绝对路径/HEAD；不切换或覆盖他人现有目录。

## 允许 scope 与排除

- 共享修改一律先在 App 的 SPEC-04 范围；本步补隔离只允许 `src/api/{runtime,auth-api,api-client,onboarding-calibration-api}.ts`、`src/lib/{device-control-platform,defer-phone-activation,phone-activation-guidance,native-phone-runtime}.ts`、`src/pages/onboarding/{connect,estimator}.vue`、`src/pages/me/devices.vue`、`src/pages/login/login.vue`、对应邻接测试、`src/i18n/messages/{zh,en,vi}.ts`、相关 `scripts/*h5*.mjs` 与 `scripts/phone-activation-defer-contract.test.mjs`。
- H5 写入仅限同步工具计划中受控 `src/`、`scripts/`、`checks/`、`PRD/`、`docs/`、`.githooks/` 及脚本 ROOT_FILES 枚举的构建根文件；`docs/app-h5-source.json` 由工具写。审阅完整增删清单，不用目录通配授权覆盖无关 WIP。
- 保留 H5 `AGENTS.md`；仅两项固定覆盖：`vite.config.ts` 开发端口 5175、`.githooks/config.json` 主线 H5。不整仓复制、不改同步器/门禁来容忍漂移，不覆盖 `.git/.claude/.codex`、私密环境、密钥、依赖或构建产物。
- 编写交接包时未执行产品写入；接手方按HANDOFF实施本步，后续同步中的覆盖/删除按工具进入 `.trash/app-h5-sync-*`。遇旧线独立功能差异先回源，不把该能力当杂项删除。

#### [FEAT-SESSION01-H5-STEP05] 网页边界与双端一致性交付

**① 用户故事**

- 作为网页版用户，我想登录、浏览及查看手机状态时不打断 App，以便继续处理账户事务。
- 作为首次使用网页版的用户，我想明确选择稍后在 Android 激活，以便保存既有初始设置决定且不获得手机运行权限。

**② 验收（GWT）、正负测试与边界映射**

| 验收 ID | 正例与可观察结果 | 异常/反例与拒绝结果 | 来源/边界 |
|---|---|---|---|
| H5-01 双向隔离 | Given A App 运行，When 同机/异机 H5 登录、刷新、多标签及退出当前会话，Then A 会话/绑定/任务不变；B App 接管也保留 H5 | 正常 H5 不显示将踢 App；旧 H5 请求不可清理新登录；UNKNOWN 或伪造组别不能取得手机权限；显式全端退出/密码找回/后台封禁仍按原安全规则 | S02–S04、S06、S32、S33、S35 |
| H5-02 手机只读 | Given 有绑定/暂停/申请/特批，When H5 查资格及历史，Then 显示最新事实、`canReplace=false` 和在 App 处理入口 | 直接请求校准/绑定/替换/激活/停用/强停/任务结束停用/phone-installation login/心跳/任务领取提交/协助创建均拒绝；查询参数或浏览器身份不授原生能力 | S02、S26、S28、S33、S34、S37、S40 |
| H5-03 既有初始延后 | Given 当前账号/浏览器安装结果为真实 404 且服务端 fleet 无任何 phone，When 明确选择稍后在 Android 激活，Then revision 0 保存 DEFERRED 并持久 GET 读回 | 已有 CALIBRATED/ACTIVE/手机记录不得写停用；非真实404、fleet失败、切号/epoch改变均不写；打开/登录/返回不隐式保存；已有 DEFERRED 仅 GET 恢复 | 既有 `docs/app-h5-sync.md`；S02、S26、S35 回归 |
| H5-04 独立能力保留 | Given H5 用户及已购非手机设备，When 浏览/普通购买/管理设备/充值恢复，Then 按原共享契约可用；下载引导只在 H5 | 不以手机只读删掉账户或已购设备管理；onboarding/条款 428 保留金额/页面/待处理单且不自动重放建单；App 无网页下载或网页退出文案 | S26、S30；既有同步契约 |
| H5-05 来源一致 | Given 经核验候选 App 树和干净 H5 目标，When plan/apply/check，Then 全部受控文件与两项覆盖匹配；App 提交后 repin 指向同内容完整 commit SHA | 脏目标、改 HEAD、改 plan、来源/目标内容漂移、符号链接、缺对象或非40位pin必须失败；禁止 offline 作为 full 替代；孤儿/新增/删除文件须核对 | 同步脚本/文档；两端共享范围 |
| H5-06 实景与交付 | Given 双端候选，When 各自 full+浏览器实景和独立页面审查，Then 报告绑定实际两仓树、三语/明暗/窄屏及刷新结果 | scoped/static、mock fixture 或单端绿不能宣称双端完成；缺原生或真服务证据保持未验证，生产包/真服务/原生安装分别交接 | S01–S04、S30、S32–S35、S37；全局完成门 |

**③ 数据字典、接口和交接物**

| 对象 | 合同 |
|---|---|
| H5 会话 | 保留既有 cookie/多会话安全机制；服务端固定 H5 组，刷新不换组；App 原生 capability 与分组分开，不用浏览器参数证明来源 |
| 资格/申请/特批/操作查询 | 仅当前账号只读，未知与空分开；原回执与当前状态分开；不得重放激活以查询结果；申请与 grant 独立期限如实显示 |
| `DEFERRED` 初始结果 | 复用现有 `/api/onboarding/calibrate/defer` 受控首次设置合同；同账号 epoch、deviceId、PRODUCTION 且空 runId 的 GET 读回才完成；不校准、不授算力/手机收益、不调用原生安装登录、不发运行态 |
| provenance | `docs/app-h5-source.json` 的 sourceRemote/sourceBranch/sourceRef/sourceRefKind/sourceTree 与逐文件 blob/SHA256 由工具生成；候选 tree 不是已提交/已发布 commit |
| 环境定位 | App full 用当前 App 树；隔离 H5 设置 `APP_H5_SYNC_TARGET`，H5 来源路径变化设置 `APP_H5_SYNC_SOURCE`；变量均为已验证绝对 checkout 路径，不把旧记录路径照抄为可用 |

向最终集成移交两仓 base/候选树/最终 commit、同步 plan/hash、完整 check/full 报告、H5-* 请求与刷新证据、独立审查及未跑原生/真服务项。不在证据中保存凭证或完整硬件数据。

**④ 状态机与禁止动作**

| 状态对象 | 允许流转 | 禁止 |
|---|---|---|
| H5 初始设置 | 显式选择→作用域/404/fleet核验→首次延后提交→持久 GET 确认；失败→保留输入/重试 | 读失败假空；已有手机改为延后；打开即写；延后成功暗示激活或收益 |
| 网页手机区 | 读取→真实状态/无记录/未知→刷新或前往 App | 页面按钮隐藏但 API 仍可执行；H5 本地状态启动手机任务 |
| 同步 | 固定候选树→plan→审阅→apply→check→App候选full/提交→repin→H5候选full/提交→双端最终full | 仅改 pin 冒充同步；跳过缺源；一端失败仍报告两端完成；强推覆盖远端 |

**⑤ 多状态与交互要求**

- 默认态：只读原手机、暂停/运行、次数/申请/授权状态，明确在 App 继续；普通 H5 登录不显示 App 接管后果。
- 空状态：无绑定说明可在 App 激活；首次设置仅显式延后入口；无申请如实为空，不能把读取失败当无申请。
- 加载态：保留页面骨架及已知信息；读回/首次设置提交防连点；不把未确认的初始设置当已完成。
- 报错/极限态：多原因、原机恢复说明、未知额度、联系客服失败各有重试/返回；三语不暴露字段/枚举/接口；长设备名/时区/窄屏不遮挡操作。

**⑥ 点击流矩阵**

| 触发 | 响应/目标 | 失败和出口 |
|---|---|---|
| 登录/退出当前账号 | 现有 H5 会话链 | 失败保留重试；不发手机停止；明确安全全端动作仍沿原入口 |
| 刷新手机状态/查看协助进度 | 资格、申请、授权与历史只读查询 | 未知给重试；关闭返回；不创建申请/延长授权 |
| 在 App 中处理 | 既有打开 App/下载入口 | 保留当前页面；不可用时说明；不会替用户登录/激活 |
| 稍后在 Android 激活，继续网页版 | 受控首次设置与持久 GET | 失败留页可重试/返回，已有手机只读；返回不隐式保存 |
| 继续浏览/返回 | 现有 H5 首页或上一页 | 不校准、不发心跳、不消费特批 |

**⑦ 跨文档一致性、执行顺序、验证和恢复交接**

以下是后续实施步骤，不是本轮已执行记录。`appRoot/h5Root/planFile/candidateTree/sourceCommit` 必须来自现场记录；命令由现有 `docs/app-h5-sync.md` 回源。

1. fetch 并确认指定两仓分支/HEAD/WIP；从正确 H5 基线准备干净实施 checkout。候选内容仅含本任务；以 `git write-tree` 得到完整树 SHA，工作树与暂存树一致。交接包本身未预生成产品候选树或机器通过状态，由接手方依据真实实施结果产生。
2. `node <appRoot>/scripts/app-h5-sync.mjs plan --source <appRoot> --target <h5Root> --ref <candidateTree> --out <planFile>`；逐项审阅 writes/deletes、两个固定覆盖及独立网页能力，记录计划摘要。
3. `node <appRoot>/scripts/app-h5-sync.mjs apply --plan <planFile>`；再 `node <appRoot>/scripts/app-h5-sync.mjs check --source <appRoot> --target <h5Root>`。失败先查源/目标/plan变更，不改元数据或工具放行；缺源对象不得用 `--offline` 代替。
4. 分层跑现有命令：两端 `npm run test:app-h5-sync`、`npm run test:production-boundaries`、`npm run test:session-reload-recovery`、`npm run test:login-flow-separation`、`npm run test:h5-runtime`；H5运行请求日志须覆盖 H5-01..04，测试名称本身不证明覆盖。
5. App 候选 `npm run verify`（full）通过后按工程门提交；以最终完整 commit SHA 运行 `node <appRoot>/scripts/app-h5-sync.mjs repin --source <appRoot> --target <h5Root> --ref <sourceCommit>`。受控内容变更时 repin 必须拒绝，回到新 plan/apply。
6. H5 候选同样 full、提交；最终两端干净树各 `npm run verify`，含生产 H5 构建与产物门。实施期再按授权和各自门禁推送指定分支；远端变化先重核，不自动 force。两仓提交非原子，记录各自成功/失败范围。
7. `check --latest` 在已发布源 commit 与真实源 checkout 可得后复核公开 test 最新来源；仅远端 SHA 相等不证明文件真实，离线缓存检查不证明最新或双端 full。
8. 浏览器实景遍历受影响登录、注册/初始设置、设备仓库、连接/下载和客服链，zh/en/vi、亮暗、窄屏、刷新/多标签；独立 agent 逐页检查人工操作友好，主线回源 P0/P1。保留既有普通账户/已购设备流程回归。
9. 生产构建、Java dev profile 真服务联调、真实 App 原生安装/签名/心跳分开记录。full runtime 的 transport/session fixture 不证明真实资金、App 接管事务或手机执行；最终集成以原生 A/B/C 与真服务证据关闭相应 S 场景。

恢复与移交：保留旧 pin、plan、目标 HEAD、`.trash` 备份及失败记录；中断先核现存 runner，不重复 apply/写业务。任何一端树或上游接口变化，重开最早影响步骤并使下游证据失效。交付记录必须分别列“候选已验证、提交、push、生产构建、隔离 dev 真实服务、原生”事实，未验证不填通过；最终跑 nexion-audit/done-review，缺项交最终集成而不宣称完成。

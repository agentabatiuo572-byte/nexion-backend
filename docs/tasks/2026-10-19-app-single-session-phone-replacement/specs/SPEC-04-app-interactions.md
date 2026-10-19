# SPEC-04 App 登录、换机与协助交互

状态：已批准产品方案的分步实施规格；本轮只交接文档，产品尚未实现或验收。批准记录见 `../HANDOFF.md`，输入副本中的“待确认”不另设审批。

## 目标、依赖与允许范围

- 目标：让所有 App 登录入口消费同一服务端接管结果，并在登录后完成可取消、可恢复的换机、协助与仓库闭环；登录成功与手机执行资格分离。
- 依赖：[SPEC-01](SPEC-01-backend-app-session.md)、[SPEC-02](SPEC-02-backend-phone-replacement.md)、[SPEC-03](SPEC-03-backend-operations.md) 的会话、资格/操作恢复、协助申请与特批接口已逐项验收。接口未齐可准备设计/测试清单，不以客户端 mock 宣布对接通过。
- 权威输入：`../inputs/PRD/specs/FEAT-SESSION01-app-single-login-phone-replacement.md` §1–§6；`../inputs/nexion-frontend-uniapp/PRD/NexGrid_产品功能架构设计文档_v3.7.md` §4.7.6、§4.7.7、§6.11、§11.8.4、§12.2。根 PRD 副本用于一致性对照，不另创规则。
- 实施仓：接手方实际`nexion-frontend-uniapp` checkout（作者取证路径为`D:/WORKS/PLAN/nexion-frontend-uniapp`，不要求同事复建D盘），remote `https://github.com/agentabatiuo572-byte/nexion-frontend-uniapp.git`，`test`；核源基线 `90d5935dc7ee17dc4cf8a460d87f7909c9d498d7`。实施前刷新远端并重核差异，保留任何现存无关改动。
- 允许产品路径：`src/App.vue`、`src/api/{auth-api,api-client,contracts,runtime,session-vault,onboarding-calibration-api,support-api}.ts`、`src/auth/`、`src/store/{auth,session,device-lifecycle,conversations}.ts`、`src/lib/{account-scope,device-id,native-phone-runtime,phone-calibration-flow,onboarding-calibration-scope,defer-phone-activation,phone-activation-guidance,device-control-platform}.ts`、`src/pages/login/login.vue`、`src/pages/session/kicked.vue`、`src/pages/onboarding/{connect,estimator}.vue`、`src/pages/me/devices.vue`、`src/pages/support/{messages,chat}.vue`、`src/components/auth-provider-grid.vue`、`src/components/me/`、`src/i18n/messages/{zh,en,vi}.ts`。
- 允许测试路径：上述模块邻接 `*.test.ts`、`scripts/{auth-entry-ui-contract,server-session-reload-recovery-contract,session-ended-remote-contract,phone-activation-defer-contract,server-auth-config-authority-contract}.test.mjs`、`scripts/verify-h5-runtime.mjs`、`checks/`；新增任务测试须在此范围明确落点，不能顺便放宽门禁。
- 排除：后端/后台实现、全局技能、AGENTS、签名与密钥、无关功能。新组件若必须落在范围外，先更新交接范围并保留理由。本文编写时仅交付文档；接手方依HANDOFF实施本步并按仓库门禁提交，不能把这项历史说明理解成禁止实施。

#### [FEAT-SESSION01-APP-STEP04] App 交互交付单元

**① 用户故事**

- 作为换手机的用户，我想明确接管登录、选择是否更换并理解限制，以便继续使用账户且不误启手机任务。
- 作为受限用户，我想保留原绑定、提交当前手机的协助申请并查回结果，以便获得处理路径且不重复申请或消耗授权。

**② 验收（GWT）、正负测试与 S 边界映射**

| 验收 ID | 正例及必须观察的结果 | 异常/反例与拒绝结果 | 共同矩阵 |
|---|---|---|---|
| APP-01 统一登录 | Given A 运行，When B 经密码、OTP、2FA、实际启用 OAuth 任一入口完成认证，Then B 唯一有效、A 停止且仍绑定 A；注册仅初始会话 | 认证失败/取消不动 A；无原生运行能力的 iOS/白 App 仍参与 App 接管，不能被判 H5；条款未确认、原生桥不可用均不能延迟旧端停止 | S01、S07、S08、S13、S34 |
| APP-02 会话恢复 | Given 最新 App 会话，When 续期/冷启动/前后台切换，Then 只恢复当前会话；同绑定显式重登不改 changedAt、不计次、不因认证强制取消在途任务；显式换账号退出原当前会话并停止其手机执行 | B/C 并发及迟到回执不复活 B；被接管页只能主动重新登录；UNKNOWN 来源不得走 H5 旁路；切号迟到响应不串账号、不清新会话 | S05、S06、S10、S12、S18、S33、S35 |
| APP-03 换机选择 | Given B 已接管且仍绑 A，When 暂不更换/关闭/返回，Then A 绑定保留暂停、B 不运行、0 次；仓库可重新进入校准及显式确认 | 冷却、次数、禁换、原生不支持、条款或校准受限时账户允许功能仍可用；无重定向循环；不得调用 defer 将拒绝换机变成停用或解绑旧手机 | S08–S14、S25、S26、S40 |
| APP-04 资格与确认 | Given 当前服务端资格，When 校准后确认，Then 展示实际窗口/次数/时间/特批并提交同一版本快照；首次绑定预期无绑定且不计次 | 资格未知不写 0/不限；本地改时间无效；多阻断完整呈现；版本变更要求刷新并重新确认；同机重装/已知硬件变化不自行免计，首绑并发不覆盖已有绑定 | S14–S16、S21、S22、S25–S27、S31、S41 |
| APP-05 未知结果 | Given 激活结果未知，When 重试/刷新/重进仓库，Then 原操作号或原键恢复原回执及最新绑定；成功只计一次 | 双击不造新键；特批后来到期/被撤销、绑定再变不把原成功改失败；失败后过期不得首次执行；离开查询页不等于撤销命令 | S17–S20、S22、S23、S36 |
| APP-06 协助申请 | Given 当前有效 App 及原绑定，When 填 8–200 字原因并确认，Then 取得可读申请号、分别呈现保存和客服关联结果；返回仓库可刷新 | 未到期同目标/版本 PENDING 复用不续期；CLOSED 显示原因且主动新确认可新申；过期/失效/旧授权结束需新上下文；H5 不得创建；客服失败保留号可复制/重试 | S22、S24、S36、S37 |
| APP-07 授权与运行 | Given 有效授权，When 仅打开/返回/查询，Then 不激活不消费；仅真实成功替换消费一次，申请 24h 与签发后授权 24h 独立 | 申请创建满 24h 不让有效 grant 提前到期；未豁免项仍阻断；A 恢复只解除换机暂停、不复活已终止任务；其他资产、试用和已确认收益不变 | S10、S11、S16、S20–S23、S28–S31、S36 |
| APP-08 人工操作 | Given 各页面四态，When 按完整点击序列操作并刷新，Then 输入、明确下一步、禁用原因、查询及返回出口齐全，三语/亮暗/窄屏可用 | 工程字段/错误码/枚举不入界面或无障碍文案；无单框多值输入、无猜测停机成功；每页独立 agent 手工友好审查，P0/P1 主线回源复验 | S08、S09、S26、S35、S37、S40 |

APP-04/05 的前端证据只能证明请求、显示与恢复行为；事务、计次、竞争及服务端拒绝仍须与 SPEC-01/02/03 证据连接，不能以按钮禁用代替服务端验证。

- 异常1（迟到登录回执）：Given B 登录提交后已被 C 接管，When B 原尝试恢复返回，Then B 显示主动重新登录；不装载有效 B 凭证、不覆盖 C、不重新停机或重开旧任务。
- 异常2（协助已关闭）：Given 申请在创建 24h 内已 CLOSED，When 用户查看原因后修改原因并主动确认重申，Then 创建新申请；不复用关闭记录、不自动恢复 PENDING、不延长旧申请或旧特批。

**③ 数据字典、接口与交接物**

| 上游交接 | 前端使用约束 |
|---|---|
| SPEC-01：`sessionGroup / installationId / appSessionVersion / sessionChainId / loginAttemptId / sessionEndedReason` | 服务端权威；安装沿用 deviceId，稳定登录尝试跨同次验证/结果恢复，不以页面重渲染换新；刷新不得换组；错误原因映射三语人话 |
| `/auth/users/login`、`/login/otp/verify`、`/login/2fa`（后两者同 `/auth/users` 前缀）及实际启用 OAuth 完成路径 | 所有最终签发点统一交接；原生能力只约束执行，不能决定分组。未知结果恢复使用 SPEC-01 实际验收接口，不另猜端点 |
| SPEC-02：资格 GET、`phone-installation/login`、激活命令和操作 GET | `phone-installation/login` 只做最新 App 绑定核验/同绑定恢复；接管已在登录事务完成，不能等到条款后/Android bridge 成功后才停旧端 |
| `binding / policyVersion / usedCount / remainingCount / historyComplete / blockedReasons / nextAllowedAt / serverNow` | 均只读投影；未知 null 保留未知；按服务端时刻显示本地时区；普通资格不能仅从 remainingCount 推断 |
| 激活 `expectedRevision / expectedBindingVersion / expectedPolicyVersion / exceptionGrantId? / Idempotency-Key` | 首绑 bindingVersion=null 代表预期仍无绑定；明确更换确认与同键请求快照绑定；无操作号按原键恢复，不能清键重新提交 |
| SPEC-02：App 申请 POST/current GET 与 grant 投影；`requestId/requestNo/status/expiresAt/grantId`、grant 当前状态/期限/豁免；SPEC-03 管后台授权命令 | 账号/安装/绑定版本自动确定，禁手输安装标识；一个申请至多一张授权；PENDING 复用，CLOSED/EXPIRED/STALE 不复活；已有有效授权返回来源申请 |
| 既有 `/pages/support/messages`、`/pages/support/chat` | 携账号内申请引用；不传 token/完整硬件标识；保存申请和关联客服分别反馈；关联失败保留原号与原原因，不另造申请 |

交付 API adapter 类型、账号 epoch/版本隔离说明、逐验收请求与读回记录、变更路由表、三语文案、候选共享源码树 SHA；不得把字段存在当成后端已经实现。

**④ 状态机与禁止动作**

| 对象 | 允许流转 | 禁止动作 |
|---|---|---|
| 登录 | 输入→既有验证→提交中→成功/确定失败/结果未知→原尝试查询；被接管→显式重新登录 | 自动缓存凭证抢回；把手机校准当登录门；失效响应清理较新账号/会话 |
| 换机 | 已登录待选择→暂不更换/读资格→校准→明确确认→提交→成功/失败/未知查询 | 校准完成即替换；取消自动恢复旧会话；把返回首页当恢复旧机 |
| 协助 | 无申请→确认原因→PENDING→GRANTED/CLOSED/EXPIRED/STALE；终态需新确认新申请 | 查询续期；关闭页面撤回；客服关联失败假称送达；收到授权自动消费 |
| 授权 | ACTIVE→CONSUMED/REVOKED/EXPIRED/STALE；只显示服务端事实 | 失败消费、到期续期、只豁免次数却绕过间隔/风控/禁换 |

**⑤ 多状态与原型交接**

- 默认态：App 登录说明其他 App 将退出；换机展示原绑定、暂停结果、实际规则与影响；停机只在服务端已确认时断言。同绑定恢复保留其他暂停说明。
- 空状态：无绑定走原首次引导；无历史仅在完整时显示 0；无申请显示明确入口；缺设备名用“原绑定手机”，不造型号。
- 加载态：读取显示原有布局骨架；提交保留输入并防连点；查询原操作可离开但仓库保留待确认状态，离开不表示取消已提交命令。
- 报错/极限态：受限原因逐条可读、倒计时以 serverNow 为基准；失败保留原因输入；申请已保存但客服未关联单独显示；授权到期与申请到期分别说明。
- 实施前按已批准文字补关键流可点原型，并与当前交互组件对齐；原型只实现上述既定行为，不扩产品规则。原型/图片未产出不得填已验收。

**⑥ 点击流矩阵**

| 触发 | 响应/目标 | 失败、禁用与出口 |
|---|---|---|
| 登录、确认登录结果、重新登录 | 既有认证；原尝试只读恢复；`/pages/login/login` 显式再认证 | 验证未完成不接管；未知保留查询；被替代不恢复失效令牌 |
| 登录后校准并更换、仓库更换为当前手机 | `/pages/onboarding/connect?mode=recalibrate`；`mode=login` 仅状态核验 | 每次读最新资格；不可用解释并给返回/协助；禁止网页下载文案进入 App |
| 确认更换并激活、确认更换结果 | 带校准/绑定/策略版本提交；原操作查询 | 冲突刷新后重确认；处理中防连点，取消确认前不写 |
| 暂不更换、返回、关闭 | 原返回页或首页，仓库保留后续入口 | A 仍绑定时指引 A 主动重登；已换到 B 后说明回 A 仍算换机 |
| 申请换机协助、提交、取消 | 当前账号/手机快照+独立原因框；提交或返回 | 加载/会话无效/无绑定给原因；失败保留输入；保存成功只代表申请存在 |
| 联系客服、复制申请号 | 既有会话入口携申请引用；复制可读号 | 关联失败可重试/返回；不重建申请 |
| 查看协助进度、刷新资格、重新申请、使用授权 | 只读刷新；终态新确认；有效授权回校准及换机确认 | 不自动延长、激活或消费；CLOSED 可查看拒绝原因 |

**⑦ 跨文档一致性、验证与移交**

- 本步消费 E6 策略/C5 特批，客户端不持有次数真源；既有显式全端退出、安全重置/封禁保持原规则。H5 隔离与共享源码同步由 SPEC-05 验证，不能只交 App。
- 已核实的现有命令（在 App 仓执行，实施期运行）：`npm run type-check`、`npm run test:country-code-contracts`、`npm run test:login-flow-separation`、`npm run test:session-reload-recovery`、`npm run test:real-backend-integration`、`npm run test:production-boundaries`、`npm run test:h5-runtime`。这些既有入口必须加入/连接本轮覆盖后才可证明 APP-*；名称含 backend 不代表连接真服务。
- 下游门：先取得本步定向检查与候选共享树，再由 SPEC-05 同步，随后双端 `npm run verify`（full）；full 的同步门使“先 App full、完全不准备 H5”不能作为前置死锁。full 内 transport/session fixture 只证明前端契约。
- 原生验收必须真实 App 安装及真服务：A/B（并发再用 C）登录、接管、返回 A、旧 token/刷新/心跳拒绝、换机/申请读回与刷新；不支持执行的原生入口仍测 SSO。不以 H5 fixture、浏览器 devtools 模拟或文案截图替代原生证明。
- 每个变化页面独立只读 agent 按 APP-08 操作；记录页面/语言/主题/宽度/验收 ID、截图及请求结果，P0/P1 主线回源或运行时复验。最终做 nexion-audit 与 done-review；缺真机/服务保持“未验证”移交最终集成。
- 恢复：保存原账号、会话版本、尝试/操作/申请号和非敏感请求快照；保留失败记录。中断先核 runner 再恢复，不新建命令冒充重试。上游契约或候选树变化，重开最早受影响步骤和下游。
- 移交 SPEC-05：候选树 SHA、受控文件差异、APP-* 证据及未覆盖项、UI 独立报告、原生环境需求；仓库不提交不推送即不得写“已发布”。

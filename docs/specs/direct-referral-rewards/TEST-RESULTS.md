# 直属分成验证证据索引

本文件说明每条验收由什么证据支持，不把计划中的用例数量当成通过数量。最终通过与否以四仓当前快照的工作流记录、独立审查及提交前后质量门为准。运行记录保存在 `C:/Users/jason/.codex/workflow-runs/direct-referral-20261005/`，不写回被检查的源码树。

## 证据层次

| 层次 | 实际运行内容 | 能证明的范围 |
|---|---|---|
| Java 单元与契约 | `scripts/direct-referral-workflow-runtime.mjs` 动态选择直属、Team、订单、收据、审批、账本及架构相关测试；校验每份新生成的 Surefire XML，无跳过 | 来源生产、输入边界、权限、旧功能回归及故障处理；不能替代数据库并发实测 |
| 真实 MySQL | `DirectReferralMySqlRuntimeTest`，隔离库 `127.0.0.1:33335/direct_referral_acceptance_20261005` | 实际事务、钱包、双币账本、政策、佣金组、F5 幂等与并发；数据库资料为专用测试账号 |
| HTTP 层 | `DirectReferralControllerTest` 的 Spring 权限代理与 MockMvc | 认证身份、权限、严格请求字段、理由、幂等键及审批绕过拒绝；并非整套服务上线后的 HTTP 联调 |
| PC 浏览器 | 本工作树 Next 页面，`scripts/direct-referral-runtime.mjs`；独立复核 `reviewer-runtime/pc-f5-review.mjs` | F2 参数、确认取消、审批后刷新读回、F5 双币组、失败重试、权限与旧入口；HTTP transport 为受控 fixture |
| App/H5 浏览器 | 各自工作树 Vite 页面，`scripts/direct-referral-workflow-runtime.mjs` | 25 个场景、直属分页与失败状态、账号切换、键盘操作、三语、两主题、窄屏、旧 Team 入口及说明；资金由数据库测试另证 |
| 双端与提交门 | `app-h5-sync` plan/apply/repin；各仓完整 verify、独立 Java/TS 审查 | 共享实现一致、来源可追溯、被检查的确为将提交/推送的树；不等于生产部署 |

## TC01—TC32 对应检查

| 用例 | 证据及边界 |
|---|---|
| TC01 | MySQL `TC01-chain`：A→B→C→D 实付订单，钱包只奖励直属上级，无新增旧 network 事件。 |
| TC02 | MySQL `TC02-device-chain-no-recursion`：B/C/D 双币设备收据，只奖励直属上级。 |
| TC03 | `DirectReferralPolicyTest` 与 MySQL：1000 USDT、10%、60/40、NEX 价 0.01，得 60 USDT + 4000 NEX。 |
| TC04 | 同一实际收据含 10 USDT + 100 NEX 时，折算基数 11 USDT；5%、60/40 得 0.33 USDT + 22 NEX，来源账户余额不减少。 |
| TC05 | `AppOrderCommandServiceTest`、`AppBundleOrderServiceTest`、`AppTradeinServiceTest`、`AppTrialLifecycleServiceTest` 覆盖普通、组合、三种换购及试用转正的真实订单事件；未宣称每种商品都做了完整商城 HTTP 结账。 |
| TC06 | MySQL 零元拒绝、标价与实付不同；订单/换购单测覆盖全额抵扣及净额。 |
| TC07 | 消费者拒绝重复设备事件；MySQL 同订单 8 个并发调用只得一组、两条释放账本。 |
| TC08 | MySQL 订单和收据各调用结算 100 次，余额与账本数量不变；防重键取业务来源而非事件 ID 由源码契约证明，此场景未实际更换 outbox 事件 ID。 |
| TC09 | 消费者拒绝佣金、H8、试用影子来源；直属奖励不再产生可触发上级分成的设备收据。 |
| TC10 | MySQL 生产 PHONE 收据可计，开发任务和测试工作器事实不可计；此处验证数据来源，不代表实体手机测试。 |
| TC11 | MySQL 更换上级及政策后重放，仍使用原结算组、原上级和原版本。 |
| TC12 | MySQL 无历史补发、延迟来源、关闭政策及同秒微秒边界；订单、任务/收据与二元对碰来源时间一致。 |
| TC13 | 比例和拆分校验；HTTP 拒绝自带价格/生效时间；MySQL 缺价格无组无入账，恢复后可重试。 |
| TC14 | 精度、负基数、超界及过细百分比单测；MySQL 小额舍入拒绝时对外两币均为零，保留原始基数事实。 |
| TC15 | MySQL 在第一币释放后注入第二币失败，钱包、账本和组全部回滚；随后只成功发放一次。 |
| TC16 | MySQL 冷却、冻结、账号状态、无上级、自邀、风险变化、钱包环境隔离。 |
| TC17 | MySQL 来源并发、到期释放与 F5 解锁并发，最终只有两条释放账本。 |
| TC18 | MySQL 发放前取消、订单退款事实后的双币追回；购买账户退款与直属追回由异步 outbox 分开，另有 `OpsDeviceServiceTest`，此场景未执行完整买家退款事务。 |
| TC19 | MySQL 重复全额退款及退款/释放竞争；非全额比例拒绝且无账务变化。当前没有部分退款业务。 |
| TC20 | 实际 F5 冲正与幂等执行器：余额不足产生待追回，后续补足后生成真实 OUT 账本；101 笔恢复队列防饿死另由单元测试覆盖。 |
| TC21 | 实际 F5 整组冻结、环境范围、冲正/释放竞争、依据无效拒绝、禁止复制补发及原来源恢复。 |
| TC22 | HTTP 缺键、短理由、权限、审批绕过及未知字段拒绝；后台权限映射保持一致。 |
| TC23 | MySQL 旧版本拒绝、两个政策发布者串行比较版本；锁记录由迁移建立，审批时不重复插入造成锁升级。 |
| TC24 | 有效 USDT/NEX 比例放大单测；MySQL 储备覆盖不足拒绝放大、关闭收缩可通过。 |
| TC25 | `OpsAuditCenterServiceTest` 核对完整前后两规则与原理由；MySQL 真发布读回；PC 浏览器确认取消及刷新。 |
| TC26 | App/H5 加载、空态、失败重试及严格 API parser；拒绝组不会展示单币伪到账。 |
| TC27 | MySQL 分页快照与计数、不暴露成员原始 ID；App/H5 期间切换、翻页、旧请求和账号切换。 |
| TC28 | 内容三语测试；App/H5 中英越、亮暗、320px/常规屏、说明页实景。 |
| TC29 | MySQL 旧 network 幂等释放、新购买不再走旧七层；Team 二元对碰、等级、培育、领导池回归及前端入口。 |
| TC30 | F5 八类、七状态、双币汇总与导出回归；App 总佣金分类及三语说明。 |
| TC31 | MySQL 来源/钱包跨环境、身份篡改拒绝；消费者检查业务对象类型、非法 JSON 和失败重试。 |
| TC32 | 正式 App→H5 工具同步、提交来源 pin、两端完整质量门和各自浏览器证据。 |

## 完成自检的六个维度

1. 真落地：资金看数据库事务和账本，配置看审批与持久读回，界面看实际点击及刷新；三类证据分别标明。
2. 需求完整：从 REQUIREMENTS.md 与 TC01—TC32 逐项核对，购买和收益两个来源、配置与账单三个实现面均有检查。
3. 交互完整：F2 两规则整组审批、理由和取消；F5 整组动作与失败重试；App/H5 加载、空态、失败、分页和账号切换。
4. 同类覆盖：六条购买生产路径共用结算；八种佣金分类保持兼容；F5 冻结、解锁、冲正和复制补发逐项覆盖。
5. 不变量：两币同事务、两类独立比例、三语双端、只付直属、不减少来源收入、保留四项独立 Team 机制。
6. 实景回归：各工作树浏览器报告须无 console 错误且源码未移动；全量门、独立审查和源码推送分别记录。

## 结果读取与限制

`backend-runtime.json`、`pc-runtime.json`、`app-runtime.json`、`h5-runtime.json` 记录真实运行时间、工作树、快照、场景及报告路径；各 `*-integration-result.json` 记录集成检查，`*-review.json` 记录独立验收。后端实际 SQL 观察在 `backend-evidence/scenario-evidence.json`，失败轮次日志保留，不用新报告抹去首轮问题。

生产部署、正式比例启用、真实资金操作、实体手机执行，以及 PC/App 浏览器直连完整 Java 服务的全链路，不属于本轮已证明的结果。源码推送成功也不得表述为这些动作已经完成。

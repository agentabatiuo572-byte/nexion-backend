# Nexion 运营控制后台后端技术文档：A-L 域能力契约

## 1. 契约原则

本文按 A-L 12 域定义运营控制后台后端能力契约。它面向实现人员，回答每个域拥有什么、不能做什么、通过什么 API 家族暴露、哪些状态由 server 裁决、哪些更新日志修正必须覆盖。

来源优先级：

1. `D:\workspace\nexion-ops-console\docs\产品更新日志.md`
2. `D:\workspace\nexion-ops-console\docs\PRD\Nexion_运营控制后台_开发落地规格.md`
3. PRD v1-v4 正文与投产 Checklist
4. 当前后端 README 与现有代码结构

更新日志覆盖旧 PRD。已下线能力只作为历史兼容，不作为活跃域能力。

## 2. 全局角色与表面

后台角色按权限矩阵裁决：

- superadmin：全域最高权限，高敏写仍需确认理由。
- finance lead：资金、储备、提现、账本、财务导出。
- risk lead：风控、KYC、geo-block、披露高敏内容。
- content lead：CMS、i18n、通知、教程、I9 配置。
- support lead/support agent：I9 即时会话接待、转交和回复。
- growth lead：Phase、Trial、Quest、活动、签到、增长奖励。
- commerce/device ops：商品、设备、Trade-in、设备运维。
- team ops：分销、佣金、排行榜、硬件配额。

API 表面：

- 后台接口：`/api/admin/{domain}/{resource}`
- 用户侧配置或展示接口：`/api/config/**` 或业务公开路径
- 实时接口：`/api/admin/**/stream`、WS 或 SSE
- 导出接口：必须经过 L5 导出审计策略

## 3. 域 A：平台基础

### 能力

A 域提供后台身份、运营账号、RBAC、A2 审计、A3 系统配置、A4 事件 schema、幂等策略和全局技术治理。

核心聚合：

- `Admin`
- `Role`
- `Permission`
- `AuditLog`
- `EventSchema`
- `SystemConfig`
- `IdempotencyRecord`

### 接口族

- `/api/admin/auth/**`：后台登录、退出、刷新、当前身份。
- `/api/admin/rbac/**`：角色、权限、菜单、分配。
- `/api/admin/audit/**`：审计查询、trace 查询、动作统计。
- `/api/admin/system/configs/**`：系统配置读写。
- `/api/admin/event-schema/**`：A4 事件 domain/action/schema 注册。

### 规则

- A2 审计是高敏动作唯一审计权威。
- A4 事件 schema 变更本身是高敏动作，仅超管确认后执行。
- SystemConfig 可以保存业务参数，不得保存数据库密码、密钥、token、对象存储 secret 等长期秘密。
- A 域不拥有 J1/J2 的最终应急闸写权；V1 临时路径必须迁移到 J 域。

## 4. 域 B：双账本驾驶舱

### 能力

B 域提供运营视角的资金安全驾驶舱。B1 是兑付覆盖率和负债分母裁决口径，B2/B3/B4/B5 是展示与预警视图。

核心聚合：

- `TreasuryCoverage`
- `LiabilityLedger`
- `ReserveSnapshot`
- `RiskRadar`
- `FunnelSnapshot`
- `PhaseSnapshot`

### 接口族

- `/api/admin/treasury/coverage`
- `/api/admin/treasury/ledger`
- `/api/admin/treasury/thresholds`
- `/api/admin/treasury/reserve-injection`
- `/api/admin/risk/radar`
- `/api/admin/risk/radar/stream`

### 规则

- 覆盖率 = D3 储备分子 / B1 负债分母。
- `coverageRedLine` 默认 100%，`yellowLine` 必须大于 `redLine`，违反返回 `400`。
- 全域放大资金流出方向必须调用 B1 Facade 做前置裁决。
- B 域可以提供 UI 入口，但储备注入、对账导出等真实写入由 D3/D4 承担。
- L3 财务报表引用 B1/D3/D4，不重算口径。

## 5. 域 C：用户与账户

### 能力

C 域提供用户检索、用户 360 画像、账户操作、资产调整入口、KYC 台账引用、安全会话与注册登录风控配置。

核心聚合：

- `UserProfile`
- `UserAccountStatus`
- `UserSession`
- `KycLedgerView`
- `UserAssetAdjustmentCommand`
- `RegisterRiskConfig`

### 接口族

- `/api/admin/users`
- `/api/admin/users/{userId}`
- `/api/admin/users/{userId}/status`
- `/api/admin/users/{userId}/adjust`
- `/api/admin/users/{userId}/sessions`
- `/api/admin/users/{userId}/security/**`
- `/api/admin/kyc/**`
- `/api/admin/risk/register-login/**`

### 规则

- C1 用户检索必须脱敏，导出进入 L5 策略。
- C3 人工资产调整中 USDT/NEX 必须走 D4 bill 和 B1 红线；历史积分只允许迁移/归档语境，不作为新增调整资产。
- C5 密码失效、禁用 2FA、强制登出等账户安全写操作必须记录审计；PRD 已声明部分账户安全操作需要 `Idempotency-Key`。
- KYC 状态权威在合规/风控相关聚合，C 只做用户视角引用。

## 6. 域 D：资金与财务

### 能力

D 域是充值、提现、储备、账单与提现参数的资金权威域。

核心聚合：

- `DepositOrder`
- `WithdrawalOrder`
- `ReserveLedger`
- `Bill`
- `WalletLedger`
- `WithdrawalPolicy`
- `WithdrawalNexGate`

### 接口族

- `/api/admin/deposits/**`
- `/api/admin/withdrawals`
- `/api/admin/withdrawals/{id}/{approve|reject|delay|freeze|unfreeze|refund}`
- `/api/admin/treasury/reserve`
- `/api/admin/treasury/reserve-injection`
- `/api/admin/bills`
- `/api/admin/withdraw/limits`

### 状态机

提现队列按 server-canonical 推进：

- 正常态：submitted、review-pending、approved、broadcasting、completed、rejected。
- 异常态：delayed、frozen、unfrozen、refund-pending、refunded、failed。
- 非法转移返回 `409`。

### 更新日志修正

D5/H1 的提现摩擦闸从旧积分门改为 NEX 销毁式提现闸：

- H1 持有 `withdrawNexGate`。
- D5/D2 只读镜像该值。
- 用户提现时按配置燃烧 NEX，NEX 余额不足不得进入放行。
- 旧积分字段只能出现在历史迁移、旧账说明或清理说明中。

### 规则

- 资金/资产写必须带 `Idempotency-Key`。
- 放行、退款、储备注入、人工调账必须写 D4 bill 与 A2 审计。
- 放大流出方向先核 B1，失败返回 `422 COVERAGE_BELOW_REDLINE`。
- D4 是账单事实源；其他域不得伪造账单。

## 7. 域 E：设备与商城

### 能力

E 域负责商品目录、代际发布、收益任务、设备生命周期、Trade-in、订单状态机、设备运维。

核心聚合：

- `ProductSpec`
- `Order`
- `Device`
- `DeviceLifecycle`
- `TradeInRule`
- `TradeInApplication`
- `DeviceRestoreCommand`

### 接口族

- `/api/admin/products/**`
- `/api/admin/orders/**`
- `/api/admin/devices/**`
- `/api/admin/devices/{id}/restore`
- `/api/admin/config/tradein`
- `/api/admin/tradeins/**`
- `/api/config/tradein`

### 状态机

- 订单：created、paid、activated、refunded、cancelled、failed。
- 设备：active、offline、recycled、restored-offline、retired。
- Trade-in：quoted、submitted、reviewing、approved、rejected、settled。

### 更新日志修正

新增 E3b 设备撤销回收：

- 接口：`POST /api/admin/devices/{id}/restore`。
- 只允许对误回收设备执行。
- 恢复后设备状态为 offline，可再次上线。
- 必须写设备生命周期系统记录和 A2 审计。
- 对已退款、已替换、已销毁或已产生不可逆资产处理的设备返回 `409 INVALID_DEVICE_STATE`。

### 规则

- `purchasedAt` 是 server-canonical，运维激活、解绑、撤销回收不得改写。
- Trade-in 残值由 server 计算，前端不得提交最终折扣结果。
- 退款联动 D 域，E 不直接写 D4 表。

## 8. 域 F：分销团队

### 能力

F 域负责 V-Rank、网络版税、双轨结算、领导奖池、佣金审计、硬件配额、区域大使、排行榜和反欺诈执行。

核心聚合：

- `VRankRule`
- `TeamCommission`
- `BinarySettlement`
- `LeadershipPool`
- `HardwareQuota`
- `AmbassadorApplication`
- `LeaderboardSnapshot`

### 接口族

- `/api/admin/team/vranks/**`
- `/api/admin/config/commission/rates`
- `/api/admin/team/binary/**`
- `/api/admin/team/leadership-pool/**`
- `/api/admin/commissions/**`
- `/api/admin/users/{userId}/quota/override`
- `/api/admin/agent/applications/**`
- `/api/admin/leaderboard/**`

### 规则

- 佣金计算 100% server-canonical。
- 补发、撤销、派奖、奖池注入必须带 `Idempotency-Key`。
- 放大奖励、提高费率、提前派奖、补发等必须先核 B1。
- K 域只产风险证据；排行榜取消资格执行权归 F4d，但必须校验 K1/K2 证据。
- “Premium”作为伙伴等级命名不等于已下线的订阅产品；文档和代码命名应避免混淆。

## 9. 域 G：金融产品与 NEX 行情

### 能力

G 域保留活跃的 Staking、兑换、G3 NEX 周曲线、Genesis、复投激励。已下线订阅和旧锁仓不作为活跃子模块。

核心聚合：

- `StakingPool`
- `ExchangePolicy`
- `NexMarketCurve`
- `NexOracleStatus`
- `GenesisPolicy`
- `ReinvestPolicy`
- `LegacyProductArchive`

### 接口族

- `/api/config/staking/pools`
- `/api/admin/staking/**`
- `/api/admin/exchange/**`
- `/api/config/market/nex`
- `/api/market/nex`
- `/api/admin/market/nex/oracle-status`
- `/api/admin/market/nex/curve`
- `/api/admin/market/nex/oracle`
- `/api/admin/market/nex/{pause|resume}`
- `/api/admin/genesis/**`
- `/api/admin/reinvest/**`

### 更新日志修正：G3 周曲线关键帧排程器

G3 从手动单值配置升级为周曲线关键帧排程器：

- 一周 7 天关键帧，每帧包含目标价、上行概率、波动参数。
- 每日 00:00 UTC 自动推进当日帧。
- 支持自动按日推进、钉住某日、跑完循环或停末值。
- 旧 6 个手动控件只作为应急 override 层。
- 现价仍是兑换和复投的唯一定价源。
- 调高目标价或上行概率属于放大流出，必须用周峰值价重估 NEX 计价负债并核 B1。

### 下线规则

- 已下线的订阅产品不提供新购买、配置、权益、入口、kill 闸。
- 已下线的 NEX v2 锁仓不提供新开仓；存量只允许到期兑付、查询和迁移。
- 旧接口如存在兼容保留，必须返回禁用状态或迁移提示，不得恢复交易能力。

## 10. 域 H：Phase 与增长活动

### 能力

H 域负责 Phase 调度、Trial、Quest、活动、签到、里程碑。

核心聚合：

- `GrowthPhase`
- `TrialPolicy`
- `QuestCampaign`
- `EventCampaign`
- `CheckInStreak`
- `MilestoneReward`
- `NexRewardPolicy`

### 接口族

- `/api/admin/phase/dials`
- `/api/admin/trial/config`
- `/api/trial/eligibility`
- `/api/admin/quests/**`
- `/api/config/quest/day-one`
- `/api/admin/events/**`
- `/api/admin/checkin/**`
- `/api/admin/milestones/**`

### 更新日志修正

H1 使用 8-dial，移除已下线订阅和旧锁仓 gate。D5/F3/G/H3/E1 等只读消费 H1。

签到和产出渠道重构：

- 签到奖励改发小额 NEX。
- NEX 产出以设备挖矿为主。
- 注册礼包、成就、转盘、复投相关奖励按更新日志下调或移除旧积分描述。
- 历史积分 ledger 只允许迁移或归档。

### 规则

- H 域奖励如果增加 NEX/USDT 流出，必须核 B1。
- Lucky Spin 由 server RNG，前端不得传中奖结果。
- Trial 领取必须拒重领，状态由 server 推进。

## 11. 域 I：内容合规 CMS 与会话中心

### 能力

I 域负责内容与合规 CMS，包括转化文案、Nova 推送、通知 Campaign、信任中心、风险披露、i18n、教程和 I9 会话中心运营。

核心聚合：

- `ContentVersion`
- `NotificationCampaign`
- `DisclosureVersion`
- `I18nMessage`
- `LearningContent`
- `Conversation`
- `ConversationMessage`
- `ConversationScript`
- `AutoPushPolicy`
- `ConversationTransfer`

### 接口族

- `/api/admin/content/**`
- `/api/admin/notifications/**`
- `/api/admin/disclosures/**`
- `/api/admin/i18n/**`
- `/api/admin/learn/**`
- `/api/admin/conversation/config`
- `/api/admin/conversation/sessions`
- `/api/admin/conversation/sessions/{id}/messages`
- `/api/admin/conversation/sessions/{id}/{transfer|accept|return}`
- `/api/admin/conversation/workbench`

### 更新日志修正：I9 跨客服转交

I9 必须支持跨客服转交会话：

- 发起转交：会话内选目标，目标可以是指定坐席、技能队列、备勤池。
- 转交原因必填且不少于 6 字。
- 会话进入“转入待处理”，从发起方工作台移出，挂到目标。
- 接收方三选一：接收接入、等待处理、手动退回。
- 手动退回必须填写退回原因，目标为原坐席或备勤池。
- 工作台可配置超时回落备勤池，默认 30 分钟。
- 客户侧无感，不挂断会话。
- 转交和退回原因写会话系统消息，不写 A2 高敏审计。

I9 配置动作仍是高敏配置动作：

- 类别启停、主动推送总开关、AutoPushPolicy、话术和模板发布需要确认理由。
- `ai` 类别由 I2/Nova 管理，I9 只读展示。
- 话术 CTA 跳下游域，成交归 E/G/F/D 等业务域，I9 不结算。

### 规则

- 内容版本 server-canonical，前端只渲染发布版。
- i18n 中英镜像与占位符必须一致。
- 风险披露 ack 是合规事实，不能由前端本地伪造。
- I7 课程奖励如果发 NEX，必须核 B1。

## 12. 域 J：紧急合规

### 能力

J 域提供 J1 Kill-Switch、J2 Geo-block、J3 篡改防御监控、J4 应急 SOP。

核心聚合：

- `KillSwitchMatrix`
- `GeoBlockPolicy`
- `TamperGate`
- `EmergencySop`
- `EmergencyBroadcast`

### 接口族

- `/api/admin/killswitch/matrix`
- `/api/admin/killswitch/{key}`
- `/api/admin/killswitch/geo`
- `/api/admin/tamper/**`
- `/api/admin/emergency/sop/**`

### 更新日志修正

J1 功能闸收缩为 5 个活跃业务闸加 geo-block 等合规能力，不再为已下线订阅和旧锁仓保留活跃闸。旧审计查询可以保留历史 key，但写接口不得允许恢复下线产品。

### 规则

- 熔断执行为授权角色单人确认即时执行，不再有复核流。
- 恢复方向只允许超管，并且需要 B1 前置核验。
- Geo-block 对财务执行类接口必须强制 enforce。
- J4 SOP 可以编排 J1/J2/I5/C2/K1/D2/I3，但各子动作仍由所属域执行。

## 13. 域 K：风控与反作弊

### 能力

K 域提供多账户簇、套利刷量检测、提现风控规则、风险评分、大额 KYC 复审与告警。

核心聚合：

- `RiskCluster`
- `AbuseSignal`
- `WithdrawalRiskRule`
- `RiskScore`
- `ManualReviewCase`
- `KycReviewAlert`

### 接口族

- `/api/admin/risk/clusters/**`
- `/api/admin/risk/abuse/**`
- `/api/admin/risk/withdrawal-rules/**`
- `/api/admin/risk/score/**`
- `/api/admin/risk/kyc-review/**`

### 规则

- K4 风险评分是唯一评分源。
- K3 提现规则只能产风险裁决，最终提现推进由 D2 执行。
- K1/K2 产证据，F4d 可根据证据执行排行榜取消资格。
- 风险模型权重修改是高敏动作，必须确认理由和审计。

## 14. 域 L：数据分析 BI

### 能力

L 域提供 KPI 看板、漏斗/cohort/留存、财务报表、设备/任务/网络报表、导出与监管报告。

核心聚合：

- `KpiSnapshot`
- `FunnelCohortReport`
- `FinanceReport`
- `OpsReport`
- `ExportJob`
- `RegulatoryReport`

### 接口族

- `/api/admin/bi/kpi`
- `/api/admin/bi/funnel`
- `/api/admin/bi/finance`
- `/api/admin/bi/ops`
- `/api/admin/bi/exports`
- `/api/admin/bi/regulatory-reports`

### 规则

- L 域只读业务事实，不拥有资金、风控、用户、会话、设备状态写权。
- 含 PII、资金、监管字段或超过 rowCap 的导出必须确认理由并写审计。
- 导出文件走 MinIO 短期链接。
- 财务报表引用 B1、D3、D4，不重算储备或负债口径。
- BI 查询可用快照和缓存，但必须可追溯到 server-canonical 事实源。

## 15. 更新日志覆盖清单

| 变更 | 所属域 | 技术落地 |
| --- | --- | --- |
| 客服中心跨客服转交会话 | I9 | `transfer/accept/return/workbench`，原因写系统消息，转交不写 A2 高敏审计 |
| 设备撤销回收 | E3b | `POST /api/admin/devices/{id}/restore`，recycled -> offline |
| 客服计数与身份优化 | I9 | 会话队列实时派生进行中、待回复、转入待处理计数 |
| G3 NEX 周曲线关键帧排程器 | G3 | 7 日关键帧、每日 UTC 推进、周峰值价重估 B1 |
| 下线积分系统 | H/D/C | 不再新增积分激励；历史积分仅迁移、清算、归档 |
| 下线 Premium 订阅 | G/J/H | 不再提供活跃入口、配置、gate、kill 闸 |
| 下线 NEX v2 锁仓 | G/J/H/B | 停止新开；存量到期兑付与历史负债兼容 |
| 提现 NEX 闸 | D5/H1 | H1 `withdrawNexGate` 权威，D5/D2 只读镜像 |
| 签到改发 NEX | H5 | 奖励策略改为 NEX，放大流出核 B1 |
| 技术标识符中文化 | A-L | 后台展示中文名，技术 key 保留为小号副标和契约字段 |

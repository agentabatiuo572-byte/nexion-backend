# 七层购买与直属设备奖励服务契约

功能裁决以主人批准的 `specs/seven-layer-direct-split/DESIGN.md` 为准。本契约是后端现行功能与接口说明；代码交付、隔离运行验证和生产切换分别记录，不代表生产已经启用。

购买只走可靠 checkout 消费入口。服务重读支付订单、购买基数和来源身份；最多七层按原费率、资格、Influence、活动倍率及整链上限分配，原 L1 基础费率固定 10%。仅明确 v2 政策启用的 L1 将原最终预算按比例拆 USDT/NEX，替代原 L1 额外 NEX；L2–L7 仍按原构成。关闭拆分仍属于新七层代际，恢复未来 L1 原构成，不停用七层。

直属设备奖励只消费真实设备结算凭证：已入账 USDT 加已入账 NEX 按当笔锁价折算，再按独立设备比例与两币占比计额。平台额外支付，本人原收益不减；佣金、礼金、影子与测试产出不递归参与。对碰、V 等级、培育、领导池及注册礼保持独立规则。

## 来源、状态与资金

`nx_unilevel_order_settlement` 以环境、runId、原订单唯一，固定 `LEGACY_7`、`DIRECT_ONLY_V1`、`SEVEN_V2` 或待核对冲突，保存七层含封顶、不合格和暂停决定。显式 `team.seven-layer.cutover-at` 是发布边界；迁移不种入生产切换时间或正式费率。任何既有旧账优先决定代际；无证据不补历史深层。

准备来源和预算、锁定价格与两币金额、物化账项与钱包分别使用事务。缺价持久为 `WAITING_CALCULATION`，不默认价格、不转全 USDT、不换上级或政策。新拆分按六位向下取整；任一币归零为小额未发，原预算不转送深层。购买冷却从首次准备起算，设备冷却从凭证确认起算；恢复保留原时间。冷却、风险冻结和未实际钱包入账均不可称到账。

资金组关联原订单和层号。`credited_usdt_at` / `credited_nex_at` 是逐币真实到账时间，`credited_at` 是全部有效币完成时间；`cancelled_usdt` / `cancelled_nex` 是已取消的权益目标，`recovered_*` 是实际追回，`recovery_pending_*` 是欠额。有效金额为原金额减取消目标，不能把欠额当有效收入。

新代际全额退款先锁权威原订单并标禁止后续发放，再逐受益人、逐币取消或追回。每次物化、自动释放、F5 操作都重读该订单，异步退款通知延迟不能产生新发放。余额不足或某钱包暂不可用留欠额，不阻止其他可追回奖励。重复退款不重复扣款，未经支持的部分退款拒绝。F5 补发沿 source/result 账项谱系追溯原订单，已退款原来源禁止再补发。

人工 F5 对 `network` 保留单账项范围，只操作批准的币；直属两类按双币组操作。单币冲正不取消另一有效币，全额订单退款仍覆盖整链两币。有效过去设备收益不因设备退货自动撤销。

## v2 接口

下列读接口传 `schemaVersion=2`；T 前旧读兼容，T 后受影响 Team 旧请求返回 `409 TEAM_SCHEMA_UPDATE_REQUIRED`，账户、钱包及本人设备收益原接口继续。

| 入口 | 请求与响应 |
|---|---|
| `GET /api/config/commission/direct-referral`、`GET /api/admin/teams/direct-referral-policy` | `configured` 仍表示任一已有政策；`policySchemaVersion` 是保存的政策代际；`purchaseSplitConfigured` 独立表示有无 v2 购买拆分政策；`purchaseSplit={enabled,usdtSharePct}`、`deviceEarning={enabled,totalRatePct,usdtSharePct,coolingDays}`；`sevenLayerReference={revision,baseRatePct,coolingDays,legacyNexPerUsd}`、`sevenLayerRevision`、`settlementMode`、`sevenLayerEnabled`、`cutoverAt`。未实际配置引用值为 null。T 前保留旧 `purchase` 只读投影，T 后移除。 |
| `PUT /api/admin/teams/direct-referral-policy` | `schemaVersion:2,expectedVersion,expectedSevenLayerRevision,purchaseSplit,deviceEarning,reason` 加 `Idempotency-Key`，必须 A2 审批重放；不接受 `purchase` 或第二套购买总比例/冷却。T 前 v2 写入拒绝 `SEVEN_LAYER_NOT_ACTIVE`。 |
| `GET /api/admin/teams/rates` | 原七层参数和 `sevenLayerRevision`。所有影响七层结算的批准更新递增 revision；政策批准任一引用版本变化拒绝重提。拆分启用时旧 L1 NEX 系数只读。 |
| `GET /api/app/team/insights/unilevel` | 全部购买奖励唯一归属：新 L1 直属组与 network 深层及历史账不重复。期间、筛选、分页和完整汇总契约由实际 App insights v2 DTO 约束。 |
| `GET /api/app/team/insights/direct-referral` | `period=today/week/month/all,page,pageSize,snapshotAt`，`kind=all/purchase/device_earning` 在分页前过滤；`events` 为资金组，`totalRows` 和 `summary={amountUSDT,amountNEX,count,creditedUSDT,creditedNEX,pendingUSDT,pendingNEX}` 是完整期间聚合，`split.purchase/deviceEarning` 保留。`waiting_calculation` 及价格 null 必须可见。 |
| F5 列表与 CSV | 等待价格组由 `pendingCalculations` 展示，不造零额钱包账；常规账项按币保留。CSV 原十列后增加 `sourceRef,settlementNo`，实际币金额、原来源和层位可逐行核对。 |
| 已发布说明 | `team-commissions-how` 为 `schemaVersion:2,templateId:commissions-v2`；network 正文保留 `{directPurchaseRules}{networkPurchaseRules}{directDeviceRules}`。`team-unilevel-how` 为 `unilevel-v2`，含 `seven-layer-scope` 与 `direct-referral-scope`。T 前兼容旧发布正文，T 后只交付匹配的已发布正文；标准发布入口保留审计、理由及版本校验，开发初始化只升级完全匹配的系统模板。 |

A2 与订单准备共用七层 revision 锁。资金放大判断按真实当前构成逐币比较：拆分开关关闭可增加 USDT；已开启拆分的占比变化总会增加一种币。旧 v1 purchase 不能成为 v2 拆分基线。价格不足以判断方向时不得用猜测绕过 B1。

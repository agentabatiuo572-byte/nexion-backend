# Nexion 运营控制后台后端技术文档：API、数据与验收矩阵

## 1. 文档目标

本文定义模块化单体后端的 API 规范、数据模型落地规则、错误码、审计、幂等、B1 红线校验和验收测试矩阵。它配合以下两份文档使用：

- `ops-console-modular-monolith-architecture.md`
- `ops-console-domain-contracts.md`

本文件不生成 Java 代码，不修改数据库脚本。

## 2. API 统一规范

### 2.1 路径

后台接口统一采用：

```text
/api/admin/{domain}/{resource}
```

用户侧或公开配置读取采用：

```text
/api/config/**
/api/{business-domain}/**
```

同一资源只能有一个权威读写路径。其他页面入口只能作为 UI 跳转或兼容别名，不能重复实现。

### 2.2 HTTP 方法

| 方法 | 用途 |
| --- | --- |
| `GET` | 查询、详情、只读配置、导出任务状态 |
| `POST` | 创建、执行动作、状态推进、提交任务 |
| `PUT` | 整体替换配置 |
| `PATCH` | 局部更新 |
| `DELETE` | 仅用于软删除或撤销入口；资金/审计事实禁止物理删除 |

### 2.3 请求头

| Header | 要求 |
| --- | --- |
| `Authorization` | `/api/admin/**` 必填 |
| `X-Nexion-Trace-Id` | 可选；缺失由 server 生成并回传 |
| `Idempotency-Key` | 资金、资产、状态写、kill 切换、导出任务、派奖、转账等必填 |
| `Content-Type` | 写请求统一 `application/json` |

### 2.4 响应包

推荐统一响应：

```json
{
  "code": "OK",
  "message": "success",
  "data": {},
  "traceId": "server-generated-or-forwarded"
}
```

错误响应：

```json
{
  "code": "COVERAGE_BELOW_REDLINE",
  "message": "coverage ratio is below red line",
  "details": {
    "coverageRatio": "0.9842",
    "coverageRedLine": "1.0000"
  },
  "traceId": "..."
}
```

## 3. 错误码

| HTTP | code | 场景 |
| --- | --- | --- |
| 400 | `REASON_REQUIRED` | 高敏写缺少确认理由或长度不在 8-200 字 |
| 400 | `VALIDATION_FAILED` | 字段格式、范围、枚举、日期、金额校验失败 |
| 401 | `UNAUTHORIZED` | 未登录或 token 无效 |
| 403 | `FORBIDDEN` | RBAC 无权、geo-block 拦截、角色门槛不足 |
| 409 | `INVALID_STATE_TRANSITION` | 状态机非法推进 |
| 409 | `IDEMPOTENCY_CONFLICT` | 相同 key 对应不同请求语义 |
| 409 | `RESOURCE_VERSION_CONFLICT` | 乐观锁版本冲突 |
| 422 | `COVERAGE_BELOW_REDLINE` | 放大流出方向未通过 B1 红线 |
| 422 | `PHASE_PARAM_READONLY` | 非 H1 域尝试写 Phase 派发参数 |
| 422 | `NO_RISK_EVIDENCE` | 需要 K 域证据但未提供或不存在 |
| 429 | `RATE_LIMITED` | 后台或 OpenAPI 限流 |
| 500 | `INTERNAL_ERROR` | 未预期服务端错误 |

旧文档中 B1 红线曾出现 `403` 语义，统一修正为 `422 COVERAGE_BELOW_REDLINE`。鉴权与权限不足仍使用 `401/403`。

## 4. 幂等策略

### 4.1 适用范围

必须携带 `Idempotency-Key` 的操作：

- D 域：提现推进、退款、储备注入、人工调账、账单修正。
- C 域：资产调整、账户安全高风险操作。
- E 域：退款、撤销回收、订单状态纠正、Trade-in 审核。
- F 域：佣金补发/撤销、奖池注入、排行榜派奖、配额覆盖。
- G 域：Staking/兑换/Genesis/复投中产生资金或资产影响的动作、G3 恢复行情引擎。
- H 域：奖励派发、Trial 强制扣款、活动奖励结算。
- I 域：内容发布/回滚、披露发布、教程奖励；I9 配置发布。
- J 域：kill-switch 切换、恢复、geo-block 写。
- L 域：含敏感数据导出任务创建。

I9 会话转交、接收、退回本质是状态写，也应带业务幂等 key；但它不写 A2 高敏审计。

### 4.2 记录模型

`IdempotencyRecord` 字段：

- `id`
- `idempotencyKey`
- `actorId`
- `method`
- `path`
- `requestHash`
- `resourceType`
- `resourceId`
- `status`
- `responseCode`
- `responseBodyHash`
- `expiresAt`
- `createdAt`
- `updatedAt`

唯一约束：

- `(actorId, idempotencyKey)`

### 4.3 流程

1. Controller 校验 header 是否存在。
2. Application 计算规范化请求摘要。
3. 插入或读取幂等记录。
4. 如果已成功且摘要一致，返回原结果。
5. 如果摘要不一致，返回 `409 IDEMPOTENCY_CONFLICT`。
6. 执行业务写。
7. 同事务写业务结果、A2 审计、outbox 和幂等结果。

## 5. 审计策略

### 5.1 A2 高敏审计

高敏动作必须写 A2：

- 资金、资产、提现、退款、储备注入。
- kill-switch、geo-block、恢复闸。
- KYC 裁决、风险模型权重、风险分人工覆盖。
- 用户冻结、批量冻结、impersonate。
- 内容发布、披露发布、教程奖励配置。
- BI 敏感导出。
- 运营账号、角色、权限变更。

记录字段：

- `traceId`
- `operatorId`
- `operatorRole`
- `action`
- `resourceType`
- `resourceId`
- `beforeJson`
- `afterJson`
- `reason`
- `riskLevel`
- `clientIp`
- `result`
- `createdAt`

### 5.2 轻量留痕

以下动作不写 A2 高敏审计，但必须可追溯：

- I9 跨客服转交、接收接入、等待处理、手动退回、超时回落。
- 普通客服回复。
- BI 普通只读查询。
- 非敏感配置读取。

I9 转交和退回原因只能进入会话系统消息，不进入 A2 高敏审计 payload。

### 5.3 脱敏

审计、日志、导出任务记录不得保存：

- 密码。
- token。
- secret。
- 私钥。
- 对象存储 secret。
- 原始支付回调敏感体。
- 完整身份证件号、银行卡号、钱包私钥。

允许保存摘要、脱敏片段或对象引用。

## 6. B1 红线校验

### 6.1 触发条件

以下方向必须前置 B1：

- 上调 APY、费率、奖励额、分红率、匹配比例、cap。
- 下调罚款、冷却、提现门槛。
- 恢复出金、恢复交易、恢复高风险产品闸。
- 补发、提前派奖、退款、储备影响类动作。
- G3 调高目标价或上行概率。
- I7/H5/H4 等增加 NEX 或 USDT 奖励。

### 6.2 G3 特殊口径

G3 周曲线变更必须：

1. 取拟提交 7 日关键帧中的周峰值价。
2. 用周峰值价重估所有 NEX 计价负债。
3. 以重估后的负债分母调用 B1。
4. 低于红线返回 `422 COVERAGE_BELOW_REDLINE`，不保存新曲线。

### 6.3 返回数据

B1 拒绝时至少返回：

- `coverageRatio`
- `coverageRedLine`
- `reserveTotalUsdt`
- `liabilityTotalUsdt`
- `blockedReason`
- `domain`
- `action`

## 7. 数据模型落地规则

### 7.1 聚合清单

| 聚合 | Owner | 说明 |
| --- | --- | --- |
| `Admin/RBAC/Audit` | A | 后台身份、权限、审计、事件 schema、幂等 |
| `TreasuryLedger` | B/D | B1 负债分母，D3 储备底账，D4 bill |
| `UserProfile` | C | 用户检索、画像、账户状态 |
| `Wallet/Withdrawal` | D | 钱包、提现、充值、账单、NEX 提现闸 |
| `Device/TradeIn` | E | 设备、商品、订单、Trade-in、撤销回收 |
| `TeamCommission` | F | 分销、佣金、排行榜、奖池 |
| `NexMarketCurve` | G | G3 周曲线、NEX 现价、oracle、override |
| `GrowthPhase` | H | H1 8-dial、Trial、Quest、活动、签到 |
| `Conversation` | I | I9 会话、消息、转交、话术、AutoPushPolicy |
| `KillSwitch` | J | J1/J2 应急闸和地域策略 |
| `RiskCase` | K | 风控证据、评分、复审、提现规则 |
| `BIReport` | L | KPI、报表、导出、监管报告 |

### 7.2 下线模型

不得新增以下活跃聚合：

- `PremiumSubscription`
- `NexV2Vault`
- `PointsReward`

允许出现的兼容形态：

- `LegacyProductArchive`
- `LegacyLiabilityItem`
- `LegacyPointsMigration`

命名和注释必须明确“历史兼容”“到期兑付”“迁移归档”，不能出现“新建订阅”“开启旧锁仓”“积分奖励配置”等活跃语义。

### 7.3 表设计原则

- 每个事实表有 server 生成业务号。
- 金额字段用 decimal，不用浮点。
- 状态字段用明确枚举。
- 重要写表有 `version` 乐观锁。
- 软删除字段不得影响审计和账本事实。
- 导出和报表使用快照表，不能代替事实表。

## 8. API 家族矩阵

| 域 | API 家族 | 读权限 | 写权限 | 幂等 | 审计 | B1 |
| --- | --- | --- | --- | --- | --- | --- |
| A | `/api/admin/rbac/**`、`/api/admin/audit/**`、`/api/admin/system/**` | platform read | platform write / superadmin | 部分 | 高敏写 | 否 |
| B | `/api/admin/treasury/coverage`、`/api/admin/treasury/thresholds` | finance/risk read | finance lead / superadmin | 写时视场景 | 阈值写 | 裁决源 |
| C | `/api/admin/users/**` | user read | user write / risk / finance | 资产和状态写 | 高敏写 | 加余额触发 |
| D | `/api/admin/withdrawals/**`、`/api/admin/bills/**` | finance read | finance lead / superadmin | 是 | 是 | 是 |
| E | `/api/admin/products/**`、`/api/admin/devices/**` | commerce read | commerce write | 状态写是 | 高敏写 | 退款/补贴触发 |
| F | `/api/admin/team/**`、`/api/admin/commissions/**` | team read | team lead / finance | 是 | 是 | 放大奖励触发 |
| G | `/api/admin/market/nex/**`、`/api/admin/staking/**` | finance/growth read | finance/growth lead | 是 | 是 | 是 |
| H | `/api/admin/phase/**`、`/api/admin/events/**` | growth read | growth lead | 奖励写是 | 高敏写 | 奖励触发 |
| I | `/api/admin/content/**`、`/api/admin/conversation/**` | content/support read | content/support lead | 配置/状态写是 | 配置高敏；转交轻量 | 奖励触发 |
| J | `/api/admin/killswitch/**` | risk/finance read | risk/finance/superadmin | 是 | 是 | 恢复触发 |
| K | `/api/admin/risk/**` | risk read | risk lead / superadmin | 视动作 | 高敏写 | 间接 |
| L | `/api/admin/bi/**` | bi/finance/risk read | export permission | 导出任务是 | 敏感导出 | 否 |

## 9. 关键场景验收

### 9.1 I9 跨客服转交

验收场景：

- 发起转交时，原因少于 6 字被拒绝。
- 转交目标排除当前 owner。
- 成功后会话进入转入待处理，从发起人队列移出。
- 接收接入后 owner 改为接收坐席，状态回 open。
- 等待处理不改变转交态。
- 手动退回必须填写原因，回原坐席或备勤池。
- 超时回落开关打开时，超过默认 30 分钟自动回落备勤池。
- 客户侧消息线程不断开。
- 转交原因写系统消息，不写 A2 高敏审计。
- 实时计数包括进行中、待回复、转入待处理。

### 9.2 E3b 设备撤销回收

验收场景：

- recycled 设备可 restore 到 offline。
- 非 recycled 设备 restore 返回 `409 INVALID_STATE_TRANSITION`。
- 已退款或已销毁设备 restore 返回 `409`。
- restore 写设备生命周期记录。
- restore 写 A2 审计。
- restore 不改 `purchasedAt`。

### 9.3 G3 周曲线

验收场景：

- 一次提交包含 7 天关键帧。
- 当前生效日可由 server 计算并返回。
- 每日 00:00 UTC 自动推进。
- pin 某日时不自动跳下一日。
- loop 模式跑完回到首日。
- hold-last 模式跑完停末值。
- 调高目标价或上行概率时以周峰值重估 B1。
- B1 未过返回 `422 COVERAGE_BELOW_REDLINE`，曲线不生效。
- 暂停行情冻结现价。
- 恢复行情只有超管可执行且需要 B1。

### 9.4 提现 NEX 闸

验收场景：

- H1 返回 `withdrawNexGate`。
- D5 只读展示，不允许写该字段。
- D5 写入该字段返回 `422 PHASE_PARAM_READONLY`。
- 提现审核显示用户 NEX 闸是否满足。
- 用户 NEX 不足时不能放行提现。
- 成功放行时 NEX 销毁和提现推进同事务或通过可靠 outbox 保证最终一致。

### 9.5 下线能力

验收场景：

- 后台菜单不出现已下线订阅入口。
- J1 不出现已下线订阅活跃闸。
- H1 不出现已下线旧锁仓 gate。
- G 域不提供新建旧锁仓入口。
- 历史旧锁仓只允许只读、兑付、迁移。
- 旧积分不作为新奖励配置项。
- `rg` 命中已下线关键词时，文档上下文必须是下线、历史、迁移、兼容、归档。

### 9.6 A2 / 幂等 / 红线

验收场景：

- 高敏写无 reason 返回 `400 REASON_REQUIRED`。
- 资金写无 `Idempotency-Key` 返回 `400 VALIDATION_FAILED` 或专用缺失码。
- 相同 key 重试返回首次结果。
- 相同 key 不同 payload 返回 `409 IDEMPOTENCY_CONFLICT`。
- 放大流出低于 B1 返回 `422 COVERAGE_BELOW_REDLINE`。
- 状态机非法转移返回 `409 INVALID_STATE_TRANSITION`。
- A2 审计与目标写失败同回滚。

## 10. 文档结构验收

目标文件必须存在：

- `D:\workspace\nexion-backend\docs\ops-console-modular-monolith-architecture.md`
- `D:\workspace\nexion-backend\docs\ops-console-domain-contracts.md`
- `D:\workspace\nexion-backend\docs\ops-console-api-data-acceptance.md`

每个文件必须包含：

- 标题。
- 来源说明。
- 更新日志优先级说明。
- 单体或域边界说明。
- 已下线能力处理说明。
- 可执行的验收或实现规则。

## 11. 覆盖验收命令

在 PowerShell 中执行：

```powershell
rg -n "域 A|域 B|域 C|域 D|域 E|域 F|域 G|域 H|域 I|域 J|域 K|域 L|I9|G3|E3b|D5|H1|J1|B1|Idempotency-Key|A2|COVERAGE_BELOW_REDLINE|PHASE_PARAM_READONLY" D:\workspace\nexion-backend\docs\ops-console-*.md
```

下线语境检查：

```powershell
rg -n "Premium|NEX v2|积分系统|积分" D:\workspace\nexion-backend\docs\ops-console-*.md
```

命中必须落在下线、历史兼容、迁移、归档、旧账清理、命名消歧等语境。

敏感信息检查：

```powershell
rg -n "local password|local credential|plaintext credential|hardcoded key|private key block" D:\workspace\nexion-backend\docs\ops-console-*.md
```

应无命中。文档只允许写环境变量名，不允许写本地账号密码。

## 12. 初审与复审评分标准

初审目标：不低于 96 分。

评分项：

- 20 分：来源覆盖完整，包含 PRD、开发落地规格、投产 Checklist、产品更新日志。
- 20 分：模块化单体口径清晰，代码边界可执行。
- 20 分：A-L 12 域契约完整，接口族和 owner 清晰。
- 15 分：横切机制完整，包含 server-canonical、A2、幂等、B1、RBAC、导出。
- 15 分：更新日志修正全部落地。
- 10 分：无敏感信息、无已下线能力误激活。

复审目标：不低于 98 分。

复审必须重复初审检查，并额外确认：

- 三份文档彼此不矛盾。
- 下线语境无歧义。
- 每个新增更新日志项都有接口或验收场景。
- 实现人员无需再决定架构口径或核心边界。

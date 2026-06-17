# Nexion 运营控制后台后端技术文档：模块化单体架构

## 1. 文档定位

本文定义 Nexion 运营控制后台的后端目标架构。目标是把 PRD v1-v4、开发落地规格、投产 Checklist 和产品更新日志收敛成可实现的模块化单体技术蓝图。

来源：

- `D:\workspace\nexion-ops-console\docs\PRD\Nexion_运营控制后台PRD_v1.md`
- `D:\workspace\nexion-ops-console\docs\PRD\Nexion_运营控制后台PRD_v2.md`
- `D:\workspace\nexion-ops-console\docs\PRD\Nexion_运营控制后台PRD_v3.md`
- `D:\workspace\nexion-ops-console\docs\PRD\Nexion_运营控制后台PRD_v4.md`
- `D:\workspace\nexion-ops-console\docs\PRD\Nexion_运营控制后台_开发落地规格.md`
- `D:\workspace\nexion-ops-console\docs\PRD\Nexion_运营后台投产Checklist.md`
- `D:\workspace\nexion-ops-console\docs\产品更新日志.md`
- `D:\workspace\nexion-backend\README.md`

产品更新日志优先级高于旧 PRD 摘要。凡更新日志声明下线的能力，不再作为活跃功能设计。

## 2. 架构结论

目标架构为模块化单体：

- 一个 Spring Boot 后端应用进程承载运营控制后台 API。
- 一个主 MySQL 数据库事务边界承载后台写操作。
- 业务按 A-L 12 域拆分为独立包、聚合、应用服务和持久化边界。
- 运行时可以使用 Redis、MinIO、RocketMQ，但它们是基础设施能力，不改变单体部署口径。
- 不按微服务拆部署，不通过网关或远程调用制造域间边界。
- 现有 Maven 多服务代码可作为迁移来源，目标文档不要求本次立即重构 Java 代码。

单体不是无边界。代码边界必须比部署边界更严格：Controller 只能调用本域 Application Service，跨域只能通过公开 Facade 或 Domain Event，不允许直接访问其他域 Mapper、Entity 或表实现细节。

## 3. 推荐工程结构

推荐未来新增单体模块时采用以下包形态：

```text
ffdd.opsconsole
  NexionOpsConsoleApplication
  common          # 运营域契约、边界标记、错误码、域目录
    api
    boundary
    domain
  shared          # 横切基础设施，不表达业务域
    api
    audit
    config
    exception
    outbox
    persistence
    rocketmq
    security
    storage
  platform        # A 平台基础
  treasury        # B 双账本驾驶舱
  user            # C 用户与账户
  finance         # D 资金与财务
  device          # E 设备与商城
  team            # F 分销团队
  market          # G 金融产品与 NEX 行情
  growth          # H Phase 与增长活动
  content         # I 内容与合规 CMS / I9 会话中心
  emergency       # J 紧急合规
  risk            # K 风控
  bi              # L 数据分析 BI
```

每个业务域内部保持同构结构：

```text
{domain}
  web             # Controller、Request、Response、参数校验
  application     # 用例编排、事务边界、权限与幂等入口
  domain          # 聚合、状态机、领域规则、Domain Event
  facade          # 给其他域调用的稳定接口
  infrastructure  # Mapper、Repository、外部适配器、缓存
```

禁止事项：

- `web` 层直接调用 Mapper。
- 任意域直接引用其他域的 `infrastructure`、Mapper、Entity。
- Controller 组装跨域业务流程。
- 前端字段、mock store、localStorage 状态成为后端权威。
- 为相同业务对象重复建表或重复计算权威字段。

## 4. 删除旧目录后的单体边界

当前 `D:\workspace\nexion-backend` 只保留一个活跃后端工程：根目录 `pom.xml` + 根目录 `src`。旧分布式服务目录、旧 `nexion-common` 公共模块和临时子服务模块已物理删除。

| 目标域 | 单体职责 | 单体代码边界 |
| --- | --- | --- |
| A 平台基础 | admin、RBAC、A2 审计、A4 事件、配置、幂等 | `ffdd.opsconsole.platform` |
| B 双账本驾驶舱 | 覆盖率、负债分母、风险水位展示 | `ffdd.opsconsole.treasury` |
| C 用户与账户 | 用户检索、360 画像、账户操作、KYC 引用 | `ffdd.opsconsole.user` |
| D 资金与财务 | 充值、提现、D3 储备、D4 账单、D5 参数 | `ffdd.opsconsole.finance` |
| E 设备与商城 | SKU、订单、设备生命周期、Trade-in、撤销回收 | `ffdd.opsconsole.device` |
| F 分销团队 | V-Rank、佣金、双轨、奖池、排行榜 | `ffdd.opsconsole.team` |
| G 金融产品 | Staking、兑换、Genesis、复投、G3 NEX 周曲线 | `ffdd.opsconsole.market` |
| H Phase 与增长活动 | H1 8-dial、Trial、Quest、活动、签到、里程碑 | `ffdd.opsconsole.growth` |
| I 内容合规 CMS | 文案、通知、披露、i18n、教程、I9 会话 | `ffdd.opsconsole.content` |
| J 紧急合规 | J1 Kill-Switch、J2 Geo-block、J3 监控、J4 SOP | `ffdd.opsconsole.emergency` |
| K 风控 | 多账户、套利刷量、提现规则、评分、复审 | `ffdd.opsconsole.risk` |
| L 数据 BI | KPI、cohort、财务报表、运营报表、导出 | `ffdd.opsconsole.bi` |

迁移要求：所有新增实现都必须落在上述单体包边界内。不得恢复旧分布式模块目录，也不得把原微服务远程边界原样搬进单体内部。

## 5. 全局铁律

### 5.1 server-canonical

所有后台控制态必须由 server 生成、校验和持久化。前端和高保真原型只负责渲染、交互和提交意图：

- 状态机推进由 server 校验合法转移，非法转移返回 `409`。
- ID 由 server mint。
- 时间统一以 UTC 存储，展示层再本地化。
- 币种、金额和精度由后端类型约束，禁止前端传入已计算的权威结果。
- BI 与驾驶舱只读视图不得反向写业务表。

### 5.2 Confirm-with-Reason

高敏写操作必须使用业务专属确认弹窗提交到目标域 API，并在 server 强制校验：

- `reason` 必填，长度 8-200 字，缺失返回 `400 REASON_REQUIRED`。
- 写入目标表与 A2 审计必须在同一事务内完成。
- 审计记录包含 operator、role、action、resource、before、after、reason、IP、traceId、时间。
- 高敏写执行后实时告警相关域 lead 和超管。
- 不再设计二次审批流；2026-06 决议为授权角色单人确认后即时执行。

### 5.3 Idempotency-Key

资金、资产、状态写操作必须携带 `Idempotency-Key`：

- 默认去重窗口 24 小时，允许通过 A3 配置到 1-72 小时。
- 相同 key、相同请求语义重复提交，返回首次结果，不重复生效。
- 相同 key、不同请求语义，返回 `409 IDEMPOTENCY_CONFLICT`。
- 幂等记录必须保存请求摘要、结果摘要、操作者、资源、过期时间。

### 5.4 B1 覆盖率红线

所有放大资金流出方向的写操作必须先核 B1 覆盖率红线：

- 低于 `coverageRedLine` 返回 `422 COVERAGE_BELOW_REDLINE`。
- 只要操作会增加应付、降低门槛、提高奖励、提高价格、恢复出金或恢复风险闸，都按放大流出处理。
- G3 NEX 目标价或上行概率调整需用拟生效周峰值价重估 NEX 计价负债。
- B1 是裁决口径，D3 是真实储备底账来源，其他域不得重算覆盖率。

### 5.5 Phase 权威

H1 是 Phase 参数唯一权威。更新日志已将 Phase 调整为 8-dial，已下线的 Premium 与 NEX v2 gate 不再作为活跃 dial。

现行 H1 8-dial：

- `newUserBonusMultiplier`
- `inviteRewardMultiplier`
- `reinvestMultiplier`
- `withdrawNexGate`
- `withdrawCooldownDays`
- `binaryDailyCap`
- `questBonusMultiplier`
- `complianceHoldEnabled`

D5、F3、G、H3、E1 等生效面只能读取 H1 派发现值。收到 Phase 只读参数写入时返回 `422 PHASE_PARAM_READONLY`，并返回跳转提示 `/admin/phase/h1`。

### 5.6 下线能力

以下能力不得作为新增活跃功能入口：

- Premium 订阅：只允许在历史迁移、审计查询或旧记录兼容中出现。
- NEX v2 Founders Vault：停止新开，只允许处理历史存量到期兑付或迁移。
- 积分系统：整套下线，增长激励改由 NEX 承接；历史积分只允许迁移、清算、只读归档。

J1 Kill-Switch 不再保留上述已下线能力的活跃功能闸。H1 不再保留对应解锁旋钮。

## 6. 模块依赖规则

### 6.1 允许依赖

- `web -> application -> domain`
- `application -> facade`，仅调用其他域公开 Facade。
- `application -> common/shared`，使用本服务内的运营域契约、审计、幂等、权限、事件发布、存储等横切能力。
- `infrastructure -> domain`，将数据库记录转为领域对象。
- `bi -> facade`，BI 只能读其他域公开查询 Facade。

### 6.2 禁止依赖

- `controller -> mapper`
- `domain A -> infrastructure B`
- `domain A -> mapper B`
- `domain A -> entity B`
- `bi -> mapper of business domain`
- `frontend DTO -> persistence entity`

### 6.3 跨域写流程

跨域写流程必须由主用例域编排，并通过 Facade 或事件完成：

1. Controller 接收请求并校验基础参数。
2. Application Service 校验权限、幂等、确认理由。
3. 主域加载本域聚合并校验状态机。
4. 必要时调用只读 Facade 获取 B1、K、C 等裁决信息。
5. 主域落库。
6. 同事务写 A2 审计、Outbox 事件、幂等结果。
7. 事务提交后异步投递 Domain Event。

示例：D2 提现放行由 D 域主导，调用 B1 覆盖率、K 风控、C KYC 只读 Facade，最终由 D 写提现状态、D4 bill 和 A2 审计。

## 7. 基础设施策略

### 7.1 MySQL

MySQL 是交易事实源：

- 后台写操作必须落主库事务。
- 资金与资产类表必须具备唯一业务号、乐观锁或幂等约束。
- 查询表、报表表、快照表不得成为业务事实源。
- D4 bill、A2 audit、outbox、idempotency 记录需要与目标业务写同事务。

### 7.2 Redis

Redis 只用于缓存、短 TTL 快照、限流与分布式互斥：

- 不能作为唯一事实源。
- BFF、BI 或 Dashboard 缓存必须可回源重建。
- 幂等最终裁决以 MySQL 记录为准，Redis 可做快速拒重。

### 7.3 MinIO

MinIO 用于证据、附件、导出文件：

- 对象 key 不写入审计明文敏感信息。
- 下载链接必须短期有效。
- 含 PII、资金或监管数据的导出必须走 L5 导出审计。

### 7.4 RocketMQ

模块化单体内优先使用本地事务和 outbox。RocketMQ 是可选外部投递机制：

- 单体内部不能用 MQ 替代同步事务一致性。
- 需要外部消费者或异步任务时，通过 outbox 可靠投递。
- 毒消息进入 DEAD 状态，并暴露运营查询能力。

## 8. 认证、权限与审计

后台统一使用 Admin/RBAC：

- 所有 `/api/admin/**` 需要后台登录态。
- 读权限与写权限分离。
- lead、finance、risk、support、content、superadmin 等角色只在权限矩阵层表达，不写死到 Controller。
- 高敏动作的执行门槛由 A2/A1 权限矩阵裁决。
- impersonate、批量导出、资金调整、kill 恢复等高风险动作需附加确认理由。

审计统一由 A2 提供：

- append-only。
- 保留不少于 13 个月。
- 敏感字段脱敏或摘要化。
- I9 转交类例行内部交接不写 A2 高敏审计，原因只写会话系统消息和轻量变更留痕。

## 9. 实时与任务

实时能力按 server-canonical 推送：

- B5 风险雷达、D2 提现队列、I9 会话计数、G3 行情现价可使用 SSE/WS。
- 客户端接收推送后必须重拉或合并 server 状态，不本地推进状态机。

定时任务按域归属：

- G3 每日 00:00 UTC 推进当日周曲线关键帧。
- I9 转入待处理超时回落按工作台开关和默认 30 分钟阈值执行。
- D、F、H、K、L 的周期结算、统计、导出生成由所属域 Scheduler 发起。
- Scheduler 也必须使用幂等业务号，防止重复结算。

## 10. 与高保真项目的关系

`D:\workspace\nexion-高保真` 是交互演示项目，其 README 声明数据为本地 mock，不接真实后端。因此：

- 高保真只作为页面结构、交互与运营语言参考。
- 真实接口、状态机、权限、审计、B1 红线和数据所有权以 PRD、开发落地规格、更新日志和本文为准。
- 不得把高保真 mock store 当作后端模型来源。

## 11. 实施顺序建议

1. 先建立单体应用骨架和 shared 横切能力：auth、RBAC、audit、idempotency、error、trace、outbox。
2. 迁入 A、B、D 三类基础安全域，先保证资金与审计闭环。
3. 迁入 C、E、G、H 的运营控制面，优先覆盖更新日志变更。
4. 迁入 I9 会话中心、J 应急、K 风控、L BI。
5. 为每个域补契约测试、状态机测试、权限测试、审计测试和端到端 smoke。

任一阶段都不得重新激活已下线的订阅、旧锁仓或积分功能。

# Support analytics API、权限与事实合同

最后更新：2026-10-09

产品入口、指标算法、分组和排行榜职责归 FE 工程的 `docs/PRD/M-专属客服中心功能契约.md`。本文只承载本仓端点、权限及事实边界，不建立第二套产品算法。会话协议与既有工单/附件能力继续按本仓现有合同执行。

## 1. 权限边界

每次读取和返回前从登录态解析当前权限、个人服务资格、主管资格及负责组，不接受客户端角色或授权集合。`PERSONAL` 为本人合法绑定；`MANAGED` 为当前负责组并集；`ALL` 仅总管理员的获准客服域。无组主管为空集合。个人与管理资格分开，兼任个人服务不扩组范围。公开榜是窄字段例外，不改变客户/会话/工单私聊/附件/导出对象权。

组/客户/资格变更后再次校验在途查询，撤销旧读取、写入、实时订阅和图片访问；历史事件归属不能恢复已撤销明细权。账户、资金等动作仍须原业务模块授权。

## 2. 端点合同

下表统一前缀 `/api/admin/content`；运营账号使用 `/api/admin/platform`。

| Method / 路径 | 功能职责与边界 |
|---|---|
| GET `/support-workbench/analytics` | `service_m1_read` 或 `service_m3_read`；服务端解析范围，查询版本绑定权限/事实，全量筛选排序后分页 |
| GET `/support-workbench/overview`、`/customers`、`/customers/{id}`、`/customers/{id}/360` | 兼容快照及当前对象资料；不开放全局客户旁路 |
| GET `/support-workbench/customers/{id}/flows`、`/devices`、`/maintenance/history` | 当前对象授权与独立分页/字段状态；不把已加载页当累计 |
| GET `/support-agents/groups`、`/{id}`、`/members/{adminId}` | `service_m1_read` 与当前范围交集 |
| GET `/support-agents/groups/supervisors` | `platform_a1_read` 且总管理员；真实主管目录 |
| POST `/support-agents/groups` | `service_m1_write`；主管负责人固定本人，总管理员可指定合格主管 |
| PATCH `/support-agents/groups/{id}/name`、`/status` | 原写权限与组管理范围；停用/归档先满足退出条件 |
| PATCH `/support-agents/groups/{id}/owner`、`/members/{adminId}`、`/customer-routes/{customerId}` | 移交/成员/路由；来源目标范围、资格、前后版本、理由及 Key；跨主管/待分组/全局路由仅总管理员 |
| PATCH `/support-agents/{adminId}/qualification` | 原账号治理权限及总管理员资格；服务/主管资格独立，退出先交接 |
| POST `/support-agents/assignments/transfer`；GET/PUT `/support-agents/rules` | 正式绑定与继承配置，不恢复临时转接；既有幂等、版本、理由和审计 |
| GET `/support-workbench/leaderboard`、`/{agentId}`、`/{agentId}/avatar` | `service_m1_read` 或 `service_m3_read`及有效客服域资格；公开白名单，详情不隐带客户/交易/附件 |
| POST `/accounts`、PATCH `/accounts/{id}/profile`（platform 前缀） | 原 A1 权限；新建性别/默认头像及显式更换，资源ID版本持久化，旧像不自动改 |

端点、字段与正式生产路径闸门分别验收；本文规定功能契约，不证明实现可用或环境已部署。

### analytics 查询

只接受单值白名单参数：`view,category,firstState,filter,keyword,basis,from,to,businessZone,groupId,agentId,currency,sortKey,direction,pageNum,pageSize,expectedVersion`。重复、未知参数和客户端范围声明拒绝。

视角 `OVERVIEW/AGENTS/CUSTOMERS/FINANCE/DEVICES/ACTIVITY`；客户分区 `ALL/BOUND/PENDING/ANOMALY`；首充状态 `ALL/CONFIRMED/NONE/UNKNOWN`。默认客户视角、当前客户历史口径；`PERIOD_EVENT` 要求合法 `[from,to)`，当前历史口径不接受期间。当前业务时区 `Asia/Shanghai`；币种和排序字段从服务端能力集合取。默认第1页、每页20，范围1–100；后页携原 `expectedVersion`。非法/越权/版本变化分别拒绝，不静默放宽。

当前 analytics 顶层字段为 `view/basis/businessZone/asOf/pageNum/pageSize/total/observedTotal/records/versionState/queryVersion/canContinue/recordsStatus/selectedCustomerCount/selectedCurrent/scopeSummary/funds`。`queryVersion` 绑定查询、权限与事实证据，不另返回完整标准化查询、`sourceVersion/definitionVersion` 或 `rules`；具体口径由产品合同及各字段投影解释。通用计数为 `observed/confirmed/status`，金额另含观察/确认事件数及观察客户数，金额值为 decimal 字符串；未知按对应状态及可空确认值表达，不统一为 `null + reason`。资金读取另有各自状态/原因字段，部分源失败不伪造0或完整值。

### leaderboard 查询与发布

只接受 `board,month,currency,scope,groupId,keyword,pageNum,pageSize,expectedVersion` 单值参数。榜键 `firstPayment/deposit/purchase/customers`；范围 `all/ownGroup/managedGroups` 来自服务端获准集合。客户规模榜不接受月份，月榜仅接受真实覆盖月；金额币种由服务端集合限定。响应保留 `viewVersion/queryVersion/sourceVersion/definitionVersion`、真实 `asOf/publishedAt`、候选及指标覆盖、状态、本人摘要、公开行、可比日末基线及原因。

公开行仅授权展示身份、组标签、资格、聚合、排名及覆盖；授权头像经独立私有读取，不附业务对象地址。成功计算原子发布，分页/详情/本人同版；过期可用版标真实时间，失败不发布半榜。资格/范围仍按读取时重验。日末基线不能由当天补数重写；无法证明可比时返回明确不可比。

## 3. 事实来源与安全降级

资格/组/成员/负责人/客户绑定/路由历史是授权与归属事实；当前与事件时两种口径保持分开，未知不能补当前值。付款事实由现有合法充值、钱包设备购单、付费试用转正式、差价置换、保留旧机另购和订单退款源适配；源ID去重并保留环境/成功时间/金额/币种/原单关联及覆盖。

成功充值不允许退款；充值业绩额为本期成功充值额，不存在充值退款扣减，也不以缺充值退款源禁榜。缺真实成功充值全历史、事件时归属或充值来源失败时仍为 `UNKNOWN`，当时未分配不摊派给个人；真实0须有完整覆盖证据。购机业绩额为本期成功购买原单金额扣截至榜单时原单已成功退款，跨月退款归原购买月份及原客服，两类金额不相加。缺购机付款、原单关联或退款覆盖仍未知；充值不退款不代表购机退款源或参榜资格历史完整。未知/失败不获完整排名或确定荣誉。购机退款事件统计与榜单原单有效额各保持已批产品口径，不借新 API 改写已有经营统计。

提现、钱包、设备、活动、邀请画像均读取各领域真实服务投影，累计独立于明细分页。跨组邀请只提供获准聚合，不输出越权后代身份；设备IDC托管不构造闲置或100%在线，缺遥测为未知/不适用。新建继承配置默认无限，既有显式配置和关系不追溯；读取失败不得当未配置。

性别 `MALE/FEMALE/UNSPECIFIED` 由有权操作者明确提供；服务账号新建默认头像仅在许可且审核通过的本地池选择一次，经受控资源校验绑定并持久化。目录或策略不可用保留缺失态；重放、刷新、改性别不重抽，显式更换失败保留旧资源。

## 4. 命令、异常与验收

高敏命令沿原确认、理由、Key、expectedVersion、事务和审计。`PROCESSING/UNKNOWN` 查询原命令或同键同载荷重试；版本冲突读回差异，确认失败保留输入和取消出口。无权直接拒绝，不能仅靠菜单隐藏。

本仓验收需覆盖查询白名单/重复参数/非法币种排序、本人/负责组/全组及无组、旧范围在途撤权、跨页同版与真实0/未知、来源重放/同刻归属/跨币去重、成功充值不退款且缺充值历史/归属/失败源仍未知、私有对象不随公开榜扩权、购机原单跨月退款及缺源不可完整排名、日末基线可比、组/资格退出原子性、新安装无限与存量保留、头像随机一次/重放不重抽及M3原协议失败恢复。产品端入口与交互验收归各 owning 工程，接口单测或文档声明不替代真实读写刷新证明。

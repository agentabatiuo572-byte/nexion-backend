# Support analytics API、权限与事实合同

最后更新：2026-10-14

产品入口、指标算法、分组和排行榜职责归 FE 工程的 `docs/PRD/M-专属客服中心功能契约.md`。本文只承载本仓端点、权限及事实边界，不建立第二套产品算法。会话协议与既有工单/附件能力继续按本仓现有合同执行。

验收状态及接续步骤见[本仓交接索引](support-center-handoff-20261014.md)；未运行分支不改变下列产品合同，也不因源码交付转为通过。

## 1. 权限边界

每次读取和返回前从登录态解析当前权限、个人服务资格、主管资格及负责组，不接受客户端角色或授权集合。`PERSONAL` 为本人合法绑定；`MANAGED` 为当前负责组并集；`ALL` 仅总管理员的获准客服域。无组主管为空集合。个人与管理资格分开，兼任个人服务不扩组范围。公开榜是窄字段例外，不改变客户/会话/工单私聊/附件/导出对象权。

工作台 `scope.availableModes` 返回当下实际可读范围。会话收件箱仅在同时获准时合并 MANAGED 与 PERSONAL、按会话号去重，保留兼任主管在负责组外的本人合法会话；组统计仍只读所选组范围。每个集合请求及对象操作独立重新授权，能力字段不能替代授权或赋予代发权。

组/客户/资格变更后再次校验在途查询，撤销旧读取、写入、实时订阅和图片访问；历史事件归属不能恢复已撤销明细权。账户、资金等动作仍须原业务模块授权。

## 2. 实际端点与调用合同

下表统一前缀 `/api/admin/content`；运营账号使用 `/api/admin/platform`。

| Method / 路径 | 当前职责与边界 |
|---|---|
| GET `/support-workbench/analytics` | `service_m1_read` 或 `service_m3_read`；服务端解析范围，查询版本绑定权限/事实，全量筛选排序后分页 |
| GET `/support-workbench/overview`、`/customers`、`/customers/{id}`、`/customers/{id}/360` | 兼容快照及当前对象资料；不开放全局客户旁路 |
| GET `/support-workbench/customers/{id}/full-profile` | `service_m1_read` 或 `service_m3_read`；正安全编号及当前客户归属校验后复用 C1 全景聚合与原角色字段裁剪，返回原 `{code,message,data}`，不授予 `user_c1hub_read` 或全域查询权 |
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

既有正式生产路径闸门保持生效；上述端点存在不代表闸门已开放或环境已经部署。

`full-profile` 在聚合及查看审计之前重新校验当前客户范围；普通客服读本人合法绑定，主管读当前负责组，总管理员仍须通过客服对象授权。原归属拒绝保持 `404 SUPPORT_CUSTOMER_NOT_FOUND`，非法编号为 `422 SUPPORT_ID_INVALID`。客服提现明细及对应全景聚合仅返回合法业务字段，不含风控决策、规则、阈值、审计详情、IP 或上级身份；金额、实际费用、到账金额、状态和时间保留。合法查看沿用全景服务的事务、outbox 和强制查看审计，失败原样传播；C1 原入口权限保持。

### 分组目录读取

GET `/support-agents/groups` 返回独立 `GroupDirectory[]`：`id/name/supervisorAdminId/status/version/supervisorName/memberCount/boundCustomers/pendingCustomers`。负责人只投影显示名；缺名字或不能确认的计数为 `null`，确证空集合为0。成员计入停用待交接账号；绑定与待分配客户完全复用 analytics 当前客户投影，按 `currentGroupId` 和 `BOUND/PENDING` 分区聚合，排除 sandbox 客户。`pendingCustomers` 是当前客户分区人数，不是操作分配池数量，也不等于详情中用于退出阻止的 `routedCustomers`。目录使用同次获准范围批量读取，个人范围拒绝，主管只读本人负责组；失败不降为零。组详情和写操作的五字段 `Group` 回包保持原契约。

### analytics 查询

只接受单值白名单参数：`view,category,firstState,filter,keyword,basis,from,to,businessZone,groupId,agentId,currency,sortKey,direction,pageNum,pageSize,expectedVersion`。重复、未知参数和客户端范围声明拒绝。

视角 `OVERVIEW/AGENTS/CUSTOMERS/FINANCE/DEVICES/ACTIVITY`；客户分区 `ALL/BOUND/PENDING/ANOMALY`；首充状态 `ALL/CONFIRMED/NONE/UNKNOWN`。默认客户视角、当前客户历史口径；`PERIOD_EVENT` 要求合法 `[from,to)`，当前历史口径不接受期间。当前业务时区 `Asia/Shanghai`；币种和排序字段从服务端能力集合取。默认第1页、每页20，范围1–100；后页携原 `expectedVersion`。非法/越权/版本变化分别拒绝，不静默放宽。

当前 analytics 顶层字段为 `view/basis/businessZone/asOf/pageNum/pageSize/total/observedTotal/records/versionState/queryVersion/canContinue/recordsStatus/selectedCustomerCount/selectedCurrent/scopeSummary/funds`。`queryVersion` 绑定查询、权限与事实证据，不另返回完整标准化查询、`sourceVersion/definitionVersion` 或 `rules`；具体口径由产品合同及各字段投影解释。通用计数为 `observed/confirmed/status`，金额另含观察/确认事件数及观察客户数，金额值为 decimal 字符串；未知按对应状态及可空确认值表达，不统一为 `null + reason`。资金读取另有各自状态/原因字段，部分源失败不伪造0或完整值。

2026-10-29 性能口径：付款历史、净贡献及其历史归属证据允许使用内部不可变事实采样，统计延迟从采集 RR 开始至返回前不得超过5秒，`asOf`显示真实采集时间。身份、当前授权、负责组、资格、客户绑定/路由仍由当前请求读取，采样不保存私有 HTTP 回包或操作者范围，不扩大任何对象权限。付款子集按逐客户来源覆盖和排除计数重建，未知和真实零保持分开；当前钱包、设备及服务任务仍沿原读取合同。查询版本继续绑定当前权限/范围及所用事实，后页发生变化仍拒绝。应用就绪前预热；刷新失败不更新旧时间，过龄/未就绪返回真实不可用，不伪造空壳或在请求中回放全部历史。客户正文在全量筛选排序后仅投影所选页，累计与版本证据仍覆盖完整获准选集。接口≤300ms、页面≤1秒须以部署实测独立验收，代码实现或预热完成不代表达标。

### leaderboard 查询与发布

只接受 `board,month,currency,scope,groupId,keyword,pageNum,pageSize,expectedVersion` 单值参数。正式榜键 `netContribution/firstPayment/customers`，默认净贡献；旧 `deposit/purchase` 输入兼容规范化为 `netContribution`，不计算或发布旧金额榜。范围 `all/ownGroup/managedGroups` 来自服务端获准集合。客户规模榜不接受月份，月榜仅接受真实覆盖月；金额币种由服务端集合限定，金额旁列统一 `NET_CONTRIBUTION`，精确十进制字符串可负，人数及追赶差距不可负。响应保留 `viewVersion/queryVersion/sourceVersion/definitionVersion`、真实 `asOf/publishedAt`、候选及指标覆盖、状态、本人摘要、公开行、可比日末基线及原因。`podium` 从同版授权整榜的确定名次1–3生成，`rows`最多四人，`total`包含全部并列；不随搜索或页码变化，非完整榜返回空摘要。沿用公开行白名单、头像读取及明细权限，不扩大私有范围。

公开行仅授权展示身份、组标签、资格、聚合、排名及覆盖；授权头像经独立私有读取，不附业务对象地址。成功计算原子发布，分页/详情/本人同版；过期可用版标真实时间，失败不发布半榜。资格/范围仍按读取时重验。日末基线不能由当天补数重写；无法证明可比时返回明确不可比。

## 3. 事实来源与安全降级

资格/组/成员/负责人/客户绑定/路由历史是授权与归属事实；当前与事件时两种口径保持分开，未知不能补当前值。付款事实由现有合法充值、钱包设备购单、付费试用转正式、差价置换、保留旧机另购和订单退款源适配；源ID去重并保留环境/成功时间/金额/币种/原单关联及覆盖。

净贡献按 owning PRD 的原资金来源规则：真实充值与全部实际消费去重，仅真实成功提现及后台退款冲减原已计贡献；收益提现不冲减，手续费不增加业绩。实际提现扣款已含实际手续费，NEX按真实原币扣款单列，不猜汇率或再次加费。成功冲减按其发生期间、原贡献客服/组/主管归属，可形成负月额。首次付款合同独立，仍仅充值与设备购买；拓宽净贡献不增加首付类别。充值成功不退款，购机成功无客户主动退款入口，后台原 E4 确认、理由、幂等及审计保持。

完整资金重放使用同一物理只读 RR 快照、真实开户起点、所有原始钱包版本、不可变操作与完整参与客户、领域原件及成功回执。消费、冻结、返还、兑换、释放和二级出售保留原来源与顺序；缺源、历史或原归属仍未知，不补造出生、零额或完整排名。金额未知与独立人数分别判断，转绑/调组不搬历史。统计缺口不能阻止合法提现或后台退款，实际现金扣款/返还只用原业务该单真实预留证据，不能扣其它订单余额。原经营统计保留各自事件口径，不静默改为净贡献。

提现、钱包、设备、活动、邀请画像均读取各领域真实服务投影，累计独立于明细分页。跨组邀请只提供获准聚合，不输出越权后代身份；设备IDC托管不构造闲置或100%在线，缺遥测为未知/不适用。新建继承配置默认无限，既有显式配置和关系不追溯；读取失败不得当未配置。

性别 `MALE/FEMALE/UNSPECIFIED` 由有权操作者明确提供；服务账号新建默认头像仅在许可且审核通过的本地池选择一次，经受控资源校验绑定并持久化。目录或策略不可用保留缺失态；重放、刷新、改性别不重抽，显式更换失败保留旧资源。

## 4. 命令、异常与验收

高敏命令沿原确认、理由、Key、expectedVersion、事务和审计。`PROCESSING/UNKNOWN` 查询原命令或同键同载荷重试；版本冲突读回差异，确认失败保留输入和取消出口。无权直接拒绝，不能仅靠菜单隐藏。

本仓验收需覆盖查询白名单、非法币种与排序、本人/负责组/全组及无组、在途撤权、同版分页、真实0/未知、来源重放/同刻归属/跨币去重、首付类别不扩大、实际消费去重/成功冲减/手续费实际扣款/负月份/原归属、旧无预留不扣其它订单及统计缺失不挡合法现金、缺源不排完整榜、公开榜不扩私域、日末基线可比、组/资格退出原子性、新默认无限与存量保留、头像随机一次和M3原协议恢复。产品端验收归 owning 工程；单测或文档声明不替代真实写入、刷新读回及数据库验收。

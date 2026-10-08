# R1 促销接口与持久化契约

本目录是 S1 冻结候选，不是已上线实现。业务源为 PC 仓 PRODUCT-PRD P2–P9、P11；openapi.json 固定接口，contracts.json 只提供隔离样例。R1 五模板、DEVICE/USDT/NEX；不含比例返币、购物车、部分退款、新支付通道或新邀请树。S1 只有静态检查，真正事务与浏览器验收属于 S2 之后。

## 1. 认证、命令与兼容

- 新接口成功为 HTTP 200 + ApiResult code=0；业务拒绝用 OpenAPI 对应 HTTP 状态和同值 code。message 为工程码，客户端映射可理解文案，不直接展示它。
- PUBLIC 只返回公开规则，登录后才能返回本账户资格预估。USER 的 account/beneficiary 从会话取得；跨账户订单、奖励、命令不可读。DIRECT_INVITER 只能看到本人奖励，不能得到好友身份或付款金额。
- ADMIN 每操作权限固定在 x-required-permission。除通用读取外，原命令结果还必须验证原动作权限、原操作者或有该对象审计权限的角色。知道 Idempotency-Key 不能跨主体读回。
- 所有变更有 Idempotency-Key；版本/状态命令另有 expectedRevision、reason。scope 包含认证主体、operation、target、必要环境，requestHash 是字段排序后的规范 JSON。相同 key/hash 原样回放；不同 hash=409；结果未知先 GET 原命令，不换 key 重试。复用 AdminIdempotencyService.executeRetained/recoveryResult，不另建第二套幂等表。命令回查不 claim、不修改状态、不触发过期恢复。
- CommandReceipt.resource 是结果定位，SUCCEEDED 只说明该命令成功；奖励是否到账看 Reward.assetReceipt。失败回执要保留错误、原资源和原 key。
- 新字段钱数为十进制字符串，最多现有 D4 的 18,6 精度；目录 scale 是最终约束。拒绝超精度/溢出/负数，不四舍五入。DEVICE 为整数。现有 E4 DeviceOrderView.amount/时间及原订单已有字段保持原序列化；仅新 quote/reward/metrics 合同强制字符串。
- /api/orders 保留单 SKU quantity；/bundle 的 productNos 与 items 恰一项，保留原有最多8种SKU边界。旧 productNos 仍每 SKU 一台；新 items 不允许重复 SKU，quantity 总和≤既有订单100台上限。itemCount=明细行数，quantity=总台数，退款不能再把二者相等作为条件。
- 旧订单没有 promotionQuoteId 时完全按旧合同。新活动必须具备 PROMOTION_QUOTE_V1、ORDER_ITEM_QUANTITIES_V1、PROMOTION_REWARDS_V1；能力不够不能以活动价/赠品承诺建普通单。公开说明仍可读。
- 创建返回 promotionQuoteId 的订单必须返回 OrderReceipt 全部字段；items 保存正式订单行 lineId、productNo、productName、quantity，rewards.lineId 必须关联同一正式订单行。刷新、支付、取消和原命令恢复均使用同一回执；历史回执缺 items 时只读取原订单明细补齐，不读取现售商品规则。赠品 disclosure.deviceName 从原批准 E1 合同的商品名生成，USDT/NEX 为 null；隐藏、下架或后来改名不改变原承诺名称，也不回退通用设备名。普通旧订单响应只按既有必要字段验收。E4 POST/PATCH 是同一命令 scope，同 key 不能执行两次。
- CreatePromotion 只要求 reason 与 draft.category/template。服务端生成活动、版本与 revision；不生成金额、期限、预算或已批准政策默认值。Draft 是可持久化的不完整配置，PromotionContract 是待审/批准/发布必须满足的完整合同，两者不得共用 required 清单。name 是运营内部活动名，1–200 字且不能全空白；草稿可暂缺，提交前必填。公开多语言标题仍为 title，二者分别保存；改候选草稿名称不改变当前发布版本名称。
- DraftWrite.draft 是顶层字段补丁：未传字段保留；已传对象/数组整体替换该字段；null 清除未决配置；空规则和空预算可以保存。仅未发布草稿可切换 category/template：界面先展示不兼容字段，确认后在同一补丁显式清除或替换这些字段；未明确处理不兼容项则拒绝并保留原草稿。服务端保存变更前后审计，稳定且已使用的 ruleId/rewardRuleId、历史计数和已发布快照不得改写；候选草稿的类别/模板不改变当前有效版本的分类，发布时才更新活动根投影。不能借补丁改活动 ID、版本号或批准字段。保存后读取同一版本必须返回实际未完成内容。只要两个时间均已配置便检查结束晚于开始；有时窗必须有明确时区，不能补假日期。
- save 不要求政策已批准：允许缺项、null 或可访问的 DRAFT 政策引用；不把未批准对象当作执行许可。submit/approve/publish 均先按 PromotionContract 校验存储版本，再按 ResolvedApprovedPolicy 核实实际政策、引用匹配与执行能力。缺配置或未批准时返回422及字段错误，不改变当前草稿。数据库只允许 DRAFT 的时窗为空，待审及以后必须有完整有效时窗。

## 2. 目录与政策对象

GET promotion-catalog 返回当前 SKU、库存模式、等级、市场、资产精度及调用者可见政策；所有目录均读真实来源，unknown 不当最低等级/无限库存。device-rights-profiles 是 kind=DEVICE_RIGHTS 政策投影，没有另一套可编辑权益库。

九类 PolicyContent 是封闭的可执行类型，不接受自由公式/任意脚本。PolicyRef 的 id+version+contentHash 必须全部匹配存储对象。政策的 kind、executorCode 必须和引用位置相符；nativeContract 的 system/id/revision/hash 必须解析到当前已有订单、E1 商品、收益释放或 A2 政策对象。审批时验证引用来源、证据可访问、操作者权限和实现可用，缺 executor/引用/证据=422或503，不接受随意字符串。

Catalog.nativeContracts 提供政策创建可选择的原合同：仅含白名单 system/resourceId/revision/contentHash 引用和可读名称，不返回 K1 算法或配置值。未解析的引用为 null，不能构造空 hash；不可执行或不可解析项 available=false 并给出三语原因。目录项不是批准事实，政策批准和活动推进仍重新核验原合同。Catalog.skus.giftEligible 单独表达当前赠品执行能力，不改变购买 SKU 的可用性。

DEVICE_RIGHTS 批准还须服务端生成非空 resolvedDeviceRights：启用方式、生效时点、存续天数、任务与收益规则对象、设备/等级计入、置换/转赠、使用后撤销方式全部具名。其值来自上述实际商品/合同版本，输入不能自称已解析；任一缺失或现有执行器不支持则拒绝批准。活动发布和赠机义务保存整份解析结果，不能只保存 profile 字符串后运行时读取最新商品。其它政策的 resolvedDeviceRights 为 null。

政策创建只生成 DRAFT；新版本不能修改已批准版本。批准必须落实际 approval_ref/approved_by/approved_at，更新 content 后必须新版本。撤销阻止新发布/报价/预留；已有承诺仍按不可变已解析快照执行，只有有依据的既有安全处置可以冻结执行。活动发布复制已解析政策快照及源版本/hash，不能运行时悄悄更换已承诺权益。

fixture_run_id 只用于识别/排除验收数据，绝不是授权或政策校验旁路。没有已批准政策、真实 nativeContract 和已实现 executor，隔离库也必须拒绝发布。生产实例拒绝 fixture 政策/活动。隔离验收需先建立真实可执行的隔离合同对象，再经过同一审批链，不凭 fixture=true 批准。迁移不种任何批准政策或活动。

placement=null 明确表示未选择首页投放；草稿可暂时省略，完整合同和公开投影必须明确返回 null 或 home.purchase-promotion。首页调用 GET /api/promotions?placement=home.purchase-promotion，服务端只返回当前已发布、ACTIVE 且显式选中该投放位的活动。商城列表不带此过滤，可包含未选择首页的已发布活动；不新增其它投放位。

FIRST_PURCHASE 和 DIRECT_REFERRAL 需要 FIRST_PURCHASE 政策；devicePresence 非 ANY 需要 DEVICE_AUDIENCE；DEVICE 奖励需要与赠 SKU 匹配的 DEVICE_RIGHTS；币奖励需要同币 ASSET。所有活动需要 SETTLEMENT/STACKING/REFUND/QUOTE/AUTHORIZATION。D01–D11 未裁决时草稿可存，发布逐项报缺项。首期不自动采用建议天数、币量、资格恢复或损失承担。

AUTHORIZATION 只控制本功能的批准与发布关系。读取真实 A2 政策决定 maker/checker 是否分离，不复活全站旧双签；不得用一个布尔“已确认”代替批准对象。发布者、审核者来自服务端身份。

## 3. 固定模板及版本身份

- SKU_GIFT：按各 rule.productNo 的 minBuyQty/PER_GROUP 或 ONCE_PER_ORDER 计算。
- FIRST_PURCHASE：NEVER_PAID，按批准 FIRST_PURCHASE.mode；支付前账户级仲裁，败选订单在扣款前409并重报价确认。未付取消不消耗资格，付后退款是否恢复取批准合同。
- DIRECT_REFERRAL：受邀买家首购，sponsor 用服务端已验证关系快照；双方各自资格/限次/奖励，买家不合格不能伪装成交礼，邀请者不合格可仅发符合发布规则的买家奖励。双方不能同人。
- MULTI_PRODUCT：ALL/ANY 明确写入合同，组合资格按整单，赠奖按行。组合折扣是否同享由 STACKING 决定。
- REPURCHASE：HAS_VALID_PURCHASE，最近有效购机区间显式配置；退款/取消不算有效历史，无历史不解释为无限天。
- 同字段 OR、不同字段 AND；天数 min≤max；未知等级/市场/关系等事实 fail closed。不存在任意嵌套条件语言。
- ruleId 在 activityId 下跨版本稳定；同 ID 不得改变 productNo。rewardRuleId 对应稳定 ruleId+beneficiaryRole；同角色不能换 ID 绕过历史计数，变更身份必须新规则且出版校验与旧规则不重叠，旧订单不能被重新计算。
- 奖励唯一键严格为 activity_id × order_line_id × beneficiary_id × beneficiary_role × reward_rule_id × unit_seq；version 只存快照，绝不进入唯一键。保留退休规则身份，禁止删除重建清空限次。
- perPersonLimit 以 activityId×account×role 算合格订单，一单多行只算一次；maxGroupsPerPerson 以 activityId×ruleId×account×role 算组数。均不含version。独立角色可分别达到限次。
- activityLimit 以活动根的 reserved_orders/used_orders 计整单，一单双方多项奖励只占一次；建单、付款、明确未付关闭原子转移，退款后是否恢复只按批准政策，不能随版本清零。
- PER_GROUP=floor(quantity/minBuyQty)，ONCE_PER_ORDER=1/0；上限只在报价时裁定，已承诺后不砍数。所有奖励单独追踪，赠设备不贡献实付、付费销量、购买佣金或再次达标。

## 4. 事务顺序与预算

S2 必须复核现有调用者并统一实际 SQL 锁序，不能只在新服务写注释：
1. 需要账户级资格/收益的钱包动作：按账户 ID 升序锁相关 nx_user；再锁 nx_order/有序订单行。
2. 活动根按 activityId 排序，版本/预算行按 asset/productNo，使用计数按 account/role/rule，预留/义务按稳定 ID。双向受益人锁顺序不能取决于请求顺序。
3. 商品根→SKU→设备/配额→钱包→收益来源记录；复用服务内部锁必须遵守相同顺序。退款、支付、发奖、出金交叉锁需真实 MySQL 并发证明，不把草案顺序当已无死锁。
4. 幂等 claim 使用既有执行器，业务事务内完成订单/库存/预留/预算/计数/outbox；任何写入行数不符抛异常回滚，不能 return fail 留半写。

quote 不占任何预算/名额/库存；可持久化 quote JSON 及 hash/TTL。expiresAt=min(now+批准TTL,endsAt)。建单重新核对 quote 账户、items、券、价格、版本、能力、资格、资源，差异409，不静默去赠品；全部预留同事务成功。

payBy=min(原订单deadline,活动endsAt)，存于预留不可修改。活动 pause/end 阻止新 quote/预留；原有效预留在原payBy内可付。支付按原快照复核账户首购、冻结、订单和预留，再扣款及 RESERVED→COMMITTED、生成唯一义务。不得按活动当前版本重新算奖。

每 activityId×asset/SKU 守恒：
available = total - reserved - committed - issued + reversed ≥ 0。
unrecoverable 是 issued-reversed 的子集，不重复扣减。取消/明确到期 reserved释放；发放 committed→issued；已付未发且退款确认才减少committed；已发且实际可复用回收才增加reversed。撤设备但不可复用库存/配额，不增加reversed。预算增加走新批准版本，不能重置存量。订单时间/支付结果未知不能按到期释放。

shortagePolicy 严格只有 RULE_STOP / ACTIVITY_PAUSE。RULE_STOP 停止资源不足规则的新预留，其它没有资源冲突且额度充足的规则可继续；ACTIVITY_PAUSE 停止整个活动的新预留并持久化暂停事实与原因。该选择不等于订单的拒绝或重报价错误处理方式：已给报价中的承诺若发生变化，仍须409要求重新确认，不能静默删减赠品或只建半单。两者均不损害已经原子预留的有效订单，也不释放结果未知的承诺。

## 5. 资产、重试与退款

USDT/NEX 在同一资产事务调用 EarningsReleaseService.creditReward（真实钱包+风险收益桶）及 TreasuryLedgerPostingFacade.postLedgerEntry（D4不可变流水+outbox）；sourceRef/bizNo由 obligationId 派生且重试不变。仅记 D4 不算到账。EarningsRelease 当前没有通用冲正，S2需按原entry/source、原资产新增可审计回收，保留风险桶，禁止其他资产扣款或负余额。

DEVICE 由 Commerce 建真实 nx_user_device，source_channel=PROMOTION_GIFT；instanceNo 由 obligationId+device_unit_seq 派生；nx_promotion_device_receipt 保存真实ID、权益版本与快照。不得制造PAID订单触发佣金。S2须让 DeviceCatalogMapper.rollbackOrderDevices 排除赠品来源，再由本义务履行退款裁决。赠品容量/库存和权益生命周期都要在预留/发放处校验。

赠品SKU必须经Native解析证明当前设备任务与收益链可执行；当前执行器仅承接AUTO激活、ISSUED生效、无独立期限的既有设备合同，不接受无运行闸的权益覆盖。SHARE/cloud-share现行收益只认ORDER来源，尚不能作为PROMOTION_GIFT批准；这不限制其作为购买SKU。其它无实际收益执行能力的产品也在批准前拒绝，目录应给出可理解的不可选原因。赠品不进入付费业绩、升级和置换来源；设备与权益快照中的数值保持原精度，不能转浮点后重新计算。

device_receipt.device_id 外键指向 nx_user_device(id)，profile_id/profile_version 复合外键指向 nx_promotion_policy(policy_id,version)，均限制删除/更新。外键只证明来源记录存在；进入ISSUED前仍须同事务核对设备归受益人、PROMOTION_GIFT来源、DEVICE_RIGHTS批准版本、全部应发实例数量和权益快照。冲正表只允许 DEVICE/USDT/NEX；DEVICE 的 amount/recovered/outstanding/reusable 四项全部为整数，不能用小数设备满足守恒。

PROCESSING→OUTCOME_UNKNOWN 时保留原资产命令号，先核账再允许 READY；无确定未发事实不得重发。ISSUED 必须有真实钱包+收益entry或全部设备回执；重试不新建obligation。MANUAL_REVIEW 只能凭可核验原资产证据进入可达状态，不能自由选“成功”。CANCELLED/REVERSED 终态不能复活。

单项 cancel/reverse 必须给明确 basis：WHOLE_ORDER_REFUND 引用已经执行的退款，或 APPROVED_CORRECTION 引用实际批准且尚可执行的 A2 operationId/revision/payloadHash。服务端核对该批准对象指向本 obligation、动作及原资产，上限不超过原未处理权益；活动停止、口头理由或任意批准字符串均不成立。后续退款与先前差错回收共用原义务累计回收数，不得累计超过原实际发放。SQL 同时约束同退款唯一及同批准处置唯一。批准流程只用于这类处置，不改变既有全站审批制度。

现有 E4 是直接执行整单钱包退款，没有现成“退款申请”接口。A2、E4与促销在同一后端进程和数据库事务内协作；不部署额外退款HTTP入口、服务身份或第二套事件表。可靠调用入口为 PromotionOrderService.holdA2Refund(operationId,orderNo)、clearA2Refund(operationId,orderNo)、confirmE4Refund(orderNo,refundLedgerBizNo)：
- A2 createProposal 持久化已验证 e4_order_refund/device_order 命令后，同事务登记 hold；sourceId 必须是实际 A2 operationId。若配置未要求 A2，不人为增加审批步骤。
- A2 reject/withdraw 在真实状态写入后，同事务解除**对应申请**的hold，不能只解除A2对象锁。expired与未知结果不得自动解除。
- A2 approve 回放成功由 E4 确认退款事实；失败仍pending/hold；响应未知进入 OUTCOME_UNKNOWN，查原退款，不恢复发奖。
- 直接 E4退款不伪造A2申请。订单事务用稳定 E4:{sha256(orderNo)} refundRequestId 写 EXECUTED，并核对原付款、真实E4账本；可在无HELD行时创建已执行事实，初始revision=1。
- source_type/source_id 是申请不可变来源。A2来源执行后仍保留原A2 operationId，执行结果追加真实E4 refundNo/refundLedgerBizNo；直接E4来源为E4_REFUND/source_id=orderNo。source_event_id作为同事务来源事实的稳定关联键：A2使用持久票据operationId，直接E4使用稳定退款号；它不是外部可伪造的事件证明或另一个HTTP参数。
- 收到 hold 时原奖励状态和预算不变；hold 与发奖在订单锁下串行，所有未解除hold都清除后才重新校验成熟/冻结恢复。已发奖励仍保留issued事实。
- 退款执行确认后未发项CANCELLED，未知先核账，已发项REVERSAL_PENDING；遍历订单所有行及双方奖励，不能只处理分页当前页。E4本金退款不无限等待奖励故障，奖励追回独立显示状态。
- clear/release 只接受已确认拒绝/撤回、从未执行的原申请；不能把EXECUTED/OUTCOME_UNKNOWN随意改RELEASED。重复事件原命令回放、同退款reversal唯一。

权限由原A2/E4受保护命令入口验证。促销方法要求已有事务，重新读取持久票据命令、订单所有权、真实状态及退款账本，不接受调用方只给一串批准说明。原命令的持久幂等回执负责重复执行保护；hold、退款和reversal另有业务唯一键，旧结果不倒退状态。未知响应保持hold且先查原命令，不能视作拒绝退款。A2/E4既有安全路径不因本合同授予额外权限。

nx_promotion_order_receipt 保存所有使用报价建成的真实订单，包括零奖励报价。order_id关联nx_order真实主键，quote_id关联nx_promotion_quote且只能被一个订单消费；同事务保存buyer、pay_by及不可变投影。ExpectedReward.lineId在建单后使用nx_order_item.id十进制字符串；报价内lineId仅用于匹配SKU。无活动报价的有效期只限制建单，不缩短既有订单付款期限。

## 6. 状态、报表与审批接线

合法转移表见 openapi.x-contract.transitions；状态转移还必须满足上述业务前置，不能把图里的边直接当授权。活动已有activeVersion时编辑draft不下线旧版本。仅 DRAFT 可保存；PENDING_APPROVAL/APPROVED 先以 POST /promotions/{activityId}/withdraw 撤回当前候选版本，需 growth_promotion_submit、version、expectedRevision、reason 和 Idempotency-Key。撤回清除原批准与解析快照并回 DRAFT，不代替审核者 reject，不修改 activeVersion 或旧发布快照；修改后须重新提交审核。publish后不可改；PAUSED发布新版仍PAUSED。ENDED只可归档，不直接恢复。ARCHIVED只读历史，不删除义务或停止既有处理。

## 活动列表与在途影响

GET /api/admin/growth/promotions 接受 query/name/activityId/category/template/state、from/to/timezone、sort、limit/cursor/querySnapshot。query 按内部名称、活动 ID 或事件编号查找，name 仅查内部名；通配符按文字处理。时窗三个字段同时提供，使用活动窗口与 [from,to) 相交语义，无日期草稿不匹配。排序支持 UPDATED_DESC/NAME_ASC/STARTS_ASC，并以 activityId 稳定排序。已发布活动采用 activeVersion 的分类、名称与时窗；没有发布版本才采用草稿。

首查在同一数据库读取快照中冻结全部匹配行、total、全查询 summary、规范化 query、asOf 和 expiresAt；分页携同 querySnapshot 与原筛选，不能只合计本页。快照限原操作者、有效 30 分钟，筛选不符或过期返回 409，跨操作者返回 404，每次读取仍即时复核权限。列表是当时事实，任何命令仍按当前 revision 复核；刷新新查得到新事实。

每行及详情的 impact 跨该活动所有版本读取预留、义务、真实钱包/设备回执及预算，包含 marked fixture 的运营责任。reserved 是仍预留的奖励项数，unpaidOrders 是去重的已锁订单数，reservedOrders 明示原版本及 payBy；issued 只计真实匹配回执，冲正后保留历史累计。pendingRewards 单列待履约，unresolved 汇总失败、结果未知、冲正处理中和人工处理；unfulfilledRewards 覆盖全部未终结义务。summary 聚合全部匹配活动，订单数量是逐活动责任数，同一订单参与多个活动可分别计入。预算按 USDT/NEX/设备 SKU 分列，禁止混币合计；source/asOf/completeness 标明来源与统计时间，不制造收入或曝光数据。

## 运营预览、奖励查询与报表口径

audience-preview 只要求 Draft 的类别、模板及所预览 Audience；不要求先配置时间、奖励、预算或全局结算政策。只解析设备人群和首购口径所需的已批准政策。返回条件摘要、来源、matched/rejected/unknown/total、拒绝原因计数；具备既有 user_c1_read 能力才返回最多 20 个仅含账户 ID 的样本，否则 samples=[]，各角色分别计数。simulate 的非空 sampleAccountId 同样要求 user_c1_read，缺少时返回 403。政策缺失或事实源不可读是 UNKNOWN，不能当无设备或未命中；账户来源不可读时 total 等为 null，estimatedAccounts 只在全部可判断时提供。预览不构成资格凭证，不写资格或订单。

simulate 返回独立 AdminSimulation，公开 Quote 不增加内部诊断字段。完整草稿使用同一规则求值器，返回精确 configHash/版本/asOf、实付报价、逐规则双方命中或拒绝原因、逐购买组 rewardUnits、分资产预算和购买/赠品库存消耗、三语公开说明预览。quotaImpact 明示活动订单、各受益人的订单及规则组数限额、已用加预留、本次预计消耗与剩余；无限限额为 null，未知消耗也为 null 并由 status 区分。没有账户样本、政策不可执行或事实不可读时明确 UNKNOWN/阻断；资源不足单列 SHORTAGE。结果 reserved=false，不保存真实报价、不占库存预算、不下单发奖；没有成本来源时 giftCostUsdt/acquisitionCostUsdt=null。提交与发布仍独立核实当前条件。

ADMIN promotion-rewards 额外支持 beneficiaryId 和 type=DEVICE/USDT/NEX，所有过滤条件与分页行、total、asOf 由同一次可重复读取事务计算。total 是过滤后完整数量，不包含 cursor 条件。每次翻页重新读取并标记自身 asOf，不承诺活动列表的跨页冻结；APP 奖励分页合同保持原样。

Metrics.orders 按订单 created_at 的 [from,to) 统计去重建单，包括未支付；paidOrders/实收按 paid_at 窗口统计，refundedOrders/refundUsdt 为这批已付款订单截至 asOf 的真实退款回执。两种时间口径显式返回，不能直接相除伪造转化率，conversionRate/acquisitionCostUsdt/roi 在缺同批次分母或成本时为 null。固定报表快照保存建单及支付订单清单。只读聚合输出使用 AggregateAmount/AggregateBudget，可超过单笔 DECIMAL(18,6) 上限；输入、单笔金额、资金数据库约束保持原上限。

salesBySku 按上述已付款订单的全部购买 SKU 分列成交单数、实付、退款、净实收。行价取原 reservation.snapshot.public.items.payableUsdt 的折后分配，商品编号和名称取原订单行；不得用折扣前 line_amount_usdt 或现售目录价代替。每单分项和必须等于实际订单金额；本期只支持整单退款，确认退款逐行按原实付回退。缺少原价快照、行关联错误或退款额不符时报告失败并可重试，不编造金额。组合单在每个购买 SKU 各计一单，SKU 单数不可横向求和当总单数；金额可以求和对账。salesBySku 与奖励分组、分页无关，固定在同一个 querySnapshot，随完整导出保留。

Metrics.breakdown 按 groupBy=SKU/TEMPLATE/BENEFICIARY 返回服务端聚合 rows/total/nextCursor/hasMore，默认 SKU、limit=20（1–100）。SKU 是锁定奖励承诺的购买商品，模板来自成交版本，受益人按既有角色及账户 ID 区分；同组仍分 USDT/NEX/DEVICE，设备另分赠品 SKU。每行包含原承诺、真实已发、待发、已回收、未追回、已取消及去重订单/受益人数。奖励以义务 created_at 的 [from,to) 取样，结果截至 asOf；这一口径显式返回，不将赠品当付费销量，也不混成成本。

首次查询在可重复读事务内冻结全部三类分组、原始奖励事实、整体指标、筛选和时区。后续页必须携带 querySnapshot 及相同 activityId/version/from/to/timezone；可在同一快照切换分组和每页数量。快照绑定当前管理员，24 小时到期后 409，查询变更 409，无快照翻页 422；每次访问即时核验报表权限。导出从该快照包含所有分组和全部原始行，不只当前页；受益人仅包含本业务已有义务的 ID 和角色，无联系资料，沿用报表/导出能力。无曝光或成本来源仍为 null。

正式全额券订单虽实付为 0，payment_channel=VOUCHER 且 PAID 仍是成功购机事实并消耗首购资格。原 E4 钱包整单退款拒绝 amount<=0（ORDER_REFUND_SETTLEMENT_INVALID），首期沿用此边界；不存在可执行的零额钱包退款或由它触发的资格恢复，不伪造零额账本。

20 项能力仅注册 metadata：查看、报表查看、政策查看、奖励查看、只读试算为 READ；草稿编辑、政策草稿、提交/撤回为 WRITE；批准、发布、生命周期、导出及奖励执行为 HIGH。迁移仅补缺失权限或更新这 20 项 perm_type，不自动授予角色。

新能力只登记到nx_admin_permission，不自动给任何角色。S2接A1/A2的操作映射、真实审核策略、回放权限和审计；不得把所有操作借用growth_h4_write。每项审计含理由、证据、当前身份、旧/新revision、activity/version/order/obligation、命令号和资产回执；敏感动作审计写失败事务回滚。

报表从订单实收/已确认退款、奖励账本/设备实际事实派生；钱包扣款不是外部入金。USDT、NEX数量和设备台数分列，赠机不计付费销量；未提供成本/曝光/外部流量= null + completeness，不造0或ROI。fixture数据排除经营指标。querySnapshot固定筛选、时区、源水位及行清单；导出异步生成既有存储artifact，READY前无下载URL；状态和下载重新验权，数据字段脱敏。数据源/水位缺失时不声称精准转化或因果增量。

P10新增四项具名指标，各自带 completeness/source/asOf：effectiveFirstPurchasePeople 是查询活动/版本/时窗内按原首购政策仍有效的去重购买账户数；directInvitedQualifiedPurchasers 是同查询内有效直接邀请关系和合格购机事实对应的去重受邀购买账户数，不计分享点击。rewardFailureMetrics.value 按发生失败事实的 obligationId 去重，breakdown 按发放/核账/冲正阶段、资产和原因分项；同项可跨原因出现，总数不是分项简单相加。recoveryDuration 以秒记录已解决失败事件的平均耗时，从该事件首次明确失败到原义务被确认履行或合法取消/回收，反复重试不重置起点；样本按失败起点落在查询时窗且解决时间不晚于asOf选取，未解决项不伪装为0秒。无已解决样本时 sampleCount=0、value=null；来源不足时值和细项为null、completeness=UNAVAILABLE。均为观察事实，不宣称因果增量。

## 7. S1检查与后续验收

`POST /api/orders/quote` 要求 `Idempotency-Key`，与实际保留报价回执的接口一致。同键同内容返回原报价及原过期时间，不延长有效期；主动重新试算使用新键。报价不预留预算、库存或参与次数，不能用报价键代替后续建单/支付的固定命令键。

公开规则、报价逐项承诺、订单回执逐项承诺和奖励回查均返回 `disclosure`：三语活动标题、活动条款、退款条款、权益/到账可用范围说明，以及设备奖励可公开的激活、生效、期限、任务、持有/等级统计、转让、兑换和撤回方式。币奖励的 `deviceRights=null`；不暴露原合同 hash、审核人员或内部配置。活动浏览使用已发布版本的批准政策，报价使用当次已验证政策；建单与奖励回查只读原订单的冻结合同。已有促销回执缺新增投影时，从原 reservation 快照补充，不读当前活动条款，不改历史数据。原 GET /api/orders 列表与命令回查共用订单投影，不新增订单系统。

`RewardAdmin.dispositionOptions` 为可选择的操作依据，每项含 action、完整 DispositionBasis 和三语标签。整单退款项同时要求真实 EXECUTED hold、订单退款状态和原 ORDER_REFUND 入账事实；A2 项要求当前奖励 revision/snapshot/action/asset、已批准工单以及对应领域审批审计。无依据返回空列表。该字段仅管理端输出，人工无须输入 hash 或将 refundRequestId 冒充 refundNo；它不替代执行时的权限、当前状态和事务内原依据复验，过期选项仍拒绝执行。

node scripts/check-growth-promotions-contract.mjs 验证OpenAPI3.1所有引用、必要操作、权限、请求响应样例、五模板、十奖励状态、旧payload、金额/数量、预算守恒、13个精确FK关系与38个CHECK表达式；初始草稿/步骤保存/未决政策的正例，以及三个推进动作的完整性拒绝例都必须实际运行。逐条删FK/CHECK、删光FK、改错外键目标、允许其它币种/小数设备的内存突变均必须被拒绝。它不是通用JSON Schema实现，也不证明MySQL迁移或业务代码已运行。

后续在33339新任务实例：迁移重复执行、真实外键/唯一键/CHECK、A×2+D×1全链、双方三奖励、最后预算并发、跨活动首购并发、重放/未知/部分失败、hold拒绝/撤回/执行、预算回收与余额不足、历史版本、新旧客户端、权限撤回后命令回查/下载。订单、钱包、收益entry、D4、赠机回执必须读回和重启后仍在。主线已发现空库的旧schema启动前置缺项，单独记录；本迁移不得绕过旧门或接生产库。

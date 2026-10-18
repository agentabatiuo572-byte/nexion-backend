# 七层正常启用、暂停与恢复能力契约

用户已明确授权交付正常启用入口并继续 TEST 实测。沿用 DESIGN.md 的显式 T、可靠 checkout 消费和来源代际冻结；本交付不直接启用 TEST。

## 入口与权限

F2 复用 GET /api/admin/teams/direct-referral-policy?schemaVersion=2。保留 policyVersion、sevenLayerRevision、sevenLayerEnabled、cutoverAt，增加 sevenLayerActivationContractVersion:1（旧后端缺失时新PC只读，不能提新command）和 sevenLayerPaused:boolean（缺配置默认 false）。enabled 表示结算代际已启用，paused 仅表示暂停首次准备新来源，不把已启用伪装为旧代际。

正常 A2 command 为 domain=F, op=f_seven_layer_activation，params 严格为 action:ACTIVATE|PAUSE|RESUME、expectedSevenLayerRevision:非负整数、expectedPolicyVersion:非负整数。reason 使用工单已有理由（8–200字符）。A2 sourceDomain=F2、obj=current、target={domain:F,type:seven_layer_activation,id:current}。申请与回放权限 network_f2_policy_amplify，沿用 platform_a2_proposal_create/platform_a2_operation_approve、MFA、不同 maker/checker、对象锁、A2 幂等与审计。ACTIVATE/RESUME 为 fund/amplifies=true，PAUSE 为 param/false；执行时 ACTIVATE/RESUME 回源检查现有 B1 覆盖率红线。

## 转换、时间与 CAS

未启用仅可 ACTIVATE；已启用未暂停仅可 PAUSE；已启用已暂停仅可 RESUME。拒绝重复新意图、非法动作、缺/负版本、不匹配版本、无权及直接绕过 A2 的调用；失败不写 T、暂停键或 revision。购买来源代际选择也在该同一revision锁下，以current read读T/paused；等待启用/暂停批准锁后必须使用已提交控制态，始终以原sourceOccurredAt比较T而不是消费时钟。共用已有 seven-layer revision 行锁，并锁已有 direct policy version 行，核七层 revision 与真实 policy version，成功任一转换递增 revision。

T 只能在首次 ACTIVATE 的批准事务中取服务器 UTC Clock instant（微秒精度），不接收客户端 T，不允许删除/移动 T、定时启用或变更结算模式。PAUSE/RESUME 保持同一 T。GET 返回实际持久 T 和 revision，A2 工单批准结果/审计包含操作、前后状态、实际 T、operationId。

## 资金与恢复

不改费率、等级门、Influence、旧政策或订单。七层使用已配置规则；缺全局冷却沿现行 PRD/引擎默认30天，不能借 schema1 purchase.coolingDays=0。schema1 来源政策在 T 后可 SEVEN_V2/split=false，沿原 L1 与 L2-L7 构成，不自动转换为 v2 拆分。

既有 DIRECT_ONLY_V1/LEGACY_7/SEVEN_V2 来源和政策/链/预算快照不重写、不补发。PAUSE 在新来源首次 SEVEN_V2 准备前拒绝并留下可靠消费者失败/重试事实；不标成功，不建冻结订单/钱包/佣金。已冻结来源继续按原快照处理，退款和解锁路径不变。RESUME 在批准事务内仅将消费组 direct-referral-settlement、checkout.completed/ORDER、精确暂停错误 SEVEN_LAYER_NEW_SOURCES_PAUSED 的 FAILED/DEAD 原consumer/outbox重新置为可重试（重试预算归零），不改 SUCCESS、其他失败原因或事件身份/时间/payload；批准文案说明将继续处理暂停滞留事件，审计记录范围及受影响行数而不记录payload。若consumer或outbox的最后失败元数据迟于RESUME提交，既有恢复scheduler在后续轮次、七层revision锁下再次精确恢复同类滞留记录，并以SYSTEM审计记录实际恢复范围/行数；暂停中不恢复，不改变其他恢复任务。随后原可靠事件自然重试，保持原来源时间选择；不创建替代支付或新 key。暂停不是删除 T，也不是停用七层或改历史资金。

同一未知提交/批准意图重用现有 A2 command key 与工单号，先读原工单结果及 GET 状态再继续；已批准工单的重复批准沿平台既有幂等返回，不重算 T。不另造一套幂等表或业务重放端点。失败/冲突正常重读后需新预览/新提案，禁止自动把新 revision 塞进旧批准。

## 必要验证与边界

行为覆盖正常不同管理员批准、真实服务器 T/版本稳定、重复/未知同意图、权限与 CAS 失败无写、旧新来源边界/冻结不改、暂停首次准备无资金副作用且原事件失败可恢复。后端与 PC 对端互审后正常推 test、保留性快进；两端交付后才 resolve/fixed，绝不关闭 Bug。Root 执行 TEST 发布、启用及有限新来源扫描。本能力不补历史、不新增模式或新业务数值。

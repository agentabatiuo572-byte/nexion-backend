# Cregis USDT-BEP20 收款接入

## 目标业务口径（当前尚未开放）

- 正式 App 只展示 USDT-BEP20。登录用户获得一个固定的 Cregis 专属地址，可复制或扫描二维码。
- 单笔链上入金至少 10 USDT，固定手续费 1 USDT。入账净额为链上转账金额减 1 USDT；低于 10 USDT 的记录进入 `DUST_HOLD`，超过 100 USDT 的记录进入 `REVIEW_HOLD`，均由人工处理。
- 到账经过 Cregis 签名通知、Cregis 交易查询、BSC USDT 合约 `Transfer` 日志和至少 15 个确认核对后，原子写入充值事件、USDT 钱包、累计本金、钱包流水、D1 储备和充值单。订单购买继续从钱包余额扣款。
- 订单退款沿用现有人工审核和钱包退款流程。链上充值本金进入 `cumulative_deposit_usdt`，可计入已有银行提现资格；银行提现的实际通道仍由银行提现服务控制。
- 本期 `PROVIDER` 链上出款在申请和执行入口均被代码硬关闭；银行提现仍按原银行通道规则申请。

## 上线配置

以下步骤是测试服验收清单。当前仍需真实供应商签名回调、小额转账和异常资金处置证据；未验收前保持数据库资金闸门关闭。

1. 按顺序在目标数据库执行 `scripts/migrations/20260928_cregis_deposit.sql` 与 `scripts/migrations/20260928_cregis_controls_reconciliation.sql`。启动迁移列表已注册两者；服务在 `PROVIDER` 模式下检查必需表、列和唯一索引，缺失时拒绝启动。
2. 配置 Cregis 项目 ID、服务端 API key、Cregis API HTTPS 地址、公开 HTTPS 回调基址、只读 BSC JSON-RPC 地址及 `NEXION_CREGIS_CALLBACK_SOURCE_IPS`（供应商确认的回调出口 IP，逗号分隔的精确 IP）。来源清单为空时回调拒绝并留存证据。密钥留在安全配置中，不写入仓库或日志。
3. 在 Cregis 项目中核验 USDT-BEP20 `chain_id=2510`、`token_id=0x55d398326f99059ff775485246999027b3197955` 可创建地址，并配置回调地址、出站访问白名单及回调网络通路。TEST 公网入口使用 `/api/cregis/callbacks/deposit`；原 `/openapi/v1/withdrawals/cregis/callbacks/deposit` 只适用于直接连到后端的环境。
4. TEST 的旧发布器会把 `nexion.finance.cregis.mode=DISABLED` 写成 JVM 参数；仅设置环境变量不会启用。须通过受信任的主机更新安装支持 Cregis 的发布器，并由 root 专用许可文件授权 `PROVIDER`。完成收款硬门后，再设置 `NEXION_CREGIS_DEPOSIT_ENABLED=true`、`NEXION_CREGIS_DEPOSIT_CREDIT_ENABLED=true`、`NEXION_CREGIS_DEPOSIT_PILOT_USER_IDS=<逗号分隔的用户 ID>`、`NEXION_CREGIS_BSC_RPC_URL=<HTTPS RPC>`。试点最多 50 个用户，目标地址池 60 个。保持 `NEXION_CREGIS_PAYOUT_ENABLED=false`。
5. 使用已批准的试点账号读取 `GET /api/deposits/address?network=BEP20`，核对返回地址在 Cregis 项目中属于该项目；再做一笔受控小额真实转账，按 Cregis 交易、链上确认、回调收件箱、事件、钱包流水、充值单和 D1 储备逐项验收。未完成这些核对时保持收款开关关闭。

## 异常与恢复

- 地址创建由 `finance_d1_channel_manage` 管理接口发起，每次只创建一个候选并记录请求；供应商结果未知时封闭许可。链日志追赶并确认归属、供应商余额和 BSC 余额均为零后才入池。用户接口只能领取已核验地址；空池返回 `CREGIS_ADDRESS_POOL_EMPTY`。
- 长度受限的回调逐次留存原文、可信代理解析后的对端 IP、来源白名单结果、签名与时间窗结果；只有来源、验签、时间窗和字段校验均通过、原文落库成功后才返回 Cregis 要求的 `success`。异步任务通过 Cregis 交易查询和链上日志核实到账；服务中断后可重试。未处理的已验签投递会阻止重开开关和人工放行资金。链上扫描对有地址的试点账户从分配区块开始，发现平台未收到回调的转账时保留 `PROVIDER_MISSING` 观察记录。
- `DUST_HOLD` 不加钱包余额。重复的 Cregis ID 或相同交易日志由唯一索引阻止再次入账；冲突保持待处理。数据库事务失败会回滚钱包、流水及储备。
- 回调和链上观察记录保留 `last_error` 以便运营区分供应商缺单、链事实不符和暂时不可用；定时任务继续核对，不根据错误自行加款或退款。已入账交易在 100 个后续区块内分段复核 canonical，冲突时冻结账户、待处理提现和可覆盖的余额并留存事件。
- 有 `finance_d1_read` 权限的运营人员可读 `GET /api/admin/finance/cregis/exceptions`，查看未知地址、超限/灰尘待审入金、供应商缺单及建址许可状态。该接口不提供直接改余额的操作。
- Cregis `PROVIDER` 模式及回调接口需维持到所有已分配地址上的未结入金处理完毕；单独关闭 `DEPOSIT_ENABLED` 只阻止新地址展示及创建。关闭 `DEPOSIT_CREDIT_ENABLED` 暂停新的钱包入账，回调与链上观察继续记录；核实的收款持久化为 `REVIEW_HOLD` 或 `DUST_HOLD`，恢复后仍须按人工处置规则处理，不自动越闸入账。

## 当前上线边界

- 上述迁移与功能开关是发布条件。开发环境测试不代表 Cregis 正式项目、BSC RPC、HTTPS 回调及银行提现运行态已验收。
- 现有银行提现服务只在绑定身份和 HDPay 银行出款配置就绪时开放。直接人工向银行卡打款的独立闭环需要单独验收，不能因充值本金已记入钱包而视为出款已可用。
- 地址池维护 API 与领取前核验已有候选代码；TEST 已有 3 个经归属、余额和链日志核验的未分配地址。D1 已有只读状态与异常队列面板，但尚无后台补池、未知建址恢复和孤儿资金双人认领工作面。
- 充值单以 `(chain_tx_hash, asset, chain_log_index)` 唯一定位链事件。旧充值单升级时日志索引置 0；同笔交易出现相同地址和金额的多条日志，仍须等待供应商可核对的日志索引证据，不能猜测分配 CID。
- `REVIEW_HOLD` 已有不同管理员发起/复核、实时 Cregis+BSC 预检摘要、版本 CAS、5 秒证据有效期和原子入账；服务端核对发起与复核摘要，后台显示毛额、手续费、净额和交易定位。达到 500 USDT 熔断后仍可用双人路径逐笔解除已核实的大额待审款，闸门不随之自动重开。`DUST_HOLD`、孤儿资金、供应商或链冲突、重组仍保持冻结，尚无完整的双人退款/认领/冲正路径。争议投递不能经补漏自动越过冻结；未结 P0 不可重开。
- 双次完整 `trade/page` 闭窗对账、链游标覆盖、唯一 CID 和整窗哈希检查、失败不推进水位、并发单运行租约已实现；暂停自动入账期间保留可重试链观察。未决敞口达到 500 USDT 时原子关闭分配和入账，链上出款本期始终禁用。TEST 已出现覆盖到近 15 分钟的完整对账；公共 BSC RPC 曾多次超时，仍需稳定节点与持续运行证据。
- 实际供应商签名回调和小额真实转账仍未验收。上述异常资金处置与真实资金闭环完成前，数据库自动入账闸门必须保持关闭；本次代码不能作为全面真实资金上线依据。

### 2026-09-28 TEST 现场状态

- 受信任的主机发布器已升级；后端 #191（`d88a7133`）与管理端 #103（`cafaa1a`）已构建成功并部署至 TEST。迁移 `20260928_cregis_controls_reconciliation.sql` 已登记执行。Cregis 项目币种查询、BSC 链 ID/区块/日志/余额查询、HTTPS 公网回调路由及无签名拒绝已验证；无签名拒绝不等于真实供应商签名回调通过。
- 3 个地址已核验为 `UNASSIGNED`，建址许可 `IDLE`，链扫描间隔 30 秒。TEST 完整对账曾因公共 BSC RPC 超时及链游标滞后失败关闭；补扫后出现连续 `COMPLETE`，2026-09-28 17:05 UTC 水位滞后约 5 分钟，未解除风险告警为 0。公共节点仍需换成稳定支持 `eth_getLogs` 的专用端点。
- `NEXION_CREGIS_DEPOSIT_ENABLED=true`、`NEXION_CREGIS_DEPOSIT_CREDIT_ENABLED=true` 仅为试点预配置，`NEXION_CREGIS_PAYOUT_ENABLED=false`。数据库地址分配、自动入账、链上出款三项资金开关仍为 0；App 实测只显示暂停提示，无收款地址。地址试点提案 #1（只开分配、继续关闭自动入账）已由第一名管理员提交，待不同管理员完成 MFA 后复核。
- 当前没有真实充值事件。回调收件箱只有一条无签名拒绝证据；尚缺供应商确认的回调出口 IP 清单、真实签名回调、小额真实转账以及余额与储备闭环核验。`DUST_HOLD`、孤儿资金、供应商或链冲突、重组的双人退款/认领/冲正工作面仍未完成。

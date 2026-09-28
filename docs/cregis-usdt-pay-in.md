# Cregis USDT-BEP20 收款接入

## 目标业务口径（当前尚未开放）

- 正式 App 只展示 USDT-BEP20。登录用户获得一个固定的 Cregis 专属地址，可复制或扫描二维码。
- 单笔链上入金至少 10 USDT，固定手续费 1 USDT。入账净额为链上转账金额减 1 USDT；低于 10 USDT 的记录进入 `DUST_HOLD`，超过 100 USDT 的记录进入 `REVIEW_HOLD`，均由人工处理。
- 到账经过 Cregis 签名通知、Cregis 交易查询、BSC USDT 合约 `Transfer` 日志和至少 15 个确认核对后，原子写入充值事件、USDT 钱包、累计本金、钱包流水、D1 储备和充值单。订单购买继续从钱包余额扣款。
- 订单退款沿用现有人工审核和钱包退款流程。链上充值本金进入 `cumulative_deposit_usdt`，可计入已有银行提现资格；银行提现的实际通道仍由银行提现服务控制。
- Cregis 链上提现另有独立开关，默认关闭。开启收款不自动开启链上提现。
- `PROVIDER` 且链上提现开关关闭时，提现策略不向 App 发布加密货币提现网络，并拒绝新的链上提现申请；银行提现仍按原银行通道规则申请。

## 上线配置

以下步骤是未来验收清单。当前存在本页“当前上线边界”列出的阻断项，禁止执行第 4、5 步或用真实资金测试。

1. 在目标数据库执行 `scripts/migrations/20260928_cregis_deposit.sql`。服务在 `PROVIDER` 模式下检查表和关键唯一索引，缺失时拒绝启动。
2. 配置 Cregis 项目 ID、服务端 API key、Cregis API HTTPS 地址、公开 HTTPS 回调基址及只读 BSC JSON-RPC 地址。密钥留在安全配置中，不写入仓库或日志。
3. 在 Cregis 项目中核验 USDT-BEP20 `chain_id=2510`、`token_id=0x55d398326f99059ff775485246999027b3197955` 可创建地址，并配置回调地址、出站访问白名单及回调网络通路。TEST 公网入口使用 `/api/cregis/callbacks/deposit`；原 `/openapi/v1/withdrawals/cregis/callbacks/deposit` 只适用于直接连到后端的环境。
4. TEST 的发布器会把 `nexion.finance.cregis.mode=DISABLED` 写成 JVM 参数；仅设置环境变量不会启用。须通过受信任的主机更新安装支持 Cregis 的发布器，并由 root 专用许可文件授权 `PROVIDER`。然后设置 `NEXION_CREGIS_DEPOSIT_ENABLED=true`、`NEXION_CREGIS_DEPOSIT_CREDIT_ENABLED=true`、`NEXION_CREGIS_DEPOSIT_PILOT_USER_IDS=<逗号分隔的用户 ID>`、`NEXION_CREGIS_BSC_RPC_URL=<HTTPS RPC>`。试点最多 50 个用户，目标地址池 60 个。保持 `NEXION_CREGIS_PAYOUT_ENABLED=false`。
5. 使用已批准的试点账号读取 `GET /api/deposits/address?network=BEP20`，核对返回地址在 Cregis 项目中属于该项目；再做一笔受控小额真实转账，按 Cregis 交易、链上确认、回调收件箱、事件、钱包流水、充值单和 D1 储备逐项验收。未完成这些核对时保持收款开关关闭。

## 异常与恢复

- 地址创建由 `finance_d1_channel_manage` 管理接口发起，每次只创建一个候选并记录请求；供应商结果未知时封闭许可。链日志追赶并确认归属、供应商余额和 BSC 余额均为零后才入池。用户接口只能领取已核验地址；空池返回 `CREGIS_ADDRESS_POOL_EMPTY`。
- 回调只有签名和字段校验通过、原文落库成功后才返回 Cregis 要求的 `success`。异步任务通过 Cregis 交易查询和链上日志核实到账；服务中断后可重试。链上扫描对有地址的试点账户从分配区块开始，发现平台未收到回调的转账时保留 `PROVIDER_MISSING` 观察记录。
- `DUST_HOLD` 不加钱包余额。重复的 Cregis ID 或相同交易日志由唯一索引阻止再次入账；冲突保持待处理。数据库事务失败会回滚钱包、流水及储备。
- 回调和链上观察记录保留 `last_error` 以便运营区分供应商缺单、链事实不符和暂时不可用；定时任务继续核对，不根据错误自行加款或退款。已入账交易在 100 个后续区块内分段复核 canonical，冲突时冻结账户、待处理提现和可覆盖的余额并留存事件。
- 有 `finance_d1_read` 权限的运营人员可读 `GET /api/admin/finance/cregis/exceptions`，查看未知地址、超限/灰尘待审入金、供应商缺单及建址许可状态。该接口不提供直接改余额的操作。
- Cregis `PROVIDER` 模式及回调接口需维持到所有已分配地址上的未结入金处理完毕；单独关闭 `DEPOSIT_ENABLED` 只阻止新地址展示及创建。关闭 `DEPOSIT_CREDIT_ENABLED` 暂停新的钱包入账，回调与链上观察继续记录，恢复后重新核验再入账。

## 当前上线边界

- 上述迁移与功能开关是发布条件。开发环境测试不代表 Cregis 正式项目、BSC RPC、HTTPS 回调及银行提现运行态已验收。
- 现有银行提现服务只在绑定身份和 HDPay 银行出款配置就绪时开放。直接人工向银行卡打款的独立闭环需要单独验收，不能因充值本金已记入钱包而视为出款已可用。
- 地址池维护 API 与领取前核验已有候选代码，但 TEST 尚未部署验证，也没有后台补池、未知建址恢复和孤儿资金双人认领工作面。
- 充值单以 `(chain_tx_hash, asset, chain_log_index)` 唯一定位链事件。旧充值单升级时日志索引置 0；同笔交易出现相同地址和金额的多条日志，仍须等待供应商可核对的日志索引证据，不能猜测分配 CID。
- `DUST_HOLD`、`REVIEW_HOLD` 当前仅可查询，尚无双人复核的放款、退回与认领动作；完整 `trade/page` 双次稳定闭窗对账与未决敞口三开关熔断也未完成。TEST 发布器和实际公网回调仍需验收。上述恢复与对账控制完成前，收款和入账开关必须保持关闭；本次代码不能作为真实资金试点上线依据。

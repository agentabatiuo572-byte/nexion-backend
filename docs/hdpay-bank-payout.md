# HDPay 越南银行卡提现

BANK-VND 提现与代收共用现有 HDPay 商户配置；2026-09-16 按用户要求取消代付独立开关和固定提交 IP，以及 D7 的供应商就绪标记、通道总开关。正式 App 使用银行账户绑定、服务端报价、提交和原单恢复；PC D2 展示脱敏账户、锁定汇率、实付 VND 与供应商状态；D7 管理 VND 手续费参数。

## 资金和状态约束

- 首次绑定只提交收款账号、户名与空银行编码，不要求短信验证码；换卡必须验证当前账号绑定手机号的短信验证码。登录 USER 身份决定平台资源所属账号，**不能证明银行账户归属**。首次绑定、换绑均立即生效；历史绑定的 24 小时等待期也取消，使用实际绑定时间。取消 7 天换卡间隔；每次换卡都需要一次有效短信验证。有未决报价或在途单时不可换绑。登录、找回密码及其他提现地址验证码不变。
- 按 2026-09-16 用户确认取消额外的外部账户/归属核验前置条件，不生成伪造的 verified 证据。报价、提交、D2 审核及派发仍检查当前用户的收款账户和报价中的账户编号/版本一致；真实 HDPay 共用配置及资金约束继续生效。历史已派发单仍仅查询原单。
- 服务端锁定 D5/D7 版本、D6 汇率、银行卡版本和报价有效期。手续费由 USDT 总额扣除，净额按锁定汇率折算为整数 VND，向下取整；客户端不决定资金结果。
- 提交复用钱包冻结、限额及风险检查；同一报价只能生成一个订单。App 在发送请求前持久保存当前账户的报价号，响应丢失后只查原单或先完成耐久取消，不能直接重发新单。
- D2 审核通过才进入代付；发送前再次核对审核时的风险指纹和当前规则。先持久化 `DISPATCHING`，后调用 HDPay。网络超时或进程中断后只查原商户订单，不再次创建代付。
- 创建接口 `code=200` 仅表示受理，不代表付款成功。签名回调先持久化，再主动查单核对商户、订单、银行账户/姓名、金额和渠道。状态 1/2 等待，3 完成，4/5 失败并通过共享事务退回冻结总额（含手续费），最多一次。矛盾终态进入人工复核。
- 银行单不能借通用 D2 确认/退款绕过供应商核对。人工恢复仅查询原单，要求读、审核、退款三项权限、最新版本、原因及幂等键；不会创建另一笔代付。
- HTTP 完整响应体受读取总时限及 64 KiB 上限约束。超时取消请求，但不把已派发订单退回可再次发送状态。

## 接口

App 用户鉴权资源前缀：`/api/withdrawals/bank`（`BankWithdrawalController`）。

| 方法 | 后缀 | 用途 |
| --- | --- | --- |
| GET | `/config` | 就绪状态、`payType=BANKQR`、`bankCodeRequired=false`、`bindingOtpRequired`（有绑定账户时为 true）、脱敏绑定资料 |
| GET | `/recovery` | 当前账号全部未决银行意图；无意图为 null，多单不猜测覆盖 |
| POST | `/beneficiary/otp` | 已绑卡用户申请换卡短信；仅发送至当前用户注册手机号，返回 challengeNo、300 秒有效期和 60 秒重发间隔，不返回验证码或完整手机号 |
| POST | `/beneficiary` | 幂等绑定 |
| POST | `/beneficiary/verify` | 已停用；鉴权后返回 410 `BANK_BENEFICIARY_VERIFICATION_NOT_REQUIRED`，不写核验记录 |
| POST | `/quotes` | 服务端报价 |
| GET | `/quotes/{quoteNo}` | 当前用户原报价恢复 |
| POST | `/quotes/{quoteNo}/abandon` | 原报价耐久取消 |
| POST | `/orders` | 幂等提交报价 |
| GET | `/orders/{orderNo}` | 当前用户原单状态 |

PC：`GET /api/admin/finance/withdrawals/{withdrawalNo}/bank`；人工查原单：`POST .../{withdrawalNo}/bank/requery`。签名回调：`POST /openapi/v1/payments/hdpay/payout/callback`；应答 `success` 仅说明通知已耐久接收。

增量返回字段（均在现有 `ApiResult.data` 内，空值显式为 null）：

- `config.bindingDelayHours=0`、`config.changeCooldownDays=0`，不再返回 `capabilitySummary`。
- `beneficiary` 返回原绑定字段、`beneficiaryNo` 和 `canWithdraw`；存在当前用户绑定时 `canWithdraw=true`。不再返回外部核验状态或证据，通道是否可用须同时读取 `config.enabled`。
- `config.unresolvedIntent` 与 `/recovery` 的 data 相同：null，或 `{state,quoteNo,withdrawalNo,intents:[{state,quoteNo,withdrawalNo,expiresAt,providerState}]}`。单意图 state 为 NOT_SUBMITTED/COMMITTED；多个为 MULTIPLE，顶层两个编号 null。停用通道不会隐藏意图。
- 原报价恢复支持 EXPIRED，服务端已持久写入过期封存，允许重新报价；ABANDONED 是主动耐久取消。二者均禁止迟到提交。账号行锁串行化报价、提交、取消和换绑，单活跃报价/未结订单互斥。
- 所有 COMMITTED 返回及 D2 详情含 `settlementEvidence:{status,evidenceRef,providerOrderId,providerStatus,checkedAt,amountUsdt}`。status 为 unconfirmed/paid/refunded/review_required。只有匹配原单、金额、供应商与现有账本的证据才展示 paid/refunded；退款还需实际钱包账本。D2 审核拒绝的未派发单按已有 D2 退款账本核对。未知绝不自动退款或重发。
- D2 `beneficiaryEligibility:{canWithdraw,reasonCode}` 展示当前用户收款账户与报价的编号/版本是否一致；不包含明文账号/姓名，也不表示已经到账。

409 拒绝原因保留 `BANK_WITHDRAWAL_UNRESOLVED_INTENT`、`BANK_BENEFICIARY_REQUIRED`、`BANK_BENEFICIARY_CHANGED` 和 `BANK_PAYOUT_SNAPSHOT_MISMATCH` 等本地一致性检查。D7 不再要求外部核验能力，实际供应商配置和资金覆盖检查继续生效。

## 共享配置与运行条件

- 代收和代付使用 `nexion.finance.hdpay` 的同一网关、商户号、签名密钥及 `mode=PROVIDER`。取消 `hdpay-payout.enabled`、`client-ip` 和旧 `bank-codes` 配置。
- 提交 IP 从已认证 HTTP 请求获取；只有受信代理可提供原始 IP 请求头，非法头回退为连接方 IP。首次提交校验并持久化 `nx_hdpay_payout.client_ip`，异步审核/重启继续使用原 IP，幂等重放不能覆盖。
- 不再读取数据库 `finance.payout_vnd.provider_ready` 或聚合中的 `channelEnabled`；旧值保留回退，新参数保存不再写入开关。返回中的 `channelEnabled` 仅是共享配置状态的兼容投影；旧 `/channel` 接口返回 410。D7 页面只显示配置状态，未向供应商发起探测，不声称已到账。
- 代付创建当前使用 `payType="BANK"`，并明确发送 `bnkCode=""`（不是省略或 null）；代收使用 `BANKQR`。`/config.banks` 返回空列表；新绑定拒绝非空银行编码，历史成功请求仍按旧摘要回放，旧账户/报价保留历史标签而派发时统一传空编码。
- `/config` 返回 `bankSelection="ACCOUNT_ROUTED"`、`bankSelectionNotice="BANK_ACCOUNT_ROUTED_BY_NUMBER"`、`bankNameSource="PAYOUT_PROVIDER"` 和 `bankRoutingVerified=false`。当前尚无供应商认证的银行名称/编码回传契约；空编码账户的 `BANKQR` 仅为历史显示标签。服务端以 `BANK_ROUTING_IDENTITY_UNVERIFIED` 拒绝新绑定、换绑短信、新报价及未提交报价的新提交；既有 READY 单在派发前退回审核，不创建新的供应商代付。旧客户端直接调用也不能保存新的无银行身份账户、冻结或派发新资金。已派发订单查询、恢复、对账及成功幂等回放继续可用。取得供应商书面契约及真实校验数据后，另行实现银行身份核验并解除此闸门。
- 绑定不采集银行卡有效期、CVV。银行身份核验能力就绪后，首次绑定不需要 OTP；换卡必须提供专属 `PAYOUT-BANK-` challengeNo 和六位短信验证码，服务端校验用户、用途、过期、失败次数及单次消费。复用现有短信服务与 OTP 表，与提现地址共享 60 秒/每日 10 次发送限制；短信发送结果未知也保留计数。错误尝试和验证码消费在独立事务中持久化，成功绑定的幂等重放不二次消费。当前银行身份不可验证时不允许预绑定。
- 复用受管 `nexion.finance.hdpay` 传输凭据、基础地址及回调域名，以及既有金融敏感字段加密配置。不在仓库、日志或文档保存真实密钥或完整银行卡号。
- 需执行下列全部银行提现迁移、有效加密配置及严格非沙箱运行配置。经 2026-09-16 授权，`public-test` 的银行卡代付使用已启用的 HDPay 共用配置，不再因测试环境标记而禁用；其余测试环境策略保持不变。部署还须开放精确 POST `/openapi/v1/payments/hdpay/payout/callback` 并验证通道接口，不能只凭构建成功认定已开放；此改动不代表已完成真实出款验收。
- 调度默认 30 秒；关闭新代付不抹除在途状态，已派发单仍可查单收敛。
- 验证范围：接口构造、签名、回调核对与故障恢复由本地模拟网关及隔离 MySQL 验证，真实到账须另以实际供应商订单核验。未知状态/类型仍拒绝结算；受理未知只查询原单，不重新代付。

## 数据库和发布

仅新增版本化迁移，不改历史迁移的校验和：

- `scripts/migrations/20260916_hdpay_shared_config_request_ip.sql`：追加可空请求 IP，重复执行安全，不伪造历史 IP。已派发历史单按原单查询，不依赖 IP；待派发旧单缺 IP 时退回审核并写审计，保留未发送退款路径，不堵塞后续队列。就绪状态检查该列存在。
- `scripts/migrations/20260915_hdpay_bank_withdrawal.sql`：四张 BANK/HDPay 表（受益账户、报价、代付、回调收件箱）。
- `scripts/migrations/20260915_l6_bank_withdrawal_route.sql`：追加 App 页面行为目录。
- `scripts/migrations/20260916_bank_beneficiary_verification.sql`：历史账户核验证据和报价过期封存；核验表保留兼容，不再读写作为提现门禁，不更改历史迁移、不写 verified、不启用通道。
- `scripts/migrations/20260916_l6_withdrawal_method_route.sql`：登记 App 提现方式选择入口的行为目录，保留既有页面记录。

现有 public-test 发布代理会先校验历史脚本、检查磁盘容量、停止后端写入、备份并验证数据库转储，再逐个执行新 SQL，成功后记录一次性迁移回执。SQL 失败必须保留错误和备份，不跳过迁移、不改历史校验和，不自动恢复覆盖新业务数据。

发布顺序：后端（数据库迁移和关闭状态验收）→ PC → App，全部使用 `test`。验收需核对真实服务路径/提交、健康检查、迁移回执及关闭态 API；Jenkins SUCCESS 单独不足以证明已经部署。

代码回退可切回上一已验证发布；新增表兼容保留，不删除银行/资金历史。涉及数据库恢复必须先评估发布后业务写入，不能直接导回旧全库备份。

## 本地验证边界

资金并发、回调、权限、价格、调度和 HTTP 故障测试使用模拟供应商及 `127.0.0.1:13306` 的 UUID 隔离 MySQL 库；浏览器验收仅拦截本地测试 API。未调用真实代付创建接口，未向真实回调入口发送模拟通知，未修改测试服务器钱包或手工入账/退款。

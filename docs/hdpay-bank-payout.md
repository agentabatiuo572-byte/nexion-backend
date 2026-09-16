# HDPay 越南银行卡提现（默认关闭）

本次补齐 BANK-VND 提现链路，不启用真实代付，也不修改现有 USDT 链路或充值开关。正式 App 使用银行账户绑定、服务端报价、提交和原单恢复；PC D2 展示脱敏账户、锁定汇率、实付 VND 与供应商状态；D7 管理 VND 手续费参数。

## 资金和状态约束

- 绑定只提交收款账号、户名与空银行编码，不要求短信验证码。登录 USER 身份决定平台资源所属账号，**不能证明银行账户归属**。首次绑定保护期 24 小时、换绑间隔 7 天；保护期到期不等于核验通过。有未决报价或在途单时不可换绑。登录、找回密码及其他提现地址验证码不变。
- 账户存在、本人归属、VND 支付账户及商户代付能力必须有当前有效的服务端证据；报价、提交、派发分别校验。尚未接入已确认的账户验证服务商，能力摘要固定 `unavailable`，新绑定/重查保持 `unavailable/unknown`，不能通过 D7 开关或人工勾选放行；历史已派发单仍仅查询原单。
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
| GET | `/config` | 就绪状态、`payType=BANKQR`、`bankCodeRequired=false`、`bindingOtpRequired=false`、脱敏绑定资料 |
| GET | `/recovery` | 当前账号全部未决银行意图；无意图为 null，多单不猜测覆盖 |
| POST | `/beneficiary/otp` | 已停用；鉴权后返回 410，不发送短信 |
| POST | `/beneficiary` | 幂等绑定 |
| POST | `/beneficiary/verify` | 无请求体/幂等键；每分钟复用当前持久结果，不重启保护期；未配置提供方返回 unavailable |
| POST | `/quotes` | 服务端报价 |
| GET | `/quotes/{quoteNo}` | 当前用户原报价恢复 |
| POST | `/quotes/{quoteNo}/abandon` | 原报价耐久取消 |
| POST | `/orders` | 幂等提交报价 |
| GET | `/orders/{orderNo}` | 当前用户原单状态 |

PC：`GET /api/admin/finance/withdrawals/{withdrawalNo}/bank`；人工查原单：`POST .../{withdrawalNo}/bank/requery`。签名回调：`POST /openapi/v1/payments/hdpay/payout/callback`；应答 `success` 仅说明通知已耐久接收。

增量返回字段（均在现有 `ApiResult.data` 内，空值显式为 null）：

- `config.capabilitySummary` 与 D7 同源只读摘要：`status/provider/country/currency/recipientIdentifier/accountVerificationAvailable/ownershipVerificationAvailable/reasonCode/capabilityVersion/checkedAt`。本次 `status=unavailable`、VN/VND/bank_account、能力布尔值 false、provider/版本/检查时间 null。
- `beneficiary` 保留原字段，新增 `beneficiaryNo/verificationStatus/payoutCapability/ownershipStatus/accountType/reasonCode/checkedAt/expiresAt/evidenceRef/capabilityVersion/canWithdraw`。核验状态为 pending/verified/rejected/unavailable；能力 supported/unsupported/unknown；归属 matched/mismatched/unknown；账户类型 payment_account/credit_card/prepaid/unknown。缺证据时不放行。
- `config.unresolvedIntent` 与 `/recovery` 的 data 相同：null，或 `{state,quoteNo,withdrawalNo,intents:[{state,quoteNo,withdrawalNo,expiresAt,providerState}]}`。单意图 state 为 NOT_SUBMITTED/COMMITTED；多个为 MULTIPLE，顶层两个编号 null。停用通道不会隐藏意图。
- 原报价恢复支持 EXPIRED，服务端已持久写入过期封存，允许重新报价；ABANDONED 是主动耐久取消。二者均禁止迟到提交。账号行锁串行化报价、提交、取消和换绑，单活跃报价/未结订单互斥。
- 所有 COMMITTED 返回及 D2 详情含 `settlementEvidence:{status,evidenceRef,providerOrderId,providerStatus,checkedAt,amountUsdt}`。status 为 unconfirmed/paid/refunded/review_required。只有匹配原单、金额、供应商与现有账本的证据才展示 paid/refunded；退款还需实际钱包账本。D2 审核拒绝的未派发单按已有 D2 退款账本核对。未知绝不自动退款或重发。
- D2 `beneficiaryVerification` 展示该报价所指受益账户的核验证据（不包含明文账号/姓名，不等同于历史到账证据）。

409 拒绝原因新增 `BANK_WITHDRAWAL_UNRESOLVED_INTENT`、`BANK_BENEFICIARY_UNVERIFIED`、`BANK_BENEFICIARY_INELIGIBLE`、`BANK_BENEFICIARY_VERIFICATION_EXPIRED`；D7 新开通还要求能力就绪，否则 `D7_ACCOUNT_VERIFICATION_NOT_READY`。前端将原因转为可读文案，不能自行变更资格。

## 配置与启用前条件

- 独立开关 `nexion.finance.hdpay-payout.enabled=false`；充值 HDPay 启用不等于代付启用。
- `client-ip` 默认空，仍必须配置经供应商确认的出口 IP。旧 `bank-codes` 属性仅保留兼容，不再作为 BANKQR 就绪条件；独立 enabled 与 D7 门禁不变。
- 用户经 HDPay 客服确认代付创建使用 `payType="BANKQR"`，必须明确发送 `bnkCode=""`（不是省略或 null）。`/config.banks` 返回空列表；新绑定拒绝非空银行编码，历史成功请求仍按旧摘要回放，旧账户/报价保留历史标签而派发时统一传空编码。
- 新绑定不采集有效期、CVV 或 OTP。请求的旧 OTP 字段仅为旧幂等结果回放保留，不参与新绑定授权。允许代付开关关闭时预绑定，不代表实际出款已启用。
- 复用受管 `nexion.finance.hdpay` 传输凭据、基础地址及回调域名，以及既有金融敏感字段加密配置。不在仓库、日志或文档保存真实密钥或完整银行卡号。
- 需执行下列全部银行提现迁移、有效加密配置及严格非沙箱运行配置。`public-test` 防护仍禁止开启真实代付，本次不改变其信任策略。
- 调度默认 30 秒；关闭新代付不抹除在途状态，已派发单仍可查单收敛。
- **正式启用仍 HOLD**：需供应商书面确认查询 `withdrawalType="2"` 的含义、状态 4 的退款终态语义、创建受理但本地断线后的 NOT_FOUND/同商户订单号重试规则。还需经授权的独立供应商联调；本次本地模拟网关通过不等于真实到账验收。

## 数据库和发布

仅新增版本化迁移，不改历史迁移的校验和：

- `scripts/migrations/20260915_hdpay_bank_withdrawal.sql`：四张 BANK/HDPay 表（受益账户、报价、代付、回调收件箱）。
- `scripts/migrations/20260915_l6_bank_withdrawal_route.sql`：追加 App 页面行为目录。
- `scripts/migrations/20260916_bank_beneficiary_verification.sql`：账户核验证据和报价过期封存；不更改历史迁移、不写 verified、不启用通道。
- `scripts/migrations/20260916_l6_withdrawal_method_route.sql`：登记 App 提现方式选择入口的行为目录，保留既有页面记录。

现有 public-test 发布代理会先校验历史脚本、检查磁盘容量、停止后端写入、备份并验证数据库转储，再逐个执行新 SQL，成功后记录一次性迁移回执。SQL 失败必须保留错误和备份，不跳过迁移、不改历史校验和，不自动恢复覆盖新业务数据。

发布顺序：后端（数据库迁移和关闭状态验收）→ PC → App，全部使用 `test`。验收需核对真实服务路径/提交、健康检查、迁移回执及关闭态 API；Jenkins SUCCESS 单独不足以证明已经部署。

代码回退可切回上一已验证发布；新增表兼容保留，不删除银行/资金历史。涉及数据库恢复必须先评估发布后业务写入，不能直接导回旧全库备份。

## 本地验证边界

资金并发、回调、权限、价格、调度和 HTTP 故障测试使用模拟供应商及 `127.0.0.1:13306` 的 UUID 隔离 MySQL 库；浏览器验收仅拦截本地测试 API。未调用真实代付创建接口，未向真实回调入口发送模拟通知，未修改测试服务器钱包或手工入账/退款。

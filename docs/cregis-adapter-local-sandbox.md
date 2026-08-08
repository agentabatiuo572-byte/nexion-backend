# Cregis USDT-BEP20 适配器与本地沙箱

## 当前结论

本批只交付服务端适配边界和进程内本地沙箱，不连接用户余额、充值入账、提现预占、真实付款或退款。

- 默认模式是 `DISABLED`，不会访问 Cregis。
- `LOCAL_SANDBOX` 每次管理探针使用一个全新的进程内模拟器实例，模拟项目币种、地址创建/归属/合法性、钱包代付、查单和回调验签；`externalFundSideEffects=false`。
- `PROVIDER` 已有 HTTP 适配器，但在正式 Project、API Key、IP 白名单、官方测试机制和未知提交恢复合同核定前不得开启。
- UniApp、Janus、PC 均不得保存 Cregis API Key，也不得直接调用 Cregis。

这不是 Cregis 官方沙箱，也不是生产接入通过证明。

## 已适配的官方合同

| 能力 | Cregis 路径 | 本批边界 |
| --- | --- | --- |
| 项目币种 | `POST /api/v1/coins` | 严格解析 payout/address 两组币种 |
| 创建充值地址 | `POST /api/v1/address/create` | 缺地址、超时、断连或 5xx 一律 `CREGIS_SUBMISSION_UNKNOWN`，不自动重发 |
| 地址归属 | `POST /api/v1/address/inner` | 只读，畸形 200 失败关闭 |
| 地址合法性 | `POST /api/v1/address/legal` | 只读，畸形 200 失败关闭 |
| 钱包代付 v1 | `POST /api/v1/payout` | `third_party_id` 作为供应商唯一业务关联/去重键；重复号或未知结果都不自动重发 |
| 钱包代付查询 | `POST /api/v1/payout/query` | 必须携带本地订单快照；严格校验 Project、USDT-BEP20、业务号、金额、状态、地址和成功 txid |
| 钱包代付回调 | Cregis 调用平台 URL | 已实现签名、项目、时间窗、终态、资产范围及本地订单快照验证；尚未开放生产回调入口 |

官方依据：

- <https://developer.cregis.com/api-reference/signature>
- <https://developer.cregis.com/api-reference/request-apis/global/currency-query>
- <https://developer.cregis.com/api-reference/request-apis/address/address-generate>
- <https://developer.cregis.com/api-reference/request-apis/payout/payout-create>
- <https://developer.cregis.com/api-reference/request-apis/payout/payout-query>
- <https://developer.cregis.com/api-reference/callback/payout>

签名按官方规则执行：排除 `sign`、null 和空值，键名字典序拼接，前置 API Key 后取小写 MD5。官方签名页当前“计算结果”段与最终请求示例不一致；自动化测试按算法自行计算并锁定最终请求示例，正式联调仍需用 Cregis 官方工具交叉验证。

供应商返回 `E0009` / `E0018`（重复业务号）或 HTTP 409 时，适配器按“可能已有历史订单”处理为未知结果，禁止把它当成可安全退款或换号重发的明确拒绝。

本地模拟器也故意不提供比供应商更强的重放保证：重复地址请求号或重复 `third_party_id` 均返回未知态，不复用地址或 `cid`。本地探针每次使用隔离实例，所以重复运行不会累计业务夹具，也不能据此证明生产幂等恢复已经解决。

## 本地启动

PowerShell 中仅为当前进程设置：

```powershell
$env:NEXION_CREGIS_MODE = 'LOCAL_SANDBOX'
& 'D:\software\apache-maven-3.9.9\bin\mvn.cmd' spring-boot:run
```

管理端合同入口：

- `GET /api/admin/finance/cregis/sandbox`：需要 `finance_d1_read`，只返回模式和边界，不返回任何凭据。
- `POST /api/admin/finance/cregis/sandbox/probes`：需要 `finance_d1_bank_config_manage`，执行无资金探针。

探针覆盖币种发现、地址生成、格式校验、项目归属、代付提交、带本地快照的查单和合成回调验签。每次探针使用独立内存实例，完成后不保留业务夹具，不能作为账本、资金事实或安全重试证明。

## 配置

```yaml
nexion:
  finance:
    cregis:
      mode: DISABLED
      base-url: ""
      callback-base-url: ""
      project-id: 0
      api-key: ""
      connect-timeout-ms: 1000
      read-timeout-ms: 2000
```

生产适配器只接受 HTTPS Base URL；HTTP 仅在包内测试构造器且目标为 loopback 时可用。充值与代付回调地址分别精确绑定到服务端配置的 HTTPS `callback-base-url` 下 `/deposit` 与 `/payout`，拒绝编码路径、跨域、查询串和片段，不能由 App、Janus、PC 或业务请求任意指定。供应商响应采用 1 MiB 空间上限与独立 body 读取截止时间的双有界读取；错误响应和异常只输出稳定错误码，不拼接 API Key、签名或供应商原始响应。

当前回调 verifier 只负责验签、时效、资产和本地订单快照匹配，不具备 durable replay 防护。生产回调入口必须先把每次原始投递写入 durable inbox，再按业务指纹幂等消费。主动查单同样必须从权威订单读取预期 `cid/third_party_id/address/amount`；只有查询结果与该快照完全一致时才能进入后续核验，单靠当前适配器仍不能完成未知提交恢复或资金记账。

## 生产 NO-GO 项

下列任一项未闭合时，不得把 `NEXION_CREGIS_MODE` 改为 `PROVIDER`：

1. 正式 Project 的 `/coins` 返回值及 USDT-BEP20 chain/token 双验；
2. Cregis 对 Wallet Payout v1、重复 `third_party_id`、无 `cid` 未知提交的书面恢复合同；
3. 官方测试环境或受控真钱白名单、回调重试/IP 白名单/手工重推行为；
4. 回调 durable inbox、查单补偿、BSC RPC 终局核验和窄日志发现；
5. 地址库存、资金预占、唯一付款执行器、双账本、对账、退款和 P0 停机回退；
6. 真实浏览器、真实后端、权限矩阵和故障注入验收。

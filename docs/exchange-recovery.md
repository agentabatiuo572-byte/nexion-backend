# 兑换结果只读恢复

`GET /api/exchange/recovery` 使用当前 USER 身份、`Idempotency-Key` 请求头及原请求的 `direction`、`fromAmount`、`queueIfCapped` 查询结果。不得传入或信任客户端提供的用户 ID、测试 run ID。

返回 `status`、`sourceEnvironment`、`runId`。只有 `SUCCEEDED` 附带原订单的当前 `order`；不返回历史钱包、完整幂等响应或请求 hash。生产路径按相同的规范化请求 hash 读取保留回执，再按认证用户及回执中的订单号读当前订单。测试路径只读当前 run、用户、请求 key 对应的隔离订单。

查询不受新兑换暂停或当前最低金额配置影响，不加钱包/订单锁，不重新认领、延长、回收或提交原请求。过期的成功回执仍可读取。损坏或缺少订单的成功回执降为 `UNKNOWN`。

`FAILED`、`PROCESSING`、`UNKNOWN`、`NOT_FOUND`、`MISMATCH` 均不授权客户端生成新 key 或重发兑换。客户端必须保留原请求；不得按金额相等或分页列表中出现新订单推断成交归属。

验证：`AppExchangeRecoveryTest`、`AppExchangeRecoveryControllerTest`；`ExchangeRecoveryMySqlIntegrationTest` 在 UUID 临时库执行真实 mapper SQL，覆盖用户/run/key 隔离及并发写入期间读取已提交订单。临时库测试需现有测试数据库环境变量，结束后删除其自有库。

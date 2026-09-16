# 银行卡提现页面额度投影

2026-09-16：App 原型界面集成沿用 `/api/withdrawals/bank/config` 的 `policy`（PC D7 原始配置），增加 `capacity`：`maxWithdrawableUsdt`、`dailyRemainingCount`、`dailyLimitCount`、`dailyCountResetAt`、`withdrawalEnabled`。

额度复用实际银行扣款的已释放余额、D5 余额比例、越南自然日次数和 J1 开关。单笔上下限来自 D7，不与余额或日剩余次数混为一项。新页面只展示和预检查；实际提交继续在事务内锁定报价、账户和余额，校验 D7/D5 版本与每日次数。

`capacity.dailyCountResetAt` 使用带时区的 ISO 8601 字符串，与 App 日期解析契约一致；固定时钟测试验证越南零点边界及输出类型。

移除银行报价及银行内部扣款路径重复写死的 20 USDT 下限，按 D7 的单笔限额执行；USDT 链上提现的既有下限不变。手续费、净额、VND 正数及六位精度校验不变。

容量是 config 事务中的只读查询，不创建订单、不预占资金。可恢复的业务读取失败返回 capacity=null，保留原单恢复信息；实际数据库异常传播回滚。Spring 代理测试覆盖这两条路径。

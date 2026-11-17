# 客服待分配策略回执与会话摘要

## M5 待分配策略

总管理员通过 `PUT /api/admin/content/support-agents/unbound-policy` 保存告警时限、兜底时限和默认分组。可恢复的请求须传 `Idempotency-Key`，去掉首尾空白后为 8–128 个英文字母、数字、下划线或连字符。含斜杠等不支持字符时返回 422 `IDEMPOTENCY_KEY_INVALID`。保存前保留该编号、原配置版本、理由和完整请求。旧客户端省略此请求头时，沿用版本校验保存。

每次新操作生成独立编号。客户端查询和重试都使用原编号，包括原有大小写。命令表沿用现有数据库的大小写不敏感排序规则，同一操作人只改变编号字母的大小写，仍定位到同一份回执。

响应丢失时，同一操作人调用 `GET /api/admin/content/support-workbench/commands/{key}`。成功返回 `data.status=SUCCEEDED`、`data.resultType=SUPPORT_UNBOUND_POLICY` 和 `data.result`。其中 `result` 是当次保存的完整回执。若另一次编辑已生效，读取当前策略用策略 GET，核对原次结果用命令 GET。查询和重放都复核当前超管身份及 `service_m1_write` 权限。

同号同内容成功后重试，返回原回执，策略版本和成功审计只增加一次。同号改动内容返回 409。两个不同命令竞争同一配置版本时，只能有一个生效。业务写入失败回滚后，按该号可以查到失败状态。已发起的写入结果未确认时，PC 应保留原提交并支持关闭重开后继续查询。

## M3-BE-01 超时策略的原请求业务结果（2026-11-17）

`GET /api/admin/content/support-workbench/commands/{key}` 仅对精确 `M3_CONVERSATION_TIMEOUT_POLICY` 补充 `data.businessCode`。原 `data.status` 保持技术处理状态，不表示业务保存成功。查询接口自身的外层 `code=0` 也不替代原保存的业务结果。

对正常完成（`status=SUCCEEDED`）的原持久回包，读取其顶层整数 `code`：0 是原业务成功码，非 0 原样返回（包括版本过时、内容等值等业务拒绝）。回包缺失、不可解析、没有有效整数码或非正常完成状态时 `businessCode=null`，表示不能确认；不从技术状态猜 0，不重读当前策略冒充当次结果，不返回整份历史 body。

沿原请求编号/查询范围、当前客服查看范围及超管身份与 `timeoutManageGrant` 检查，不增加其它 support scope 的字段，不更改原 owner、业务 writer、幂等 hash/TTL/完成状态、历史回包或异常事务政策。仅 Java 读取投影与必要跳测构建；未执行 E2E、HTTP、SQL、数据库业务或部署，未推送或核销原行。

## M3 会话列表与 M1 最近会话

客服或客户发送无正文图片时，列表返回 `lastMessageKind=IMAGE`。PC 可据此显示「[图片]」。公开消息按写入的消息 ID 定序，摘要类型与 `lastPublicMessageId` 指向同一条消息，即使两次发送的时间相同或时间戳先后倒置。老消息按已保存的消息类型或人工消息记录识别，普通历史文本使用 `TEXT`。商品和页面推荐分别返回 `SKU`、`LINK`，保留原正文。

内部备注不构成公开预览。若系统操作替换了摘要，应显示该系统提示；原有超时关闭标识为 `IDLE_TIMEOUT_CLOSE`。客服主动发起会话时，发送回执与随后读取的消息类型一致。会话列表只返回获准范围内的摘要，打开图片仍单独校验附件权限。

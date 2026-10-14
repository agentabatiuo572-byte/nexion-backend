# 专属客服中心交付与待测索引

日期：2026-10-14。代码交付目标为 `test`；不是生产发布或全部产品场景签收。

## 合同与验收入口

- 本仓 API、授权和事实边界：[support-analytics-contract.md](support-analytics-contract.md)。
- 产品 PRD：[PC 工程 M 专属客服中心功能契约](https://github.com/agentabatiuo572-byte/nexion-frontend-pc/blob/test/docs/PRD/M-专属客服中心功能契约.md)。
- 已验范围与同事接续清单：[统一交接](https://github.com/agentabatiuo572-byte/nexion-frontend-pc/blob/test/docs/acceptance/support-center-handoff-20261014.md)。
- 客户消息链：[暂缓测试说明](https://github.com/agentabatiuo572-byte/nexion-frontend-pc/blob/test/docs/acceptance/support-customer-message-pending-20261014.md)。暂缓测试不删除正常客户认证、接收、阅读、刷新或断线恢复的要求。

## 可复用证明

本次实现版本 `c37c582e6acec2547fd11c8a5d37737bb02775a5` 已由 [PR #3](https://github.com/agentabatiuo572-byte/nexion-backend/pull/3) 合入 `test`（合并提交 `e19e3ce1a7e471d6e994492f13724b9104a11055`）。组目录批量读取、空值/真实0、当前范围、客户同源计数及原写回包边界的定向检查与本机 MySQL 实景已通过；未改变现有五字段写回包或资金权限。

本金提现、收益优先提现、原单后台退款、手续费及净贡献的已列真实场景和纯算法证明继续沿用。不得为了补页面验收再造充值、提现、退款或重复完整库。消息7675的服务事务、实时回执与刷新证明，不等同于正常客户 controller 链。

## 仍需同事确认的真实前提

1. 客户正常认证与消息链按任务负责人决定暂缓；使用合法客户会话，不能自签 token、SQL 改已读或以管理账号代替客户。
2. 现隔离库活动窗口未配置、资格历史起点缺失，新版榜候选/资金来源仍不完整；不能证明真实 READY 多页查询、完整三榜或昨日可比基线。先取得真实完整来源，再验全量排序、版本失效、本人定位和日末基线；不手写完整标志或历史榜。
3. 新空库首次初始化、完整应用启动两次、缺共享 schema 的严格负例尚未取得本轮 native 证明。已过现有单例升级及显式有限0/2保留，不代替新安装场景。现隔离库不能通过删历史伪装新库。
4. 未出现的业务对象、缺授权的写动作保持未验，不从同类单测或其它对象结果推定通过。

本机已有隔离环境为 `127.0.0.1:33338/cs_analytics_20261007`，UUID `abe359c0-c621-11f1-97ea-a40c6626953d`。复用前核身份、源码、授权与服务占用；配置及凭据通过团队受控方式交接，不进入 Git。沿用 `support-analytics-20261007-r2` 环境记录，窗口日志限8 MiB、增量限256 MiB；完成后只停止本任务自有服务。不要改原来源库或扩大数据库授权。

# S4 实施补充

来源：协调会话 01a0eb85-749f-7e70-bb83-0139afe06045 转达主人已确认设计还原需求和实施 GO。冻结 S1 七文件保持不变。

## 已确认补充

- TODO 是 DUE、WAITING_REPLY、FIRST_CONTACT 按客户去重的并集，由服务端筛选与统计。
- 当前归属的 STOPPED 人数与同响应停止名单总量一致。
- performance 选定 from/to 按日返回维护执行数、成功周期数；成功客户数单独去重。日期复用 Asia/Shanghai 业务时区，事件保存 UTC，范围采用半开区间；无能力返回 unavailable。
- overview 与 customers 返回同一响应中的指标和分页名单，使用同一授权、规则、evaluatedAt 和 snapshotId；跨请求不得宣称同一快照。
- 最近会话沿用 S3 已有授权分页，不新造入口。

## 实施边界和验证

基底 7fec53d231ebcef1e36c898bbbee61d329b69b99，唯一写树 cs-maintenance-20260929-backend。复用客户锁、归属表、对象存储；无新 BI/事件框架。所有测试只访问回环 MySQL33329/cs_redesign、Redis16329、S3对象19029、应用18129，保留S3数据，新建S4夹具，不重跑S3初始化。

先证伪：发送成功与维护成功混淆、登录刷新假活动、覆盖缺口假完整、停止/转绑竞态、重试双计、图片旧权限与未发草稿泄漏、分页和多原因总数不一致。真实HTTP/SQL/对象存储回读并留日志，最终Java和security独立审查。

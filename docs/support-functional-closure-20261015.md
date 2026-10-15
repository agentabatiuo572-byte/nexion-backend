# 客服中心最小功能验收收尾

2026-10-15：按已批准的最小测试范围，**非消息功能验收通过**。独立 Java 审查及范围/流程复核均无 P0/P1/P2。真实客户消息链继续交由同事接续；不将原112项未执行操作、旧库历史兼容性或生产部署改签为通过。

## 本轮仅补两条

| 功能 | 实际执行与结果 |
| --- | --- |
| 完整统计排序、翻页与变化失效 | 5个无交易客户、完整首次付款历史、双币零资金输入，固定来源时点；采用生产六参数 Stats、四参数 PrivateQuery，实际计算 READY 和版本，经 Controller/MVC 返回。ASC/DESC各三页、后置最大值、并列稳定顺序、空值末尾均正确；非首页钱包版本变化和客户移除分别令旧页返回409，刷新得到新版本。1 started / 1 passed / 0 skipped，2.557秒 |
| 空停用组归档 | 原 GroupService 将 DISABLED 变为 ARCHIVED，使用版本7更新、返回版本8；核对完整审计、范围变更事件及无关系/历史写操作。1 started / 1 passed / 0 skipped，1.061秒 |

[机器证据](support-functional-closure-20261015.json)包含完整测试输出、11个源码指纹、明确输入及独立审查结论。两个测试都使用受控来源和身份；不声明此次执行了真实登录、SQL、网络或浏览器。分页没有预写 READY、版本或最终排序。归档的数据库更新由 mock 模拟，其真实 SQL 证明按下节复用。

运行器只选择两个新增方法；分页补入当前资金依赖后，只重跑该方法，已通过归档不重跑。最终有效测试耗时合计3.618秒；没有新增数据库、业务数据或服务。Java 参数文件的 `#` 方法选择器已加引号，运行器拒绝零参数、零测试、跳过或测试数量不符，不能以进程退出0代替测试通过。

## 复用而不重跑

- 首充榜、净贡献榜：沿用[两榜完整发布验收](support-rank-complete-20261015.md)；合成来源与固定时间，真实计算、双币四流持久发布、独立连接及服务重建读回。
- G09：原 I1-B 实景已经证明队列路由关闭、分配后待分配池为0、个人客户不重复计数。原候选 `7d8505cbfcdd72808eb8548bbe9eeff8d3714e9c` 及独立报告均通过，原生执行与清理完成；当前 `transferOnce`、`closeQueueRoute` 与该候选逐方法一致。
- G21：同一真实记录已有阻退、停用、同键单次版本、旧版本拒绝和队列先关闭证明。当前 `status` 方法及参数化状态更新 SQL 与原候选相同；本轮只补之前缺少的归档成功分支，不永久归档现有411组。
- 原统计 MySQL 四项已通过，原版本分支仍为 UNKNOWN，不能改写为本轮真实 READY。完整输入的应用组合与原 SQL 证明分层使用。
- 资金、账号、调组、M3原能力、正式页面与批准的布局/材质/交互证据沿用原版本。前后端生产源码未变；不重复全量构建或浏览器矩阵。

## 复现与交接

```text
mvn "-Dtest=SupportAnalyticsCompletePagingTest#readyFullSetSortsAcrossThreePagesAndRejectsChangedMembership,SupportGroupServiceTest#emptyDisabledGroupArchivesWithCasAuditAndPreservedHistory" test
```

只应启动这两个方法，最终必须2 passed、0 skipped。它们不要求数据库配置或凭据。后续接续范围和正常客户认证要求见[交接索引](support-center-handoff-20261014.md)。产品契约未变，PRD保留已批准的净贡献、范围和异常规则。

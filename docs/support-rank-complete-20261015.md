# 首充榜、净贡献榜最小集成验收

2026-10-15：**PASS**。采用明确合成的完整来源和固定测试时间，正式榜单计算、MySQL 发布提交、Controller 读取及重建服务后的同版读回均通过。未修改生产代码、旧业务数据或机器时钟。

## 输入与独立预期

3 位专属客服、3 位客户、6 个双币付款事实、4 个原来源冲减；资格起点为 2026-09-01，来源计算时点固定为 2026-10-09T02:00:00Z。发布时间仍由数据库真实时钟产生。

| 客服测试 ID | 首充客户数（两币相同） | 绑定客户数 | USDT 净额 | NEX 净额 |
| --- | ---: | ---: | ---: | ---: |
| 7 | 2 | 2 | (100−20)+30 = 110 | (10−2)+3 = 11 |
| 8 | 1 | 1 | 200−50 = 150 | 10−5 = 5 |
| 9 | 0 | 0 | 0 | 0 |

首充按每客全历史唯一事件计数，六笔付款合计三个首充客户。首充双币和 NEX 净额名次为 7、8、9；USDT 净额名次为 8、7、9。整数类型断言保证缺字段或 null 不能冒充真实零。

## 执行与结果

- [测试源码](../src/test/java/ffdd/opsconsole/content/application/SupportLeaderboardCompletePublicationIntegrationTest.java)：只新增一个测试，直接调用原 SourceService、Service、排名算法、PublicationService 和 MyBatis SQL；使用真实 RR 与 REQUIRES_NEW 事务。
- 来源 mapper、资金事实接口及身份为测试输入；Controller 经 MockMvc 调用。不是正常登录或真实网络测试，也不声称旧库历史已经修复。
- 四流全部 COMPLETE；金额、名次、首充、绑定数、本人摘要、前三、第二页和详情符合预期。独立数据库连接读取四条已提交发布及指针、核对 payload/hash/定义/版本；重建服务后整份首屏响应不变，没有重复发布。
- 1 found / 1 started / 1 passed / 0 skipped；测试 2.905 秒，定向编译加运行 5.045 秒；九个相关源码指纹未漂移。生产源文件重新定向编译，其他依赖沿用现有构建。
- 现有 33338 上只创建唯一临时 schema 及原迁移两张表，记录四条快照和四条指针；独立连接确认后删除 schema，三个连接池全部关闭。测试数据库占用及清理结果见[机器证据](support-rank-complete-20261015.json)。本轮本地全部产物 367,173 字节，未复制大库或启动完整应用。
- 独立 Java agent 回源核算并复核证据；发现的零值断言 P2 已修正，最终 P0/P1/P2 无遗留。独立流程 agent 审查范围，无重复测试或扩大环境。

## 复现

在本仓根目录，用团队私密渠道设置临时数据库维护凭据，配置以下环境变量后只运行该类：

```text
SUPPORT_RANK_COMPLETE_MYSQL=true
SUPPORT_RANK_MYSQL_ADMIN_URL=jdbc:mysql://127.0.0.1:33338/mysql?useSSL=false&allowPublicKeyRetrieval=true&connectionTimeZone=UTC
SUPPORT_RANK_SERVER_UUID=<明确获准的本机隔离 MySQL UUID>
SUPPORT_RANK_MYSQL_USERNAME=<隔离环境维护账号>
SUPPORT_RANK_MYSQL_PASSWORD=<私密值>
SUPPORT_RANK_EVIDENCE=<已有目录内的报告 JSON 绝对路径>
mvn -Dtest=SupportLeaderboardCompletePublicationIntegrationTest test
```

执行前确认仅此一类被选中；结果必须实际启动并通过 1 个测试，环境变量缺失导致跳过不算通过。类只允许本机 33338，并先核对 UUID；动态 schema 由本次创建，收尾只删除该 schema。进程被外部强停时，依据报告/日志确认本次 schema 归属后清理，禁止清理原业务库。

## 已通过证据复用与边界

已通过的资金结算、去重/提现/后台退款、权限、算法反例、正常登录和页面交互不重跑。此次关闭的是完整输入下两榜计算到持久发布的功能缺口。旧库缺失历史属于数据接入兼容性问题，仍显示未知，不能因此阻止隔离完整数据验收，也不能以本次测试补造其历史。

真实客户消息链继续按既有清单交由同事；本轮不新增消息测试或外部支付。产品契约、生产权限和现有服务状态均未改变。

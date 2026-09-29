# App 本人顾问查询

## 范围与来源

基线 `f5e5f65bff4e0cf62ca68d8a4170c96f0fee057d`；仅 `codex/cs-app-assignment-20260929`。
主线确认正式客户端 `nexion-frontend-uniapp/test`，来源记录在仓外 `CLIENT-SOURCE-CONFIRMATION.md`。
这是无会话首屏所缺的只读投影；不改责任绑定写入、历史作者、scope-invalidated、数据库结构或原接口。

基本事实：ACTIVE assignment 是当前责任；顾问停用不会解除该责任。资格沿用 admin、SUPPORT 角色和专属 advisor profile 的既有条件。busy 不等于在线；HTTP 首屏没有 WebSocket 在线证据。
最小实验：真实认证 HTTP 对无会话客户读取绑定/未绑定/停用/忙碌，再转绑并以新请求读回；验证匿名与替身读取失败，活动和维护事实不增加。

## 接口契约

`GET /api/app/support/advisor`，无查询参数，Bearer 身份必须是当前认证的 USER；沿用 ProductionSupportPathGuard，响应 `Cache-Control: no-store`。
成功为 `ApiResult` 的 `code=0`，data 仅含以下五项。ID 是后端生成的数值；assignmentId 不是会话 ID。

| 情况 | assignmentId / currentAdvisorId / currentAdvisorName | assignmentState | availability |
|---|---|---|---|
| 无 ACTIVE 绑定 | 全部 null | UNBOUND | UNBOUND |
| 有绑定，admin/role/profile 任一资格失效 | 保留真实绑定和顾问身份 | ADVISOR_DISABLED | DISABLED |
| 有效顾问且 busy=true | 当前绑定和顾问身份 | ASSIGNED | BUSY |
| 有效顾问且 busy=false | 当前绑定和顾问身份 | ASSIGNED | UNKNOWN |

```json
{"code":0,"data":{"assignmentId":123,"currentAdvisorId":45,"currentAdvisorName":"顾问甲","assignmentState":"ASSIGNED","availability":"UNKNOWN"}}
```

已软删除或停用的顾问仍读取原记录上的真实名称；若记录本身缺失，名称为 null，不虚构替代人名。资格失败优先于 busy。只有不存在 ACTIVE 绑定才是 UNBOUND。
UNKNOWN 不能显示为离线。客户端显示中性可留言状态，仅在现有 WS presence 明确提供证据后展示在线/离线；不能由姓名、登录会话、历史接待或容量推断。停用仍可留求助，严格专属、无 fallback。scope-invalidated 后重新 GET；不得继续使用旧顾问投影。
匿名被认证链拒绝；非 USER 为 403；任何查询参数（包括 customerId）为 422，不支持代查。环境或账号类别不匹配仍由既有 guard 拒绝。失败显示重试，不用旧姓名伪造当前归属。

实现仅执行同一条普通 SELECT：以当前 ACTIVE 绑定为根，LEFT JOIN 顾问资料并用共享资格条件判断状态；不混合 FOR SHARE 当前读和旧 RR 快照。GET 不创建档案、会话、登录活动、维护执行或成功事实。不返回手机号、内部原因、资格细则或维护状态。

## 分步验收

1. 固定上述五字段契约并同步 S6；回源确认所有资格层和现有认证入口。
2. 最小 DTO、mapper/repository/service/controller 投影；新增真实 HTTP 契约与安全反例。
3. 独立复制库 `cs_advisor_patch`、Redis DB14、19030 独立存储的 `cs-advisor-patch-private` 私有桶和 18130 测试上下文，测试显式移除定时任务处理器。现行 18129、19029 和旧工作树不动。补丁 env 仅用于测试；主线升级原服务时使用原 S4 环境和新 jar，不能将补丁 clone 环境带到原服务。
4. 回归原 S4 279 项和 9 项真实并发；新补丁证据独立保存在 `C:/Users/jason/.codex/workflow-runs/customer-service-20260929/advisor-patch`，不覆写历史 S4 证据。
5. 独立 Java 审查、当前提交运行门、六维复核后 commit/push；主线验收后才接手部署，不将分支推送视为运行服务升级。

验收映射：advisor-contract 覆盖本人/无会话/三种资格状态/忙碌/UNKNOWN/转绑新请求；advisor-security 覆盖匿名/错身份/替身参数/环境保护/no-store/无业务写副作用；advisor-regression 覆盖共享资格语义和原 S4 全部选择测试及 RR 并发；advisor-handoff 覆盖接口、隔离资源、提交、未部署边界。

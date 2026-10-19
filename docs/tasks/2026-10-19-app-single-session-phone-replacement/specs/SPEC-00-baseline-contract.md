# SPEC-00：开工基线、契约和验收准备

状态：未开始。Owner由接手团队指定。前置：产品方案已确认，不再等待相同规则批准。

## 目标与范围

建立四个真实仓库的实施快照，让后续每步使用相同规则、接口和验证环境。读取 [HANDOFF](../HANDOFF.md)、[共同规格](../inputs/PRD/specs/FEAT-SESSION01-app-single-login-phone-replacement.md) 及各仓规则；此步不修改生产配置，不把调研当业务实现。

允许：本任务记录、定向测试计划、接口清单、关键流原型；如需可执行 workflow v2 计划，先核本地CLI/模板，用实际路径分别建仓级计划，再关联跨仓集成。不可复制发起人的绝对路径或伪造检查通过状态。

## 执行动作

1. 每仓运行 `git status --short --branch`、`git remote -v`、`git fetch origin --prune`、`git rev-parse HEAD`、`git rev-parse origin/test`（H5用`origin/H5`）及左右差异计数。记录当前分支、远端、dirty范围和基线。存在无关WIP则隔离；禁止清空或暂存他人内容。
2. 核实际源码与 [manifest](../manifest.json) 的审查基线差异，记录是否影响会话/绑定/配置。后台有已知品牌更新，不回退它。不得用本地旧tracking ref代替刚fetch的远端。
3. 读App `AGENTS.md`、`docs/app-h5-sync.md`、两前端 `package.json`、后端`README.md`/根`pom.xml`及迁移入口。当前App/H5要求full验证后按指定分支推送；没有本机某套skill并不等于可绕过仓库门。
4. 建一份接口实施清单：共同规格§2全部登录入口、§3资格/激活/申请/结果恢复、§4E6/C5/命令查询，逐项标现有扩展或新增、所属步骤、认证/版本/幂等/错误契约。`loginAttemptId`恢复必须绑定既有认证尝试，不能只凭ID读取token。
5. 明确事务边界及同账号锁顺序：App会话接管、旧手机停止；换绑、成功事件、特批消费；配置版本及审计。01/02涉及同一任务/认证模块时由一名集成人合入，避免两套执行权。
6. 拟定存量会话归组、真实换机历史核实、未知标识、原配置保留、版本演进及回滚兼容策略。现有`changedAt`不能推导完整30天次数；新版本不得把历史未知置0。
7. 对照共同稿4态和点击矩阵补关键流可点击原型或交互状态演示：App接管/拒绝/仓库恢复/协助，后台E6/C5及超时查询。沿现有设计组件，独立人工操作检查；已确认流程不重开产品裁决，仅新增业务变化需反馈。原型不能替代真实API或原生验收。
8. 准备隔离dev测试数据与时钟控制：三个App安装A/B/C、H5两个浏览器、同安装/重装/硬件变化、已购非手机设备、不同风控暂停、不同权限运营者及历史未知账号。禁止复用生产用户/真实资金作为夹具。

## 后台跨仓验证的必填路径

PC现有`scripts/lib/nexion-workspace-paths.mjs`自动候选不包含推荐克隆名`nexion-frontend-uniapp`，在作者机器实际误选旧`Nexion-uniapp/main`；缺仓时部分门会SKIP但进程仍返回0。因此后台检查前必须显式设置并读回以下路径，不能只看退出码或`verdict=pass`：

```powershell
# 在已确认的 nexion-frontend-pc 仓根运行；目录与实际隔离checkout不同时替换为其真实路径。
$env:NEXION_APP_ROOT = (Resolve-Path '../nexion-frontend-uniapp').Path
$env:NEXION_BACKEND_ROOT = (Resolve-Path '../nexion-backend').Path
$env:NEXION_PRD_ROOT = (Resolve-Path './docs/PRD').Path
node --input-type=module -e 'import {resolveNexionAppRoot,resolveNexionBackendRoot,resolveNexionPrdRoot} from "./scripts/lib/nexion-workspace-paths.mjs"; const o={adminRoot:process.cwd()}; console.log(JSON.stringify({app:resolveNexionAppRoot(o),backend:resolveNexionBackendRoot(o),prd:resolveNexionPrdRoot(o)}));'
```

随后逐目录核对Git远端、HEAD和本任务实际checkout，保存解析结果。Bash/CI用等价环境注入，不改用户全局环境。最终full不得有SKIP、SCOPED-SKIP、innerSkipped或DEGRADED；`NEXION_VERIFY_ALLOW_SKIP=1`仅改变显示，不能补足证据，禁止用它宣称本任务验收完成。

## 入口检查

```sh
git status --short --branch
node --version
npm --version
java -version
mvn -version
```

Node/npm在前端机器验证，Java/Maven在后端机器验证；README中的另一台机器Maven路径不是依赖。先估算测试耗时、空间和独占端口/数据库；一次性准备隔离资源，后续复用证据，不重复整库导出。

## 验收

- BASE-01：四仓远端/分支/commit和未提交范围可读回；没有丢失或夹带WIP。
- BASE-02：所有接口具owner和现有/新增标记；所有41条边界在SPEC索引有责任步骤。
- BASE-03：首绑、同安装恢复、实际替换判据、两个24h及四参数源没有另起口径；迁移不静默覆盖配置。
- BASE-04：隔离验证环境、凭证供给方式、原生设备可用性和阻点如实记录；缺设备标未验证，不能伪装已完成。
- BASE-05：关键交互可按相同操作序列检查默认/空/加载/失败、返回/取消、重复提交及原结果恢复；与共同稿一致。
- BASE-06：后台解析到本任务正式App、后端和当前PC正式PRD，Git基线可核；无旧原型误命中或缺仓跳过被冒充通过。

独立检查者核对定位、scope和验收覆盖后，推进01/02。输入或接口发生实质变化先更新来源与受影响步骤，不静默改已确认业务值。

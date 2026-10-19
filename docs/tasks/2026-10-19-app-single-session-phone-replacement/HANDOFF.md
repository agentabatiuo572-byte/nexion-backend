# 交接：App 单点登录、手机换机与后台一次性特批

## 1. 接手即执行的任务

你是本任务的实施 AI。用户已确认产品方案，并要求把任务拆为分步 SPEC 交给同事实施。请依本交接逐步实现服务端、App、H5、后台，完成验收，将最终功能契约分别写入正式 PRD，再删除本临时任务目录。不要重新设计已确认的产品规则，不要只交计划或只改界面。

**确认记录（2026-10-19）**：用户先明确“H5不踢APP，但APP只允许单点登录，换机风控就按照现有方案不改……换机默认滚动30天2次、间隔24小时，后台可配置，保留一次性特批”，审阅补齐后的 PRD 后回复“方案确认”。本包包含其确认的完整稿件及执行拆解。

输入快照仍保留原来的“待确认/未实施”文字，以保存审阅时的原文；**待确认状态由上述确认覆盖，未实施状态仍然成立**。本轮只准备并上传文档，没有改业务代码、做数据库迁移或验证产品运行效果。

授权范围：本功能的实现、必要测试、PRD 回写与任务文档清理；按仓库现行门禁提交本任务改动。远端有读写权限不代表允许 force-push、覆盖他人工作或操作生产数据。生产部署与生产配置发布不是本交接自动执行项。

## 2. 阅读顺序和规则优先级

1. 本文件 → [SPEC-INDEX](SPEC-INDEX.md) → [共同规格](inputs/PRD/specs/FEAT-SESSION01-app-single-login-phone-replacement.md)。共同规格 §5 是 41 条业务边界验收源。
2. 对照 [前端随仓稿](inputs/nexion-frontend-uniapp/PRD/NexGrid_产品功能架构设计文档_v3.7.md) §4.7.7、§6.11、§12.2，以及后台 [V1](inputs/PRD/NexGrid_运营控制后台PRD_v1.md) C5b、[V2](inputs/PRD/NexGrid_运营控制后台PRD_v2.md) E6。
3. 读取实际 checkout 的 AGENTS、CLAUDE、README、package/pom 与门禁；执行 SPEC-00 固定新基线，再开始业务实现。
4. [PRD-INTEGRATION](PRD-INTEGRATION.md) 提前规定最终落点，不能最后才发现正式 PRD 仍依赖将被删除的任务文件。

后发用户确认和共同规格控制本任务的产品规则；分步 SPEC 只分配实施与验收。完整历史 PRD 用于上下文，不将其无关旧方案、过期品牌、取消的 KYC/硬件证明带回产品。若发现实施矛盾，先回源核实并记录具体阻点；仅新产品决定需反馈，不重复请求已确认事项。

本包不依赖发起人的 `D:` 盘、私有技能或工作流 CLI。接手方有这些工具可使用；没有则直接按 SPEC 的范围、验收、独立审查和证据要求执行，不能把缺少本机工具当作业务豁免。`EXECUTION-STATE.json` 是交接状态表，不是已初始化的 workflow v2 机器计划，也不伪造运行器凭证。

## 3. 实际仓库、分支与基线

| 角色 | 远端 | 活跃分支 | 本次取证/交接基线 |
|---|---|---|---|
| 服务端及任务包 | [nexion-backend](https://github.com/agentabatiuo572-byte/nexion-backend) | `test` | `fe9457efe54e112f0ca21322746befbb2e9f215b`（上传任务文档之前） |
| 原生 App、共享前端源 | [nexion-frontend-uniapp](https://github.com/agentabatiuo572-byte/nexion-frontend-uniapp) | `test` | `90d5935dc7ee17dc4cf8a460d87f7909c9d498d7` |
| 运营后台 | [nexion-frontend-pc](https://github.com/agentabatiuo572-byte/nexion-frontend-pc) | `test` | 审查 `551a37e1603bd1cccb6d6e1627aaf01e2e661673`；交接 fetch 后 `f30e08e976885b76d677e0acf69b4fba261880e1` |
| H5 共享目标 | [nexion-frontend-prototype](https://github.com/agentabatiuo572-byte/nexion-frontend-prototype) | `H5` | `c547f4a87a49860e7ecff659615ff1d3233e39b2` |

后台新增远端提交是客服系统生成标题品牌文案及回归测试，不属于本功能；保留它。以上 SHA 是阅读起点，不能用 reset 把同事后续提交退回这些版本。先 fetch、检查差异及工作树，再固定实际实施 commit。

可在空父目录克隆为下列同级目录；已有 checkout 不重复克隆、不覆盖：

```sh
git clone --branch test https://github.com/agentabatiuo572-byte/nexion-backend.git
git clone --branch test https://github.com/agentabatiuo572-byte/nexion-frontend-uniapp.git
git clone --branch test https://github.com/agentabatiuo572-byte/nexion-frontend-pc.git
git clone --branch H5 https://github.com/agentabatiuo572-byte/nexion-frontend-prototype.git Nexion-H5
```

App 当前 AGENTS 要求沿用 `test`、H5 沿用 `H5`，不自行新建 `UniApp` 或逃生分支；有无关 WIP 时按其规则使用 detached 隔离工作树并保留原工作区。每仓独立检查、提交、推送，不以父目录 Git 状态代替。不得使用已退役目录 `Nexion-prototype`、旧 `admin-ops` 或原型 `main` 作为正式实现面。

原作者机器有预先存在的 `.claude` 文件删除、原型 `src/lib/a11y-activate.ts` 未提交修改；这些不是本任务。正式 App 的未提交 PRD 补丁已作为输入快照收录，不需要复制其余工作树。旧本机 `Nexion-H5` delivery 分支和本地旧 tracking ref 不代表最新 `origin/H5`。

## 4. 不得漂移的业务合同

- H5 与 App 普通登录互不踢出，H5 保留现有多会话；所有现有原生入口共用一个 App 单点名额。显式全端退出、找回密码、封禁仍沿现有安全规则。
- 新的不同安装 App 认证完成即原子接管并停止旧手机未完成任务；不能等用户确认条款或第二次 phoneLogin 才停。拒绝换机、校准失败或受限时旧绑定保留暂停，新手机不能运行。
- 同绑定同安装主动重登不计次、不重置 changedAt、不因重登强制取消该手机在途任务；恢复不解除运营/风控等其他暂停。旧会话刷新不得自动抢回。
- 默认滚动30天最多2次、最短间隔24小时；首次绑定不计次但开始间隔。A→B成功→A成功共2次，特批成功也计次；失败、取消、重放不计次。
- E6四参数为唯一全局策略；已有显式配置不静默覆盖，按确认值发布需展示差异。改规则不追溯撤销已激活手机。计数未知不能冒充0，真实历史不清零。
- C5一次性特批固定账号、目标安装与绑定版本，签发后24小时内成功消费一次；只豁免所选次数/间隔。申请待处理24小时与特批24小时独立计算。原签发请求过缓存期也不能续期；所有绑定版本变化使未消费旧特批失效。
- 沿用现有安装与校准风控，不新增强制 OTP、设备证书、硬件真实性证明、KYC 或永久白名单。H5不获得手机校准/激活/运行能力，既有受控显式首次 DEFERRED 设置继续保留。
- 已购非手机资产、已确认收益、新人礼及试用不因换手机重置或停止。替换复用手机槽位，不能另造第二台激活手机。

## 5. 开始与持续交接

从 [SPEC-00](specs/SPEC-00-baseline-contract.md) 开始。SPEC-01/02 可分析并行，共享认证、任务、迁移文件由一个 owner 串行合入；SPEC-04/06 在 SPEC-03 验收后可并行；SPEC-05 消费 App 候选源码；最后 SPEC-07 和 SPEC-08 不能省略。

每步将 owner、当前状态、各仓 commit、实际命令/退出码、业务证据、独立审查与阻点写入执行状态或其引用记录。初始全部 `pending`，不因文档齐备标 `passed`。中断后先核对原进程、Git 差异及记录，从最早未通过步骤继续；修上游则重开受影响下游。

工作记录留在本目录，最终证据摘要按 SPEC-08 写到永久验收记录。日志、截图、数据库夹具保存在接手方专用证据目录，记录路径与摘要，不把凭证、真实用户数据、依赖或构建目录塞进任务包。

## 6. 完成的定义

四仓本次范围全部实现并满足各自门禁；41条边界有当前提交对应的测试/实景证据；原生 App 与真实服务端验证不能以 H5 fixture 冒充；独立审查无未解决阻断项；正式 PRD、接口合同和验收记录完成远端回读；最后移走临时目录并提交其删除。任何一仓未验证/未推送，都明确列出，不能宣布整项完成。

生产部署不在本次自动完成定义中。若没有真机或服务端联调环境，可推进独立可验部分，但真实联调保持未验证并保留任务目录，不能清理后交一份“全绿”。

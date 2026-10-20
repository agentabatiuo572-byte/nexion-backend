# SPEC-00 实施记录

Owner: root。状态：SPEC-00 已通过独立审查，进入01/02；尚未声明业务实施通过。

## 当前基线

证据目录：`D:/workspace/audit-artifacts/app-single-session-phone-replacement-20261019`。
四仓使用 `.worktrees/session-phone-20261019/{backend,app,pc,h5}` detached 候选。
基线、原工作区文件哈希、审阅后差异分别见 `FORMAL-BASELINES.json`、`ISOLATED-BASELINE-DIFFS.json`。后端差异只有交接文档；App/H5 的后续变更为 UVEL 历史名称展示，PC 为客服/SKU 标题及测试，不改变本任务会话、绑定或配置契约，全部保留。
PC 路径解析实测见 `PC-PATH-RESOLUTION.json`，显式注入本候选 App、后端和本候选 PC 的 `docs/PRD`；最终 full 同样注入，不依赖自动旧仓查找。

## 接口与单一事务 owner

接口清单分别为 `SPEC00-AUTH-INTERFACES.json`（28项）和 `SPEC00-PHONE-INTERFACES.json`（23项），所属步骤与现有/新增标记在条目内。业务源仍是共同规格全部 S01–S41 及 SPEC-INDEX 的责任映射。
root 负责集成与复核；SPEC-01 写窗口唯一 owner 为 session_auth_contract，串行拥有认证接线、任务执行、共享 onboarding 文件及会话迁移入口。SPEC-02 的 phone_domain_contract 仅写明确分配的新增领域文件及独立领域迁移，既有激活接线等01完成后由 root 串行整合。不能产生第二套执行权。
同账号写顺序固定为账号 → 会话 → 手机绑定 → 设备 → 任务；所有手机写必须在持有账号锁后复验当前 App 链/版本/安装。全局策略先取得一致策略锁/版本再取得账号；配置发布不逐账号取得反向锁。管理员换机处置同样使用账号序列。现有事务审计和 outbox 失败必须使业务回滚。

## 技术映射

- 入组在既有认证签发边界固定；cookie/Web 上下文为 H5，显式原生认证上下文为 APP，APP 必须有安装身份并声明执行能力上下文。executionSupported=false 的原生入口同样成功占唯一 APP 名额并停止旧手机，不因不能执行手机任务拒绝其正常登录。签发后绝不从每次 header/body 覆写组。现有安装声明不证明物理硬件来源，不引入硬件证明。
- 成功认证尝试绑定账号、原认证方法/挑战、安装、语义指纹与独立高熵恢复凭据的 hash。新增 `POST /auth/users/login/attempts/{loginAttemptId}/recover` 在 body 接收原恢复证明和固定上下文，仅恢复该成功尝试，ID 单独不授权。指纹包含组/安装/能力/认证方法及账号或原挑战引用，排除明文密码和令牌。旧 B 结果不能复活已被 C 取代的权限。refresh 继承上下文，绝不接管。
- 存量没有可靠分组/安装证据的会话为 UNKNOWN 并要求重认证，不把历史展示标签当授权。只有可证实记录才迁移归组；迁移可重放且不复活撤销会话。
- 换机历史使用真实成功事件及可靠采集起点，已有 changedAt 或版本不能推导过去次数。存量计数/不可靠 changedAt 保持未知；新首次绑定从其明确事实开始，计数为零但开始间隔。窗口扩大超出完整历史时仍未知。采集起点只能取新写入链实际启用且旧写入路径关闭后的服务器事实，不能取 DDL 创建时间；尚不能证明时继续未知。
- 配置保留已存在 allowReplacement 和间隔值；仅补缺失窗口30/上限2。4字段单一版本 CAS，已有 A2 治理不弱化。无效策略阻止普通替换，不能阻止账户与同绑定恢复。
- App 新增按原幂等键只读查询，用于未收到 operationId 的恢复；H5 仅可读本人状态，不能重放写命令。
- 激活使用显式 `replacementConfirmed=true`，缺少或 false 不能替换；`expectedBindingVersion` 必须出现，null 只表示预期仍未绑定，字段缺失是非法请求而不是首绑。原成功回执恢复优先于当前策略/授权到期重新判定。
- 客服关联沿既有会话入口显式建立；新增 `POST /api/onboarding/phone-installation/replacement-requests/{requestId}/support-link` 只把当前账号拥有的既有会话引用关联到既有申请，服务端复验双方归属和当前 App；不创建申请、不续期、不代发消息。保存成功与关联成功分别返回；失败仍可复制申请号，重试同一关联不变申请期限。页面进入会话/发送引用继续遵守实际用户明确动作。
- 已有工单内部 note 没有结构化核实标记，不能按自由文字判定已核实。C5 操作者显式确认已核实，服务端复验主管处置权限、原工单归属及当前读取范围，由 content 公共边界读取证据摘要；签发保存核实者、权限、时间和不可变工单快照。重放不改原核实记录，不新增用户身份资格或永久豁免。
- 实际必需范围含注册初始签发和 RefreshCookie 响应重建，不能因 SPEC 路径表未逐个列出而丢失会话上下文。

## 验证资源与阻点

独立 MySQL 8.0.45：127.0.0.1:33319，数据目录位于证据 `runtime/mysql-data`，launcher PID 22952、server/listener PID 31000；不使用已有3306业务实例。任务专用凭据由 `D:/workspace/test-credentials/session-phone-dev-20261019.json` 在进程内读入 NEXION_TEST_DB_*，报告不含秘密。
单测/集成测试使用场次专属 schema，A/B/C 合成安装、两个独立 H5 会话、合成账号和非手机资产账目；冻结 Clock 覆盖左开窗口及两个24小时，真实 MySQL 连接覆盖锁和回滚。失败时保留 schema 和操作快照，不用清表隐藏结果未知。后续运行证据按实际执行补充，环境准备不等于测试通过。
用户已明确只有一台三星 SM_A5760。SPEC-07 多 Android 安装真实接管当前缺设备，保持未验证，继续可独立实现/验收的部分；不得用 H5 fixture 代替真机或提前清理任务目录。
Node24.14.0、npm11.9.0、Java17.0.12、Maven3.9.9；初查 C67GB/D188GB可用。三端 full 不容许缺仓跳过；App候选及H5同步准备后执行两边full，提交前与最终干净提交门按各仓规则执行。

## 后续交付

SPEC-00 独立检查通过后进入01/02；03等01/02验收；04/06等03；05消费04候选；07通过后才执行08正式 PRD、永久契约及验收回写、远端读回和可恢复清理。当前不能声称已实施或清理。

## 实施窗口记录

- SPEC-00 最终独立审查见证据 `SPEC00-INDEPENDENT-REVIEW.md`，末两项（support-link清单和MySQL双PID）已同步读回。交接校验命令 `node .../validate-handoff.mjs` exit0。
- 后端基线 targeted Maven：AppSessionSurfaceTest、AppUserAuthServiceTest、PhoneInstallationServiceTest，91项/0失败/0跳过，exit0；日志 `BASELINE-TARGETED-TESTS.log`。
- 三前端依赖安装完成。App/H5普通npm ci因既有Vue peer锁形状失败；按现存 `docs/PORT-LEDGER.md` 的legacy-peer-deps约定重跑成功，未修改package/lock文件；PC普通npm ci成功。
- root另拥有SPEC-02的S31容量公共边界：`PhoneActivationCapacityContract`、`PhoneActivationCapacityService`、CanonicalStateMapper的旧手机当前占槽查询及两测试类。已写候选，尚待编译/真实MySQL与激活接线；不以源码存在声明通过。
- 01和02修改同一后端工作树的互斥文件；Maven由root排队，不能并行污染target；每阶段稳定后运行对应测试。

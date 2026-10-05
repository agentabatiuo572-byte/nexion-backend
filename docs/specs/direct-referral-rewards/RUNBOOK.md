# 直属分成联调与发布操作

本文是执行步骤。测试结果以交付报告和本轮机器记录为准，列出的命令不代表已经执行成功。

## 本机隔离验收

1. 分别记录后端、PC、App、H5实际工作树与HEAD；先读取各仓规则，不使用父目录Git状态代替子仓状态。
2. 使用独立MySQL实例和数据库，不连接已有业务库。本轮固定127.0.0.1:33335、direct_referral_acceptance_20261005；先导入基线schema，再补齐该基线的既有迁移：20260718_c2_account_action_closure、20260720_d2_withdrawal_closure、20260807_nexion_hard_blockers、20260810_kl_janus_applied_proof、20260722_f3_binary_settlement、20260722_f1_vrank_promotion_engine、20260801_admin_idempotency_expiry_recovery、20260801_admin_idempotency_expiry_claim_index，最后应用scripts/migrations/20261005_direct_referral_rewards.sql。测试类再次检查端口和库名，不允许指向生产；已应用的迁移按现有执行记录核验，不能随意重放。
   MySQL会话和JDBC时区与工程现有Asia/Shanghai约定一致（+08:00），接口时间转成UTC。不能让Windows宿主的本地时区替代工程时间约定。
3. 设置JAVA_HOME、DIRECT_REFERRAL_MAVEN为实际Java17和Maven可执行路径。后端运行器使用DIRECT_REFERRAL_RUNTIME=1，读取真实钱包、佣金组、释放记录与账本；第二币故障、并发和重复投递必须执行且不得跳过。
4. 后端工作流的scripts/direct-referral-workflow-runtime.mjs同时运行相关JUnit回归与DirectReferralMySqlRuntimeTest，只接受本轮生成、无skip的SUrefire报告，以及含SQL实际观察的scenario-evidence.json。单元测试与数据库测试在报告中分别列出。
5. PC设置NEXION_BACKEND_ROOT、NEXION_APP_ROOT、NEXION_PRD_ROOT为本轮三个对应实现面，并以NEXION_JANUS_ROOT定位现有Janus源码供原有质量门检查；App/H5设置NEXGRID_BACKEND_ROOT为本轮后端、NEXGRID_PC_ROOT为本轮PC。禁止缺兄弟仓时将跳过当通过。
6. PC的scripts/direct-referral-runtime.mjs起本工作树页面，以明确的HTTP fixture覆盖确认、A2审批、刷新读回、错误、权限与旧入口；报告只能证明前端行为。生产资金和真实数据库持久化由后端场景证明。
7. App内容稳定后，按受控同步工具的plan/apply把候选同步H5；设置APP_H5_SYNC_TARGET或APP_H5_SYNC_SOURCE指向本轮对应工作树，两端完整运行npm run verify。App提交后以提交SHA执行repin，再核对H5来源；不能手改来源文件冒充同步。
8. Java、TypeScript独立审查当前快照，主线回源复验P0/P1。推送前fetch并保留远端新提交；候选与提交后检查按各仓规则执行，无强推或绕门。

## 生产发布前的业务准备

- 首次无政策时两类直属分成都关闭；示例10%、5%、60/40和0.01只用于测试，不是正式配置。运营须确定两类总比例、拆分和等待天数，经现有A2审批。
- 政策在A2批准成功时立即生效，不提供未来定时发布。生效前来源不补发，延迟审批不能倒填历史；须据此安排部署与批准窗口。
- 发布前备份并核验迁移。新增政策和结算资料，并提升来源时间精度及审批摘要容量；保留原佣金、钱包与账本。迁移文件按现有执行记录管理，不能靠反复重建业务库发布。
- 检查现有NEX服务端价格可用。价格缺失时保留失败待重试，不采用客户端、示例或固定默认价发钱。
- 说明内容使用仓内新三语模板并由正常内容发布流程生效。初始化只替换精确识别的系统旧模板；已有运营自定义正文不会自动覆盖。发布前核对直属说明与总佣金说明，避免旧七层文案作为现行规则展示。

## 切换时必须核对

- 同一checkout.completed只进入新直属购买结算；device.purchase_completed不能再给相同订单第二份奖励。试用转正与各换购入口使用各自真实订单编号。
- earnings.credited必须回查真实已入钱包的设备收据。测试工作器、开发模拟、试用影子及佣金再次到账不可触发上级分成。
- 二元对碰、等级、培育奖、领导池维持原规则；历史network账单与已经计提的原奖励继续按原流程处理。
- F5只要操作新双币组中的一行，就必须整组处置；新直属来源不开放复制补发，失败恢复沿原来源重试。人工冲正或退款后的来源禁止重发。
- 冷却/冻结不进入可花余额。退款余额不足时，账单保留待追回金额；不能显示已全额追回，也不能卡住购买人的正常退款。

## 故障处置

先关闭新政策并保留已有结算、来源事件与失败回执。关闭仅约束新来源，已计提奖励仍按原政策快照和风险状态处理；需停止已有发放时使用现有风险/资金处置能力，不把修改比例当成历史撤销。

发生错误入账时按原结算快照冲正并对账，保留真实已追回和待追回。不能删除账本、改来源防重键或重放旧七层引擎。代码恢复也必须继续保持同源唯一性；有真实资金后禁止回滚数据库备份来抹除后续交易。

源码提交、源码推送、测试环境验收与生产发布分别记录。本任务不执行生产部署、生产费率启用或真实资金操作。

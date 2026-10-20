# SPEC-01/02 后端验收

2026-10-20；root 在 detached 后端候选上完成本步验收，允许进入 SPEC-03。尚未提交/推送；不代表 App/H5/PC 页面、双原生安装或最终发布通过。

证据根：`D:/workspace/audit-artifacts/app-single-session-phone-replacement-20261019`。候选基线 `4113c61e19509ec8e7e0ca182d4ac0d35f1ba445`；本步源码清单见证据 `SPEC01-02-SOURCE-MANIFEST.json`，各测试最后结果及用例见 `SPEC01-02-TEST-MANIFEST.json`。

| 验收ID | 实际证明 | 证据 |
|---|---|---|
| BE01-A1 | 密码/OTP/2FA/OAuth/注册统一固定上下文，原尝试恢复及并发顺序；旧展示标签均不授予可信分组，重复迁移保留新上下文及原撤销时间 | targeted3 的认证/注册/OAuth类；affected7 单点真实事务与auth83；final11 存量迁移1项；SPEC01-INDEPENDENT-REVIEW.md |
| BE01-A2 | 新App提交与旧链撤销/手机停止/A2/A4同事务；同安装不取消，非手机保持；并发接管/任务完成仅一方有效；5写点故障回滚 | AppSingleSessionMySqlIntegrationTest复合用例；targeted4任务63及grace31；affected10完整worker84真实事务 |
| BE01-A3 | H5多会话不踢App、伪造标签不可获执行权；旧refresh不接管，旧logout不影响新会话；安全撤销及原H5受控defer回归 | targeted3/4；affected7 auth83/reason8；affected8 Lease4；affected9 DeviceSettlement5；affected10 PasswordReset4；SPEC01-LIVE-HTTP-1.json、SPEC01-LIVE-RECOVERY-CORRECTED.json、SPEC01-LIVE-REFRESH-REASON-FINAL.json |
| BE02-A1 | 滚动窗口/间隔/多阻断/未知历史；显式策略保留；可靠coverage起点、gap及验证导入均幂等，不伪造历史 | final11 policy7、domain12；manual_migrations/README-phone-replacement-history-cutover.md；隔离canonical runner重复执行日志 |
| BE02-A2 | 真激活首绑/替换/同绑定/容量/原回执与当前绑定分离，版本和当前App校验；A2/A4失败全回滚；预检查包含当前安装校准与条款且无写入 | final11 activation5、calibration30；targeted3容量9；SPEC02-ACTIVATION-INDEPENDENT-REVIEW.md、SPEC02-CAPACITY-ROOT-REVIEW.md |
| BE02-A3 | 服务端目标申请、本人原操作、H5只读、客服关联归属、双24小时，重复申请不续期 | affected8 AppPhoneReplacement6；final11 domain12及service7；BACKEND-CONTRACT-NOTES.md |
| BE02-A4 | 一张有效授权、部分豁免、消费/撤销竞争、跨缓存原回执；到期与换绑终态顺序；冻结账号允许管理员撤销/原回执恢复，App与新签发门保留 | final11 domain12；SPEC02-DOMAIN-INDEPENDENT-REVIEW.md；HTTP管理员权限/核实证据适配仍由SPEC03负责 |

最后受影响轮次：affected10 **88/0 failure/0 error/0 skipped**；final11 **85/0/0/0**。此前失败轮次完整保留于 IMPLEMENTATION-LOG，不将其整轮写为通过，也不将不同轮次用例数量相加冒充唯一测试数。

独立复核确认并关闭：SPEC01原尝试锁后恢复与旧安装当前读；SPEC02容量RR快照、资格预检查遗漏、特批终态顺序及冻结账号撤销门。root回源核对修复与实际日志；无本步未解决P0/P1/P2。

迁移入口走 canonical installer：新增01/02迁移已在任务专属33319执行和重复读回。历史coverage手工流程仅真实夹具验证，未执行生产cutover。隔离HTTP18119通过普通认证流程验证，合成账号与数据库夹具不是原生真机证明。

继续约束：SPEC03才能接后台权限、范围、证据核验与E6原子发布；04/05/06仍待实施；41条最终业务边界保持待联合验收。用户只有一部Android，SPEC07所需多安装原生实景未验证；暂不回写最终验收结论或删除临时目录。

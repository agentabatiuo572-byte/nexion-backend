# App 单点登录与手机换机任务交接包

产品方案已于 **2026-10-19** 获用户确认。当前交付是规格和交接材料，业务实现尚未开始。

接手 AI 从 [HANDOFF.md](HANDOFF.md) 开始，再按 [SPEC-INDEX.md](SPEC-INDEX.md) 的依赖推进。所有本次稿件在 [inputs/](inputs/)；[manifest.json](manifest.json) 记录来源、基线和快照摘要。

- [已确认共同规则与 41 条边界](inputs/PRD/specs/FEAT-SESSION01-app-single-login-phone-replacement.md)
- [分步 SPEC 与验收覆盖](SPEC-INDEX.md)
- [执行状态：初始全部待实施](EXECUTION-STATE.json)
- [正式 PRD 回写和临时文件清理](PRD-INTEGRATION.md)
- [交接包检查记录及验证边界](REVIEW.md)

在任意工作目录执行 `node <本目录>/validate-handoff.mjs` 可只读检查五份快照摘要、九步依赖、41条边界映射及新写文档链接。此检查不运行产品测试，不证明业务已实现。

本目录是本任务唯一临时协调入口，集中放在 `nexion-backend/test`。其他仓库分别实现、验证、提交；不得把前端代码写进后端仓库，也不得仅因本目录存在就认为功能已上线。

实施全部验收通过、正式 PRD 和最终验收记录已提交并可从远端读取后，按 SPEC-08 移走本目录并提交删除。Git 历史保留原交接材料；禁止先删除任务稿再补正式文档。

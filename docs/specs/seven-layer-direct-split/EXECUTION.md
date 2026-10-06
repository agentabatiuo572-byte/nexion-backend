# UVEL 七层与直属拆分实施记录

## 范围和约束

主人已批准DESIGN第7节全部建议并授权实施。资金规则不新增待决项：L1基础10%不变，直属购买及设备收益拆USDT/NEX，拆分购买的NEX替代原L1额外NEX；L2–L7保留原构成，其他Team业务不删除。Janus暂停，生产切换与历史补算不在自动执行范围。

UI遵循主人后续明确要求：先调用内置image_gen生图，再转换。产品品牌只使用UVEL，NEX币种名称保留。设计图中的示例比例和金额不是后台默认配置。

## 并行所有权与依赖

| 产物 | 隔离worktree | 负责人/模型 | 依赖与验收 |
|---|---|---|---|
| 结算、政策审批、资金恢复、SQL及后端测试 | direct-referral-backend-20261005 | royalty_backend_implement，GPT-6.1-sol xhigh | 批准规格；真实MySQL验证七层、拆分、退款及历史边界 |
| App购买统计API及总集成 | 同后端worktree，文件范围单独分配 | 主线 | 来源/分组schema；完整期间统计、过滤前分页、双币去重 |
| F2/F5/A2、后台PRD及浏览器 | direct-referral-pc-20261005 | royalty_pc_implement，GPT-6.1-sol high | v2契约+设计稿；真实审批写入读回、原入口保留 |
| App/H5页面、API、三语、PRD及同步 | direct-referral-app-20261005 / direct-referral-h5-20261005 | royalty_app_h5_implement，GPT-6.1-sol high | v2契约+设计稿；三类奖励、双主题窄屏、跨端同步 |
| 独立审查 | 最终改动快照 | 未参与对应实现的审查agent | 资金/权限/交互/一致性；主线复核P0/P1 |

既有四个worktree继续使用，避免复制未知WIP。后台原4文件未提交修改属于此前本任务，保留并继续核对；App/H5远端新增修复先合入，不覆盖上游。各仓自动提交推送仅在对应检查通过后进行。

## 设计资产

使用内置image_gen生成并目视审阅后保存到各自工程。旧品牌初稿已移入各仓`.trash`，不能作为交付依据。

| 工程 | 当前稿 | 提示词要点 |
|---|---|---|
| PC | docs/design/seven-layer-direct-split/admin-royalty-v3.png | UVEL暗色运营台，七层五列与短细辅助线→直属购买/设备两块→独立权益及冷却；未知费率不画满条 |
| PC | docs/design/seven-layer-direct-split/admin-audit-v1.png | UVEL佣金审计，金额账项前展示独立只读待计算组，不显示伪金额或伪账项操作 |
| App/H5 | docs/design/seven-layer-direct-split/app-royalty-v2.png | UVEL双主题、期间、两币汇总、直属购买/网络购买/设备收益三筛选、平铺记录及分页 |

生成图的装饰性页脚、示例数据及文字误差不覆盖批准业务契约、已有品牌组件、权限或真实字段。界面验收须对照当前稿与真实实现，不能以图片代替交互。

## 验收证据入口

本轮机器计划和独立报告位于`C:/Users/jason/.codex/workflow-runs/seven-layer-direct-split-20261006/`，后端活动计划为`backend-plan-r2.json`。r2只统一实际实施者身份以承接已完成的前任务会话绑定；初始未运行检查的计划保留，不移植其通过状态。

原41项验收见IMPLEMENTATION-AND-ACCEPTANCE.md，执行结果及边界见ACCEPTANCE-RESULTS.md。构建、单元测试、隔离MySQL、fixture浏览器和真实联调分别记录，未运行项不标通过。独立MySQL端口33335，资金回归库`direct_referral_acceptance_20261005`；后台展示与真实API联调使用另外的专用库`direct_referral_admin_preview_20261006`。不把fixture或本地发布当生产生效。

真实后台联调保留原 `superadmin` 和正常 MFA/双人审批。专用非超级管理员 `seven_layer_checker_20261006` 仅获得五个精确权限，其随机口令和 MFA 密钥仅本机 DPAPI 保存。正常批准 WO-261006150406255-500 后，政策版本0→1，关闭状态下保存购买61/39，设备分成仍关闭；浏览器刷新、API和数据库读回一致。验收账号已登出，账号及角色均禁用并读回status=0。原管理员凭据、MFA 和权限未改。此库升级前业务账号/订单/奖励组均为零；仅此库设置本地切换时点，不代表生产启用。

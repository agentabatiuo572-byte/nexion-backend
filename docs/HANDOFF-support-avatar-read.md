# 客服头像只读接口补丁交接

本补丁基于 `f66b14d39d6d6b986c0068adbc8f270f8fcaf163`，补齐冻结 R05/V04/C4 的读取义务。本文是接口契约；当前运行验收与候选提交状态以末尾独立证据目录为准。

`GET /api/admin/content/support-agents/{adminId}/avatar`

- 提供 `customerId`：需 ADMIN 身份和 `service_m1_read` 或 `service_m3_read`；主体必须按现有 ownership 可读该客户。目标 admin 只能是客户当前顾问，或该客户 VERIFIED 历史消息的真实 ADMIN 作者（metadata、sender、客户及会话键一致）。转绑后旧顾问用旧客户上下文返回 404。
- 不提供 `customerId`：需 ADMIN 身份和 `service_m1_read`；沿既有 M1 名册边界，普通合格顾问只读自身，主管读取既有客服名册。目标必须是启用、未删除账号，最新有效主角色为 SUPPORT，且存在未删除坐席 profile；不以目标是否 DEDICATED/接单/忙碌过滤名册，也不允许读取其它账号。
- 成功返回真实 JPEG/PNG 字节，沿用 `no-store`、`nosniff`。无头像、未知作者或不属于可读范围返回 404，前端使用姓名占位；认证与接口权限错误沿既有 401/403。头像 metadata 存在不等于二进制已读成功。
- 两种读取都使用现有 admin 账号 `avatarAssetId/avatarVersion` 与 ATTACHED 私有资产，不新建头像源，不使用 USER/App 接口或私聊 attachmentId。

投影补齐：M1 `agents[]/records[]` 在既有 `avatarAssetId/avatarVersion` 上增加派生 `avatarRef`（无 customerId 的名册读地址）；M1/M3 共用客户投影及客户 360 的 `profile.service.data` 增加 `advisorAvatar={assetId,version}` 与 `advisorAvatarRef`（带当前客户 customerId）。无资源时二者为 null。历史 `messages[].senderAvatar={assetId,version}` 保持实际 sender 语义，调用时用该消息 `senderId` 与所属 customerId 构造上述地址，不能换成当前顾问。

原 `/api/admin/platform/accounts/{accountId}/avatar` 仍需 `platform_a1_read`；头像上传、预览、取消及账号 profile 写入仍保留原 A1/超管边界。顾问不会因此获得账号管理权限。

头像上传仅在明确 `STATUS_ROLLED_BACK` 时清理暂存对象；`STATUS_UNKNOWN` 保留对象供原 client/key 重试。真实提交后注入注册回调的反例与明确回滚反例见 `SupportAvatarCompensationRuntimeTest`，不把回调注入表述为物理 JDBC 断网。

已核准的实际调用：M1 `m1-supervisor-pool`、`m1-personal-workbench` 使用 `support-agents/page`；M3/客户资料使用共用工作台客户投影；会话消息已有作者 ID/可信度与资源版本。接口交接由主线交给 P3，本补丁不修改前端树。

独立证据目录：`C:/Users/jason/.codex/workflow-runs/customer-service-enhancements-20261001/backend-avatar-read/`。旧 P1/P2 封存与冻结 S1 保留。最终候选须实跑 service-only 顾问、主管、无关主体/任意账号/未知作者拒绝、转绑撤权、原 A1 边界、真实 PNG 与版本更换，并重跑完整 core/bulk 集成及独立 Java/权限审查。

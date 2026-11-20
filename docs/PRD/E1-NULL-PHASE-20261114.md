# E1 缺陷：空阶段说明/商品标签 null 导致 PC 整页加载失败（候选）

## 1. 范围与去重

- 来源：本轮 E1 实景：approved `WO-261114073927038-846`，phase id1 P1，`meta/skus` 为空/null；PC `validPhase` 要求 `typeof meta/skus === "string"`，整组 E1 清空。P2–P6 尚未提交，不处理。
- 去重：全 backend handoff/Bug 未发现本 null 读合同缺陷的既有实现；BUG-150 是空阶段商品编辑选择，非本项。
- 不改写库/审批/锁/幂等/阶段算法/权限/页面/App/H5；不填假说明；不处理 P2–P6 或 H1；不操作 TEST/DB/AWS。
- 基线：backend/test `e695d168ecb96eeded7e30ce77139fc239c6607f`；docs/main `d88cd0c9e9d02d45fe502f975c0ed3023c1c72d9`。

## 2. 根因（静态，小6已只读核）

- `MybatisDeviceCatalogRepository.savePhase` 对 `meta/skus` 调 `blankToNull`，DB 允许 NULL。
- `DeviceCatalogMapper.PHASE_COLUMNS` 直接 `meta, sku_label AS skus`；`DevicePhaseView.meta/skus` 可为 null。
- `OpsDeviceService.e1GenerationGates()` 原直接 `response.put("phases", phases)`，序列化 null。
- PC `lib/admin/e1-overview-contract.ts` `validPhase` 约 198–199 要求两者为 string；null 使 `fetchE1Catalog` 整组清空，隐藏新增入口。
- 其它消费者：
  - `phaseSnapshot`（E1 audit before/after）和 create audit 仍使用原始 `DevicePhaseView` 值；本次不改，保留审计/审批原始 null 事实。
  - `StorefrontProductReleasePolicy` 只用 p/sortOrder，不读 meta/skus。
  - `createE1Phase`/`patchE1Phase` 成功响应复用 `e1GenerationGates()`，因此同一投影修复也覆盖写响应。
  - `PHASE_COLUMNS` 的 list/find 供阶段算法/current/排序；不改 SQL，算法不变。

## 3. 实现（1 文件，+17/−1）

在 `OpsDeviceService.e1GenerationGates()` 现有 `response.put("phases", ...)` 处**内联**映射为现有 `DevicePhaseView`，不新增 helper/Map：

- `meta == null` → `""`；`skus == null` → `""`；p/label/sortOrder/status/createdAt/updatedAt 六字段原样；
- 原 `phases` 变量继续用于 phaseOrder/current/算法/审计，不替换；
- 保留原 record 序列化形状；
- **不修改 SQL/PHASE_COLUMNS/DevicePhaseView/DeviceCatalogRepository/savePhase**；
- **DB 仍保留 NULL**；不 update/回填/改阶段/审批/锁/幂等/审计；
- 不新增类/接口/权限/查询；不改 `phaseSnapshot` 的审计原值。

## 4. 语义矩阵

| 场景 | 结果 |
|---|---|
| DB meta/skus NULL | API `phases[].meta/skus` 为 `""`；DB 仍 NULL |
| DB 非空文本 | 原样返回 |
| E1 GET generation-gates | PC `validPhase` 通过；不再整组清空 |
| E1 create/update 成功响应 | 复用同一 generations 投影，空文本为 string |
| phase 顺序/current/算法 | 不变（仍用原 p/sortOrder/status） |
| E1 audit before/after / create audit | 保留原 null 事实，不改 |
| 其它 SKU/gate/H1/App/H5 | 不改 |

不改用户数据、阶段本身、说明内容或补假文案。

## 5. 构建与指纹

- 基线：`e695d168ecb96eeded7e30ce77139fc239c6607f`；工作树 `D:/workspace/uvel/.worktrees/nexion-backend-e1-null-phase-20261114`，detached，未 commit/push。
- 改动：1 文件 `+7/−1`。
- JAR：146,594,848 bytes，`sha256=a810cfc9cd0e1767db79f29482177680e0b7d7d7ecc11891a9dc91bf10837293`。
- Patch：1,214 bytes，`sha256=86b55a16562904fbff000bf6adbd064a6003b904bfc5ea941f730bfd5748cade`。
- Java17/Maven 3.9.9 `-Dmaven.test.skip=true -DskipTests package`：`BUILD SUCCESS`，`Tests are skipped.`。
- `OpsDeviceService.class` JAR 回读 `6a6d9bfd…`，与 target/classes 一致。

## 6. 未验证与风险

- 未启动/未 HTTP/未连 TEST DB/未浏览器；PC 实景未重放。
- P1 已存在；P2–P6 未提交，本单不造/补数据。
- 仅 e1GenerationGates 响应投影归一；`phaseSnapshot` 审计保留 null，若未来审计 UI 有严格字符串要求需另立。
- 未改权限/写/审批/锁/幂等/算法/App/H5；未提交/部署。
- 未编写/运行单元或集成测试；遵守只 E2E/整体测试。

## 7. 最少自然整体验收（小6新TEST）

1. 用已 approved P1（meta/skus null）读取 E1 generation-gates：`phases[0].meta/skus` 为字符串空值；PC 整页加载、新增入口可见；P2–P6 未提交时仍只显示已提交阶段。
2. DB `nx_admin_phase_config` 对应行 meta/sku_label 仍 NULL（不因读修复写库）。
3. E1 create/patch 成功响应同样 `meta/skus` 为字符串；审计 before/after 保留原始 null（记录事实不变）。
4. 阶段 current/order/algorithm、H1/source、SKU/gate 列表不变；无权限/App/H5 变化。
5. 不触发 P2–P6 创建、不造数据、不改任何配置。

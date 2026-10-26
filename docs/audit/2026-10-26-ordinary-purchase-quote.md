# 普通多数量购买报价资格修复

## 问题与范围

App 普通商品选择数量大于 1，会进入现有统一报价确认页。后端已经校验 canonical 购买资格、商品可售状态、金额和券，但没有命中促销时仍返回 `INELIGIBLE`、有效金额和空 `warnings`。客户端正确地拒绝创建订单，因此用户反复读取也无法继续。

本候选基于 fetch 后 `origin/test` 的 `d04ce3f48b1f74079f7b49eb157ed435f455c169`，位于独立 detached 工作树 `D:/workspace/.worktrees/backend-ordinary-quote-20261026`。正式工作区原有 `scripts/__pycache__/` 保留。实现阶段未提交或推送；独立复核通过后，主线程授权仅本候选四文件提交并推送 test。发布另由主线程执行；本子任务不操作真机、认证接口或资金。

## 变更

`PromotionQuoteService` 将报价资格定义为：命中所选活动，或本来没有显式指定活动。所有前置检查和活动评估不变：

- 普通无活动/无券、明确关闭自动选活动、有效券但无匹配活动均可形成 `ELIGIBLE` 报价。
- 无活动不生成奖励、活动 ID 或活动版本；服务端原价、券金额和组合折扣仍按原路径计算。
- 显式指定活动不存在、结束或未达条件时，原 `PROMOTION_NOT_ELIGIBLE_OR_UNAVAILABLE` 在资格赋值前抛出；不得静默变成普通购买。
- 商品不可售、canonical 资格失败/售罄/月限额/事实不可用、非法券、数量上限、预算不足等原门禁不变。
- `reserved=false`，报价不占库存、预算或月配额，不创建订单或支付。原建单/付款的锁定、容量、余额和配额检查仍决定最终执行结果。

客户端继续只接受明确 `ELIGIBLE`，没有前端绕过或默认资格。当前真机首次通用错误“已保留当前选择，请重试后再确认”另案保留：本修复只解决有价格但普通报价恒定不可确认，不宣称解决首次 catalog/voucher/quote/本地存储异常。

## 验证

- 红测：修复前运行 `OrdinaryPurchaseQuoteTest`，18 场景中 6 失败，均为普通购买预期 `ELIGIBLE`、实际 `INELIGIBLE`；Errors=0，Skipped=0。其余门禁场景通过。
- 红测日志：`D:/workspace/output/app-native-full-click-20261026/backend-ordinary-quote-red.log`。
- 针对性绿测命令：`mvn -Dtest=OrdinaryPurchaseQuoteTest,PromotionPurchaseEligibilityTest,PromotionOrderDiscountTest,PromotionOrderServiceTest,PromotionRulesTest test`。
- 绿测结果：2026-10-26 21:51:25，58 场景、Failures=0、Errors=0、Skipped=0，BUILD SUCCESS；`git diff --check` 通过。日志：`D:/workspace/output/app-native-full-click-20261026/backend-ordinary-quote-green.log`。
- 首轮绿测有 1 个新增测试断言的 Java Long/JSON Integer 类型差异；改为按数值比较后同范围重跑通过。原失败日志保留为 `backend-ordinary-quote-first-green-test-type-failure.log`，未为此改变业务实现。
- 独立复核：`ordinary-quote-independent-review.md` 核对服务 SHA256 `EAE01773814089E93E1A201D7DCE5B103087203F9D2E91E281DB27A3A6BB20B1` 后判定 PASS；另跑 `AppCanonicalBoundaryServiceTest,AppBundleOrderServiceTest,AppOrderCommandServiceTest`，149 场景、0 失败/错误/跳过，2026-10-26 21:53:50 BUILD SUCCESS。回执为同一 output 目录的 `ordinary-quote-independent-tests.log`，并非本修复者重跑。
- 新增测试使用真实 QuoteService、价格计算、schema 验证器及 prepareCreate，mock 持久层和外部依赖；覆盖同 SKU 数量 1/2/100、设备/Share、多 SKU 折扣、自动候选不匹配、显式活动不匹配、有效/无效券、canonical 拒绝、数量越界，以及原报价的所有者、数量、金额、过期和当前价格变化检查。
- 已有促销测试覆盖实际奖励折扣、活动预算不足、券叠加拒绝、资格不可用和已有订单回执。不将 mock 数据测试描述成真实 TEST 订单或库存账本验收。

## PRD 同步

已将实际条款同步本候选现有 `docs/specs/growth-promotions/operations.md` 的接口边界。其他仓库不在本子任务修改范围，以下原文应由主线程合入正式 PRD 相应段落，不能把它们标记为已同步：

- PC：`D:/workspace/nexion-ops-console/docs/PRD/growth-promotions/PRODUCT-PRD.md`，P4.2“预览与正式预留的区别”、P4.3“现有购买链路”。
- App：`PRD/NexGrid_APP端开发落地规格.md` 及 `PRD/NexGrid_产品功能架构设计文档_v3.7.md` 的商城数量/确认报价条款；本子任务不改已冻结 App 候选。

同步条款：**普通商品单 SKU 多数量及多 SKU 购买不以命中促销为前提。服务端确认购买资格、当前商品价格、数量与所选券有效后，无指定活动的普通报价可进入订单确认；没有促销时不显示或承诺奖励。显式指定但不满足的活动、无效券和购买条件失败仍阻断，不自动改为普通购买。报价不代表库存预留、订单创建或付款成功，实际提交继续核对最新价格、库存、容量、限额与余额。**

## 后续验收与回退

主线程部署批准范围后，用当前 TEST 真机对普通数量 2→读取→数量 1 验证金额及确认资格；如账户购买条件拒绝，应核对真实拒绝码而不是期待按钮必可用。仅按钮恢复不能签订单、扣款、库存和奖励终态。本阶段没有创建或支付订单。

回退仅撤回本候选资格表达式及相应测试/契约说明，不修改配置、活动、券、用户数据或数据库 schema。

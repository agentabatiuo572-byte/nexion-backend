# S1 审查返修证据

本文件仅记录独立报告 S1-01 至 S1-07 的返修与静态检查，不代表独立审查通过、运行时验收通过或允许进入 S2。

| 审查项 | 修改 | 已运行的针对检查 |
|---|---|---|
| S1-01 / P1 | shortagePolicy 使用 RULE_STOP / ACTIVITY_PAUSE；订单冲突处理不代替资源处置策略 | 两个合法值均有完整合同正例；两个旧值均拒绝；旧枚举内存突变被拦截 |
| S1-02 / P1 | 分离 Draft、DraftPatch、PromotionContract；创建仅 category/template；步骤补丁允许空规则、空预算、未决政策；三个推进动作验证完整存储合同与 ResolvedApprovedPolicy | 仅模板结构创建、基础步骤保存、空配置、DRAFT政策引用、初始版本读回为正例；缺项、空规则、空预算、无时间、错误时窗和未批准政策在 submit/approve/publish 共24次拒绝；批准版本不能带不完整草稿 |
| S1-03 / P2 | device_id 外键到 nx_user_device(id)；profile_id/version 复合外键到政策版本；显式限制删除更新 | 13个FK逐条核对拥有表、列、目标表/列；删除两个新FK或改成虚假目标都会被拦截 |
| S1-04 / P2 | 冲正资产限 DEVICE/USDT/NEX；DEVICE 的 amount/recovered/outstanding/reusable 均为整数 | 两个CHECK及原守恒表达式进入精确清单；扩大币种或允许小数的突变被拦截 |
| S1-05 / P2 | 清点13个FK、38个CHECK并固定精确关系/表达式 | 每条约束分别删除、全部FK删除均被拦截；继续明确静态门不等于DDL执行 |
| S1-06 / P2 | placement 可显式为 null，公开投影保留该值；首页须传唯一允许的placement过滤，限制ACTIVE已发布版本 | 无首页投放的正式合同与公开响应均为正例；错误投放位拒绝；强制首页或删过滤的突变被拦截 |
| S1-07 / P2 | 添加有效首购人数、直邀合格成交人数、逐项失败、恢复秒数四维；逐指标 quality metadata | 已知值与未知值均有正例，负恢复时长拒绝；逐一删除四维均被拦截；未知不得补0，无已解决样本不生成平均值 |

## 实际命令结果

`node --check scripts/check-growth-promotions-contract.mjs`：exit 0。

`node scripts/check-growth-promotions-contract.mjs`：exit 0，输出：

```text
PASS growth promotions S1: 57 operations, all refs/permissions/request-response fixtures, 5 templates, 10 reward states, 12 focused positive fixtures, 20 negative fixtures, 24 publication rejection checks, 74 adversarial gate mutations, 13 FK and 38 CHECK contracts.
STATIC CONTRACT ONLY: no API, MySQL migration, asset settlement or production approval is claimed.
```

检查为 Node 标准库实现的有界 JSON Schema/静态 DDL 契约校验，不是通用标准验证器。三个推进动作的检查对象是服务端存储合同和读取到的政策对象，不能让客户端提交“已批准”对象替代授权。真实政策来源、匹配关系、审批权限和执行器仍由后续服务实现与运行时拒绝测试证明。

## 独立 MySQL 复验要点

- 保留现有 `growth_promotions_20261007_s1_review` 及反例。该旧库的表不会因 CREATE IF NOT EXISTS 重新执行而自动增加约束，不能用重复执行当作旧表已升级。
- 在主线允许的33339实例内，使用独立空白审查schema；预备现有 `nx_admin_permission` 与 `nx_user_device` 表结构，不复制业务数据。新 FK 依赖后者，这是刻意要求真实设备来源。
- 迁移执行两次均应成功；无时窗DRAFT应能写入并读回；待审/批准/发布版本没有完整时窗应被CHECK拒绝。
- 不存在的device_id应被FK拒绝；存在设备但不存在政策版本应被复合FK拒绝；四个DEVICE数值任一小数及不支持资产应被CHECK拒绝。保留原预算、跨版本去重与父表存在检查。
- FK不能证明设备属于受益人、来源为PROMOTION_GIFT、政策已批准且kind正确、数量和权益快照吻合；这些仍需S2同事务校验及拒绝测试。退款/A2来源真实性、跨处置累计追回上限也仍属于S2。

## 完成检查边界

文件已经写入、读取并运行静态门；逐项补齐了审查确认缺失的合同；同类枚举、公开/私有placement、三个推进动作、全部FK/CHECK均进入检测；双币和设备整数保持各自语义。此阶段没有UI改动，没有运行真实退款/发奖、浏览器或Java，未接触原审查反例库，没有commit/push。最终结论仍以原独立审查复验为准。

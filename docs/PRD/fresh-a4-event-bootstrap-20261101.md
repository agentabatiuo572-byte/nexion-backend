# Fresh A4 钱包与试用事件恢复（2026-11-01）

新库按既有 schema/seed、startup 与 fresh post 首次安装后，自动拥有现行官方钱包/试用事件合同：wallet.topup_confirmed revision10 补齐 D1 的五个本金属性；trial.started100、cancelled101、extended102，以及 redeemed311 的十四个结算/订单属性完整可用。字段类型、必填与 PII 沿用原 D1/H2 及 20260829 redeemed alignment，不新增业务事件或修改 payload。

恢复只在 `scripts/fresh_db/04_event_registry_restore.sql`，保持当前 startup225 与 post 顺序。redeemed 首次就以最终311合同建立，不能依赖第二次启动才能用。本次新增恢复段仅补缺失 registry/属性；对已有较新、停用、软删或非canonical自定义合同保留原样，已有属性不改型、降版或复活。官方当前合同的完整兼容子集可以补缺，冲突仍由原 A4 门明确拒绝。该保护不扩展为整份04既有历史upsert对所有运营数据无副作用的承诺。

原 immutable migrations、EventOutbox、资金 reader、部署 pins 与运营费用均不改变，不回填历史 outbox、资金账或设备。此 fresh 恢复不进入正式迁移 catalog，不授权向现有生产运营 schema 写入。

本轮独占 MySQL8.0 首次 fresh 安装、恢复段重放/部分恢复、七类历史合同保护，以及真实 A4 服务和 mapper 的五个原 payload/十五个拒绝分支均已留证通过。此结果仅证明 fresh 元数据与 A4 消费合同；真实金融腿复用主任务已有 E01 证据，不能代替商户、完整购买或尚未签收的 E01 场景。

恢复验证限于bootstrap逐文件使用新连接的重试入口及持久属性缺口恢复；同连接中途异常后原地重跑TEMP未测，不在本轮恢复承诺内。

## 课程、旧活动与设备激活事件（2026-11-06）

Bash 整库安装和 PowerShell 启动迁移共用 `20261106_a4_app_event_registry_restore.sql`。空库与缺登记的旧库安装后使用同一正式合同。

| 业务入口 | 权威登记 | 版本 | 必填属性数 |
| --- | --- | --- | --- |
| I6 通过课程测验 | `learn.course_completed` | 116 | 3 |
| H4 旧活动报名 | `event.joined` | 93 | 1 |
| H4 旧活动领取 | `event.claimed` | 94 | 4 |
| 商城支付激活设备 | `admin.device_activated` | 51 | 9 |

迁移插入缺失的登记，仅为匹配原官方合同的活动版本补缺失属性和域扩展。已有登记、属性与扩展行保留原值。课程完成继续以 `LEARNING_COURSE_COMPLETED` 投递给 H3。商城激活继续与支付、扣款、设备在同一事务落库。`device.activated` 使用独立合同。迁移仅写 A4 元数据。

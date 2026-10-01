# 客服增强后端 core 交接

权威规格：S1 `fceac7fc4c8451772f63a976c0b2865734248e17`，`docs/specs/support-enhancements-20261001/` 六份冻结契约。实现基线 `968fbdcfbbbd2a583d4015661b613a23cfd6e954`，交付分支 `codex/cs-enhance-core-20261001`。

本阶段只交付后端 core。群发 bulk、三仓集成、后台/APP 页面操作、生产迁移与部署由后续阶段验收；不能据本仓通过宣布整体上线。

## 接口和共享契约

- `/api/admin/content/support-agents/rules`：唯一 `unboundAssignmentMode=AUTO_RANDOM|SUPERVISOR`，保留原继承配置、版本、理由、角色和审计。
- support-agents 下 `/assignments/random-preview`、`/assignments/random`：冻结客户、规则版本和客户池版本；逐客事务、原 key 恢复、部分结果持久化。预览五分钟过期。60 秒重试只扫描新自动记录，单轮最多 100 客；历史主管池和待核对记录不暗分。
- `/api/admin/content/support-workbench/customers/{customerId}`、`/{customerId}/360`、`/{customerId}/devices`、`/{customerId}/flows`：当前归属读权限；各组独立 `READY/UNKNOWN/FORBIDDEN/ERROR`，失败不会把其它组回滚成错误。未知值为 null，真实空集合/零值明确区分。
- 身份组分别保留内部数字 ID、`userNo`、实际等级、脱敏手机、地区、注册和登录时间；来源业务时间转 UTC，不能借注册时间填活动或登录时间。
- 财务按实际入账币种分组，所有金额为 decimal 字符串；充值读全历史成功入账，不把支付 currency 配入账金额。成功提现本金、费用、净到账和处理中分别累计。退款无完整权威源时为 UNKNOWN。全部流水可分页、按币种/状态/UTC 时间过滤；总数和行使用同一条件。
- 设备全量分页稳定排序；在网复用 E5 最近心跳与实际持有/激活规则。旧 `hashrate` 包含不同单位，不能累加成 TH/s；配置收益不能充当真实收入。缺失、失败、无设备分别表达。
- 系统标签只读；自定义标签和备注复用原写权限并要求当前归属。备注保留真实作者、时间；旧无作者 ID 保持未知。
- `/api/admin/content/support-workbench/skus`：可搜索分页的当前上架商品；`SKU` 消息携带 `skuId` 与提交时名称快照，可带原文字说明。提交事务对当前 SKU 行加锁重验上架/展示/发布门/价格，旧成功消息重放保持原结果。
- `LINK` 使用结构目标 `HOME/WALLET/SUPPORT` 和空参数白名单；外链、管理路径、退役目标及任意参数不能发送。消息读取含 `targetAvailability`，失效商品标记 UNAVAILABLE；旧文字保留。
- 归档为独立 `archived` 状态，不改业务 `status`。归档/撤销和显式批量选择保持 version CAS 与未回复保护；CLOSED 撤销归档后仍不能在旧段发信。列表、计数、游标、App 和工单源投影保留实际归档值和当前 scope。
- `OpsConversationAfterCommitPublisher.publish` 供后续 bulk 复用：真实事务提交之后才发布消息；回滚不推送。每客仍走原 `SupportHumanMessageService`、附件与维护链，不能用群发绕过它们。

## 头像与账号安全

超管并持原账号写权限才可上传和绑定管理账号头像。头像资源、账号引用分离；上传复用私有存储和图片策略，实际解码重编码，拒绝 SVG、假 MIME、超策略、外链和他人暂存资源。READY 资产有有效期，可取消；绑定要求上传主体、状态、对象实际存在。

原账号 CAS 与头像 attach 同事务。仅换头像也递增账号版本；省略头像保持原值，失败不改原图或姓名/账号/角色/状态/邮箱。账号、坐席、当前顾问和可信历史消息都投影相同 asset/version；原作者不会替换成新接待顾问。客户头像是受控只读资源，不暴露原对象 key。

A1 幂等结果原 scope 不区分操作者，可能回放他人敏感响应；现在新命令按 actor 隔离，并在读取结果前重验实际账号、角色和原治理约束。历史收据不能证明主体时返回 `409 ACCOUNT_LEGACY_COMMAND_REVIEW_REQUIRED`，不会自动重执行或把旧收据归给当前操作者。此为明确兼容边界，不能删除旧审计，也不能自动将旧 key 重放为新写入。

客服资料读权限不会授予重置密码、冻结或资金调整权；各 action 仍按原模块权限、确认、理由、审计和当前状态执行。主管只读不获得冒名发信或批注写权。

## 数据迁移与回退

`scripts/migrations/20261001_support_enhancements_core.sql` 为可重复迁移：新增模式、随机操作/逐客结果、头像资产与账号引用、独立归档、备注作者等存储；不将旧 CLOSED 推断成归档，不重排已有归属或后代，不把旧池加入自动重试。

实施时先保留数据库备份并运行原环境既有前置迁移。本阶段迁移仅在独立验收库运行。代码回退前关闭 AUTO_RANDOM 并停止重试任务；保留新增表列与操作/审计记录，不能直接删除已绑定头像、已提交随机结果或历史事实。旧代码不认识的新增消息类型须按升级兼容约定处理，不能假定旧客户端已支持。

## 可重复验收与证据边界

`pwsh -NoProfile -File scripts/support-enhancements-check.ps1` 在独立 MySQL `cs_enhance_20261001`、Redis 16341、私有 S3 19041、HTTP 18141 执行明确套件。每个 suite 必须本轮实际执行、非零 tests、零 skipped/failures/errors；保留每个 Surefire 文件哈希与 case 名称。私有凭据在仓外，脚本不会输出。

workflow core 先运行上述检查，再运行 `scripts/support-enhancements-runtime.mjs --phase core --plan <r6计划> --report <仓外报告>`。生产器要求本轮同 task/step/run/snapshot 的检查摘要、鲜活且未改写的报告和场景证据，逐项绑定 55 个后端 AC；缺一项直接失败。未实现的 bulk/integration phase 直接拒绝，不包装成成功。

场景包括真实 DB 并发/故障回滚/同 key 恢复、HTTP 分页刷新、真实 WS/SSE 撤权、私有 S3 上传失败与读权限、原模块命令和精确已读回执。M2/M4/AI 原契约同时运行原有回归，AI 使用既有测试边界，不声称访问外部模型。C1AuditEvidenceMySqlTest 使用同一本地 MySQL 的专属临时库 `cs_enhance_20261001_c1_audit`，runner 仅额外授该库权限，结束删除其自身 fixture；不访问原项目审计库。

成功回归摘要同时封存全部 24 个场景文件的路径、产生时间与 SHA256；新 core 场景带本轮 workflow run/snapshot。消费时逐个匹配封存哈希，任何后续单测替换场景都会拒绝。存储准备对照克隆快照中固定的 90 个原附件，不拿后来故障测试生成的对象冒充原存储基线。

完成前以当前稳定快照接受独立审查与 done-review 六维矩阵；仓外 workflow 状态、结构报告和审查结论为证据入口。接口验收不会替代后台或 APP browser 实景，更不会替代生产部署证明。

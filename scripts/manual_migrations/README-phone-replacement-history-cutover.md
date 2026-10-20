# 手机换机历史可靠覆盖切换

此流程由部署负责人显式执行，不由 DDL、服务启动或首次查询触发。本任务未对生产执行。`20261019_phone_history_coverage.sql` 只安装手工过程；安装本身不会把任何账号标记为历史完整。

## 确定真实起点

1. 暂停换绑写入口，排空进行中的写事务；停止所有旧版本实例及可能改写手机绑定的后台任务。记录部署批次、实例版本、关闭入口和排空结果。
2. 核验所有恢复后的换绑路径均使用新原子事务：绑定、成功事件、授权消费、回执、A2、A4 同成同败；旧客户端旁路被拒绝。使用隔离测试账号完成成功和故障回滚探针，保留探针操作号、SQL读回和构建版本。
3. 在这一屏障已经满足之后记录 UTC 毫秒时刻 T0，随后才开放新写入口。证据清单须包含 T0 来源、所有写入方、实例清单、账号作用域及上述探针。**DDL执行时间、表创建时间、当前 changed_at、部署开始时间都不能替代 T0。**
4. 清单冻结后，针对已有绑定的明确账号名单执行下列过程。`evidence_ref` 指向持久保留的清单，长度不超过200字符。逐账号使用已读回的 `binding.version`；冲突后重新核验，不自动覆盖。无绑定账号不跑此流程，新首绑由业务事务记录完整起点。

## 执行与读回

使用已批准的数据库连接方式进入目标 schema 的独立会话（无其他未提交事务）；凭据由安全配置或 MySQL login-path 提供，不放命令参数、SQL或证据文件。过程使用调用者自身权限，不借创建者权限执行。以下是 MySQL 客户端可执行语法，变量值来自已批准清单，不使用 NOW 自动生成覆盖起点。

```sql
SOURCE scripts/manual_migrations/20261019_phone_history_coverage.sql;
-- 先读取目标；将输出纳入证据。
SELECT user_id,source_environment,run_id,version,changed_at
FROM nx_phone_binding WHERE user_id=@account AND source_environment=@environment AND run_id=@run;
-- 显式赋值：@revision、@account、@environment、@run、@t0_ms、@binding_version、@evidence。
CALL apply_phone_history_coverage(@revision,@account,@environment,@run,
  'ESTABLISH',@t0_ms,@binding_version,0,@evidence);
```

过程锁顺序是策略、账号、绑定、历史状态；同操作键同输入只返回原记录，异输入拒绝。结果含原/新投影和证据引用；保存输出，并查询 `nx_phone_history_coverage_revision` 与 `nx_phone_replacement_history_state` 核对。迁移重跑不能覆盖此数据。

`ESTABLISH` 保留 `all_history_known=0`，T0之前历史仍未知。只有整个当前滚动窗口进入 `[T0, now]` 才能按已记录事件计算次数。它也不会自动证明旧绑定时间：若 `changed_at_verified=0`，间隔资格继续未知。只有清单中另有能证明**当前锁定版本**准确绑定时间的原始审计证据，才可把 CALL 的倒数第二个数字改为1；仅查看当前行不构成证明。

独立核验的旧成功换机事件由既有 `PhoneReplacementService.importVerifiedEvent` 在策略/账号锁事务中按原来源种类+来源ID导入；它不设置完整覆盖。未完成实际导入与证据核验，不得把 T0 向前延伸。来源去重不可更换ID规避；重复导入和迁移保留由 `PhoneReplacementAtomicityMySqlTest` 验证。

## 回退或出现采集空档

只要允许旧 writer 重新写绑定、发现漏记成功事件，或无法证明连续采集，就先暂停写入。对受影响账号使用**新操作键**，把空档起止、旧版本/漏记事实及此前revision写入新证据清单，然后执行：

```sql
CALL apply_phone_history_coverage(@gap_revision,@account,@environment,@run,
  'INVALIDATE',NULL,@current_binding_version,0,@gap_evidence);
```

此操作将覆盖与绑定时间验证置未知，原投影、证据、成功事件全部保留。重新完成屏障与探针后记录真实 T1，再用新 revision 调用 `ESTABLISH`。不得直接用T1覆盖仍有效的更早T0；过程会返回 `PHONE_HISTORY_INVALIDATE_GAP_FIRST`，要求先明确记录空档。若新 writer 再次停用，必须再次作废，而不是等待30天后误认为完整。

回滚应用时保留领域表与 revision 表，不删除事实、不重置次数、不恢复旧会话。数据不完整时继续显示未知；需要换机只能走既有核实特批规则。

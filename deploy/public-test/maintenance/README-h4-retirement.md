# TEST H4 持仓下线

2026-11-04 产品决定删除整个持仓类活动。此工具只渲染 SQL，不连接数据库、不读取凭据、不启动服务。生产目标固定为 TEST 的 `nexion` 和 server UUID `123d8594-ab52-11f1-b635-f661104b171d`；不用于 PROD，不进入自动 startup。

执行前必须先读迁移登记，再按下文区分旧 H4 文件已执行或 pending。维护时停止 TEST 后端并核验既有发布流程的全库备份。DDL 隐式提交；任何失败保持后端停止，不能使用 `--force`、跳过检查或修改未知函数正文来放行。

## 识别与操作

从本目录执行：

```sh
python3 -B render_h4_retirement.py --stage diagnose --output /REVIEWED/diagnose.sql
mysql --defaults-extra-file=/PRIVATE/test.cnf --batch --binary-mode nexion < /REVIEWED/diagnose.sql
```

| reader 输出 | H4 数量 | 识别结果及目标 |
| --- | --- | --- |
| `original` | 0 | 状态 (i)，原版函数、四条钱包触发器；重建同一正文并恢复授权 |
| `original` | 1 | 状态 (iii)，原版函数被第五条 H4 阻断；删除 H4，正文不变 |
| `bug001` | 1 | 状态 (ii)，候选版精确排除 H4；仅撤销该排除条款，恢复四条 |
| `fund-update` | 1 | 状态 (ii)，7c5a5a46 维护版：五条钱包清单及 H4 正文检查；只撤销这些条款，三条资金 BU 继续必须缺失 |
| `fund-update` | 0 | H4 已不存在；正文可能仍要求五条，也可能已完成下线，使用 `reader` 步骤收紧或幂等核验 |
| `UNKNOWN_REFUSED` | 任意 | 未知正文，停止；不生成猜测性的修复 |

`diagnose` 只报告正文版本及数量；正式 `retire` / `reader` 才执行完整结构、函数属性与权限前置检查。用读回的 reader 生成第二步，例如：

```sh
python3 -B render_h4_retirement.py --stage retire --reader fund-update --output /REVIEWED/retire.sql
mysql --defaults-extra-file=/PRIVATE/test.cnf --batch --binary-mode nexion < /REVIEWED/retire.sql
```

成功输出：

```text
result      schema_ready  wallet_triggers
H4_RETIRED  1             4
```

已完成后同一 reader 参数可重跑。原版及 BUG-001 都落到同一原版正文；BU 维护版落到四条钱包、BU 缺失正文。工具不会重建 BU，不改资产 CHECK。之后的资金 BU 维护继续使用 `render_test_fund_update.py`，其钱包条款现为四条。

前置要求：

- 函数正文必须逐字节匹配已知原版、候选版、旧 BU 维护版或相应完成态；类型、参数、SQL mode、charset、collation、definer 也必须匹配。
- 其余原有 schema 条件全部成立。H4 只能不存在或与历史快照的名称、钱包表、AFTER UPDATE ROW、顺序、definer、正文完全一致。任何未知额外钱包触发器均在 DDL 前拒绝。
- 函数级授权必须正好是 `nexion_support_schema@localhost` 和 `nexion_app@%` 的 EXECUTE。函数仍使用原 definer `nexion_support_schema@localhost`；DROP/CREATE 后逐条恢复并核对两项授权。该账号原表级元数据权限保持原样。

`--stage retire` 同时执行 `20261104_h4_holding_retire.sql`：删除 H4 触发器和持仓表；停用所有 holding 规则，墓碑化相关 quest，不受原发布状态限制。非钱包函数条款按所识别版本保留，末尾核对正文并调用真实函数，必须为 1。`--stage reader` 只重建函数及恢复授权，要求钱包已经是原四条；不删除触发器、表或更新活动行，不接受 H4 仍存在的状态。

## 本地副本排练

```sh
python3 -B render_h4_retirement.py --stage retire --reader original \
  --rehearsal --clone-server-uuid CLONE_UUID \
  --clone-database nexion_h4_retire_verify --output /TASK/retire.sql
```

`--rehearsal` 和 UUID 必须一起提供，UUID 不得为真实 TEST。可选副本库名仅接受 `nexion_h4` 或 `nexion_h4_*`；不传时沿用隔离服务器同名 `nexion` 库的既有维护惯例。实际 SQL 第一条检查同时核对库名与 UUID。副本参数不会放宽函数、trigger、definer 或授权检查。

本轮本机没有 TEST 两个专用账号。演练只在生成的临时 SQL 中将 definer 映射到已有 `root@127.0.0.1`，两项函数级授权映射到已有 `root@127.0.0.1`、`root@localhost`；候选正文中 H4 definer 的精确比较同步映射。未创建用户、未改全局权限。已验证同一 definer 保留与两项副本 EXECUTE 恢复；真实 TEST 两账号及其表级权限必须由协调者现场核验。

## 发布门与历史

H4 安装生成器和 compute-v2 候选中的 H4 安装分支已移除。固定 updater 同步候选 SHA，删除退役安装审批键，其他审批与固定程序通道保留。updater 仍只接受文档中原有的精确主机基线；若现场已变更，停止并由协调者重新审阅基线，不改 pin 绕过。

`20261025_h4_event_rules.sql`、`20261011_*` 及 SUPPORT_PINS 均不改。先读取主机登记，不能用 BUG 记录或 reader=1 推断旧文件已经执行：

```sh
sudo /usr/bin/python3 -I -c 'import json; print(json.load(open("/srv/nexgrid/cd/migrations/state.json"))["scripts"].get("20261025_h4_event_rules.sql", "PENDING"))'
```

- **已有 APPLIED 登记**：执行 `diagnose` → 对应的 `--stage retire --reader ...`，确认 reader=1、钱包=4，再部署新正向迁移；已登记旧文件不会重放。
- **没有登记（pending）**：安装本轮已审 release gate 后，部署按文件名先执行旧文件的固定 table/seed 前缀，再执行 `20261104_h4_holding_retire.sql`。不安装 H4，不需要 root 安装通道或 H4 审批键。若现场 H4 仍存在（例如曾手工安装），先按上一条执行完整下线，再走前缀部署。
- **pending 且 H4 不存在，但 reader 仍为五条维护正文**：使用下面的 `reader` 步骤，只收紧函数钱包条件，保留 BU 缺失条款和授权。应在部署前完成，避免已有资金/Compute V2 preflight 因 reader=0 阻止整个批次；部署仍依次执行前缀和正向下线。部署后再次核对 reader=1、钱包=4、持仓表不存在。BUG-001 候选正文同样可用 `--reader bug001` 收紧。
- `BASELINE_NOT_REPLAYED` 不是 pending，也不是 SQL 已执行的证明：保留原登记，协调者结合实际结构确认；不得改历史状态或 hash 强迫重放。

```sh
python3 -B render_h4_retirement.py --stage reader --reader fund-update --output /REVIEWED/reader.sql
mysql --defaults-extra-file=/PRIVATE/test.cnf --batch --binary-mode nexion < /REVIEWED/reader.sql
```

两个 runner 都仅接受旧文件 SHA256 `921d9f1e3567dfbd049da78ba878ab2187f9c6b15ed449a748be3e2118688d5d`，提取第一个 `DELIMITER $$` 前的精确 5004 字节；该内容与旧安装通道的 `H4_PREFIX` 相同，SHA256 为 `b575d55f888751cd27c6e2a6efb137797b7bb51e4d38400ad0f8dd4d4ec03796`。前缀仍经过通用 SQL preflight，并通过正常 app-account `execute_sql` 执行。成功后旧文件按完整原 SHA 登记 APPLIED，登记及 journal 写入 `RETIRED_H4_TRIGGER_SKIPPED`，journal 另记录前缀 SHA。其他字节一律 `MIGRATION_H4_FIXED_SOURCE_CHANGED`。原始文件作为回执保留，但从不执行其中 trigger 部分。

`20261104` 的 DROP TRIGGER / DROP TABLE / prepared UPDATE 在同批真实 generic preflight 已通过，没有放宽 stored-program gate。部署后必须读取旧文件 APPLIED + skip note 和新文件 APPLIED 回执；原 backup、hold、失败恢复流程不变。

真实副本输出与限制见 [PRD 验收记录](../../../docs/PRD/H4-持仓类活动下线-20261104.md)。本轮未执行公共 TEST、不运行测试、不推送。

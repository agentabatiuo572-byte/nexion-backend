# B38-BE-26 历史充值累计异常处理

本命令由有权维护目标 MySQL 的执行人手动运行。财务负责人须在现有审批渠道确认逐笔依据、权威累计起点、完整历史边界及目标金额，并在审批中登记清单文件的 SHA-256。执行人从该审批取得摘要并核对签认者身份。命令核对文件与摘要的对应关系，审批权限和外部凭据真实性由现有财务流程核实。

适用对象为现有 `OPEN` 的 `WALLET / UNBACKED_CUMULATIVE_BALANCE`，每次一条，必须能确认同一只现存非沙箱钱包。异常金额是旧迁移重算前记录的观察值。涉及费率、储备、充值账本绑定、旧拒付或缺失钱包的记录，依据相应来源另行核清，继续保留原状态。当前历史盘点无已签认目标或可执行清单。

## 生成并签认清单

使用 Python 3.11+、MySQL 8 客户端和获准使用的 MySQL 客户端选项文件。预览和回执查询可使用只读账号。应用需要读取钱包、异常、审计及归档和 A2 审计策略，更新钱包累计／版本／时间、指定异常状态／时间，并写入审计。应用须用可归责到执行人的具名数据库账号，并在运维记录中关联审批编号。选项文件和凭据在受控目录保管。

```sh
python scripts/ops/resolve_historical_topup.py --client-config "$DB_CLIENT_CONFIG" --database "$DB_NAME" inspect --anomaly-id "$ANOMALY_ID" > snapshot.json
```

将 `snapshot.json` 的 JSON 对象完整放入清单的 `snapshot` 字段。表中列出清单的其余字段。

| 字段 | 填写内容 |
| --- | --- |
| `targetUsdt` | 财务签认的最终累计，使用六位小数的字符串 |
| `approvalRef` / `financeOwner` | 可查的审批编号与指定财务负责人 |
| `reviewer` | 核清证据的人员 |
| `openingBasisRef` | 证明该钱包身份连续性和权威累计起点的凭据编号 |
| `historyEvidenceRef` | 串联来源订单、资金与账本并复算到目标值的证据包编号 |
| `historyCutoff` | 证据覆盖的明确截止点，包含时区和区分同一时刻事件的依据 |
| `reason` | 该异常的结案或累计修改理由 |
| `evidenceSha256` | 财务核认的非空证据包文件 SHA-256 |

证据包应覆盖正确的充值用途和去重关系、各次退款及冲正和当次钳零，并核实旧凭据与人工入账的实收来源。`OPEN=0`、旧回填无标记或从假定零重演一致，均不足以确认累计起点或目标。累计纠正可能改变后续 K1 风险判断，签认时一并核对。审批、清单、证据通过既有渠道保存，钱包快照或审批清单变化后重新核对签认。

## 应用和查回

首次应用前核实审批仍有效、原始账证未被更正。审批和证据包只填写可追溯的引用，历史凭据正文保存在财务渠道。

```sh
python scripts/ops/resolve_historical_topup.py --client-config "$DB_CLIENT_CONFIG" --database "$DB_NAME" apply \
  --manifest "$APPROVED_MANIFEST" --approved-sha256 "$FINANCE_APPROVED_SHA" --evidence "$APPROVED_EVIDENCE"
```

命令在事务内锁定并复核异常与钱包。目标等于现值时只结案。否则更新累计和钱包版本。提交同时保存结案和 A2 审计，记录原/目标累计、钱包版本、财务签认、证据摘要、数据库连接账户及执行时间。现有钱包观察触发器会登记版本变更。资金余额、钱包账本和旧回填标记保持原值。

`APPLIED` 是提交后的回执。中断或提交结果未知时，用 `receipt --anomaly-id "$ANOMALY_ID"` 查询热表或归档，并核对 `detail.manifestSha256` 与原审批摘要。查不到回执时以同一清单、原审批摘要和证据重试。`REPLAYED` 返回原审计编号、执行时间与依据，即使后来有正常充值或退款。首次应用时发现钱包已变化，或发现换目标、异常已另行关闭或重新打开，按拒绝结果重新核对。核对成功回执、该钱包累计、D1 `OPEN` 数及证据包目标。缺原重建标记时 `historicalBackfillComplete` 仍为 `false`。

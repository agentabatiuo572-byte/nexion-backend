# TEST资金证据永久允许更新

本维护仅锁定 TEST MySQL `123d8594-ab52-11f1-b635-f661104b171d` 的 `nexion` 库，不由启动迁移自动执行，不增加产品公共开关，不改变 PROD。

永久移除 operation/leg/party 三条 BEFORE UPDATE 禁止触发器，空替换及实际 UPDATE 均允许。三条 BEFORE DELETE 禁止触发器原样保留。钱包 journal、debit counter、H4、所有表结构/FK/金额规则及应用中的摘要、真实批准、来源校验保留；修改历史正文不会自动获得有效摘要或批准。

现场旧 `nx_support_fund_schema_ready_v1` 已返回0：它要求钱包触发器总数/白名单为4，但合法部署的 `nx_wallet_h4_holding_observation` 是第5条。维护定义将三条资金BU改为必须缺失，保留三条BD全文校验；钱包总数/白名单改为5，并按现场原文精确校验H4的正文、definer、ROW、AFTER UPDATE、ACTION_ORDER=2。其他原始校验块保持字节一致。`--stage diagnose` 输出旧合同及维护前置合同各子条件，可确认是否还有其他漂移。

## 生成与执行

`test_fund_update_source.json` 是本轮现场SHOW CREATE、routine definition、11条相关触发器、两条EXECUTE授权及4条资产CHECK的无凭据快照。脚本只渲染SQL，不连接数据库、不取凭据、不启动服务。

```bash
# 真实TEST：固定UUID，先生成、审核，再由主流程按本批授权执行。
python3 render_test_fund_update.py --stage diagnose --output diagnose.sql
python3 render_test_fund_update.py --stage fund-update --output fund-update.sql

# 只能在主流程已创建且网络隔离的真实备份副本排练。
python3 render_test_fund_update.py --stage fund-update --rehearsal \
  --clone-server-uuid <clone-uuid> --output clone-fund-update.sql
mysql --defaults-extra-file=/private/clone-mysql.cnf --batch --binary-mode nexion < clone-fund-update.sql
```

两项副本参数必须同时提供，副本UUID不得等于真实TEST。SQL第一条可执行检查验证实际 `@@server_uuid` 和 `DATABASE()`；不使用 `--force`，错误目标必须在第一处PREPARE失败并停止，后续DDL不得执行。执行器应使用有权读取routine body和重建固定definer函数的维护账号；保留锁定的schema definer及原表权限，重新赋予原有两条EXECUTE授权，不修改全局权限。

维护前检查原/已维护函数正文及完整schema形状，允许仅本批三条BU已部分删除的精确恢复状态，不接受其他trigger漂移。重建函数后调用真实schema_ready，必须为1。DDL会隐式提交，执行前停止TEST后端并保留已验证全库备份。该脚本不自动回滚已提交DDL；异常时保持服务停止，读回具体失败点后从同一脚本恢复或按已审备份恢复。成功后不重建三条BU，更新权限永久保持。

## 四条资产CHECK前向协调

```bash
python3 render_test_fund_update.py --stage asset-expand --output asset-expand.sql
# 主流程随后完成已审核的NEX→UVT全库数据替换，并读回。
python3 render_test_fund_update.py --stage asset-finalize --output asset-finalize.sql
```

副本同样必须加两项rehearsal参数。expand仅在4个旧CHECK的资产集合加入UVT，保留NEX供迁移；finalize仅在对应表NEX行数为0时收紧为UVT。两阶段都精确核对原CHECK形状且保留ENFORCED，单表DROP/ADD在同一ALTER中完成。范围仅：

- `nx_earnings_source_recovery.chk_earnings_recovery_asset`：USDT/NEX→USDT/UVT。
- `nx_promotion_reversal.chk_promotion_reversal_asset`：DEVICE/USDT/NEX→DEVICE/USDT/UVT。
- `nx_promotion_budget.chk_promotion_budget_kind`：资产列表迁移，DEVICE与product_no空/非空规则保持。
- `nx_promotion_reservation.chk_promotion_reservation_asset`：资产列表迁移，DEVICE、product_no及设备数量整数规则保持。

这是手工维护来源，不能放进通用startup迁移目录。SQL不更新数据、不改变其他CHECK，不生成或重算资金摘要；数据替换和摘要/批准事实的验证由本批主流程单独执行。

## 验证

```bash
python3 -m unittest -v test_render_test_fund_update
```

离线合同测试覆盖固定目标/错误参数、只删除三BU、三BD及其他约束保留、H4精确校验、部分DDL恢复合同、definer/授权保留及4CHECK两阶段。真实副本必须另验证：

1. 旧schema_ready=0及诊断失败子条件；维护后=1，原应用账号也可调用。
2. 对三表现存行分别在事务中执行 `UPDATE ... SET <原列>=<原列> LIMIT 1` 后ROLLBACK，证明空替换不再被阻止；再读回行数与原值。
3. 三表DELETE各自仍报 `SUPPORT_FUND_RECORD_IMMUTABLE`，事务ROLLBACK；不要在一个普通batch中混合三个预期错误。
4. 实际trigger清单只少三BU，所有剩余trigger全文/顺序不变；临时扰动BD或H4时schema_ready必须为0，恢复后为1。
5. 错误UUID/数据库SQL在首个目标检查失败，schema及数据不变；资产expand→迁移→finalize全链在副本验证。

离线测试不等于真实TEST已执行；实际变更与验收证据由主流程单独记录。

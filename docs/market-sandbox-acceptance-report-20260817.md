# Exchange / Genesis App Sandbox 闭环验收报告

日期：2026-08-17

## 决策与边界

- 仅在恰好一个 `test`、`acceptance` 或 `local-sandbox` profile 且存在合法 `NEXION_ACCEPTANCE_RUN_ID` 时启用。
- App Sandbox 用户必须由 `nx_user.sandbox=1` 证明；生产用户、生产 profile 和未知/混合 profile 均失败关闭。
- Exchange 事实只写 `nx_exchange_sandbox_*`；Genesis 事实只写 `nx_genesis_sandbox_*`。没有调用生产 wallet、order、holding、ledger、outbox 或共享幂等表。
- 所有查询和唯一键均绑定 `run_id + user_id`。服务重启后通过 MySQL 查询恢复余额、订单、持仓、挂牌和隔离账本。

## 最小闭环

- Exchange：余额/隔离账本、swap、queued cancel、状态查询、按账号和 RunID 幂等。
- Genesis：购买、持仓、挂牌、取消挂牌、二级购买、买卖双方隔离账本、按账号和 RunID 幂等。
- 数据库钱包使用版本 CAS；Genesis 二级买卖按用户 ID 顺序锁定双方钱包，降低并发死锁风险；唯一键冲突路径重新读回赢家或返回 409。

## 测试证据

- Red gate：后端完整 Maven 编译当前仍被工作树中既有的 `AppStakingService.java:338` 语法错误阻断；该错误不在本变更范围。
- Green gate：使用 Maven 依赖 classpath 对新增 mapper/service、修改后的 `AppExchangeService`/`AppGenesisService` 及两项测试执行独立 `javac`，全部通过。
- 合同测试：`AppMarketSandboxMapperContractTest` 断言所有 Sandbox SQL 带 `run_id/user_id`，并排除 `nx_user_wallet`、`nx_exchange_order`、`nx_genesis_order`、`nx_genesis_holding`、`nx_wallet_ledger`。
- 服务测试覆盖：生产 profile 禁止写入、Exchange 账号/RunID 传递、隔离钱包更新和双账本写入。需在修复既有编译错误后运行 Maven Surefire 完整红绿套件。

## 集成点

1. 将唯一 migration `scripts/migrations/20260817_market_app_sandbox_run_scope.sql` 加入受控启动迁移序列；本任务未修改中央 migration runner。
2. 启动 local-sandbox 时注入合法 `NEXION_ACCEPTANCE_RUN_ID`，并确保 Sandbox App 账号 `nx_user.sandbox=1`。
3. App 继续使用现有 Exchange/Genesis HTTP 路由；服务层在 Sandbox profile 自动切换到独立 MySQL mapper。

## 风险与 HOLD

- 真实链上、真实钱包、真实 Genesis 发行和生产 Exchange 仍为 HOLD；Sandbox 成功不得显示为生产成功。
- 当前初始 Sandbox 余额由显式 local-sandbox 配置写入隔离钱包，仅用于验收，不可作为生产资金。
- 启动迁移未接入前，运行态会返回隔离表不可用；不得回退到内存、本地 mock 或生产表。

# 本地运行（macOS / Linux）

Windows 用 `scripts/*.ps1`；本目录是等价的 bash 版本（问题 C-1 / C-2 / C-3）。

## 前置

- JDK 17、Maven、MySQL 8.4 客户端与服务、Redis（均监听 127.0.0.1）。
- 机密放在仓库外、权限 600 的文件里，默认 `~/.nexion-local.env`（可用环境变量 `NEXION_LOCAL_ENV` 指定），至少包含：
  `NEXION_DB_URL`、`NEXION_DB_USERNAME`、`NEXION_DB_PASSWORD`、`NEXION_FINANCE_DATA_KEY`、`NEXION_JWT_SECRET`、`NEXION_ADMIN_MFA_ENCRYPTION_KEY`。
- 工具不在 PATH 上时，在同一文件里设 `JAVA_HOME`、`MYSQL_HOME`、`MAVEN_HOME`。

曾运行旧版 `3dc5196e` 的本地库在升级客服资金读取函数时需要 Python 3.9 或以上。Bash 使用 `python3`，Windows 使用 `python`。如旧脚本执行中断，使用原安装用户和该库对应的私有回执重新运行。回执缺失或与数据库不符时，先从原安装用户的备份恢复。详见 [H4 下线 PRD](../../docs/PRD/H4-持仓类活动下线-20261104.md)。

## 从空库开始

```bash
scripts/local/bootstrap_db.sh --baseline     # 建库 + seed，再跑启动迁移链（含空库前置）
scripts/local/set_superadmin_password.sh     # 设置 superadmin 随机密码（只显示一次）
scripts/local/start_backend.sh               # 每次启动前会重跑幂等迁移，然后以 dev 配置在 8110 端口启动
```

以后启动只需 `scripts/local/start_backend.sh`。

用于客服资金统计的 TEST 副本需要完整的 MySQL 备份。逻辑导出沿用受管备份的 `--routines --events --triggers`，还原时保留原执行者、触发器顺序和受限账号的授权。恢复后用应用数据库账号执行 [客服验收库前置检查](check_support_analytics_restore.sql)，`funds_ready` 和 `rules_ready` 均为 `1` 时进入业务验收。已有库从对应备份恢复 `nx_support_rules.id=1` 的原版本与运营配置。全新空库按 S3 初始化该行。首份客服统计快照发布后，用授权客服查询并核对所选客户的钱包余额。

### 空库为什么需要额外步骤（C-1）

Bash 直接读取 PowerShell 的 `$migrations` 清单，新增启动必需的迁移应按依赖顺序登记在该清单。

启动迁移链（`apply_startup_schema_migrations.ps1` 中的清单）默认面对一个长期存在的开发库：部分迁移依赖清单之外的历史迁移建的表、运行时才建的客服表。`scripts/fresh_db/` 补上这些前置：

| 文件 | 作用 |
|---|---|
| `prereqs.txt` | `启动迁移|前置文件`：在指定启动迁移之前先执行的结构（只建表，不含历史种子数据） |
| `post.txt` | 启动链之后补建应用仍在查询的表，并执行依赖这些后置表的迁移 |
| `skip.txt` | 业务数据还不存在时跳过的迁移：只含数据断言（如要求商品 `stellarbox-s1` 已存在）。第三列是补跑条件，在后台建好该数据后，下次运行 `bootstrap_db.sh` / `start_backend.sh` 会自动执行 |

这些步骤须可重复执行；历史数据修复只处理仍满足修复条件的记录。

### D6 汇率配置初值

`post.txt` 登记的 `fresh_db/06_d6_fx_quote_config.sql` 在启动迁移链之后补齐 D6 必需的 `VND_USDT` 单例。表由 `prereqs.txt` 中的越南支付 DDL 前置创建；后置步骤只在主键缺失时插入原历史迁移的安装初值：基础汇率 26000、买入价差 1.50%、锁价 30 分钟、版本 0。已有运营值、版本、更新时间及软删除记录均保留，不会在启动时重置。该值是安装初值，后续汇率维护继续使用原 D6 权限、版本和审批流程。

空库安装验收应在人工测试准备前，用有权管理员读取 `GET /api/admin/finance/fx-quote` 并核对该单例；已有配置重跑前后逐字段对照。该后置只补 D6，不恢复其他历史业务种子，也不属于受管发布的 `scripts/migrations` 目录。详见 [D6 空库初始化 PRD](../../docs/PRD/D6-空库汇率配置初始化-20261109.md)。

## 首次登录后

- **超管 MFA**：首次登录要求绑定一次性验证码（TOTP），请保存密钥。
- **账号治理**：系统要求至少 2 名有效超级管理员；只有 1 名时，只能先创建第 2 名超管，之后才能创建其他账号或重置密码（错误码 `ADMIN_GOVERNANCE_RECOVERY_ONLY`）。新账号的密码由系统生成临时密码，不能由操作人指定。
- **F4 领导奖金池**：空库里的配置是 `__UNCONFIGURED__` 占位，结算调度保持关闭，日志提示"not configured yet"；在 F4 页面填入经审计的配置后自动启用。

# 本地运行（macOS / Linux）

Windows 用 `scripts/*.ps1`；本目录是等价的 bash 版本（问题 C-1 / C-2 / C-3）。

## 前置

- JDK 17、Maven、MySQL 8.4 客户端与服务、Redis（均监听 127.0.0.1）。
- 机密放在仓库外、权限 600 的文件里，默认 `~/.nexion-local.env`（可用环境变量 `NEXION_LOCAL_ENV` 指定），至少包含：
  `NEXION_DB_URL`、`NEXION_DB_USERNAME`、`NEXION_DB_PASSWORD`、`NEXION_FINANCE_DATA_KEY`、`NEXION_JWT_SECRET`、`NEXION_ADMIN_MFA_ENCRYPTION_KEY`。
- 工具不在 PATH 上时，在同一文件里设 `JAVA_HOME`、`MYSQL_HOME`、`MAVEN_HOME`。

## 从空库开始

```bash
scripts/local/bootstrap_db.sh --baseline     # 建库 + seed，再跑启动迁移链（含空库前置）
scripts/local/set_superadmin_password.sh     # 设置 superadmin 随机密码（只显示一次）
scripts/local/start_backend.sh               # 每次启动前会重跑幂等迁移，然后以 dev 配置在 8110 端口启动
```

以后启动只需 `scripts/local/start_backend.sh`。

### 空库为什么需要额外步骤（C-1）

启动迁移链（`apply_startup_schema_migrations.ps1` 中的清单）默认面对一个长期存在的开发库：部分迁移依赖清单之外的历史迁移建的表、运行时才建的客服表。`scripts/fresh_db/` 补上这些前置：

| 文件 | 作用 |
|---|---|
| `prereqs.txt` | `启动迁移|前置文件`：在指定启动迁移之前先执行的结构（只建表，不含历史种子数据） |
| `post.txt` | 启动链之后补建、应用仍在查询的表 |
| `skip.txt` | 业务数据还不存在时跳过的迁移：只含数据断言（如要求商品 `stellarbox-s1` 已存在）。第三列是补跑条件，在后台建好该数据后，下次运行 `bootstrap_db.sh` / `start_backend.sh` 会自动执行 |

这些文件都是 `CREATE TABLE IF NOT EXISTS` 一类的幂等结构，对已有库重复执行无副作用。

## 首次登录后

- **超管 MFA**：首次登录要求绑定一次性验证码（TOTP），请保存密钥。
- **账号治理**：系统要求至少 2 名有效超级管理员；只有 1 名时，只能先创建第 2 名超管，之后才能创建其他账号或重置密码（错误码 `ADMIN_GOVERNANCE_RECOVERY_ONLY`）。新账号的密码由系统生成临时密码，不能由操作人指定。
- **F4 领导奖金池**：空库里的配置是 `__UNCONFIGURED__` 占位，结算调度保持关闭，日志提示"not configured yet"；在 F4 页面填入经审计的配置后自动启用。

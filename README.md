# Nexion Backend

Java 17 + Spring Boot/Spring Cloud Alibaba + MySQL 8 + MyBatis-Plus + Redis + MinIO.

The project follows the module layout of `compute-power-leasing`:

| Module | Port | Responsibility |
|---|---:|---|
| `nexion-common` | - | shared API result, base entity, exception, security, MyBatis-Plus, MinIO config |
| `nexion-gateway` | 8090 | Spring Cloud Gateway routes |
| `nexion-auth-service` | 8101 | user register/login/referral identity |
| `nexion-auth-service` | 8101 | admin, role, permission, and assignment management |
| `nexion-device-service` | 8102 | user-owned device instances, using `nx_user_device` |
| `nexion-task-service` | 8103 | compute tasks and Proof-of-Compute receipts |
| `nexion-store-service` | 8104 | sellable devices and orders, using `nx_device` as device/SKU table |
| `nexion-wallet-service` | 8105 | wallet balances, bills, withdrawals |
| `nexion-team-service` | 8106 | V rank, team volume, commission summary |
| `nexion-notice-service` | 8107 | notifications and Stella messages |

User growth levels are modeled separately from the current user snapshot:

- `nx_user.user_level` / `nx_user.v_rank`: current L/V snapshot.
- `nx_user_level_config`: L0-L5 product-stage configuration.
- `nx_v_rank_config`: V0-V12 team-rank configuration and upgrade requirements.
- `nx_user_level_log`: user L/V level change history.

Rank upgrades are handled by `POST /team/ranks/evaluate`. Business services call it when rank-related events happen:

- `USER_REGISTERED`
- `FIRST_EARNING_RECEIVED`
- `STORE_VIEWED`
- `DEVICE_PURCHASED`
- `DIRECT_REFERRAL_REGISTERED`
- `TEAM_VOLUME_CHANGED`

Commission settlement is handled by independent trigger endpoints under `/team/commissions`:

- `POST /team/commissions/unilevel`: 7-layer order commissions, 30-day cooldown.
- `POST /team/commissions/binary`: daily binary collision, min(left,right) * 10%, capped at 5000 USDT.
- `POST /team/commissions/peer`: same-rank volume * 5%.
- `POST /team/commissions/cultivation`: one-time NEX reward when a downline promotes to V1-V5.
- `POST /team/commissions/leadership`: weekly leadership pool, platform weekly volume * 5% by V-rank votes.

## Local Database

```powershell
& 'D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe' -h 127.0.0.1 -P 3306 -u root '-p<mysql-password>' -e "source D:/workspace/nexion-backend/scripts/schema.sql; source D:/workspace/nexion-backend/scripts/seed.sql;"
```

## Build

```powershell
& 'D:\software\apache-maven-3.9.9\bin\mvn.cmd' -DskipTests compile
```

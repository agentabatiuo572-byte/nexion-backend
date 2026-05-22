# Nexion Backend

Java 17 + Spring Boot/Spring Cloud Alibaba + MySQL 8 + MyBatis-Plus + Redis + MinIO.

The project now follows the first-phase service split from the Nexion high-concurrency architecture document.

| Module | Port | Responsibility |
|---|---:|---|
| `nexion-common` | - | shared API result, base entity, exception, security, MyBatis-Plus, MinIO config |
| `nexion-gateway` | 8090 | Spring Cloud Gateway routes |
| `nexion-bff-service` | 8100 | page aggregation and high-concurrency read cache |
| `nexion-auth-service` | 8101 | user register/login/referral identity |
| `nexion-auth-service` | 8101 | admin, role, permission, and assignment management |
| `nexion-compute-service` | 8102 | device status, compute tasks, node map, Proof-of-Compute receipts |
| `nexion-mission-service` | 8103 | check-in, quests, points, achievements |
| `nexion-commerce-service` | 8104 | SKU catalog, orders, payment callbacks, Trade-in |
| `nexion-wallet-service` | 8105 | wallet balances, bills, withdrawals |
| `nexion-team-service` | 8106 | V rank, team volume, commission summary |
| `nexion-notification-service` | 8107 | notifications, Stella messages, push, unread counters |
| `nexion-earnings-service` | 8108 | earning ticks, summaries, event stream |
| `nexion-compliance-service` | 8109 | KYC, risk decisions, withdrawal checks, Proof assets |
| `nexion-system-service` | 8110 | operation config, i18n, content, help center |
| `nexion-openapi-service` | 8111 | API app keys, HMAC signature auth, call audit, webhook queue |

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

## Main Chain Smoke Test

Start these services first: `nexion-commerce-service`, `nexion-compute-service`, `nexion-earnings-service`, and `nexion-wallet-service`.

Then run:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_main_chain_services.ps1
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_main_chain.ps1
```

The smoke script verifies:

`commerce paid -> compute activate -> compute receipt -> earnings settle -> wallet post`

## Gateway Chain Smoke Test

Start the Gateway path services first: `nexion-gateway`, `nexion-bff-service`, `nexion-auth-service`, `nexion-commerce-service`, `nexion-compute-service`, `nexion-earnings-service`, and `nexion-wallet-service`.

Then run:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_gateway_chain_services.ps1
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_gateway_chain.ps1
```

The gateway start script defaults to the route config committed in this repository instead of any stale Nacos gateway config. Pass `-UseNacosConfig` when you intentionally want to validate the Nacos-published gateway routes.

The gateway smoke verifies anonymous business routes are rejected, registers a real user, logs in with that user's JWT, checks the BFF route, and then runs the same P0 business chain through `/api/**` Gateway routes. The smoke order and wallet checks use the `userId` returned by Auth instead of a fixed seeded user.

Optional user-smoke parameters:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_gateway_chain.ps1 -CountryCode "+1" -Phone "4151234567" -Password "Nexion123456" -ReferralCode "NX4892"
```

If `-Phone` is omitted, the script generates a unique smoke phone number for each run.

## OpenAPI Baseline

The OpenAPI service is exposed through Gateway at `/api/openapi/**`.

- Authenticated user APIs:
  - `POST /api/openapi/apps`: create an app key/secret pair.
  - `GET /api/openapi/apps`: list the current user's apps.
  - `POST /api/openapi/webhooks`: create a webhook subscription.
  - `GET /api/openapi/webhooks?appId=...`: list webhook subscriptions.
- Signed partner API:
  - `POST /api/openapi/v1/compute/receipts`: submit a compute receipt through HMAC-SHA256 signature auth.

Signed requests use these headers: `X-Nexion-App-Key`, `X-Nexion-Timestamp`, `X-Nexion-Nonce`, and `X-Nexion-Signature`.
The string to sign is `appKey + "\n" + timestamp + "\n" + nonce + "\n" + sha256(canonicalJsonBody)`.

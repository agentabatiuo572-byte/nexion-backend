# Nexion Backend

Java 17 + Spring Boot/Spring Cloud Alibaba + MySQL 8 + MyBatis-Plus + Redis + MinIO.

The project now follows the first-phase service split from the Nexion high-concurrency architecture document.

| Module | Port | Responsibility |
|---|---:|---|
| `nexion-common` | - | shared API result, base entity, exception, security, MyBatis-Plus, MinIO config |
| `nexion-gateway` | 8090 | Spring Cloud Gateway routes |
| `nexion-bff-service` | 8100 | Home/Earn/Wallet page aggregation and short TTL Redis snapshots |
| `nexion-auth-service` | 8101 | user register/login/referral identity |
| `nexion-auth-service` | 8101 | admin, role, permission, and assignment management |
| `nexion-compute-service` | 8102 | device status, compute tasks, node map, Proof-of-Compute receipts |
| `nexion-mission-service` | 8103 | check-in, quests, points, achievements |
| `nexion-commerce-service` | 8104 | SKU catalog, orders, payment callbacks, Trade-in |
| `nexion-wallet-service` | 8105 | wallet balances, bills, withdrawals |
| `nexion-team-service` | 8106 | OrderPaid outbox consumption, unilevel commission events, team overview |
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

The current backend baseline implements the first event-driven slice:

- `POST /team/outbox/consume-order-paid`: pulls pending `OrderPaid` events from commerce outbox, creates unilevel commission events for sponsor layers, and marks the outbox event as published.
- `GET /team/overview`: team count and commission summary for the current user.
- `GET /team/commissions`: paged commission events for the current user.

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

## Team Commission Smoke Test

Start the gateway chain services first. The start script now includes `nexion-team-service`.

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_gateway_chain_services.ps1
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_team_commission.ps1
```

The smoke script verifies:

`referred user register -> order paid -> OrderPaid outbox -> team consume -> sponsor unilevel commission`

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

## Reliable Event Outbox Baseline

`nexion-common` provides a JDBC-backed outbox helper for the local transaction + reliable event pattern described in the high-concurrency architecture document.

- Table: `nx_event_outbox`.
- Initial producer: `nexion-commerce-service` writes an `OrderPaid` event in the same transaction that marks an order as paid.
- Initial consumer: `nexion-team-service` consumes `OrderPaid` events and creates unilevel commission records.
- Internal commerce endpoints:
  - `GET /commerce/outbox/pending?limit=20`: list pending or retryable events.
  - `GET /commerce/outbox/aggregates/{aggregateType}/{aggregateId}`: inspect events for one aggregate.
  - `POST /commerce/outbox/{eventId}/published`: mark delivery complete.
  - `POST /commerce/outbox/{eventId}/failed`: mark delivery failed and schedule retry.

`smoke_main_chain.ps1` now verifies the `OrderPaid` outbox event before continuing to compute activation and earnings settlement.

## BFF Aggregation Baseline

The BFF service exposes page-level view models through Gateway at `/api/bff/**` and caches short-lived snapshots in Redis.

- `GET /api/bff/home`: wallet, devices, earning events, recent orders, and counts.
- `GET /api/bff/earn`: earning summaries and recent earning events.
- `GET /api/bff/wallet`: wallet balance and recent ledgers.
- `GET /api/bff/team`: placeholder aggregation until team-service business APIs are implemented.

Snapshot keys use `bff:{view}:{userId}` with a default TTL of 3 seconds, plus `bff:{view}:{userId}:last` for stale fallback.

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

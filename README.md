# Nexion Backend

Java 17 + Spring Boot/Spring Cloud Alibaba + MySQL 8 + MyBatis-Plus + Redis + MinIO + optional RocketMQ.

The project now follows the first-phase service split from the Nexion high-concurrency architecture document.

| Module | Port | Responsibility |
|---|---:|---|
| `nexion-common` | - | shared API result, base entity, exception, security, MyBatis-Plus, MinIO config |
| `nexion-gateway` | 8090 | Spring Cloud Gateway routes, JWT forwarding, Redis distributed rate limits, Sentinel flow/degrade protection |
| `nexion-bff-service` | 8100 | Home/Earn/Wallet page aggregation and short TTL Redis snapshots |
| `nexion-auth-service` | 8101 | user register/login/referral identity |
| `nexion-auth-service` | 8101 | admin, role, permission, and assignment management |
| `nexion-compute-service` | 8102 | device status, compute tasks, node map, Proof-of-Compute receipts |
| `nexion-mission-service` | 8103 | check-in, quests, points, achievements |
| `nexion-commerce-service` | 8104 | SKU catalog, orders, payment callbacks, Trade-in, outbox broker publisher |
| `nexion-wallet-service` | 8105 | wallet balances, bills, idempotent earning/team commission credits, withdrawals |
| `nexion-team-service` | 8106 | OrderPaid outbox worker/broker listener, unilevel commission events, commission unlock, team overview |
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
- `TeamOutboxWorker`: automatically polls due commerce outbox events and runs the same idempotent `OrderPaid` commission consumer.
- `CommerceOutboxRocketPublisher` / `TeamOrderPaidRocketListener` / `ComputeOrderPaidRocketListener`: optional RocketMQ path for publishing `OrderPaid` outbox events to a broker and consuming them from Team and Compute.
- `POST /team/commissions/unlock`: scans due `PENDING` commission events, posts USDT/NEX credits to wallet, and marks commissions as `POSTED`.
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

`referred user register -> order paid -> OrderPaid outbox -> team consume -> sponsor unilevel commission -> commission unlock -> wallet post`

Pass `-RequireWorker` to require the scheduled Team outbox worker or RocketMQ listener to consume the event without using the manual fallback endpoint.

## Gateway Chain Smoke Test

Start the Gateway path services first: `nexion-gateway`, `nexion-bff-service`, `nexion-auth-service`, `nexion-commerce-service`, `nexion-compute-service`, `nexion-earnings-service`, and `nexion-wallet-service`.

Then run:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_gateway_chain_services.ps1
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_gateway_chain.ps1
```

The gateway start script defaults to the route config committed in this repository instead of any stale Nacos gateway config. Pass `-UseNacosConfig` when you intentionally want to validate the Nacos-published gateway routes.

Publish the current Gateway route config to Nacos before using `-UseNacosConfig`:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\publish_nacos_gateway_config.ps1
```

`NacosGatewayConfigParityTest` verifies that `scripts/nacos/nexion-gateway.yaml` stays aligned with `nexion-gateway/src/main/resources/application.yml` for Gateway routes, Redis settings, rate-limit settings, and Sentinel settings.

The gateway smoke verifies anonymous business routes are rejected, registers a real user, logs in with that user's JWT, checks the BFF route, and then runs the same P0 business chain through `/api/**` Gateway routes. The smoke order and wallet checks use the `userId` returned by Auth instead of a fixed seeded user.

Optional user-smoke parameters:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_gateway_chain.ps1 -CountryCode "+1" -Phone "4151234567" -Password "Nexion123456" -ReferralCode "NX4892"
```

If `-Phone` is omitted, the script generates a unique smoke phone number for each run.

Pass `-CheckRateLimit` to also verify that anonymous `/api/commerce/**` traffic is rejected with `429` after the configured anonymous burst.

## Gateway Rate Limit Baseline

Gateway has a Redis fixed-window limiter for `/api/**` routes, with an in-process fixed-window fallback when Redis is unavailable. Keys are scoped by identity and route group:

- Anonymous key: client IP + first segment after `/api/`.
- Authenticated key: validated JWT subject hash + first segment after `/api/`.
- Forged or expired Bearer tokens stay on anonymous IP quota and cannot claim user-level limits.
- Default anonymous limit: 20 requests per 60 seconds.
- Default user limit: 120 requests per 60 seconds.

Local startup parameters:

- `-GatewayRateLimitEnabled true`
- `-GatewayRedisRateLimitEnabled true`
- `-GatewayRedisRateLimitTimeoutMs 50`
- `-GatewaySentinelEnabled true`
- `-SentinelDashboard 127.0.0.1:8858`
- `-SentinelTransportPort 8719`
- `-GatewaySentinelDefaultFlowQps 1000`
- `-GatewaySentinelCommerceQps 1000`
- `-GatewayAnonymousRateLimit 20`
- `-GatewayUserRateLimit 120`
- `-GatewayRateLimitWindowSeconds 60`

Gateway responses include `X-RateLimit-Backend` with `redis`, `local`, or `local-fallback`, which makes Redis outages visible during smoke and log checks. Gateway also includes the Spring Cloud Alibaba Sentinel starter and a route-group Sentinel filter. Sentinel resources use `gateway:{routeGroup}` names such as `gateway:commerce`; flow-control blocks return `429` with `X-Sentinel-Block-Type: flow`, and degrade/circuit blocks return `503` with `X-Sentinel-Block-Type: degrade`.

## Wallet Balance Mutation Baseline

Wallet owns balance mutation and ledger idempotency. Internal services can post balance changes through:

- `POST /wallet/credits/post`: protected by `PERM_WALLET_WRITE`.
- `POST /wallet/debits/post`: protected by `PERM_WALLET_WRITE`.
- `POST /wallet/deposits/confirmed`: protected by `PERM_WALLET_WRITE`; records an idempotent deposit order by `(chainTxHash, asset)` and credits available balance through the wallet ledger.
- `GET /wallet/deposits/pending|success|dead`: protected by `PERM_WALLET_READ`; inspect deposit order states.
- `GET /wallet/deposits/records?chainTxHash={tx}&asset={asset}`: protected by `PERM_WALLET_READ`; inspect deposit confirmation records.
- `POST /wallet/withdrawals`: creates an idempotent USDT withdrawal order, checks Compliance, and moves available balance into `pending_withdraw` when approved. Compliance `REVIEW` leaves the order in `REVIEWING` without mutating balances.
- `POST /wallet/withdrawals/{withdrawalNo}/chain-submitted`: records chain tx hash and moves the order to `CHAIN_SUBMITTED`.
- `POST /wallet/withdrawals/{withdrawalNo}/succeeded`: finalizes an approved/submitted withdrawal and releases `pending_withdraw`.
- `POST /wallet/withdrawals/{withdrawalNo}/failed`: marks the withdrawal failed, releases `pending_withdraw`, refunds available balance, and records a refund ledger.
- `POST /wallet/withdrawals/broadcast/publish?limit=20`: ops trigger for the withdrawal broadcast batch.
- `GET /wallet/withdrawals/broadcast/pending|dead|summary`: inspect withdrawal broadcast backlog and local DEAD rows.
- `POST /wallet/exchanges`: creates an idempotent exchange order, checks Compliance, debits the source asset, and credits the target asset when approved. Compliance `REVIEW` leaves the order in `REVIEWING`.
- `POST /compliance/gates/check`: protected by `PERM_COMPLIANCE_WRITE`; creates an idempotent `nx_risk_decision` from the KYC profile, blacklist, amount threshold, and daily frequency checks.
- `GET /compliance/risk-decisions/review`: lists pending manual review decisions.
- `POST /compliance/risk-decisions/{decisionNo}/approve|reject`: records manual review outcome.
- Required fields: `userId`, `bizNo`, `bizType`, `asset`, and positive `amount`.
- Idempotency key: `(biz_no, asset, direction)`.
- Debit safety: debits use a single conditional update (`available >= amount`) inside the transaction, so concurrent withdrawals or exchanges cannot drive available balance negative.
- Withdrawal safety: withdrawal reservation uses one conditional update (`usdt_available >= amount + fee`) to atomically decrement available USDT and increment `pending_withdraw`; success decrements pending only, while failure moves pending back to available.
- Withdrawal broadcast worker is disabled by default (`NEXION_WALLET_WITHDRAWAL_BROADCAST_ENABLED=false`). It scans `PENDING_CHAIN` rows, calls a replaceable `WithdrawalChainBroadcaster`, records `CHAIN_SUBMITTED` on success, and uses exponential retry before moving poison rows to local `DEAD`.
- Compliance risk thresholds are configurable with `NEXION_COMPLIANCE_WITHDRAWAL_REVIEW_AMOUNT`, `NEXION_COMPLIANCE_EXCHANGE_REVIEW_AMOUNT`, and `NEXION_COMPLIANCE_DAILY_REVIEW_COUNT`.

Team commission unlock uses `bizType=TEAM_COMMISSION` and `bizNo=TEAM-COMMISSION-{commissionId}` for both USDT and NEX ledger entries.

## Reliable Event Outbox Baseline

`nexion-common` provides JDBC-backed outbox and consumer-delivery helpers for the local transaction + reliable event pattern described in the high-concurrency architecture document.

- Table: `nx_event_outbox`.
- Initial producer: `nexion-commerce-service` writes an `OrderPaid` event in the same transaction that marks an order as paid.
- Initial consumer: `nexion-team-service` consumes `OrderPaid` events and creates unilevel commission records.
- Internal commerce endpoints:
- `GET /commerce/outbox/pending?limit=20`: list pending or retryable events.
- `GET /commerce/outbox/dead?limit=20`: list dead-letter events.
- `GET /commerce/outbox/aggregates/{aggregateType}/{aggregateId}`: inspect events for one aggregate.
- `POST /commerce/outbox/{eventId}/published`: mark delivery complete.
- `POST /commerce/outbox/{eventId}/failed`: mark delivery failed, schedule exponential retry, and move to `DEAD` after `nexion.outbox.max-retries`.
- `POST /commerce/outbox/broker/publish?limit=20`: manually trigger one broker publish batch when the broker publisher is enabled.
- `GET /{service}/outbox/consumer/summary`: summarize consumer delivery status by group/topic/status.
- `GET /{service}/outbox/consumer/dead?consumerGroup={group}&limit=20`: inspect local DEAD rows.
- `GET /{service}/outbox/consumer/events/{eventId}`: inspect one consumer delivery row, defaulting to the service's RocketMQ group.
- `GET /{service}/outbox/consumer/aggregates/{aggregateType}/{aggregateId}`: inspect delivery rows for one aggregate.
- Consumer delivery endpoints are available under `/team`, `/compute`, `/earnings`, `/wallet`, `/notifications`, and `/missions`.
- `GET /team/outbox/broker/consumer/status?includeDlq=true`: inspect RocketMQ broker-side consumer offset lag, active consumer connections, and `%DLQ%{consumerGroup}` queue depth.

RocketMQ broker delivery is optional and disabled by default. To switch the OrderPaid path to RocketMQ delivery, start commerce/team with:

- `NEXION_OUTBOX_ROCKETMQ_ENABLED=true`
- `ROCKETMQ_NAME_SERVER=127.0.0.1:9876`
- `NEXION_OUTBOX_ROCKETMQ_ORDER_PAID_TOPIC=nexion-order-paid`
- `NEXION_OUTBOX_ROCKETMQ_ORDER_PAID_GROUP=nexion-team-order-paid`
- `NEXION_OUTBOX_ROCKETMQ_COMPUTE_GROUP=nexion-compute-order-paid`
- `NEXION_OUTBOX_ROCKETMQ_EARNINGS_GROUP=nexion-earnings-compute-task-completed`
- `NEXION_OUTBOX_ROCKETMQ_WALLET_GROUP=nexion-wallet-earning-generated`
- `NEXION_OUTBOX_ROCKETMQ_NOTIFICATION_GROUP=nexion-notification-earning-generated`
- `NEXION_OUTBOX_ROCKETMQ_MISSION_GROUP=nexion-mission-earning-generated`
- `NEXION_OUTBOX_ROCKETMQ_CONSUMER_MAX_RETRIES=5`

For a broker-only Team path, also set `NEXION_TEAM_OUTBOX_WORKER_ENABLED=false`. The RocketMQ publisher marks the outbox row `PUBLISHED` only after RocketMQ returns `SEND_OK`; failed sends reuse the outbox exponential retry and `DEAD` handling. Consumer delivery is tracked in `nx_event_consumer_delivery` for Team, Compute, Earnings, Wallet, Notification, and Mission consumers; duplicate `consumer_group + event_id` deliveries are fenced, retry attempts are counted, malformed messages are recorded by `msgId`, and poison messages move to local `DEAD` after the configured max retry count. Broker-side lag and DLQ depth are available from `/team/outbox/broker/consumer/status`.

Gateway chain startup supports the same switch:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_gateway_chain_services.ps1 -OutboxRocketMqEnabled true -RocketMqNameServer "127.0.0.1:9876" -TeamOutboxWorkerEnabled false -OutboxRocketMqConsumerMaxRetries 5
```

Team outbox worker defaults:

- `NEXION_TEAM_OUTBOX_WORKER_ENABLED=true`
- `NEXION_TEAM_OUTBOX_WORKER_BATCH_SIZE=50`
- `NEXION_TEAM_OUTBOX_WORKER_INITIAL_DELAY_MS=5000`
- `NEXION_TEAM_OUTBOX_WORKER_FIXED_DELAY_MS=5000`

`smoke_main_chain.ps1` verifies the `OrderPaid` outbox event before continuing to compute activation and earnings settlement. `smoke_team_commission.ps1` also checks Team consumer delivery state and expects the OrderPaid delivery to reach `SUCCESS`; pass `-CheckBrokerMonitor` to also assert broker-side lag and DLQ depth.

## BFF Aggregation Baseline

The BFF service exposes page-level view models through Gateway at `/api/bff/**` and caches short-lived snapshots in Redis.

- `GET /api/bff/home`: wallet, devices, earning events, recent orders, and counts.
- `GET /api/bff/earn`: earning summaries and recent earning events.
- `GET /api/bff/wallet`: wallet balance and recent ledgers.
- `GET /api/bff/team`: team overview and recent commission aggregation from team-service.

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

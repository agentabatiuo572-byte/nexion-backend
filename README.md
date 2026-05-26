# Nexion Backend

Java 17 + Spring Boot/Spring Cloud Alibaba + MySQL 8 + MyBatis-Plus + Redis + MinIO + optional RocketMQ.

The project now follows the first-phase service split from the Nexion high-concurrency architecture document.

| Module | Port | Responsibility |
|---|---:|---|
| `nexion-common` | - | shared API result, base entity, exception, security, MyBatis-Plus, MinIO config |
| `nexion-gateway` | 8090 | Spring Cloud Gateway routes, JWT forwarding, Redis distributed rate limits, canary routing, Sentinel flow/degrade protection |
| `nexion-bff-service` | 8100 | Home/Earn/Wallet page aggregation and short TTL Redis snapshots |
| `nexion-auth-service` | 8101 | user register/login/referral identity |
| `nexion-auth-service` | 8101 | admin, role, permission, and assignment management |
| `nexion-compute-service` | 8102 | device status, compute tasks, node map, Proof-of-Compute receipts |
| `nexion-mission-service` | 8103 | check-in, quests, points, achievements, streak milestones, streak power-ups |
| `nexion-commerce-service` | 8104 | SKU catalog, orders, payment callbacks, Trade-in, outbox broker publisher |
| `nexion-wallet-service` | 8105 | wallet balances, bills, idempotent earning/team commission credits, withdrawals |
| `nexion-team-service` | 8106 | OrderPaid outbox worker/broker listener, unilevel/binary/peer/cultivation/leadership commission events, commission unlock, team overview |
| `nexion-notification-service` | 8107 | notifications, Stella messages, push, unread counters |
| `nexion-earnings-service` | 8108 | earning ticks, device snapshot settlement, milestone rewards, summaries, event stream, read-only analytics |
| `nexion-compliance-service` | 8109 | KYC lifecycle, risk decisions, withdrawal checks, Proof assets |
| `nexion-system-service` | 8110 | operation config, i18n, content, help center |
| `nexion-openapi-service` | 8111 | API app keys, HMAC signature auth, Redis app quotas, call audit, webhook delivery worker |

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
- `POST /team/commissions/binary`: daily binary collision, min(left,right) * 10%, capped at 5000 USDT, with historical matched-volume deduction.
- `GET /team/commissions/binary/summary`: inspect binary daily settlement rows.
- `POST /team/commissions/peer`: same-rank volume * 5%.
- `POST /team/commissions/cultivation`: one-time NEX reward when a downline promotes to V1-V5.
- `POST /team/commissions/leadership`: weekly leadership pool, platform weekly volume * 5% by V-rank votes.

The current backend baseline implements the first event-driven slice:

- `POST /team/outbox/consume-order-paid`: pulls pending `OrderPaid` events from commerce outbox, creates unilevel commission events for sponsor layers, and marks the outbox event as published.
- `TeamOutboxWorker`: automatically polls due commerce outbox events and runs the same idempotent `OrderPaid` commission consumer.
- `CommerceOutboxRocketPublisher` / `TeamOrderPaidRocketListener` / `ComputeOrderPaidRocketListener`: optional RocketMQ path for publishing `OrderPaid` outbox events to a broker and consuming them from Team and Compute.
- `POST /team/commissions/binary`: scans users with at least two direct legs, treats the first two direct legs as LEFT/RIGHT roots, deducts historical matched volume, and creates one daily `BINARY` commission event per user.
- `POST /team/commissions/peer`: creates monthly same-rank `PEER` commission events from `nx_team_member` volume for V3+ ranks with peer bonus enabled.
- `POST /team/commissions/cultivation`: scans V-rank upgrade logs and creates one-time `CULTIVATION` NEX commission events for the promoted user's direct sponsor.
- `POST /team/commissions/leadership`: splits a weekly leadership pool from `platformVolumeUsdt * leadership rule rate` across V3+ users by configured rank votes.
- `GET /team/leadership-pool`: returns the current user's pool unlock state, vote count, estimated share, total votes, and top vote participants for a supplied platform volume snapshot.
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

## Mission Streak Power-Ups Baseline

Mission now persists the Daily Streak Power-Up activation state described by the product spec. Unlock state is derived from the user's current streak; only explicit activation is stored.

- `GET /daily/power-ups`: current user's Power-Up list with `LOCKED`, `UNLOCKED`, or `ACTIVATED` status.
- `POST /daily/power-ups/{powerUpCode}/activate`: activates an unlocked Power-Up idempotently.
- `GET /missions/power-ups` and `POST /missions/power-ups/{powerUpCode}/activate`: Mission Center compatible aliases.
- Seeded Power-Ups: `royalty_boost` at 7 days, `premium_trial` at 14 days, `staking_boost` at 30 days, and `genesis_whitelist` at 60 days.
- Tables: `nx_streak_power_up` for configuration and `nx_user_streak_power_up` for user activation state.
- Activation also unlocks the matching badge achievement when configured and active; it does not yet apply cross-service commercial benefits.

## Mission Streak Milestones Baseline

Mission now persists the Daily 30-day milestone roadmap from the product spec. Unlock state is derived from the user's current streak; one successful claim is fenced by `nx_user_streak_milestone (user_id, milestone_day)`.

- `GET /daily/milestones`: current user's streak milestone list with `LOCKED`, `UNLOCKED`, or `CLAIMED` status.
- `POST /daily/milestones/{day}/claim`: claims an unlocked milestone idempotently.
- `GET /missions/milestones` and `POST /missions/milestones/{day}/claim`: Mission Center compatible aliases.
- Seeded milestones: day `3`, `7`, `14`, `21`, `30`, `60`, and `100`.
- Tables: `nx_streak_milestone` for configuration and `nx_user_streak_milestone` for claim state.
- `POINTS` rewards write `nx_points_ledger` using `STREAK-MILESTONE-{day}-{userId}`. `BADGE` rewards unlock the configured achievement. `USDT`, `NEX`, and `SPIN` are claim-recorded for downstream wallet/spin integration and do not yet post assets.

## Mission Top Streakers Baseline

Daily now exposes the product spec's Top Streakers social-proof list from `nx_user_streak`.

- `GET /daily/top-streakers?limit=5`: authenticated user's Daily Top Streakers list.
- `GET /missions/streak/top?limit=5`: Mission Center compatible alias.
- The response includes rank, user id, display name, avatar URL, country code, current streak, longest streak, last check-in date, and whether the user checked in today.
- Only active streaks with `last_check_in_date` today or yesterday are listed. `limit` is bounded to `1..100`.
- Phone numbers and emails are not exposed; blank nicknames fall back to a generated `Nexion_####` display name.

## Commerce Trade-in Baseline

Commerce now owns the server-side Trade-in quote and application baseline. Prices, product mapping, salvage value, and net upgrade cost are calculated on the server; clients only provide the current user and source device id.

- `POST /commerce/tradeins/quote`: validates the source device through compute-service, verifies it belongs to the user, maps S1/Pro to Pro v2 or Rack to Rack P2, then returns salvage, discount, and net upgrade cost.
- `POST /commerce/tradeins`: creates a `SUBMITTED` trade-in application from a fresh server-side quote.
- `GET /commerce/tradeins?userId=&status=&pageNum=&pageSize=`: pages trade-in applications, with `pageSize` capped at `100`.
- `GET /commerce/tradeins/{tradeinNo}`: returns one trade-in application.
- Table: `nx_tradein_application`.
- Seeded upgrade targets: `NX-PRO-V2` and `NX-RACK-P2`.
- Salvage formula: `source price * current efficiency * 0.30`; trade-in discounts are `300` USDT for S1/Pro -> Pro v2 and `800` USDT for Rack -> Rack P2.
- Device efficiency follows the product lifecycle curve: months 1-3 multiply by `0.96`, months 4-8 by `0.94`, months 9+ by `0.90`, floored at `0.22`.

## Commerce Payment Callback Baseline

Commerce now has a provider-facing payment baseline that keeps the existing `markPaid -> OrderPaid outbox -> compute activation` chain as the source of truth.

- `POST /commerce/payments/checkout`: creates or reuses a pending payment session for an unpaid order. The initial provider is `MOCK`.
- `POST /commerce/payments/callbacks/{provider}`: verifies provider callback HMAC headers, records callback events idempotently by `provider + eventId`, validates order/payment id/amount/currency, then calls the existing `markPaid` flow on successful payment.
- `GET /commerce/payments?userId=&orderNo=&paymentNo=&provider=&paymentStatus=&pageNum=&pageSize=`: pages payment records.
- `GET /commerce/payments/{paymentNo}`: returns one payment record.
- Tables: `nx_payment_record`, `nx_payment_callback_event`.
- Mock callback signature headers: `X-Nexion-Payment-Timestamp`, `X-Nexion-Payment-Nonce`, `X-Nexion-Payment-Signature`.
- Mock string to sign: `provider + "\n" + timestamp + "\n" + nonce + "\n" + sha256(rawJsonBody)`, HMAC-SHA256 with `NEXION_PAYMENT_MOCK_SECRET`.

## Main Chain Smoke Test

Start these services first: `nexion-commerce-service`, `nexion-compute-service`, `nexion-earnings-service`, and `nexion-wallet-service`.

Then run:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_main_chain_services.ps1
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_main_chain.ps1
```

The smoke script verifies:

`commerce paid -> compute activate -> worker lease task -> task completion receipt -> earnings settle -> wallet post`

## Team Commission Smoke Test

Start the gateway chain services first. The start script now includes `nexion-team-service`.

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_gateway_chain_services.ps1
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_team_commission.ps1
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_team_binary_commission.ps1
```

The smoke script verifies:

`referred user register -> order paid -> OrderPaid outbox -> team consume -> sponsor unilevel commission -> commission unlock -> wallet post`

The binary smoke verifies:

`sponsor + two direct legs -> two paid orders -> Team volume -> binary daily settlement -> binary summary -> commission unlock -> wallet post`

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

`NacosGatewayConfigParityTest` verifies that `scripts/nacos/nexion-gateway.yaml` stays aligned with `nexion-gateway/src/main/resources/application.yml` for Gateway routes, Redis settings, rate-limit settings, canary settings, and Sentinel settings.

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

Gateway reloads Sentinel flow/degrade rules when Spring Cloud publishes environment changes for `nexion.gateway.sentinel.*` or `spring.cloud.sentinel.*`, so Nacos-pushed Sentinel rule updates can take effect without restarting Gateway.

## Compute Device State Baseline

Compute now keeps high-frequency device state in Redis instead of writing every heartbeat to MySQL.

- `PATCH /compute/devices/{id}/status`: reports transient status and telemetry into `compute:device:state:{deviceId}` with TTL; requires `PERM_COMPUTE_WRITE`.
- `GET /compute/devices/{id}/status`: reads Redis first and falls back to the device row when cache is empty or unavailable.
- `GET /compute/devices/node-map?limit=100`: reads cached device states and returns node-map points plus online/busy/degraded/offline counts; if Redis is unavailable it falls back to database device metadata.
- Redis index key: `compute:device:state:index`.
- TTL config: `NEXION_COMPUTE_DEVICE_STATE_TTL_SECONDS`, default `30`.

Run the direct Compute smoke after applying `scripts/seed.sql` or `scripts/patch_business_api_permissions.sql` so admin/service tokens can include `PERM_COMPUTE_WRITE`:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_compute_device_status.ps1
```

## Compute Task Scheduling Baseline

Compute now has a minimal real task lifecycle instead of only accepting already-completed receipt submissions.

- `POST /compute/tasks/dispatch`: ops/scheduler entrypoint that atomically claims one `ONLINE` device with a conditional MySQL update, creates a `RUNNING` task with a lease and attempt counter, marks the device `BUSY`, and writes best-effort Redis lifecycle state.
- `POST /compute/tasks/worker/lease`: worker-facing lease endpoint. It returns a compact task + device assignment, renews an existing unexpired `RUNNING` task for the same `clientName + taskType` without creating a duplicate, or atomically dispatches a new task and records `workerAckAt` immediately.
- `POST /compute/tasks/{taskNo}/ack`: worker acknowledgement/heartbeat for a `RUNNING` task; records `workerAckAt` and renews `leaseExpiresAt`.
- `POST /compute/tasks/{taskNo}/complete`: completes a `RUNNING` task, creates the Proof-of-Compute receipt, publishes the existing `ComputeTaskCompleted` outbox event, triggers the existing earnings settlement path, and releases the device to `ONLINE`.
- `POST /compute/tasks/{taskNo}/fail`: marks a `RUNNING` task `FAILED` and releases the device to `ONLINE`.
- `POST /compute/tasks/maintenance/timeouts?limit=20`: scans expired `RUNNING` leases, releases devices, and moves tasks to `RETRYING` or terminal `FAILED` when attempts are exhausted.
- `POST /compute/tasks/maintenance/retries?limit=20`: scans due `RETRYING` tasks, atomically claims a new `ONLINE` device, and moves the same task back to `RUNNING`.
- `GET /compute/tasks?status=&userId=&userDeviceId=&taskType=`: paged task inspection for ops.
- Task write endpoints require `PERM_COMPUTE_WRITE`; task reads require `PERM_COMPUTE_READ`.
- `nx_compute_receipt.task_no` is unique so repeated completion can return the existing receipt instead of double-settling rewards.
- Maintenance worker is disabled by default (`NEXION_COMPUTE_TASK_MAINTENANCE_ENABLED=false`). Enable it to run timeout and retry scans on a fixed delay. Key knobs: `NEXION_COMPUTE_TASK_DEFAULT_LEASE_SECONDS`, `NEXION_COMPUTE_TASK_DEFAULT_MAX_ATTEMPTS`, `NEXION_COMPUTE_TASK_RETRY_INITIAL_BACKOFF_SECONDS`, and `NEXION_COMPUTE_TASK_MAINTENANCE_BATCH_SIZE`.

Run the direct Compute task smoke after applying `scripts/schema.sql` and `scripts/seed.sql`:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_compute_task_dispatch.ps1
```

## Earnings Analytics Baseline

Earnings exposes read-only derived analytics for the `/earn` experience without mutating wallet balances or settlement events.

- `GET /earnings/analytics/trend?userId=&startDate=&endDate=`: returns up to 90 days of daily USDT/NEX summary points, fills missing dates with zeroes, and includes period totals, cumulative values, average daily USDT, and best day.
- `GET /earnings/analytics/milestones?userId=`: computes lifetime USDT from `nx_earning_summary` and returns the five product milestone thresholds (`earn-100` through `earn-10000`), achieved state, next milestone, remaining USDT, and progress percent.
- `GET /earnings/analytics/missed-income?userId=&joinedAt=`: computes the product missed-income banner numbers from configurable `PHONE_DAILY` and `S1_DAILY` constants. Defaults are `0.06` and `38.50` USDT/day; override with `NEXION_EARNINGS_ANALYTICS_PHONE_DAILY_USDT` and `NEXION_EARNINGS_ANALYTICS_S1_DAILY_USDT`.

The analytics endpoints are intentionally read-only and use parameterized mapper queries for range and lifetime aggregation. Direct and Gateway smoke scripts now verify trend, milestone, and missed-income responses as part of the main earning chain.

## Earnings Tick And Milestone Baseline

Earnings now has an ops/scheduler path for automatic device income ticks and lifetime milestone reward events. It stays inside `nexion-earnings-service`; no separate compute worker service is required for this baseline.

- `POST /earnings/ticks/settle-batch`: settles an explicit batch of earning ticks through the existing idempotent receipt settlement path. Batch size is capped at 500 and requires `PERM_EARNINGS_WRITE`.
- `POST /earnings/ticks/settle-device-snapshot?tickAt=&limit=100`: scans `ONLINE`/`BUSY` devices from `nx_user_device`, prorates `daily_usdt` and `daily_nex` by the configured interval, creates deterministic `TICK-{slotStart}-{deviceId}` receipts, and then scans affected users for milestones. Requires `PERM_EARNINGS_WRITE`.
- `POST /earnings/milestones/scan?userId=&achievedAt=`: scans lifetime USDT from `nx_earning_summary` and creates one idempotent NEX reward event per newly achieved milestone.
- Milestone rules are shared by read-only analytics and reward settlement: `earn-100`, `earn-500`, `earn-1000`, `earn-5000`, and `earn-10000`.
- Milestone reward receipts use `MILESTONE-{userId}-{milestoneId}` and are fenced by `nx_earning_milestone (user_id, milestone_id)`.
- The scheduled `EarningTickWorker` is disabled by default. Enable it only after confirming the intended settlement cadence.

Local startup knobs:

- `NEXION_EARNINGS_TICK_WORKER_ENABLED=false`
- `NEXION_EARNINGS_TICK_INTERVAL_SECONDS=3600`
- `NEXION_EARNINGS_TICK_BATCH_SIZE=100`
- `NEXION_EARNINGS_TICK_WORKER_INITIAL_DELAY_MS=60000`
- `NEXION_EARNINGS_TICK_WORKER_FIXED_DELAY_MS=3600000`

Manual direct-service smoke coverage:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_main_chain_services.ps1
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_main_chain.ps1
```

The direct smoke now verifies `commerce paid -> compute task completion receipt -> earnings settle -> analytics -> device snapshot tick -> wallet post`.

## Notification Center Baseline

Notification now exposes the user-facing notification center and an internal push dispatch baseline inside `nexion-notification-service`.

- `GET /notifications?readFlag=&type=&pageNum=&pageSize=`: current user's notification page.
- `GET /notifications/unread-count`: Redis-first unread count using `notification:unread:{userId}` with MySQL fallback.
- `POST /notifications/{notificationId}/read`: mark one owned notification as read and invalidate unread cache.
- `POST /notifications/read-all`: mark all current user's unread notifications as read.
- `DELETE /notifications/{notificationId}`: soft-delete one owned notification.
- `POST /notifications/ops/push-pending?limit=100`: manually dispatch due `PENDING`/`FAILED` pushes; requires `PERM_NOTIFICATION_WRITE`.
- The scheduled push worker is disabled by default. Enable it with `NEXION_NOTIFICATION_PUSH_WORKER_ENABLED=true`; tune `NEXION_NOTIFICATION_PUSH_WORKER_BATCH_SIZE`, `NEXION_NOTIFICATION_PUSH_WORKER_FIXED_DELAY_MS`, `NEXION_NOTIFICATION_PUSH_MAX_ATTEMPTS`, and `NEXION_NOTIFICATION_PUSH_RETRY_DELAY_SECONDS`.
- The default `PushProvider` is a no-op logger so production push vendors can be wired later without changing notification state handling.

## Gateway Canary Baseline

Gateway canary routing is disabled by default and only rewrites the downstream target URI when a configured route rule matches. The first committed route template is `commerce`:

- Global switch: `NEXION_GATEWAY_CANARY_ENABLED=false`.
- Route switch: `NEXION_GATEWAY_CANARY_COMMERCE_ENABLED=false`.
- Canary target: `NEXION_GATEWAY_CANARY_COMMERCE_URI=http://localhost:18104`.
- Forced canary header: `X-Nexion-Canary: true`.
- App-version header: `X-App-Version`, matched by `NEXION_GATEWAY_CANARY_COMMERCE_VERSIONS`.
- Percent rollout: `NEXION_GATEWAY_CANARY_COMMERCE_PERCENT`, using a stable user-id hash when authenticated and client IP otherwise.

When a request is routed to canary, Gateway adds `X-Nexion-Canary: true`, `X-Nexion-Canary-Route`, and `X-Nexion-Canary-Reason` response headers. With canary disabled or unmatched, existing stable routes are unchanged.

Local startup example for forcing `/api/commerce/**` requests with the canary header to `localhost:18104`:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_gateway_chain_services.ps1 -GatewayCanaryEnabled true -GatewayCanaryCommerceEnabled true -GatewayCanaryCommerceUri "http://localhost:18104"
```

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
- `POST /compliance/gates/check`: protected by `PERM_COMPLIANCE_WRITE`; creates an idempotent `nx_risk_decision` from the KYC profile, blacklist, amount threshold, region, user tier, same-IP velocity, same-device velocity, and daily frequency checks.
- Compliance gate requests require `userId`, `bizNo`, `bizType`, `asset`, and positive `amount`; optional `region`, `userLevel`, `clientIp`, and `deviceFingerprint` are persisted on the risk decision for ops traceability.
- Compliance gate responses now include `riskScore` and `ruleCodes` so ops can trace whether a hold came from active blacklist, KYC state, KYC expiry, region policy, region mismatch, amount threshold, low user tier, IP/device velocity, or daily frequency rules.
- `GET /compliance/kyc-profiles?userId=&status=&limit=20` and `GET /compliance/kyc-profiles/users/{userId}`: inspect KYC profiles.
- `POST /compliance/kyc-profiles`: submit or resubmit KYC metadata with storage object key only; no raw document number is stored.
- `POST /compliance/kyc-profiles/{userId}/approve|reject|expire`: operate KYC lifecycle with reviewer attribution, reason, and optional approval expiry.
- `POST /compliance/kyc-profiles/maintenance/expire-approved?limit=50`: ops trigger for expiring approved KYC rows whose `expiresAt` is in the past.
- `GET /compliance/proof-assets?userId=&proofType=&status=&limit=20` and `GET /compliance/proof-assets/{proofNo}`: inspect Proof asset metadata.
- `POST /compliance/proof-assets`: record Proof metadata such as object key, checksum, related business no, and JSON metadata.
- `POST /compliance/proof-assets/{proofNo}/verify|reject` and `DELETE /compliance/proof-assets/{proofNo}`: review or archive Proof rows.
- `POST /compliance/evidence/kyc-documents`: multipart upload of a KYC document to MinIO, then submit/resubmit the KYC profile with only the generated object key and last4 metadata stored.
- `POST /compliance/evidence/proof-assets`: multipart upload of Proof evidence to MinIO, computes `sha256:*`, then creates the Proof asset metadata row.
- `POST /compliance/evidence/upload-policies`: creates a generated object key plus presigned MinIO `PUT` URL for direct client upload.
- `GET /compliance/evidence/download-url?objectKey=&expiresInSeconds=900`: creates a short-lived presigned read URL for ops review.
- `GET /compliance/risk-decisions?userId=&bizType=&decision=&reason=&limit=20`: query recent risk decisions for operations review.
- `GET /compliance/risk-decisions/summary?days=7`: returns decision totals by outcome plus active blacklist count.
- `GET /compliance/risk-decisions/review`: lists pending manual review decisions.
- `POST /compliance/risk-decisions/{decisionNo}/approve|reject`: records manual review outcome.
- `GET /compliance/blacklists?status=&limit=20`: query blacklist rows.
- `POST /compliance/blacklists`: add or reactivate a user blacklist row with `reason`, `riskLevel`, `source`, `operator`, and optional `expiresAt`.
- `POST /compliance/blacklists/{userId}/release`: release an active blacklist row with reviewer attribution.
- Required fields: `userId`, `bizNo`, `bizType`, `asset`, and positive `amount`.
- Idempotency key: `(biz_no, asset, direction)`.
- Debit safety: debits use a single conditional update (`available >= amount`) inside the transaction, so concurrent withdrawals or exchanges cannot drive available balance negative.
- Withdrawal safety: withdrawal reservation uses one conditional update (`usdt_available >= amount + fee`) to atomically decrement available USDT and increment `pending_withdraw`; success decrements pending only, while failure moves pending back to available.
- Withdrawal broadcast worker is disabled by default (`NEXION_WALLET_WITHDRAWAL_BROADCAST_ENABLED=false`). It scans `PENDING_CHAIN` rows, calls a replaceable `WithdrawalChainBroadcaster`, records `CHAIN_SUBMITTED` on success, and uses exponential retry before moving poison rows to local `DEAD`.
- Compliance risk thresholds are configurable with `NEXION_COMPLIANCE_WITHDRAWAL_REVIEW_AMOUNT`, `NEXION_COMPLIANCE_EXCHANGE_REVIEW_AMOUNT`, `NEXION_COMPLIANCE_DAILY_REVIEW_COUNT`, `NEXION_COMPLIANCE_BLOCKED_REGIONS`, `NEXION_COMPLIANCE_REVIEW_REGIONS`, `NEXION_COMPLIANCE_LOW_TIER_REVIEW_AMOUNT`, `NEXION_COMPLIANCE_LOW_TIER_LEVELS`, `NEXION_COMPLIANCE_IP_DAILY_REVIEW_COUNT`, and `NEXION_COMPLIANCE_DEVICE_DAILY_REVIEW_COUNT`.
- Compliance evidence storage uses `NEXION_STORAGE_ENDPOINT`, `NEXION_STORAGE_ACCESS_KEY`, `NEXION_STORAGE_SECRET_KEY`, `NEXION_STORAGE_BUCKET`, `NEXION_COMPLIANCE_EVIDENCE_MAX_UPLOAD_SIZE_BYTES`, and `NEXION_COMPLIANCE_EVIDENCE_PRESIGN_EXPIRY_SECONDS`.
- KYC expiry worker is disabled by default (`NEXION_COMPLIANCE_KYC_EXPIRY_ENABLED=false`) and can be enabled with `NEXION_COMPLIANCE_KYC_EXPIRY_BATCH_SIZE`, `NEXION_COMPLIANCE_KYC_EXPIRY_REVIEWER`, `NEXION_COMPLIANCE_KYC_EXPIRY_INITIAL_DELAY_MS`, and `NEXION_COMPLIANCE_KYC_EXPIRY_FIXED_DELAY_MS`.

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
- `NEXION_OUTBOX_ROCKETMQ_NAME_SERVER=127.0.0.1:9876` (`ROCKETMQ_NAME_SERVER` is still accepted as a fallback)
- `NEXION_OUTBOX_ROCKETMQ_ACL_ENABLED=false`
- `NEXION_OUTBOX_ROCKETMQ_ACCESS_KEY=<rocketmq-access-key>`
- `NEXION_OUTBOX_ROCKETMQ_SECRET_KEY=<rocketmq-secret-key>`
- `NEXION_OUTBOX_ROCKETMQ_SECURITY_TOKEN=<optional-security-token>`
- `NEXION_OUTBOX_ROCKETMQ_ORDER_PAID_TOPIC=nexion-order-paid`
- `NEXION_OUTBOX_ROCKETMQ_ORDER_PAID_GROUP=nexion-team-order-paid`
- `NEXION_OUTBOX_ROCKETMQ_COMPUTE_GROUP=nexion-compute-order-paid`
- `NEXION_OUTBOX_ROCKETMQ_EARNINGS_GROUP=nexion-earnings-compute-task-completed`
- `NEXION_OUTBOX_ROCKETMQ_WALLET_GROUP=nexion-wallet-earning-generated`
- `NEXION_OUTBOX_ROCKETMQ_NOTIFICATION_GROUP=nexion-notification-earning-generated`
- `NEXION_OUTBOX_ROCKETMQ_MISSION_GROUP=nexion-mission-earning-generated`
- `NEXION_OUTBOX_ROCKETMQ_CONSUMER_MAX_RETRIES=5`

For a broker-only Team path, also set `NEXION_TEAM_OUTBOX_WORKER_ENABLED=false`. The RocketMQ publisher marks the outbox row `PUBLISHED` only after RocketMQ returns `SEND_OK`; failed sends reuse the outbox exponential retry and `DEAD` handling. Consumer delivery is tracked in `nx_event_consumer_delivery` for Team, Compute, Earnings, Wallet, Notification, and Mission consumers; duplicate `consumer_group + event_id` deliveries are fenced, retry attempts are counted, malformed messages are recorded by `msgId`, and poison messages move to local `DEAD` after the configured max retry count. Broker-side lag and DLQ depth are available from `/team/outbox/broker/consumer/status`.

When RocketMQ ACL is enabled, all configured outbox producers, push consumers, and the Team broker monitor create RocketMQ clients with `AclClientRPCHook`. Startup fails fast if ACL is enabled but access key or secret key is blank; logs and diagnostics only expose masked key metadata, not the secret.

Gateway chain startup supports the same switch:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_gateway_chain_services.ps1 -OutboxRocketMqEnabled true -RocketMqNameServer "127.0.0.1:9876" -OutboxRocketMqAclEnabled true -OutboxRocketMqAccessKey "<access-key>" -OutboxRocketMqSecretKey "<secret-key>" -TeamOutboxWorkerEnabled false -OutboxRocketMqConsumerMaxRetries 5
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

## System Operations Baseline

The System service exposes operational configuration, i18n messages, content pages, and help articles through Gateway at `/api/system/**`.

- `GET /api/system/configs?query=&status=&limit=20`: query config items for operations screens.
- `GET /api/system/configs/{configKey}`: read one active config item by key.
- `POST /api/system/configs/batch-query`: read active config items by key list.
- `POST /api/system/configs`: create a config item.
- `PATCH /api/system/configs/{id}`: update value, value type, remark, or status.
- `GET /api/system/i18n/messages?locale=&query=&status=&limit=20`: query i18n messages.
- `GET /api/system/i18n/messages/{messageKey}?locale=en-US`: read one active i18n message.
- `POST /api/system/i18n/messages/batch-query`: read active messages by locale and key list.
- `POST /api/system/i18n/messages`: create an i18n message.
- `PATCH /api/system/i18n/messages/{id}`: update message value or status.
- `GET /api/system/content/pages?query=&status=&limit=20`: query content pages.
- `GET /api/system/content/pages/{pageCode}`: read one active content page.
- `POST /api/system/content/pages`: create a content page.
- `PATCH /api/system/content/pages/{id}`: update title, content, or status.
- `GET /api/system/help/articles?query=&status=&limit=20`: query help articles.
- `GET /api/system/help/articles/{articleCode}`: read one active help article.
- `POST /api/system/help/articles`: create a help article.
- `PATCH /api/system/help/articles/{id}`: update title, content, sort order, or status.
- Read endpoints require `PERM_SYSTEM_READ`; create/update endpoints require `PERM_SYSTEM_WRITE`.

Supported value types are `STRING`, `NUMBER`, `BOOLEAN`, and `JSON`. Do not store credentials, private keys, or long-lived secrets in `nx_config_item`; use environment variables or a secret manager for those values.

Content payloads are capped at 65,535 characters at the API layer. Message keys, page codes, and article codes only allow letters, numbers, dot, underscore, colon, and hyphen; locales are normalized from forms such as `en_US` to `en-US`. Clients that render content as HTML must sanitize or escape it before display.

Seeded operational keys include product phase, withdrawal minimums, OpenAPI default quotas, risk review thresholds, and feature switches. Run the System smoke after applying `scripts/seed.sql` or `scripts/patch_business_api_permissions.sql` so admin tokens include `PERM_SYSTEM_READ` and `PERM_SYSTEM_WRITE`:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_gateway_chain_services.ps1
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_system_config.ps1 -AdminPassword "<admin-password>"
```

The smoke verifies anonymous rejection, normal-user permission denial, admin config create/list/get/batch/update, and disabled-config active lookup rejection. Pass `-AdminToken` or set `NEXION_SMOKE_ADMIN_PASSWORD` when you do not want to pass the admin password on the command line.

## OpenAPI Baseline

The OpenAPI service is exposed through Gateway at `/api/openapi/**`.

- Authenticated user APIs:
  - `POST /api/openapi/apps`: create an app key/secret pair with QPS and daily quota fields.
  - `GET /api/openapi/apps`: list the current user's apps without returning app secrets.
  - `POST /api/openapi/webhooks`: create a webhook subscription.
  - `GET /api/openapi/webhooks?appId=...`: list webhook subscriptions.
- Signed partner API:
  - `POST /api/openapi/v1/compute/receipts`: submit a compute receipt through HMAC-SHA256 signature auth.
- Ops APIs:
  - `GET /api/openapi/ops/apps?status=&appKey=&ownerUserId=&limit=20`: query API apps without returning app secrets.
  - `POST /api/openapi/ops/apps/{appId}/enable`: enable an API app.
  - `POST /api/openapi/ops/apps/{appId}/disable`: disable an API app.
  - `PATCH /api/openapi/ops/apps/{appId}/quotas`: adjust QPS/daily quota and ops remark.
  - `GET /api/openapi/ops/call-audits?appId=&appKey=&apiPath=&responseCode=`: query signed API call audit rows.
  - `POST /api/openapi/webhooks/deliveries/publish?limit=20`: manually trigger webhook delivery.
  - `GET /api/openapi/webhooks/deliveries?status=&appId=&eventType=`: query webhook delivery records.
  - `GET /api/openapi/webhooks/deliveries/pending|success|failed|dead|summary`: inspect delivery backlog and poison rows.
  - Ops endpoints require `PERM_OPENAPI_ADMIN`.

Signed requests use these headers: `X-Nexion-App-Key`, `X-Nexion-Timestamp`, `X-Nexion-Nonce`, and `X-Nexion-Signature`.
The string to sign is `appKey + "\n" + timestamp + "\n" + nonce + "\n" + sha256(canonicalJsonBody)`.
Nonce replay protection is tracked in `nx_openapi_nonce` with `NEXION_OPENAPI_NONCE_TTL_SECONDS`, while app quota counters use Redis keys scoped by `appKey + endpoint`.
If Redis ACL is enabled, set `SPRING_DATA_REDIS_USERNAME` and `SPRING_DATA_REDIS_PASSWORD` for the OpenAPI service.

See `docs/openapi-integration.md` for JavaScript signing, curl, webhook verification, and ops/admin examples.

Webhook delivery is disabled by default (`NEXION_OPENAPI_WEBHOOK_DELIVERY_ENABLED=false`). It sends JSON payloads to the subscription callback URL with `X-Nexion-Webhook-Id`, `X-Nexion-Event-Type`, `X-Nexion-Timestamp`, and `X-Nexion-Signature`, then retries with exponential backoff before moving poison deliveries to `DEAD`. Private callback URLs are rejected by default; set `NEXION_OPENAPI_WEBHOOK_ALLOW_PRIVATE_CALLBACKS=true` only for local integration tests.

Run the OpenAPI smoke after applying `scripts/seed.sql` or `scripts/patch_business_api_permissions.sql` so admin tokens include `PERM_OPENAPI_ADMIN`:

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_gateway_chain_services.ps1
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\smoke_openapi.ps1 -AdminPassword "<admin-password>"
```

The smoke verifies owner app creation, HMAC signing, nonce replay rejection, daily quota rejection, app disable rejection, call audit query, and webhook queue query. Pass `-AdminToken` or set `NEXION_SMOKE_ADMIN_PASSWORD` when you do not want to pass the admin password on the command line.

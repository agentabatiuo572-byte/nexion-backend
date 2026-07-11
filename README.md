# Nexion Backend

Active runtime: Nexion Ops Console modular monolith.

The backend now runs as one Spring Boot process with one primary database transaction boundary. Code is split by business domain packages under the root `src` tree, with a single Maven `pom.xml`.

## Active Project

The root `pom.xml` is the active backend project:

- `nexion-backend`: the active Ops Console backend process.

Legacy distributed service directories, the old `nexion-common` module, and the temporary child service module have been removed from this working tree. New Ops Console backend work must go into root `src` packages: business domains under `ffdd.opsconsole.{domain}`, boundary contracts under `ffdd.opsconsole.common`, and cross-cutting infrastructure under `ffdd.opsconsole.shared`.

## Run And Test

```powershell
& 'D:\software\apache-maven-3.9.9\bin\mvn.cmd' test
```

```powershell
powershell -ExecutionPolicy Bypass -File D:\workspace\nexion-backend\scripts\start_ops_console_monolith.ps1
```

Direct Maven run:

```powershell
& 'D:\software\apache-maven-3.9.9\bin\mvn.cmd' spring-boot:run
```

Default app entry:

- Main class: `ffdd.opsconsole.NexionOpsConsoleApplication`
- Default port: `8110`
- Base backend prefix: `/api/admin/**`
- Public config prefix: `/api/config/**`

Use environment variables for local credentials and secrets. Do not commit plaintext passwords or long-lived keys.

Common placeholders:

- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_DATA_REDIS_HOST`
- `SPRING_DATA_REDIS_PORT`
- `SPRING_DATA_REDIS_PASSWORD`
- `NEXION_STORAGE_ENDPOINT`
- `NEXION_STORAGE_ACCESS_KEY`
- `NEXION_STORAGE_SECRET_KEY`
- `NEXION_OUTBOX_ROCKETMQ_NAME_SERVER`

## Database

Initialize or refresh local schema and system baseline data with:

```powershell
& '<mysql-bin>\mysql.exe' -h 127.0.0.1 -P 3306 -u <mysql-user> '-p<mysql-password>' -e "source D:/workspace/nexion-backend/scripts/schema.sql; source D:/workspace/nexion-backend/scripts/seed.sql;"
```

`scripts/seed.sql` is limited to the local system baseline: admin login, RBAC, navigation, and platform configuration. It does not create business records.

The schema keeps existing business tables and adds the Ops Console tables needed by the monolith, such as user impersonation sessions, risk signals, weekly market curves, emergency gates, and BI reports.

For an existing database, run dated migrations before deploying the matching application revision. The rhythm-configurable release requires:

```powershell
& '<mysql-bin>\mysql.exe' -h 127.0.0.1 -P 3306 -u <mysql-user> '-p<mysql-password>' <database-name> -e "source D:/workspace/nexion-backend/scripts/migrations/20260711_rhythm_configurable.sql;"
```

## Module Boundaries

The code boundary is stricter than the deployment boundary:

- Controllers call only their own domain Application Service.
- Cross-domain reads or validations go through public Facades.
- Cross-domain side effects use domain events or explicit Application Service contracts.
- A domain must not directly depend on another domain's Mapper, Entity, or repository implementation.
- `server-canonical` rules apply: backend computes prices, balances, risk gates, coverage, payout eligibility, state transitions, and audit fields.

## Ops Domains

| Code | Domain | Package |
|---|---|---|
| A | Platform, RBAC, Audit, Config | `ffdd.opsconsole.platform` |
| B | Treasury and B1 coverage redline | `ffdd.opsconsole.treasury` |
| C | User profile, session, asset adjustment | `ffdd.opsconsole.user` |
| D | Finance and withdrawals | `ffdd.opsconsole.finance` |
| E | Device, trade-in restore, datacenter controls | `ffdd.opsconsole.device` |
| F | Team commission and rank policy | `ffdd.opsconsole.team` |
| G | NEX market weekly curve scheduling | `ffdd.opsconsole.market` |
| H | Growth phase, check-in NEX reward, withdraw gate | `ffdd.opsconsole.growth` |
| I | Content and I9 cross-agent handoff | `ffdd.opsconsole.content` |
| J | Emergency kill switches and SOP | `ffdd.opsconsole.emergency` |
| K | Risk cases and risk signals | `ffdd.opsconsole.risk` |
| L | BI reports, sensitive exports, regulatory templates | `ffdd.opsconsole.bi` |

## Cross-Cutting Rules

- Write commands that mutate money, risk, gates, user status, exports, or configuration require `Idempotency-Key`.
- High-risk operations require Confirm-with-Reason and write A2 audit rows.
- B1 coverage redline failures return `422`.
- Illegal state transitions return `409`.
- Retired active-product entries return `422`.
- Sensitive exports must not expose decrypted PII and must use short-lived download metadata.
- Redis, MinIO, and RocketMQ remain optional infrastructure adapters inside the same process.

## Retired Capabilities

Premium, NEX v2, and Points are not active product domains. They may appear only as historical compatibility, migration, rejection, or sunset notes. New active APIs must not create entry points for them.

## Technical Docs

Ops Console migration docs live in:

- `docs/ops-console-modular-monolith-architecture.md`
- `docs/ops-console-domain-contracts.md`
- `docs/ops-console-api-data-acceptance.md`

These documents are the current backend implementation target for the monolith migration.

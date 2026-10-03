# Persistent Python TEST worker (v2)

This service performs actual `UVEL_TEST_VECTOR_STATS_V1` computation on the TEST backend's normal task/receipt rail. It is not commercial compute evidence. It is disabled by default and cannot run on a `prod`, mixed, or missing active profile. v1's separately issued finite grants remain unchanged.

## Fixed scope and API

Only owner `60723153007`, paid Cloud Share device `1152`, instance `NEX-ORD-A86AD2E23EF62D719AEFB67A`, configuration `E2-20260924-EM` are accepted. Continuous mode requires actual profile **dev only**, `deployment-scope=TEST`, `server.forward-headers-strategy=none`, and a direct loopback request without `Forwarded`, `X-Real-IP` or `X-Forwarded-*` headers. It has its own `tc1_` credential and role; neither finite `tw1_` credentials nor USER/ADMIN tokens authorize v2. No country header is fabricated and ordinary App Geo enforcement remains active.

| Method | Exact path | Behavior |
| --- | --- | --- |
| POST | `/api/test/compute-workers/v2/next-task` | Body `{}`; normal locked claim/dispatch followed by TEST marking, in one existing transaction. Returns actual input and proof, or canonical `idle` while the normal device lock is active. |
| POST | `/api/test/compute-workers/v2/tasks/{taskNo}/complete` | Existing strict v1 result schema; independently recomputes all 32 integers and settles through the original transaction. |
| POST | `/api/test/compute-workers/v2/tasks/{taskNo}/release` | Body `{}`; CAS closes only the bound task's own runtime; never settles or cancels a task. |
| GET | `/api/test/compute-workers/v2/tasks/{taskNo}/receipt` | Read-only, same owner/device/instance/config and deterministic TEST kind. Returns safe receipt fields; never a nonce or raw artifact. |

`next-task` uses a persisted `TEST355-NEXT-<32 lower-hex>` idempotency key and the existing user → device → task locks. A task already marked as TEST cannot be taken over by a different claim. Normal task routing uses the original Cloud Share effective 8 GB, config/capacity/lock rules and server-created nonce. No broker, mapper, schema migration or manual ledger write is added.

The continuous identity has no assistant-created receipt/snapshot or 15 minute job expiry. Each task's actual `startedAt`, `proofExpiresAt` and `leaseExpiresAt` remain authoritative. Existing runtime heartbeat and proof timestamp checks remain. A task can earn at most **0.045005 USDT** and the normal UTC daily Cloud Share NEX transaction remains capped at **3**, with the existing daily deduplication. Multiple normal USDT jobs are permitted by the explicitly authorized continuous TEST scope. The default interval between jobs is 60 seconds; `--interval` is explicit (1–3600 seconds), and shorter intervals increase TEST earnings and load.

## Install on the verified TEST server

Root first completes the backend's normal required checks and TEST release. TEST210 without this source change does not implement v2. No host installation or enablement has been performed by the author.

1. Install both `scripts/test-compute-worker-daemon.py` and the unchanged `scripts/test-compute-worker.py` in `/opt/uvel-test-worker/`, root owned and readable but not writable by the worker. Python 3.9+ standard library is sufficient. Do not install a DB client, AWS capability, ordinary App password or backend environment access for this service.
2. Use `deploy/public-test/test-compute-worker-continuous.properties.example` as the **non-secret** binding reference in the verified TEST backend configuration. Retain `continuous.enabled=false` while installing. Keep v1's enable/credential/expiry properties untouched.
3. Root generates a separate random 32-byte credential. Its presentation is `tc1_` plus canonical unpadded base64url (43 characters). Backend `continuous.credential-sha256` is SHA256 of the **decoded 32 bytes**, not the token text. Supply the hash through the existing protected TEST backend configuration. Put the presentation token only in `/srv/nexgrid/secrets/test-compute-worker.credential`, root owned `0600`, with no shell echo, argv or report copy. Rotate credential and `continuous.executor-id` together so an already-authenticated identity cannot survive a registration change.
4. Install `deploy/public-test/uvel-test-compute-worker.service` into systemd. The service uses `DynamicUser`, its own private `StateDirectory`, `LoadCredential`, no capabilities, and loopback-only networking. Its credential is read into RAM from systemd's private credential mount; it is never passed via process arguments or an environment token. Validate this template against the actual server's systemd version before enabling it.
5. `/etc/uvel-test-worker/worker.env` is root owned `0600` and contains only `UVEL_TEST_WORKER_ENABLED=true` and `UVEL_TEST_WORKER_SCOPE=TEST`. Keep it absent/false until Root has verified the newly released v2 disabled/auth/local-channel guards and fixed bindings. Set backend `continuous.enabled=true` through the normal protected configuration/restart procedure, then start/enable only `uvel-test-compute-worker.service`. There is no permission request added by this runbook; these actions are within the user's explicit continuous TEST authorization.

The service never restarts, kills or modifies the backend. Disabling backend continuous mode revokes future requests; stop the worker normally first where possible. Keep non-secret bindings/executor id so the existing scheduled cleanup can close only that identity's stale runtime after a crash/revocation.

## Stop, restart and UNKNOWN

`systemctl stop uvel-test-compute-worker.service` prevents the next claim. A job already in flight finishes its bounded calculation/wait (75 seconds) and uses at most one request per operation (10 seconds each). On failure it attempts its own release once. The 95 second systemd stop budget allows that bounded work/cleanup. These are resource limits, not Root evidence-validity windows.

The private SQLite journal uses full synchronous commits and a process `flock`. It stores only task numbers, request keys, operation/status, input/result hashes and confirmed receipt numbers. Intent is committed **before** a potentially mutating call; a nonce, token, full response, input bytes and complete request never go into the journal. Confirmation requires the actual matching receipt/COMPLETED response and hashes. The original backend transaction is the final economic authority.

A lost/invalid claim or complete response, process death after intent, or a pending job on restart is **UNKNOWN**. Exit `78` prevents systemd automatic replay; the persisted state also blocks a reboot/manual restart. Release success cannot clear UNKNOWN. The service makes no automatic complete retry and never manufactures a receipt. Normal clean restarts from `IDLE` remain supported; systemd can restart ordinary crashes that occurred before any persisted pending intent.

A canonical 4xx rejection is recorded as `REJECTED`, not credited or automatically retried. Logs include only the HTTP status and an allowlisted fixed business code; all other message values become `UNRECOGNIZED_REJECTION`, with no response body, nonce or token printed. A release with an unknown outcome keeps the service stopped as UNKNOWN. A canonical no-eligible-task rejection from `next-task` is a transaction rollback and ordinary idle polling; other server errors remain conservative stops.

If TCP connection fails **before `request()` starts**, the journal records `NOT_SENT`. For `next-task` this has no task side effect and returns to normal polling at the configured interval; backend startup/restart does not permanently poison an idle worker. Once `request()` has begun, transport failure remains UNKNOWN. A known-unsent complete after an actual claim stops for reconciliation and attempts its own release once; it is never silently resubmitted. A release known not sent is recorded separately and leaves the original outcome intact.

For UNKNOWN, leave the service stopped and preserve the journal. Root performs normal read-only receipt/task/ledger reconciliation, using the fixed readback endpoint or already-authorized owner read paths. `receiptFound=false` does not prove rollback; no resubmission or new task is authorized by it. After a positively reconciled outcome, Root records the decision and preserves the old journal before preparing any fresh service state. This implementation deliberately provides no automatic state-reset/replay command. An already marked unresolved task remains unavailable for a second claim, even after runtime cleanup; its original business expiry and normal backend lifecycle remain in force.

SIGKILL, machine failure or lost storage cannot guarantee release. The durable intent and original CAS/receipt constraints prevent blind repeated settlement; server cleanup only closes its own stale runtime. Logs use fixed status codes and never print raw exception/response text.

## Local checks and live acceptance boundary

Run the scoped Java service/application/security/Geo tests and `scripts/test_test_compute_worker_daemon.py`; the Python checks are fully offline and use synthetic inputs with real SQLite commit/constraint behavior. Linux ownership/flock, systemd credentials/network restrictions, normal stop/restart and multiple actual next tasks require Root's TEST deployment acceptance. MySQL transaction rollback/concurrency checks remain part of the backend's required integration gate. Unit fixtures are not actual worker/receipt/production evidence.

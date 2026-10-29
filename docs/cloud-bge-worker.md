# TEST Cloud Share BGE-M3 worker

This worker performs real BGE-M3 embedding of immutable, explicitly labelled TEST text. The output is not a customer order or evidence of commercial revenue. It uses a dedicated TEST/dev-only backend API and its own systemd identity/state; it never uses an App password, USER token, production executor key or the old device-1152 `tc1_` worker. The previous worker's unit, credentials and UNKNOWN journal remain untouched.

## Runtime and fixed model

Python 3.9+ standard library is sufficient. The operator installs `scripts/cloud-bge-worker.py` root-owned and not worker-writable at `/opt/uvel-cloud-bge-worker/cloud-bge-worker.py`, and installs `deploy/public-test/uvel-cloud-bge-worker.service`. The unit uses `DynamicUser`, a private `StateDirectory`, `LoadCredential`, no capabilities and loopback-only networking. Validate the unit against the actual TEST systemd version before enabling it.

The operator prepared a separate Ollama 0.33.3 service on `http://127.0.0.1:11435`, CPU 2 / memory 4 GiB. Its fixed model is `bge-m3:567m`, with digest `sha256:7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab`, F16 / 566.70M parameters / embedding length 1024 / about 1.157 GB model bytes. This is the operator's 2026-10-30 model inventory, not a claim that the worker has been deployed or the target task has completed. The worker and backend verify `/api/tags` before and after embedding; a changed digest stops completion. Do not substitute the existing RAG's `bge-small-zh-v1.5`, Gemma or deterministic integer test calculation.

The worker sends `POST /api/embed` with `{model,input:inputTexts,truncate:false}` to the dedicated loopback service. It requires one 1024-element finite, nonzero vector per input, converts values to finite IEEE754 float32 little-endian bytes in input order, and checks that no vector becomes all zero after conversion. The artifact's SHA256 is `resultHash`. The backend independently recomputes the same model/text and validates every submitted float before calling the existing normal receipt/settlement transaction.

## Protected configuration

Keep backend `nexion.compute-task.test-cloud.enabled=false` and worker `UVEL_CLOUD_WORKER_ENABLED=false` until installation and negative guards have been checked. Backend activation requires its actual sole profile `dev`, `deployment-scope=TEST`, `server.forward-headers-strategy=none`, direct loopback requests and no forwarded headers. App Geo enforcement is unchanged.

Backend property prefix is `nexion.compute-task.test-cloud.`:

- `enabled`, `deployment-scope`, `credential-sha256`;
- `executor-id`, `owner-id`, `device-id`, `instance-no`, `task-config-id`;
- `model`, `model-digest`, `ollama-origin`;
- `cancel-unexecuted-task-no` is an optional exact legacy task authorization, empty by default. Root must first reconcile that task/receipt/ledger and provide the separately approved exact task if this migration is needed. It is not general cancellation permission;
- `cleanup-ms` defaults to 30000.

Root generates a new random 32-byte secret. Its presentation is `ct1_` followed by canonical unpadded base64url (43 characters). Backend `credential-sha256` is SHA256 of the decoded random bytes, not the token's printable text. Put only the presentation token in `/srv/nexgrid/secrets/cloud-bge-worker.credential`, root-owned mode `0600`. `LoadCredential=cloud-worker-token:...` exposes its private copy to the worker. Never pass the token through argv, environment, reports, Git or the journal. Neither `tc1_` nor `tw1_` authorizes this API.

The non-secret worker environment file `/etc/uvel-cloud-bge-worker/worker.env` is root-owned `0600`. For this first scope, set:

```ini
UVEL_CLOUD_WORKER_ENABLED=true
UVEL_CLOUD_WORKER_SCOPE=TEST
UVEL_CLOUD_BACKEND_URL=http://127.0.0.1:8110
UVEL_CLOUD_OLLAMA_URL=http://127.0.0.1:11435
UVEL_CLOUD_OWNER_ID=60723153007
UVEL_CLOUD_DEVICE_ID=1202
UVEL_CLOUD_INSTANCE_NO=NEX-ORD-657FD9775DFE5E2D11CC381D
UVEL_CLOUD_TASK_CONFIG_ID=E2-20260924-EM
UVEL_CLOUD_EXECUTOR_ID=<root-assigned-dedicated-executor>
UVEL_CLOUD_MODEL=bge-m3:567m
UVEL_CLOUD_MODEL_DIGEST=sha256:7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab
UVEL_CLOUD_POLL_SECONDS=60
```

Verify the actual authoritative task-config id before activation; the displayed E2 value is the first target's intended binding. All seven worker binding values must equal the backend's protected configuration and every canonical manifest/receipt. IDs are configuration, not hardcoded worker logic. Configuring another device is a separate authorization, not an automatic fleet rollout. The minimum poll interval is 60 seconds and task lock/capacity rules remain authoritative; do not shorten locks or change rewards to generate more TEST income.

New rebuildable cache, model-download staging, build and temporary data go under the task directory allocated by `sudo /usr/local/sbin/uvel-test-workdir <task-name>` on `/mnt/test-scratch`, using its `TMPDIR` and `XDG_CACHE_HOME`. Stop if scratch is unavailable. Keep durable worker state, necessary evidence, model persistence and rollback references according to the task's actual operational retention policy; do not delete shared model/KB data or run whole-stack activation. Finish the owned scratch task after evidence is saved.

## Wire contract

All backend paths are under `/api/test/cloud-executors/v1`, authenticated by the dedicated Bearer token. Success envelope is `{code:0,data:{...}}`.

| Method/path | Body / behavior |
| --- | --- |
| `POST /next-task` | `{}` with persisted `BGE-NEXT-<32hex>` idempotency key. Canonical manifest or `idle:true`; a lock produces idle without a fake task. |
| `POST /heartbeat` | `{}` for the configured active instance, independently of task creation. Returns canonical owner/device/instance/executor and `heartbeatAt` epoch milliseconds. |
| `POST /tasks/{taskNo}/heartbeat` | Backend also supports task-scoped `{claimToken}`; this worker uses the instance heartbeat so task gaps stay online without a fake claim. |
| `POST /tasks/{taskNo}/complete` | Exact fields: `specVersion,inputHash,modelDigest,resultArtifactBase64,resultHash,proofNonce,proofTimestamp,claimToken`; persisted `BGE-COMPLETE-<SHA256(taskNo)>` key. |
| `GET /tasks/{taskNo}/receipt` | Read-only positive confirmation; `found:false` never proves a mutating request rolled back. |

Manifest identity includes `executionKind=TEST_BGE_M3_V1`, `specVersion=UVEL_TEST_BGE_M3_V1`, `deploymentScope=TEST`, `serverCanonical=true`, and the seven configured binding fields. Active manifests also contain `taskNo,status,inputTexts,inputHash,proofNonce,claimToken,proofExpiresAt,leaseExpiresAt,completableAt,outputDimensions=1024`. Times are epoch milliseconds. `inputHash=SHA256(UTF8(compact JSON array of inputTexts))`, with Unicode preserved and separators `,` / `:`. Text is server-owned; the worker cannot replace it. A completed old next key is read back through the receipt endpoint and never computes or submits complete again.

Receipts include the same canonical identity, taskNo/inputHash/resultHash, `found`, `status`, and receiptNo. Automatic confirmation requires every configured binding and both hashes to match the persisted intent, `found=true`, `status=COMPLETED`, and a valid receipt number. The backend remains the financial authority.

## Safe standby, finite acceptance and separately authorized continuous operation

The installed unit defaults to `--heartbeat-only`. This mode requires the existing journal to be exactly `IDLE` with the previously confirmed binding equal to current protected configuration. It checks this before any reconciliation or network request; an empty/unconfirmed binding, `CLAIMED`, `NEXT_NOT_SENT`, UNKNOWN or any other pending phase is refused. Every standby heartbeat response must explicitly contain `activeTaskNo:null`; a backend active task or missing field stops standby, while ordinary task execution may still heartbeat its active task. Standby only checks the fixed model's health and sends the existing instance heartbeat every 10 seconds. It never calls next-task, inference, complete or receipt, and it cannot hide unresolved work. A model/permission/binding failure stops the service with 78; SIGTERM stops it normally. Preserve the journal from the successful finite acceptance when changing to standby.

An empty journal first obtains a canonical manifest/idle result; it does not heartbeat a legacy task before the normal next-task transition. A previously proven-unsent next request also retries its original key before heartbeat. Once the instance binding is confirmed, the worker verifies model availability and heartbeats every 10 seconds, including inference and task gaps. A fresh synchronous heartbeat gates complete after inference. Temporary heartbeat failures do not replay any economic operation; the next ordinary heartbeat can restore liveness. Persistent identity/model/credential rejection prevents a successful complete. Backend cleanup eventually marks an actually stopped worker offline.

`--once` performs at most one task and then stops. It returns 0 only for a newly confirmed computation or positive reconciliation of its previously sent complete, 2 for idle/proven-unsent next/already-completed old task, and 78 for an unconfirmed or invalid operation. A service exiting after `--once` is not continuous liveness evidence.

For first acceptance, root may install a temporary systemd override for this new unit only:

```ini
[Service]
ExecStart=
ExecStart=/usr/bin/python3 -I -B /opt/uvel-cloud-bge-worker/cloud-bge-worker.py --once
Restart=no
```

After actual task/receipt/ledger/App acceptance, remove only that owned override and reload systemd to use the template's safe `--heartbeat-only` standby. Preserve the same journal and confirmed identity. The first finite TEST-text acceptance does not authorize indefinitely repeating the same TEST texts and generating more ledger rewards. No real business input source is connected by this worker.

The command without either mode flag retains the previous continuous TEST execution mode for a separately authorized run. Enabling it requires explicit continuous TEST authorization and an operator override; it must not be the default service command. It polls at the configured interval and follows server locks/idle, without changing E2 economics, accelerated rewards, other instances or production scope. `--once` and `--heartbeat-only` are mutually exclusive.

SIGTERM prevents a new cycle. An in-flight HTTP call has a bounded deadline (10 seconds for next/heartbeat/receipt/tags; 300 seconds for inference/complete); a stop observed before complete leaves the task uncompleted. The unit's 330-second stop budget allows an already-started request to return and be reconciled. Killing the process can leave an intent requiring read-only reconciliation.

## Journal and recovery boundary

`/var/lib/uvel-cloud-bge-worker/journal.sqlite3` uses SQLite WAL, `synchronous=FULL`, a Linux process lock and private state permissions. Each possibly mutating backend HTTP call commits intent before `request()` begins. Only connection failure before HTTP request start is `NOT_SENT`. Once request start is possible, a lost/invalid response is UNKNOWN. The journal stores binding, task/key, operation/outcome, input/result/request hashes and confirmed receipt numbers, never token, claimToken, nonce, input texts, complete artifact or raw response.

- `NEXT_NOT_SENT`: no request started. Original next key may resume through normal polling or a restart; no replacement key and no heartbeat precedes its successful canonical response.
- `UNKNOWN_NEXT` or a persisted `NEXT_INTENT`: stop with 78. Do not automatically replay even though the backend's original key is idempotent. Root reconciles its durable key against backend request/task/manifest/audit state.
- `CLAIMED`, `COMPLETE_NOT_SENT`, or an interrupted computation: stop with 78 and preserve the task/journal for root reconciliation. This implementation does not claim automatic inference/complete recovery from these states.
- `COMPLETE_INTENT` / `UNKNOWN_COMPLETE`: on failure or restart, only GET receipt may positively confirm the same complete. Matching positive receipt atomically records the local confirmation. Missing receipt, transport failure, hash/binding disagreement or malformed result keeps the service stopped. It never sends complete or next as a recovery action.
- Ordinary heartbeat recovery changes liveness only. It cannot prove execution, erase UNKNOWN, synthesize a receipt or create funds.

The script has no reset, replay, force-complete or manual-credit command. Keep old state and recorded reconciliation before preparing any separately approved fresh state. A failed/78 service restart is not a remedy for economic ambiguity. The old device-1152 worker/journal is outside this worker's namespace.

## Automatic purchased hardware fleet (separately enabled TEST scope)

`--fleet` reuses the same `Worker.one`, inference, proof, durable intent and receipt logic. The existing single-instance unit and its state remain separate. Install `deploy/public-test/uvel-cloud-bge-fleet.service` as a new unit; its checked-in defaults disable both worker and fleet. The authorized operator enables `UVEL_CLOUD_WORKER_ENABLED=true` and `UVEL_CLOUD_FLEET_ENABLED=true` in root-owned `/etc/uvel-cloud-bge-fleet/worker.env`, and enables backend `nexion.compute-task.test-cloud.fleet-enabled` with the approved `fleet-started-at` cutoff. Credentials remain in the existing protected `LoadCredential` channel. No per-device IDs, passwords or secrets are written into worker environment files.

This mode is TEST cloud execution on behalf of purchased Cloud/S1/Pro/Rack hardware. Discovery requires a normally paid ORDER instance, current ownership, both purchase and activation after the configured cutoff, effective entitlement and an eligible real BGE-M3 task configuration. The cutoff uses backend business time (UTC+8). It does not manufacture local hardware telemetry or substitute cloud heartbeats for the free phone's native telemetry. Original task duration, reward, cooldown and capacity rules stay authoritative. The shared fixed TEST texts remain labelled TEST inputs; no customer workload or commercial revenue is implied.

The authenticated, loopback-only `GET /api/test/cloud-executors/v1/instances?afterDeviceId=0&limit=100` is a pure read. Its success data has `executionKind`, `specVersion`, `deploymentScope=TEST`, `serverCanonical=true`, `instances`, `nextAfterDeviceId` and `hasMore`. Each instance has the seven canonical binding fields and the same canonical metadata. Pages are strictly increasing by deviceId; the worker loads all pages before applying a snapshot. A concrete instance's first normal `next-task` transaction persists its stable backend binding. The worker sends `X-UVEL-Executor-Id` on its existing next/heartbeat/complete/receipt routes; the server derives and checks owner/device/instance from the binding. An unscoped App/USER/ADMIN credential never gains fleet authority. Single-instance requests without the header retain their existing contract.

Fleet state lives only in `/var/lib/uvel-cloud-bge-fleet/instances/<SHA256(executorId)>/journal.sqlite3`, each with its own private directory and process lock. No root single-instance journal is read, cleared or copied. Device 1152 and the still-owned single-instance device 1202 are excluded by `UVEL_CLOUD_FLEET_EXCLUDE_DEVICE_IDS=1152,1202`; the backend also excludes its configured static binding. Keep this exclusion until an explicit service/state ownership handoff is completed.

One background thread refreshes discovery and performs health heartbeats; the main thread runs a serial round-robin queue through the existing real inference path. No newly discovered empty journal heartbeats before its first canonical next response. Confirmed IDLE heartbeats require an explicit null active task; active heartbeats match the journal task. Confirmed IDLE also passes a synchronous idle health check before each new next. A late background response from a previous CLAIMED phase cannot quarantine an already confirmed completion. Health continues while waiting for the original completion time. A discovery removal interrupts that instance's wait and prevents new jobs; the server rechecks entitlement and instance status on every mutation. New instances may wait behind the current serial round; throughput is deliberately bounded by one computation at a time.

Any economic UNKNOWN or interrupted pending phase quarantines that instance and stops its health/economic actions while other instances continue. `NEXT_NOT_SENT` alone may retry its original durable key. `COMPLETE_INTENT`/`UNKNOWN_COMPLETE` may only read the matching receipt, including after removal/restart; a positive match confirms locally, and a missing or conflicting receipt never authorizes another complete/next. `UNKNOWN_NEXT`, `NEXT_INTENT`, `CLAIMED` and `COMPLETE_NOT_SENT` retain the existing manual reconciliation boundary. Private per-instance state is retained after revocation; stable historical bindings remain available for read-only receipts. SIGTERM interrupts pending waits and prevents a new complete, while an already-started HTTP request retains the existing bounded deadline and receipt recovery.

A temporary permission/DC pause may block a confirmed IDLE instance. If a later authoritative discovery returns the exact same binding, a real model/strict-IDLE heartbeat may restore health and scheduling without a restart. This recovery is allowed only for confirmed IDLE with no pending economic intent; discovery never clears an UNKNOWN or interrupted task.

## Verification

Run `python -B -m unittest discover -s scripts -p test_cloud_bge_worker.py -v`. Offline tests exercise committed intent visibility through an independent SQLite connection, real binary vector encoding, target/model/input boundaries, connect-before-request classification, next restart ordering, complete-response loss, negative-readback refusal and crash-after-intent recovery. Standby tests use the real heartbeat thread without fixed sleeps and prove confirmed-IDLE heartbeat with zero next/complete, rejection of unconfirmed or unresolved state before network, model/permission failure stop and clean shutdown. Fleet checks add pure-read pagination (including skipped/empty scanning pages), independent journals, legacy exclusions, UNKNOWN isolation, positive-only receipt recovery after removal/restart, strict idle/active health, temporary-pause recovery and actual background-thread stop races. They do not establish real Ollama execution, Linux/systemd isolation, backend independent recomputation, TEST deployment or actual financial/App acceptance; root performs those checks and independent review.

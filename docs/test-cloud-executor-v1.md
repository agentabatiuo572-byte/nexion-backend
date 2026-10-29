# TEST real BGE-M3 cloud executor

This channel executes three declared TEST texts through the real pinned BGE-M3 model. It is not customer workload, commercial execution attestation, GPU attestation, or permission to release production. Its manifests, stored task model/client, proof and audit retain `TEST_BGE_M3_V1`. Public App task/receipt and Home presentation maps that known kind to BGE-M3 / UVEL Compute / BGE-M3 Embedding. It does not relabel the old vector-statistics worker as BGE.

## Deployment and authorization

The API is disabled unless all loaded guards hold: `nexion.compute-task.test-cloud.enabled=true`, `deployment-scope=TEST`, the sole Spring profile is `dev`, and `server.forward-headers-strategy=none`. A separate SecurityFilterChain rejects ordinary USER/ADMIN JWTs and old `tw1_`/`tc1_` credentials. Every exact route must arrive from a literal loopback peer with no Forwarded, X-Forwarded-* or X-Real-IP header; the early Geo filter checks before header rewriting. This does not exempt ordinary App or similar URL paths from their existing gates.

The protected configuration prefix is `nexion.compute-task.test-cloud.`:

| Field | Rule |
|---|---|
| enabled | Default off |
| deployment-scope | Exact TEST |
| credential-sha256 | SHA256 of the dedicated credential's decoded 32 bytes; protected config only |
| executor-id | Dedicated 3–32 safe ASCII identity |
| owner-id / device-id / instance-no | One exact authorized database owner and owned paid Cloud Share instance; no IDs are compiled into source |
| task-config-id | Exact eligible E2 EM configuration |
| model | Exact `bge-m3:567m` |
| model-digest | Exact `sha256:7907646426070047a77226ac3e684fbbe8410524f7b4a74d02837e43f2146bab` |
| ollama-origin | Controlled `http://127.0.0.1:<port>` origin, no credentials/path/query/fragment; backend independently checks tags before and after inference |
| model-timeout-ms | Default 180000, bounded to 100–180000; total async deadline cancels even a response that stalls after headers. Each tags request has a 10000ms total deadline. Model body is capped at 262144 bytes |
| cancel-unexecuted-task-no | Optional one exact old task, default no cancellation. Existing unmanifested tasks block otherwise |
| cleanup-ms | Default 30000, own runtime heartbeat older than 120 seconds becomes OFFLINE, without changing task/financial facts |

Credential wire format is `ct1_` plus canonical unpadded base64url encoding of 32 random bytes. Neither token nor its hash goes in commands, logs, documents or evidence. Root deploys an independent protected configuration and worker credential; it does not change the old 1152 worker identity, service, token or UNKNOWN journal.

Apply `scripts/migrations/20261030_test_cloud_bge_execution.sql` through the normal migration route. The startup migration list and fresh schema include this create-only table. It does not edit historical business rows or create rewards. Deploy backend plus worker/config together, verify actual loaded revision and guards, then enable only the authorized binding. No production deployment follows from this document.

Once a TEST binding is registered (scope/profile plus exact owner/device), ordinary USER claim and automatic dispatch reject that binding, including while its executor is disabled. Only the dedicated next-task transaction can claim its normal eligible E2 task; activation, ownership, purchased snapshot, price and wallet are unchanged. Removing the registration does not let an already marked TEST_BGE_M3_V1 task use the generic production completion API.

## HTTP contract

Base `/api/test/cloud-executors/v1`, dedicated Bearer header, normal `{code:0,data:...}` envelope. POST bodies are strict JSON objects: duplicate/unknown keys, trailing JSON and bodies over 20000 bytes are rejected. Idempotency-Key is 8–128 safe ASCII characters.

Common response bindings: `executionKind=TEST_BGE_M3_V1`, `specVersion=UVEL_TEST_BGE_M3_V1`, `deploymentScope=TEST`, `serverCanonical=true`, executorId, ownerId, deviceId, instanceNo, taskConfigId, model, modelDigest. All timestamps are integer epoch milliseconds.

| Route | Body and effect |
|---|---|
| POST next-task | `{}` + fixed Idempotency-Key. Under user→device→task locks, returns the existing own manifest task or normally creates a new E2 task, freezes input/model/nonce/instance, records actual executor acknowledgment and heartbeat. Data adds taskNo/status/inputTexts/inputHash/proofNonce/claimToken/outputDimensions=1024/proofExpiresAt/leaseExpiresAt/completableAt. Same key always addresses its original manifest; a completed old key returns status COMPLETED and is reconciled through receipt rather than recomputed. A normal device task lock returns common bindings plus `idle:true,retryAfterSeconds:60`, no task |
| POST heartbeat | `{}`. Refreshes only this live configured instance and its current validated manifest task (or idle state). Data adds heartbeatAt and activeTaskNo. It cannot overwrite another marker or abnormal/paused reason |
| POST tasks/{taskNo}/heartbeat | Exactly `{claimToken}`. Requires exact own live task/manifest/token; returns common bindings plus taskNo/heartbeatAt/activeTaskNo |
| POST tasks/{taskNo}/complete | Exactly specVersion/inputHash/modelDigest/resultArtifactBase64/resultHash/proofNonce/proofTimestamp/claimToken plus fixed Idempotency-Key. Validates immutable manifest and actual task/runtime, checks artifact shape/hash/finite/nonzero, independently runs the same pinned Ollama model, compares every value, then uses original atomic settlement. Data is the receipt projection below |
| GET tasks/{taskNo}/receipt | Read-only in effect, ordinary locking transaction (not a MySQL read-only transaction). Returns common bindings plus found/taskNo/inputHash/resultHash/receiptNo/status. found=true requires matching natural completed receipt, model/client/config/instance, stored artifact hash and domain proofHash. Allows owned-instance reconciliation after deferred deactivation; it does not claim, heartbeat, consume a nonce or settle |

Exact TEST texts, in this order:

1. `UVEL TEST: Cloud Share inference acceptance.`
2. `This text is a TEST workload, not a customer order.`
3. `多语言向量嵌入验收：仅用于TEST环境。`

`inputHash` is SHA256 of compact UTF-8 JSON serialization of this array, with Unicode unescaped and no separator whitespace. The server stores the exact JSON text and hash; input is never generated from nonce. Output artifact is the three vectors in text order, flattened 3×1024 IEEE754 little-endian float32 values (12288 bytes). resultHash is SHA256 of the decoded artifact; Base64 must be canonical. Each vector and independently computed vector must be finite and nonzero. Per-value difference must be at most `1e-5 + 1e-4*abs(expected)`; a correct hash alone cannot authorize settlement.

The manifest primary key is taskNo; unique executor+nextKeyHash retains the next request association. The result artifact/hash and complete key/body hash are saved in the same transaction as normal receipt/wallet/ledger/events/nonce/audit/outbox. Identical complete key/body retry returns the original receipt; changed key/body after a result is rejected. Ordinary production HMAC proof is unchanged and cannot complete these TEST tasks.

## Old task and recovery

An old task without a manifest is never given synthetic input/proof. Only the configured exact `cancel-unexecuted-task-no` may be cancelled under the existing locks, and only when its E2 config, BGE-M3/UVEL App metadata, unconsumed nonce, no completion, no receipt/ledger, no execution audit and no executor runtime marker all agree. Cancellation preserves its nonce and startedAt, records required audit and clears only that old task reference. The service then performs normal E2 claim in the same transaction; a later failure rolls back cancellation too. Any conflicting evidence blocks the operation. No purchase, activation, price or financial correction is issued.

Idle/new claims require fully eligible paid active ownership and pending_deactivate=0. A current manifest task can heartbeat/complete with pending deactivation, preserving the original graceful completion/deactivation transaction. Receipt lookup remains available after that deactivation if the configured owner still owns the same instance.

Worker journal saves intents before mutations. Unknown next or complete outcomes stop for reconciliation; no new request/body/timestamp is manufactured to bypass ambiguity. A missing/404/found=false receipt is not proof of no settlement. Only a full matching positive receipt confirms success. Preserve old UNKNOWN history. See [worker runbook](cloud-bge-worker.md) for the implementation's restart and idle behavior.

Revocation disables the channel or removes its credential through the loaded protected configuration. Keep nonsecret scope/binding/executor values until stale own runtime cleanup has completed. A committed receipt remains committed. Do not clear tasks, manifests, artifact, journal or ledger to restart a worker. Rollback stops this new worker, disables its channel and restores the previous backend artifact/config; retain the additive manifest table and evidence, and reconcile any unknown result before another mutation. Never start the old worker against the new instance.

## Verification

Local tests cover wrong semantic output with correct hash, malformed/NaN/zero output, token/profile/scope/forwarded boundaries, exact binding, old-task cancellation refusal, immutable input/model/nonce/token, result replay, stale heartbeat, public display mapping, model digest drift and real slow-body total deadline. Existing production proof, task settlement and old TEST-worker regressions remain required. Local tests do not prove deployed financial acceptance.

Root's TEST acceptance must connect actual pinned model/input/artifact to a new target task, independent backend recomputation, unique natural receipt, original frozen reward/ledger/events, lawful daily UVT rule and current App page for that device. Test request loss/restart/revocation and injected settlement/audit/outbox failures on isolated samples. Retain current service/config/artifact versions and the original cancelled/expired task history; a build or model health probe is not this acceptance.

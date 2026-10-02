# UVEL TEST compute worker v1

This runbook implements the frozen `UVEL_TEST_VECTOR_STATS_V1` contract for Bug 355.
It covers one short-lived, explicitly granted TEST job. It does not accept real
BGE-M3 execution, GPU attestation, commercial income or the whole product.
Local Python fixtures are protocol checks, not deployed settlement evidence.

## Root preflight and permission boundary

Only Root enables the TEST grant, operates the real service and accepts the
natural App receipt and balances. The worker reads no database, user session,
production proof key, phone credential or business configuration. It never buys
or activates an asset, alters a reward, selects the next task or loops as a daemon.

Before enabling, record the current host identity, deployed commit/artifact,
actual Spring profile and loaded TEST scope. A Git `test` branch, a Spring `test`
profile and a TEST host are separate facts. Keep `ProductionDeviceRuntimeGate`
and `ComputeTaskProofVerifier` intact. If the actual profile fails the old runtime
gate, report that fact; do not switch the whole stack's profile to bypass it.

Read the fresh authoritative task and owned paid SHARE/CLOUD_SHARE instance.
The grant binds one positive owner database ID, device ID, instance, task config
and exact task. A phone number is not an owner ID. PHONE/MOBILE and unpaid,
deleted, inactive, unactivated, reassigned, paused or abnormal devices are outside
this grant. Do not create a purchase, SQL fixture, manual credit or fake receipt
to prepare this run.

Root chooses one currently valid task with a fresh unconsumed nonce and lease,
or authorizes the server's existing claim transaction to create exactly one fresh
TEST task for the same eligible paid instance. The latter task ID is:

```text
CTA-TEST- + uppercase(first 32 hex of SHA256(UTF8(
  runId + LF + executorId + LF + ownerId + LF + deviceId + LF + instanceNo + LF
)))
```

The worker does not issue grants or generate task IDs. An expired old task cannot
be renewed by this protocol. The server retains the old E2/E3 eligibility, frozen
rewards, random nonce, lease, locks, audit, uniqueness and no-other-active-task
checks. One completed/expired grant cannot create another job.

## Safe configuration, default off

The namespace is `nexion.compute-task.test-worker`. These are flat properties:

| Property | Required loaded value or rule |
|---|---|
| `enabled` | Default `false`; enable only for the confirmed TEST host/run |
| `deployment-scope` | Default `DISABLED`; only exact `TEST` opens requests |
| `executor-id` | 3–32 safe ASCII characters, dedicated logical TEST executor |
| `credential-sha256` | SHA256 of the dedicated Bearer token's 32 decoded random bytes; supplied through Root's secure mechanism, never printed |
| `owner-id` / `device-id` | Positive database IDs from fresh authorized readback |
| `instance-no` | Exact owned paid instance, maximum 128 safe ASCII characters |
| `task-no` | Exact existing or server-determined TEST task, maximum 96 characters |
| `task-config-id` | Exact selected config, maximum 64 characters |
| `run-id` | Fresh ID beginning `TEST`, maximum 96 characters |
| `issued-at` / `expires-at` | Positive epoch milliseconds, `expires-at > issued-at`, lifetime at most 900000 ms |

String metadata uses `[A-Za-z0-9._:-]+`, without trimming, CR/LF or Unicode.
Both guards and the grant's validity are checked for each request and again in
the transaction. Wrong scope remains closed even when enabled. Disabled startup
does not need a credential or start a worker. Do not commit config or secret
values, pass them in process arguments, reuse USER/ADMIN/impersonation tokens,
or broaden this grant to future tasks. Server authentication compares the hash
in constant time and creates only `ROLE_TEST_COMPUTE_WORKER` in its own namespace.

The dedicated token format is `tw1_` plus canonical base64url, without padding,
of 32 cryptographically random bytes. Root supplies it only through a protected
environment variable `UVEL_TEST_WORKER_CREDENTIAL`, or types it at the worker's
hidden terminal prompt. The worker removes that environment variable after
reading it. The token and its hash are absent from argv, URLs, console output
and evidence. A valid token grants no ordinary `/api/**` identity.

Use a certificate-validated HTTPS origin. Root may instead approve an already
controlled tunnel bound to a literal loopback IP. The script accepts HTTP only
for literal loopback addresses such as `127.0.0.1` or `::1`; it rejects remote
cleartext HTTP, `localhost`/DNS aliases, URL credentials, query/fragment, base
paths, redirects and TLS-verification bypasses. It disables environment proxy
settings so the token is not sent through an incidental proxy. It does not
create a tunnel or change firewall/service configuration.

## Exact HTTP wire

All three operations are POST under
`/api/test/compute-workers/v1/tasks/{taskNo}/`, with Bearer authentication,
JSON body and `Idempotency-Key`. Success uses the existing envelope
`{code: 0, message: ..., data: ...}`. No owner, reward or verified flag is
accepted from a worker command body.

| Operation | Request and response contract |
|---|---|
| `claim` | Body `{}`. Key `TEST355-CLAIM-` + SHA256(UTF8(taskNo)) in 64 lower hex. Data: `executionKind`, `specVersion`, `runId`, `executorId`, `ownerId`, `deviceId`, `instanceNo`, `taskNo`, `taskConfigId`, `proofNonce`, `proofExpiresAt`, `leaseExpiresAt`, `completableAt`, `inputBytesBase64`, `inputHash`. No expected result. |
| `complete` | Body contains only `specVersion`, `inputHash`, `resultHash`, `resultArtifactBase64`, `proofNonce`, `proofTimestamp`. Timestamp is fixed at first send. Key `TEST355-COMPLETE-` + SHA256(exact saved body bytes). Data contains TEST kind/spec, all seven grant bindings, input/result hashes, `receiptNo` and nested existing `completion` view. |
| `release` | Body `{}`. Key `TEST355-RELEASE-` + SHA256(UTF8(taskNo)). Data: TEST `executionKind`, same `taskNo`, boolean `released`. `false` is a confirmed no-op/already-closed result, not permission to close another marker. |

`executionKind` must equal `TEST_DETERMINISTIC_V1`; `specVersion` must equal
`UVEL_TEST_VECTOR_STATS_V1`. All four time fields are JSON integer epoch
milliseconds, never local datetime strings. Nonzero business codes are rejected;
the worker does not log untrusted server messages or raw error responses.

Typical HTTP failures are 503 `TEST_COMPUTE_WORKER_DISABLED`, 401
`TEST_COMPUTE_WORKER_AUTH_INVALID`, 403 `TEST_COMPUTE_WORKER_BINDING_INVALID`,
400 `TEST_COMPUTE_BODY_INVALID`, 413 `TEST_COMPUTE_BODY_TOO_LARGE`, and 422
`TEST_COMPUTE_RESULT_INVALID`. Existing `TASK_ASSIGNMENT_*` guards remain active.
The controller limits actual complete body reads to 8192 bytes, rejecting unknown
or duplicate keys, trailing JSON, bad types and malformed Base64.

## Canonical computation and independent server verification

The server builds this eight-line ASCII seed, each line ending in exactly LF,
without BOM, CR, blank lines or leading zeroes in IDs:

```text
UVEL_TEST_VECTOR_STATS_INPUT_V1
owner=<ownerId>
device=<deviceId>
instance=<instanceNo>
task=<taskNo>
taskConfig=<taskConfigId>
nonce=<64 lower hex proofNonce>
count=32
```

For each decimal index 0 through 31, the server hashes
`seed || ASCII("index=" + index + LF)`, reads the first two bytes as unsigned
big-endian, and computes `value = (number % 2001) - 1000`. The input is the seed
followed by `values=<32 canonical comma-separated integers>` and LF, at most
2048 bytes. `inputHash` is its SHA256 in 64 lower hex.

The Python worker decodes the actual input, checks canonical Base64, full hash,
metadata binding, exact count and integer range, then actually sorts those 32
values and calculates their sum and sum of squares. It does not replace the
input with nonce-derived output or a timer receipt. The result bytes are:

```text
UVEL_TEST_VECTOR_STATS_RESULT_V1
input=<inputHash>
count=32
sum=<sum>
sumSquares=<sum of value * value>
sorted=<all 32 values in ascending order, canonical CSV>
```

Each line ends with LF, and the result is at most 1024 bytes. `resultHash` is
SHA256 of the full result bytes. The Java server independently reconstructs the
locked task input, sorts with `Arrays.sort`, performs long arithmetic and compares
the entire expected bytes. A correct uploaded hash of an incorrect sum or sort
still fails. The TEST domain proof digest binds run, executor, owner, device,
instance, task, config, nonce, spec and input/result hashes. It is an audit digest,
not a fabricated production signature or commercial remote attestation.

## One finite run and evidence

The command below is illustrative; this author has not run a real task. Root
supplies the approved origin, exact grant task and absolute new evidence leaf.
The leaf must not exist, and its parent must already exist. No secret belongs
in this command or a shell-history assignment.

```powershell
python scripts/test-compute-worker.py --base-url '<approved-TEST-origin>' --task-no '<exact-grant-task>' --output-dir '<absolute-new-evidence-directory>'
```

The run has a fixed 60-second monotonic budget, reserving the final 10 seconds
for own release. It refuses a `completableAt` wait over 60 seconds, insufficient
remaining run/proof/lease time, or a wall-clock jump during waiting. The bounded
budget can reject a nominally valid task that is too far from completion; Root
must choose another naturally eligible timing window, not reduce requiredSeconds.
Each HTTP request has an at-most-10-second socket deadline that also interrupts
a response which keeps dripping bytes. DNS resolution remains the operating
system resolver's responsibility; approve a healthy resolved origin or literal
tunnel endpoint before supplying the grant. The script adds no DNS workaround.

Each operation makes at most three total attempts for network/short-read/5xx
failures. All retries reuse the exact body bytes and key. Complete never changes
nonce, result, timestamp or reward on retry. 4xx or redirect responses stop that
operation immediately. No GET, unrelated endpoint, automatic next-task claim,
public file fetch or SQL is performed.

The new directory retains validated `claim.json`, actual `input.bin`, actual
`result.bin`, exact `complete-request.json`, `complete-key.txt`, validated
`complete-confirmation.json` when received, and `manifest.json`. Only expected
nonsecret response fields are retained; arbitrary messages and nested completion
fields are not copied. Input/result/request files are actual run bytes, never
an expected-result fixture. Every file is created exclusively and never overwrites
old evidence. Failure artifacts remain available.

| Manifest result | Meaning and next step |
|---|---|
| `CONFIRMED` | Received code 0, exact TEST kind/spec/bindings/hashes and a safe receipt number. Root still verifies the authoritative receipt, server audit and two currencies. |
| `REJECTED` | A definitive rejection without an earlier ambiguous complete attempt. No resubmission by the worker. Root checks the underlying guard/state. |
| `UNKNOWN` | A complete may have reached the server but confirmation was lost, truncated, redirected, malformed or inconsistent. Nonzero exit. Read the natural receipt/task/audit/wallet/ledger before any further submission; do not assert no credit, rollback or failure settlement. |
| `FAILED` | Local pre-complete validation or claim failure. Preserve evidence and inspect the fresh state. Claim response loss can still have left own TEST liveness; finally/TTL handle it. |

Finally, the worker calls release after any claim attempt, including a lost or
invalid claim response. Release failure is recorded separately and never hides
the original error or erases a confirmed receipt. Exit 0 requires both completion
and release confirmation. A failed release after `CONFIRMED` means that receipt
remains confirmed while liveness cleanup needs readback. Do not rerun the script
to generate a new complete timestamp/body after UNKNOWN; use its saved request
and Root's authoritative reconciliation.

## Liveness, settlement acceptance and revocation

The server's TEST marker identifies only this executor plus task. Claim can mark
an eligible OFFLINE paid instance online as an actual logical TEST process, but
cannot overwrite another active agent, ERROR/ABNORMAL/LOST, ownership, activation,
paused reason, datacenter state or unknown hardware telemetry. Completion
requires the same fresh marker. Complete closes it transactionally; release
only CAS-closes the same own marker and changes no task, nonce or financial fact.
The bounded internal TTL cleanup targets only this configured binding/marker;
it does not scan all users or settle anything.

The completed task displays `UVEL TEST vector statistics v1`, model
`TEST_DETERMINISTIC_V1` and client `UVEL TEST deterministic`, with original task
metadata and replacement recorded in required audit. Do not claim a BGE-M3 job
was executed simply because the old config ID or a PRODUCTION DTO remains.

Root's TEST acceptance must connect the actual worker bytes/hashes to independent
server recomputation, untruncated required audit/domainProofHash, receipt proof
hash, natural App task/receipt and authoritative USDT/NEX wallet, ledger and
events. The original frozen USDT reward and paid daily NEX snapshot remain the
only amount sources. Daily NEX is deduplicated by device and UTC day; zero NEX
can be a correct already-paid-day result and must not be forced to a nominal 3.
The canonical TEST database's existing `source_environment=PRODUCTION` label
does not turn this TEST evidence into commercial execution proof.

Verify valid execution plus incorrect semantic result with a recomputed result
hash, foreign binding, expired/revoked grant or lease, replay and injected
settlement failures on lawful isolated samples. Wrong output must produce no
receipt/credit/nonce consumption. Same-key identical retry must return one
receipt, and a fresh-key completed-task replay must not credit again. Java SQL
transaction tests and Root's actual deployed readback supply that evidence;
this local Python fixture cannot prove it. Keep the existing production-proof,
USER-role, runtime, audit/outbox and settlement regression gates.

Revocation is an operator sequence, not a dynamic management API:

1. Disable this grant or remove its credential hash using Root's approved
   configuration mechanism; preserve the nonsecret binding for cleanup.
2. Normally restart/load and verify the actual loaded revision. Confirm old
   authentication is rejected and the configured HTTP guard is closed. An edited
   but unloaded file is not revocation evidence.
3. Inflight transactions recheck scope/expiry before reward. A receipt committed
   while the grant was valid is retained; a lost response requires reconciliation.
   Record actual ordering rather than promise instant cancellation/rollback.
4. Wait for inflight requests to finish/stop and verify own TEST marker OFFLINE
   through release/complete/TTL. Only then remove the binding/scope and finish
   TEST mode. Do not clear someone else's marker or abnormal reason.

## Local verification command

```powershell
python -m unittest discover -s scripts -p test_test_compute_worker.py -v
```

The test server binds only `127.0.0.1` on an ephemeral port, limits requests and
body size, and suppresses HTTP logs. Its dedicated token is generated test data
and never a business credential. An independent seed generator and insertion
sort/arithmetic oracle verify actual worker output. Tests cover fixed retries,
truncated/lost responses, rejection, redirect/secret reflection, malformed input,
wait/lease limits, drip deadlines, release failure and evidence preservation.
To retain every local run's safe artifacts, set `UVEL_WORKER_TEST_ARTIFACT_ROOT`
to a new existing local evidence parent; the tests create unique children.
These tests do not start a real worker job, database or payment operation.

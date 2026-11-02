# TEST Compute V2

This protocol manages up to1,000 accounts and6,000 device slots. Six fixed runner threads
share the six existing loopback model servers; one independent validator uses the existing
11435 server. There is no distributed model pool, per-account JVM, model, timer or journal.
Two shared scheduler threads handle lifecycle/SQL checkpoints and bounded admission.
RocketMQ has32 partitions and32 fixed lightweight I/O pull threads; those threads do not
run inference. The Python worker retains six SQLite journals and one shared heartbeat.

## Current scope

The user selected efficiency before durable-message guarantees. Default
`strict-durability=false` accepts TEST's existing ASYNC_FLUSH and72h retention without
changing/restarting the shared broker. Task lifecycle events still use SEND_OK, fixed
business IDs and manual contiguous SQL offsets. Synchronous disk flush, seven-day
retention and forced-cleanup requirements are deferred. The system does not promise
zero loss of asynchronously flushed broker data after machine/storage failure.

Only `test-bge-m3-v1` with an installed BGE-M3 model and independent validator is eligible.
The six task classes remain configurable; absent executors do not enter the pool.
Two genuinely different BGE input configurations can be admitted via the explicit task-ID
allowlist. Each workload is an object containing exactly three nonempty `texts` entries,
maximum4,000 characters each, or `{}` for the existing TEST texts. No commercial orders
or other models are fabricated. Expected duration, actual duration and execution timeout
are separate. Independent verification currently uses the existing bounded180-second
HTTP deadline; it does not complete a task when a countdown expires.

## Publication and admission

1. Publish reviewed backend/PC/App source through their existing `test` Jenkins/native
   broker workflow. Backend forward migrations create the V2 policy/checkpoint/event/
   settlement/allocation tables and six-decimal E2 reward bounds. Verify runtime SHA,
   migration receipts and the existing rollback record. V2 defaults disabled.
2. Allocate TEST scratch through `uvel-test-workdir compute-v2-20261101` for disposable
   test sources/builds. Model files, journals, MySQL, MQ storage and result evidence remain
   on persistent storage. Do not run a second backend JVM beside the4GiB service.
3. Provision the environment-scoped32-read/32-write MQ topic. Reuse existing Redis and
   ACL configuration. Record the seven current model container IDs and loopback ports;
   they must use the approved image/model digest and remain within the host resource gate.
4. Review exact devices for a one-account canary. Every device needs current paid delivery,
   ownership, E3 eligibility and no unresolved V1 request or active V1 task. Keep old UNKNOWN
   journals/recovery budgets. Gracefully drain the old fleet before changing its exclusions;
   add enrolled devices to its exclusion list before enabling V2. Do not restart an excluded
   legacy UNKNOWN worker or delete any journal to obtain capacity.
5. Install this worker script/unit from the reviewed immutable source, verify hashes and
   `systemd-analyze verify`, then prepare private root-owned configuration. Append the
   V2 properties using `SPRING_APPLICATION_JSON` in the existing backend EnvironmentFile;
   merge existing JSON keys and preserve other Spring configuration. The TEST launcher
   supplies `spring.config.additional-location` on the command line, so changing its
   environment variable alone does not load another properties file.
   Use a generated32-byte hexadecimal proof secret, admitted IDs/config IDs, exact container
   IDs, `lease-seconds=45` and `execution-capacity=6`. Worker credentials use the existing
   systemd credential; the proof/observer secret is never given to the worker.
   DynamicUser exposes `/var/lib/uvel-compute-v2` as a symlink. The worker opens the
   actual `/var/lib/private/uvel-compute-v2` StateDirectory to retain its no-symlink
   directory check. Resource inventory and operator journal references use the original
   `/var/lib/uvel-compute-v2` alias and verify its exact private target.
6. Enable the backend protocol after these preconditions, then start the worker with
   `UVEL_COMPUTE_V2_ENABLED=true`. Check queued/compute/verify/complete/settle/next cycles
   through actual API, Redis, SQL and App/H5 pages. Scale the allowlist only after the canary.

Config/quote/input/version are frozen once. Repeated claims or completions reuse the same
task/generation. Completed devices enter a bounded priority queue for the next scheduler
tick instead of waiting for a full6,000-device scan. Busy/queued scans use Redis and do not
borrow SQL connections. Same-account windows use a fixed hash offset within60seconds;
transactions hold at most200 events and update a positive currency wallet once.

Pending earnings cannot be spent. SQL-posted allocations take precedence over Redis
pending summaries, including an unknown COMMIT response. Deterministic financial holds
display BLOCKED; transient database/network errors do not invent that state. Failed
account batches retain their original window and cannot advance past unpersisted MQ
offsets; other accounts/queues continue to settle. The original five-minute unpersisted
backlog admission stop and bounded memory capacity remain active.

## Exit, normal deactivation and recovery

A normal successful worker observes the inference response, waits for independent
verification and completion acknowledgement, then sends the idempotent EXITED request.
Only this releases the compute permit. A normal device-deactivation command records
pending intent; a queued task can be atomically cancelled, while running work finishes
before the device becomes inactive. Verified earnings can post after ordinary deactivation.
Replacement/unbind operations require the V2 generation to have drained.

Timeouts do not prove model inference exited. Interrupted inference stays in its original
journal as RECOVERY_REQUIRED and cannot automatically recompute or release a permit.
The root-only `compute-v2-observe-exit.py` accepts a private allowlist and the original
runner journal. It compares the backend manifest and verifies BOTH exact model containers
are physically stopped, PID0, with FinishedAt after task start and the expected loopback
port. It signs a bounded observation, retains sanitized evidence and submits it locally.
The tool never stops/removes a container, changes a journal or grants V1 replay authority.
Container stop/drain uses the existing reviewed host maintenance workflow.

The backend rejects observations for other containers/generations, stale signatures or
an active verification request. EXITED evidence is referenced in the lifecycle event.
After authoritative EXITED, the worker atomically archives its old state in SQLite recovery
history and returns to IDLE. A lost completion response can instead reconcile the exact
verified result and take the normal exit path without repeating inference.

For a rollback, stop new admission and drain the V2 worker before backend restart. Preserve
the enrolled-ID list until its generations have converged, and keep the V2 read/settlement
components available while posting outstanding batches. Re-enabling V1 on a still-active
V2 device is not a rollback. Source rollback does not undo committed MySQL migrations.

## Evidence

Record6,000-slot protocol/state load independently from six actual model executions.
Unit/fake-model tests do not prove real inference. Report two-hour execution and24-hour
soak only after observing them, with resource snapshots, OOM/restart counters, bounded
queues, settlement lag, duplicate-credit checks and actual page操作 evidence.

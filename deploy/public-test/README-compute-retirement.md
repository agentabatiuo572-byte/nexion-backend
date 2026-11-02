# TEST compute Python permanent retirement

Applied on TEST on 2026-11-03 after backend #451 publication, observed phone/four-IDC
SIM settlement and native task/receipt UI acceptance, and exact legacy closure.
`apply` exited 0. This is a host resource transition, not a SQL migration or a
replacement release entry. The 13-file trusted release closure, broker pins and
application publication remain unchanged.

## Applied TEST receipt

- Retirement ID: `b07f8aea51924a33a182362253ca0aa4`.
- Normal READY drain: `c5582f4afe0e45ebb9c65ea33a326fec`; the helper closed it through
  the original `resume-maintenance`, keeping workers stopped.
- Receipt: `/var/lib/uvel-task-control/compute-retirement.json`, raw SHA256
  `cf920ab9a76f42bfa72869c1f050febd11645daf918ba9d0d3517ab4e59c5767`.
- Preserved evidence: `/var/lib/uvel-task-control/retirements/b07f8aea51924a33a182362253ca0aa4/`.
- Committed budget: `26600275968` → `15032385536` bytes. The three compute workers
  and observation unit are permanently disabled by the owned receipt condition;
  original journals have read-only archived copies and remain preserved.
- All nine old model containers have restart `no`; the three previously running
  models stopped at UTC `17:33:16`, before the four observed IDC settlements were
  due. Independent `nexgrid-ollama` (11434) and RAG (8010) continue unchanged.
- Backend PID 3626775: SIM true, V2 false, test-cloud false. Exact checks returned
  legacyUnresolved 0, v2Unexited 0, verifiedAllocationInvalid 0, and one match for
  each of the four protected zero-credit cases. Old MQ/Redis replay data remain
  preserved and disabled; no key clearing or offset skipping was used.

The installed files match these reviewed Linux LF sources:

| Installed file | SHA256 |
|---|---|
| `/opt/uvel-task-control/resource-control.py` | `5b8696ae68acded46449521ed92189caa107c345623f424391a1d4edf4a44cdd` |
| `/opt/uvel-task-control/compute-v2-ci-maintenance.py` | `8a7d289bdad6de8165df4661b1874a8896629c3e41a26bfb2500485840211e83` |
| `/opt/uvel-task-control/compute-retirement.py` | `446a7d98f0ab31f39fb113543abf44cd8f34cce84dcd6f93ff7dfa9ab22b0aa5` |

This records the completed transition. The one-time procedure below describes its
original MODEL-host preconditions; a retired host must retain this receipt and
must not re-enter a former model budget. The remaining product acceptance limits
include support-funds aggregation 503, unrun 1000-user load, other model/type
combinations and a full public one-hour background window.

`compute-retirement.py` has a read-only `plan` and a forward-only `apply`. It never
starts workers/models, changes a journal, replays a request, writes business SQL,
deletes model data, or fabricates a terminal build. Original Python units become
disabled and receive an owned permanent condition preventing execution while the
retirement receipt exists. Containers must already be stopped with restart `no`.
Their configuration/image/mount identities and the journals remain preserved.

## Required real evidence

- The normal resource controller's exact READY drain ID, or its matching
  `last-drain-receipt.json` with `workersKeptStopped=true`. All three old workers
  and the V2 observation unit must be loaded, inactive and MainPID zero. Release
  timer/service must retain their original inactive state. An active READY is
  closed only through the original `resume-maintenance` code, before any budget
  or protection change. The tool never edits an active drain receipt.
- The raw current budget SHA256, all current model controls plus stopped controls,
  their real container IDs and stopped/restart states. Every model being removed
  must be exclusively owned by this compute flow. Review other consumers,
  compose/boot automation and configuration before producing the reference review
  below; the tool verifies the reviewed file hashes and compose references, not a
  claim that it can discover every consumer on a host.
- The running `nexgrid-backend.service` process must have explicit JSON booleans in
  `SPRING_APPLICATION_JSON`: `nexion.simulated-tasks.enabled=true`,
  `nexion.compute-v2.enabled=false`,
  `nexion.compute-task.test-cloud.enabled=false`. Both flat and nested JSON work.
  Conflicting command-line switches/JSON are rejected. Config file edits without
  the corresponding real service process do not satisfy this check.
- All Jenkins jobs/builds and build reservations must be terminal and the same
  CI container idle. CI limits/build admission settings are preserved, not
  silently increased by retiring models. A later CI budget exchange needs its
  own reviewed path; old model-profile `restore` is permanently blocked.
- Read-only business SQL must find zero unresolved legacy tasks/requests, zero
  V2 generations lacking physical EXITED (except FAILED-before-STARTED), and
  exact USDT/UVT allocations with matching settlement identity for every VERIFIED
  event. The four approved UNKNOWN_COMPLETE originals each require the complete
  `LegacyZeroCreditResolutionSql.RESOLVED_REQUEST` predicate and the original
  fixed owner/device/task/generation. Cancellation alone is insufficient. Their
  SQL/journal states are not rewritten to appear idle.

## Source pins and input keys

The applied helper checked these pre-transition installed legacy files before any mutation:

| Pre-transition installed file | Required original SHA256 |
|---|---|
| `/opt/uvel-task-control/resource-control.py` | `cb756f5f3c47ed887957e1209a44f061b52e6c4541998cddcd107b82ebb6bf4e` |
| `/opt/uvel-task-control/compute-v2-ci-maintenance.py` | `8dce191be8f39a6c0cdd5789d95f9e6806dc48e3168ff19fff81945c3bbf5455` |

An actual different host hash is a closed gate: first inspect that real source
and independently review a new pin. Do not replace it just to obtain a known
hash. All tool/source/review paths and ancestors must be root-owned and not
writable by other users. Install the reviewed helper separately outside the
trusted release closure. Stage the new resource/CI candidates root-owned; the
helper pins their exact source hashes internally.

The exact SQL predicate source is the approved
`src/main/java/ffdd/opsconsole/device/mapper/LegacyZeroCreditResolutionSql.java`,
SHA256 `accd861ba2a2c1a7d2038a096cc263e7599bac7c08723b55adc0780f4bb4733f`.
Its two text blocks and case-set SHA are read as data, never imported/executed.
Database access uses existing `nexgrid-mysql`, schema `nexion`, and the container's
`MYSQL_ROOT_PASSWORD` key passed internally as `MYSQL_PWD`. No secret values or
raw SQL/journal content are printed.

Produce a private root-owned JSON reference review, pin its raw SHA256, and keep
all real references in it (replace examples; do not copy placeholder claims):

```json
{
  "version": 1,
  "scope": "TEST",
  "operator": "reviewed-root-operator",
  "exclusiveToCompute": true,
  "modelNames": ["EXACT_MODEL_NAMES_FROM_CURRENT_BUDGET_AND_STOPPED_CONTROLS"],
  "consumerUnits": [
    "nexgrid-backend.service",
    "uvel-cloud-bge-worker.service",
    "uvel-cloud-bge-fleet.service",
    "uvel-compute-v2-worker.service",
    "uvel-compute-v2-observation.service"
  ],
  "reviewedFiles": [
    {"path": "/ROOT_OWNED_ACTUAL_COMPOSE_OR_SERVICE_CONFIG", "sha256": "ACTUAL_REVIEWED_FILE_SHA256"}
  ]
}
```

Keep source candidates and reference evidence in a persistent protected operator
location, not a directory scheduled for scratch cleanup. Generate a fresh 32-hex
retirement ID; use the actual normal 32-hex drain ID and budget/review hashes:

```sh
sudo env UVEL_COMPUTE_RETIREMENT_SCOPE=TEST /usr/bin/python3 -I -B /opt/uvel-task-control/compute-retirement.py plan \
  --retirement-id RETIREMENT_ID --drain-id EXACT_DRAIN_ID --budget-sha256 ACTUAL_BUDGET_SHA256 \
  --reference-review /ROOT_OWNED/reference-review.json --reference-review-sha256 REVIEW_SHA256 \
  --resource-candidate /ROOT_OWNED/compute-v2-resource-control.py \
  --ci-candidate /ROOT_OWNED/compute-v2-ci-maintenance.py \
  --closure-source /ROOT_OWNED/LegacyZeroCreditResolutionSql.java
```

Review that concrete plan, then repeat the exact arguments with `apply`. Neither
command stops models to make its own preconditions true. Stop only the reviewed
exclusive containers through normal Docker controls before planning; keep IDs,
images, mounts and configuration for evidence.

## Transition and evidence

Apply reuses the existing whole-operation lock, then the normal release-lock →
resource-control-lock order for short mutations. It closes an active READY with
the original controller, archives the original budget/controller/CI source,
matching closed drain and reference review, and makes read-only SQLite backups
whose logical fingerprints must match the originals. Archives are root-private,
files read-only, with SHA256 recorded in the retirement receipt. Nothing deletes
or moves original journals/recovery budgets.

It creates its own `HALTED.json` hold only after validating no foreign release,
migration or maintenance hold; installs the four owned
`90-compute-retired.conf` conditions and disables those units; rechecks stopped
identities, SQL closure and CI state; then installs the reviewed resource/CI
sources, immutable `/var/lib/uvel-task-control/compute-retirement.json`, and the
sealed zero-model budget. Backend/MySQL/other commitments, CI cap and the actual
20% MemAvailable check remain in force. Only `kind=model` and the exact retired
worker/observation controls leave commitments; the active worker/protection lists
become empty, with their original evidence retained in the archive.

The resource controller accepts `executionProfile=RETIRED` only with zero model
controls, `maxActiveExecutors=0`, empty active worker/protection/stopped lists and
the exact receipt seal. Normal MODEL profiles retain their previous rules. A
retirement receipt prevents loading a MODEL budget, and both the old-profile CI
exchange and a legacy drain worker restart are rejected. Future releases can
drain/resume the empty retired worker set without resurrecting Python.

Success requires a fresh valid resource snapshot, unchanged stopped workers and
release units. Only then is this tool's exact hold removed. Any later failure
retains the owned hold/receipt/archive; there is no automatic rollback, restore,
receipt deletion or model restart. Do not retry by deleting markers or swapping
an old controller/budget back in: preserve the failure and independently review
the actual partial state for a forward recovery.

The host transition above has executed successfully, in addition to the earlier
static Python compilation and independent source review. No unit or independent
integration suites were run. Preserve the actual source/budget/review/drain/receipt
hashes and resource evidence; the completed retirement does not expand the scope
of product acceptance beyond the observed business flows.

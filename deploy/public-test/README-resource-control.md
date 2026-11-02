# TEST resource admission and release drain

`scripts/ops/resource_control.py` is an independently reviewed, SHA-pinned host
operator tool, installed root-owned at `/opt/uvel-task-control/resource-control.py`.
It neither imports nor modifies the broker's trusted closure. The existing
`python3 -I /srv/jenkins/release/trusted_entry.py broker ...` remains the only
release entry. No token, password, database credential or raw journal state is
needed by this tool.

## Permanent retirement applied on TEST

The explicit `RETIRED` zero-model profile was applied successfully on 2026-11-03
through the independently reviewed [compute-retirement operator flow](README-compute-retirement.md).
Retirement ID `b07f8aea51924a33a182362253ca0aa4` closed normal READY drain
`c5582f4afe0e45ebb9c65ea33a326fec` through the original `resume-maintenance`.
The sealed receipt is `/var/lib/uvel-task-control/compute-retirement.json`, SHA256
`cf920ab9a76f42bfa72869c1f050febd11645daf918ba9d0d3517ab4e59c5767`;
original budget/source/journal evidence is preserved under
`/var/lib/uvel-task-control/retirements/b07f8aea51924a33a182362253ca0aa4/`.
Committed bytes changed from `26600275968` to `15032385536`; the three compute
workers and observation unit are permanently condition-disabled, nine old model
containers have restart `no`, and the original journals remain read-only archived.
Independent ollama 11434 and RAG 8010 were not changed.

The installed controller SHA256 is
`5b8696ae68acded46449521ed92189caa107c345623f424391a1d4edf4a44cdd`.
The retired budget has maxActiveExecutors 0 and empty active worker/protection/
stopped lists, bound to the exact preserved receipt. Existing MODEL profiles below
retain their historical rules; they are not the current host budget and cannot be
restored while the permanent receipt exists.

## Budget and real entry points

Root must collect the current `/proc/meminfo`, all runnable persistent model containers,
backend/other service limits, CI container limits and full worker journal inventory.
Store the reviewed budget in root-owned `/etc/uvel-task-control/budget.json`:

```json
{
  "version": 1,
  "scope": "TEST",
  "maxActiveExecutors": 6,
  "buildsEnabled": false,
  "buildBudgetBytes": 1073741824,
  "buildControl": {"type": "docker", "name": "nexgrid-ci-agent"},
  "buildRequests": {"backend": 1073741824, "pc": 1073741824, "uniapp": 1073741824},
  "stoppedControls": [{"type": "docker", "name": "uvel-cloud-bge-model-fleet-7"}],
  "commitments": [
    {"name": "backend", "kind": "backend", "bytes": 4294967296,
     "control": {"type": "systemd", "name": "nexgrid-backend.service"}},
    {"name": "each-reviewed-model", "kind": "model", "bytes": 1610612736,
     "control": {"type": "docker", "name": "REPLACE_WITH_REAL_CONTAINER"}},
    {"name": "business-mysql", "kind": "database", "bytes": 3758096384,
     "control": {"type": "docker", "name": "nexgrid-mysql"}},
    {"name": "all-other-host-work", "kind": "other", "bytes": 5905580032}
  ],
  "workerServices": [
    {"name": "uvel-cloud-bge-fleet.service", "journalRoots": ["/var/lib/uvel-cloud-bge-fleet/instances"]},
    {"name": "uvel-cloud-bge-worker.service", "journalRoots": ["/var/lib/uvel-cloud-bge-worker"]}
  ],
  "protectedJournals": [],
  "drainTimeoutSeconds": 300
}
```

This is an **incomplete schema example**, not an approved host budget. Expand one
`model` entry per runnable persistent model container, including verification-only
models, and list every other workload's reserved budget. Never lower a live
model limit from this example: first measure cold-start and inference cgroup
memory, including anon, file cache and temporary workspaces, and leave required
overhead. `maxActiveExecutors` is the global task-runner capacity across all owners
and worker services, excluding runners used only for validation. It is distinct
from fleet parallelism. The measured runtime topology has six distinct fleet
endpoints (11436–11441) plus one base runner (11435) used only for backend
validation: seven runnable model containers, fleet parallelism six and global
maxActiveExecutors six. The base validator is not a seventh task slot. The actual
cohort is 1204/1208/1209/1210/1212/1214; 1206 is deactivated and single1202 is
SHARE/DEACTIVATED. Any future net addition, including 1202 if it becomes eligible,
still needs room within the global six-slot bound. Existing activated instances remain untouched;
historical activated fixtures above the global bound close only new activation.
The example is intentionally rejected because it has fewer models than executors.

The revised runtime envelope is `7*1.5 + 4 backend + 3.5 MySQL + 1 idle CI +
5.5 other = 24.5 GiB`, below 80% of the measured approximately 30.974 GiB host.
The backend uses a 3 GiB heap within its 4 GiB service cap. The previous
2 GiB/Xmx1280m backend repeatedly exhausted its heap; that budget did not pass.
These revised limits passed the specific continuous workload recorded below;
they do not guarantee larger inputs or longer workloads will fit.
Real MemAvailable must independently remain above the 20% reserve.

Runtime config sets `buildsEnabled: false`. The optional field defaults to true
for compatibility and accepts only JSON booleans. False rejects heavy-build
admission before reading or writing reservations. It does not disable activation
or execution by itself. The CI entry also requires an inherited cgroup allowance
of at least 3 GiB, so a direct Jenkins trigger cannot start a heavy build in the
1 GiB idle envelope. Maven and its test fork each use at most a 768 MiB heap.

Heavy builds require an operator-coordinated maintenance budget. First drain
workers and verify their complete stopped journal inventory, then stop only the
three confirmed idle fleet runners 4–6 while preserving their containers and
configuration. Four remaining model runners, backend4, MySQL3.5, CI3 and other5.5
reserve 22 GiB: three fleet task runners and one base validator, so maintenance
maxActiveExecutors is three. A separately reserved 1.75 GiB may cover an isolated
test database and test JVM, for 23.75 GiB total. In a separate phase, after
releasing that isolated-test reservation, a temporary 2.5 GiB frontend-switch
commitment brought maintenance to 24.5 GiB. These reservations were replacements,
not cumulative, and both have now been removed.
All previously activated devices remain paused only for the release; the smaller
maintenance snapshot does not revoke their activation. After build/test processes
terminate, pause CI and recheck terminal build metadata, reservations and actual
memory before lowering it to 1 GiB. Only then restart the three runners, restore
seven model containers with maxActiveExecutors six, and disable heavy builds before
resuming workers. Never use this maintenance budget as a six-slot runtime claim.

The backend CI435/source `07731861`, PC277 and App356 have been deployed to TEST.
All 154 real SQL cases (8+111+35) passed with zero skips. Each of the original five
requests has one completion receipt and the current six devices continue cycling.
The final 3601.08807-second observer window, resource replay, unique-settlement
audit and App/H5 page acceptance are complete as recorded below. Deployment and
SQL results are separate from this continuous workload evidence.

The eighth model container, `uvel-cloud-bge-model-fleet-7`, may be omitted from
runtime commitments only after root proves no current lease/inference, gracefully
drains workers, tests the spare as needed, then stops that exact container while
preserving its image/configuration/model mount. Do not infer a permanent device-to-
port assignment from device 1380's exclusion: runner leasing uses the endpoint pool.
Remove 11442 from the live fleet endpoint list while retaining all six real task
lanes. Preserve the current excluded-device set and append 1380 without dropping
existing exclusions; protected journals are not recovery/replay permission.

`stoppedControls` is optional. It accepts the old spare-only set above, or the
complete known set `uvel-cloud-bge-model-fleet-2` through `-7` for the modal
replacement. Partial replacement sets, duplicates, unknown containers and
overlap with committed controls are rejected. Every snapshot and build admission
checks Docker Running false, PID zero and Paused false. The old spare-only set
retains restart-policy `no` or `unless-stopped`; the complete modal replacement
set requires `no` for all six containers. Missing containers, running state,
unsupported policies or inspection failure close new activation/build admission.
Do not leave compose/boot automation able to start the unbudgeted spare. Record its
original restart/configuration for rollback; before intentionally restarting it,
approve a new budget that reserves its complete cap and update the fleet topology.

The explicit modal runtime profile reserves only base validator 1.5 GiB,
fleet-1 1.5 GiB and `uvel-compute-modal-runtime` 7.5 GiB as model controls. These
exact hard caps and all six stopped controls permit `maxActiveExecutors=6` logical
slots while Java/Lua and the child service share the bounded model work pool.
There is no arbitrary multiplier for other model containers. Total model
commitment remains 10.5 GiB; the modal cap includes a 6 GiB work pool plus
1.5 GiB framework overhead. The corresponding two-profile CI exchange and its
same-ID restore are described in `README-compute-v2-ci-maintenance.md`.

With physical memory `P`, reserve `R=ceil(P/5)`, nonbuild commitments `C`, and the
CI cap `B`, admission requires `C+B <= P-R`. Every runnable model's full cap is
committed, whether currently idle or running; an activation cannot spend it twice.
The backend, every model and the CI agent must have actual matching hard limits.
Docker `MemorySwap` must equal `Memory`, preventing swap from enlarging its budget.
Unmanaged services need a conservative reserved commitment that root verifies.
The tool reads real `MemAvailable`, never a supplied or fabricated value.

Install the approved limits independently, retaining previous values for rollback:

```sh
# Use root-approved integer BYTES values; preserve backend Java/pool settings.
systemctl set-property nexgrid-backend.service MemoryMax=BACKEND_BYTES
docker update --memory MODEL_BYTES --memory-swap MODEL_BYTES MODEL_CONTAINER
docker update --memory CI_BYTES --memory-swap CI_BYTES nexgrid-ci-agent
```

Keep each model container, image, CPU limit and model mount intact, including the
explicitly stopped spare. These are
persistent services; never add ephemeral cleanup labels. The CI hard cap protects
business memory even for builds triggered from the normal Jenkins UI. Install the
same CI cap in the existing compose configuration before its next recreation;
otherwise snapshot validation closes activation admission on drift. CI carries no
host socket or deployment capability.

The root build entry runs the existing protected trigger-and-wait provider under
the budget wrapper, using the job's actual next build number. The provider must
trigger and wait for that exact build to reach a terminal state, keeping its
credentials internal. Its executable must be root-owned with trusted ancestors.
The wrapper suppresses provider output and does not trigger if admission fails:

```sh
sudo /usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py run-build backend BUILD_NUMBER -- /ROOT_OWNED_TRIGGER_AND_WAIT_PROVIDER backend BUILD_NUMBER
```

`admit-build backend BUILD_NUMBER` is also available when the existing protected
provider is a separately controlled interactive channel; its caller must preserve
the same reserve-before-trigger and exact-terminal-wait ordering.

Reservations share one `flock` and persist in
`/var/lib/uvel-task-control/build-reservations.json`. Concurrent requests require
`sum(outstanding)+request <= B` and `MemAvailable-sum(outstanding)-request >= R`.
This pessimistically counts resident builds again, preserving headroom rather than
overselling unspent commitments. Only a matching completed `build.xml` releases a
reservation. A failed trigger leaves its reservation held for operator inspection;
there is no TTL or automatic cancellation of a possibly queued/running build.
Direct UI builds bypass the wrapper's pre-trigger reservation but still face the
fixed host 20% memory gate, inherited cgroup allowance check and fully reserved CI
hard cap. The 1 GiB runtime envelope rejects heavy work before compilation. Use
the reviewed maintenance entry for builds in this task. A trigger process exiting
never releases a reservation without terminal Jenkins metadata.

Install `resource-control-snapshot.service` and `.timer` under
`/etc/systemd/system`, then enable the timer. It publishes root-owned,
world-readable `/run/uvel-task-control/capacity.json` every five seconds, with a
15-second expiry, current limit verification and running build reservations.
Missing/expired snapshots, limit drift, pressure and draining close **new**
activation admission. Java must check this file only for a new activation under
its global activation mutex; existing activations, tasks, heartbeats and completion
keep their allocated resources. The snapshot is not a replacement for that mutex
or the global active-instance count.

## Exact protected ambiguity and release drain

Run `inventory` to obtain each journal's path, phase, device identity and hashes.
It hashes state, calls and all journal tables, including original recovery budgets,
but prints no raw state, input, claim token or permit. Empty `IDLE` journals without
a binding remain in inventory. Only the two systemd DynamicUser state-root links
`/var/lib/uvel-cloud-bge-worker` and `/var/lib/uvel-cloud-bge-fleet` may resolve to
their exact same-name directories under the root-owned `/var/lib/private`;
arbitrary symlinks and substituted state targets are rejected. Root may copy an exact reviewed entry into
`protectedJournals`, adding `allowedPhases`, for example `["UNKNOWN_COMPLETE"]`.
Each entry requires `path`, `deviceId`, `bindingSha256`, `stateSha256`,
`callsSha256`, `contentSha256` and `allowedPhases`. Only the backed-up originals
explicitly adjudicated by root belong there. This permits deployment with durable
original evidence; it never grants recovery, replay, expiry or budget consumption.
No device, including 1152 or 1202, is implicitly exempt.

Install `resource-control-release.conf` as
`/etc/systemd/system/nexgrid-release.service.d/60-task-drain.conf`, then
`systemctl daemon-reload`. The actual broker service's `ExecStartPre` checks the
latest backend build. When a new SUCCESS backend artifact is pending, it:

1. Verifies exact protected hashes and records which workers are currently active.
2. Creates and fsyncs the persistent root-owned drain marker, then persists the drain receipt.
3. Waits for unprotected journals to reach `IDLE` or `NEXT_NOT_SENT`, allowing the
   live workers to finish inference/complete/status while new NEXT is blocked.
4. Sends only SIGTERM to active worker mains, waits for natural complete process
   exit, then rereads every journal. It never uses SIGKILL or edits a journal.
5. Requires every protected object to remain exact and every other object to be
   idle/unsent before the trusted broker executes. Every initial journal path must remain present during every poll and after process exit; new journals are also checked.

Any new UNKNOWN, changed protected hash, missing journal, process that will not
exit, corrupt journal or timeout prevents broker execution and leaves the marker
and durable receipt held for review. The worker implementation must check the
same persistent `/var/lib/uvel-task-control/drain.json` marker and presence of
`drain-receipt.json` immediately before new NEXT intent and send, while permitting
in-flight completion and reconciliation. The state directory is root-owned 0755,
the marker is 0644 and private receipts remain 0600. Update any explicit old
`UVEL_CLOUD_DRAIN_FILE=/run/...` environment setting during installation. A host
reboot cannot reopen claims while either persistent file remains; the activation
snapshot also treats either file as draining.

After a successful broker service invocation, `ExecStartPost` verifies unchanged
stopped journals, restarts only workers that were previously active, and removes
the marker. A broker failure leaves claims held. For explicit operator deployment,
use the exact same sequence (and restore only after successful trusted deployment):

```sh
sudo /usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py inventory
sudo /usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py drain
sudo /usr/bin/python3 -I /srv/jenkins/release/trusted_entry.py broker deploy backend BUILD_NUMBER
sudo /usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py resume-release
```

No command above updates trusted pins. Before installation, independently verify
the reviewed tool and unit hashes and preserve the existing files/limits. On
rollback, hold the release timer, retain drain/protected evidence, restore the
previous known resource limits and drop-ins, daemon-reload, and resume only the
workers documented by a verified receipt. Removing the guardian while its Java
activation gate is enabled closes new activation admission when the snapshot
expires. Never delete a held marker to force a release.

Local validation (does not access live services):
`python -m unittest scripts.ops.test_resource_control -v`.

## TEST acceptance snapshot, 2026-11-01

Backend 435 (`07731861772d90ed88f345192f629e46efcaf0d5`) completed the
six-slot observation from 2026-10-31T22:38:35.498459Z to
23:38:36.586529Z (3601.08807 seconds). All 3602 expected samples were complete;
maximum complete gap was 1.433902 seconds and collection took at most 0.498274
seconds. Observed identities were unchanged and all observed OOM increments were
zero. Minimum host MemAvailable was 9,461,432,320 bytes (28.4485% of physical
memory), above the 20% reserve. Independent resource replay matched the analysis.

The financial snapshot, read at 2026-11-01T00:10:01Z, verified 2609 settlements
inside the same observation window,
zero duplicate or mismatched task chains and no unresolved request states. Every
device had completions in all twelve full five-minute buckets. Observed throughput
was 0.724503 tasks/second; this is a lower bound for this short-text workload,
not measured maximum database TPS. Fleet runner overlap reached six distinct
devices and ports for 42.910421 seconds. Original-five recovery and existing
protected journals/budgets were verified separately and again in the final read.

Local limits remain material: backend sampled current reached within 50.664 MiB
of its 4 GiB cap; MySQL reached within 0.25 MiB of its 3.5 GiB cap, with 1270
additional memory.events.max counts. These are reclaim/limit pressure, not OOM.
The hour does not exclude longer-term leaks. Twenty-four notification-list
deadlock victims were recorded separately as issue 702 (active); they are not
120 incidents despite 120 matching transaction-keyword lines. No connection
acquisition timeout, connection exception class mention, Hikari or OOM match was
observed in the same 11995 backend MESSAGE rows. This is not a claim of an
error-free whole application.

Durable originals are under
`/srv/nexgrid/backups/task-stability-20261031/`: `sixty-minute-final1/`,
`six-60m-financial-ci435-final3/`, `independent-sixty-minute-review-001/` and
`original-five-financial-ci435-2/`. The final financial evidence digest is
`742ad9506802c8f788acd8a2bb4bfcf18efb95e7f1dff299c9f7878c678436b8`.
The original read-query timeout attempts are retained; optimized audit queries
kept the 15-second statement cap, 60-second client cap and original assertions.
These audit-only changes did not restart or change the application or window.
Independent review read the full financial original and recomputed all 2620
related chains, including 2609 in-window settlements, the original five and six
tasks created inside but completed outside the window. Only the first group
counts toward throughput. A zero inflightTail at snapshot time does not assert
zero in-flight work at the window boundary. All 37 protection checks passed a fresh read-only
comparison. Independent financial summary SHA256:
`3b872ef70a0721e365ba81c238316383b35a9bdb4ab6db8e1848150689434d9e`.
Issue 701 was closed/fixed and read back at 2026-11-01T00:16:35Z; 702 remains active.

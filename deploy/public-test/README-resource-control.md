# TEST resource admission and release drain

`scripts/ops/resource_control.py` is an independently reviewed, SHA-pinned host
operator tool, installed root-owned at `/opt/uvel-task-control/resource-control.py`.
It neither imports nor modifies the broker's trusted closure. The existing
`python3 -I /srv/jenkins/release/trusted_entry.py broker ...` remains the only
release entry. No token, password, database credential or raw journal state is
needed by this tool.

## Budget and real entry points

Root must collect the current `/proc/meminfo`, all runnable persistent model containers,
backend/other service limits, CI container limits and full worker journal inventory.
Store the reviewed budget in root-owned `/etc/uvel-task-control/budget.json`:

```json
{
  "version": 1,
  "scope": "TEST",
  "maxActiveExecutors": 7,
  "buildBudgetBytes": 3221225472,
  "buildControl": {"type": "docker", "name": "nexgrid-ci-agent"},
  "buildRequests": {"backend": 3221225472, "pc": 3221225472, "uniapp": 3221225472},
  "stoppedControls": [{"type": "docker", "name": "uvel-cloud-bge-model-fleet-7"}],
  "commitments": [
    {"name": "backend", "kind": "backend", "bytes": 2684354560,
     "control": {"type": "systemd", "name": "nexgrid-backend.service"}},
    {"name": "each-reviewed-model", "kind": "model", "bytes": 1610612736,
     "control": {"type": "docker", "name": "REPLACE_WITH_REAL_CONTAINER"}},
    {"name": "business-mysql", "kind": "database", "bytes": 3489660928,
     "control": {"type": "docker", "name": "nexgrid-mysql"}},
    {"name": "all-other-host-work", "kind": "other", "bytes": 5368709120}
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
from fleet parallelism. The reviewed candidate topology is six distinct fleet
endpoints (11436–11441) plus one base runner (11435) used by single worker 1202 and
backend validation: seven runnable model containers, fleet parallelism six and
global maxActiveExecutors seven. Existing activated instances remain untouched;
historical activated fixtures above the global bound close only new activation.
The example is intentionally rejected because it has fewer models than executors.

The candidate envelope is `7*1.5 + 2.5 backend + 3.25 MySQL + 3 CI + 5 other =
24.25 GiB`, below 80% of the measured approximately 30.974 GiB host by approximately
0.529 GiB. These are **conditional trial budgets**, not verified peak requirements.
The backend 2.5 GiB cap requires a measured suitable heap/native budget, MySQL
3.25 GiB requires actual buffer-pool/other-memory validation, and the 5 GiB other
envelope must cover measured essential-service/kernel peaks. The 3 GiB CI cap must
pass the existing full build with Maven/test forks and agent overhead. Real
MemAvailable must independently remain above the 20% reserve.

The eighth model container, `uvel-cloud-bge-model-fleet-7`, may be omitted from
runtime commitments only after root proves no current lease/inference, gracefully
drains workers, tests the spare as needed, then stops that exact container while
preserving its image/configuration/model mount. Do not infer a permanent device-to-
port assignment from device 1380's exclusion: runner leasing uses the endpoint pool.
Remove 11442 from the live fleet endpoint list while retaining all six real task
lanes. Preserve the current excluded-device set and append 1380 without dropping
existing exclusions; protected journals are not recovery/replay permission.

`stoppedControls` is optional and accepts only the exact known spare above. Every
snapshot and build admission checks Docker State.Running is false and its actual
restart policy is `no` or `unless-stopped`. Missing containers, running state,
unsupported policies or inspection failure close new activation/build admission.
Do not leave compose/boot automation able to start the unbudgeted spare. Record its
original restart/configuration for rollback; before intentionally restarting it,
approve a new budget that reserves its complete cap and update the fleet topology.

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
Direct UI builds bypass this pre-trigger refusal but remain inside the fully
reserved CI hard cap; use the wrapper entry for all builds in this task. A trigger
process exiting never releases a reservation without terminal Jenkins metadata.

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

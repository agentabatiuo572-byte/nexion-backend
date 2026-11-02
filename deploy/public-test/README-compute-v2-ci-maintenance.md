# Compute V2 CI maintenance budget exchange

This independent TEST operator tool is outside the 13 trusted release files.
The reviewed retirement version is installed on TEST as of 2026-11-03. The host is
now permanently RETIRED: former MODEL plan/enter/restore operations are blocked by
`/var/lib/uvel-task-control/compute-retirement.json`, SHA256
`cf920ab9a76f42bfa72869c1f050febd11645daf918ba9d0d3517ab4e59c5767`.
Retirement ID `b07f8aea51924a33a182362253ca0aa4` applied successfully, committed
budget changed from `26600275968` to `15032385536` bytes, and the original journals
remain preserved. See the [applied retirement evidence](README-compute-retirement.md).
The MODEL exchange procedure below is retained for historical receipts and source
review; it is not the current retired-host CI budget path.

Before installation, verify the helper and resource candidate hashes independently.
Preserve the root-owned pre-V2 resource source as
`/opt/uvel-task-control/resource-control.pre-compute-v2.py` (or pass its reviewed
absolute path with `--baseline-resource`). Its required SHA256 is
`1b66c15b0b03c8ee17e9a46219027e9f3030f963beac448bab4bc3573e0f52b8`.
The installed resource controller must match SHA256
`5b8696ae68acded46449521ed92189caa107c345623f424391a1d4edf4a44cdd`.
The installed permanent-retirement version refuses every plan/enter/restore while
`/var/lib/uvel-task-control/compute-retirement.json` exists; a zero-model host must
not restore a former model profile. The controlled [retirement flow](README-compute-retirement.md)
checked the prior installed resource/CI hashes before installing these reviewed sources.
Historical receipts from resource digest `cb756f5f3c47ed887957e1209a44f061b52e6c4541998cddcd107b82ebb6bf4e`
remain readable on a MODEL host alongside the previous six-BGE-only legacy pin.

Both source files and their path ancestors must be root-owned and unwritable by
other users. A source update requires a new independent review and matching pin.

The operator first snapshots the original active/enabled states of
`nexgrid-release.timer` and `nexgrid-release.service`, then explicitly stops an
active timer and waits for the service to be inactive with MainPID zero. The helper
only checks these stopped states; it never stops or starts either unit. A HALTED
marker alone cannot prevent the timer's pre-hook from creating another drain before
the broker reads the hold. Keep the timer stopped throughout maintenance. After
successful resource recovery, restore the timer only if that original snapshot
showed it active; do not enable or start a previously inactive timer. The current
TEST snapshot has both timer and service inactive, so both must remain inactive
after this maintenance. Do not restart the release service merely to restore state.

With the release timer stopped, the operator uses the original graceful `drain`
and waits for READY and every worker `inactive/MainPID=0`. Directly sending SIGTERM
to a busy old worker would prevent its pending completion from finishing. The new
root-only `resume-maintenance` closes only this verified ready drain while keeping
workers stopped:

```sh
sudo /usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py drain
sudo /usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py resume-maintenance
```

This branch preserves matching drain IDs, backend health, absent release holds,
unchanged final inventory/protected budget, stopped workers and stopped release
units. It first persists `last-drain-receipt.json` with the original service intent
unchanged and `workersKeptStopped=true`, then clears only its verified markers.
Do not call ordinary `resume-release` here, hand-edit a receipt, or delete markers.
A partial marker cleanup remains held for reviewed recovery. The CI helper rejects
any remaining drain marker or foreign release hold. It never stops a worker, edits
a journal, triggers Jenkins or starts a worker.

The default action is a read-only plan. Supply the independently observed SHA256
of the current raw `/etc/uvel-task-control/budget.json`:

```sh
sudo /usr/bin/python3 -I -B /opt/uvel-task-control/compute-v2-ci-maintenance.py --budget-sha256 REVIEWED_BUDGET_SHA256
sudo env UVEL_COMPUTE_V2_SCOPE=TEST /usr/bin/python3 -I -B /opt/uvel-task-control/compute-v2-ci-maintenance.py enter --budget-sha256 REVIEWED_BUDGET_SHA256
```

Only two explicit model profiles in the known 24.5 GiB runtime budget plus the
installed Compute V2 worker and observation commitments are accepted:

| Profile | Compute containers stopped for CI | Retained base validator |
| --- | --- | --- |
| `six-bge` | `uvel-cloud-bge-model-fleet-1` through `-6`, each 1.5 GiB, loopback 11436–11441 to container 11434 | `uvel-cloud-bge-model`, 1.5 GiB |
| `bge-modal` | `uvel-cloud-bge-model-fleet-1`, 1.5 GiB on 11436→11434; `uvel-compute-modal-runtime`, 7.5 GiB on 11442→11442 | `uvel-cloud-bge-model`, 1.5 GiB |

The modal cap comprises a 6 GiB model work pool and 1.5 GiB framework/CUDA/JSON
overhead; overhead does not admit another task. Both profiles reserve the same
10.5 GiB for models, and maintenance removes exactly 9 GiB of compute commitments.
The modal runtime profile retains `maxActiveExecutors=6` for logical permits;
the reviewed controller accepts this exception only for these exact three model
commitments/caps and the complete stopped-controls set `fleet-2` through `-7`.
Those six stopped containers must each have Running false, PID zero, Paused false
and restart policy `no`. The old spare-only profile retains its existing
`no`/`unless-stopped` compatibility. The controller does not infer six physical
model processes from the three containers; Java/Lua and the modal child service
independently enforce the shared 6 GiB work pool and CPU admission.
Fleet containers 2–6 are preserved and stopped in the modal deployment, not
silently added to that profile or restarted by restore. Their absence from the
budget is not permission for boot/compose automation to restart them.

`compute-v2-worker` reserves 268435456 bytes
for `systemd/uvel-compute-v2-worker.service`, and `compute-v2-observation` reserves
25165824 bytes for `systemd/uvel-compute-v2-observation.service`, both kind `other`.
These two commitments remain unchanged during maintenance and restore.
The helper records the original
budget bytes, CI container identity and hard limits, the selected profile's exact container identities/ports/
limits/model digests, and stopped journal fingerprints. It creates a root-private
persistent `/var/lib/uvel-task-control/ci-maintenance.json` receipt and its own exact
`/srv/nexgrid/cd/HALTED.json` marker. This blocks promotion/resume without creating
a drain marker that would prevent the original build admission gate.

The helper briefly pauses the confirmed idle CI agent to close resource-transition
races, stops only the recorded compute
container IDs, and verifies every stopped PID is zero. The base validator remains
reserved and running. It raises CI memory and swap limits to 3 GiB, removes only
those profile-selected stopped model commitments, sets `maxActiveExecutors=1`, and sets the CI
build budget and all existing component requests to 3 GiB with builds enabled.
The resulting total is 17.5 GiB plus 293601280 bytes, exactly 19084083200 bytes;
the budget assertion, plan and ACTIVE output use the same total.
Other commitments, worker inventory, protected
journals, stopped-control rules and all other budget fields remain identical.
The original controller snapshot must allow heavy builds before the agent resumes.

Actual builds still require their real build number and the original admission
command before the reviewed manual CI trigger:

```sh
sudo /usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py snapshot
sudo /usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py admit-build backend REAL_BUILD_NUMBER
```

The helper never invents a build number or releases a nonterminal reservation.
The resource controller's existing `admit-build` and `run-build` commands are
specific to those real Jenkins jobs: only their matching terminal `build.xml`
releases a reservation. They do not reserve a standalone Docker dependency build.
Do not create a synthetic job/build number or manufacture terminal metadata.

A custom image/dependency build therefore needs a separately reviewed operator
budget exchange. In the existing 3 GiB maintenance envelope, the CI agent must be
stopped (pausing retains its RAM), its same-ID state preserved, and that exact
build commitment/control temporarily assigned to a builder with matching memory
and swap hard limits. Run the original controller's limit/snapshot/20% checks
against that explicit budget before the custom work. The builder and all build
processes must actually inherit the bounded cgroup; limiting the Docker CLI with
`systemd-run` does not constrain the daemon's BuildKit processes. Do not run a
second unbudgeted 3 GiB builder beside the reserved CI container.

After the custom process physically exits, restore the exact maintenance budget,
CI container identity/limits and idle running state before this helper's normal
restore. The helper does not implement this custom-build exchange or accept an
intermediate foreign budget in `restore`; the original maintenance bytes must
first be restored by that reviewed operator step. A READY drain alone does not
grant build admission: complete the documented `resume-maintenance` transition,
keeping workers stopped, before entering the CI maintenance envelope.
Its Jenkins inventory requires the three `nexgrid-{backend,pc,uniapp}-test` jobs.
Only the corresponding three archived `-main` names may additionally exist; each
must be a `flow-definition` with `disabled=true`, and every numeric historical
build must have `completed=true`. Archive config/build XML is read with the same
8 MiB bound and rejects DOCTYPE, ENTITY, malformed or non-UTF-8 input. Enabled
main jobs, unfinished archive builds and unknown job names keep maintenance held.
The helper only reads these archives; it never enables, triggers or modifies them.
After all builds and reservations are terminal and the agent is idle, restore:

```sh
sudo env UVEL_COMPUTE_V2_SCOPE=TEST /usr/bin/python3 -I -B /opt/uvel-task-control/compute-v2-ci-maintenance.py restore
```

Restore uses the original receipt, not a fresh budget baseline. It disables new
build admission, restores CI to its original 1 GiB hard limits and restores the
original budget bytes exactly. It derives the two-container or six-container
profile from that frozen original budget and starts only those same recorded IDs.
It checks their limits and loopback ports; BGE retains `/api/tags` and its original
`bge-m3:567m` digest. Only modal port 11442 uses `/health`, requiring `status=ready`
and `modelDigest=sha256:<installed-manifest-file-SHA256>`. The same frozen digest
must return after restoration. It also requires the
original resource snapshot/20% gate. Workers stay inactive for the operator's
explicit original release/resume workflow. Only after verification does it remove
its own marker whose task ID and receipt hash match exactly. The durable receipt
remains as evidence; repeat `restore` is idempotent. A second `enter` cannot overwrite
this task's receipt. Existing `RESTORED` receipts still validate against their
own original profile; a new deployment does not relabel them or overwrite them.
Only old `six-bge` receipts may retain the previous reviewed controller digest
`b2c053f53ca95d805f7d413d6d8449254ae1cbe7369115761626a2b56fcf52a9`;
the running imported controller must still match the new pinned digest above.
Any operator archival needed before another maintenance operation must preserve
the original receipt and its independently verified restoration evidence.

The root-owned `/var/lib/uvel-task-control/maintenance-operation.lock` uses a
nonblocking exclusive flock over the whole enter/restore operation. A concurrent
enter or restore is rejected before it can change the same receipt or hold.
Every mutation intent is recorded first. Failure retains the receipt and hold;
repeat `restore` resolves partial Docker updates or lost command acknowledgements
using actual same-ID state. Foreign holds, budget drift, changed containers, changed
stopped journals, active builds or failed resource/health checks remain held for
review. The helper does not clear someone else's evidence. The release lock
`/srv/nexgrid/cd/lock` precedes `/var/lib/uvel-task-control/control.lock`; these two
locks remain brief and are not held during Docker, model-health or snapshot waits.
Only the dedicated maintenance-operation lock spans those waits, leaving normal
resource snapshots and build admission available.

Validation uses the existing TEST maintenance, actual build and restore flow;
unit and independent integration test suites are not run.

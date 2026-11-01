# Compute V2 CI maintenance budget exchange

This independent TEST operator tool is a candidate for review. It is not installed
by these files and is outside the 13 trusted release files. The resource controller
and its original snapshot/admission rules remain unchanged.

Before installation, verify the helper and resource candidate hashes independently.
Preserve the root-owned pre-V2 resource source as
`/opt/uvel-task-control/resource-control.pre-compute-v2.py` (or pass its reviewed
absolute path with `--baseline-resource`). Its required SHA256 is
`1b66c15b0b03c8ee17e9a46219027e9f3030f963beac448bab4bc3573e0f52b8`.
The installed resource controller must match SHA256
`b2c053f53ca95d805f7d413d6d8449254ae1cbe7369115761626a2b56fcf52a9`.
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

Only the known 24.5 GiB runtime budget is accepted. The helper records the original
budget bytes, CI container identity and hard limits, six fleet identities/ports/
limits/model digests, and stopped journal fingerprints. It creates a root-private
persistent `/var/lib/uvel-task-control/ci-maintenance.json` receipt and its own exact
`/srv/nexgrid/cd/HALTED.json` marker. This blocks promotion/resume without creating
a drain marker that would prevent the original build admission gate.

The helper briefly pauses the confirmed idle CI agent to close resource-transition
races, stops only the six recorded `uvel-cloud-bge-model-fleet-1` through `-6`
container IDs, and verifies every stopped PID is zero. The base validator remains
reserved and running. It raises CI memory and swap limits to 3 GiB, removes only
those six stopped model commitments, sets `maxActiveExecutors=1`, and sets the CI
build budget and all existing component requests to 3 GiB with builds enabled.
The resulting total is 17.5 GiB. Other commitments, worker inventory, protected
journals, stopped-control rules and all other budget fields remain identical.
The original controller snapshot must allow heavy builds before the agent resumes.

Actual builds still require their real build number and the original admission
command before the reviewed manual CI trigger:

```sh
sudo /usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py snapshot
sudo /usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py admit-build backend REAL_BUILD_NUMBER
```

The helper never invents a build number or releases a nonterminal reservation.
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
original budget bytes exactly. It starts only the same six recorded container IDs,
checks their limits, loopback ports and original BGE digests, and requires the
original resource snapshot/20% gate. Workers stay inactive for the operator's
explicit original release/resume workflow. Only after verification does it remove
its own marker whose task ID and receipt hash match exactly. The durable receipt
remains as evidence; repeat `restore` is idempotent. A second `enter` cannot overwrite
this task's receipt.

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

Local validation: `python -m unittest scripts.ops.test_compute_v2_ci_maintenance -v`.

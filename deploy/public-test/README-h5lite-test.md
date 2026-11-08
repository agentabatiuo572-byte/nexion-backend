# H5 Lite independent TEST release

2026-11-08. Approved scope: publish the existing `h5-lite` branch at
`https://18.142.169.24/h5-lite/`. Record installation, a Jenkins build, public
deployment and business acceptance separately; this procedure defines their
requirements rather than serving as an acceptance receipt.

| Component | Repository | Only branch | Jenkins job | Artifact | Loopback slots |
| --- | --- | --- | --- | --- | --- |
| backend | nexion-backend | test | nexgrid-backend-test | backend.jar | 8110 |
| pc | nexion-frontend-pc | test | nexgrid-pc-test | pc-build.tgz | 3003/3004 |
| uniapp | nexion-frontend-uniapp | test | nexgrid-uniapp-test | uniapp-h5-build.tgz | 8082/8083 |
| h5lite | nexion-frontend-uniapp | h5-lite | nexgrid-h5lite-test | h5lite-h5-build.tgz | 8084/8085 |

The pinned job checks out the exact branch/refspec. CI verifies HEAD against that
remote tracking ref and writes the fixed branch into `release.json`. The owner
broker verifies pinned job XML, completed SUCCESS Git BuildData identity,
manifest/component/artifact/hash, and the current GitHub head of that fixed ref.
None of these checks accepts a caller-supplied branch. Jenkins remains manual,
uses the isolated existing agent and receives no deployment credentials/socket.

Lite uses the existing H5 install/type-check/production build, with
`VITE_NEXGRID_API_BASE_URL=https://18.142.169.24` and `--base /h5-lite/`.
It does not build APKs or inject App's DB-IP footer. No Vue, preview safe-top,
navigation, authentication, CORS, MFA, role or business-data changes are included.
Root `/api`, `/auth` and `/ws` remain the existing endpoints. App and Lite share
the same origin's storage and refresh cookie; business acceptance must use an
independent browser identity or serial use of the approved TEST user.

## Initially absent release and real rollback

Create only an empty root-owned `/srv/nexgrid/cd/h5lite` and this state entry
alongside the byte-preserved existing component entries:

```json
"h5lite": {"build": 0, "sha": null, "port": null, "container": null}
```

This is not a healthy legacy Lite release. Installation must leave nginx and its
state hash unchanged, so `/h5-lite/` remains absent until a candidate cutover.
The first candidate uses 8084; later releases alternate 8084/8085. Retention,
image hash, safe extraction, read-only non-root container, shared release lock,
durable STAGING/APPLYING/HEALTHY journal and promotion receipts are reused.

Only the first Lite cutover inserts the exact `/h5-lite` 308 canonical redirect
and `/h5-lite/` proxy block before the single existing Jenkins location include
in `nexgrid-public`. The trailing proxy slash strips this prefix for the static
container. Assets stay under `/h5-lite/assets/` and `/h5-lite/static/`; it does not
add a root assets/static route. The static container's existing api/auth rejection
also applies after stripping the Lite prefix. Later Lite cutovers swap only its
8084/8085 upstream. Existing App/PC/root/API routes are not rewritten.

With no previous Lite port/container, failure or `rollback-check h5lite BUILD`
restores the exact saved nginx bytes, validates/reloads nginx, verifies the
absence of the Lite route, stops/removes only that Lite candidate, and retains
the staged files as recovery evidence. It never probes a fabricated old Lite
server or restarts App. Successful rollback-check leaves the absent state and
records proof for that exact SHA/build. Initial `deploy` requires this proof;
automatic polling cannot publish a first Lite build before it exists.
Interrupted APPLYING recovery performs the same restoration. Failed or
uncertain recovery preserves HALTED/journal and requires operator inspection.

After the first successful deploy, Lite has its own real old container/port;
subsequent failures restore that container and the saved ingress. A successful
rollback-check is a real temporary cutover/restore, not a post-deployment command
to remove an arbitrary live version. Preserve each promotion receipt for any
later operator recovery. Retained staging directories must not be reported as
live releases; remove them only after checking mount and journal references.

## Existing-host upgrade: evidence required before any write

`install_release.py ci/host` is the historical initial bootstrap; do not rerun it
on an installed host. `40-release-jobs.groovy` is its initial hook, not an updater
for the existing marker. No older `update_*.py` is a general-purpose Lite updater:
their exact closure/change-set pins remain intentionally unchanged.

The current host closure is not assumed to equal the repository example. Obtain
these read-only facts through the normal approved operator session:

1. Raw `/srv/jenkins/release/runtime-lock.json`, its independent SHA256, all 13
   pinned runtime file hashes and verified bytes for the five changed files.
2. Non-secret `config.json` pin maps and image IDs; all existing job XML and job
   hashes; actual CI bind mount sources/inodes for build script and pipeline.
3. Exact `nexgrid-public` bytes/hash, proxy snippet hash, active sites and the
   unique insertion anchor; current public App response/build and Lite 404.
4. Current state, timer/service/lock, queue and running builds, transaction/HALTED
   and migration journal/hold presence, active containers/mounts, free disk and
   actual absence of listeners/containers on 8084/8085.

`h5lite-runtime-change.json` pins the five reviewed source changes against the
candidate's Git baseline. If any changed host file differs from its `old_sha256`,
stop installation and apply/review the focused change against the verified host
source. Do not overwrite host-specific work with the full repository file. The
other eight host closure members must remain byte-for-byte unchanged; preserve
their existing pins, including any host-specific migration/policy versions.
Do not replace them with the repository `runtime-lock.json` or the dated Cregis
example. Never mint trust from unverified host files.
The reference lock is deliberately unchanged here: already at this candidate's
baseline its `test-server.yml` pin differs from the repository bytes. A full
repository closure check therefore does not pass and is not installation proof.
The change manifest distinguishes the five installed old/new source pins from
their repository baseline, and pins the outside-closure controller and bound CI.

After root review and commit, record the immutable full backend commit SHA and
independently approved SHA256 of each new file, the change manifest, any one-time
operator updater, and the staged host-specific runtime lock **outside** the
downloaded content. Download only from that commit. Verify those pins before
executing an updater. Record that reviewed immutable publication SHA and the
approved external pins before installation; a placeholder SHA never authorizes it.

## Narrow owner installation and recovery contract

Prepare/review the host-specific operation only after the preceding evidence:

1. Verify the installed 13-file host closure against its independently observed
   lock. Stage only the five reviewed replacement members, new host-specific lock
   and outside-closure resource source. The eight unchanged members remain at
   their verified installed paths; only five lock entries change.
2. Preserve timer's active/inactive state, AUTO_ENABLED, existing three job XML,
   config, state, nginx, modes/owners and CI bound file identities in a verified
   root-only backup. Require no pending/running builds, queued jobs, transaction,
   HALTED or unresolved migration. Hold the timer and release lock, pause the
   idle build agent and stop only the idle Jenkins controller before writes.
3. Update the two individually bind-mounted CI files without changing their
   inodes, as existing approved updaters do. Install only the five pinned runtime
   files and the staged lock; refresh changed `trusted_files` pins already present
   in config. Preserve all other config flags, policies, external/job pins and
   images. Add only `job_hashes.h5lite` for the new job.
4. Create `nexgrid-h5lite-test` using the reviewed pipeline rendered with
   `@KIND@=h5lite` and the fixed UniApp repository. Use sandboxed CpsFlowDefinition,
   no parameters/triggers, disable concurrent builds, and General retention of
   ten build records/five artifact sets. Keep controller security/executors,
   credentials and the existing three job definitions unchanged. Its exact saved
   XML hash must equal the new config pin before any build.
5. Add only the absent Lite state/root described above. Leave nginx and its state
   digest unchanged. Verify the complete installed closure, bound bytes/inodes,
   job pins and unchanged old component states before restoring CI/timer state.
6. On failure, keep controller/agent/timer held until the exact backed-up runtime,
   bound files, config, old three jobs and state are restored and verified. Remove
   only the newly created Lite job/root when they contain no builds/releases;
   unexpected content requires inspection. Do not clear journals, restore auth,
   restart business containers or resume a partially verified closure.

Installing source is separate from publishing. The main Agent must re-freeze the
actual current `origin/h5-lite`, reserve the real next build with the official
resource controller, manually start only the matching new Jenkins job, and
verify checkout, manifest, artifact SHA256, `/h5-lite/` base and root API URLs.
The retired host keeps the sealed three-component budget and retirement receipt
unchanged. Only the already approved `CI_10_GIB` effective profile adds a separate
`h5lite` request of 10 GiB. All four requests share the existing single 10 GiB
envelope and 20% host reserve, so a Lite build cannot borrow an App reservation or
run beside another admitted build. Other profiles reject Lite admission.
`compute-v2-ci-maintenance.py` remains permanently retired and unchanged; do not
call plan/enter/restore or invent a `h5lite-main` archive.

Before the manual Jenkins trigger, read its actual next build number and run:

```text
/usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py snapshot
/usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py admit-build h5lite BUILD
```

Only the matching real terminal Jenkins build releases this reservation. Do not
write terminal metadata or clear a nonterminal reservation. After terminal build
verification, use the official `drain` before each owner cutover, require READY,
and call `resume-release` only after that cutover/rollback has completed cleanly.
Hold automatic promotion using the existing owner procedure, preserve its previous
flag/timer state, then use the verified canonical entry:

```text
/usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py drain
/usr/bin/python3 -I /srv/jenkins/release/trusted_entry.py broker rollback-check h5lite BUILD
/usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py resume-release
/usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py drain
/usr/bin/python3 -I /srv/jenkins/release/trusted_entry.py broker deploy h5lite BUILD
/usr/bin/python3 -I -B /opt/uvel-task-control/resource-control.py resume-release
```

Before deploy, observe temporary public Lite availability during rollback-check
and restoration to the prior 404 afterward; confirm App/PC/backend state and
responses remain as before. Then deploy that same still-current build, inspect
promotion/state/container and normal TLS public resource bytes, recheck old
component responses, and restore the previous automatic-promotion state only
after a clean transaction. Do not use `install enable` to bypass the first Lite
proof or manufacture proof for an absent release.

Acceptance is E2E/system only: current branch/job/manifest/hash success; wrong
branch, stale head or digest rejection before cutover through the real owner
workflow; first cutover and restore to absence; independent final deploy; resource
base and restart/state recovery; normal Lite login/material/private-file/terminal
flows. Syntax and build checks do not sign those scenarios PASS. Retain redacted
receipts and finish only this task's scratch work under existing cleanup rules.

## Reviewed host upgrade entry

`update_h5lite_test.py` is outside the 13-file closure. Independently pin this
updater, the new host-specific lock and resource-controller source from the same
reviewed immutable commit before invocation. Its fixed old host lock is
`61929e7cb3f57d3b2772748fa7fb27155c3cb0e53f6ee2f3b086a47665163cf3`;
the old resource source is
`129569c7d164de4c3402995dc87424f4c838a304bc5eeb788793b9ec419b09d7`.
The installed CI build script has its own separately observed baseline
`2de25be6d6fc4460b99fd52e7e985195bc9ad025b15cd279ef0d3e1b5f216bbe`,
different from the protected historical copy; the updater pins both separately.

Stage only the five approved changed runtime files, the new
`runtime-lock-20261108-host-h5lite.json`, and `compute-v2-resource-control.py` in
one fresh root-owned child `stage-<review-id>` of the fixed task directory
`/mnt/test-scratch/uvel-tests/be27-lite-test-entry-20261108`. Allocate that task
through `uvel-test-workdir` first; absent scratch storage must stop the operation.
Keep the updater outside the stage, in that same task directory. Verify the
stage, updater and every ancestor are root-owned and not group/other-writable;
if allocation does not meet these checks, stop for inspection rather than
changing another task's parent directory or falling back to the root disk. Retain
the verified eight unchanged installed closure members in place. The updater
verifies every old file before replacing only the five approved members and
their lock/config pins, the separately pinned resource controller, the two CI
bound files without changing their inodes, and the new Lite job/state/root.
It preserves the exact original budget, retirement/CI receipts, existing jobs,
nginx, file modes/owners and prior timer state; AUTO remains absent.
Container identity comparisons sort Docker's mount list by destination because
inspect can return the same mounts in different orders; every mount field and
all other identity checks remain part of the comparison.

```text
/usr/bin/python3 -I /mnt/test-scratch/uvel-tests/be27-lite-test-entry-20261108/update_h5lite_test.py /mnt/test-scratch/uvel-tests/be27-lite-test-entry-20261108/stage-REVIEW_ID NEW_LOCK_SHA256 NEW_RESOURCE_SHA256
```

Invoke this entry only after independent review freezes the exact staged source,
lock hashes and immutable source commit. On a write failure it restores original bytes
and modes from its root-only backup and removes only its empty new job/root;
uncertain restore or CI/timer recovery leaves polling held and prints the backup
path. An interrupted process requires that backup's exact path/identity/hash
manifest; do not resume a partial closure or use repository reference locks.
The same persistent root-only backup also contains the original six job XML
files, nginx, budget and retirement/CI receipt bytes, SHA256 and file identities.
Its manifest marks these `automatic_restore=false`: drift in those external
files leaves polling/CI held for operator comparison with the saved originals;
the updater never overwrites an unknown external change automatically.

Artifact pins for independent operator verification (record the reviewed
immutable source commit separately):

| Artifact | SHA256 |
| --- | --- |
| New host-specific lock | `4fceecda7653f32a20b5754e1ae7addf557b55b22c7b15102431718a5a094603` |
| Resource controller outside closure | `b27930659907643b66b9b960681a506ec67d481f3c548bd2069c4b7df084b5f2` |
| One-time updater outside closure | `0fe3ae652ad390cef8d20b88442a11913e30722ae7c334772e7f2d207b6e7cf6` |

The new CI source preserves the live bound script's cgroup ancestry and 20%
memory checks, exact backend Maven limits/skip flag and existing
`publicationNative=OWNER_ADMISSION_REQUIRED` marker/log. The protected historical
CI copy had none of those live-script additions. The reviewed new CI bytes are
installed identically at both paths without changing either inode or mode.
The protected historical hook/installer used main and automatic initial builds;
this candidate retains the repository's existing test/manual bootstrap fix and
adds Lite. The upgrade never invokes that bootstrap. The old protected pipeline
had a top-level `properties(buildDiscarder(...))`; the actual existing jobs and
this candidate use General retention without that job-mutating pipeline call.

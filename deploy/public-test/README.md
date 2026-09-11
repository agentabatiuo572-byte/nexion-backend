# GitHub main to TEST release

Business source of truth is each repository's GitHub main. Do not merge old server
uploads or local uncommitted work into CI. Preserve existing data, media, model/KB
storage, credentials, administrator MFA and login channels.

## Boundaries

The authenticated Jenkins controller has zero executors. Its isolated build agent
has no host socket, business secrets or deployment API. A root-owned broker accepts
completed SUCCESS builds from pinned job definitions, verifies current main SCM
identity and artifact digest, then runs business code as existing non-root users.
Main and controller administrators are trusted; this is not a reproducible-build proof.

The operator bootstrap must independently pin every runtime file and runtime-lock.json
by SHA256 and download from one reviewed immutable GitHub commit into a fresh
root-owned directory. Never execute a downloaded installer before that verification.
The `python3 -I trusted_entry.py` entry checks the complete fixed closure and all
root-owned, non-symlink, non-writable ancestors before compiling verified bytes.
Never regenerate a trust lock from unverified host files. Infra updates need review.

GitHub main is approved business source: ordinary mapper, startup and source changes
do not require a second schema fingerprint approval. CI compiles/packages main; it
does not repeat the business contract suites previously imposed at deploy. Five
automatic backend checks retain TEST/profile isolation, database environment routing
and forwarded-header trust; they require no manual approval.
UniApp type checking and actual frontend/backend compilation remain build checks.
Frontends use alternate local ports before Nginx cutover. Backend restarts only its
own service. For a code-only release, a durable journal restores the old release on
failure. New versioned SQL uses the automatic backup/migration flow below instead;
code rollback cannot undo committed MySQL DDL. Main may contain its own startup
effects, which are not equivalent to tracked incremental SQL migrations.

Each repository polls main every five minutes. The host checks completed artifacts
about once per minute. Component failures are recorded in `component-failures.json`
and do not block the other repositories after staging cleanup or a verified rollback.
A failed staged artifact is not repeatedly restarted: push a fix or rebuild it with
a new Jenkins build number. Transient preflight failures are retried automatically.
An unfinished transaction, failed rollback or unknown shared ingress/state still
halts promotion for recovery. These are runtime recovery safeguards, not business
source approvals. Publishing UniApp replaces H5 assets; it does not reboot EC2.

## Verified operator sequence

All commands below require `/usr/bin/python3 -I /srv/jenkins/release/trusted_entry.py`.

1. `install ci`: update only idle CI containers, preserving volumes/auth, queue builds.
2. Verify all three Linux builds, artifacts, exact SHAs and predeployment backups.
3. `install host`: establish protected deployment state, auto remains OFF.
4. For each component/build, `broker rollback-check KIND NUMBER`, then
   `broker deploy KIND NUMBER`. The check makes a real cutover and restores the old
   version, recording rollback proof for that exact SHA.
5. Verify public PC/H5, TEST policy, protected endpoints and unchanged login/data.
6. `install enable`: requires exact-SHA rollback evidence and three healthy releases.

CI SUCCESS means artifact-ready, not deployed; broker state is deployment truth.
Failed/unstable builds, config drift, low disk or HALTED block the relevant promotion.
Do not clear HALTED without checking recovery and the journal. Keep one working
rollback release and its images/config. Never clean paths used by data/model mounts.

Local gate: `python -m unittest discover -v`. Isolated tests alone do not prove live
deployment, business acceptance or real rollback completion.

`update_independent_releases.py` is a one-time operator migration, not automatic
runtime code. Independently pin its SHA256 and the new lock from an immutable
reviewed commit. It accepts only the known previous lock and changes only the broker
and CI build script. It holds the release timer/lock, pauses an idle CI agent,
backs up the old files, updates the bind-mounted script without changing its inode,
verifies the new runtime, then resumes the agent/timer. Failure restores those files;
failed recovery leaves the agent/timer held. No business container, database, login,
job configuration, policy or EC2 instance is restarted/modified by this migration.

## Automatic database migrations (TEST)

Push a **new** UTF-8 forward migration into backend main at
`scripts/migrations/YYYYMMDD_descriptive_name.sql`. Files in the same batch execute
in filename order; use a numeric order in the descriptive part when dependencies
require it. Do not modify/delete previously recorded files: add another forward
script. This is once-only execution, not replay-on-every-build. Initialization,
reset, rollback, nested SQL directories and arbitrary `.sql` elsewhere are not run.
Historical files were explicitly baselined without replay; that does not claim all
historical SQL had run. The registration snapshot repair has its own backup receipt.

After Jenkins successfully builds current main, the root-owned host broker:

1. Downloads the SQL catalog from that exact immutable GitHub SHA, validates tracked
   checksums and the database route/grants. Jenkins never receives DB credentials.
2. Persists a reboot-safe backend startup hold and briefly stops only the backend.
3. Creates and verifies a full `nexion` dump including routines, events and triggers.
   Backup files and SQL receipts remain root-only under
   `/srv/nexgrid/backups/auto-migrations/`. These are local backups, not off-site DR.
4. Executes only new scripts with the database-scoped app account, not MySQL root;
   records each completed filename/hash. No additional human approval is requested.
5. Switches to the new JAR and allows one controlled start using a volatile `/run`
   permit. The persistent startup hold survives crashes/reboots until health passes.
   Success seals the receipt and clears the hold and active migration journal.

Backup failure before SQL restores the previous backend. SQL error/timeout or
interruption retains the backup, logs, persistent startup hold and active journal;
it does not automatically replay an uncertain script or overwrite newer data by
restoring a dump. A failure after **all SQL completed** stops the candidate and
allows a later main build to repair forward without replaying the completed SQL.
PC/UniApp jobs remain independent. A whole EC2 reboot is never part of this flow.
Failure receipts are in `migrations/active.json` and `component-failures.json` under
`/srv/nexgrid/cd`; the normal release state is the last successful version, not a
claim that a held backend is currently running.

On a partial/uncertain SQL failure, inspect private SQL stderr, completed hashes,
actual schema and server sessions before recovery. MySQL DDL is not transactional:
decide explicitly whether to repair forward or restore a verified full backup;
do not simply delete the journal/startup hold or restart the old JAR. Routine SQL
does not need pre-approval, but failure recovery must not guess what committed.

`update_database_migrations.py` is the one-time operator upgrade for the known
previous 12-file closure. Verify its own SHA and the new 13-file runtime lock from
one reviewed immutable commit first. It holds the release timer/lock, backs up the
runtime, verifies the existing repair/backup, records the historical baseline,
installs the runner and systemd startup condition, then resumes polling. It does
not change Jenkins jobs, CI agent, secrets, MFA, TEST policies or business data.
An identical complete baseline can be verified and reused after an interrupted
upgrade; a different/partial baseline requires operator inspection. An interrupted
runtime-file replacement fails closure verification rather than executing partial
code. Recover those exact files from the printed runtime backup before resuming.

MySQL references: [noninteractive binary-mode client](https://dev.mysql.com/doc/refman/8.0/en/mysql-command-options.html)
and [mysqldump consistency and options](https://dev.mysql.com/doc/refman/8.0/en/mysqldump.html).

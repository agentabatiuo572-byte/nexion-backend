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
own service. A durable journal restores the old release on failure, not database
migrations. The broker does not execute SQL/import scripts. Main may contain its
own startup effects; code rollback cannot undo those. Back up the database before
any separately authorized migration.

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

# Fixed SIM policy epoch installer (TEST)

This operator-only update was approved in the USDT completion task on 2026-11-09.
It preserves the exact installed host runtime, including the existing H4 and
Compute V2 lanes. It adds only the three fixed AFTER INSERT policy-cache epoch
triggers from `20261109_sim_policy_epoch.sql`. The original SQL is unchanged.

The host baseline is the raw lock SHA
`4fceecda7653f32a20b5754e1ae7addf557b55b22c7b15102431718a5a094603`, with
migrator SHA `3452485f429c1d814e6e97b146fff01c692c30e9926330e2f5c5bfd048cc5ca8`.
The reviewed source is `sim-policy-database-migrations.py`, installed as
`database_migrations.py`; its LF source SHA is fixed by the updater. Do not copy
the older repository example runtime or lock onto the host.

The installer checks the whole SQL file, prefix and each CREATE block. Only the
table/seed prefix runs as the existing app account. None of the original DROP
statements execute. The three history tables and optional epoch table must be
InnoDB; an existing epoch table must have the fixed two-column singleton shape.
Any trigger on those history tables, or a collision with a reserved name, blocks
first installation before backend stop and SQL. Root creates exactly the fixed,
schema-qualified templates with `DEFINER=nexion_app@%`; neither privileges nor
global settings are changed. Names, bodies, timing, tables, definer and session
metadata are checked before and after each write. Other unreviewed stored
programs remain refused. Interrupted DDL stays held for forward recovery.

Create a fresh root-owned stage directly under the allocated temporary disk path
`/mnt/test-scratch/uvel-tests/usdt-three-network-20261109/runtime-updates`. Copy the
13 files from the verified live closure, replace only its migrator, and update
only that hash in a copy of the live lock. The stage contains exactly those 13
files and `runtime-lock.json`. Verify the candidate, updater and staged lock
hashes independently before invoking:

```sh
sudo /usr/bin/python3 -I /ROOT_OWNED_OPERATOR_PATH/update_sim_policy_migrations.py /mnt/test-scratch/uvel-tests/usdt-three-network-20261109/runtime-updates/REVIEWED_STAGE STAGED_LOCK_SHA256
```

The updater preserves all approval flags, job and external pins, the other
twelve files, release/migration state and original timer state. It requires all
four TEST jobs (including H5 Lite), queue and reservations to be idle. It holds
the shared release lock then the existing admission lock, pauses the same CI
container, rechecks the closure and makes a root-private durable backup. The
three bound files are updated through their original inodes. Verification or
write failure restores their original bytes and modes under the same locks;
uncertain restoration or CI identity leaves the timer stopped for inspection.
No hold is removed by this updater. After installation, the normal broker still
performs full database backup, stop, journal, migration and release-health checks.

USDT auto-forward switches remain off. This runtime update does not initiate any
transfer or constitute business acceptance of the new USDT flows.

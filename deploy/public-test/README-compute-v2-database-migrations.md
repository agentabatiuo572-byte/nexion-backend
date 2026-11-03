# Fixed Compute V2 migration candidate

`compute-v2-database-migrations.py` is a candidate replacement for the installed
trusted `database_migrations.py`. It is based on the actual 75,700-byte host file,
SHA256 `0794fa15fb5d0aa2470bb6486dc56fad18eb6732337e471e0a15e4961b1a7221`.
It does not replace the repository's older `database_migrations.py`. The H4 trigger installer is retired as of 2026-11-04. Cregis, support, ordinary
SQL, backup, startup hold and recovery remain. First read the H4 migration registry and follow the
[recorded/pending retirement order](maintenance/README-h4-retirement.md).
A pending, exact-hash historical H4 file executes only its original table/seed
prefix as the app account, records APPLIED with RETIRED_H4_TRIGGER_SKIPPED,
and is followed by the new retirement migration. The trigger is never installed.
Unknown historical bytes are rejected; generic stored-program checks remain.

Candidate SHA256:
`8c4c28270ce7867aecb7d712a78af8999fa12f2f04e1ffb1c57d75d9b372d8db`.
The separate reviewed updater must accept the exact installed baseline and this
candidate, update the trusted closure and both pins together, and preserve all
other trusted files and approval values, apart from removing the retired H4
installer opt-in. It adds the explicit boolean
`allow_fixed_compute_v2_trigger_install=true` to the root-owned release config.
Missing, false or nonboolean approval rejects this lane before backup or hold.
No changes are made merely by checking out these candidate files.

The single admitted SQL source is
`scripts/migrations/20261101_compute_v2_settlement.sql`, SHA256
`df380554de06a556090fd018a13981716ffca25dcbbaf8c60f453f4f737a783d`.
The entire blob, eight exact byte offsets/lengths/hashes, complete tail layout,
and literal trigger headers/body are checked. The ordinary prefix contains the
four table definitions and remains subject to generic stored-program rejection.
It executes through the existing application-account SQL path. Root receives only
the eight fixed, schema-qualified CREATE statements with definer
`nexion_app`@`%`, covering BEFORE UPDATE and BEFORE DELETE on each of:

- `nx_compute_v2_checkpoint`
- `nx_compute_v2_event`
- `nx_compute_v2_settlement`
- `nx_compute_v2_allocation`

Every body is the fixed `SIGNAL SQLSTATE '45000'` immutable-record rejection.
The new lane contains no grants, user creation, global variable changes, arbitrary
root SQL transport or general trigger allowance. Other new stored programs still
fail the existing generic preflight.

Preflight requires matching app/root MySQL 8.4 contexts, fixed app identity,
binary logging enabled, trust-function-creators disabled, ROW binlog format,
required scoped app capabilities and root definer capabilities. Existing financial
originals, wallet incarnation/revision columns and the support schema-readiness
function must be ready. Any existing V2 table or any trigger on the four target
tables or with a reserved program name rejects first installation. This prevents
`IF NOT EXISTS` from hiding an existing or partially installed object.

The original broker still owns the release lock, stops the backend, verifies the
full backup and journals each SQL file before writing. This lane records each
fixed-template intent and hash, rechecks contexts and complete trigger inventory
before/after each root statement, and checks exact body, definer, table, timing,
event, row orientation and session metadata. SQL APPLIED is recorded only after
all checks pass. Unknown DDL acknowledgement or a mismatched postcheck leaves the
original SQL_FAILED journal/startup hold and never restarts old code or blindly
retries the file. Candidate-start permits and healthy-release cleanup remain the
native broker's responsibility.

Install this candidate only via the separately reviewed exact-baseline updater,
after the maintenance budget/model restoration and removal of this task's owned
hold have succeeded. Do not execute it as an independent migration shell. The
verified native entry continues to load `database_migrations.py` and the backend
broker continues to own the full backup and the pending forward migrations.

Historical validation of the pre-retirement candidate (not rerun or claimed for
the 2026-11-04 candidate; no tests were run for this change):

- 13 hermetic safety/failure tests passed, zero skips, including all nine physical
  DDL acknowledgement-loss positions and preservation of the original source
  sections. Log: `compute-v2-migration-fixed-lane-tests.log` in the task run.
- The existing ordinary, fixed-earnings, Cregis and support migration suites were
  loaded against this candidate: 73 passed, zero skips. Log:
  `compute-v2-migration-existing-regression.log`.
- The exact eight qualified templates were installed on the task-owned local
  MySQL **8.0.45** fixture. Actual metadata matched, and all eight UPDATE/DELETE
  attempts failed with error 1644 / SQLSTATE 45000. The fixture schema and its
  temporary locked definer were removed and the owned server was gracefully
  stopped. This verifies template/metadata behavior; it does **not** claim the
  production MySQL 8.4 capability preflight passed on MySQL 8.0. Production
  preflight deliberately rejects 8.0. Evidence:
  `compute-v2-template-mysql-results.json`, `mysql-lane-fixture-shutdown.json`.

These local checks neither install the trusted closure nor execute host/business
migrations. The existing trusted source file and all thirteen host files remain
unchanged by this candidate work.

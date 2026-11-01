# Fixed Compute V2 migrator update (TEST)

This operator-only updater is separate from the 13 trusted release runtime files.
It installs only the reviewed fixed Compute V2 migration lane; the normal native
broker later performs the SQL backup, persistent hold, migration journal, trigger
checks and backend health checks. The updater never imports or executes the
migrator, runs SQL, changes grants, starts a build or changes auth, jobs, models,
budgets or resource limits.

The fixed installed baseline is:

- `runtime-lock.json` SHA256 `907fb594f70782151ba12cdb168c324af47ef81c9ca961c9ce1f4a5e803d5cbc`.
- Installed `database_migrations.py` SHA256 `0794fa15fb5d0aa2470bb6486dc56fad18eb6732337e471e0a15e4961b1a7221`.
- Candidate migrator SHA256 `3452485f429c1d814e6e97b146fff01c692c30e9926330e2f5c5bfd048cc5ca8`,
  fixed by `NEW_MIGRATIONS_SHA` in the independently reviewed updater.

The candidate comes from the reviewed immutable Git source
`deploy/public-test/compute-v2-database-migrations.py`, renamed to
`database_migrations.py` in the stage. Its new lane accepts the exact settlement
SQL file and eight immutable triggers. Existing Cregis, support, H4 and earnings
lanes and the generic stored-program rejection remain in that candidate.

The current task explicitly approves adding only
`allow_fixed_compute_v2_trigger_install: true`. The updater requires that new key
to be absent at the original baseline, adds it together with the migrator pin,
and preserves all existing approval flags and other config semantics. The only
other changed config value is `trusted_files.database_migrations.py`.

Before running, wait for all current backend/pc/uniapp builds and reservations to
finish. Use the original CI maintenance helper's `restore` flow to restore its
resource budget and remove only its owned hold. Every existing release/migration
hold, active transaction, migration start permit or non-idle release service
blocks this updater. No HALTED exception or marker removal is implemented here.

Create one fresh root-owned stage directly under `/srv/jenkins/updates`. Copy all
13 files from the **currently verified host closure**; preserve the other 12 host
files byte for byte, rather than copying older repository examples. Replace only
the staged `database_migrations.py` with the reviewed candidate. Copy the host
lock and update only that one file hash in the staged lock. The stage must contain
exactly those 13 files plus `runtime-lock.json`. Files, directories and ancestors
must be root-owned, without symlinks or group/other write access. Independently
verify the updater SHA, the candidate SHA and the staged lock's raw SHA before use.

```sh
sudo /usr/bin/python3 -I /ROOT_OWNED_OPERATOR_PATH/update_compute_v2_migrations.py /srv/jenkins/updates/REVIEWED_STAGE STAGED_LOCK_SHA256
```

The updater verifies the existing JAR policy pin separately from the bundled
policy file, plus all configured internal, external and Jenkins job pins. It
preserves whether the release timer was initially active or inactive. After
stopping that timer, it holds the original shared release lock, confirms the
three TEST jobs are terminal and the same CI container has only its idle
remoting process, pauses that container, then repeats all source/config/state
checks. Optional `-main` job archives must be disabled and terminal; unknown jobs
and active builds are rejected.

Before any runtime write it fsyncs a root-private backup of the original
migrator, `config.json` and `runtime-lock.json` and prints
`COMPUTE_V2_MIGRATIONS_BACKUP_READY`. Its backup manifest records each original
hash, mode and inode identity. Those three installed files are updated through
their original inodes, so existing individual bind mounts see the new bytes.
No rename substitutes a new inode. All source/pin writes, verification and CI
resume remain inside the same release lock.

A write or installed-validation failure restores those three original byte
strings and modes, including removal of the newly added opt-in. A verified
rollback resumes only the same paused CI identity and starts the timer only if
it was originally active. Failed rollback, inode/identity drift or uncertain
pause/resume leaves the timer stopped and prints an operator-recovery marker.
Retain the printed backup and stopped state for independent inspection; never
repin unknown installed bytes or restart the timer to bypass this state. A hard
process kill during an in-place write likewise requires review against that
durable original backup before a same-inode restoration; no partial file is
treated as a trusted closure.

After a successful update, verify the 13-file closure at the staged lock SHA,
the unchanged other 12 files, original JAR/external/job pins, new opt-in, release
and migration state bytes, service/CI identity and original timer state. A lost
success acknowledgement is resolved by those reads; this baseline-specific
updater does not repin or reinstall an already changed/unknown closure. Then run
the **full CI artifact of the final current `test` commit** through the normal
native broker. Installing this updater is not evidence that any migration or build ran.

Local verification: `python -m unittest test_compute_v2_migrations_update -q`
from this directory uses disposable files and controlled systemctl/Docker
responses. It covers exact closure/pins, only the approved config additions,
actual inode-preserving writes, partial-write rollback, retained bytes/modes,
CI and release-lock races, active builds, source/state drift and uncertain
recovery. It is not a host-install or native-CI acceptance result.

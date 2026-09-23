#!/usr/bin/env python3
"""One-incident, fail-closed release-journal recovery for MySQL 1137.

This is an externally SHA-pinned root operator tool, not a broker command.
Audit is read-only. Arm preserves the failed receipt and places a broker HALT;
release only removes that HALT for a separate, manual forward deployment.
"""
import argparse
import gzip
import hashlib
import io
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import sys
import tarfile
import urllib.request

CD = Path("/srv/nexgrid/cd")
ROOT = CD / "migrations"
BACKUPS = Path("/srv/nexgrid/backups/auto-migrations")
FOLDER = BACKUPS / "20260923-171315-d5a10811669e"
ARCHIVE = ROOT / "archive-h3-1137-20260923-171315"
INTENT = ROOT / "h3-1137-recovery.json"
HALT = CD / "HALTED.json"
GUARD = Path("/etc/systemd/system/nexgrid-backend.service.d/60-database-migration-hold.conf")
GUARD_TEXT = ("[Unit]\nConditionPathExists=|!/srv/nexgrid/cd/migrations/START_BLOCKED\n"
              "ConditionPathExists=|/run/nexgrid-backend-migration-start\n")
FAILED = "20260923_h3_weekly_event_gate.sql"
OLD_SHA = "d5a10811669eaddbaee86fe90231cf3256b31fae"
OLD_SQL_HASH = "77080fdec9405bd8dd4b24a176d14f48b9694dcfb616e096c3b4586806758341"
BACKUP_HASH = "7859683a8abd7de6d34d7ad1d8c8983a204de2a45c61816418c7a8bc81f543e9"
BACKUP_BYTES = 11_030_983
OLD_PENDING_SUFFIX = [
    FAILED,
    "20260923_h3_weekly_event_gate_recovery_audit.sql",
    "20260923_i6_invalid_published_key_retirement.sql",
    "20260924_i6_legacy_course_retirement.sql",
]
TABLES = ("nx_growth_mission_business_gate_rollout",
          "nx_growth_mission_gate_pause_receipt",
          "nx_growth_quest_binding_quarantine_receipt")


def require(ok, code):
    if not ok:
        raise RuntimeError(code)


def trusted(path, directory=False):
    for item in (path, *path.parents):
        info = item.lstat()
        require(not stat.S_ISLNK(info.st_mode) and info.st_uid == 0
                and not info.st_mode & 0o022, "UNTRUSTED_PATH")
    require(path.is_dir() if directory else path.is_file(), "UNTRUSTED_TYPE")


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def read_json(path):
    trusted(path)
    return json.loads(path.read_text())


def durable_json(path, value):
    temporary = path.with_name(path.name + ".new")
    require(not temporary.exists() and not temporary.is_symlink(), "RECOVERY_TEMP_EXISTS")
    with temporary.open("xb") as stream:
        stream.write((json.dumps(value, sort_keys=True, indent=2) + "\n").encode())
        stream.flush()
        os.fsync(stream.fileno())
    temporary.chmod(0o600)
    temporary.replace(path)
    sync(path.parent)


def sync(folder):
    fd = os.open(folder, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def command(args, *, env=None, input=None):
    result = subprocess.run(args, env=env, input=input, capture_output=True, timeout=60)
    require(result.returncode == 0, "RECOVERY_COMMAND_FAILED")
    return result.stdout


def verify_backup():
    path = FOLDER / "database.sql.gz"
    trusted(path)
    require(sha256(path) == BACKUP_HASH, "BACKUP_HASH_CHANGED")
    total, tail = 0, b""
    with gzip.open(path, "rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            total += len(chunk)
            tail = (tail + chunk)[-1024:]
    require(total == BACKUP_BYTES and b"Dump completed on" in tail, "BACKUP_INVALID")


def verify_candidate(sha, state):
    require(re.fullmatch(r"[0-9a-f]{40}", sha) and sha != OLD_SHA, "NEW_SHA_INVALID")
    url = "https://codeload.github.com/agentabatiuo572-byte/nexion-backend/tar.gz/" + sha
    with urllib.request.urlopen(url, timeout=45) as response:
        require(response.status == 200 and response.url == url, "CANDIDATE_DOWNLOAD_REJECTED")
        raw = response.read(100 * 1024 * 1024 + 1)
    require(len(raw) <= 100 * 1024 * 1024, "CANDIDATE_ARCHIVE_TOO_LARGE")
    catalog = {}
    prefix = ("nexion-backend-" + sha, "scripts", "migrations")
    count, total_sql = 0, 0
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:gz") as archive:
        for entry in archive:
            count += 1
            require(count <= 50_000, "CANDIDATE_ARCHIVE_ENTRY_LIMIT")
            path = PurePosixPath(entry.name)
            require(not path.is_absolute() and ".." not in path.parts and "\\" not in entry.name,
                    "CANDIDATE_ARCHIVE_PATH_REJECTED")
            if len(path.parts) != 4 or path.parts[:3] != prefix or not path.name.endswith(".sql"):
                continue
            require(entry.isfile() and not entry.issym() and entry.size <= 8 * 1024 * 1024
                    and path.name not in catalog, "CANDIDATE_SQL_REJECTED")
            total_sql += entry.size
            require(total_sql <= 32 * 1024 * 1024, "CANDIDATE_SQL_TOTAL_LIMIT")
            catalog[path.name] = hashlib.sha256(archive.extractfile(entry).read()).hexdigest()
    recorded = state["scripts"]
    require(all(catalog.get(name) == row["sha256"] for name, row in recorded.items()),
            "CANDIDATE_HISTORY_CHANGED")
    require(set(catalog) - set(recorded) == {FAILED}
            and catalog[FAILED] != OLD_SQL_HASH
            and "20260923_h3_weekly_event_gate_recovery_audit.sql" not in catalog,
            "CANDIDATE_PENDING_NOT_EXACT_H3")
    return catalog[FAILED]


def verify_db():
    # No secret is placed in argv or emitted. Query only the fixed incident tables.
    container = json.loads(command(["docker", "inspect", "nexgrid-mysql"]))[0]
    password = dict(item.split("=", 1) for item in container["Config"]["Env"]
                    if "=" in item).get("MYSQL_ROOT_PASSWORD")
    require(bool(password), "DB_CREDENTIAL_UNAVAILABLE")
    defaults = command(["docker", "exec", "nexgrid-mysql", "mysql", "--print-defaults"]).decode()
    require(not re.search(r"(?:^|\s)(?:--force(?:=\S+)?|-f)(?:\s|$)", defaults),
            "MYSQL_FORCE_OPTION_PRESENT")
    query = (
        "SELECT "
        "(SELECT COUNT(*) FROM information_schema.innodb_trx),"
        "(SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='nexion' "
        "AND table_name IN ('" + "','".join(TABLES) + "') AND engine='InnoDB'),"
        "(SELECT COUNT(*) FROM information_schema.columns WHERE table_schema='nexion' "
        "AND table_name IN ('" + "','".join(TABLES) + "')),"
        + ",".join("(SELECT COUNT(*) FROM nexion.`" + table + "`)" for table in TABLES) + ";"
    )
    output = command(["docker", "exec", "-i", "-e", "MYSQL_PWD", "nexgrid-mysql",
                      "mysql", "--no-defaults", "--user=root", "--batch",
                      "--skip-column-names", "nexion"],
                     env={**os.environ, "MYSQL_PWD": password}, input=query.encode())
    require(output.decode().strip().split("\t") == ["0", "3", "13", "0", "0", "0"],
            "H3_DB_POSTFAIL_STATE_CHANGED")
    shape_sql = (
        "SELECT CONCAT(table_name,'|',ordinal_position,'|',column_name,'|',column_type,"
        "'|',is_nullable,'|',column_key,'|',extra) FROM information_schema.columns "
        "WHERE table_schema='nexion' AND table_name IN ('" + "','".join(TABLES)
        + "') ORDER BY table_name,ordinal_position;"
    )
    shape = command(["docker", "exec", "-i", "-e", "MYSQL_PWD", "nexgrid-mysql",
                     "mysql", "--no-defaults", "--user=root", "--batch",
                     "--skip-column-names", "nexion"],
                    env={**os.environ, "MYSQL_PWD": password}, input=shape_sql.encode())
    expected = {
        "nx_growth_mission_business_gate_rollout|1|mission_code|varchar(64)|NO|PRI|",
        "nx_growth_mission_business_gate_rollout|2|first_applied_at|datetime(3)|NO||",
        "nx_growth_mission_gate_pause_receipt|1|id|bigint|NO|PRI|auto_increment",
        "nx_growth_mission_gate_pause_receipt|2|mission_id|bigint|NO|MUL|",
        "nx_growth_mission_gate_pause_receipt|3|mission_code|varchar(64)|NO||",
        "nx_growth_mission_gate_pause_receipt|4|reason|varchar(64)|NO||",
        "nx_growth_mission_gate_pause_receipt|5|previous_status|tinyint|NO||",
        "nx_growth_mission_gate_pause_receipt|6|paused_at|datetime(3)|NO||",
        "nx_growth_quest_binding_quarantine_receipt|1|binding_id|bigint|NO|PRI|",
        "nx_growth_quest_binding_quarantine_receipt|2|binding_code|varchar(48)|NO||",
        "nx_growth_quest_binding_quarantine_receipt|3|previous_quest_code|varchar(64)|NO||",
        "nx_growth_quest_binding_quarantine_receipt|4|previous_event_type|varchar(128)|NO||",
        "nx_growth_quest_binding_quarantine_receipt|5|quarantined_at|datetime(3)|NO||",
    }
    require(set(shape.decode().splitlines()) == expected, "H3_TABLE_SHAPE_CHANGED")
    index_sql = (
        "SELECT CONCAT(table_name,'|',index_name,'|',seq_in_index,'|',column_name,'|',non_unique) "
        "FROM information_schema.statistics WHERE table_schema='nexion' AND table_name IN ('"
        + "','".join(TABLES) + "') ORDER BY table_name,index_name,seq_in_index;"
    )
    indexes = command(["docker", "exec", "-i", "-e", "MYSQL_PWD", "nexgrid-mysql",
                       "mysql", "--no-defaults", "--user=root", "--batch",
                       "--skip-column-names", "nexion"],
                      env={**os.environ, "MYSQL_PWD": password}, input=index_sql.encode())
    require(set(indexes.decode().splitlines()) == {
        "nx_growth_mission_business_gate_rollout|PRIMARY|1|mission_code|0",
        "nx_growth_mission_gate_pause_receipt|PRIMARY|1|id|0",
        "nx_growth_mission_gate_pause_receipt|idx_h3_pause_mission|1|mission_id|1",
        "nx_growth_mission_gate_pause_receipt|idx_h3_pause_mission|2|paused_at|1",
        "nx_growth_quest_binding_quarantine_receipt|PRIMARY|1|binding_id|0",
    }, "H3_TABLE_INDEX_CHANGED")
    require(command(["systemctl", "show", "nexgrid-backend", "-p", "ActiveState", "--value"]).strip()
            == b"inactive", "BACKEND_ACTIVE")
    require(command(["systemctl", "show", "nexgrid-release.timer", "-p", "ActiveState", "--value"]).strip()
            == b"inactive", "RELEASE_TIMER_ACTIVE")


def verify_failed_state():
    trusted(ROOT, directory=True)
    trusted(FOLDER, directory=True)
    require(read_json(ROOT / "START_BLOCKED") == {"reason": "DATABASE_MIGRATION_HOLD"},
            "BACKEND_HOLD_CHANGED")
    trusted(GUARD)
    require(GUARD.read_text() == GUARD_TEXT, "BACKEND_GUARD_CHANGED")
    require(not (CD / "transaction.json").exists() and not HALT.exists(), "BROKER_BUSY_OR_HALTED")
    require(not Path("/run/nexgrid-backend-migration-start").exists(), "BACKEND_START_PERMIT_PRESENT")
    active = read_json(ROOT / "active.json")
    require(active.get("phase") == "SQL_FAILED" and active.get("sha") == OLD_SHA
            and active.get("database") == "nexion" and active.get("current") == FAILED
            and active.get("folder") == str(FOLDER), "FAILED_JOURNAL_CHANGED")
    require(len(active.get("completed", [])) == 15
            and len(set(active["completed"])) == 15
            and active.get("pending", [])[:15] == active["completed"]
            and active["pending"][15:] == OLD_PENDING_SUFFIX,
            "FAILED_ORDER_CHANGED")
    require(active.get("backup", {}).get("path") == str(FOLDER / "database.sql.gz")
            and active["backup"].get("sha256") == BACKUP_HASH
            and active["backup"].get("uncompressed_bytes") == BACKUP_BYTES,
            "BACKUP_RECEIPT_CHANGED")
    require(read_json(FOLDER / "receipt.json") == active, "FAILED_RECEIPT_CHANGED")
    require(sha256(FOLDER / FAILED) == OLD_SQL_HASH, "FAILED_SQL_CHANGED")
    error = (FOLDER / (FAILED + ".stderr"))
    trusted(error)
    require(b"ERROR 1137 (HY000)" in error.read_bytes()
            and b"Can't reopen table: 'e'" in error.read_bytes(), "FAILED_ERROR_CHANGED")
    verify_state_against_failure(active)
    verify_backup()
    verify_db()
    return active


def verify_state_against_failure(active):
    before = read_json(FOLDER / "state-before.json")
    state = read_json(ROOT / "state.json")
    old = before.get("scripts", {})
    now = state.get("scripts", {})
    require(before.get("version") == state.get("version") == 1
            and before.get("database") == state.get("database") == "nexion"
            and len(old) == 262 and len(now) == 277
            and FAILED not in now and all(now.get(name) == row for name, row in old.items())
            and set(now) - set(old) == set(active["completed"]), "STATE_DELTA_CHANGED")
    for name in active["completed"]:
        row = now[name]
        require(row.get("status") == "APPLIED" and row.get("sha") == OLD_SHA
                and row.get("backup") == str(FOLDER / "database.sql.gz")
                and sha256(FOLDER / name) == row.get("sha256"),
                "COMPLETED_MIGRATION_CHANGED")
    return state


def audit_archived(expected_sha):
    trusted(ROOT, directory=True)
    trusted(ARCHIVE, directory=True)
    require(not (ROOT / "active.json").exists(), "NEW_MIGRATION_ALREADY_STARTED")
    require(not (CD / "transaction.json").exists(), "BROKER_BUSY")
    require(read_json(ROOT / "START_BLOCKED") == {"reason": "DATABASE_MIGRATION_HOLD"},
            "BACKEND_HOLD_CHANGED")
    trusted(GUARD)
    require(GUARD.read_text() == GUARD_TEXT, "BACKEND_GUARD_CHANGED")
    intent = read_json(INTENT)
    require(intent.get("old_sha") == OLD_SHA and intent.get("new_sha") == expected_sha
            and intent.get("failed") == FAILED and intent.get("phase") in
            ("ARCHIVED", "READY_FOR_MANUAL_FORWARD_DEPLOY"), "RECOVERY_INTENT_CHANGED")
    archived = read_json(ARCHIVE / "active.json")
    require(archived.get("phase") == "SQL_FAILED" and archived.get("sha") == OLD_SHA
            and archived.get("current") == FAILED, "ARCHIVED_JOURNAL_CHANGED")
    require(read_json(FOLDER / "receipt.json") == archived
            and sha256(FOLDER / FAILED) == OLD_SQL_HASH, "ARCHIVED_RECEIPT_CHANGED")
    state = verify_state_against_failure(archived)
    require(read_json(CD / "state.json").get("backend") == intent.get("backend_release_before"),
            "BACKEND_RELEASE_STATE_CHANGED")
    require(verify_candidate(expected_sha, state) == intent.get("new_sql_sha256"),
            "CANDIDATE_CHANGED")
    verify_backup()
    verify_db()
    return intent


def arm(new_sha):
    active = verify_failed_state()
    current_release = read_json(CD / "state.json").get("backend")
    require(isinstance(current_release, dict) and current_release.get("build") == 96,
            "BACKEND_RELEASE_CHANGED")
    candidate_sql_hash = verify_candidate(new_sha, read_json(ROOT / "state.json"))
    require(not ARCHIVE.exists() and not INTENT.exists(), "RECOVERY_ALREADY_STARTED")
    # The HALT is durable before the failed active file is moved. Any crash keeps
    # both the backend start guard and a broker-level hold.
    durable_json(INTENT, {"phase": "ARMING", "old_sha": OLD_SHA, "new_sha": new_sha,
                          "failed": FAILED, "old_sql_sha256": OLD_SQL_HASH,
                          "backup_sha256": BACKUP_HASH,
                          "new_sql_sha256": candidate_sql_hash,
                          "backend_release_before": current_release})
    durable_json(HALT, {"reason": "H3_1137_MANUAL_FORWARD_RECOVERY", "sha": new_sha})
    ARCHIVE.mkdir(mode=0o700)
    sync(ROOT)
    for name in ("active.json",):
        source = ROOT / name
        target = ARCHIVE / name
        with source.open("rb") as src, target.open("xb") as dst:
            dst.write(src.read())
            dst.flush()
            os.fsync(dst.fileno())
        target.chmod(0o600)
    sync(ARCHIVE)
    require(read_json(ARCHIVE / "active.json") == active, "ARCHIVE_COPY_CHANGED")
    (ROOT / "active.json").replace(ARCHIVE / "active.json")
    sync(ROOT)
    sync(ARCHIVE)
    durable_json(INTENT, {**read_json(INTENT), "phase": "ARCHIVED"})


def release(new_sha):
    intent = audit_archived(new_sha)
    if HALT.exists():
        require(read_json(HALT) ==
                {"reason": "H3_1137_MANUAL_FORWARD_RECOVERY", "sha": new_sha},
                "RECOVERY_HALT_CHANGED")
        if intent["phase"] == "ARCHIVED":
            durable_json(INTENT, {**intent, "phase": "READY_FOR_MANUAL_FORWARD_DEPLOY"})
        HALT.unlink()
        sync(CD)
    else:
        require(intent["phase"] == "READY_FOR_MANUAL_FORWARD_DEPLOY",
                "RECOVERY_HALT_MISSING")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", nargs="?", choices=("audit", "arm", "release"), default="audit")
    parser.add_argument("--new-sha")
    parser.add_argument("--trusted-tool-sha256")
    args = parser.parse_args()
    require(os.geteuid() == 0 and sys.flags.isolated, "ISOLATED_ROOT_REQUIRED")
    trusted(Path(__file__).absolute())
    if args.action != "audit":
        require(args.trusted_tool_sha256 and sha256(Path(__file__)) == args.trusted_tool_sha256,
                "EXTERNAL_TOOL_SHA_REQUIRED")
    import fcntl
    trusted(CD / "lock")
    with (CD / "lock").open("rb") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        require(args.action == "audit" or args.new_sha, "NEW_SHA_REQUIRED")
        if args.action == "audit":
            if (ROOT / "active.json").exists():
                verify_failed_state()
            else:
                audit_archived(read_json(INTENT)["new_sha"])
        elif args.action == "arm":
            arm(args.new_sha)
        else:
            release(args.new_sha)
    print("H3_1137_" + args.action.upper() + "_OK")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("H3_1137_RECOVERY_HELD: " + (str(error) if isinstance(error, RuntimeError)
                                         else type(error).__name__))
        raise SystemExit(1)

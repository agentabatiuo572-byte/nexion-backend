#!/usr/bin/env python3
"""TEST host admission and release drain. Install independently SHA-pinned, outside the broker closure."""
import argparse
from contextlib import closing, contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import subprocess
import sys
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

CONFIG = Path("/etc/uvel-task-control/budget.json")
RUNTIME = Path("/run/uvel-task-control")
STATE = Path("/var/lib/uvel-task-control")
JOBS = Path("/var/lib/docker/volumes/nexgrid_jenkins_home/_data/jobs")
RELEASE_STATE = Path("/srv/nexgrid/cd/state.json")
COMPONENTS = ("backend", "pc", "uniapp")
STOPPED_SPARE = "uvel-cloud-bge-model-fleet-7"


class Rejected(RuntimeError):
    pass


def require(condition, reason):
    if not condition:
        raise Rejected(reason)


def encoded(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def digest(value):
    return hashlib.sha256(encoded(value)).hexdigest()


def trusted(path, directory=False):
    for index, part in enumerate((path, *path.parents)):
        info = part.lstat()
        kind = stat.S_ISDIR(info.st_mode) if directory or index else stat.S_ISREG(info.st_mode)
        require(kind and info.st_uid == 0 and not info.st_mode & 0o022, "ROOT_OWNED_PATH_REQUIRED")


def atomic(path, value, mode=0o600):
    temp = path.with_name(path.name + ".tmp-" + uuid.uuid4().hex)
    try:
        with temp.open("xb") as stream:
            os.chmod(temp, mode)
            stream.write(encoded(value) + b"\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp, path)
        fd = os.open(path.parent, os.O_DIRECTORY)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)
    finally:
        temp.unlink(missing_ok=True)


@contextmanager
def locked():
    import fcntl
    with (STATE / "control.lock").open("a+b") as stream:
        fcntl.flock(stream, fcntl.LOCK_EX)
        yield


def run(*arguments):
    return subprocess.check_output(arguments, text=True, timeout=30, stderr=subprocess.DEVNULL).strip()


def load_config():
    trusted(CONFIG)
    value = json.loads(CONFIG.read_bytes())
    require(value.get("version") == 1 and value.get("scope") == "TEST", "TEST_BUDGET_REQUIRED")
    require(type(value.get("buildBudgetBytes")) is int and value["buildBudgetBytes"] > 0
            and type(value.get("maxActiveExecutors")) is int and value["maxActiveExecutors"] > 0,
            "BUDGET_INVALID")
    require(isinstance(value.get("commitments"), list) and value["commitments"], "COMMITMENTS_REQUIRED")
    names = set()
    for entry in value["commitments"]:
        require(isinstance(entry.get("name"), str) and entry["name"] not in names
                and entry.get("kind") in ("backend", "model", "database", "other")
                and type(entry.get("bytes")) is int and entry["bytes"] > 0, "COMMITMENT_INVALID")
        names.add(entry["name"])
        require(entry["kind"] not in ("backend", "model") or entry.get("control", {}).get("type") in ("systemd", "docker"),
                "BUSINESS_LIMIT_REQUIRED")
    require(sum(entry["kind"] == "backend" for entry in value["commitments"]) == 1
            and sum(entry["kind"] == "model" for entry in value["commitments"]) >= value["maxActiveExecutors"],
            "EXECUTOR_RESERVATION_REQUIRED")
    require(value.get("buildControl", {}).get("type") == "docker", "CI_CONTAINER_LIMIT_REQUIRED")
    require(set(value.get("buildRequests", {})) == set(COMPONENTS)
            and all(type(size) is int and 0 < size <= value["buildBudgetBytes"] for size in value["buildRequests"].values()),
            "BUILD_REQUESTS_INVALID")
    require(isinstance(value.get("workerServices"), list) and value["workerServices"], "WORKER_INVENTORY_REQUIRED")
    for service in value["workerServices"]:
        require(re.fullmatch(r"uvel-cloud-bge-(?:worker|fleet)\.service", service.get("name", "")) is not None
                and isinstance(service.get("journalRoots"), list) and service["journalRoots"], "WORKER_SERVICE_INVALID")
        for root in service["journalRoots"]:
            require(Path(root).is_absolute() and root.startswith("/var/lib/uvel-cloud-bge-"), "JOURNAL_ROOT_INVALID")
    stopped_controls(value)
    return value


def stopped_controls(config):
    controls = config.get("stoppedControls", [])
    require(isinstance(controls, list) and len(controls) <= 1
            and all(isinstance(entry, dict) and entry == {"type": "docker", "name": STOPPED_SPARE}
                    for entry in controls), "STOPPED_CONTROL_INVALID")
    return controls


def memory():
    fields = {}
    for line in Path("/proc/meminfo").read_text().splitlines():
        key, raw = line.split(":", 1)
        if key in ("MemTotal", "MemAvailable"):
            fields[key] = int(raw.split()[0]) * 1024
    require(set(fields) == {"MemTotal", "MemAvailable"}, "HOST_MEMORY_UNAVAILABLE")
    return fields["MemTotal"], fields["MemAvailable"]


def verify_limit(control, size):
    require(isinstance(control, dict) and re.fullmatch(r"[a-zA-Z0-9_.@-]+", control.get("name", "")) is not None,
            "RESOURCE_CONTROL_INVALID")
    if control["type"] == "docker":
        actual = json.loads(run("docker", "inspect", "--format", "{{json .HostConfig}}", control["name"]))
        require(actual.get("Memory") == size and actual.get("MemorySwap") == size, "CONTAINER_MEMORY_LIMIT_DRIFT")
    elif control["type"] == "systemd":
        require(run("systemctl", "show", control["name"], "--property=MemoryMax", "--value") == str(size),
                "SERVICE_MEMORY_LIMIT_DRIFT")
    else:
        raise Rejected("RESOURCE_CONTROL_INVALID")


def verify_limits(config):
    for control in stopped_controls(config):
        actual = json.loads(run("docker", "inspect", "--format",
                                '{"running":{{json .State.Running}},"restart":{{json .HostConfig.RestartPolicy.Name}}}',
                                control["name"]))
        require(actual.get("running") is False, "STOPPED_CONTAINER_RUNNING")
        require(actual.get("restart") in ("no", "unless-stopped"), "STOPPED_CONTAINER_RESTART_POLICY_REJECTED")
    for entry in config["commitments"]:
        if entry.get("control"):
            verify_limit(entry["control"], entry["bytes"])
    verify_limit(config["buildControl"], config["buildBudgetBytes"])


def capacity(config, physical, available, builds, now):
    reserve = (physical + 4) // 5
    committed = sum(entry["bytes"] for entry in config["commitments"]) + config["buildBudgetBytes"]
    return {"version": 1, "observedAtEpochSeconds": now, "expiresAtEpochSeconds": now + 15,
            "maxActiveExecutors": config["maxActiveExecutors"], "physicalMemoryBytes": physical,
            "availableMemoryBytes": available, "reserveBytes": reserve, "committedBytes": committed,
            "outstandingBuildBytes": builds,
            "allowNewActivations": committed <= physical - reserve and available - builds >= reserve}


def build_completed(component, number):
    path = JOBS / f"nexgrid-{component}-test/builds" / str(number) / "build.xml"
    if not path.is_file():
        return False
    raw = path.read_bytes()
    require(len(raw) < 8 * 1024 * 1024 and b"<!DOCTYPE" not in raw.upper() and b"<!ENTITY" not in raw.upper(),
            "BUILD_METADATA_INVALID")
    return ET.fromstring(raw).findtext("completed") == "true"


def reservations():
    path = STATE / "build-reservations.json"
    value = json.loads(path.read_bytes()) if path.exists() else {}
    remaining = {key: entry for key, entry in value.items() if not build_completed(entry["component"], entry["number"])}
    if remaining != value:
        atomic(path, remaining)
    return remaining


def check_build_admission(config, physical, available, outstanding, request):
    require(outstanding + request <= config["buildBudgetBytes"], "BUILD_BUDGET_EXHAUSTED")
    state = capacity(config, physical, available, outstanding + request, int(time.time()))
    require(state["committedBytes"] <= physical - state["reserveBytes"], "COMMITTED_BUDGET_EXCEEDS_HOST")
    require(state["allowNewActivations"], "HOST_MEMORY_RESERVE_REQUIRED")


def drain_pending():
    return any((STATE / name).exists() for name in ("drain.json", "drain-receipt.json"))


def snapshot(config):
    with locked():
        builds = sum(entry["bytes"] for entry in reservations().values())
        physical, available = memory()
        result = capacity(config, physical, available, builds, int(time.time()))
        try:
            verify_limits(config)
        except (Rejected, subprocess.SubprocessError, ValueError, OSError) as error:
            result["allowNewActivations"] = False
            result["resourceCheckError"] = str(error) if isinstance(error, Rejected) else type(error).__name__
        result["draining"] = drain_pending()
        if result["draining"]:
            result["allowNewActivations"] = False
        atomic(RUNTIME / "capacity.json", result, 0o644)
    return result


def admit_build(config, component, number):
    require(number > 0, "BUILD_NUMBER_REQUIRED")
    with locked():
        require(not drain_pending(), "RELEASE_DRAIN_ACTIVE")
        entries = reservations()
        key = f"{component}-{number}"
        require(key not in entries and not (JOBS / f"nexgrid-{component}-test/builds" / str(number)).exists(),
                "BUILD_ALREADY_RESERVED_OR_STARTED")
        verify_limits(config)
        physical, available = memory()
        size = config["buildRequests"][component]
        check_build_admission(config, physical, available, sum(entry["bytes"] for entry in entries.values()), size)
        entries[key] = {"component": component, "number": number, "bytes": size, "reservedAt": int(time.time())}
        atomic(STATE / "build-reservations.json", entries)
    snapshot(config)
    return {"buildReservation": key, "bytes": size}


def run_build(config, component, number, command):
    require(command and Path(command[0]).is_absolute(), "ROOT_BUILD_TRIGGER_AND_WAIT_REQUIRED")
    trusted(Path(command[0]))
    reservation = admit_build(config, component, number)
    # The protected provider triggers and waits for this exact Jenkins build.
    # Never print its command, environment, credentials or output.
    process = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=3600)
    require(build_completed(component, number), "BUILD_NOT_TERMINAL_RESERVATION_HELD")
    snapshot(config)
    require(process.returncode == 0, "BUILD_TRIGGER_OR_WAIT_FAILED")
    return {**reservation, "completed": True}


def journal_fingerprint(path):
    require(path.is_file() and not path.is_symlink(), "JOURNAL_FILE_INVALID")
    with closing(sqlite3.connect(path.resolve().as_uri() + "?mode=ro", uri=True, timeout=5)) as db:
        db.execute("BEGIN")
        require(db.execute("PRAGMA quick_check").fetchone()[0] == "ok", "JOURNAL_CORRUPT")
        rows = db.execute("SELECT value FROM state WHERE id=1").fetchall()
        require(len(rows) == 1, "JOURNAL_STATE_INVALID")
        state = json.loads(rows[0][0])
        calls = db.execute("SELECT * FROM calls ORDER BY id").fetchall()
        tables = db.execute("SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
        contents = []
        for name, schema in tables:
            require(re.fullmatch(r"[a-zA-Z0-9_]+", name) is not None, "JOURNAL_SCHEMA_INVALID")
            contents.append([name, schema, db.execute(f'SELECT * FROM "{name}" ORDER BY rowid').fetchall()])
    binding = state.get("binding")
    return {"path": str(path), "phase": state.get("phase"), "deviceId": binding.get("deviceId") if isinstance(binding, dict) else None,
            "bindingSha256": digest(binding), "stateSha256": digest(state), "callsSha256": digest(calls),
            "contentSha256": digest(contents)}


def inventory(config):
    paths = set()
    for service in config["workerServices"]:
        for item in service["journalRoots"]:
            root = Path(item)
            require(root.is_dir(), "JOURNAL_ROOT_MISSING_OR_UNSAFE")
            for part in (root, *root.parents):
                if part.is_symlink():
                    expected = Path("/var/lib/private") / part.name
                    require(part.parent == Path("/var/lib")
                            and part.name in ("uvel-cloud-bge-worker", "uvel-cloud-bge-fleet")
                            and part.lstat().st_uid == 0 and part.resolve() == expected
                            and expected.is_dir() and not expected.is_symlink(), "JOURNAL_ROOT_MISSING_OR_UNSAFE")
                    trusted(expected.parent, directory=True)
            paths.update(root.rglob("journal.sqlite3"))
    require(paths, "JOURNAL_INVENTORY_EMPTY")
    return [journal_fingerprint(path) for path in sorted(paths)]


def require_initial_journals(initial, current):
    require({entry["path"] for entry in initial} <= {entry["path"] for entry in current},
            "DRAIN_JOURNAL_MISSING")


def check_journals(journals, protected):
    entries = {entry["path"]: entry for entry in protected}
    require(len(entries) == len(protected), "PROTECTED_JOURNAL_DUPLICATE")
    seen = set()
    for journal in journals:
        expected = entries.get(journal["path"])
        if expected:
            require(journal["phase"] in expected["allowedPhases"]
                    and all(journal[key] == expected[key] for key in ("deviceId", "bindingSha256", "stateSha256", "callsSha256", "contentSha256")),
                    "PROTECTED_JOURNAL_CHANGED")
            seen.add(journal["path"])
        else:
            require(journal["phase"] in ("IDLE", "NEXT_NOT_SENT"), "JOURNAL_PENDING_RECONCILIATION")
    require(seen == set(entries), "PROTECTED_JOURNAL_MISSING")


def service_state(name):
    fields = run("systemctl", "show", name, "--property=ActiveState,MainPID").splitlines()
    return dict(item.split("=", 1) for item in fields)


def backend_pending():
    current = json.loads(RELEASE_STATE.read_bytes())["backend"]["build"]
    builds = JOBS / "nexgrid-backend-test/builds"
    numbers = [int(path.name) for path in builds.iterdir() if path.name.isdigit()]
    if not numbers or max(numbers) <= current:
        return False
    number = max(numbers)
    if not build_completed("backend", number):
        return False
    return ET.fromstring((builds / str(number) / "build.xml").read_bytes()).findtext("result") == "SUCCESS"


def drain(config, poll=False):
    with locked():
        require(not drain_pending(), "PREVIOUS_DRAIN_REQUIRES_REVIEW")
        if poll and not backend_pending():
            return {"drain": "NO_PENDING_BACKEND"}
        initial = inventory(config)
        protected = config.get("protectedJournals", [])
        # Check exact protected objects before changing any worker state; other work may still finish.
        check_journals([entry for entry in initial if entry["path"] in {p["path"] for p in protected}], protected)
        services = {entry["name"]: service_state(entry["name"])["ActiveState"] for entry in config["workerServices"]}
        require(all(value in ("active", "inactive", "failed") for value in services.values()), "WORKER_SERVICE_TRANSITIONING")
        receipt = {"version": 1, "drainId": uuid.uuid4().hex, "createdAt": int(time.time()),
                   "services": services, "protectedJournals": protected, "initialInventory": initial, "ready": False}
        atomic(STATE / "drain.json", {"version": 1, "drainId": receipt["drainId"]}, 0o644)
        atomic(STATE / "drain-receipt.json", receipt)
    snapshot(config)
    deadline = time.monotonic() + config.get("drainTimeoutSeconds", 300)
    observed = initial
    while True:
        try:
            current = inventory(config)
            require_initial_journals(observed, current)
            observed = current
            check_journals(current, protected)
            break
        except Rejected as error:
            if str(error) != "JOURNAL_PENDING_RECONCILIATION":
                raise
            require(time.monotonic() < deadline, "DRAIN_INFLIGHT_TIMEOUT")
            time.sleep(1)
    for name, active in services.items():
        if active == "active":
            run("systemctl", "kill", "--kill-whom=main", "--signal=SIGTERM", name)
    while True:
        states = [service_state(name) for name in services]
        if all(item["ActiveState"] in ("inactive", "failed") and item["MainPID"] == "0" for item in states):
            break
        require(time.monotonic() < deadline, "DRAIN_PROCESS_EXIT_TIMEOUT")
        time.sleep(1)
    final = inventory(config)
    require_initial_journals(observed, final)
    check_journals(final, protected)
    receipt.update(ready=True, finalInventory=final, readyAt=int(time.time()))
    with locked():
        atomic(STATE / "drain-receipt.json", receipt)
    return {"drain": "READY", "drainId": receipt["drainId"], "journals": final}


def resume(config):
    with locked():
        receipt_path, marker = STATE / "drain-receipt.json", STATE / "drain.json"
        if not receipt_path.exists() and not marker.exists():
            return {"drain": "NOT_REQUIRED"}
        require(receipt_path.exists() and marker.exists(), "DRAIN_RECEIPT_OR_MARKER_MISSING")
        receipt = json.loads(receipt_path.read_bytes())
        require(receipt.get("ready") is True and json.loads(marker.read_bytes())["drainId"] == receipt["drainId"], "DRAIN_NOT_READY")
        release_root = RELEASE_STATE.parent
        require(not any((release_root / name).exists() for name in
                        ("HALTED.json", "transaction.json", "migrations/active.json", "migrations/START_BLOCKED")),
                "RELEASE_RECOVERY_REQUIRES_REVIEW")
        require(service_state("nexgrid-backend.service")["ActiveState"] == "active", "BACKEND_NOT_ACTIVE")
        # poll() may return success after a verified rollback: health, not exit status, reopens claims.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        with opener.open("http://127.0.0.1:8110/api/config/platform", timeout=5) as response:
            body = response.read(1024 * 1024 + 1)
        require(len(body) <= 1024 * 1024, "BACKEND_HEALTH_INVALID")
        health = json.loads(body)
        require(health.get("code") == 0 and health.get("data") is not None, "BACKEND_HEALTH_INVALID")
        require(inventory(config) == receipt["finalInventory"], "DRAINED_JOURNAL_CHANGED")
        for name, previous in receipt["services"].items():
            if previous == "active":
                run("systemctl", "start", name)
                require(service_state(name)["ActiveState"] == "active", "WORKER_RESUME_FAILED")
        atomic(STATE / "last-drain-receipt.json", {**receipt, "resumedAt": int(time.time())})
        receipt_path.unlink()
        marker.unlink()
        descriptor = os.open(STATE, os.O_DIRECTORY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
    snapshot(config)
    return {"drain": "RESUMED", "drainId": receipt["drainId"]}


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise Rejected("BACKEND_HEALTH_REDIRECT_REJECTED")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="action", required=True)
    for action in ("snapshot", "inventory", "drain", "prepare-release-poll", "resume-release"):
        sub.add_parser(action)
    for action in ("admit-build", "run-build"):
        build = sub.add_parser(action)
        build.add_argument("component", choices=COMPONENTS)
        build.add_argument("number", type=int)
        if action == "run-build":
            build.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    require(os.geteuid() == 0 and sys.flags.isolated, "ISOLATED_ROOT_ENTRY_REQUIRED")
    trusted(Path(__file__))
    for directory in (RUNTIME, STATE):
        directory.mkdir(mode=0o755, exist_ok=True)
        trusted(directory, directory=True)
    config = load_config()
    if args.action == "snapshot":
        result = snapshot(config)
    elif args.action == "inventory":
        result = inventory(config)
    elif args.action == "admit-build":
        result = admit_build(config, args.component, args.number)
    elif args.action == "run-build":
        command = args.command[1:] if args.command[:1] == ["--"] else args.command
        result = run_build(config, args.component, args.number, command)
    elif args.action == "resume-release":
        result = resume(config)
    else:
        result = drain(config, poll=args.action == "prepare-release-poll")
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        reason = str(error) if isinstance(error, Rejected) else type(error).__name__
        print("RESOURCE_CONTROL_FAILED: " + reason, file=sys.stderr)
        raise SystemExit(1)

#!/usr/bin/env python3
"""Operator-only TEST CI budget exchange. Default action is a read-only plan."""
import argparse
import base64
from contextlib import contextmanager
import copy
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import urllib.request
import uuid
import xml.etree.ElementTree as ET

GIB = 1024 ** 3
BASELINE_SHA = "1b66c15b0b03c8ee17e9a46219027e9f3030f963beac448bab4bc3573e0f52b8"
RESOURCE_SHA = "b2c053f53ca95d805f7d413d6d8449254ae1cbe7369115761626a2b56fcf52a9"
RESOURCE = Path("/opt/uvel-task-control/resource-control.py")
BASELINE = Path("/opt/uvel-task-control/resource-control.pre-compute-v2.py")
RELEASE = Path("/srv/nexgrid/cd")
RECEIPT_NAME = "ci-maintenance.json"
CI = "nexgrid-ci-agent"
FLEET = {f"uvel-cloud-bge-model-fleet-{number}": 11435 + number for number in range(1, 7)}
HOLD_REASON = "COMPUTE_V2_CI_MAINTENANCE"
INSPECT = ('{"id":{{json .Id}},"name":{{json .Name}},"image":{{json .Image}},'
           '"running":{{json .State.Running}},"paused":{{json .State.Paused}},"pid":{{json .State.Pid}},'
           '"memory":{{json .HostConfig.Memory}},"swap":{{json .HostConfig.MemorySwap}},'
           '"ports":{{json .HostConfig.PortBindings}}}')


class Rejected(RuntimeError):
    pass


def require(condition, reason):
    if not condition:
        raise Rejected(reason)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def command(*args):
    return subprocess.check_output(args, text=True, timeout=90, stderr=subprocess.DEVNULL).strip()


def trusted(path, directory=False):
    import stat
    for index, part in enumerate((path, *path.parents)):
        info = part.lstat()
        kind = stat.S_ISDIR(info.st_mode) if directory or index else stat.S_ISREG(info.st_mode)
        require(kind and info.st_uid == 0 and not info.st_mode & 0o022, "ROOT_OWNED_PATH_REQUIRED")


def load_control(baseline=BASELINE):
    for path, expected in ((baseline, BASELINE_SHA), (RESOURCE, RESOURCE_SHA)):
        trusted(path)
        require(sha(path.read_bytes()) == expected, "RESOURCE_SOURCE_PIN_MISMATCH")
    spec = importlib.util.spec_from_file_location("uvel_reviewed_resource_control", RESOURCE)
    control = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(control)
    return control


@contextmanager
def operation_lock(control):
    # Serialize whole maintenance operations; snapshot/admission do not use this lock.
    import fcntl
    trusted(control.STATE, directory=True)
    path = control.STATE / "maintenance-operation.lock"
    if path.exists() or path.is_symlink():
        trusted(path)
    fd = os.open(path, os.O_CREAT | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
    with os.fdopen(fd, "ab") as stream:
        trusted(path)
        try:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            raise Rejected("MAINTENANCE_OPERATION_ALREADY_RUNNING") from None
        try:
            yield
        finally:
            fcntl.flock(stream, fcntl.LOCK_UN)


@contextmanager
def locks(control):
    # Same order and same inodes as the real release and resource controllers.
    import fcntl
    trusted(RELEASE / "lock")
    control.trusted(control.STATE / "control.lock")
    with (RELEASE / "lock").open("a") as release:
        fcntl.flock(release, fcntl.LOCK_EX | fcntl.LOCK_NB)
        with control.locked():
            yield


def sync_directory(path):
    fd = os.open(path, os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


def atomic_bytes(path, raw, mode):
    temporary = path.with_name(path.name + ".ci-" + uuid.uuid4().hex)
    try:
        with temporary.open("xb") as stream:
            os.chmod(temporary, mode)
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
        sync_directory(path.parent)
    finally:
        temporary.unlink(missing_ok=True)


def maintenance_budget(original):
    require(original.get("scope") == "TEST" and original.get("version") == 1, "TEST_BUDGET_REQUIRED")
    require(original.get("buildBudgetBytes") == GIB and original.get("buildsEnabled") is False
            and original.get("maxActiveExecutors") == 6
            and original.get("buildControl") == {"type": "docker", "name": CI}
            and original.get("buildRequests") == {kind: GIB for kind in ("backend", "pc", "uniapp")},
            "KNOWN_IDLE_CI_BUDGET_REQUIRED")
    entries = original.get("commitments", [])
    expected = {
        "backend": ("backend", 4 * GIB, {"type": "systemd", "name": "nexgrid-backend.service"}),
        "business-mysql": ("database", 7 * GIB // 2, {"type": "docker", "name": "nexgrid-mysql"}),
        "other-host-work": ("other", 11 * GIB // 2, None),
        "uvel-cloud-bge-model": ("model", 3 * GIB // 2, {"type": "docker", "name": "uvel-cloud-bge-model"}),
        **{name: ("model", 3 * GIB // 2, {"type": "docker", "name": name}) for name in FLEET}}
    require(len(entries) == len(expected) and {entry.get("name") for entry in entries} == set(expected),
            "KNOWN_COMMITMENTS_REQUIRED")
    for entry in entries:
        kind, size, limit = expected[entry["name"]]
        require((entry.get("kind"), entry.get("bytes"), entry.get("control")) == (kind, size, limit),
                "KNOWN_COMMITMENTS_REQUIRED")
    updated = copy.deepcopy(original)
    updated.update(buildBudgetBytes=3 * GIB, buildRequests={kind: 3 * GIB for kind in original["buildRequests"]},
                   buildsEnabled=True, maxActiveExecutors=1)
    updated["commitments"] = [entry for entry in updated["commitments"] if entry["name"] not in FLEET]
    require(sum(entry["bytes"] for entry in updated["commitments"]) + updated["buildBudgetBytes"] == 35 * GIB // 2,
            "MAINTENANCE_BUDGET_TOTAL_INVALID")
    return updated


def inspect(name):
    value = json.loads(command("docker", "inspect", "--format", INSPECT, name))
    require(re.fullmatch(r"[a-f0-9]{64}", value.get("id", "")) is not None
            and re.fullmatch(r"sha256:[a-f0-9]{64}", value.get("image", "")) is not None
            and type(value.get("running")) is bool and type(value.get("paused")) is bool
            and type(value.get("pid")) is int and value["pid"] >= 0, "CONTAINER_IDENTITY_INVALID")
    return value


def same_container(original, current, limits):
    require(all(current[key] == original[key] for key in ("id", "name", "image", "ports")), "CONTAINER_IDENTITY_CHANGED")
    require((current["memory"], current["swap"]) in limits, "CONTAINER_LIMIT_CHANGED")


def workers_stopped(control, original):
    for worker in original["workerServices"]:
        state = control.service_state(worker["name"])
        require(state.get("ActiveState") == "inactive" and state.get("MainPID") == "0", "WORKER_MUST_BE_GRACEFULLY_STOPPED")


def release_stopped(control):
    require(control.service_state("nexgrid-release.timer").get("ActiveState") == "inactive", "RELEASE_TIMER_MUST_BE_STOPPED")
    state = control.service_state("nexgrid-release.service")
    require(state.get("ActiveState") == "inactive" and state.get("MainPID") == "0", "RELEASE_SERVICE_MUST_BE_STOPPED")


def archived_xml(path):
    with path.open("rb") as stream:
        raw = stream.read(8 * 1024 * 1024 + 1)
    require(len(raw) < 8 * 1024 * 1024, "ARCHIVED_JENKINS_XML_INVALID")
    try:
        text = raw.decode("utf-8-sig")
        require("<!DOCTYPE" not in text.upper() and "<!ENTITY" not in text.upper(), "ARCHIVED_JENKINS_XML_INVALID")
        return ET.fromstring(text)
    except (UnicodeError, ET.ParseError):
        raise Rejected("ARCHIVED_JENKINS_XML_INVALID") from None


def ci_idle(control, identity):
    expected = {f"nexgrid-{kind}-test" for kind in control.COMPONENTS}
    archived = {f"nexgrid-{kind}-main" for kind in control.COMPONENTS}
    jobs = {path.name for path in control.JOBS.iterdir() if path.is_dir()}
    require(expected <= jobs <= expected | archived, "UNEXPECTED_JENKINS_JOBS")
    for job in sorted(jobs & archived):
        config = archived_xml(control.JOBS / job / "config.xml")
        require(config.tag == "flow-definition" and config.findtext("disabled", "").strip() == "true",
                "ARCHIVED_MAIN_JOB_MUST_BE_DISABLED")
        builds = control.JOBS / job / "builds"
        numbers = [path for path in builds.iterdir() if path.name.isdigit()]
        require(numbers and all(archived_xml(path / "build.xml").findtext("completed") == "true" for path in numbers),
                "WAIT_FOR_TERMINAL_ARCHIVED_BUILDS")
    for job in expected:
        numbers = [int(path.name) for path in (control.JOBS / job / "builds").iterdir() if path.name.isdigit()]
        require(numbers and all(control.build_completed(job.removeprefix("nexgrid-").removesuffix("-test"), number)
                                for number in numbers), "WAIT_FOR_TERMINAL_BUILDS")
    path = control.STATE / "build-reservations.json"
    pending = json.loads(path.read_bytes()) if path.exists() else {}
    require(all(control.build_completed(entry["component"], entry["number"]) for entry in pending.values()),
            "WAIT_FOR_BUILD_RESERVATIONS")
    processes = command("docker", "top", identity["id"], "-eo", "pid,comm").splitlines()
    require(len(processes) > 1 and all(len(row.split()) == 2 and row.split()[0].isdigit()
            and row.split()[1] in {"tini", "docker-init", "java"} for row in processes[1:])
            and sum(row.split()[1] == "java" for row in processes[1:]) == 1
            and len(processes) <= 4, "WAIT_FOR_IDLE_AGENT")


def hold(receipt):
    return {"version": 1, "scope": "TEST", "reason": HOLD_REASON, "taskId": receipt["plan"]["taskId"],
            "receiptSha256": receipt["receiptSha256"]}


def no_transactions(control, receipt=None):
    require(not control.drain_pending(), "DRAIN_MARKER_REQUIRES_ORIGINAL_RELEASE_REVIEW")
    require(not any((RELEASE / name).exists() or (RELEASE / name).is_symlink() for name in
                    ("transaction.json", "HALTED", "migrations/active.json", "migrations/START_BLOCKED")), "RELEASE_TRANSACTION_PRESENT")
    marker = RELEASE / "HALTED.json"
    if receipt is None:
        require(not marker.exists() and not marker.is_symlink(), "EXISTING_RELEASE_HOLD")
    else:
        trusted(marker)
        require(json.loads(marker.read_bytes()) == hold(receipt), "MAINTENANCE_HOLD_OWNERSHIP_CHANGED")


def inventory_unchanged(control, original, expected=None):
    inventory = control.inventory(original)
    control.check_journals(inventory, original.get("protectedJournals", []))
    if expected is not None:
        require(inventory == expected, "STOPPED_JOURNAL_CHANGED")
    return inventory


def port_digest(control, port):
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), control.NoRedirect())
    with opener.open(f"http://127.0.0.1:{port}/api/tags", timeout=5) as response:
        raw = response.read(1024 * 1024 + 1)
    require(len(raw) <= 1024 * 1024, "MODEL_HEALTH_INVALID")
    models = [model for model in json.loads(raw).get("models", []) if model.get("name") == "bge-m3:567m"]
    require(len(models) == 1, "MODEL_HEALTH_INVALID")
    digest = models[0].get("digest", "").removeprefix("sha256:")
    require(re.fullmatch(r"[a-f0-9]{64}", digest) is not None, "MODEL_HEALTH_INVALID")
    return digest


def prepare(control, budget_sha):
    require(re.fullmatch(r"[a-f0-9]{64}", budget_sha or "") is not None, "REVIEWED_BUDGET_SHA_REQUIRED")
    original = control.load_config()
    raw = control.CONFIG.read_bytes()
    require(sha(raw) == budget_sha and json.loads(raw) == original, "BUDGET_PIN_MISMATCH")
    updated = maintenance_budget(original)
    no_transactions(control)
    release_stopped(control)
    workers_stopped(control, original)
    inventory = inventory_unchanged(control, original)
    ci = inspect(CI)
    require(ci["name"] == "/" + CI and ci["running"] and not ci["paused"] and ci["pid"] > 0
            and (ci["memory"], ci["swap"]) == (GIB, GIB), "KNOWN_IDLE_AGENT_REQUIRED")
    ci_idle(control, ci)
    control.verify_limits(original)
    models = []
    for name, port in FLEET.items():
        model = inspect(name)
        require(model["name"] == "/" + name and model["running"] and not model["paused"] and model["pid"] > 0
                and (model["memory"], model["swap"]) == (3 * GIB // 2, 3 * GIB // 2)
                and model["ports"] == {"11434/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(port)}]},
                "KNOWN_RUNNING_FLEET_REQUIRED")
        models.append({**model, "port": port, "modelDigest": port_digest(control, port)})
    physical, available = control.memory()
    require(control.capacity(updated, physical, available, 0, int(time.time()))["allowHeavyBuilds"], "HOST_MEMORY_RESERVE_REQUIRED")
    return {"taskId": uuid.uuid4().hex, "scope": "TEST", "resourceSha256": RESOURCE_SHA,
            "originalBudget": base64.b64encode(raw).decode(), "originalBudgetSha256": sha(raw),
            "budgetMode": control.CONFIG.stat().st_mode & 0o777, "maintenanceBudget": updated,
            "inventory": inventory, "ci": ci, "models": models}


def public_plan(plan):
    return {"action": "plan", "scope": "TEST", "budgetSha256": plan["originalBudgetSha256"],
            "stopModels": list(FLEET), "retainValidator": "uvel-cloud-bge-model", "ciMemoryBytes": 3 * GIB,
            "committedBytes": 35 * GIB // 2, "maxActiveExecutors": 1, "workerRestarted": False}


def decoded(receipt):
    plan = receipt["plan"]
    require(receipt.get("version") == 1 and plan.get("scope") == "TEST" and plan.get("resourceSha256") == RESOURCE_SHA
            and sha(json.dumps(plan, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()) == receipt.get("receiptSha256"),
            "MAINTENANCE_RECEIPT_INVALID")
    raw = base64.b64decode(plan["originalBudget"], validate=True)
    require(sha(raw) == plan["originalBudgetSha256"], "ORIGINAL_BUDGET_CORRUPT")
    original = json.loads(raw)
    require(maintenance_budget(original) == plan["maintenanceBudget"], "MAINTENANCE_RECEIPT_INVALID")
    require(len(plan["models"]) == 6 and {model["name"] for model in plan["models"]} == {"/" + name for name in FLEET},
            "MAINTENANCE_RECEIPT_INVALID")
    disabled = {**plan["maintenanceBudget"], "buildsEnabled": False}
    return original, raw, disabled


def save(control, receipt, phase):
    with locks(control):
        no_transactions(control, receipt)
        receipt["phase"] = phase
        control.atomic(control.STATE / RECEIPT_NAME, receipt)


def write_budget(control, receipt, raw, allowed):
    with locks(control):
        no_transactions(control, receipt)
        control.trusted(control.CONFIG)
        require(sha(control.CONFIG.read_bytes()) in allowed, "BUDGET_CHANGED_OUTSIDE_MAINTENANCE")
        atomic_bytes(control.CONFIG, raw, receipt["plan"]["budgetMode"])


def pause_ci(control, receipt):
    ci = receipt["plan"]["ci"]
    current = inspect(CI)
    same_container(ci, current, {(GIB, GIB), (3 * GIB, 3 * GIB)})
    require(current["running"] and current["pid"] > 0, "CI_AGENT_NOT_RUNNING")
    ci_idle(control, ci)
    save(control, receipt, "PAUSING_CI")
    if not current["paused"]:
        command("docker", "pause", ci["id"])
    try:
        ci_idle(control, ci)
    except Exception:
        # A build that raced the first idle check may finish under the unchanged budget.
        if not current["paused"]:
            command("docker", "unpause", ci["id"])
        raise
    same_container(ci, inspect(CI), {(GIB, GIB), (3 * GIB, 3 * GIB)})
    require(inspect(CI)["paused"], "CI_PAUSE_NOT_CONFIRMED")


def unpause_ci(receipt):
    original = receipt["plan"]["ci"]
    current = inspect(CI)
    same_container(original, current, {(original["memory"], original["swap"]), (3 * GIB, 3 * GIB)})
    if current["paused"]:
        command("docker", "unpause", original["id"])
    require(not inspect(CI)["paused"], "CI_RESUME_NOT_CONFIRMED")


def models_stopped(receipt):
    for original in receipt["plan"]["models"]:
        current = inspect(original["name"].removeprefix("/"))
        same_container(original, current, {(original["memory"], original["swap"])})
        require(not current["running"] and current["pid"] == 0, "FLEET_NOT_STOPPED")


def receipt_identities(receipt):
    ci = receipt["plan"]["ci"]
    current = inspect(CI)
    same_container(ci, current, {(GIB, GIB), (3 * GIB, 3 * GIB)})
    require(current["running"] and current["pid"] > 0, "CI_AGENT_NOT_RUNNING")
    for model in receipt["plan"]["models"]:
        current = inspect(model["name"].removeprefix("/"))
        same_container(model, current, {(model["memory"], model["swap"])})
        require(not current["paused"] and current["running"] == (current["pid"] > 0), "MODEL_PROCESS_STATE_INVALID")


def enter(control, budget_sha):
    with operation_lock(control):
        return _enter(control, budget_sha)


def _enter(control, budget_sha):
    plan = prepare(control, budget_sha)
    receipt = {"version": 1, "plan": plan, "receiptSha256": sha(control.encoded(plan)), "phase": "PREPARED"}
    with locks(control):
        no_transactions(control)
        require(not (control.STATE / RECEIPT_NAME).exists() and not (control.STATE / RECEIPT_NAME).is_symlink(),
                "EXISTING_MAINTENANCE_RECEIPT_USE_RESTORE")
        require(sha(control.CONFIG.read_bytes()) == budget_sha, "BUDGET_PIN_MISMATCH")
        control.atomic(control.STATE / RECEIPT_NAME, receipt)
        control.atomic(RELEASE / "HALTED.json", hold(receipt))
    pause_ci(control, receipt)
    for model in plan["models"]:
        save(control, receipt, "STOPPING_MODELS")
        current = inspect(model["name"].removeprefix("/"))
        same_container(model, current, {(model["memory"], model["swap"])})
        command("docker", "stop", "--time", "30", model["id"])
    models_stopped(receipt)
    save(control, receipt, "RAISING_CI_LIMIT")
    command("docker", "update", "--memory", str(3 * GIB), "--memory-swap", str(3 * GIB), plan["ci"]["id"])
    same_container(plan["ci"], inspect(CI), {(3 * GIB, 3 * GIB)})
    save(control, receipt, "ENABLING_BUILD_BUDGET")
    write_budget(control, receipt, control.encoded(plan["maintenanceBudget"]) + b"\n", {budget_sha})
    state = control.snapshot(control.load_config())
    require(state["allowHeavyBuilds"], "ORIGINAL_RESOURCE_BUILD_GATE_CLOSED")
    unpause_ci(receipt)
    save(control, receipt, "ACTIVE")
    return {"maintenance": "ACTIVE", "taskId": plan["taskId"], "committedBytes": 35 * GIB // 2,
            "next": "Use the original resource-control.py admit-build before the reviewed manual CI trigger."}


def restored_models(control, receipt):
    for model in receipt["plan"]["models"]:
        current = inspect(model["name"].removeprefix("/"))
        same_container(model, current, {(model["memory"], model["swap"])})
        require(current["running"] and current["pid"] > 0 and not current["paused"], "MODEL_RESTORE_NOT_CONFIRMED")
        require(port_digest(control, model["port"]) == model["modelDigest"], "MODEL_DIGEST_CHANGED")


def restore(control):
    with operation_lock(control):
        return _restore(control)


def _restore(control):
    path = control.STATE / RECEIPT_NAME
    control.trusted(path)
    receipt = json.loads(path.read_bytes())
    original, raw, disabled = decoded(receipt)
    plan = receipt["plan"]
    allowed = {sha(raw), sha(control.encoded(plan["maintenanceBudget"]) + b"\n"), sha(control.encoded(disabled) + b"\n")}
    require(sha(control.CONFIG.read_bytes()) in allowed, "BUDGET_CHANGED_OUTSIDE_MAINTENANCE")
    release_stopped(control)
    receipt_identities(receipt)
    workers_stopped(control, original)
    inventory_unchanged(control, original, plan["inventory"])
    ci_idle(control, plan["ci"])
    marker = RELEASE / "HALTED.json"
    if not marker.exists() and receipt["phase"] == "PREPARED":
        with locks(control):
            no_transactions(control)
            control.atomic(marker, hold(receipt))
    if receipt["phase"] == "RESTORED":
        no_transactions(control, receipt if marker.exists() else None)
        same_container(plan["ci"], inspect(CI), {(GIB, GIB)})
        require(not inspect(CI)["paused"], "CI_RESUME_NOT_CONFIRMED")
        restored_models(control, receipt)
        require(control.CONFIG.read_bytes() == raw, "ORIGINAL_BUDGET_NOT_RESTORED")
        if marker.exists():
            with locks(control):
                no_transactions(control, receipt)
                marker.unlink()
                sync_directory(marker.parent)
        return {"maintenance": "RESTORED", "workerRestarted": False}
    no_transactions(control, receipt)
    pause_ci(control, receipt)
    save(control, receipt, "DISABLING_BUILD_BUDGET")
    current = control.CONFIG.read_bytes()
    if sha(current) != sha(raw):
        write_budget(control, receipt, control.encoded(disabled) + b"\n", allowed)
    save(control, receipt, "RESTORING_CI_LIMIT")
    command("docker", "update", "--memory", str(GIB), "--memory-swap", str(GIB), plan["ci"]["id"])
    same_container(plan["ci"], inspect(CI), {(GIB, GIB)})
    write_budget(control, receipt, raw, allowed)
    for model in plan["models"]:
        save(control, receipt, "RESTORING_MODELS")
        current = inspect(model["name"].removeprefix("/"))
        same_container(model, current, {(model["memory"], model["swap"])})
        if not current["running"]:
            command("docker", "start", model["id"])
        deadline = time.monotonic() + 120
        while True:
            try:
                require(port_digest(control, model["port"]) == model["modelDigest"], "MODEL_DIGEST_CHANGED")
                break
            except (OSError, ValueError):
                require(time.monotonic() < deadline, "MODEL_HEALTH_RESTORE_TIMEOUT")
                time.sleep(2)
    restored_models(control, receipt)
    workers_stopped(control, original)
    inventory_unchanged(control, original, plan["inventory"])
    release_stopped(control)
    ci_idle(control, plan["ci"])
    state = control.snapshot(control.load_config())
    require(state["allowNewActivations"] and not state["allowHeavyBuilds"], "ORIGINAL_RESOURCE_RESTORE_GATE_CLOSED")
    unpause_ci(receipt)
    with locks(control):
        no_transactions(control, receipt)
        require(control.CONFIG.read_bytes() == raw, "ORIGINAL_BUDGET_NOT_RESTORED")
        receipt["phase"] = "RESTORED"
        control.atomic(path, receipt)
        marker.unlink()
        sync_directory(marker.parent)
    return {"maintenance": "RESTORED", "taskId": plan["taskId"], "workerRestarted": False}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", nargs="?", choices=("plan", "enter", "restore"), default="plan")
    parser.add_argument("--budget-sha256")
    parser.add_argument("--baseline-resource", type=Path, default=BASELINE)
    args = parser.parse_args()
    if args.action != "plan":
        require(os.geteuid() == 0 and sys.flags.isolated and os.environ.get("UVEL_COMPUTE_V2_SCOPE") == "TEST",
                "ISOLATED_TEST_ROOT_ENTRY_REQUIRED")
    trusted(Path(__file__))
    control = load_control(args.baseline_resource)
    for directory in (control.STATE, control.RUNTIME, RELEASE):
        trusted(directory, directory=True)
    control.trusted(control.CONFIG)
    if args.action == "plan":
        result = public_plan(prepare(control, args.budget_sha256))
    elif args.action == "enter":
        result = enter(control, args.budget_sha256)
    else:
        result = restore(control)
    print(json.dumps(result, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("CI_MAINTENANCE_HELD: " + (str(error) if isinstance(error, Rejected) else type(error).__name__), file=sys.stderr)
        raise SystemExit(1)

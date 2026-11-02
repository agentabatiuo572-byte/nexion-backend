#!/usr/bin/env python3
"""Root-only, forward-only TEST compute retirement. Default action only reads the host."""
import argparse
from contextlib import nullcontext
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import sys
import time

LIVE_RESOURCE_SHA = "cb756f5f3c47ed887957e1209a44f061b52e6c4541998cddcd107b82ebb6bf4e"
LIVE_CI_SHA = "8dce191be8f39a6c0cdd5789d95f9e6806dc48e3168ff19fff81945c3bbf5455"
RESOURCE_SHA = "5b8696ae68acded46449521ed92189caa107c345623f424391a1d4edf4a44cdd"
CI_SHA = "8a7d289bdad6de8165df4661b1874a8896629c3e41a26bfb2500485840211e83"
CLOSURE_SOURCE_SHA = "accd861ba2a2c1a7d2038a096cc263e7599bac7c08723b55adc0780f4bb4733f"
ROOT = Path("/opt/uvel-task-control")
RESOURCE = ROOT / "resource-control.py"
CI = ROOT / "compute-v2-ci-maintenance.py"
RELEASE = Path("/srv/nexgrid/cd")
STATE = Path("/var/lib/uvel-task-control")
RECEIPT = STATE / "compute-retirement.json"
WORKERS = {"uvel-cloud-bge-worker.service", "uvel-cloud-bge-fleet.service", "uvel-compute-v2-worker.service"}
OBSERVATION = "uvel-compute-v2-observation.service"
MODELS = {"uvel-cloud-bge-model", "uvel-compute-modal-runtime"} | {f"uvel-cloud-bge-model-fleet-{n}" for n in range(1, 8)}
CONDITION = "[Unit]\nConditionPathExists=!/var/lib/uvel-task-control/compute-retirement.json\n"
CASES = {1208: "CTA-F55257583F6F4681975DB2EF6C0B3F15", 1209: "CTA-2B6C43B79E6A480ABDF932054539AC13",
         1212: "CTA-BE61708B7DDA4BFF9F624A4647BD5035", 1214: "CTA-ED3763A8C79042AE9EC86FA8AACD3FDE"}


class Rejected(RuntimeError):
    pass


def require(condition, reason):
    if not condition:
        raise Rejected(reason)


def sha(raw):
    return hashlib.sha256(raw).hexdigest()


def module(path, expected, name):
    # The installed legacy modules supply their existing locks and read-only checks.
    import stat
    for index, item in enumerate((path, *path.parents)):
        info = item.lstat()
        require((stat.S_ISREG(info.st_mode) if index == 0 else stat.S_ISDIR(info.st_mode))
                and info.st_uid == 0 and not info.st_mode & 0o022, "ROOT_OWNED_SOURCE_REQUIRED")
    require(sha(path.read_bytes()) == expected, "KNOWN_LIVE_SOURCE_PIN_MISMATCH")
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


def stopped_models(control, original, review):
    names = {entry["control"]["name"] for entry in original["commitments"] if entry["kind"] == "model"}
    names |= {entry["name"] for entry in original.get("stoppedControls", [])}
    require(names and names <= MODELS and set(review["modelNames"]) == names
            and len(review["modelNames"]) == len(names), "MODEL_REFERENCE_REVIEW_MISMATCH")
    files = {item["path"] for item in review["reviewedFiles"]}
    models = []
    for name in sorted(names):
        actual = json.loads(control.run("docker", "inspect", "--format",
            '{"id":{{json .Id}},"name":{{json .Name}},"image":{{json .Image}},'
            '"running":{{json .State.Running}},"pid":{{json .State.Pid}},"paused":{{json .State.Paused}},'
            '"restart":{{json .HostConfig.RestartPolicy.Name}},"labels":{{json .Config.Labels}},'
            '"mounts":{{json .Mounts}},"ports":{{json .HostConfig.PortBindings}}}', name))
        require(actual["running"] is False and actual["pid"] == 0 and actual["paused"] is False
                and actual["restart"] == "no", "MODEL_NOT_PERMANENTLY_STOPPED")
        compose = (actual["labels"] or {}).get("com.docker.compose.project.config_files", "")
        require(not compose or set(compose.split(",")) <= files, "MODEL_COMPOSE_REFERENCE_NOT_REVIEWED")
        models.append(actual)
    return models


def backend_flags(control):
    state = control.service_state("nexgrid-backend.service")
    require(state.get("ActiveState") == "active" and int(state.get("MainPID", "0")) > 0, "BACKEND_NOT_ACTIVE")
    proc = Path("/proc") / state["MainPID"]
    env = dict(part.split(b"=", 1) for part in (proc / "environ").read_bytes().split(b"\0") if b"=" in part)
    values = json.loads(env.get(b"SPRING_APPLICATION_JSON", b"{}"))
    def setting(key):
        if key in values:
            return values[key]
        value = values
        for part in key.split("."):
            if not isinstance(value, dict) or part not in value:
                return None
            value = value[part]
        return value
    flags = {"nexion.simulated-tasks.enabled": True, "nexion.compute-v2.enabled": False,
             "nexion.compute-task.test-cloud.enabled": False}
    require(all(setting(key) is expected for key, expected in flags.items()), "RUNNING_BACKEND_MODE_NOT_RETIRED")
    argv = (proc / "cmdline").read_bytes().decode().split("\0")
    require(not any(arg.startswith("--spring.application.json") for arg in argv), "COMMAND_LINE_JSON_OVERRIDE")
    for key, expected in flags.items():
        require(all(arg == f"--{key}={str(expected).lower()}" for arg in argv
                    if arg == f"--{key}" or arg.startswith(f"--{key}=")),
                "COMMAND_LINE_EXECUTION_OVERRIDE")
    return {"pid": int(state["MainPID"]), "executionFlags": flags}


def sql_closure(control, source):
    control.trusted(source)
    raw = source.read_bytes()
    require(sha(raw) == CLOSURE_SOURCE_SHA, "EXACT_CLOSURE_SOURCE_PIN_MISMATCH")
    text = raw.decode()
    blocks = re.findall(r'"""(.*?)"""', text, re.S)
    case_sha = re.search(r'CASE_SET_SHA = "([0-9a-f]{64})"', text).group(1)
    require(len(blocks) == 2, "EXACT_CLOSURE_SOURCE_INVALID")
    resolved = blocks[0] + " AND z.case_set_sha256='" + case_sha + "' " + blocks[1]
    legacy = """(SELECT COUNT(*) FROM nx_compute_task WHERE source_environment='PRODUCTION' AND is_deleted=0
      AND status IN ('CLAIMED','RUNNING','PAUSED') AND task_no NOT LIKE 'CV2-%' AND task_no NOT LIKE 'SIM-%')
      +(SELECT COUNT(*) FROM nx_test_cloud_request r WHERE r.state IN ('PROCESSING','UNKNOWN') AND NOT EXISTS (""" + resolved + "))"
    unexited = """SELECT COUNT(DISTINCT e.task_no,e.generation) FROM nx_compute_v2_event e
      WHERE e.kind IN ('ISSUED','STARTED','VERIFIED') AND NOT EXISTS(SELECT 1 FROM nx_compute_v2_event x
      WHERE x.task_no=e.task_no AND x.generation=e.generation AND x.kind='EXITED')
      AND (EXISTS(SELECT 1 FROM nx_compute_v2_event s WHERE s.task_no=e.task_no AND s.generation=e.generation AND s.kind='STARTED')
      OR NOT EXISTS(SELECT 1 FROM nx_compute_v2_event f WHERE f.task_no=e.task_no AND f.generation=e.generation AND f.kind='FAILED'))"""
    allocation = """SELECT COUNT(*) FROM nx_compute_v2_event e WHERE e.kind='VERIFIED' AND (
      (SELECT COUNT(*) FROM nx_compute_v2_allocation a WHERE a.event_id=e.event_id)<>
        IF(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.evidence.rewardNexMicros')) AS SIGNED)>0,2,1)
      OR (SELECT COUNT(*) FROM nx_compute_v2_allocation a JOIN nx_compute_v2_settlement s
        ON s.settlement_id=a.settlement_id AND s.user_id=a.user_id AND s.asset=a.asset
        WHERE a.event_id=e.event_id AND a.task_no=e.task_no AND a.user_id=e.user_id AND a.user_device_id=e.user_device_id
        AND a.asset='USDT' AND a.amount_micros=CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.task.rewardUsdtMicros')) AS SIGNED))<>1
      OR (SELECT COUNT(*) FROM nx_compute_v2_allocation a JOIN nx_compute_v2_settlement s
        ON s.settlement_id=a.settlement_id AND s.user_id=a.user_id AND s.asset=a.asset
        WHERE a.event_id=e.event_id AND a.task_no=e.task_no AND a.user_id=e.user_id AND a.user_device_id=e.user_device_id
        AND a.asset='UVT' AND a.amount_micros=CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.evidence.rewardNexMicros')) AS SIGNED))<>
        IF(CAST(JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.evidence.rewardNexMicros')) AS SIGNED)>0,1,0))"""
    fields = ["'legacyUnresolved',(" + legacy + ")", "'v2Unexited',(" + unexited + ")",
              "'verifiedAllocationInvalid',(" + allocation + ")"]
    for device, task in CASES.items():
        fields.append(f"'case{device}',(SELECT COUNT(*) FROM nx_test_cloud_request r WHERE r.owner_id=60723153007 "
            f"AND r.device_id={device} AND BINARY r.task_no=BINARY '{task}' AND r.generation=1 AND r.operation='COMPLETE' "
            "AND r.state IN ('PROCESSING','UNKNOWN') AND EXISTS (" + resolved + "))")
    sql = "SET TRANSACTION READ ONLY; START TRANSACTION WITH CONSISTENT SNAPSHOT; SELECT JSON_OBJECT(" + ",".join(fields) + "); COMMIT;"
    result = subprocess.run(["docker", "exec", "-i", "nexgrid-mysql", "sh", "-c",
        'MYSQL_PWD="$MYSQL_ROOT_PASSWORD" exec mysql --user=root --batch --raw --skip-column-names nexion'],
        input=sql, text=True, capture_output=True, timeout=60)
    require(result.returncode == 0 and len(result.stdout) < 65536, "SQL_CLOSURE_READ_FAILED")
    metrics = json.loads(result.stdout)
    require(all(metrics[key] == 0 for key in ("legacyUnresolved", "v2Unexited", "verifiedAllocationInvalid"))
            and all(metrics[f"case{device}"] == 1 for device in CASES), "SQL_COMPUTE_NOT_EXACTLY_CLOSED")
    return metrics


def main():
    sys.dont_write_bytecode = True
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", nargs="?", choices=("plan", "apply"), default="plan")
    parser.add_argument("--retirement-id", required=True)
    parser.add_argument("--drain-id", required=True)
    parser.add_argument("--budget-sha256", required=True)
    parser.add_argument("--reference-review", type=Path, required=True)
    parser.add_argument("--reference-review-sha256", required=True)
    parser.add_argument("--resource-candidate", type=Path, required=True)
    parser.add_argument("--ci-candidate", type=Path, required=True)
    parser.add_argument("--closure-source", type=Path, required=True)
    args = parser.parse_args()
    require(os.geteuid() == 0 and sys.flags.isolated and os.environ.get("UVEL_COMPUTE_RETIREMENT_SCOPE") == "TEST",
            "ISOLATED_TEST_ROOT_ENTRY_REQUIRED")
    require(re.fullmatch(r"[0-9a-f]{32}", args.retirement_id) and re.fullmatch(r"[0-9a-f]{32}", args.drain_id), "RETIREMENT_ID_INVALID")
    control = module(RESOURCE, LIVE_RESOURCE_SHA, "uvel_retirement_legacy_control")
    maintenance = module(CI, LIVE_CI_SHA, "uvel_retirement_legacy_maintenance")
    control.trusted(Path(__file__))
    for path, expected in ((args.resource_candidate, RESOURCE_SHA), (args.ci_candidate, CI_SHA),
                           (args.reference_review, args.reference_review_sha256)):
        control.trusted(path)
        require(sha(path.read_bytes()) == expected, "REVIEWED_CANDIDATE_PIN_MISMATCH")
    review = json.loads(args.reference_review.read_bytes())
    require(review.get("version") == 1 and review.get("scope") == "TEST" and review.get("exclusiveToCompute") is True
            and review.get("reviewedFiles") and review.get("operator")
            and set(review["consumerUnits"]) == WORKERS | {OBSERVATION, "nexgrid-backend.service"}, "EXCLUSIVE_REFERENCE_REVIEW_REQUIRED")
    for item in review["reviewedFiles"]:
        path = Path(item["path"])
        control.trusted(path)
        require(sha(path.read_bytes()) == item["sha256"], "MODEL_REFERENCE_FILE_CHANGED")
    with maintenance.operation_lock(control) if args.action == "apply" else nullcontext():
        require(not RECEIPT.exists(), "COMPUTE_ALREADY_RETIRED_OR_HELD")
        raw = control.CONFIG.read_bytes()
        require(sha(raw) == args.budget_sha256, "REVIEWED_BUDGET_CHANGED")
        original = control.load_config()
        require(original.get("executionProfile", "MODEL") == "MODEL", "MODEL_PROFILE_REQUIRED")
        require({entry["name"] for entry in original["workerServices"]} == WORKERS, "COMPLETE_WORKER_INVENTORY_REQUIRED")
        maintenance.release_stopped(control)
        maintenance.workers_stopped(control, original)
        observation = control.service_state(OBSERVATION)
        require(observation.get("ActiveState") == "inactive" and observation.get("MainPID") == "0", "OBSERVATION_NOT_STOPPED")
        inventory = maintenance.inventory_unchanged(control, original)
        active_drain = control.drain_pending()
        drain_path = STATE / ("drain-receipt.json" if active_drain else "last-drain-receipt.json")
        control.trusted(drain_path)
        drain = json.loads(drain_path.read_bytes())
        require(drain.get("ready") is True and drain.get("drainId") == args.drain_id
                and drain.get("finalInventory") == inventory
                and drain.get("protectedJournals", []) == original.get("protectedJournals", []), "EXACT_READY_DRAIN_REQUIRED")
        if active_drain:
            marker = json.loads((STATE / "drain.json").read_bytes())
            require(marker.get("drainId") == args.drain_id, "DRAIN_ID_CHANGED")
        else:
            require(drain.get("workersKeptStopped") is True, "STOPPED_DRAIN_CLOSURE_REQUIRED")
        ci = maintenance.inspect(original["buildControl"]["name"])
        maintenance.ci_idle(control, ci)
        control.verify_limits(original)
        physical, available = control.memory()
        require(available >= (physical + 4) // 5, "HOST_MEMORY_RESERVE_REQUIRED")
        models = stopped_models(control, original, review)
        flags = backend_flags(control)
        metrics = sql_closure(control, args.closure_source)
        units = sorted(WORKERS | {OBSERVATION})
        for unit in units:
            require(maintenance.command("systemctl", "show", unit, "--property=LoadState", "--value") == "loaded",
                    "RETIRED_UNIT_NOT_LOADED")
            require(not (Path("/etc/systemd/system") / (unit + ".d/90-compute-retired.conf")).exists(), "RETIREMENT_DROPIN_ALREADY_EXISTS")
        updated = {**original, "executionProfile": "RETIRED", "maxActiveExecutors": 0,
                   "workerServices": [], "protectedJournals": [], "stoppedControls": [],
                   "commitments": [item for item in original["commitments"] if item["kind"] != "model"
                                   and item.get("control", {}).get("name") not in units]}
        report = {"retirementId": args.retirement_id, "drainId": args.drain_id,
                  "previousCommittedBytes": sum(item["bytes"] for item in original["commitments"]) + original["buildBudgetBytes"],
                  "retiredCommittedBytes": sum(item["bytes"] for item in updated["commitments"]) + updated["buildBudgetBytes"],
                  "models": [item["name"] for item in models], "workersKeptStopped": True, "sqlClosure": metrics}
        if args.action == "plan":
            print(json.dumps(report, sort_keys=True))
            return
        # Close the active READY through its original entry before any budget/protection change.
        if active_drain:
            control.resume(original, restart_workers=False)
        archive = STATE / "retirements" / args.retirement_id
        archive.mkdir(mode=0o700, parents=True, exist_ok=False)
        saved = {}
        def preserve(name, data):
            path = archive / name
            with path.open("xb") as stream:
                os.fchmod(stream.fileno(), 0o400)
                stream.write(data)
                stream.flush()
                os.fsync(stream.fileno())
            saved[name] = sha(data)
        preserve("budget.json", raw)
        preserve("resource-control.py", RESOURCE.read_bytes())
        preserve("ci-maintenance.py", CI.read_bytes())
        closed_raw = (STATE / "last-drain-receipt.json").read_bytes()
        closed = json.loads(closed_raw)
        require(closed.get("drainId") == args.drain_id and closed.get("workersKeptStopped") is True
                and closed.get("finalInventory") == inventory, "STOPPED_DRAIN_CLOSURE_CHANGED")
        preserve("drain-receipt.json", closed_raw)
        preserve("reference-review.json", args.reference_review.read_bytes())
        for entry in inventory:
            name = "journal-" + sha(entry["path"].encode()) + ".sqlite"
            with sqlite3.connect(Path(entry["path"]).resolve().as_uri() + "?mode=ro", uri=True) as source:
                with sqlite3.connect(archive / name) as target:
                    source.backup(target)
            path = archive / name
            copied = control.v2_journal_fingerprint(path) if re.fullmatch(r"runner-[1-6]\.sqlite", Path(entry["path"]).name) else control.journal_fingerprint(path)
            require({key: value for key, value in copied.items() if key != "path"}
                    == {key: value for key, value in entry.items() if key != "path"}, "ARCHIVED_JOURNAL_CHANGED")
            with path.open("rb") as stream:
                os.fsync(stream.fileno())
            path.chmod(0o400)
            saved[name] = sha(path.read_bytes())
        maintenance.sync_directory(archive)
        receipt = {"version": 1, "scope": "TEST", "retirementId": args.retirement_id,
                   "createdAt": int(time.time()), "drainId": args.drain_id, "previousBudgetSha256": sha(raw),
                   "retiredBudgetSha256": control.digest(updated), "resourceSha256": RESOURCE_SHA,
                   "ciSha256": CI_SHA, "closureSourceSha256": CLOSURE_SOURCE_SHA, "backend": flags,
                   "models": models, "inventory": inventory, "sqlClosure": metrics, "archive": str(archive), "files": saved}
        receipt_raw = control.encoded(receipt) + b"\n"
        updated["retirementReceiptSha256"] = sha(receipt_raw)
        hold = {"version": 1, "scope": "TEST", "reason": "COMPUTE_PERMANENT_RETIREMENT",
                "taskId": args.retirement_id, "receiptSha256": sha(receipt_raw)}
        with maintenance.locks(control):
            maintenance.no_transactions(control)
            require(control.CONFIG.read_bytes() == raw, "REVIEWED_BUDGET_CHANGED")
            control.atomic(RELEASE / "HALTED.json", hold)
        # Any failure below keeps this owned hold and all evidence. No automatic rollback/restart.
        for unit in units:
            directory = Path("/etc/systemd/system") / (unit + ".d")
            directory.mkdir(mode=0o755, exist_ok=True)
            maintenance.atomic_bytes(directory / "90-compute-retired.conf", CONDITION.encode(), 0o644)
        maintenance.command("systemctl", "daemon-reload")
        maintenance.command("systemctl", "disable", *units)
        maintenance.inventory_unchanged(control, original, inventory)
        maintenance.ci_idle(control, ci)
        control.verify_limits(original)
        require(stopped_models(control, original, review) == models and backend_flags(control) == flags, "RETIREMENT_SUBJECT_CHANGED")
        require(sql_closure(control, args.closure_source) == metrics, "SQL_CLOSURE_CHANGED")
        with maintenance.locks(control):
            require(json.loads((RELEASE / "HALTED.json").read_bytes()) == hold, "RETIREMENT_HOLD_CHANGED")
            require(control.CONFIG.read_bytes() == raw and not control.drain_pending(), "RETIREMENT_STATE_CHANGED")
            maintenance.atomic_bytes(RECEIPT, receipt_raw, 0o400)
            maintenance.atomic_bytes(RESOURCE, args.resource_candidate.read_bytes(), 0o755)
            maintenance.atomic_bytes(CI, args.ci_candidate.read_bytes(), 0o755)
            maintenance.atomic_bytes(control.CONFIG, control.encoded(updated) + b"\n", 0o600)
        current = module(RESOURCE, RESOURCE_SHA, "uvel_retired_control")
        state = current.snapshot(current.load_config())
        require(state["allowNewActivations"] and not state["draining"], "RETIRED_RESOURCE_GATE_CLOSED")
        maintenance.workers_stopped(control, original)
        maintenance.release_stopped(control)
        with maintenance.locks(control):
            require(json.loads((RELEASE / "HALTED.json").read_bytes()) == hold, "RETIREMENT_HOLD_CHANGED")
            (RELEASE / "HALTED.json").unlink()
            maintenance.sync_directory(RELEASE)
        print(json.dumps({**report, "retirement": "COMPLETE", "receiptSha256": sha(receipt_raw)}, sort_keys=True))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print("COMPUTE_RETIREMENT_HELD: " + (str(error) if type(error).__name__ == "Rejected" else type(error).__name__), file=sys.stderr)
        raise SystemExit(1)

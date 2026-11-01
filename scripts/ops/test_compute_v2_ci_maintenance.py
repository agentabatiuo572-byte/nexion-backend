"""Local fault/recovery tests; never connects to Docker, Jenkins or a host service."""
from contextlib import contextmanager
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    value = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(value)
    return value


helper = module("compute_v2_ci_maintenance", ROOT / "deploy/public-test/compute-v2-ci-maintenance.py")
resource = module("compute_v2_ci_resources", ROOT / "deploy/public-test/compute-v2-resource-control.py")
G = helper.GIB


def budget():
    protected = {"path": "/var/lib/uvel-cloud-bge-fleet/instances/old/journal.sqlite3", "phase": "UNKNOWN_NEXT",
                 "allowedPhases": ["UNKNOWN_NEXT"], "deviceId": 1380,
                 **{key: "a" * 64 for key in ("bindingSha256", "stateSha256", "callsSha256", "contentSha256")}}
    return {"version": 1, "scope": "TEST", "maxActiveExecutors": 6, "buildsEnabled": False,
            "buildBudgetBytes": G, "buildControl": {"name": helper.CI, "type": "docker"},
            "buildRequests": {component: G for component in resource.COMPONENTS},
            "commitments": [
                {"name": "backend", "kind": "backend", "bytes": 4 * G,
                 "control": {"name": "nexgrid-backend.service", "type": "systemd"}},
                {"name": "business-mysql", "kind": "database", "bytes": 7 * G // 2,
                 "control": {"name": "nexgrid-mysql", "type": "docker"}},
                {"name": "other-host-work", "kind": "other", "bytes": 11 * G // 2},
                *[{"name": name, "kind": "model", "bytes": 3 * G // 2,
                   "control": {"name": name, "type": "docker"}}
                  for name in ["uvel-cloud-bge-model", *helper.FLEET]]],
            "workerServices": [
                {"name": "uvel-cloud-bge-worker.service", "journalRoots": ["/var/lib/uvel-cloud-bge-worker"]},
                {"name": "uvel-cloud-bge-fleet.service", "journalRoots": ["/var/lib/uvel-cloud-bge-fleet/instances"]},
                {"name": "uvel-compute-v2-worker.service", "journalRoots": ["/var/lib/uvel-compute-v2"]}],
            "protectedJournals": [protected],
            "stoppedControls": [{"name": resource.STOPPED_SPARE, "type": "docker"}], "drainTimeoutSeconds": 300}


class Fixture:
    def __init__(self, root):
        self.root, self.depth = root, 0
        self.operation = threading.Lock()
        self.STATE, self.RUNTIME, self.JOBS = root / "state", root / "run", root / "jobs"
        self.CONFIG, self.release = root / "budget.json", root / "release"
        for path in (self.STATE, self.RUNTIME, self.JOBS, self.release):
            path.mkdir()
        self.original = budget()
        self.raw = (json.dumps(self.original, indent=3) + "\n\n").encode()
        self.CONFIG.write_bytes(self.raw)
        self.inventory_rows = [{key: value for key, value in self.original["protectedJournals"][0].items()
                                if key != "allowedPhases"},
                               *[{"path": f"/var/lib/uvel-compute-v2/runner-{number}.sqlite", "phase": "IDLE"} for number in range(1, 7)]]
        self.workers = {entry["name"]: {"ActiveState": "inactive", "MainPID": "0"} for entry in self.original["workerServices"]}
        self.workers.update({name: {"ActiveState": "inactive", "MainPID": "0"}
                             for name in ("nexgrid-release.timer", "nexgrid-release.service")})
        self.containers = {}
        for index, name in enumerate([helper.CI, *helper.FLEET]):
            size = G if name == helper.CI else 3 * G // 2
            self.containers[name] = {"id": hashlib.sha256(name.encode()).hexdigest(), "name": "/" + name,
                                     "image": "sha256:" + "b" * 64, "running": True, "paused": False,
                                     "pid": 100 + index, "memory": size, "swap": size,
                                     "ports": {} if name == helper.CI else
                                     {"11434/tcp": [{"HostIp": "127.0.0.1", "HostPort": str(helper.FLEET[name])}]}}
        for component in resource.COMPONENTS:
            build = self.JOBS / f"nexgrid-{component}-test/builds/1"
            build.mkdir(parents=True)
            (build / "build.xml").write_text("<run><completed>true</completed></run>")
        self.events, self.snapshots, self.fault = [], [], None
        self.COMPONENTS, self.encoded, self.NoRedirect = resource.COMPONENTS, resource.encoded, resource.NoRedirect
        self.check_journals = resource.check_journals

    def load_config(self): return json.loads(self.CONFIG.read_bytes())
    def trusted(self, *_args, **_kwargs): pass
    def memory(self): return 32 * G, 20 * G
    def capacity(self, *args): return resource.capacity(*args)
    def service_state(self, name): return self.workers[name]
    def inventory(self, _config): return copy.deepcopy(self.inventory_rows)
    def drain_pending(self): return any((self.STATE / name).exists() for name in ("drain.json", "drain-receipt.json"))
    def atomic(self, path, value): path.write_bytes(resource.encoded(value) + b"\n")

    def build_completed(self, component, number):
        path = self.JOBS / f"nexgrid-{component}-test/builds/{number}/build.xml"
        return path.is_file() and ET.fromstring(path.read_bytes()).findtext("completed") == "true"

    def verify_limits(self, config):
        ci = self.containers[helper.CI]
        helper.require((ci["memory"], ci["swap"]) == (config["buildBudgetBytes"], config["buildBudgetBytes"]), "CI_LIMIT_DRIFT")
        for entry in config["commitments"]:
            if entry["name"] in self.containers:
                helper.require(self.containers[entry["name"]]["memory"] == entry["bytes"], "MODEL_LIMIT_DRIFT")

    def snapshot(self, config):
        if self.depth:
            raise AssertionError("snapshot must not wait while helper locks are held")
        self.verify_limits(config)
        self.snapshots.append(copy.deepcopy(config))
        return resource.capacity(config, *self.memory(), 0, 123)

    @contextmanager
    def operation_lock(self, _control):
        if not self.operation.acquire(blocking=False):
            raise helper.Rejected("MAINTENANCE_OPERATION_ALREADY_RUNNING")
        try:
            yield
        finally:
            self.operation.release()

    @contextmanager
    def locks(self, _control):
        self.depth += 1
        try:
            yield
        finally:
            self.depth -= 1

    def command(self, *args):
        if self.depth:
            raise AssertionError("Docker must never wait while helper locks are held")
        if args[:2] == ("docker", "inspect"):
            identity = args[-1]
            container = next((value for name, value in self.containers.items() if identity in (name, value["id"])), None)
            if container is None:
                raise RuntimeError("unknown container")
            return json.dumps(container)
        if args[:2] == ("docker", "top"):
            return "PID COMMAND\n123 java"
        self.events.append(args)
        container = next(value for value in self.containers.values() if value["id"] == args[-1])
        if args[1] == "pause": container["paused"] = True
        elif args[1] == "unpause": container["paused"] = False
        elif args[1] == "stop": container.update(running=False, pid=0)
        elif args[1] == "start": container.update(running=True, pid=222)
        elif args[1] == "update": container.update(memory=int(args[3]), swap=int(args[5]))
        else: raise AssertionError("unsupported mutation")
        if self.fault and self.fault(args):
            self.fault = None
            raise RuntimeError("simulated ACK loss after actual mutation")
        return ""

    @contextmanager
    def patched(self):
        with patch.object(helper, "RELEASE", self.release), patch.object(helper, "trusted"), \
                patch.object(helper, "operation_lock", side_effect=self.operation_lock), \
                patch.object(helper, "locks", side_effect=self.locks), patch.object(helper, "sync_directory"), \
                patch.object(helper, "command", side_effect=self.command), \
                patch.object(helper, "port_digest", return_value="c" * 64):
            yield self


class ComputeV2CiMaintenanceTests(unittest.TestCase):
    @contextmanager
    def fixture(self):
        with tempfile.TemporaryDirectory() as directory:
            value = Fixture(Path(directory))
            with value.patched():
                yield value

    def test_default_plan_reads_only_and_calculates_exact_budget_exchange(self):
        with self.fixture() as state:
            plan = helper.prepare(state, helper.sha(state.raw))
            result = helper.public_plan(plan)
            self.assertEqual(result["committedBytes"], 35 * G // 2)
            self.assertEqual(state.CONFIG.read_bytes(), state.raw)
            self.assertEqual(state.events, [])
            self.assertFalse((state.STATE / helper.RECEIPT_NAME).exists())
            self.assertFalse((state.release / "HALTED.json").exists())
            changed = plan["maintenanceBudget"]
            for key in set(state.original) - {"commitments", "maxActiveExecutors", "buildBudgetBytes", "buildRequests", "buildsEnabled"}:
                self.assertEqual(changed[key], state.original[key])
            self.assertEqual(changed["commitments"], [row for row in state.original["commitments"] if row["name"] not in helper.FLEET])
            with patch.object(helper, "load_control", return_value=state), \
                    patch.object(helper.sys, "argv", ["tool", "--budget-sha256", helper.sha(state.raw)]), \
                    patch("builtins.print") as output:
                helper.main()
                self.assertEqual(json.loads(output.call_args.args[0])["action"], "plan")
            self.assertEqual(state.events, [])

    def test_unknown_budget_or_unreviewed_sha_cannot_mutate(self):
        with self.fixture() as state:
            with self.assertRaisesRegex(helper.Rejected, "BUDGET_PIN_MISMATCH"):
                helper.enter(state, "0" * 64)
            current = copy.deepcopy(state.original)
            current["commitments"][2]["bytes"] -= 1
            with self.assertRaisesRegex(helper.Rejected, "KNOWN_COMMITMENTS_REQUIRED"):
                helper.maintenance_budget(current)
            current = copy.deepcopy(state.original)
            current["commitments"].append({"name": "unknown-business", "kind": "other", "bytes": G})
            with self.assertRaisesRegex(helper.Rejected, "KNOWN_COMMITMENTS_REQUIRED"):
                helper.maintenance_budget(current)
            self.assertEqual(state.events, [])

    def test_workers_protected_journals_and_existing_release_or_drain_hold_block_enter(self):
        with self.fixture() as state:
            worker = state.original["workerServices"][0]["name"]
            state.workers[worker] = {"ActiveState": "active", "MainPID": "123"}
            with self.assertRaisesRegex(helper.Rejected, "WORKER_MUST_BE_GRACEFULLY_STOPPED"):
                helper.enter(state, helper.sha(state.raw))
            state.workers[worker] = {"ActiveState": "inactive", "MainPID": "0"}
            state.inventory_rows[0]["contentSha256"] = "changed"
            with self.assertRaisesRegex(resource.Rejected, "PROTECTED_JOURNAL_CHANGED"):
                helper.enter(state, helper.sha(state.raw))
            state.inventory_rows[0]["contentSha256"] = "a" * 64
            for path in (state.STATE / "drain.json", state.release / "transaction.json", state.release / "HALTED.json"):
                path.write_text("{}")
                with self.assertRaises(helper.Rejected):
                    helper.enter(state, helper.sha(state.raw))
                path.unlink()
            self.assertEqual(state.events, [])

    def test_release_timer_and_service_must_remain_stopped_without_helper_stopping_them(self):
        for unit, reason in (("nexgrid-release.timer", "RELEASE_TIMER_MUST_BE_STOPPED"),
                             ("nexgrid-release.service", "RELEASE_SERVICE_MUST_BE_STOPPED")):
            with self.subTest(unit=unit), self.fixture() as state:
                state.workers[unit] = {"ActiveState": "active", "MainPID": "123"}
                with self.assertRaisesRegex(helper.Rejected, reason):
                    helper.enter(state, helper.sha(state.raw))
                self.assertEqual(state.events, [])
                state.workers[unit] = {"ActiveState": "inactive", "MainPID": "0"}
                helper.enter(state, helper.sha(state.raw))
                state.workers[unit] = {"ActiveState": "active", "MainPID": "123"}
                before = list(state.events)
                with self.assertRaisesRegex(helper.Rejected, reason):
                    helper.restore(state)
                self.assertEqual(state.events, before)
                self.assertTrue((state.release / "HALTED.json").exists())

    def test_enter_stops_only_six_pinned_models_and_keeps_original_admission_gate(self):
        with self.fixture() as state:
            result = helper.enter(state, helper.sha(state.raw))
            self.assertEqual(result["maintenance"], "ACTIVE")
            stop_ids = {args[-1] for args in state.events if args[1] == "stop"}
            self.assertEqual(stop_ids, {state.containers[name]["id"] for name in helper.FLEET})
            self.assertEqual(state.containers[helper.CI]["memory"], 3 * G)
            self.assertFalse(state.containers[helper.CI]["paused"])
            self.assertTrue(state.load_config()["buildsEnabled"])
            self.assertEqual(state.load_config()["maxActiveExecutors"], 1)
            self.assertEqual(len(state.snapshots), 1)
            self.assertTrue((state.release / "HALTED.json").exists())
            self.assertEqual(state.original["protectedJournals"], state.load_config()["protectedJournals"])
            with self.assertRaisesRegex(helper.Rejected, "KNOWN_IDLE_CI_BUDGET_REQUIRED"):
                helper.enter(state, helper.sha(state.CONFIG.read_bytes()))

    def test_restore_preserves_original_bytes_limits_ids_and_never_starts_a_worker(self):
        with self.fixture() as state:
            ids = {name: value["id"] for name, value in state.containers.items()}
            helper.enter(state, helper.sha(state.raw))
            helper.restore(state)
            self.assertEqual(state.CONFIG.read_bytes(), state.raw)
            self.assertEqual(state.containers[helper.CI]["memory"], G)
            self.assertTrue(all(state.containers[name]["running"] for name in helper.FLEET))
            self.assertEqual(ids, {name: value["id"] for name, value in state.containers.items()})
            self.assertFalse((state.release / "HALTED.json").exists())
            before = list(state.events)
            self.assertEqual(helper.restore(state)["maintenance"], "RESTORED")
            self.assertEqual(state.events, before)
            self.assertFalse(any(args[0] == "systemctl" for args in state.events))

    def test_ack_loss_during_each_resource_mutation_retains_receipt_then_recovers(self):
        cases = [lambda args: args[1] == "pause", lambda args: args[1] == "stop", lambda args: args[1] == "update",
                 lambda args: args[1] == "unpause"]
        for position, fault in enumerate(cases):
            with self.subTest(position=position), self.fixture() as state:
                state.fault = fault
                with self.assertRaises(RuntimeError):
                    helper.enter(state, helper.sha(state.raw))
                self.assertTrue((state.STATE / helper.RECEIPT_NAME).exists())
                self.assertTrue((state.release / "HALTED.json").exists())
                helper.restore(state)
                self.assertEqual(state.CONFIG.read_bytes(), state.raw)
                self.assertFalse((state.release / "HALTED.json").exists())

    def test_partial_restore_ack_loss_is_resumable_using_only_original_receipt(self):
        for action in ("update", "start", "unpause"):
            with self.subTest(action=action), self.fixture() as state:
                helper.enter(state, helper.sha(state.raw))
                state.fault = lambda args: args[1] == action
                with self.assertRaises(RuntimeError):
                    helper.restore(state)
                self.assertTrue((state.release / "HALTED.json").exists())
                helper.restore(state)
                self.assertEqual(state.CONFIG.read_bytes(), state.raw)
                self.assertTrue(all(state.containers[name]["running"] for name in helper.FLEET))

    def test_changed_container_budget_or_hold_never_authorizes_restore_or_clears_evidence(self):
        for change in ("id", "budget", "hold", "journal"):
            with self.subTest(change=change), self.fixture() as state:
                helper.enter(state, helper.sha(state.raw))
                if change == "id": state.containers[next(iter(helper.FLEET))]["id"] = "f" * 64
                elif change == "budget": state.CONFIG.write_text("{}")
                elif change == "hold": (state.release / "HALTED.json").write_text('{"taskId":"foreign"}')
                else: state.inventory_rows[0]["contentSha256"] = "foreign"
                before = list(state.events)
                with self.assertRaises((helper.Rejected, resource.Rejected)):
                    helper.restore(state)
                self.assertEqual(state.events, before)
                self.assertTrue((state.release / "HALTED.json").exists())
                self.assertTrue((state.STATE / helper.RECEIPT_NAME).exists())

    def test_inflight_build_or_busy_agent_prevents_limit_lowering(self):
        with self.fixture() as state:
            helper.enter(state, helper.sha(state.raw))
            (state.JOBS / "nexgrid-backend-test/builds/1/build.xml").write_text("<run><completed>false</completed></run>")
            before = list(state.events)
            with self.assertRaisesRegex(helper.Rejected, "WAIT_FOR_TERMINAL_BUILDS"):
                helper.restore(state)
            self.assertEqual(state.events, before)
            self.assertEqual(state.containers[helper.CI]["memory"], 3 * G)
            (state.JOBS / "nexgrid-backend-test/builds/1/build.xml").write_text("<run><completed>true</completed></run>")
            original_command = state.command
            with patch.object(helper, "command", side_effect=lambda *args:
                              "PID COMMAND\n123 java\n124 java" if args[:2] == ("docker", "top") else original_command(*args)):
                with self.assertRaisesRegex(helper.Rejected, "WAIT_FOR_IDLE_AGENT"):
                    helper.restore(state)
            self.assertEqual(state.events, before)

    def test_budget_commit_ack_loss_is_recovered_without_a_new_baseline(self):
        for where in ("enter", "restore"):
            with self.subTest(where=where), self.fixture() as state:
                if where == "restore":
                    helper.enter(state, helper.sha(state.raw))
                write = helper.atomic_bytes
                failed = False
                def lost_ack(path, raw, mode):
                    nonlocal failed
                    write(path, raw, mode)
                    if not failed and (where == "enter" or raw == state.raw):
                        failed = True
                        raise RuntimeError("budget physical replace succeeded, ACK lost")
                with patch.object(helper, "atomic_bytes", side_effect=lost_ack):
                    with self.assertRaises(RuntimeError):
                        helper.enter(state, helper.sha(state.raw)) if where == "enter" else helper.restore(state)
                self.assertTrue((state.release / "HALTED.json").exists())
                helper.restore(state)
                self.assertEqual(state.CONFIG.read_bytes(), state.raw)

    def test_restore_health_or_resource_failure_keeps_owned_hold_for_retry(self):
        for failure in ("health", "resource"):
            with self.subTest(failure=failure), self.fixture() as state:
                helper.enter(state, helper.sha(state.raw))
                original_snapshot = state.snapshot
                if failure == "health":
                    with patch.object(helper, "port_digest", return_value="d" * 64):
                        with self.assertRaisesRegex(helper.Rejected, "MODEL_DIGEST_CHANGED"):
                            helper.restore(state)
                else:
                    state.snapshot = lambda config: {"allowNewActivations": False, "allowHeavyBuilds": False}
                    with self.assertRaisesRegex(helper.Rejected, "ORIGINAL_RESOURCE_RESTORE_GATE_CLOSED"):
                        helper.restore(state)
                    state.snapshot = original_snapshot
                self.assertTrue((state.release / "HALTED.json").exists())
                helper.restore(state)
                self.assertEqual(state.CONFIG.read_bytes(), state.raw)

    def test_resource_snapshot_can_close_enter_and_receipt_stays_recoverable(self):
        with self.fixture() as state:
            original_snapshot = state.snapshot
            state.snapshot = lambda config: {"allowHeavyBuilds": False}
            with self.assertRaisesRegex(helper.Rejected, "ORIGINAL_RESOURCE_BUILD_GATE_CLOSED"):
                helper.enter(state, helper.sha(state.raw))
            self.assertTrue((state.release / "HALTED.json").exists())
            state.snapshot = original_snapshot
            helper.restore(state)
            self.assertEqual(state.CONFIG.read_bytes(), state.raw)

    def test_both_resource_source_pins_are_checked_before_any_import(self):
        with tempfile.TemporaryDirectory() as directory:
            baseline = Path(directory) / "before.py"
            installed = Path(directory) / "installed.py"
            baseline.write_text("raise RuntimeError('must never import')")
            installed.write_text("raise RuntimeError('must never import')")
            with patch.object(helper, "RESOURCE", installed), patch.object(helper, "trusted"):
                with self.assertRaisesRegex(helper.Rejected, "RESOURCE_SOURCE_PIN_MISMATCH"):
                    helper.load_control(baseline)
                with patch.object(helper, "BASELINE_SHA", helper.sha(baseline.read_bytes())):
                    with self.assertRaisesRegex(helper.Rejected, "RESOURCE_SOURCE_PIN_MISMATCH"):
                        helper.load_control(baseline)

    def test_mutating_entry_requires_root_isolation_before_loading_operator_code(self):
        with patch.object(helper.sys, "argv", ["tool", "enter", "--budget-sha256", "a" * 64]), \
                patch.object(helper.os, "geteuid", return_value=1000, create=True), \
                patch.object(helper, "load_control") as load:
            with self.assertRaisesRegex(helper.Rejected, "ISOLATED_TEST_ROOT_ENTRY_REQUIRED"):
                helper.main()
            load.assert_not_called()

    def test_enter_and_restore_cannot_interleave_the_same_receipt_or_drop_the_hold(self):
        for first in ("enter", "restore"):
            with self.subTest(first=first), self.fixture() as state:
                if first == "restore":
                    helper.enter(state, helper.sha(state.raw))
                entered, release, errors = threading.Event(), threading.Event(), []
                original_command = state.command
                blocked_action = "stop" if first == "enter" else "start"
                blocked = False
                def command(*args):
                    nonlocal blocked
                    if args[1] == blocked_action and not blocked:
                        blocked = True
                        entered.set()
                        if not release.wait(5):
                            raise AssertionError("concurrency test did not release the first operation")
                    return original_command(*args)
                def run_first():
                    try:
                        helper.enter(state, helper.sha(state.raw)) if first == "enter" else helper.restore(state)
                    except Exception as error:
                        errors.append(error)
                with patch.object(helper, "command", side_effect=command):
                    original = threading.Thread(target=run_first)
                    original.start()
                    try:
                        self.assertTrue(entered.wait(5))
                        before = list(state.events)
                        with self.assertRaisesRegex(helper.Rejected, "MAINTENANCE_OPERATION_ALREADY_RUNNING"):
                            helper.restore(state) if first == "enter" else helper.enter(state, helper.sha(state.raw))
                        self.assertEqual(state.events, before)
                        self.assertTrue((state.release / "HALTED.json").exists())
                    finally:
                        release.set()
                        original.join(5)
                    self.assertFalse(original.is_alive())
                    self.assertEqual(errors, [])
                if first == "enter":
                    helper.restore(state)
                self.assertEqual(state.CONFIG.read_bytes(), state.raw)
                self.assertFalse((state.release / "HALTED.json").exists())
                self.assertTrue(all(state.containers[name]["running"] for name in helper.FLEET))

    def test_operation_file_lock_is_root_checked_and_nonblocking(self):
        from unittest.mock import Mock
        flock = Mock()
        fcntl = SimpleNamespace(LOCK_EX=2, LOCK_NB=4, LOCK_UN=8, flock=flock)
        with tempfile.TemporaryDirectory() as directory:
            state = SimpleNamespace(STATE=Path(directory))
            with patch.dict(helper.sys.modules, {"fcntl": fcntl}), patch.object(helper, "trusted") as trusted, \
                    patch.object(helper.os, "O_NOFOLLOW", 0, create=True):
                with helper.operation_lock(state):
                    self.assertTrue((state.STATE / "maintenance-operation.lock").is_file())
                    self.assertEqual(flock.call_args.args[1], fcntl.LOCK_EX | fcntl.LOCK_NB)
                self.assertEqual(flock.call_args.args[1], fcntl.LOCK_UN)
                self.assertIn(((state.STATE / "maintenance-operation.lock",), {}), trusted.call_args_list)
                flock.reset_mock(side_effect=True)
                flock.side_effect = BlockingIOError()
                with self.assertRaisesRegex(helper.Rejected, "MAINTENANCE_OPERATION_ALREADY_RUNNING"):
                    with helper.operation_lock(state):
                        self.fail("busy operation lock must not enter")
                self.assertEqual(flock.call_count, 1)


if __name__ == "__main__":
    unittest.main()

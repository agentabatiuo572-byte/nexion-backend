"""Run: python -m unittest scripts.ops.test_resource_control -v (no live services)."""
import importlib.util
from contextlib import closing, nullcontext
from concurrent.futures import ThreadPoolExecutor
import json
from pathlib import Path
import sqlite3
import tempfile
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import patch


SPEC = importlib.util.spec_from_file_location("resource_control", Path(__file__).with_name("resource_control.py"))
control = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(control)


class ResourceControlTests(unittest.TestCase):
    def test_stopped_spare_requires_known_exact_name_and_safe_restart_policy(self):
        spare = {"type": "docker", "name": "uvel-cloud-bge-model-fleet-7"}
        config = {"commitments": [], "buildControl": {"type": "docker", "name": "ci"},
                  "buildBudgetBytes": 100, "stoppedControls": [spare]}
        for policy in ("no", "unless-stopped"):
            with self.subTest(policy=policy), patch.object(control, "verify_limit"), \
                    patch.object(control, "run", return_value=json.dumps({"running": False, "restart": policy})) as command:
                control.verify_limits(config)
                command.assert_called_once()
                self.assertEqual(command.call_args.args[-1], spare["name"])
        for state, reason in (({"running": True, "restart": "unless-stopped"}, "STOPPED_CONTAINER_RUNNING"),
                              ({"running": False, "restart": "always"}, "STOPPED_CONTAINER_RESTART_POLICY_REJECTED"),
                              ({"running": False, "restart": "on-failure"}, "STOPPED_CONTAINER_RESTART_POLICY_REJECTED")):
            with self.subTest(state=state), patch.object(control, "verify_limit"), \
                    patch.object(control, "run", return_value=json.dumps(state)):
                with self.assertRaisesRegex(control.Rejected, reason):
                    control.verify_limits(config)
        with patch.object(control, "verify_limit"), patch.object(control, "run") as command:
            with self.assertRaisesRegex(control.Rejected, "STOPPED_CONTROL_INVALID"):
                control.verify_limits({**config, "stoppedControls": [{"type": "docker", "name": "arbitrary-business-container"}]})
            command.assert_not_called()

    def test_stopped_spare_drift_closes_snapshot_and_blocks_build_before_reservation(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config = {"commitments": [], "buildControl": {"type": "docker", "name": "ci"},
                      "buildBudgetBytes": 100, "maxActiveExecutors": 7, "buildRequests": {"backend": 100},
                      "stoppedControls": [{"type": "docker", "name": "uvel-cloud-bge-model-fleet-7"}]}
            with patch.object(control, "STATE", root), patch.object(control, "RUNTIME", root), \
                    patch.object(control, "JOBS", root / "jobs"), patch.object(control, "locked", return_value=nullcontext()), \
                    patch.object(control, "memory", return_value=(1000, 1000)), patch.object(control, "verify_limit"), \
                    patch.object(control, "run", return_value=json.dumps({"running": True, "restart": "unless-stopped"})), \
                    patch.object(control, "atomic", side_effect=lambda path, value, mode=0o600: path.write_text(json.dumps(value))):
                snapshot = control.snapshot(config)
                self.assertFalse(snapshot["allowNewActivations"])
                self.assertEqual(snapshot["resourceCheckError"], "STOPPED_CONTAINER_RUNNING")
                with self.assertRaisesRegex(control.Rejected, "STOPPED_CONTAINER_RUNNING"):
                    control.admit_build(config, "backend", 1)
            self.assertFalse((root / "build-reservations.json").exists())

    def test_persistent_drain_survives_empty_runtime_after_host_reboot(self):
        with tempfile.TemporaryDirectory() as directory:
            state, runtime = Path(directory) / "state", Path(directory) / "new-run"
            state.mkdir()
            runtime.mkdir()
            (state / "drain-receipt.json").write_text(json.dumps({"ready": False, "drainId": "retained"}))
            config = {"buildBudgetBytes": 100, "maxActiveExecutors": 7, "commitments": [{"bytes": 600}]}
            with patch.object(control, "STATE", state), patch.object(control, "RUNTIME", runtime), \
                    patch.object(control, "locked", return_value=nullcontext()), \
                    patch.object(control, "reservations", return_value={}), \
                    patch.object(control, "memory", return_value=(1000, 1000)), \
                    patch.object(control, "verify_limits"), \
                    patch.object(control, "atomic", side_effect=lambda path, value, mode: path.write_text(json.dumps(value))):
                snapshot = control.snapshot(config)
            self.assertTrue(snapshot["draining"])
            self.assertFalse(snapshot["allowNewActivations"])

    def test_disappearing_inflight_journal_blocks_drain_without_signalling(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            initial = [{"path": "inflight", "phase": "CLAIMED"}, {"path": "idle", "phase": "IDLE"}]
            remaining = [{"path": "idle", "phase": "IDLE"}]
            with patch.object(control, "STATE", root), patch.object(control, "RUNTIME", root), \
                    patch.object(control, "locked", return_value=nullcontext()), \
                    patch.object(control, "atomic", side_effect=lambda path, value, mode=0o600: path.write_text(json.dumps(value))), \
                    patch.object(control, "inventory", side_effect=[initial, remaining, remaining]), \
                    patch.object(control, "snapshot"), patch.object(control, "run") as command, \
                    patch.object(control, "service_state", side_effect=[{"ActiveState": "active", "MainPID": "123"},
                                                                         {"ActiveState": "inactive", "MainPID": "0"}]):
                with self.assertRaisesRegex(control.Rejected, "DRAIN_JOURNAL_MISSING"):
                    control.drain({"workerServices": [{"name": "uvel-cloud-bge-fleet.service"}]})
                command.assert_not_called()
            self.assertTrue((root / "drain.json").exists())
            self.assertFalse(json.loads((root / "drain-receipt.json").read_text())["ready"])

    def test_twenty_percent_and_full_reserved_commitments(self):
        config = {"buildBudgetBytes": 100, "maxActiveExecutors": 7,
                  "commitments": [{"bytes": 650}]}
        self.assertEqual(control.capacity(config, 1001, 400, 0, 123)["reserveBytes"], 201)
        self.assertTrue(control.capacity(config, 1001, 201, 0, 123)["allowNewActivations"])
        self.assertFalse(control.capacity(config, 1001, 200, 0, 123)["allowNewActivations"])
        config["commitments"][0]["bytes"] = 701
        self.assertFalse(control.capacity(config, 1001, 1001, 0, 123)["allowNewActivations"])

    def test_running_builds_cannot_oversell_reserved_budget_or_available_memory(self):
        config = {"buildBudgetBytes": 200, "maxActiveExecutors": 7,
                  "commitments": [{"bytes": 600}]}
        control.check_build_admission(config, 1000, 450, 100, 100)
        with self.assertRaisesRegex(control.Rejected, "BUILD_BUDGET_EXHAUSTED"):
            control.check_build_admission(config, 1000, 1000, 101, 100)
        with self.assertRaisesRegex(control.Rejected, "HOST_MEMORY_RESERVE_REQUIRED"):
            control.check_build_admission(config, 1000, 399, 100, 100)
        self.assertFalse(control.capacity(config, 1000, 299, 100, 123)["allowNewActivations"])

    def test_protected_unknown_is_exact_and_does_not_authorize_new_unknown(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "journal.sqlite3"
            with closing(sqlite3.connect(path)) as db:
                with db:
                    db.executescript("CREATE TABLE state(id INTEGER PRIMARY KEY,value TEXT);"
                                     "CREATE TABLE calls(id INTEGER PRIMARY KEY,outcome TEXT);"
                                     "CREATE TABLE durable_retries(id INTEGER PRIMARY KEY,value TEXT);")
                    db.execute("INSERT INTO state VALUES(1,?)", (json.dumps({"phase": "UNKNOWN_COMPLETE", "binding": {"deviceId": 1204}}),))
                    db.execute("INSERT INTO calls VALUES(1,'UNKNOWN')")
            original = control.journal_fingerprint(path)
            protected = [{**original, "allowedPhases": ["UNKNOWN_COMPLETE"]}]
            control.check_journals([original], protected)
            with closing(sqlite3.connect(path)) as db:
                with db:
                    db.execute("INSERT INTO durable_retries VALUES(1,'used')")
            with self.assertRaisesRegex(control.Rejected, "PROTECTED_JOURNAL_CHANGED"):
                control.check_journals([control.journal_fingerprint(path)], protected)
            with self.assertRaisesRegex(control.Rejected, "JOURNAL_PENDING_RECONCILIATION"):
                control.check_journals([{**original, "path": "new.sqlite3"}], [])

    def test_idle_and_not_sent_are_safe_but_intent_and_missing_protected_are_not(self):
        control.check_journals([{"path": "empty", "phase": "IDLE"}, {"path": "unsent", "phase": "NEXT_NOT_SENT"}], [])
        with self.assertRaisesRegex(control.Rejected, "JOURNAL_PENDING_RECONCILIATION"):
            control.check_journals([{"path": "inflight", "phase": "COMPLETE_INTENT"}], [])
        with self.assertRaisesRegex(control.Rejected, "PROTECTED_JOURNAL_MISSING"):
            control.check_journals([], [{"path": "old"}])

    def test_snapshot_expiry_and_distinct_executor_capacity_are_explicit(self):
        result = control.capacity({"buildBudgetBytes": 100, "maxActiveExecutors": 7,
                                   "commitments": [{"bytes": 600}]}, 1000, 500, 0, 123)
        self.assertEqual(result["maxActiveExecutors"], 7)
        self.assertEqual(result["expiresAtEpochSeconds"], 138)
        self.assertEqual(result["committedBytes"], 700)

    def test_simultaneous_build_reservations_have_one_winner(self):
        with tempfile.TemporaryDirectory() as directory:
            root, lock, ready = Path(directory), threading.Lock(), threading.Barrier(2)
            config = {"buildBudgetBytes": 200, "maxActiveExecutors": 7,
                      "commitments": [{"bytes": 600}], "buildRequests": {"backend": 150}}

            def reserve(number):
                ready.wait()
                try:
                    control.admit_build(config, "backend", number)
                    return "ADMITTED"
                except control.Rejected as error:
                    return str(error)

            with patch.object(control, "STATE", root), patch.object(control, "JOBS", root / "jobs"), \
                    patch.object(control, "locked", return_value=lock), patch.object(control, "verify_limits"), \
                    patch.object(control, "memory", return_value=(1000, 1000)), \
                    patch.object(control, "build_completed", return_value=False), patch.object(control, "snapshot"), \
                    patch.object(control, "atomic", side_effect=lambda path, value, mode=0o600: path.write_text(json.dumps(value))), \
                    ThreadPoolExecutor(max_workers=2) as workers:
                results = list(workers.map(reserve, (1, 2)))
            self.assertCountEqual(results, ["ADMITTED", "BUILD_BUDGET_EXHAUSTED"])
            self.assertEqual(len(json.loads((root / "build-reservations.json").read_text())), 1)

    def test_trigger_exit_does_not_release_nonterminal_build(self):
        with patch.object(control, "trusted"), patch.object(control, "admit_build", return_value={"buildReservation": "backend-1"}), \
                patch.object(control.subprocess, "run", return_value=SimpleNamespace(returncode=0)), \
                patch.object(control, "build_completed", return_value=False), patch.object(control, "snapshot") as snapshot:
            with self.assertRaisesRegex(control.Rejected, "BUILD_NOT_TERMINAL_RESERVATION_HELD"):
                control.run_build({}, "backend", 1, [str(Path(__file__).resolve())])
            snapshot.assert_not_called()

    def test_release_hold_keeps_drain_marker_and_never_starts_workers(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "migrations").mkdir()
            (root / "migrations/START_BLOCKED").write_text("held")
            (root / "drain.json").write_text(json.dumps({"drainId": "exact"}))
            (root / "drain-receipt.json").write_text(json.dumps({"ready": True, "drainId": "exact"}))
            with patch.object(control, "STATE", root), patch.object(control, "RUNTIME", root), \
                    patch.object(control, "RELEASE_STATE", root / "state.json"), \
                    patch.object(control, "locked", return_value=nullcontext()), patch.object(control, "run") as command:
                with self.assertRaisesRegex(control.Rejected, "RELEASE_RECOVERY_REQUIRES_REVIEW"):
                    control.resume({})
                command.assert_not_called()
            self.assertTrue((root / "drain.json").exists())

    def test_drain_finishes_existing_work_before_signalling_and_rereads_after_exit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            initial = [{"path": "current", "phase": "CLAIMED"}]
            idle = [{"path": "current", "phase": "IDLE"}]
            config = {"workerServices": [{"name": "uvel-cloud-bge-fleet.service"}]}
            order = []

            def read_inventory(_):
                order.append("inventory")
                return initial if len(order) == 1 else idle

            def command(*args):
                self.assertEqual(order, ["inventory", "inventory"])
                order.append("sigterm")
                self.assertEqual(args[0:2], ("systemctl", "kill"))
                self.assertIn("--signal=SIGTERM", args)
                self.assertNotIn("SIGKILL", args)

            def write(path, value, mode=0o600):
                path.write_text(json.dumps(value))

            with patch.object(control, "STATE", root), patch.object(control, "RUNTIME", root), \
                    patch.object(control, "locked", return_value=nullcontext()), \
                    patch.object(control, "atomic", side_effect=write), \
                    patch.object(control, "inventory", side_effect=read_inventory), \
                    patch.object(control, "snapshot"), patch.object(control, "run", side_effect=command), \
                    patch.object(control, "service_state", side_effect=[{"ActiveState": "active", "MainPID": "123"},
                                                                         {"ActiveState": "inactive", "MainPID": "0"}]):
                result = control.drain(config)
            self.assertEqual(order, ["inventory", "inventory", "sigterm", "inventory"])
            self.assertEqual(result["drain"], "READY")
            self.assertTrue(json.loads((root / "drain-receipt.json").read_text())["ready"])


if __name__ == "__main__":
    unittest.main()

"""Run python -m unittest scripts.ops.test_compute_v2_resource_control -v; no host services."""
from contextlib import closing, contextmanager, nullcontext
import importlib.util
import io
import json
from pathlib import Path, PurePosixPath
import sqlite3
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from scripts.ops import test_resource_control as legacy_tests


def module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


ROOT = Path(__file__).resolve().parents[2]
control = module("compute_v2_resource_control", ROOT / "deploy/public-test/compute-v2-resource-control.py")
worker = module("compute_v2_resource_worker", ROOT / "scripts/compute-v2-worker.py")
baseline = legacy_tests.control


class ExistingResourceControlTests(legacy_tests.ResourceControlTests):
    """Run all existing resource guards against the extended independent candidate."""
    def setUp(self):
        self.replace = patch.object(legacy_tests, "control", control)
        self.replace.start()
        self.addCleanup(self.replace.stop)


class ComputeV2ResourceControlTests(unittest.TestCase):
    @contextmanager
    def journals(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            journals = [worker.Journal(root / f"runner-{number}.sqlite") for number in range(1, 7)]
            config = {"workerServices": [{"name": control.V2_SERVICE, "journalRoots": [str(root)]}]}
            try:
                with patch.object(control, "V2_ROOT", root):
                    yield root, journals, config
            finally:
                for journal in journals:
                    journal.close()

    def test_only_the_exact_v2_service_and_root_extend_the_budget_whitelist(self):
        config = {"version": 1, "scope": "TEST", "buildBudgetBytes": 100, "maxActiveExecutors": 1,
                  "commitments": [{"name": "backend", "kind": "backend", "bytes": 100,
                                   "control": {"type": "systemd"}},
                                  {"name": "model", "kind": "model", "bytes": 100,
                                   "control": {"type": "docker"}}],
                  "buildControl": {"type": "docker"},
                  "buildRequests": {component: 100 for component in control.COMPONENTS}}
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "budget.json"
            with patch.object(control, "CONFIG", path), patch.object(control, "trusted"):
                valid = {"name": control.V2_SERVICE, "journalRoots": ["/var/lib/uvel-compute-v2"]}
                path.write_text(json.dumps({**config, "workerServices": [valid]}))
                self.assertEqual(control.load_config()["workerServices"], [valid])
                for entry, reason in (({**valid, "name": "uvel-compute-v2-worker-2.service"}, "WORKER_SERVICE_INVALID"),
                                      ({**valid, "journalRoots": ["/tmp/uvel-compute-v2"]}, "JOURNAL_ROOT_INVALID"),
                                      ({**valid, "journalRoots": ["/var/lib/uvel-compute-v2/nested"]}, "JOURNAL_ROOT_INVALID"),
                                      ({**valid, "journalRoots": ["/var/lib/uvel-compute-v2/../other"]}, "JOURNAL_ROOT_INVALID")):
                    with self.subTest(entry=entry):
                        path.write_text(json.dumps({**config, "workerServices": [entry]}))
                        with self.assertRaisesRegex(control.Rejected, reason):
                            control.load_config()

    def test_six_real_journals_are_required_and_extra_sqlite_is_rejected(self):
        with self.journals() as (root, journals, config):
            inventory = control.inventory(config)
            self.assertEqual({Path(item["path"]).name for item in inventory}, control.V2_JOURNALS)
            self.assertEqual(len(inventory), 6)
            control.check_journals(inventory, [])
            missing = root / "runner-6.sqlite"
            moved = root / "runner-6.saved"
            journals[5].close()
            missing.rename(moved)
            with self.assertRaisesRegex(control.Rejected, "V2_JOURNAL_INVENTORY_INCOMPLETE"):
                control.inventory(config)
            moved.rename(missing)
            (root / "runner-7.sqlite").touch()
            with self.assertRaisesRegex(control.Rejected, "V2_JOURNAL_INVENTORY_INCOMPLETE"):
                control.inventory(config)

    def test_fingerprint_reads_committed_wal_and_hashes_recovery_history_without_writes(self):
        with self.journals() as (root, journals, _):
            path, journal = root / "runner-1.sqlite", journals[0]
            original = control.v2_journal_fingerprint(path)
            task = {"taskNo": "CV2-" + "A" * 32, "generation": 1, "deviceId": 11}
            journal.set({"phase": "RECOVERY_REQUIRED", "task": task})
            self.assertTrue(path.with_name(path.name + "-wal").exists())
            recovered = control.v2_journal_fingerprint(path)
            self.assertEqual(recovered["deviceId"], 11)
            self.assertNotEqual(original["stateSha256"], recovered["stateSha256"])
            changes = journal.db.total_changes
            with patch.object(control.sqlite3, "connect", wraps=sqlite3.connect) as connect:
                self.assertEqual(control.v2_journal_fingerprint(path), recovered)
                self.assertIn("?mode=ro", connect.call_args.args[0])
                self.assertTrue(connect.call_args.kwargs["uri"])
            self.assertEqual(journal.db.total_changes, changes)
            with journal.db:
                journal.db.execute("INSERT INTO recovery_history(task_no,generation,original_json,receipt_json) VALUES(?,?,?,?)",
                                   (task["taskNo"], 1, '{"phase":"RECOVERY_REQUIRED"}', '{"exitConfirmed":true}'))
            history = control.v2_journal_fingerprint(path)
            self.assertEqual(history["stateSha256"], recovered["stateSha256"])
            self.assertNotEqual(history["callsSha256"], recovered["callsSha256"])
            self.assertNotEqual(history["contentSha256"], recovered["contentSha256"])
            self.assertEqual(set(history), set(original))

    def test_wrong_schema_multiple_state_rows_and_symlink_are_not_journals(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "runner-1.sqlite"
            with closing(sqlite3.connect(path)) as db, db:
                db.executescript('CREATE TABLE state(id INTEGER,value TEXT);CREATE TABLE calls(id INTEGER);'
                                 'INSERT INTO state VALUES(1,\'{"phase":"IDLE"}\');')
            with self.assertRaisesRegex(control.Rejected, "V2_JOURNAL_SCHEMA_INVALID"):
                control.v2_journal_fingerprint(path)
            with closing(sqlite3.connect(path)) as db, db:
                db.executescript("DROP TABLE calls;CREATE TABLE recovery_history(task_no TEXT,generation INTEGER);"
                                 "INSERT INTO state VALUES(2,'{}');")
            with self.assertRaisesRegex(control.Rejected, "JOURNAL_STATE_INVALID"):
                control.v2_journal_fingerprint(path)
            with patch.object(control.Path, "is_symlink", return_value=True):
                with self.assertRaisesRegex(control.Rejected, "JOURNAL_FILE_INVALID"):
                    control.v2_journal_fingerprint(path)

    def test_unprotected_v2_only_idle_is_safe_and_recovery_is_never_generic_unknown_permission(self):
        for phase in ("CLAIM_INTENT", "CLAIMED", "COMPUTE_INTENT", "COMPLETE_INTENT", "VERIFIED",
                      "RECOVERY_REQUIRED", "UNKNOWN", "UNKNOWN_NEXT", "NEXT_NOT_SENT"):
            with self.subTest(phase=phase):
                with self.assertRaisesRegex(control.Rejected, "JOURNAL_PENDING_RECONCILIATION"):
                    control.check_journals([{"path": "/var/lib/uvel-compute-v2/runner-1.sqlite", "phase": phase}], [])
        control.check_journals([{"path": "/var/lib/uvel-compute-v2/runner-1.sqlite", "phase": "IDLE"}], [])

    def test_old_v1_hashes_and_exact_protected_unknown_budget_are_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "journal.sqlite3"
            with closing(sqlite3.connect(path)) as db, db:
                db.executescript("CREATE TABLE state(id INTEGER PRIMARY KEY,value TEXT);"
                                 "CREATE TABLE calls(id INTEGER PRIMARY KEY,request TEXT);CREATE TABLE audit(id INTEGER,value TEXT);")
                db.execute("INSERT INTO state VALUES(1,?)", (json.dumps({"phase": "UNKNOWN_NEXT", "binding": {"deviceId": 1380}}),))
                db.execute("INSERT INTO calls VALUES(1,'original')")
            original = baseline.journal_fingerprint(path)
            self.assertEqual(control.journal_fingerprint(path), original)
            protected = [{**original, "allowedPhases": ["UNKNOWN_NEXT"]}]
            control.check_journals([original], protected)
            with closing(sqlite3.connect(path)) as db, db:
                db.execute("INSERT INTO audit VALUES(1,'changed')")
            changed = control.journal_fingerprint(path)
            with self.assertRaisesRegex(control.Rejected, "PROTECTED_JOURNAL_CHANGED"):
                control.check_journals([changed], protected)

    def test_dynamic_user_link_requires_exact_root_owned_private_target(self):
        class VirtualPath(PurePosixPath):
            target = "/var/lib/private/uvel-compute-v2"
            owner = 0
            def is_dir(self): return True
            def is_symlink(self): return str(self) == "/var/lib/uvel-compute-v2"
            def resolve(self): return VirtualPath(self.target)
            def lstat(self): return SimpleNamespace(st_uid=self.owner)
            def glob(self, _): return [self / name for name in control.V2_JOURNALS]
        root = VirtualPath("/var/lib/uvel-compute-v2")
        config = {"workerServices": [{"name": control.V2_SERVICE, "journalRoots": [str(root)]}]}
        with patch.object(control, "Path", VirtualPath), patch.object(control, "V2_ROOT", root), \
                patch.object(control, "trusted") as trusted, \
                patch.object(control, "v2_journal_fingerprint", side_effect=lambda path: {"path": str(path)}):
            self.assertEqual(len(control.inventory(config)), 6)
            trusted.assert_called_once_with(VirtualPath("/var/lib/private"), directory=True)
            for target, owner in (("/tmp/uvel-compute-v2", 0), (VirtualPath.target, 1000)):
                with self.subTest(target=target, owner=owner), patch.object(VirtualPath, "target", target), patch.object(VirtualPath, "owner", owner):
                    with self.assertRaisesRegex(control.Rejected, "JOURNAL_ROOT_MISSING_OR_UNSAFE"):
                        control.inventory(config)
            old = {"workerServices": [{"name": "uvel-cloud-bge-worker.service", "journalRoots": [str(root)]}]}
            with self.assertRaisesRegex(control.Rejected, "JOURNAL_ROOT_MISSING_OR_UNSAFE"):
                control.inventory(old)

    def test_busy_v2_drain_holds_marker_and_never_signals_worker(self):
        with self.journals() as (_, journals, config), tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            journals[2].set({"phase": "RECOVERY_REQUIRED", "task": {"deviceId": 11}})
            config["drainTimeoutSeconds"] = 0
            with patch.object(control, "STATE", state), patch.object(control, "locked", return_value=nullcontext()), \
                    patch.object(control, "atomic", side_effect=lambda path, value, mode=0o600: path.write_text(json.dumps(value))), \
                    patch.object(control, "snapshot"), patch.object(control, "service_state", return_value={"ActiveState": "active", "MainPID": "123"}), \
                    patch.object(control, "run") as run:
                with self.assertRaisesRegex(control.Rejected, "DRAIN_INFLIGHT_TIMEOUT"):
                    control.drain(config)
                run.assert_not_called()
            self.assertTrue((state / "drain.json").exists())
            self.assertFalse(json.loads((state / "drain-receipt.json").read_text())["ready"])

    def test_six_idle_journals_are_reread_after_the_exact_v2_worker_exits(self):
        with self.journals() as (_, _, config), tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            with patch.object(control, "STATE", state), patch.object(control, "locked", return_value=nullcontext()), \
                    patch.object(control, "atomic", side_effect=lambda path, value, mode=0o600: path.write_text(json.dumps(value))), \
                    patch.object(control, "snapshot"), patch.object(control, "service_state", side_effect=[
                        {"ActiveState": "active", "MainPID": "123"}, {"ActiveState": "inactive", "MainPID": "0"}]), \
                    patch.object(control, "run") as run:
                result = control.drain(config)
                run.assert_called_once_with("systemctl", "kill", "--kill-whom=main", "--signal=SIGTERM", control.V2_SERVICE)
            self.assertEqual(result["drain"], "READY")
            receipt = json.loads((state / "drain-receipt.json").read_text())
            self.assertTrue(receipt["ready"])
            self.assertEqual(len(receipt["finalInventory"]), 6)
            self.assertEqual(receipt["initialInventory"], receipt["finalInventory"])

    @contextmanager
    def maintenance_receipt(self):
        with self.journals() as (_, _, config), tempfile.TemporaryDirectory() as directory:
            state = Path(directory)
            release = state / "release"
            release.mkdir()
            final = control.inventory(config)
            receipt = {"ready": True, "drainId": "maintenance-exact", "finalInventory": final,
                       "protectedJournals": [], "services": {control.V2_SERVICE: "active"}}
            (state / "drain.json").write_text(json.dumps({"drainId": receipt["drainId"]}))
            (state / "drain-receipt.json").write_text(json.dumps(receipt))
            services = {control.V2_SERVICE: {"ActiveState": "inactive", "MainPID": "0"},
                        "nexgrid-backend.service": {"ActiveState": "active", "MainPID": "77"},
                        "nexgrid-release.timer": {"ActiveState": "inactive"},
                        "nexgrid-release.service": {"ActiveState": "inactive", "MainPID": "0"}}
            health = {"code": 0, "data": {}}
            opener = SimpleNamespace(open=lambda *_args, **_kwargs: io.BytesIO(json.dumps(health).encode()))
            with patch.object(control, "STATE", state), patch.object(control, "RELEASE_STATE", release / "state.json"), \
                    patch.object(control, "locked", return_value=nullcontext()), \
                    patch.object(control, "service_state", side_effect=lambda name: services[name]), \
                    patch.object(control.urllib.request, "build_opener", return_value=opener), \
                    patch.object(control, "atomic", side_effect=lambda path, value, mode=0o600: path.write_text(json.dumps(value))), \
                    patch.object(control.os, "O_DIRECTORY", 0, create=True), \
                    patch.object(control.os, "open", return_value=123), patch.object(control.os, "fsync"), patch.object(control.os, "close"), \
                    patch.object(control, "run") as commands, patch.object(control, "snapshot") as snapshot:
                yield state, release, config, receipt, services, health, commands, snapshot

    def test_resume_maintenance_archives_original_service_intent_without_starting_workers(self):
        with self.maintenance_receipt() as (state, _, config, receipt, _, _, commands, snapshot):
            result = control.resume(config, restart_workers=False)
            self.assertEqual(result["drain"], "MAINTENANCE_READY")
            commands.assert_not_called()
            archived = json.loads((state / "last-drain-receipt.json").read_text())
            self.assertTrue(archived["workersKeptStopped"])
            self.assertEqual(archived["services"], receipt["services"])
            self.assertFalse((state / "drain.json").exists())
            self.assertFalse((state / "drain-receipt.json").exists())
            snapshot.assert_called_once_with(config)

    def test_resume_maintenance_keeps_all_original_ready_identity_health_and_inventory_guards(self):
        cases = ("ready", "drainId", "releaseHold", "backend", "health", "inventory", "protected", "workers", "timer", "releaseService")
        for failure in cases:
            with self.subTest(failure=failure), self.maintenance_receipt() as (state, release, config, receipt, services, health, commands, snapshot):
                if failure == "ready": receipt["ready"] = False
                elif failure == "drainId": receipt["drainId"] = "foreign"
                elif failure == "releaseHold": (release / "HALTED.json").write_text("{}")
                elif failure == "backend": services["nexgrid-backend.service"]["ActiveState"] = "inactive"
                elif failure == "health": health["code"] = 1
                elif failure == "inventory": receipt["finalInventory"] = []
                elif failure == "protected": config["protectedJournals"] = [{"path": "foreign"}]
                elif failure == "workers": services[control.V2_SERVICE] = {"ActiveState": "active", "MainPID": "123"}
                elif failure == "timer": services["nexgrid-release.timer"]["ActiveState"] = "active"
                else: services["nexgrid-release.service"] = {"ActiveState": "active", "MainPID": "456"}
                (state / "drain-receipt.json").write_text(json.dumps(receipt))
                with self.assertRaises(control.Rejected):
                    control.resume(config, restart_workers=False)
                commands.assert_not_called()
                snapshot.assert_not_called()
                self.assertTrue((state / "drain.json").exists())
                self.assertTrue((state / "drain-receipt.json").exists())
                self.assertFalse((state / "last-drain-receipt.json").exists())

    def test_resume_maintenance_persists_evidence_before_marker_cleanup_and_holds_partial_cleanup(self):
        with self.maintenance_receipt() as (state, _, config, _, _, _, commands, _):
            unlink = Path.unlink
            def lost_ack(path, *args, **kwargs):
                unlink(path, *args, **kwargs)
                if path == state / "drain-receipt.json":
                    raise RuntimeError("receipt unlink succeeded but ACK lost")
            with patch.object(control.Path, "unlink", side_effect=lost_ack, autospec=True):
                with self.assertRaisesRegex(RuntimeError, "ACK lost"):
                    control.resume(config, restart_workers=False)
            self.assertTrue((state / "drain.json").exists())
            self.assertFalse((state / "drain-receipt.json").exists())
            self.assertTrue(json.loads((state / "last-drain-receipt.json").read_text())["workersKeptStopped"])
            with self.assertRaisesRegex(control.Rejected, "DRAIN_RECEIPT_OR_MARKER_MISSING"):
                control.resume(config, restart_workers=False)
            commands.assert_not_called()


if __name__ == "__main__":
    unittest.main()

"""State-machine checks on owned temporary files; never contact the host."""
import hashlib
import io
import json
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch

import recover_h3_weekly_gate_1137 as r


class H3RecoveryTests(unittest.TestCase):
    def setUp(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        base = Path(temporary.name)
        self.cd = base / "cd"
        self.root = self.cd / "migrations"
        self.folder = base / "backups" / "20260923-171315-d5a10811669e"
        self.archive = self.root / "archive-h3-1137-20260923-171315"
        self.root.mkdir(parents=True)
        self.folder.mkdir(parents=True)
        self.sha = "a" * 40
        completed = [f"20260923_completed_{i:02d}.sql" for i in range(15)]
        old = {f"20260801_old_{i:03d}.sql": {"sha256": "0" * 64, "status": "BASELINE_NOT_REPLAYED"}
               for i in range(262)}
        now = dict(old)
        for name in completed:
            data = name.encode()
            (self.folder / name).write_bytes(data)
            now[name] = {"sha256": hashlib.sha256(data).hexdigest(), "status": "APPLIED",
                         "sha": r.OLD_SHA, "backup": str(self.folder / "database.sql.gz")}
        state = {"version": 1, "database": "nexion", "scripts": now}
        before = {"version": 1, "database": "nexion", "scripts": old}
        active = {"phase": "SQL_FAILED", "sha": r.OLD_SHA, "database": "nexion",
                  "current": r.FAILED, "folder": str(self.folder),
                  "completed": completed, "pending": completed + r.OLD_PENDING_SUFFIX,
                  "backup": {"path": str(self.folder / "database.sql.gz"),
                             "sha256": r.BACKUP_HASH, "uncompressed_bytes": r.BACKUP_BYTES}}
        for path, value in ((self.root / "active.json", active),
                            (self.folder / "receipt.json", active),
                            (self.root / "state.json", state),
                            (self.folder / "state-before.json", before),
                            (self.cd / "state.json", {"backend": {"build": 96, "sha": "old"}})):
            path.write_text(json.dumps(value))
        (self.folder / r.FAILED).write_bytes(b"failed original script")
        (self.folder / (r.FAILED + ".stderr")).write_bytes(
            b"ERROR 1137 (HY000) at line 48: Can't reopen table: 'e'")
        (self.root / "START_BLOCKED").write_text(json.dumps({"reason": "DATABASE_MIGRATION_HOLD"}))
        guard = base / "guard.conf"
        guard.write_text(r.GUARD_TEXT)
        self.patches = [
            patch.object(r, "CD", self.cd), patch.object(r, "ROOT", self.root),
            patch.object(r, "FOLDER", self.folder), patch.object(r, "ARCHIVE", self.archive),
            patch.object(r, "INTENT", self.root / "h3-1137-recovery.json"),
            patch.object(r, "HALT", self.cd / "HALTED.json"),
            patch.object(r, "GUARD", guard),
            patch.object(r, "trusted"), patch.object(r, "sync"),
            patch.object(r, "verify_backup"), patch.object(r, "verify_db"),
            patch.object(r, "verify_candidate", return_value="c" * 64),
        ]
        for item in self.patches:
            item.start()
            self.addCleanup(item.stop)
        self.real_sha256 = r.sha256
        self.sha_patch = patch.object(r, "sha256", side_effect=self.hash)
        self.sha_patch.start()
        self.addCleanup(self.sha_patch.stop)

    def hash(self, path):
        return r.OLD_SQL_HASH if path == self.folder / r.FAILED else self.real_sha256(path)

    def test_dry_audit_preserves_all_state(self):
        old = (self.root / "active.json").read_bytes()
        r.verify_failed_state()
        self.assertEqual((self.root / "active.json").read_bytes(), old)
        self.assertFalse((self.cd / "HALTED.json").exists())

    def test_changed_registered_entry_holds_before_archive(self):
        state = json.loads((self.root / "state.json").read_text())
        state["scripts"]["20260801_old_001.sql"]["sha256"] = "1" * 64
        (self.root / "state.json").write_text(json.dumps(state))
        with self.assertRaisesRegex(RuntimeError, "STATE_DELTA_CHANGED"):
            r.arm(self.sha)
        self.assertTrue((self.root / "active.json").exists())
        self.assertFalse(self.archive.exists())

    def test_arm_archives_failure_and_release_keeps_backend_hold(self):
        r.arm(self.sha)
        self.assertFalse((self.root / "active.json").exists())
        self.assertTrue((self.archive / "active.json").exists())
        self.assertTrue((self.folder / "receipt.json").exists())
        self.assertTrue((self.cd / "HALTED.json").exists())
        self.assertTrue((self.root / "START_BLOCKED").exists())
        self.assertEqual(json.loads((self.root / "h3-1137-recovery.json").read_text())["phase"],
                         "ARCHIVED")
        with self.assertRaisesRegex(RuntimeError, "RECOVERY_INTENT_CHANGED"):
            r.release("b" * 40)
        r.release(self.sha)
        self.assertFalse((self.cd / "HALTED.json").exists())
        self.assertTrue((self.root / "START_BLOCKED").exists())
        self.assertEqual(json.loads((self.root / "h3-1137-recovery.json").read_text())["phase"],
                         "READY_FOR_MANUAL_FORWARD_DEPLOY")

    def test_failed_archive_step_retains_old_journal_and_broker_halt(self):
        with patch.object(Path, "mkdir", side_effect=OSError("fixture failure")):
            with self.assertRaises(OSError):
                r.arm(self.sha)
        self.assertTrue((self.root / "active.json").exists())
        self.assertTrue((self.cd / "HALTED.json").exists())
        self.assertTrue((self.root / "START_BLOCKED").exists())

    def test_release_rejects_backend_release_drift(self):
        r.arm(self.sha)
        (self.cd / "state.json").write_text(json.dumps({"backend": {"build": 97, "sha": "other"}}))
        with self.assertRaisesRegex(RuntimeError, "BACKEND_RELEASE_STATE_CHANGED"):
            r.release(self.sha)
        self.assertTrue((self.cd / "HALTED.json").exists())


class CandidateCatalogTests(unittest.TestCase):
    def test_only_fixed_h3_may_be_pending(self):
        sha = "a" * 40
        old = b"unchanged"
        state = {"scripts": {"20260901_existing.sql": {
            "sha256": hashlib.sha256(old).hexdigest()}}}

        def archive(files):
            buffer = io.BytesIO()
            with tarfile.open(fileobj=buffer, mode="w:gz") as tar:
                for name, data in files.items():
                    path = f"nexion-backend-{sha}/scripts/migrations/{name}"
                    info = tarfile.TarInfo(path)
                    info.size = len(data)
                    tar.addfile(info, io.BytesIO(data))
            return buffer.getvalue()

        class Response(io.BytesIO):
            status = 200
            url = f"https://codeload.github.com/agentabatiuo572-byte/nexion-backend/tar.gz/{sha}"

        files = {"20260901_existing.sql": old, r.FAILED: b"fixed"}
        with patch.object(r.urllib.request, "urlopen", return_value=Response(archive(files))):
            self.assertEqual(r.verify_candidate(sha, state), hashlib.sha256(b"fixed").hexdigest())
        files["20260923_h3_weekly_event_gate_recovery_audit.sql"] = b"UPDATE nx_mission SET status=1"
        with patch.object(r.urllib.request, "urlopen", return_value=Response(archive(files))):
            with self.assertRaisesRegex(RuntimeError, "CANDIDATE_PENDING_NOT_EXACT_H3"):
                r.verify_candidate(sha, state)
        del files["20260923_h3_weekly_event_gate_recovery_audit.sql"]
        files["20260923_i6_invalid_published_key_retirement.sql"] = b"premature I6"
        with patch.object(r.urllib.request, "urlopen", return_value=Response(archive(files))):
            with self.assertRaisesRegex(RuntimeError, "CANDIDATE_PENDING_NOT_EXACT_H3"):
                r.verify_candidate(sha, state)


if __name__ == "__main__":
    unittest.main()

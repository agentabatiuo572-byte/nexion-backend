"""Offline recovery protocol regressions; fixtures use only temporary SQLite journals."""
from contextlib import redirect_stdout
from io import StringIO
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from test_cloud_bge_worker import w, config, identity, task, ok, Backend, Ollama


class RecoveryBackend(Backend):
    def __init__(self, cfg, journal, path):
        super().__init__(cfg, journal, path)
        self.found = False
        self.remote_state = "COMMITTED"
        self.response = self.task
        self.status_changes = {}
        self.status_error = None
        self.recovery_headers = []
        self.stability_review = None
        self.legacy_headers = []

    def request(self, method, path, body=None, key=None, original_next_key=None,
                recovery_token=None, request_generation=None, legacy_review_id=None):
        if "/stability-review?" in path:
            self.calls.append((method, path, key))
            if self.stability_review is None:
                raise w.Halt("OFFLINE_REVIEW_NOT_APPROVED")
            return ok(self.stability_review)
        if path.startswith(w.PREFIX + "/requests/status?"):
            self.calls.append((method, path, key))
            if self.status_error:
                raise self.status_error
            query = w.urllib.parse.parse_qs(w.urllib.parse.urlsplit(path).query)
            operation = query["operation"][0]
            return ok({**identity(self.cfg), "operation": operation, "requestKeyHash": w.sha256(key.encode()),
                       "requestHash": query["requestHash"][0], "state": self.remote_state,
                       "executionGeneration": 1, "taskNo": self.task["taskNo"], "taskStatus": "RUNNING",
                       "response": self.response if self.remote_state == "COMMITTED" else None,
                       "recoveryAllowed": self.remote_state == "ROLLED_BACK", "maxAttempts": 1,
                       "terminationAllowed": self.journal.state().get("completeBody", {}).get("proofTimestamp", int(time.time() * 1000)) < int(time.time() * 1000) - 120000,
                       "recoveryToken": "test-permit-do-not-log", **self.status_changes})
        if path == w.PREFIX + "/requests/terminate":
            self.calls.append((method, path, key))
            self.recovery_headers.append((recovery_token, request_generation))
            return ok({**identity(self.cfg), "state": "ROLLED_BACK", "taskNo": self.task["taskNo"],
                       "taskStatus": "CANCELLED", "executionGeneration": 2, "recoveryAllowed": False})
        if recovery_token is not None:
            self.recovery_headers.append((recovery_token, request_generation))
        if legacy_review_id is not None:
            self.legacy_headers.append(legacy_review_id)
        return super().request(method, path, body, key)


class DurableRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.drain_env = patch.dict(w.os.environ, {"UVEL_CLOUD_DRAIN_FILE": str(Path(self.tmp.name) / "drain.json")})
        self.drain_env.start()
        self.path = Path(self.tmp.name) / "journal.sqlite3"
        self.cfg = config()
        self.journal = w.Journal(self.path)
        self.backend = RecoveryBackend(self.cfg, self.journal, self.path)
        self.ollama = Ollama(self.cfg)
        self.worker = w.Worker(self.cfg, self.backend, self.ollama, self.journal, threading.Event())
        self.worker.start_heartbeat = lambda: self.worker.heartbeat()

    def tearDown(self):
        self.journal.db.close()
        self.tmp.cleanup()
        self.drain_env.stop()

    def unknown_next(self):
        state = {"phase": "UNKNOWN_NEXT", "binding": self.cfg.binding, "requestProtocol": 1, "nextKey": "BGE-NEXT-" + "d" * 32}
        call = self.journal.intent("NEXT", state["nextKey"], None, {}, state)
        self.journal.outcome(call, "UNKNOWN")
        return state

    def unknown_complete(self, timestamp=None):
        state = self.unknown_next()
        self.journal.db.execute("UPDATE calls SET outcome='CONFIRMED' WHERE operation='NEXT'")
        self.journal.db.commit()
        raw = w.artifact(self.ollama.vectors, 2)
        body = {"specVersion": w.SPEC, "inputHash": self.backend.task["inputHash"],
                "modelDigest": self.cfg.binding["modelDigest"], "resultArtifactBase64": w.base64.b64encode(raw).decode(),
                "resultHash": w.sha256(raw), "proofNonce": self.backend.task["proofNonce"],
                "proofTimestamp": timestamp or int(time.time() * 1000), "claimToken": self.backend.task["claimToken"]}
        state.update(phase="UNKNOWN_COMPLETE", taskNo=self.backend.task["taskNo"], inputHash=body["inputHash"],
                     resultHash=body["resultHash"], completeBody=body)
        key = "BGE-COMPLETE-" + w.sha256(state["taskNo"].encode())
        call = self.journal.intent("COMPLETE", key, state["taskNo"], body, state)
        self.journal.outcome(call, "UNKNOWN")
        return state

    def test_unknown_next_recovers_unique_original_task_without_new_next_or_erasing_unknown(self):
        original = self.unknown_next()
        self.assertTrue(self.worker.recover())
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='NEXT'").fetchall(), [("UNKNOWN",)])
        self.assertEqual(sum(path.endswith("/next-task") for _, path, _ in self.backend.calls), 0)
        self.assertEqual(sum(path.endswith("/complete") for _, path, _ in self.backend.calls), 1)
        self.assertTrue(any(path.endswith("/manifest") and key == original["nextKey"] for _, path, key in self.backend.calls))

    def test_unknown_next_noncommitted_and_failed_reads_never_authorize_execution(self):
        original = self.unknown_next()
        for state in ("NOT_FOUND", "PROCESSING", "UNKNOWN", "ROLLED_BACK"):
            with self.subTest(state=state):
                self.backend.remote_state = state
                with self.assertRaises(w.Halt):
                    self.worker.recover()
                self.assertEqual(self.journal.state(), original)
                self.assertEqual(self.ollama.calls, [])
        self.backend.status_error = w.Halt("HTTP_OUTCOME_UNKNOWN")
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertEqual(self.journal.state(), original)

    def test_unknown_next_wrong_hash_binding_or_task_is_never_adopted(self):
        original = self.unknown_next()
        for change in ({"requestHash": "f" * 64}, {"requestKeyHash": "e" * 64}, {"ownerId": 42},
                       {"operation": "COMPLETE"}, {"taskNo": "CTA-" + "B" * 32}, {"executionGeneration": 0}):
            with self.subTest(change=change):
                self.backend.status_changes = change
                with self.assertRaises(w.Halt):
                    self.worker.recover()
                self.assertEqual(self.journal.state(), original)
                self.assertEqual(self.ollama.calls, [])

    def test_durable_rollback_replays_exact_body_once_after_atomic_permit_reservation(self):
        original = self.unknown_complete()
        self.backend.remote_state = "ROLLED_BACK"
        self.assertTrue(self.worker.recover())
        self.assertEqual(w.sha256(w.json_bytes(self.backend.complete_body)), w.sha256(w.json_bytes(original["completeBody"])))
        self.assertEqual(self.backend.recovery_headers, [("test-permit-do-not-log", 1)])
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='COMPLETE' ORDER BY id").fetchall(), [("UNKNOWN",), ("CONFIRMED",)])
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM durable_retries").fetchone()[0], 1)
        self.assertFalse(any(path == "/api/embed" for _, path, _ in self.ollama.calls))

    def test_complete_nonrollback_status_and_negative_receipt_never_replay(self):
        original = self.unknown_complete()
        for state in ("NOT_FOUND", "PROCESSING", "UNKNOWN", "COMMITTED"):
            with self.subTest(state=state):
                self.backend.remote_state = state
                with self.assertRaises(w.Halt):
                    self.worker.recover()
                self.assertEqual(self.journal.state(), original)
                self.assertIsNone(self.backend.complete_body)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM durable_retries").fetchone()[0], 0)

    def test_expired_original_body_is_terminated_and_never_refreshed_or_completed(self):
        original = self.unknown_complete(int(time.time() * 1000) - 121000)
        self.backend.remote_state = "ROLLED_BACK"
        self.assertTrue(self.worker.recover())
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertIsNone(self.backend.complete_body)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 0)
        row = self.journal.db.execute("SELECT task_no,status FROM terminal_jobs").fetchone()
        self.assertEqual(row, (original["taskNo"], "CANCELLED"))
        self.assertTrue(any(path.endswith("/eligibility") for _, path, _ in self.backend.calls))

    def test_spent_ambiguous_durable_permit_survives_restart_without_second_post(self):
        self.unknown_complete()
        self.backend.remote_state = "ROLLED_BACK"
        self.backend.complete_error = w.Halt("HTTP_OUTCOME_UNKNOWN")
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.worker = w.Worker(self.cfg, self.backend, self.ollama, self.journal, threading.Event())
        count = len([path for _, path, _ in self.backend.calls if path.endswith("/complete")])
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertEqual(len([path for _, path, _ in self.backend.calls if path.endswith("/complete")]), count)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM durable_retries").fetchone()[0], 1)

    def test_late_old_worker_response_cannot_overwrite_a_new_execution_generation(self):
        self.backend.found = True
        actual = self.backend.request
        def replace_runner(method, path, body=None, key=None, **kwargs):
            data = actual(method, path, body, key, **kwargs)
            if path.endswith("/next-task"):
                w.Worker(self.cfg, self.backend, self.ollama, self.journal, threading.Event())
            return data
        self.backend.request = replace_runner
        with self.assertRaises(w.Halt):
            self.worker.one()
        self.assertNotEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual(self.ollama.calls, [])
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='NEXT'").fetchone(), ("UNKNOWN",))

    def test_drain_marker_blocks_next_before_intent_and_keeps_original_key(self):
        drain = Path(self.tmp.name) / "drain.json"
        drain.write_text('{"drainId":"offline-test"}')
        with patch.dict(w.os.environ, {"UVEL_CLOUD_DRAIN_FILE": str(drain)}):
            self.assertEqual(self.worker.one(), "NOT_SENT")
        self.assertEqual(self.backend.calls, [])
        self.assertEqual(self.journal.state()["phase"], "NEXT_NOT_SENT")

    def test_recovery_logs_only_request_summaries_and_fixed_exception_classes(self):
        original = self.unknown_complete()
        self.backend.remote_state = "ROLLED_BACK"
        output = StringIO()
        with redirect_stdout(output):
            self.worker.recover()
        log = output.getvalue()
        for secret in (original["nextKey"], original["completeBody"]["claimToken"],
                       original["completeBody"]["proofNonce"], "test-permit-do-not-log", original["completeBody"]["resultArtifactBase64"]):
            self.assertNotIn(secret, log)
        self.assertIn('"operation":"REQUEST_STATUS"', log)
        self.assertIn('"elapsedMicros":', log)

    def test_permit_budget_state_and_original_hash_roll_back_atomically(self):
        original = self.unknown_complete()
        self.backend.remote_state = "ROLLED_BACK"
        self.journal.db.execute("CREATE TRIGGER fail_budget BEFORE UPDATE ON state WHEN NEW.value LIKE '%DURABLE_RETRY_INTENT%' BEGIN SELECT RAISE(ABORT,'OFFLINE_WRITE_FAILURE'); END")
        self.journal.db.commit()
        with self.assertRaises(w.sqlite3.IntegrityError):
            self.worker.recover()
        self.assertEqual(self.journal.state(), original)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM durable_retries").fetchone()[0], 0)
        self.assertIsNone(self.backend.complete_body)

    def test_crash_after_permit_reservation_only_reconciles_after_restart(self):
        self.unknown_complete()
        self.backend.remote_state = "ROLLED_BACK"
        with patch.object(self.worker, "mutate", side_effect=w.Halt("OFFLINE_CRASH")):
            with self.assertRaises(w.Halt):
                self.worker.recover()
        self.assertEqual(self.journal.state()["phase"], "DURABLE_RETRY_INTENT")
        self.worker = w.Worker(self.cfg, self.backend, self.ollama, self.journal, threading.Event())
        self.backend.calls.clear()
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertTrue(all(method == "GET" for method, _, _ in self.backend.calls))
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM durable_retries").fetchone()[0], 1)

    def test_missing_or_tampered_permit_never_spends_budget(self):
        original = self.unknown_complete()
        self.backend.remote_state = "ROLLED_BACK"
        for changed in ({"recoveryAllowed": False}, {"maxAttempts": 2}, {"maxAttempts": True},
                        {"recoveryToken": None}, {"recoveryToken": ""}, {"recoveryToken": "bad\nheader"},
                        {"executionGeneration": 0}, {"requestHash": "a" * 64}, {"taskNo": "CTA-" + "F" * 32}):
            with self.subTest(changed=changed):
                self.backend.status_changes = changed
                with self.assertRaises(w.Halt):
                    self.worker.recover()
                self.assertEqual(self.journal.state(), original)
                self.assertIsNone(self.backend.complete_body)
                self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM durable_retries").fetchone()[0], 0)

    def test_local_body_cannot_be_changed_under_original_call_hash(self):
        original = self.unknown_complete()
        original["completeBody"]["proofTimestamp"] += 1
        self.journal.set(original)
        self.backend.remote_state = "ROLLED_BACK"
        with self.assertRaisesRegex(w.Halt, "DURABLE_ORIGINAL_CALL_INVALID"):
            self.worker.recover()
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM durable_retries").fetchone()[0], 0)
        self.assertIsNone(self.backend.complete_body)

    def test_local_expiration_without_server_termination_permission_cannot_clear_task(self):
        original = self.unknown_complete(int(time.time() * 1000) - 121000)
        self.backend.remote_state = "ROLLED_BACK"
        self.backend.status_changes = {"terminationAllowed": False}
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertEqual(self.journal.state(), original)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM durable_retries").fetchone()[0], 0)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM terminal_jobs").fetchone()[0], 0)

    def test_positive_complete_receipt_precedes_status_and_any_permit(self):
        self.unknown_complete()
        self.backend.found = True
        self.backend.status_error = w.Halt("HTTP_OUTCOME_UNKNOWN")
        self.assertTrue(self.worker.recover())
        self.assertEqual([path for _, path, _ in self.backend.calls], [w.PREFIX + "/tasks/" + self.backend.task["taskNo"] + "/receipt"])
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM durable_retries").fetchone()[0], 0)

    def test_confirmed_terminal_waits_for_canonical_idle_eligibility_before_new_work(self):
        self.unknown_complete(int(time.time() * 1000) - 121000)
        self.backend.remote_state = "ROLLED_BACK"
        self.backend.eligible = False
        with self.assertRaisesRegex(w.Halt, "TERMINAL_WAITING_SERVER_ELIGIBILITY"):
            self.worker.recover()
        self.assertEqual(self.journal.state()["phase"], "TERMINAL_CONFIRMED")
        count = len(self.backend.recovery_headers)
        self.backend.eligible = True
        self.assertTrue(self.worker.recover())
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual(len(self.backend.recovery_headers), count)
        self.assertFalse(any(path.endswith("/next-task") for _, path, _ in self.backend.calls))

    def test_legacy_unknown_devices_outside_two_reviewed_next_requests_remain_untouched(self):
        for device in (1152, 1380):
            with self.subTest(device=device):
                self.cfg.binding["deviceId"] = device
                state = self.unknown_next()
                state.pop("requestProtocol")
                self.journal.set(state)
                with self.assertRaises(w.Halt):
                    self.worker.recover()
                self.assertEqual(self.journal.state(), state)
                self.assertEqual(self.backend.calls, [])
                self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='NEXT' ORDER BY id DESC LIMIT 1").fetchone()[0], "UNKNOWN")

    def test_http_recovery_transmits_original_key_generation_and_opaque_token_headers(self):
        response = unittest.mock.Mock()
        response.status = 200
        response.read.return_value = w.json_bytes(ok({}))
        connection = unittest.mock.Mock()
        connection.getresponse.return_value = response
        with patch.object(w.http.client, "HTTPConnection", return_value=connection):
            w.Http(("127.0.0.1", 8110)).request("POST", w.PREFIX + "/tasks/CTA-test/complete", {},
                                            "BGE-COMPLETE-original", recovery_token="opaque-permit", request_generation=7)
        args = connection.request.call_args.args
        self.assertEqual(args[2], b"{}")
        self.assertEqual(args[3]["Idempotency-Key"], "BGE-COMPLETE-original")
        self.assertEqual(args[3]["X-UVEL-Recovery-Token"], "opaque-permit")
        self.assertEqual(args[3]["X-UVEL-Request-Generation"], "7")

    def test_reviewed_legacy_next_uses_positive_binding_without_inventing_a_generation(self):
        self.cfg.binding["deviceId"] = 1208
        self.backend.task = task(self.cfg)
        self.backend.response = self.backend.task
        original = self.unknown_next()
        original.pop("requestProtocol")
        self.journal.set(original)
        self.backend.status_changes = {"legacyPositiveBinding": True, "executionGeneration": None, "recoveryAllowed": False}
        self.assertTrue(self.worker.recover())
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual(sum(path.endswith("/next-task") for _, path, _ in self.backend.calls), 0)
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='NEXT'").fetchone()[0], "UNKNOWN")
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM durable_retries").fetchone()[0], 0)

    def test_legacy_positive_binding_cannot_also_authorize_a_recovery_permit(self):
        self.cfg.binding["deviceId"] = 1210
        self.backend.task = task(self.cfg)
        self.backend.response = self.backend.task
        original = self.unknown_next()
        original.pop("requestProtocol")
        self.journal.set(original)
        self.backend.status_changes = {"legacyPositiveBinding": True, "executionGeneration": None, "recoveryAllowed": True}
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertEqual(self.journal.state(), original)
        self.assertEqual(self.ollama.calls, [])

    def test_unsent_claim_server_expiry_is_explicit_terminal_then_fresh_eligibility(self):
        original = self.unknown_next()
        self.journal.db.execute("UPDATE calls SET outcome='CONFIRMED' WHERE operation='NEXT'")
        self.journal.db.commit()
        original.update(phase="CLAIMED", taskNo=self.backend.task["taskNo"], inputHash=self.backend.task["inputHash"])
        self.journal.set(original)
        receipt = {**self.backend.receipt(), "status": "EXPIRED"}
        with patch.object(self.backend, "receipt", return_value=receipt):
            self.assertTrue(self.worker.recover())
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual(self.journal.db.execute("SELECT status FROM terminal_jobs").fetchone()[0], "EXPIRED")
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 0)
        self.assertFalse(any(path.endswith("/complete") or path.endswith("/next-task") for _, path, _ in self.backend.calls))

    def test_committed_next_current_server_expiry_does_not_infer_or_fake_completion(self):
        self.unknown_next()
        self.backend.status_changes = {"taskStatus": "EXPIRED"}
        self.assertTrue(self.worker.recover())
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual(self.journal.db.execute("SELECT status FROM terminal_jobs").fetchone()[0], "EXPIRED")
        self.assertEqual(self.ollama.calls, [])
        self.assertFalse(any(path.endswith("/complete") or path.endswith("/next-task") for _, path, _ in self.backend.calls))

    def test_exhausted_completion_permit_may_only_terminate_after_a_second_explicit_rollback(self):
        original = self.unknown_complete()
        self.backend.remote_state = "ROLLED_BACK"
        self.backend.complete_error = w.Halt("HTTP_OUTCOME_UNKNOWN")
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.backend.complete_error = None
        self.backend.status_changes = {"executionGeneration": 2, "recoveryAllowed": False,
                                       "terminationAllowed": True, "recoveryToken": "termination-only-permit"}
        request = self.backend.request
        def terminal(method, path, body=None, key=None, **kwargs):
            result = request(method, path, body, key, **kwargs)
            if path.endswith("/requests/terminate"):
                result["data"]["executionGeneration"] = 3
            return result
        self.backend.request = terminal
        complete_count = sum(path.endswith("/complete") for _, path, _ in self.backend.calls)
        self.assertTrue(self.worker.recover())
        self.assertEqual(sum(path.endswith("/complete") for _, path, _ in self.backend.calls), complete_count)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM durable_retries").fetchone()[0], 1)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM termination_attempts").fetchone()[0], 1)
        self.assertEqual(self.journal.state()["phase"], "IDLE")

    def test_lost_termination_response_never_repeats_a_locally_spent_termination(self):
        self.unknown_complete(int(time.time() * 1000) - 121000)
        self.backend.remote_state = "ROLLED_BACK"
        request = self.backend.request
        def lost(method, path, body=None, key=None, **kwargs):
            result = request(method, path, body, key, **kwargs)
            if path.endswith("/requests/terminate"):
                raise w.Halt("HTTP_OUTCOME_UNKNOWN")
            return result
        self.backend.request = lost
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.worker = w.Worker(self.cfg, self.backend, self.ollama, self.journal, threading.Event())
        self.backend.calls.clear()
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertGreater(len(self.backend.calls), 0)
        self.assertTrue(all(method == "GET" for method, _, _ in self.backend.calls))
        self.assertTrue(any("/requests/status?" in path for _, path, _ in self.backend.calls))
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM termination_attempts").fetchone()[0], 1)
        self.backend.status_changes = {"taskStatus": "CANCELLED", "executionGeneration": 2,
                                       "recoveryAllowed": False, "terminationAllowed": False, "recoveryToken": None}
        self.backend.calls.clear()
        self.assertTrue(self.worker.recover())
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual(self.journal.db.execute("SELECT status FROM terminal_jobs").fetchone()[0], "CANCELLED")
        self.assertTrue(all(method == "GET" for method, _, _ in self.backend.calls))
        self.assertTrue(any(path.endswith("/eligibility") for _, path, _ in self.backend.calls))

    def test_drain_receipt_alone_blocks_next_without_reading_its_contents(self):
        receipt = Path(self.tmp.name) / "drain-receipt.json"
        receipt.write_text("offline-read-protected-receipt")
        receipt.chmod(0o600)
        self.assertEqual(self.worker.one(), "NOT_SENT")
        self.assertEqual(self.backend.calls, [])
        self.assertEqual(self.journal.state()["phase"], "NEXT_NOT_SENT")

    def test_missing_or_unreadable_drain_control_is_fail_closed(self):
        for env, failure in (({"UVEL_CLOUD_DRAIN_FILE": str(Path(self.tmp.name) / "missing" / "drain.json")}, None),
                             ({"UVEL_CLOUD_DRAIN_FILE": str(Path(self.tmp.name) / "drain.json")}, PermissionError("PRIVATE_ERROR"))):
            with self.subTest(failure=type(failure).__name__), patch.dict(w.os.environ, env):
                if failure:
                    with patch.object(w.Path, "lstat", side_effect=failure):
                        self.assertEqual(self.worker.one(), "NOT_SENT")
                else:
                    self.assertEqual(self.worker.one(), "NOT_SENT")
                self.assertEqual(self.backend.calls, [])
                self.assertEqual(self.journal.state()["phase"], "NEXT_NOT_SENT")

    def test_receipt_created_after_intent_blocks_the_send_and_preserves_the_same_key(self):
        intent = self.journal.intent
        def draining_intent(*args, **kwargs):
            call = intent(*args, **kwargs)
            (Path(self.tmp.name) / "drain-receipt.json").write_text("offline-nonce")
            return call
        with patch.object(self.journal, "intent", side_effect=draining_intent):
            self.assertEqual(self.worker.one(), "NOT_SENT")
        self.assertEqual(self.backend.calls, [])
        self.assertEqual(self.journal.db.execute("SELECT request_key,outcome FROM calls WHERE operation='NEXT'").fetchone(),
                         (self.journal.state()["nextKey"], "NOT_SENT"))

    def test_new_protocol_canonical_rejection_uses_exact_body_rollback_permit_not_old_fresh_proof_budget(self):
        self.backend.complete_error = w.Rejected("BACKEND_REJECTED_409", 409, "TEST_CLOUD_BINDING_CHANGED")
        with self.assertRaises(w.Halt):
            self.worker.one()
        original = self.journal.state()
        self.assertEqual(original["phase"], "REJECTED_COMPLETE")
        self.backend.complete_error = None
        self.backend.remote_state = "ROLLED_BACK"
        self.backend.calls.clear()
        self.ollama.calls.clear()
        self.assertTrue(self.worker.recover())
        self.assertEqual(w.sha256(w.json_bytes(self.backend.complete_body)), w.sha256(w.json_bytes(original["completeBody"])))
        self.assertEqual(self.backend.recovery_headers, [("test-permit-do-not-log", 1)])
        self.assertFalse(any(path == "/api/embed" for _, path, _ in self.ollama.calls))
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM rejected_retries").fetchone()[0], 0)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM durable_retries").fetchone()[0], 1)

    def test_first_complete_after_legacy_next_uses_its_own_completion_protocol_marker_for_4xx(self):
        self.cfg.binding["deviceId"] = 1208
        self.backend.task = task(self.cfg)
        self.backend.complete_error = w.Rejected("BACKEND_REJECTED_409", 409, "TEST_CLOUD_BINDING_CHANGED")
        with self.assertRaises(w.Halt):
            self.worker.one()
        original = self.journal.state()
        original.pop("requestProtocol")  # A legacy NEXT never had the new NEXT protocol marker.
        self.journal.set(original)
        self.assertEqual(original["completionProtocol"], 1)
        self.backend.complete_error = None
        self.backend.remote_state = "ROLLED_BACK"
        self.ollama.calls.clear()
        self.assertTrue(self.worker.recover())
        self.assertEqual(w.sha256(w.json_bytes(self.backend.complete_body)), w.sha256(w.json_bytes(original["completeBody"])))
        self.assertFalse(any(path == "/api/embed" for _, path, _ in self.ollama.calls))
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM rejected_retries").fetchone()[0], 0)


class StabilityReviewTests(unittest.TestCase):
    setUp = DurableRecoveryTests.setUp
    tearDown = DurableRecoveryTests.tearDown

    def reviewed_origin(self):
        self.cfg.binding.update(deviceId=1204, instanceNo="DEV-TI-27D1233EBF21434FB85EC6B49D66507F")
        self.backend.task = task(self.cfg)
        self.backend.task["taskNo"] = "CTA-F1D89023D75F473E8D7A57F4B0E6662D"
        key = "BGE-NEXT-" + "e" * 32
        original = {"phase": "UNKNOWN_COMPLETE", "binding": dict(self.cfg.binding), "nextKey": key,
                    "taskNo": self.backend.task["taskNo"], "inputHash": self.backend.task["inputHash"],
                    "resultHash": w.sha256(w.artifact(self.ollama.vectors, 2))}
        call = self.journal.intent("NEXT", key, None, {})
        self.journal.outcome(call, "CONFIRMED")
        complete_key = "BGE-COMPLETE-" + w.sha256(original["taskNo"].encode())
        self.journal.db.execute("INSERT INTO calls(operation,request_key,task_no,request_hash,outcome) VALUES('COMPLETE',?,?,?,'UNKNOWN')",
                                (complete_key, original["taskNo"], "f" * 64))
        self.journal.set(original)
        expected = {"deviceId": 1204, "instanceNo": self.cfg.binding["instanceNo"], "originalRequestHash": "f" * 64,
                    "inputHash": original["inputHash"], "resultHash": original["resultHash"],
                    "nextKeyHash": w.sha256(key.encode()), "completeKeyHash": w.sha256(complete_key.encode()),
                    "stateHash": w.sha256(w.json_bytes(original))}
        scoped = patch.object(w, "STABILITY_REVIEW_TASKS", {original["taskNo"]: expected}, create=True)
        scoped.start()
        self.addCleanup(scoped.stop)
        self.backend.stability_review = {**identity(self.cfg), "taskNo": original["taskNo"], "inputHash": original["inputHash"],
            "resultHash": original["resultHash"], "originalRequestHash": "f" * 64,
            "completeKeyHash": expected["completeKeyHash"], "nextKeyHash": expected["nextKeyHash"],
            "reviewId": 314159, "evidenceSha256": "d" * 64, "rollbackConfirmed": True, "maxAttempts": 1, "recoveryAllowed": True}
        self.backend.calls.clear()
        return original

    def test_new_incident_review_spends_only_its_independent_budget_and_fresh_manifest_once(self):
        original = self.reviewed_origin()
        self.assertTrue(self.worker.recover())
        self.assertEqual(self.backend.legacy_headers, [314159])
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM stability_retries").fetchone()[0], 1)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM rollback_retries").fetchone()[0], 0)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM rejected_retries").fetchone()[0], 0)
        self.assertEqual(self.journal.db.execute("SELECT request_hash,outcome FROM calls WHERE operation='COMPLETE' ORDER BY id LIMIT 1").fetchone(), ("f" * 64, "UNKNOWN"))
        origin_json, origin_hash = self.journal.db.execute("SELECT original_state_json,original_state_hash FROM stability_retries").fetchone()
        self.assertEqual(w.strict_json(origin_json.encode()), original)
        self.assertEqual(origin_hash, w.sha256(w.json_bytes(original)))
        self.assertTrue(any(path == "/api/embed" for _, path, _ in self.ollama.calls))

    def test_missing_or_forged_incident_review_cannot_compute_or_spend_old_budgets(self):
        original = self.reviewed_origin()
        valid = self.backend.stability_review
        for changed in (None, {**valid, "recoveryAllowed": False}, {**valid, "rollbackConfirmed": False}, {**valid, "maxAttempts": 2}, {**valid, "originalRequestHash": "a" * 64}):
            with self.subTest(changed=changed):
                self.backend.stability_review = changed
                with self.assertRaises(w.Halt):
                    self.worker.recover()
                self.assertEqual(self.journal.state(), original)
                self.assertEqual(self.ollama.calls, [])
                self.assertIsNone(self.backend.complete_body)

    def test_incident_review_unknown_after_first_recovery_never_gets_a_second_completion(self):
        self.reviewed_origin()
        self.backend.complete_error = w.Halt("HTTP_OUTCOME_UNKNOWN")
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.worker = w.Worker(self.cfg, self.backend, self.ollama, self.journal, threading.Event())
        before = len(self.backend.legacy_headers)
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertEqual(len(self.backend.legacy_headers), before)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM stability_retries").fetchone()[0], 1)

    def test_stability_budget_origin_and_review_roll_back_if_state_write_fails(self):
        original = self.reviewed_origin()
        self.journal.db.execute("CREATE TRIGGER fail_stability BEFORE UPDATE ON state WHEN NEW.value LIKE '%STABILITY_RETRY_INTENT%' BEGIN SELECT RAISE(ABORT,'OFFLINE_ATOMIC_WRITE'); END")
        self.journal.db.commit()
        with self.assertRaises(w.sqlite3.IntegrityError):
            self.worker.recover()
        self.assertEqual(self.journal.state(), original)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM stability_retries").fetchone()[0], 0)
        self.assertIsNone(self.backend.complete_body)

    def test_stability_scope_wrong_original_call_or_key_cannot_borrow_any_old_budget(self):
        original = self.reviewed_origin()
        for changed in ({"nextKey": "BGE-NEXT-" + "a" * 32}, {"resultHash": "b" * 64}, {"inputHash": "c" * 64}):
            with self.subTest(changed=changed):
                bad = {**original, **changed}
                self.journal.set(bad)
                self.backend.calls.clear()
                with self.assertRaises(w.Halt):
                    self.worker.recover()
                self.assertEqual(self.journal.state(), bad)
                self.assertFalse(any("/stability-review?" in path for _, path, _ in self.backend.calls))
                self.assertIsNone(self.backend.complete_body)
        self.journal.set(original)
        self.journal.db.execute("UPDATE calls SET request_hash=? WHERE operation='COMPLETE'", ("a" * 64,))
        self.journal.db.commit()
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM stability_retries").fetchone()[0], 0)

    def test_http_stability_replay_keeps_original_key_and_server_review_header(self):
        response = unittest.mock.Mock(status=200)
        response.read.return_value = w.json_bytes(ok({}))
        connection = unittest.mock.Mock()
        connection.getresponse.return_value = response
        with patch.object(w.http.client, "HTTPConnection", return_value=connection):
            w.Http(("127.0.0.1", 8110)).request("POST", w.PREFIX + "/tasks/CTA-test/complete", {},
                                            "BGE-COMPLETE-original", legacy_review_id=314159)
        headers = connection.request.call_args.args[3]
        self.assertEqual(headers["Idempotency-Key"], "BGE-COMPLETE-original")
        self.assertEqual(headers["X-UVEL-Legacy-Review-Id"], "314159")

    def test_live_scope_is_exact_three_disjoint_from_the_unchanged_historical_four(self):
        self.assertEqual(set(w.STABILITY_REVIEW_TASKS), {"CTA-F1D89023D75F473E8D7A57F4B0E6662D",
            "CTA-863FE539FA4943CB8695283A2BDCDB5F", "CTA-D8C51049769A469FB39DB21FEFA5374C"})
        self.assertFalse(set(w.STABILITY_REVIEW_TASKS) & set(w.ROLLBACK_REVIEW_TASKS))
        self.assertEqual({row["deviceId"] for row in w.STABILITY_REVIEW_TASKS.values()}, {1204, 1209, 1214})
        self.assertEqual(w.LEGACY_NEXT_RECOVERY_DEVICES, {1208, 1210})
        for task_no, scope in w.STABILITY_REVIEW_TASKS.items():
            self.assertEqual(scope["completeKeyHash"], w.sha256(("BGE-COMPLETE-" + w.sha256(task_no.encode())).encode()))
            self.assertTrue(all(w.hash64(scope[key]) for key in ("originalRequestHash", "inputHash", "resultHash", "nextKeyHash")))


class NextRollbackRecoveryTests(unittest.TestCase):
    setUp = DurableRecoveryTests.setUp
    tearDown = DurableRecoveryTests.tearDown
    unknown_next = DurableRecoveryTests.unknown_next

    def rollback(self, reply_error=None, after="COMMITTED", generation=2):
        original = self.unknown_next()
        self.backend.remote_state = "ROLLED_BACK"
        self.backend.status_changes = {"taskNo": None, "taskStatus": None}
        request = self.backend.request
        def replay(method, path, body=None, key=None, **headers):
            if method == "POST" and path.endswith("/next-task"):
                self.assertEqual(key, original["nextKey"])
                self.assertEqual(body, {})
                self.assertEqual(headers.get("recovery_token"), "test-permit-do-not-log")
                self.assertEqual(headers.get("request_generation"), 1)
                self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM next_retries").fetchone()[0], 1)
                self.assertTrue(self.journal.state()["nextRetry"])
            result = request(method, path, body, key, **headers)
            if method == "POST" and path.endswith("/next-task"):
                self.backend.remote_state = after
                self.backend.status_changes = {"executionGeneration": generation,
                    "taskNo": self.backend.task["taskNo"] if after == "COMMITTED" else None,
                    "taskStatus": "RUNNING" if after == "COMMITTED" else None,
                    "recoveryAllowed": False}
                if reply_error:
                    raise reply_error
            return result
        self.backend.request = replay
        return original

    def restart(self):
        self.journal.db.close()
        self.journal = w.Journal(self.path)
        self.backend.journal = self.journal
        self.worker = w.Worker(self.cfg, self.backend, self.ollama, self.journal, threading.Event())
        self.worker.start_heartbeat = lambda: self.worker.heartbeat()

    def test_confirmed_next_rollback_replays_original_key_and_empty_body_once_then_only_original_task(self):
        original = self.rollback()
        self.assertTrue(self.worker.recover())
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual(sum(path.endswith("/next-task") for _, path, _ in self.backend.calls), 1)
        self.assertEqual(sum(path.endswith("/complete") for _, path, _ in self.backend.calls), 1)
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='NEXT' ORDER BY id").fetchall(), [("UNKNOWN",), ("CONFIRMED",)])
        row = self.journal.db.execute("SELECT original_state_json,original_state_hash FROM next_retries").fetchone()
        self.assertEqual(w.strict_json(row[0].encode()), original)
        self.assertEqual(row[1], w.sha256(w.json_bytes(original)))
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 1)

    def test_lost_next_replay_response_restarts_with_status_and_no_second_post(self):
        self.rollback(reply_error=w.Halt("HTTP_OUTCOME_UNKNOWN"))
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='NEXT' ORDER BY id").fetchall(), [("UNKNOWN",), ("UNKNOWN",)])
        self.restart()
        self.backend.calls.clear()
        self.assertTrue(self.worker.recover())
        self.assertTrue(any("/requests/status?operation=NEXT" in path for _, path, _ in self.backend.calls))
        self.assertFalse(any(path.endswith("/next-task") for _, path, _ in self.backend.calls))
        self.assertEqual(sum(path.endswith("/complete") for _, path, _ in self.backend.calls), 1)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM next_retries").fetchone()[0], 1)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 1)

    def test_second_explicit_next_rollback_is_read_only_after_restart_even_with_another_token(self):
        self.rollback(reply_error=w.Rejected("BACKEND_REJECTED_409", 409, "TEST_CLOUD_BINDING_CHANGED"), after="ROLLED_BACK")
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.restart()
        self.backend.calls.clear()
        self.backend.status_changes.update(recoveryAllowed=True, recoveryToken="another-permit-must-not-be-used")
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertGreater(len(self.backend.calls), 0)
        self.assertTrue(all(method == "GET" for method, _, _ in self.backend.calls))
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM next_retries").fetchone()[0], 1)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM calls WHERE operation='NEXT'").fetchone()[0], 2)
        self.assertEqual(self.ollama.calls, [])

    def test_next_retry_wrong_committed_generation_cannot_execute(self):
        self.rollback(generation=1)
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertEqual(sum(path.endswith("/next-task") for _, path, _ in self.backend.calls), 1)
        self.assertEqual(self.ollama.calls, [])
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM next_retries").fetchone()[0], 1)

    def test_crash_after_next_budget_reservation_only_queries_after_restart(self):
        self.rollback()
        with patch.object(self.worker, "mutate", side_effect=w.Halt("OFFLINE_CRASH")):
            with self.assertRaises(w.Halt):
                self.worker.recover()
        self.restart()
        self.backend.calls.clear()
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertGreater(len(self.backend.calls), 0)
        self.assertTrue(all(method == "GET" for method, _, _ in self.backend.calls))
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM next_retries").fetchone()[0], 1)

    def test_next_budget_and_original_state_roll_back_atomically(self):
        original = self.rollback()
        self.journal.db.execute("CREATE TRIGGER fail_next_budget BEFORE UPDATE ON state WHEN NEW.value LIKE '%NEXT_RETRY_INTENT%' BEGIN SELECT RAISE(ABORT,'OFFLINE_NEXT_WRITE'); END")
        self.journal.db.commit()
        with self.assertRaises(w.sqlite3.IntegrityError):
            self.worker.recover()
        self.assertEqual(self.journal.state(), original)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM next_retries").fetchone()[0], 0)
        self.assertFalse(any(method == "POST" for method, _, _ in self.backend.calls))

    def test_legacy_next_rollback_does_not_get_the_new_protocol_replay_permission(self):
        for device in (1208, 1210):
            with self.subTest(device=device):
                self.cfg.binding["deviceId"] = device
                original = self.unknown_next()
                original.pop("requestProtocol")
                self.journal.set(original)
                self.backend.remote_state = "ROLLED_BACK"
                self.backend.status_changes = {"taskNo": None, "taskStatus": None}
                with self.assertRaises(w.Halt):
                    self.worker.recover()
                self.assertEqual(self.journal.state(), original)
                self.assertFalse(any(method == "POST" for method, _, _ in self.backend.calls))
                self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM next_retries").fetchone()[0], 0)

    def test_next_rollback_missing_or_wrong_permit_never_spends_budget(self):
        original = self.rollback()
        good = dict(self.backend.status_changes)
        for invalid in ({"recoveryAllowed": False}, {"recoveryToken": None}, {"maxAttempts": 2},
                        {"maxAttempts": True}, {"executionGeneration": 0}, {"requestHash": "f" * 64},
                        {"taskNo": self.backend.task["taskNo"]}, {"taskStatus": "RUNNING"}):
            with self.subTest(invalid=invalid):
                self.backend.status_changes = {**good, **invalid}
                with self.assertRaises(w.Halt):
                    self.worker.recover()
                self.assertEqual(self.journal.state(), original)
                self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM next_retries").fetchone()[0], 0)
                self.assertFalse(any(method == "POST" for method, _, _ in self.backend.calls))

    def test_next_rollback_current_ineligibility_capacity_and_drain_do_not_spend_budget(self):
        original = self.rollback()
        self.backend.eligible = False
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.backend.eligible = True
        self.worker.claim_capacity = threading.Event()
        with self.assertRaises(w.NotSent):
            self.worker.recover()
        self.worker.claim_capacity.set()
        (Path(self.tmp.name) / "drain-receipt.json").write_text("offline-drain")
        with self.assertRaises(w.NotSent):
            self.worker.recover()
        self.assertEqual(self.journal.state(), original)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM next_retries").fetchone()[0], 0)
        self.assertFalse(any(method == "POST" for method, _, _ in self.backend.calls))

    def test_original_proven_unsent_calls_before_rollback_are_preserved(self):
        original = self.rollback()
        self.journal.db.execute("UPDATE calls SET outcome='NOT_SENT' WHERE operation='NEXT'")
        self.journal.db.commit()
        call = self.journal.intent("NEXT", original["nextKey"], None, {}, original)
        self.journal.outcome(call, "UNKNOWN")
        self.assertTrue(self.worker.recover())
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='NEXT' ORDER BY id").fetchall(),
                         [("NOT_SENT",), ("UNKNOWN",), ("CONFIRMED",)])
        self.assertEqual(sum(path.endswith("/next-task") for _, path, _ in self.backend.calls), 1)

    def test_rejected_original_next_only_retries_when_status_explicitly_authorizes_rollback(self):
        self.rollback()
        self.journal.db.execute("UPDATE calls SET outcome='REJECTED' WHERE operation='NEXT'")
        self.journal.db.commit()
        self.assertTrue(self.worker.recover())
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='NEXT' ORDER BY id").fetchall(), [("REJECTED",), ("CONFIRMED",)])
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM next_retries").fetchone()[0], 1)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 1)

    def test_successful_next_replay_followed_by_status_read_loss_restarts_without_replay(self):
        self.rollback()
        request = self.backend.request
        reads = 0
        def lost_status(method, path, body=None, key=None, **headers):
            nonlocal reads
            response = request(method, path, body, key, **headers)
            if "/requests/status?operation=NEXT" in path:
                reads += 1
                if reads == 2:
                    raise w.Halt("HTTP_OUTCOME_UNKNOWN")
            return response
        self.backend.request = lost_status
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.restart()
        self.backend.calls.clear()
        self.assertTrue(self.worker.recover())
        self.assertTrue(any("/requests/status?operation=NEXT" in path for _, path, _ in self.backend.calls))
        self.assertFalse(any(path.endswith("/next-task") for _, path, _ in self.backend.calls))
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='NEXT' ORDER BY id").fetchall(), [("UNKNOWN",), ("CONFIRMED",)])
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 1)


if __name__ == "__main__":
    unittest.main()

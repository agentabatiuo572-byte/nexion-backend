"""Offline trust-boundary and durable recovery tests; no model, network or financial effects."""
import base64
from contextlib import closing
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import struct
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location("cloud_bge_worker", Path(__file__).with_name("cloud-bge-worker.py"))
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)


def config():
    return w.Config({"UVEL_CLOUD_WORKER_ENABLED": "true", "UVEL_CLOUD_WORKER_SCOPE": "TEST",
                     "UVEL_CLOUD_OWNER_ID": "91", "UVEL_CLOUD_DEVICE_ID": "92", "UVEL_CLOUD_INSTANCE_NO": "NEX-ORD-TEST",
                     "UVEL_CLOUD_TASK_CONFIG_ID": "E2-TEST", "UVEL_CLOUD_EXECUTOR_ID": "bge-offline",
                     "UVEL_CLOUD_MODEL": "bge-m3:567m", "UVEL_CLOUD_MODEL_DIGEST": "sha256:" + "1" * 64})


def identity(cfg):
    return {**cfg.binding, "executionKind": w.KIND, "specVersion": w.SPEC, "deploymentScope": "TEST", "serverCanonical": True}


def task(cfg):
    texts = ["UVEL TEST text", "多语言 TEST"]
    return {**identity(cfg), "taskNo": "CTA-" + "A" * 32, "status": "RUNNING", "claimToken": "b" * 64, "proofNonce": "c" * 64,
            "inputTexts": texts, "inputHash": w.sha256(w.json_bytes(texts)), "outputDimensions": 1024,
            "proofExpiresAt": int(time.time() * 1000) + 600000, "leaseExpiresAt": int(time.time() * 1000) + 600000,
            "completableAt": int(time.time() * 1000) - 1}


def ok(data):
    return {"code": 0, "data": data}


class Ollama:
    def __init__(self, cfg):
        self.cfg, self.calls = cfg, []
        self.tag_digest = cfg.binding["modelDigest"][7:]
        self.vectors = [[0.25] + [0.0] * 1023 for _ in range(2)]

    def request(self, method, path, body=None, key=None):
        self.calls.append((method, path, body))
        if path == "/api/tags":
            return {"models": [{"name": self.cfg.binding["model"], "digest": self.tag_digest}]}
        if path == "/api/embed":
            return {"model": self.cfg.binding["model"], "embeddings": self.vectors}
        raise AssertionError(path)


class Backend:
    def __init__(self, cfg, journal, db_path):
        self.cfg, self.journal, self.db_path = cfg, journal, db_path
        self.task, self.calls, self.complete_body = task(cfg), [], None
        self.next_error, self.complete_error, self.found = None, None, True
        self.idle = False

    def request(self, method, path, body=None, key=None):
        self.calls.append((method, path, key))
        if method == "POST":
            # A different SQLite connection sees FULL-committed intent before every backend mutation.
            with closing(sqlite3.connect(self.db_path)) as other:
                row = other.execute("SELECT outcome,request_hash FROM calls WHERE request_key=? ORDER BY id DESC", (key,)).fetchone()
            assert row == ("INTENT", w.sha256(w.json_bytes(body)))
        if path.endswith("/next-task"):
            if self.next_error:
                error, self.next_error = self.next_error, None
                raise error
            return ok({**identity(self.cfg), "idle": True, "retryAfterSeconds": 60} if self.idle else self.task)
        if path == w.PREFIX + "/heartbeat":
            phase = self.journal.state()["phase"]
            active = self.task["taskNo"] if phase in ("CLAIMED", "COMPLETE_INTENT", "UNKNOWN_COMPLETE", "REJECTED_RETRY_INTENT", "ROLLBACK_RETRY_INTENT") else None
            return ok({**self.cfg.binding, "serverCanonical": True, "heartbeatAt": int(time.time() * 1000), "activeTaskNo": active})
        if path.endswith("/complete"):
            self.complete_body = body
            if self.complete_error:
                raise self.complete_error
            self.found = True
            return ok(self.receipt())
        if path.endswith("/manifest"):
            return ok({**self.task, "resumeAllowed": True, "nextKeyHash": w.sha256(key.encode())})
        if path.endswith("/receipt"):
            return ok(self.receipt())
        raise AssertionError(path)

    def receipt(self):
        state = self.journal.state()
        return {**identity(self.cfg), "taskNo": self.task["taskNo"], "inputHash": self.task["inputHash"],
                "found": self.found, "status": "COMPLETED" if self.found else "RUNNING",
                "resultHash": state.get("resultHash") if self.found else None, "receiptNo": "CTR-" + "A" * 32 if self.found else None}


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.path = Path(self.tmp.name) / "journal.sqlite3"
        self.cfg = config()
        self.journal = w.Journal(self.path)
        self.backend = Backend(self.cfg, self.journal, self.path)
        self.ollama = Ollama(self.cfg)
        self.worker = w.Worker(self.cfg, self.backend, self.ollama, self.journal, threading.Event())
        # Test actual synchronous heartbeats; no sleeping or background scheduling needed offline.
        self.worker.start_heartbeat = lambda: self.worker.heartbeat()

    def tearDown(self):
        self.journal.db.close()
        self.tmp.cleanup()

    def interrupted_claim(self, with_hash=True):
        state = {"phase": "NEXT_INTENT", "nextKey": "BGE-NEXT-" + "d" * 32, "binding": self.cfg.binding}
        data = self.worker.mutate("NEXT", "/next-task", {}, state["nextKey"], state)
        state.update(phase="CLAIMED", taskNo=data["taskNo"], inputHash=data["inputHash"])
        if with_hash:
            state["resultHash"] = w.sha256(w.artifact(self.ollama.vectors, len(data["inputTexts"])))
        self.journal.set(state)
        self.backend.found = False
        self.backend.calls.clear()
        return state

    def test_stop_while_durable_intent_commits_never_sends_next_or_complete(self):
        original = self.journal.intent
        def stop_at_intent(*args, **kwargs):
            call = original(*args, **kwargs)
            self.worker.stop.set()
            return call
        with patch.object(self.journal, "intent", side_effect=stop_at_intent):
            for operation, path in (("NEXT", "/next-task"), ("COMPLETE", "/tasks/CTA-" + "A" * 32 + "/complete")):
                self.worker.stop.clear()
                with self.subTest(operation=operation), self.assertRaises(w.NotSent):
                    self.worker.mutate(operation, path, {}, "test-" + operation)
        self.assertEqual(self.backend.calls, [])
        self.assertEqual(self.journal.db.execute("SELECT operation,outcome FROM calls ORDER BY id").fetchall(),
                         [("NEXT", "NOT_SENT"), ("COMPLETE", "NOT_SENT")])

    def test_claimed_resume_reads_original_manifest_and_recomputes_before_first_complete(self):
        state = self.interrupted_claim()
        self.assertEqual(self.worker.run(once=True), 0)
        paths = [path for _, path, _ in self.backend.calls]
        self.assertEqual(paths[:2], [w.PREFIX + "/tasks/" + state["taskNo"] + "/receipt",
                                   w.PREFIX + "/tasks/" + state["taskNo"] + "/manifest"])
        self.assertFalse(any(path.endswith("/next-task") for path in paths))
        self.assertEqual(sum(path.endswith("/complete") for path in paths), 1)
        self.assertEqual(self.backend.complete_body["resultHash"], state["resultHash"])
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 1)

    def test_claimed_without_prior_hash_performs_real_inference_and_first_completion(self):
        self.interrupted_claim(with_hash=False)
        self.assertTrue(self.worker.recover())
        self.assertTrue(any(path == "/api/embed" for _, path, _ in self.ollama.calls))
        self.assertTrue(w.hash64(self.backend.complete_body["resultHash"]))

    def test_claimed_positive_receipt_confirms_without_manifest_inference_or_complete(self):
        state = self.interrupted_claim()
        self.backend.found = True
        self.assertTrue(self.worker.recover())
        self.assertEqual([method for method, _, _ in self.backend.calls], ["GET"])
        self.assertEqual(self.ollama.calls, [])
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual(self.journal.db.execute("SELECT result_hash FROM jobs").fetchone()[0], state["resultHash"])

    def test_claimed_positive_receipt_without_local_hash_requires_complete_canonical_receipt(self):
        self.interrupted_claim(with_hash=False)
        receipt = self.backend.receipt
        self.backend.receipt = lambda: {**receipt(), "found": True, "status": "COMPLETED", "resultHash": "e" * 64,
                                       "receiptNo": "CTR-" + "A" * 32}
        self.assertTrue(self.worker.recover())
        self.assertEqual(self.journal.db.execute("SELECT result_hash FROM jobs").fetchone()[0], "e" * 64)
        self.assertEqual(self.ollama.calls, [])
        self.assertEqual(len(self.backend.calls), 1)

    def test_claimed_positive_receipt_cannot_replace_a_different_persisted_result_hash(self):
        state = self.interrupted_claim()
        receipt = self.backend.receipt
        self.backend.receipt = lambda: {**receipt(), "found": True, "status": "COMPLETED", "resultHash": "f" * 64,
                                       "receiptNo": "CTR-" + "A" * 32}
        with self.assertRaisesRegex(w.Halt, "RECEIPT_RESULT_INVALID"):
            self.worker.recover()
        self.assertEqual(self.journal.state(), state)
        self.assertEqual(self.ollama.calls, [])
        self.assertEqual(len(self.backend.calls), 1)

    def test_claimed_any_complete_intent_blocks_resume_even_when_receipt_is_negative(self):
        for outcome in ("INTENT", "NOT_SENT", "REJECTED", "UNKNOWN", "CONFIRMED"):
            with self.subTest(outcome=outcome):
                self.journal.db.execute("DELETE FROM calls")
                self.journal.db.commit()
                state = self.interrupted_claim()
                call = self.journal.intent("COMPLETE", "BGE-COMPLETE-" + w.sha256(state["taskNo"].encode()), state["taskNo"], {})
                self.journal.outcome(call, outcome)
                with self.assertRaisesRegex(w.Halt, "CLAIM_COMPLETE_ALREADY_ATTEMPTED"):
                    self.worker.recover()
                self.assertEqual(len(self.backend.calls), 1)
                self.assertTrue(self.backend.calls[0][1].endswith("/receipt"))
                self.assertEqual(self.journal.state(), state)
                self.assertEqual(self.ollama.calls, [])

    def test_claimed_missing_or_unconfirmed_next_cannot_resume_from_negative_receipt(self):
        state = self.interrupted_claim()
        self.journal.db.execute("UPDATE calls SET outcome='UNKNOWN' WHERE operation='NEXT'")
        self.journal.db.commit()
        with self.assertRaisesRegex(w.Halt, "CLAIM_NEXT_NOT_CONFIRMED"):
            self.worker.recover()
        self.assertEqual(len(self.backend.calls), 1)
        self.assertEqual(self.journal.state(), state)

    def test_claimed_wrong_binding_key_input_or_expired_manifest_never_infers_or_completes(self):
        state = self.interrupted_claim()
        request = self.backend.request
        for changes in ({"deviceId": 93}, {"nextKeyHash": "f" * 64}, {"inputHash": "f" * 64},
                        {"modelDigest": "sha256:" + "f" * 64}, {"leaseExpiresAt": 1}, {"proofExpiresAt": 1},
                        {"resumeAllowed": False}):
            with self.subTest(changes=changes):
                def changed(method, path, body=None, key=None):
                    response = request(method, path, body, key)
                    if path.endswith("/manifest"):
                        response["data"].update(changes)
                    return response
                self.backend.request = changed
                self.backend.calls.clear()
                with self.assertRaises(w.Halt):
                    self.worker.recover()
                self.assertEqual(self.journal.state(), state)
                self.assertEqual(self.ollama.calls, [])
                self.assertFalse(any(method == "POST" for method, _, _ in self.backend.calls))

    def test_claimed_recomputed_hash_mismatch_preserves_old_hash_and_never_completes(self):
        state = self.interrupted_claim()
        self.ollama.vectors[0][0] = 0.5
        with self.assertRaisesRegex(w.Halt, "CLAIM_RESULT_HASH_CHANGED"):
            self.worker.recover()
        self.assertEqual(self.journal.state(), state)
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in self.backend.calls))

    def test_failed_claimed_recovery_stops_its_real_single_mode_heartbeat_thread(self):
        self.interrupted_claim()
        self.ollama.vectors[0][0] = 0.5
        worker = w.Worker(self.cfg, self.backend, self.ollama, self.journal, threading.Event())
        with self.assertRaisesRegex(w.Halt, "CLAIM_RESULT_HASH_CHANGED"):
            worker.run(once=True)
        self.assertIsNotNone(worker.heartbeat_thread)
        self.assertFalse(worker.heartbeat_thread.is_alive())

    def test_claimed_revoked_manifest_only_reads_and_cannot_resume(self):
        state = self.interrupted_claim()
        request = self.backend.request
        def revoked(method, path, body=None, key=None):
            if path.endswith("/manifest"):
                raise w.Rejected("BACKEND_REJECTED_403")
            return request(method, path, body, key)
        self.backend.request = revoked
        with self.assertRaises(w.Rejected):
            self.worker.recover()
        self.assertEqual(self.journal.state(), state)
        self.assertEqual(self.ollama.calls, [])
        self.assertFalse(any(method == "POST" for method, _, _ in self.backend.calls))

    def test_claimed_removed_instance_only_reads_receipt_and_does_not_request_manifest(self):
        self.interrupted_claim()
        with self.assertRaisesRegex(w.Halt, "STOP_PENDING_RECONCILIATION"):
            self.worker.recover(claimed_execution=False)
        self.assertEqual(len(self.backend.calls), 1)
        self.assertEqual(self.ollama.calls, [])

    def rejected_claim(self, legacy=False):
        state = self.interrupted_claim()
        self.backend.complete_error = w.Rejected("BACKEND_REJECTED_409", 409, "TEST_CLOUD_HEARTBEAT_STALE")
        with self.assertRaises(w.Halt):
            self.worker.execute(self.backend.task, state)
        state = self.journal.state()
        self.assertEqual(state["phase"], "REJECTED_COMPLETE")
        self.assertEqual(state["rejection"], {"httpStatus": 409, "errorCode": "TEST_CLOUD_HEARTBEAT_STALE"})
        if legacy:
            state["phase"] = "UNKNOWN_COMPLETE"
            state.pop("rejection")
            self.journal.set(state)
        self.backend.complete_error = None
        self.backend.calls.clear()
        self.ollama.calls.clear()
        return state

    def test_explicit_rejection_can_retry_original_task_once_with_a_new_proof_timestamp(self):
        origin = self.rejected_claim()
        first_timestamp = self.backend.complete_body["proofTimestamp"]
        with patch.object(w.time, "time", return_value=first_timestamp / 1000 + 1):
            self.assertTrue(self.worker.recover())
        rows = self.journal.db.execute("SELECT request_key,outcome FROM calls WHERE operation='COMPLETE'").fetchall()
        self.assertEqual([outcome for _, outcome in rows], ["REJECTED", "CONFIRMED"])
        self.assertEqual(rows[0][0], rows[1][0])
        self.assertGreater(self.backend.complete_body["proofTimestamp"], first_timestamp)
        self.assertEqual(self.backend.complete_body["resultHash"], origin["resultHash"])
        self.assertFalse(any(path.endswith("/next-task") for _, path, _ in self.backend.calls))
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM calls WHERE operation='REJECTED_RETRY'").fetchone()[0], 1)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 1)
        saved, saved_hash = self.journal.db.execute("SELECT original_state_json,original_state_hash FROM rejected_retries").fetchone()
        self.assertEqual(json.loads(saved), origin)
        self.assertEqual(saved_hash, w.sha256(w.json_bytes(origin)))

    def test_historical_unknown_with_only_canonical_rejected_call_is_classified_by_actual_call_evidence(self):
        self.rejected_claim(legacy=True)
        self.assertTrue(self.worker.recover())
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual(sum(path.endswith("/complete") for _, path, _ in self.backend.calls), 1)

    def test_rejected_recovery_positive_receipt_is_prior_to_any_retry_authorization(self):
        self.rejected_claim(legacy=True)
        self.journal.db.execute("UPDATE calls SET outcome='UNKNOWN' WHERE operation='COMPLETE'")
        self.journal.db.commit()
        self.backend.found = True
        self.assertTrue(self.worker.recover())
        self.assertEqual(len(self.backend.calls), 1)
        self.assertTrue(self.backend.calls[0][1].endswith("/receipt"))
        self.assertEqual(self.ollama.calls, [])
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM calls WHERE operation='REJECTED_RETRY'").fetchone()[0], 0)

    def test_any_non_rejected_or_foreign_key_complete_evidence_forbids_a_negative_receipt_retry(self):
        state = self.rejected_claim(legacy=True)
        key = "BGE-COMPLETE-" + w.sha256(state["taskNo"].encode())
        for outcome, request_key in [(outcome, key) for outcome in ("UNKNOWN", "INTENT", "CONFIRMED", "NOT_SENT")] + [("REJECTED", "BGE-COMPLETE-FOREIGN")]:
            with self.subTest(outcome=outcome, key=request_key):
                call = self.journal.intent("COMPLETE", request_key, state["taskNo"], {})
                self.journal.outcome(call, outcome)
                self.backend.calls.clear()
                with self.assertRaisesRegex(w.Halt, "STOP_PENDING_RECONCILIATION"):
                    self.worker.recover()
                self.assertEqual(len(self.backend.calls), 1)
                self.assertEqual(self.journal.state(), state)
                self.assertEqual(self.ollama.calls, [])
                self.journal.db.execute("DELETE FROM calls WHERE id=?", (call,))
                self.journal.db.commit()

    def test_second_explicit_rejection_preserves_the_spent_budget_across_restart(self):
        origin = self.rejected_claim()
        self.backend.complete_error = w.Rejected("BACKEND_REJECTED_409", 409, "TEST_CLOUD_HEARTBEAT_STALE")
        with self.assertRaises(w.Halt):
            self.worker.recover()
        spent = self.journal.state()
        self.assertEqual(spent["phase"], "REJECTED_COMPLETE")
        self.assertTrue(spent["rejectedRetry"])
        self.assertEqual(spent["rejectionRecoveryOrigin"], origin)
        self.journal.db.close()
        self.journal = w.Journal(self.path)
        self.worker.journal = self.backend.journal = self.journal
        self.backend.calls.clear()
        self.ollama.calls.clear()
        with self.assertRaisesRegex(w.Halt, "REJECTED_RETRY_BUDGET_USED"):
            self.worker.recover()
        self.assertEqual(len(self.backend.calls), 1)
        self.assertEqual(self.ollama.calls, [])
        self.assertEqual(self.journal.state(), spent)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM calls WHERE operation='COMPLETE'").fetchone()[0], 2)

    def test_two_historical_rejected_calls_without_a_budget_do_not_authorize_recovery(self):
        state = self.rejected_claim(legacy=True)
        key = "BGE-COMPLETE-" + w.sha256(state["taskNo"].encode())
        call = self.journal.intent("COMPLETE", key, state["taskNo"], {})
        self.journal.outcome(call, "REJECTED")
        with self.assertRaisesRegex(w.Halt, "STOP_PENDING_RECONCILIATION"):
            self.worker.recover()
        self.assertEqual(len(self.backend.calls), 1)
        self.assertTrue(self.backend.calls[0][1].endswith("/receipt"))
        self.assertEqual(self.ollama.calls, [])
        self.assertEqual(self.journal.state(), state)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM rejected_retries").fetchone()[0], 0)

    def test_crash_after_atomic_retry_reservation_can_only_read_receipt_after_restart(self):
        origin = self.rejected_claim()
        with patch.object(self.worker, "execute", side_effect=w.Halt("SIMULATED_CRASH")):
            with self.assertRaisesRegex(w.Halt, "SIMULATED_CRASH"):
                self.worker.recover()
        with closing(sqlite3.connect(self.path)) as other:
            state = json.loads(other.execute("SELECT value FROM state WHERE id=1").fetchone()[0])
            self.assertEqual(state["phase"], "REJECTED_RETRY_INTENT")
            self.assertEqual(state["rejectionRecoveryOrigin"], origin)
            self.assertEqual(other.execute("SELECT COUNT(*) FROM calls WHERE operation='REJECTED_RETRY' AND outcome='AUTHORIZED'").fetchone()[0], 1)
        self.journal.db.close()
        self.journal = w.Journal(self.path)
        self.worker.journal = self.backend.journal = self.journal
        self.backend.calls.clear()
        with self.assertRaisesRegex(w.Halt, "STOP_PENDING_RECONCILIATION"):
            self.worker.recover()
        self.assertEqual(len(self.backend.calls), 1)
        self.assertEqual(self.ollama.calls, [])
        # Even a stale caller overwriting the mutable JSON cannot recreate the consumed durable budget.
        self.journal.set(origin)
        self.backend.calls.clear()
        with self.assertRaisesRegex(w.Halt, "REJECTED_RETRY_BUDGET_USED"):
            self.worker.recover()
        self.assertEqual(len(self.backend.calls), 1)
        self.assertEqual(self.ollama.calls, [])

    def test_retry_budget_origin_and_state_are_one_atomic_transaction(self):
        origin = self.rejected_claim()
        self.journal.db.execute("CREATE TRIGGER fail_retry BEFORE UPDATE ON state WHEN NEW.value LIKE '%REJECTED_RETRY_INTENT%' BEGIN SELECT RAISE(ABORT,'SIMULATED_STATE_WRITE_FAILURE'); END")
        self.journal.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            self.worker.recover()
        self.assertEqual(self.journal.state(), origin)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM rejected_retries").fetchone()[0], 0)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM calls WHERE operation='REJECTED_RETRY'").fetchone()[0], 0)
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in self.backend.calls))

    def test_rejected_retry_hash_mismatch_keeps_original_evidence_and_consumes_the_only_attempt(self):
        origin = self.rejected_claim()
        self.ollama.vectors[0][0] = 0.5
        with self.assertRaisesRegex(w.Halt, "CLAIM_RESULT_HASH_CHANGED"):
            self.worker.recover()
        state = self.journal.state()
        self.assertEqual(state["resultHash"], origin["resultHash"])
        self.assertEqual(state["rejectionRecoveryOrigin"], origin)
        self.assertEqual(state["phase"], "REJECTED_RETRY_INTENT")
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in self.backend.calls))

    def test_rejected_retry_does_not_consume_budget_when_original_manifest_is_expired_or_revoked(self):
        origin = self.rejected_claim()
        request = self.backend.request
        def expired(method, path, body=None, key=None):
            response = request(method, path, body, key)
            if path.endswith("/manifest"):
                response["data"]["leaseExpiresAt"] = 1
            return response
        self.backend.request = expired
        with self.assertRaisesRegex(w.Halt, "LEASE_INVALID"):
            self.worker.recover()
        self.assertEqual(self.journal.state(), origin)
        self.assertEqual(self.ollama.calls, [])
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM calls WHERE operation='REJECTED_RETRY'").fetchone()[0], 0)

    def rollback_claim(self):
        task_no = "CTA-FDDE9396AE554345A116E4026D5590A4"
        original = w.ROLLBACK_REVIEW_TASKS[task_no]
        self.cfg.binding.update(deviceId=original["deviceId"], instanceNo=original["instanceNo"])
        self.backend.task = {**task(self.cfg), "taskNo": task_no}
        origin = self.interrupted_claim()
        origin["phase"] = "UNKNOWN_COMPLETE"
        self.journal.set(origin)
        key = "BGE-COMPLETE-" + w.sha256(task_no.encode())
        body = {"offlineOriginalAttempt": True}
        expected = {**original, "inputHash": origin["inputHash"], "resultHash": origin["resultHash"],
                    "originalRequestHash": w.sha256(w.json_bytes(body))}
        patcher = patch.dict(w.ROLLBACK_REVIEW_TASKS, {task_no: expected})
        patcher.start()
        self.addCleanup(patcher.stop)
        call = self.journal.intent("COMPLETE", key, task_no, body)
        self.journal.outcome(call, "UNKNOWN")
        self.review_response = {**identity(self.cfg), "taskNo": task_no, "inputHash": origin["inputHash"],
                                "resultHash": origin["resultHash"], "originalRequestHash": expected["originalRequestHash"],
                                "completeKeyHash": w.sha256(key.encode()), "nextKeyHash": w.sha256(origin["nextKey"].encode()),
                                "reviewId": 8421, "evidenceSha256": "d" * 64, "maxAttempts": 1, "rollbackConfirmed": True}
        receipt = self.backend.receipt
        self.backend.receipt = lambda: {**receipt(), "receiptNo": "CTR-" + task_no[-32:] if self.backend.found else None}
        request = self.backend.request
        def reviewed(method, path, body=None, key=None, original_next_key=None):
            if "/rollback-review?" in path:
                self.backend.calls.append((method, path, key))
                self.assertEqual(method, "GET")
                self.assertEqual(key, "BGE-COMPLETE-" + w.sha256(task_no.encode()))
                self.assertEqual(original_next_key, origin["nextKey"])
                self.assertEqual(self.journal.state(), origin)
                if self.review_response is None:
                    raise w.Rejected("BACKEND_REJECTED_409", 409, "TEST_CLOUD_ROLLBACK_REVIEW_REQUIRED")
                return ok(dict(self.review_response))
            if path.endswith("/manifest"):
                self.assertEqual(self.journal.state()["phase"], "ROLLBACK_RETRY_INTENT")
                self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM rollback_retries").fetchone()[0], 1)
            return request(method, path, body, key)
        self.backend.request = reviewed
        self.backend.calls.clear()
        return origin

    def test_audited_rollback_retry_preserves_unknown_and_spends_separate_budget_before_manifest(self):
        origin = self.rollback_claim()
        review = dict(self.review_response)
        self.assertTrue(self.worker.recover())
        calls = self.journal.db.execute("SELECT request_key,request_hash,outcome FROM calls WHERE operation='COMPLETE' ORDER BY id").fetchall()
        self.assertEqual([row[2] for row in calls], ["UNKNOWN", "CONFIRMED"])
        self.assertEqual(calls[0][0], calls[1][0])
        self.assertEqual(calls[0][1], review["originalRequestHash"])
        self.assertEqual(self.backend.complete_body["resultHash"], origin["resultHash"])
        self.assertFalse(any(path.endswith("/next-task") for _, path, _ in self.backend.calls))
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM rejected_retries").fetchone()[0], 0)
        saved, saved_hash, evidence, evidence_hash = self.journal.db.execute("SELECT original_state_json,original_state_hash,review_json,review_hash FROM rollback_retries").fetchone()
        self.assertEqual(json.loads(saved), origin)
        self.assertEqual(saved_hash, w.sha256(w.json_bytes(origin)))
        self.assertEqual(json.loads(evidence), review)
        self.assertEqual(evidence_hash, w.sha256(w.json_bytes(review)))
        self.assertEqual(self.journal.state()["phase"], "IDLE")

    def test_rollback_without_audit_or_with_tampered_review_cannot_spend_or_compute(self):
        origin = self.rollback_claim()
        review = dict(self.review_response)
        changes = [None, {"deploymentScope": "PRODUCTION"}, {"serverCanonical": False}, {"deviceId": 1206},
                   {"instanceNo": "other"}, {"taskNo": "CTA-" + "B" * 32}, {"inputHash": "a" * 64},
                   {"resultHash": "a" * 64}, {"originalRequestHash": "a" * 64}, {"completeKeyHash": "a" * 64},
                   {"nextKeyHash": "a" * 64}, {"rollbackConfirmed": False}, {"maxAttempts": 2},
                   {"maxAttempts": True}, {"reviewId": 0}, {"reviewId": True}, {"evidenceSha256": "invalid"}]
        for change in changes:
            with self.subTest(change=change):
                self.review_response = None if change is None else {**review, **change}
                self.backend.calls.clear()
                with self.assertRaises(w.Halt):
                    self.worker.recover()
                self.assertEqual(self.journal.state(), origin)
                self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM rollback_retries").fetchone()[0], 0)
                self.assertFalse(any(path.endswith(("/manifest", "/complete")) for _, path, _ in self.backend.calls))
                self.assertEqual(self.ollama.calls, [])

    def test_rollback_budget_and_origin_review_are_atomic_and_crash_can_only_read_receipt(self):
        origin = self.rollback_claim()
        self.journal.db.execute("CREATE TRIGGER fail_rollback BEFORE UPDATE ON state WHEN NEW.value LIKE '%ROLLBACK_RETRY_INTENT%' BEGIN SELECT RAISE(ABORT,'SIMULATED_STATE_WRITE_FAILURE'); END")
        self.journal.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            self.worker.recover()
        self.assertEqual(self.journal.state(), origin)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM rollback_retries").fetchone()[0], 0)
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM calls WHERE operation='ROLLBACK_RETRY'").fetchone()[0], 0)
        self.journal.db.execute("DROP TRIGGER fail_rollback")
        self.journal.db.commit()
        with patch.object(self.worker, "execute", side_effect=w.Halt("SIMULATED_CRASH")):
            with self.assertRaisesRegex(w.Halt, "SIMULATED_CRASH"):
                self.worker.recover()
        self.assertEqual(self.journal.state()["phase"], "ROLLBACK_RETRY_INTENT")
        self.journal.db.close()
        self.journal = w.Journal(self.path)
        self.worker.journal = self.backend.journal = self.journal
        self.backend.calls.clear()
        with self.assertRaisesRegex(w.Halt, "STOP_PENDING_RECONCILIATION"):
            self.worker.recover()
        self.assertEqual([method for method, _, _ in self.backend.calls], ["GET"])
        self.assertTrue(self.backend.calls[0][1].endswith("/receipt"))
        self.journal.set(origin)
        self.backend.calls.clear()
        with self.assertRaisesRegex(w.Halt, "ROLLBACK_RETRY_BUDGET_USED"):
            self.worker.recover()
        self.assertEqual(len(self.backend.calls), 1)
        self.assertEqual(self.ollama.calls, [])

    def test_rollback_second_unknown_never_gets_another_attempt_or_rejected_budget(self):
        origin = self.rollback_claim()
        self.backend.complete_error = w.Halt("HTTP_OUTCOME_UNKNOWN")
        with self.assertRaises(w.Halt):
            self.worker.recover()
        state = self.journal.state()
        self.assertTrue(state["rollbackRetry"])
        self.assertEqual(state["phase"], "UNKNOWN_COMPLETE")
        self.backend.calls.clear()
        self.ollama.calls.clear()
        with self.assertRaises(w.Halt):
            self.worker.recover()
        self.assertEqual(len(self.backend.calls), 1)
        self.assertTrue(self.backend.calls[0][1].endswith("/receipt"))
        self.assertEqual(self.ollama.calls, [])
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='COMPLETE' ORDER BY id").fetchall(), [("UNKNOWN",), ("UNKNOWN",)])
        with self.assertRaisesRegex(w.Halt, "REJECTED_RETRY_BUDGET_USED"):
            self.journal.require_rejected_retry(origin)

    def test_rollback_recomputed_hash_mismatch_spends_budget_preserves_origin_and_sends_nothing(self):
        origin = self.rollback_claim()
        self.ollama.vectors[0][0] = 0.5
        with self.assertRaisesRegex(w.Halt, "CLAIM_RESULT_HASH_CHANGED"):
            self.worker.recover()
        state = self.journal.state()
        self.assertEqual(state["phase"], "ROLLBACK_RETRY_INTENT")
        self.assertEqual(state["rollbackRecoveryOrigin"], origin)
        self.assertEqual(state["resultHash"], origin["resultHash"])
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in self.backend.calls))

    def test_rollback_scope_excludes_1152_and_nonexact_original_call_or_rejected_budget(self):
        self.assertEqual({entry["deviceId"] for entry in w.ROLLBACK_REVIEW_TASKS.values()}, {1204, 1206})
        origin = self.rollback_claim()
        for change in ("device", "call", "rejected"):
            with self.subTest(change=change):
                self.backend.calls.clear()
                if change == "device":
                    self.cfg.binding["deviceId"] = 1152
                    changed = {**origin, "binding": dict(self.cfg.binding)}
                    self.journal.set(changed)
                elif change == "call":
                    self.cfg.binding["deviceId"] = 1204
                    self.journal.set(origin)
                    self.journal.db.execute("UPDATE calls SET request_hash=? WHERE operation='COMPLETE'", ("a" * 64,))
                    self.journal.db.commit()
                else:
                    self.journal.db.execute("UPDATE calls SET request_hash=? WHERE operation='COMPLETE'", (self.review_response["originalRequestHash"],))
                    self.journal.db.execute("INSERT INTO rejected_retries VALUES(?,?,?)", (origin["taskNo"], "{}", "a" * 64))
                    self.journal.db.commit()
                with self.assertRaises(w.Halt):
                    self.worker.recover()
                self.assertEqual(len(self.backend.calls), 1)
                self.assertTrue(self.backend.calls[0][1].endswith("/receipt"))
                self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM rollback_retries").fetchone()[0], 0)
                self.assertEqual(self.ollama.calls, [])

    def test_real_vector_serialization_and_normal_confirmation(self):
        self.assertEqual(self.worker.run(once=True), 0)
        body = self.backend.complete_body
        raw = base64.b64decode(body["resultArtifactBase64"], validate=True)
        self.assertEqual(len(raw), 2 * 1024 * 4)
        self.assertEqual(struct.unpack_from("<f", raw, 0), (0.25,))
        self.assertEqual(body["resultHash"], w.sha256(raw))
        embed_body = next(body for _, path, body in self.ollama.calls if path == "/api/embed")
        self.assertEqual(embed_body, {"model": "bge-m3:567m", "input": self.backend.task["inputTexts"], "truncate": False})
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 1)
        saved = " ".join(row[0] for row in self.journal.db.execute("SELECT value FROM state"))
        saved += " ".join(str(row) for row in self.journal.db.execute("SELECT * FROM calls"))
        for secret in (self.backend.task["claimToken"], self.backend.task["proofNonce"], "UVEL TEST text", body["resultArtifactBase64"]):
            self.assertNotIn(secret, saved)

    def test_next_connect_failure_reuses_key_only_when_not_sent(self):
        self.backend.next_error = w.NotSent("CONNECT_NOT_SENT")
        self.assertEqual(self.worker.one(), "NOT_SENT")
        self.assertEqual(self.journal.state()["phase"], "NEXT_NOT_SENT")
        self.assertEqual(self.worker.one(), "CONFIRMED")
        keys = [key for _, path, key in self.backend.calls if path.endswith("/next-task")]
        self.assertEqual(len(keys), 2)
        self.assertEqual(keys[0], keys[1])

    def test_run_restarts_proven_unsent_next_before_any_heartbeat(self):
        self.backend.next_error = w.NotSent("CONNECT_NOT_SENT")
        self.assertEqual(self.worker.run(once=True), 2)
        original_key = self.journal.state()["nextKey"]
        before = len(self.backend.calls)
        self.assertEqual(self.worker.run(once=True), 0)
        resumed = self.backend.calls[before:]
        self.assertEqual(resumed[0], ("POST", w.PREFIX + "/next-task", original_key))
        self.assertTrue(any(path == w.PREFIX + "/heartbeat" for _, path, _ in resumed[1:]))

    def test_initial_run_claims_before_any_heartbeat(self):
        self.assertEqual(self.worker.run(once=True), 0)
        self.assertEqual(self.backend.calls[0][1], w.PREFIX + "/next-task")

    def test_sent_unknown_next_blocks_restart_before_network(self):
        self.backend.next_error = w.Halt("HTTP_OUTCOME_UNKNOWN")
        with self.assertRaises(w.Halt):
            self.worker.one()
        self.assertEqual(self.journal.state()["phase"], "UNKNOWN_NEXT")
        before = len(self.backend.calls)
        with self.assertRaises(w.Halt):
            self.worker.run(once=True)
        self.assertEqual(len(self.backend.calls), before)

    def test_unknown_complete_positive_receipt_confirms_without_retry(self):
        self.backend.complete_error = w.Halt("HTTP_OUTCOME_UNKNOWN")
        self.assertEqual(self.worker.one(), "CONFIRMED")
        self.assertEqual(sum(path.endswith("/complete") for _, path, _ in self.backend.calls), 1)

    def test_unknown_complete_negative_receipt_stops_and_restart_reads_only(self):
        self.backend.complete_error, self.backend.found = w.Halt("HTTP_OUTCOME_UNKNOWN"), False
        with self.assertRaises(w.Halt):
            self.worker.one()
        self.assertEqual(self.journal.state()["phase"], "UNKNOWN_COMPLETE")
        before = len(self.backend.calls)
        with self.assertRaises(w.Halt):
            self.worker.run(once=True)
        self.assertEqual([method for method, _, _ in self.backend.calls[before:]], ["GET"])
        self.assertEqual(sum(path.endswith("/complete") for _, path, _ in self.backend.calls), 1)

    def test_crash_after_complete_intent_can_only_reconcile(self):
        self.backend.complete_error = KeyboardInterrupt()
        with self.assertRaises(KeyboardInterrupt):
            self.worker.one()
        self.assertEqual(self.journal.state()["phase"], "COMPLETE_INTENT")
        self.journal.db.close()
        self.journal = w.Journal(self.path)
        self.backend.journal = self.journal
        self.worker.journal = self.journal
        before = len(self.backend.calls)
        self.assertEqual(self.worker.run(once=True), 0)
        self.assertEqual([method for method, _, _ in self.backend.calls[before:]], ["GET"])

    def test_receipt_wrong_binding_or_result_cannot_confirm(self):
        for changed in ({"deviceId": 93}, {"instanceNo": "other"}, {"resultHash": "f" * 64}, {"modelDigest": "sha256:" + "f" * 64}):
            with self.subTest(changed=changed):
                state = {"phase": "UNKNOWN_COMPLETE", "binding": self.cfg.binding, "taskNo": self.backend.task["taskNo"],
                         "inputHash": self.backend.task["inputHash"], "resultHash": "e" * 64}
                self.journal.set(state)
                receipt = {**self.backend.receipt(), **changed}
                with patch.object(self.backend, "receipt", return_value=receipt), self.assertRaises(w.Halt):
                    self.worker.recover()

    def test_complete_connect_failure_stops_without_economic_retry(self):
        self.backend.complete_error = w.NotSent("CONNECT_NOT_SENT")
        with self.assertRaises(w.Halt):
            self.worker.one()
        self.assertEqual(self.journal.state()["phase"], "COMPLETE_NOT_SENT")
        before = len(self.backend.calls)
        with self.assertRaises(w.Halt):
            self.worker.run(once=True)
        self.assertEqual(len(self.backend.calls), before)

    def test_model_digest_mismatch_prevents_inference_and_complete(self):
        self.ollama.tag_digest = "2" * 64
        with self.assertRaises(w.Halt):
            self.worker.one()
        self.assertEqual([p for _, p, _ in self.ollama.calls], ["/api/tags"])
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in self.backend.calls))

    def test_model_digest_changed_during_inference_cannot_complete(self):
        original = self.ollama.request
        def changed(method, path, body=None, key=None):
            value = original(method, path, body, key)
            if path == "/api/embed":
                self.ollama.tag_digest = "2" * 64
            return value
        self.ollama.request = changed
        with self.assertRaises(w.Halt):
            self.worker.one()
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in self.backend.calls))

    def test_idle_is_a_manifest_read_not_fake_task_and_heartbeats_are_separate(self):
        self.backend.idle = True
        self.assertEqual(self.worker.run(once=True), 2)
        self.assertEqual(self.journal.state()["phase"], "IDLE")
        self.assertEqual([path for _, path, _ in self.ollama.calls], ["/api/tags"])
        self.assertTrue(any(path == w.PREFIX + "/heartbeat" for _, path, _ in self.backend.calls))
        self.assertIsNone(self.backend.complete_body)

    def test_completed_next_key_only_reads_existing_receipt(self):
        self.backend.task["status"] = "COMPLETED"
        original = self.backend.receipt
        self.backend.receipt = lambda: {**original(), "resultHash": "e" * 64}
        self.assertEqual(self.worker.run(once=True), 2)
        self.assertEqual([path for _, path, _ in self.backend.calls],
                         [w.PREFIX + "/next-task", w.PREFIX + "/tasks/" + self.backend.task["taskNo"] + "/receipt"])
        self.assertEqual(self.ollama.calls, [])
        self.assertIsNone(self.backend.complete_body)

    def test_input_or_scope_tampering_cannot_compute(self):
        for key, value in (("ownerId", 99), ("instanceNo", "other"), ("serverCanonical", False),
                           ("deploymentScope", "PRODUCTION"), ("inputHash", "d" * 64), ("outputDimensions", 384)):
            with self.subTest(key=key):
                self.backend.task = {**task(self.cfg), key: value}
                self.journal.set({"phase": "IDLE"})
                with self.assertRaises(w.Halt):
                    self.worker.one()
                self.assertEqual(self.ollama.calls, [])

    def standby(self):
        self.journal.set({"phase": "IDLE", "binding": self.cfg.binding})
        return w.Worker(self.cfg, self.backend, self.ollama, self.journal, threading.Event())

    def test_heartbeat_only_confirmed_idle_has_no_next_or_complete_and_stops_cleanly(self):
        worker = self.standby()
        request = self.backend.request
        def stop_after_heartbeat(method, path, body=None, key=None):
            result = request(method, path, body, key)
            worker.stop.set()
            return result
        self.backend.request = stop_after_heartbeat
        self.assertEqual(worker.run(heartbeat_only=True), 0)
        self.assertEqual([path for _, path, _ in self.backend.calls], [w.PREFIX + "/heartbeat"])
        self.assertFalse(worker.heartbeat_thread.is_alive())
        self.assertEqual(self.journal.state(), {"phase": "IDLE", "binding": self.cfg.binding})

    def test_heartbeat_only_refuses_unconfirmed_binding_and_every_pending_phase_before_network(self):
        for state in ({"phase": "IDLE"}, {"phase": "IDLE", "binding": {**self.cfg.binding, "deviceId": 93}},
                      *({"phase": phase, "binding": self.cfg.binding} for phase in
                        ("UNKNOWN_NEXT", "NEXT_INTENT", "NEXT_NOT_SENT", "CLAIMED", "COMPLETE_INTENT", "UNKNOWN_COMPLETE", "COMPLETE_NOT_SENT", "ROLLBACK_RETRY_INTENT"))):
            with self.subTest(state=state):
                self.journal.set(state)
                worker = w.Worker(self.cfg, self.backend, self.ollama, self.journal, threading.Event())
                with self.assertRaisesRegex(w.Halt, "HEARTBEAT_ONLY_CONFIRMED_IDLE_REQUIRED"):
                    worker.run(heartbeat_only=True)
                self.assertEqual(self.backend.calls, [])
                self.assertEqual(self.ollama.calls, [])
                self.assertEqual(self.journal.state(), state)

    def test_heartbeat_only_model_mismatch_stops_before_backend_heartbeat(self):
        worker = self.standby()
        self.ollama.tag_digest = "2" * 64
        with self.assertRaisesRegex(w.Halt, "MODEL_DIGEST_CHANGED"):
            worker.run(heartbeat_only=True)
        self.assertEqual(self.backend.calls, [])
        self.assertIsNone(worker.heartbeat_thread)

    def test_heartbeat_only_permission_revocation_stops_background_thread(self):
        worker = self.standby()
        request = self.backend.request
        def revoked_after_first(method, path, body=None, key=None):
            if self.backend.calls:
                self.backend.calls.append((method, path, key))
                raise w.Rejected("BACKEND_REJECTED_401")
            return request(method, path, body, key)
        self.backend.request = revoked_after_first
        with patch.object(worker.heartbeat_stop, "wait", return_value=False):
            with self.assertRaisesRegex(w.Rejected, "BACKEND_REJECTED_401"):
                worker.run(heartbeat_only=True)
        self.assertEqual([path for _, path, _ in self.backend.calls], [w.PREFIX + "/heartbeat"] * 2)
        self.assertFalse(worker.heartbeat_thread.is_alive())
        self.assertEqual(self.journal.state()["phase"], "IDLE")

    def test_heartbeat_only_already_stopped_performs_no_network(self):
        worker = self.standby()
        worker.stop.set()
        self.assertEqual(worker.run(heartbeat_only=True), 0)
        self.assertEqual(self.backend.calls, [])
        self.assertEqual(self.ollama.calls, [])

    def test_heartbeat_only_backend_active_task_blocks_standby_but_not_normal_task(self):
        request = self.backend.request
        def active_task(method, path, body=None, key=None):
            result = request(method, path, body, key)
            if path == w.PREFIX + "/heartbeat":
                result["data"]["activeTaskNo"] = self.backend.task["taskNo"]
            return result
        self.backend.request = active_task
        worker = self.standby()
        with self.assertRaisesRegex(w.Halt, "HEARTBEAT_ONLY_ACTIVE_TASK_BLOCKED"):
            worker.run(heartbeat_only=True)
        self.assertEqual([path for _, path, _ in self.backend.calls], [w.PREFIX + "/heartbeat"])
        self.assertEqual(self.journal.state(), {"phase": "IDLE", "binding": self.cfg.binding})
        self.backend.calls.clear()
        self.assertEqual(self.worker.run(once=True), 0)


class FleetTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.env = {"UVEL_CLOUD_WORKER_ENABLED": "true", "UVEL_CLOUD_WORKER_SCOPE": "TEST",
                    "UVEL_CLOUD_FLEET_ENABLED": "true", "UVEL_CLOUD_MODEL": "bge-m3:567m",
                    "UVEL_CLOUD_MODEL_DIGEST": "sha256:" + "1" * 64}
        self.backend = unittest.mock.Mock(token="protected-test-placeholder")
        self.fleet = w.Fleet(self.env, self.backend, Ollama(config()), Path(self.tmp.name) / "instances",
                             threading.Event(), self.open_journal)
        self.fleet.directory.mkdir(mode=0o700)

    def tearDown(self):
        for entry in self.fleet.snapshot():
            entry.worker.journal.db.close()
            os.close(entry.file_lock)
        self.tmp.cleanup()

    def open_journal(self, path):
        return w.Journal(path / "journal.sqlite3"), os.open(path / "worker.lock", os.O_CREAT | os.O_RDWR, 0o600)

    def row(self, device=92):
        cfg = config()
        cfg.binding = {**cfg.binding, "deviceId": device, "executorId": "fleet-" + str(device),
                       "instanceNo": "NEX-ORD-" + str(device)}
        return identity(cfg)

    def page(self, rows, more=False, cursor=None):
        return ok({"executionKind": w.KIND, "specVersion": w.SPEC, "deploymentScope": "TEST", "serverCanonical": True,
                   "instances": rows, "hasMore": more, "nextAfterDeviceId": cursor if cursor is not None else rows[-1]["deviceId"] if rows else 0})

    def discover(self, rows):
        self.backend.request.return_value = self.page(rows)
        self.fleet.refresh()
        return self.fleet.snapshot()

    def wire(self, entry):
        worker = entry.worker
        backend = Backend(worker.config, worker.journal, Path(worker.journal.db.execute("PRAGMA database_list").fetchone()[2]))
        worker.backend = backend
        return backend

    def test_parallelism_is_small_bounded_and_single_worker_config_is_unchanged(self):
        self.assertEqual(self.fleet.config.parallelism, 2)
        for value in ("0", "9", "-1", "2.0", "", "unlimited"):
            with self.subTest(value=value), self.assertRaisesRegex(w.Halt, "FLEET_PARALLELISM_INVALID"):
                w.Config({**self.env, "UVEL_CLOUD_FLEET_PARALLELISM": value}, fleet=True)
        self.assertFalse(hasattr(config(), "parallelism"))

    def test_model_endpoint_pool_requires_distinct_loopback_runners_at_parallel_capacity(self):
        urls = "http://127.0.0.1:11436,http://127.0.0.1:11437"
        cfg = w.Config({**self.env, "UVEL_CLOUD_FLEET_OLLAMA_URLS": urls}, fleet=True)
        self.assertEqual(cfg.ollama_endpoints, [("127.0.0.1", 11436), ("127.0.0.1", 11437)])
        fleet = w.Fleet({**self.env, "UVEL_CLOUD_FLEET_OLLAMA_URLS": urls}, self.backend, self.fleet.ollama,
                        self.fleet.directory, threading.Event(), self.open_journal)
        self.assertIsInstance(fleet.ollama, w.OllamaPool)
        self.assertEqual([client.address for client in fleet.ollama.clients], cfg.ollama_endpoints)
        for invalid in ("", "http://127.0.0.1:11436", "http://127.0.0.1:11436,http://127.0.0.1:11436/",
                        "http://[::1]:11436,http://[0:0:0:0:0:0:0:1]:11436",
                        "http://127.0.0.1:11436,http://example.com:11437"):
            with self.subTest(urls=invalid), self.assertRaises(w.Halt):
                w.Config({**self.env, "UVEL_CLOUD_FLEET_OLLAMA_URLS": invalid}, fleet=True)
        self.assertEqual(self.fleet.config.ollama_endpoints, [])
        self.assertIsInstance(self.fleet.ollama, Ollama)  # An unconfigured fleet retains its original client.
        single = w.Http(("127.0.0.1", 11435))
        with self.assertRaisesRegex(w.Halt, "FLEET_MODEL_ENDPOINTS_REQUIRED"):
            w.Fleet(self.env, self.backend, single, self.fleet.directory)
        serial = w.Fleet({**self.env, "UVEL_CLOUD_FLEET_PARALLELISM": "1"}, self.backend, single, self.fleet.directory)
        self.assertIs(serial.ollama, single)

    def test_scheduler_overlaps_two_inferences_bounds_capacity_and_advances_past_slow_instance(self):
        entries = self.discover([self.row(), self.row(93), self.row(94)])
        backends = [self.wire(entry) for entry in entries]
        for index, backend in enumerate(backends):
            backend.task["inputTexts"][0] = str(index)
            backend.task["inputHash"] = w.sha256(w.json_bytes(backend.task["inputTexts"]))
        barrier, release, third = threading.Barrier(2), threading.Event(), threading.Event()
        release_discovery, discovery_started = threading.Event(), threading.Event()
        active, maximum = 0, 0
        guard = threading.Lock()
        infer = self.fleet.ollama.request
        def model(method, path, body=None, key=None):
            nonlocal active, maximum
            if path != "/api/embed":
                return infer(method, path, body, key)
            index = int(body["input"][0])
            with guard:
                active += 1
                maximum = max(maximum, active)
            try:
                if index < 2:
                    barrier.wait(4)  # A serial implementation cannot pass this barrier.
                if index == 0:
                    self.assertTrue(release.wait(6))
                if index == 2:
                    third.set()
                return infer(method, path, body, key)
            finally:
                with guard:
                    active -= 1
        def slow_discovery():
            discovery_started.set()
            release_discovery.wait(6)
        self.fleet.ollama.request = model
        self.fleet.restore, self.fleet.refresh = lambda: None, slow_discovery
        result = []
        thread = threading.Thread(target=lambda: result.append(self.fleet.run()))
        thread.start()
        try:
            self.assertTrue(discovery_started.wait(2))
            self.assertTrue(third.wait(4))  # Instance 3 progresses while instance 1 and discovery remain blocked.
            self.assertEqual(maximum, 2)
            self.assertFalse(release.is_set())
            self.assertEqual(sum(path.endswith("/complete") for _, path, _ in backends[1].calls), 1)
            self.fleet.stop.set()
            self.assertTrue(thread.is_alive())
            # Shutdown retains the live journal and process file lock while inference is in flight.
            self.assertEqual(entries[0].worker.journal.state()["phase"], "CLAIMED")
            os.fstat(entries[0].file_lock)
        finally:
            self.fleet.stop.set()
            release.set()
            release_discovery.set()
            thread.join(5)
        self.assertFalse(thread.is_alive())
        self.assertEqual(result, [0])
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in backends[0].calls))
        with self.assertRaises(sqlite3.ProgrammingError):
            entries[0].worker.journal.state()
        with self.assertRaises(OSError):
            os.fstat(entries[0].file_lock)
        self.fleet.entries.clear()  # run() owns and has closed these resources.

    def test_same_instance_repeated_tick_never_runs_a_second_workflow(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        entered, release = threading.Event(), threading.Event()
        infer = self.fleet.ollama.request
        def model(method, path, body=None, key=None):
            if path == "/api/embed":
                entered.set()
                self.assertTrue(release.wait(3))
            return infer(method, path, body, key)
        self.fleet.ollama.request = model
        thread = threading.Thread(target=self.fleet.tick, args=(entry,))
        thread.start()
        try:
            self.assertTrue(entered.wait(2))
            for _ in range(3):
                self.fleet.tick(entry)
            self.assertEqual(sum(path.endswith("/next-task") for _, path, _ in backend.calls), 1)
            self.assertTrue(entry.running)
        finally:
            release.set()
            thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertEqual(sum(path.endswith("/complete") for _, path, _ in backend.calls), 1)
        self.assertEqual(entry.worker.journal.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 1)

    def test_unexpected_workflow_future_error_is_quarantined_without_stopping_other_instances(self):
        first, second = self.discover([self.row(), self.row(93)])
        first_backend, second_backend = self.wire(first), self.wire(second)
        quarantined = threading.Event()
        tick, stop = self.fleet.tick, first.worker.stop.set
        def fail_one(entry):
            if entry is first:
                with entry.gate:
                    entry.running = True
                raise RuntimeError("offline injected workflow failure")
            return tick(entry)
        def mark_quarantine():
            stop()
            quarantined.set()
        infer = self.fleet.ollama.request
        def model(method, path, body=None, key=None):
            if path == "/api/embed":
                self.assertTrue(quarantined.wait(3))
            return infer(method, path, body, key)
        request = second_backend.request
        def stop_after_receipt(method, path, body=None, key=None):
            result = request(method, path, body, key)
            if method == "GET" and path.endswith("/receipt"):
                self.fleet.stop.set()
            return result
        first.worker.stop.set = mark_quarantine
        second_backend.request = stop_after_receipt
        self.fleet.tick, self.fleet.ollama.request = fail_one, model
        self.fleet.restore, self.fleet.refresh = lambda: None, lambda: None
        self.assertEqual(self.fleet.run(), 0)
        self.assertTrue(first.blocked)
        self.assertFalse(first.running)
        self.assertEqual(first_backend.calls, [])
        self.assertEqual(sum(path.endswith("/complete") for _, path, _ in second_backend.calls), 1)
        self.fleet.entries.clear()

    def test_cancelled_inference_does_not_cancel_another_instances_workflow(self):
        first, second = self.discover([self.row(), self.row(93)])
        first_backend, second_backend = self.wire(first), self.wire(second)
        barrier = threading.Barrier(2)
        infer = self.fleet.ollama.request
        def model(method, path, body=None, key=None):
            if path == "/api/embed":
                barrier.wait(3)
            return infer(method, path, body, key)
        self.fleet.ollama.request = model
        request = first_backend.request
        # The first instance's post-inference heartbeat revokes only its own generation.
        def revoke_at_gate(method, path, body=None, key=None):
            result = request(method, path, body, key)
            if path.endswith("/heartbeat"):
                with first.gate:
                    first.generation += 1
                    first.worker.stop.set()
            return result
        first_backend.request = revoke_at_gate
        with w.ThreadPoolExecutor(max_workers=2) as pool:
            futures = [pool.submit(self.fleet.tick, entry) for entry in (first, second)]
            for future in futures:
                future.result(4)
        self.assertEqual(first.worker.journal.state()["phase"], "CLAIMED")
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in first_backend.calls))
        self.assertEqual(second.worker.journal.state()["phase"], "IDLE")
        self.assertEqual(sum(path.endswith("/complete") for _, path, _ in second_backend.calls), 1)

    def test_inflight_idle_health_blocks_duplicate_tick_but_not_revocation_or_other_instance(self):
        first, second = self.discover([self.row(), self.row(93)])
        first_backend, second_backend = self.wire(first), self.wire(second)
        first.worker.journal.set({"phase": "IDLE", "binding": first.worker.config.binding})
        entered, release = threading.Event(), threading.Event()
        request = first_backend.request
        def slow_health(method, path, body=None, key=None):
            result = request(method, path, body, key)
            if path.endswith("/heartbeat"):
                entered.set()
                self.assertTrue(release.wait(4))
            return result
        first_backend.request = slow_health
        thread = threading.Thread(target=self.fleet.health, args=(first,))
        thread.start()
        try:
            self.assertTrue(entered.wait(2))
            self.fleet.tick(first)
            self.fleet.health(first)
            self.assertEqual(len(first_backend.calls), 1)
            self.backend.request.return_value = self.page([self.row(93)])
            self.fleet.refresh()  # Would deadlock here if health held entry.gate during HTTP.
            self.assertFalse(first.present)
            self.assertTrue(first.worker.stop.is_set())
            self.fleet.tick(second)
            self.assertEqual(second.worker.journal.state()["phase"], "IDLE")
            self.assertEqual(sum(path.endswith("/complete") for _, path, _ in second_backend.calls), 1)
        finally:
            release.set()
            thread.join(3)
        self.assertFalse(thread.is_alive())
        self.assertFalse(first.present)
        self.assertTrue(first.worker.stop.is_set())  # An older successful health response cannot undo revocation.
        self.assertFalse(any(path.endswith("/next-task") for _, path, _ in first_backend.calls))

    def test_discovery_paginates_and_scopes_independent_journals_without_initial_heartbeat(self):
        self.backend.request.side_effect = [self.page([self.row()], True), self.page([self.row(93)])]
        self.fleet.refresh()
        entries = self.fleet.snapshot()
        self.assertEqual(len(entries), 2)
        self.assertEqual(self.backend.request.call_args_list[1].args[1], w.PREFIX + "/instances?afterDeviceId=92&limit=100")
        self.assertEqual([entry.worker.backend.executor for entry in entries], ["fleet-92", "fleet-93"])
        self.assertNotEqual(entries[0].worker.journal.db, entries[1].worker.journal.db)
        self.fleet.health()
        self.assertEqual(self.fleet.ollama.calls, [])

    def test_scan_cursor_can_skip_filtered_rows_and_empty_pages_but_cannot_move_backwards(self):
        self.backend.request.side_effect = [self.page([self.row()], True, 95), self.page([], True, 97), self.page([self.row(98)])]
        self.fleet.refresh()
        self.assertEqual([entry.worker.config.binding["deviceId"] for entry in self.fleet.snapshot()], [92, 98])
        self.assertEqual(self.backend.request.call_args_list[2].args[1], w.PREFIX + "/instances?afterDeviceId=97&limit=100")
        self.backend.request.side_effect = [self.page([self.row()], True), self.page([], True, 91)]
        with self.assertRaisesRegex(w.Halt, "FLEET_DISCOVERY_CURSOR_INVALID"):
            self.fleet.refresh()

    def test_unknown_instance_stops_heartbeat_and_does_not_block_other_real_computation(self):
        first, second = self.discover([self.row(), self.row(93)])
        first_backend, second_backend = self.wire(first), self.wire(second)
        first_backend.next_error = w.Halt("HTTP_OUTCOME_UNKNOWN")
        self.fleet.tick(first)
        self.assertTrue(first.blocked)
        self.assertEqual(first.worker.journal.state()["phase"], "UNKNOWN_NEXT")
        self.fleet.tick(second)
        self.assertEqual(second.worker.journal.state()["phase"], "IDLE")
        self.assertEqual(len(second_backend.complete_body["resultArtifactBase64"]), 10924)
        first_calls = list(first_backend.calls)
        self.fleet.health()
        self.fleet.tick(first)
        self.assertEqual(first_backend.calls, first_calls)
        self.assertTrue(any(path.endswith("/heartbeat") for _, path, _ in second_backend.calls))

    def test_proven_unsent_next_retries_original_key_without_health_before_confirmation(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        backend.next_error, backend.idle = w.NotSent("CONNECT_NOT_SENT"), True
        self.fleet.tick(entry)
        self.assertFalse(entry.blocked)
        self.assertEqual(entry.worker.journal.state()["phase"], "NEXT_NOT_SENT")
        self.fleet.health()
        self.assertEqual(len(backend.calls), 1)
        entry.due = 0
        self.fleet.tick(entry)
        self.assertEqual(backend.calls[0][2], backend.calls[1][2])

    def test_revocation_interrupts_wait_and_removed_instance_receipt_is_read_only(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        pending = {"phase": "UNKNOWN_COMPLETE", "binding": entry.worker.config.binding,
                   "taskNo": backend.task["taskNo"], "inputHash": backend.task["inputHash"], "resultHash": "c" * 64}
        entry.worker.journal.set(pending)
        self.backend.request.return_value = self.page([])
        self.fleet.refresh()
        self.assertFalse(entry.present)
        self.assertTrue(entry.worker.stop.is_set())
        self.fleet.tick(entry)
        self.assertEqual(entry.worker.journal.state()["phase"], "IDLE")
        self.assertEqual([method for method, _, _ in backend.calls], ["GET"])
        self.fleet.health()
        self.assertEqual(len(backend.calls), 1)

    def test_lost_complete_negative_receipt_remains_isolated_and_never_resubmits(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        backend.complete_error, backend.found = w.Halt("HTTP_OUTCOME_UNKNOWN"), False
        self.fleet.tick(entry)
        self.assertEqual(entry.worker.journal.state()["phase"], "UNKNOWN_COMPLETE")
        entry.due = 0
        self.fleet.tick(entry)
        self.fleet.health()
        self.assertEqual(sum(path.endswith("/complete") for _, path, _ in backend.calls), 1)
        self.assertTrue(entry.blocked)

    def test_discovery_excludes_legacy_and_rejects_duplicate_wrong_scope_and_cursor(self):
        self.assertEqual(self.discover([self.row(1152), self.row(1202)]), [])
        for result in (self.page([self.row(), self.row()]),
                       self.page([self.row()], True, 0),
                       ok({**self.page([self.row()])["data"], "deploymentScope": "PRODUCTION"})):
            self.backend.request.return_value = result
            with self.assertRaises(w.Halt):
                self.fleet.refresh()
        self.assertEqual(self.fleet.snapshot(), [])

    def test_restart_restores_unknown_complete_even_after_discovery_revocation(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        entry.worker.journal.set({"phase": "UNKNOWN_COMPLETE", "binding": entry.worker.config.binding,
                                  "taskNo": backend.task["taskNo"], "inputHash": backend.task["inputHash"], "resultHash": "d" * 64})
        entry.worker.journal.db.close()
        os.close(entry.file_lock)
        self.fleet.entries.clear()
        self.fleet.restore()
        restored, = self.fleet.snapshot()
        self.assertTrue(restored.blocked)
        self.assertFalse(restored.present)
        backend = self.wire(restored)
        self.fleet.tick(restored)
        self.assertEqual(restored.worker.journal.state()["phase"], "IDLE")
        self.assertEqual([method for method, _, _ in backend.calls], ["GET"])

    def test_identity_change_preserves_original_journal(self):
        entry, = self.discover([self.row()])
        changed = self.row()
        changed["ownerId"] += 1
        self.backend.request.return_value = self.page([changed])
        with self.assertRaisesRegex(w.Halt, "FLEET_BINDING_CHANGED"):
            self.fleet.refresh()
        self.assertEqual(entry.worker.config.binding["ownerId"], 91)

    def test_background_health_during_original_completion_wait_and_sigterm_prevents_complete(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        backend.task["completableAt"] = int(time.time() * 1000) + 100000
        inferred, heartbeat = threading.Event(), threading.Event()
        infer = self.fleet.ollama.request
        def model(method, path, body=None, key=None):
            result = infer(method, path, body, key)
            if path == "/api/embed":
                inferred.set()
            return result
        self.fleet.ollama.request = model
        request = backend.request
        def backend_request(method, path, body=None, key=None):
            result = request(method, path, body, key)
            if path.endswith("/heartbeat"):
                heartbeat.set()
                self.fleet.stop.set()
            return result
        backend.request = backend_request
        self.fleet.restore, self.fleet.refresh = lambda: None, lambda: None
        # Keep health within the scheduler-owned pool so shutdown also waits for this in-flight request.
        health = self.fleet.health
        def health_after_inference(item=None):
            inferred.wait(3)
            return health(item)
        self.fleet.health = health_after_inference
        result = []
        thread = threading.Thread(target=lambda: result.append(self.fleet.run()))
        thread.start()
        try:
            self.assertTrue(inferred.wait(3))
            self.assertTrue(heartbeat.wait(3))
        finally:
            self.fleet.stop.set()
            entry.worker.stop.set()
            thread.join(4)
        self.assertFalse(thread.is_alive())
        self.assertEqual(result, [0])
        self.assertTrue(heartbeat.is_set())
        self.assertTrue(entry.worker.stop.is_set())
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in backend.calls))
        # run() owns and closes these handles.
        self.fleet.entries.clear()

    def test_global_stop_inside_synchronous_heartbeat_prevents_new_complete_without_propagation(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        request = backend.request
        def stop_at_gate(method, path, body=None, key=None):
            result = request(method, path, body, key)
            if path.endswith("/heartbeat"):
                self.fleet.stop.set()
            return result
        backend.request = stop_at_gate
        self.fleet.tick(entry)
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in backend.calls))
        self.assertEqual(entry.worker.journal.state()["phase"], "CLAIMED")

    def test_idle_server_task_and_wrong_active_task_quarantine_without_next_or_complete(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        entry.worker.journal.set({"phase": "IDLE", "binding": entry.worker.config.binding})
        request = backend.request
        def wrong_task(method, path, body=None, key=None):
            result = request(method, path, body, key)
            if path.endswith("/heartbeat"):
                result["data"]["activeTaskNo"] = "CTA-" + "F" * 32
            return result
        backend.request = wrong_task
        self.fleet.health()
        self.assertTrue(entry.blocked)
        self.fleet.tick(entry)
        self.assertFalse(any(path.endswith(("/next-task", "/complete")) for _, path, _ in backend.calls))
        entry.blocked, entry.running = False, True
        entry.worker.stop.clear()
        entry.worker.journal.set({"phase": "CLAIMED", "binding": entry.worker.config.binding, "taskNo": backend.task["taskNo"]})
        self.fleet.health()
        self.assertTrue(entry.blocked)
        self.assertTrue(entry.worker.stop.is_set())

    def test_idle_preflight_rejects_server_active_task_before_new_next_without_background_health(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        entry.worker.journal.set({"phase": "IDLE", "binding": entry.worker.config.binding})
        request = backend.request
        def unexpected_task(method, path, body=None, key=None):
            result = request(method, path, body, key)
            result["data"]["activeTaskNo"] = backend.task["taskNo"]
            return result
        backend.request = unexpected_task
        self.fleet.tick(entry)
        self.assertTrue(entry.blocked)
        self.assertEqual([path for _, path, _ in backend.calls], [w.PREFIX + "/heartbeat"])

    def test_late_active_heartbeat_after_positive_confirmation_does_not_quarantine_instance(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        state = {"phase": "CLAIMED", "binding": entry.worker.config.binding, "taskNo": backend.task["taskNo"],
                 "inputHash": backend.task["inputHash"], "resultHash": "e" * 64}
        entry.worker.journal.set(state)
        entry.running = True
        request = backend.request
        def completed_while_heartbeat_in_flight(method, path, body=None, key=None):
            result = request(method, path, body, key)
            if path.endswith("/heartbeat"):
                entry.worker.journal.set({**state, "phase": "COMPLETE_INTENT"})
                entry.worker.journal.confirm(state, {"receiptNo": "CTR-" + "A" * 32})
                result["data"]["activeTaskNo"] = None
            return result
        backend.request = completed_while_heartbeat_in_flight
        self.fleet.health()
        self.assertFalse(entry.blocked)
        self.assertFalse(entry.worker.stop.is_set())
        self.assertEqual(entry.worker.journal.state()["phase"], "IDLE")

    def test_confirmed_idle_recovers_after_temporary_pause_but_unknown_does_not(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        entry.worker.journal.set({"phase": "IDLE", "binding": entry.worker.config.binding})
        request = backend.request
        backend.request = unittest.mock.Mock(side_effect=w.Rejected("BACKEND_REJECTED_403"))
        self.fleet.health()
        self.assertTrue(entry.blocked)
        backend.request, backend.idle = request, True
        self.fleet.refresh()
        self.assertTrue(entry.blocked)  # Discovery itself never waits for instance health HTTP.
        self.fleet.health()
        self.assertFalse(entry.blocked)
        self.assertFalse(entry.worker.stop.is_set())
        self.fleet.tick(entry)
        self.assertTrue(any(path.endswith("/next-task") for _, path, _ in backend.calls))
        entry.blocked = True
        entry.worker.stop.set()
        entry.worker.journal.set({"phase": "UNKNOWN_NEXT", "binding": entry.worker.config.binding, "nextKey": "BGE-NEXT-" + "a" * 32})
        calls = list(backend.calls)
        self.fleet.refresh()
        self.assertTrue(entry.blocked)
        self.assertTrue(entry.worker.stop.is_set())
        self.assertEqual(backend.calls, calls)

    def test_fleet_restored_claimed_quarantine_lifts_only_after_original_manifest_authorization(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        state = {"phase": "NEXT_INTENT", "nextKey": "BGE-NEXT-" + "d" * 32, "binding": entry.worker.config.binding}
        data = entry.worker.mutate("NEXT", "/next-task", {}, state["nextKey"], state)
        state.update(phase="CLAIMED", taskNo=data["taskNo"], inputHash=data["inputHash"],
                     resultHash=w.sha256(w.artifact(self.fleet.ollama.vectors, len(data["inputTexts"]))))
        entry.worker.journal.set(state)
        entry.blocked = True
        entry.worker.stop.set()
        backend.calls.clear()
        backend.found = False
        request = backend.request
        def observe_authorization(method, path, body=None, key=None):
            if path.endswith("/manifest"):
                self.assertTrue(entry.blocked)
                self.assertTrue(entry.worker.stop.is_set())
            if method == "POST":
                self.assertFalse(entry.blocked)
                self.assertFalse(entry.worker.stop.is_set())
            return request(method, path, body, key)
        backend.request = observe_authorization
        self.fleet.tick(entry)
        self.assertFalse(entry.blocked)
        self.assertEqual(entry.worker.journal.state()["phase"], "IDLE")
        self.assertEqual(sum(path.endswith("/complete") for _, path, _ in backend.calls), 1)
        self.assertFalse(any(path.endswith("/next-task") for _, path, _ in backend.calls))

    def test_claim_resume_cannot_clear_new_health_quarantine_while_manifest_is_in_flight(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        state = {"phase": "NEXT_INTENT", "nextKey": "BGE-NEXT-" + "d" * 32, "binding": entry.worker.config.binding}
        data = entry.worker.mutate("NEXT", "/next-task", {}, state["nextKey"], state)
        state.update(phase="CLAIMED", taskNo=data["taskNo"], inputHash=data["inputHash"])
        entry.worker.journal.set(state)
        backend.found = False
        backend.calls.clear()
        request = backend.request
        def raced(method, path, body=None, key=None):
            result = request(method, path, body, key)
            if path.endswith("/manifest"):
                self.fleet.health()
            if path.endswith("/heartbeat"):
                result["data"]["activeTaskNo"] = "CTA-" + "F" * 32
            return result
        backend.request = raced
        self.fleet.tick(entry)
        self.assertTrue(entry.blocked)
        self.assertTrue(entry.worker.stop.is_set())
        self.assertEqual(entry.worker.journal.state(), state)
        self.assertFalse(any(path == "/api/embed" for _, path, _ in self.fleet.ollama.calls))
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in backend.calls))

    def test_positive_receipt_cleanup_preserves_new_in_flight_health_quarantine(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        state = {"phase": "CLAIMED", "binding": entry.worker.config.binding, "taskNo": backend.task["taskNo"],
                 "inputHash": backend.task["inputHash"], "resultHash": "e" * 64}
        entry.worker.journal.set(state)
        request = backend.request
        def raced(method, path, body=None, key=None):
            result = request(method, path, body, key)
            if path.endswith("/receipt"):
                self.fleet.health()
            if path.endswith("/heartbeat"):
                result["data"]["activeTaskNo"] = "CTA-" + "F" * 32
            return result
        backend.request = raced
        self.fleet.tick(entry)
        self.assertEqual(entry.worker.journal.state()["phase"], "IDLE")
        self.assertTrue(entry.blocked)
        self.assertTrue(entry.worker.stop.is_set())
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in backend.calls))

    def test_rejected_retry_cannot_override_a_discovery_removal_during_manifest_read(self):
        entry, = self.discover([self.row()])
        backend = self.wire(entry)
        state = {"phase": "NEXT_INTENT", "nextKey": "BGE-NEXT-" + "d" * 32, "binding": entry.worker.config.binding}
        data = entry.worker.mutate("NEXT", "/next-task", {}, state["nextKey"], state)
        state.update(phase="UNKNOWN_COMPLETE", taskNo=data["taskNo"], inputHash=data["inputHash"],
                     resultHash=w.sha256(w.artifact(self.fleet.ollama.vectors, len(data["inputTexts"]))))
        entry.worker.journal.set(state)
        call = entry.worker.journal.intent("COMPLETE", "BGE-COMPLETE-" + w.sha256(state["taskNo"].encode()), state["taskNo"], {})
        entry.worker.journal.outcome(call, "REJECTED")
        entry.blocked = True
        entry.worker.stop.set()
        backend.found = False
        backend.calls.clear()
        request = backend.request
        def revoked_in_flight(method, path, body=None, key=None):
            result = request(method, path, body, key)
            if path.endswith("/manifest"):
                self.backend.request.return_value = self.page([])
                self.fleet.refresh()
            return result
        backend.request = revoked_in_flight
        self.fleet.tick(entry)
        self.assertFalse(entry.present)
        self.assertTrue(entry.blocked)
        self.assertTrue(entry.worker.stop.is_set())
        self.assertEqual(entry.worker.journal.state(), state)
        self.assertEqual(entry.worker.journal.db.execute("SELECT COUNT(*) FROM rejected_retries").fetchone()[0], 0)
        self.assertFalse(any(path == "/api/embed" for _, path, _ in self.fleet.ollama.calls))
        self.assertFalse(any(path.endswith("/complete") for _, path, _ in backend.calls))


class BoundaryTests(unittest.TestCase):
    def test_rollback_review_http_transmits_original_complete_and_next_keys_in_headers(self):
        connection = unittest.mock.Mock()
        response = connection.getresponse.return_value
        response.status = 200
        response.read.return_value = w.json_bytes(ok({"rollbackConfirmed": True}))
        with patch.object(w.http.client, "HTTPConnection", return_value=connection):
            w.Http(("127.0.0.1", 8110)).request("GET", "/rollback-review", key="BGE-COMPLETE-original",
                                              original_next_key="BGE-NEXT-" + "a" * 32)
        headers = connection.request.call_args.args[3]
        self.assertEqual(headers["Idempotency-Key"], "BGE-COMPLETE-original")
        self.assertEqual(headers["X-UVEL-Original-Next-Key"], "BGE-NEXT-" + "a" * 32)

    def test_pool_leases_distinct_runners_for_both_digest_checks_and_concurrent_embed_requests(self):
        cfg, barrier = config(), threading.Barrier(2)
        class Runner(w.Http):
            def __init__(self, address):
                super().__init__(address)
                self.fake = Ollama(cfg)
            def request(self, method, path, body=None, key=None):
                with pool.available.mutex:
                    self_test.assertNotIn(self, pool.available.queue)
                if path == "/api/embed":
                    barrier.wait(3)
                return self.fake.request(method, path, body, key)
        self_test = self
        with patch.object(w, "Http", Runner), patch("builtins.print") as log:
            pool = w.OllamaPool([("127.0.0.1", 11436), ("127.0.0.1", 11437)])
            manifests = [task(cfg), {**task(cfg), "deviceId": 93, "instanceNo": "NEX-ORD-93", "taskNo": "CTA-" + "B" * 32}]
            with w.ThreadPoolExecutor(max_workers=2) as executor:
                futures = [executor.submit(w.infer, pool, manifest) for manifest in manifests]
                for future in futures:
                    self.assertEqual(len(future.result(4)), 8192)
        self.assertEqual(pool.available.qsize(), 2)
        for runner in pool.clients:
            self.assertEqual([path for _, path, _ in runner.fake.calls], ["/api/tags", "/api/embed", "/api/tags"])
        records = [json.loads(call.args[0]) for call in log.call_args_list]
        starts = [record for record in records if record["event"] == "INFERENCE_STARTED"]
        finishes = [record for record in records if record["event"] == "INFERENCE_FINISHED"]
        self.assertEqual({record["modelPort"] for record in starts}, {11436, 11437})
        self.assertEqual({record["deviceId"] for record in starts}, {92, 93})
        # Windows can return equal clock ticks for these tiny fake responses; the barrier proves overlap.
        self.assertLessEqual(max(record["monotonicNs"] for record in starts), min(record["monotonicNs"] for record in finishes))

    def test_nondefault_pool_runner_http_failure_or_digest_change_returns_the_complete_lease(self):
        cfg = config()
        class Runner(w.Http):
            def __init__(self, address):
                super().__init__(address)
                self.fake = Ollama(cfg)
                self.failure = None
            def request(self, method, path, body=None, key=None):
                if path == "/api/embed" and self.failure == "http":
                    raise w.Halt("HTTP_OUTCOME_UNKNOWN")
                result = self.fake.request(method, path, body, key)
                if path == "/api/embed" and self.failure == "digest":
                    self.fake.tag_digest = "2" * 64
                return result
        with patch.object(w, "Http", Runner), patch("builtins.print"):
            pool = w.OllamaPool([("127.0.0.1", 11436), ("127.0.0.1", 11437)])
            with pool.lease() as held:
                self.assertEqual(held.address[1], 11436)
                runner = pool.clients[1]
                for failure, code in (("http", "HTTP_OUTCOME_UNKNOWN"), ("digest", "MODEL_DIGEST_CHANGED")):
                    runner.failure = failure
                    runner.fake.tag_digest = cfg.binding["modelDigest"][7:]
                    with self.subTest(failure=failure), self.assertRaisesRegex(w.Halt, code):
                        w.infer(pool, task(cfg))
                    self.assertEqual(pool.available.qsize(), 1)
                runner.failure = None
                runner.fake.tag_digest = cfg.binding["modelDigest"][7:]
                self.assertEqual(len(w.infer(pool, task(cfg))), 8192)
            self.assertEqual(pool.available.qsize(), 2)
            self.assertEqual(held.fake.calls, [])  # Health/default runner cannot mask a bad leased runner.

    def test_embed_http_observation_logs_exact_interval_identifiers_and_failure_without_inputs(self):
        cfg = config()
        manifest = task(cfg)
        for failure in (None, w.Halt("HTTP_OUTCOME_UNKNOWN")):
            ollama = Ollama(cfg)
            request = ollama.request
            def observed_request(method, path, body=None, key=None):
                if path == "/api/embed":
                    self.assertEqual(log.call_count, 1)
                    if failure:
                        raise failure
                return request(method, path, body, key)
            ollama.request = observed_request
            with self.subTest(failure=bool(failure)), patch("builtins.print") as log:
                if failure:
                    with self.assertRaises(w.Halt):
                        w.infer(ollama, manifest)
                else:
                    w.infer(ollama, manifest)
            records = [json.loads(call.args[0]) for call in log.call_args_list]
            self.assertEqual([record["event"] for record in records], ["INFERENCE_STARTED", "INFERENCE_FINISHED"])
            identifiers = {key: manifest[key] for key in ("deviceId", "instanceNo", "taskNo", "model")}
            for record in records:
                self.assertEqual({key: record[key] for key in identifiers}, identifiers)
                self.assertRegex(record["atUtc"], r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{6}Z$")
            start, finish = records
            self.assertEqual(set(start), set(identifiers) | {"event", "atUtc", "monotonicNs"})
            self.assertEqual(set(finish), set(start) | {"elapsedMicros", "success"})
            self.assertGreaterEqual(finish["monotonicNs"], start["monotonicNs"])
            self.assertEqual(finish["elapsedMicros"], (finish["monotonicNs"] - start["monotonicNs"]) // 1000)
            self.assertIs(finish["success"], failure is None)

    def test_rejection_keeps_only_strictly_whitelisted_http_and_error_code_metadata(self):
        for message, expected in (("TEST_CLOUD_HEARTBEAT_STALE", "TEST_CLOUD_HEARTBEAT_STALE"),
                                  ("TEST_CLOUD_BAD token=secret", None), ("private response body", None)):
            connection = unittest.mock.Mock()
            response = connection.getresponse.return_value
            response.status = 409
            response.read.return_value = w.json_bytes({"code": 409, "message": message, "data": None})
            with patch.object(w.http.client, "HTTPConnection", return_value=connection):
                with self.assertRaises(w.Rejected) as caught:
                    w.Http(("127.0.0.1", 8110)).request("POST", "/complete", {})
            self.assertEqual(caught.exception.http_status, 409)
            self.assertEqual(caught.exception.error_code, expected)
            self.assertEqual(str(caught.exception), "BACKEND_REJECTED_409")

    def test_loopback_only_origins(self):
        self.assertEqual(w.loopback_url("http://127.0.0.1:11435"), ("127.0.0.1", 11435))
        for value in ("http://example.com:80", "http://localhost:11435", "https://127.0.0.1:11435", "http://127.0.0.1:80/x",
                      "http://u:p@127.0.0.1:80", "http://127.0.0.1:80?x=1", "http://127.0.0.1:80/#x"):
            with self.subTest(value=value), self.assertRaises(w.Halt):
                w.loopback_url(value)

    def test_bad_vector_numbers_dimensions_and_float32_range(self):
        for row in ([0.0] * 1024, [0.1] * 384, [float("nan")] * 1024, [float("inf")] * 1024,
                    [True] * 1024, [1e100] * 1024, [1e-100] * 1024):
            with self.subTest(first=row[0], length=len(row)), self.assertRaises(w.Halt):
                w.artifact([row], 1)

    def test_duplicate_json_and_nonfinite_are_rejected(self):
        for raw in (b'{"code":0,"code":1}', b'{"x":NaN}', b'{"x":Infinity}', b'not-json'):
            with self.subTest(raw=raw), self.assertRaises(w.Halt):
                w.strict_json(raw)

    def test_transport_classifies_only_pre_request_connect_failure_as_not_sent(self):
        connection = unittest.mock.Mock()
        connection.connect.side_effect = ConnectionRefusedError()
        with patch.object(w.http.client, "HTTPConnection", return_value=connection), self.assertRaises(w.NotSent):
            w.Http(("127.0.0.1", 8110)).request("POST", "/next-task", {})
        connection.request.assert_not_called()
        connection = unittest.mock.Mock()
        connection.request.side_effect = BrokenPipeError()
        with patch.object(w.http.client, "HTTPConnection", return_value=connection), self.assertRaises(w.Halt) as result:
            w.Http(("127.0.0.1", 8110)).request("POST", "/next-task", {})
        self.assertNotIsInstance(result.exception, w.NotSent)


if __name__ == "__main__":
    unittest.main()

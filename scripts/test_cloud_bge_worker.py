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
            active = self.task["taskNo"] if phase in ("CLAIMED", "COMPLETE_INTENT", "UNKNOWN_COMPLETE") else None
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
                        ("UNKNOWN_NEXT", "NEXT_INTENT", "NEXT_NOT_SENT", "CLAIMED", "COMPLETE_INTENT", "UNKNOWN_COMPLETE", "COMPLETE_NOT_SENT"))):
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
        # Wake the real health thread when inference has entered the original task wait.
        def wake(_):
            inferred.wait(2)
            return self.fleet.stop.is_set()
        with patch.object(self.fleet.stop, "wait", side_effect=wake):
            self.assertEqual(self.fleet.run(), 0)
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


class BoundaryTests(unittest.TestCase):
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

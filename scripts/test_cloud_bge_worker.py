"""Offline trust-boundary and durable recovery tests; no model, network or financial effects."""
import base64
from contextlib import closing
import importlib.util
import json
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
            return ok({**self.cfg.binding, "serverCanonical": True, "heartbeatAt": int(time.time() * 1000)})
        if path.endswith("/complete"):
            self.complete_body = body
            if self.complete_error:
                raise self.complete_error
            return ok(self.receipt())
        if path.endswith("/receipt"):
            return ok(self.receipt())
        raise AssertionError(path)

    def receipt(self):
        state = self.journal.state()
        return {**identity(self.cfg), "taskNo": self.task["taskNo"], "inputHash": self.task["inputHash"],
                "found": self.found, "status": "COMPLETED" if self.found else "RUNNING",
                "resultHash": state.get("resultHash"), "receiptNo": "CTR-" + "A" * 32 if self.found else None}


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

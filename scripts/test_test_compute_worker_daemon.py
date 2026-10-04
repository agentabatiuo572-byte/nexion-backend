"""Offline protocol/journal checks. No backend, login, real token, nonce or network."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import sqlite3
import stat
import tempfile
import time
import unittest
from unittest.mock import patch
from types import SimpleNamespace

spec = importlib.util.spec_from_file_location("daemon", Path(__file__).with_name("test-compute-worker-daemon.py"))
daemon = importlib.util.module_from_spec(spec)
spec.loader.exec_module(daemon)


def fixture():
    claim = dict(ownerId=daemon.OWNER, deviceId=daemon.DEVICE, instanceNo=daemon.INSTANCE,
                 taskNo="CTA-LOCAL-TEST", taskConfigId=daemon.CONFIG, proofNonce="b" * 64,
                 executorId="test355-continuous", runId="TEST355-CONTINUOUS", executionKind=daemon.compute.KIND,
                 specVersion=daemon.compute.SPEC, deploymentScope="TEST", serverCanonical=True)
    now = int(time.time() * 1000)
    claim.update(completableAt=now - 1000, proofExpiresAt=now + 3600000, leaseExpiresAt=now + 3600000,
                 jobIssuedAt=now - 3600000, jobExpiresAt=now + 3600000, serverNow=now)
    seed = ("UVEL_TEST_VECTOR_STATS_INPUT_V1\nowner=" + str(daemon.OWNER) + "\ndevice=" + str(daemon.DEVICE)
            + "\ninstance=" + daemon.INSTANCE + "\ntask=" + claim["taskNo"] + "\ntaskConfig=" + daemon.CONFIG
            + "\nnonce=" + claim["proofNonce"] + "\ncount=32\n")
    values = [int.from_bytes(hashlib.sha256((seed + "index=" + str(i) + "\n").encode()).digest()[:2], "big") % 2001 - 1000 for i in range(32)]
    raw = (seed + "values=" + ",".join(map(str, values)) + "\n").encode()
    claim.update(inputHash=hashlib.sha256(raw).hexdigest(), inputBytesBase64=base64.b64encode(raw).decode())
    oracle = ("UVEL_TEST_VECTOR_STATS_RESULT_V1\ninput=" + claim["inputHash"] + "\ncount=32\nsum=" + str(sum(values))
              + "\nsumSquares=" + str(sum(x * x for x in values)) + "\nsorted=" + ",".join(map(str, sorted(values))) + "\n").encode()
    return claim, oracle


class MemoryClient:
    def __init__(self, failure=None):
        self.calls = []
        self.failure = failure
        self.claim, self.result = fixture()

    def post(self, path, body, key):
        self.calls.append((path, body, key))
        if path.endswith("next-task"):
            if self.failure == "next":
                raise daemon.Halt("HTTP_UNKNOWN")
            return self.claim
        if path.endswith("release"):
            if self.failure == "complete-release-not-sent":
                raise daemon.NotSent("HTTP_NOT_SENT")
            if self.failure == "release":
                raise daemon.Halt("HTTP_UNKNOWN")
            return dict(taskNo=self.claim["taskNo"], executionKind=daemon.compute.KIND, released=True,
                        taskStatus="CANCELLED", serverCanonical=True, deploymentScope="TEST")
        if self.failure in ("complete-not-sent", "complete-release-not-sent"):
            raise daemon.NotSent("HTTP_NOT_SENT")
        if self.failure in ("complete", "release"):
            raise daemon.Halt("HTTP_UNKNOWN")
        require = unittest.TestCase().assertEqual
        request = daemon.compute.strict_json(body)
        require(base64.b64decode(request["resultArtifactBase64"]), self.result)
        reply = {k: self.claim[k] for k in daemon.compute.BINDINGS + ("executionKind", "specVersion", "inputHash")}
        receipt = "CTR-" + self.claim["taskNo"][-32:]
        reply.update(resultHash=hashlib.sha256(self.result).hexdigest(), receiptNo=receipt,
                     completion=dict(status="COMPLETED", serverCanonical=True, taskNo=self.claim["taskNo"], deviceId=daemon.DEVICE,
                                     taskId=daemon.CONFIG, receiptNo=receipt))
        return reply


def local_journal(path):
    # Exercise real SQLite commits and constraints on Windows; Linux ownership/flock is a deployment check.
    journal = object.__new__(daemon.Journal)
    journal.db = sqlite3.connect(path)
    journal.db.execute("PRAGMA synchronous=FULL")
    journal.db.executescript("""
        CREATE TABLE IF NOT EXISTS state(id INTEGER PRIMARY KEY CHECK(id=1), phase TEXT NOT NULL);
        INSERT OR IGNORE INTO state VALUES(1,'IDLE');
        CREATE TABLE IF NOT EXISTS calls(request_key TEXT PRIMARY KEY, operation TEXT NOT NULL, task_no TEXT, outcome TEXT NOT NULL);
        CREATE TABLE IF NOT EXISTS jobs(task_no TEXT PRIMARY KEY,status TEXT NOT NULL,input_hash TEXT,result_hash TEXT,receipt_no TEXT);
    """)
    journal.db.commit()
    return journal


class DaemonTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / "journal.sqlite3"
        self.journal = local_journal(self.path)

    def tearDown(self):
        self.journal.db.close()
        self.directory.cleanup()

    def test_genuine_computation_confirmation_and_journal_contain_no_nonce_or_artifact(self):
        client = MemoryClient()
        self.assertTrue(daemon.process_one(client, self.journal, "test355-continuous"))
        self.assertEqual(len(client.calls), 2)
        self.journal.check_pending()
        rows = self.journal.db.execute("SELECT * FROM jobs").fetchall()
        self.assertEqual(rows[0][1], "CONFIRMED")
        all_stored = repr(rows + self.journal.db.execute("SELECT * FROM calls").fetchall())
        for secret in (client.claim["proofNonce"], client.claim["inputBytesBase64"], base64.b64encode(client.result).decode()):
            self.assertNotIn(secret, all_stored)

    def test_unknown_complete_is_one_attempt_release_cannot_erase_it_and_restart_stays_stopped(self):
        client = MemoryClient("complete")
        with self.assertRaisesRegex(daemon.Halt, "STOP_PENDING_RECONCILIATION"):
            daemon.process_one(client, self.journal, "test355-continuous")
        self.assertEqual(sum(path.endswith("complete") for path, _, _ in client.calls), 1)
        self.assertEqual(sum(path.endswith("release") for path, _, _ in client.calls), 1)
        self.journal.db.close()
        self.journal = local_journal(self.path)
        with self.assertRaisesRegex(daemon.Halt, "STOP_PENDING_RECONCILIATION"):
            self.journal.check_pending()
        self.assertEqual(self.journal.db.execute("SELECT status FROM jobs").fetchone()[0], "UNKNOWN")

    def test_unknown_claim_stays_stopped_without_guessing_a_task_to_release(self):
        client = MemoryClient("next")
        with self.assertRaises(daemon.Halt):
            daemon.process_one(client, self.journal, "test355-continuous")
        self.assertEqual(len(client.calls), 1)
        with self.assertRaises(daemon.Halt):
            self.journal.check_pending()

    def test_known_unsent_next_request_returns_to_idle_and_allows_a_new_poll(self):
        client = MemoryClient()
        client.post = lambda *_: (_ for _ in ()).throw(daemon.NotSent("HTTP_NOT_SENT"))
        self.assertFalse(daemon.process_one(client, self.journal, "test355-continuous"))
        self.journal.check_pending()
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls").fetchone()[0], "NOT_SENT")
        self.assertTrue(daemon.process_one(MemoryClient(), self.journal, "test355-continuous"))

    def unsent_claim(self):
        client = MemoryClient("complete-release-not-sent")
        self.assertFalse(daemon.process_one(client, self.journal, "test355-continuous"))
        self.assertEqual(self.journal.not_sent_task(), client.claim["taskNo"])
        return client

    def test_unsent_complete_and_confirmed_own_release_abandon_only_local_job_without_receipt(self):
        client = MemoryClient("complete-not-sent")
        self.assertFalse(daemon.process_one(client, self.journal, "test355-continuous"))
        self.journal.check_pending()
        self.assertIsNone(self.journal.not_sent_task())
        self.assertEqual(self.journal.db.execute("SELECT status,receipt_no FROM jobs").fetchone(), ("RELEASED_NOT_SENT", None))
        self.assertEqual(self.journal.db.execute("SELECT operation,outcome FROM calls ORDER BY rowid").fetchall(),
                         [("NEXT", "CLAIMED"), ("COMPLETE", "NOT_SENT"), ("RELEASE", "RELEASED_CANCELLED")])
        self.assertEqual(sum(path.endswith("complete") for path, _, _ in client.calls), 1)

    def test_persisted_unsent_complete_resumes_only_release_after_backend_returns(self):
        client = self.unsent_claim()
        self.journal.db.close()
        self.journal = local_journal(self.path)
        task = self.journal.not_sent_task()
        client.failure = None
        self.journal.finish_not_sent_release(task, daemon.release_claim(client, self.journal, task))
        self.journal.check_pending()
        self.assertEqual(sum(path.endswith("next-task") for path, _, _ in client.calls), 1)
        self.assertEqual(sum(path.endswith("complete") for path, _, _ in client.calls), 1)
        keys = [key for path, _, key in client.calls if path.endswith("release")]
        self.assertEqual(keys, [daemon.release_key(task), daemon.release_key(task)])
        self.assertEqual(self.journal.db.execute("SELECT status,receipt_no FROM jobs").fetchone(), ("RELEASED_NOT_SENT", None))

    def test_release_intent_crash_or_non_owned_runtime_cannot_resume_or_clear_unsent_job(self):
        for outcome in ("PENDING", "NOT_OWNED", "RELEASED"):
            with self.subTest(outcome=outcome):
                client = self.unsent_claim()
                with self.journal.db:
                    self.journal.db.execute("UPDATE calls SET outcome=? WHERE operation='RELEASE'", (outcome,))
                self.journal.db.close()
                self.journal = local_journal(self.path)
                with self.assertRaisesRegex(daemon.Halt, "STOP_PENDING_RECONCILIATION"):
                    self.journal.not_sent_task()
                with self.journal.db:
                    self.journal.db.execute("DELETE FROM calls")
                    self.journal.db.execute("DELETE FROM jobs")
                    self.journal.db.execute("UPDATE state SET phase='IDLE'")

    def test_unsent_recovery_unknown_or_rejected_release_stops_without_complete_or_new_claim(self):
        for rejected in (False, True):
            with self.subTest(rejected=rejected):
                client = self.unsent_claim()
                client.failure = "release"
                if rejected:
                    original = client.post
                    def post(path, body, key):
                        if path.endswith("release"):
                            client.calls.append((path, body, key))
                            raise daemon.Rejected("HTTP_REJECTED")
                        return original(path, body, key)
                    client.post = post
                with self.assertRaisesRegex(daemon.Halt, "STOP_REJECTED" if rejected else "STOP_PENDING_RECONCILIATION"):
                    daemon.release_claim(client, self.journal, self.journal.not_sent_task())
                self.assertEqual(self.journal.db.execute("SELECT phase FROM state").fetchone()[0], "REJECTED" if rejected else "UNKNOWN")
                with self.assertRaises(daemon.Halt):
                    self.journal.not_sent_task()
                self.assertEqual(sum(path.endswith("complete") for path, _, _ in client.calls), 1)
                self.assertEqual(sum(path.endswith("next-task") for path, _, _ in client.calls), 1)
                with self.journal.db:
                    self.journal.db.execute("DELETE FROM calls")
                    self.journal.db.execute("DELETE FROM jobs")
                    self.journal.db.execute("UPDATE state SET phase='IDLE'")

    def test_unsent_recovery_canonical_release_false_is_not_a_cleanup_confirmation(self):
        client = self.unsent_claim()
        task = self.journal.not_sent_task()
        client.post = lambda *_: dict(taskNo=task, executionKind=daemon.compute.KIND, released=False)
        outcome = daemon.release_claim(client, self.journal, task)
        with self.assertRaisesRegex(daemon.Halt, "STOP_PENDING_RECONCILIATION"):
            self.journal.finish_not_sent_release(task, outcome)
        with self.assertRaises(daemon.Halt):
            self.journal.not_sent_task()
        self.assertEqual(self.journal.db.execute("SELECT status,receipt_no FROM jobs").fetchone(), ("NOT_SENT", None))

    def test_unsent_recovery_requires_exact_claim_complete_and_release_history(self):
        changes = (
            "UPDATE calls SET outcome='UNKNOWN' WHERE operation='COMPLETE'",
            "UPDATE calls SET outcome='REJECTED' WHERE operation='COMPLETE'",
            "UPDATE calls SET outcome='PENDING' WHERE operation='NEXT'",
            "UPDATE calls SET request_key='foreign-key' WHERE operation='RELEASE'",
            "UPDATE jobs SET receipt_no='CTR-SYNTHETIC'",
            "UPDATE jobs SET status='UNKNOWN'",
            "INSERT INTO jobs VALUES('CTA-FOREIGN','CLAIMED',NULL,NULL,NULL)",
        )
        for change in changes:
            with self.subTest(change=change):
                self.unsent_claim()
                with self.journal.db:
                    self.journal.db.execute(change)
                with self.assertRaisesRegex(daemon.Halt, "STOP_PENDING_RECONCILIATION"):
                    self.journal.not_sent_task()
                with self.journal.db:
                    self.journal.db.execute("DELETE FROM calls")
                    self.journal.db.execute("DELETE FROM jobs")
                    self.journal.db.execute("UPDATE state SET phase='IDLE'")

    def test_death_before_release_intent_allows_only_first_release_not_complete(self):
        client = self.unsent_claim()
        with self.journal.db:
            self.journal.db.execute("DELETE FROM calls WHERE operation='RELEASE'")
        task = self.journal.not_sent_task()
        client.failure = None
        self.journal.finish_not_sent_release(task, daemon.release_claim(client, self.journal, task))
        self.journal.check_pending()
        self.assertEqual(sum(path.endswith("complete") for path, _, _ in client.calls), 1)

    def test_main_waits_at_configured_interval_and_performs_only_known_unsent_cleanup(self):
        client = self.unsent_claim()
        waits = []
        class Stop:
            def is_set(self):
                return len(waits) == 2
            def wait(self, interval):
                waits.append(interval)
                client.failure = None
            def set(self):
                pass
        with patch.dict(os.environ, {"UVEL_TEST_WORKER_ENABLED": "true", "UVEL_TEST_WORKER_SCOPE": "TEST"}, clear=True), \
                patch.object(daemon, "STOP", Stop()), patch.object(daemon, "credential", return_value="SYNTHETIC-NOT-A-TOKEN"), \
                patch.object(daemon, "Journal", return_value=self.journal), patch.object(self.journal, "close"), \
                patch.object(daemon, "Client", return_value=client), patch.object(daemon.sys, "argv", ["worker", "--interval", "17"]), \
                patch.object(daemon.signal, "signal"), patch.object(daemon, "process_one", side_effect=AssertionError), \
                patch("builtins.print") as printed:
            self.assertEqual(daemon.main(), 0)
        self.assertEqual(waits, [17, 17])
        self.assertEqual([entry.args[0] for entry in printed.call_args_list], ["TEST_COMPUTE_WAIT_UNSENT_RELEASE", "TEST_COMPUTE_IDLE"])
        self.assertEqual(sum(path.endswith("complete") for path, _, _ in client.calls), 1)
        self.assertEqual(sum(path.endswith("next-task") for path, _, _ in client.calls), 1)
        self.journal.check_pending()

    def test_confirmed_cancellation_before_local_finish_crash_resumes_local_only(self):
        client = self.unsent_claim()
        task = self.journal.not_sent_task()
        client.failure = None
        self.assertEqual(daemon.release_claim(client, self.journal, task), "RELEASED_CANCELLED")
        self.journal.db.close()
        self.journal = local_journal(self.path)
        self.assertEqual(self.journal.not_sent_task(), task)
        calls = list(client.calls)
        waits = []
        stop = SimpleNamespace(is_set=lambda: bool(waits), wait=lambda interval: waits.append(interval))
        with patch.dict(os.environ, {"UVEL_TEST_WORKER_ENABLED": "true", "UVEL_TEST_WORKER_SCOPE": "TEST"}, clear=True), \
                patch.object(daemon, "STOP", stop), patch.object(daemon, "credential", return_value="SYNTHETIC-NOT-A-TOKEN"), \
                patch.object(daemon, "Journal", return_value=self.journal), patch.object(self.journal, "close"), \
                patch.object(daemon, "Client", return_value=client), patch.object(client, "post", side_effect=AssertionError), \
                patch.object(daemon.sys, "argv", ["worker"]), patch.object(daemon.signal, "signal"), patch("builtins.print"):
            self.assertEqual(daemon.main(), 0)
        self.journal.check_pending()
        self.assertEqual(client.calls, calls)
        self.assertEqual(self.journal.db.execute("SELECT status,receipt_no FROM jobs").fetchone(), ("RELEASED_NOT_SENT", None))

    def test_legacy_liveness_success_cannot_be_recorded_as_terminal_cleanup(self):
        client = self.unsent_claim()
        task = self.journal.not_sent_task()
        client.post = lambda *_: dict(taskNo=task, executionKind=daemon.compute.KIND, released=True)
        with self.assertRaisesRegex(daemon.Halt, "STOP_PENDING_RECONCILIATION"):
            daemon.release_claim(client, self.journal, task)
        self.assertEqual(self.journal.db.execute("SELECT phase FROM state").fetchone()[0], "UNKNOWN")
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='RELEASE'").fetchone()[0], "UNKNOWN")

    def test_release_rejection_does_not_erase_previously_unknown_complete(self):
        client = MemoryClient("complete")
        original = client.post
        def post(path, body, key):
            if path.endswith("release"):
                client.calls.append((path, body, key))
                raise daemon.Rejected("HTTP_REJECTED")
            return original(path, body, key)
        client.post = post
        with self.assertRaisesRegex(daemon.Halt, "STOP_PENDING_RECONCILIATION"):
            daemon.process_one(client, self.journal, "test355-continuous")
        self.assertEqual(self.journal.db.execute("SELECT phase FROM state").fetchone()[0], "UNKNOWN")
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='COMPLETE'").fetchone()[0], "UNKNOWN")
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='RELEASE'").fetchone()[0], "REJECTED")

    def test_transport_connect_failure_is_not_sent_but_request_started_failure_is_unknown(self):
        for connect_failure in (True, False):
            requests = []
            pinned = SimpleNamespace(settimeout=lambda _: None, close=lambda: None)

            class Connection:
                def __init__(self, *_, **kwargs):
                    self.sock = pinned
                def connect(self):
                    if connect_failure:
                        raise ConnectionRefusedError
                def request(self, *args):
                    requests.append(args)
                    raise OSError
                def close(self):
                    pass

            with patch.object(daemon.http.client, "HTTPConnection", Connection):
                client = daemon.Client("SYNTHETIC-NOT-A-TOKEN")
                if connect_failure:
                    with self.assertRaises(daemon.NotSent):
                        client.post(daemon.PREFIX + "/next-task", b"{}", "local")
                    self.assertEqual(len(requests), 0)
                else:
                    with self.assertRaisesRegex(daemon.Halt, "HTTP_UNKNOWN") as raised:
                        client.post(daemon.PREFIX + "/next-task", b"{}", "local")
                    self.assertNotIsInstance(raised.exception, daemon.NotSent)
                    self.assertEqual(len(requests), 1)

    def test_release_unknown_keeps_complete_unknown(self):
        client = MemoryClient("release")
        with self.assertRaises(daemon.Halt):
            daemon.process_one(client, self.journal, "test355-continuous")
        outcomes = self.journal.db.execute("SELECT operation,outcome FROM calls").fetchall()
        self.assertIn(("COMPLETE", "UNKNOWN"), outcomes)
        self.assertIn(("RELEASE", "UNKNOWN"), outcomes)

    def test_foreign_owner_device_instance_config_and_executor_are_rejected(self):
        for name, value in {"ownerId": daemon.OWNER + 1, "deviceId": daemon.DEVICE + 1,
                            "instanceNo": "other", "taskConfigId": "other", "executorId": "other"}.items():
            claim, _ = fixture()
            claim[name] = value
            with self.assertRaisesRegex(daemon.Halt, "CLAIM_BINDING_INVALID"):
                daemon.validate_claim(claim, "test355-continuous")

    def test_pending_intent_alone_survives_crash_and_blocks_restart(self):
        self.journal.begin("NEXT", "local-crash-key")
        self.journal.db.close()
        self.journal = local_journal(self.path)
        with self.assertRaises(daemon.Halt):
            self.journal.check_pending()

    def test_default_off_never_loads_credentials_state_or_network(self):
        with patch.dict(os.environ, {}, clear=True), patch.object(daemon, "credential", side_effect=AssertionError), \
                patch.object(daemon, "Journal", side_effect=AssertionError), patch.object(daemon.sys, "argv", ["worker"]):
            self.assertEqual(daemon.main(), 0)

    def test_clean_idle_process_failure_allows_restart_but_pending_intent_prevents_it(self):
        holder = SimpleNamespace(check_pending=self.journal.check_pending, not_sent_task=self.journal.not_sent_task, close=lambda: None)
        for pending, expected in ((False, 1), (True, daemon.HALTED)):
            if pending:
                self.journal.begin("NEXT", "restart-protection")
            with patch.dict(os.environ, {"UVEL_TEST_WORKER_ENABLED": "true", "UVEL_TEST_WORKER_SCOPE": "TEST"}, clear=True), \
                    patch.object(daemon, "credential", return_value="SYNTHETIC-NOT-A-TOKEN"), \
                    patch.object(daemon, "Journal", return_value=holder), patch.object(daemon.sys, "argv", ["worker"]), \
                    patch.object(daemon.signal, "signal"), patch.object(daemon, "process_one", side_effect=RuntimeError):
                self.assertEqual(daemon.main(), expected)

    def test_duplicate_task_cannot_be_confirmed_or_computed_twice(self):
        client = MemoryClient()
        daemon.process_one(client, self.journal, "test355-continuous")
        again = MemoryClient()
        with self.assertRaises(daemon.Halt):
            daemon.process_one(again, self.journal, "test355-continuous")
        self.assertEqual(sum(path.endswith("complete") for path, _, _ in again.calls), 0)
        self.assertEqual(self.journal.db.execute("SELECT receipt_no FROM jobs").fetchone()[0], "CTR-CTA-LOCAL-TEST")

    def test_known_rejection_is_not_reported_as_a_confirmed_receipt_or_an_unknown_complete(self):
        client = MemoryClient()
        original = client.post

        def rejected(path, body, key):
            if path.endswith("complete"):
                client.calls.append((path, body, key))
                raise daemon.Rejected("HTTP_REJECTED")
            return original(path, body, key)

        client.post = rejected
        with self.assertRaisesRegex(daemon.Halt, "STOP_REJECTED"):
            daemon.process_one(client, self.journal, "test355-continuous")
        self.assertEqual(self.journal.db.execute("SELECT phase FROM state").fetchone()[0], "REJECTED")
        self.assertEqual(self.journal.db.execute("SELECT outcome FROM calls WHERE operation='COMPLETE'").fetchone()[0], "REJECTED")
        self.assertIsNone(self.journal.db.execute("SELECT receipt_no FROM jobs").fetchone()[0])

    def test_canonical_next_rejection_logs_only_status_and_whitelisted_code_without_replay(self):
        for message, safe_code in (
                ("TEST_COMPUTE_WORKER_RUNTIME_STALE", "TEST_COMPUTE_WORKER_RUNTIME_STALE"),
                ("SYNTHETIC-NONCE-TOKEN-BODY", "UNRECOGNIZED_REJECTION"),
                ({"nonce": "SYNTHETIC-NONCE-TOKEN-BODY"}, "UNRECOGNIZED_REJECTION")):
            with self.subTest(message=message):
                requests = []
                pinned = SimpleNamespace(settimeout=lambda _: None, close=lambda: None)
                raw = json.dumps(dict(code=409, message=message, data=None)).encode()

                class Connection:
                    def __init__(self, *_, **kwargs):
                        self.sock = pinned
                    def connect(self):
                        pass
                    def request(self, *args):
                        requests.append(args)
                    def getresponse(self):
                        return SimpleNamespace(status=409, read=lambda _: raw)
                    def close(self):
                        pass

                with patch.object(daemon.http.client, "HTTPConnection", Connection), patch("builtins.print") as printed:
                    with self.assertRaisesRegex(daemon.Halt, "STOP_REJECTED"):
                        daemon.process_one(daemon.Client("SYNTHETIC-NOT-A-TOKEN"), self.journal, "test355-continuous")
                    printed.assert_called_once_with("TEST_COMPUTE_REJECTED status=409 code=" + safe_code)
                self.assertEqual(len(requests), 1)
                self.assertEqual(self.journal.db.execute("SELECT phase FROM state").fetchone()[0], "REJECTED")
                self.assertEqual(self.journal.db.execute("SELECT COUNT(*) FROM jobs").fetchone()[0], 0)
                self.assertNotIn("SYNTHETIC-NONCE-TOKEN-BODY", repr(self.journal.db.execute("SELECT * FROM calls").fetchall()))
                with self.assertRaisesRegex(daemon.Halt, "STOP_PENDING_RECONCILIATION"):
                    self.journal.check_pending()

    def test_transport_has_one_attempt_total_deadline_and_closes_detached_body_socket(self):
        class Pinned:
            closed = False
            shutdowns = 0
            def settimeout(self, value):
                self.timeout = value
            def shutdown(self, _):
                self.shutdowns += 1
            def close(self):
                self.closed = True

        for timeout in (False, True):
            pinned = Pinned()
            elapsed = [0]
            timers = []
            attempts = []

            class Timer:
                def __init__(self, delay, callback):
                    self.delay, self.callback = delay, callback
                    timers.append(self)
                def start(self):
                    pass
                def cancel(self):
                    pass

            class Connection:
                def __init__(self, host, port, timeout):
                    attempts.append((host, port, timeout))
                    self.sock = pinned
                def connect(self):
                    elapsed[0] += 3
                def request(self, *args):
                    pass
                def getresponse(self):
                    self.sock = None  # Python Connection: close behavior.
                    def read(_):
                        if timeout:
                            timers[0].callback()
                            elapsed[0] += 8
                        return b'{"code":0,"data":{"idle":true}}'
                    return SimpleNamespace(status=200, read=read)
                def close(self):
                    pass

            with patch.object(daemon.http.client, "HTTPConnection", Connection), patch.object(daemon.threading, "Timer", Timer), \
                    patch.object(daemon.time, "monotonic", lambda: elapsed[0]):
                client = daemon.Client("SYNTHETIC-NOT-A-TOKEN")
                if timeout:
                    with self.assertRaisesRegex(daemon.Halt, "HTTP_UNKNOWN"):
                        client.post(daemon.PREFIX + "/next-task", b"{}", "local")
                    self.assertEqual(pinned.shutdowns, 1)
                else:
                    self.assertEqual(client.post(daemon.PREFIX + "/next-task", b"{}", "local"), {"idle": True})
            self.assertEqual(attempts, [("127.0.0.1", 8110, 10)])
            self.assertEqual(timers[0].delay, 7)
            self.assertTrue(pinned.closed)


class CredentialTest(unittest.TestCase):
    def read_credential(self, *, mode=0o440, owner=0, group=0, links=1, regular=True,
                        directory="/run/credentials/uvel-test-compute-worker.service",
                        directory_mode=0o550, directory_owner=0, directory_group=0,
                        directory_type=stat.S_IFDIR, raw=None):
        # Synthetic bytes only; no real credential file or Linux namespace is accessed.
        token = b"tc1_" + base64.urlsafe_b64encode(bytes(range(32))).rstrip(b"=")
        file_info = SimpleNamespace(st_mode=(stat.S_IFREG if regular else stat.S_IFIFO) | mode,
                                    st_uid=owner, st_gid=group, st_nlink=links)
        directory_info = SimpleNamespace(st_mode=directory_type | directory_mode,
                                         st_uid=directory_owner, st_gid=directory_group)
        with patch.dict(os.environ, {"CREDENTIALS_DIRECTORY": directory}, clear=True), \
                patch.object(daemon.os, "getuid", return_value=62412, create=True), \
                patch.object(daemon.os, "O_NOFOLLOW", 0x20000, create=True), \
                patch.object(daemon.os, "open", return_value=123) as opened, \
                patch.object(daemon.os, "fstat", return_value=file_info), \
                patch.object(daemon.os, "read", return_value=token + b"\n" if raw is None else raw), \
                patch.object(daemon.os, "close") as closed, \
                patch.object(daemon.Path, "lstat", return_value=directory_info) as parent:
            try:
                return daemon.credential(), opened, parent
            finally:
                closed.assert_called_once_with(123)
                opened.assert_called_once_with(Path(directory) / "test-worker-token", os.O_RDONLY | os.O_NOFOLLOW)

    def test_systemd_root_idmapped_0440_in_private_0550_directory_is_accepted(self):
        token, _, parent = self.read_credential()
        self.assertTrue(token.startswith("tc1_"))
        parent.assert_called_once()

    def test_previous_owner_only_modes_do_not_require_the_systemd_directory_exception(self):
        for owner in (0, 62412):
            for mode in (0o400, 0o600):
                with self.subTest(owner=owner, mode=mode):
                    _, _, parent = self.read_credential(mode=mode, owner=owner, directory="/private/old-credential")
                    parent.assert_not_called()

    def test_group_read_exception_rejects_wrong_file_or_nonprivate_directory(self):
        cases = (
            dict(group=62412), dict(owner=62412), dict(owner=42, mode=0o400),
            dict(links=2), dict(regular=False), dict(mode=0o444), dict(mode=0o460), dict(mode=0o640),
            dict(directory="/other/credentials"), dict(directory_mode=0o555), dict(directory_mode=0o570),
            dict(directory_owner=62412), dict(directory_group=62412), dict(directory_type=stat.S_IFLNK),
        )
        for attributes in cases:
            with self.subTest(attributes=attributes), self.assertRaisesRegex(daemon.Halt, "CREDENTIAL_SOURCE_INVALID"):
                self.read_credential(**attributes)

    def test_file_and_format_guards_still_reject_links_nonregular_and_invalid_tokens(self):
        for attributes in (dict(mode=0o400, links=2), dict(mode=0o400, regular=False),
                           dict(raw=b"not-a-token"), dict(raw=b"tc1_" + b"A" * 42 + b"B"),
                           dict(raw=b"tc1_" + b"A" * 43 + b"\nEXTRA")):
            with self.subTest(attributes=attributes), self.assertRaises(daemon.Halt):
                self.read_credential(**attributes)


if __name__ == "__main__":
    unittest.main()

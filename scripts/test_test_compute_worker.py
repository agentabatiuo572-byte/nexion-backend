"""Local-only protocol oracle. No database, payment, public API or real grant."""
import base64
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from unittest.mock import patch


SCRIPT = Path(__file__).with_name("test-compute-worker.py")
KIND = "TEST_DETERMINISTIC_V1"
SPEC = "UVEL_TEST_VECTOR_STATS_V1"
TASK = "CTA-TEST-local-vector-only"
TOKEN = "tw1_" + base64.urlsafe_b64encode(bytes(range(32))).decode().rstrip("=")


def worker_module():
    spec = importlib.util.spec_from_file_location("test_compute_worker", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def fixture_input():
    # Independent seed/vector generator; never imports the worker implementation.
    nonce = "a9" * 32
    seed = ("UVEL_TEST_VECTOR_STATS_INPUT_V1\nowner=71\ndevice=83\n"
            "instance=SH-LOCAL-001\ntask=" + TASK + "\n"
            "taskConfig=LOCAL-VECTOR-1\nnonce=" + nonce + "\ncount=32\n")
    values = []
    for index in range(32):
        digest = hashlib.sha256((seed + "index=" + str(index) + "\n").encode()).digest()
        values.append(((digest[0] << 8) | digest[1]) % 2001 - 1000)
    return (seed + "values=" + ",".join(map(str, values)) + "\n").encode()


def independent_result(raw):
    # Read the actual input, then insertion-sort and accumulate in a loop.
    values = [int(part) for part in raw.decode().splitlines()[-1][7:].split(",")]
    ordered = []
    total = squares = 0
    for value in values:
        position = 0
        while position < len(ordered) and ordered[position] <= value:
            position += 1
        ordered.insert(position, value)
        total += value
        squares += value * value
    digest = hashlib.sha256(raw).hexdigest()
    return ("UVEL_TEST_VECTOR_STATS_RESULT_V1\ninput=" + digest + "\ncount=32\n"
            "sum=" + str(total) + "\nsumSquares=" + str(squares) + "\n"
            "sorted=" + ",".join(map(str, ordered)) + "\n").encode()


def claim_data():
    now = int(time.time() * 1000)
    raw = fixture_input()
    return dict(executionKind=KIND, specVersion=SPEC, runId="TEST-LOCAL-001",
                executorId="local-oracle", ownerId=71, deviceId=83,
                instanceNo="SH-LOCAL-001", taskNo=TASK, taskConfigId="LOCAL-VECTOR-1",
                proofNonce="a9" * 32, proofExpiresAt=now + 120000,
                leaseExpiresAt=now + 120000, completableAt=now - 1000,
                inputBytesBase64=base64.b64encode(raw).decode(),
                inputHash=hashlib.sha256(raw).hexdigest())


class Fixture:
    def __init__(self, mode="ok"):
        self.mode = mode
        self.requests = []
        self.errors = []
        self.claim = claim_data()
        fixture = self

        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass  # Never log Authorization or any request/response body.

            def reply(self, status, data=None, code=0):
                body = json.dumps(dict(code=code, message="local fixture", data=data)).encode()
                if fixture.mode == "escaped_secret":
                    escaped = "".join("\\u%04x" % ord(char) for char in TOKEN).encode()
                    body = body.replace(TOKEN.encode(), escaped)
                self.send_response(status)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def do_POST(self):
                length = int(self.headers.get("Content-Length", "0"))
                if length > 8192 or len(fixture.requests) >= 12:
                    self.reply(413, code=413)
                    return
                raw = self.rfile.read(length)
                action = self.path.rsplit("/", 1)[-1]
                key = self.headers.get("Idempotency-Key")
                fixture.requests.append((action, raw, key))
                if self.headers.get("Authorization") != "Bearer " + TOKEN:
                    self.reply(401, code=401)
                    return
                if self.path != "/api/test/compute-workers/v1/tasks/" + TASK + "/" + action:
                    fixture.errors.append("WRONG_PATH")
                    self.reply(404, code=404)
                    return
                count = sum(row[0] == action for row in fixture.requests)
                if action == "claim":
                    if raw != b"{}" or key != "TEST355-CLAIM-" + hashlib.sha256(TASK.encode()).hexdigest():
                        fixture.errors.append("CLAIM_WIRE_INVALID")
                    if fixture.mode == "claim_retry" and count < 3:
                        self.reply(503, code=503)
                        return
                    data = fixture.claim.copy()
                    if fixture.mode == "bad_input_hash":
                        data["inputHash"] = "0" * 64
                    elif fixture.mode == "bad_input_count":
                        raw_input = fixture_input().replace(b"count=32\n", b"count=31\n")
                        data["inputBytesBase64"] = base64.b64encode(raw_input).decode()
                        data["inputHash"] = hashlib.sha256(raw_input).hexdigest()
                    elif fixture.mode == "far_future":
                        data["completableAt"] = int(time.time() * 1000) + 61000
                    elif fixture.mode == "expired":
                        data["leaseExpiresAt"] = int(time.time() * 1000) - 1
                    self.reply(200, data)
                elif action == "complete":
                    request = json.loads(raw)
                    expected = independent_result(fixture_input())
                    expected_keys = {"specVersion", "inputHash", "resultHash", "resultArtifactBase64",
                                     "proofNonce", "proofTimestamp"}
                    valid = (set(request) == expected_keys and request["specVersion"] == SPEC
                             and request["inputHash"] == fixture.claim["inputHash"]
                             and request["proofNonce"] == fixture.claim["proofNonce"]
                             and type(request["proofTimestamp"]) is int
                             and abs(request["proofTimestamp"] - int(time.time() * 1000)) < 120000
                             and base64.b64decode(request["resultArtifactBase64"], validate=True) == expected
                             and request["resultHash"] == hashlib.sha256(expected).hexdigest())
                    if not valid:
                        fixture.errors.append("INDEPENDENT_RESULT_MISMATCH")
                        self.reply(422, code=422)
                        return
                    if key != "TEST355-COMPLETE-" + hashlib.sha256(raw).hexdigest():
                        fixture.errors.append("COMPLETE_KEY_MISMATCH")
                    if fixture.mode == "incomplete_response":
                        self.send_response(200)
                        self.send_header("Content-Length", "1000")
                        self.end_headers()
                        self.wfile.write(b"{")
                        self.wfile.flush()
                        self.close_connection = True
                        return
                    if fixture.mode == "slow_response":
                        self.send_response(200)
                        self.send_header("Content-Length", "1000")
                        self.end_headers()
                        try:
                            for _ in range(40):
                                self.wfile.write(b" ")
                                self.wfile.flush()
                                time.sleep(0.02)
                        except (OSError, ValueError):
                            pass
                        self.close_connection = True
                        return
                    if fixture.mode in ("lost_response", "lost_then_auth"):
                        if fixture.mode == "lost_then_auth" and count > 1:
                            self.reply(401, code=401)
                            return
                        self.close_connection = True
                        self.connection.shutdown(socket.SHUT_RDWR)
                        self.connection.close()
                        return
                    if fixture.mode == "complete_retry" and count < 3:
                        self.reply(503, code=503)
                        return
                    if fixture.mode in ("reject", "reject_release"):
                        self.reply(422, code=422)
                        return
                    if fixture.mode == "redirect":
                        self.send_response(307)
                        self.send_header("Location", fixture.url + "/forbidden")
                        self.send_header("Content-Length", "0")
                        self.end_headers()
                        return
                    data = {name: fixture.claim[name] for name in (
                        "executionKind", "specVersion", "runId", "executorId", "ownerId", "deviceId",
                        "instanceNo", "taskNo", "taskConfigId", "inputHash")}
                    data.update(resultHash=request["resultHash"], receiptNo="RCT-LOCAL-ONLY", completion={})
                    if fixture.mode == "wrong_confirmation":
                        data["deviceId"] = 999
                    if fixture.mode == "unsafe_confirmation":
                        data["receiptNo"] = TOKEN
                    if fixture.mode == "escaped_secret":
                        data["receiptNo"] = "RCT-" + TOKEN
                    self.reply(200, data)
                elif action == "release":
                    if raw != b"{}" or key != "TEST355-RELEASE-" + hashlib.sha256(TASK.encode()).hexdigest():
                        fixture.errors.append("RELEASE_WIRE_INVALID")
                    if fixture.mode == "reject_release":
                        self.reply(503, code=503)
                    else:
                        self.reply(200, dict(executionKind=KIND, taskNo=TASK, released=False))
                else:
                    fixture.errors.append("UNEXPECTED_ENDPOINT")
                    self.reply(404, code=404)

        self.server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        self.server.daemon_threads = True
        self.url = "http://127.0.0.1:" + str(self.server.server_port)
        self.thread = threading.Thread(target=self.server.serve_forever, kwargs={"poll_interval": 0.01}, daemon=True)

    def __enter__(self):
        self.thread.start()
        return self

    def __exit__(self, *args):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(2)


class WorkerWireTests(unittest.TestCase):
    def run_worker(self, fixture, extra_env=None, output=None):
        artifact_root = os.environ.get("UVEL_WORKER_TEST_ARTIFACT_ROOT")
        case_dir = Path(tempfile.mkdtemp(prefix="worker-local-", dir=artifact_root))
        output = output or case_dir / "evidence"
        # Pass only operating-system paths, not ambient business credentials.
        env = {name: os.environ[name] for name in ("SystemRoot", "SYSTEMROOT", "WINDIR", "PATH", "Path", "TEMP", "TMP")
               if name in os.environ}
        env.update(UVEL_TEST_WORKER_CREDENTIAL=TOKEN, PYTHONDONTWRITEBYTECODE="1")
        if extra_env:
            env.update(extra_env)
        result = subprocess.run([sys.executable, str(SCRIPT), "--base-url", fixture.url,
                                 "--task-no", TASK, "--output-dir", str(output)],
                                env=env, capture_output=True, timeout=12)
        # Store safe real stdout/stderr for both RED and GREEN, not the grant.
        (case_dir / "stdout.txt").write_bytes(result.stdout)
        (case_dir / "stderr.txt").write_bytes(result.stderr)
        manifest = json.loads((output / "manifest.json").read_text()) if (output / "manifest.json").exists() else {}
        self.assertTrue(TOKEN.encode() not in result.stdout + result.stderr, "Credential leaked to console")
        if output.exists():
            for file in output.iterdir():
                self.assertTrue(TOKEN.encode() not in file.read_bytes(), "Credential leaked to evidence")
        return result, manifest, output

    def test_confirms_actual_calculation_and_releases(self):
        with Fixture() as fixture:
            result, manifest, output = self.run_worker(fixture)
        self.assertEqual(result.returncode, 0, result.stderr.decode(errors="replace"))
        self.assertEqual(manifest["status"], "CONFIRMED")
        self.assertEqual((output / "input.bin").read_bytes(), fixture_input())
        self.assertEqual((output / "result.bin").read_bytes(), independent_result(fixture_input()))
        self.assertEqual([row[0] for row in fixture.requests], ["claim", "complete", "release"])
        self.assertEqual(fixture.errors, [])

    def test_retry_uses_identical_body_timestamp_and_key(self):
        for mode, action in (("complete_retry", "complete"), ("claim_retry", "claim")):
            with self.subTest(mode=mode), Fixture(mode) as fixture:
                result, manifest, output = self.run_worker(fixture)
            self.assertEqual(result.returncode, 0)
            self.assertEqual(manifest["status"], "CONFIRMED")
            rows = [row[1:] for row in fixture.requests if row[0] == action]
            self.assertEqual(len(rows), 3)
            self.assertEqual(rows, [rows[0]] * 3)
            self.assertEqual(fixture.errors, [])
            self.assertTrue((output / "complete-request.json").is_file())

    def test_lost_response_is_unknown_even_if_later_auth_rejects(self):
        for mode, attempts in (("lost_response", 3), ("lost_then_auth", 2), ("incomplete_response", 3)):
            with self.subTest(mode=mode), Fixture(mode) as fixture:
                result, manifest, _ = self.run_worker(fixture)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(manifest["status"], "UNKNOWN")
            rows = [row[1:] for row in fixture.requests if row[0] == "complete"]
            self.assertEqual(len(rows), attempts)
            self.assertEqual(rows, [rows[0]] * attempts)
            self.assertEqual(fixture.requests[-1][0], "release")

    def test_definitive_rejection_does_not_retry(self):
        with Fixture("reject") as fixture:
            result, manifest, _ = self.run_worker(fixture)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(manifest["status"], "REJECTED")
        self.assertEqual(sum(row[0] == "complete" for row in fixture.requests), 1)
        self.assertEqual(fixture.requests[-1][0], "release")

    def test_redirect_is_never_followed(self):
        with Fixture("redirect") as fixture:
            result, manifest, _ = self.run_worker(fixture)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(manifest["status"], "UNKNOWN")
        self.assertEqual([row[0] for row in fixture.requests], ["claim", "complete", "release"])

    def test_bad_input_expiry_or_excess_wait_never_submits(self):
        for mode in ("bad_input_hash", "bad_input_count", "far_future", "expired"):
            with self.subTest(mode=mode), Fixture(mode) as fixture:
                result, manifest, _ = self.run_worker(fixture)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(manifest["status"], "FAILED")
            self.assertEqual([row[0] for row in fixture.requests], ["claim", "release"])

    def test_confirmation_must_match_and_cannot_leak_credential(self):
        for mode in ("wrong_confirmation", "unsafe_confirmation", "escaped_secret"):
            with self.subTest(mode=mode), Fixture(mode) as fixture:
                result, manifest, _ = self.run_worker(fixture)
            self.assertNotEqual(result.returncode, 0)
            self.assertEqual(manifest["status"], "UNKNOWN")

    def test_release_failure_preserves_original_error(self):
        with Fixture("reject_release") as fixture:
            result, manifest, _ = self.run_worker(fixture)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(manifest["status"], "REJECTED")
        self.assertEqual(manifest["error"], "HTTP_422")
        self.assertEqual(manifest["release"]["status"], "FAILED")
        self.assertEqual(sum(row[0] == "release" for row in fixture.requests), 3)

    def test_existing_evidence_is_not_overwritten(self):
        root = Path(tempfile.mkdtemp(prefix="worker-existing-", dir=os.environ.get("UVEL_WORKER_TEST_ARTIFACT_ROOT")))
        (root / "keep.txt").write_text("preserved")
        with Fixture() as fixture:
            result, manifest, _ = self.run_worker(fixture, output=root)
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual((root / "keep.txt").read_text(), "preserved")
        self.assertEqual(fixture.requests, [])
        self.assertEqual(manifest, {})

    def test_invalid_credential_has_no_network_or_secret_output(self):
        with Fixture() as fixture:
            result, manifest, _ = self.run_worker(fixture, {"UVEL_TEST_WORKER_CREDENTIAL": "invalid"})
        self.assertNotEqual(result.returncode, 0)
        self.assertEqual(fixture.requests, [])
        self.assertEqual(manifest, {})

    def test_total_budget_survives_dripping_http_body(self):
        module = worker_module()
        root = Path(tempfile.mkdtemp(prefix="worker-deadline-", dir=os.environ.get("UVEL_WORKER_TEST_ARTIFACT_ROOT")))
        with Fixture("slow_response") as fixture, patch.object(module, "RUN_SECONDS", 0.6), \
                patch.object(module, "RELEASE_RESERVE_SECONDS", 0.2), \
                patch.object(module, "REQUEST_TIMEOUT_SECONDS", 0.15):
            started = time.monotonic()
            manifest = module.run(fixture.url, TASK, root / "evidence", TOKEN)
            elapsed = time.monotonic() - started
        self.assertLess(elapsed, 0.7, "Dripping wire exceeded the finite run budget")
        self.assertEqual(manifest["status"], "UNKNOWN")
        self.assertEqual(manifest["release"]["status"], "CONFIRMED")


class WorkerParserTests(unittest.TestCase):
    def setUp(self):
        self.worker = worker_module()

    def test_actual_values_not_nonce_or_timer_determine_result(self):
        claim = claim_data()
        raw = fixture_input()
        self.assertEqual(self.worker.compute_result(raw, claim), independent_result(raw))
        changed = raw[:raw.index(b"values=")] + b"values=" + b",".join([b"-1", b"1"] * 16) + b"\n"
        claim["inputHash"] = hashlib.sha256(changed).hexdigest()
        self.assertEqual(self.worker.compute_result(changed, claim), independent_result(changed))

    def test_noncanonical_input_rejected(self):
        raw = fixture_input()
        for bad in (raw.replace(b"\n", b"\r\n"), raw + b"\n", b"\xef\xbb\xbf" + raw,
                    raw.replace(b"count=32", b"count=031"),
                    raw[:raw.index(b"values=")] + b"values=" + b",".join([b"1001"] * 32) + b"\n",
                    raw[:raw.index(b"values=")] + b"values=" + b",".join([b"-0"] * 32) + b"\n",
                    raw[:raw.index(b"values=")] + b"values=" + b",".join([b"+1"] * 32) + b"\n"):
            with self.subTest(prefix=bad[:20]):
                claim = claim_data()
                claim["inputHash"] = hashlib.sha256(bad).hexdigest()
                with self.assertRaises(self.worker.WorkerError):
                    self.worker.compute_result(bad, claim)

    def test_input_binding_mismatch_rejected(self):
        for name, value in (("ownerId", 72), ("deviceId", 84), ("instanceNo", "OTHER"),
                            ("taskNo", "CTA-OTHER"), ("taskConfigId", "OTHER"), ("proofNonce", "b0" * 32)):
            claim = claim_data()
            claim[name] = value
            with self.subTest(name=name), self.assertRaises(self.worker.WorkerError):
                self.worker.compute_result(fixture_input(), claim)

    def test_url_credential_and_tls_boundaries(self):
        for bad in ("http://example.com", "http://localhost", "http://127.0.0.1.example.com",
                    "http://2130706433", "http://127.0.0.1@remote.example",
                    "https://user:password@example.com", "https://example.com/?token=x",
                    "https://example.com/#x", "https://example.com/api", "file:///tmp/test"):
            with self.subTest(url=bad), self.assertRaises(self.worker.WorkerError):
                self.worker.validate_base_url(bad)
        self.assertEqual(self.worker.validate_base_url("http://127.0.0.1:8110/"), "http://127.0.0.1:8110")
        self.assertEqual(self.worker.validate_base_url("http://[::1]:8110"), "http://[::1]:8110")
        self.assertEqual(self.worker.validate_base_url("https://test.example.com"), "https://test.example.com")
        import ssl
        context = ssl.create_default_context()
        self.assertTrue(context.check_hostname)
        self.assertEqual(context.verify_mode, ssl.CERT_REQUIRED)
        with patch.object(self.worker.ssl, "create_default_context", wraps=ssl.create_default_context) as factory:
            self.worker.Client("https://test.example.com", TOKEN, [])
            self.assertEqual(factory.call_count, 1)

    def test_duplicate_json_and_noncanonical_base64_rejected(self):
        for raw in (b'{"code":0,"code":1,"data":{}}', b'{} {}', b'\xef\xbb\xbf{}', b'{"n":NaN}'):
            with self.subTest(raw=raw), self.assertRaises(self.worker.WorkerError):
                self.worker.strict_json(raw)
        for value in ("AA==\n", "AB==", "_A==", "A==="):
            with self.subTest(value=value), self.assertRaises(self.worker.WorkerError):
                self.worker.decode_base64(value, 2048)

    def test_clock_jump_and_request_budget_rejected(self):
        claim = claim_data()
        claim.update(completableAt=1000000, leaseExpiresAt=1200000, proofExpiresAt=1200000)
        with patch.object(self.worker.time, "time", side_effect=[1000.0, 1005.0]), \
                patch.object(self.worker.time, "monotonic", side_effect=[50.0, 50.0, 50.01]), \
                self.assertRaises(self.worker.WorkerError):
            self.worker.wait_until_completable(claim, 100.0)
        with patch.object(self.worker.time, "time", return_value=1000.0), \
                patch.object(self.worker.time, "monotonic", return_value=50.0), \
                self.assertRaises(self.worker.WorkerError):
            self.worker.wait_until_completable(claim, 55.0)


if __name__ == "__main__":
    unittest.main()

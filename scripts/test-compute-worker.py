#!/usr/bin/env python3
"""One explicitly granted TEST vector job; standard library only."""
import argparse
import base64
import getpass
import hashlib
import http.client
import ipaddress
import json
import os
from pathlib import Path
import re
import socket
import ssl
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request


KIND = "TEST_DETERMINISTIC_V1"
SPEC = "UVEL_TEST_VECTOR_STATS_V1"
RUN_SECONDS = 60
RELEASE_RESERVE_SECONDS = 10
MAX_ATTEMPTS = 3
REQUEST_TIMEOUT_SECONDS = 10
MAX_RESPONSE_BYTES = 16384
BINDINGS = ("runId", "executorId", "ownerId", "deviceId", "instanceNo", "taskNo", "taskConfigId")


class WorkerError(Exception):
    def __init__(self, code, *, ambiguous=False, rejected=False):
        super().__init__(code)
        self.code = code
        self.ambiguous = ambiguous
        self.rejected = rejected


def require(condition, code):
    if not condition:
        raise WorkerError(code)


def safe_name(value, maximum, minimum=1):
    return (isinstance(value, str) and minimum <= len(value) <= maximum
            and re.fullmatch(r"[A-Za-z0-9._:-]+", value) is not None
            and not value.startswith("tw1_"))


def hex_digest(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def positive_integer(value):
    return type(value) is int and 0 < value <= 9223372036854775807


def strict_json(raw):
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            require(key not in result, "RESPONSE_JSON_INVALID")
            result[key] = value
        return result

    def bad_constant(value):
        raise WorkerError("RESPONSE_JSON_INVALID")

    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=unique_pairs, parse_constant=bad_constant)
    except (ValueError, UnicodeError, RecursionError):
        raise WorkerError("RESPONSE_JSON_INVALID") from None


def decode_base64(value, maximum):
    require(isinstance(value, str) and len(value) <= 4 * ((maximum + 2) // 3), "BASE64_INVALID")
    try:
        raw = base64.b64decode(value, validate=True)
    except (ValueError, UnicodeError):
        raise WorkerError("BASE64_INVALID") from None
    require(len(raw) <= maximum and base64.b64encode(raw).decode("ascii") == value, "BASE64_INVALID")
    return raw


def validate_base_url(value):
    require(isinstance(value, str) and value.isascii() and not re.search(r"[\s\\]", value), "BASE_URL_INVALID")
    try:
        parsed = urllib.parse.urlsplit(value)
        port = parsed.port
        host = parsed.hostname
    except ValueError:
        raise WorkerError("BASE_URL_INVALID") from None
    require(parsed.scheme in ("https", "http") and host and parsed.netloc,
            "BASE_URL_INVALID")
    require(parsed.username is None and parsed.password is None and not parsed.query
            and not parsed.fragment and parsed.path in ("", "/") and "%" not in parsed.netloc,
            "BASE_URL_INVALID")
    require(port is None or 1 <= port <= 65535, "BASE_URL_INVALID")
    if parsed.scheme == "http":
        try:
            loopback = ipaddress.ip_address(host).is_loopback
        except ValueError:
            loopback = False
        require(loopback, "REMOTE_HTTP_REFUSED")
    else:
        require(re.fullmatch(r"[A-Za-z0-9.:-]+", host) is not None, "BASE_URL_INVALID")
    return value.rstrip("/")


def read_credential():
    # Never accept secrets in argv or retain them in a child-process environment.
    credential = os.environ.pop("UVEL_TEST_WORKER_CREDENTIAL", None)
    if credential is None:
        require(sys.stdin.isatty(), "CREDENTIAL_REQUIRED")
        credential = getpass.getpass("Dedicated short-lived TEST worker credential: ")
    require(isinstance(credential, str)
            and re.fullmatch(r"tw1_[A-Za-z0-9_-]{43}", credential) is not None,
            "CREDENTIAL_INVALID")
    try:
        raw = base64.b64decode(credential[4:] + "=", altchars=b"-_", validate=True)
    except ValueError:
        raise WorkerError("CREDENTIAL_INVALID") from None
    require(len(raw) == 32 and base64.urlsafe_b64encode(raw).decode().rstrip("=") == credential[4:],
            "CREDENTIAL_INVALID")
    return credential


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        raise WorkerError("REDIRECT_REFUSED", ambiguous=True)


class RequestDeadline:
    # A socket inactivity timeout alone lets a dripping response run forever.
    # This timer closes only this request's socket across headers and body reads.
    def connect(self):
        self.deadline_expired = False
        self.deadline_socket = None
        self.deadline_timer = threading.Timer(self.timeout, self.abort_request)
        self.deadline_timer.daemon = True
        self.deadline_timer.start()
        try:
            super().connect()
            self.deadline_socket = self.sock
            if self.deadline_expired:
                self.abort_request()
                raise TimeoutError("Request deadline")
        except Exception:
            self.stop_deadline()
            raise

    def abort_request(self):
        self.deadline_expired = True
        request_socket = self.deadline_socket or self.sock
        if request_socket is not None:
            try:
                request_socket.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            request_socket.close()

    def stop_deadline(self):
        if getattr(self, "deadline_timer", None) is not None:
            self.deadline_timer.cancel()
        self.deadline_socket = None


class BoundedHTTPConnection(RequestDeadline, http.client.HTTPConnection):
    pass


class BoundedHTTPSConnection(RequestDeadline, http.client.HTTPSConnection):
    pass


def connection_factory(connection_class, connections):
    def create(host, **kwargs):
        connection = connection_class(host, **kwargs)
        connections.append(connection)
        return connection
    return create


class BoundedHTTPHandler(urllib.request.HTTPHandler):
    def __init__(self, connections):
        super().__init__()
        self.connections = connections

    def http_open(self, request):
        return self.do_open(connection_factory(BoundedHTTPConnection, self.connections), request)


class BoundedHTTPSHandler(urllib.request.HTTPSHandler):
    def __init__(self, connections, context):
        super().__init__(context=context)
        self.connections = connections
        self.context = context

    def https_open(self, request):
        return self.do_open(connection_factory(BoundedHTTPSConnection, self.connections), request, context=self.context)


class Client:
    def __init__(self, base_url, credential, attempts):
        self.base_url = validate_base_url(base_url)
        self.credential = credential
        self.attempts = attempts
        self.connections = []
        # Do not route a dedicated credential through environment proxy settings.
        self.opener = urllib.request.build_opener(
            urllib.request.ProxyHandler({}), NoRedirect(), BoundedHTTPHandler(self.connections),
            BoundedHTTPSHandler(self.connections, ssl.create_default_context()))

    def post(self, task_no, operation, body, key, deadline):
        ambiguous = False
        for attempt in range(1, MAX_ATTEMPTS + 1):
            remaining = deadline - time.monotonic()
            require(remaining > 0, "RUN_DEADLINE_EXCEEDED")
            url = self.base_url + "/api/test/compute-workers/v1/tasks/" + task_no + "/" + operation
            request = urllib.request.Request(url, data=body, method="POST", headers={
                "Content-Type": "application/json", "Accept": "application/json",
                "Authorization": "Bearer " + self.credential, "Idempotency-Key": key})
            entry = dict(operation=operation, attempt=attempt)
            self.attempts.append(entry)
            try:
                with self.opener.open(request, timeout=min(REQUEST_TIMEOUT_SECONDS, remaining)) as response:
                    entry["httpStatus"] = response.status
                    require(response.status == 200, "RESPONSE_STATUS_INVALID")
                    lengths = response.headers.get_all("Content-Length", [])
                    require(len(lengths) <= 1 and (not lengths or re.fullmatch(r"[0-9]{1,8}", lengths[0])),
                            "RESPONSE_LENGTH_INVALID")
                    expected_length = int(lengths[0]) if lengths else None
                    require(expected_length is None or expected_length <= MAX_RESPONSE_BYTES, "RESPONSE_TOO_LARGE")
                    raw = response.read(MAX_RESPONSE_BYTES + 1)
                    if expected_length is not None and len(raw) < expected_length:
                        raise http.client.IncompleteRead(raw, expected_length - len(raw))
                    require(len(raw) <= MAX_RESPONSE_BYTES, "RESPONSE_TOO_LARGE")
                    require(time.monotonic() <= deadline, "RUN_DEADLINE_EXCEEDED")
                envelope = strict_json(raw)
                require(self.credential.encode("ascii") not in raw
                        and self.credential not in json.dumps(envelope, ensure_ascii=True),
                        "RESPONSE_SECRET_REFLECTION")
                require(isinstance(envelope, dict) and type(envelope.get("code")) is int,
                        "RESPONSE_ENVELOPE_INVALID")
                if envelope["code"] != 0:
                    raise WorkerError("BUSINESS_REJECTED", ambiguous=ambiguous, rejected=True)
                require(isinstance(envelope.get("data"), dict), "RESPONSE_ENVELOPE_INVALID")
                entry["outcome"] = "RECEIVED"
                return envelope["data"]
            except urllib.error.HTTPError as error:
                status = error.code
                error.close()
                entry.update(httpStatus=status, outcome="HTTP_" + str(status))
                if not 500 <= status <= 599:
                    raise WorkerError("HTTP_" + str(status), ambiguous=ambiguous or 300 <= status <= 399,
                                      rejected=400 <= status <= 499) from None
                ambiguous = True
            except WorkerError as error:
                entry["outcome"] = error.code
                if error.rejected:
                    raise
                raise WorkerError(error.code, ambiguous=True) from None
            except (OSError, urllib.error.URLError, http.client.HTTPException):
                entry["outcome"] = "NETWORK_UNCONFIRMED"
                ambiguous = True
            finally:
                for connection in self.connections:
                    connection.stop_deadline()
                self.connections.clear()
            if attempt < MAX_ATTEMPTS:
                time.sleep(min(0.1, max(0, deadline - time.monotonic())))
        raise WorkerError("RETRIES_UNCONFIRMED", ambiguous=True)


def validate_claim(data, task_no):
    require(data.get("executionKind") == KIND and data.get("specVersion") == SPEC,
            "CLAIM_KIND_INVALID")
    require(safe_name(data.get("runId"), 96) and data["runId"].startswith("TEST"), "CLAIM_BINDING_INVALID")
    require(safe_name(data.get("executorId"), 32, 3), "CLAIM_BINDING_INVALID")
    require(positive_integer(data.get("ownerId")) and positive_integer(data.get("deviceId")),
            "CLAIM_BINDING_INVALID")
    require(safe_name(data.get("instanceNo"), 128) and safe_name(data.get("taskConfigId"), 64)
            and data.get("taskNo") == task_no, "CLAIM_BINDING_INVALID")
    require(hex_digest(data.get("proofNonce")) and hex_digest(data.get("inputHash")), "CLAIM_PROOF_INVALID")
    for name in ("proofExpiresAt", "leaseExpiresAt", "completableAt"):
        require(positive_integer(data.get(name)), "CLAIM_TIME_INVALID")
    raw = decode_base64(data.get("inputBytesBase64"), 2048)
    require(hashlib.sha256(raw).hexdigest() == data["inputHash"], "INPUT_HASH_INVALID")
    # Parse before retaining any untrusted response text as evidence.
    compute_result(raw, data)
    safe = {name: data[name] for name in BINDINGS + (
        "executionKind", "specVersion", "proofNonce", "proofExpiresAt", "leaseExpiresAt", "completableAt", "inputHash")}
    return safe, raw


def compute_result(raw, claim):
    require(isinstance(raw, bytes) and 0 < len(raw) <= 2048 and raw.isascii()
            and b"\r" not in raw and raw.endswith(b"\n"), "INPUT_FORMAT_INVALID")
    require(hashlib.sha256(raw).hexdigest() == claim["inputHash"], "INPUT_HASH_INVALID")
    lines = raw.decode("ascii").split("\n")
    expected = ["UVEL_TEST_VECTOR_STATS_INPUT_V1", "owner=" + str(claim["ownerId"]),
                "device=" + str(claim["deviceId"]), "instance=" + claim["instanceNo"],
                "task=" + claim["taskNo"], "taskConfig=" + claim["taskConfigId"],
                "nonce=" + claim["proofNonce"], "count=32"]
    require(len(lines) == 10 and lines[:8] == expected and lines[-1] == ""
            and lines[8].startswith("values="), "INPUT_FORMAT_INVALID")
    parts = lines[8][7:].split(",")
    require(len(parts) == 32, "INPUT_FORMAT_INVALID")
    require(all(re.fullmatch(r"0|-?[1-9][0-9]{0,3}", part) is not None for part in parts),
            "INPUT_FORMAT_INVALID")
    values = [int(part) for part in parts]
    require(all(-1000 <= value <= 1000 for value in values), "INPUT_FORMAT_INVALID")
    ordered = sorted(values)
    total = sum(values)
    squares = sum(value * value for value in values)
    result = ("UVEL_TEST_VECTOR_STATS_RESULT_V1\ninput=" + claim["inputHash"] + "\ncount=32\n"
              "sum=" + str(total) + "\nsumSquares=" + str(squares) + "\nsorted="
              + ",".join(map(str, ordered)) + "\n").encode("ascii")
    require(len(result) <= 1024, "RESULT_TOO_LARGE")
    return result


def wait_until_completable(claim, deadline):
    now = time.time()
    start = time.monotonic()
    wait = max(0, claim["completableAt"] / 1000 - now)
    require(wait <= 60 and wait + REQUEST_TIMEOUT_SECONDS <= deadline - start, "WAIT_LIMIT_EXCEEDED")
    require(min(claim["proofExpiresAt"], claim["leaseExpiresAt"]) / 1000 > now + wait + REQUEST_TIMEOUT_SECONDS,
            "LEASE_INSUFFICIENT")
    if wait:
        time.sleep(wait)
    require(abs((time.time() - now) - (time.monotonic() - start)) <= 2,
            "CLOCK_UNTRUSTED")
    timestamp = int(time.time() * 1000)
    require(timestamp >= claim["completableAt"]
            and timestamp < min(claim["proofExpiresAt"], claim["leaseExpiresAt"]), "CLOCK_UNTRUSTED")
    return timestamp


def json_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def write_new(path, raw):
    with path.open("xb") as stream:
        stream.write(raw)


def validate_confirmation(data, claim, result_hash):
    expected = {name: claim[name] for name in BINDINGS + ("executionKind", "specVersion", "inputHash")}
    expected["resultHash"] = result_hash
    require(all(type(data.get(name)) is type(value) and data[name] == value
                for name, value in expected.items()), "CONFIRMATION_MISMATCH")
    require(safe_name(data.get("receiptNo"), 128) and isinstance(data.get("completion"), dict),
            "CONFIRMATION_INVALID")
    expected["receiptNo"] = data["receiptNo"]
    return expected


def run(base_url, task_no, output_dir, credential):
    started = time.monotonic()
    deadline = started + RUN_SECONDS
    output = Path(output_dir)
    require(output.is_absolute() and not output.exists(), "EVIDENCE_DIRECTORY_MUST_BE_NEW")
    try:
        output.mkdir()
    except OSError:
        raise WorkerError("EVIDENCE_DIRECTORY_UNAVAILABLE") from None
    manifest = dict(executionKind=KIND, specVersion=SPEC, taskNo=task_no,
                    status="FAILED", error=None, startedAt=int(time.time() * 1000), attempts=[],
                    release=dict(status="NOT_ATTEMPTED"), commercialIncomeAccepted=False)
    client = Client(base_url, credential, manifest["attempts"])
    claim_attempted = complete_sent = False
    try:
        task_hash = hashlib.sha256(task_no.encode("ascii")).hexdigest()
        claim_attempted = True
        data = client.post(task_no, "claim", b"{}", "TEST355-CLAIM-" + task_hash,
                           deadline - RELEASE_RESERVE_SECONDS)
        claim, raw = validate_claim(data, task_no)
        manifest["binding"] = claim
        write_new(output / "claim.json", json_bytes(claim))
        write_new(output / "input.bin", raw)
        result = compute_result(raw, claim)
        result_hash = hashlib.sha256(result).hexdigest()
        write_new(output / "result.bin", result)
        manifest["resultHash"] = result_hash
        timestamp = wait_until_completable(claim, deadline - RELEASE_RESERVE_SECONDS)
        body = json_bytes(dict(specVersion=SPEC, inputHash=claim["inputHash"], resultHash=result_hash,
                               resultArtifactBase64=base64.b64encode(result).decode("ascii"),
                               proofNonce=claim["proofNonce"], proofTimestamp=timestamp))
        key = "TEST355-COMPLETE-" + hashlib.sha256(body).hexdigest()
        write_new(output / "complete-request.json", body)
        write_new(output / "complete-key.txt", key.encode("ascii"))
        complete_sent = True
        data = client.post(task_no, "complete", body, key, deadline - RELEASE_RESERVE_SECONDS)
        confirmation = validate_confirmation(data, claim, result_hash)
        write_new(output / "complete-confirmation.json", json_bytes(confirmation))
        manifest.update(status="CONFIRMED", confirmation=confirmation)
    except WorkerError as error:
        manifest["error"] = error.code
        if complete_sent and (error.ambiguous or not error.rejected):
            manifest["status"] = "UNKNOWN"
        elif error.rejected:
            manifest["status"] = "REJECTED"
    except KeyboardInterrupt:
        manifest.update(status="UNKNOWN" if complete_sent else "FAILED", error="INTERRUPTED")
    except Exception:
        # Never print a traceback that might include an Authorization request.
        manifest.update(status="UNKNOWN" if complete_sent else "FAILED", error="UNEXPECTED_ERROR")
    finally:
        if claim_attempted:
            try:
                release = client.post(task_no, "release", b"{}", "TEST355-RELEASE-" + task_hash, deadline)
                require(release.get("executionKind") == KIND and release.get("taskNo") == task_no
                        and type(release.get("released")) is bool, "RELEASE_CONFIRMATION_INVALID")
                manifest["release"] = dict(status="CONFIRMED", released=release["released"])
            except WorkerError as error:
                manifest["release"] = dict(status="FAILED", error=error.code)
            except (Exception, KeyboardInterrupt):
                manifest["release"] = dict(status="FAILED", error="RELEASE_UNCONFIRMED")
        manifest["finishedAt"] = int(time.time() * 1000)
        manifest["elapsedSeconds"] = round(time.monotonic() - started, 3)
        write_new(output / "manifest.json", json_bytes(manifest))
    return manifest


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--base-url", required=True, help="Verified TEST HTTPS origin, or approved literal loopback tunnel")
    parser.add_argument("--task-no", required=True, help="One exact task bound to a fresh short-lived grant")
    parser.add_argument("--output-dir", required=True, help="Absolute new evidence directory; parent must exist")
    args = parser.parse_args()
    try:
        base_url = validate_base_url(args.base_url)
        require(safe_name(args.task_no, 96) and args.task_no not in (".", ".."), "TASK_NO_INVALID")
        output = Path(args.output_dir)
        require(output.is_absolute() and not output.exists(), "EVIDENCE_DIRECTORY_MUST_BE_NEW")
        credential = read_credential()
        result = run(base_url, args.task_no, args.output_dir, credential)
        summary = dict(status=result["status"], error=result["error"], releaseStatus=result["release"]["status"])
        print(json.dumps(summary, separators=(",", ":")))
        return 0 if result["status"] == "CONFIRMED" and result["release"]["status"] == "CONFIRMED" else 1
    except WorkerError as error:
        print(json.dumps(dict(status="FAILED", error=error.code), separators=(",", ":")), file=sys.stderr)
        return 1
    except (OSError, KeyboardInterrupt):
        print('{"status":"FAILED","error":"LOCAL_IO_OR_INTERRUPT"}', file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())

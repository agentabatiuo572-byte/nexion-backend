#!/usr/bin/env python3
"""Dedicated TEST BGE-M3 worker. A sent, uncertain economic request is never replayed."""
import argparse
import base64
import hashlib
import http.client
import ipaddress
import json
import math
import os
from pathlib import Path
import re
import signal
import socket
import sqlite3
import stat
import struct
import sys
import threading
import time
import urllib.parse
import uuid

PREFIX = "/api/test/cloud-executors/v1"
KIND, SPEC = "TEST_BGE_M3_V1", "UVEL_TEST_BGE_M3_V1"
HALTED = 78
IDENTITY = ("ownerId", "deviceId", "instanceNo", "taskConfigId", "executorId", "model", "modelDigest")
STOP = threading.Event()


class Halt(Exception):
    """Only fixed codes, never raw response/exception bodies, reach service logs."""


class NotSent(Halt):
    """TCP connect failed before HTTP request() began."""


class Rejected(Halt):
    """A fully received canonical rejection, never a computation confirmation."""
    def __init__(self, code, http_status=None, error_code=None):
        super().__init__(code)
        self.http_status = http_status if type(http_status) is int and 400 <= http_status < 500 else None
        self.error_code = error_code if isinstance(error_code, str) and re.fullmatch(r"TEST_CLOUD_[A-Z0-9_]{1,80}", error_code) else None


def require(condition, code):
    if not condition:
        raise Halt(code)


def json_bytes(value):
    return json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode("utf-8")


def sha256(value):
    return hashlib.sha256(value).hexdigest()


def strict_json(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, "JSON_DUPLICATE_KEY")
            result[key] = value
        return result
    def invalid(_):
        raise Halt("JSON_NONFINITE")
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=pairs, parse_constant=invalid)
    except (ValueError, UnicodeError, RecursionError):
        raise Halt("JSON_INVALID") from None


def digest(value):
    return isinstance(value, str) and re.fullmatch(r"sha256:[a-f0-9]{64}", value) is not None


def hash64(value):
    return isinstance(value, str) and re.fullmatch(r"[a-f0-9]{64}", value) is not None


def loopback_url(value):
    try:
        parsed = urllib.parse.urlsplit(value)
        valid = (parsed.scheme == "http" and ipaddress.ip_address(parsed.hostname).is_loopback
                 and parsed.port is not None and 0 < parsed.port <= 65535
                 and parsed.path in ("", "/") and not parsed.query and not parsed.fragment
                 and parsed.username is None and parsed.password is None
                 and value.isascii() and re.search(r"[\s\\%]", value) is None)
    except (ValueError, TypeError):
        valid = False
    require(valid, "LOOPBACK_ORIGIN_REQUIRED")
    return parsed.hostname, parsed.port


class Config:
    def __init__(self, env, fleet=False):
        require(env.get("UVEL_CLOUD_WORKER_ENABLED") == "true"
                and env.get("UVEL_CLOUD_WORKER_SCOPE") == "TEST", "WORKER_DISABLED")
        self.backend = loopback_url(env.get("UVEL_CLOUD_BACKEND_URL", "http://127.0.0.1:8110"))
        self.ollama = loopback_url(env.get("UVEL_CLOUD_OLLAMA_URL", "http://127.0.0.1:11435"))
        self.binding = {}
        if fleet:
            self.interval = int(env.get("UVEL_CLOUD_POLL_SECONDS", "60"))
            require(60 <= self.interval <= 3600, "POLL_INTERVAL_INVALID")
            return
        for key, name in (("ownerId", "OWNER_ID"), ("deviceId", "DEVICE_ID"), ("instanceNo", "INSTANCE_NO"),
                          ("taskConfigId", "TASK_CONFIG_ID"), ("executorId", "EXECUTOR_ID"),
                          ("model", "MODEL"), ("modelDigest", "MODEL_DIGEST")):
            value = env.get("UVEL_CLOUD_" + name, "")
            if key in ("ownerId", "deviceId"):
                require(re.fullmatch(r"[1-9][0-9]{0,18}", value) is not None, "CONFIG_BINDING_INVALID")
                value = int(value)
                require(value <= 9223372036854775807, "CONFIG_BINDING_INVALID")
            else:
                require(re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", value) is not None, "CONFIG_BINDING_INVALID")
            self.binding[key] = value
        require(self.binding["model"] == "bge-m3:567m" and digest(self.binding["modelDigest"]), "MODEL_CONFIG_INVALID")
        self.interval = int(env.get("UVEL_CLOUD_POLL_SECONDS", "60"))
        require(60 <= self.interval <= 3600, "POLL_INTERVAL_INVALID")


def credential(env):
    directory = env.get("CREDENTIALS_DIRECTORY", "")
    require(directory and Path(directory).is_absolute(), "LOAD_CREDENTIAL_REQUIRED")
    path = Path(directory) / "cloud-worker-token"
    require(not path.is_symlink() and path.is_file(), "CREDENTIAL_FILE_INVALID")
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_mode & 0o022 == 0 and info.st_size <= 64, "CREDENTIAL_FILE_INVALID")
    token = path.read_text(encoding="ascii").strip()
    require(re.fullmatch(r"ct1_[A-Za-z0-9_-]{43}", token) is not None, "CREDENTIAL_INVALID")
    raw = base64.urlsafe_b64decode(token[4:] + "=")
    require(len(raw) == 32 and base64.urlsafe_b64encode(raw).decode().rstrip("=") == token[4:], "CREDENTIAL_INVALID")
    return token


class Http:
    def __init__(self, address, token=None, timeout=300, executor=None):
        self.address, self.token, self.timeout, self.executor = address, token, timeout, executor

    def request(self, method, path, body=None, key=None):
        timeout = self.timeout if path.endswith("/complete") or path == "/api/embed" else 10
        connection = http.client.HTTPConnection(*self.address, timeout=timeout)
        timer = None
        try:
            try:
                connection.connect()
            except (OSError, TimeoutError):
                raise NotSent("CONNECT_NOT_SENT") from None
            # Bound the whole response, including a server dripping bytes below socket timeout.
            sock = connection.sock
            def abort():
                try:
                    sock.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
            timer = threading.Timer(timeout, abort)
            timer.daemon = True
            timer.start()
            headers = {"Accept": "application/json", "Content-Type": "application/json"}
            if self.token:
                headers["Authorization"] = "Bearer " + self.token
            if self.executor:
                headers["X-UVEL-Executor-Id"] = self.executor
            if key:
                headers["Idempotency-Key"] = key
            connection.request(method, path, None if body is None else json_bytes(body), headers)
            response = connection.getresponse()
            raw = response.read(1024 * 1024 + 1)
            require(len(raw) <= 1024 * 1024, "RESPONSE_TOO_LARGE")
            data = strict_json(raw)
            if not 200 <= response.status < 300:
                if 400 <= response.status < 500 and isinstance(data, dict) and type(data.get("code")) is int and data["code"] != 0:
                    raise Rejected("BACKEND_REJECTED_" + str(response.status), response.status, data.get("message"))
                raise Halt("HTTP_OUTCOME_UNKNOWN")
            return data
        except (OSError, TimeoutError, http.client.HTTPException):
            raise Halt("HTTP_OUTCOME_UNKNOWN") from None
        finally:
            if timer:
                timer.cancel()
            connection.close()


class Journal:
    def __init__(self, path):
        self.guard = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS state(id INTEGER PRIMARY KEY CHECK(id=1), value TEXT NOT NULL);
            INSERT OR IGNORE INTO state VALUES(1,'{"phase":"IDLE"}');
            CREATE TABLE IF NOT EXISTS calls(id INTEGER PRIMARY KEY, operation TEXT NOT NULL,
              request_key TEXT NOT NULL, task_no TEXT, request_hash TEXT NOT NULL, outcome TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS jobs(task_no TEXT PRIMARY KEY, result_hash TEXT, receipt_no TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS rejected_retries(task_no TEXT PRIMARY KEY, original_state_json TEXT NOT NULL,
              original_state_hash TEXT NOT NULL);
        """)
        self.db.commit()

    def state(self):
        with self.guard:
            return json.loads(self.db.execute("SELECT value FROM state WHERE id=1").fetchone()[0])

    def set(self, value):
        with self.guard, self.db:
            self.db.execute("UPDATE state SET value=? WHERE id=1", (json_bytes(value).decode(),))

    def intent(self, operation, key, task, body, state=None):
        with self.guard, self.db:
            cursor = self.db.execute("INSERT INTO calls(operation,request_key,task_no,request_hash,outcome) VALUES(?,?,?,?, 'INTENT')",
                                     (operation, key, task, sha256(json_bytes(body))))
            if state is not None:
                self.db.execute("UPDATE state SET value=? WHERE id=1", (json_bytes(state).decode(),))
            return cursor.lastrowid

    def outcome(self, call, outcome):
        with self.guard, self.db:
            self.db.execute("UPDATE calls SET outcome=? WHERE id=?", (outcome, call))

    def require_unsent_claim(self, state):
        complete_key = "BGE-COMPLETE-" + sha256(state["taskNo"].encode())
        with self.guard:
            attempted = self.db.execute("SELECT 1 FROM calls WHERE operation='COMPLETE' AND (task_no=? OR request_key=?) LIMIT 1",
                                        (state["taskNo"], complete_key)).fetchone()
            require(attempted is None, "CLAIM_COMPLETE_ALREADY_ATTEMPTED")
            self.require_next_confirmed(state)

    def require_next_confirmed(self, state):
        key = state.get("nextKey", "")
        require(re.fullmatch(r"BGE-NEXT-[a-f0-9]{32}", key) is not None, "CLAIM_NEXT_KEY_INVALID")
        with self.guard:
            calls = self.db.execute("SELECT request_hash,outcome FROM calls WHERE operation='NEXT' AND request_key=? ORDER BY id", (key,)).fetchall()
            require(calls and calls[-1] == (sha256(json_bytes({})), "CONFIRMED")
                    and all(row[0] == sha256(json_bytes({})) and row[1] in ("NOT_SENT", "CONFIRMED") for row in calls),
                    "CLAIM_NEXT_NOT_CONFIRMED")

    def require_rejected_retry(self, state):
        require(hash64(state.get("resultHash")), "REJECTED_RESULT_HASH_REQUIRED")
        key = "BGE-COMPLETE-" + sha256(state["taskNo"].encode())
        with self.guard:
            calls = self.db.execute("SELECT task_no,request_key,outcome FROM calls WHERE operation='COMPLETE' AND (task_no=? OR request_key=?)",
                                    (state["taskNo"], key)).fetchall()
            used = self.db.execute("SELECT 1 FROM calls WHERE operation='REJECTED_RETRY' AND (task_no=? OR request_key=?) LIMIT 1",
                                   (state["taskNo"], key)).fetchone()
            consumed = self.db.execute("SELECT 1 FROM rejected_retries WHERE task_no=?", (state["taskNo"],)).fetchone()
            require(used is None and consumed is None and not state.get("rejectedRetry"), "REJECTED_RETRY_BUDGET_USED")
            require(len(calls) == 1 and calls[0] == (state["taskNo"], key, "REJECTED"), "STOP_PENDING_RECONCILIATION")
            self.require_next_confirmed(state)

    def reserve_rejected_retry(self, state):
        with self.guard, self.db:
            require(self.state() == state, "CLAIM_STATE_CHANGED")
            self.require_rejected_retry(state)
            resumed = {**state, "phase": "REJECTED_RETRY_INTENT", "rejectedRetry": True, "rejectionRecoveryOrigin": state}
            original = json_bytes(state)
            self.db.execute("INSERT INTO rejected_retries VALUES(?,?,?)", (state["taskNo"], original.decode(), sha256(original)))
            self.db.execute("INSERT INTO calls(operation,request_key,task_no,request_hash,outcome) VALUES('REJECTED_RETRY',?,?,?,'AUTHORIZED')",
                            ("BGE-COMPLETE-" + sha256(state["taskNo"].encode()), state["taskNo"], sha256(json_bytes(state))))
            self.db.execute("UPDATE state SET value=? WHERE id=1", (json_bytes(resumed).decode(),))
        return resumed

    def confirm(self, state, receipt):
        with self.guard, self.db:
            self.db.execute("INSERT OR IGNORE INTO jobs VALUES(?,?,?)", (state["taskNo"], state["resultHash"], receipt["receiptNo"]))
            require(self.db.execute("SELECT result_hash,receipt_no FROM jobs WHERE task_no=?", (state["taskNo"],)).fetchone()
                    == (state["resultHash"], receipt["receiptNo"]), "LOCAL_RECEIPT_CONFLICT")
            self.db.execute("UPDATE state SET value=? WHERE id=1", (json_bytes({"phase": "IDLE", "binding": state["binding"]}).decode(),))


def envelope(response):
    require(isinstance(response, dict) and type(response.get("code")) is int
            and response["code"] == 0 and isinstance(response.get("data"), dict), "ENVELOPE_INVALID")
    return response["data"]


def binding(data, config):
    require(data.get("executionKind") == KIND and data.get("specVersion") == SPEC
            and data.get("deploymentScope") == "TEST" and data.get("serverCanonical") is True, "SCOPE_INVALID")
    require(all(type(data.get(k)) is type(v) and data[k] == v for k, v in config.binding.items()), "BINDING_INVALID")
    return {k: data[k] for k in IDENTITY}


def claim(data, config):
    binding(data, config)
    require(re.fullmatch(r"CTA-[A-Fa-f0-9]{32}", data.get("taskNo", "")) is not None, "TASK_INVALID")
    require(hash64(data.get("claimToken")) and hash64(data.get("proofNonce")), "CLAIM_INVALID")
    texts = data.get("inputTexts")
    require(isinstance(texts, list) and 1 <= len(texts) <= 8
            and all(isinstance(t, str) and 0 < len(t) <= 2048 for t in texts), "INPUT_INVALID")
    require(data.get("inputHash") == sha256(json_bytes(texts)) and data.get("outputDimensions") == 1024, "INPUT_HASH_INVALID")
    now = int(time.time() * 1000)
    require(all(type(data.get(k)) is int and data[k] > now for k in ("proofExpiresAt", "leaseExpiresAt"))
            and type(data.get("completableAt")) is int
            and data["completableAt"] < min(data["proofExpiresAt"], data["leaseExpiresAt"]), "LEASE_INVALID")
    return data


def artifact(embeddings, count):
    require(isinstance(embeddings, list) and len(embeddings) == count, "OUTPUT_COUNT_INVALID")
    values = []
    for row in embeddings:
        require(isinstance(row, list) and len(row) == 1024, "OUTPUT_DIMENSION_INVALID")
        require(all(type(x) in (int, float) and math.isfinite(x) for x in row)
                and any(x != 0 for x in row), "OUTPUT_NUMBER_INVALID")
        values.extend(row)
    try:
        raw = struct.pack("<" + "f" * len(values), *values)
    except (OverflowError, struct.error):
        raise Halt("OUTPUT_FLOAT32_INVALID") from None
    packed = struct.unpack("<" + "f" * len(values), raw)
    require(all(math.isfinite(x) for x in packed)
            and all(any(packed[offset:offset + 1024]) for offset in range(0, len(packed), 1024)), "OUTPUT_FLOAT32_INVALID")
    return raw


def verify_model(ollama, task):
    tags = ollama.request("GET", "/api/tags")
    models = tags.get("models") if isinstance(tags, dict) else None
    require(isinstance(models, list), "MODEL_TAGS_INVALID")
    matches = [m for m in models if isinstance(m, dict) and m.get("name") == task["model"]]
    require(len(matches) == 1, "MODEL_TAG_MISSING")
    actual = matches[0].get("digest", "")
    require(isinstance(actual, str), "MODEL_TAGS_INVALID")
    actual = actual if actual.startswith("sha256:") else "sha256:" + actual
    require(actual == task["modelDigest"], "MODEL_DIGEST_CHANGED")


def infer(ollama, task):
    verify_model(ollama, task)
    data = ollama.request("POST", "/api/embed", {"model": task["model"], "input": task["inputTexts"], "truncate": False})
    require(isinstance(data, dict) and data.get("model") == task["model"], "OUTPUT_MODEL_INVALID")
    raw = artifact(data.get("embeddings"), len(task["inputTexts"]))
    verify_model(ollama, task)
    return raw


class Worker:
    def __init__(self, config, backend, ollama, journal, stop=STOP, parent_stop=None):
        self.config, self.backend, self.ollama, self.journal, self.stop = config, backend, ollama, journal, stop
        self.parent_stop = parent_stop
        self.heartbeat_stop = threading.Event()
        self.heartbeat_thread = None
        self.heartbeat_error = None

    def mutate(self, operation, path, body, key, state=None, validator=None):
        if operation in ("NEXT", "COMPLETE"):
            require(not self.stopping(), "STOP_BEFORE_ECONOMIC_REQUEST")
        call = self.journal.intent(operation, key, (state or {}).get("taskNo"), body, state)
        try:
            data = envelope(self.backend.request("POST", PREFIX + path, body, key))
            if validator:
                validator(data)
        except NotSent:
            self.journal.outcome(call, "NOT_SENT")
            raise
        except Rejected:
            self.journal.outcome(call, "REJECTED")
            raise
        except Halt:
            self.journal.outcome(call, "UNKNOWN")
            raise
        self.journal.outcome(call, "CONFIRMED")
        return data

    def stopping(self):
        return self.stop.is_set() or (self.parent_stop is not None and self.parent_stop.is_set())

    def heartbeat(self, idle_only=False, expected_task=None):
        verify_model(self.ollama, self.config.binding)
        def validate(data):
            require(data.get("serverCanonical") is True and type(data.get("heartbeatAt")) is int
                    and abs(int(time.time() * 1000) - data["heartbeatAt"]) <= 120000
                    and all(type(data.get(k)) is type(self.config.binding[k]) and data[k] == self.config.binding[k]
                            for k in ("ownerId", "deviceId", "instanceNo", "executorId")), "HEARTBEAT_INVALID")
            require(not idle_only or ("activeTaskNo" in data and data["activeTaskNo"] is None),
                    "HEARTBEAT_ONLY_ACTIVE_TASK_BLOCKED")
            require(expected_task is None or data.get("activeTaskNo") == expected_task,
                    "HEARTBEAT_TASK_INVALID")
        return self.mutate("HEARTBEAT", "/heartbeat", {}, "BGE-HB-" + uuid.uuid4().hex, validator=validate)

    def start_heartbeat(self, fatal=False):
        try:
            self.heartbeat(idle_only=fatal)
        except Halt:
            if fatal:
                raise
            print("HEARTBEAT_UNCONFIRMED", flush=True)
        def run():
            while not self.heartbeat_stop.wait(10):
                try:
                    self.heartbeat(idle_only=fatal)
                except Exception as error:
                    if fatal:
                        self.heartbeat_error = error if isinstance(error, Halt) else Halt("HEARTBEAT_UNEXPECTED_STOP")
                        self.stop.set()
                        break
                    # Heartbeats do not settle funds. A fresh synchronous heartbeat gates completion.
                    print("HEARTBEAT_UNCONFIRMED", flush=True)
        self.heartbeat_thread = threading.Thread(target=run, daemon=True)
        self.heartbeat_thread.start()

    def receipt_status(self, state):
        data = envelope(self.backend.request("GET", PREFIX + "/tasks/" + state["taskNo"] + "/receipt"))
        binding(data, self.config)
        require(data.get("taskNo") == state["taskNo"] and data.get("inputHash") == state["inputHash"], "RECEIPT_BINDING_INVALID")
        require(type(data.get("found")) is bool, "RECEIPT_STATUS_INVALID")
        return data

    def read_receipt(self, state):
        data = self.receipt_status(state)
        return self.positive_receipt(data)

    def positive_receipt(self, data):
        require(data.get("found") is True and data.get("status") == "COMPLETED" and hash64(data.get("resultHash"))
                and isinstance(data.get("receiptNo"), str) and re.fullmatch(r"CTR-[A-Fa-f0-9]{32}", data["receiptNo"]),
                "STOP_PENDING_RECONCILIATION")
        return data

    def receipt(self, state):
        data = self.read_receipt(state)
        require(hash64(state.get("resultHash")) and data["resultHash"] == state["resultHash"], "RECEIPT_RESULT_INVALID")
        return data

    def recover_claimed(self, state, allow_execution, on_resume, rejected=False):
        require(state.get("binding") == self.config.binding, "JOURNAL_BINDING_CHANGED")
        data = self.receipt_status(state)
        if data["found"]:
            self.positive_receipt(data)
            require(data["receiptNo"] == "CTR-" + state["taskNo"][-32:], "RECEIPT_NUMBER_INVALID")
            require(not rejected or hash64(state.get("resultHash")), "RECEIPT_RESULT_INVALID")
            require("resultHash" not in state or state["resultHash"] == data["resultHash"], "RECEIPT_RESULT_INVALID")
            self.journal.confirm({**state, "resultHash": data["resultHash"]}, data)
            print("RECEIPT_RECONCILED", flush=True)
            return True
        require(allow_execution, "STOP_PENDING_RECONCILIATION")
        if rejected:
            self.journal.require_rejected_retry(state)
        else:
            self.journal.require_unsent_claim(state)
        require(data.get("status") in ("CLAIMED", "RUNNING") and data.get("resultHash") is None
                and data.get("receiptNo") is None, "CLAIM_REMOTE_RESULT_UNCONFIRMED")
        task = claim(envelope(self.backend.request("GET", PREFIX + "/tasks/" + state["taskNo"] + "/manifest", key=state["nextKey"])), self.config)
        require(task.get("resumeAllowed") is True and task.get("nextKeyHash") == sha256(state["nextKey"].encode())
                and task["taskNo"] == state["taskNo"] and task["inputHash"] == state["inputHash"], "CLAIM_RESUME_MANIFEST_INVALID")
        require(self.journal.state() == state, "CLAIM_STATE_CHANGED")
        if on_resume:
            on_resume()
        if rejected:
            state = self.journal.reserve_rejected_retry(state)
        self.execute(task, state)
        return True

    def recover(self, claimed_execution=True, on_resume=None):
        state = self.journal.state()
        if state["phase"] in ("IDLE", "NEXT_NOT_SENT"):
            return False
        if state["phase"] == "CLAIMED":
            return self.recover_claimed(state, claimed_execution, on_resume)
        if state["phase"] in ("REJECTED_COMPLETE", "UNKNOWN_COMPLETE"):
            return self.recover_claimed(state, claimed_execution, on_resume, rejected=True)
        require(state["phase"] in ("COMPLETE_INTENT", "REJECTED_RETRY_INTENT"), "STOP_PENDING_RECONCILIATION")
        require(state.get("binding") == self.config.binding, "JOURNAL_BINDING_CHANGED")
        data = self.receipt(state)  # A negative read never authorizes a second complete or next.
        self.journal.confirm(state, data)
        print("RECEIPT_RECONCILED", flush=True)
        return True

    def one(self):
        old = self.journal.state()
        require(old["phase"] in ("IDLE", "NEXT_NOT_SENT"), "STOP_PENDING_RECONCILIATION")
        require(old.get("binding", self.config.binding) == self.config.binding, "JOURNAL_BINDING_CHANGED")
        key = old.get("nextKey") or "BGE-NEXT-" + uuid.uuid4().hex
        state = {"phase": "NEXT_INTENT", "nextKey": key, "binding": self.config.binding}
        try:
            data = self.mutate("NEXT", "/next-task", {}, key, state, lambda d: binding(d, self.config))
        except NotSent:
            self.journal.set({**state, "phase": "NEXT_NOT_SENT"})
            return "NOT_SENT"
        except Halt:
            self.journal.set({**state, "phase": "UNKNOWN_NEXT"})
            raise
        if data.get("idle") is True:
            self.journal.set({"phase": "IDLE", "binding": self.config.binding})
            return "IDLE"
        if data.get("status") == "COMPLETED":
            # A replayed, already completed next key must never execute or complete again.
            require(re.fullmatch(r"CTA-[A-Fa-f0-9]{32}", data.get("taskNo", "")) is not None
                    and hash64(data.get("inputHash")), "TASK_INVALID")
            state.update(phase="ALREADY_COMPLETED", taskNo=data["taskNo"], inputHash=data["inputHash"])
            self.journal.set(state)
            receipt = self.read_receipt(state)
            state["resultHash"] = receipt["resultHash"]
            self.journal.confirm(state, receipt)
            print("ALREADY_COMPLETED_RECEIPT", flush=True)
            return "ALREADY_COMPLETED"
        require(data.get("status") in ("CLAIMED", "RUNNING"), "TASK_STATUS_INVALID")
        task = claim(data, self.config)
        state.update(phase="CLAIMED", taskNo=task["taskNo"], inputHash=task["inputHash"])
        self.journal.set(state)
        return self.execute(task, state)

    def execute(self, task, state):
        require(not self.stopping(), "STOP_BEFORE_INFERENCE")
        if self.heartbeat_thread is None:
            self.start_heartbeat()
        raw = infer(self.ollama, task)
        require("resultHash" not in state or state["resultHash"] == sha256(raw), "CLAIM_RESULT_HASH_CHANGED")
        state["resultHash"] = sha256(raw)
        self.journal.set(state)
        remaining = max(0, (task["completableAt"] - int(time.time() * 1000)) / 1000)
        require(not self.stop.wait(remaining), "STOP_BEFORE_COMPLETE")
        self.heartbeat(expected_task=task["taskNo"])  # Recheck identity and active task after model execution.
        require(not self.stopping(), "STOP_BEFORE_COMPLETE")
        require(int(time.time() * 1000) < min(task["proofExpiresAt"], task["leaseExpiresAt"]), "LEASE_EXPIRED_BEFORE_COMPLETE")
        body = {"specVersion": SPEC, "inputHash": task["inputHash"], "modelDigest": task["modelDigest"],
                "resultArtifactBase64": base64.b64encode(raw).decode("ascii"), "resultHash": state["resultHash"],
                "proofNonce": task["proofNonce"], "proofTimestamp": int(time.time() * 1000), "claimToken": task["claimToken"]}
        state["phase"] = "COMPLETE_INTENT"
        try:
            self.mutate("COMPLETE", "/tasks/" + task["taskNo"] + "/complete", body,
                        "BGE-COMPLETE-" + sha256(task["taskNo"].encode()), state)
        except NotSent:
            self.journal.set({**state, "phase": "COMPLETE_NOT_SENT"})
            raise Halt("STOP_COMPLETE_NOT_SENT") from None
        except Rejected as error:
            state.update(phase="REJECTED_COMPLETE", rejection={"httpStatus": error.http_status, "errorCode": error.error_code})
            self.journal.set(state)
            data = self.receipt(state)
            self.journal.confirm(state, data)
            print("RECEIPT_RECONCILED", flush=True)
            return "CONFIRMED"
        except Halt:
            self.journal.set({**state, "phase": "UNKNOWN_COMPLETE"})
            # May positively confirm a lost response; never submit complete again.
            data = self.receipt(state)
            self.journal.confirm(state, data)
            print("RECEIPT_RECONCILED", flush=True)
            return "CONFIRMED"
        data = self.receipt(state)
        self.journal.confirm(state, data)
        print("COMPUTATION_CONFIRMED", flush=True)
        return "CONFIRMED"

    def run(self, once=False, heartbeat_only=False):
        require(not (once and heartbeat_only), "WORKER_MODE_INVALID")
        if heartbeat_only:
            current = self.journal.state()
            require(current["phase"] == "IDLE" and current.get("binding") == self.config.binding,
                    "HEARTBEAT_ONLY_CONFIRMED_IDLE_REQUIRED")
        try:
            if not heartbeat_only:
                recovered = self.recover()
                if once and recovered:
                    return 0
            if heartbeat_only:
                if self.stop.is_set():
                    return 0
                self.start_heartbeat(fatal=True)
                self.stop.wait()
                if self.heartbeat_error:
                    raise self.heartbeat_error
                return 0
            current = self.journal.state()
            if current["phase"] == "IDLE" and current.get("binding") == self.config.binding:
                self.start_heartbeat()
            while not self.stop.is_set():
                outcome = self.one()
                if self.heartbeat_thread is None and outcome == "IDLE":
                    self.start_heartbeat()
                if once:
                    return 0 if outcome == "CONFIRMED" else 2
                self.stop.wait(self.config.interval)
            return 0
        finally:
            self.heartbeat_stop.set()
            if self.heartbeat_thread:
                self.heartbeat_thread.join(12)


def open_journal(directory):
    import fcntl
    path = Path(directory).resolve(strict=True)
    info = path.stat()
    require(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid() and info.st_mode & 0o077 == 0, "STATE_DIRECTORY_UNSAFE")
    lock = os.open(path / "worker.lock", os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    try:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except OSError:
        os.close(lock)
        raise Halt("ANOTHER_WORKER_RUNNING") from None
    db_path = path / "journal.sqlite3"
    fd = os.open(db_path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    info = os.fstat(fd)
    os.close(fd)
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid() and info.st_nlink == 1
            and info.st_mode & 0o077 == 0, "STATE_FILE_UNSAFE")
    return Journal(db_path), lock


class FleetEntry:
    def __init__(self, worker, lock):
        self.worker, self.file_lock = worker, lock
        self.gate = threading.RLock()
        self.present, self.blocked, self.running = False, False, False
        self.generation = 0
        self.due = 0
        # Fleet owns one health thread; task execution still uses the existing Worker.
        worker.start_heartbeat = lambda: None


class Fleet:
    """One serial computation queue; durable ambiguity is isolated per instance."""
    def __init__(self, env, backend, ollama, directory, stop=STOP, journal_opener=open_journal):
        self.env, self.config = dict(env), Config(env, fleet=True)
        require(env.get("UVEL_CLOUD_FLEET_ENABLED") == "true", "FLEET_DISABLED")
        self.backend, self.ollama, self.stop = backend, ollama, stop
        self.directory, self.journal_opener = Path(directory), journal_opener
        self.entries, self.guard = {}, threading.RLock()
        excluded = env.get("UVEL_CLOUD_FLEET_EXCLUDE_DEVICE_IDS", "1152,1202").split(",")
        require(all(re.fullmatch(r"[1-9][0-9]*", value) for value in excluded), "FLEET_EXCLUSION_INVALID")
        self.excluded = {int(value) for value in excluded}

    def instance_config(self, data):
        require(isinstance(data, dict), "FLEET_BINDING_INVALID")
        env = dict(self.env)
        for key, name in zip(IDENTITY, ("OWNER_ID", "DEVICE_ID", "INSTANCE_NO", "TASK_CONFIG_ID", "EXECUTOR_ID", "MODEL", "MODEL_DIGEST")):
            require(key in data, "FLEET_BINDING_INVALID")
            env["UVEL_CLOUD_" + name] = str(data[key])
        config = Config(env)
        require(all(type(data[key]) is type(value) and data[key] == value for key, value in config.binding.items()),
                "FLEET_BINDING_INVALID")
        require(config.binding["model"] == self.env.get("UVEL_CLOUD_MODEL")
                and config.binding["modelDigest"] == self.env.get("UVEL_CLOUD_MODEL_DIGEST"), "FLEET_MODEL_INVALID")
        return config

    def add(self, config):
        executor = config.binding["executorId"]
        with self.guard:
            entry = self.entries.get(executor)
            if entry:
                require(entry.worker.config.binding == config.binding, "FLEET_BINDING_CHANGED")
                return entry
            path = self.directory / sha256(executor.encode("ascii"))
            path.mkdir(mode=0o700, parents=False, exist_ok=True)
            require(not path.is_symlink(), "FLEET_STATE_DIRECTORY_UNSAFE")
            journal, lock = self.journal_opener(path)
            worker = Worker(config, Http(self.config.backend, self.backend.token, executor=executor),
                            self.ollama, journal, threading.Event(), self.stop)
            entry = FleetEntry(worker, lock)
            self.entries[executor] = entry
            return entry

    def restore(self):
        # Only this fleet's hashed children are read; legacy service journals are never imported.
        self.directory.mkdir(mode=0o700, exist_ok=True)
        require(not self.directory.is_symlink(), "FLEET_STATE_DIRECTORY_UNSAFE")
        for path in sorted(self.directory.iterdir()):
            if not re.fullmatch(r"[a-f0-9]{64}", path.name) or not path.is_dir() or path.is_symlink():
                continue
            journal, lock = self.journal_opener(path)
            try:
                state = journal.state()
                if "binding" not in state:
                    continue
                config = self.instance_config(state["binding"])
                require(path.name == sha256(config.binding["executorId"].encode("ascii")), "FLEET_STATE_BINDING_INVALID")
                require(config.binding["deviceId"] not in self.excluded, "FLEET_EXCLUDED_STATE")
                executor = config.binding["executorId"]
                worker = Worker(config, Http(self.config.backend, self.backend.token, executor=executor),
                                self.ollama, journal, threading.Event(), self.stop)
                entry = FleetEntry(worker, lock)
                entry.blocked = state["phase"] not in ("IDLE", "NEXT_NOT_SENT")
                with self.guard:
                    self.entries[executor] = entry
                journal, lock = None, None
            finally:
                if journal is not None:
                    journal.db.close()
                    os.close(lock)

    def snapshot(self):
        with self.guard:
            return list(self.entries.values())

    def refresh(self):
        configs, seen, cursor = [], set(), 0
        while not self.stop.is_set():
            data = envelope(self.backend.request("GET", PREFIX + "/instances?afterDeviceId=" + str(cursor) + "&limit=100"))
            require(data.get("executionKind") == KIND and data.get("specVersion") == SPEC
                    and data.get("deploymentScope") == "TEST" and data.get("serverCanonical") is True,
                    "FLEET_DISCOVERY_SCOPE_INVALID")
            rows = data.get("instances")
            require(isinstance(rows, list) and len(rows) <= 100 and type(data.get("hasMore")) is bool
                    and type(data.get("nextAfterDeviceId")) is int, "FLEET_DISCOVERY_INVALID")
            previous = cursor
            for row in rows:
                config = self.instance_config(row)
                binding(row, config)
                executor, device = config.binding["executorId"], config.binding["deviceId"]
                require(executor not in seen and device > previous, "FLEET_DISCOVERY_ORDER_INVALID")
                seen.add(executor)
                previous = device
                if device not in self.excluded:
                    configs.append(config)
            require(data["nextAfterDeviceId"] >= previous, "FLEET_DISCOVERY_CURSOR_INVALID")
            if not data["hasMore"]:
                break
            require(data["nextAfterDeviceId"] > cursor, "FLEET_DISCOVERY_CURSOR_INVALID")
            cursor = data["nextAfterDeviceId"]
        if self.stop.is_set():
            return
        accepted = set()
        for config in configs:
            entry = self.add(config)
            with entry.gate:
                entry.present = True
                state = entry.worker.journal.state()
                if entry.blocked and not entry.running and state["phase"] == "IDLE" and state.get("binding") == config.binding:
                    try:
                        entry.worker.heartbeat(idle_only=True)
                        entry.blocked = False
                    except Halt:
                        print("FLEET_IDLE_HEALTH_STILL_BLOCKED", flush=True)
                if not entry.blocked and not entry.running:
                    entry.worker.stop.clear()
            accepted.add(config.binding["executorId"])
        for entry in self.snapshot():
            with entry.gate:
                if entry.worker.config.binding["executorId"] not in accepted:
                    if entry.present:
                        entry.generation += 1
                    entry.present = False
                    entry.worker.stop.set()

    def health(self):
        for entry in self.snapshot():
            with entry.gate:
                state = entry.worker.journal.state()
                confirmed = state.get("binding") == entry.worker.config.binding
                active_phase = state["phase"] in ("CLAIMED", "REJECTED_RETRY_INTENT")
                healthy_phase = (state["phase"] == "IDLE" and not entry.running) or (entry.running and active_phase)
                if not entry.present or entry.blocked or not confirmed or not healthy_phase or self.stop.is_set():
                    continue
                try:
                    entry.worker.heartbeat(idle_only=state["phase"] == "IDLE",
                                           expected_task=state.get("taskNo") if active_phase else None)
                except Halt as error:
                    current = entry.worker.journal.state()
                    if active_phase and (current["phase"] != state["phase"] or current.get("taskNo") != state.get("taskNo")):
                        # Completion may clear server activeTaskNo while this older health request is in flight.
                        continue
                    if isinstance(error, (NotSent,)) or str(error) == "HTTP_OUTCOME_UNKNOWN":
                        print("FLEET_HEARTBEAT_UNCONFIRMED", flush=True)
                    else:
                        entry.generation += 1
                        entry.blocked = True
                        entry.worker.stop.set()
                        print("FLEET_INSTANCE_HEALTH_BLOCKED", flush=True)

    def tick(self, entry):
        with entry.gate:
            phase = entry.worker.journal.state()["phase"]
            reconcile = phase in ("COMPLETE_INTENT", "UNKNOWN_COMPLETE", "CLAIMED", "REJECTED_COMPLETE", "REJECTED_RETRY_INTENT")
            if entry.running or (not reconcile and (not entry.present or entry.blocked)) or time.monotonic() < entry.due:
                return
            generation = entry.generation
            entry.running = True
        try:
            if reconcile:
                def authorize_resume():
                    with entry.gate:
                        require(entry.present and not self.stop.is_set(), "STOP_BEFORE_CLAIM_RESUME")
                        require(entry.generation == generation, "CLAIM_RESUME_CANCELLED")
                        entry.blocked = False
                        entry.worker.stop.clear()
                entry.worker.recover(claimed_execution=entry.present, on_resume=authorize_resume)
                with entry.gate:
                    if entry.generation == generation:
                        entry.blocked = False
                        if entry.present and not self.stop.is_set():
                            entry.worker.stop.clear()
            else:
                entry.worker.recover()
                current = entry.worker.journal.state()
                if current["phase"] == "IDLE" and current.get("binding") == entry.worker.config.binding:
                    try:
                        entry.worker.heartbeat(idle_only=True)
                    except Halt as error:
                        if isinstance(error, NotSent) or str(error) == "HTTP_OUTCOME_UNKNOWN":
                            print("FLEET_HEARTBEAT_UNCONFIRMED", flush=True)
                            return
                        raise
                entry.worker.one()
        except Exception:
            with entry.gate:
                entry.generation += 1
                entry.blocked = True
                entry.worker.stop.set()
            print("FLEET_INSTANCE_PENDING_RECONCILIATION", flush=True)
        finally:
            with entry.gate:
                entry.running = False
                entry.due = time.monotonic() + self.config.interval

    def run(self):
        self.restore()
        def maintain():
            next_refresh = 0
            try:
                while not self.stop.is_set():
                    try:
                        if time.monotonic() >= next_refresh:
                            self.refresh()
                            next_refresh = time.monotonic() + self.config.interval
                        self.health()
                    except Exception:
                        print("FLEET_DISCOVERY_OR_HEALTH_UNCONFIRMED", flush=True)
                    self.stop.wait(10)
            finally:
                for entry in self.snapshot():
                    entry.worker.stop.set()
        thread = threading.Thread(target=maintain, daemon=True)
        thread.start()
        try:
            # ponytail: one inference at a time; add concurrency only when measured queue latency requires it.
            while not self.stop.is_set():
                for entry in self.snapshot():
                    if self.stop.is_set():
                        break
                    self.tick(entry)
                self.stop.wait(1)
            return 0
        finally:
            for entry in self.snapshot():
                entry.worker.stop.set()
            thread.join(12)
            require(not thread.is_alive(), "FLEET_HEALTH_STOP_PENDING")
            for entry in self.snapshot():
                entry.worker.journal.db.close()
                os.close(entry.file_lock)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group()
    modes.add_argument("--once", action="store_true")
    modes.add_argument("--heartbeat-only", action="store_true")
    modes.add_argument("--fleet", action="store_true")
    args = parser.parse_args()
    if args.fleet:
        config = Config(os.environ, fleet=True)
        token = credential(os.environ)
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, lambda *_: STOP.set())
        return Fleet(os.environ, Http(config.backend, token), Http(config.ollama),
                     Path(os.environ.get("STATE_DIRECTORY", "/var/lib/uvel-cloud-bge-fleet")) / "instances").run()
    config = Config(os.environ)
    journal, lock = open_journal(os.environ.get("STATE_DIRECTORY", "/var/lib/uvel-cloud-bge-worker"))
    try:
        token = credential(os.environ)
        for sig in (signal.SIGINT, signal.SIGTERM):
            signal.signal(sig, lambda *_: STOP.set())
        return Worker(config, Http(config.backend, token), Http(config.ollama), journal).run(args.once, args.heartbeat_only)
    finally:
        journal.db.close()
        os.close(lock)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Halt as error:
        print(str(error), file=sys.stderr, flush=True)
        sys.exit(HALTED)
    except Exception:
        print("WORKER_UNEXPECTED_STOP", file=sys.stderr, flush=True)
        sys.exit(HALTED)

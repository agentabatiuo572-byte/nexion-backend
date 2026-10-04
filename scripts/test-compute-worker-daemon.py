#!/usr/bin/env python3
"""One explicitly enabled TEST Cloud Share worker. No automatic replay after ambiguity."""
import argparse
import base64
import hashlib
import http.client
import importlib.util
import json
import os
from pathlib import Path
import re
import signal
import socket
import sqlite3
import stat
import sys
import threading
import time
import uuid

OWNER, DEVICE = 60723153007, 1152
INSTANCE = "NEX-ORD-A86AD2E23EF62D719AEFB67A"
CONFIG = "E2-20260924-EM"
PREFIX = "/api/test/compute-workers/v2"
STOP = threading.Event()
HALTED = 78
SAFE_REJECTION_CODES = frozenset({
    "TEST_COMPUTE_WORKER_RUNTIME_STALE", "TEST_COMPUTE_WORKER_RUNTIME_CONFLICT",
    "TEST_COMPUTE_WORKER_AUTH_INVALID", "TEST_COMPUTE_WORKER_BINDING_INVALID",
    "TEST_COMPUTE_WORKER_DEVICE_INVALID", "TEST_COMPUTE_WORKER_TASK_INVALID",
    "TEST_COMPUTE_WORKER_ALREADY_CLAIMED", "TEST_COMPUTE_WORKER_OTHER_TASK_ACTIVE",
    "TEST_COMPUTE_WORKER_TASK_CONFIG_INVALID", "TEST_COMPUTE_WORKER_REWARD_LIMIT",
    "TEST_COMPUTE_WORKER_IDEMPOTENCY_INVALID", "TEST_COMPUTE_RESULT_INVALID",
    "TEST_COMPUTE_CONTINUOUS_DISABLED", "TEST_COMPUTE_WORKER_RELEASE_CONFLICT",
})

# Reuse the finite worker's actual input parser/computation, never its retries or evidence writer.
_spec = importlib.util.spec_from_file_location("finite_compute", Path(__file__).with_name("test-compute-worker.py"))
compute = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(compute)


class Halt(Exception):
    """Only fixed codes are printed; response bodies, tokens and exception text never are."""


class Rejected(Halt):
    """Canonical client/business rejection, distinct from an ambiguous outcome."""


class NotSent(Halt):
    """The HTTP request was never started; a failed TCP connect cannot mutate a task."""


def require(condition, code):
    if not condition:
        raise Halt(code)


def json_bytes(value):
    return json.dumps(value, sort_keys=True, separators=(",", ":"), ensure_ascii=True).encode("ascii")


def secure_file(path):
    fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_NOFOLLOW, 0o600)
    info = os.fstat(fd)
    require(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
            and info.st_nlink == 1 and info.st_mode & 0o077 == 0, "STATE_FILE_UNSAFE")
    return fd


class Journal:
    def __init__(self, directory):
        import fcntl  # Linux systemd service only; no DB credentials or backend-file access.
        directory = Path(directory).resolve(strict=True)  # systemd DynamicUser may expose StateDirectory through a root-owned symlink.
        info = directory.lstat()
        require(directory.is_absolute() and stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
                and info.st_mode & 0o077 == 0, "STATE_DIRECTORY_UNSAFE")
        self.lock = secure_file(directory / "worker.lock")
        try:
            fcntl.flock(self.lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            os.close(self.lock)
            raise Halt("ANOTHER_WORKER_RUNNING") from None
        path = directory / "journal.sqlite3"
        os.close(secure_file(path))
        self.db = sqlite3.connect(path)
        self.db.execute("PRAGMA synchronous=FULL")
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS state(id INTEGER PRIMARY KEY CHECK(id=1), phase TEXT NOT NULL);
            INSERT OR IGNORE INTO state VALUES(1,'IDLE');
            CREATE TABLE IF NOT EXISTS calls(request_key TEXT PRIMARY KEY, operation TEXT NOT NULL,
                task_no TEXT, outcome TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS jobs(task_no TEXT PRIMARY KEY, status TEXT NOT NULL,
                input_hash TEXT, result_hash TEXT, receipt_no TEXT);
        """)
        self.db.commit()
        self.not_sent_task()  # Only proven-unsent cleanup may resume; never claim/complete replay.

    def check_pending(self):
        require(self.db.execute("SELECT phase FROM state WHERE id=1").fetchone()[0] == "IDLE", "STOP_PENDING_RECONCILIATION")

    def not_sent_task(self):
        phase = self.db.execute("SELECT phase FROM state WHERE id=1").fetchone()[0]
        if phase == "IDLE":
            return None
        require(phase == "NOT_SENT_AFTER_CLAIM", "STOP_PENDING_RECONCILIATION")
        jobs = self.db.execute("SELECT task_no,status,receipt_no FROM jobs WHERE status NOT IN ('CONFIRMED','RELEASED_NOT_SENT')").fetchall()
        require(len(jobs) == 1 and jobs[0][1:] == ("NOT_SENT", None), "STOP_PENDING_RECONCILIATION")
        task = jobs[0][0]
        require(isinstance(task, str) and re.fullmatch(r"CTA-[A-Za-z0-9._:-]{1,92}", task), "STOP_PENDING_RECONCILIATION")
        calls = self.db.execute("SELECT operation,outcome,request_key FROM calls WHERE task_no=? ORDER BY rowid", (task,)).fetchall()
        require(len(calls) in (2, 3) and calls[0][:2] == ("NEXT", "CLAIMED")
                and calls[1][:2] == ("COMPLETE", "NOT_SENT")
                and re.fullmatch(r"TEST355-NEXT-[a-f0-9]{32}", calls[0][2])
                and re.fullmatch(r"TEST355-COMPLETE-[a-f0-9]{64}", calls[1][2])
                and (len(calls) == 2 or (calls[2][0] == "RELEASE" and calls[2][1] in ("NOT_SENT", "RELEASED_CANCELLED")
                                       and calls[2][2] == release_key(task))), "STOP_PENDING_RECONCILIATION")
        require(self.db.execute("SELECT COUNT(*) FROM calls WHERE outcome IN ('PENDING','UNKNOWN','REJECTED')").fetchone()[0] == 0,
                "STOP_PENDING_RECONCILIATION")
        return task

    def finish_not_sent_release(self, task, outcome):
        if outcome == "NOT_SENT":
            return
        require(outcome == "RELEASED_CANCELLED", "STOP_PENDING_RECONCILIATION")
        with self.db:
            require(self.db.execute("SELECT phase FROM state WHERE id=1").fetchone()[0] == "NOT_SENT_AFTER_CLAIM",
                    "STOP_PENDING_RECONCILIATION")
            require(self.db.execute("SELECT COUNT(*) FROM calls WHERE task_no=? AND operation='COMPLETE' AND outcome='NOT_SENT'", (task,)).fetchone()[0] == 1
                    and self.db.execute("SELECT outcome FROM calls WHERE request_key=?", (release_key(task),)).fetchone() == ("RELEASED_CANCELLED",),
                    "STOP_PENDING_RECONCILIATION")
            updated = self.db.execute("UPDATE jobs SET status='RELEASED_NOT_SENT' WHERE task_no=? AND status='NOT_SENT' AND receipt_no IS NULL", (task,))
            require(updated.rowcount == 1, "STOP_PENDING_RECONCILIATION")
            self.db.execute("UPDATE state SET phase='IDLE' WHERE id=1")

    def begin(self, operation, key, task=None):
        with self.db:
            self.db.execute("INSERT INTO calls VALUES(?,?,?,'PENDING')", (key, operation, task))
            self.db.execute("UPDATE state SET phase=? WHERE id=1", (operation + "_SENT",))

    def idle(self, key):
        with self.db:
            self.db.execute("UPDATE calls SET outcome='IDLE' WHERE request_key=?", (key,))
            self.db.execute("UPDATE state SET phase='IDLE' WHERE id=1")

    def not_sent(self, key, idle):
        with self.db:
            self.db.execute("UPDATE calls SET outcome='NOT_SENT' WHERE request_key=?", (key,))
            self.db.execute("UPDATE state SET phase=? WHERE id=1", ("IDLE" if idle else "NOT_SENT_AFTER_CLAIM",))
            if not idle:
                self.db.execute("UPDATE jobs SET status='NOT_SENT' WHERE status='CLAIMED'")

    def claimed(self, key, claim, result_hash):
        with self.db:
            self.db.execute("INSERT INTO jobs VALUES(?,'CLAIMED',?,?,NULL)",
                            (claim["taskNo"], claim["inputHash"], result_hash))
            self.db.execute("UPDATE calls SET task_no=?,outcome='CLAIMED' WHERE request_key=?", (claim["taskNo"], key))
            self.db.execute("UPDATE state SET phase='CLAIMED' WHERE id=1")

    def confirmed(self, key, task, receipt):
        with self.db:
            self.db.execute("UPDATE jobs SET status='CONFIRMED',receipt_no=? WHERE task_no=?", (receipt, task))
            self.db.execute("UPDATE calls SET outcome='CONFIRMED' WHERE request_key=?", (key,))
            self.db.execute("UPDATE state SET phase='IDLE' WHERE id=1")

    def unknown(self):
        with self.db:
            self.db.execute("UPDATE state SET phase='UNKNOWN' WHERE id=1")
            self.db.execute("UPDATE calls SET outcome='UNKNOWN' WHERE outcome='PENDING'")
            self.db.execute("UPDATE jobs SET status='UNKNOWN' WHERE status='CLAIMED'")

    def rejected(self):
        with self.db:
            self.db.execute("UPDATE state SET phase='REJECTED' WHERE id=1")
            self.db.execute("UPDATE calls SET outcome='REJECTED' WHERE outcome='PENDING'")
            self.db.execute("UPDATE jobs SET status='REJECTED' WHERE status='CLAIMED'")

    def release_result(self, key, task, outcome):
        with self.db:
            existing = self.db.execute("SELECT operation,task_no,outcome FROM calls WHERE request_key=?", (key,)).fetchone()
            if existing is None:
                self.db.execute("INSERT INTO calls VALUES(?,'RELEASE',?,?)", (key, task, outcome))
            else:
                require(existing == ("RELEASE", task, "NOT_SENT") and outcome == "PENDING", "STOP_PENDING_RECONCILIATION")
                self.db.execute("UPDATE calls SET outcome='PENDING' WHERE request_key=?", (key,))

    def close(self):
        self.db.close()
        os.close(self.lock)


class Client:
    def __init__(self, credential):
        self.credential = credential

    def post(self, path, body, key):
        require(path == PREFIX + "/next-task" or re.fullmatch(
            re.escape(PREFIX) + r"/tasks/CTA-[A-Za-z0-9._:-]{1,92}/(?:complete|release)", path), "ROUTE_INVALID")
        connection = http.client.HTTPConnection("127.0.0.1", 8110, timeout=10)
        deadline = time.monotonic() + 10  # Socket/resource deadline, not a Root receipt validity window.
        pinned = timer = None
        request_started = False
        try:
            connection.connect()
            pinned = connection.sock
            remaining = deadline - time.monotonic()
            require(remaining > 0 and pinned is not None, "HTTP_UNKNOWN")
            pinned.settimeout(remaining)

            def cancel_socket():
                try:
                    pinned.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass

            timer = threading.Timer(remaining, cancel_socket)
            timer.daemon = True
            timer.start()
            request_started = True
            connection.request("POST", path, body, {"Content-Type": "application/json",
                               "Authorization": "Bearer " + self.credential, "Idempotency-Key": key,
                               "Connection": "close"})
            response = connection.getresponse()
            raw = response.read(65537)
            require(len(raw) <= 65536 and time.monotonic() < deadline, "HTTP_UNKNOWN")
            data = compute.strict_json(raw)
            require(time.monotonic() < deadline, "HTTP_UNKNOWN")
            require(isinstance(data, dict) and type(data.get("code")) is int, "HTTP_UNKNOWN")
            if response.status != 200 or data["code"] != 0:
                # A canonical no-task rejection rolls back the original transaction. No other mutation is replayed.
                if path == PREFIX + "/next-task" and response.status == 409 and data["code"] == 409 \
                        and data.get("message") == "TASK_ASSIGNMENT_NO_ELIGIBLE_TASK":
                    return {"idle": True, "serverCanonical": True, "deploymentScope": "TEST"}
                if response.status in (400, 401, 403, 404, 409, 413, 422, 428) and data["code"] == response.status:
                    message = data.get("message")
                    code = message if isinstance(message, str) and message in SAFE_REJECTION_CODES else "UNRECOGNIZED_REJECTION"
                    print("TEST_COMPUTE_REJECTED status=" + str(response.status) + " code=" + code)
                    raise Rejected("HTTP_REJECTED")
                raise Halt("HTTP_UNKNOWN")
            require(isinstance(data.get("data"), dict), "HTTP_UNKNOWN")
            return data["data"]
        except Halt:
            if not request_started:
                raise NotSent("HTTP_NOT_SENT") from None
            raise
        except Exception:
            if not request_started:
                raise NotSent("HTTP_NOT_SENT") from None
            raise Halt("HTTP_UNKNOWN") from None
        finally:
            if timer is not None:
                timer.cancel()
            connection.close()
            if pinned is not None:
                pinned.close()  # Also closes a Connection: close socket detached by getresponse().


def validate_claim(data, executor):
    expected = dict(ownerId=OWNER, deviceId=DEVICE, instanceNo=INSTANCE, taskConfigId=CONFIG,
                    executorId=executor, runId="TEST355-CONTINUOUS", serverCanonical=True, deploymentScope="TEST")
    require(all(type(data.get(k)) is type(v) and data[k] == v for k, v in expected.items()), "CLAIM_BINDING_INVALID")
    task = data.get("taskNo")
    require(isinstance(task, str) and re.fullmatch(r"CTA-[A-Za-z0-9._:-]{1,92}", task), "CLAIM_BINDING_INVALID")
    claim, raw = compute.validate_claim(data, task)
    require(all(type(data.get(k)) is int and data[k] > 0 for k in ("jobIssuedAt", "jobExpiresAt", "serverNow")), "CLAIM_TIME_INVALID")
    require(data["jobIssuedAt"] <= data["serverNow"] < data["jobExpiresAt"]
            and data["jobExpiresAt"] == min(claim["proofExpiresAt"], claim["leaseExpiresAt"]), "CLAIM_TIME_INVALID")
    return claim, raw


def release_key(task):
    return "TEST355-RELEASE-" + hashlib.sha256(task.encode("ascii")).hexdigest()


def release_claim(client, journal, task):
    key = release_key(task)
    journal.release_result(key, task, "PENDING")  # Crash after intent still blocks restart.
    try:
        released = client.post(PREFIX + "/tasks/" + task + "/release", b"{}", key)
        require(released.get("taskNo") == task and released.get("executionKind") == compute.KIND
                and type(released.get("released")) is bool, "RELEASE_UNKNOWN")
        if released["released"]:
            require(released.get("taskStatus") == "CANCELLED" and released.get("serverCanonical") is True
                    and released.get("deploymentScope") == "TEST", "RELEASE_UNKNOWN")
        outcome = "RELEASED_CANCELLED" if released["released"] else "NOT_OWNED"
    except NotSent:
        outcome = "NOT_SENT"
    except Rejected:
        outcome = "REJECTED"
    except Exception:
        outcome = "UNKNOWN"
    with journal.db:
        journal.db.execute("UPDATE calls SET outcome=? WHERE request_key=?", (outcome, key))
    if outcome == "REJECTED":
        if journal.db.execute("SELECT phase FROM state WHERE id=1").fetchone()[0] == "UNKNOWN":
            raise Halt("STOP_PENDING_RECONCILIATION") from None
        journal.rejected()
        raise Halt("STOP_REJECTED") from None
    if outcome == "UNKNOWN":
        journal.unknown()
        raise Halt("STOP_PENDING_RECONCILIATION") from None
    return outcome


def process_one(client, journal, executor):
    claim = None
    confirmed = False
    complete_not_sent = False
    key = "TEST355-NEXT-" + uuid.uuid4().hex
    deadline = time.monotonic() + 75  # One bounded calculation/wait; not a service-credential lifetime.
    journal.begin("NEXT", key)
    try:
        data = client.post(PREFIX + "/next-task", b"{}", key)
        if data.get("idle") is True:
            require(data.get("serverCanonical") is True and data.get("deploymentScope") == "TEST", "IDLE_INVALID")
            journal.idle(key)
            return False
        claim, raw = validate_claim(data, executor)
        result = compute.compute_result(raw, claim)
        result_hash = hashlib.sha256(result).hexdigest()
        journal.claimed(key, claim, result_hash)
        # On normal SIGTERM, finish this bounded job; never start a new one.
        timestamp = compute.wait_until_completable(claim, deadline)
        body = json_bytes(dict(specVersion=compute.SPEC, inputHash=claim["inputHash"], resultHash=result_hash,
                               resultArtifactBase64=base64.b64encode(result).decode("ascii"),
                               proofNonce=claim["proofNonce"], proofTimestamp=timestamp))
        complete_key = "TEST355-COMPLETE-" + hashlib.sha256(body).hexdigest()
        journal.begin("COMPLETE", complete_key, claim["taskNo"])
        response = client.post(PREFIX + "/tasks/" + claim["taskNo"] + "/complete", body, complete_key)
        safe = compute.validate_confirmation(response, claim, result_hash)
        completion = response["completion"]
        require(completion.get("status") == "COMPLETED" and completion.get("serverCanonical") is True
                and completion.get("taskNo") == claim["taskNo"] and completion.get("deviceId") == DEVICE
                and completion.get("taskId") == CONFIG and completion.get("receiptNo") == safe["receiptNo"]
                and safe["receiptNo"] == "CTR-" + claim["taskNo"][-32:], "CONFIRMATION_INVALID")
        journal.confirmed(complete_key, claim["taskNo"], safe["receiptNo"])
        confirmed = True  # The original atomic settlement also closed only this runtime.
        return True
    except NotSent:
        journal.not_sent(key if claim is None else complete_key, idle=claim is None)
        if claim is None:
            return False  # No HTTP request was started; normal next-task polling may continue.
        complete_not_sent = True
        return False  # Abandon this computation; only its own release may resume.
    except Rejected:
        journal.rejected()
        raise Halt("STOP_REJECTED") from None
    except Exception:
        journal.unknown()
        raise Halt("STOP_PENDING_RECONCILIATION") from None
    finally:
        if claim is not None and not confirmed:
            outcome = release_claim(client, journal, claim["taskNo"])
            if complete_not_sent:
                journal.finish_not_sent_release(claim["taskNo"], outcome)
            # Release never clears UNKNOWN or authorizes another claim/complete.


def credential():
    directory = os.environ.get("CREDENTIALS_DIRECTORY", "")
    require(directory.startswith("/"), "CREDENTIAL_SOURCE_INVALID")
    fd = os.open(Path(directory) / "test-worker-token", os.O_RDONLY | os.O_NOFOLLOW)
    try:
        info = os.fstat(fd)
        private_group_read = False
        if info.st_uid == 0 and info.st_gid == 0 and stat.S_IMODE(info.st_mode) == 0o440:
            parent = Path(directory).lstat()
            # LoadCredential's private idmapped mount can expose root:root 0440 to DynamicUser.
            private_group_read = (directory == "/run/credentials/uvel-test-compute-worker.service"
                                  and stat.S_ISDIR(parent.st_mode) and parent.st_uid == 0 and parent.st_gid == 0
                                  and parent.st_mode & 0o027 == 0)
        require(stat.S_ISREG(info.st_mode) and (info.st_mode & 0o077 == 0 or private_group_read) and info.st_nlink == 1
                and info.st_uid in (0, os.getuid()), "CREDENTIAL_SOURCE_INVALID")
        raw = os.read(fd, 81)
        require(re.fullmatch(rb"tc1_[A-Za-z0-9_-]{43}\n?", raw) is not None, "CREDENTIAL_INVALID")
        encoded = raw.strip()[4:]
        secret = base64.urlsafe_b64decode(encoded + b"=")
        require(len(secret) == 32 and base64.urlsafe_b64encode(secret).rstrip(b"=") == encoded, "CREDENTIAL_INVALID")
        return raw.strip().decode("ascii")
    finally:
        os.close(fd)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state-dir", default="/var/lib/uvel-test-worker")
    parser.add_argument("--interval", type=int, default=60, help="Seconds between jobs; default 60 (range 1..3600)")
    parser.add_argument("--executor", default="test355-continuous")
    args = parser.parse_args()
    if os.environ.get("UVEL_TEST_WORKER_ENABLED") != "true" or os.environ.get("UVEL_TEST_WORKER_SCOPE") != "TEST":
        print("TEST_WORKER_DISABLED")
        return 0
    journal = None
    try:
        os.umask(0o077)
        require(1 <= args.interval <= 3600 and re.fullmatch(r"[A-Za-z0-9._:-]{3,32}", args.executor), "CONFIG_INVALID")
        journal = Journal(args.state_dir)  # Pending state is checked before loading credentials or making a request.
        client = Client(credential())
        signal.signal(signal.SIGTERM, lambda *_: STOP.set())
        signal.signal(signal.SIGINT, lambda *_: STOP.set())
        while not STOP.is_set():
            task = journal.not_sent_task()
            if task is None:
                confirmed = process_one(client, journal, args.executor)
            else:
                previous = journal.db.execute("SELECT outcome FROM calls WHERE request_key=?", (release_key(task),)).fetchone()
                outcome = "RELEASED_CANCELLED" if previous == ("RELEASED_CANCELLED",) else release_claim(client, journal, task)
                journal.finish_not_sent_release(task, outcome)
                confirmed = False
            status = "TEST_COMPUTE_WAIT_UNSENT_RELEASE" if journal.not_sent_task() else (
                "TEST_COMPUTE_CONFIRMED" if confirmed else "TEST_COMPUTE_IDLE")
            print(status, flush=True)
            STOP.wait(args.interval)
        return 0
    except Halt as failure:
        print("TEST_COMPUTE_STOP_REJECTED" if str(failure) == "STOP_REJECTED" else "TEST_COMPUTE_STOP_PENDING_RECONCILIATION", flush=True)
        return HALTED
    except (Exception, KeyboardInterrupt):
        if journal is not None:
            try:
                journal.check_pending()
                print("TEST_COMPUTE_IDLE_PROCESS_FAILURE", flush=True)
                return 1  # systemd may restart only when no persisted mutation/job is pending.
            except Exception:
                pass
        print("TEST_COMPUTE_STOP_PENDING_RECONCILIATION", flush=True)
        return HALTED
    finally:
        if journal is not None:
            journal.close()


if __name__ == "__main__":
    sys.exit(main())

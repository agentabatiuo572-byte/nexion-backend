#!/usr/bin/env python3
"""Six fixed TEST runners. Journals preserve unknown work; only durable VERIFIED permits exit acknowledgement."""
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
import sqlite3
import stat
import struct
import threading
import time
import urllib.parse
from datetime import datetime, timezone

PREFIX = '/api/test/cloud-executors/v2'
DRAIN = Path('/var/lib/uvel-task-control/drain.json')


def draining():
    for marker in (DRAIN, DRAIN.with_name('drain-receipt.json')):
        try:
            marker.lstat()
            return True
        except FileNotFoundError:
            continue
    return False


class WorkerError(Exception):
    pass


class Rejected(WorkerError):
    pass


def require(value, code):
    if not value:
        raise WorkerError(code)


def encode(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'), allow_nan=False).encode()


def decode(raw):
    def pairs(items):
        result = {}
        for key, value in items:
            require(key not in result, 'DUPLICATE_JSON_KEY')
            result[key] = value
        return result
    def constant(_):
        raise WorkerError('NONFINITE_JSON')
    return json.loads(raw, object_pairs_hook=pairs, parse_constant=constant)


def address(url):
    parsed = urllib.parse.urlsplit(url)
    require(parsed.scheme == 'http' and ipaddress.ip_address(parsed.hostname).is_loopback
            and parsed.port and parsed.path in ('', '/') and not parsed.query and not parsed.fragment
            and parsed.username is None and parsed.password is None, 'LOOPBACK_URL_REQUIRED')
    return str(ipaddress.ip_address(parsed.hostname)), parsed.port


def credential(path):
    path = Path(path)
    require(path.is_absolute() and not path.is_symlink(), 'CREDENTIAL_FILE_REQUIRED')
    info = path.stat()
    require(stat.S_ISREG(info.st_mode) and info.st_size <= 64 and info.st_mode & 0o022 == 0, 'CREDENTIAL_FILE_MODE')
    token = path.read_text(encoding='ascii').strip()
    require(re.fullmatch(r'ct1_[A-Za-z0-9_-]{43}', token), 'CREDENTIAL_INVALID')
    value = base64.urlsafe_b64decode(token[4:] + '=')
    require(len(value) == 32 and base64.urlsafe_b64encode(value).decode().rstrip('=') == token[4:], 'CREDENTIAL_INVALID')
    return token


class Http:
    def __init__(self, endpoint, token=None):
        self.endpoint, self.token = endpoint, token

    def call(self, method, path, body=None, headers=None, timeout=10):
        connection = http.client.HTTPConnection(*self.endpoint, timeout=timeout)
        request_headers = {'Content-Type': 'application/json', 'Accept': 'application/json'}
        if self.token:
            request_headers.update(Authorization='Bearer ' + self.token, **{'X-UVEL-Executor-Id': 'fleet'})
        request_headers.update(headers or {})
        try:
            connection.request(method, path, body, request_headers)
            response = connection.getresponse()
            raw = response.read(1024 * 1024 + 1)
            require(len(raw) <= 1024 * 1024, 'RESPONSE_TOO_LARGE')
            value = decode(raw)
            if 400 <= response.status < 500:
                raise Rejected('HTTP_REJECTED_' + str(response.status))
            require(200 <= response.status < 300, 'HTTP_OUTCOME_UNKNOWN')
            if self.token:
                require(type(value.get('code')) is int and value['code'] == 0 and isinstance(value.get('data'), dict), 'API_OUTCOME_UNKNOWN')
                return value['data']
            return value
        except (OSError, TimeoutError, http.client.HTTPException, ValueError, UnicodeError):
            raise WorkerError('HTTP_OUTCOME_UNKNOWN') from None
        finally:
            connection.close()


class Journal:
    def __init__(self, path):
        self.lock = threading.RLock()
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.execute('PRAGMA synchronous=FULL')
        self.db.execute('CREATE TABLE IF NOT EXISTS state(id INTEGER PRIMARY KEY CHECK(id=1),value TEXT NOT NULL)')
        self.db.execute('CREATE TABLE IF NOT EXISTS recovery_history(task_no TEXT NOT NULL,generation INTEGER NOT NULL,'
                        'original_json TEXT NOT NULL,receipt_json TEXT NOT NULL,recovered_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,'
                        'PRIMARY KEY(task_no,generation))')
        self.db.execute("INSERT OR IGNORE INTO state VALUES(1,'{\"phase\":\"IDLE\"}')")
        self.db.commit()

    def get(self):
        with self.lock:
            return decode(self.db.execute('SELECT value FROM state WHERE id=1').fetchone()[0])

    def set(self, state):
        with self.lock, self.db:
            self.db.execute('UPDATE state SET value=? WHERE id=1', (encode(state).decode(),))

    def close(self):
        self.db.close()

    def recovered(self, expected, receipt, phase='IDLE'):
        with self.lock, self.db:
            raw = self.db.execute('SELECT value FROM state WHERE id=1').fetchone()[0]
            require(decode(raw) == expected and expected['phase'] == 'RECOVERY_REQUIRED', 'RECOVERY_STATE_CHANGED')
            task = expected['task']
            self.db.execute('INSERT OR IGNORE INTO recovery_history(task_no,generation,original_json,receipt_json) VALUES(?,?,?,?)',
                            (task['taskNo'], task['generation'], raw, encode(receipt).decode()))
            next_state = {**expected, 'phase': 'VERIFIED', 'receiptNo': receipt.get('receiptNo')} if phase == 'VERIFIED' else {'phase': 'IDLE'}
            self.db.execute('UPDATE state SET value=? WHERE id=1', (encode(next_state).decode(),))


def manifest(task):
    require(task.get('protocolVersion') == 2 and re.fullmatch(r'CV2-[0-9A-F]{32}', task.get('taskNo', ''))
            and type(task.get('generation')) is int and task['generation'] > 0, 'TASK_BINDING_INVALID')
    for field in ('claimToken', 'proofNonce', 'inputHash'):
        require(re.fullmatch(r'[a-f0-9]{64}', task.get(field, '')), 'TASK_PROOF_INVALID')
    require(re.fullmatch(r'sha256:[a-f0-9]{64}', task.get('modelDigest', '')) and task.get('model') == 'bge-m3:567m', 'TASK_MODEL_INVALID')
    require(isinstance(task.get('texts'), list) and len(task['texts']) == 3
            and all(isinstance(text, str) and 0 < len(text) <= 4000 for text in task['texts']), 'TASK_INPUT_INVALID')
    require(type(task.get('executionTimeoutSec')) is int and 1 <= task['executionTimeoutSec'] <= 172800, 'TASK_TIMEOUT_INVALID')
    require(isinstance(task.get('startedAt'), str) and task['startedAt'].endswith('Z'), 'TASK_START_INVALID')
    started = datetime.fromisoformat(task['startedAt'].replace('Z', '+00:00'))
    require(started.tzinfo == timezone.utc, 'TASK_START_INVALID')
    require(all(re.fullmatch(r'[a-f0-9]{64}', task.get(field, '')) for field in ('computeContainerId', 'validatorContainerId'))
            and task['computeContainerId'] != task['validatorContainerId'], 'TASK_CONTAINER_BINDING_INVALID')
    return task


def artifact(vectors):
    require(isinstance(vectors, list) and len(vectors) == 3, 'VECTOR_COUNT_INVALID')
    values = []
    for vector in vectors:
        require(isinstance(vector, list) and len(vector) == 1024 and any(vector), 'VECTOR_SHAPE_INVALID')
        require(all(type(value) in (int, float) and math.isfinite(value) for value in vector), 'VECTOR_NUMBER_INVALID')
        values.extend(vector)
    try:
        result = struct.pack('<3072f', *values)
        packed = struct.unpack('<3072f', result)
    except (OverflowError, struct.error):
        raise WorkerError('VECTOR_FLOAT32_INVALID') from None
    require(all(math.isfinite(value) for value in packed)
            and all(any(packed[i:i + 1024]) for i in range(0, 3072, 1024)), 'VECTOR_FLOAT32_INVALID')
    return result


def verify_model(model, task):
    models = model.call('GET', '/api/tags').get('models', [])
    matches = [value for value in models if value.get('name') == task['model']]
    require(len(matches) == 1, 'MODEL_NOT_INSTALLED')
    digest = matches[0].get('digest', '')
    require((digest if digest.startswith('sha256:') else 'sha256:' + digest) == task['modelDigest'], 'MODEL_DIGEST_CHANGED')


class Runner:
    def __init__(self, name, backend, model, journal, stop):
        self.name, self.backend, self.model, self.journal, self.stop = name, backend, model, journal, stop
        self.operation_lock = threading.RLock()
        state = journal.get()
        if state['phase'] == 'COMPUTE_INTENT':
            journal.set({**state, 'phase': 'RECOVERY_REQUIRED', 'reason': 'INFERENCE_INTERRUPTED'})

    def binding(self, task):
        return {'runner': self.name, 'generation': task['generation'], 'claimToken': task['claimToken']}

    def receipt(self, task):
        return self.backend.call('GET', PREFIX + '/tasks/' + task['taskNo'] + '/receipt', headers={
            'X-Compute-Runner': self.name, 'X-Compute-Generation': str(task['generation']), 'X-Compute-Claim': task['claimToken']})

    def step(self):
        state = self.journal.get()
        phase = state['phase']
        if phase == 'IDLE' and draining():
            return
        if phase == 'RECOVERY_REQUIRED':
            task = state.get('task')
            if task:
                receipt = self.receipt(task)
                require(receipt.get('taskNo') == task['taskNo'] and type(receipt.get('generation')) is int
                        and receipt['generation'] == task['generation'] and receipt.get('inputHash') == task['inputHash']
                        and receipt.get('startedAt') == task['startedAt'], 'RECOVERY_RECEIPT_BINDING_INVALID')
                if state.get('inferenceExited') is True and state.get('request') and receipt.get('state') == 'COMPLETED':
                    require(receipt.get('resultHash') == decode(state['request'])['resultHash'], 'RECOVERY_RESULT_CONFLICT')
                    self.journal.recovered(state, receipt, 'VERIFIED')
                    return
                if receipt.get('exitConfirmed') is True and receipt.get('state') in ('FAILED', 'COMPLETED'):
                    self.journal.recovered(state, receipt)
            return
        if phase in ('IDLE', 'CLAIM_INTENT'):
            body = state.get('request', encode({'runner': self.name}).decode())
            self.journal.set({**state, 'phase': 'CLAIM_INTENT', 'request': body})
            task = self.backend.call('POST', PREFIX + '/next-task', body=body.encode())
            if task.get('state') == 'IDLE':
                if state.get('task'):
                    self.journal.set({**state, 'phase': 'RECOVERY_REQUIRED', 'reason': 'CLAIM_NO_LONGER_ASSIGNED'})
                    return
                self.journal.set({'phase': 'IDLE'})
                return
            if state.get('task'):
                require((state['task']['taskNo'], state['task']['generation']) == (task.get('taskNo'), task.get('generation')), 'CLAIM_TASK_CHANGED')
            if task.get('state') not in ('COMPUTING', 'STARTING'):
                self.journal.set({'phase': 'RECOVERY_REQUIRED', 'task': task, 'reason': 'CLAIM_REQUIRES_RECONCILIATION'})
                return
            task = manifest(task)
            if task['state'] == 'STARTING':
                self.journal.set({'phase': 'CLAIM_INTENT', 'request': body, 'task': task})
                return
            self.journal.set({'phase': 'CLAIMED', 'task': task})
            state, phase = self.journal.get(), 'CLAIMED'
        task = state['task']
        if phase == 'CLAIMED':
            self.journal.set({**state, 'phase': 'COMPUTE_INTENT'})
            try:
                verify_model(self.model, task)
                result = self.model.call('POST', '/api/embed', body=encode({'model': task['model'], 'input': task['texts'], 'truncate': False}),
                                         timeout=task['executionTimeoutSec'])
                require(result.get('model') == task['model'], 'RESULT_MODEL_INVALID')
                raw = artifact(result.get('embeddings'))
                verify_model(self.model, task)
            except Exception:
                self.journal.set({**state, 'phase': 'RECOVERY_REQUIRED', 'reason': 'INFERENCE_OUTCOME_UNKNOWN'})
                raise
            body = {**self.binding(task), 'proofNonce': task['proofNonce'], 'inputHash': task['inputHash'],
                    'modelDigest': task['modelDigest'], 'resultHash': hashlib.sha256(raw).hexdigest(),
                    'resultArtifactBase64': base64.b64encode(raw).decode(), 'proofTimestamp': int(time.time())}
            state = {'phase': 'COMPLETE_INTENT', 'task': task, 'request': encode(body).decode(), 'inferenceExited': True}
            self.journal.set(state)
            phase = 'COMPLETE_INTENT'
        if phase == 'COMPLETE_INTENT':
            receipt = self.receipt(task)
            expected = decode(state['request'])['resultHash']
            require(receipt.get('taskNo') == task['taskNo'], 'RECEIPT_TASK_CONFLICT')
            if receipt.get('state') == 'COMPLETED':
                require(receipt.get('resultHash') == expected, 'RECEIPT_RESULT_CONFLICT')
            else:
                receipt = self.backend.call('POST', PREFIX + '/tasks/' + task['taskNo'] + '/complete',
                                            body=state['request'].encode(), timeout=task['executionTimeoutSec'] + 120)
                require(receipt.get('state') == 'COMPLETED' and receipt.get('taskNo') == task['taskNo']
                        and receipt.get('resultHash') == expected, 'COMPLETE_UNCONFIRMED')
            self.journal.set({**state, 'phase': 'VERIFIED', 'receiptNo': receipt.get('receiptNo')})
            state, phase = self.journal.get(), 'VERIFIED'
        if phase == 'VERIFIED':
            require(state.get('inferenceExited') is True, 'PROCESS_EXIT_NOT_PROVEN')
            receipt = self.backend.call('POST', PREFIX + '/tasks/' + task['taskNo'] + '/exited', body=encode(self.binding(task)))
            require(receipt.get('state') == 'EXIT_CONFIRMED' and receipt.get('taskNo') == task['taskNo'], 'EXIT_UNCONFIRMED')
            self.journal.set({'phase': 'IDLE'})

    def run(self):
        while not self.stop.is_set():
            try:
                self.step()
            except Rejected:
                state = self.journal.get()
                self.journal.set({**state, 'phase': 'RECOVERY_REQUIRED', 'reason': 'BACKEND_REJECTED_OR_EXPIRED'})
            except Exception as failure:
                print(encode({'runner': self.name, 'event': 'RETRY_OR_RECOVERY', 'phase': self.journal.get()['phase'],
                              'errorClass': type(failure).__name__}).decode(), flush=True)
            self.stop.wait(1)


def heartbeat(runners, stop):
    while not stop.wait(10):
        for runner in runners:
            state = runner.journal.get()
            if state['phase'] not in ('CLAIMED', 'COMPUTE_INTENT', 'COMPLETE_INTENT'):
                continue
            task = state['task']
            try:
                runner.backend.call('POST', PREFIX + '/tasks/' + task['taskNo'] + '/heartbeat', body=encode(runner.binding(task)), timeout=2)
            except Exception:
                # An uncertain renewal is never evidence that inference exited or a completion failed.
                print(encode({'runner': runner.name, 'event': 'HEARTBEAT_UNCONFIRMED'}).decode(), flush=True)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--journal-directory', required=True)
    parser.add_argument('--credential-file', required=True)
    parser.add_argument('--backend', default='http://127.0.0.1:8110')
    parser.add_argument('--ollama-urls', required=True)
    args = parser.parse_args()
    require(os.environ.get('UVEL_COMPUTE_V2_ENABLED') == 'true' and os.environ.get('UVEL_COMPUTE_V2_SCOPE') == 'TEST', 'WORKER_DISABLED')
    endpoints = [address(value) for value in args.ollama_urls.split(',')]
    require(len(endpoints) == 6 and len(set(endpoints)) == 6, 'SIX_DISTINCT_MODEL_RUNNERS_REQUIRED')
    directory = Path(args.journal_directory)
    require(directory.is_absolute() and not directory.is_symlink(), 'ABSOLUTE_JOURNAL_DIRECTORY_REQUIRED')
    os.umask(0o077)
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    lock = directory.joinpath('worker.lock').open('a+b')
    if os.name == 'nt':
        import msvcrt
        lock.write(b'0'); lock.flush(); lock.seek(0); msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
    else:
        import fcntl
        fcntl.flock(lock.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
    backend = Http(address(args.backend), credential(args.credential_file))
    stop = threading.Event()
    for signum in (signal.SIGINT, signal.SIGTERM):
        signal.signal(signum, lambda *_: stop.set())
    runners = [Runner('runner-' + str(i + 1), backend, Http(endpoint), Journal(directory / ('runner-' + str(i + 1) + '.sqlite')), stop)
               for i, endpoint in enumerate(endpoints)]
    threads = [threading.Thread(target=runner.run, name=runner.name) for runner in runners]
    threads.append(threading.Thread(target=heartbeat, args=(runners, stop), name='compute-v2-heartbeat'))
    for thread in threads:
        thread.start()
    for thread in threads:
        thread.join()
    for runner in runners:
        runner.journal.close()
    lock.close()


if __name__ == '__main__':
    main()

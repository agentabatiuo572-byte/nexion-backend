#!/usr/bin/env python3
"""Root observer: prove two allowlisted containers exited, retain evidence, then acknowledge one generation."""
import argparse
import contextlib
import datetime as dt
import hashlib
import hmac
import importlib.util
import json
import os
from pathlib import Path
import re
import sqlite3
import stat
import subprocess
import tempfile

_spec = importlib.util.spec_from_file_location('compute_v2_worker', Path(__file__).with_name('compute-v2-worker.py'))
worker = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(worker)


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), ensure_ascii=True, allow_nan=False).encode('ascii')


def utc(value):
    worker.require(isinstance(value, str), 'OBSERVER_UTC_REQUIRED')
    try:
        parsed = dt.datetime.fromisoformat(value.replace('Z', '+00:00'))
    except ValueError:
        raise worker.WorkerError('OBSERVER_UTC_REQUIRED') from None
    worker.require(parsed.tzinfo is not None and parsed.utcoffset() == dt.timedelta(0), 'OBSERVER_UTC_REQUIRED')
    return parsed.astimezone(dt.timezone.utc)


def root_config(path):
    worker.require(os.name == 'posix' and os.geteuid() == 0, 'ROOT_OBSERVER_REQUIRES_TEST_ROOT')
    path = Path(path)
    worker.require(path.is_absolute() and not path.is_symlink(), 'ROOT_CONFIG_REQUIRED')
    info = path.stat()
    worker.require(stat.S_ISREG(info.st_mode) and info.st_uid == 0 and info.st_mode & 0o077 == 0
                   and info.st_size <= 32768, 'ROOT_CONFIG_OWNER_ONLY_REQUIRED')
    value = worker.decode(path.read_bytes())
    fields = {'backend', 'proofSecret', 'workerBearer', 'runners', 'validator'}
    worker.require(isinstance(value, dict) and set(value) in (fields, fields | {'modal'}),
                   'ROOT_CONFIG_FIELDS_INVALID')
    worker.address(value['backend'])
    worker.require(re.fullmatch(r'[a-f0-9]{64}', value.get('proofSecret', '')) is not None
                   and re.fullmatch(r'ct1_[A-Za-z0-9_-]{43}', value.get('workerBearer', '')) is not None, 'ROOT_CONFIG_CREDENTIAL_INVALID')
    worker.require(isinstance(value['runners'], dict) and 1 <= len(value['runners']) <= 6, 'ROOT_RUNNER_ALLOWLIST_REQUIRED')
    for runner, item in value['runners'].items():
        worker.require(re.fullmatch(r'runner-[1-6]', runner) is not None, 'ROOT_RUNNER_ALLOWLIST_INVALID')
        allowlist(item)
    allowlist(value['validator'])
    if 'modal' in value:
        allowlist(value['modal'])
    return value


def allowlist(item):
    worker.require(isinstance(item, dict) and set(item) == {'container', 'origin'}, 'ROOT_CONTAINER_ALLOWLIST_REQUIRED')
    worker.require(isinstance(item['container'], str) and re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_.-]{0,127}', item['container']) is not None,
                   'ROOT_CONTAINER_SELECTOR_INVALID')
    worker.address(item['origin'])


def journal(path):
    path = Path(path).resolve()
    worker.require(path.is_file() and re.fullmatch(r'runner-[1-6]\.sqlite', path.name) is not None, 'V2_RUNNER_JOURNAL_REQUIRED')
    # Read the current WAL view; immutable=1 would silently ignore the live worker's WAL.
    with contextlib.closing(sqlite3.connect(path.as_uri() + '?mode=ro', uri=True)) as connection:
        rows = connection.execute('SELECT value FROM state WHERE id=1').fetchall()
    worker.require(len(rows) == 1, 'V2_RUNNER_JOURNAL_AMBIGUOUS')
    state = worker.decode(rows[0][0])
    task = state.get('task')
    worker.require(isinstance(task, dict), 'V2_RUNNER_TASK_REQUIRED')
    worker.manifest(task)
    utc(task.get('startedAt'))
    for field in ('computeContainerId', 'validatorContainerId'):
        worker.require(re.fullmatch(r'[a-f0-9]{64}', task.get(field, '')) is not None, 'FROZEN_CONTAINER_ID_REQUIRED')
    return path.stem, task


def docker_inspect(selector):
    completed = subprocess.run(['docker', 'inspect', '--type=container', selector], capture_output=True, timeout=15, check=False)
    worker.require(completed.returncode == 0 and len(completed.stdout) <= 1024 * 1024, 'DOCKER_INSPECT_UNAVAILABLE')
    values = worker.decode(completed.stdout)
    worker.require(isinstance(values, list) and len(values) == 1, 'DOCKER_INSPECT_AMBIGUOUS')
    return values[0]


def checked_container(value, allowed, expected_id, started_at):
    selector = allowed['container']
    identity = value.get('Id', '')
    worker.require(re.fullmatch(r'[a-f0-9]{64}', identity) is not None and identity == expected_id, 'CONTAINER_MANIFEST_ID_CHANGED')
    if re.fullmatch(r'[a-f0-9]{64}', selector):
        worker.require(identity == selector, 'CONTAINER_ALLOWLIST_ID_CHANGED')
    else:
        worker.require(value.get('Name') == '/' + selector, 'CONTAINER_ALLOWLIST_NAME_CHANGED')
    state = value.get('State', {})
    worker.require(state.get('Running') is False and state.get('Restarting') is False
                   and type(state.get('Pid')) is int and state['Pid'] == 0
                   and state.get('Status') in ('exited', 'dead'), 'CONTAINER_NOT_PHYSICALLY_STOPPED')
    worker.require(type(state.get('ExitCode')) is int and 0 <= state['ExitCode'] <= 255, 'CONTAINER_EXIT_CODE_INVALID')
    finished = utc(state.get('FinishedAt'))
    worker.require(finished > started_at, 'CONTAINER_EXIT_PRECEDES_TASK')
    # NetworkSettings.Ports may be empty after stop; HostConfig retains the actual configured publishing contract.
    host, port = worker.address(allowed['origin'])
    bindings = value.get('HostConfig', {}).get('PortBindings', {})
    matches = [item for items in bindings.values() for item in (items or []) if item.get('HostPort') == str(port)
               and item.get('HostIp', '') in ('', '0.0.0.0', '::', host)]
    worker.require(len(matches) == 1, 'CONTAINER_ORIGIN_PORT_MISMATCH')
    return {'id': identity, 'name': value['Name'], 'pid': 0, 'running': False,
            'exitCode': state['ExitCode'], 'finishedAt': finished.isoformat().replace('+00:00', 'Z'), 'originPort': port}


def save(path, evidence):
    with tempfile.NamedTemporaryFile(mode='wb', dir=path.parent, prefix='.compute-v2-observer-', delete=False) as target:
        temporary = Path(target.name)
        target.write(canonical(evidence) + b'\n')
        target.flush()
        os.fsync(target.fileno())
    os.replace(temporary, path)


def observe(config, journal_path, artifact_path, client=None, inspector=docker_inspect, now=None):
    runner, task = journal(journal_path)
    worker.require(runner in config['runners'], 'RUNNER_NOT_ALLOWLISTED')
    compute, validator = config['runners'][runner], config['validator']
    if task['protocolVersion'] == 3:
        worker.require('modal' in config, 'MODAL_CONTAINER_ALLOWLIST_REQUIRED')
        compute = validator = config['modal']
    allowlist(compute)
    allowlist(validator)
    client = client or worker.Http(worker.address(config['backend']), config['workerBearer'])
    original = client.call('GET', worker.PREFIX + '/tasks/' + task['taskNo'] + '/receipt', headers={
        'X-Compute-Runner': runner, 'X-Compute-Generation': str(task['generation']), 'X-Compute-Claim': task['claimToken']})
    worker.require(original.get('taskNo') == task['taskNo'] and original.get('generation') == task['generation']
                   and original.get('inputHash') == task['inputHash']
                   and utc(original.get('startedAt')) == utc(task['startedAt']), 'JOURNAL_BACKEND_MANIFEST_MISMATCH')
    started = utc(task['startedAt'])
    compute_exit = checked_container(inspector(compute['container']), compute, task['computeContainerId'], started)
    validator_exit = checked_container(inspector(validator['container']), validator, task['validatorContainerId'], started)
    observed = now or dt.datetime.now(dt.timezone.utc)
    worker.require(observed > started and observed >= utc(compute_exit['finishedAt'])
                   and observed >= utc(validator_exit['finishedAt']), 'OBSERVATION_TIME_INVALID')
    observation = {'taskNo': task['taskNo'], 'generation': task['generation'], 'runner': runner,
                   'observedAt': observed.astimezone(dt.timezone.utc).isoformat(timespec='microseconds').replace('+00:00', 'Z'),
                   'computeContainerId': compute_exit['id'], 'validatorContainerId': validator_exit['id'],
                   'computeExitCode': compute_exit['exitCode'], 'validatorExitCode': validator_exit['exitCode']}
    body = {'runner': runner, 'generation': task['generation'], 'claimToken': task['claimToken'],
            'observation': observation, 'signature': hmac.new(bytes.fromhex(config['proofSecret']), canonical(observation), hashlib.sha256).hexdigest()}
    path = Path(artifact_path).resolve()
    worker.require(path != Path(journal_path).resolve(), 'OBSERVER_ARTIFACT_MUST_NOT_REPLACE_JOURNAL')
    # Reserve a new artifact before the mutation; retries must use a new evidence path, never erase the incident.
    with path.open('xb'):
        pass
    evidence = {'schemaVersion': 1, 'observation': observation, 'observationSha256': hashlib.sha256(canonical(observation)).hexdigest(),
                'containers': {'compute': compute_exit, 'validator': validator_exit}, 'outcome': 'SUBMITTING'}
    save(path, evidence)
    try:
        result = client.call('POST', worker.PREFIX + '/tasks/' + task['taskNo'] + '/observed-exit', body=canonical(body))
        worker.require(result.get('taskNo') == task['taskNo'] and result.get('state') == 'EXIT_CONFIRMED', 'OBSERVER_ACK_UNKNOWN')
        evidence['outcome'] = 'EXIT_CONFIRMED'
        save(path, evidence)
    except Exception as failure:
        evidence['outcome'] = 'UNKNOWN'
        evidence['errorClass'] = type(failure).__name__
        if isinstance(failure, worker.WorkerError):
            evidence['errorCode'] = str(failure)
        save(path, evidence)
        raise worker.WorkerError('OBSERVER_SUBMISSION_UNKNOWN_EVIDENCE_RETAINED') from None
    return path


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--config', required=True)
    parser.add_argument('--journal', required=True)
    parser.add_argument('--artifact', required=True)
    args = parser.parse_args()
    try:
        result = observe(root_config(args.config), args.journal, args.artifact)
        print(json.dumps({'outcome': 'EXIT_CONFIRMED', 'artifact': str(result)}))
    except (worker.WorkerError, OSError, ValueError, sqlite3.Error, subprocess.SubprocessError) as failure:
        error = {'outcome': 'NOT_CONFIRMED', 'errorClass': type(failure).__name__}
        if isinstance(failure, worker.WorkerError):
            error['errorCode'] = str(failure)
        print(json.dumps(error))
        raise SystemExit(1) from None


if __name__ == '__main__':
    main()

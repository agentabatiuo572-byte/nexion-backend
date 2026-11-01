import copy
import datetime as dt
import hashlib
import hmac
import importlib.util
import json
from pathlib import Path
import stat
import tempfile
import types
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('compute_v2_observer', Path(__file__).with_name('compute-v2-observe-exit.py'))
o = importlib.util.module_from_spec(spec)
spec.loader.exec_module(o)


class ObserverTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='compute-v2-observer-')
        self.root = Path(self.directory.name)
        self.path = self.root / 'runner-1.sqlite'
        self.task = {'protocolVersion': 2, 'taskNo': 'CV2-' + 'A' * 32, 'generation': 4,
                     'claimToken': '1' * 64, 'proofNonce': '2' * 64, 'inputHash': '3' * 64,
                     'modelDigest': 'sha256:' + '4' * 64, 'model': 'bge-m3:567m', 'texts': ['one', 'two', 'three'],
                     'executionTimeoutSec': 300, 'startedAt': '2026-11-01T00:00:00Z',
                     'computeContainerId': 'a' * 64, 'validatorContainerId': 'b' * 64}
        self.journal = o.worker.Journal(self.path)
        self.original = {'phase': 'RECOVERY_REQUIRED', 'task': self.task, 'reason': 'INFERENCE_OUTCOME_UNKNOWN'}
        self.journal.set(self.original)
        self.config = {'backend': 'http://127.0.0.1:8110', 'proofSecret': 'f' * 64, 'workerBearer': 'ct1_' + 'S' * 43,
                       'runners': {'runner-1': {'container': 'compute-one', 'origin': 'http://127.0.0.1:11435'}},
                       'validator': {'container': 'validator', 'origin': 'http://127.0.0.1:11436'}}
        self.inspected = []
        self.calls = []
        self.reply = {'taskNo': self.task['taskNo'], 'generation': 4, 'startedAt': self.task['startedAt'], 'inputHash': self.task['inputHash']}
        self.now = dt.datetime(2026, 11, 1, 0, 1, tzinfo=dt.timezone.utc)

    def tearDown(self):
        self.assertEqual(self.journal.get(), self.original)
        self.journal.close()
        self.directory.cleanup()

    def container(self, name, identity, port):
        return {'Id': identity, 'Name': '/' + name, 'State': {'Running': False, 'Restarting': False, 'Pid': 0, 'Status': 'exited',
                'ExitCode': 137, 'FinishedAt': '2026-11-01T00:00:30.123456789Z'},
                'HostConfig': {'PortBindings': {'11434/tcp': [{'HostIp': '127.0.0.1', 'HostPort': str(port)}]}},
                'Config': {'Env': ['UNRELATED_SECRET=must-not-copy']}}

    def inspect(self, selector):
        self.inspected.append(selector)
        if selector == 'compute-one':
            return self.container(selector, 'a' * 64, 11435)
        if selector == 'validator':
            return self.container(selector, 'b' * 64, 11436)
        self.fail('Must inspect only root allowlisted selectors')

    def call(self, method, path, **kwargs):
        self.calls.append((method, path, kwargs))
        if method == 'GET':
            return self.reply
        self.assertTrue(path.endswith('/observed-exit'))
        return {'taskNo': self.task['taskNo'], 'state': 'EXIT_CONFIRMED'}

    def observe(self, name='evidence.json', **kwargs):
        return o.observe(self.config, self.path, self.root / name, client=self, inspector=self.inspect, now=self.now, **kwargs)

    def test_success_matches_original_journal_and_signs_exact_eight_key_observation(self):
        path = self.observe()
        self.assertEqual(self.inspected, ['compute-one', 'validator'])
        self.assertEqual([call[0] for call in self.calls], ['GET', 'POST'])
        body = o.worker.decode(self.calls[-1][2]['body'])
        self.assertEqual(set(body), {'runner', 'generation', 'claimToken', 'observation', 'signature'})
        self.assertEqual(set(body['observation']), {'taskNo', 'generation', 'runner', 'observedAt', 'computeContainerId',
                                                'validatorContainerId', 'computeExitCode', 'validatorExitCode'})
        expected = hmac.new(bytes.fromhex(self.config['proofSecret']), o.canonical(body['observation']), hashlib.sha256).hexdigest()
        self.assertEqual(body['signature'], expected)
        raw = path.read_text()
        for secret in (self.config['proofSecret'], self.config['workerBearer'], self.task['claimToken'], body['signature'], 'UNRELATED_SECRET'):
            self.assertNotIn(secret, raw)
        self.assertEqual(json.loads(raw)['outcome'], 'EXIT_CONFIRMED')

    def test_wrong_journal_manifest_cannot_inspect_or_sign_any_container(self):
        self.reply['inputHash'] = '9' * 64
        with self.assertRaisesRegex(o.worker.WorkerError, 'JOURNAL_BACKEND_MANIFEST_MISMATCH'):
            self.observe()
        self.assertEqual(self.inspected, [])
        self.assertEqual(len(self.calls), 1)

    def test_running_restarting_nonzero_pid_old_exit_wrong_id_and_wrong_port_never_sign(self):
        scenarios = [{'Running': True}, {'Restarting': True}, {'Pid': 123}, {'FinishedAt': '2026-10-31T23:59:59Z'}]
        original = self.container('compute-one', 'a' * 64, 11435)
        for change in scenarios:
            value = copy.deepcopy(original)
            value['State'].update(change)
            with self.assertRaises(o.worker.WorkerError):
                o.checked_container(value, self.config['runners']['runner-1'], 'a' * 64, o.utc(self.task['startedAt']))
        for name, identity, port in [('wrong-name', 'a' * 64, 11435), ('compute-one', 'c' * 64, 11435), ('compute-one', 'a' * 64, 9999)]:
            with self.assertRaises(o.worker.WorkerError):
                o.checked_container(self.container(name, identity, port), self.config['runners']['runner-1'], 'a' * 64, o.utc(self.task['startedAt']))

    def test_unknown_post_preserves_evidence_without_retry_or_journal_rewrite(self):
        def uncertain(method, path, **kwargs):
            result = self.call(method, path, **kwargs)
            if method == 'POST':
                raise o.worker.WorkerError('HTTP_OUTCOME_UNKNOWN')
            return result
        client = types.SimpleNamespace(call=uncertain)
        artifact = self.root / 'unknown.json'
        with self.assertRaisesRegex(o.worker.WorkerError, 'OBSERVER_SUBMISSION_UNKNOWN'):
            o.observe(self.config, self.path, artifact, client=client, inspector=self.inspect, now=self.now)
        self.assertEqual([call[0] for call in self.calls], ['GET', 'POST'])
        evidence = json.loads(artifact.read_text())
        self.assertEqual(evidence['outcome'], 'UNKNOWN')
        self.assertEqual(evidence['errorCode'], 'HTTP_OUTCOME_UNKNOWN')

    def test_root_allowlist_cannot_redirect_observer_to_another_runner(self):
        self.config['runners'] = {'runner-2': self.config['runners']['runner-1']}
        with self.assertRaisesRegex(o.worker.WorkerError, 'RUNNER_NOT_ALLOWLISTED'):
            self.observe()
        self.assertEqual(self.calls, [])

    def test_existing_evidence_is_not_overwritten_and_stopped_nonzero_exit_is_valid(self):
        path = self.observe()
        previous = path.read_bytes()
        with self.assertRaises(FileExistsError):
            self.observe()
        self.assertEqual(path.read_bytes(), previous)
        self.assertEqual(sum(method == 'POST' for method, _, _ in self.calls), 1)
        self.assertEqual(json.loads(previous)['observation']['computeExitCode'], 137)

    def test_private_root_config_rejects_world_readable_or_nonroot_ownership(self):
        config = self.root / 'private.json'
        config.write_bytes(o.canonical(self.config))
        info = types.SimpleNamespace(st_mode=stat.S_IFREG | 0o600, st_uid=0, st_size=config.stat().st_size)
        with patch.object(o.os, 'name', 'posix'), patch.object(o.os, 'geteuid', return_value=0, create=True), patch.object(o, 'Path', return_value=config), patch.object(type(config), 'stat', return_value=info):
            self.assertEqual(o.root_config(config), self.config)
            for owner, mode in [(1000, 0o600), (0, 0o644), (0, 0o660)]:
                info.st_uid, info.st_mode = owner, stat.S_IFREG | mode
                with self.assertRaisesRegex(o.worker.WorkerError, 'ROOT_CONFIG_OWNER_ONLY_REQUIRED'):
                    o.root_config(config)


if __name__ == '__main__':
    unittest.main()

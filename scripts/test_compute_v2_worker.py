import base64
import importlib.util
from pathlib import Path
import struct
import tempfile
import threading
import unittest
from unittest.mock import Mock, patch

spec = importlib.util.spec_from_file_location('compute_v2_worker', Path(__file__).with_name('compute-v2-worker.py'))
w = importlib.util.module_from_spec(spec)
spec.loader.exec_module(w)


def task():
    return {'protocolVersion': 2, 'state': 'COMPUTING', 'taskNo': 'CV2-' + 'A' * 32, 'generation': 1,
            'claimToken': 'a' * 64, 'proofNonce': 'b' * 64, 'inputHash': 'c' * 64,
            'model': 'bge-m3:567m', 'modelDigest': 'sha256:' + 'd' * 64,
            'startedAt': '2026-11-01T01:00:00Z', 'computeContainerId': '1' * 64, 'validatorContainerId': '2' * 64,
            'texts': ['first', 'second', 'third'], 'executionTimeoutSec': 30}


class Model:
    def __init__(self):
        self.calls = []
        self.failure = None

    def call(self, method, path, **kwargs):
        self.calls.append((method, path, kwargs))
        if path == '/api/tags':
            return {'models': [{'name': 'bge-m3:567m', 'digest': 'd' * 64}]}
        if self.failure:
            raise self.failure
        return {'model': 'bge-m3:567m', 'embeddings': [[0.25] * 1024 for _ in range(3)]}


class Backend:
    def __init__(self):
        self.calls = []
        self.drop_claim = False
        self.drop_complete = False
        self.drop_exit = False
        self.verified = None
        self.exited = False
        self.claim_state = 'COMPUTING'
        self.recovery_receipt = None

    def call(self, method, path, body=None, **kwargs):
        self.calls.append((method, path, body))
        if path.endswith('/next-task'):
            if self.drop_claim:
                self.drop_claim = False
                raise w.WorkerError('HTTP_OUTCOME_UNKNOWN')
            return {**task(), 'state': self.claim_state}
        if path.endswith('/receipt'):
            if self.recovery_receipt:
                return self.recovery_receipt
            return self.verified or {'state': 'IN_PROGRESS', 'taskNo': task()['taskNo']}
        if path.endswith('/complete'):
            self.verified = {'state': 'COMPLETED', 'taskNo': task()['taskNo'], 'resultHash': w.decode(body)['resultHash'], 'receiptNo': 'CTR-test'}
            if self.drop_complete:
                self.drop_complete = False
                raise w.WorkerError('HTTP_OUTCOME_UNKNOWN')
            return self.verified
        if path.endswith('/exited'):
            assert self.verified is not None
            self.exited = True
            if self.drop_exit:
                self.drop_exit = False
                raise w.WorkerError('HTTP_OUTCOME_UNKNOWN')
            return {'state': 'EXIT_CONFIRMED', 'taskNo': task()['taskNo']}
        raise AssertionError(path)


class WorkerTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.path = Path(self.directory.name) / 'runner.sqlite'
        self.journal = w.Journal(self.path)
        self.backend, self.model, self.stop = Backend(), Model(), threading.Event()
        self.runner = w.Runner('runner-1', self.backend, self.model, self.journal, self.stop)

    def tearDown(self):
        self.journal.close()
        self.directory.cleanup()

    def run_once(self):
        self.stop.clear()
        with patch.object(self.stop, 'wait', side_effect=lambda _: self.stop.set()), patch('builtins.print') as output:
            self.runner.run()
        return [w.decode(call.args[0]) for call in output.call_args_list]

    def test_http_failure_source_and_status_survive_non_json_error_without_exposing_body(self):
        for token in (None, 'private-token'):
            for status in (400, 503):
                with self.subTest(source='BACKEND' if token else 'MODEL', status=status):
                    connection = Mock()
                    connection.getresponse.return_value.status = status
                    connection.getresponse.return_value.read.return_value = b'private upstream error, not JSON'
                    with patch.object(w.http.client, 'HTTPConnection', return_value=connection):
                        with self.assertRaises(w.HttpFailure) as raised:
                            w.Http(('127.0.0.1', 11434), token).call('POST', '/api/embed', body=b'{}')
                    failure = raised.exception
                    self.assertEqual((failure.source, failure.status), ('BACKEND' if token else 'MODEL', status))
                    self.assertEqual(isinstance(failure, w.Rejected), bool(token) and status == 400)
                    self.assertNotIn('private', str(failure))
                    connection.close.assert_called_once()

    def test_model_http_failure_and_later_backend_rejection_preserve_original_recovery(self):
        self.model.failure = w.HttpFailure('MODEL', 400)
        events = self.run_once()
        original = self.journal.get()
        self.assertEqual((original['phase'], original['reason'], original['errorSource'], original['httpStatus']),
                         ('RECOVERY_REQUIRED', 'INFERENCE_OUTCOME_UNKNOWN', 'MODEL', 400))
        self.assertEqual((events[-1]['errorSource'], events[-1]['httpStatus']), ('MODEL', 400))
        self.assertFalse(any(path.endswith('/complete') or path.endswith('/exited') for _, path, _ in self.backend.calls))
        with patch.object(self.backend, 'call', side_effect=w.Rejected('BACKEND', 409)):
            events = self.run_once()
        self.assertEqual(self.journal.get(), original)
        self.assertEqual((events[-1]['errorSource'], events[-1]['httpStatus']), ('BACKEND', 409))
        self.assertEqual(sum(path == '/api/embed' for _, path, _ in self.model.calls), 1)

    def test_backend_rejection_retains_original_claim_payload_and_status(self):
        with patch.object(self.backend, 'call', side_effect=w.Rejected('BACKEND', 403)):
            events = self.run_once()
        state = self.journal.get()
        self.assertEqual((state['phase'], state['reason'], state['errorSource'], state['httpStatus']),
                         ('RECOVERY_REQUIRED', 'BACKEND_REJECTED_OR_EXPIRED', 'BACKEND', 403))
        self.assertEqual(w.decode(state['request']), {'runner': 'runner-1'})
        self.assertEqual((events[-1]['errorSource'], events[-1]['httpStatus']), ('BACKEND', 403))
        self.assertEqual(self.model.calls, [])

    def test_backend_server_failure_keeps_exact_unconfirmed_claim_retryable(self):
        with patch.object(self.backend, 'call', side_effect=w.HttpFailure('BACKEND', 503)):
            events = self.run_once()
        self.assertEqual(self.journal.get(), {'phase': 'CLAIM_INTENT', 'request': w.encode({'runner': 'runner-1'}).decode()})
        self.assertEqual((events[-1]['errorSource'], events[-1]['httpStatus']), ('BACKEND', 503))
        self.assertEqual(self.model.calls, [])

    def test_recovery_does_not_renew_but_live_phases_still_send_heartbeats(self):
        original = {'phase': 'RECOVERY_REQUIRED', 'task': task(), 'reason': 'INFERENCE_OUTCOME_UNKNOWN',
                    'errorSource': 'MODEL', 'httpStatus': 400}
        self.journal.set(original)
        stop = Mock()
        stop.wait.side_effect = [False, True]
        w.heartbeat([self.runner], stop)
        self.assertEqual(self.backend.calls, [])
        self.assertEqual(self.journal.get(), original)
        for phase in ('CLAIMED', 'COMPUTE_INTENT', 'COMPLETE_INTENT'):
            self.journal.set({'phase': phase, 'task': task()})
            stop.wait.side_effect = [False, True]
            with patch.object(self.backend, 'call') as call:
                w.heartbeat([self.runner], stop)
            call.assert_called_once()
            self.assertTrue(call.call_args.args[1].endswith('/heartbeat'))

    def test_drain_stops_only_idle_new_claims(self):
        marker = Path(self.directory.name) / 'drain.json'
        with patch.object(w, 'DRAIN', marker):
            marker.write_text('{}')
            self.runner.step()
            self.assertEqual(self.journal.get(), {'phase': 'IDLE'})
            self.assertEqual(self.backend.calls, [])
            self.assertEqual(self.model.calls, [])
            marker.unlink()
            marker.with_name('drain-receipt.json').write_text('{}')
            self.runner.step()
            self.assertEqual(self.backend.calls, [])

    def test_drain_allows_unknown_complete_and_exit_to_converge(self):
        marker = Path(self.directory.name) / 'drain.json'
        with patch.object(w, 'DRAIN', marker):
            self.backend.drop_complete = True
            with self.assertRaises(w.WorkerError):
                self.runner.step()
            marker.write_text('{}')
            self.runner.step()
            self.assertTrue(self.backend.exited)
            self.assertEqual(self.journal.get(), {'phase': 'IDLE'})
            calls = len(self.backend.calls)
            self.runner.step()
            self.assertEqual(len(self.backend.calls), calls)
            self.assertEqual(sum(path == '/api/embed' for _, path, _ in self.model.calls), 1)

    def test_drain_allows_authoritative_recovery_and_preserves_original_journal(self):
        marker = Path(self.directory.name) / 'drain.json'
        original = {'phase': 'RECOVERY_REQUIRED', 'task': task(), 'reason': 'INFERENCE_INTERRUPTED'}
        self.journal.set(original)
        raw = self.journal.db.execute('SELECT value FROM state').fetchone()[0]
        self.backend.recovery_receipt = {'taskNo': task()['taskNo'], 'generation': 1, 'inputHash': task()['inputHash'],
                'startedAt': task()['startedAt'], 'state': 'FAILED', 'exitConfirmed': True}
        with patch.object(w, 'DRAIN', marker):
            marker.write_text('{}')
            self.runner.step()
            self.assertEqual(self.journal.get(), {'phase': 'IDLE'})
            self.assertEqual(self.journal.db.execute('SELECT original_json FROM recovery_history').fetchone()[0], raw)
            self.runner.step()
            self.assertEqual(len(self.backend.calls), 1)
            self.assertEqual(self.model.calls, [])

    def test_success_has_real_float32_payload_and_exit_only_after_verified(self):
        self.runner.step()
        self.assertEqual(self.journal.get(), {'phase': 'IDLE'})
        body = next(body for _, path, body in self.backend.calls if path.endswith('/complete'))
        values = struct.unpack('<3072f', base64.b64decode(w.decode(body)['resultArtifactBase64']))
        self.assertEqual(set(values), {0.25})
        self.assertTrue(self.backend.exited)
        self.assertEqual([path for _, path, _ in self.model.calls], ['/api/tags', '/api/embed', '/api/tags'])

    def test_lost_claim_reuses_original_payload_and_never_changes_runner(self):
        self.backend.drop_claim = True
        with self.assertRaises(w.WorkerError):
            self.runner.step()
        original = self.journal.get()
        self.assertEqual(original['phase'], 'CLAIM_INTENT')
        self.assertEqual(self.model.calls, [])
        self.runner.step()
        claims = [body for _, path, body in self.backend.calls if path.endswith('/next-task')]
        self.assertEqual(claims, [original['request'].encode()] * 2)

    def test_lost_complete_keeps_exact_payload_and_receipt_avoids_duplicate_compute(self):
        self.backend.drop_complete = True
        with self.assertRaises(w.WorkerError):
            self.runner.step()
        original = self.journal.get()
        self.assertEqual(original['phase'], 'COMPLETE_INTENT')
        self.assertFalse(self.backend.exited)
        self.runner.step()
        self.assertEqual(sum(path == '/api/embed' for _, path, _ in self.model.calls), 1)
        self.assertEqual(sum(path.endswith('/complete') for _, path, _ in self.backend.calls), 1)
        self.assertTrue(self.backend.exited)

    def test_unknown_complete_resends_same_timestamp_and_bytes_without_recompute(self):
        self.backend.drop_complete = True
        with self.assertRaises(w.WorkerError):
            self.runner.step()
        original = self.journal.get()['request'].encode()
        self.backend.verified = None
        self.runner.step()
        self.assertEqual([body for _, path, body in self.backend.calls if path.endswith('/complete')], [original, original])
        self.assertEqual(sum(path == '/api/embed' for _, path, _ in self.model.calls), 1)

    def test_timeout_or_restart_during_inference_never_recomputes_or_releases_permit(self):
        self.model.failure = TimeoutError()
        with self.assertRaises(TimeoutError):
            self.runner.step()
        self.assertEqual(self.journal.get()['phase'], 'RECOVERY_REQUIRED')
        with self.assertRaises(w.WorkerError):
            self.runner.step()
        self.assertFalse(self.backend.exited)
        self.assertEqual(sum(path == '/api/embed' for _, path, _ in self.model.calls), 1)
        self.journal.set({'phase': 'COMPUTE_INTENT', 'task': task()})
        restored = w.Runner('runner-1', self.backend, self.model, self.journal, self.stop)
        with self.assertRaises(w.WorkerError):
            restored.step()
        self.assertEqual(self.journal.get()['reason'], 'INFERENCE_INTERRUPTED')
        self.assertFalse(self.backend.exited)

    def test_observer_exit_is_only_recovery_authority_and_preserves_raw_evidence_before_next_job(self):
        original = {'phase': 'RECOVERY_REQUIRED', 'task': task(), 'reason': 'INFERENCE_OUTCOME_UNKNOWN', 'originalPayload': 'retained'}
        self.journal.set(original)
        raw = self.journal.db.execute('SELECT value FROM state').fetchone()[0]
        receipt = {'taskNo': task()['taskNo'], 'generation': 1, 'inputHash': task()['inputHash'], 'startedAt': task()['startedAt'],
                   'state': 'FAILED', 'exitConfirmed': False}
        self.backend.recovery_receipt = receipt
        self.runner.step()
        self.assertEqual(self.journal.get(), original)
        self.assertEqual(self.model.calls, [])
        self.assertFalse(any(path.endswith('/next-task') or path.endswith('/exited') for _, path, _ in self.backend.calls))
        self.backend.recovery_receipt = {**receipt, 'exitConfirmed': True, 'generation': 2}
        with self.assertRaisesRegex(w.WorkerError, 'RECOVERY_RECEIPT_BINDING_INVALID'):
            self.runner.step()
        self.assertEqual(self.journal.get(), original)
        self.backend.recovery_receipt = {**receipt, 'exitConfirmed': True}
        self.runner.step()
        self.assertEqual(self.journal.get(), {'phase': 'IDLE'})
        self.assertEqual(self.journal.db.execute('SELECT original_json FROM recovery_history').fetchone()[0], raw)
        self.backend.recovery_receipt = None
        self.runner.step()
        self.assertEqual(sum(path == '/api/embed' for _, path, _ in self.model.calls), 1)

    def test_reconciled_original_completion_uses_normal_exit_without_recompute_or_observer(self):
        self.backend.drop_complete = True
        with self.assertRaises(w.WorkerError):
            self.runner.step()
        original = {**self.journal.get(), 'phase': 'RECOVERY_REQUIRED', 'reason': 'BACKEND_REJECTED_OR_EXPIRED'}
        self.journal.set(original)
        receipt = {**self.backend.verified, 'generation': 1, 'inputHash': task()['inputHash'], 'startedAt': task()['startedAt'], 'exitConfirmed': False}
        self.backend.recovery_receipt = {**receipt, 'resultHash': 'wrong'}
        with self.assertRaisesRegex(w.WorkerError, 'RECOVERY_RESULT_CONFLICT'):
            self.runner.step()
        self.assertEqual(self.journal.get(), original)
        self.backend.recovery_receipt = receipt
        self.runner.step()
        self.assertEqual(self.journal.get()['phase'], 'VERIFIED')
        self.assertFalse(self.backend.exited)
        self.runner.step()
        self.assertEqual(self.journal.get(), {'phase': 'IDLE'})
        self.assertTrue(self.backend.exited)
        self.assertEqual(sum(path == '/api/embed' for _, path, _ in self.model.calls), 1)
        self.assertEqual(w.decode(self.journal.db.execute('SELECT original_json FROM recovery_history').fetchone()[0]), original)

    def test_lost_exit_ack_resends_same_exit_without_new_claim_or_inference(self):
        self.backend.drop_exit = True
        with self.assertRaises(w.WorkerError):
            self.runner.step()
        self.assertEqual(self.journal.get()['phase'], 'VERIFIED')
        self.runner.step()
        self.assertEqual(sum(path.endswith('/next-task') for _, path, _ in self.backend.calls), 1)
        self.assertEqual(sum(path == '/api/embed' for _, path, _ in self.model.calls), 1)
        exits = [body for _, path, body in self.backend.calls if path.endswith('/exited')]
        self.assertEqual(exits[0], exits[1])

    def test_unconfirmed_start_waits_without_inference_and_retains_original_claim(self):
        self.backend.claim_state = 'STARTING'
        self.runner.step()
        self.assertEqual(self.journal.get()['phase'], 'CLAIM_INTENT')
        self.assertEqual(self.model.calls, [])
        self.backend.claim_state = 'COMPUTING'
        self.runner.step()
        self.assertEqual(sum(path == '/api/embed' for _, path, _ in self.model.calls), 1)

    def test_invalid_vectors_and_rebound_claim_cannot_create_completion(self):
        self.assertEqual(w.manifest({**task(), 'executionTimeoutSec': 172800})['executionTimeoutSec'], 172800)
        for fields in ({'computeContainerId': 'unknown'}, {'validatorContainerId': '1' * 64}, {'startedAt': None}):
            with self.assertRaises(w.WorkerError):
                w.manifest({**task(), **fields})
        for vectors in ([[0.0] * 1024] * 3, [[True] * 1024] * 3, [[float('nan')] * 1024] * 3,
                        [[1e100] * 1024] * 3, [[1e-100] * 1024] * 3):
            with self.assertRaises(w.WorkerError):
                w.artifact(vectors)
        self.backend.claim_state = 'STARTING'
        self.runner.step()
        state = self.journal.get()
        state['task']['generation'] = 2
        self.journal.set(state)
        self.backend.claim_state = 'COMPUTING'
        with self.assertRaisesRegex(w.WorkerError, 'CLAIM_TASK_CHANGED'):
            self.runner.step()
        self.assertEqual(self.model.calls, [])

    def test_six_concurrent_runners_keep_distinct_model_and_journal_ownership(self):
        barrier = threading.Barrier(6)
        class ConcurrentModel(Model):
            def call(self, method, path, **kwargs):
                if path == '/api/embed':
                    barrier.wait(5)
                return super().call(method, path, **kwargs)
        journals = [w.Journal(Path(self.directory.name) / ('runner' + str(i) + '.sqlite')) for i in range(6)]
        models = [ConcurrentModel() for _ in range(6)]
        runners = [w.Runner('runner-' + str(i + 1), Backend(), models[i], journals[i], self.stop) for i in range(6)]
        failures = []
        def execute(runner):
            try:
                runner.step()
            except Exception as error:
                failures.append(error)
        threads = [threading.Thread(target=execute, args=(runner,)) for runner in runners]
        try:
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join(10)
            self.assertEqual(failures, [])
            self.assertTrue(all(not thread.is_alive() for thread in threads))
            self.assertTrue(all(journal.get()['phase'] == 'IDLE' for journal in journals))
            self.assertEqual([sum(path == '/api/embed' for _, path, _ in model.calls) for model in models], [1] * 6)
        finally:
            for journal in journals:
                journal.close()


if __name__ == '__main__':
    unittest.main()

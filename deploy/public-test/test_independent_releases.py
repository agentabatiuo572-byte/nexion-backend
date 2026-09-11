"""Real journal/filesystem fixtures, no production commands or database access."""
import hashlib
import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch
import zipfile

import release_broker as b


class IndependentReleasesTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.nginx = self.root / 'nginx'
        self.nginx.write_bytes(b'proxy_pass http://127.0.0.1:3003;\nproxy_pass http://127.0.0.1:8083;\n')
        self.dropin = self.root / 'backend.conf'
        self.dropin.write_text('old dropin')
        self.state = {kind: {'build': 1, 'sha': 'b'*40, 'port': port,
                             'container': f'nexgrid-cd-{kind}-1'}
                      for kind, port in [('backend', 8110), ('pc', 3003), ('uniapp', 8083)]}
        self.state['nginx_sha256'] = b.digest(self.nginx)
        b.save(self.root / 'state.json', self.state)
        for name, value in [('ROOT', self.root), ('JOBS', self.root / 'jobs'),
                            ('NGINX', self.nginx), ('DROPIN', self.dropin)]:
            patcher = patch.object(b, name, value)
            patcher.start()
            self.addCleanup(patcher.stop)
        self.config = {'job_hashes': {}, 'policy_sha256': hashlib.sha256(b'policy').hexdigest()}
        migration = patch.object(b.migrations, 'apply', return_value=None)
        migration.start()
        self.addCleanup(migration.stop)
        for name in ('allow_candidate_start', 'revoke_candidate_start'):
            patcher = patch.object(b.migrations, name)
            patcher.start()
            self.addCleanup(patcher.stop)
        for kind in b.ARTIFACTS:
            job = b.JOBS / f'nexgrid-{kind}-main'
            artifacts = job / 'builds/2/archive/artifacts'
            artifacts.mkdir(parents=True)
            (job / 'config.xml').write_text('trusted job ' + kind)
            self.config['job_hashes'][kind] = b.digest(job / 'config.xml')
            (job / 'builds/2/build.xml').write_text(
                '<flow-build><result>SUCCESS</result><completed>true</completed><actions>'
                '<hudson.plugins.git.util.BuildData><SHA1>' + 'a'*40 + '</SHA1>'
                '<name>origin/main</name></hudson.plugins.git.util.BuildData></actions></flow-build>')
            artifact = artifacts / b.ARTIFACTS[kind]
            if kind == 'backend':
                with zipfile.ZipFile(artifact, 'w') as jar:
                    jar.writestr('BOOT-INF/classes/public-test-policy.properties', b'policy')
                    jar.writestr('BOOT-INF/classes/ffdd/opsconsole/PublicTestDeploymentSafety.class', b'fixture')
            else:
                artifact.write_bytes(b'bad archive')
            b.save(artifacts / 'release.json', {'version': 1, 'component': kind, 'branch': 'main',
                   'sha': 'a'*40, 'artifact': b.ARTIFACTS[kind], 'sha256': b.digest(artifact), 'schema': 'c'*64})
            (self.root / kind).mkdir()

    def journal(self, phase='APPLYING', kind='pc'):
        journal = {'phase': phase, 'component': kind, 'old_state': self.state,
                   'candidate': f'nexgrid-cd-{kind}-2', 'old_port': self.state[kind]['port'],
                   'old_nginx': self.nginx.read_text()}
        b.save(self.root / 'transaction.json', journal)
        return journal

    def test_backend_preflight_failure_does_not_block_pc_or_uniapp(self):
        called = []
        def promote(kind, *_):
            called.append(kind)
            if kind == 'backend':
                raise b.Rejected('BUILD_IS_NOT_CURRENT_MAIN')
        with patch.object(b, 'promote', side_effect=promote):
            b.poll(self.config, self.state)
        self.assertEqual(called, ['backend', 'pc', 'uniapp'])
        failures = json.loads((self.root / 'component-failures.json').read_text())
        self.assertTrue(failures['backend']['retryable'])

    def test_failed_build_does_not_block_other_repositories(self):
        path = b.JOBS / 'nexgrid-backend-main/builds/2/build.xml'
        path.write_text(path.read_text().replace('SUCCESS', 'FAILURE'))
        with patch.object(b, 'promote') as promote:
            b.poll(self.config, self.state)
        self.assertEqual([c.args[0] for c in promote.call_args_list], ['pc', 'uniapp'])

    def test_failed_artifact_is_not_recut_over_until_new_build(self):
        def failed(*_):
            raise b.ComponentFailed('ROLLED_BACK: HEALTH_FAILED')
        with patch.object(b, 'promote', side_effect=failed) as promote:
            b.poll(self.config, self.state)
            b.poll(self.config, self.state)
            self.assertEqual(promote.call_count, 3)
        build = b.JOBS / 'nexgrid-uniapp-main/builds'
        (build / '3').mkdir()
        (build / '3/build.xml').write_bytes((build / '2/build.xml').read_bytes())
        with patch.object(b, 'promote') as promote:
            b.poll(self.config, self.state)
            self.assertEqual(promote.call_args.args[:2], ('uniapp', 3))

    def test_incomplete_transaction_or_halt_stops_other_components(self):
        for marker in ['HALTED.json', 'transaction.json']:
            with self.subTest(marker=marker):
                def failed(*_):
                    (self.root / marker).write_text('{}')
                    raise RuntimeError('fixture')
                with patch.object(b, 'promote', side_effect=failed) as promote:
                    with self.assertRaisesRegex(b.Rejected, 'RELEASE_RECOVERY_REQUIRED'):
                        b.poll(self.config, self.state)
                    self.assertEqual(promote.call_count, 1)
                (self.root / marker).unlink()

    def test_healthy_rollback_clears_journal_without_global_halt(self):
        journal = self.journal()
        with patch.object(b, 'restore') as restore, patch.object(b, 'health'):
            with self.assertRaisesRegex(b.ComponentFailed, 'ROLLED_BACK'):
                b.apply_with_rollback(journal, lambda: b.require(False, 'APPLY_FAILED'), 3004, 3003)
        restore.assert_called_once_with(journal)
        self.assertFalse((self.root / 'transaction.json').exists())
        self.assertFalse((self.root / 'HALTED.json').exists())
        self.assertEqual(json.loads((self.root / 'state.json').read_text()), self.state)

    def test_old_health_failure_cannot_be_marked_restored(self):
        journal = self.journal()
        with patch.object(b, 'restore'), patch.object(b, 'health', side_effect=b.Rejected('OLD_UNHEALTHY')):
            with self.assertRaises(b.Rejected):
                b.apply_with_rollback(journal, lambda: b.require(False, 'APPLY_FAILED'), 3004, 3003)
        self.assertTrue((self.root / 'transaction.json').exists())
        self.assertTrue((self.root / 'HALTED.json').exists())

    def test_staging_failure_cleans_candidate_without_touching_ingress(self):
        before = self.nginx.read_bytes()
        with patch.object(b, 'run', return_value='a'*40 + ' refs/heads/main') as run, \
                patch.object(b.shutil, 'disk_usage', return_value=SimpleNamespace(free=100*1024**3)), \
                patch.object(b, 'stop_candidate') as stop, patch.object(b, 'health') as health:
            with self.assertRaisesRegex(b.ComponentFailed, 'STAGING_FAILED'):
                b.promote('pc', 2, self.config, self.state)
        stop.assert_called_once_with('nexgrid-cd-pc-2')
        health.assert_not_called()
        self.assertEqual(self.nginx.read_bytes(), before)
        self.assertFalse((self.root / 'transaction.json').exists())
        self.assertFalse((self.root / 'HALTED.json').exists())
        self.assertTrue(all(c.args[0] == 'git' for c in run.call_args_list))

    def test_staging_drift_does_not_clear_recovery_evidence(self):
        journal = self.journal('STAGING')
        self.nginx.write_text('unexpected configuration')
        with patch.object(b, 'stop_candidate') as stop:
            with self.assertRaisesRegex(b.Rejected, 'STAGING_NGINX_DRIFT'):
                b.discard_staging(journal)
        stop.assert_not_called()
        self.assertTrue((self.root / 'transaction.json').exists())

    def test_main_backend_without_schema_approval_reaches_normal_build_validation(self):
        # No config['schema']; the old source-fingerprint gate would fail here.
        with patch.object(b, 'run', return_value='a'*40 + ' refs/heads/main') as run, \
                patch.object(b.shutil, 'disk_usage', return_value=SimpleNamespace(free=100*1024**3)), \
                patch.object(b, 'apply_with_rollback'), patch.object(b, 'finish_commit'):
            b.promote('backend', 2, self.config, self.state)
        self.assertEqual(self.state['backend']['sha'], 'a'*40)
        self.assertEqual(run.call_count, 1)
        self.assertEqual(run.call_args.args[:2], ('git', 'ls-remote'))
        self.assertTrue((self.root / 'backend/2-aaaaaaaaaaaa/app/nexion-backend.jar').is_file())

    def test_policy_guard_still_blocks_backend_and_leaves_no_transaction(self):
        self.config['policy_sha256'] = '0'*64
        with patch.object(b, 'run', return_value='a'*40 + ' refs/heads/main'), \
                patch.object(b.shutil, 'disk_usage', return_value=SimpleNamespace(free=100*1024**3)):
            with self.assertRaisesRegex(b.ComponentFailed, 'JAR_POLICY_REJECTED'):
                b.promote('backend', 2, self.config, self.state)
        self.assertFalse((self.root / 'transaction.json').exists())
        self.assertFalse((self.root / 'HALTED.json').exists())


if __name__ == '__main__':
    unittest.main()

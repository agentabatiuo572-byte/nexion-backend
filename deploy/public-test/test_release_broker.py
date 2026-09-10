"""Isolated adversarial tests; no Docker, systemd, network or business database."""
import io
import json
import runpy
from pathlib import Path
import tarfile
import tempfile
import unittest
from unittest.mock import patch
import release_broker as b
from schema_fingerprint import guarded


class ReleaseBrokerTests(unittest.TestCase):
    def test_draft_broker_rejects_before_any_host_command(self):
        with patch.object(b, 'run') as command:
            with self.assertRaisesRegex(b.Rejected, 'TRUSTED_ENTRY_REQUIRED'):
                b.main()
            command.assert_not_called()

    def test_draft_installer_rejects_before_local_module_import(self):
        with self.assertRaisesRegex(SystemExit, 'TRUSTED_ENTRY_REQUIRED'):
            runpy.run_path(str(Path(__file__).with_name('install_release.py')), run_name='__main__')

    def test_ci_recreates_only_controller_and_agent_with_both_compose_files(self):
        import install_release as installer
        with patch.object(b, 'run') as command:
            installer.recreate_ci()
        args = command.call_args.args
        self.assertEqual(args[-2:], ('jenkins', 'agent'))
        self.assertIn('--force-recreate', args)
        self.assertIn('--no-deps', args)
        self.assertIn('--no-build', args)
        self.assertEqual(args.count('-f'), 2)

    def test_enabling_requires_rollback_evidence_for_the_deployed_sha(self):
        import install_release as installer
        state = {kind: {'build': 1, 'sha': 'a'*40, 'port': 1} for kind in b.ARTIFACTS}
        (self.root / 'state.json').write_text(json.dumps(state))
        (self.root / 'rollback-verified.json').write_text(json.dumps({'backend': {'sha': 'b'*40}}))
        with patch.object(b, 'ROOT', self.root), patch.object(b, 'run') as command:
            with self.assertRaisesRegex(b.Rejected, 'ROLLBACK_SHA_NOT_VERIFIED'):
                installer.enable()
            command.assert_not_called()
        self.assertFalse((self.root / 'AUTO_ENABLED').exists())

    def test_ci_partial_job_configuration_is_detected_even_with_marker(self):
        import install_release as installer
        (self.root / 'nexgrid-release-v1-jobs-configured').write_text('fixture')
        jobs = self.root / 'jobs'
        for kind in b.ARTIFACTS:
            job = jobs / f'nexgrid-{kind}-main'
            job.mkdir(parents=True)
            (job / 'config.xml').write_text('DEPLOYMENT_HELD' if kind == 'pc' else 'RELEASE_ARTIFACT_READY')
        with patch.object(installer, 'HOME', self.root), patch.object(b, 'JOBS', jobs):
            with self.assertRaisesRegex(b.Rejected, 'PARTIAL_CI_JOB_CONFIGURATION'):
                installer.verify_ci_hook()

    def test_rollback_check_refuses_auto_enabled_before_any_staging(self):
        (self.root / 'AUTO_ENABLED').write_text('enabled')
        with patch.object(b, 'ROOT', self.root), patch.object(b, 'run') as command:
            with self.assertRaisesRegex(b.Rejected, 'ROLLBACK_CHECK_REQUIRES_AUTO_HELD'):
                b.promote('pc', 1, {}, {}, rollback_check=True)
            command.assert_not_called()

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)

    def tearDown(self):
        self.temp.cleanup()

    def archive(self, name='server.js', kind=tarfile.REGTYPE, link='', mode=0o644):
        source = self.root / 'payload.tgz'
        with tarfile.open(source, 'w:gz') as archive:
            member = tarfile.TarInfo(name)
            member.type, member.linkname, member.mode = kind, link, mode
            data = b'console.log("unit fixture")'
            member.size = len(data) if kind == tarfile.REGTYPE else 0
            archive.addfile(member, io.BytesIO(data) if member.size else None)
        return source

    def test_accepts_plain_files_and_normalizes_permissions(self):
        dest = self.root / 'out'
        b.safe_extract(self.archive(mode=0o7777), dest)
        self.assertTrue((dest / 'server.js').is_file())
        self.assertEqual((dest / 'server.js').stat().st_mode & 0o111, 0)

    def test_rejects_traversal_absolute_backslash_and_secret_files(self):
        for name in ('../escaped', '/tmp/escaped', 'a/../../escaped', 'a\\escaped', '.env', 'a/.env.production'):
            with self.subTest(name=name), self.assertRaises(b.Rejected):
                b.safe_extract(self.archive(name), self.root / 'out')

    def test_rejects_links_and_device_entries(self):
        for kind in (tarfile.SYMTYPE, tarfile.LNKTYPE, tarfile.CHRTYPE, tarfile.FIFOTYPE):
            with self.subTest(kind=kind), self.assertRaises(b.Rejected):
                b.safe_extract(self.archive(kind=kind, link='/etc/passwd'), self.root / 'out')

    def test_rejects_duplicate_entries(self):
        source = self.root / 'dup.tar'
        with tarfile.open(source, 'w') as archive:
            for _ in range(2):
                archive.addfile(tarfile.TarInfo('same'))
        with self.assertRaises(b.Rejected):
            b.safe_extract(source, self.root / 'out')

    def test_rejects_oversize_before_extraction(self):
        with patch.object(b, 'MAX_EXPANDED', 1), self.assertRaises(b.Rejected):
            b.safe_extract(self.archive(), self.root / 'out')

    def test_requires_whole_build_success_completion_main_and_matching_sha(self):
        sha = 'a' * 40
        xml = ('<flow-build><result>SUCCESS</result><completed>true</completed>'
               '<actions><hudson.plugins.git.util.BuildData><lastBuiltRevision><SHA1>' + sha +
               '</SHA1><branches><hudson.plugins.git.Branch><name>origin/main</name>'
               '</hudson.plugins.git.Branch></branches></lastBuiltRevision></hudson.plugins.git.util.BuildData>'
               '</actions></flow-build>')
        self.assertEqual(b.build_identity(xml.encode()), sha)
        for wrong in (xml.replace('SUCCESS', 'UNSTABLE'), xml.replace('SUCCESS', 'FAILURE'),
                      xml.replace('<completed>true', '<completed>false'), xml.replace('origin/main', 'origin/dev'),
                      '<flow-build><actions><result>SUCCESS</result></actions></flow-build>'):
            with self.assertRaises(b.Rejected):
                b.build_identity(wrong.encode())

    def test_rejects_xml_doctype_and_entities(self):
        with self.assertRaises(b.Rejected):
            b.build_identity(b'<!DOCTYPE x [<!ENTITY p SYSTEM "file:///etc/passwd">]><x>&p;</x>')

    def test_route_swap_is_exact_and_fails_if_unexpected(self):
        nginx = 'proxy_pass http://127.0.0.1:3002;\nproxy_pass http://127.0.0.1:8110;\n'
        changed = b.swap_upstream(nginx, 3002, 3003)
        self.assertIn('127.0.0.1:3003;', changed)
        self.assertIn('127.0.0.1:8110;', changed)
        with self.assertRaises(b.Rejected):
            b.swap_upstream(nginx, 3004, 3003)

    def test_transaction_rolls_back_on_health_failure(self):
        events = []
        def unhealthy():
            events.append('check'); raise b.Rejected('unhealthy')
        with self.assertRaises(b.Rejected):
            b.transaction(lambda: events.append('apply'), unhealthy,
                          lambda: events.append('rollback'), lambda: events.append('verify-old'))
        self.assertEqual(events, ['apply', 'check', 'rollback', 'verify-old'])

    def test_transaction_rolls_back_on_partial_apply(self):
        events = []
        def partial():
            events.append('partial'); raise RuntimeError('unit fixture')
        with self.assertRaises(RuntimeError):
            b.transaction(partial, lambda: events.append('check'),
                          lambda: events.append('rollback'), lambda: events.append('verify-old'))
        self.assertEqual(events, ['partial', 'rollback', 'verify-old'])

    def test_success_does_not_roll_back(self):
        events = []
        b.transaction(lambda: events.append('apply'), lambda: events.append('healthy'),
                      lambda: events.append('rollback'), lambda: events.append('verify-old'))
        self.assertEqual(events, ['apply', 'healthy'])

    def test_frontend_restore_uses_running_old_container_and_restores_ingress(self):
        journal = {'component': 'pc', 'old_state': {'pc': {'container': 'nexgrid-pc'}},
                   'old_port': 3002, 'old_nginx': 'old nginx', 'candidate': 'nexgrid-cd-pc-9'}
        nginx = self.root / 'nginx.conf'
        nginx.write_text('candidate nginx')
        with patch.object(b, 'NGINX', nginx), patch.object(b, 'health'), \
                patch.object(b, 'run', return_value='true') as command, patch.object(b, 'stop_candidate') as stop:
            b.restore(journal)
        self.assertEqual(nginx.read_text(), 'old nginx')
        self.assertFalse(any(c.args[:2] == ('docker', 'start') for c in command.call_args_list))
        self.assertTrue(any(c.args == ('systemctl', 'reload', 'nginx') for c in command.call_args_list))
        stop.assert_called_once_with('nexgrid-cd-pc-9')

    def test_manifest_requires_exact_component_main_and_policy(self):
        manifest = {'version': 1, 'component': 'backend', 'branch': 'main', 'sha': 'a'*40,
                    'artifact': 'backend.jar', 'sha256': 'b'*64, 'schema': 'c'*64}
        self.assertEqual(b.validate_manifest(manifest, 'backend', 'a'*40), manifest)
        for field, value in [('branch', 'dev'), ('component', 'pc'), ('sha', 'd'*40),
                             ('artifact', '../../secret'), ('version', 0), ('sha256', 'invalid')]:
            with self.subTest(field=field), self.assertRaises(b.Rejected):
                b.validate_manifest({**manifest, field: value}, 'backend', 'a'*40)

    def test_sha256_mismatch_cannot_be_promoted(self):
        artifact = self.archive()
        with self.assertRaises(b.Rejected):
            b.verify_digest(artifact, '0'*64)

    def test_schema_gate_includes_real_hidden_startup_ddl_and_persistence_inputs(self):
        for path, content in [
            ('src/main/java/ffdd/opsconsole/auth/application/AppUserAuthService.java', b'@PostConstruct void ensureLoginGuardSchema() {}'),
            ('src/main/java/ffdd/opsconsole/auth/mapper/UserLoginGuardMapper.java', b'createTable()'),
            ('src/main/java/example/ordinary/Service.java', b'JdbcTemplate jdbc;'),
            ('src/main/java/example/ordinary/Service.java', b'create table sample(id bigint)'),
            ('src/main/resources/application.yml', b'spring:'), ('pom.xml', b'<project/>')]:
            self.assertTrue(guarded(path, content), path)
        self.assertFalse(guarded('src/main/java/example/web/ReadOnlyController.java', b'return dto;'))

    def test_current_jenkins_builddata_reference_format_is_verified(self):
        data = ('<flow-build><result>SUCCESS</result><completed>true</completed><actions>'
                '<hudson.plugins.git.util.BuildData><buildsByBranchName><entry><hudson.plugins.git.util.Build>'
                '<marked><sha1>' + 'a'*40 + '</sha1><branches><hudson.plugins.git.Branch>'
                '<sha1 reference="../../../sha1"/><name>refs/remotes/origin/main</name>'
                '</hudson.plugins.git.Branch></branches></marked><revision reference="../marked"/>'
                '</hudson.plugins.git.util.Build></entry></buildsByBranchName>'
                '</hudson.plugins.git.util.BuildData></actions></flow-build>')
        self.assertEqual(b.build_identity(data.encode()), 'a'*40)

    def test_crash_after_healthy_commit_converges_to_new_state_without_rollback(self):
        journal = {'phase': 'HEALTHY', 'component': 'pc', 'new_state': {'pc': {'port': 3003}},
                   'old_state': {'pc': {'port': 3002}}, 'candidate': 'nexgrid-cd-pc-9'}
        with patch.object(b, 'ROOT', self.root), patch.object(b, 'health'), \
                patch.object(b, 'restore') as restore, patch.object(b, 'finish_commit') as finish:
            b.recover_transaction(journal)
        restore.assert_not_called()
        finish.assert_called_once_with(journal)

    def test_interrupted_cutover_restores_both_runtime_and_old_state(self):
        journal = {'phase': 'APPLYING', 'component': 'backend',
                   'old_state': {'backend': {'port': 8110, 'sha': 'old'}}}
        with patch.object(b, 'ROOT', self.root), patch.object(b, 'restore') as restore:
            b.recover_transaction(journal)
        restore.assert_called_once_with(journal)
        self.assertEqual(json.loads((self.root / 'state.json').read_text()), journal['old_state'])
        self.assertTrue((self.root / 'HALTED.json').exists())

    def test_interrupted_staging_does_not_restart_or_replace_live_service(self):
        journal = {'phase': 'STAGING', 'component': 'pc', 'candidate': 'nexgrid-cd-pc-9', 'old_state': {}}
        with patch.object(b, 'ROOT', self.root), patch.object(b, 'restore') as restore, \
                patch.object(b, 'stop_candidate') as stop:
            b.recover_transaction(journal)
        restore.assert_not_called()
        stop.assert_called_once_with('nexgrid-cd-pc-9')


if __name__ == '__main__':
    unittest.main()

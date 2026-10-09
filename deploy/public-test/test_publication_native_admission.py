"""Offline admission/recovery checks; POSIX metadata is simulated on Windows."""
import copy
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
import time
import types
import unittest
from unittest.mock import Mock, patch
import xml.etree.ElementTree as ET
import release_broker as b


class PublicationNativeAdmissionTests(unittest.TestCase):
    negative_checks = 0

    def assertRaises(self, *args, **kwargs):
        type(self).negative_checks += 1
        return super().assertRaises(*args, **kwargs)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='synthetic-admission-draft-')
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.module = b
        patch.object(b, 'ROOT', self.root).start()
        self.metadata = {}
        original_stat = Path.stat
        def simulated_posix(path, *args, **kwargs):
            info = original_stat(path, *args, **kwargs)
            values = list(info)
            mode = 0o700 if stat.S_ISDIR(info.st_mode) else 0o600
            override = self.metadata.get(str(path), {})
            values[0] = override.get('type', stat.S_IFMT(info.st_mode)) | override.get('mode', mode)
            values[4] = override.get('uid', 0)
            values[5] = 0
            return os.stat_result(values)
        self.addCleanup(patch.stopall)
        patch.object(Path, 'stat', simulated_posix).start()
        self.sha, self.tree, self.artifact = 'a' * 40, 'b' * 40, 'c' * 64
        self.directory = self.root / 'publication-native' / self.sha / '7' / self.artifact
        self.directory.mkdir(parents=True)
        self.policy = {'gateManifestSHA256': 'd' * 64, 'entrySHA256': 'e' * 64,
                       'sourcePinsSHA256': 'f' * 64,
                       'candidateLFPins': {f'synthetic-source-{i}': '1' * 64 for i in range(13)}}
        self.config = {'publication_native': self.policy}
        self.manifest = {'tree': self.tree, 'sha256': self.artifact,
                         'publicationNative': 'OWNER_ADMISSION_REQUIRED'}
        self.xml = ET.Element('testsuite', name=self.module.PUBLICATION_CLASS,
                              tests='6', failures='0', errors='0', skipped='0')
        for name in sorted(self.module.PUBLICATION_METHODS):
            ET.SubElement(self.xml, 'testcase', name=name, classname=self.module.PUBLICATION_CLASS)
        self.receipt = {'SYNTHETIC_ONLY_NOT_ACTUAL_PROOF': True,
                        'status': 'QUALIFIED_EXACT_CANDIDATE_ONLY', 'nativeMySql': 'ACTUAL_6_0F_0E_0S',
                        'mavenExit': 0, 'entryRawSHA256': self.policy['entrySHA256'],
                        'sourcePinsRawSHA256': self.policy['sourcePinsSHA256'],
                        'startedEpoch': time.time() - 5, 'finishedEpoch': time.time() + 5,
                        'mavenArgv': ['/synthetic/mvn', '-B', '-ntp', '-o', '-Dstyle.color=never',
                                      '-Dtest=' + self.module.PUBLICATION_CLASS.rsplit('.', 1)[1],
                                      '-Dsupport.test.reportsDirectory=/synthetic/reports', 'clean', 'test'],
                        'ownership': {'rawSHA256': '2' * 64, 'resourceIdentity': 'SYNTHETIC_ONLY',
                                      'permissions': ['SYNTHETIC_ONLY'], 'databaseIdentity': {
                                          'database': 'cs_analytics_20261007', 'port': 33337,
                                          'currentUser': 'cs_analytics_runner@127.0.0.1',
                                          'serverUuid': '00000000-0000-4000-8000-000000000001',
                                          'dataDirectory': '/synthetic/not-actual'}},
                        'nativeXml': {'tests': 6, 'failures': 0, 'errors': 0, 'skipped': 0,
                                      'methods': sorted(self.module.PUBLICATION_METHODS)}}
        binding = {'head': self.sha, 'tree': self.tree, 'clean': True,
                   'sourceLFPins': self.policy['candidateLFPins']}
        self.receipt.update(sourceBefore=copy.deepcopy(binding), sourceAfter=copy.deepcopy(binding))
        self.seal = {'version': 1, 'component': 'backend', 'branch': 'test', 'build': 7,
                     'sha': self.sha, 'tree': self.tree, 'artifactSHA256': self.artifact,
                     'gateManifestSHA256': self.policy['gateManifestSHA256'], 'ownershipProofSHA256': '2' * 64}
        self.write_fixture()

    def write_fixture(self):
        xml_bytes = ET.tostring(self.xml)
        xml_hash = hashlib.sha256(xml_bytes).hexdigest()
        self.receipt['nativeXml']['rawSHA256'] = xml_hash
        self.seal['nativeXMLSHA256'] = xml_hash
        receipt_bytes = json.dumps(self.receipt).encode()
        self.seal['ownerReceiptSHA256'] = hashlib.sha256(receipt_bytes).hexdigest()
        (self.directory / 'PUBLICATION.xml').write_bytes(xml_bytes)
        (self.directory / 'NATIVE-RECEIPT.json').write_bytes(receipt_bytes)
        (self.directory / 'ADMISSION.json').write_text(json.dumps(self.seal))

    def invoke(self):
        return self.module.require_native_publication('backend', 7, self.sha, self.manifest, self.config)

    def test_synthetic_acceptance_and_no_receipt_creation(self):
        self.assertEqual(self.invoke()['status'], 'ADMITTED_BY_ROOT_OWNER')
        self.assertEqual(len(list(self.directory.iterdir())), 3)
        self.assertIsNone(self.module.require_native_publication('pc', 7, self.sha, {}, {}))

    def test_missing_owner_cannot_be_replaced_by_ci_success(self):
        (self.directory / 'ADMISSION.json').unlink()
        self.manifest.update(nativeStatus='PASS', ciResult='SUCCESS')
        with self.assertRaises(self.module.Rejected):
            self.invoke()
        self.assertFalse((self.directory / 'ADMISSION.json').exists())

    def test_exact_binding_negative_matrix(self):
        for key, value in [('sha', '9' * 40), ('tree', '9' * 40), ('build', 8),
                           ('artifactSHA256', '9' * 64), ('branch', 'main'),
                           ('gateManifestSHA256', '9' * 64)]:
            with self.subTest(key=key):
                original = self.seal[key]
                self.seal[key] = value
                self.write_fixture()
                with self.assertRaises(self.module.Rejected): self.invoke()
                self.seal[key] = original

    def test_qualification_and_fresh_command_negative_matrix(self):
        for key, value in [('status', 'HOLD'), ('nativeMySql', 'NOT_RUN'), ('mavenExit', 1),
                           ('mavenExit', False), ('entryRawSHA256', '9' * 64),
                           ('sourcePinsRawSHA256', '9' * 64), ('startedEpoch', float('nan')),
                           ('finishedEpoch', float('inf')),
                           ('mavenArgv', self.receipt['mavenArgv'][:-2] + ['test'])]:
            with self.subTest(key=key, value=value):
                original = self.receipt[key]
                self.receipt[key] = value
                self.write_fixture()
                with self.assertRaises(self.module.Rejected): self.invoke()
                self.receipt[key] = original

    def test_before_after_source_and_proof_negative_matrix(self):
        for which, key, value in [('sourceBefore', 'clean', False), ('sourceAfter', 'head', '9' * 40),
                                  ('sourceAfter', 'tree', '9' * 40), ('sourceAfter', 'sourceLFPins', {})]:
            with self.subTest(which=which, key=key):
                original = self.receipt[which][key]
                self.receipt[which][key] = value
                self.write_fixture()
                with self.assertRaises(self.module.Rejected): self.invoke()
                self.receipt[which][key] = original
        self.receipt['ownership']['databaseIdentity']['port'] = 3306
        self.write_fixture()
        with self.assertRaises(self.module.Rejected): self.invoke()

    def test_six_skips_wrong_methods_and_wrong_xml(self):
        for field, value in [('skipped', '6'), ('failures', '1'), ('errors', '1'), ('tests', '5')]:
            with self.subTest(field=field):
                self.xml.set(field, value)
                self.write_fixture()
                with self.assertRaises(self.module.Rejected): self.invoke()
                self.xml.set(field, '6' if field == 'tests' else '0')
        first = self.xml[0]
        original = first.get('name')
        first.set('name', 'wrongMethod')
        self.write_fixture()
        with self.assertRaises(self.module.Rejected): self.invoke()
        first.set('name', original)
        ET.SubElement(first, 'skipped')
        self.write_fixture()
        with self.assertRaises(self.module.Rejected): self.invoke()

    def test_raw_hash_tampering_and_stale_xml(self):
        (self.directory / 'NATIVE-RECEIPT.json').write_bytes(b'{}')
        with self.assertRaises(self.module.Rejected): self.invoke()
        self.write_fixture()
        (self.directory / 'PUBLICATION.xml').write_bytes(b'<wrong/>')
        with self.assertRaises(self.module.Rejected): self.invoke()
        self.write_fixture()
        os.utime(self.directory / 'PUBLICATION.xml', (1, 1))
        with self.assertRaises(self.module.Rejected): self.invoke()

    def test_simulated_root_private_metadata_required(self):
        for path in (self.directory, self.directory / 'ADMISSION.json',
                     self.directory / 'NATIVE-RECEIPT.json', self.directory / 'PUBLICATION.xml'):
            for override in ({'uid': 1000}, {'mode': 0o644}, {'mode': 0o620}, {'type': stat.S_IFLNK}):
                with self.subTest(path=path.name, metadata=override):
                    self.metadata[str(path)] = override
                    with self.assertRaises(self.module.Rejected): self.invoke()
                    del self.metadata[str(path)]

    def test_unconfigured_or_legacy_manifest_is_hold(self):
        for obj, key in ((self.manifest, 'tree'), (self.manifest, 'publicationNative'),
                         (self.config, 'publication_native')):
            value = obj.pop(key)
            with self.assertRaises(self.module.Rejected): self.invoke()
            obj[key] = value

    def test_integrated_promote_missing_owner_precedes_all_mutations(self):
        module = self.module
        patch.object(module, 'JOBS', self.root / 'jobs').start()
        job = module.JOBS / 'nexgrid-backend-test'
        archive = job / 'builds/7/archive/artifacts'
        archive.mkdir(parents=True)
        (job / 'config.xml').write_bytes(b'SYNTHETIC_JOB')
        xml = ('<flow-build><result>SUCCESS</result><completed>true</completed><actions>'
               '<hudson.plugins.git.util.BuildData><marked><sha1>' + self.sha + '</sha1></marked>'
               '<name>origin/test</name></hudson.plugins.git.util.BuildData></actions></flow-build>')
        (job / 'builds/7/build.xml').write_text(xml)
        manifest = {**self.manifest, 'version': 1, 'component': 'backend', 'branch': 'test',
                    'sha': self.sha, 'artifact': 'backend.jar', 'schema': '3' * 64}
        (archive / 'release.json').write_text(json.dumps(manifest))
        config = {**self.config, 'job_hashes': {'backend': module.digest(job / 'config.xml')}}
        patch.object(module, 'run', Mock(return_value=self.sha + '\trefs/heads/test')).start()
        patch.object(module, 'save', Mock(side_effect=RuntimeError('UNEXPECTED_WRITE'))).start()
        patch.object(module, 'stage_release', Mock(side_effect=RuntimeError('UNEXPECTED_STAGE'))).start()
        patch.object(module, 'migrations', types.SimpleNamespace(apply=Mock(side_effect=RuntimeError('UNEXPECTED_DB')))).start()
        (self.directory / 'ADMISSION.json').unlink()
        with self.assertRaises(module.Rejected): module.promote('backend', 7, config, {})
        module.save.assert_not_called()
        module.stage_release.assert_not_called()
        module.migrations.apply.assert_not_called()

    def test_integrated_unqualified_healthy_recovery_cannot_commit(self):
        module = self.module
        patch.object(module, 'INSTALL', self.root / 'install').start()
        module.INSTALL.mkdir()
        (module.INSTALL / 'config.json').write_text(json.dumps(self.config))
        patch.object(module, 'save', Mock(side_effect=RuntimeError('UNEXPECTED_STATE_ADVANCE'))).start()
        with self.assertRaises(module.Rejected):
            module.finish_commit({'component': 'backend', 'build': 7, 'sha': self.sha, 'phase': 'HEALTHY'})
        module.save.assert_not_called()

    def test_qualified_healthy_recovery_rechecks_admission_before_commit(self):
        module = self.module
        patch.object(module, 'INSTALL', self.root / 'install').start()
        module.INSTALL.mkdir()
        (module.INSTALL / 'config.json').write_text(json.dumps(self.config))
        (self.root / 'transaction.json').write_text('SYNTHETIC_ONLY')
        journal = {'component': 'backend', 'build': 7, 'sha': self.sha, 'phase': 'HEALTHY',
                   'publicationManifest': self.manifest, 'publicationNative': self.invoke(),
                   'new_state': {'backend': {'port': 8110}}, 'old_state': {'backend': {'port': 8110}}}
        migration = types.SimpleNamespace(active=Mock(return_value=False), complete_release=Mock())
        with patch.object(module, 'migrations', migration), patch.object(module, 'health'), \
                patch.object(module, 'save') as save, patch.object(module, 'prune_releases'), \
                patch.object(module, 'sync_directory'), patch.object(module, 'restore') as restore:
            module.recover_transaction(journal)
        save.assert_called_once_with(self.root / 'state.json', journal['new_state'])
        migration.complete_release.assert_called_once_with()
        restore.assert_not_called()
        self.assertFalse((self.root / 'transaction.json').exists())

    def test_seal_drift_blocks_healthy_commit_without_touching_state(self):
        module = self.module
        patch.object(module, 'INSTALL', self.root / 'install').start()
        module.INSTALL.mkdir()
        (module.INSTALL / 'config.json').write_text(json.dumps(self.config))
        journal = {'component': 'backend', 'build': 7, 'sha': self.sha,
                   'publicationManifest': self.manifest, 'publicationNative': self.invoke()}
        self.seal['approvedBy'] = 'SYNTHETIC_CHANGED_ROOT_SEAL'
        self.write_fixture()
        with patch.object(module, 'save') as save, self.assertRaisesRegex(module.Rejected, 'ADMISSION_DRIFT'):
            module.finish_commit(journal)
        save.assert_not_called()


if __name__ == '__main__':
    unittest.main(verbosity=2)

"""The trust gate must fail before any deployment module can execute."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import trusted_entry as t


class TrustedEntryTests(unittest.TestCase):
    def test_checked_in_runtime_lock_matches_every_real_source(self):
        with patch.object(t, 'trusted_path'):
            sources = t.verified_sources(Path(__file__).parent)
        self.assertEqual(set(sources), t.FILES)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.files = {'release_broker.py': b'raise RuntimeError("must not execute")\n',
                      'schema_fingerprint.py': b'# fixture\n'}
        for name, data in self.files.items():
            (self.root / name).write_bytes(data)
        self.lock = {'version': 1, 'files': {name: hashlib.sha256(data).hexdigest()
                                          for name, data in self.files.items()}}
        (self.root / 'runtime-lock.json').write_text(json.dumps(self.lock))

    def tearDown(self):
        self.temp.cleanup()

    def check(self):
        with patch.object(t, 'trusted_path'), patch.object(t, 'FILES', frozenset(self.files)):
            return t.verified_sources(self.root)

    def test_complete_exact_closure_is_checked_without_execution(self):
        self.assertEqual(self.check(), self.files)

    def test_tampered_source_rejected_before_execution(self):
        (self.root / 'release_broker.py').write_text('print("changed")')
        with self.assertRaisesRegex(RuntimeError, 'DIGEST'):
            self.check()

    def test_missing_and_extra_manifest_entries_rejected(self):
        for entries in ({'release_broker.py': 'a'*64}, {**self.lock['files'], '../extra': 'b'*64}):
            (self.root / 'runtime-lock.json').write_text(json.dumps({'version': 1, 'files': entries}))
            with self.assertRaisesRegex(RuntimeError, 'CLOSURE'):
                self.check()

    def test_symlink_nonroot_and_writable_entries_rejected(self):
        for mode, uid in [(0o100666, 0), (0o100644, 1000), (0o120777, 0), (0o020600, 0)]:
            from types import SimpleNamespace
            with patch.object(Path, 'lstat', return_value=SimpleNamespace(st_mode=mode, st_uid=uid)):
                with self.assertRaisesRegex(RuntimeError, 'TRUST'):
                    t.trusted_path(Path('/srv/example.py'))

    def test_insecure_parent_rejected(self):
        from types import SimpleNamespace
        with patch.object(Path, 'lstat', side_effect=[SimpleNamespace(st_mode=0o100644, st_uid=0),
                                                    SimpleNamespace(st_mode=0o40777, st_uid=0)]):
            with self.assertRaisesRegex(RuntimeError, 'TRUST'):
                t.trusted_path(Path('/srv/example.py'))


if __name__ == '__main__':
    unittest.main()

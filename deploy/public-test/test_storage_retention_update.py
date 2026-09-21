import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch

import update_storage_retention as update


class StorageRetentionUpdateTests(unittest.TestCase):
    def test_job_update_pins_test_pipeline_and_native_retention(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'config.xml'
            path.write_text(
                '<flow-definition><properties/><definition><script>'
                'echo RELEASE_ARTIFACT_READY</script></definition></flow-definition>')
            data = update.retained_job_xml(
                path,
                "checkout '@REPO@' branch test; build @KIND@; RELEASE_ARTIFACT_READY",
                'pc')
        root = ET.fromstring(data)
        self.assertIn('nexion-frontend-pc.git', root.findtext('./definition/script'))
        self.assertEqual(root.findtext(
            './properties/jenkins.model.BuildDiscarderProperty/strategy/numToKeep'), '10')
        self.assertEqual(root.findtext(
            './properties/jenkins.model.BuildDiscarderProperty/strategy/artifactNumToKeep'), '5')

    def test_atomic_failure_removes_its_temporary_file(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / 'target'
            target.write_text('old')
            with patch.object(Path, 'replace', side_effect=OSError('fixture')):
                with self.assertRaises(OSError):
                    update.atomic(target, b'new')
            self.assertEqual(target.read_text(), 'old')
            self.assertFalse((Path(directory) / 'target.storage-retention-new').exists())


if __name__ == '__main__':
    unittest.main()

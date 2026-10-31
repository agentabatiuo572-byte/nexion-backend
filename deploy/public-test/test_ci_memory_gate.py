"""Isolated real bash/awk harness; no build, network, host mutation or fake runtime knob."""
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


class CiMemoryGateTests(unittest.TestCase):
    def run_build(self, kind, meminfo, extra_env=None):
        bash = shutil.which("bash")
        if bash is None:
            git = Path(shutil.which("git"))
            bash = str(git.parent.parent / "usr/bin/bash.exe")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture, heavy = root / "meminfo", root / "heavy-command"
            fixture.write_bytes(meminfo.encode())
            source = Path(__file__).with_name("ci-build.sh").read_text()
            source = source.replace("bash /opt/nexgrid-ci/ci-network-check.sh", "true")
            # Only the test's copied shell text gets a fixture; production keeps /proc/meminfo literal.
            source = source.replace("/proc/meminfo", shlex.quote(fixture.as_posix()))
            prefix = ('PATH=/usr/bin:/bin:$PATH\n'
                      'git() { echo identical-test-sha; }\n'
                      'mvn() { echo heavy > "$CI_MEMORY_TEST_HEAVY_LOG"; exit 42; }\n'
                      'npm() { echo heavy > "$CI_MEMORY_TEST_HEAVY_LOG"; exit 42; }\n')
            env = {**os.environ, **(extra_env or {}), "JAVA17_HOME": "/isolated/java",
                   "CI_MEMORY_TEST_HEAVY_LOG": heavy.as_posix()}
            result = subprocess.run([bash, "-s", "--", kind], input=prefix + source, text=True,
                                    capture_output=True, cwd=root, env=env, timeout=10)
            return result, heavy.exists()

    def test_low_memory_blocks_every_heavy_entry_even_with_environment_overrides(self):
        for kind in ("backend", "pc", "uniapp"):
            with self.subTest(kind=kind):
                result, heavy = self.run_build(kind, "MemTotal: 1000 kB\nMemAvailable: 199 kB\n",
                                              {"MEMINFO_PATH": "/fake/high-memory", "MIN_AVAILABLE_PERCENT": "0"})
                self.assertEqual(result.returncode, 78, result.stderr)
                self.assertIn("CI_HOST_MEMORY_RESERVE_REQUIRED", result.stderr)
                self.assertFalse(heavy)

    def test_exactly_twenty_percent_reaches_every_heavy_entry(self):
        for kind in ("backend", "pc", "uniapp"):
            with self.subTest(kind=kind):
                result, heavy = self.run_build(kind, "MemTotal: 1000 kB\nMemAvailable: 200 kB\n")
                self.assertEqual(result.returncode, 42, result.stderr)
                self.assertTrue(heavy)

    def test_missing_invalid_and_duplicate_memory_fields_fail_closed(self):
        for meminfo in ("MemTotal: 1000 kB\n", "MemTotal: 1000 kB\nMemAvailable: invalid kB\n",
                        "MemTotal: 1000 kB\nMemAvailable: 200 MB\n",
                        "MemTotal: 1000 kB\nMemTotal: 1000 kB\nMemAvailable: 200 kB\n"):
            with self.subTest(meminfo=meminfo):
                result, heavy = self.run_build("backend", meminfo)
                self.assertEqual(result.returncode, 78, result.stderr)
                self.assertIn("CI_HOST_MEMORY_INVALID", result.stderr)
                self.assertFalse(heavy)


if __name__ == "__main__":
    unittest.main()

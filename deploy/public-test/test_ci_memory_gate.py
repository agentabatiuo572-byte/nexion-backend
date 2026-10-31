"""Isolated real bash/awk harness; no build, network, host mutation or fake runtime knob."""
import os
from pathlib import Path
import shlex
import shutil
import subprocess
import tempfile
import unittest


class CiMemoryGateTests(unittest.TestCase):
    def run_build(self, kind, meminfo, extra_env=None, limits=None,
                  group="/jobs/agent", mount_root="/", mountinfo=None, cgroup=None):
        bash = shutil.which("bash")
        if bash is None:
            git = Path(shutil.which("git"))
            bash = str(git.parent.parent / "usr/bin/bash.exe")
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            fixture, heavy = root / "meminfo", root / "heavy-command"
            fixture.write_bytes(meminfo.encode())
            mount = root / "cgroup"
            mount.mkdir()
            for relative, value in (limits if limits is not None else {
                    "": "max", "jobs": "max", "jobs/agent": "3221225472"}).items():
                directory = mount / relative
                directory.mkdir(parents=True, exist_ok=True)
                (directory / "memory.max").write_text(value + "\n")
            group_file, mount_file = root / "self-cgroup", root / "mountinfo"
            group_file.write_text(cgroup if cgroup is not None else "0::" + group + "\n")
            mount_file.write_text(mountinfo if mountinfo is not None else
                                  f"29 23 0:26 {mount_root} {mount.as_posix()} ro - cgroup2 cgroup rw\n")
            source = Path(__file__).with_name("ci-build.sh").read_text()
            source = source.replace("bash /opt/nexgrid-ci/ci-network-check.sh", "true")
            # Only the test's copied shell text gets a fixture; production keeps /proc/meminfo literal.
            source = source.replace("/proc/meminfo", shlex.quote(fixture.as_posix()))
            source = source.replace("/sys/fs/cgroup", mount.as_posix())
            source = source.replace("/proc/self/cgroup", shlex.quote(group_file.as_posix()))
            source = source.replace("/proc/self/mountinfo", shlex.quote(mount_file.as_posix()))
            prefix = ('PATH=/usr/bin:/bin:$PATH\n'
                      'git() { echo identical-test-sha; }\n'
                      'mvn() { echo heavy > "$CI_MEMORY_TEST_HEAVY_LOG"; exit 42; }\n'
                      'npm() { echo heavy > "$CI_MEMORY_TEST_HEAVY_LOG"; exit 42; }\n')
            env = {**os.environ, **(extra_env or {}), "JAVA17_HOME": "/isolated/java",
                   "CI_MEMORY_TEST_HEAVY_LOG": heavy.as_posix()}
            result = subprocess.run([bash, "-s", "--", kind], input=prefix + source, text=True,
                                    capture_output=True, cwd=root, env=env, timeout=10)
            return result, heavy.exists(), (root / "artifacts").exists()

    def test_low_memory_blocks_every_heavy_entry_even_with_environment_overrides(self):
        for kind in ("backend", "pc", "uniapp"):
            with self.subTest(kind=kind):
                result, heavy, artifacts = self.run_build(kind, "MemTotal: 1000 kB\nMemAvailable: 199 kB\n",
                                              {"MEMINFO_PATH": "/fake/high-memory", "MIN_AVAILABLE_PERCENT": "0"})
                self.assertEqual(result.returncode, 78, result.stderr)
                self.assertIn("CI_HOST_MEMORY_RESERVE_REQUIRED", result.stderr)
                self.assertFalse(heavy)
                self.assertFalse(artifacts)

    def test_exactly_twenty_percent_reaches_every_heavy_entry(self):
        for kind in ("backend", "pc", "uniapp"):
            with self.subTest(kind=kind):
                result, heavy, _ = self.run_build(kind, "MemTotal: 1000 kB\nMemAvailable: 200 kB\n")
                self.assertEqual(result.returncode, 42, result.stderr)
                self.assertTrue(heavy)

    def test_missing_invalid_and_duplicate_memory_fields_fail_closed(self):
        for meminfo in ("MemTotal: 1000 kB\n", "MemTotal: 1000 kB\nMemAvailable: invalid kB\n",
                        "MemTotal: 1000 kB\nMemAvailable: 200 MB\n",
                        "MemTotal: 1000 kB\nMemTotal: 1000 kB\nMemAvailable: 200 kB\n"):
            with self.subTest(meminfo=meminfo):
                result, heavy, artifacts = self.run_build("backend", meminfo)
                self.assertEqual(result.returncode, 78, result.stderr)
                self.assertIn("CI_HOST_MEMORY_INVALID", result.stderr)
                self.assertFalse(heavy)
                self.assertFalse(artifacts)

    def test_every_entry_rejects_idle_cap_and_inherited_lower_cap_before_artifacts(self):
        for kind in ("backend", "pc", "uniapp"):
            for limits in ({"": "max", "jobs": "max", "jobs/agent": "1073741824"},
                           {"": "max", "jobs": "3221225471", "jobs/agent": "max"},
                           {"": "1073741824", "jobs": "max", "jobs/agent": "3221225472"}):
                with self.subTest(kind=kind, limits=limits):
                    result, heavy, artifacts = self.run_build(
                        kind, "MemTotal: 1000 kB\nMemAvailable: 800 kB\n", limits=limits,
                        extra_env={"CGROUP_MEMORY_MAX": "max", "CI_MIN_CAPACITY": "0"})
                    self.assertEqual(78, result.returncode, result.stderr)
                    self.assertIn("CI_CGROUP_CAPACITY_INSUFFICIENT", result.stderr)
                    self.assertFalse(heavy)
                    self.assertFalse(artifacts)

    def test_exact_floor_unlimited_and_mount_subtree_reach_heavy_work(self):
        fixtures = (("/jobs/agent", "/", {"": "max", "jobs": "max", "jobs/agent": "3221225472"}),
                    ("/", "/", {"": "max"}),
                    ("/host/agent", "/host", {"": "max", "agent": "3221225472"}))
        for group, mount_root, limits in fixtures:
            with self.subTest(group=group, mount_root=mount_root):
                result, heavy, artifacts = self.run_build(
                    "backend", "MemTotal: 1000 kB\nMemAvailable: 800 kB\n",
                    limits=limits, group=group, mount_root=mount_root)
                self.assertEqual(42, result.returncode, result.stderr)
                self.assertTrue(heavy)
                self.assertTrue(artifacts)

    def test_unknown_cgroup_or_missing_malformed_limit_fails_closed(self):
        fixtures = ({"limits": {"": "max", "jobs": "max"}},
                    {"limits": {"": "max", "jobs": "max", "jobs/agent": "bogus"}},
                    {"limits": {"": "max", "jobs": "max", "jobs/agent": "max\n3221225472"}},
                    {"cgroup": "1:memory:/jobs/agent\n"},
                    {"cgroup": "0::/jobs/agent\n0::/jobs/agent\n"},
                    {"group": "/jobs/../agent"},
                    {"group": "/jobs/agent/"},
                    {"group": "/elsewhere", "mount_root": "/host"},
                    {"mountinfo": ""})
        for fixture in fixtures:
            with self.subTest(fixture=fixture):
                result, heavy, artifacts = self.run_build(
                    "backend", "MemTotal: 1000 kB\nMemAvailable: 800 kB\n", **fixture)
                self.assertEqual(78, result.returncode, result.stderr)
                self.assertIn("CI_CGROUP_CAPACITY_INSUFFICIENT", result.stderr)
                self.assertFalse(heavy)
                self.assertFalse(artifacts)


if __name__ == "__main__":
    unittest.main()

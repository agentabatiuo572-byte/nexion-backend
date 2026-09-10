#!/usr/bin/env python3
"""Start with python3 -I. Bootstrap pins this file and runtime-lock.json externally.

No repository module or bytecode is imported before validating the full fixed closure.
Only root may install/update this closure; CI can supply artifacts, never these files.
"""
import hashlib
import json
import os
import re
from pathlib import Path
import stat
import sys
import types

INSTALL = Path('/srv/jenkins/release')
FILES = frozenset({'trusted_entry.py', 'release_broker.py', 'schema_fingerprint.py',
                   'install_release.py', 'ci-build.sh', 'main.pipeline.groovy',
                   '40-release-jobs.groovy', 'test-server.yml', 'h5-nginx.conf',
                   'public-test-policy.properties', 'nexgrid-release.service',
                   'nexgrid-release.timer'})


def trusted_path(path):
    for index, part in enumerate((path, *path.parents)):
        info = part.lstat()
        regular = stat.S_ISREG(info.st_mode) if index == 0 else stat.S_ISDIR(info.st_mode)
        if not regular or info.st_uid != 0 or info.st_mode & 0o022:
            raise RuntimeError('TRUST_PATH_REJECTED')


def verified_sources(directory):
    lock = directory / 'runtime-lock.json'
    trusted_path(lock)
    manifest = json.loads(lock.read_bytes())
    if manifest.get('version') != 1 or set(manifest.get('files', {})) != FILES:
        raise RuntimeError('TRUST_CLOSURE_REJECTED')
    sources = {}
    for name, expected in manifest['files'].items():
        path = directory / name
        trusted_path(path)
        data = path.read_bytes()
        if hashlib.sha256(data).hexdigest() != expected:
            raise RuntimeError('TRUST_DIGEST_REJECTED')
        sources[name] = data
    return sources


def main():
    if os.geteuid() != 0 or not sys.flags.isolated or Path(__file__) != INSTALL / 'trusted_entry.py':
        raise RuntimeError('ISOLATED_CANONICAL_ROOT_ENTRY_REQUIRED')
    sources = verified_sources(INSTALL)
    if len(sys.argv) < 3 or sys.argv[1] not in ('install', 'broker'):
        raise RuntimeError('ENTRY_ACTION_REQUIRED')
    selected = 'install_release' if sys.argv[1] == 'install' else 'release_broker'
    # Compile the verified bytes directly. Do not load local __pycache__, .pth,
    # extension modules or any path entries from the deployment directory.
    sys._nexgrid_verified_entry = True
    for name in ('schema_fingerprint', 'release_broker'):
        module = types.ModuleType(name)
        module.__file__ = str(INSTALL / (name + '.py'))
        sys.modules[name] = module
        exec(compile(sources[name + '.py'], module.__file__, 'exec'), module.__dict__)
    sys.argv = [str(INSTALL / (selected + '.py')), *sys.argv[2:]]
    if selected == 'release_broker':
        sys.modules[selected].main()
    else:
        scope = {'__name__': '__main__', '__file__': sys.argv[0]}
        exec(compile(sources[selected + '.py'], sys.argv[0], 'exec'), scope)


if __name__ == '__main__':
    try:
        main()
    except Exception as error:
        # Never spill environment, credentials or arbitrary application output.
        reason = str(error)
        safe = reason if re.fullmatch(r'[A-Z][A-Z0-9_: .-]{0,150}', reason) else type(error).__name__
        print('TRUSTED_ENTRY_FAILED: ' + safe, flush=True)
        raise SystemExit(1)

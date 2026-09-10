#!/usr/bin/env python3
"""Conservative migration-review gate; not a proof of arbitrary Java program purity."""
import hashlib
from pathlib import Path
import re
import subprocess

def guarded(path, content):
    if path in ('pom.xml', '.mvn/jvm.config', '.mvn/maven.config'):
        return True
    if not path.startswith('src/main/'):
        return False
    return (path.startswith('src/main/resources/')
            or re.search(r'schema|migration|initializer|bootstrap|mapper|repository|entity|/domain/|/config/', path, re.I)
            or re.search(rb'@PostConstruct|ApplicationRunner|CommandLineRunner|InitializingBean|SmartLifecycle|ContextRefreshedEvent|ApplicationReadyEvent|ApplicationStartedEvent|JdbcTemplate|JdbcClient|java\.sql\.|\b(?:CREATE|ALTER|DROP|TRUNCATE|RENAME)\s+(?:TABLE|INDEX|DATABASE|COLUMN|VIEW)\b', content, re.I))

def fingerprint(root):
    if subprocess.check_output(['git', 'rev-parse', '--show-prefix'], cwd=root).strip():
        raise ValueError('REPOSITORY_ROOT_REQUIRED')
    names = subprocess.check_output(['git', 'ls-files', '-z'], cwd=root).decode().split('\0')
    lines = []
    for name in sorted(filter(None, names)):
        data = (root / name).read_bytes().replace(b'\r\n', b'\n')
        if guarded(name, data):
            lines.append(name + '\0' + hashlib.sha256(data).hexdigest())
    return hashlib.sha256('\n'.join(lines).encode()).hexdigest()

def fingerprint_objects(git_dir, sha):
    """Read immutable Git blobs, never checkout source or execute repository hooks/filters."""
    if not re.fullmatch(r'[0-9a-f]{40}', sha):
        raise ValueError('COMMIT_SHA_REQUIRED')
    command = ['git', '--git-dir=' + str(git_dir)]
    listing = subprocess.check_output(command + ['ls-tree', '-r', '-z', sha], timeout=30)
    entries = []
    for raw in filter(None, listing.split(b'\0')):
        metadata, raw_name = raw.split(b'\t', 1)
        name = raw_name.decode()
        if not (name.startswith('src/main/') or name in ('pom.xml', '.mvn/jvm.config', '.mvn/maven.config')):
            continue
        mode, kind, oid = metadata.decode().split()
        if kind != 'blob' or mode not in ('100644', '100755'):
            raise ValueError('SOURCE_MODE_REJECTED')
        entries.append((name, oid))
    entries.sort()
    if not entries:
        raise ValueError('SOURCE_INPUTS_MISSING')
    result = subprocess.run(command + ['cat-file', '--batch'], input=('\n'.join(oid for _, oid in entries)+'\n').encode(),
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=60, check=True)
    output, offset, lines = result.stdout, 0, []
    if len(output) > 100 * 1024 * 1024:
        raise ValueError('SOURCE_SIZE_REJECTED')
    for name, oid in entries:
        end = output.index(b'\n', offset)
        actual, kind, length = output[offset:end].decode().split()
        if actual != oid or kind != 'blob':
            raise ValueError('SOURCE_OBJECT_REJECTED')
        length = int(length)
        data = output[end+1:end+1+length].replace(b'\r\n', b'\n')
        offset = end + length + 2
        if guarded(name, data):
            lines.append(name + '\0' + hashlib.sha256(data).hexdigest())
    return hashlib.sha256('\n'.join(lines).encode()).hexdigest()

if __name__ == '__main__':
    print(fingerprint(Path.cwd()))

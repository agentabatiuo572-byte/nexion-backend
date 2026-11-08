#!/usr/bin/env bash
set -euo pipefail
bash /opt/nexgrid-ci/ci-network-check.sh
kind=${1:?kind required}
case "$kind" in
  h5lite) branch=h5-lite ;;
  backend|pc|uniapp) branch=test ;;
  *) echo 'Unknown build kind' >&2; exit 33 ;;
esac
test "$(git rev-parse HEAD)" = "$(git rev-parse "refs/remotes/origin/$branch")"
# Idle CI has only 1 GiB; official drain must reserve 3 GiB before any build.
# Check every visible cgroup v2 ancestor, not just an unlimited child.
if ! LC_ALL=C /usr/bin/awk '
  function canonical(path) {
    return path ~ /^\// && path !~ /\\/ && path !~ /\/\// &&
           (path == "/" || path !~ /\/$/) && path !~ /(^|\/)\.\.?($|\/)/
  }
  FILENAME == ARGV[1] {
    if (substr($0, 1, 3) == "0::") { groups++; group=substr($0, 4) }
    next
  }
  {
    for (i=7; i<=NF; i++) {
      if ($i == "-" && $(i+1) == "cgroup2" && $5 == "/sys/fs/cgroup") {
        mounts++; root=$4; mount=$5
      }
    }
  }
  END {
    if (groups != 1 || mounts != 1 || !canonical(group) || !canonical(root)) exit 1
    if (root == "/") relative=group
    else if (group == root) relative="/"
    else if (index(group, root "/") == 1) relative=substr(group, length(root)+1)
    else exit 1
    current=mount (relative == "/" ? "" : relative)
    while (1) {
      file=current "/memory.max"
      first=(getline limit < file); extra=(getline unexpected < file); close(file)
      if (first != 1 || extra != 0 || (limit != "max" &&
          (limit !~ /^[0-9]+$/ || limit+0 < 3221225472))) exit 1
      if (current == mount) break
      sub(/\/[^/]+$/, "", current)
    }
  }
' /proc/self/cgroup /proc/self/mountinfo; then
  echo 'CI_CGROUP_CAPACITY_INSUFFICIENT' >&2
  exit 78
fi
# CI's hard cap is already reserved; every entry, including Jenkins UI builds,
# must also preserve at least 20% of real host memory before heavy work starts.
LC_ALL=C /usr/bin/awk '
  $1 == "MemTotal:" {
    totals++; total=$2
    if (NF != 3 || $2 !~ /^[0-9]+$/ || $3 != "kB") invalid=1
  }
  $1 == "MemAvailable:" {
    available_fields++; available=$2
    if (NF != 3 || $2 !~ /^[0-9]+$/ || $3 != "kB") invalid=1
  }
  END {
    if (invalid || totals != 1 || available_fields != 1 || total <= 0 || available > total) {
      print "CI_HOST_MEMORY_INVALID" > "/dev/stderr"; exit 78
    }
    if (available * 5 < total) {
      print "CI_HOST_MEMORY_RESERVE_REQUIRED" > "/dev/stderr"; exit 78
    }
  }
' /proc/meminfo
mkdir -p artifacts
git rev-parse HEAD > artifacts/test-sha.txt
git rev-parse 'HEAD^{tree}' > artifacts/test-tree.txt
case "$kind" in
  backend)
    export JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH"
    # Bound Maven inside the shared 3 GiB CI budget.
    export MAVEN_OPTS='-Xms128m -Xmx768m -XX:MaxMetaspaceSize=384m'
    export JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/home/jenkins/agent/tmp'
    # Compile/package only; business acceptance uses E2E and system business flows.
    mvn -B -ntp -s /opt/nexgrid-ci/maven-settings.xml '-DargLine=-Xms64m -Xmx768m -XX:MaxMetaspaceSize=384m' -Dmaven.test.skip=true package
    cp target/nexion-backend-0.0.1-SNAPSHOT.jar artifacts/backend.jar
    ;;
  pc)
    npm ci --ignore-scripts --no-audit --no-fund
    NEXGRID_STANDALONE_BUILD=1 npm run build
    test -f .next/standalone/server.js
    cp -a .next/static .next/standalone/.next/static
    if [ -d public ]; then cp -a public .next/standalone/public; fi
    # Dereference build-local dependency links; the host extractor rejects every link.
    tar -h --exclude='.env*' --exclude='*/.env*' --exclude='*/.bin' --exclude='*/cache' -czf artifacts/pc-build.tgz -C .next/standalone .
    ;;
  uniapp|h5lite)
    base=/app/
    if [ "$kind" = h5lite ]; then
      base=/h5-lite/
      export VITE_NEXGRID_API_BASE_URL=https://18.142.169.24
    fi
    npm ci --legacy-peer-deps --ignore-scripts --no-audit --no-fund
    npm run type-check
    npm run build:h5:prod -- --base "$base"
    node - "$kind" "$base" <<'JS'
const fs = require('node:fs');
const file = 'dist/build/h5/index.html';
let html = fs.readFileSync(file, 'utf8');
if (!html.includes(process.argv[3]+'assets/')) throw new Error('H5_PUBLIC_BASE_REQUIRED');
if (process.argv[2] === 'uniapp' && !html.includes('https://db-ip.com')) {
  html = html.replace('</body>', '<a href="https://db-ip.com" target="_blank" rel="noopener noreferrer" style="position:fixed;bottom:2px;left:6px;z-index:10;font:9px sans-serif;color:#8b929c">IP Geolocation by DB-IP</a></body>');
}
fs.writeFileSync(file, html);
JS
    tar -czf "artifacts/$kind-h5-build.tgz" -C dist/build/h5 .
    ;;
  *) echo 'Unknown build kind' >&2; exit 33 ;;
esac
# Legacy manifest field retained for compatible artifact parsing, not approval.
schema=$(printf '' | sha256sum | cut -d ' ' -f 1)
node - "$kind" "$schema" <<'JS'
const fs = require('node:fs');
const crypto = require('node:crypto');
const kind = process.argv[2];
const artifact = {backend:'backend.jar',pc:'pc-build.tgz',uniapp:'uniapp-h5-build.tgz',h5lite:'h5lite-h5-build.tgz'}[kind];
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const schema = process.argv[3];
const manifest = {version:1,component:kind,branch:kind === 'h5lite' ? 'h5-lite' : 'test',sha:fs.readFileSync('artifacts/test-sha.txt','utf8').trim(),
  artifact,sha256:hash(fs.readFileSync('artifacts/'+artifact)),schema};
if (kind === 'backend') {
  manifest.tree = fs.readFileSync('artifacts/test-tree.txt','utf8').trim();
  manifest.publicationNative = 'OWNER_ADMISSION_REQUIRED';
}
fs.writeFileSync('artifacts/release.json', JSON.stringify(manifest, null, 2)+'\n');
JS
(cd artifacts && shopt -s nullglob && sha256sum test-sha.txt test-tree.txt release.json *.jar *.tgz | tee SHA256SUMS)
echo 'RELEASE_ARTIFACT_READY: isolated CI passed; host broker independently controls promotion and rollback.'
if [ "$kind" = backend ]; then echo 'PUBLICATION_NATIVE_HOLD: normal build success needs independent root owner admission before promotion.'; fi

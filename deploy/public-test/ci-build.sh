#!/usr/bin/env bash
set -euo pipefail
bash /opt/nexgrid-ci/ci-network-check.sh
kind=${1:?kind required}
test "$(git rev-parse HEAD)" = "$(git rev-parse refs/remotes/origin/main)"
mkdir -p artifacts
git rev-parse HEAD > artifacts/main-sha.txt
case "$kind" in
  backend)
    export JAVA_HOME="$JAVA17_HOME" PATH="$JAVA17_HOME/bin:$PATH"
    export MAVEN_OPTS='-Xms128m -Xmx1536m -XX:MaxMetaspaceSize=384m'
    export JAVA_TOOL_OPTIONS='-Djava.io.tmpdir=/home/jenkins/agent/tmp'
    mvn -B -ntp -s /opt/nexgrid-ci/maven-settings.xml -Dtest=RuntimeProfileEnvironmentPostProcessorTest,DatabaseEnvironmentResolverTest,PublicTestDeploymentIsolationTest,PublicTestDeploymentSafetyTest,OpsPlatformParamRegistryServiceTest,DeploymentForwardedHeadersConfigurationTest,UserOtpDeliveryServiceTest,CaptchaOtpGateTest,AppUserRegistrationServiceTest,AppUserPasswordResetServiceTest,AppUserSecurityServiceTest,GeoBlockEnforcementFilterTest,GeoBlockPolicyServiceTest,OpsAdminAuthControllerTest package
    cp target/nexion-backend-0.0.1-SNAPSHOT.jar artifacts/backend.jar
    ;;
  pc)
    npm ci --ignore-scripts --no-audit --no-fund
    node --experimental-strip-types --test tests/fetch-guard.test.mjs tests/standalone-deployment.test.mjs
    NEXGRID_STANDALONE_BUILD=1 npm run build
    test -f .next/standalone/server.js
    cp -a .next/static .next/standalone/.next/static
    if [ -d public ]; then cp -a public .next/standalone/public; fi
    # Dereference build-local dependency links; the host extractor rejects every link.
    tar -h --exclude='.env*' --exclude='*/.env*' --exclude='*/.bin' --exclude='*/cache' -czf artifacts/pc-build.tgz -C .next/standalone .
    ;;
  uniapp)
    npm ci --legacy-peer-deps --ignore-scripts --no-audit --no-fund
    node --test scripts/phase-isolation-contract.test.mjs
    npm run type-check
    npm run build:h5:prod -- --base /app/
    node - <<'JS'
const fs = require('node:fs');
const file = 'dist/build/h5/index.html';
let html = fs.readFileSync(file, 'utf8');
if (!html.includes('/app/assets/')) throw new Error('H5_PUBLIC_BASE_REQUIRED');
if (!html.includes('https://db-ip.com')) {
  html = html.replace('</body>', '<a href="https://db-ip.com" target="_blank" rel="noopener noreferrer" style="position:fixed;bottom:2px;left:6px;z-index:10;font:9px sans-serif;color:#8b929c">IP Geolocation by DB-IP</a></body>');
}
fs.writeFileSync(file, html);
JS
    tar -czf artifacts/uniapp-h5-build.tgz -C dist/build/h5 .
    ;;
  *) echo 'Unknown build kind' >&2; exit 33 ;;
esac
# This field is informational only. The privileged host independently fingerprints
# source Git objects; an untrusted build process cannot approve its own migrations.
schema=$(printf '' | sha256sum | cut -d ' ' -f 1)
node - "$kind" "$schema" <<'JS'
const fs = require('node:fs');
const crypto = require('node:crypto');
const kind = process.argv[2];
const artifact = {backend:'backend.jar',pc:'pc-build.tgz',uniapp:'uniapp-h5-build.tgz'}[kind];
const hash = value => crypto.createHash('sha256').update(value).digest('hex');
const schema = process.argv[3];
const manifest = {version:1,component:kind,branch:'main',sha:fs.readFileSync('artifacts/main-sha.txt','utf8').trim(),
  artifact,sha256:hash(fs.readFileSync('artifacts/'+artifact)),schema};
fs.writeFileSync('artifacts/release.json', JSON.stringify(manifest, null, 2)+'\n');
JS
(cd artifacts && shopt -s nullglob && sha256sum main-sha.txt release.json *.jar *.tgz | tee SHA256SUMS)
echo 'RELEASE_ARTIFACT_READY: isolated CI passed; host broker independently controls promotion and rollback.'

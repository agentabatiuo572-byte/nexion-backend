import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';

// The isolated driver runs new transactions; old evidence never satisfies this gate.
const env = process.env;
for (const key of ['WORKFLOW_TASK_ID', 'WORKFLOW_STEP_ID', 'WORKFLOW_CHECK_ID', 'WORKFLOW_RUN_ID', 'WORKFLOW_REPO', 'WORKFLOW_SNAPSHOT_HASH', 'GROWTH_PROMOTION_ACCEPTANCE_DRIVER']) assert(env[key], `Missing ${key}`);
assert.equal(path.resolve(env.WORKFLOW_REPO).toLowerCase(), process.cwd().toLowerCase(), 'Wrong repository');
const index = process.argv.indexOf('--report');
assert(index >= 0 && process.argv[index + 1], '--report is required');
const reportFile = path.resolve(process.argv[index + 1]);
const directory = path.dirname(reportFile);
fs.mkdirSync(directory, { recursive: true });
const startedAt = new Date().toISOString(), start = Date.now();
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const driver = path.resolve(env.GROWTH_PROMOTION_ACCEPTANCE_DRIVER);
const driverRoot = path.dirname(driver);
const harnessFiles = [driver, ...['prepare-owned-http-accounts.mjs', 'prepare-owned-http-wallets.mjs', 'authenticate-fixtures.mjs', 'promotion-http-acceptance.mjs', 'promotion-restart-readback.mjs', 'start-local-services.ps1'].map(file => path.join(driverRoot, file))];
const harnessHashes = () => Object.fromEntries(harnessFiles.map(file => [file, createHash('sha256').update(fs.readFileSync(file)).digest('hex')]));
const harnessBefore = harnessHashes();
const sourceDigest = () => {
  const list = spawnSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], { encoding: 'utf8', windowsHide: true });
  assert.equal(list.status, 0, 'Cannot inventory source');
  const hash = createHash('sha256');
  for (const file of [...new Set(list.stdout.split('\0').filter(Boolean))].sort()) {
    hash.update(file + '\0');
    hash.update(fs.existsSync(file) ? fs.readFileSync(file) : '<deleted>');
  }
  return hash.digest('hex');
};
const before = sourceDigest();
const unique = `${env.WORKFLOW_RUN_ID}-${start}`;
const driverReport = path.join(directory, `backend-driver-${unique}.json`);
const log = path.join(directory, `backend-runtime-${unique}.log`);
const fd = fs.openSync(log, 'wx');
let result;
try {
  result = spawnSync('pwsh', ['-NoProfile', '-File', driver, '-Report', driverReport], {
    env, stdio: ['ignore', fd, fd], windowsHide: true, timeout: 540000,
  });
} finally { fs.closeSync(fd); }
assert(!result.error && result.status === 0, `Fresh isolated runtime failed: ${log}`);
assert(fs.statSync(driverReport).mtimeMs >= start, 'Driver report is stale');
const proof = read(driverReport);
assert.equal(proof.completed, true);
assert.equal(proof.database, 'growth_promotions_20261007:33339');
assert.equal(path.resolve(proof.repo).toLowerCase(), process.cwd().toLowerCase());
const evidence = [driverReport, log];
let suites = 0, totalTests = 0;
for (const file of fs.readdirSync('target/surefire-reports').filter(file => /^TEST-.*\.xml$/.test(file))) {
  const full = path.resolve('target/surefire-reports', file);
  if (fs.statSync(full).mtimeMs < start) continue;
  const header = fs.readFileSync(full, 'utf8').match(/<testsuite\b[^>]*>/)?.[0] ?? '';
  const counts = Object.fromEntries([...header.matchAll(/(tests|failures|errors|skipped)="(\d+)"/g)].map(([, key, value]) => [key, Number(value)]));
  assert(counts.tests > 0 && counts.failures === 0 && counts.errors === 0 && counts.skipped === 0, `Failed, empty or skipped suite: ${file}`);
  suites++; totalTests += counts.tests; evidence.push(full);
}
assert(suites >= 33 && totalTests >= 600, 'Required promotion and existing commerce regression suites did not all run');
for (const name of ['PromotionLifecycleMySqlTest', 'PromotionDisclosureMySqlTest', 'PromotionMetricsBreakdownMySqlTest', 'PromotionReversalQueueMySqlTest', 'EarningsSourceRecoveryMySqlTest', 'AdminRbacAuthorizationFilterTest', 'AppWalletBillsServiceTest', 'AppAcceptanceSandboxStartupMigrationContractTest']) assert(evidence.some(file => file.endsWith(`.${name}.xml`)), `Missing fresh suite ${name}`);
for (const file of [proof.lifecycle, proof.http, proof.restart]) assert(fs.statSync(file).mtimeMs >= start, `Stale evidence ${file}`);
const lifecycle = read(proof.lifecycle), http = read(proof.http), restart = read(proof.restart);
assert.equal(lifecycle.completed, true); assert.equal(http.completed, true); assert.equal(restart.completed, true);
assert.equal(restart.compiledFiles.count, restart.compiledFiles.targetCount, 'Runtime must contain every compiled target file');
assert.equal(restart.compiledFiles.manifestSha256, restart.compiledFiles.targetManifestSha256, 'Runtime and target artifacts must match in both directions');
assert.equal(http.run, restart.run);
assert(Date.parse(http.startedAt) >= start, 'HTTP transactions reused an earlier run');
for (const scenario of ['first-purchase-two-stale-snapshots', 'activity-last-capacity-race', 'multi-sku-quantities-and-lines', 'real-direct-inviter-two-beneficiaries']) assert(lifecycle.evidence.some(item => item.scenario === scenario && Object.keys(item.facts).length), `Missing concurrency/lifecycle observations ${scenario}`);
for (const asset of ['DEVICE', 'USDT', 'NEX']) {
  assert(http.steps.some(step => step.scenario === 'REAL_CANONICAL_BUY_REWARD_REFUND' && step.asset === asset && step.passed), `Missing real HTTP lifecycle ${asset}`);
  assert(restart.checks.some(step => step.asset === asset && step.passed && step.rewardState === 'REVERSED' && step.recoveryOutstanding === '0.000000'), `Missing post-restart readback ${asset}`);
}
assert.equal(before, sourceDigest(), 'Source changed during runtime verification');
assert.deepEqual(harnessBefore, harnessHashes(), 'Acceptance harness changed during runtime verification');
const steps = [
  { id: 'backend-persistence', evidence: [proof.http, proof.restart, ...evidence] },
  { id: 'backend-concurrency', evidence: [proof.lifecycle, ...evidence] },
  { id: 'backend-operations', evidence: [proof.http, proof.restart, ...evidence] },
].map(step => ({ ...step, status: 'pass', innerSkipped: 0 }));
fs.writeFileSync(reportFile, JSON.stringify({ taskId: env.WORKFLOW_TASK_ID, stepId: env.WORKFLOW_STEP_ID, checkId: env.WORKFLOW_CHECK_ID, runId: env.WORKFLOW_RUN_ID, repo: env.WORKFLOW_REPO, snapshotHash: env.WORKFLOW_SNAPSHOT_HASH, startedAt, at: new Date().toISOString(), verdict: 'pass', mode: 'full', treeMoved: false, capability: 'runtime', harnessFiles: harnessBefore, suites, totalTests, steps }, null, 2));
console.log(`Fresh ${suites} suites / ${totalTests} tests, three HTTP asset lifecycles and restart reads passed: ${reportFile}`);

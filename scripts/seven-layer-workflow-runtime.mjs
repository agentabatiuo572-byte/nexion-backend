import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import { spawnSync } from 'node:child_process';

// A report is written only after fresh, unskipped JUnit and real MySQL observations.
const env = process.env;
const startedAt = new Date().toISOString();
const reportIndex = process.argv.indexOf('--report');
assert(reportIndex >= 0 && process.argv[reportIndex + 1], '--report is required');
const report = path.resolve(process.argv[reportIndex + 1]);
for (const key of ['WORKFLOW_TASK_ID', 'WORKFLOW_STEP_ID', 'WORKFLOW_CHECK_ID', 'WORKFLOW_RUN_ID', 'WORKFLOW_REPO', 'WORKFLOW_SNAPSHOT_HASH', 'JAVA_HOME', 'DIRECT_REFERRAL_MAVEN']) assert(env[key], `Missing ${key}`);
assert.equal(path.resolve(env.WORKFLOW_REPO).toLowerCase(), process.cwd().toLowerCase(), 'Wrong source repository');
const dir = path.join(path.dirname(report), 'backend-evidence');
fs.mkdirSync(dir, { recursive: true });
const files = root => fs.readdirSync(root, { withFileTypes: true }).flatMap(entry => entry.isDirectory() ? files(path.join(root, entry.name)) : [path.join(root, entry.name)]);
const tests = files('src/test/java').filter(file => file.endsWith('Test.java'));
const extra = new Set(['AppTaskAssignmentServiceTest', 'AppTaskAssignmentMapperContractTest', 'AppTrialLifecycleServiceTest', 'AppTrialLifecycleControllerIntegrationTest', 'AppTrialLifecycleMapperContractTest', 'OpsDeviceServiceTest', 'CommissionWalletFundsTest', 'MybatisTreasuryLedgerRepositoryTest', 'TreasuryLedgerMapperSqlContractTest', 'AppWalletBillsCommissionSummaryContractTest', 'DevelopmentCommissionHowInitializerTest', 'OpsConsoleArchitectureTest']);
for (const name of ['AppOrderCommandServiceTest', 'AppBundleOrderServiceTest', 'AppTradeinServiceTest', 'OpsAuditCenterServiceTest', 'EventOutboxDispatchSchedulerTest', 'AuditReplayBusinessPermissionGuardTest', 'AuditReplayDispatcherTest']) extra.add(name);
for (const name of ['PublishedHowContentControllerTest','PublishedHowContentServiceTest','F5CommissionExportTest','AppCommissionConfigControllerTest','AppCommissionGuideControllerTest']) extra.add(name);
const classes = tests.filter(file => {
  const name = path.basename(file, '.java');
  return (name.startsWith('DirectReferral') || name.startsWith('SevenLayer')) || extra.has(name) || (file.split(path.sep).includes('team') && !/mysql/i.test(name));
}).map(file => path.basename(file, '.java')).sort();
assert(classes.includes('DirectReferralMySqlRuntimeTest'), 'The real database suite is required');
const log = path.join(dir, `maven-${env.WORKFLOW_RUN_ID}-${Date.now()}.log`);
const fd = fs.openSync(log, 'wx');
const start = Date.now();
const testEnv = { ...env, DIRECT_REFERRAL_RUNTIME: '1', DIRECT_REFERRAL_EVIDENCE_DIR: dir, DIRECT_REFERRAL_MYSQL_URL: 'jdbc:mysql://127.0.0.1:33335/direct_referral_acceptance_20261005?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai' };
const executable = process.platform === 'win32' ? 'pwsh.exe' : env.DIRECT_REFERRAL_MAVEN;
const args = process.platform === 'win32'
  ? ['-NoProfile', '-Command', '& $env:DIRECT_REFERRAL_MAVEN "-Dtest=$env:DIRECT_REFERRAL_TEST_CLASSES" test; exit $LASTEXITCODE']
  : [`-Dtest=${classes.join(',')}`, 'test'];
let result;
try { result = spawnSync(executable, args, { env: { ...testEnv, DIRECT_REFERRAL_TEST_CLASSES: classes.join(',') }, stdio: ['ignore', fd, fd], windowsHide: true, timeout: 540000 }); }
finally { fs.closeSync(fd); }
assert(!result.error && result.status === 0, `JUnit failed: ${log}; ${result.error?.message || result.status}`);
const suiteDir = 'target/surefire-reports';
const xmlFiles = fs.readdirSync(suiteDir);
let totalTests = 0;
for (const name of classes) {
  const xml = xmlFiles.find(file => file.startsWith('TEST-') && file.endsWith(`.${name}.xml`));
  assert(xml, `Missing test report: ${name}`);
  const file = path.join(suiteDir, xml);
  assert(fs.statSync(file).mtimeMs >= start - 1000, `Stale test report: ${name}`);
  const header = fs.readFileSync(file, 'utf8').match(/<testsuite\b[^>]*>/)?.[0] || '';
  const counts = Object.fromEntries([...header.matchAll(/(tests|failures|errors|skipped)="(\d+)"/g)].map(match => [match[1], Number(match[2])]));
  assert(counts.tests > 0 && counts.failures === 0 && counts.errors === 0 && counts.skipped === 0, `Failed, empty or skipped suite: ${name}`);
  totalTests += counts.tests;
}
const proofFile = path.join(dir, 'seven-layer-funds.json');
assert(fs.statSync(proofFile).mtimeMs >= start, 'Database observations are stale');
const proof = JSON.parse(fs.readFileSync(proofFile, 'utf8'));
assert.equal(proof.database, 'direct_referral_acceptance_20261005');
assert.equal(proof.port, 33335);
const queryProofFile = path.join(dir, 'seven-layer-insights.json');
assert(fs.statSync(queryProofFile).mtimeMs >= start, 'App query observations are stale');
const queryProof = JSON.parse(fs.readFileSync(queryProofFile, 'utf8'));
assert.equal(queryProof.database, 'direct_referral_acceptance_20261005');
assert.equal(queryProof.port, 33335);
const migrationProofFile = path.join(dir, 'seven-layer-migrations.json');
assert(fs.statSync(migrationProofFile).mtimeMs >= start, 'Migration observations are stale');
const migrationProof = JSON.parse(fs.readFileSync(migrationProofFile, 'utf8'));
assert.equal(migrationProof.port, 33335);
for (const mode of ['fresh', 'old']) {
  assert.match(migrationProof.results?.[mode]?.database || '', /^seven_mig_20261006_(fresh|old)_[a-f0-9]{32}$/);
  assert.deepEqual(migrationProof.results[mode].after, migrationProof.results[mode].before, 'Repeated migration changed per-asset funds facts');
  assert.equal(migrationProof.results[mode].cutoverRows, 0);
}
const steps = ['funds-seven-layers', 'funds-direct-split', 'funds-refund', 'funds-history'].map(id => {
  const scenarios = proof.groups?.[id];
  assert(Array.isArray(scenarios) && scenarios.length > 0, `Missing database scenarios: ${id}`);
  for (const scenario of scenarios) assert(scenario.id && scenario.passed === true && scenario.observations && Object.keys(scenario.observations).length > 0, `Missing actual observations: ${id}/${scenario.id}`);
  return { id, status: 'pass', innerSkipped: 0, evidence: [proofFile, log, ...(id === 'funds-history' ? [migrationProofFile] : []), ...scenarios.map(scenario => `SQL scenario: ${scenario.id}`)] };
});
fs.writeFileSync(report, JSON.stringify({ taskId: env.WORKFLOW_TASK_ID, stepId: env.WORKFLOW_STEP_ID, checkId: env.WORKFLOW_CHECK_ID, runId: env.WORKFLOW_RUN_ID, repo: env.WORKFLOW_REPO, snapshotHash: env.WORKFLOW_SNAPSHOT_HASH, startedAt, at: new Date().toISOString(), verdict: 'pass', mode: 'full', treeMoved: false, capability: 'runtime', totalTests, steps }, null, 2));
console.log(`Verified ${totalTests} tests with no skips and four new seven-layer funds groups and fresh App query MySQL observations. ${report}`);

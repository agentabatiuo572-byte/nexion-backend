import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { parseArgs } from 'node:util';

const { values } = parseArgs({ options: { phase: { type: 'string' }, plan: { type: 'string' }, report: { type: 'string' } } });
const assert = (condition, message) => { if (!condition) throw new Error(message); };
const hash = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
if (values.phase === 'bulk' || values.phase === 'integration') {
  await import('./support-enhancements-bulk-runtime.mjs');
  process.exit(0);
}
assert(values.phase === 'core', 'Only the authorized core phase has an evidence producer');
assert(values.plan && values.report, '--plan and --report required');
const plan = read(values.plan), core = plan.steps.find(step => step.id === 'core');
let root = plan.id.startsWith('cs-enhance-backend-20261001-bulk-')
  ? path.join('C:/Users/jason/.codex/workflow-runs/customer-service-enhancements-20261001/backend-bulk/runs', process.env.WORKFLOW_RUN_ID || 'missing', process.env.WORKFLOW_STEP_ID || 'missing')
  : 'C:/Users/jason/.codex/workflow-runs/customer-service-enhancements-20261001/backend-core';
const identity = Object.fromEntries(['TASK_ID','STEP_ID','CHECK_ID','RUN_ID','REPO','SNAPSHOT_HASH'].map(key => [key, process.env['WORKFLOW_' + key]]));
const integrated = identity.STEP_ID === 'integration';
if (integrated) root = path.join(root, 'core');
const summaryFile = path.join(root, 'core-check-summary.json'), summary = read(summaryFile);
assert(Object.values(identity).every(value => typeof value === 'string' && value.length), 'Workflow identity missing');
assert(identity.TASK_ID === plan.id && identity.STEP_ID === (integrated ? 'integration' : 'core')
  && identity.CHECK_ID === (integrated ? 'step-0-check-1' : 'runtime'), 'Wrong workflow phase or plan');
if (integrated) assert(plan.steps[0].id === 'core', 'Integration core check index changed');
for (const [field, key] of Object.entries({ taskId: 'TASK_ID', stepId: 'STEP_ID', runId: 'RUN_ID', repo: 'REPO', snapshotHash: 'SNAPSHOT_HASH' })) {
  assert(summary[field] === identity[key], 'Regression identity mismatch: ' + field);
}
assert(summary.checkId === (integrated ? 'step-0-check-0' : 'regression') && summary.database === 'cs_enhance_20261001' && summary.port === 18141, 'Independent regression boundary missing');
const started = Date.parse(summary.startedAt), finished = Date.parse(summary.checkedAt);
assert(Number.isFinite(started) && finished >= started && Date.now() - finished < 600000, 'Stale regression summary');
const suites = new Map(summary.suites.map(suite => [suite.suite, suite]));
assert(suites.size === summary.suites.length && suites.size >= 27, 'Missing regression suites');
for (const suite of suites.values()) {
  assert(suite.tests > 0 && suite.skipped === 0 && suite.failures === 0 && suite.errors === 0, 'Unexecuted or failed suite: ' + suite.suite);
  assert(hash(suite.report) === suite.sha256 && fs.statSync(suite.report).mtimeMs >= started, 'Stale or changed Surefire report: ' + suite.suite);
  assert(Array.isArray(suite.cases) && suite.cases.length === suite.tests, 'Missing executed case names: ' + suite.suite);
}
const evidence = new Map();
const scenes = new Map((summary.scenes || []).map(scene => [scene.name, scene]));
assert(scenes.size === 24, 'Complete sealed scenario manifest required');
for (const scene of scenes.values()) {
  assert(path.resolve(scene.path) === path.resolve(root, scene.name) && hash(scene.path) === scene.sha256, 'Scenario replaced after successful regression: ' + scene.name);
  assert(Date.parse(scene.writtenAt) >= started && Date.parse(scene.writtenAt) <= finished, 'Scenario outside regression: ' + scene.name);
}
function add(id, item) { const rows = evidence.get(id) || []; rows.push(item); evidence.set(id, rows); }
function fresh(file) {
  const absolute = path.join(root, file);
  assert(scenes.has(file) && hash(absolute) === scenes.get(file).sha256, 'Unsealed or changed scenario: ' + file);
  return absolute;
}
function caseEvidence(suite, name) {
  const row = suites.get(suite);
  assert(row && row.cases.includes(name), 'Required actual test did not execute: ' + suite + '#' + name);
  return `${row.report} sha256=${row.sha256} testcase=${name}`;
}
for (const filename of ['random-runtime.json','random-partial-runtime.json','random-concurrency-runtime.json',
  'binding-availability-runtime.json','finance-profile-runtime.json','profile-device-runtime.json','conversation-runtime.json',
  'avatar-runtime.json','sku-link-runtime.json','account-replay-runtime.json','original-actions-runtime.json','publisher-runtime.json','presence-runtime.json','profile-errors-runtime.json']) {
  const file = fresh(filename), report = read(file);
  assert(report.database === 'cs_enhance_20261001' && Date.parse(report.checkedAt) >= started, 'Scenario boundary mismatch: ' + filename);
  assert(report.workflowRunId === identity.RUN_ID && report.snapshotHash === identity.SNAPSHOT_HASH, 'Scenario workflow mismatch: ' + filename);
  assert(report.checks && Object.keys(report.checks).length, 'Empty scenario: ' + filename);
  for (const [id, result] of Object.entries(report.checks)) {
    assert(result.status === 'pass' && typeof result.evidence === 'string' && result.evidence.length, 'Failed scenario: ' + id);
    add(id, `${file} sha256=${hash(file)} ${result.evidence}`);
  }
}
const bindingSuite = 'SupportBindingRuntimeTest';
add('core-A11', caseEvidence('SupportEnhancementCoreRuntimeTest', 'originalOrphanCycleAndUnknownInheritanceRemainReviewRequired'));
add('core-A11', caseEvidence('SupportEnhancementCoreRuntimeTest', 'originalDuplicateAndOrphanMigrationAbortBeforeChangingRows'));
const privateTicket = fresh('legacy-s3/ticket-private-evidence.json');
assert(Object.values(read(privateTicket)).every(value => value === true), 'Protected ticket regression failed');
add('core-R22', caseEvidence(bindingSuite, 'convertedPrivateTextIsHiddenButInternalCollaborationRemains'));
add('core-R22', `${privateTicket} sha256=${hash(privateTicket)} real ticket/internal collaboration and protected source conversation`);
for (const id of ['core-R31','core-R32']) {
  const file = fresh('legacy-s3/unanswered-evidence.json'), result = read(file);
  assert(result.closeAndConvertBlocked && result.explicitCrossSegmentReply && result.historyUnchanged, 'Close/convert guard proof missing');
  add(id, caseEvidence(bindingSuite, 'unansweredMessagesCannotBeSealedAndCrossSegmentReplyDoesNotRewriteHistory'));
  add(id, `${file} sha256=${hash(file)} pending guard and explicit old-segment reply verified through actual HTTP`);
}
add('core-R32', caseEvidence(bindingSuite, 'unansweredOldRowsCannotStarveHandledIdleCandidate'));
const sourceScope = fresh('legacy-s3/scenario-evidence.json'), scope = read(sourceScope);
assert(scope.checks['s3-ac05'] && scope.checks['s3-ac06'], 'Actual HTTP/WS/SSE scope proof missing');
add('core-X01', `${sourceScope} sha256=${hash(sourceScope)} ${scope.checks['s3-ac05']} ${scope.checks['s3-ac06']}`);
add('core-X01', caseEvidence(bindingSuite, 'ticketWritesRequireCurrentAdvisorAndPersistActualAuthor'));
const readFile = fresh('legacy-s4/message-replay-mysql-evidence.json'), replay = read(readFile);
for (const name of ['MUTEX_READ_FIRST','MUTEX_REPLY_FIRST']) assert(replay.scenarios[name]?.passed === true, 'Read/reply mutex not proven: ' + name);
add('core-R34', `${readFile} sha256=${hash(readFile)} actual read/reply mutex in both orders persists exact receipts`);
for (const suite of ['OpsSupportTicketServiceTest','OpsSupportKnowledgeServiceTest','OpsSupportKnowledgeControllerTest',
  'OpsSessionTemplateServiceTest','AppNovaAiServiceTest','AppNovaAiPublishedFaqPathTest','AppSupportControllerProductionPathContractTest','KycRemovalContractTest']) {
  const row = suites.get(suite); assert(row, 'Original capability regression absent: ' + suite);
  add('core-X05', `${row.report} sha256=${row.sha256} ${row.tests} executed original module contract tests; no external AI provider or UI claim`);
}
add('core-X05', caseEvidence(bindingSuite, 'ticketWritesRequireCurrentAdvisorAndPersistActualAuthor'));
add('core-X05', caseEvidence(bindingSuite, 'isolatedApplicationStartsWithBindingSchema'));
const required = core.checks.find(check => check.id === 'runtime').requiredChecks;
assert(required.length === core.acceptance.length && core.acceptance.every(item => required.includes(item.id)), 'Runtime/acceptance coverage mismatch');
const steps = required.map(id => { const rows = evidence.get(id); assert(rows?.length, 'Unproven acceptance: ' + id); return { id, status: 'pass', evidence: rows }; });
fs.mkdirSync(path.dirname(values.report), { recursive: true });
fs.writeFileSync(values.report, JSON.stringify({ schemaVersion: 2, at: new Date().toISOString(), checkedAt: new Date().toISOString(), verdict: 'pass', mode: 'full',
  capability: 'runtime', treeMoved: false, innerSkipped: 0, taskId: identity.TASK_ID, stepId: identity.STEP_ID, checkId: identity.CHECK_ID,
  runId: identity.RUN_ID, repo: identity.REPO, snapshotHash: identity.SNAPSHOT_HASH,
  regression: { summary: summaryFile, sha256: hash(summaryFile), tests: summary.suites.reduce((sum, suite) => sum + suite.tests, 0) }, steps }, null, 2));
console.log(`Core evidence: ${steps.length} proven backend acceptances, ${summary.suites.length} fresh executed suites.`);

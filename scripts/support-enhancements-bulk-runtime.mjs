import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { parseArgs } from 'node:util';

const { values } = parseArgs({ options: { phase: { type: 'string' }, plan: { type: 'string' }, report: { type: 'string' } } });
const assert = (ok, message) => { if (!ok) throw new Error(message); };
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const hash = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
assert(['bulk','integration'].includes(values.phase) && values.plan && values.report, 'Authorized phase, plan and report required');
const plan = read(values.plan);
const identity = Object.fromEntries(['TASK_ID','STEP_ID','CHECK_ID','RUN_ID','REPO','SNAPSHOT_HASH'].map(key => [key, process.env['WORKFLOW_' + key]]));
assert(Object.values(identity).every(value => typeof value === 'string' && value.length), 'Workflow identity missing');
assert(identity.TASK_ID === plan.id && path.resolve(identity.REPO) === path.resolve(plan.repo), 'Wrong plan/repository');
assert(plan.steps[0].id === 'core' && plan.steps[1].id === 'bulk', 'Integration replay indexes changed');
const integrated = identity.STEP_ID === 'integration';
const runRoot = path.join('C:/Users/jason/.codex/workflow-runs/customer-service-enhancements-20261001/backend-bulk/runs',identity.RUN_ID,identity.STEP_ID);
const validateIdentity = (document, check) => {
  for (const [field,key] of Object.entries({taskId:'TASK_ID',stepId:'STEP_ID',runId:'RUN_ID',repo:'REPO',snapshotHash:'SNAPSHOT_HASH'}))
    assert(document[field] === identity[key], 'Evidence identity mismatch: ' + field);
  assert(document.checkId === check, 'Evidence check mismatch');
};
function summary(file, root, check, minimumSuites, sceneCount) {
  const document = read(file); validateIdentity(document, check);
  const start = Date.parse(document.startedAt), finish = Date.parse(document.checkedAt);
  assert(Number.isFinite(start) && finish >= start && finish <= Date.now()+2000, 'Invalid regression interval');
  assert(document.database === 'cs_enhance_20261001' && document.port === 18141, 'Independent runtime boundary missing');
  const suites = new Map(document.suites.map(row => [row.suite,row]));
  assert(suites.size === document.suites.length && suites.size >= minimumSuites, 'Missing actual suites');
  for (const row of suites.values()) {
    assert(row.tests > 0 && row.skipped === 0 && row.failures === 0 && row.errors === 0, 'Failed/unexecuted suite: '+row.suite);
    assert(hash(row.report) === row.sha256 && fs.statSync(row.report).mtimeMs >= start, 'Changed/stale XML: '+row.suite);
    assert(row.cases.length === row.tests && new Set(row.cases).size === row.tests, 'Missing actual case names: '+row.suite);
  }
  const scenes = new Map(document.scenes.map(row => [row.name,row]));
  assert(scenes.size === sceneCount && scenes.size === document.scenes.length, 'Incomplete sealed scenarios');
  for (const row of scenes.values()) {
    assert(path.resolve(row.path) === path.resolve(root,row.name) && hash(row.path) === row.sha256, 'Unsealed/changed scenario: '+row.name);
    assert(Date.parse(row.writtenAt) >= start && Date.parse(row.writtenAt) <= finish, 'Scenario outside actual regression');
  }
  return {document,suites,scenes,start,finish,file};
}
function fullRecord(file, check, required) {
  const record=read(file);validateIdentity(record,check);
  assert(record.schemaVersion === 2 && record.verdict === 'pass' && record.mode === 'full' && record.capability === 'runtime'
    && record.treeMoved === false && record.innerSkipped === 0, 'Incomplete upstream runtime record');
  const rows = new Map(record.steps.map(row=>[row.id,row]));
  assert(rows.size === required.length && required.every(id=>rows.has(id)), 'Upstream AC coverage changed');
  for(const row of rows.values()) assert(row.status === 'pass' && row.evidence.length > 0 && row.evidence.every(value=>typeof value==='string' && value.trim()), 'Missing upstream observed evidence');
  assert(hash(record.regression.summary) === record.regression.sha256, 'Upstream regression summary replaced');
  return {record,rows,file};
}
let steps, regression;
if (values.phase === 'bulk') {
  assert(identity.STEP_ID === (integrated ? 'integration' : 'bulk') && identity.CHECK_ID === (integrated ? 'step-1-check-1' : 'runtime'), 'Wrong bulk workflow check');
  const root=integrated ? path.join(runRoot,'bulk') : runRoot;
  const current=summary(path.join(root,'bulk-check-summary.json'),root,integrated ? 'step-1-check-0' : 'regression',6,3);
  assert(Date.now()-current.finish < 600000, 'Stale bulk regression');
  const evidence=new Map();
  for(const name of ['bulk-runtime.json','bulk-restart-runtime.json']) {
    const scene=current.scenes.get(name);assert(scene,'Missing bulk scene: '+name);
    const report=read(scene.path);
    assert(report.database === 'cs_enhance_20261001' && report.port === 18141 && report.workflowRunId === identity.RUN_ID && report.snapshotHash === identity.SNAPSHOT_HASH, 'Bulk scene boundary/identity mismatch');
    assert(Date.parse(report.checkedAt) >= current.start && Date.parse(report.checkedAt) <= current.finish, 'Stale bulk scene');
    assert(report.checks && Object.keys(report.checks).length, 'Empty bulk assertions');
    for(const [id,row] of Object.entries(report.checks)) {
      assert(row.status === 'pass' && typeof row.evidence === 'string' && row.evidence.trim(), 'Unproven bulk assertion: '+id);
      assert(['SupportBulkRuntimeTest','SupportBulkRestartRuntimeTest'].includes(row.suite), 'Bulk assertion is not an actual bulk suite');
      const suite=current.suites.get(row.suite);
      assert(suite?.cases.includes(row.testcase), 'Bulk assertion testcase did not execute: '+id);
      const rows=evidence.get(id) || [];
      rows.push(`${scene.path} sha256=${scene.sha256} ${row.evidence}; ${suite.report} sha256=${suite.sha256} testcase=${row.testcase}`);
      evidence.set(id,rows);
    }
  }
  const required=plan.steps[1].checks.find(row=>row.id==='runtime').requiredChecks;
  assert(required.length === plan.steps[1].acceptance.length && plan.steps[1].acceptance.every(row=>required.includes(row.id)), 'Bulk AC/check coverage mismatch');
  steps=required.map(id=>{const rows=evidence.get(id);assert(rows?.length,'Missing actual bulk AC: '+id);return {id,status:'pass',evidence:rows};});
  regression={summary:current.file,sha256:hash(current.file),tests:current.document.suites.reduce((total,row)=>total+row.tests,0)};
} else {
  assert(integrated && identity.CHECK_ID === 'integration-check-0', 'Wrong integration check');
  const core=fullRecord(plan.steps[0].checks.find(row=>row.id==='runtime').report,'step-0-check-1',plan.steps[0].acceptance.map(row=>row.id));
  const bulk=fullRecord(plan.steps[1].checks.find(row=>row.id==='runtime').report,'step-1-check-1',plan.steps[1].acceptance.map(row=>row.id));
  const coreSummary=summary(core.record.regression.summary,path.join(runRoot,'core'),'step-0-check-0',27,24);
  const bulkSummary=summary(bulk.record.regression.summary,path.join(runRoot,'bulk'),'step-1-check-0',6,3);
  const references=[`${core.file} sha256=${hash(core.file)} 55 current backend core ACs`,`${bulk.file} sha256=${hash(bulk.file)} 14 current backend bulk ACs`];
  const evidence={
    'integration-X01':[...core.rows.get('core-X01').evidence,...bulk.rows.get('bulk-X01').evidence,...bulk.rows.get('bulk-B07').evidence,...bulk.rows.get('bulk-B11').evidence],
    'integration-X05':[...core.rows.get('core-X05').evidence,...references],
    'integration-X06':[...references,`${coreSummary.file} sha256=${hash(coreSummary.file)} ${core.record.regression.tests} tests; ${bulkSummary.file} sha256=${hash(bulkSummary.file)} ${bulk.record.regression.tests} tests; all upstream checks replayed on the same run/snapshot, isolated DB/HTTP/storage; no frontend or production claim`]
  };
  const required=plan.integration.checks[0].requiredChecks;
  assert(required.length === 3 && plan.integration.acceptance.every(row=>required.includes(row.id)), 'Integration AC/check mismatch');
  steps=required.map(id=>{assert(evidence[id]?.length,'Missing integration AC');return {id,status:'pass',evidence:evidence[id]};});
  regression={summary:bulkSummary.file,sha256:hash(bulkSummary.file),tests:core.record.regression.tests+bulk.record.regression.tests};
}
fs.mkdirSync(path.dirname(values.report),{recursive:true});
fs.writeFileSync(values.report,JSON.stringify({schemaVersion:2,at:new Date().toISOString(),checkedAt:new Date().toISOString(),verdict:'pass',mode:'full',
  capability:'runtime',treeMoved:false,innerSkipped:0,taskId:identity.TASK_ID,stepId:identity.STEP_ID,checkId:identity.CHECK_ID,
  runId:identity.RUN_ID,repo:identity.REPO,snapshotHash:identity.SNAPSHOT_HASH,regression,steps},null,2));
console.log(`${values.phase} evidence: ${steps.length} actual backend acceptances, same run/snapshot.`);

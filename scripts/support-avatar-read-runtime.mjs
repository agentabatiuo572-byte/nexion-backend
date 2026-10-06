import fs from 'node:fs';
import path from 'node:path';
import { createHash } from 'node:crypto';
import { parseArgs } from 'node:util';

const { values } = parseArgs({ options: { phase: { type: 'string' }, plan: { type: 'string' }, report: { type: 'string' } } });
const assert = (ok, message) => { if (!ok) throw new Error(message); };
const read = file => JSON.parse(fs.readFileSync(file, 'utf8').replace(/^\uFEFF/, ''));
const hash = file => createHash('sha256').update(fs.readFileSync(file)).digest('hex');
assert(['avatar','integration'].includes(values.phase) && values.plan && values.report, 'Phase, plan and report required');
const plan = read(values.plan);
const identity = Object.fromEntries(['TASK_ID','STEP_ID','CHECK_ID','RUN_ID','REPO','SNAPSHOT_HASH'].map(key => [key, process.env['WORKFLOW_' + key]]));
assert(Object.values(identity).every(value => typeof value === 'string' && value.length), 'Workflow identity missing');
assert(identity.TASK_ID === plan.id && path.resolve(identity.REPO) === path.resolve(plan.repo), 'Wrong task/repository');
const integrated = identity.STEP_ID === 'integration';
const root = path.join('C:/Users/jason/.codex/workflow-runs/customer-service-enhancements-20261001/backend-avatar-read/runs', identity.RUN_ID, identity.STEP_ID);
function summary(phase, check, suiteCount, sceneCount) {
  const file = path.join(root, phase, `${phase}-check-summary.json`), document = read(file);
  for (const [field,key] of Object.entries({taskId:'TASK_ID',stepId:'STEP_ID',runId:'RUN_ID',repo:'REPO',snapshotHash:'SNAPSHOT_HASH'}))
    assert(document[field] === identity[key], 'Regression identity mismatch: ' + field);
  assert(document.checkId === check, 'Regression check mismatch');
  const start = Date.parse(document.startedAt), finish = Date.parse(document.checkedAt);
  assert(Number.isFinite(start) && finish >= start && finish <= Date.now()+2000, 'Invalid regression interval');
  assert(document.database === 'cs_enhance_20261001' && document.port === 18141, 'Wrong runtime boundary');
  const suites = new Map(document.suites.map(row => [row.suite,row]));
  assert(suites.size === suiteCount && suites.size === document.suites.length, 'Incomplete suite coverage');
  for (const row of suites.values()) {
    assert(row.tests > 0 && row.skipped === 0 && row.failures === 0 && row.errors === 0, 'Unexecuted/failed suite');
    assert(path.resolve(row.report).startsWith(path.resolve(root,phase)+path.sep) && hash(row.report) === row.sha256 && fs.statSync(row.report).mtimeMs >= start, 'Changed/stale/out-of-scope XML');
    assert(row.cases.length === row.tests && new Set(row.cases).size === row.tests, 'Missing executed case names');
  }
  assert(document.scenes.length === sceneCount && new Set(document.scenes.map(row=>row.name)).size === sceneCount, 'Incomplete scenes');
  for (const row of document.scenes) {
    assert(path.resolve(row.path) === path.resolve(root,phase,row.name) && hash(row.path) === row.sha256, 'Changed/out-of-scope scene');
    assert(Date.parse(row.writtenAt) >= start && Date.parse(row.writtenAt) <= finish, 'Scene outside regression');
  }
  return {file,document,suites,start,finish};
}
let steps, regressions;
if (values.phase === 'avatar') {
  assert(identity.STEP_ID === (integrated ? 'integration' : 'avatar') && identity.CHECK_ID === (integrated ? 'step-0-check-1' : 'runtime'), 'Wrong avatar check');
  const current = summary('avatar', integrated ? 'step-0-check-0' : 'regression', 2, 2);
  const assertions = new Map();
  for (const scene of current.document.scenes) {
    const document = read(scene.path);
    assert(document.workflowRunId === identity.RUN_ID && document.snapshotHash === identity.SNAPSHOT_HASH && document.database === 'cs_enhance_20261001' && document.port === 18141, 'Avatar scene identity mismatch');
    assert(Date.parse(document.checkedAt) >= current.start && Date.parse(document.checkedAt) <= current.finish, 'Stale avatar assertions');
    for (const [id,row] of Object.entries(document.checks)) {
      assert(!assertions.has(id), 'Duplicate avatar assertion');
      assertions.set(id,{scene,row});
    }
  }
  const required = plan.steps[0].acceptance.map(row=>row.id);
  assert(assertions.size === required.length, 'Avatar coverage mismatch');
  steps = required.map(id => {
    const {scene,row} = assertions.get(id) || {}, suite = current.suites.get(row?.suite);
    assert(row?.status === 'pass' && typeof row.evidence === 'string' && row.evidence.trim() && suite?.cases.includes(row.method), 'Missing executed avatar assertion: '+id);
    return {id,status:'pass',evidence:[`${scene.path} sha256=${scene.sha256} ${row.evidence}`,`${suite.report} sha256=${suite.sha256} testcase=${row.method}`]};
  });
  regressions = [current];
} else {
  assert(integrated && identity.CHECK_ID === 'integration-check-2', 'Wrong integration check');
  const avatar = summary('avatar','step-0-check-0',2,2), core = summary('core','integration-check-0',27,24), bulk = summary('bulk','integration-check-1',6,3);
  const avatarFile = plan.steps[0].checks.find(row=>row.id==='runtime').report, record = read(avatarFile);
  for (const [field,key] of Object.entries({taskId:'TASK_ID',stepId:'STEP_ID',runId:'RUN_ID',repo:'REPO',snapshotHash:'SNAPSHOT_HASH'}))
    assert(record[field] === identity[key], 'Avatar replay identity mismatch: '+field);
  assert(record.checkId === 'step-0-check-1' && record.schemaVersion === 2 && record.verdict === 'pass' && record.mode === 'full'
    && record.capability === 'runtime' && record.treeMoved === false && record.innerSkipped === 0, 'Current full avatar replay missing');
  const avatarIds = plan.steps[0].acceptance.map(row=>row.id);
  assert(record.steps.length === avatarIds.length && new Set(record.steps.map(row=>row.id)).size === avatarIds.length
    && record.steps.every(row=>avatarIds.includes(row.id) && row.status==='pass' && row.evidence?.length), 'Avatar replay acceptance coverage missing');
  assert(record.regressions.length === 1 && path.resolve(record.regressions[0].summary) === path.resolve(avatar.file)
    && hash(avatar.file) === record.regressions[0].sha256, 'Avatar replay summary changed');
  assert(core.document.suites.reduce((sum,row)=>sum+row.tests,0) >= 365 && bulk.document.suites.reduce((sum,row)=>sum+row.tests,0) >= 101, 'Core/bulk execution coverage reduced');
  const evidence = {
    'integration-core':[`${core.file} sha256=${hash(core.file)} 27 current core suites and 24 scenarios, zero failures/errors/skips`,`${avatarFile} sha256=${hash(avatarFile)} eight current avatar read/compensation boundaries`],
    'integration-bulk':[`${bulk.file} sha256=${hash(bulk.file)} six current bulk suites, three scenarios and separate JVM restart, zero failures/errors/skips`]
  };
  steps = plan.integration.acceptance.map(row=>({id:row.id,status:'pass',evidence:evidence[row.id]}));
  assert(steps.every(row=>row.evidence?.length), 'Integration coverage missing');
  regressions = [avatar,core,bulk];
}
fs.mkdirSync(path.dirname(values.report),{recursive:true});
fs.writeFileSync(values.report,JSON.stringify({schemaVersion:2,at:new Date().toISOString(),checkedAt:new Date().toISOString(),verdict:'pass',mode:'full',capability:'runtime',treeMoved:false,innerSkipped:0,
  taskId:identity.TASK_ID,stepId:identity.STEP_ID,checkId:identity.CHECK_ID,runId:identity.RUN_ID,repo:identity.REPO,snapshotHash:identity.SNAPSHOT_HASH,
  regressions:regressions.map(row=>({summary:row.file,sha256:hash(row.file),tests:row.document.suites.reduce((sum,suite)=>sum+suite.tests,0)})),steps},null,2));
console.log(`${values.phase}: ${steps.length} current runtime acceptance records.`);

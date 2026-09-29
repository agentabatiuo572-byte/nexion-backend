import fs from 'node:fs';
import path from 'node:path';
import {createHash} from 'node:crypto';
import {spawnSync} from 'node:child_process';

// Actual opt-in Spring Boot/MySQL/Redis/HTTP/WS/SSE checks. Reports never infer pass from source text.
const env=process.env, startedAt=new Date().toISOString();
const reportFile=process.argv[process.argv.indexOf('--report')+1];
const integration=process.argv.includes('--integration');
const required=['WORKFLOW_TASK_ID','WORKFLOW_STEP_ID','WORKFLOW_CHECK_ID','WORKFLOW_RUN_ID','WORKFLOW_REPO','WORKFLOW_SNAPSHOT_HASH','S3_EVIDENCE_DIR','JAVA_HOME'];
for(const key of required) if(!env[key]) throw Error(`Missing ${key}; load support-redesign-s3-env.ps1 and use workflow run`);
if(!reportFile || !env.NEXION_DB_URL?.startsWith('jdbc:mysql://127.0.0.1:33329/cs_redesign?') || env.NEXION_REDIS_PORT!=='16329') throw Error('Missing report or isolation boundary mismatch');
const dir=env.S3_EVIDENCE_DIR;
const evidence={};
function check(value,message){if(!value)throw Error(message);}
function read(file){return JSON.parse(fs.readFileSync(file,'utf8').replace(/^\uFEFF/,''));}
function migration(file,report,count,preflight){
  const data=read(report), hash=createHash('sha256').update(fs.readFileSync(file,'utf8').replaceAll('\r\n','\n')).digest('hex');
  check(data.pass===true && data.exitCode===0 && data.sourceUnchangedDuringRun===true && data.migrationSha256===hash,'Migration evidence stale or failed: '+file);
  check(data.cases.length>=count && data.cases.every(c=>c.pass && c.assertions.length && c.assertions.every(a=>a.pass)),'Incomplete migration cases');
  if(preflight) check(data.preflightSha256===createHash('sha256').update(fs.readFileSync(preflight,'utf8').replaceAll('\r\n','\n')).digest('hex'),'Preflight evidence stale: '+preflight);
  return report;
}
const binding=migration('scripts/migrations/20260929_support_binding_s3.sql',path.join(dir,'migration-review/20260929T083220Z_bb35cb4a/report.json'),12,'scripts/migrations/20260929_support_binding_s3_preflight.sql');
const source=migration('scripts/migrations/20260929_support_ticket_source_s3.sql',path.join(dir,'migration-review/ticket_source_20260929T085226Z_dffc3d77/report.json'),18);
const runtimePath=path.join(dir,'runtime.json');
if(integration){
  const prior=read(runtimePath);
  check(prior.runId===env.WORKFLOW_RUN_ID && prior.snapshotHash===env.WORKFLOW_SNAPSHOT_HASH && prior.verdict==='pass','Integration requires fresh upstream runtime in this exact run');
  check(fs.statSync('docs/HANDOFF-support-s3.md').size>1000,'Missing concrete handoff');
  const diff=spawnSync('git',['diff','--check'],{encoding:'utf8',windowsHide:true});
  check(diff.status===0,'Whitespace/diff check failed');
  evidence['s3-handoff']=[runtimePath,binding,source,path.resolve('docs/HANDOFF-support-s3.md')];
}else{
  const classes=['SupportBindingRuntimeTest','OpsConsoleArchitectureTest','OpsSupportKnowledgeServiceTest','OpsSupportKnowledgeControllerTest','OpsSupportTicketServiceTest','OpsConversationControllerTest','MybatisSupportTicketRepositoryTest','ConversationSocketCommandsTest','ConversationSocketHandlerTest','ConversationSocketTicketsTest','ConversationSocketWireTest','ConversationSocketSecurityIntegrationTest','AppSupportCommandRecoveryTest','ConversationIdleTimeoutSchedulerTest','ConversationMapperSqlTest','OpsSupportAgentServiceTest','AppSupportServiceTest','OpsConversationServiceTest','MybatisSupportAgentRepositoryTest','MybatisConversationRepositoryTest','AppUserRegistrationServiceTest','AppUserRegistrationReferralLockContractTest','ConversationSocketAccessTest','ConversationAdminReadServiceTest','OpsConversationStreamControllerTest','OpsAdminAccountServiceTest'];
  const log=path.join(dir,`workflow-runtime-${Date.now()}.log`), fd=fs.openSync(log,'wx'),start=Date.now();
  const mvn='D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9/bin/mvn.cmd';
  const result=spawnSync('pwsh.exe',['-NoProfile','-Command',`& '${mvn}' '-Dtest=${classes.join(',')}' test; exit $LASTEXITCODE`],{env,stdio:['ignore',fd,fd],windowsHide:true,timeout:540000});
  fs.closeSync(fd);check(!result.error && result.status===0,`Runtime regression failed; inspect ${log}`);
  let tests=0;
  const xmlDir='target/surefire-reports',xmls=fs.readdirSync(xmlDir);
  for(const name of classes){
    const file=xmls.find(f=>f.endsWith(`.${name}.xml`) && f.startsWith('TEST-'));check(file,`No report for ${name}`);
    const full=path.join(xmlDir,file);check(fs.statSync(full).mtimeMs>=start-1000,`Stale report ${name}`);
    const header=fs.readFileSync(full,'utf8').match(/<testsuite\b[^>]*>/)?.[0]||'';
    const attrs=Object.fromEntries([...header.matchAll(/(tests|failures|errors|skipped)="(\d+)"/g)].map(m=>[m[1],Number(m[2])]));
    check(attrs.tests>0 && attrs.failures===0 && attrs.errors===0 && attrs.skipped===0,`Nonpassing/skipped suite ${name}`);
    if(name==='SupportBindingRuntimeTest')check(attrs.tests>=12,'Runtime scenarios missing');tests+=attrs.tests;
  }
  const artifacts=['scenario-evidence.json','registration-evidence.json','concurrency-evidence.json','strict-rules-evidence.json','strict-binding-evidence.json','unanswered-evidence.json','ticket-evidence.json','ticket-private-evidence.json','send-transfer-evidence.json','read-grant-evidence.json','runtime-identities.json'];
  for(const name of artifacts){const file=path.join(dir,name);check(fs.statSync(file).mtimeMs>=start-1000,`Stale scenario evidence ${name}`);read(file);}
  const scenario=read(path.join(dir,'scenario-evidence.json'));
  for(const id of ['s3-ac02','s3-ac03','s3-ac04','s3-ac05','s3-ac06','s3-ac13'])check(scenario.checks[id],`No observed proof ${id}`);
  for(const id of ['s3-ac02','s3-ac03','s3-ac04','s3-ac05','s3-ac06','s3-ac11','s3-ac13'])evidence[id]=[log,path.join(dir,'scenario-evidence.json')];
  evidence['s3-ac02'].push(path.join(dir,'registration-evidence.json'),path.join(dir,'concurrency-evidence.json'));
  evidence['s3-ac04'].push(path.join(dir,'strict-binding-evidence.json'),path.join(dir,'send-transfer-evidence.json'));
  evidence['s3-ac05'].push(path.join(dir,'read-grant-evidence.json'),path.join(dir,'ticket-private-evidence.json'));
  evidence['s3-ac06'].push(path.join(dir,'ticket-evidence.json'),path.join(dir,'ticket-private-evidence.json'));
  evidence['s3-r08']=[log,path.join(dir,'ticket-private-evidence.json'),source];
  evidence['s3-ac11'].push(binding,source);
  evidence['s3-ac13'].push(path.join(dir,'strict-rules-evidence.json'));
  console.log(`Real runtime and regressions passed: ${tests} tests, no skips; migration cases: 12 + 18.`);
}
fs.writeFileSync(reportFile,JSON.stringify({taskId:env.WORKFLOW_TASK_ID,stepId:env.WORKFLOW_STEP_ID,checkId:env.WORKFLOW_CHECK_ID,runId:env.WORKFLOW_RUN_ID,repo:env.WORKFLOW_REPO,snapshotHash:env.WORKFLOW_SNAPSHOT_HASH,startedAt,at:startedAt,verdict:'pass',mode:'full',treeMoved:false,capability:'runtime',steps:Object.entries(evidence).map(([id,files])=>({id,status:'pass',innerSkipped:0,evidence:files}))},null,2));

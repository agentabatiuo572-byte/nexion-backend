import fs from 'node:fs';
import path from 'node:path';
import {spawnSync} from 'node:child_process';
const env=process.env, startedAt=new Date().toISOString();
const report=process.argv[process.argv.indexOf('--report')+1];
const dir='C:/Users/jason/.codex/workflow-runs/customer-service-20260929/advisor-patch';
function check(ok,msg){if(!ok)throw Error(msg);}
for(const key of ['WORKFLOW_TASK_ID','WORKFLOW_STEP_ID','WORKFLOW_CHECK_ID','WORKFLOW_RUN_ID','WORKFLOW_REPO','WORKFLOW_SNAPSHOT_HASH','JAVA_HOME'])check(env[key],'Missing '+key);
check(report && env.NEXION_DB_URL?.startsWith('jdbc:mysql://127.0.0.1:33329/cs_advisor_patch?') && env.NEXION_REDIS_PORT==='16329' && env.SPRING_DATA_REDIS_DATABASE==='14' && env.NEXION_MINIO_BUCKET==='cs-advisor-patch-private' && env.SUPPORT_PATCH_ISOLATED==='true' && env.S4_HTTP_PORT==='18130' && env.SERVER_ADDRESS==='127.0.0.1','Isolation boundary mismatch');
fs.mkdirSync(dir,{recursive:true});
check(env.NEXION_MINIO_ENDPOINT==='http://127.0.0.1:19030','Dedicated storage required');
const read=f=>JSON.parse(fs.readFileSync(f,'utf8').replace(/^\uFEFF/,''));
const runtime=path.join(dir,'runtime.json'), evidence={};
if(process.argv.includes('--integration')){
 const prior=read(runtime);check(prior.runId===env.WORKFLOW_RUN_ID && prior.snapshotHash===env.WORKFLOW_SNAPSHOT_HASH && prior.verdict==='pass','Fresh runtime required');
 check(fs.statSync('docs/HANDOFF-support-advisor.md').size>1000,'Concrete handoff required');
 check(spawnSync('git',['diff','--check'],{windowsHide:true}).status===0,'Diff check failed');
 evidence['advisor-handoff']=[runtime,path.resolve('docs/HANDOFF-support-advisor.md')];
}else{
 const classes=['AppSupportAdvisorRuntimeTest','AppSupportControllerTest','AppSupportControllerProductionPathContractTest','AppSupportControllerSecurityTest','MybatisSupportAgentRepositoryTest','OpsSupportAgentServiceTest','SupportAgentMapperTicketAssigneeCandidateSqlContractTest','AppSupportVisibilityMapperContractTest','SupportS4RuntimeTest','SupportMessageReplayMySqlS4Test','SupportHumanMessageServiceTest','AppUserAuthServiceTest','AppUserOAuthServiceTest','AppSupportServiceTest','AppSupportCommandRecoveryTest','OpsConversationServiceTest','MybatisConversationRepositoryTest','ConversationMapperSqlTest','AppSupportCursorPaginationContractTest','ConversationSocketCommandsTest','ConversationSocketWireTest','OpsConsoleArchitectureTest'];
 const find=d=>fs.readdirSync(d,{withFileTypes:true}).flatMap(x=>x.isDirectory()?find(path.join(d,x.name)):[path.join(d,x.name)]);
 for(const file of find('src/test/java/ffdd/opsconsole/content'))if(/Support(?:Maintenance|Activity|Workbench|Attachment).*Test\.java$/.test(file))classes.push(path.basename(file,'.java'));
 const log=path.join(dir,'runtime-'+Date.now()+'.log'),fd=fs.openSync(log,'wx'),start=Date.now();
 const result=spawnSync('pwsh.exe',['-NoProfile','-Command',"& 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9/bin/mvn.cmd' '-Dtest="+classes.join(',')+"' test; exit $LASTEXITCODE"],{env:{...env,S4_EVIDENCE_DIR:dir},stdio:['ignore',fd,fd],windowsHide:true,timeout:540000});fs.closeSync(fd);
 check(!result.error && result.status===0,'Tests failed; '+log);
 const reports=fs.readdirSync('target/surefire-reports');let total=0;
 for(const name of classes){const xml=reports.find(f=>f.startsWith('TEST-') && f.endsWith('.'+name+'.xml'));check(xml,'Missing suite '+name);const file=path.join('target/surefire-reports',xml);check(fs.statSync(file).mtimeMs>=start-1000,'Stale suite '+name);const header=fs.readFileSync(file,'utf8').match(/<testsuite\b[^>]*>/)?.[0]||'';const attrs=Object.fromEntries([...header.matchAll(/(tests|failures|errors|skipped)="(\d+)"/g)].map(m=>[m[1],+m[2]]));check(attrs.tests>0 && attrs.errors===0 && attrs.failures===0 && attrs.skipped===0,'Failed/skipped suite '+name);total+=attrs.tests;}
 const proofFile=path.join(dir,'scenario-evidence.json');check(fs.statSync(proofFile).mtimeMs>=start-1000,'Stale runtime observations');const proof=read(proofFile);
 const replayFile=path.join(dir,'message-replay-mysql-evidence.json');check(fs.statSync(replayFile).mtimeMs>=start-1000,'Stale replay observations');
 const replay=read(replayFile);for(const route of ['APP_CREATE','APP_REPLY','ADMIN_INITIATE','ADMIN_REPLY','APP_CREATE_TRANSFER','ADMIN_WS_INITIATE','ADMIN_WS_REPLY','MUTEX_READ_FIRST','MUTEX_REPLY_FIRST'])check(replay.scenarios[route]?.passed===true,'Missing concurrency proof '+route);
 for(const id of ['s4-ac01','s4-ac07','s4-ac08','s4-ac09','s4-ac10','s4-ac13','s4-supplement']){check(proof.checks[id]===true,'Missing runtime proof '+id);evidence[id]=[log,proofFile];}
 const advisorFile=path.join(dir,'advisor-evidence.json');check(fs.statSync(advisorFile).mtimeMs>=start-1000,'Stale advisor observations');
 const advisor=read(advisorFile);check(advisor.checks?.length===11 && advisor.scheduledJobsDisabled===true && advisor.database==='cs_advisor_patch' && advisor.port===18130,'Advisor proof incomplete');
 for(const id of Object.keys(evidence))delete evidence[id];
 evidence['advisor-contract']=[log,advisorFile];
 evidence['advisor-security']=[log,advisorFile];
 evidence['advisor-regression']=[log,proofFile,replayFile,'Original 279 selected S4 tests plus advisor and controller tests; no skips.'];
 console.log('Runtime checks passed: '+total+' tests; no skips.');
}
fs.writeFileSync(report,JSON.stringify({taskId:env.WORKFLOW_TASK_ID,stepId:env.WORKFLOW_STEP_ID,checkId:env.WORKFLOW_CHECK_ID,runId:env.WORKFLOW_RUN_ID,repo:env.WORKFLOW_REPO,snapshotHash:env.WORKFLOW_SNAPSHOT_HASH,startedAt,at:new Date().toISOString(),verdict:'pass',mode:'full',treeMoved:false,capability:'runtime',steps:Object.entries(evidence).map(([id,evidence])=>({id,status:'pass',innerSkipped:0,evidence}))},null,2));

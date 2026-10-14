[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$ContextPath,
    [Parameter(Mandatory)][string]$EvidenceDirectory,
    [Parameter(Mandatory)][string]$AppClientPath,
    [Parameter(Mandatory)][string]$SchemaReaderClient,
    [Parameter(Mandatory)][string]$TestPomPath,
    [ValidateSet('contribution','refund','access','settlement','refund-readback-and-settlement','earnings-and-retained-principal','retained-principal')][string]$Scenario='contribution'
)
$ErrorActionPreference='Stop'
$repo=Split-Path -Parent $PSScriptRoot
$EvidenceDirectory=[IO.Path]::GetFullPath($EvidenceDirectory).Replace('\','/').TrimEnd('/')
$runtime='D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2/runtime'
function Assert-NetWithdrawalReady([string]$JsonText) {
    $proof=$JsonText | ConvertFrom-Json
    if($proof.databaseName -cne 'cs_analytics_20261007' -or $proof.port -ne 33338 -or $proof.serverUuid -cne 'abe359c0-c621-11f1-97ea-a40c6626953d' -or $proof.currentUser -cne 'support_net_r55_app@127.0.0.1'){throw 'Withdrawal preflight requires the fixed restricted clone'}
    $value=if($null -ne $proof.canonical){$proof.canonical}else{$proof.legacy}
    if($null -eq $value -or $value.Trim().ToLowerInvariant() -cnotin @('enabled','enable','on','true','1')){throw 'Withdrawal is disabled or missing; stop before Spring and account creation'}
    return $proof
}
function Read-NetResourceReply([Diagnostics.Process]$Process) {
    $line=$Process.StandardOutput.ReadLineAsync()
    if(!$line.Wait(10000)){throw 'NET resource guard did not answer'}
    $text=$line.GetAwaiter().GetResult()
    if(!$text){throw 'NET resource guard stopped before verification'}
    $reply=$text | ConvertFrom-Json
    if(!$reply.ok){throw "NET resource budget failed: $($reply.error)"}
    return $reply
}
function Start-NetResourceGuard([string]$EvidenceRoot,[string]$CloneRoot) {
    $code=@'
import {createInterface} from 'node:readline';
import {resourceGuard} from 'file:///D:/WORKS/PLAN/scripts/codex-workflow/lib/test-resources.mjs';
const send=value=>process.stdout.write(JSON.stringify(value)+'\n');
try {
  const guard=resourceGuard(process.argv[1],{kind:'record',resources:{roots:[process.argv[2]],
    maxBytes:2*1024**3,maxGrowthBytes:256*1024**2,minFreeBytes:10*1024**3,maxFiles:20000}});
  send({ok:true,budget:guard.budget,roots:guard.roots,before:guard.before});
  for await(const line of createInterface({input:process.stdin})) {
    if(line==='quit')break;
    try {if(line!=='measure')throw new Error('Unexpected resource guard request');send({ok:true,measurement:guard.measure()});}
    catch(error){send({ok:false,error:error.message});}
  }
} catch(error){send({ok:false,error:error.message});process.exitCode=1;}
'@
    $info=[Diagnostics.ProcessStartInfo]::new((Get-Command node -ErrorAction Stop).Source)
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardInput=$true;$info.RedirectStandardOutput=$true
    foreach($argument in @('--input-type=module','-e',$code,$EvidenceRoot,$CloneRoot)){$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info;$started=$false
    try {
        if(!$process.Start()){throw 'NET resource guard failed to start'}
        $started=$true
        $baseline=Read-NetResourceReply $process
        return @{process=$process;baseline=$baseline}
    } catch {if($started -and !$process.HasExited){$process.Kill($true);[void]$process.WaitForExit(5000)};$process.Dispose();throw}
}
function Measure-NetResourceGuard {
    $netResourceGuard.process.StandardInput.WriteLine('measure')
    $netResourceGuard.process.StandardInput.Flush()
    return (Read-NetResourceReply $netResourceGuard.process).measurement
}
function Stop-NetResourceGuard {
    try {
        if(!$netResourceGuard.process.HasExited){$netResourceGuard.process.StandardInput.WriteLine('quit');$netResourceGuard.process.StandardInput.Close();if(!$netResourceGuard.process.WaitForExit(5000)){$netResourceGuard.process.Kill($true);[void]$netResourceGuard.process.WaitForExit(5000)}}
    } finally {$netResourceGuard.process.Dispose()}
}
foreach($path in @($ContextPath,$AppClientPath,$SchemaReaderClient,$TestPomPath)) {
    $item=Get-Item -LiteralPath $path
    if($item.PSIsContainer -or ($item.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw 'Plain current NET input required'}
}
$context=Get-Content -Raw -LiteralPath $ContextPath | ConvertFrom-Json
$flat=$context.identity
foreach($field in @('taskId','stepId','checkId','runId','repo','snapshotHash')){if([string]::IsNullOrWhiteSpace($flat.$field)){throw "Current workflow identity missing: $field"}}
if([IO.Path]::GetFullPath($flat.repo) -cne [IO.Path]::GetFullPath($repo)){throw 'Current NET repository mismatch'}
if($flat.snapshotHash -cnotmatch '^[a-f0-9]{64}$'){throw 'Current NET snapshot required'}
foreach($name in @('actor-context.json','object-before.json','shared-before.json','net-result.json')){if(Test-Path -LiteralPath "$EvidenceDirectory/$name"){throw 'One-time NET evidence already exists; preserve it'} }
function Read-FixedNetAppClient([string]$ClientText,[ValidateSet('support_net_r55_app','support_net_schema_reader')][string]$ExpectedUser='support_net_r55_app') {
    $fields=@{}
    foreach($line in ($ClientText -split '\r?\n')) {
        if([string]::IsNullOrWhiteSpace($line) -or $line -match '^\s*[#;]' -or $line.Trim() -ceq '[client]'){continue}
        if($line -cnotmatch '^\s*(host|port|user|password|protocol|database)\s*=\s*(.*?)\s*$'){throw 'Unexpected private APP option'}
        $key=$Matches[1];$value=$Matches[2]
        if($fields.ContainsKey($key)){throw 'Duplicate private APP option'}
        $fields[$key]=$value
    }
    if($fields.host -cne '127.0.0.1' -or $fields.port -cne '33338' -or $fields.user -cne $ExpectedUser -or $fields.database -cne 'cs_analytics_20261007' -or !$fields.protocol -or $fields.protocol.ToUpperInvariant() -cne 'TCP'){throw 'Fixed clone-only client required'}
    $privateValue=$fields.password
    if($privateValue -and $privateValue.StartsWith('"') -and $privateValue.EndsWith('"')){$privateValue=$privateValue.Substring(1,$privateValue.Length-2)}
    if([string]::IsNullOrEmpty($privateValue) -or $privateValue -match '[\x00\r\n''"\\\s]'){throw 'Unsupported private APP password syntax'}
    return @{username=$fields.user;password=$privateValue}
}
$app=Read-FixedNetAppClient ([IO.File]::ReadAllText($AppClientPath))
$reader=Read-FixedNetAppClient ([IO.File]::ReadAllText($SchemaReaderClient)) 'support_net_schema_reader'
$withdrawalPreflight=$null
if($Scenario -in @('settlement','refund-readback-and-settlement','earnings-and-retained-principal','retained-principal')) {
    $sql="SELECT JSON_OBJECT('databaseName',DATABASE(),'port',@@port,'serverUuid',@@server_uuid,'currentUser',CURRENT_USER(),'canonical',(SELECT setting_value FROM nx_emergency_control_setting WHERE setting_key='killswitch.withdraw' AND is_deleted=0),'legacy',(SELECT setting_value FROM nx_emergency_control_setting WHERE setting_key='emergency.killswitch.withdraw' AND is_deleted=0));"
    $reply=& 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/mysql-verified/mysql-8.4.6-winx64/bin/mysql.exe' "--defaults-file=$AppClientPath" --batch --skip-column-names --skip-reconnect --execute=$sql
    if($LASTEXITCODE -ne 0){throw 'Read-only withdrawal preflight failed; do not start Spring'}
    $withdrawalPreflight=Assert-NetWithdrawalReady ($reply -join "`n")
}
$local=Get-Content -Raw -LiteralPath "$runtime/local-runtime-private.json" | ConvertFrom-Json
$storage=Get-Content -Raw -LiteralPath "$runtime/local-storage-private.json" | ConvertFrom-Json
$encryption=Get-Content -Raw -LiteralPath "$runtime/local-encryption-private.json" | ConvertFrom-Json
$env:WORKFLOW_TASK_ID=$flat.taskId;$env:WORKFLOW_STEP_ID=$flat.stepId;$env:WORKFLOW_CHECK_ID=$flat.checkId
$env:WORKFLOW_RUN_ID=$flat.runId;$env:WORKFLOW_REPO=$flat.repo;$env:WORKFLOW_SNAPSHOT_HASH=$flat.snapshotHash
$env:SUPPORT_RUNTIME_TARGET='analytics-net-committed-r64';$env:SUPPORT_RESOURCE_OWNERSHIP=$context.resourceOwnership.path
$env:JAVA_HOME='D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/jdk-17.0.20.1+1'
$env:NEXION_DB_URL='jdbc:mysql://127.0.0.1:33338/cs_analytics_20261007?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:NEXION_DB_USERNAME=$app.username;$env:NEXION_DB_PASSWORD=$app.password
$env:NEXION_SUPPORT_NET_SCHEMA_READER_USERNAME=$reader.username;$env:NEXION_SUPPORT_NET_SCHEMA_READER_PASSWORD=$reader.password
$env:NEXION_REDIS_HOST='127.0.0.1';$env:NEXION_REDIS_PORT='16343';$env:NEXION_REDIS_PASSWORD=$local.redisPassword;$env:SPRING_DATA_REDIS_DATABASE='0'
$env:NEXION_JWT_SECRET=$local.jwtSecret;$env:NEXION_FINANCE_DATA_KEY=$encryption.financeKey;$env:NEXION_ADMIN_MFA_ENCRYPTION_KEY=$encryption.adminMfaEncryptionKey
$env:NEXION_MINIO_ENDPOINT=$storage.endpoint;$env:NEXION_MINIO_BUCKET=$storage.bucket;$env:NEXION_MINIO_ACCESS_KEY=$storage.accessKey;$env:NEXION_MINIO_SECRET_KEY=$storage.secretKey
$env:SERVER_ADDRESS='127.0.0.1';$env:SERVER_PORT='18162';$env:S4_HTTP_PORT='18162';$env:SPRING_PROFILES_ACTIVE='dev'
foreach($name in @('NEXION_TREASURY_DEVELOPMENTRESERVE_ENABLED','NEXION_HOME_DEVELOPMENTSETTLEMENT_ENABLED','NEXGRID_SMS_ITNIO_ENABLED','NEXION_FINANCE_DEVELOPMENTPAYOUTADDRESS_ENABLED')){[Environment]::SetEnvironmentVariable($name,'false')}
$env:NEXION_CREGIS_MODE='DISABLED';$env:NEXION_HDPAY_MODE='DISABLED'
$env:NEXION_SUPPORT_ATTACHMENTS_ALLOWEDMIMETYPES='image/png,image/jpeg';$env:NEXION_SUPPORT_ATTACHMENTS_MAXBYTES='1048576';$env:NEXION_SUPPORT_ATTACHMENTS_MAXPIXELS='1000000';$env:NEXION_SUPPORT_ATTACHMENTS_TTLSECONDS='300'
$env:CS_ENHANCE_EVIDENCE_DIR=$EvidenceDirectory
$env:SUPPORT_STORAGE_BASELINE_MANIFEST="$runtime/storage-baseline-objects.json"
Set-Location -LiteralPath $repo
$env:SUPPORT_CANDIDATE=(& git rev-parse HEAD).Trim()
foreach($flag in @('CS_ENHANCE_PREP_ENABLED','CS_ENHANCE_CORE_ENABLED','NEXION_C1_AUDIT_MYSQL','SUPPORT_PATCH_ISOLATED','CS_ENHANCE_BULK_ENABLED','CS_ENHANCE_AVATAR_READ_ENABLED','CS_ENHANCE_AVATAR_A2_ENABLED','CS_ENHANCE_DEFAULT_AVATAR_SELF_ENABLED','CS_ANALYTICS_AGGREGATE_ENABLED','CS_ANALYTICS_FIRST_ENABLED','CS_ANALYTICS_HTTP_ENABLED')){[Environment]::SetEnvironmentVariable($flag,'true')}
foreach($flag in @('CS_ANALYTICS_COMMITTED_ENABLED','CS_ANALYTICS_GROUPS_ENABLED','CS_ANALYTICS_GROUP_SCOPE_ENABLED','CS_ANALYTICS_PAYMENT_FACTS_ENABLED','CS_ANALYTICS_RULES_CONTRACT_ENABLED','CS_ANALYTICS_SESSION_CONTRACT_ENABLED','CS_LEADERBOARD_ENABLED')){[Environment]::SetEnvironmentVariable($flag,'false')}
$env:S3_EVIDENCE_DIR="$EvidenceDirectory/legacy-s3";$env:S4_EVIDENCE_DIR="$EvidenceDirectory/legacy-s4";$env:S3_FIXTURE_PASSWORD='Aa1!'+[Guid]::NewGuid().ToString('N')
$budgetRoot="$runtime/support-minimal-20261012"
$evidenceFull=[IO.Path]::GetFullPath($EvidenceDirectory);$budgetFull=[IO.Path]::GetFullPath($budgetRoot)
if($evidenceFull -ine $budgetFull -and !$evidenceFull.StartsWith($budgetFull+[IO.Path]::DirectorySeparatorChar,[StringComparison]::OrdinalIgnoreCase)){throw 'NET evidence must remain in the budgeted task root'}
$netResourceGuard=Start-NetResourceGuard $budgetRoot "$runtime/support-committed-clone-r21-fresh-v1"
try {
New-Item -ItemType Directory -Force -Path $EvidenceDirectory,$env:S3_EVIDENCE_DIR,$env:S4_EVIDENCE_DIR | Out-Null
if($withdrawalPreflight){$withdrawalPreflight | ConvertTo-Json | Set-Content -LiteralPath "$EvidenceDirectory/withdrawal-preflight.json" -Encoding utf8}
$netResourceGuard.baseline | ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$EvidenceDirectory/resource-before.json" -Encoding utf8
Copy-Item -LiteralPath "$runtime/storage-bucket-claim.txt" -Destination "$EvidenceDirectory/storage-bucket-claim.txt"
$env:CS_ENHANCE_ACTOR_CONTEXT=$ContextPath;$env:CS_ENHANCE_ACTOR_CONTEXT_SHA256=(Get-FileHash -LiteralPath $ContextPath -Algorithm SHA256).Hash.ToLowerInvariant()
$env:SUPPORT_ANALYTICS_CAPTURE='';$env:CS_NET_CONTRIBUTION_COMMITTED_ENABLED='false';$env:CS_NET_CONTRIBUTION_READONLY_CAPTURE='true'
$mavenHome='D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9'
function Run-NetSuite([string]$Suite,[int]$Expected,[string]$Stage,[string]$Method='',[string[]]$ReportSuites=@($Suite)) {
    Measure-NetResourceGuard | Out-Null
    $testSelector=if($Method){"$Suite#$Method"}else{$Suite}
    $info=[Diagnostics.ProcessStartInfo]::new("$env:JAVA_HOME/bin/java.exe")
    $info.UseShellExecute=$false;$info.CreateNoWindow=$true;$info.RedirectStandardOutput=$true;$info.RedirectStandardError=$true;$info.WorkingDirectory=$repo
    $info.Environment['NEXION_LOG_FILE']='';$info.Environment['LOGGING_FILE_NAME']=''
    $boot=@(Get-ChildItem -LiteralPath "$mavenHome/boot" -Filter 'plexus-classworlds-*.jar' -File)
    if($boot.Count -ne 1){throw 'Installed Maven launcher unavailable'}
    foreach($argument in @('-classpath',$boot[0].FullName,"-Dclassworlds.conf=$mavenHome/bin/m2.conf","-Dmaven.home=$mavenHome","-Dmaven.multiModuleProjectDirectory=$repo",'org.codehaus.plexus.classworlds.launcher.Launcher','-o','-B','-Dstyle.color=never','-f',[IO.Path]::GetFullPath($TestPomPath),"-Dsupport.test.reportsDirectory=$EvidenceDirectory/$Stage-reports",'-DforkedProcessTimeoutInSeconds=180','-Dsurefire.timeout=180',"-Dtest=$testSelector",'surefire:test')){$info.ArgumentList.Add($argument)}
    $process=[Diagnostics.Process]::new();$process.StartInfo=$info
    $log=[IO.File]::Open("$EvidenceDirectory/$Stage.log",[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::Read)
    $record=@{stage=$Stage;testSelector=$testSelector;expectedTests=$Expected;startedAt=[DateTime]::UtcNow.ToString('o');deadlineSeconds=240;logLimitBytes=8MB;status='STARTING';testPom=@{path=[IO.Path]::GetFullPath($TestPomPath);sha256=(Get-FileHash -LiteralPath $TestPomPath -Algorithm SHA256).Hash.ToLowerInvariant()}}
    $clock=[Diagnostics.Stopwatch]::StartNew();$reason=$null;$nextResourceCheck=0
    try {
        if(!$process.Start()){throw 'Current Maven process failed to start'}
        $record.pid=$process.Id;$record.processStartedAt=$process.StartTime.ToUniversalTime().ToString('o');$record.status='RUNNING'
        $record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$EvidenceDirectory/$Stage-process.json" -Encoding utf8
        $streams=@($process.StandardOutput.BaseStream,$process.StandardError.BaseStream)
        $buffers=@([byte[]]::new(4096),[byte[]]::new(4096));$reads=@($streams[0].ReadAsync($buffers[0],0,4096),$streams[1].ReadAsync($buffers[1],0,4096))
        while($reads[0] -or $reads[1] -or !$process.HasExited) {
            if($clock.Elapsed.TotalSeconds -ge 240){$reason='TIMED_OUT';break}
            if($clock.Elapsed.TotalMilliseconds -ge $nextResourceCheck) {
                try {$record.resources=Measure-NetResourceGuard}
                catch {$reason='RESOURCE_LIMIT';$record.resourceError=$_.Exception.Message;throw}
                $nextResourceCheck=$clock.Elapsed.TotalMilliseconds+2000
            }
            for($i=0;$i -lt 2;$i++) {
                if(!$reads[$i] -or !$reads[$i].IsCompleted){continue}
                $count=$reads[$i].GetAwaiter().GetResult()
                if(!$count){$reads[$i]=$null;continue}
                $remaining=8MB-$log.Length;$log.Write($buffers[$i],0,[Math]::Min($count,$remaining))
                if($count -ge $remaining){$reason='LOG_LIMIT';break}
                $reads[$i]=$streams[$i].ReadAsync($buffers[$i],0,4096)
            }
            if($reason){break}
            [void]$process.WaitForExit(20)
        }
        if($reason){$record.status=$reason;throw "NET $Stage $reason; preserve database and evidence"}
        $record.status=if($process.ExitCode -eq 0){'EXITED'}else{'FAILED'};$record.exitCode=$process.ExitCode
        if($process.ExitCode -ne 0){throw "NET $Stage failed; preserve evidence"}
    } finally {
        try {
            if($record.pid -and !$process.HasExited){$record.status=if($reason){$reason}else{'INTERRUPTED'};$process.Kill($true);if(!$process.WaitForExit(5000)){throw 'Current Maven subtree did not stop'}}
        } catch {$record.status='STOP_FAILED';$record.stopError=$_.Exception.GetType().FullName}
        finally {
            $record.elapsedSeconds=$clock.Elapsed.TotalSeconds;$record.logBytes=$log.Length
            $log.Dispose();$process.Dispose()
            try {$record.resources=Measure-NetResourceGuard}
            catch {if($record.status -ne 'STOP_FAILED'){$record.status='RESOURCE_LIMIT'};$record.resourceError=$_.Exception.Message;throw}
            finally {$record | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$EvidenceDirectory/$Stage-process.json" -Encoding utf8}
        }
    }
    $actualTests=0
    foreach($reportSuite in $ReportSuites) {
        [xml]$xml=Get-Content -Raw -LiteralPath "$EvidenceDirectory/$Stage-reports/TEST-ffdd.opsconsole.content.application.$reportSuite.xml"
        if([int]$xml.testsuite.tests -lt 1 -or [int]$xml.testsuite.failures -ne 0 -or [int]$xml.testsuite.errors -ne 0 -or [int]$xml.testsuite.skipped -ne 0){throw "NET $Stage requires executed tests and zero failures/skips in $reportSuite"}
        $actualTests += [int]$xml.testsuite.tests
    }
    if($actualTests -ne $Expected){throw "NET $Stage must execute exactly $Expected tests"}
}
Run-NetSuite 'SupportNetContributionBeforeCaptureTest' 1 'before'
$env:CS_NET_CONTRIBUTION_READONLY_CAPTURE='false';$env:CS_NET_CONTRIBUTION_COMMITTED_ENABLED='true'
$env:CS_ENHANCE_ACTOR_CONTEXT="$EvidenceDirectory/actor-context.json";$env:CS_ENHANCE_ACTOR_CONTEXT_SHA256=(Get-FileHash -LiteralPath $env:CS_ENHANCE_ACTOR_CONTEXT -Algorithm SHA256).Hash.ToLowerInvariant()
if($Scenario -eq 'refund') {
    $netTests=1
    Run-NetSuite 'SupportRefundRetainedPreflightTest' 1 'retained-read-preflight' 'retainedRowsMatchOriginalReceiptBeforeSpringStartup'
    Run-NetSuite 'SupportNetContributionRefundRuntimeTest' $netTests 'committed' 'retainedRechargeFundedS1WalletRefundPreservesOriginalFirstAndNetThenReplays'
} elseif($Scenario -eq 'refund-readback-and-settlement') {
    $netTests=2
    Run-NetSuite 'SupportRefundCommittedReadbackTest#committedRefundHttpReadbackAndOriginalKeyReplay,SupportNetContributionSettlementRuntimeTest#principalWithdrawalReserveSubmittedTerminalRollbackAndReplayDeductActualGrossOnce' $netTests 'committed' '' @('SupportRefundCommittedReadbackTest','SupportNetContributionSettlementRuntimeTest')
} elseif($Scenario -eq 'earnings-and-retained-principal') {
    $netTests=2
    Run-NetSuite 'SupportNetContributionEarningsRuntimeTest' $netTests 'committed' 'retainedPrincipalContinuesAfterPolicyFailureWithoutAnotherAuthOrRecharge+actualTaskEarningsConsumptionRefundThenMixedWithdrawalRespectOriginalSources'
} elseif($Scenario -eq 'retained-principal') {
    $netTests=1
    Run-NetSuite 'SupportNetContributionEarningsRuntimeTest' $netTests 'committed' 'retainedPrincipalContinuesAfterPolicyFailureWithoutAnotherAuthOrRecharge'
} elseif($Scenario -eq 'settlement') {
    $netTests=1
    Run-NetSuite 'SupportNetContributionSettlementRuntimeTest' $netTests 'committed' 'principalWithdrawalReserveSubmittedTerminalRollbackAndReplayDeductActualGrossOnce'
} elseif($Scenario -eq 'access') {
    $netTests=2
    Run-NetSuite 'SupportAccessCommittedRuntimeTest' $netTests 'committed' 'actualAnalyticsScopesRejectForeignAndForgedIdentityAndKeepOwnerlessEmpty+oldTokenLosesCustomerReadImmediatelyAfterProductionTransferBeforeStaleVersion'
} else {
    $netTests=2
    Run-NetSuite 'SupportNetContributionCommittedRuntimeTest' $netTests 'committed'
}
@{scenario=$Scenario;runId=$flat.runId;snapshotHash=$flat.snapshotHash;contextSha256=$env:CS_ENHANCE_ACTOR_CONTEXT_SHA256;captureTests=1;netTests=$netTests;skipped=0;financialRecordsRetained=$true;phaseSigned=$false;schemaReaderClient=@{path=[IO.Path]::GetFullPath($SchemaReaderClient);sha256=(Get-FileHash -LiteralPath $SchemaReaderClient -Algorithm SHA256).Hash.ToLowerInvariant()}} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$EvidenceDirectory/net-result.json" -Encoding utf8
Measure-NetResourceGuard | Out-Null
} finally {Stop-NetResourceGuard}

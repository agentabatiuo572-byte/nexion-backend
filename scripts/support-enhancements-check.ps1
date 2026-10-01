$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
. "$PSScriptRoot/support-enhancements-core-env.ps1"
$suites = @(
    'SupportEnhancementPreparationTest','SupportEnhancementCoreRuntimeTest','SupportBindingRuntimeTest',
    'SupportS4RuntimeTest','SupportMaintenanceMySqlS4Test','SupportMessageReplayMySqlS4Test',
    'SupportHumanMessageServiceTest','OpsConversationServiceTest','OpsUser360ServiceTest',
    'OpsSupportAgentControllerTest','SupportWorkbenchProfileTest','SupportWorkbenchServiceTest',
    'SupportAttachmentServiceTest','SupportMaintenanceServiceTest','OpsAdminAccountServiceTest',
    'OpsAdminAccountControllerTest','OpsSupportAgentServiceTest','AppSupportServiceTest','OpsSupportTicketServiceTest',
    'C1AuditEvidenceMySqlTest','OpsSupportKnowledgeServiceTest','OpsSupportKnowledgeControllerTest',
    'OpsSessionTemplateServiceTest','AppNovaAiServiceTest','AppNovaAiPublishedFaqPathTest',
    'AppSupportControllerProductionPathContractTest','KycRemovalContractTest'
)
$started = [DateTime]::UtcNow
$log = Join-Path $env:CS_ENHANCE_EVIDENCE_DIR ('core-check-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
$reportDirectory = "$env:CS_ENHANCE_EVIDENCE_DIR/surefire-reports"
& 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9/bin/mvn.cmd' "-Dsupport.test.reportsDirectory=$reportDirectory" "-Dtest=$($suites -join ',')" test *> $log
if ($LASTEXITCODE -ne 0) { Write-Output "Core Maven check failed. Private log: $log"; exit 1 }
$finished = [DateTime]::UtcNow
$summary = @()
foreach ($suite in $suites) {
    $reports = @(Get-ChildItem -LiteralPath $reportDirectory -Filter "TEST-*.$suite.xml")
    if ($reports.Count -ne 1 -or $reports[0].LastWriteTimeUtc -lt $started) { throw "Missing or stale core report: $suite" }
    [xml]$xml = Get-Content -Raw -LiteralPath $reports[0].FullName
    $result = $xml.testsuite
    if ([int]$result.tests -le 0 -or [int]$result.skipped -ne 0 -or [int]$result.failures -ne 0 -or [int]$result.errors -ne 0) {
        throw "Core suite did not execute cleanly: $suite"
    }
    $summary += [ordered]@{suite=$suite;tests=[int]$result.tests;skipped=0;failures=0;errors=0;report=$reports[0].FullName.Replace('\','/');sha256=(Get-FileHash -LiteralPath $reports[0].FullName -Algorithm SHA256).Hash.ToLower();cases=@($result.testcase | ForEach-Object {$_.name})}
}
$sceneNames = @('random-runtime.json','random-partial-runtime.json','random-concurrency-runtime.json','binding-availability-runtime.json',
    'finance-profile-runtime.json','profile-device-runtime.json','conversation-runtime.json','avatar-runtime.json','sku-link-runtime.json',
    'account-replay-runtime.json','original-actions-runtime.json','publisher-runtime.json','presence-runtime.json','profile-errors-runtime.json',
    'legacy-s3/scenario-evidence.json','legacy-s3/ticket-private-evidence.json','legacy-s3/unanswered-evidence.json',
    'legacy-s4/scenario-evidence.json','legacy-s4/message-replay-mysql-evidence.json',
    'legacy-s4/maintenance-activity-stop-lock.json','legacy-s4/maintenance-coverage-fence.json',
    'legacy-s4/maintenance-send-transfer-lock.json','legacy-s4/maintenance-sequence.json','preparation-runtime.json')
$scenes = foreach ($name in $sceneNames) {
    $file = Get-Item -LiteralPath (Join-Path $env:CS_ENHANCE_EVIDENCE_DIR $name)
    if ($file.LastWriteTimeUtc -lt $started -or $file.LastWriteTimeUtc -gt $finished) { throw "Scenario outside current regression: $name" }
    $document = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json -DateKind String
    $stamp = if ($document.checkedAt) {$document.checkedAt} elseif ($document.at) {$document.at} else {$document.timestamp}
    if ($stamp -and ([DateTime]::Parse($stamp).ToUniversalTime() -lt $started -or [DateTime]::Parse($stamp).ToUniversalTime() -gt $finished)) { throw "Scenario timestamp outside current regression: $name" }
    if ($name -match '^.+-runtime\.json$' -and $name -ne 'preparation-runtime.json' -and $env:WORKFLOW_RUN_ID) {
        if ($document.workflowRunId -ne $env:WORKFLOW_RUN_ID -or $document.snapshotHash -ne $env:WORKFLOW_SNAPSHOT_HASH) { throw "Scenario workflow mismatch: $name" }
    }
    [ordered]@{name=$name;path=$file.FullName.Replace('\','/');sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLower();writtenAt=$file.LastWriteTimeUtc.ToString('o')}
}
[ordered]@{startedAt=$started.ToString('o');checkedAt=[DateTime]::UtcNow.ToString('o');database='cs_enhance_20261001';port=18141;
    taskId=$env:WORKFLOW_TASK_ID;stepId=$env:WORKFLOW_STEP_ID;checkId=$env:WORKFLOW_CHECK_ID;runId=$env:WORKFLOW_RUN_ID;repo=$env:WORKFLOW_REPO;snapshotHash=$env:WORKFLOW_SNAPSHOT_HASH;suites=$summary;scenes=@($scenes)} |
    ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$env:CS_ENHANCE_EVIDENCE_DIR/core-check-summary.json" -Encoding utf8
Write-Output "Core passed: $($summary.Count) current suites, no skipped tests."

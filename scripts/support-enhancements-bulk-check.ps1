$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
. "$PSScriptRoot/support-enhancements-core-env.ps1"
$env:CS_ENHANCE_BULK_ENABLED = 'true'
$suites = @('SupportHumanMessageServiceTest','OpsConversationServiceTest','SupportAttachmentServiceTest',
    'SupportMaintenanceServiceTest','SupportBulkRuntimeTest','SupportBulkRestartRuntimeTest')
$started = [DateTime]::UtcNow
$reportDirectory = "$env:CS_ENHANCE_EVIDENCE_DIR/surefire-reports"
$maven = 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9/bin/mvn.cmd'
$firstLog = "$env:CS_ENHANCE_EVIDENCE_DIR/bulk-check-$($started.ToString('yyyyMMdd-HHmmss')).log"
& $maven "-Dsupport.test.reportsDirectory=$reportDirectory" "-Dtest=$($suites[0..4] -join ',')" test *> $firstLog
if ($LASTEXITCODE -ne 0) { Write-Output "Bulk check failed. Private log: $firstLog"; exit 1 }
# A separate Maven/Surefire JVM must consume the first process's persisted queue.
$restartLog = "$env:CS_ENHANCE_EVIDENCE_DIR/bulk-restart-$($started.ToString('yyyyMMdd-HHmmss')).log"
& $maven "-Dsupport.test.reportsDirectory=$reportDirectory" '-Dtest=SupportBulkRestartRuntimeTest' test *> $restartLog
if ($LASTEXITCODE -ne 0) { Write-Output "Bulk restart check failed. Private log: $restartLog"; exit 1 }
$finished = [DateTime]::UtcNow
$summary = foreach ($suite in $suites) {
    $reports = @(Get-ChildItem -LiteralPath $reportDirectory -Filter "TEST-*.$suite.xml")
    if ($reports.Count -ne 1 -or $reports[0].LastWriteTimeUtc -lt $started) { throw "Missing or stale bulk report: $suite" }
    [xml]$xml = Get-Content -Raw -LiteralPath $reports[0].FullName
    $result = $xml.testsuite
    if ([int]$result.tests -le 0 -or [int]$result.skipped -ne 0 -or [int]$result.failures -ne 0 -or [int]$result.errors -ne 0) { throw "Bulk suite did not execute cleanly: $suite" }
    [ordered]@{suite=$suite;tests=[int]$result.tests;skipped=0;failures=0;errors=0;
        report=$reports[0].FullName.Replace('\','/');sha256=(Get-FileHash -LiteralPath $reports[0].FullName -Algorithm SHA256).Hash.ToLower();
        cases=@($result.testcase | ForEach-Object {$_.name})}
}
$scenes = foreach ($name in @('bulk-runtime.json','bulk-restart-runtime.json','bulk-restart-seed.json')) {
    $file = Get-Item -LiteralPath (Join-Path $env:CS_ENHANCE_EVIDENCE_DIR $name)
    if ($file.LastWriteTimeUtc -lt $started -or $file.LastWriteTimeUtc -gt $finished) { throw "Bulk scenario outside current regression: $name" }
    $document = Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json -DateKind String
    $stamp = [DateTime]::Parse($document.checkedAt).ToUniversalTime()
    if ($stamp -lt $started -or $stamp -gt $finished -or $document.database -ne 'cs_enhance_20261001') { throw "Bulk scenario boundary mismatch: $name" }
    if ($env:WORKFLOW_RUN_ID -and ($document.workflowRunId -ne $env:WORKFLOW_RUN_ID -or $document.snapshotHash -ne $env:WORKFLOW_SNAPSHOT_HASH)) { throw "Bulk scenario workflow mismatch: $name" }
    [ordered]@{name=$name;path=$file.FullName.Replace('\','/');sha256=(Get-FileHash -LiteralPath $file.FullName -Algorithm SHA256).Hash.ToLower();writtenAt=$file.LastWriteTimeUtc.ToString('o')}
}
[ordered]@{startedAt=$started.ToString('o');checkedAt=$finished.ToString('o');database='cs_enhance_20261001';port=18141;
    taskId=$env:WORKFLOW_TASK_ID;stepId=$env:WORKFLOW_STEP_ID;checkId=$env:WORKFLOW_CHECK_ID;runId=$env:WORKFLOW_RUN_ID;
    repo=$env:WORKFLOW_REPO;snapshotHash=$env:WORKFLOW_SNAPSHOT_HASH;suites=@($summary);scenes=@($scenes)} |
    ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$env:CS_ENHANCE_EVIDENCE_DIR/bulk-check-summary.json" -Encoding utf8
Write-Output "Bulk passed: $($summary.Count) current suites, separate process restart, no skipped tests."

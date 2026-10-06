param([ValidateSet('avatar','core','bulk')][string]$Phase = 'avatar')
$ErrorActionPreference = 'Stop'
Set-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
if ($env:WORKFLOW_RUN_ID -notmatch '^[a-zA-Z0-9_-]+$' -or $env:WORKFLOW_STEP_ID -notin @('avatar','integration')) { throw 'Current workflow identity required' }
$env:CS_ENHANCE_OUTPUT_ROOT = "C:/Users/jason/.codex/workflow-runs/customer-service-enhancements-20261001/backend-avatar-read/runs/$env:WORKFLOW_RUN_ID/$env:WORKFLOW_STEP_ID/$Phase"
if ($Phase -eq 'core') { & "$PSScriptRoot/support-enhancements-check.ps1"; exit $LASTEXITCODE }
if ($Phase -eq 'bulk') { & "$PSScriptRoot/support-enhancements-bulk-check.ps1"; exit $LASTEXITCODE }
. "$PSScriptRoot/support-enhancements-core-env.ps1"
$env:CS_ENHANCE_BULK_ENABLED = 'true'
$env:CS_ENHANCE_AVATAR_READ_ENABLED = 'true'
$started = [DateTime]::UtcNow
$reportDirectory = "$env:CS_ENHANCE_EVIDENCE_DIR/surefire-reports"
$log = "$env:CS_ENHANCE_EVIDENCE_DIR/avatar-check-$($started.ToString('yyyyMMdd-HHmmss')).log"
& 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9/bin/mvn.cmd' "-Dsupport.test.reportsDirectory=$reportDirectory" '-Dtest=SupportAdminAvatarReadRuntimeTest,SupportAvatarCompensationRuntimeTest' test *> $log
if ($LASTEXITCODE -ne 0) { Write-Output "Avatar read check failed. Private log: $log"; exit 1 }
$finished = [DateTime]::UtcNow
$suites = foreach ($name in @('SupportAdminAvatarReadRuntimeTest','SupportAvatarCompensationRuntimeTest')) {
    $reports = @(Get-ChildItem -LiteralPath $reportDirectory -Filter "TEST-*.$name.xml")
    if ($reports.Count -ne 1 -or $reports[0].LastWriteTimeUtc -lt $started) { throw "Missing current avatar suite: $name" }
    [xml]$xml = Get-Content -Raw -LiteralPath $reports[0].FullName
    $suite = $xml.testsuite
    if ([int]$suite.tests -le 0 -or [int]$suite.skipped -ne 0 -or [int]$suite.failures -ne 0 -or [int]$suite.errors -ne 0) { throw "Avatar test did not execute cleanly: $name" }
    [ordered]@{suite=$name;tests=[int]$suite.tests;skipped=0;failures=0;errors=0;
        report=$reports[0].FullName.Replace('\','/');sha256=(Get-FileHash $reports[0].FullName -Algorithm SHA256).Hash.ToLower();cases=@($suite.testcase | ForEach-Object {$_.name})}
}
$scenes = foreach ($name in @('avatar-read-runtime.json','avatar-compensation-runtime.json')) {
    $scene = Get-Item -LiteralPath "$env:CS_ENHANCE_EVIDENCE_DIR/$name"
    if ($scene.LastWriteTimeUtc -lt $started -or $scene.LastWriteTimeUtc -gt $finished) { throw "Missing current avatar runtime proof: $name" }
    [ordered]@{name=$scene.Name;path=$scene.FullName.Replace('\','/');sha256=(Get-FileHash $scene.FullName -Algorithm SHA256).Hash.ToLower();writtenAt=$scene.LastWriteTimeUtc.ToString('o')}
}
[ordered]@{startedAt=$started.ToString('o');checkedAt=$finished.ToString('o');database='cs_enhance_20261001';port=18141;
    taskId=$env:WORKFLOW_TASK_ID;stepId=$env:WORKFLOW_STEP_ID;checkId=$env:WORKFLOW_CHECK_ID;runId=$env:WORKFLOW_RUN_ID;
    repo=$env:WORKFLOW_REPO;snapshotHash=$env:WORKFLOW_SNAPSHOT_HASH;
    suites=@($suites);scenes=@($scenes)} |
    ConvertTo-Json -Depth 8 | Set-Content -LiteralPath "$env:CS_ENHANCE_EVIDENCE_DIR/avatar-check-summary.json" -Encoding utf8
Write-Output "Avatar passed: two real runtime suites, no skipped tests."

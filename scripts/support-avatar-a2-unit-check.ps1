param(
    [Parameter(Mandatory=$true)][string]$EvidenceRoot,
    [string]$Maven = 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9/bin/mvn.cmd',
    [string]$JdkHome = 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/jdk-17.0.20.1+1'
)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
$env:JAVA_HOME = $JdkHome
# This selector runs pure tests only; it does not load shared DB credentials or start a server.
$suites = @('OpsAdminAccountServiceTest','OpsAuditCenterServiceTest','AuditReplayDispatcherTest',
    'AuditReplayBusinessPermissionGuardTest','A2AccessPolicyTest','A2RuntimePolicyTest','SupportAttachmentServiceTest')
$run = Join-Path $EvidenceRoot ([Guid]::NewGuid().ToString())
$reports = Join-Path $run 'xml'
New-Item -ItemType Directory -Path $reports -Force | Out-Null
$started = [DateTime]::UtcNow
$log = Join-Path $run 'maven.log'
& $Maven '-B' '-Dstyle.color=never' "-Dsupport.test.reportsDirectory=$reports" "-Dtest=$($suites -join ',')" test *> $log
$mavenExit = $LASTEXITCODE
$summary = foreach ($suite in $suites) {
    $files = @(Get-ChildItem -LiteralPath $reports -Filter "TEST-*.$suite.xml")
    if ($files.Count -ne 1 -or $files[0].LastWriteTimeUtc -lt $started) {
        [ordered]@{suite=$suite;tests=0;failures=0;errors=1;skipped=0;report=$null;reason='Missing current XML';cases=@()}
        continue
    }
    [xml]$xml = Get-Content -Raw -LiteralPath $files[0].FullName
    $r = $xml.testsuite
    [ordered]@{suite=$suite;tests=[int]$r.tests;failures=[int]$r.failures;errors=[int]$r.errors;skipped=[int]$r.skipped;
        report=$files[0].FullName;sha256=(Get-FileHash -LiteralPath $files[0].FullName -Algorithm SHA256).Hash.ToLower();
        cases=@($r.testcase | ForEach-Object {$_.name})}
}
$receipt = [ordered]@{checkedAt=[DateTime]::UtcNow.ToString('o');scope='pure service tests and test compilation; no HTTP/DB/browser acceptance';
    runId=$env:WORKFLOW_RUN_ID;snapshotHash=$env:WORKFLOW_SNAPSHOT_HASH;log=$log;mavenExit=$mavenExit;suites=@($summary)}
$receipt | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $run 'summary.json') -Encoding utf8
$receipt | ConvertTo-Json -Depth 7 | Set-Content -LiteralPath (Join-Path $EvidenceRoot 'latest-unit.json') -Encoding utf8
if ($mavenExit -ne 0 -or @($summary | Where-Object {$_.tests -le 0 -or $_.failures -ne 0 -or $_.errors -ne 0 -or $_.skipped -ne 0}).Count -gt 0) {
    Write-Output "Pure service tests failed; inspect $log"
    exit 1
}
$total = ($summary | ForEach-Object {$_.tests} | Measure-Object -Sum).Sum
Write-Output "Pure tests passed: $($summary.Count) suites, $total tests; zero failures/errors/skips. HTTP/DB/browser unverified."

param([switch]$StatusOnly)
$ErrorActionPreference='Stop'
$wrapper=Join-Path (Split-Path -Parent $PSScriptRoot) 'support-net-minimal-runtime.ps1'
$tokens=$null;$parseErrors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($wrapper,[ref]$tokens,[ref]$parseErrors)
if($parseErrors.Count){throw 'NET wrapper must parse before budget regression'}
$normalizer=$ast.Find({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -ceq '$EvidenceDirectory'},$true)
$evidenceEnvironment=$ast.Find({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -ceq '$env:CS_ENHANCE_EVIDENCE_DIR'},$true)
if(!$evidenceEnvironment){throw 'Production evidence environment assignment missing'}
$priorEvidenceEnvironment=$env:CS_ENHANCE_EVIDENCE_DIR
$pathResults=@()
try {
    foreach($EvidenceDirectory in @('D:\CodexData\test-environments\workflow-runs\owned\run','D:/CodexData/test-environments/workflow-runs/owned/./run/','D:/CodexData/test-environments/workflow-runs/owned/run')) {
        if($normalizer){. ([scriptblock]::Create($normalizer.Extent.Text))}
        . ([scriptblock]::Create($evidenceEnvironment.Extent.Text))
        if($env:CS_ENHANCE_EVIDENCE_DIR -cne 'D:/CodexData/test-environments/workflow-runs/owned/run'){throw 'Equivalent evidence paths must match the fixed forward-slash material reference'}
        $pathResults+=@{passed=$true;path=$env:CS_ENHANCE_EVIDENCE_DIR}
    }
} finally {$env:CS_ENHANCE_EVIDENCE_DIR=$priorEvidenceEnvironment}
$selector=$ast.Find({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -ceq '$testSelector'},$true)
if(!$selector){throw 'Production test selector missing'}
foreach($Method in @('','retainedRechargeFundedS1WalletRefundPreservesOriginalFirstAndNetThenReplays','actualAnalyticsScopesRejectForeignAndForgedIdentityAndKeepOwnerlessEmpty+oldTokenLosesCustomerReadImmediatelyAfterProductionTransferBeforeStaleVersion')) {
    $Suite='SupportNetContributionRefundRuntimeTest'
    . ([scriptblock]::Create($selector.Extent.Text))
    $expected=$Suite+$(if($Method){'#'+$Method}else{''})
    if($testSelector -cne $expected){throw 'Selected refund method must not rerun inherited money cases'}
}
$budgetCatches=$ast.FindAll({param($node) $node -is [Management.Automation.Language.CatchClauseAst] -and $node.Extent.Text.Contains("`$record.status='RESOURCE_LIMIT'")},$true)
if($budgetCatches.Count -ne 1){throw 'Exactly one final budget failure branch required'}
$body=$budgetCatches[0].Body.Extent.Text
$failureBranch=[scriptblock]::Create($body.Substring(1,$body.Length-2))
$statusResults=@()
foreach($originalStatus in @('STOP_FAILED','EXITED')) {
    $record=@{status=$originalStatus;stopError='injected stop failure'};$raised=$false
    try {throw 'injected resource limit'} catch {try {& $failureBranch} catch {$raised=$true}}
    $expected=if($originalStatus -eq 'STOP_FAILED'){'STOP_FAILED'}else{'RESOURCE_LIMIT'}
    if(!$raised -or $record.status -ne $expected -or $record.resourceError -ne 'injected resource limit' -or $record.stopError -ne 'injected stop failure'){throw 'Final budget failure must preserve stop failure and still throw'}
    $statusResults+=@{original=$originalStatus;actual=$record.status;resourceError=$record.resourceError;threw=$raised;passed=$true}
}
$gate=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Assert-NetWithdrawalReady'},$true)
if(!$gate){throw 'Withdrawal readiness must be checked before Spring startup'}
. ([scriptblock]::Create($gate.Extent.Text))
$gateResults=@()
foreach($case in @(@{canonical=$null;legacy=$null;allowed=$false},@{canonical='disabled';legacy='enabled';allowed=$false},@{canonical=' enabled ';legacy=$null;allowed=$true},@{canonical=$null;legacy='on';allowed=$true},@{canonical='enabled';legacy=$null;allowed=$false;port=3306})) {
    $proof=@{databaseName='cs_analytics_20261007';port=33338;serverUuid='abe359c0-c621-11f1-97ea-a40c6626953d';currentUser='support_net_r55_app@127.0.0.1';canonical=$case.canonical;legacy=$case.legacy}
    if($case.ContainsKey('port')){$proof.port=$case.port}
    $accepted=$false;try{Assert-NetWithdrawalReady ($proof|ConvertTo-Json) | Out-Null;$accepted=$true}catch{}
    if($accepted -ne $case.allowed){throw 'Withdrawal readiness must preserve canonical fail-closed behavior and clone identity'}
    $gateResults+=@{passed=$true;allowed=$accepted}
}
if($StatusOnly){@{passed=$true;databaseStarted=$false;jvmStarted=$false;processStarted=$false;statusResults=$statusResults;pathResults=$pathResults;gateResults=$gateResults} | ConvertTo-Json -Depth 4;return}
$functions=$ast.FindAll({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst]},$true)
foreach($function in $functions) {
    $definition=$function.Extent.Text
    if($function.Name -eq 'Start-NetResourceGuard') {
        if(!$definition.Contains('maxGrowthBytes:256*1024**2')){throw 'Production growth budget changed'}
        $definition=$definition.Replace('maxGrowthBytes:256*1024**2','maxGrowthBytes:8*1024')
    }
    if($function.Name -eq 'Run-NetSuite') {
        $launcher='[Diagnostics.ProcessStartInfo]::new("$env:JAVA_HOME/bin/java.exe")'
        if(!$definition.Contains($launcher)){throw 'Expected owned process launcher missing'}
        $definition=$definition.Replace($launcher,'[Diagnostics.ProcessStartInfo]::new($fakeExecutable)')
        $arguments="(?m)^    foreach\(\`$argument in @\('-classpath'[^\r\n]+$"
        if([regex]::Matches($definition,$arguments).Count -ne 1){throw 'Expected Maven argument line missing'}
        $fakeArguments='    foreach($argument in @(''-NoProfile'',''-File'',$fakeScript,''-EvidenceDirectory'',$EvidenceDirectory,''-Stage'',$Stage,''-Suite'',$Suite,''-Mode'',$testMode)){$info.ArgumentList.Add($argument)}'
        $definition=[regex]::Replace($definition,$arguments,[Text.RegularExpressions.MatchEvaluator]{param($match) $fakeArguments})
    }
    Invoke-Expression $definition
}
$repo=Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$mavenHome='D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9'
$fakeExecutable=(Get-Process -Id $PID).Path
$testRoot=Join-Path 'D:/WORKS/PLAN/output' ('net-budget-regression-'+[Guid]::NewGuid().ToString('N'))
[void][IO.Directory]::CreateDirectory($testRoot)
$fakeScript=Join-Path $testRoot 'fake-test.ps1'
$TestPomPath=Join-Path $testRoot 'unused-pom.xml'
[IO.File]::WriteAllText($TestPomPath,'<project/>')
[IO.File]::WriteAllText($fakeScript,@'
param($EvidenceDirectory,$Stage,$Suite,$Mode)
$ErrorActionPreference='Stop'
$bytes=if($Mode -eq 'overflow'){16384}elseif($Mode -eq 'cumulative'){4096}else{64}
[IO.File]::WriteAllBytes("$EvidenceDirectory/$Stage-artifact.bin",[byte[]]::new($bytes))
if($Mode -eq 'overflow'){Start-Sleep -Seconds 30}
[void][IO.Directory]::CreateDirectory("$EvidenceDirectory/$Stage-reports")
[IO.File]::WriteAllText("$EvidenceDirectory/$Stage-reports/TEST-ffdd.opsconsole.content.application.$Suite.xml",'<testsuite tests="1" failures="0" errors="0" skipped="0"/>')
'@)
$results=@()
foreach($testMode in @('normal','overflow','cumulative')) {
    $EvidenceDirectory=Join-Path $testRoot "$testMode/evidence"
    $cloneRoot=Join-Path $testRoot "$testMode/clone"
    [void][IO.Directory]::CreateDirectory($EvidenceDirectory);[void][IO.Directory]::CreateDirectory($cloneRoot)
    $netResourceGuard=Start-NetResourceGuard $EvidenceDirectory $cloneRoot
    $watch=[Diagnostics.Stopwatch]::StartNew();$failure=$null
    try {
        if($testMode -eq 'cumulative'){Run-NetSuite 'FakeBefore' 1 'before'}
        try {
            Run-NetSuite 'FakeNet' 1 'committed'
            if($testMode -eq 'normal'){Run-NetSuite 'FakeAfter' 1 'after'}
        } catch {$failure=$_.Exception.Message}
        if($testMode -eq 'normal') {
            if($failure){throw $failure}
        } else {
            if(!$failure -or $failure -notmatch 'artifact growth exceeds'){throw 'Actual growth guard must reject the test process'}
            $record=Get-Content -Raw -LiteralPath "$EvidenceDirectory/committed-process.json" | ConvertFrom-Json
            if($record.status -ne 'RESOURCE_LIMIT' -or (Get-Process -Id $record.pid -ErrorAction SilentlyContinue)){throw 'Overflow must leave its owned test process stopped'}
            if($testMode -eq 'overflow' -and $watch.Elapsed.TotalSeconds -ge 15){throw 'Overflow was not stopped by the running monitor'}
        }
        $results+=@{case=$testMode;passed=$true;seconds=$watch.Elapsed.TotalSeconds}
    } finally {Stop-NetResourceGuard}
}
@{passed=$true;databaseStarted=$false;jvmStarted=$false;evidence=$testRoot;results=$results;statusResults=$statusResults} | ConvertTo-Json -Depth 4

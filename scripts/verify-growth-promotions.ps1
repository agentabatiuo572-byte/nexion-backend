param(
    [ValidateSet('Behavior')][string]$Suite = 'Behavior',
    [switch]$Runtime
)
$ErrorActionPreference = 'Stop'
$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$javaRoot = 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/jdk-17.0.20.1+1'
$mavenPath = 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9/bin/mvn.cmd'
if (-not (Test-Path -LiteralPath (Join-Path $javaRoot 'bin/java.exe'))) { throw 'Java 17 runtime missing' }
if (-not (Test-Path -LiteralPath $mavenPath)) { throw 'Maven runtime missing' }
$priorJava = $env:JAVA_HOME
$priorRuntime = $env:GROWTH_PROMOTION_RUNTIME
$priorRecoveryRuntime = $env:EARNINGS_SOURCE_RECOVERY_RUNTIME
Push-Location $repoRoot
try {
    $env:JAVA_HOME = $javaRoot
    if ($Runtime) {
        $connectionFile = 'D:/CodexData/test-environments/workflow-runs/growth-promotions-20261007/mysql/client.private.ini'
        if (-not (Test-Path -LiteralPath $connectionFile)) { throw 'Isolated runtime connection file missing' }
        $env:GROWTH_PROMOTION_RUNTIME = '1'
        $env:EARNINGS_SOURCE_RECOVERY_RUNTIME = '1'
    } else {
        $env:GROWTH_PROMOTION_RUNTIME = '0'
        $env:EARNINGS_SOURCE_RECOVERY_RUNTIME = '0'
    }
    & node 'scripts/check-growth-promotions-contract.mjs'
    if ($LASTEXITCODE -ne 0) { throw 'Promotion contract verification failed' }
    $testNames = 'Promotion*Test,AppCanonicalBoundaryServiceTest,AppBundleOrderServiceTest,AppOrderCommandServiceTest,AppWalletBillsServiceTest,AppAcceptanceSandboxStartupMigrationContractTest,OpsDeviceServiceTest,OpsGrowthServiceTest,AppTradeinEligibilityMapperSqlContractTest,OutboxRecordOnlyRetirementTest,EarningsSourceRecoveryServiceTest,EarningsReleaseServiceTest,EarningsWithdrawalLockContractTest,AdminIdempotencyServiceTest,AdminRbacBaselineInitializerTest,AdminRbacAuthorizationFilterTest'
    if ($Runtime) { $testNames += ',EarningsSourceRecoveryMySqlTest' }
    else { $testNames += ',!Promotion*MySqlTest' }
    & $mavenPath '-q' "-Dtest=$testNames" 'test'
    if ($LASTEXITCODE -ne 0) { throw 'Promotion Java verification failed' }
    if ($Runtime) {
        Write-Output 'Promotion contract and Java checks passed, including enabled isolated database probes. Full API and business acceptance remains a separate gate.'
    } else {
        Write-Output 'Promotion contract and Java checks passed. Isolated database probes were not requested; this is not runtime acceptance.'
    }
} finally {
    Pop-Location
    $env:JAVA_HOME = $priorJava
    $env:GROWTH_PROMOTION_RUNTIME = $priorRuntime
    $env:EARNINGS_SOURCE_RECOVERY_RUNTIME = $priorRecoveryRuntime
}

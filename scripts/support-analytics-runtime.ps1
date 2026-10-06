[CmdletBinding()]
param([ValidateSet('I0-B')][string]$Phase, [Parameter(Mandatory)][ValidateSet('core','bulk','avatar')][string]$SuiteGroup,
    [Parameter(Mandatory)][string]$Report)
$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
$root = 'C:/Users/jason/.codex/workflow-runs/support-analytics-20261007-r2'
$runtime = "$root/runtime"
foreach ($name in @('TASK_ID','STEP_ID','CHECK_ID','RUN_ID','REPO','SNAPSHOT_HASH')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("WORKFLOW_$name"))) { throw "Missing current workflow identity: $name" }
}
if ($env:WORKFLOW_STEP_ID -ne $Phase -or $env:WORKFLOW_RUN_ID -notmatch '^[a-zA-Z0-9_-]+$' -or $env:WORKFLOW_SNAPSHOT_HASH -notmatch '^[a-f0-9]{64}$') { throw 'Current I0-B workflow binding required' }
if ([IO.Path]::GetFullPath($env:WORKFLOW_REPO) -ne [IO.Path]::GetFullPath($repo)) { throw 'Workflow repository mismatch' }
function Hash([string]$Path) { (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant() }
function Read-Private([string]$Name) { Get-Content -Raw -LiteralPath "$runtime/$Name" | ConvertFrom-Json }
function Reference([string]$Path) { @{path=[IO.Path]::GetFullPath($Path);sha256=(Hash $Path)} }
function Inventory([string]$File, [string]$Variable) {
    $tokens=$null; $errors=$null
    $ast=[Management.Automation.Language.Parser]::ParseFile($File,[ref]$tokens,[ref]$errors)
    if ($errors.Count) { throw 'Original runtime inventory could not be parsed' }
    $matches=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and $node.Left.VariablePath.UserPath -eq $Variable},$false))
    if ($matches.Count -ne 1) { throw "Original inventory is ambiguous: $Variable" }
    $right=$matches[0].Right
    if (@($right.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst] -or $node -is [Management.Automation.Language.VariableExpressionAst]},$true)).Count) { throw 'Original inventory must contain only literal names' }
    @($right.FindAll({param($node) $node -is [Management.Automation.Language.StringConstantExpressionAst]},$true) | ForEach-Object Value)
}
$local=Read-Private 'local-runtime-private.json'; $storage=Read-Private 'local-storage-private.json'; $encryption=Read-Private 'local-encryption-private.json'
if ($local.host -ne '127.0.0.1' -or $local.port -ne 33337 -or $local.database -ne 'cs_analytics_20261007' -or $local.username -ne 'cs_analytics_runner' -or $local.redisPort -ne 16343 -or $storage.endpoint -ne 'http://127.0.0.1:19043' -or $storage.bucket -ne 'cs-analytics-20261007-private') { throw 'Exclusive analytics resource mismatch' }
$evidence="$root/runs/$($env:WORKFLOW_RUN_ID)/$Phase/$SuiteGroup"
if (Test-Path -LiteralPath $evidence) { throw 'A fresh runtime evidence directory is required' }
New-Item -ItemType Directory -Path $evidence | Out-Null
$env:SUPPORT_RUNTIME_TARGET='analytics-20261007'
$env:JAVA_HOME='D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/jdk-17.0.20.1+1'
$env:NEXION_DB_URL='jdbc:mysql://127.0.0.1:33337/cs_analytics_20261007?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:NEXION_DB_USERNAME=$local.username; $env:NEXION_DB_PASSWORD=$local.password
$env:NEXION_REDIS_HOST='127.0.0.1'; $env:NEXION_REDIS_PORT='16343'; $env:NEXION_REDIS_PASSWORD=$local.redisPassword
$env:SPRING_DATA_REDIS_DATABASE='0'; $env:NEXION_JWT_SECRET=$local.jwtSecret
$env:NEXION_FINANCE_DATA_KEY=$encryption.financeKey; $env:NEXION_ADMIN_MFA_ENCRYPTION_KEY=$encryption.adminMfaEncryptionKey
$env:NEXION_MINIO_ENDPOINT=$storage.endpoint; $env:NEXION_MINIO_BUCKET=$storage.bucket
$env:NEXION_MINIO_ACCESS_KEY=$storage.accessKey; $env:NEXION_MINIO_SECRET_KEY=$storage.secretKey
$env:SERVER_ADDRESS='127.0.0.1'; $env:SERVER_PORT='18161'; $env:S4_HTTP_PORT='18161'; $env:SPRING_PROFILES_ACTIVE='dev'
$env:LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_BOOT_AUTOCONFIGURE_SECURITY_SERVLET_USERDETAILSSERVICEAUTOCONFIGURATION='ERROR'
foreach ($name in @('NEXION_TREASURY_DEVELOPMENTRESERVE_ENABLED','NEXION_HOME_DEVELOPMENTSETTLEMENT_ENABLED','NEXGRID_SMS_ITNIO_ENABLED')) { [Environment]::SetEnvironmentVariable($name,'false') }
$env:NEXION_CREGIS_MODE='DISABLED'; $env:NEXION_HDPAY_MODE='DISABLED'
$env:NEXION_SUPPORT_ATTACHMENTS_ALLOWEDMIMETYPES='image/png,image/jpeg'; $env:NEXION_SUPPORT_ATTACHMENTS_MAXBYTES='1048576'
$env:NEXION_SUPPORT_ATTACHMENTS_MAXPIXELS='1000000'; $env:NEXION_SUPPORT_ATTACHMENTS_TTLSECONDS='300'
$env:CS_ENHANCE_EVIDENCE_DIR=$evidence; $env:NEXION_LOG_FILE="$evidence/backend.log"
$env:SUPPORT_RESOURCE_OWNERSHIP="$runtime/resource-ownership.json"
$env:SUPPORT_STORAGE_BASELINE_MANIFEST="$runtime/storage-baseline-objects.json"
$env:SUPPORT_CANDIDATE=(& git rev-parse HEAD).Trim()
$initialTree=(& git diff HEAD --binary | Out-String)
$initialStatus=(& git status --porcelain=v1 | Out-String)
$env:CS_ENHANCE_PREP_ENABLED='true'; $env:CS_ENHANCE_CORE_ENABLED='true'; $env:NEXION_C1_AUDIT_MYSQL='true'
$env:SUPPORT_PATCH_ISOLATED='true'; $env:CS_ENHANCE_BULK_ENABLED='true'; $env:CS_ENHANCE_AVATAR_READ_ENABLED='true'; $env:CS_ENHANCE_AVATAR_A2_ENABLED='true'
$env:S3_EVIDENCE_DIR="$evidence/legacy-s3"; $env:S4_EVIDENCE_DIR="$evidence/legacy-s4"
$env:S3_FIXTURE_PASSWORD='Aa1!'+[Guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Path $env:S3_EVIDENCE_DIR,$env:S4_EVIDENCE_DIR | Out-Null
Copy-Item -LiteralPath "$runtime/storage-bucket-claim.txt" -Destination "$evidence/storage-bucket-claim.txt"
$maven='D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9/bin/mvn.cmd'
$suiteEvidence=[Collections.Generic.List[object]]::new()
function Run-Suites([string]$Group,[string[]]$Suites) {
    $started=[DateTime]::UtcNow; $directory="$evidence/$Group-reports"; $log="$evidence/$Group.log"
    & $maven '-B' '-Dstyle.color=never' "-Dsupport.test.reportsDirectory=$directory" "-Dtest=$($Suites -join ',')" test *> $log
    if ($LASTEXITCODE -ne 0) { throw "Runtime group $Group failed; private log: $log" }
    foreach ($suite in $Suites) {
        $files=@(Get-ChildItem -LiteralPath $directory -Filter "TEST-*.$suite.xml" -File)
        if ($files.Count -ne 1 -or $files[0].LastWriteTimeUtc -lt $started) { throw "Current report missing: $suite" }
        [xml]$xml=Get-Content -Raw -LiteralPath $files[0].FullName; $result=$xml.testsuite
        if ([int]$result.tests -le 0 -or [int]$result.failures -ne 0 -or [int]$result.errors -ne 0 -or [int]$result.skipped -ne 0 -or @($result.testcase).Count -ne [int]$result.tests) { throw "Suite incomplete: $suite" }
        $suiteEvidence.Add(@{group=$Group;suite=$suite;tests=[int]$result.tests;failures=0;errors=0;skipped=0;report=(Reference $files[0].FullName)})
    }
    Write-Output "group=$Group status=pass suites=$($Suites.Count)"
}
$started=[DateTime]::UtcNow
$env:SUPPORT_ANALYTICS_CAPTURE='before'
Run-Suites 'before' @('SupportAnalyticsCaptureTest')
$env:SUPPORT_ANALYTICS_CAPTURE=''
$env:CS_ENHANCE_ACTOR_CONTEXT="$evidence/actor-context.json"
$env:CS_ENHANCE_ACTOR_CONTEXT_SHA256=Hash $env:CS_ENHANCE_ACTOR_CONTEXT
$core=@(Inventory "$PSScriptRoot/support-enhancements-check.ps1" 'suites')
$scenes=@(Inventory "$PSScriptRoot/support-enhancements-check.ps1" 'sceneNames')
if ($core.Count -ne 27 -or $scenes.Count -ne 24) { throw 'Complete original core inventory changed' }
try {
    switch ($SuiteGroup) {
        core { Run-Suites 'core' $core }
        bulk {
            Run-Suites 'bulk' @('SupportHumanMessageServiceTest','OpsConversationServiceTest','SupportAttachmentServiceTest','SupportMaintenanceServiceTest','SupportBulkRuntimeTest')
            # The persisted queue is consumed in a genuinely different Surefire JVM.
            Run-Suites 'bulk-restart' @('SupportBulkRestartRuntimeTest')
        }
        avatar { Run-Suites 'avatar' @('SupportAdminAvatarReadRuntimeTest','SupportAvatarCompensationRuntimeTest','SupportAdminAvatarA2RuntimeTest') }
    }
} finally {
    $env:SUPPORT_ANALYTICS_CAPTURE='after'
    Run-Suites 'after' @('SupportAnalyticsCaptureTest')
    $env:SUPPORT_ANALYTICS_CAPTURE=''
}
if ($SuiteGroup -eq 'bulk') { $scenes=@('bulk-runtime.json','bulk-restart-runtime.json','bulk-restart-seed.json') }
if ($SuiteGroup -eq 'avatar') {
    $scenes=@('avatar-read-runtime.json','avatar-compensation-runtime.json')
    $a2=@(Get-ChildItem -LiteralPath $evidence -Filter 'avatar-a2-runtime-*.json' -File)
    if ($a2.Count -ne 1) { throw 'Exactly one current A2 runtime scene is required' }
    $scenes+=$a2[0].Name
}
$sceneEvidence=foreach($name in $scenes) {
    $file=Get-Item -LiteralPath "$evidence/$name"
    if ($file.LastWriteTimeUtc -lt $started -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Stale or indirect runtime scene: $name" }
    $document=Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json -DateKind String
    if ($name -notlike 'legacy-*/*' -and $name -ne 'preparation-runtime.json') {
        if ($document.workflowRunId -cne $env:WORKFLOW_RUN_ID -or $document.snapshotHash -cne $env:WORKFLOW_SNAPSHOT_HASH) { throw "Scene workflow mismatch: $name" }
    }
    if ($name -eq 'preparation-runtime.json') {
        if ($document.database -cne 'cs_analytics_20261007' -or $document.httpPort -ne 18161 -or $document.redisPort -ne 16343 -or $document.bucket -cne 'cs-analytics-20261007-private' -or $document.copiedObjects -ne 0 -or $document.triggerWritesRolledBack -ne 3) { throw 'Preparation resource boundary mismatch' }
        foreach ($field in @('sqlReadback','sourceDatabaseDenied','redisReadback','storageReadback','anonymousStorageDenied','baselineAttachmentReadback','httpHealth','anonymousAdminDenied')) {
            if ($document.$field -isnot [bool] -or !$document.$field) { throw "Preparation readback missing: $field" }
        }
        if ($document.scheduledJobs -ne $false -or $document.productImplementationReleased -ne $false) { throw 'Preparation isolation mismatch' }
    }
    Reference $file.FullName
}
$restored=Get-Content -Raw -LiteralPath "$evidence/shared-restoration.json" | ConvertFrom-Json
if ($restored.complete -ne $true -or $restored.rulesBusinessValuesEqual -ne $true -or $restored.contextSha256 -cne $env:CS_ENHANCE_ACTOR_CONTEXT_SHA256) { throw 'Exact shared restoration was not verified' }
$sceneEvidence+=Reference "$evidence/shared-restoration.json"
if ($SuiteGroup -eq 'core') {
    $cleaned=Get-Content -Raw -LiteralPath "$evidence/preparation-cleanup.json" | ConvertFrom-Json
    if ($cleaned.complete -ne $true -or $cleaned.contextSha256 -cne $env:CS_ENHANCE_ACTOR_CONTEXT_SHA256) { throw 'Exact preparation cleanup was not verified' }
    $sceneEvidence+=Reference "$evidence/preparation-cleanup.json"
}
$treeMoved=((& git rev-parse HEAD).Trim() -ne $env:SUPPORT_CANDIDATE -or (& git diff HEAD --binary | Out-String) -cne $initialTree -or (& git status --porcelain=v1 | Out-String) -cne $initialStatus)
if ($treeMoved) { throw 'Candidate changed during runtime checks' }
$observed=switch ($SuiteGroup) {
    core { 'All existing 27 core suites and 24 core scenes executed on the verified exclusive resources; random assignment, finance/profile reads, messaging, and existing permission behavior were exercised.' }
    bulk { 'All existing 6 bulk suites executed; the queued batch was persisted then read and completed in a separate JVM, with duplicate delivery and refresh readback checked.' }
    avatar { 'Avatar read permissions, upload compensation, and A2 approval runtime suites executed with real private object storage and database readback.' }
}
$result=[ordered]@{at=[DateTime]::UtcNow.ToString('o');taskId=$env:WORKFLOW_TASK_ID;stepId=$Phase;checkId=$env:WORKFLOW_CHECK_ID;runId=$env:WORKFLOW_RUN_ID;repo=$env:WORKFLOW_REPO;snapshotHash=$env:WORKFLOW_SNAPSHOT_HASH;candidate=$env:SUPPORT_CANDIDATE;verdict='pass';mode='full';treeMoved=$false;capability='runtime';suiteGroup=$SuiteGroup;steps=@(@{id='BE-BASELINE';status='pass';innerSkipped=0;evidence=@($observed,"Current zero-skip XML, scene, object and database before/after references: $evidence")});suites=@($suiteEvidence.ToArray());scenes=@($sceneEvidence);ownership=(Reference $env:SUPPORT_RESOURCE_OWNERSHIP);before=(Reference "$evidence/shared-before.json");after=(Reference "$evidence/shared-after.json");objectBefore=(Reference "$evidence/object-before.json");objectAfter=(Reference "$evidence/object-after.json")}
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Report -Encoding utf8
Write-Output "step=$Phase status=pass report=$Report"

[CmdletBinding()]
param([ValidateSet('I0-B','I1-A','I1-B','I2-A')][string]$Phase, [Alias('SuiteGroup')][ValidateSet('core','bulk','avatar')][string]$RequestedSuiteGroup,
    [Parameter(Mandatory)][string]$Report)
$ErrorActionPreference = 'Stop'
$SuiteGroup = $RequestedSuiteGroup
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
$root = 'D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2'
$runtime = "$root/runtime"
foreach ($name in @('TASK_ID','STEP_ID','CHECK_ID','RUN_ID','REPO','SNAPSHOT_HASH')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("WORKFLOW_$name"))) { throw "Missing current workflow identity: $name" }
}
function Test-WorkflowBinding([string]$Step, [string]$Check, [string]$SourcePhase, [string]$Group) {
    if ($Step -ceq $SourcePhase) { return $true }
    if ($Step -cne 'integration') { return $false }
    # The runner replays upstream checks with integration identity and positional check IDs.
    $replayed = switch ($Check) {
        'step-0-check-1' { 'I0-B/core' }
        'step-0-check-2' { 'I0-B/bulk' }
        'step-0-check-3' { 'I0-B/avatar' }
        'step-1-check-1' { 'I1-A/groups' }
        'step-2-check-1' { 'I1-B/group-scope' }
        'step-3-check-1' { 'I2-A/payment-facts' }
        default { return $false }
    }
    return $replayed -ceq "$SourcePhase/$Group"
}
if ($Phase -eq 'I0-B' -and [string]::IsNullOrWhiteSpace($SuiteGroup)) { throw 'I0-B requires its original explicit suite group' }
if ($Phase -ne 'I0-B') {
    if (-not [string]::IsNullOrWhiteSpace($SuiteGroup)) { throw 'SuiteGroup is reserved for I0-B' }
    $SuiteGroup = switch ($Phase) { 'I1-A' { 'groups' } 'I1-B' { 'group-scope' } 'I2-A' { 'payment-facts' } }
}
if (!(Test-WorkflowBinding $env:WORKFLOW_STEP_ID $env:WORKFLOW_CHECK_ID $Phase $SuiteGroup) -or $env:WORKFLOW_RUN_ID -notmatch '^[a-zA-Z0-9_-]+$' -or $env:WORKFLOW_SNAPSHOT_HASH -notmatch '^[a-f0-9]{64}$') { throw 'Current workflow binding required' }
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
$evidenceRoot="$root/current-runs"
$evidence="$evidenceRoot/$($env:WORKFLOW_RUN_ID)/$Phase/$SuiteGroup"
# The Java ownership ledger requires physical paths; archived compatibility junctions are read-only history.
foreach ($artifactPath in @($evidence, $Report)) {
    for ($ancestor=[IO.Path]::GetFullPath($artifactPath); $ancestor; $ancestor=[IO.Path]::GetDirectoryName($ancestor)) {
        if ((Test-Path -LiteralPath $ancestor) -and ((Get-Item -LiteralPath $ancestor).Attributes -band [IO.FileAttributes]::ReparsePoint)) {
            throw 'Runtime evidence must use a physical directory, without links in its parent path'
        }
    }
}
foreach ($drive in @([IO.Path]::GetPathRoot($evidence),[IO.Path]::GetPathRoot([IO.Path]::GetFullPath($Report))) | Select-Object -Unique) {
    if ([IO.DriveInfo]::new($drive).AvailableFreeSpace -lt 2GB) { throw "Runtime evidence needs at least 2 GiB free on $drive" }
}
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
$env:CS_ANALYTICS_GROUPS_ENABLED=if($Phase -eq 'I1-A'){'true'}else{'false'}
$env:CS_ANALYTICS_GROUP_SCOPE_ENABLED=if($Phase -eq 'I1-B'){'true'}else{'false'}
$env:CS_ANALYTICS_PAYMENT_FACTS_ENABLED=if($Phase -eq 'I2-A'){'true'}else{'false'}
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
        groups { Run-Suites 'groups' @('SupportGroupServiceTest','SupportGroupMapperSqlTest','MybatisSupportAgentRepositoryTest','OpsSupportAgentControllerTest','OpsAdminAccountServiceTest','SupportGroupMigrationRuntimeTest','SupportGroupRuntimeTest') }
        group-scope { Run-Suites 'group-scope' @('SupportOwnershipScopeTest','SupportScopeSqlContractTest','SupportContentScopeSqlTest','SupportScopedCustomerSqlContractTest','SupportBulkScopeTest','OpsSupportCommandScopeTest','SupportAdminAvatarScopeTest','GlobalExceptionHandlerTest','OpsSupportTicketControllerTest','SupportGroupScopeRuntimeTest') }
        payment-facts { Run-Suites 'payment-facts' @('SupportPaymentFactServiceTest','SupportPaymentFactMapperSqlTest','SupportPaymentFactRuntimeTest') }
    }
} finally {
    $env:SUPPORT_ANALYTICS_CAPTURE='after'
    Run-Suites 'after' @('SupportAnalyticsCaptureTest')
    $env:SUPPORT_ANALYTICS_CAPTURE=''
}
if ($SuiteGroup -eq 'bulk') { $scenes=@('bulk-runtime.json','bulk-restart-runtime.json','bulk-restart-seed.json') }
if ($Phase -eq 'I1-A') { $scenes=@('groups-runtime.json','groups-migration-runtime.json') }
if ($Phase -eq 'I1-B') { $scenes=@('group-scope-runtime.json') }
if ($Phase -eq 'I2-A') { $scenes=@('payment-facts-runtime.json') }
if ($SuiteGroup -eq 'avatar') {
    $scenes=@('avatar-read-runtime.json','avatar-compensation-runtime.json')
    $a2=@(Get-ChildItem -LiteralPath $evidence -Filter 'avatar-a2-runtime-*.json' -File)
    if ($a2.Count -ne 1) { throw 'Exactly one current A2 runtime scene is required' }
    $scenes+=$a2[0].Name
}
$sceneEvidence=@(foreach($name in $scenes) {
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
})
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
$acceptance=@('BE-BASELINE')
if ($Phase -eq 'I1-A') {
    $acceptance=@('BE-G05','BE-G06','BE-G07','BE-G21-FOUNDATION','BE-G23-FOUNDATION','BE-G24-FOUNDATION','BE-SUP-02','BE-SUP-03')
    $groups=Get-Content -Raw "$evidence/groups-runtime.json" | ConvertFrom-Json
    $migration=Get-Content -Raw "$evidence/groups-migration-runtime.json" | ConvertFrom-Json
    if($groups.proofs.cleanupComplete -ne $true -or $groups.proofs.legacyBindingsUnchanged -ne $true -or $migration.ownedScratchRemoved -ne $true){throw 'Group preservation and cleanup proof missing'}
    foreach($id in $acceptance){if($null -eq $groups.proofs.$id -and $null -eq $migration.$id){throw "Group acceptance proof missing: $id"}}
}
if ($Phase -eq 'I2-A') {
    $acceptance=@('BE-FACT-SOURCES')
    $facts=Get-Content -Raw "$evidence/payment-facts-runtime.json" | ConvertFrom-Json
    if($facts.capability -ne 'runtime'){throw 'Payment facts runtime capability missing'}
    foreach($id in @('deposit-rails','device-paid-free-trial','refund-lineage-and-missing-evidence')){
        if($facts.checks.$id.status -ne 'pass' -or [string]::IsNullOrWhiteSpace($facts.checks.$id.testcase)){throw "Payment source proof missing: $id"}
    }
}
if ($Phase -eq 'I1-B') {
    $acceptance=@('BE-G08','BE-G09','BE-SUP-01','BE-SUP-04','BE-G21','BE-G24-OBJECTS')
    $scope=Get-Content -Raw "$evidence/group-scope-runtime.json" | ConvertFrom-Json
    if($scope.capability -ne 'runtime' -or $scope.proofs.completed -ne $true -or $scope.proofs.cleanupComplete -ne $true -or $scope.proofs.legacyBindingsUnchanged -ne $true){throw 'Current scope runtime completion and preservation proof missing'}
    foreach($id in $acceptance){if($null -eq $scope.proofs.$id){throw "Current scope acceptance proof missing: $id"}}
    if(@($scope.requests).Count -lt 40){throw 'Authenticated current-scope request matrix incomplete'}
    foreach($field in @('ownerChangedReadback','oldTokenAndUrlDenied','socketWatchAndTypingDenied','sseInvalidated','dualPersonalStillReadable','managementHasNoPersonalRows','oldTicketAndAttachmentDenied','oldBulkSummaryAndRecipientsDenied','frozenAssignmentRejected','sseCurrentMessagesFiltered','socketCurrentEventsFiltered','searchRechecksCurrentScope')) {
        if($scope.proofs.'BE-G24-OBJECTS'.$field -ne $true){throw "Object revocation proof missing: $field"}
    }
}
$observed=switch ($SuiteGroup) {
    core { 'All existing 27 core suites and 24 core scenes executed on the verified exclusive resources; random assignment, finance/profile reads, messaging, and existing permission behavior were exercised.' }
    bulk { 'All existing 6 bulk suites executed; the queued batch was persisted then read and completed in a separate JVM, with duplicate delivery and refresh readback checked.' }
    avatar { 'Avatar read permissions, upload compensation, and A2 approval runtime suites executed with real private object storage and database readback.' }
    groups { 'Group and independent qualification commands, old A1 handover guards, concurrent versions, required audit rollback, legacy preservation and real controlled migration replay executed. Foundation scope only; later object authorization and statistics are not signed here.' }
    group-scope { 'Authenticated HTTP lists and counts, queue routing and assignment, separate personal/managed scopes, existing conversations/tickets/private attachments/bulk recipients, committed owner transfer and live socket/SSE invalidation were exercised on exact owned actors and customers; original bindings and objects were preserved.' }
    payment-facts { 'Canonical payment source fixtures were read through the source adapter and rolled back; deposit rails, paid/free/trial device settlement, original refund lineage and missing evidence were checked without executing financial settlement.' }
}
$steps=@(foreach($id in $acceptance){@{id=$id;status='pass';innerSkipped=0;evidence=@($observed,"Current zero-skip XML, scene, object and database before/after references: $evidence")}})
$result=[ordered]@{at=[DateTime]::UtcNow.ToString('o');taskId=$env:WORKFLOW_TASK_ID;stepId=$env:WORKFLOW_STEP_ID;sourcePhase=$Phase;checkId=$env:WORKFLOW_CHECK_ID;runId=$env:WORKFLOW_RUN_ID;repo=$env:WORKFLOW_REPO;snapshotHash=$env:WORKFLOW_SNAPSHOT_HASH;candidate=$env:SUPPORT_CANDIDATE;verdict='pass';mode='full';treeMoved=$false;capability='runtime';suiteGroup=$SuiteGroup;steps=$steps;suites=@($suiteEvidence.ToArray());scenes=@($sceneEvidence);ownership=(Reference $env:SUPPORT_RESOURCE_OWNERSHIP);before=(Reference "$evidence/shared-before.json");after=(Reference "$evidence/shared-after.json");objectBefore=(Reference "$evidence/object-before.json");objectAfter=(Reference "$evidence/object-after.json")}
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Report -Encoding utf8
Write-Output "step=$Phase status=pass report=$Report"

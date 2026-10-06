[CmdletBinding()]
param(
    [ValidateSet('all','remaining','runtime','summary')][string]$CoreGroup = 'all',
    [string]$AggregateRoot
)

$ErrorActionPreference = 'Stop'
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
# These are the original complete inventories; groups only partition them.
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
$sceneNames = @('random-runtime.json','random-partial-runtime.json','random-concurrency-runtime.json','binding-availability-runtime.json',
    'finance-profile-runtime.json','profile-device-runtime.json','conversation-runtime.json','avatar-runtime.json','sku-link-runtime.json',
    'account-replay-runtime.json','original-actions-runtime.json','publisher-runtime.json','presence-runtime.json','profile-errors-runtime.json',
    'legacy-s3/scenario-evidence.json','legacy-s3/ticket-private-evidence.json','legacy-s3/unanswered-evidence.json',
    'legacy-s4/scenario-evidence.json','legacy-s4/message-replay-mysql-evidence.json',
    'legacy-s4/maintenance-activity-stop-lock.json','legacy-s4/maintenance-coverage-fence.json',
    'legacy-s4/maintenance-send-transfer-lock.json','legacy-s4/maintenance-sequence.json','preparation-runtime.json')
$groupSuites = @{
    runtime = @($suites | Where-Object { $_ -eq 'SupportEnhancementCoreRuntimeTest' })
    remaining = @($suites | Where-Object { $_ -ne 'SupportEnhancementCoreRuntimeTest' })
    all = $suites
}
$groupScenes = @{
    runtime = @($sceneNames | Where-Object { $_ -notlike 'legacy-*/*' -and $_ -ne 'preparation-runtime.json' })
    remaining = @($sceneNames | Where-Object { $_ -like 'legacy-*/*' -or $_ -eq 'preparation-runtime.json' })
    all = $sceneNames
}
if ($suites.Count -ne 27 -or $sceneNames.Count -ne 24 -or $groupSuites.runtime.Count -ne 1 -or $groupSuites.remaining.Count -ne 26 -or $groupScenes.runtime.Count -ne 14 -or $groupScenes.remaining.Count -ne 10) {
    throw 'Original core inventory or its complete partition changed'
}

function Get-CoreHash([string]$Path) {
    (Get-FileHash -LiteralPath $Path -Algorithm SHA256).Hash.ToLowerInvariant()
}
function Get-CoreFile([string]$Path) {
    $file = Get-Item -LiteralPath $Path
    if ($file.PSIsContainer -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw "Regular evidence file required: $Path" }
    $file
}
function Read-CoreJson([string]$Path) {
    Get-Content -Raw -LiteralPath (Get-CoreFile $Path).FullName | ConvertFrom-Json -DateKind String
}
function Get-CoreTime([string]$Value) {
    if ([string]::IsNullOrWhiteSpace($Value)) { throw 'Evidence timestamp missing' }
    [DateTimeOffset]::Parse($Value).UtcDateTime
}
function Get-CleanCoreCommit {
    $head = (& git -C $repo rev-parse HEAD 2>$null)
    if ($LASTEXITCODE -ne 0 -or $head -notmatch '^[0-9a-f]{40}$') { throw 'Actual core commit unavailable' }
    $status = @(& git -C $repo status --porcelain 2>$null)
    if ($LASTEXITCODE -ne 0 -or $status.Count) { throw 'Grouped core evidence requires a clean candidate' }
    $head.Trim()
}
function Assert-CoreIdentity($Document) {
    foreach ($entry in @{'taskId'='WORKFLOW_TASK_ID';'stepId'='WORKFLOW_STEP_ID';'runId'='WORKFLOW_RUN_ID';'repo'='WORKFLOW_REPO';'snapshotHash'='WORKFLOW_SNAPSHOT_HASH'}.GetEnumerator()) {
        if ($Document.($entry.Key) -cne [Environment]::GetEnvironmentVariable($entry.Value)) { throw "Core workflow mismatch: $($entry.Key)" }
    }
}
function Read-CoreSuites([string[]]$Names, [string]$Directory, [DateTime]$Started, [DateTime]$Finished, [string]$EvidenceGroup) {
    $reportDirectory = Join-Path $Directory 'surefire-reports'
    $reports = @(Get-ChildItem -LiteralPath $reportDirectory -Filter 'TEST-*.xml' -File)
    if ($EvidenceGroup -ne 'all' -and $reports.Count -ne $Names.Count) { throw 'Core group must contain exactly its original suite reports' }
    foreach ($suite in $Names) {
        $matching = @($reports | Where-Object { $_.Name -like "TEST-*.$suite.xml" })
        if ($matching.Count -ne 1) { throw "Missing or duplicate core report: $suite" }
        $file = Get-CoreFile $matching[0].FullName
        if ($file.LastWriteTimeUtc -lt $Started -or $file.LastWriteTimeUtc -gt $Finished) { throw "Stale core report: $suite" }
        [xml]$xml = Get-Content -Raw -LiteralPath $file.FullName
        $result = $xml.testsuite
        if (!$result -or !$result.GetAttribute('name').EndsWith(".$suite", [StringComparison]::Ordinal)) { throw "Wrong original XML suite: $suite" }
        $counts = @{}
        foreach ($attribute in @('tests','skipped','failures','errors')) {
            $number = 0
            if (![int]::TryParse($result.GetAttribute($attribute), [ref]$number) -or $number -lt 0) { throw "Invalid original XML count: $suite/$attribute" }
            $counts[$attribute] = $number
        }
        $cases = @($result.testcase | ForEach-Object { $_.GetAttribute('name') })
        if ($counts.tests -le 0 -or $counts.skipped -ne 0 -or $counts.failures -ne 0 -or $counts.errors -ne 0 -or $cases.Count -ne $counts.tests -or @($cases | Where-Object { [string]::IsNullOrWhiteSpace($_) }).Count) {
            throw "Core suite did not execute completely and cleanly: $suite"
        }
        [ordered]@{suite=$suite;tests=$counts.tests;skipped=0;failures=0;errors=0;report=$file.FullName.Replace('\','/');sha256=(Get-CoreHash $file.FullName);cases=$cases;writtenAt=$file.LastWriteTimeUtc.ToString('o');group=$EvidenceGroup;runId=$env:WORKFLOW_RUN_ID;snapshotHash=$env:WORKFLOW_SNAPSHOT_HASH}
    }
}
function Read-CoreScenes([string[]]$Names, [string]$Directory, [DateTime]$Started, [DateTime]$Finished, [string]$EvidenceGroup) {
    foreach ($name in $Names) {
        $file = Get-CoreFile (Join-Path $Directory $name)
        if ($file.LastWriteTimeUtc -lt $Started -or $file.LastWriteTimeUtc -gt $Finished) { throw "Scenario outside current regression: $name" }
        $document = Read-CoreJson $file.FullName
        $stamp = if ($document.checkedAt) {$document.checkedAt} elseif ($document.at) {$document.at} else {$document.timestamp}
        if ($stamp -and ((Get-CoreTime $stamp) -lt $Started -or (Get-CoreTime $stamp) -gt $Finished)) { throw "Scenario timestamp outside current regression: $name" }
        if ($name -match '^.+-runtime\.json$' -and $name -ne 'preparation-runtime.json' -and $env:WORKFLOW_RUN_ID) {
            if ($document.workflowRunId -cne $env:WORKFLOW_RUN_ID -or $document.snapshotHash -cne $env:WORKFLOW_SNAPSHOT_HASH) { throw "Scenario workflow mismatch: $name" }
        }
        if ($EvidenceGroup -ne 'all' -and $name -eq 'preparation-runtime.json') {
            if ($document.stage -cne 'preparation-only' -or $document.database -cne 'cs_enhance_20261001' -or $document.httpPort -ne 18141 -or $document.redisPort -ne 16341 -or $document.bucket -cne 'cs-enhance-20261001-private' -or $null -eq $document.copiedObjects -or $document.copiedObjects -ne 0 -or $document.triggerWritesRolledBack -ne 3) { throw 'Original isolated preparation boundary and existing storage baseline required' }
            foreach ($field in @('sqlReadback','sourceDatabaseDenied','redisReadback','storageReadback','anonymousStorageDenied','baselineAttachmentReadback','httpHealth','anonymousAdminDenied')) {
                if ($document.$field -isnot [bool] -or !$document.$field) { throw "Original preparation readback failed: $field" }
            }
            foreach ($field in @('scheduledJobs','productImplementationReleased')) {
                if ($document.$field -isnot [bool] -or $document.$field) { throw "Preparation isolation failed: $field" }
            }
        }
        # Original legacy/preparation documents lack workflow fields; their fresh bytes are sealed in this group's identity.
        [ordered]@{name=$name;path=$file.FullName.Replace('\','/');sha256=(Get-CoreHash $file.FullName);writtenAt=$file.LastWriteTimeUtc.ToString('o');group=$EvidenceGroup;runId=$env:WORKFLOW_RUN_ID;snapshotHash=$env:WORKFLOW_SNAPSHOT_HASH}
    }
}
function Assert-CoreInventory($Actual, [string[]]$Expected, [string]$Field) {
    $names = @($Actual | ForEach-Object { $_.$Field })
    if ($names.Count -ne $Expected.Count -or @($names | Sort-Object -Unique).Count -ne $Expected.Count -or @(Compare-Object $names $Expected -CaseSensitive).Count) { throw "Incomplete or duplicate original core inventory: $Field" }
}
function Write-CoreSummary($Document, [string]$Path) {
    $Document | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Path -Encoding utf8
    Get-CoreHash $Path | Set-Content -LiteralPath ($Path + '.sha256') -Encoding ascii
}

if ($CoreGroup -ne 'all') {
    foreach ($key in @('TASK_ID','STEP_ID','CHECK_ID','RUN_ID','REPO','SNAPSHOT_HASH')) {
        if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("WORKFLOW_$key"))) { throw "Current native identity required: $key" }
    }
    if ($env:WORKFLOW_RUN_ID -notmatch '^[a-zA-Z0-9_-]+$' -or $env:WORKFLOW_STEP_ID -cne 'integration' -or $env:WORKFLOW_SNAPSHOT_HASH -notmatch '^[0-9a-f]{64}$') { throw 'Current integration run and snapshot required' }
    $commit = Get-CleanCoreCommit
}

if ($CoreGroup -eq 'summary') {
    # No environment overlay, Maven, service, or dependency access in the aggregation branch.
    if (!$AggregateRoot -or ![IO.Path]::IsPathFullyQualified($AggregateRoot)) { throw 'Explicit absolute AggregateRoot required' }
    $root = [IO.Path]::GetFullPath($AggregateRoot).TrimEnd([char[]]@('\','/'))
    if ($root.Replace('\','/') -notlike "*/backend-avatar-read/runs/$env:WORKFLOW_RUN_ID/integration") { throw 'AggregateRoot must be this native run integration directory' }
    $coreRoot = Join-Path $root 'core'
    $output = Join-Path $coreRoot 'core-check-summary.json'
    if (Test-Path -LiteralPath $output) { throw 'Fresh aggregate summary required' }
    $checked = [DateTime]::UtcNow
    $allSuites = @(); $allScenes = @(); $groups = @(); $firstStarted = $null; $previousCleanup = $null; $firstBefore = $null
    foreach ($group in @('remaining','runtime')) {
        $directory = Join-Path $coreRoot $group
        $folder = Get-Item -LiteralPath $directory
        if (!$folder.PSIsContainer -or ($folder.Attributes -band [IO.FileAttributes]::ReparsePoint)) { throw 'Exact current core group directory required' }
        $summaryPath = Join-Path $directory 'core-check-summary.json'
        $summaryFile = Get-CoreFile $summaryPath
        $summaryHash = Get-CoreHash $summaryPath
        $seal = (Get-Content -Raw -LiteralPath (Get-CoreFile ($summaryPath + '.sha256')).FullName).Trim()
        if ($seal -cne $summaryHash) { throw "Core group summary hash mismatch: $group" }
        $document = Read-CoreJson $summaryPath
        Assert-CoreIdentity $document
        if ($document.group -cne $group -or $document.verdict -cne 'pass' -or $document.execution -cne 'single-maven' -or $document.commit -cne $commit -or $document.cleanCandidate -isnot [bool] -or !$document.cleanCandidate -or !$document.checkId -or $document.database -cne 'cs_enhance_20261001' -or $document.port -ne 18141) { throw "Successful current clean core group required: $group" }
        $started = Get-CoreTime $document.startedAt
        $finished = Get-CoreTime $document.finishedAt
        $groupChecked = Get-CoreTime $document.checkedAt
        if ($finished -lt $started -or $groupChecked -lt $finished -or $groupChecked -gt $checked -or ($groupChecked - $started).TotalMilliseconds -ge 600000 -or $summaryFile.LastWriteTimeUtc -lt $groupChecked -or $summaryFile.LastWriteTimeUtc -gt $checked) { throw "Invalid bounded core group timeline: $group" }
        if ($null -eq $firstStarted) { $firstStarted = $started }
        Assert-CoreInventory $document.suites $groupSuites[$group] 'suite'
        Assert-CoreInventory $document.scenes $groupScenes[$group] 'name'
        $currentSuites = @(Read-CoreSuites $groupSuites[$group] $directory $started $finished $group)
        $currentScenes = @(Read-CoreScenes $groupScenes[$group] $directory $started $finished $group)
        foreach ($entry in $currentSuites) {
            $record = @($document.suites | Where-Object { $_.suite -ceq $entry.suite })[0]
            foreach ($field in @('tests','skipped','failures','errors','report','sha256','writtenAt','group','runId','snapshotHash')) {
                if ($record.$field -cne $entry[$field]) { throw "Original XML differs from sealed summary: $group/$($entry.suite)/$field" }
            }
            if (($record.cases | ConvertTo-Json -Compress) -cne ($entry.cases | ConvertTo-Json -Compress)) { throw 'Executed original case names changed' }
        }
        foreach ($entry in $currentScenes) {
            $record = @($document.scenes | Where-Object { $_.name -ceq $entry.name })[0]
            foreach ($field in @('path','sha256','writtenAt','group','runId','snapshotHash')) {
                if ($record.$field -cne $entry[$field]) { throw "Original scenario differs from sealed summary: $group/$($entry.name)/$field" }
            }
        }
        $boundaries = @{}
        foreach ($name in @('preparation-before.json','shared-state-restoration.json','preparation-cleanup.json')) {
            $path = Join-Path $directory $name
            $file = Get-CoreFile $path
            $proof = Read-CoreJson $path
            if ($proof.runId -cne $env:WORKFLOW_RUN_ID -or $proof.snapshotHash -cne $env:WORKFLOW_SNAPSHOT_HASH) { throw "Current group boundary identity required: $group/$name" }
            $at = Get-CoreTime $proof.at
            if ($at -gt $checked -or $file.LastWriteTimeUtc -lt $at -or $file.LastWriteTimeUtc -gt $checked) { throw "Invalid group boundary timestamp: $group/$name" }
            $boundaries[$name] = @{document=$proof;at=$at;writtenAt=$file.LastWriteTimeUtc;path=$path.Replace('\','/');sha256=(Get-CoreHash $path)}
        }
        $before = $boundaries['preparation-before.json']; $cleanup = $boundaries['preparation-cleanup.json']; $restoration = $boundaries['shared-state-restoration.json']
        if ($before.document.PSObject.Properties.Name -notcontains 'coverageStart' -or [string]::IsNullOrWhiteSpace([string]$before.document.coverageStart)) { throw 'Actual original activity coverage baseline required' }
        if ($before.at -gt $started -or $before.writtenAt -gt $started -or ($previousCleanup -and $before.at -lt $previousCleanup) -or $restoration.at -lt $groupChecked -or $cleanup.at -lt $restoration.at -or $cleanup.writtenAt -lt $restoration.writtenAt) { throw 'Each complete core group must be followed by cleanup before the next preflight' }
        if ($before.document.baselineCount -ne 90 -or $before.document.markers -cne 'existing/exact-owner' -or $cleanup.document.cleanup -cne 'pass' -or $restoration.document.mode -cne 'restore') { throw 'Original preparation and owned cleanup contracts required' }
        foreach ($field in @('temporarySchemaAbsent')) { if ($before.document.$field -isnot [bool] -or !$before.document.$field) { throw "Preflight boundary failed: $field" } }
        foreach ($field in @('temporarySchemaAbsent','priorSqlAndStorageProbeIdsPreserved','sharedBusinessRulesAndEnabledIdsRestored','activityCoverageStartRestored')) { if ($cleanup.document.$field -isnot [bool] -or !$cleanup.document.$field) { throw "Cleanup boundary failed: $field" } }
        foreach ($field in @('sharedBusinessRulesAndEnabledIdsRestored','ownedProfilesAndAccountsDisabled','ownedSessionAndPermissionCacheAbsent')) { if ($restoration.document.$field -isnot [bool] -or !$restoration.document.$field) { throw "Shared-state boundary failed: $field" } }
        if ([IO.Path]::GetFullPath($restoration.document.beforeEvidence) -ne [IO.Path]::GetFullPath($before.path) -or $restoration.document.beforeSha256 -cne $before.sha256 -or !$before.document.rules -or $null -eq $before.document.enabledIds -or ($restoration.document.originalBusinessRules | ConvertTo-Json -Compress) -cne ($before.document.rules | ConvertTo-Json -Compress)) { throw 'Shared restoration must bind its actual original preflight' }
        if ($firstBefore) {
            foreach ($field in @('rules','enabledIds','coverageStart')) {
                if (($before.document.$field | ConvertTo-Json -Compress) -cne ($firstBefore.$field | ConvertTo-Json -Compress)) { throw "Shared baseline changed between complete core groups: $field" }
            }
        } else { $firstBefore = $before.document }
        $previousCleanup = $cleanup.at
        $allSuites += $currentSuites; $allScenes += $currentScenes
        $groups += [ordered]@{group=$group;commit=$commit;runId=$document.runId;snapshotHash=$document.snapshotHash;checkId=$document.checkId;startedAt=$document.startedAt;finishedAt=$document.finishedAt;checkedAt=$document.checkedAt;summary=$summaryFile.FullName.Replace('\','/');sha256=$summaryHash;preparationBefore=$before.path;preparationBeforeSha256=$before.sha256;cleanup=$cleanup.path;cleanupSha256=$cleanup.sha256;sharedRestoration=$restoration.path;sharedRestorationSha256=$restoration.sha256}
    }
    Assert-CoreInventory $allSuites $suites 'suite'
    Assert-CoreInventory $allScenes $sceneNames 'name'
    if ((Get-CleanCoreCommit) -cne $commit) { throw 'Core candidate changed during aggregation' }
    Write-CoreSummary ([ordered]@{group='summary';verdict='pass';execution='aggregate-two-maven-runs';rawNativeBinding='legacy/preparation via current bound group manifest and fresh original XML/hash/time';commit=$commit;cleanCandidate=$true;startedAt=$firstStarted.ToString('o');checkedAt=[DateTime]::UtcNow.ToString('o');database='cs_enhance_20261001';port=18141;taskId=$env:WORKFLOW_TASK_ID;stepId=$env:WORKFLOW_STEP_ID;checkId=$env:WORKFLOW_CHECK_ID;runId=$env:WORKFLOW_RUN_ID;repo=$env:WORKFLOW_REPO;snapshotHash=$env:WORKFLOW_SNAPSHOT_HASH;groups=$groups;suites=$allSuites;scenes=$allScenes}) $output
    Write-Output 'Core aggregate passed: all 27 original suites and 24 original scenes; two bounded Maven runs, zero skips, both cleanup/shared-state receipts verified.'
    exit 0
}

if ($AggregateRoot) { throw 'AggregateRoot is only supported for CoreGroup summary' }
. "$PSScriptRoot/support-enhancements-core-env.ps1"
$selectedSuites = $groupSuites[$CoreGroup]
$selectedScenes = $groupScenes[$CoreGroup]
$summaryPath = Join-Path $env:CS_ENHANCE_EVIDENCE_DIR 'core-check-summary.json'
if ($CoreGroup -ne 'all') {
    $directory = [IO.Path]::GetFullPath($env:CS_ENHANCE_EVIDENCE_DIR).TrimEnd([char[]]@('\','/')).Replace('\','/')
    if ($directory -notlike "*/backend-avatar-read/runs/$env:WORKFLOW_RUN_ID/integration/core/$CoreGroup") { throw 'Exact current native core group output required' }
    if ((Test-Path -LiteralPath $summaryPath) -or ((Test-Path -LiteralPath "$directory/surefire-reports") -and @(Get-ChildItem -LiteralPath "$directory/surefire-reports" -Filter 'TEST-*.xml' -File).Count)) { throw 'Fresh core group evidence directory required' }
    foreach ($scene in $selectedScenes) { if (Test-Path -LiteralPath (Join-Path $directory $scene)) { throw "Existing group scenario cannot be reused: $scene" } }
    if ((Get-CleanCoreCommit) -cne $commit) { throw 'Candidate changed before core group execution' }
}
$started = [DateTime]::UtcNow
$log = Join-Path $env:CS_ENHANCE_EVIDENCE_DIR ('core-check-' + $started.ToString('yyyyMMdd-HHmmss') + '.log')
$reportDirectory = "$env:CS_ENHANCE_EVIDENCE_DIR/surefire-reports"
& 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/apache-maven-3.9.9/bin/mvn.cmd' "-Dsupport.test.reportsDirectory=$reportDirectory" "-Dtest=$($selectedSuites -join ',')" test *> $log
if ($LASTEXITCODE -ne 0) { Write-Output "Core Maven check failed. Private log: $log"; exit 1 }
$finished = [DateTime]::UtcNow
$summary = @(Read-CoreSuites $selectedSuites $env:CS_ENHANCE_EVIDENCE_DIR $started $finished $CoreGroup)
$scenes = @(Read-CoreScenes $selectedScenes $env:CS_ENHANCE_EVIDENCE_DIR $started $finished $CoreGroup)
$checked = [DateTime]::UtcNow
if ($CoreGroup -ne 'all' -and (($checked - $started).TotalMilliseconds -ge 600000 -or (Get-CleanCoreCommit) -cne $commit)) { throw 'Core group exceeded its original ten-minute bound or changed candidate' }
Write-CoreSummary ([ordered]@{group=$CoreGroup;verdict='pass';execution='single-maven';rawNativeBinding='legacy/preparation via current bound group manifest and fresh original XML/hash/time';commit=$commit;cleanCandidate=($CoreGroup -ne 'all');startedAt=$started.ToString('o');finishedAt=$finished.ToString('o');checkedAt=$checked.ToString('o');database='cs_enhance_20261001';port=18141;taskId=$env:WORKFLOW_TASK_ID;stepId=$env:WORKFLOW_STEP_ID;checkId=$env:WORKFLOW_CHECK_ID;runId=$env:WORKFLOW_RUN_ID;repo=$env:WORKFLOW_REPO;snapshotHash=$env:WORKFLOW_SNAPSHOT_HASH;suites=$summary;scenes=$scenes}) $summaryPath
if ($CoreGroup -eq 'all') { Write-Output "Core passed: $($summary.Count) current suites, no skipped tests." }
else { Write-Output "Core group $CoreGroup passed: $($summary.Count) original suites, $($scenes.Count) current scenes; complete core acceptance requires CoreGroup summary after owned cleanup." }

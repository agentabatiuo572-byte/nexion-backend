[CmdletBinding()]
param([ValidateSet('I0-B','I1-A','I1-B','I2-A','I2-B','I3-B','I4-B','I5R-B','I6-B')][string]$Phase, [Alias('SuiteGroup')][ValidateSet('core','core-runtime','core-remaining','bulk','avatar','avatar-a2','avatar-read','avatar-compensation','avatar-self')][string]$RequestedSuiteGroup,
    [Parameter(Mandatory)][string]$Report)
$ErrorActionPreference = 'Stop'
$SuiteGroup = $RequestedSuiteGroup
function Compare-SharedCaptures($Before, $After) {
    if (($Before.schemaVersion -isnot [int] -and $Before.schemaVersion -isnot [long]) -or ($After.schemaVersion -isnot [int] -and $After.schemaVersion -isnot [long]) -or $Before.schemaVersion -cnotin @(1,2) -or $After.schemaVersion -cne $Before.schemaVersion) { throw 'Unsupported or mixed shared capture format' }
    if ($Before.tables -isnot [pscustomobject] -or $After.tables -isnot [pscustomobject]) { throw 'Shared capture table inventory missing' }
    $names=@($Before.tables.PSObject.Properties.Name | Sort-Object)
    if (($names -join "`n") -cne (@($After.tables.PSObject.Properties.Name | Sort-Object) -join "`n")) { throw 'Shared capture table inventory changed' }
    $changes=@(foreach($name in $names) {
        $first=$Before.tables.$name; $last=$After.tables.$name
        if ($Before.schemaVersion -eq 2) {
            foreach($summary in @($first,$last)) {
                if ($summary.columns -isnot [array] -or !$summary.columns.Count -or ($summary.rowCount -isnot [long] -and $summary.rowCount -isnot [int]) -or $summary.rowCount -lt 0 -or $summary.digestAlgorithm -cne 'SORTED_ROW_SHA256_V1' -or $summary.sha256 -cnotmatch '^[a-f0-9]{64}$') { throw "Invalid shared table summary: $name" }
                if (@($summary.columns | Where-Object { $_ -isnot [string] -or [string]::IsNullOrWhiteSpace($_) }).Count -or @($summary.columns | Sort-Object -Unique).Count -ne $summary.columns.Count) { throw "Invalid shared table columns: $name" }
            }
            if (($first.columns -join "`n") -cne ($last.columns -join "`n")) { throw "Shared table columns changed: $name" }
            if ($first.rowCount -ne $last.rowCount -or $first.sha256 -cne $last.sha256) { @{table=$name;beforeCount=$first.rowCount;afterCount=$last.rowCount;beforeSha256=$first.sha256;afterSha256=$last.sha256} }
        } else {
            if ($first.rows -isnot [array] -or $last.rows -isnot [array]) { throw "Legacy shared rows missing: $name" }
            if (($first.rows | ConvertTo-Json -Depth 100 -Compress) -cne ($last.rows | ConvertTo-Json -Depth 100 -Compress)) { @{table=$name;beforeCount=$first.rows.Count;afterCount=$last.rows.Count} }
        }
    })
    # Changes are recorded, not blanket-rejected: committed financial scenes intentionally retain owned facts.
    return @{schemaVersion=$Before.schemaVersion;tableCount=$names.Count;changes=$changes}
}
$repo = Split-Path -Parent $PSScriptRoot
Set-Location -LiteralPath $repo
$root = 'D:/CodexData/test-environments/workflow-runs/support-analytics-20261007-r2'
$runtime = "$root/runtime"
foreach ($name in @('TASK_ID','STEP_ID','CHECK_ID','RUN_ID','REPO','SNAPSHOT_HASH')) {
    if ([string]::IsNullOrWhiteSpace([Environment]::GetEnvironmentVariable("WORKFLOW_$name"))) { throw "Missing current workflow identity: $name" }
}
function Test-WorkflowBinding([string]$Step, [string]$Check, [string]$SourcePhase, [string]$Group, [string]$Task = '') {
    if($Group -cin @('avatar-a2','avatar-read','avatar-compensation','avatar-self') -and $Task -cne 'support-analytics-backend-20261009-r13'){return $false}
    foreach ($id in @($Step,$Check,$SourcePhase,$Group)) { if ([string]::IsNullOrWhiteSpace($id)) { return $false } }
    if ($Step -ceq $SourcePhase) {
        $nativeGroup = switch -CaseSensitive ("$Step/$Check") {
            'I0-B/runtime-core' { 'core' }
            'I0-B/runtime-core-runtime' { 'core-runtime' }
            'I0-B/runtime-core-remaining' { 'core-remaining' }
            'I0-B/runtime-bulk' { 'bulk' }
            'I0-B/runtime-avatar' { 'avatar' }
            'I0-B/runtime-avatar-a2' { 'avatar-a2' }
            'I0-B/runtime-avatar-read' { 'avatar-read' }
            'I0-B/runtime-avatar-compensation' { 'avatar-compensation' }
            'I0-B/runtime-avatar-self' { 'avatar-self' }
            'I1-A/runtime' { 'groups' }
            'I1-B/runtime' { 'group-scope' }
            'I2-B/runtime' { 'first-charge-aggregate' }
            'I3-B/runtime' { 'analytics-http' }
            'I4-B/runtime' { 'rules-configuration' }
            'I5R-B/runtime' { 'leaderboard' }
            'I6-B/runtime' { 'session-profile' }
            'I2-A/runtime' { 'payment-facts' }
            default { return $false }
        }
        return $nativeGroup -ceq $Group
    }
    if ($Step -cne 'integration') { return $false }
    # The runner replays upstream checks with integration identity and positional check IDs.
    $replayed = switch -CaseSensitive ($Check) {
        'step-0-check-1' { 'I0-B/core-runtime' }
        'step-0-check-2' { 'I0-B/core-remaining' }
        'step-0-check-3' { 'I0-B/bulk' }
        'step-0-check-4' { if($Group -ceq 'avatar-a2'){'I0-B/avatar-a2'}else{'I0-B/avatar'} }
        'step-0-check-5' { 'I0-B/avatar-read' }
        'step-0-check-6' { 'I0-B/avatar-compensation' }
        'step-0-check-7' { 'I0-B/avatar-self' }
        'step-1-check-1' { 'I1-A/groups' }
        'step-2-check-1' { 'I1-B/group-scope' }
        'step-4-check-1' { 'I2-B/first-charge-aggregate' }
        'step-5-check-1' { 'I3-B/analytics-http' }
        'step-6-check-1' { 'I4-B/rules-configuration' }
        'step-7-check-1' { 'I5R-B/leaderboard' }
        'step-8-check-1' { 'I6-B/session-profile' }
        'step-3-check-1' { 'I2-A/payment-facts' }
        default { return $false }
    }
    return $replayed -ceq "$SourcePhase/$Group"
}
function Get-NativePhaseInventory([string]$SourcePhase) {
    switch -CaseSensitive ($SourcePhase) {
        'I2-B' { return @{ TaskId='support-analytics-backend-20261009-r13'; Dependencies=@('SupportAnalyticsHttpRuntimeTest','SupportGroupRuntimeFixtures','SupportFirstChargeCommandReceipt'); Group='first-charge-aggregate'; Suites=@('SupportFirstChargeRuntimeTest','SupportAnalyticsAggregateRuntimeTest'); OptIns=@('CS_ANALYTICS_FIRST_ENABLED','CS_ANALYTICS_AGGREGATE_ENABLED'); Acceptance=@('BE-FST-01','BE-FST-02','BE-FST-03','BE-FST-04','BE-FST-05','BE-FST-06','BE-FST-07','BE-FST-08','BE-FST-09','BE-FST-10','BE-FST-11','BE-FST-12','BE-AGG-01','BE-AGG-02','BE-AGG-03','BE-AGG-04','BE-AGG-05','BE-AGG-06','BE-AGG-07','BE-AGG-08','BE-AGG-09','BE-G13','BE-G14','BE-G15','BE-G16','BE-G17','BE-G18','BE-G19','BE-G20'); Cases=@(
            @{suite='SupportFirstChargeRuntimeTest';testcase='actualSpringCardSettlementKeepsSingleFirstAndRealAuditInsideFullRollback';scene='SupportFirstChargeRuntimeTest-actualSpringCardSettlementKeepsSingleFirstAndRealAuditInsideFullRollback.json';policy='scenario'}
            @{suite='SupportFirstChargeRuntimeTest';testcase='registrationFacadeCreatesOnlyNewBoundaryAndMissingLegacyHistoryStaysUnknown';scene='SupportFirstChargeRuntimeTest-registrationFacadeCreatesOnlyNewBoundaryAndMissingLegacyHistoryStaysUnknown.json';policy='scenario'}
            @{suite='SupportFirstChargeRuntimeTest';testcase='genuinePurchaseThenDepositPreservesFirstReplayAndHalfOpenEventBoundary';scene='SupportFirstChargeRuntimeTest-genuinePurchaseThenDepositPreservesFirstReplayAndHalfOpenEventBoundary.json';policy='scenario';sourceSha256='2dab20d7b2c3a65af5b0c45a003111c9b4f8234be7b84c4bea51e82658f64591';receiptSha256='e4498223dd2c6b4ac65a0e635b4a7c8e994a3f8cc292f5932cd5e44706cad478'}
            @{suite='SupportFirstChargeRuntimeTest';testcase='genuineDepositThenPurchaseNeverReplacesOriginalFirstOrOwnership';scene='SupportFirstChargeRuntimeTest-genuineDepositThenPurchaseNeverReplacesOriginalFirstOrOwnership.json';policy='scenario';sourceSha256='2dab20d7b2c3a65af5b0c45a003111c9b4f8234be7b84c4bea51e82658f64591';receiptSha256='e4498223dd2c6b4ac65a0e635b4a7c8e994a3f8cc292f5932cd5e44706cad478'}
            @{suite='SupportFirstChargeRuntimeTest';testcase='ownedGiftAndFreeTrialSourceRowsDoNotQualifyBeforeRealPaidCheckout';scene='SupportFirstChargeRuntimeTest-ownedGiftAndFreeTrialSourceRowsDoNotQualifyBeforeRealPaidCheckout.json';policy='scenario';sourceSha256='2dab20d7b2c3a65af5b0c45a003111c9b4f8234be7b84c4bea51e82658f64591';receiptSha256='e4498223dd2c6b4ac65a0e635b4a7c8e994a3f8cc292f5932cd5e44706cad478'}
            @{suite='SupportFirstChargeRuntimeTest';testcase='originalPurchaseRefundAndLaterDepositKeepTheOriginalLifetimeFirst';scene='SupportFirstChargeRuntimeTest-originalPurchaseRefundAndLaterDepositKeepTheOriginalLifetimeFirst.json';policy='scenario';sourceSha256='2dab20d7b2c3a65af5b0c45a003111c9b4f8234be7b84c4bea51e82658f64591';receiptSha256='e4498223dd2c6b4ac65a0e635b4a7c8e994a3f8cc292f5932cd5e44706cad478'}
            @{suite='SupportFirstChargeRuntimeTest';testcase='realUnassignedPaymentAndMissingLegacyBirthKeepDifferentUnknownDimensions';scene='SupportFirstChargeRuntimeTest-realUnassignedPaymentAndMissingLegacyBirthKeepDifferentUnknownDimensions.json';policy='scenario';sourceSha256='2dab20d7b2c3a65af5b0c45a003111c9b4f8234be7b84c4bea51e82658f64591';receiptSha256='e4498223dd2c6b4ac65a0e635b4a7c8e994a3f8cc292f5932cd5e44706cad478'}
            @{suite='SupportAnalyticsAggregateRuntimeTest';testcase='realInjectedFundsReaderKeepsExactTwoCurrencySelectedTotalsAndMissingWalletUnknown';scene='SupportAnalyticsAggregateRuntimeTest-realInjectedFundsReaderKeepsExactTwoCurrencySelectedTotalsAndMissingWalletUnknown.json';policy='scenario'}
            @{suite='SupportAnalyticsAggregateRuntimeTest';testcase='crossGroupFourNodeInvitationIsAggregateOnlyAndCurrentScopeNeverExpands';scene='SupportAnalyticsAggregateRuntimeTest-crossGroupFourNodeInvitationIsAggregateOnlyAndCurrentScopeNeverExpands.json';policy='scenario'}
            @{suite='SupportAnalyticsAggregateRuntimeTest';testcase='currentTransferChangesBothRolesCurrentFundsWithoutMovingOldScopeAuthority';scene='SupportAnalyticsAggregateRuntimeTest-currentTransferChangesBothRolesCurrentFundsWithoutMovingOldScopeAuthority.json';policy='scenario'}
        ) } }
        'I3-B' { return @{ TaskId='support-analytics-backend-20261009-r13'; Dependencies=@('SupportGroupRuntimeFixtures'); Group='analytics-http'; Suites=@('SupportAnalyticsHttpRuntimeTest'); OptIns=@('CS_ANALYTICS_HTTP_ENABLED'); Acceptance=@('BE-API-HANDOFF','BE-G23','BE-G24'); Cases=@(
            @{suite='SupportAnalyticsHttpRuntimeTest';testcase='actualAnalyticsScopesRejectForeignAndForgedIdentityAndKeepOwnerlessEmpty';scene='SupportAnalyticsHttpRuntimeTest-actualAnalyticsScopesRejectForeignAndForgedIdentityAndKeepOwnerlessEmpty.json';policy='scenario'}
            @{suite='SupportAnalyticsHttpRuntimeTest';testcase='realViewAndCurrencyMatrixKeepsUnknownSeparateFromZero';scene='SupportAnalyticsHttpRuntimeTest-realViewAndCurrencyMatrixKeepsUnknownSeparateFromZero.json';policy='scenario'}
            @{suite='SupportAnalyticsHttpRuntimeTest';testcase='oldTokenLosesCustomerReadImmediatelyAfterProductionTransferBeforeStaleVersion';scene='SupportAnalyticsHttpRuntimeTest-oldTokenLosesCustomerReadImmediatelyAfterProductionTransferBeforeStaleVersion.json';policy='scenario'}
            @{suite='SupportAnalyticsHttpRuntimeTest';testcase='readyVersionMustActuallySupportThreePagesAndRejectOwnedWalletMutation';scene='SupportAnalyticsHttpRuntimeTest-readyVersionMustActuallySupportThreePagesAndRejectOwnedWalletMutation.json';policy='scenario'}
        ) } }
        'I4-B' { return @{ TaskId='support-analytics-backend-20261009-r13'; Dependencies=@('SupportSessionProfileContractRuntimeTest'); Group='rules-configuration'; Suites=@('SupportRulesConfigurationRuntimeTest'); OptIns=@('CS_ANALYTICS_RULES_CONTRACT_ENABLED'); Acceptance=@('BE-CFG-01','BE-CFG-02','BE-CFG-03','BE-CFG-04','BE-CFG-05','BE-CFG-06','BE-G10'); Cases=@(
            @{suite='SupportRulesConfigurationRuntimeTest';testcase='persistedRulesStrictInputsAndControlledCasReplayRestoreOriginalBusinessValues';scene='rules-configuration-contract-runtime.json';policy='contract'}
            @{suite='SupportRulesConfigurationRuntimeTest';testcase='actualLostHttpResponseRecoversOwnedCommandBeforeSameKeyRetry';scene='rules-lost-response-runtime.json';policy='contract'}
            @{suite='SupportRulesConfigurationRuntimeTest';testcase='partialGroupSetupRetainsEveryCommittedCommandAndCleansExactRows';scene='rules-partial-group-cleanup-runtime.json';policy='contract'}
            @{suite='SupportRulesConfigurationRuntimeTest';testcase='genuineFacadeRegistrationsHonorZeroTwoUnlimitedDirectInviterCycleAndPolicyEpoch';scene='rules-registration-contract-runtime.json';policy='contract'}
            @{suite='SupportRulesConfigurationRuntimeTest';testcase='canonicalRulesInitializationAndLegacyLimitedValuesReplayInExclusiveOwnedScratch';scene='rules-canonical-migration-runtime.json';policy='contract'}
            @{suite='SupportRulesConfigurationRuntimeTest';testcase='approvedManualUnconfiguredUpgradeUsesActualHttpAuditRecoveryAndSecondRun';scene='rules-manual-upgrade-runtime.json';policy='contract'}
        ) } }
        'I5R-B' { return @{ TaskId='support-analytics-backend-20261009-r13'; Dependencies=@('SupportAnalyticsHttpRuntimeTest','SupportGroupRuntimeFixtures'); Group='leaderboard'; Suites=@('SupportLeaderboardRuntimeTest'); OptIns=@('CS_LEADERBOARD_ENABLED'); Acceptance=@('BE-RANK-01','BE-RANK-02','BE-RANK-03','BE-RANK-04','BE-RANK-05','BE-RANK-06','BE-RANK-07','BE-RANK-08','BE-RANK-09','BE-RANK-10','BE-RANK-11','BE-RANK-12','BE-RANK-13','BE-RANK-14'); Cases=@(
            @{suite='SupportLeaderboardRuntimeTest';testcase='realBoundCountsProduceCompetitionTiesPinnedPagesSelfAndSearch';scene='SupportLeaderboardRuntimeTest-realBoundCountsProduceCompetitionTiesPinnedPagesSelfAndSearch.json';policy='scenario'}
            @{suite='SupportLeaderboardRuntimeTest';testcase='actualAllSourceAndPublicOwnedSummaryNeverGrantForeignObjectRead';scene='SupportLeaderboardRuntimeTest-actualAllSourceAndPublicOwnedSummaryNeverGrantForeignObjectRead.json';policy='scenario'}
            @{suite='SupportLeaderboardRuntimeTest';testcase='realFourBoardsKeepTwoCurrenciesCurrentBindingsAndUnprovedMoneySeparate';scene='SupportLeaderboardRuntimeTest-realFourBoardsKeepTwoCurrenciesCurrentBindingsAndUnprovedMoneySeparate.json';policy='scenario'}
            @{suite='SupportLeaderboardRuntimeTest';testcase='productionTransfersRecomputeOwnedVersionWithoutRewritingAnyPriorPublication';scene='SupportLeaderboardRuntimeTest-productionTransfersRecomputeOwnedVersionWithoutRewritingAnyPriorPublication.json';policy='scenario'}
            @{suite='SupportLeaderboardRuntimeTest';testcase='sameTokenImmediatelyLosesOldGroupAndCustomerAfterOwnedProductionMove';scene='SupportLeaderboardRuntimeTest-sameTokenImmediatelyLosesOldGroupAndCustomerAfterOwnedProductionMove.json';policy='scenario'}
        ) } }
        'I6-B' { return @{ TaskId='support-analytics-backend-20261009-r13'; Dependencies=@(); Group='session-profile'; Suites=@('SupportSessionProfileContractRuntimeTest'); OptIns=@('CS_ANALYTICS_SESSION_CONTRACT_ENABLED'); Acceptance=@('BE-M3-CONTRACT'); Cases=@(
            @{suite='SupportSessionProfileContractRuntimeTest';testcase='messageRecoveryAndRealReadReceiptsStaySeparateAndOldScopeIsRevoked';scene='session-message-contract-runtime.json';policy='contract'}
            @{suite='SupportSessionProfileContractRuntimeTest';testcase='completeOwnedDevicePagesAndProfileUnknownsDoNotBecomeInventedZeros';scene='session-profile-contract-runtime.json';policy='contract'}
        ) } }
        default { return $null }
    }
}
function Test-NativeScene([object]$Document,[object]$Case,[string]$Run,[string]$Snapshot) {
    if ($null -eq $Document -or $Document.workflowRunId -cne $Run -or $Document.snapshotHash -cne $Snapshot -or
        $Document.suite -cne $Case.suite -or $Document.testcase -cne $Case.testcase) { return $false }
    if ($Case.policy -ceq 'scenario') {
        return $Document.capability -ceq 'runtime' -and $Document.status -ceq 'scenario-assertions-passed' -and
            $Document.acceptanceCoverage -ceq 'PARTIAL_NO_PHASE_SIGNOFF' -and ![string]::IsNullOrWhiteSpace($Document.assertions) -and $Document.cleanupComplete -is [bool] -and $Document.cleanupComplete
    }
    if ($Case.policy -ceq 'contract') {
        return $Document.acceptanceStatus -ceq 'PARTIAL' -and $Document.cleanup -ceq 'EXACT_OWNED_ROWS_ONLY' -and
            $null -ne $Document.observations -and @($Document.exactFactReceipts).Count -gt 0 -and $Document.exactFactsCleaned -is [bool] -and $Document.exactFactsCleaned
    }
    return $false
}
function Get-NativeCaseSourceBinding([object]$Case,[object[]]$Sources,[string]$Repository) {
    if(!$Case.sourceSha256){return $null}
    if($Case.suite -cne 'SupportFirstChargeRuntimeTest' -or $Case.policy -cne 'scenario' -or $Case.sourceSha256 -cnotmatch '^[a-f0-9]{64}$' -or $Case.receiptSha256 -cnotmatch '^[a-f0-9]{64}$'){throw 'Exact first-charge case source contract missing'}
    $bindings=@{}
    foreach($spec in @(@{name=$Case.suite;sha=$Case.sourceSha256;alias='suite'},@{name='SupportFirstChargeCommandReceipt';sha=$Case.receiptSha256;alias='commandReceipt'})){
        $path=[IO.Path]::GetFullPath("$Repository/src/test/java/ffdd/opsconsole/content/application/$($spec.name).java")
        $matches=@($Sources|Where-Object {$null -ne $_.path -and [IO.Path]::GetFullPath($_.path) -ceq $path})
        if($matches.Count -ne 1 -or $matches[0].sha256 -cne $spec.sha){throw "Exact first-charge case source SHA missing/mismatched: $($spec.name)"}
        $bindings[$spec.alias]=$matches[0]
    }
    return $bindings
}
function New-NativeSceneReference([object]$Document,[object]$Case,[object]$SceneReference,[object[]]$Sources,[string]$Repository,[string]$Run,[string]$Snapshot) {
    if(!(Test-NativeScene $Document $Case $Run $Snapshot)){throw "Exact scenario identity/partial cleanup metadata missing: $($Case.scene)"}
    $result=@{suite=$Case.suite;testcase=$Case.testcase;policy=$Case.policy;reference=$SceneReference;phaseSignoff=$false}
    if($Case.sourceSha256){
        $result.sourceBinding=Get-NativeCaseSourceBinding $Case $Sources $Repository
        # These are existing real scene fields, not invented Java producer metadata or an acceptance certificate.
        $result.policyMetadata=@{capability=$Document.capability;status=$Document.status;acceptanceCoverage=$Document.acceptanceCoverage;assertions=$Document.assertions;cleanupComplete=$Document.cleanupComplete}
    }
    return $result
}
function New-NativePartialRecord([hashtable]$Context,[hashtable]$Inventory,[object[]]$Suites,[object[]]$Scenes,[object[]]$Issues,[hashtable]$References) {
    if ($Context.taskId -cne $Inventory.TaskId -or !(Test-WorkflowBinding $Context.stepId $Context.checkId $Context.sourcePhase $Inventory.Group)) { throw 'Partial native report workflow binding mismatch' }
    # Scenario XML/scene success is deliberately not a per-acceptance proof or independent phase signoff.
    $steps=@(foreach($id in $Inventory.Acceptance){@{id=$id;status='fail';evidence=@('Native scene coverage is partial; exact per-ID coverage and independent signoff are missing. No phase PASS is authorized.')}})
    $result=[ordered]@{at=[DateTime]::UtcNow.ToString('o');taskId=$Context.taskId;stepId=$Context.stepId;sourcePhase=$Context.sourcePhase;checkId=$Context.checkId;runId=$Context.runId;repo=$Context.repo;snapshotHash=$Context.snapshotHash;candidate=$Context.candidate;verdict='fail';mode='partial';capability='runtime';suiteGroup=$Inventory.Group;steps=$steps;suites=@($Suites);scenes=@($Scenes);executionIssues=@($Issues);acceptanceCoverage='PARTIAL_NO_PHASE_SIGNOFF';phaseSigned=$false;missingProofs=@('EXACT_PER_ACCEPTANCE_NATIVE_COVERAGE','INDEPENDENT_CURRENT_PHASE_SIGNOFF');scenarioAssertionsValidated=($Issues.Count -eq 0)}
    foreach($key in $References.Keys){if($key -cnotin @('before','after','objectBefore','objectAfter','sharedRestoration','actorContext','rawArtifacts','ownership','treeMoved','nativeSources','tableComparison')){throw 'Reference cannot overwrite native report/signoff fields'};$result[$key]=$References[$key]}
    return $result
}

function Get-OriginalAvatarSuites([string]$File) {
    $tokens=$null;$errors=$null;$tree=[Management.Automation.Language.Parser]::ParseFile($File,[ref]$tokens,[ref]$errors)
    if($errors.Count){throw 'Avatar original inventory syntax error'}
    $calls=@($tree.FindAll({param($node) $node -is [Management.Automation.Language.CommandAst] -and $node.CommandElements.Count -eq 3 -and $node.CommandElements[0].Extent.Text -ceq 'Run-Suites' -and $node.CommandElements[1].Value -ceq 'avatar'},$true))
    if($calls.Count -ne 1){throw 'Exactly one original complete avatar inventory required'}
    $argument=$calls[0].CommandElements[2]
    if(@($argument.FindAll({param($node) $node -is [Management.Automation.Language.VariableExpressionAst] -or $node -is [Management.Automation.Language.CommandAst]},$true)).Count){throw 'Original avatar suites must be literal'}
    return @($argument.FindAll({param($node) $node -is [Management.Automation.Language.StringConstantExpressionAst]},$true)|ForEach-Object Value)
}
function Get-AvatarPartition([string[]]$Original,[string]$Group) {
    $groups=@('avatar-a2','avatar-read','avatar-compensation','avatar-self')
    $spec=@{
        'avatar-a2'=@{suite='SupportAdminAvatarA2RuntimeTest';method='makerAvatarUsesRealTwoPersonApprovalAndRejectedAttemptsRemainAtomic';scene='avatar-a2-runtime-*.json';index=4}
        'avatar-read'=@{suite='SupportAdminAvatarReadRuntimeTest';method='controlledAvatarReadScopesAndReplacement';scene='avatar-read-runtime.json';index=5}
        'avatar-compensation'=@{suite='SupportAvatarCompensationRuntimeTest';method='committedAvatarSurvivesUnknownCallbackAndRollbackStillCompensates';scene='avatar-compensation-runtime.json';index=6}
        'avatar-self'=@{suite='SupportDefaultAvatarSelfRuntimeTest';method='defaultCreationAndAvatarOnlySelfCommandsPersistAndFailuresPreserveOriginal';scene='default-avatar-self-runtime.json';index=7}
    }
    $expected=@($groups|ForEach-Object {$spec[$_].suite})
    if($Original.Count -ne 4 -or @($Original|Sort-Object -Unique).Count -ne 4 -or @(Compare-Object $Original $expected -CaseSensitive).Count){throw 'Original four avatar suites missing/duplicated/changed'}
    if($Group -cnotin $groups){throw 'Unknown avatar partition'}
    $proofIds=switch -CaseSensitive($Group){
        'avatar-a2' {@('avatar-a2-self-approval','avatar-a2-create-and-retry','avatar-a2-update-by-distinct-checker-and-retry','avatar-a2-checker-and-third-party-ownership','avatar-a2-stale-account-cas','avatar-a2-expired-ready-asset','avatar-a2-cancelled-asset','avatar-a2-old-attached-asset-cannot-rebind','avatar-a2-storage-failure-rolls-back-create-and-update','avatar-a2-proposal-target-mismatch','avatar-a2-persisted-ticket-target-tamper')}
        'avatar-read' {@('avatar-service-read','avatar-roster-scope','avatar-history','avatar-transfer','avatar-a1-boundary','avatar-version','avatar-subject')}
        'avatar-compensation' {@('avatar-compensation')}
        'avatar-self' {@('default-avatar-self')}
    }
    $p=$spec[$Group];return @{group=$Group;Suites=@($Original|Where-Object {$_ -ceq $p.suite});Methods=@($p.method);ScenePattern=$p.scene;ProofIds=@($proofIds);originalSuiteNames=$Original;originalSuiteCount=4;originalSceneCount=4;wholeAvatarExecuted=$false;requiredGroups=$groups;integrationCheckId="step-0-check-$($p.index)"}
}
function Test-AvatarScene([object]$Document,[hashtable]$Partition,[string]$Run,[string]$Snapshot) {
    if($null -eq $Document -or $Document.workflowRunId -cne $Run -or $Document.snapshotHash -cne $Snapshot -or $Document.database -cne 'cs_analytics_20261007'){return $false}
    $properties=@($Document.checks.PSObject.Properties)
    if($properties.Count -ne $Partition.ProofIds.Count -or @(Compare-Object @($properties|ForEach-Object Name) $Partition.ProofIds -CaseSensitive).Count){return $false}
    foreach($proof in $properties){if($proof.Value.status -cne 'pass' -or $proof.Value.suite -cne $Partition.Suites[0] -or $proof.Value.testcase -cne $Partition.Methods[0]){return $false}}
    if($Partition.group -ceq 'avatar-a2'){return $Document.capability -ceq 'runtime' -and $Document.cleanup -ceq 'pass' -and $Document.databasePort -eq 33337 -and $Document.httpPort -eq 18161}
    return $Document.port -eq 18161
}
function Test-AvatarPartitionSet([object[]]$Reports,[string]$Run,[string]$Snapshot,[string]$Candidate) {
    $groups=@('avatar-a2','avatar-read','avatar-compensation','avatar-self')
    if($Reports.Count -ne 4 -or @($Reports|ForEach-Object suiteGroup|Sort-Object -Unique).Count -ne 4){return $false}
    foreach($r in $Reports){
        if($r.suiteGroup -cnotin $groups -or $r.taskId -cne 'support-analytics-backend-20261009-r13' -or $r.runId -cne $Run -or $r.snapshotHash -cne $Snapshot -or $r.candidate -cne $Candidate -or [string]::IsNullOrWhiteSpace($r.repo) -or $r.repo.Replace('\','/') -cne 'D:/WORKS/PLAN/.wt/cs-analytics-api-20261007' -or $r.stepId -cne $Reports[0].stepId -or $r.verdict -cne 'pass' -or $r.mode -cne 'full' -or $r.treeMoved -isnot [bool] -or $r.treeMoved -ne $false -or $r.phaseSigned -isnot [bool] -or $r.phaseSigned -ne $false -or $r.acceptanceCoverage -cne 'PARTIAL_PIECE_NO_BASELINE_SIGNOFF'){return $false}
        $p=Get-AvatarPartition @('SupportAdminAvatarReadRuntimeTest','SupportAvatarCompensationRuntimeTest','SupportAdminAvatarA2RuntimeTest','SupportDefaultAvatarSelfRuntimeTest') $r.suiteGroup
        if(@($r.avatarCoverage.originalSuiteNames).Count -ne 4 -or @(Compare-Object @($r.avatarCoverage.originalSuiteNames) $p.originalSuiteNames -CaseSensitive).Count){return $false}
        if(!(Test-WorkflowBinding $r.stepId $r.checkId 'I0-B' $r.suiteGroup $r.taskId) -or $r.sourcePhase -cne 'I0-B' -or $r.avatarCoverage.wholeAvatarExecuted -isnot [bool] -or $r.avatarCoverage.wholeAvatarExecuted -or $r.avatarCoverage.originalSuiteCount -ne 4 -or $r.avatarCoverage.originalSceneCount -ne 4 -or @($r.avatarCoverage.suiteNames).Count -ne 1 -or $r.avatarCoverage.suiteNames[0] -cne $p.Suites[0] -or @($r.avatarCoverage.sceneNames).Count -ne 1 -or $r.avatarCoverage.sceneNames[0] -cnotlike $p.ScenePattern -or @($r.avatarCoverage.requiredGroups).Count -ne 4 -or @(Compare-Object @($r.avatarCoverage.requiredGroups) $groups -CaseSensitive).Count){return $false}
        $selected=@($r.suites|Where-Object group -CEQ $r.suiteGroup)
        if($selected.Count -ne 1 -or $selected[0].suite -cne $p.Suites[0] -or $selected[0].tests -ne 1 -or $selected[0].skipped -ne 0 -or $selected[0].errors -ne 0 -or $selected[0].failures -ne 0 -or @($r.avatarCoverage.sourceReferences).Count -ne 1){return $false}
        if([string]::IsNullOrWhiteSpace($r.avatarCoverage.sourceReferences[0].path) -or !$r.avatarCoverage.sourceReferences[0].path.EndsWith("/$($p.Suites[0]).java") -and !$r.avatarCoverage.sourceReferences[0].path.EndsWith("\$($p.Suites[0]).java") -or $r.avatarCoverage.sourceReferences[0].sha256 -cnotmatch '^[a-f0-9]{64}$'){return $false}
        foreach($key in @('before','after','objectBefore','objectAfter','actorContext','sharedRestoration')){if([string]::IsNullOrWhiteSpace($r.$key.path) -or $r.$key.sha256 -cnotmatch '^[a-f0-9]{64}$'){return $false}}
    }
    return $true # complete current coverage only; independent baseline signoff is still required.
}
function Read-CandidateTreeState([string]$ExpectedHead,[string]$ExpectedDiff,[string]$ExpectedStatus,[scriptblock]$Reader) {
    try {
        $results=@();foreach($arguments in @(@('rev-parse','HEAD'),@('diff','HEAD','--binary'),@('status','--porcelain=v1'))){
            $value=& $Reader $arguments
            if($null -eq $value -or $null -eq $value.exitCode -or $value.exitCode -ne 0 -or $null -eq $value.output){throw 'Git candidate readback missing/failed'}
            $results+=@($value)
        }
        $head=[string]$results[0].output
        if([string]::IsNullOrWhiteSpace($head)){throw 'Git candidate HEAD is empty'}
        return @{treeMoved=($head.Trim() -cne $ExpectedHead -or [string]$results[1].output -cne $ExpectedDiff -or [string]$results[2].output -cne $ExpectedStatus);issue=$null}
    } catch {return @{treeMoved=$true;issue=$_.Exception.Message}}
}

$nativeInventory=Get-NativePhaseInventory $Phase
if($nativeInventory -and $env:WORKFLOW_TASK_ID -cne $nativeInventory.TaskId){throw 'Exact current r13 task identity required for new native phases'}
if ($Phase -eq 'I0-B' -and [string]::IsNullOrWhiteSpace($SuiteGroup)) { throw 'I0-B requires its original explicit suite group' }
if ($Phase -ne 'I0-B') {
    if (-not [string]::IsNullOrWhiteSpace($SuiteGroup)) { throw 'SuiteGroup is reserved for I0-B' }
    $SuiteGroup = switch ($Phase) { 'I1-A' { 'groups' } 'I1-B' { 'group-scope' } 'I2-A' { 'payment-facts' } 'I2-B' { 'first-charge-aggregate' } 'I3-B' { 'analytics-http' } 'I4-B' { 'rules-configuration' } 'I5R-B' { 'leaderboard' } 'I6-B' { 'session-profile' } }
}
if (!(Test-WorkflowBinding $env:WORKFLOW_STEP_ID $env:WORKFLOW_CHECK_ID $Phase $SuiteGroup $env:WORKFLOW_TASK_ID) -or $env:WORKFLOW_RUN_ID -notmatch '^[a-zA-Z0-9_-]+$' -or $env:WORKFLOW_SNAPSHOT_HASH -notmatch '^[a-f0-9]{64}$') { throw 'Current workflow binding required' }
if ([IO.Path]::GetFullPath($env:WORKFLOW_REPO) -ne [IO.Path]::GetFullPath($repo)) { throw 'Workflow repository mismatch' }
$avatarPartition=$null
if($SuiteGroup -cin @('avatar-a2','avatar-read','avatar-compensation','avatar-self')){$avatarPartition=Get-AvatarPartition @(Get-OriginalAvatarSuites $PSCommandPath) $SuiteGroup}
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
function Get-CorePartition([string[]]$Core, [string[]]$Scenes, [string]$Group) {
    if ($Core.Count -ne 27 -or $Scenes.Count -ne 24 -or @($Core | Sort-Object -Unique).Count -ne 27 -or @($Scenes | Sort-Object -Unique).Count -ne 24) {
        throw 'Complete original core inventory changed or contains duplicates'
    }
    # Reuse the original runtime/remaining partition; never maintain a second inventory.
    $runtimeSuites=@($Core | Where-Object { $_ -ceq 'SupportEnhancementCoreRuntimeTest' })
    $remainingSuites=@($Core | Where-Object { $_ -cne 'SupportEnhancementCoreRuntimeTest' })
    $runtimeScenes=@($Scenes | Where-Object { $_ -notlike 'legacy-*/*' -and $_ -cne 'preparation-runtime.json' })
    $remainingScenes=@($Scenes | Where-Object { $_ -like 'legacy-*/*' -or $_ -ceq 'preparation-runtime.json' })
    if ($runtimeSuites.Count -ne 1 -or $remainingSuites.Count -ne 26 -or $runtimeScenes.Count -ne 14 -or $remainingScenes.Count -ne 10 -or
        @(Compare-Object (@($runtimeSuites)+@($remainingSuites)) $Core -CaseSensitive).Count -or
        @(Compare-Object (@($runtimeScenes)+@($remainingScenes)) $Scenes -CaseSensitive).Count) {
        throw 'Original core partition is incomplete'
    }
    switch ($Group) {
        core { return @{Suites=$Core;Scenes=$Scenes} }
        core-runtime { return @{Suites=$runtimeSuites;Scenes=$runtimeScenes} }
        core-remaining { return @{Suites=$remainingSuites;Scenes=$remainingScenes} }
        default { throw 'Unknown core partition' }
    }
}
$nativeSources=@()
if($nativeInventory){
    foreach($suite in @(@($nativeInventory.Suites)+@($nativeInventory.Dependencies)|Sort-Object -Unique)){
        $file=Get-Item -LiteralPath "$repo/src/test/java/ffdd/opsconsole/content/application/$suite.java"
        if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Native source must be a physical current repository file'}
        if($suite -cin $nativeInventory.Suites){
            $expected=@($nativeInventory.Cases|Where-Object suite -CEQ $suite|ForEach-Object testcase)
            $actual=@([regex]::Matches((Get-Content -Raw -LiteralPath $file.FullName),'@Test\s+void\s+(\w+)\s*\(')|ForEach-Object {$_.Groups[1].Value})
            if($actual.Count -ne $expected.Count -or @(Compare-Object $actual $expected -CaseSensitive).Count){throw "Current native testcase inventory mismatch: $suite"}
        }
        $nativeSources+=Reference $file.FullName
    }
    foreach($case in $nativeInventory.Cases){if($case.sourceSha256){$null=Get-NativeCaseSourceBinding $case $nativeSources $repo}}
}
if($avatarPartition){
    foreach($suite in $avatarPartition.Suites){
        $file=Get-Item -LiteralPath "$repo/src/test/java/ffdd/opsconsole/content/application/$suite.java"
        if($file.Attributes -band [IO.FileAttributes]::ReparsePoint){throw 'Avatar partition source must be physical'}
        $methods=@([regex]::Matches((Get-Content -Raw -LiteralPath $file.FullName),'@Test\s+void\s+(\w+)\s*\(')|ForEach-Object {$_.Groups[1].Value})
        if($methods.Count -ne 1 -or @(Compare-Object $methods $avatarPartition.Methods -CaseSensitive).Count){throw 'Original avatar testcase inventory changed'}
        $nativeSources+=Reference $file.FullName
    }
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
$env:SUPPORT_PATCH_ISOLATED='true'; $env:CS_ENHANCE_BULK_ENABLED='true'; $env:CS_ENHANCE_AVATAR_READ_ENABLED='true'; $env:CS_ENHANCE_AVATAR_A2_ENABLED='true'; $env:CS_ENHANCE_DEFAULT_AVATAR_SELF_ENABLED='true'
$env:CS_ANALYTICS_GROUPS_ENABLED=if($Phase -eq 'I1-A'){'true'}else{'false'}
$env:CS_ANALYTICS_GROUP_SCOPE_ENABLED=if($Phase -eq 'I1-B'){'true'}else{'false'}
$env:CS_ANALYTICS_PAYMENT_FACTS_ENABLED=if($Phase -eq 'I2-A'){'true'}else{'false'}
foreach($flag in @('CS_ANALYTICS_AGGREGATE_ENABLED','CS_ANALYTICS_FIRST_ENABLED','CS_ANALYTICS_HTTP_ENABLED','CS_ANALYTICS_RULES_CONTRACT_ENABLED','CS_ANALYTICS_SESSION_CONTRACT_ENABLED','CS_LEADERBOARD_ENABLED')){ [Environment]::SetEnvironmentVariable($flag,'false') }
if($nativeInventory){foreach($flag in $nativeInventory.OptIns){[Environment]::SetEnvironmentVariable($flag,'true')}}
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
    if ($Group -like 'core*' -and @(Get-ChildItem -LiteralPath $directory -Filter 'TEST-*.xml' -File).Count -ne $Suites.Count) { throw "Unexpected core suite report inventory: $Group" }
    foreach ($suite in $Suites) {
        $files=@(Get-ChildItem -LiteralPath $directory -Filter "TEST-*.$suite.xml" -File)
        if ($files.Count -ne 1 -or $files[0].LastWriteTimeUtc -lt $started) { throw "Current report missing: $suite" }
        [xml]$xml=Get-Content -Raw -LiteralPath $files[0].FullName; $result=$xml.testsuite
        if ([int]$result.tests -le 0 -or [int]$result.failures -ne 0 -or [int]$result.errors -ne 0 -or [int]$result.skipped -ne 0 -or @($result.testcase).Count -ne [int]$result.tests) { throw "Suite incomplete: $suite" }
        if($avatarPartition -and $Group -ceq $avatarPartition.group){
            if($Suites.Count -ne 1 -or @(Get-ChildItem -LiteralPath $directory -Filter 'TEST-*.xml' -File).Count -ne 1 -or [int]$result.tests -ne 1 -or @($result.testcase)[0].name -cne $avatarPartition.Methods[0]){throw 'Avatar partition exact original suite/XML testcase mismatch'}
        }
        $suiteEvidence.Add(@{group=$Group;suite=$suite;tests=[int]$result.tests;failures=0;errors=0;skipped=0;report=(Reference $files[0].FullName)})
    }
    Write-Output "group=$Group status=pass suites=$($Suites.Count)"
}
$gitReader={param([string[]]$Arguments) $output=& git @Arguments;@{exitCode=$LASTEXITCODE;output=if($null -eq $output){''}else{($output|Out-String)}}}
$core=@(Inventory "$PSScriptRoot/support-enhancements-check.ps1" 'suites')
$scenes=@(Inventory "$PSScriptRoot/support-enhancements-check.ps1" 'sceneNames')
$corePartition=Get-CorePartition $core $scenes 'core'
if ($SuiteGroup -like 'core*') { $corePartition=Get-CorePartition $core $scenes $SuiteGroup; $scenes=@($corePartition.Scenes) }
$started=[DateTime]::UtcNow
if ($nativeInventory -or $avatarPartition) {
    $nativeIssues=[Collections.Generic.List[object]]::new()
    $env:CS_ENHANCE_ACTOR_CONTEXT="$evidence/actor-context.json"
    $env:CS_ENHANCE_ACTOR_CONTEXT_SHA256=''
    try {
        $env:SUPPORT_ANALYTICS_CAPTURE='before';Run-Suites 'before' @('SupportAnalyticsCaptureTest')
        $env:SUPPORT_ANALYTICS_CAPTURE='';$env:CS_ENHANCE_ACTOR_CONTEXT_SHA256=Hash $env:CS_ENHANCE_ACTOR_CONTEXT
        if($avatarPartition){Run-Suites $SuiteGroup @($avatarPartition.Suites)}else{Run-Suites $SuiteGroup @($nativeInventory.Suites)}
    } catch { $nativeIssues.Add(@{stage='before-or-native';error=$_.Exception.Message}) }
    finally {
        try { $env:SUPPORT_ANALYTICS_CAPTURE='after';Run-Suites 'after' @('SupportAnalyticsCaptureTest') }
        catch { $nativeIssues.Add(@{stage='after';error=$_.Exception.Message}) }
        finally { $env:SUPPORT_ANALYTICS_CAPTURE='' }
    }
} else {
$started=[DateTime]::UtcNow
$env:SUPPORT_ANALYTICS_CAPTURE='before'
Run-Suites 'before' @('SupportAnalyticsCaptureTest')
$env:SUPPORT_ANALYTICS_CAPTURE=''
$env:CS_ENHANCE_ACTOR_CONTEXT="$evidence/actor-context.json"
$env:CS_ENHANCE_ACTOR_CONTEXT_SHA256=Hash $env:CS_ENHANCE_ACTOR_CONTEXT
try {
    switch ($SuiteGroup) {
        core { Run-Suites 'core' $core }
        core-runtime { Run-Suites 'core-runtime' @($corePartition.Suites) }
        core-remaining { Run-Suites 'core-remaining' @($corePartition.Suites) }
        bulk {
            Run-Suites 'bulk' @('SupportHumanMessageServiceTest','OpsConversationServiceTest','SupportAttachmentServiceTest','SupportMaintenanceServiceTest','SupportBulkRuntimeTest')
            # The persisted queue is consumed in a genuinely different Surefire JVM.
            Run-Suites 'bulk-restart' @('SupportBulkRestartRuntimeTest')
        }
        avatar { Run-Suites 'avatar' @('SupportAdminAvatarReadRuntimeTest','SupportAvatarCompensationRuntimeTest','SupportAdminAvatarA2RuntimeTest','SupportDefaultAvatarSelfRuntimeTest') }
        groups { Run-Suites 'groups' @('SupportGroupServiceTest','SupportGroupMapperSqlTest','MybatisSupportAgentRepositoryTest','OpsSupportAgentControllerTest','OpsAdminAccountServiceTest','SupportGroupMigrationRuntimeTest','SupportGroupRuntimeTest') }
        group-scope { Run-Suites 'group-scope' @('SupportOwnershipScopeTest','SupportScopeSqlContractTest','SupportContentScopeSqlTest','SupportScopedCustomerSqlContractTest','SupportBulkScopeTest','OpsSupportCommandScopeTest','SupportAdminAvatarScopeTest','GlobalExceptionHandlerTest','OpsSupportTicketControllerTest','SupportGroupScopeRuntimeTest') }
        payment-facts { Run-Suites 'payment-facts' @('SupportPaymentFactServiceTest','SupportPaymentFactMapperSqlTest','SupportPaymentFactRuntimeTest') }
    }
} finally {
    $env:SUPPORT_ANALYTICS_CAPTURE='after'
    Run-Suites 'after' @('SupportAnalyticsCaptureTest')
    $env:SUPPORT_ANALYTICS_CAPTURE=''
}
}
if ($nativeInventory) {
    $nativeScenes=[Collections.Generic.List[object]]::new();$nativeReferences=@{}
    try { $nativeReferences['tableComparison']=Compare-SharedCaptures (Get-Content -Raw "$evidence/shared-before.json" | ConvertFrom-Json) (Get-Content -Raw "$evidence/shared-after.json" | ConvertFrom-Json) }
    catch { $nativeIssues.Add(@{stage='shared-capture-format';error=$_.Exception.Message}) }
    foreach($case in $nativeInventory.Cases){
        try{
            $file=Get-Item -LiteralPath "$evidence/$($case.scene)"
            if($file.LastWriteTimeUtc -lt $started -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw "Stale or indirect scene: $($case.scene)"}
            $document=Get-Content -Raw -LiteralPath $file.FullName | ConvertFrom-Json -DateKind String
            $nativeScenes.Add((New-NativeSceneReference $document $case (Reference $file.FullName) $nativeSources $repo $env:WORKFLOW_RUN_ID $env:WORKFLOW_SNAPSHOT_HASH))
        }catch{$nativeIssues.Add(@{stage='scene';scene=$case.scene;error=$_.Exception.Message})}
    }
    foreach($artifact in @('shared-before.json','shared-after.json','object-before.json','object-after.json','shared-restoration.json','actor-context.json')){
        try{
            $file=Get-Item -LiteralPath "$evidence/$artifact"
            if($file.LastWriteTimeUtc -lt $started -or ($file.Attributes -band [IO.FileAttributes]::ReparsePoint)){throw "Stale or indirect capture: $artifact"}
            $alias=switch -CaseSensitive($artifact){'shared-before.json'{'before'}'shared-after.json'{'after'}'object-before.json'{'objectBefore'}'object-after.json'{'objectAfter'}'shared-restoration.json'{'sharedRestoration'}'actor-context.json'{'actorContext'}}
            $nativeReferences[$alias]=Reference $file.FullName
        }catch{$nativeIssues.Add(@{stage='capture-reference';artifact=$artifact;error=$_.Exception.Message})}
    }
    try{
        $restored=Get-Content -Raw -LiteralPath "$evidence/shared-restoration.json" | ConvertFrom-Json
        if($restored.complete -ne $true -or $restored.rulesBusinessValuesEqual -ne $true -or $restored.contextSha256 -cne $env:CS_ENHANCE_ACTOR_CONTEXT_SHA256){throw 'Exact shared restoration was not verified'}
    }catch{$nativeIssues.Add(@{stage='shared-restoration';error=$_.Exception.Message})}
    $candidateState=Read-CandidateTreeState $env:SUPPORT_CANDIDATE $initialTree $initialStatus $gitReader
    $treeMoved=$candidateState.treeMoved
    if($candidateState.issue){$nativeIssues.Add(@{stage='candidate-readback';error=$candidateState.issue})}elseif($treeMoved){$nativeIssues.Add(@{stage='candidate';error='Candidate changed during runtime checks'})}
    # Include hashes of raw failed XML/log/secondary receipts; never copy credential contents into the formal record.
    $raw=@();$rawFiles=@()
    try{$rawFiles=@(Get-ChildItem -LiteralPath $evidence -Recurse -File | Where-Object { ($_.Extension -in @('.json','.xml','.log') -or $_.Name.EndsWith('.json.gz',[StringComparison]::OrdinalIgnoreCase)) -and !($_.Attributes -band [IO.FileAttributes]::ReparsePoint) })}catch{$nativeIssues.Add(@{stage='raw-artifact-inventory';error=$_.Exception.Message})}
    foreach($file in $rawFiles){try{$raw+=Reference $file.FullName}catch{$nativeIssues.Add(@{stage='raw-artifact-reference';path=$file.FullName;error=$_.Exception.Message})}}
    foreach($source in $nativeSources){try{if((Hash $source.path) -cne $source.sha256){throw 'Native source changed during runtime'}}catch{$nativeIssues.Add(@{stage='native-source';path=$source.path;error=$_.Exception.Message})}}
    $nativeReferences['nativeSources']=@($nativeSources)
    $nativeReferences['rawArtifacts']=$raw
    try{$nativeReferences['ownership']=Reference $env:SUPPORT_RESOURCE_OWNERSHIP}catch{$nativeIssues.Add(@{stage='ownership-reference';error=$_.Exception.Message})}
    $nativeReferences['treeMoved']=$treeMoved
    $context=@{taskId=$env:WORKFLOW_TASK_ID;stepId=$env:WORKFLOW_STEP_ID;sourcePhase=$Phase;checkId=$env:WORKFLOW_CHECK_ID;runId=$env:WORKFLOW_RUN_ID;repo=$env:WORKFLOW_REPO;snapshotHash=$env:WORKFLOW_SNAPSHOT_HASH;candidate=$env:SUPPORT_CANDIDATE}
    New-NativePartialRecord $context $nativeInventory @($suiteEvidence.ToArray()) @($nativeScenes.ToArray()) @($nativeIssues.ToArray()) $nativeReferences | ConvertTo-Json -Depth 14 | Set-Content -LiteralPath $Report -Encoding utf8
    Write-Output "step=$Phase status=partial phase-signoff=fail report=$Report"
    throw 'New native phase retained raw before/tests/after evidence; formal signoff refused because exact per-ID coverage and independent current-phase signoff are not implemented'
}
if($avatarPartition){
    # Preserve primary native/before/after failure before a missing scene can obscure it.
    if($nativeIssues.Count){throw "Avatar partition before/native/after failed; raw evidence retained: $(@($nativeIssues|ForEach-Object { "$($_.stage): $($_.error)" }) -join ' | ')"}
    $matches=@(Get-ChildItem -LiteralPath $evidence -Filter $avatarPartition.ScenePattern -File)
    if($matches.Count -ne 1){throw 'Exactly one selected original avatar scene required'}
    $scenes=@($matches[0].Name)
}
if ($SuiteGroup -eq 'bulk') { $scenes=@('bulk-runtime.json','bulk-restart-runtime.json','bulk-restart-seed.json') }
if ($Phase -eq 'I1-A') { $scenes=@('groups-runtime.json','groups-migration-runtime.json') }
if ($Phase -eq 'I1-B') { $scenes=@('group-scope-runtime.json') }
if ($Phase -eq 'I2-A') { $scenes=@('payment-facts-runtime.json') }
if ($SuiteGroup -eq 'avatar') {
    $scenes=@('avatar-read-runtime.json','avatar-compensation-runtime.json','default-avatar-self-runtime.json')
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
    if($avatarPartition -and !(Test-AvatarScene $document $avatarPartition $env:WORKFLOW_RUN_ID $env:WORKFLOW_SNAPSHOT_HASH)){throw 'Selected original avatar proof metadata mismatch'}
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
$tableComparison=Compare-SharedCaptures (Get-Content -Raw "$evidence/shared-before.json" | ConvertFrom-Json) (Get-Content -Raw "$evidence/shared-after.json" | ConvertFrom-Json)
$sceneEvidence+=Reference "$evidence/shared-restoration.json"
if ($SuiteGroup -in @('core','core-remaining')) {
    $cleaned=Get-Content -Raw -LiteralPath "$evidence/preparation-cleanup.json" | ConvertFrom-Json
    if ($cleaned.complete -ne $true -or $cleaned.contextSha256 -cne $env:CS_ENHANCE_ACTOR_CONTEXT_SHA256) { throw 'Exact preparation cleanup was not verified' }
    $sceneEvidence+=Reference "$evidence/preparation-cleanup.json"
}
if($avatarPartition){
    foreach($source in $nativeSources){if((Hash $source.path) -cne $source.sha256){throw 'Avatar source changed during runtime'}}
    $candidateState=Read-CandidateTreeState $env:SUPPORT_CANDIDATE $initialTree $initialStatus $gitReader
    $treeMoved=$candidateState.treeMoved
    if($candidateState.issue){throw "Avatar candidate readback failed; raw evidence retained: $($candidateState.issue)"}
}else{
$treeMoved=((& git rev-parse HEAD).Trim() -ne $env:SUPPORT_CANDIDATE -or (& git diff HEAD --binary | Out-String) -cne $initialTree -or (& git status --porcelain=v1 | Out-String) -cne $initialStatus)
}
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
    $handover=$scope.proofs.roleRevokedScopedHandover
    if($handover.eligibleBefore -ne 1 -or $handover.eligibleRevoked -ne 0 -or $handover.eligibleRestored -ne 1){throw 'Current-role handover eligibility proof missing'}
    foreach($field in @('scopeCountAndPaging','otherGroupAndSharedRolesUnchanged','qualificationAndBindingsUnchanged','foreignGroupDenied')) {
        if($handover.$field -ne $true){throw "Current-role handover proof missing: $field"}
    }
    if(@($scope.requests).Count -lt 40){throw 'Authenticated current-scope request matrix incomplete'}
    foreach($field in @('ownerChangedReadback','oldTokenAndUrlDenied','socketWatchAndTypingDenied','sseInvalidated','dualPersonalStillReadable','managementHasNoPersonalRows','oldTicketAndAttachmentDenied','oldBulkSummaryAndRecipientsDenied','frozenAssignmentRejected','sseCurrentMessagesFiltered','socketCurrentEventsFiltered','searchRechecksCurrentScope')) {
        if($scope.proofs.'BE-G24-OBJECTS'.$field -ne $true){throw "Object revocation proof missing: $field"}
    }
}
$observed=switch ($SuiteGroup) {
    core { 'All existing 27 core suites and 24 core scenes executed on the verified exclusive resources; random assignment, finance/profile reads, messaging, and existing permission behavior were exercised.' }
    core-runtime { 'Original core runtime partition executed: 1 of 27 suites and 14 of 24 scenes, with its own before/after database and object readback. Complete core acceptance also requires the current core-remaining partition.' }
    core-remaining { 'Original core remaining partition executed: 26 of 27 suites and 10 of 24 scenes, with preparation cleanup and its own before/after database and object readback. Complete core acceptance also requires the current core-runtime partition.' }
    bulk { 'All existing 6 bulk suites executed; the queued batch was persisted then read and completed in a separate JVM, with duplicate delivery and refresh readback checked.' }
    avatar { 'Original avatar read, upload compensation, and A2 suites plus default-create/self-avatar baseline regression executed with real private storage and database readback; this I0 baseline does not claim the whole I6 session contract.' }
    groups { 'Group and independent qualification commands, old A1 handover guards, concurrent versions, required audit rollback, legacy preservation and real controlled migration replay executed. Foundation scope only; later object authorization and statistics are not signed here.' }
    group-scope { 'Authenticated HTTP lists and counts, queue routing and assignment, separate personal/managed scopes, existing conversations/tickets/private attachments/bulk recipients, committed owner transfer and live socket/SSE invalidation were exercised on exact owned actors and customers; original bindings and objects were preserved.' }
    payment-facts { 'Canonical payment source fixtures were read through the source adapter and rolled back; deposit rails, paid/free/trial device settlement, original refund lineage and missing evidence were checked without executing financial settlement.' }
}
if($avatarPartition){$observed="Original avatar partition $SuiteGroup executed: 1 of 4 existing suites and 1 of 4 scenes with zero-skip XML and its own before/after database and objects. This is a current baseline partial-piece only; independent BE-BASELINE signoff requires all four current avatar partitions plus the original core/bulk checks."}
$steps=@(foreach($id in $acceptance){@{id=$id;status='pass';innerSkipped=0;evidence=@($observed,"Current zero-skip XML, scene, object and database before/after references: $evidence")}})
$result=[ordered]@{at=[DateTime]::UtcNow.ToString('o');taskId=$env:WORKFLOW_TASK_ID;stepId=$env:WORKFLOW_STEP_ID;sourcePhase=$Phase;checkId=$env:WORKFLOW_CHECK_ID;runId=$env:WORKFLOW_RUN_ID;repo=$env:WORKFLOW_REPO;snapshotHash=$env:WORKFLOW_SNAPSHOT_HASH;candidate=$env:SUPPORT_CANDIDATE;verdict='pass';mode='full';treeMoved=$false;capability='runtime';suiteGroup=$SuiteGroup;steps=$steps;suites=@($suiteEvidence.ToArray());scenes=@($sceneEvidence);ownership=(Reference $env:SUPPORT_RESOURCE_OWNERSHIP);before=(Reference "$evidence/shared-before.json");after=(Reference "$evidence/shared-after.json");objectBefore=(Reference "$evidence/object-before.json");objectAfter=(Reference "$evidence/object-after.json")}
if ($SuiteGroup -like 'core*') {
    $result.coreCoverage=@{inventory=(Reference "$PSScriptRoot/support-enhancements-check.ps1");suiteNames=@($corePartition.Suites);sceneNames=@($corePartition.Scenes);originalSuiteCount=27;originalSceneCount=24;wholeCoreExecuted=($SuiteGroup -eq 'core');requiredGroups=@('core-runtime','core-remaining')}
}
$result.tableComparison=$tableComparison
if($avatarPartition){
    $result.acceptanceCoverage='PARTIAL_PIECE_NO_BASELINE_SIGNOFF';$result.phaseSigned=$false
    $result.avatarCoverage=@{suiteNames=@($avatarPartition.Suites);sceneNames=@($scenes);originalSuiteNames=@($avatarPartition.originalSuiteNames);originalSuiteCount=4;originalSceneCount=4;wholeAvatarExecuted=$false;requiredGroups=@($avatarPartition.requiredGroups);sourceReferences=@($nativeSources);baselineSignoffRequires='ALL_FOUR_CURRENT_AVATAR_PARTITIONS_AND_ORIGINAL_CORE_BULK_CHECKS_AND_INDEPENDENT_REVIEW'}
    $result.actorContext=Reference $env:CS_ENHANCE_ACTOR_CONTEXT;$result.sharedRestoration=Reference "$evidence/shared-restoration.json"
}
$result | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $Report -Encoding utf8
Write-Output "step=$Phase status=pass report=$Report"

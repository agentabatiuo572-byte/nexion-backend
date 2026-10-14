$ErrorActionPreference = 'Stop'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/../support-analytics-runtime.ps1",[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Runtime script syntax error' }
$function=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Test-WorkflowBinding'},$true)
if (!$function) { throw 'Binding guard missing' }
Invoke-Expression $function.Extent.Text
$cases=@(
    @('I0-B','runtime-avatar-a2','I0-B','avatar-a2',$true,'support-analytics-backend-20261009-r13'),
    @('integration','step-0-check-4','I0-B','avatar-a2',$true,'support-analytics-backend-20261009-r13'),
    @('I0-B','runtime-avatar-a2','I0-B','avatar-a2',$false,'support-analytics-backend-20261009-r12'),
    @('integration','step-0-check-4','I0-B','avatar-a2',$false,'support-analytics-backend-20261009-r11'),
    @('I0-B','runtime-avatar','I0-B','avatar-a2',$false,'support-analytics-backend-20261009-r13'),
    @('I0-B','runtime-avatar-a2','I0-B','avatar',$false,'support-analytics-backend-20261009-r13'),
    @('integration','step-0-check-4','I0-B','avatar-read',$false,'support-analytics-backend-20261009-r13'),
    @('I0-B','runtime-avatar-read','I0-B','avatar-read',$true,'support-analytics-backend-20261009-r13'),
    @('integration','step-0-check-5','I0-B','avatar-read',$true,'support-analytics-backend-20261009-r13'),
    @('I0-B','runtime-avatar-read','I0-B','avatar-read',$false,'support-analytics-backend-20261009-r12'),
    @('integration','step-0-check-5','I0-B','avatar-read',$false,'support-analytics-backend-20261009-r11'),
    @('I0-B','runtime-avatar','I0-B','avatar-read',$false,'support-analytics-backend-20261009-r13'),
    @('I0-B','runtime-avatar-read','I0-B','avatar',$false,'support-analytics-backend-20261009-r13'),
    @('integration','step-0-check-5','I0-B','avatar-self',$false,'support-analytics-backend-20261009-r13'),
    @('I0-B','runtime-avatar-compensation','I0-B','avatar-compensation',$true,'support-analytics-backend-20261009-r13'),
    @('integration','step-0-check-6','I0-B','avatar-compensation',$true,'support-analytics-backend-20261009-r13'),
    @('I0-B','runtime-avatar-compensation','I0-B','avatar-compensation',$false,'support-analytics-backend-20261009-r12'),
    @('integration','step-0-check-6','I0-B','avatar-compensation',$false,'support-analytics-backend-20261009-r11'),
    @('I0-B','runtime-avatar','I0-B','avatar-compensation',$false,'support-analytics-backend-20261009-r13'),
    @('I0-B','runtime-avatar-compensation','I0-B','avatar',$false,'support-analytics-backend-20261009-r13'),
    @('integration','step-0-check-6','I0-B','avatar-read',$false,'support-analytics-backend-20261009-r13'),
    @('I0-B','runtime-avatar-self','I0-B','avatar-self',$true,'support-analytics-backend-20261009-r13'),
    @('integration','step-0-check-7','I0-B','avatar-self',$true,'support-analytics-backend-20261009-r13'),
    @('I0-B','runtime-avatar-self','I0-B','avatar-self',$false,'support-analytics-backend-20261009-r12'),
    @('integration','step-0-check-7','I0-B','avatar-self',$false,'support-analytics-backend-20261009-r11'),
    @('I0-B','runtime-avatar','I0-B','avatar-self',$false,'support-analytics-backend-20261009-r13'),
    @('I0-B','runtime-avatar-self','I0-B','avatar',$false,'support-analytics-backend-20261009-r13'),
    @('integration','step-0-check-7','I0-B','avatar-read',$false,'support-analytics-backend-20261009-r13'),
    @('I2-B','runtime','I2-B','first-charge-aggregate',$true),
    @('integration','step-4-check-1','I2-B','first-charge-aggregate',$true),
    @('I2-B','runtime','I2-B','core',$false),
    @('I2-B','runtime','I2-B','FIRST-CHARGE-AGGREGATE',$false),
    @('I2-B','RUNTIME','I2-B','first-charge-aggregate',$false),
    @('I2-B','runtime','i2-b','first-charge-aggregate',$false),
    @('integration','step-4-check-0','I2-B','first-charge-aggregate',$false),
    @('integration','STEP-4-CHECK-1','I2-B','first-charge-aggregate',$false),
    @('integration','step-4-check-1','I1-A','groups',$false),
    @('integration','step-3-check-1','I2-B','first-charge-aggregate',$false),
    @('I3-B','runtime','I3-B','analytics-http',$true),
    @('integration','step-5-check-1','I3-B','analytics-http',$true),
    @('I3-B','runtime','I3-B','core',$false),
    @('I3-B','runtime','I3-B','ANALYTICS-HTTP',$false),
    @('I3-B','RUNTIME','I3-B','analytics-http',$false),
    @('I3-B','runtime','i3-b','analytics-http',$false),
    @('integration','step-5-check-0','I3-B','analytics-http',$false),
    @('integration','STEP-5-CHECK-1','I3-B','analytics-http',$false),
    @('integration','step-5-check-1','I1-A','groups',$false),
    @('integration','step-4-check-1','I3-B','analytics-http',$false),
    @('I4-B','runtime','I4-B','rules-configuration',$true),
    @('integration','step-6-check-1','I4-B','rules-configuration',$true),
    @('I4-B','runtime','I4-B','core',$false),
    @('I4-B','runtime','I4-B','RULES-CONFIGURATION',$false),
    @('I4-B','RUNTIME','I4-B','rules-configuration',$false),
    @('I4-B','runtime','i4-b','rules-configuration',$false),
    @('integration','step-6-check-0','I4-B','rules-configuration',$false),
    @('integration','STEP-6-CHECK-1','I4-B','rules-configuration',$false),
    @('integration','step-6-check-1','I1-A','groups',$false),
    @('integration','step-5-check-1','I4-B','rules-configuration',$false),
    @('I5R-B','runtime','I5R-B','leaderboard',$true),
    @('integration','step-7-check-1','I5R-B','leaderboard',$true),
    @('I5R-B','runtime','I5R-B','core',$false),
    @('I5R-B','runtime','I5R-B','LEADERBOARD',$false),
    @('I5R-B','RUNTIME','I5R-B','leaderboard',$false),
    @('I5R-B','runtime','i5r-b','leaderboard',$false),
    @('integration','step-7-check-0','I5R-B','leaderboard',$false),
    @('integration','STEP-7-CHECK-1','I5R-B','leaderboard',$false),
    @('integration','step-7-check-1','I1-A','groups',$false),
    @('integration','step-6-check-1','I5R-B','leaderboard',$false),
    @('I6-B','runtime','I6-B','session-profile',$true),
    @('integration','step-8-check-1','I6-B','session-profile',$true),
    @('I6-B','runtime','I6-B','core',$false),
    @('I6-B','runtime','I6-B','SESSION-PROFILE',$false),
    @('I6-B','RUNTIME','I6-B','session-profile',$false),
    @('I6-B','runtime','i6-b','session-profile',$false),
    @('integration','step-8-check-0','I6-B','session-profile',$false),
    @('integration','STEP-8-CHECK-1','I6-B','session-profile',$false),
    @('integration','step-8-check-1','I1-A','groups',$false),
    @('integration','step-7-check-1','I6-B','session-profile',$false),
    @('I1-A','runtime','I1-A','groups',$true),
    @('I1-A','runtime','I1-B','group-scope',$false),
    @('I0-B','runtime-core','I0-B','core',$true),
    @('I0-B','runtime-core-runtime','I0-B','core-runtime',$true),
    @('I0-B','runtime-core-remaining','I0-B','core-remaining',$true),
    @('I0-B','runtime-bulk','I0-B','bulk',$true),
    @('I0-B','runtime-avatar','I0-B','avatar',$true),
    @('I1-B','runtime','I1-B','group-scope',$true),
    @('I2-A','runtime','I2-A','payment-facts',$true),
    @('I0-B','runtime-core','I0-B','core-runtime',$false),
    @('I0-B','runtime-core-runtime','I0-B','core',$false),
    @('I0-B','runtime-core-runtime','I0-B','core-remaining',$false),
    @('I0-B','runtime-core-remaining','I0-B','core-runtime',$false),
    @('I0-B','runtime-bulk','I0-B','avatar',$false),
    @('I0-B','runtime-avatar','I0-B','bulk',$false),
    @('I0-B','runtime','I0-B','core',$false),
    @('I0-B','unknown','I0-B','bulk',$false),
    @('I1-A','runtime','I1-A','group-scope',$false),
    @('I1-A','runtime-core','I1-A','groups',$false),
    @('I1-B','runtime','I1-B','groups',$false),
    @('I1-B','unknown','I1-B','group-scope',$false),
    @('I2-A','runtime','I2-A','groups',$false),
    @('I2-A','unknown','I2-A','payment-facts',$false),
    @('I0-B','RUNTIME-BULK','I0-B','bulk',$false),
    @('unknown','runtime','unknown','groups',$false),
    @('I0-B','','I0-B','bulk',$false),
    @('I1-A','','I1-A','groups',$false),
    @('I0-B',' ','I0-B','bulk',$false),
    @('I0-B','runtime-bulk','I0-B','',$false),
    @('','runtime','','bulk',$false),
    @('I0-B','runtime-bulk','','bulk',$false),
    @('integration','step-0-check-1','I0-B','core-runtime',$true),
    @('integration','step-0-check-2','I0-B','core-remaining',$true),
    @('integration','step-0-check-3','I0-B','bulk',$true),
    @('integration','step-0-check-4','I0-B','avatar',$true),
    @('integration','step-1-check-1','I1-A','groups',$true),
    @('integration','step-2-check-1','I1-B','group-scope',$true),
    @('integration','step-3-check-1','I2-A','payment-facts',$true),
    @('integration','step-0-check-1','I0-B','bulk',$false),
    @('integration','step-0-check-1','I0-B','core',$false),
    @('integration','step-0-check-1','I0-B','core-remaining',$false),
    @('integration','step-0-check-2','I0-B','core-runtime',$false),
    @('integration','step-0-check-2','I0-B','bulk',$false),
    @('integration','step-0-check-3','I0-B','avatar',$false),
    @('integration','step-0-check-4','I0-B','bulk',$false),
    @('integration','step-0-check-0','I0-B','core-runtime',$false),
    @('integration','step-0-check-5','I0-B','avatar',$false),
    @('integration','','I0-B','core-runtime',$false),
    @('integration','step-0-check-1','I0-B','',$false),
    @('integration','step-1-check-1','I1-B','group-scope',$false),
    @('integration','integration-check-1','I1-A','groups',$false),
    @('integration','step-99-check-1','I1-A','groups',$false),
    @('integration','step-1-check-1','I1-A','core',$false),
    @('unknown','step-1-check-1','I1-A','groups',$false)
)
foreach ($case in $cases) {
    if ((Test-WorkflowBinding $case[0] $case[1] $case[2] $case[3] $(if($case.Count -gt 5){$case[5]}else{''})) -cne $case[4]) { throw "Binding mismatch: $($case[0..3] -join '/')" }
}
Write-Output "workflow-binding contract passed: $($cases.Count) cases"

# Exercise only extracted pure inventory functions, never the runtime environment or Maven.
foreach ($name in @('Inventory','Get-CorePartition')) {
    $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true)
    if (!$definition) { throw "Core inventory function missing: $name" }
    Invoke-Expression $definition.Extent.Text
}
$original="$PSScriptRoot/../support-enhancements-check.ps1"
$suites=@(Inventory $original 'suites'); $sceneNames=@(Inventory $original 'sceneNames')
$originalAst=[Management.Automation.Language.Parser]::ParseFile($original,[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Original inventory syntax error' }
foreach ($name in @('groupSuites','groupScenes')) {
    $definitions=@($originalAst.FindAll({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left -is [Management.Automation.Language.VariableExpressionAst] -and $node.Left.VariablePath.UserPath -ceq $name},$false))
    if ($definitions.Count -ne 1) { throw "Original partition missing or ambiguous: $name" }
    Invoke-Expression $definitions[0].Extent.Text
}
$runtime=Get-CorePartition $suites $sceneNames 'core-runtime'
$remaining=Get-CorePartition $suites $sceneNames 'core-remaining'
$all=Get-CorePartition $suites $sceneNames 'core'
foreach ($pair in @(@($runtime,'runtime'),@($remaining,'remaining'),@($all,'all'))) {
    if (@(Compare-Object @($pair[0].Suites) @($groupSuites[$pair[1]]) -CaseSensitive).Count -or
        @(Compare-Object @($pair[0].Scenes) @($groupScenes[$pair[1]]) -CaseSensitive).Count) { throw "Original core partition changed: $($pair[1])" }
}
$combinedSuites=@($runtime.Suites)+@($remaining.Suites); $combinedScenes=@($runtime.Scenes)+@($remaining.Scenes)
if ($combinedSuites.Count -ne 27 -or @($combinedSuites | Sort-Object -Unique).Count -ne 27 -or @(Compare-Object $combinedSuites $suites -CaseSensitive).Count -or
    $combinedScenes.Count -ne 24 -or @($combinedScenes | Sort-Object -Unique).Count -ne 24 -or @(Compare-Object $combinedScenes $sceneNames -CaseSensitive).Count) {
    throw 'Split core coverage is incomplete or duplicated'
}
$invalid=@(
    @{Suites=$suites[1..26];Scenes=$sceneNames;Group='core'},
    @{Suites=$suites;Scenes=$sceneNames[1..23];Group='core'},
    @{Suites=@($suites[0])+$suites[0..25];Scenes=$sceneNames;Group='core'},
    @{Suites=$suites;Scenes=@($sceneNames[0])+$sceneNames[0..22];Group='core'},
    @{Suites=@($suites | ForEach-Object { if ($_ -ceq 'SupportEnhancementCoreRuntimeTest') {'UnknownRuntimeSuite'} else {$_} });Scenes=$sceneNames;Group='core'},
    @{Suites=$suites;Scenes=$sceneNames;Group='unknown'}
)
foreach ($case in $invalid) {
    $rejected=$false
    try { $null=Get-CorePartition $case.Suites $case.Scenes $case.Group } catch { $rejected=$true }
    if (!$rejected) { throw 'Invalid split core inventory was accepted' }
}
Write-Output "core-partition contract passed: original 27 suites / 24 scenes, runtime 1/14 + remaining 26/10, $($invalid.Count) invalid inventories rejected"

# Extract only pure functions. No runner top-level/private files, Maven, socket, Spring, DB or HTTP.
foreach($name in @('Get-NativePhaseInventory','Test-NativeScene','New-NativePartialRecord')){
    $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true)
    if(!$definition){throw "New phase pure function missing: $name"};Invoke-Expression $definition.Extent.Text
}
$expected=@{
    'I2-B'=@{group='first-charge-aggregate';suites=2;scenes=10;ids=29}
    'I3-B'=@{group='analytics-http';suites=1;scenes=4;ids=3}
    'I4-B'=@{group='rules-configuration';suites=1;scenes=6;ids=7}
    'I5R-B'=@{group='leaderboard';suites=1;scenes=5;ids=14}
    'I6-B'=@{group='session-profile';suites=1;scenes=2;ids=1}
}
$nativeChecks=0;$nativeIds=@();$nativeSceneNames=@();$allFlags=@()
foreach($phase in $expected.Keys){
    $inventory=Get-NativePhaseInventory $phase;$exp=$expected[$phase]
    if($inventory.TaskId -cne 'support-analytics-backend-20261009-r13'){throw 'New phase requires exact fresh r13 task'};$nativeChecks++
    if($inventory.Group -cne $exp.group -or $inventory.Suites.Count -ne $exp.suites -or $inventory.Cases.Count -ne $exp.scenes -or $inventory.Acceptance.Count -ne $exp.ids){throw "New phase exact inventory mismatch: $phase"}
    $nativeIds+=@($inventory.Acceptance);$nativeSceneNames+=@($inventory.Cases|ForEach-Object scene);$allFlags+=@($inventory.OptIns)
    foreach($case in $inventory.Cases){
        if($case.suite -cnotin $inventory.Suites -or [string]::IsNullOrWhiteSpace($case.testcase)){throw 'Native testcase source missing'}
        $doc=@{workflowRunId='contract_run';snapshotHash=('a'*64);suite=$case.suite;testcase=$case.testcase;capability='runtime';assertions='Actual producer assertion evidence';observations=@{case='native'};exactFactReceipts=@(@{path='owned-exact-row-receipt';sha256=('c'*64)});status='scenario-assertions-passed';acceptanceCoverage='PARTIAL_NO_PHASE_SIGNOFF';cleanupComplete=$true;acceptanceStatus='PARTIAL';cleanup='EXACT_OWNED_ROWS_ONLY';exactFactsCleaned=$true}
        if(!(Test-NativeScene ([pscustomobject]$doc) $case 'contract_run' ('a'*64))){throw 'Valid partial scenario rejected'};$nativeChecks++
        $changes=@(@{suite='ForeignSuite'},@{testcase='AnotherMethod'},@{workflowRunId='old_run'},@{snapshotHash=('b'*64)})
        if($case.policy -ceq 'scenario'){$changes+=@(@{status='pass'},@{acceptanceCoverage='PASS'},@{cleanupComplete=$false},@{cleanupComplete='true'},@{capability='static'},@{assertions=$null})}
        else{$changes+=@(@{acceptanceStatus='PASS'},@{cleanup='ACTOR_ONLY'},@{exactFactsCleaned=$false},@{exactFactsCleaned='true'},@{observations=$null},@{exactFactReceipts=@()})}
        foreach($change in $changes){$bad=$doc.Clone();foreach($key in $change.Keys){$bad[$key]=$change[$key]};if(Test-NativeScene ([pscustomobject]$bad) $case 'contract_run' ('a'*64)){throw 'Invalid identity/partial metadata accepted'};$nativeChecks++}
    }
    $ctx=@{taskId=$inventory.TaskId;stepId=$phase;sourcePhase=$phase;checkId='runtime';runId='contract_run';repo='contract_repo';snapshotHash=('a'*64);candidate='contract_candidate'}
    foreach($issues in @(@(),@(@{stage='native';error='simulated pure input'}))){
        $record=New-NativePartialRecord $ctx $inventory @() @() $issues @{}
        if($record.verdict -cne 'fail' -or $record.mode -cne 'partial' -or $record.phaseSigned -ne $false -or $record.acceptanceCoverage -cne 'PARTIAL_NO_PHASE_SIGNOFF' -or @($record.steps|Where-Object status -cne 'fail').Count -or @($record.steps).Count -ne $exp.ids){throw 'Native partial record was promoted to phase PASS'};$nativeChecks++
    }
    $poisoned=$false;try{$null=New-NativePartialRecord $ctx $inventory @() @() @() @{verdict='pass'}}catch{$poisoned=$true};if(!$poisoned){throw 'Reference overwrote fail-closed report'};$nativeChecks++
    $wrongTask=$ctx.Clone();$wrongTask.taskId='another-task';$poisoned=$false;try{$null=New-NativePartialRecord $wrongTask $inventory @() @() @() @{}}catch{$poisoned=$true};if(!$poisoned){throw 'Foreign task partial record accepted'};$nativeChecks++
    foreach($oldTask in @('support-analytics-backend-20261009-r12','support-analytics-backend-20261009-r11')){
        $wrongTask=$ctx.Clone();$wrongTask.taskId=$oldTask;$rejected=$false
        try{$null=New-NativePartialRecord $wrongTask $inventory @() @() @() @{}}catch{$rejected=$true};if(!$rejected){throw "Prior task accepted for fresh r13 phase: $phase / $oldTask"};$nativeChecks++
    }
    $badContext=$ctx.Clone();$badContext.checkId='targeted-maven';$rejected=$false
    try{$null=New-NativePartialRecord $badContext $inventory @() @() @() @{}}catch{$rejected=$true};if(!$rejected){throw 'Unbound partial record accepted'};$nativeChecks++
}
if($nativeIds.Count -ne 54 -or @($nativeIds|Sort-Object -Unique).Count -ne 54 -or $nativeSceneNames.Count -ne 27 -or @($nativeSceneNames|Sort-Object -Unique).Count -ne 27 -or @($allFlags|Sort-Object -Unique).Count -ne 6){throw 'New phase inventory omitted or duplicated acceptance/scene/opt-in'}
foreach($invalidPhase in @('I2-b','I4-b','unknown','I0-B','')){if($null -ne (Get-NativePhaseInventory $invalidPhase)){throw 'Unknown/case-forged/legacy phase got new native inventory'}}
Write-Output "new-native-phase contract passed: 5 phases / 6 suites / 27 scenes / 54 acceptance IDs remain FAIL until per-ID independent signoff; $nativeChecks pure metadata/fail-record cases"




# Pure original-avatar partitions and injected Git readback, never execute runner top-level.
foreach($name in @('Get-OriginalAvatarSuites','Get-AvatarPartition','Test-AvatarScene','Test-AvatarPartitionSet','Read-CandidateTreeState')){
    $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true)
    if(!$definition){throw "Avatar/Git pure function missing: $name"};Invoke-Expression $definition.Extent.Text
}
$avatarOriginal=@(Get-OriginalAvatarSuites "$PSScriptRoot/../support-analytics-runtime.ps1")
$avatarGroups=@('avatar-a2','avatar-read','avatar-compensation','avatar-self');$avatarChecks=0;$pieceReports=@()
foreach($group in $avatarGroups){
    $partition=Get-AvatarPartition $avatarOriginal $group
    if($partition.Suites.Count -ne 1 -or $partition.Methods.Count -ne 1 -or $partition.wholeAvatarExecuted -ne $false -or $partition.originalSuiteCount -ne 4){throw 'Avatar partition not exactly one original suite'};$avatarChecks++
    foreach($wrongTask in @('support-analytics-backend-20261009-r12','support-analytics-backend-20261009-r11','')){
        if(Test-WorkflowBinding 'I0-B' "runtime-$group" 'I0-B' $group $wrongTask){throw 'Avatar partition accepted non-r13 task'};$avatarChecks++
    }
    $proof=[pscustomobject]@{status='pass';suite=$partition.Suites[0];testcase=$partition.Methods[0]}
    $proofMap=@{};foreach($id in $partition.ProofIds){$proofMap[$id]=$proof}
    $doc=[pscustomobject]@{workflowRunId='avatar_current';snapshotHash=('a'*64);database='cs_analytics_20261007';port=18161;databasePort=33337;httpPort=18161;capability='runtime';cleanup='pass';checks=[pscustomobject]$proofMap}
    if(!(Test-AvatarScene $doc $partition 'avatar_current' ('a'*64))){throw 'Original avatar proof rejected'};$avatarChecks++
    foreach($field in @('status','suite','testcase')){
        $bad=$doc|ConvertTo-Json -Depth 8|ConvertFrom-Json;$bad.checks.($partition.ProofIds[0]).$field='wrong'
        if(Test-AvatarScene $bad $partition 'avatar_current' ('a'*64)){throw 'Wrong existing avatar proof value accepted'};$avatarChecks++
    }
    foreach($changes in @(@{workflowRunId='old'},@{snapshotHash=('b'*64)},@{database='source_db'},@{checks=[pscustomobject]@{}},@{checks=[pscustomobject]@{selected=[pscustomobject]@{status='fail';suite=$partition.Suites[0];testcase=$partition.Methods[0]}}},@{checks=[pscustomobject]@{selected=[pscustomobject]@{status='pass';suite='ForeignSuite';testcase=$partition.Methods[0]}}},@{checks=[pscustomobject]@{selected=[pscustomobject]@{status='pass';suite=$partition.Suites[0];testcase='AnotherMethod'}}})){
        $bad=$doc|ConvertTo-Json -Depth 8|ConvertFrom-Json;foreach($key in $changes.Keys){$bad.$key=$changes[$key]};if(Test-AvatarScene $bad $partition 'avatar_current' ('a'*64)){throw 'Wrong avatar scene metadata accepted'};$avatarChecks++
    }
    $reference=@{path='pure-artifact.json';sha256=('c'*64)}
    $piece=@{taskId='support-analytics-backend-20261009-r13';stepId='I0-B';sourcePhase='I0-B';checkId="runtime-$group";runId='avatar_current';snapshotHash=('a'*64);candidate='candidate_current';repo='D:/WORKS/PLAN/.wt/cs-analytics-api-20261007';suiteGroup=$group;verdict='pass';mode='full';treeMoved=$false;phaseSigned=$false;acceptanceCoverage='PARTIAL_PIECE_NO_BASELINE_SIGNOFF';suites=@(@{group=$group;suite=$partition.Suites[0];tests=1;skipped=0;failures=0;errors=0});avatarCoverage=@{suiteNames=$partition.Suites;sceneNames=@($partition.ScenePattern.Replace('*','owned-run'));originalSuiteNames=$avatarOriginal;originalSuiteCount=4;originalSceneCount=4;wholeAvatarExecuted=$false;requiredGroups=$avatarGroups;sourceReferences=@(@{path="/pure/$($partition.Suites[0]).java";sha256=('d'*64)})};before=$reference;after=$reference;objectBefore=$reference;objectAfter=$reference;actorContext=$reference;sharedRestoration=$reference}
    $pieceReports+=@([pscustomobject]$piece)
}
if(!(Test-AvatarPartitionSet $pieceReports 'avatar_current' ('a'*64) 'candidate_current')){throw 'Complete current avatar coverage rejected'};$avatarChecks++
foreach($subset in @(@($pieceReports[0]),@($pieceReports[0..2]),@($pieceReports[0..2]+$pieceReports[0]))){if(Test-AvatarPartitionSet $subset 'avatar_current' ('a'*64) 'candidate_current'){throw 'Missing or duplicated avatar partitions accepted'};$avatarChecks++}
foreach($changes in @(@{taskId='support-analytics-backend-20261009-r12'},@{runId='old'},@{snapshotHash=('b'*64)},@{candidate='old'},@{repo='foreign'},@{checkId='runtime-avatar'},@{phaseSigned=$true},@{phaseSigned='false'},@{acceptanceCoverage='PASS'},@{treeMoved=$true},@{treeMoved='false'},@{after=@{path='x';sha256='wrong'}})){
    $reports=@($pieceReports|ConvertTo-Json -Depth 12|ConvertFrom-Json);foreach($key in $changes.Keys){$reports[0].$key=$changes[$key]};if(Test-AvatarPartitionSet $reports 'avatar_current' ('a'*64) 'candidate_current'){throw 'Wrong avatar merge identity/metadata accepted'};$avatarChecks++
}
foreach($field in @('suiteNames','sceneNames','originalSuiteNames','requiredGroups','sourceReferences')){
    $reports=@($pieceReports|ConvertTo-Json -Depth 12|ConvertFrom-Json);$reports[0].avatarCoverage.$field=@();if(Test-AvatarPartitionSet $reports 'avatar_current' ('a'*64) 'candidate_current'){throw 'Incomplete avatar coverage metadata accepted'};$avatarChecks++
}
$invalidAvatar=@(@($avatarOriginal[0..2]),@($avatarOriginal[0..2]+$avatarOriginal[0]),@('ForeignSuite')+$avatarOriginal[1..3])
foreach($inventory in $invalidAvatar){$rejected=$false;try{$null=Get-AvatarPartition $inventory 'avatar-read'}catch{$rejected=$true};if(!$rejected){throw 'Missing/duplicate/foreign original avatar suite accepted'};$avatarChecks++}
$rejected=$false;try{$null=Get-AvatarPartition $avatarOriginal 'avatar-READ'}catch{$rejected=$true};if(!$rejected){throw 'Case-forged avatar group accepted'};$avatarChecks++
$gitCases=@('valid','changed','null-result','null-head','empty-head','head-exit','diff-exit','status-exit','throw')
foreach($fault in $gitCases){
    $reader={param([string[]]$Arguments)
        if($fault -ceq 'throw'){throw 'Injected pure Git failure'}
        if($fault -ceq 'null-result'){return $null}
        $command=$Arguments[0];$code=0;$output=if($command -ceq 'rev-parse'){'expected_head'}elseif($command -ceq 'diff'){'expected_diff'}else{'expected_status'}
        if($fault -ceq 'changed' -and $command -ceq 'rev-parse'){$output='another_head'}
        if($fault -ceq 'null-head' -and $command -ceq 'rev-parse'){$output=$null}
        if($fault -ceq 'empty-head' -and $command -ceq 'rev-parse'){$output=''}
        if(($fault -ceq 'head-exit' -and $command -ceq 'rev-parse') -or ($fault -ceq 'diff-exit' -and $command -ceq 'diff') -or ($fault -ceq 'status-exit' -and $command -ceq 'status')){$code=128}
        return @{exitCode=$code;output=$output}
    }
    $state=Read-CandidateTreeState 'expected_head' 'expected_diff' 'expected_status' $reader
    if($fault -ceq 'valid'){if($state.treeMoved -ne $false -or $state.issue){throw 'Valid pure Git readback rejected'}}
    elseif($fault -ceq 'changed'){if($state.treeMoved -ne $true -or $state.issue){throw 'Changed pure candidate not detected'}}
    else{
        if($state.treeMoved -ne $true -or [string]::IsNullOrWhiteSpace($state.issue)){throw 'Git failure escaped structured failure state'}
        $inventory=Get-NativePhaseInventory 'I2-B';$ctx=@{taskId=$inventory.TaskId;stepId='I2-B';sourcePhase='I2-B';checkId='runtime';runId='pure_git';snapshotHash=('a'*64);repo='pure';candidate='pure'}
        $record=New-NativePartialRecord $ctx $inventory @() @() @(@{stage='candidate-readback';error=$state.issue}) @{treeMoved=$state.treeMoved;rawArtifacts=@(@{path='pure-before-after';sha256=('c'*64)})}
        if($record.verdict -cne 'fail' -or $record.executionIssues.Count -ne 1 -or $record.rawArtifacts.Count -ne 1 -or !$record.treeMoved -or $record.phaseSigned -ne $false){throw 'Git failure stopped structured partial record'}
    }
}
Write-Output "avatar-partition contract passed: 4 original suites / 4 exact r13 groups; $avatarChecks inventory/metadata/merge cases; single pieces never sign whole avatar; Git readback passed $($gitCases.Count) injected pure cases"

# Exact five first-charge cases bind existing Java scene fields to actual frozen suite/helper source SHA.
foreach($name in @('Get-NativeCaseSourceBinding','New-NativeSceneReference')){
    $definition=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq $name},$true)
    if(!$definition){throw "First-charge pure binding function missing: $name"};Invoke-Expression $definition.Extent.Text
}
$firstMethods=@('genuinePurchaseThenDepositPreservesFirstReplayAndHalfOpenEventBoundary','genuineDepositThenPurchaseNeverReplacesOriginalFirstOrOwnership','ownedGiftAndFreeTrialSourceRowsDoNotQualifyBeforeRealPaidCheckout','originalPurchaseRefundAndLaterDepositKeepTheOriginalLifetimeFirst','realUnassignedPaymentAndMissingLegacyBirthKeepDifferentUnknownDimensions')
$inventory=Get-NativePhaseInventory 'I2-B';$firstCases=@($inventory.Cases|Where-Object {$_.sourceSha256});$firstChecks=0
if($firstCases.Count -ne 5 -or @(Compare-Object @($firstCases.testcase) $firstMethods -CaseSensitive).Count -or 'SupportFirstChargeCommandReceipt' -cnotin $inventory.Dependencies){throw 'Exact five first-charge cases/helper omitted or widened'};$firstChecks++
$repository='D:/pure-first-charge-source-contract'
$suiteSource=@{path="$repository/src/test/java/ffdd/opsconsole/content/application/SupportFirstChargeRuntimeTest.java";sha256='2dab20d7b2c3a65af5b0c45a003111c9b4f8234be7b84c4bea51e82658f64591'}
$helperSource=@{path="$repository/src/test/java/ffdd/opsconsole/content/application/SupportFirstChargeCommandReceipt.java";sha256='e4498223dd2c6b4ac65a0e635b4a7c8e994a3f8cc292f5932cd5e44706cad478'}
$sources=@($suiteSource,$helperSource)
foreach($case in $firstCases){
    if($case.suite -cne 'SupportFirstChargeRuntimeTest' -or $case.scene -cne "SupportFirstChargeRuntimeTest-$($case.testcase).json" -or $case.policy -cne 'scenario' -or $case.sourceSha256 -cne $suiteSource.sha256 -or $case.receiptSha256 -cne $helperSource.sha256){throw 'First-charge exact method/scene/policy/source contract changed'};$firstChecks++
    $doc=[pscustomobject]@{workflowRunId='first_pure';snapshotHash=('a'*64);suite=$case.suite;testcase=$case.testcase;capability='runtime';status='scenario-assertions-passed';acceptanceCoverage='PARTIAL_NO_PHASE_SIGNOFF';cleanupComplete=$true;assertions='Pure probe copied existing fields; not real native evidence'}
    $record=New-NativeSceneReference $doc $case @{path='pure-scene.json';sha256=('d'*64)} $sources $repository 'first_pure' ('a'*64)
    if($record.phaseSignoff -ne $false -or $record.sourceBinding.suite.sha256 -cne $suiteSource.sha256 -or $record.sourceBinding.commandReceipt.sha256 -cne $helperSource.sha256){throw 'Source SHA was not bound to exact scene'};$firstChecks++
    foreach($field in @('capability','status','acceptanceCoverage','assertions','cleanupComplete')){if($record.policyMetadata[$field] -cne $doc.$field){throw 'Policy metadata fabricated instead of copying verified scene fields'};$firstChecks++}
    $wrongSuite=$suiteSource.Clone();$wrongSuite.sha256='b'*64;$wrongHelper=$helperSource.Clone();$wrongHelper.sha256='c'*64;$foreign=$suiteSource.Clone();$foreign.path=$foreign.path.Replace($repository,'D:/foreign')
    foreach($badSources in @(@($suiteSource),@($helperSource),@($suiteSource,$helperSource,$suiteSource),@($wrongSuite,$helperSource),@($suiteSource,$wrongHelper),@($foreign,$helperSource))){
        $rejected=$false;try{$null=New-NativeSceneReference $doc $case @{path='pure';sha256=('d'*64)} $badSources $repository 'first_pure' ('a'*64)}catch{$rejected=$true}
        if(!$rejected){throw 'Missing/duplicate/foreign/mismatched source SHA accepted'};$firstChecks++
    }
    foreach($field in @('status','cleanupComplete','snapshotHash','testcase')){
        $bad=$doc|ConvertTo-Json|ConvertFrom-Json;$bad.$field=if($field -ceq 'cleanupComplete'){$false}else{'wrong'};$rejected=$false
        try{$null=New-NativeSceneReference $bad $case @{path='pure';sha256=('d'*64)} $sources $repository 'first_pure' ('a'*64)}catch{$rejected=$true}
        if(!$rejected){throw 'Failed/foreign scene acquired source/policy metadata'};$firstChecks++
    }
}
Write-Output "first-charge source-policy contract passed: 5 new exact methods/scenes / 2 frozen source SHA bindings / $firstChecks pure cases; native NOT_RUN"

# Execute only this small parsed selection branch with injected in-memory enumeration.
$selection=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.IfStatementAst] -and $node.Extent.Text.StartsWith('if($avatarPartition){') -and $node.Extent.Text.Contains('$matches=@(Get-ChildItem') -and $node.Extent.Text.Contains('Avatar partition before/native/after failed')},$true))
if($selection.Count -ne 1){throw 'Exactly one avatar failure/scene selection branch required'}
$branch=[scriptblock]::Create($selection[0].Extent.Text);$diagnosticChecks=0
foreach($probe in @(
    @{issues=@(@{stage='before-or-native';error='Actual assertion expected HTTP200 but received HTTP403; private native log retained'});matches=@();failure='HTTP403';reads=0},
    @{issues=@(@{stage='before-or-native';error='native primary HTTP403'},@{stage='after';error='after capture secondary'});matches=@();failure='native primary HTTP403';reads=0},
    @{issues=@(@{stage='after';error='actual cleanup/capture failure'});matches=@();failure='actual cleanup/capture failure';reads=0},
    @{issues=@();matches=@();failure='Exactly one selected original avatar scene required';reads=1},
    @{issues=@();matches=@(@{Name='avatar-current.json'});failure=$null;reads=1}
)){
    $observed=& {
        param($inputCase,$selectionBranch)
        $reads=[Collections.Generic.List[string]]::new();$nativeIssues=[Collections.Generic.List[object]]::new();foreach($issue in $inputCase.issues){$nativeIssues.Add($issue)}
        $avatarPartition=@{ScenePattern='avatar-pure.json'};$evidence='pure-no-filesystem';$scenes=@()
        function Get-ChildItem {param($LiteralPath,$Filter,[switch]$File) $reads.Add('enumeration');return @($inputCase.matches)}
        $errorText=$null;try{& $selectionBranch}catch{$errorText=$_.Exception.Message}
        return @{error=$errorText;readCount=$reads.Count;issues=@($nativeIssues.ToArray())}
    } $probe $branch
    if($observed.readCount -ne $probe.reads){throw 'Missing scene was checked before primary native failure'}
    if($probe.failure){if([string]::IsNullOrWhiteSpace($observed.error) -or !$observed.error.Contains($probe.failure)){throw 'Primary native/cleanup error was obscured'}}elseif($observed.error){throw 'Valid selected scene rejected'}
    if($observed.issues.Count -ne $probe.issues.Count){throw 'Original native issues were lost'}
    foreach($issue in $probe.issues){if(!$observed.error.Contains($issue.error)){throw 'Native error text was replaced instead of retained'}}
    $diagnosticChecks++
}
Write-Output "avatar-failure diagnostic contract passed: $diagnosticChecks pure cases; primary native HTTP403 text preserved, secondary errors retained, scene-only failure remains failure; no subprocess/native execution"

# Run the production inventory filter without starting a suite or reading private files.
$inventoryAssignment=@($ast.FindAll({param($node) $node -is [Management.Automation.Language.AssignmentStatementAst] -and $node.Left.Extent.Text -ceq '$rawFiles' -and $node.Extent.Text.Contains('Get-ChildItem')},$true))
if($inventoryAssignment.Count -ne 1){throw 'Exactly one raw artifact inventory required'}
$names=& {
    param($statement)
    $evidence='pure-no-filesystem'
    function Get-ChildItem {
        param($LiteralPath,[switch]$Recurse,[switch]$File)
        foreach($name in @('scene.json','suite.xml','runtime.log','intent.json.gz','READBACK.JSON.GZ','unrelated.gz','private.cnf','linked.json.gz')) {
            [pscustomobject]@{Name=$name;Extension=[IO.Path]::GetExtension($name);Attributes=if($name -eq 'linked.json.gz'){[IO.FileAttributes]::ReparsePoint}else{[IO.FileAttributes]::Normal}}
        }
    }
    . ([scriptblock]::Create($statement));$rawFiles.Name
} $inventoryAssignment[0].Extent.Text
if(@(Compare-Object @('scene.json','suite.xml','runtime.log','intent.json.gz','READBACK.JSON.GZ') @($names) -CaseSensitive).Count){throw 'Interrupted runtime evidence must retain gzip facts and exclude private or linked files'}
Write-Output 'raw-artifact inventory passed: gzip facts retained, private/unrelated/linked files excluded; no native runtime'

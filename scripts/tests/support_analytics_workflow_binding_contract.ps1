$ErrorActionPreference = 'Stop'
$tokens=$null; $errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/../support-analytics-runtime.ps1",[ref]$tokens,[ref]$errors)
if ($errors.Count) { throw 'Runtime script syntax error' }
$function=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Test-WorkflowBinding'},$true)
if (!$function) { throw 'Binding guard missing' }
Invoke-Expression $function.Extent.Text
$cases=@(
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
    if ((Test-WorkflowBinding $case[0] $case[1] $case[2] $case[3]) -cne $case[4]) { throw "Binding mismatch: $($case[0..3] -join '/')" }
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

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
    @('integration','step-0-check-1','I0-B','core',$true),
    @('integration','step-0-check-2','I0-B','bulk',$true),
    @('integration','step-0-check-3','I0-B','avatar',$true),
    @('integration','step-1-check-1','I1-A','groups',$true),
    @('integration','step-2-check-1','I1-B','group-scope',$true),
    @('integration','step-3-check-1','I2-A','payment-facts',$true),
    @('integration','step-0-check-1','I0-B','bulk',$false),
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

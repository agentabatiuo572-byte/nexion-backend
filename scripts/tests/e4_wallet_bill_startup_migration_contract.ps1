param(
  [string]$RunnerPath = (Join-Path $PSScriptRoot "..\apply_startup_schema_migrations.ps1")
)
$ErrorActionPreference = "Stop"

# Parse the real normal-startup manifest without executing it or reading its environment/configuration.
$tokens = $null
$parseErrors = $null
$ast = [Management.Automation.Language.Parser]::ParseFile(
  $RunnerPath, [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count -ne 0) { throw "Startup migration runner has syntax errors." }
$assignments = @($ast.FindAll({
  param($node)
  $node -is [Management.Automation.Language.AssignmentStatementAst] -and
    $node.Left.Extent.Text -eq '$migrations'
}, $true))
if ($assignments.Count -ne 1) { throw "Normal startup must have one migration manifest." }
$paths = @($assignments[0].Right.FindAll({
  param($node)
  $node -is [Management.Automation.Language.CommandAst] -and $node.GetCommandName() -eq 'Join-Path'
}, $true) | ForEach-Object {
  if ($_.CommandElements.Count -ne 3 -or
      $_.CommandElements[2] -isnot [Management.Automation.Language.StringConstantExpressionAst]) {
    throw "Normal startup migration paths must be literal relative paths."
  }
  $_.CommandElements[2].Value.Replace('\', '/')
})
$compat = 'scripts/migrations/20261009_e4_wallet_bill_compat.sql'
$strict = 'scripts/migrations/20261009_e4_wallet_bill_schema.sql'
if (@($paths | Where-Object { $_ -eq $compat }).Count -ne 1 -or
    @($paths | Where-Object { $_ -eq $strict }).Count -ne 1) {
  throw "Normal startup must register compat and strict wallet schema exactly once."
}
if ([Array]::IndexOf($paths, $compat) + 1 -ne [Array]::IndexOf($paths, $strict)) {
  throw "Wallet compatibility must execute immediately before the strict schema guard."
}
if ([String]::CompareOrdinal([IO.Path]::GetFileName($compat), [IO.Path]::GetFileName($strict)) -ge 0) {
  throw "Versioned compatibility must also precede strict schema in deployment filename order."
}
$root = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
foreach ($path in @($compat, $strict)) {
  if (-not (Test-Path -LiteralPath (Join-Path $root $path) -PathType Leaf)) {
    throw "A registered wallet migration file is missing."
  }
}
"E4 wallet bill normal-startup ordering contract: PASS (static only; no SQL executed)"

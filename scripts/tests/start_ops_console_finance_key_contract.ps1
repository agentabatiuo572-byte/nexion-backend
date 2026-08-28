$ErrorActionPreference = "Stop"

$scriptPath = Join-Path $PSScriptRoot "..\start_ops_console_monolith.ps1"
$source = Get-Content -LiteralPath $scriptPath -Raw

if ($source -notmatch 'NEXION_FINANCE_DATA_KEY') {
  throw "start script does not forward NEXION_FINANCE_DATA_KEY"
}
if ($source -notmatch 'GetEnvironmentVariable\("NEXION_FINANCE_DATA_KEY",\s*"User"\)') {
  throw "start script does not recover the user-scoped finance key"
}
if ($source -notmatch '\$userFinanceDataKey\s*=\s*\[Environment\]::GetEnvironmentVariable\("NEXION_FINANCE_DATA_KEY",\s*"User"\)') {
  throw "start script does not keep the stable user-scoped finance key separate from an inherited process value"
}
if ($source -notmatch '\$financeDataKey\s*=\s*if\s*\(\$SpringProfile\s*-eq\s*"dev"\s*-and\s*-not\s*\[string\]::IsNullOrWhiteSpace\(\$userFinanceDataKey\)\)') {
  throw "dev startup does not prefer the stable user-scoped finance key over a stale inherited process value"
}
if ($source -notmatch '\$financeDataKey\s*=\s*if[\s\S]*?\{\s*\$userFinanceDataKey\s*\}\s*else\s*\{\s*\$previousFinanceDataKey\s*\}') {
  throw "production startup can fall back to the user-scoped finance key"
}
if ($source -notmatch 'throw\s+"NEXION_FINANCE_DATA_KEY is required') {
  throw "start script does not fail closed when the finance key is absent"
}
if ($source -notmatch '\$financeDataKey\.Trim\(\)\.Length\s*-lt\s*32[\s\S]*throw\s+"NEXION_FINANCE_DATA_KEY must contain at least 32 characters"') {
  throw "start script does not fail closed when the finance key is too short"
}
if ($source -match 'Start-Process[\s\S]{0,300}-Environment\s+') {
  throw "start script relies on Start-Process -Environment, which is unavailable in Windows PowerShell 5.1"
}
if ($source -notmatch '\$previousFinanceDataKey\s*=\s*\[Environment\]::GetEnvironmentVariable\("NEXION_FINANCE_DATA_KEY",\s*"Process"\)') {
  throw "start script does not preserve the process-scoped finance key before child launch"
}
if ($source -notmatch '\[Environment\]::SetEnvironmentVariable\("NEXION_FINANCE_DATA_KEY",\s*\$financeDataKey,\s*"Process"\)') {
  throw "start script does not inject the finance key through the inherited process environment"
}
if ($source -notmatch '\[Environment\]::SetEnvironmentVariable\("NEXION_FINANCE_DATA_KEY",\s*\$previousFinanceDataKey,\s*"Process"\)') {
  throw "start script does not restore the process-scoped finance key after child launch"
}
if ($source -notmatch 'finally\s*\{[\s\S]*\[Environment\]::SetEnvironmentVariable\("NEXION_FINANCE_DATA_KEY",\s*\$previousFinanceDataKey,\s*"Process"\)') {
  throw "start script does not restore the process-scoped finance key from a finally block"
}

"PASS start_ops_console_finance_key_contract"

$ErrorActionPreference = "Stop"

$scriptPath = Resolve-Path (Join-Path $PSScriptRoot "..\start_ops_console_monolith.ps1")
$source = Get-Content -LiteralPath $scriptPath -Raw

if ($source -match '\[string\]\$AcceptanceRunId\s*=') {
  throw "Backend startup must not expose the retired AcceptanceRunId parameter"
}

if ($source -match 'NEXION_ACCEPTANCE_RUN_ID=') {
  throw "Backend startup must not pass the retired acceptance RunID to the child process"
}

if ($source -notmatch 'Remove-Item Env:NEXION_ACCEPTANCE_RUN_ID') {
  throw "Backend startup must clear inherited acceptance RunID state"
}

Write-Output "START_OPS_CONSOLE_GENESIS_RUN_ID_CONTRACT: PASS"

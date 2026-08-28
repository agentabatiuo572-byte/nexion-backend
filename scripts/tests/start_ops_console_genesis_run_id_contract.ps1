$ErrorActionPreference = "Stop"

$scriptPath = Resolve-Path (Join-Path $PSScriptRoot "..\start_ops_console_monolith.ps1")
$source = Get-Content -LiteralPath $scriptPath -Raw

if ($source -notmatch '\[string\]\$AcceptanceRunId\s*=') {
  throw "Backend startup must expose an AcceptanceRunId parameter"
}

if ($source -notmatch 'NEXION_ACCEPTANCE_RUN_ID=\{0\}') {
  throw "Backend startup must pass the resolved acceptance RunID to the child process"
}

if ($source -notmatch 'nexion-local-dev') {
  throw "Dev startup must have a stable, non-secret local acceptance RunID fallback"
}

if ($source -notmatch '\$SpringProfile\s+-eq\s+"prod"[\s\S]*AcceptanceRunId is allowed only for the dev profile') {
  throw "Production startup must fail closed when an acceptance RunID is supplied"
}

Write-Output "START_OPS_CONSOLE_GENESIS_RUN_ID_CONTRACT: PASS"

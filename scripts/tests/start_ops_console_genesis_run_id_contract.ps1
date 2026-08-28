$ErrorActionPreference = "Stop"

$scriptPath = Resolve-Path (Join-Path $PSScriptRoot "..\start_ops_console_monolith.ps1")
$source = Get-Content -LiteralPath $scriptPath -Raw

if ($source -notmatch '\[string\]\$AcceptanceRunId\s*=') {
  throw "Backend startup must expose an AcceptanceRunId parameter"
}

if ($source -notmatch 'NEXION_ACCEPTANCE_RUN_ID=\{0\}') {
  throw "Backend startup must pass the resolved acceptance RunID to the child process"
}

if ($source -match 'elseif\s*\(\$SpringProfile\s+-eq\s+"dev"\)\s*\{\s*"nexion-local-dev"') {
  throw "Standard development startup must not silently opt into an acceptance Sandbox RunID"
}

if ($source -notmatch '\$SpringProfile\s+-eq\s+"dev"\s+-and\s+-not\s+\[string\]::IsNullOrWhiteSpace\(\$acceptanceRunIdValue\)\s+-and') {
  throw "Standard development startup must allow an empty acceptance RunID"
}

if ($source -notmatch '\$SpringProfile\s+-eq\s+"prod"[\s\S]*AcceptanceRunId is allowed only for the dev profile') {
  throw "Production startup must fail closed when an acceptance RunID is supplied"
}

Write-Output "START_OPS_CONSOLE_GENESIS_RUN_ID_CONTRACT: PASS"

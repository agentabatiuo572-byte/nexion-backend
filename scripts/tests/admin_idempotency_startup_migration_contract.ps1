$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$runner = Join-Path $root "scripts\apply_startup_schema_migrations.ps1"
$fakeMySql = Join-Path $PSScriptRoot "fake_mysql_index_count.cmd"
$previousPassword = $env:SPRING_DATASOURCE_PASSWORD
$previousCount = $env:NEXION_FAKE_INDEX_COUNT

function Invoke-RunnerCase {
  param(
    [Parameter(Mandatory = $true)][string]$IndexCount,
    [Parameter(Mandatory = $true)][bool]$ExpectFailure
  )

  $env:NEXION_FAKE_INDEX_COUNT = $IndexCount
  $failed = $false
  try {
    & $runner -MySql $fakeMySql `
      -JdbcUrl "jdbc:mysql://127.0.0.1:3306/idempotency_runner_contract" `
      -Username "contract" -Confirm:$false
  } catch {
    $failed = $true
  }
  if ($failed -ne $ExpectFailure) {
    throw "Unexpected startup-runner result for required-index count $IndexCount."
  }
}

try {
  $env:SPRING_DATASOURCE_PASSWORD = "contract-only"
  Invoke-RunnerCase -IndexCount "2" -ExpectFailure $false
  Invoke-RunnerCase -IndexCount "1" -ExpectFailure $true
  Invoke-RunnerCase -IndexCount "0" -ExpectFailure $true
  "admin idempotency startup migration contract: PASS"
} finally {
  if ($null -eq $previousPassword) {
    Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
  } else {
    $env:SPRING_DATASOURCE_PASSWORD = $previousPassword
  }
  if ($null -eq $previousCount) {
    Remove-Item Env:NEXION_FAKE_INDEX_COUNT -ErrorAction SilentlyContinue
  } else {
    $env:NEXION_FAKE_INDEX_COUNT = $previousCount
  }
}

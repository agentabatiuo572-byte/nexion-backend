$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$runner = Join-Path $root "scripts\apply_startup_schema_migrations.ps1"
$fakeMySql = Join-Path $PSScriptRoot "fake_mysql_index_count.cmd"
$previousUrl = $env:NEXION_DB_URL
$previousUsername = $env:NEXION_DB_USERNAME
$previousPassword = $env:NEXION_DB_PASSWORD
$previousSpringUrl = $env:SPRING_DATASOURCE_URL
$previousSpringUsername = $env:SPRING_DATASOURCE_USERNAME
$previousSpringPassword = $env:SPRING_DATASOURCE_PASSWORD
$previousCount = $env:NEXION_FAKE_INDEX_COUNT

function Invoke-RunnerCase {
  param(
    [Parameter(Mandatory = $true)][string]$IndexCount,
    [Parameter(Mandatory = $true)][bool]$ExpectFailure
  )

  $env:NEXION_FAKE_INDEX_COUNT = $IndexCount
  $failed = $false
  try {
    & $runner -MySql $fakeMySql -Confirm:$false
  } catch {
    $failed = $true
  }
  if ($failed -ne $ExpectFailure) {
    throw "Unexpected startup-runner result for required-index count $IndexCount."
  }
}

try {
  $env:NEXION_DB_URL = "jdbc:mysql://127.0.0.1:3306/idempotency_runner_contract"
  $env:NEXION_DB_USERNAME = "contract"
  $env:NEXION_DB_PASSWORD = "contract-only"
  Remove-Item Env:SPRING_DATASOURCE_URL -ErrorAction SilentlyContinue
  Remove-Item Env:SPRING_DATASOURCE_USERNAME -ErrorAction SilentlyContinue
  Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
  Invoke-RunnerCase -IndexCount "2" -ExpectFailure $false
  Invoke-RunnerCase -IndexCount "1" -ExpectFailure $true
  Invoke-RunnerCase -IndexCount "0" -ExpectFailure $true
  "admin idempotency startup migration contract: PASS"
} finally {
  if ($null -eq $previousPassword) {
    Remove-Item Env:NEXION_DB_PASSWORD -ErrorAction SilentlyContinue
  } else {
    $env:NEXION_DB_PASSWORD = $previousPassword
  }
  if ($null -eq $previousUrl) {
    Remove-Item Env:NEXION_DB_URL -ErrorAction SilentlyContinue
  } else {
    $env:NEXION_DB_URL = $previousUrl
  }
  if ($null -eq $previousUsername) {
    Remove-Item Env:NEXION_DB_USERNAME -ErrorAction SilentlyContinue
  } else {
    $env:NEXION_DB_USERNAME = $previousUsername
  }
  if ($null -eq $previousSpringUrl) {
    Remove-Item Env:SPRING_DATASOURCE_URL -ErrorAction SilentlyContinue
  } else {
    $env:SPRING_DATASOURCE_URL = $previousSpringUrl
  }
  if ($null -eq $previousSpringUsername) {
    Remove-Item Env:SPRING_DATASOURCE_USERNAME -ErrorAction SilentlyContinue
  } else {
    $env:SPRING_DATASOURCE_USERNAME = $previousSpringUsername
  }
  if ($null -eq $previousSpringPassword) {
    Remove-Item Env:SPRING_DATASOURCE_PASSWORD -ErrorAction SilentlyContinue
  } else {
    $env:SPRING_DATASOURCE_PASSWORD = $previousSpringPassword
  }
  if ($null -eq $previousCount) {
    Remove-Item Env:NEXION_FAKE_INDEX_COUNT -ErrorAction SilentlyContinue
  } else {
    $env:NEXION_FAKE_INDEX_COUNT = $previousCount
  }
}

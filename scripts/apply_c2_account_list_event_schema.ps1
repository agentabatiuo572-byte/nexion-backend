[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
  [string]$MySql = "D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe",
  [string]$JdbcUrl = $env:SPRING_DATASOURCE_URL,
  [string]$Username = $(if ([string]::IsNullOrWhiteSpace($env:SPRING_DATASOURCE_USERNAME)) { "root" } else { $env:SPRING_DATASOURCE_USERNAME }),
  [string]$Password = $env:SPRING_DATASOURCE_PASSWORD
)

$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$migration = Join-Path $root "scripts\migrations\20260729_c2_account_list_event_schema.sql"

if ([string]::IsNullOrWhiteSpace($JdbcUrl)) {
  $JdbcUrl = "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
}
if (-not $JdbcUrl.StartsWith("jdbc:mysql://", [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "Only jdbc:mysql URLs are supported by this controlled migration runner."
}
$databaseUri = [Uri]$JdbcUrl.Substring(5)
$database = $databaseUri.AbsolutePath.Trim('/')
if ([string]::IsNullOrWhiteSpace($database)) {
  throw "The JDBC URL must include a database name."
}
$port = if ($databaseUri.IsDefaultPort) { 3306 } else { $databaseUri.Port }

if ($WhatIfPreference) {
  [pscustomobject]@{
    Migration = $migration
    Host = $databaseUri.Host
    Port = $port
    Database = $database
    Username = $Username
    Action = "Would apply the idempotent C2 account-list A4 schema migration before backend startup."
  }
  return
}

if (-not (Test-Path -LiteralPath $MySql)) {
  throw "MySQL executable not found: $MySql"
}
if ([string]::IsNullOrWhiteSpace($Password)) {
  throw "SPRING_DATASOURCE_PASSWORD (or -Password) is required to apply the C2 account-list migration."
}
if (-not $PSCmdlet.ShouldProcess("$($databaseUri.Host):$port/$database", "apply C2 account-list A4 schema migration")) {
  return
}

$previousMySqlPassword = $env:MYSQL_PWD
try {
  $env:MYSQL_PWD = $Password
  $migrationForMysql = $migration.Replace('\', '/')
  & $MySql --default-character-set=utf8mb4 --protocol=tcp -h $databaseUri.Host -P $port -u $Username $database -e "source $migrationForMysql;"
  if ($LASTEXITCODE -ne 0) {
    throw "C2 account-list A4 schema migration failed with mysql exit code $LASTEXITCODE. Backend startup has been stopped."
  }
} finally {
  if ($null -eq $previousMySqlPassword) {
    Remove-Item Env:MYSQL_PWD -ErrorAction SilentlyContinue
  } else {
    $env:MYSQL_PWD = $previousMySqlPassword
  }
}

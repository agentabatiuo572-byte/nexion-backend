[CmdletBinding(SupportsShouldProcess = $true, ConfirmImpact = 'High')]
param(
  [Parameter(Mandatory = $true)]
  [string]$Confirmation,
  [string]$MySql = "D:\software\MySQL\MySQL Server 8.0\bin\mysql.exe"
)

$ErrorActionPreference = "Stop"
if ($Confirmation -cne "RETIRE_SANDBOX_TO_DEVELOPMENT") {
  throw "Confirmation must be RETIRE_SANDBOX_TO_DEVELOPMENT"
}
if (Get-NetTCPConnection -LocalPort 8110 -State Listen -ErrorAction SilentlyContinue) {
  throw "Stop the backend on port 8110 before migrating Sandbox data"
}
if (-not (Test-Path -LiteralPath $MySql)) {
  throw "MySQL executable not found: $MySql"
}

$root = Resolve-Path (Join-Path $PSScriptRoot "..")
$sql = Join-Path $root "scripts\maintenance\retire_sandbox_to_development.sql"
if (-not (Test-Path -LiteralPath $sql)) {
  throw "Sandbox retirement SQL not found: $sql"
}
$databaseEnvironment = & (Join-Path $PSScriptRoot "resolve_nexion_database_environment.ps1")
if (-not $databaseEnvironment.JdbcUrl.StartsWith("jdbc:mysql://", [System.StringComparison]::OrdinalIgnoreCase)) {
  throw "Only jdbc:mysql URLs are supported"
}
$uri = [Uri]$databaseEnvironment.JdbcUrl.Substring(5)
$Database = $uri.AbsolutePath.Trim('/')
if ($Database -ne "nexion") {
  throw "Sandbox retirement is restricted to the local nexion development database"
}
if ($uri.Host -notin @("127.0.0.1", "localhost")) {
  throw "Sandbox retirement is restricted to 127.0.0.1 or localhost"
}
if ([string]::IsNullOrWhiteSpace($databaseEnvironment.Password)) {
  throw "NEXION_DB_PASSWORD is required"
}
$port = if ($uri.IsDefaultPort) { 3306 } else { $uri.Port }
if (-not $PSCmdlet.ShouldProcess("$($uri.Host):$port/$Database", "archive, classify, and retire Sandbox data")) {
  return
}

$previousPassword = $env:MYSQL_PWD
try {
  $env:MYSQL_PWD = $databaseEnvironment.Password
  $sourcePath = $sql.Replace('\\','/')
  & $MySql --host=$($uri.Host) --port=$port --user=$($databaseEnvironment.Username) `
    --database=$Database --show-warnings --execute="source $sourcePath"
  if ($LASTEXITCODE -ne 0) {
    throw "Sandbox retirement SQL failed with exit code $LASTEXITCODE"
  }
  & $MySql --host=$($uri.Host) --port=$port --user=$($databaseEnvironment.Username) `
    --database=$Database --batch --raw --execute="SELECT migration_id,status,archive_schema,completed_at FROM nx_data_environment_migration WHERE migration_id='sandbox-to-development-v2-classified'"
  if ($LASTEXITCODE -ne 0) {
    throw "Sandbox retirement verification query failed with exit code $LASTEXITCODE"
  }
} finally {
  $env:MYSQL_PWD = $previousPassword
}

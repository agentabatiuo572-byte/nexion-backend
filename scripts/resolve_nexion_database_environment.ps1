param(
  [switch]$RequireExplicit
)

$ErrorActionPreference = "Stop"

$defaultJdbcUrl = "jdbc:mysql://127.0.0.1:3306/nexion?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true"
$defaultUsername = "root"

function Get-ProcessEnvironmentValue {
  param([Parameter(Mandatory = $true)][string]$Name)
  [Environment]::GetEnvironmentVariable($Name, "Process")
}

function Test-EnvironmentValuePresent {
  param([AllowNull()][string]$Value)
  -not [string]::IsNullOrWhiteSpace($Value)
}

$nexionUrl = Get-ProcessEnvironmentValue "NEXION_DB_URL"
$nexionUsername = Get-ProcessEnvironmentValue "NEXION_DB_USERNAME"
$nexionPassword = Get-ProcessEnvironmentValue "NEXION_DB_PASSWORD"
$springUrl = Get-ProcessEnvironmentValue "SPRING_DATASOURCE_URL"
$springUsername = Get-ProcessEnvironmentValue "SPRING_DATASOURCE_USERNAME"
$springPassword = Get-ProcessEnvironmentValue "SPRING_DATASOURCE_PASSWORD"

$nexionPresent = (Test-EnvironmentValuePresent $nexionUrl) -or
  (Test-EnvironmentValuePresent $nexionUsername) -or
  (Test-EnvironmentValuePresent $nexionPassword)
$springPresent = (Test-EnvironmentValuePresent $springUrl) -or
  (Test-EnvironmentValuePresent $springUsername) -or
  (Test-EnvironmentValuePresent $springPassword)

if ($springPresent) {
  $springComplete = (Test-EnvironmentValuePresent $springUrl) -and
    (Test-EnvironmentValuePresent $springUsername) -and
    (Test-EnvironmentValuePresent $springPassword)
  if (-not $springComplete) {
    throw "SPRING_DATASOURCE_URL, SPRING_DATASOURCE_USERNAME, and SPRING_DATASOURCE_PASSWORD must be supplied as one complete bundle; cross-bundle assembly is forbidden."
  }
}

if ($nexionPresent -and $springPresent) {
  $nexionComplete = (Test-EnvironmentValuePresent $nexionUrl) -and
    (Test-EnvironmentValuePresent $nexionUsername) -and
    (Test-EnvironmentValuePresent $nexionPassword)
  if (-not $nexionComplete) {
    throw "NEXION_DB_URL, NEXION_DB_USERNAME, and NEXION_DB_PASSWORD must be supplied as one complete bundle when legacy SPRING_DATASOURCE_* variables are also present; cross-bundle assembly is forbidden."
  }
  $sameBundle = [string]::Equals($nexionUrl, $springUrl, [System.StringComparison]::Ordinal) -and
    [string]::Equals($nexionUsername, $springUsername, [System.StringComparison]::Ordinal) -and
    [string]::Equals($nexionPassword, $springPassword, [System.StringComparison]::Ordinal)
  if (-not $sameBundle) {
    throw "Refusing conflicting database environment bundles: NEXION_DB_* is authoritative and does not match SPRING_DATASOURCE_*."
  }
}

if ($nexionPresent) {
  $nexionComplete = (Test-EnvironmentValuePresent $nexionUrl) -and
    (Test-EnvironmentValuePresent $nexionUsername) -and
    (Test-EnvironmentValuePresent $nexionPassword)
  if ($RequireExplicit -and -not $nexionComplete) {
    throw "Production requires NEXION_DB_URL, NEXION_DB_USERNAME, and NEXION_DB_PASSWORD as one complete bundle."
  }
  [pscustomobject]@{
    Source = "NEXION_DB"
    JdbcUrl = $(if (Test-EnvironmentValuePresent $nexionUrl) { $nexionUrl } else { $defaultJdbcUrl })
    Username = $(if (Test-EnvironmentValuePresent $nexionUsername) { $nexionUsername } else { $defaultUsername })
    Password = $(if ($null -eq $nexionPassword) { "" } else { $nexionPassword })
  }
  return
}

if ($springPresent) {
  [pscustomobject]@{
    Source = "SPRING_DATASOURCE_COMPATIBILITY"
    JdbcUrl = $springUrl
    Username = $springUsername
    Password = $springPassword
  }
  return
}

if ($RequireExplicit) {
  throw "Production requires one complete database environment bundle; loopback defaults are forbidden."
}

[pscustomobject]@{
  Source = "NEXION_DB"
  JdbcUrl = $defaultJdbcUrl
  Username = $defaultUsername
  Password = ""
}

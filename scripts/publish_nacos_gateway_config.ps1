param(
  [string]$NacosServer = "http://127.0.0.1:8848",
  [string]$Username = "nacos",
  [string]$Password = "nacos",
  [string]$NamespaceId = "public",
  [string]$Group = "DEFAULT_GROUP",
  [string]$DataId = "nexion-gateway.yaml",
  [string]$ConfigFile = ""
)

$ErrorActionPreference = "Stop"

if (-not $ConfigFile) {
  $ConfigFile = Join-Path $PSScriptRoot "nacos\nexion-gateway.yaml"
}
if (-not (Test-Path $ConfigFile)) {
  throw "Nacos config file not found: $ConfigFile"
}

$baseUrl = $NacosServer.TrimEnd("/")
$content = Get-Content -Raw -Path $ConfigFile
$accessToken = $null

try {
  $login = Invoke-RestMethod `
    -Method Post `
    -Uri "$baseUrl/nacos/v1/auth/login" `
    -ContentType "application/x-www-form-urlencoded" `
    -Body @{
      username = $Username
      password = $Password
    } `
    -TimeoutSec 10
  if ($login.accessToken) {
    $accessToken = $login.accessToken
  }
} catch {
  Write-Host "Nacos auth login skipped or unavailable: $($_.Exception.Message)"
}

$body = @{
  dataId = $DataId
  group = $Group
  content = $content
  type = "yaml"
}
if ($NamespaceId -and $NamespaceId -ne "public") {
  $body.tenant = $NamespaceId
}
if ($accessToken) {
  $body.accessToken = $accessToken
}

$result = Invoke-RestMethod `
  -Method Post `
  -Uri "$baseUrl/nacos/v1/cs/configs" `
  -ContentType "application/x-www-form-urlencoded" `
  -Body $body `
  -TimeoutSec 15

if ($result -ne $true -and $result -ne "true") {
  throw "Nacos publish failed: $result"
}

Write-Host "Published $DataId to Nacos group=$Group namespace=$NamespaceId from $ConfigFile"

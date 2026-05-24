param(
  [string]$GatewayUrl = "http://127.0.0.1:8090",
  [string]$AdminUsername = "superadmin",
  [string]$AdminPassword = $env:NEXION_SMOKE_ADMIN_PASSWORD,
  [string]$AdminToken = "",
  [string]$CountryCode = "+1",
  [string]$Phone = "",
  [string]$Password = "Nexion123456",
  [string]$ReferralCode = "NX4892"
)

$ErrorActionPreference = "Stop"
$script:AdminToken = $AdminToken

function Invoke-RawJson {
  param(
    [Parameter(Mandatory = $true)][string]$Method,
    [Parameter(Mandatory = $true)][string]$Uri,
    [object]$Body = $null,
    [string]$Token = ""
  )

  $headers = @{}
  if ($Token) {
    $headers.Authorization = "Bearer $Token"
  }

  $args = @{
    Method = $Method
    Uri = $Uri
    Headers = $headers
    TimeoutSec = 20
  }
  if ($null -ne $Body) {
    $args.ContentType = "application/json"
    $args.Body = ($Body | ConvertTo-Json -Depth 10 -Compress)
  }

  try {
    $response = Invoke-RestMethod @args
    return [pscustomobject]@{
      HttpStatus = 200
      Code = $response.code
      Message = $response.message
      Data = $response.data
      Body = $response
    }
  } catch {
    $statusCode = $null
    if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
      $statusCode = [int]$_.Exception.Response.StatusCode
    }
    $message = $_.Exception.Message
    $body = $null
    if ($_.ErrorDetails -and $_.ErrorDetails.Message) {
      $message = $_.ErrorDetails.Message
      try {
        $body = $message | ConvertFrom-Json
      } catch {
        $body = $message
      }
    }
    return [pscustomobject]@{
      HttpStatus = $statusCode
      Code = $null
      Message = $message
      Data = $null
      Body = $body
    }
  }
}

function Invoke-NexionJson {
  param(
    [Parameter(Mandatory = $true)][string]$Method,
    [Parameter(Mandatory = $true)][string]$Uri,
    [object]$Body = $null,
    [string]$Token = ""
  )

  $response = Invoke-RawJson -Method $Method -Uri $Uri -Body $Body -Token $Token
  if ($response.HttpStatus -lt 200 -or $response.HttpStatus -ge 300) {
    throw "HTTP request failed: $Method $Uri -> status=$($response.HttpStatus), message=$($response.Message)"
  }
  if ($null -ne $response.Code -and $response.Code -ne 0) {
    throw "API request failed: $Method $Uri -> code=$($response.Code), message=$($response.Message)"
  }
  return $response.Data
}

function Assert-ApiCode {
  param(
    [Parameter(Mandatory = $true)][object]$Response,
    [Parameter(Mandatory = $true)][int]$ExpectedCode,
    [Parameter(Mandatory = $true)][string]$Context
  )

  if ($Response.HttpStatus -lt 200 -or $Response.HttpStatus -ge 300) {
    throw "$Context expected API code $ExpectedCode, got HTTP status $($Response.HttpStatus). $($Response.Message)"
  }
  if ($Response.Code -ne $ExpectedCode) {
    throw "$Context expected API code $ExpectedCode, got code=$($Response.Code), message=$($Response.Message)"
  }
}

function Assert-ServiceHealth {
  try {
    $health = Invoke-RestMethod -Method Get -Uri "$GatewayUrl/actuator/health" -TimeoutSec 8
    Write-Host "OK health gateway -> $($health.status)"
  } catch {
    throw "Gateway is not reachable at $GatewayUrl. Start the gateway chain services before running this smoke script."
  }
}

function Ensure-AdminToken {
  if ($script:AdminToken) {
    return
  }
  if (-not $AdminPassword) {
    throw "Admin password is required. Pass -AdminPassword, set NEXION_SMOKE_ADMIN_PASSWORD, or pass -AdminToken."
  }

  Write-Host "Logging in as admin for System config checks..."
  $login = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/admin/login" -Body @{
    username = $AdminUsername
    password = $AdminPassword
  }
  $script:AdminToken = $login.token
  if (-not $script:AdminToken) {
    throw "Admin login did not return a token."
  }
  $authorities = @($login.admin.authorities)
  if (($authorities -notcontains "PERM_SYSTEM_READ") -or ($authorities -notcontains "PERM_SYSTEM_WRITE")) {
    throw "Admin token does not include PERM_SYSTEM_READ and PERM_SYSTEM_WRITE. Run scripts/seed.sql or scripts/patch_business_api_permissions.sql."
  }
}

function New-SmokeUserToken {
  $smokePhone = $Phone
  if (-not $smokePhone) {
    $smokePhone = "8$(Get-Date -Format "MMddHHmmss")$(Get-Random -Minimum 10 -Maximum 99)"
  }
  Write-Host "Registering a normal user for permission denial check..."
  $null = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/users/register" -Body @{
    countryCode = $CountryCode
    phone = $smokePhone
    password = $Password
    referralCode = $ReferralCode
  }
  $login = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/users/login" -Body @{
    countryCode = $CountryCode
    phone = $smokePhone
    password = $Password
  }
  if (-not $login.token) {
    throw "Normal user login did not return a token."
  }
  return $login.token
}

Assert-ServiceHealth
Ensure-AdminToken

Write-Host "Verifying anonymous System config access is rejected..."
$anonymousResponse = Invoke-RawJson -Method Get -Uri "$GatewayUrl/api/system/configs?limit=1"
if ($anonymousResponse.HttpStatus -ne 401) {
  throw "anonymous read expected HTTP 401, got status=$($anonymousResponse.HttpStatus), message=$($anonymousResponse.Message)"
}
Write-Host "Anonymous read rejected with HTTP 401"

$userToken = New-SmokeUserToken
Write-Host "Verifying normal user without System permission is rejected..."
$userDenied = Invoke-RawJson -Method Get -Uri "$GatewayUrl/api/system/configs?limit=1" -Token $userToken
Assert-ApiCode -Response $userDenied -ExpectedCode 403 -Context "normal user read"
Write-Host "Normal user read rejected with code=403"

$stamp = Get-Date -Format "yyyyMMddHHmmss"
$configKey = "smoke.system.config.$stamp.$(Get-Random -Minimum 1000 -Maximum 9999)"
$encodedKey = [System.Uri]::EscapeDataString($configKey)

Write-Host "Creating System config item..."
$created = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/system/configs" -Token $script:AdminToken -Body @{
  configKey = $configKey
  configValue = "alpha"
  valueType = "STRING"
  remark = "smoke system config"
  status = 1
}
if (-not $created.id -or $created.configKey -ne $configKey) {
  throw "System config creation returned unexpected payload."
}
Write-Host "Created config id=$($created.id), key=$($created.configKey)"

Write-Host "Checking list and active lookup..."
$listed = @(Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/system/configs?query=$encodedKey&limit=5" -Token $script:AdminToken)
if (@($listed | Where-Object { $_.configKey -eq $configKey }).Count -lt 1) {
  throw "Expected created config in list response."
}
$active = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/system/configs/$encodedKey" -Token $script:AdminToken
if ($active.configValue -ne "alpha") {
  throw "Expected active config value alpha, got $($active.configValue)."
}

Write-Host "Checking batch query..."
$batch = @(Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/system/configs/batch-query" -Token $script:AdminToken -Body @{
  configKeys = @($configKey, "feature.genesis.enabled")
})
if (@($batch | Where-Object { $_.configKey -eq $configKey }).Count -lt 1) {
  throw "Expected created config in batch response."
}

Write-Host "Updating config value..."
$updated = Invoke-NexionJson -Method Patch -Uri "$GatewayUrl/api/system/configs/$($created.id)" -Token $script:AdminToken -Body @{
  configValue = "bravo"
  remark = "smoke updated"
}
if ($updated.configValue -ne "bravo" -or $updated.remark -ne "smoke updated") {
  throw "System config update returned unexpected payload."
}

Write-Host "Disabling config and verifying active lookup returns API code 404..."
$disabled = Invoke-NexionJson -Method Patch -Uri "$GatewayUrl/api/system/configs/$($created.id)" -Token $script:AdminToken -Body @{
  status = 0
}
if ($disabled.status -ne 0) {
  throw "Expected disabled status 0, got $($disabled.status)."
}
$missingActive = Invoke-RawJson -Method Get -Uri "$GatewayUrl/api/system/configs/$encodedKey" -Token $script:AdminToken
Assert-ApiCode -Response $missingActive -ExpectedCode 404 -Context "disabled active lookup"

Write-Host "System config smoke completed. configId=$($created.id), configKey=$configKey"

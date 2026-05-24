param(
  [string]$GatewayUrl = "http://127.0.0.1:8090",
  [string]$CountryCode = "+1",
  [string]$Phone = "",
  [string]$Password = "Nexion123456",
  [string]$ReferralCode = "NX4892",
  [string]$AdminUsername = "superadmin",
  [string]$AdminPassword = $env:NEXION_SMOKE_ADMIN_PASSWORD,
  [string]$AdminToken = "",
  [int]$Quantity = 1,
  [decimal]$RewardUsdt = 0.018,
  [decimal]$RewardNex = 3.2,
  [string]$WebhookCallbackUrl = "https://merchant.example/webhook"
)

$ErrorActionPreference = "Stop"
$script:UserToken = $null
$script:UserId = $null
$script:AdminToken = $AdminToken

function Invoke-RawJson {
  param(
    [Parameter(Mandatory = $true)][string]$Method,
    [Parameter(Mandatory = $true)][string]$Uri,
    [object]$Body = $null,
    [string]$RawBody = "",
    [string]$Token = "",
    [hashtable]$Headers = @{}
  )

  $requestHeaders = @{}
  foreach ($key in $Headers.Keys) {
    $requestHeaders[$key] = $Headers[$key]
  }
  if ($Token) {
    $requestHeaders.Authorization = "Bearer $Token"
  }

  $args = @{
    Method = $Method
    Uri = $Uri
    Headers = $requestHeaders
    TimeoutSec = 25
  }
  if ($RawBody) {
    $args.ContentType = "application/json"
    $args.Body = $RawBody
  } elseif ($null -ne $Body) {
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
    [string]$RawBody = "",
    [string]$Token = "",
    [hashtable]$Headers = @{}
  )

  $response = Invoke-RawJson -Method $Method -Uri $Uri -Body $Body -RawBody $RawBody -Token $Token -Headers $Headers
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

function First-Record {
  param([object]$Page)
  if ($null -eq $Page -or $null -eq $Page.records -or $Page.records.Count -lt 1) {
    throw "Expected at least one record, got none."
  }
  return $Page.records[0]
}

function Get-Sha256Hex {
  param([Parameter(Mandatory = $true)][string]$Value)

  $sha = [System.Security.Cryptography.SHA256]::Create()
  try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    return ([System.BitConverter]::ToString($sha.ComputeHash($bytes)) -replace "-", "").ToLowerInvariant()
  } finally {
    $sha.Dispose()
  }
}

function Get-HmacSha256Hex {
  param(
    [Parameter(Mandatory = $true)][string]$Secret,
    [Parameter(Mandatory = $true)][string]$Value
  )

  $hmac = [System.Security.Cryptography.HMACSHA256]::new([System.Text.Encoding]::UTF8.GetBytes($Secret))
  try {
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($Value)
    return ([System.BitConverter]::ToString($hmac.ComputeHash($bytes)) -replace "-", "").ToLowerInvariant()
  } finally {
    $hmac.Dispose()
  }
}

function New-ReceiptPayload {
  param(
    [Parameter(Mandatory = $true)][long]$UserDeviceId,
    [Parameter(Mandatory = $true)][string]$TaskType,
    [Parameter(Mandatory = $true)][string]$ClientName,
    [Parameter(Mandatory = $true)][decimal]$RewardUsdt,
    [Parameter(Mandatory = $true)][decimal]$RewardNex
  )

  return ([ordered]@{
    clientName = $ClientName
    rewardNex = $RewardNex
    rewardUsdt = $RewardUsdt
    taskType = $TaskType
    userDeviceId = $UserDeviceId
  } | ConvertTo-Json -Depth 8 -Compress)
}

function New-OpenApiHeaders {
  param(
    [Parameter(Mandatory = $true)][string]$AppKey,
    [Parameter(Mandatory = $true)][string]$AppSecret,
    [Parameter(Mandatory = $true)][string]$Payload,
    [Parameter(Mandatory = $true)][string]$Nonce
  )

  $timestamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds().ToString()
  $stringToSign = "$AppKey`n$timestamp`n$Nonce`n$(Get-Sha256Hex -Value $Payload)"
  return @{
    "X-Nexion-App-Key" = $AppKey
    "X-Nexion-Timestamp" = $timestamp
    "X-Nexion-Nonce" = $Nonce
    "X-Nexion-Signature" = Get-HmacSha256Hex -Secret $AppSecret -Value $stringToSign
  }
}

function Invoke-SignedReceipt {
  param(
    [Parameter(Mandatory = $true)][string]$AppKey,
    [Parameter(Mandatory = $true)][string]$AppSecret,
    [Parameter(Mandatory = $true)][string]$Payload,
    [Parameter(Mandatory = $true)][string]$Nonce
  )

  $headers = New-OpenApiHeaders -AppKey $AppKey -AppSecret $AppSecret -Payload $Payload -Nonce $Nonce
  return Invoke-RawJson -Method Post -Uri "$GatewayUrl/api/openapi/v1/compute/receipts" -RawBody $Payload -Headers $headers
}

function Ensure-AdminToken {
  if ($script:AdminToken) {
    return
  }
  if (-not $AdminPassword) {
    throw "Admin password is required. Pass -AdminPassword, set NEXION_SMOKE_ADMIN_PASSWORD, or pass -AdminToken."
  }

  Write-Host "Logging in as admin for OpenAPI Ops checks..."
  $login = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/admin/login" -Body @{
    username = $AdminUsername
    password = $AdminPassword
  }
  $script:AdminToken = $login.token
  if (-not $script:AdminToken) {
    throw "Admin login did not return a token."
  }
  if ($login.admin -and $login.admin.authorities -and ($login.admin.authorities -notcontains "PERM_OPENAPI_ADMIN")) {
    throw "Admin token does not include PERM_OPENAPI_ADMIN. Run scripts/seed.sql or pass -AdminToken with that authority."
  }
}

Assert-ServiceHealth
Ensure-AdminToken

if (-not $Phone) {
  $Phone = "7$(Get-Date -Format "MMddHHmmss")$(Get-Random -Minimum 10 -Maximum 99)"
}

$stamp = Get-Date -Format "yyyyMMddHHmmss"

Write-Host "Registering a real user for OpenAPI owner flow..."
$registered = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/users/register" -Body @{
  countryCode = $CountryCode
  phone = $Phone
  password = $Password
  referralCode = $ReferralCode
}
$registeredUserId = [long]$registered.userId

Write-Host "Logging in as OpenAPI owner user..."
$login = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/users/login" -Body @{
  countryCode = $CountryCode
  phone = $Phone
  password = $Password
}
$script:UserToken = $login.token
$script:UserId = [long]$login.userId
if (-not $script:UserToken -or $script:UserToken.StartsWith("dev-")) {
  throw "User login did not return a real JWT token."
}
if ($script:UserId -ne $registeredUserId) {
  throw "Registered userId $registeredUserId does not match login userId $($script:UserId)."
}
Write-Host "Owner userId=$($script:UserId)"

Write-Host "Preparing a compute device through commerce order flow..."
$products = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/commerce/products?pageNum=1&pageSize=1&status=ON_SALE" -Token $script:UserToken
$product = First-Record $products
$order = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/commerce/orders" -Token $script:UserToken -Body @{
  userId = $script:UserId
  productId = $product.id
  quantity = $Quantity
}
$order = Invoke-NexionJson -Method Put -Uri "$GatewayUrl/api/commerce/orders/$($order.orderNo)/paid" -Token $script:UserToken -Body @{
  paymentNo = "SMOKE-OPENAPI-PAY-$stamp"
}
$devices = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/compute/devices?sourceOrderNo=$($order.orderNo)&pageNum=1&pageSize=1" -Token $script:UserToken
$device = First-Record $devices
Write-Host "Prepared device id=$($device.id), order=$($order.orderNo)"

Write-Host "Creating OpenAPI app and webhook subscription..."
$app = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/openapi/apps" -Token $script:UserToken -Body @{
  appName = "smoke-openapi-$stamp"
  qpsLimit = 100
  dailyLimit = 100
  remark = "smoke-openapi"
}
if (-not $app.appKey -or -not $app.appSecret) {
  throw "OpenAPI app creation did not return appKey/appSecret."
}
$webhook = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/openapi/webhooks" -Token $script:UserToken -Body @{
  appId = $app.id
  eventType = "COMPUTE_RECEIPT_CREATED"
  callbackUrl = $WebhookCallbackUrl
}
if (-not $webhook.id) {
  throw "Webhook subscription was not created."
}
Write-Host "OpenAPI app id=$($app.id), appKey=$($app.appKey)"

Write-Host "Submitting signed OpenAPI compute receipt..."
$payload = New-ReceiptPayload -UserDeviceId ([long]$device.id) -TaskType "SMOKE_OPENAPI_INFERENCE" -ClientName "smoke-openapi" -RewardUsdt $RewardUsdt -RewardNex $RewardNex
$nonce = "smoke-$([guid]::NewGuid().ToString())"
$receiptResponse = Invoke-SignedReceipt -AppKey $app.appKey -AppSecret $app.appSecret -Payload $payload -Nonce $nonce
Assert-ApiCode -Response $receiptResponse -ExpectedCode 0 -Context "signed receipt"
$receipt = $receiptResponse.Data
Write-Host "Signed receipt OK receiptNo=$($receipt.receiptNo)"

Write-Host "Verifying nonce replay is rejected..."
$replayResponse = Invoke-SignedReceipt -AppKey $app.appKey -AppSecret $app.appSecret -Payload $payload -Nonce $nonce
Assert-ApiCode -Response $replayResponse -ExpectedCode 409 -Context "nonce replay"
Write-Host "Nonce replay rejected with code=409"

Write-Host "Checking webhook delivery queue for the receipt-created event..."
$deliveries = @(Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/openapi/webhooks/deliveries?status=PENDING&appId=$($app.id)&eventType=COMPUTE_RECEIPT_CREATED&limit=20" -Token $script:AdminToken)
$delivery = $deliveries | Select-Object -First 1
if ($null -eq $delivery) {
  throw "Expected at least one pending webhook delivery for app $($app.id)."
}
Write-Host "Webhook delivery queued id=$($delivery.id), status=$($delivery.status)"

Write-Host "Checking call audit records..."
$audits = @(Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/openapi/ops/call-audits?appId=$($app.id)&limit=20" -Token $script:AdminToken)
if ($audits.Count -lt 1) {
  throw "Expected at least one OpenAPI call audit row for app $($app.id)."
}
Write-Host "Call audit rows=$($audits.Count)"

Write-Host "Updating daily quota to verify quota rejection..."
$updatedApp = Invoke-NexionJson -Method Patch -Uri "$GatewayUrl/api/openapi/ops/apps/$($app.id)/quotas" -Token $script:AdminToken -Body @{
  qpsLimit = 100
  dailyLimit = 1
  remark = "smoke quota clamp"
}
if ($updatedApp.dailyLimit -ne 1) {
  throw "Expected updated dailyLimit=1, got $($updatedApp.dailyLimit)."
}
$quotaPayload = New-ReceiptPayload -UserDeviceId ([long]$device.id) -TaskType "SMOKE_OPENAPI_QUOTA" -ClientName "smoke-openapi" -RewardUsdt $RewardUsdt -RewardNex $RewardNex
$quotaResponse = Invoke-SignedReceipt -AppKey $app.appKey -AppSecret $app.appSecret -Payload $quotaPayload -Nonce "smoke-$([guid]::NewGuid().ToString())"
Assert-ApiCode -Response $quotaResponse -ExpectedCode 429 -Context "daily quota"
Write-Host "Daily quota rejection OK code=429"

Write-Host "Disabling app and verifying signed calls are rejected..."
$disabledApp = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/openapi/ops/apps/$($app.id)/disable" -Token $script:AdminToken
if ($disabledApp.status -ne "DISABLED") {
  throw "Expected app status DISABLED, got $($disabledApp.status)."
}
$disabledPayload = New-ReceiptPayload -UserDeviceId ([long]$device.id) -TaskType "SMOKE_OPENAPI_DISABLED" -ClientName "smoke-openapi" -RewardUsdt $RewardUsdt -RewardNex $RewardNex
$disabledResponse = Invoke-SignedReceipt -AppKey $app.appKey -AppSecret $app.appSecret -Payload $disabledPayload -Nonce "smoke-$([guid]::NewGuid().ToString())"
Assert-ApiCode -Response $disabledResponse -ExpectedCode 403 -Context "disabled app"
Write-Host "Disabled app rejection OK code=403"

Write-Host "Re-enabling app for cleanup..."
$enabledApp = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/openapi/ops/apps/$($app.id)/enable" -Token $script:AdminToken
if ($enabledApp.status -ne "ACTIVE") {
  throw "Expected app status ACTIVE, got $($enabledApp.status)."
}

Write-Host "OpenAPI smoke completed. appId=$($app.id), ownerUserId=$($script:UserId), receiptNo=$($receipt.receiptNo)"

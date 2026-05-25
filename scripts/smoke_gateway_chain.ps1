param(
  [string]$GatewayUrl = "http://127.0.0.1:8090",
  [string]$CountryCode = "+1",
  [string]$Phone = "",
  [string]$Password = "Nexion123456",
  [string]$ReferralCode = "NX4892",
  [int]$Quantity = 1,
  [decimal]$RewardUsdt = 0.018,
  [decimal]$RewardNex = 3.2,
  [switch]$CheckRateLimit,
  [int]$RateLimitBurst = 25
)

$ErrorActionPreference = "Stop"
$script:Token = $null
$script:UserId = $null

function Invoke-GatewayJson {
  param(
    [Parameter(Mandatory = $true)][string]$Method,
    [Parameter(Mandatory = $true)][string]$Uri,
    [object]$Body = $null,
    [switch]$Anonymous
  )

  $headers = @{}
  if (-not $Anonymous) {
    if (-not $script:Token) {
      throw "Gateway token is missing. Login must complete before authenticated requests."
    }
    $headers.Authorization = "Bearer $script:Token"
  }

  $args = @{
    Method = $Method
    Uri = $Uri
    Headers = $headers
    TimeoutSec = 20
  }
  if ($null -ne $Body) {
    $args.ContentType = "application/json"
    $args.Body = ($Body | ConvertTo-Json -Depth 8)
  }

  $response = Invoke-RestMethod @args
  if ($null -ne $response.code -and $response.code -ne 0) {
    throw "Request failed: $Method $Uri -> code=$($response.code), message=$($response.message)"
  }
  return $response.data
}

function Assert-GatewayHealth {
  try {
    $health = Invoke-RestMethod -Method Get -Uri "$GatewayUrl/actuator/health" -TimeoutSec 8
    Write-Host "OK health gateway -> $($health.status)"
  } catch {
    throw "Gateway is not reachable at $GatewayUrl. Start the gateway chain services before running this smoke script."
  }
}

function Assert-Unauthorized {
  param([Parameter(Mandatory = $true)][string]$Uri)

  try {
    Invoke-RestMethod -Method Get -Uri $Uri -TimeoutSec 8 | Out-Null
  } catch {
    $statusCode = $null
    if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
      $statusCode = [int]$_.Exception.Response.StatusCode
    }
    if ($statusCode -eq 401) {
      Write-Host "OK gateway rejected anonymous business route -> 401"
      return
    }
    throw "Expected 401 from $Uri, got $statusCode. $($_.Exception.Message)"
  }

  throw "Expected 401 from $Uri, but the request succeeded."
}

function Assert-RateLimited {
  param(
    [Parameter(Mandatory = $true)][string]$Uri,
    [int]$Attempts = 25
  )

  $unauthorized = 0
  $rateLimitIp = "203.0.113.$(Get-Random -Minimum 1 -Maximum 254)"
  $headers = @{ "X-Forwarded-For" = $rateLimitIp }
  for ($i = 1; $i -le $Attempts; $i++) {
    try {
      Invoke-RestMethod -Method Get -Uri "$Uri&rateSmoke=$i" -Headers $headers -TimeoutSec 8 | Out-Null
    } catch {
      $statusCode = $null
      if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
        $statusCode = [int]$_.Exception.Response.StatusCode
      }
      if ($statusCode -eq 429) {
        Write-Host "OK gateway rate limited anonymous route -> 429 after $i attempts"
        return
      }
      if ($statusCode -eq 401) {
        $unauthorized++
        continue
      }
      throw "Expected 401 or 429 from rate-limit route, got $statusCode. $($_.Exception.Message)"
    }
  }

  throw "Expected gateway rate limit 429 within $Attempts attempts, saw $unauthorized unauthorized responses."
}

function First-Record {
  param([object]$Page)
  if ($null -eq $Page -or $null -eq $Page.records -or $Page.records.Count -lt 1) {
    throw "Expected at least one record, got none."
  }
  return $Page.records[0]
}

Assert-GatewayHealth
Assert-Unauthorized "$GatewayUrl/api/commerce/products?pageNum=1&pageSize=1&status=ON_SALE"
if ($CheckRateLimit) {
  Assert-RateLimited "$GatewayUrl/api/commerce/products?pageNum=1&pageSize=1&status=ON_SALE" $RateLimitBurst
}

if (-not $Phone) {
  $Phone = "9$(Get-Date -Format "MMddHHmmss")$(Get-Random -Minimum 10 -Maximum 99)"
}

Write-Host "Registering a real user through gateway auth route..."
$registered = Invoke-GatewayJson -Method Post -Uri "$GatewayUrl/api/auth/users/register" -Anonymous -Body @{
  countryCode = $CountryCode
  phone = $Phone
  password = $Password
  referralCode = $ReferralCode
}
if (-not $registered.token -or $registered.token.StartsWith("dev-")) {
  throw "User register did not return a real JWT token."
}
$registeredUserId = [long]$registered.userId
Write-Host "Registered user $registeredUserId / $($registered.referralCode)"

Write-Host "Logging in as the real user through gateway auth route..."
$login = Invoke-GatewayJson -Method Post -Uri "$GatewayUrl/api/auth/users/login" -Anonymous -Body @{
  countryCode = $CountryCode
  phone = $Phone
  password = $Password
}
$script:Token = $login.token
$script:UserId = [long]$login.userId
if (-not $script:Token -or $script:Token.StartsWith("dev-")) {
  throw "User login did not return a real JWT token."
}
if ($script:UserId -ne $registeredUserId) {
  throw "Registered userId $registeredUserId does not match login userId $($script:UserId)."
}
Write-Host "Gateway user login OK for userId=$($script:UserId), phone=$CountryCode $Phone"

Write-Host "Checking BFF route through gateway..."
$bff = Invoke-GatewayJson -Method Get -Uri "$GatewayUrl/api/bff/ops/overview"
Write-Host "BFF service=$($bff.service), domain=$($bff.domain)"

$stamp = Get-Date -Format "yyyyMMddHHmmss"

Write-Host "Finding an on-sale product through gateway..."
$products = Invoke-GatewayJson -Method Get -Uri "$GatewayUrl/api/commerce/products?pageNum=1&pageSize=1&status=ON_SALE"
$product = First-Record $products
Write-Host "Using product $($product.id) / $($product.productNo) / $($product.name)"

Write-Host "Creating order through gateway..."
$order = Invoke-GatewayJson -Method Post -Uri "$GatewayUrl/api/commerce/orders" -Body @{
  userId = $script:UserId
  productId = $product.id
  quantity = $Quantity
}
Write-Host "Created order $($order.orderNo)"

Write-Host "Marking order paid through gateway; commerce should activate compute devices..."
$order = Invoke-GatewayJson -Method Put -Uri "$GatewayUrl/api/commerce/orders/$($order.orderNo)/paid" -Body @{
  paymentNo = "SMOKE-GW-PAY-$stamp"
}
Write-Host "Order activationStatus=$($order.activationStatus)"

Write-Host "Loading activated device through gateway..."
$devices = Invoke-GatewayJson -Method Get -Uri "$GatewayUrl/api/compute/devices?sourceOrderNo=$($order.orderNo)&pageNum=1&pageSize=1"
$device = First-Record $devices
Write-Host "Activated device $($device.id) / $($device.instanceNo)"

Write-Host "Creating compute receipt through gateway; compute should settle earnings after commit..."
$receipt = Invoke-GatewayJson -Method Post -Uri "$GatewayUrl/api/compute/receipts" -Body @{
  userDeviceId = $device.id
  taskType = "SMOKE_GATEWAY_INFERENCE"
  clientName = "smoke-gateway-chain"
  rewardUsdt = $RewardUsdt
  rewardNex = $RewardNex
}
Write-Host "Created receipt $($receipt.receiptNo), earningStatus=$($receipt.earningStatus)"

Start-Sleep -Seconds 2

Write-Host "Loading earning events through gateway..."
$eventsPage = Invoke-GatewayJson -Method Get -Uri "$GatewayUrl/api/earnings/events?receiptNo=$($receipt.receiptNo)&pageNum=1&pageSize=10"
if ($eventsPage.records.Count -lt 1) {
  throw "No earning events found for receipt $($receipt.receiptNo)"
}
$eventsPage.records | ForEach-Object {
  Write-Host "Earning event $($_.eventNo) $($_.asset) $($_.amount) status=$($_.status)"
}

Write-Host "Checking earnings analytics through gateway..."
$trendEndDate = Get-Date -Format "yyyy-MM-dd"
$trendStartDate = (Get-Date).AddDays(-13).ToString("yyyy-MM-dd")
$trend = Invoke-GatewayJson -Method Get -Uri "$GatewayUrl/api/earnings/analytics/trend?userId=$($script:UserId)&startDate=$trendStartDate&endDate=$trendEndDate"
if ($trend.points.Count -lt 1 -or $trend.totalUsdt -lt 0) {
  throw "Unexpected gateway earnings trend response."
}
$milestones = Invoke-GatewayJson -Method Get -Uri "$GatewayUrl/api/earnings/analytics/milestones?userId=$($script:UserId)"
if ($milestones.milestones.Count -lt 5) {
  throw "Expected at least 5 gateway earnings milestones."
}
$joinedAt = [System.Uri]::EscapeDataString((Get-Date).AddDays(-2).ToString("s"))
$missedIncome = Invoke-GatewayJson -Method Get -Uri "$GatewayUrl/api/earnings/analytics/missed-income?userId=$($script:UserId)&joinedAt=$joinedAt"
if ($missedIncome.dailyGapUsdt -le 0 -or $missedIncome.daysSinceJoin -lt 1) {
  throw "Unexpected gateway missed income response."
}
Write-Host "Gateway earnings analytics trendDays=$($trend.points.Count), lifetime=$($milestones.lifetimeUsdt), missed=$($missedIncome.cumulativeMissedUsdt)"

Write-Host "Posting any pending earnings to wallet through gateway for retry safety..."
$postResult = Invoke-GatewayJson -Method Post -Uri "$GatewayUrl/api/wallet/earnings/post-pending" -Body @{
  limit = 100
}
Write-Host "Wallet post-pending requested=$($postResult.requested), posted=$($postResult.posted)"

Write-Host "Loading wallet through gateway..."
$wallet = Invoke-GatewayJson -Method Get -Uri "$GatewayUrl/api/wallet/users/$($script:UserId)"
Write-Host "Wallet user=$($wallet.userId), USDT=$($wallet.usdtAvailable), NEX=$($wallet.nexAvailable), lifetime=$($wallet.lifetimeEarned)"

Write-Host "Gateway real-user smoke chain completed."

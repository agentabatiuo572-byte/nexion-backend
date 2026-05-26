param(
  [string]$GatewayUrl = "http://127.0.0.1:8090",
  [string]$CountryCode = "+1",
  [string]$Phone = "",
  [string]$Password = "Nexion123456",
  [string]$ReferralCode = "NX4892",
  [string]$AdminUsername = "superadmin",
  [string]$AdminPassword = $env:NEXION_SMOKE_ADMIN_PASSWORD,
  [string]$AdminToken = "",
  [string]$SeriesCode = "GENESIS-2026",
  [int]$Quantity = 1,
  [decimal]$FundAmountUsdt = 0,
  [decimal]$FundBufferUsdt = 100
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
    TimeoutSec = 25
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

  Write-Host "Logging in as admin for KYC and wallet funding..."
  $login = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/admin/login" -Body @{
    username = $AdminUsername
    password = $AdminPassword
  }
  $script:AdminToken = $login.token
  if (-not $script:AdminToken) {
    throw "Admin login did not return a token."
  }
  if ($login.admin -and $login.admin.authorities) {
    foreach ($authority in @("PERM_COMPLIANCE_WRITE", "PERM_WALLET_WRITE")) {
      if ($login.admin.authorities -notcontains $authority) {
        throw "Admin token does not include $authority. Run scripts/seed.sql or pass -AdminToken with the required authority."
      }
    }
  }
}

function First-Record {
  param([object]$Page)
  [object[]]$records = @()
  if ($null -ne $Page -and $null -ne $Page.records) {
    $records = @($Page.records)
  }
  if ($records.Count -lt 1) {
    throw "Expected at least one record, got none."
  }
  return $records[0]
}

function Find-Series {
  param([object]$Overview)
  $series = @($Overview.series) | Where-Object { $_.seriesCode -eq $SeriesCode } | Select-Object -First 1
  if ($null -eq $series) {
    throw "Genesis series $SeriesCode was not found in overview."
  }
  return $series
}

function Ensure-KycApproved {
  param([long]$UserId, [string]$Stamp)

  Write-Host "Preparing approved KYC profile for userId=$UserId..."
  $kyc = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/compliance/kyc-profiles" -Token $script:AdminToken -Body @{
    userId = $UserId
    kycNo = "SMOKE-GENESIS-KYC-$Stamp"
    country = "US"
    applicantName = "Genesis Smoke User"
    documentType = "PASSPORT"
    documentLast4 = "2026"
    documentObjectKey = "smoke/genesis/kyc/$UserId/$Stamp.txt"
  }
  $expiresAt = (Get-Date).AddYears(1).ToString("yyyy-MM-ddTHH:mm:ss")
  $approved = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/compliance/kyc-profiles/$UserId/approve" -Token $script:AdminToken -Body @{
    reviewer = "smoke-genesis"
    reason = "Genesis smoke KYC approval"
    expiresAt = $expiresAt
  }
  if ($approved.status -ne "APPROVED") {
    throw "Expected KYC status APPROVED, got $($approved.status)."
  }
  Write-Host "KYC approved kycNo=$($kyc.kycNo)"
}

function Fund-Wallet {
  param([long]$UserId, [decimal]$RequiredAmount, [string]$Stamp)

  $amount = $FundAmountUsdt
  if ($amount -le 0 -or $amount -lt $RequiredAmount) {
    $amount = $RequiredAmount + $FundBufferUsdt
  }

  Write-Host "Funding wallet with $amount USDT for userId=$UserId..."
  $ledger = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/wallet/credits/post" -Token $script:AdminToken -Body @{
    userId = $UserId
    bizNo = "SMOKE-GENESIS-FUND-$Stamp"
    bizType = "SMOKE_GENESIS_FUND"
    asset = "USDT"
    amount = $amount
    remark = "Genesis smoke funding"
  }
  if ($ledger.status -ne "SUCCESS") {
    throw "Expected funding ledger SUCCESS, got $($ledger.status)."
  }
  Write-Host "Funding ledger id=$($ledger.id), amount=$($ledger.amount)"
}

Assert-ServiceHealth
Ensure-AdminToken

if (-not $Phone) {
  $Phone = "8$(Get-Date -Format "MMddHHmmss")$(Get-Random -Minimum 10 -Maximum 99)"
}

$stamp = Get-Date -Format "yyyyMMddHHmmss"

Write-Host "Registering a real user for Genesis smoke..."
$registered = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/users/register" -Body @{
  countryCode = $CountryCode
  phone = $Phone
  password = $Password
  referralCode = $ReferralCode
}
$registeredUserId = [long]$registered.userId

Write-Host "Logging in as Genesis smoke user..."
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
Write-Host "Genesis smoke userId=$($script:UserId)"

Write-Host "Loading Genesis overview through gateway..."
$overview = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/genesis/overview?userId=$($script:UserId)" -Token $script:UserToken
$series = Find-Series -Overview $overview
$unitPrice = [decimal]$series.priceUsdt
$requiredAmount = $unitPrice * $Quantity
Write-Host "Using series=$($series.seriesCode), price=$unitPrice, quantity=$Quantity, required=$requiredAmount"

Ensure-KycApproved -UserId $script:UserId -Stamp $stamp
Fund-Wallet -UserId $script:UserId -RequiredAmount $requiredAmount -Stamp $stamp

$walletBefore = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/wallet/users/$($script:UserId)" -Token $script:UserToken
Write-Host "Wallet before purchase USDT=$($walletBefore.usdtAvailable)"

$clientRequestNo = "smoke-genesis-$stamp-$([guid]::NewGuid().ToString("N").Substring(0, 8))"
Write-Host "Purchasing Genesis node through gateway..."
$order = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/genesis/orders" -Token $script:UserToken -Body @{
  userId = $script:UserId
  seriesCode = $SeriesCode
  quantity = $Quantity
  clientRequestNo = $clientRequestNo
}
if ($order.status -eq "REVIEWING") {
  throw "Genesis order entered REVIEWING. Raise NEXION_COMPLIANCE_GENESIS_REVIEW_AMOUNT above $($order.amountUsdt) or use default start_gateway_chain_services.ps1 settings for this smoke."
}
if ($order.status -ne "COMPLETED") {
  throw "Expected Genesis order COMPLETED, got $($order.status)."
}
if (-not $order.walletLedgerId) {
  throw "Genesis order completed without walletLedgerId."
}
Write-Host "Genesis order completed orderNo=$($order.orderNo), amount=$($order.amountUsdt), walletLedgerId=$($order.walletLedgerId)"

$loadedOrder = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/genesis/orders/$($order.orderNo)" -Token $script:UserToken
if ($loadedOrder.orderNo -ne $order.orderNo -or $loadedOrder.status -ne "COMPLETED") {
  throw "Genesis order detail did not match completed order."
}

$holdingsPage = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/genesis/holdings?userId=$($script:UserId)&seriesCode=$SeriesCode&pageNum=1&pageSize=20" -Token $script:UserToken
$matchingHoldings = @(@($holdingsPage.records) | Where-Object { $_.orderNo -eq $order.orderNo })
if ($matchingHoldings.Count -lt $Quantity) {
  throw "Expected at least $Quantity holdings for order $($order.orderNo), got $($matchingHoldings.Count)."
}
Write-Host "Genesis holdings allocated count=$($matchingHoldings.Count)"

$ledgerPage = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/wallet/ledgers?userId=$($script:UserId)&bizNo=$($order.orderNo)&asset=USDT&direction=OUT&pageNum=1&pageSize=10" -Token $script:UserToken
$debit = First-Record $ledgerPage
if ($debit.status -ne "SUCCESS" -or $debit.bizType -ne "GENESIS_PURCHASE") {
  throw "Expected GENESIS_PURCHASE debit ledger SUCCESS, got status=$($debit.status), bizType=$($debit.bizType)."
}
Write-Host "Wallet debit verified ledgerId=$($debit.id), amount=$($debit.amount)"

$outboxRows = @(Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/commerce/outbox/aggregates/GENESIS_ORDER/$($order.orderNo)?limit=5" -Token $script:AdminToken)
$event = $outboxRows | Where-Object { $_.eventType -eq "GenesisPurchased" } | Select-Object -First 1
if ($null -eq $event) {
  throw "Expected GenesisPurchased outbox event for order $($order.orderNo)."
}
Write-Host "Genesis outbox verified eventId=$($event.eventId), status=$($event.status)"

$walletAfter = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/wallet/users/$($script:UserId)" -Token $script:UserToken
Write-Host "Wallet after purchase USDT=$($walletAfter.usdtAvailable)"

Write-Host "Genesis smoke completed. userId=$($script:UserId), orderNo=$($order.orderNo), holdings=$($matchingHoldings.Count), outboxEvent=$($event.eventId)"

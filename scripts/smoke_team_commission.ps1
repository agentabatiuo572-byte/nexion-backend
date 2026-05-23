param(
  [string]$AuthUrl = "http://127.0.0.1:8101",
  [string]$CommerceUrl = "http://127.0.0.1:8104",
  [string]$TeamUrl = "http://127.0.0.1:8106",
  [string]$WalletUrl = "http://127.0.0.1:8105",
  [string]$GatewaySecret = "nexion-local-gateway-secret",
  [string]$CountryCode = "+1",
  [string]$Phone = "",
  [string]$Password = "Nexion123456",
  [string]$ReferralCode = "NX4892",
  [long]$SponsorUserId = 10001,
  [string]$UnlockBefore = "2099-01-01T00:00:00",
  [switch]$RequireWorker,
  [switch]$CheckBrokerMonitor
)

$ErrorActionPreference = "Stop"

$InternalHeaders = @{
  "X-Nexion-Gateway-Secret" = $GatewaySecret
  "X-Nexion-Subject-Id" = "0"
  "X-Nexion-Subject-Type" = "SERVICE"
  "X-Nexion-Username" = "smoke-team-commission"
  "X-Nexion-Authorities" = "PERM_COMMERCE_WRITE,PERM_COMMERCE_READ,PERM_TEAM_READ,PERM_TEAM_WRITE,PERM_WALLET_READ,PERM_WALLET_WRITE"
}

function Invoke-NexionJson {
  param(
    [Parameter(Mandatory = $true)][string]$Method,
    [Parameter(Mandatory = $true)][string]$Uri,
    [object]$Body = $null,
    [hashtable]$Headers = $InternalHeaders
  )

  $args = @{
    Method = $Method
    Uri = $Uri
    Headers = $Headers
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

function Assert-ServiceHealth {
  param(
    [Parameter(Mandatory = $true)][string]$Name,
    [Parameter(Mandatory = $true)][string]$BaseUrl
  )

  try {
    $health = Invoke-RestMethod -Method Get -Uri "$BaseUrl/actuator/health" -TimeoutSec 8
    Write-Host "OK health $Name -> $($health.status)"
  } catch {
    throw "$Name is not reachable at $BaseUrl. Start the service before running this smoke script."
  }
}

function First-Record {
  param([object]$Page)
  if ($null -eq $Page -or $null -eq $Page.records -or $Page.records.Count -lt 1) {
    throw "Expected at least one record, got none."
  }
  return $Page.records[0]
}

function Find-CommissionForOrder {
  param(
    [Parameter(Mandatory = $true)][string]$OrderNo,
    [Parameter(Mandatory = $true)][long]$UserId
  )

  $page = Invoke-NexionJson -Method Get -Uri "$TeamUrl/team/commissions?userId=$UserId&pageNum=1&pageSize=50"
  if ($null -eq $page -or $null -eq $page.records) {
    return $null
  }
  return $page.records | Where-Object { $_.orderNo -eq $OrderNo } | Select-Object -First 1
}

function Wait-CommissionForOrder {
  param(
    [Parameter(Mandatory = $true)][string]$OrderNo,
    [Parameter(Mandatory = $true)][long]$UserId,
    [int]$TimeoutSeconds = 30
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    $commission = Find-CommissionForOrder -OrderNo $OrderNo -UserId $UserId
    if ($null -ne $commission) {
      return $commission
    }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  return $null
}

Assert-ServiceHealth "auth" $AuthUrl
Assert-ServiceHealth "commerce" $CommerceUrl
Assert-ServiceHealth "team" $TeamUrl
Assert-ServiceHealth "wallet" $WalletUrl

if (-not $Phone) {
  $Phone = "8$(Get-Date -Format "MMddHHmmss")$(Get-Random -Minimum 10 -Maximum 99)"
}

Write-Host "Registering referred user..."
$registered = Invoke-NexionJson -Method Post -Uri "$AuthUrl/auth/users/register" -Headers @{} -Body @{
  countryCode = $CountryCode
  phone = $Phone
  password = $Password
  referralCode = $ReferralCode
}
$buyerUserId = [long]$registered.userId
Write-Host "Registered buyer userId=$buyerUserId with sponsor referralCode=$ReferralCode"

Write-Host "Finding an on-sale product..."
$products = Invoke-NexionJson -Method Get -Uri "$CommerceUrl/commerce/products?pageNum=1&pageSize=1&status=ON_SALE"
$product = First-Record $products

$stamp = Get-Date -Format "yyyyMMddHHmmss"
Write-Host "Creating order for buyer $buyerUserId..."
$order = Invoke-NexionJson -Method Post -Uri "$CommerceUrl/commerce/orders" -Body @{
  userId = $buyerUserId
  productId = $product.id
  quantity = 1
}
Write-Host "Created order $($order.orderNo)"

Write-Host "Marking order paid to create OrderPaid outbox event..."
$order = Invoke-NexionJson -Method Put -Uri "$CommerceUrl/commerce/orders/$($order.orderNo)/paid" -Body @{
  paymentNo = "SMOKE-TEAM-PAY-$stamp"
}
Write-Host "Order activationStatus=$($order.activationStatus)"

Write-Host "Waiting for team outbox worker/listener to consume OrderPaid..."
$commission = Wait-CommissionForOrder -OrderNo $order.orderNo -UserId $SponsorUserId -TimeoutSeconds 30
if ($null -eq $commission) {
  if ($RequireWorker) {
    throw "Expected team outbox worker/listener to create commission for order $($order.orderNo)."
  }
  Write-Host "Worker/listener did not consume within timeout; falling back to manual consume endpoint..."
  $consume = Invoke-NexionJson -Method Post -Uri "$TeamUrl/team/outbox/consume-order-paid?limit=50"
  Write-Host "Consume scanned=$($consume.scanned), processed=$($consume.processed), failed=$($consume.failed)"
  $commission = Wait-CommissionForOrder -OrderNo $order.orderNo -UserId $SponsorUserId -TimeoutSeconds 10
  if ($null -eq $commission) {
    throw "Expected at least one commission event to be created."
  }
} else {
  Write-Host "Worker/listener created commission id=$($commission.id), status=$($commission.status)"
}

$encodedOrderNo = [uri]::EscapeDataString($order.orderNo)
Write-Host "Checking Team consumer delivery state for order $($order.orderNo)..."
$consumerDeliveries = @(Invoke-NexionJson -Method Get -Uri "$TeamUrl/team/outbox/consumer/aggregates/ORDER/${encodedOrderNo}?limit=5")
$consumerDelivery = $consumerDeliveries | Where-Object { $_.eventType -eq "OrderPaid" } | Select-Object -First 1
if ($null -eq $consumerDelivery) {
  throw "Expected Team consumer delivery state for OrderPaid order $($order.orderNo)."
}
Write-Host "Consumer delivery group=$($consumerDelivery.consumerGroup), status=$($consumerDelivery.status), attempts=$($consumerDelivery.attemptCount), deadAt=$($consumerDelivery.deadAt)"
if ($consumerDelivery.status -ne "SUCCESS") {
  throw "Expected Team consumer delivery status SUCCESS, got $($consumerDelivery.status)."
}

if ($CheckBrokerMonitor) {
  Write-Host "Checking RocketMQ broker consumer status..."
  $brokerStatus = Invoke-NexionJson -Method Get -Uri "$TeamUrl/team/outbox/broker/consumer/status?includeDlq=true"
  Write-Host "Broker monitor ok=$($brokerStatus.ok), topic=$($brokerStatus.topic), group=$($brokerStatus.consumerGroup), lag=$($brokerStatus.totalLag), dlqMessages=$($brokerStatus.dlqMessages)"
  if (-not $brokerStatus.ok) {
    throw "Expected RocketMQ broker monitor to be ok, errors=$($brokerStatus.errors -join '; ')"
  }
  if ($brokerStatus.totalLag -lt 0) {
    throw "Expected RocketMQ consumer lag to be zero or greater."
  }
  if ($brokerStatus.dlqMessages -gt 0) {
    throw "Expected RocketMQ broker DLQ to be empty, got $($brokerStatus.dlqMessages)."
  }
}

Write-Host "Loading sponsor team overview..."
$overview = Invoke-NexionJson -Method Get -Uri "$TeamUrl/team/overview?userId=$SponsorUserId"
Write-Host "Sponsor directCount=$($overview.directCount), commissionCount=$($overview.commissionCount), pendingUsdt=$($overview.pendingUsdt), pendingNex=$($overview.pendingNex)"
if ($overview.commissionCount -lt 1) {
  throw "Expected sponsor commissionCount to be greater than zero."
}

Write-Host "Loading sponsor wallet before commission unlock..."
$walletBefore = Invoke-NexionJson -Method Get -Uri "$WalletUrl/wallet/users/$SponsorUserId"
Write-Host "Before wallet USDT=$($walletBefore.usdtAvailable), NEX=$($walletBefore.nexAvailable)"

$encodedUnlockBefore = [uri]::EscapeDataString($UnlockBefore)
Write-Host "Unlocking due team commissions for order $($order.orderNo)..."
$unlock = Invoke-NexionJson -Method Post -Uri "$TeamUrl/team/commissions/unlock?limit=20&orderNo=$encodedOrderNo&unlockBefore=$encodedUnlockBefore"
Write-Host "Unlock scanned=$($unlock.scanned), posted=$($unlock.posted), failed=$($unlock.failed), walletPosts=$($unlock.walletPosts)"
if ($unlock.posted -lt 1 -or $unlock.failed -gt 0) {
  throw "Expected at least one commission to be unlocked and no unlock failures."
}

Write-Host "Loading sponsor wallet after commission unlock..."
$walletAfter = Invoke-NexionJson -Method Get -Uri "$WalletUrl/wallet/users/$SponsorUserId"
Write-Host "After wallet USDT=$($walletAfter.usdtAvailable), NEX=$($walletAfter.nexAvailable)"
if ([decimal]$walletAfter.usdtAvailable -le [decimal]$walletBefore.usdtAvailable) {
  throw "Expected sponsor USDT wallet balance to increase after commission unlock."
}
if ([decimal]$walletAfter.nexAvailable -le [decimal]$walletBefore.nexAvailable) {
  throw "Expected sponsor NEX wallet balance to increase after commission unlock."
}

Write-Host "Team commission unlock smoke completed."

param(
  [string]$CommerceUrl = "http://127.0.0.1:8104",
  [string]$ComputeUrl = "http://127.0.0.1:8102",
  [string]$EarningsUrl = "http://127.0.0.1:8108",
  [string]$WalletUrl = "http://127.0.0.1:8105",
  [string]$GatewaySecret = "nexion-local-gateway-secret",
  [long]$UserId = 10001,
  [int]$Quantity = 1,
  [decimal]$RewardUsdt = 0.018,
  [decimal]$RewardNex = 3.2
)

$ErrorActionPreference = "Stop"

$InternalHeaders = @{
  "X-Nexion-Gateway-Secret" = $GatewaySecret
  "X-Nexion-Subject-Id" = "0"
  "X-Nexion-Subject-Type" = "SERVICE"
  "X-Nexion-Username" = "smoke-main-chain"
  "X-Nexion-Authorities" = "PERM_COMMERCE_WRITE,PERM_COMPUTE_READ,PERM_EARNINGS_READ,PERM_WALLET_READ"
}

function Invoke-NexionJson {
  param(
    [Parameter(Mandatory = $true)][string]$Method,
    [Parameter(Mandatory = $true)][string]$Uri,
    [object]$Body = $null
  )

  $args = @{
    Method = $Method
    Uri = $Uri
    Headers = $InternalHeaders
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

Assert-ServiceHealth "commerce" $CommerceUrl
Assert-ServiceHealth "compute" $ComputeUrl
Assert-ServiceHealth "earnings" $EarningsUrl
Assert-ServiceHealth "wallet" $WalletUrl

$stamp = Get-Date -Format "yyyyMMddHHmmss"

Write-Host "Finding an on-sale product..."
$products = Invoke-NexionJson -Method Get -Uri "$CommerceUrl/commerce/products?pageNum=1&pageSize=1&status=ON_SALE"
$product = First-Record $products
Write-Host "Using product $($product.id) / $($product.productNo) / $($product.name)"

Write-Host "Creating order..."
$order = Invoke-NexionJson -Method Post -Uri "$CommerceUrl/commerce/orders" -Body @{
  userId = $UserId
  productId = $product.id
  quantity = $Quantity
}
Write-Host "Created order $($order.orderNo)"

Write-Host "Marking order paid; commerce should activate compute devices..."
$order = Invoke-NexionJson -Method Put -Uri "$CommerceUrl/commerce/orders/$($order.orderNo)/paid" -Body @{
  paymentNo = "SMOKE-PAY-$stamp"
}
Write-Host "Order activationStatus=$($order.activationStatus)"

Write-Host "Loading activated device..."
$devices = Invoke-NexionJson -Method Get -Uri "$ComputeUrl/compute/devices?sourceOrderNo=$($order.orderNo)&pageNum=1&pageSize=1"
$device = First-Record $devices
Write-Host "Activated device $($device.id) / $($device.instanceNo)"

Write-Host "Creating compute receipt; compute should settle earnings after commit..."
$receipt = Invoke-NexionJson -Method Post -Uri "$ComputeUrl/compute/receipts" -Body @{
  userDeviceId = $device.id
  taskType = "SMOKE_INFERENCE"
  clientName = "smoke-main-chain"
  rewardUsdt = $RewardUsdt
  rewardNex = $RewardNex
}
Write-Host "Created receipt $($receipt.receiptNo), earningStatus=$($receipt.earningStatus)"

Start-Sleep -Seconds 2

Write-Host "Loading earning events..."
$eventsPage = Invoke-NexionJson -Method Get -Uri "$EarningsUrl/earnings/events?receiptNo=$($receipt.receiptNo)&pageNum=1&pageSize=10"
if ($eventsPage.records.Count -lt 1) {
  throw "No earning events found for receipt $($receipt.receiptNo)"
}
$eventsPage.records | ForEach-Object {
  Write-Host "Earning event $($_.eventNo) $($_.asset) $($_.amount) status=$($_.status)"
}

Write-Host "Posting any pending earnings to wallet for retry safety..."
$postResult = Invoke-NexionJson -Method Post -Uri "$WalletUrl/wallet/earnings/post-pending" -Body @{
  limit = 100
}
Write-Host "Wallet post-pending requested=$($postResult.requested), posted=$($postResult.posted)"

Write-Host "Loading wallet..."
$wallet = Invoke-NexionJson -Method Get -Uri "$WalletUrl/wallet/users/$UserId"
Write-Host "Wallet user=$($wallet.userId), USDT=$($wallet.usdtAvailable), NEX=$($wallet.nexAvailable), lifetime=$($wallet.lifetimeEarned)"

Write-Host "Smoke chain completed."

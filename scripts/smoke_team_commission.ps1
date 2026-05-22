param(
  [string]$AuthUrl = "http://127.0.0.1:8101",
  [string]$CommerceUrl = "http://127.0.0.1:8104",
  [string]$TeamUrl = "http://127.0.0.1:8106",
  [string]$GatewaySecret = "nexion-local-gateway-secret",
  [string]$CountryCode = "+1",
  [string]$Phone = "",
  [string]$Password = "Nexion123456",
  [string]$ReferralCode = "NX4892",
  [long]$SponsorUserId = 10001
)

$ErrorActionPreference = "Stop"

$InternalHeaders = @{
  "X-Nexion-Gateway-Secret" = $GatewaySecret
  "X-Nexion-Subject-Id" = "0"
  "X-Nexion-Subject-Type" = "SERVICE"
  "X-Nexion-Username" = "smoke-team-commission"
  "X-Nexion-Authorities" = "PERM_COMMERCE_WRITE,PERM_COMMERCE_READ,PERM_TEAM_READ,PERM_TEAM_WRITE"
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

Assert-ServiceHealth "auth" $AuthUrl
Assert-ServiceHealth "commerce" $CommerceUrl
Assert-ServiceHealth "team" $TeamUrl

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

Write-Host "Consuming OrderPaid outbox event in team-service..."
$consume = Invoke-NexionJson -Method Post -Uri "$TeamUrl/team/outbox/consume-order-paid?limit=50"
Write-Host "Consume scanned=$($consume.scanned), processed=$($consume.processed), failed=$($consume.failed)"
if ($consume.processed -lt 1) {
  throw "Expected at least one commission event to be created."
}

Write-Host "Loading sponsor team overview..."
$overview = Invoke-NexionJson -Method Get -Uri "$TeamUrl/team/overview?userId=$SponsorUserId"
Write-Host "Sponsor directCount=$($overview.directCount), commissionCount=$($overview.commissionCount), pendingUsdt=$($overview.pendingUsdt), pendingNex=$($overview.pendingNex)"
if ($overview.commissionCount -lt 1) {
  throw "Expected sponsor commissionCount to be greater than zero."
}

Write-Host "Team commission smoke completed."

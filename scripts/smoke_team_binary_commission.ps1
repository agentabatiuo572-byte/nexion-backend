param(
  [string]$AuthUrl = "http://127.0.0.1:8101",
  [string]$CommerceUrl = "http://127.0.0.1:8104",
  [string]$TeamUrl = "http://127.0.0.1:8106",
  [string]$WalletUrl = "http://127.0.0.1:8105",
  [string]$GatewaySecret = "nexion-local-gateway-secret",
  [string]$CountryCode = "+1",
  [string]$Password = "Nexion123456",
  [string]$ReferralCode = "NX4892",
  [string]$UnlockBefore = "2099-01-01T00:00:00"
)

$ErrorActionPreference = "Stop"

$InternalHeaders = @{
  "X-Nexion-Gateway-Secret" = $GatewaySecret
  "X-Nexion-Subject-Id" = "0"
  "X-Nexion-Subject-Type" = "SERVICE"
  "X-Nexion-Username" = "smoke-team-binary"
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
    TimeoutSec = 25
  }
  if ($null -ne $Body) {
    $args.ContentType = "application/json"
    $args.Body = ($Body | ConvertTo-Json -Depth 8 -Compress)
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

function Register-SmokeUser {
  param(
    [Parameter(Mandatory = $true)][string]$Phone,
    [Parameter(Mandatory = $true)][string]$SponsorReferralCode
  )

  return Invoke-NexionJson -Method Post -Uri "$AuthUrl/auth/users/register" -Headers @{} -Body @{
    countryCode = $CountryCode
    phone = $Phone
    password = $Password
    referralCode = $SponsorReferralCode
  }
}

function Create-PaidOrder {
  param(
    [Parameter(Mandatory = $true)][long]$UserId,
    [Parameter(Mandatory = $true)][long]$ProductId,
    [Parameter(Mandatory = $true)][string]$PaymentNo
  )

  $order = Invoke-NexionJson -Method Post -Uri "$CommerceUrl/commerce/orders" -Body @{
    userId = $UserId
    productId = $ProductId
    quantity = 1
  }
  return Invoke-NexionJson -Method Put -Uri "$CommerceUrl/commerce/orders/$($order.orderNo)/paid" -Body @{
    paymentNo = $PaymentNo
  }
}

function Wait-BinaryCommission {
  param(
    [Parameter(Mandatory = $true)][long]$UserId,
    [Parameter(Mandatory = $true)][string]$OrderNo,
    [int]$TimeoutSeconds = 20
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    $page = Invoke-NexionJson -Method Get -Uri "$TeamUrl/team/commissions?userId=$UserId&pageNum=1&pageSize=50"
    if ($page -and $page.records) {
      $commission = $page.records |
        Where-Object { $_.commissionType -eq "BINARY" -and $_.orderNo -eq $OrderNo } |
        Select-Object -First 1
      if ($null -ne $commission) {
        return $commission
      }
    }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  return $null
}

function Wait-SponsorDirectCount {
  param(
    [Parameter(Mandatory = $true)][long]$SponsorUserId,
    [int]$TimeoutSeconds = 40
  )

  $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
  do {
    $consume = Invoke-NexionJson -Method Post -Uri "$TeamUrl/team/outbox/consume-order-paid?limit=100"
    Write-Host "Consume scanned=$($consume.scanned), processed=$($consume.processed), failed=$($consume.failed)"
    $overview = Invoke-NexionJson -Method Get -Uri "$TeamUrl/team/overview?userId=$SponsorUserId"
    Write-Host "Sponsor directCount=$($overview.directCount), teamCount=$($overview.teamCount), commissionCount=$($overview.commissionCount)"
    if ($overview.directCount -ge 2) {
      return $overview
    }
    Start-Sleep -Seconds 2
  } while ((Get-Date) -lt $deadline)
  return $overview
}

Assert-ServiceHealth "auth" $AuthUrl
Assert-ServiceHealth "commerce" $CommerceUrl
Assert-ServiceHealth "team" $TeamUrl
Assert-ServiceHealth "wallet" $WalletUrl

$stamp = Get-Date -Format "MMddHHmmss"
$sponsorPhone = "91$stamp$(Get-Random -Minimum 10 -Maximum 99)"
$leftPhone = "92$stamp$(Get-Random -Minimum 10 -Maximum 99)"
$rightPhone = "93$stamp$(Get-Random -Minimum 10 -Maximum 99)"

Write-Host "Registering binary sponsor..."
$sponsor = Register-SmokeUser -Phone $sponsorPhone -SponsorReferralCode $ReferralCode
$sponsorUserId = [long]$sponsor.userId
Write-Host "Sponsor userId=$sponsorUserId, referralCode=$($sponsor.referralCode)"

Write-Host "Registering left and right direct legs..."
$left = Register-SmokeUser -Phone $leftPhone -SponsorReferralCode $sponsor.referralCode
$right = Register-SmokeUser -Phone $rightPhone -SponsorReferralCode $sponsor.referralCode
$leftUserId = [long]$left.userId
$rightUserId = [long]$right.userId
Write-Host "Left userId=$leftUserId, right userId=$rightUserId"

Write-Host "Finding an on-sale product..."
$products = Invoke-NexionJson -Method Get -Uri "$CommerceUrl/commerce/products?pageNum=1&pageSize=1&status=ON_SALE"
$product = First-Record $products

Write-Host "Creating paid orders for both binary legs..."
$leftOrder = Create-PaidOrder -UserId $leftUserId -ProductId ([long]$product.id) -PaymentNo "SMOKE-BINARY-L-$stamp"
$rightOrder = Create-PaidOrder -UserId $rightUserId -ProductId ([long]$product.id) -PaymentNo "SMOKE-BINARY-R-$stamp"
Write-Host "Paid left order=$($leftOrder.orderNo), right order=$($rightOrder.orderNo)"

Write-Host "Consuming OrderPaid events into Team volume..."
$overview = Wait-SponsorDirectCount -SponsorUserId $sponsorUserId
if ($overview.directCount -lt 2) {
  throw "Expected sponsor to have two direct legs before binary settlement."
}

$settlementDate = Get-Date -Format "yyyy-MM-dd"
$binaryOrderNo = "BINARY-$settlementDate"
Write-Host "Settling binary commissions for $settlementDate..."
$settlement = Invoke-NexionJson -Method Post -Uri "$TeamUrl/team/commissions/binary?settlementDate=$settlementDate&limit=100"
Write-Host "Binary settlement scanned=$($settlement.scanned), created=$($settlement.created), skipped=$($settlement.skipped), failed=$($settlement.failed)"
if ($settlement.created -lt 1 -or $settlement.failed -gt 0) {
  throw "Expected at least one binary commission to be created and no failures."
}

$binaryCommission = Wait-BinaryCommission -UserId $sponsorUserId -OrderNo $binaryOrderNo
if ($null -eq $binaryCommission) {
  throw "Expected binary commission for sponsor userId=$sponsorUserId, orderNo=$binaryOrderNo."
}
Write-Host "Binary commission id=$($binaryCommission.id), amountUsdt=$($binaryCommission.amountUsdt), status=$($binaryCommission.status)"

$summary = @(Invoke-NexionJson -Method Get -Uri "$TeamUrl/team/commissions/binary/summary?settlementDate=$settlementDate&userId=$sponsorUserId&limit=10")
if (@($summary | Where-Object { $_.userId -eq $sponsorUserId }).Count -lt 1) {
  throw "Expected binary settlement summary row for sponsor userId=$sponsorUserId."
}

Write-Host "Loading sponsor wallet before binary unlock..."
$walletBefore = Invoke-NexionJson -Method Get -Uri "$WalletUrl/wallet/users/$sponsorUserId"
Write-Host "Before wallet USDT=$($walletBefore.usdtAvailable)"

$encodedOrderNo = [uri]::EscapeDataString($binaryOrderNo)
$encodedUnlockBefore = [uri]::EscapeDataString($UnlockBefore)
Write-Host "Unlocking binary commission into sponsor wallet..."
$unlock = Invoke-NexionJson -Method Post -Uri "$TeamUrl/team/commissions/unlock?limit=100&orderNo=$encodedOrderNo&unlockBefore=$encodedUnlockBefore"
Write-Host "Unlock scanned=$($unlock.scanned), posted=$($unlock.posted), failed=$($unlock.failed), walletPosts=$($unlock.walletPosts)"
if ($unlock.posted -lt 1 -or $unlock.failed -gt 0) {
  throw "Expected binary commission unlock to post at least one commission and no failures."
}

$walletAfter = Invoke-NexionJson -Method Get -Uri "$WalletUrl/wallet/users/$sponsorUserId"
Write-Host "After wallet USDT=$($walletAfter.usdtAvailable)"
if ([decimal]$walletAfter.usdtAvailable -le [decimal]$walletBefore.usdtAvailable) {
  throw "Expected sponsor USDT wallet balance to increase after binary unlock."
}

Write-Host "Team binary commission smoke completed. sponsorUserId=$sponsorUserId, binaryOrderNo=$binaryOrderNo"

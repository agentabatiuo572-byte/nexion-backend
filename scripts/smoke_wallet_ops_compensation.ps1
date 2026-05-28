param(
  [string]$GatewayUrl = "http://127.0.0.1:8090",
  [string]$CountryCode = "+1",
  [string]$Phone = "",
  [string]$Password = "Nexion123456",
  [string]$ReferralCode = "NX4892",
  [string]$AdminUsername = "superadmin",
  [string]$AdminPassword = $env:NEXION_SMOKE_ADMIN_PASSWORD,
  [string]$AdminToken = ""
)

$ErrorActionPreference = "Stop"
$script:AdminToken = $AdminToken
$script:UserId = $null

if (-not $AdminPassword) {
  $AdminPassword = "Admin@123456"
}

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

  Write-Host "Logging in as admin for Wallet ops compensation smoke..."
  $login = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/admin/login" -Body @{
    username = $AdminUsername
    password = $AdminPassword
  }
  $script:AdminToken = $login.token
  if (-not $script:AdminToken) {
    throw "Admin login did not return a token."
  }

  $authorities = @($login.admin.authorities)
  foreach ($authority in @("PERM_WALLET_READ", "PERM_WALLET_WRITE", "PERM_COMPLIANCE_WRITE", "PERM_AUDIT_READ")) {
    if ($authorities.Count -gt 0 -and $authorities -notcontains $authority) {
      throw "Admin token does not include $authority. Run scripts/seed.sql or pass -AdminToken with the required authority."
    }
  }
}

function New-SmokeUser {
  param([string]$Stamp)

  $smokePhone = $Phone
  if (-not $smokePhone) {
    $smokePhone = "8$(Get-Date -Format "MMddHHmmss")$(Get-Random -Minimum 10 -Maximum 99)"
  }

  Write-Host "Registering Wallet smoke user..."
  $registered = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/users/register" -Body @{
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
  if (-not $login.token -or $login.token.StartsWith("dev-")) {
    throw "User login did not return a real JWT token."
  }
  if ([long]$registered.userId -ne [long]$login.userId) {
    throw "Registered userId $($registered.userId) does not match login userId $($login.userId)."
  }
  $script:UserId = [long]$login.userId
  Write-Host "Wallet smoke userId=$($script:UserId), phone=$smokePhone"
}

function Ensure-KycApproved {
  param([long]$UserId, [string]$Stamp)

  Write-Host "Approving KYC for userId=$UserId..."
  $kyc = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/compliance/kyc-profiles" -Token $script:AdminToken -Body @{
    userId = $UserId
    kycNo = "SMOKE-WALLET-KYC-$Stamp"
    country = "US"
    applicantName = "Wallet Ops Smoke"
    documentType = "PASSPORT"
    documentLast4 = "2026"
    documentObjectKey = "smoke/wallet/kyc/$UserId/$Stamp.txt"
  }
  $approved = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/compliance/kyc-profiles/$UserId/approve" -Token $script:AdminToken -Body @{
    reviewer = "smoke-wallet-ops"
    reason = "Wallet ops smoke KYC approval"
    expiresAt = (Get-Date).AddYears(1).ToString("yyyy-MM-ddTHH:mm:ss")
  }
  if ($approved.status -ne "APPROVED") {
    throw "Expected KYC status APPROVED, got $($approved.status)."
  }
  Write-Host "KYC approved kycNo=$($kyc.kycNo)"
}

function Find-RequiredRecord {
  param(
    [object[]]$Records,
    [scriptblock]$Predicate,
    [string]$Context
  )

  $record = @($Records | Where-Object $Predicate | Select-Object -First 1)
  if ($record.Count -lt 1) {
    throw "Expected record was not found: $Context"
  }
  return $record[0]
}

function Assert-AuditLog {
  param(
    [string]$Action,
    [string]$BizNo
  )

  $encodedAction = [System.Uri]::EscapeDataString($Action)
  $encodedBizNo = [System.Uri]::EscapeDataString($BizNo)
  $logs = @(Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/audit/logs?action=$encodedAction&bizNo=$encodedBizNo&limit=20" -Token $script:AdminToken)
  if (@($logs | Where-Object { $_.action -eq $Action -and $_.bizNo -eq $BizNo }).Count -lt 1) {
    throw "Expected audit log action=$Action, bizNo=$BizNo."
  }
  Write-Host "Audit verified action=$Action, bizNo=$BizNo"
}

Assert-ServiceHealth
Ensure-AdminToken

$stamp = Get-Date -Format "yyyyMMddHHmmss"
New-SmokeUser -Stamp $stamp
Ensure-KycApproved -UserId $script:UserId -Stamp $stamp

Write-Host "Posting manual deposit through Wallet ops..."
$depositTx = "0xWALLETOPS$stamp$([guid]::NewGuid().ToString("N").Substring(0, 8))"
$deposit = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/wallet/ops/deposits/manual" -Token $script:AdminToken -Body @{
  userId = $script:UserId
  chain = "TRON"
  chainTxHash = $depositTx
  asset = "USDT"
  amount = "0.100000"
  confirmations = 20
  reason = "Wallet ops compensation smoke manual deposit"
}
if ($deposit.status -ne "SUCCESS" -or -not $deposit.ledgerId) {
  throw "Expected manual deposit SUCCESS with ledgerId, got status=$($deposit.status), ledgerId=$($deposit.ledgerId)."
}
Write-Host "Manual deposit posted depositNo=$($deposit.depositNo), ledgerId=$($deposit.ledgerId)"

$depositRecords = @(Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/wallet/deposits/records?chainTxHash=$depositTx&asset=USDT" -Token $script:AdminToken)
$null = Find-RequiredRecord -Records $depositRecords -Predicate { $_.depositNo -eq $deposit.depositNo -and $_.status -eq "SUCCESS" } -Context "manual deposit readback"

Write-Host "Retrying the same deposit idempotently..."
$retriedDeposit = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/wallet/ops/deposits/$($deposit.depositNo)/retry" -Token $script:AdminToken -Body @{
  reason = "Wallet ops compensation smoke retry"
}
if ($retriedDeposit.depositNo -ne $deposit.depositNo -or $retriedDeposit.status -ne "SUCCESS") {
  throw "Expected deposit retry to return the same SUCCESS order."
}

$walletAfterDeposit = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/wallet/users/$($script:UserId)" -Token $script:AdminToken
Write-Host "Wallet after deposit USDT=$($walletAfterDeposit.usdtAvailable), pending=$($walletAfterDeposit.pendingWithdraw)"

Write-Host "Creating withdrawal for manual failure refund path..."
$failedWithdrawalNo = "SMOKE-WALLET-WD-F-$stamp"
$failedWithdrawal = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/wallet/withdrawals" -Token $script:AdminToken -Body @{
  userId = $script:UserId
  withdrawalNo = $failedWithdrawalNo
  asset = "USDT"
  amount = "0.000010"
  fee = "0.000000"
  targetAddress = "TSmokeWalletFailed$stamp"
}
if ($failedWithdrawal.status -ne "PENDING_CHAIN") {
  throw "Expected failed-path withdrawal PENDING_CHAIN, got $($failedWithdrawal.status)."
}

$failedResult = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/wallet/ops/withdrawals/$failedWithdrawalNo/mark-failed" -Token $script:AdminToken -Body @{
  reason = "Wallet ops compensation smoke failure refund"
}
if ($failedResult.status -ne "FAILED" -or $failedResult.failureReason -notlike "*failure refund*") {
  throw "Expected manual failed withdrawal, got status=$($failedResult.status), reason=$($failedResult.failureReason)."
}
$refundLedgers = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/wallet/ledgers?userId=$($script:UserId)&bizNo=$failedWithdrawalNo-REFUND&asset=USDT&direction=IN&pageNum=1&pageSize=10" -Token $script:AdminToken
if (@($refundLedgers.records).Count -lt 1) {
  throw "Expected withdrawal refund ledger for $failedWithdrawalNo."
}
Write-Host "Manual failure refund verified withdrawalNo=$failedWithdrawalNo"

Write-Host "Creating withdrawal for manual success release path..."
$successWithdrawalNo = "SMOKE-WALLET-WD-S-$stamp"
$successWithdrawal = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/wallet/withdrawals" -Token $script:AdminToken -Body @{
  userId = $script:UserId
  withdrawalNo = $successWithdrawalNo
  asset = "USDT"
  amount = "0.000010"
  fee = "0.000000"
  targetAddress = "TSmokeWalletSuccess$stamp"
}
if ($successWithdrawal.status -ne "PENDING_CHAIN") {
  throw "Expected success-path withdrawal PENDING_CHAIN, got $($successWithdrawal.status)."
}
$successTxHash = "0xWALLETWDS$stamp"
$successResult = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/wallet/ops/withdrawals/$successWithdrawalNo/mark-succeeded" -Token $script:AdminToken -Body @{
  chainTxHash = $successTxHash
  reason = "Wallet ops compensation smoke success release"
}
if ($successResult.status -ne "SUCCESS" -or $successResult.chainTxHash -ne $successTxHash) {
  throw "Expected manual succeeded withdrawal with tx hash, got status=$($successResult.status), tx=$($successResult.chainTxHash)."
}
Write-Host "Manual success release verified withdrawalNo=$successWithdrawalNo"

Write-Host "Creating withdrawal for broadcast publish and retry path..."
$broadcastWithdrawalNo = "SMOKE-WALLET-WD-B-$stamp"
$broadcastWithdrawal = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/wallet/withdrawals" -Token $script:AdminToken -Body @{
  userId = $script:UserId
  withdrawalNo = $broadcastWithdrawalNo
  asset = "USDT"
  amount = "0.000010"
  fee = "0.000000"
  targetAddress = "TSmokeWalletBroadcast$stamp"
}
if ($broadcastWithdrawal.status -ne "PENDING_CHAIN") {
  throw "Expected broadcast-path withdrawal PENDING_CHAIN, got $($broadcastWithdrawal.status)."
}

$pendingBefore = @(Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/wallet/withdrawals/broadcast/pending?limit=50" -Token $script:AdminToken)
$null = Find-RequiredRecord -Records $pendingBefore -Predicate { $_.withdrawalNo -eq $broadcastWithdrawalNo } -Context "broadcast pending before publish"

$published = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/wallet/withdrawals/broadcast/publish?limit=50" -Token $script:AdminToken
if (($published.submitted + $published.failed + $published.dead) -lt 1) {
  throw "Expected broadcast publish to process at least one withdrawal."
}
Write-Host "Broadcast publish checked scanned=$($published.scanned), submitted=$($published.submitted), failed=$($published.failed), dead=$($published.dead)"

$retryBroadcast = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/wallet/ops/withdrawals/$broadcastWithdrawalNo/retry-broadcast" -Token $script:AdminToken -Body @{
  reason = "Wallet ops compensation smoke retry broadcast"
}
if ($retryBroadcast.status -ne "PENDING_CHAIN" -or $retryBroadcast.chainBroadcastAttempts -ne 0) {
  throw "Expected retry-broadcast to reset status/attempts, got status=$($retryBroadcast.status), attempts=$($retryBroadcast.chainBroadcastAttempts)."
}

$summary = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/wallet/withdrawals/broadcast/summary" -Token $script:AdminToken
if ($null -eq $summary.pending -or $null -eq $summary.dead) {
  throw "Withdrawal broadcast summary missing pending/dead counters."
}
Write-Host "Broadcast summary pending=$($summary.pending), submitted=$($summary.submitted), dead=$($summary.dead)"

$finalWallet = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/wallet/users/$($script:UserId)" -Token $script:AdminToken
if ([decimal]$finalWallet.pendingWithdraw -lt 0) {
  throw "Wallet pendingWithdraw must not be negative."
}
Write-Host "Final wallet USDT=$($finalWallet.usdtAvailable), pending=$($finalWallet.pendingWithdraw)"

Assert-AuditLog -Action "DEPOSIT_MANUAL_POST" -BizNo $deposit.depositNo
Assert-AuditLog -Action "DEPOSIT_RETRY_POST" -BizNo $deposit.depositNo
Assert-AuditLog -Action "WITHDRAWAL_MANUAL_FAILED" -BizNo $failedWithdrawalNo
Assert-AuditLog -Action "WITHDRAWAL_MANUAL_SUCCESS" -BizNo $successWithdrawalNo
Assert-AuditLog -Action "WITHDRAWAL_BROADCAST_RETRY" -BizNo $broadcastWithdrawalNo

Write-Host "Wallet ops compensation smoke completed. userId=$($script:UserId), depositNo=$($deposit.depositNo), failedWithdrawal=$failedWithdrawalNo, successWithdrawal=$successWithdrawalNo, broadcastWithdrawal=$broadcastWithdrawalNo"

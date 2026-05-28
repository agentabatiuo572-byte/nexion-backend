param(
  [string]$GatewayUrl = "http://127.0.0.1:8090",
  [string]$AdminUsername = "superadmin",
  [string]$AdminPassword = $env:NEXION_SMOKE_ADMIN_PASSWORD,
  [string]$CountryCode = "+1",
  [string]$Phone = "",
  [string]$Password = "Nexion123456",
  [string]$ReferralCode = "NX4892"
)

$ErrorActionPreference = "Stop"

function Invoke-NexionJson {
  param(
    [Parameter(Mandatory = $true)][string]$Method,
    [Parameter(Mandatory = $true)][string]$Uri,
    [object]$Body = $null,
    [string]$Token = ""
  )
  $headers = @{}
  if ($Token) { $headers.Authorization = "Bearer $Token" }
  $args = @{ Method = $Method; Uri = $Uri; Headers = $headers; TimeoutSec = 20 }
  if ($null -ne $Body) {
    $args.ContentType = "application/json"
    $args.Body = ($Body | ConvertTo-Json -Depth 10 -Compress)
  }
  $response = Invoke-RestMethod @args
  if ($response.code -ne 0) {
    throw "API failed: $Method $Uri -> code=$($response.code), message=$($response.message)"
  }
  return $response.data
}

function Invoke-Raw {
  param([string]$Method, [string]$Uri, [string]$Token = "")
  $headers = @{}
  if ($Token) { $headers.Authorization = "Bearer $Token" }
  try {
    $response = Invoke-RestMethod -Method $Method -Uri $Uri -Headers $headers -TimeoutSec 20
    if ($null -ne $response.code -and $response.code -ne 0) {
      return [int]$response.code
    }
    return 200
  } catch {
    if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
      return [int]$_.Exception.Response.StatusCode
    }
    throw
  }
}

if (-not $AdminPassword) {
  throw "Admin password is required. Pass -AdminPassword or set NEXION_SMOKE_ADMIN_PASSWORD."
}

$adminLogin = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/admin/login" -Body @{
  username = $AdminUsername
  password = $AdminPassword
}
$adminToken = $adminLogin.token
if (-not $adminToken) { throw "Admin login did not return token." }

if (-not $Phone) {
  $Phone = "9$(Get-Date -Format "MMddHHmmss")$(Get-Random -Minimum 10 -Maximum 99)"
}
$null = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/users/register" -Body @{
  countryCode = $CountryCode
  phone = $Phone
  password = $Password
  referralCode = $ReferralCode
}
$userLogin = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/users/login" -Body @{
  countryCode = $CountryCode
  phone = $Phone
  password = $Password
}
$userToken = $userLogin.token
if (-not $userToken) { throw "User login did not return token." }

$created = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/system/support/tickets" -Token $userToken -Body @{
  category = "wallet"
  priority = "normal"
  title = "Smoke withdrawal support"
  content = "Please check this support ticket smoke."
}
if (-not $created.ticketNo) { throw "Ticket create did not return ticketNo." }

$ticketNo = $created.ticketNo
$opsPage = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/system/support/ops/tickets?status=OPEN&pageSize=20" -Token $adminToken
if (@($opsPage.records | Where-Object { $_.ticketNo -eq $ticketNo }).Count -lt 1) {
  throw "Ops page did not include created ticket $ticketNo."
}

$reply = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/system/support/ops/tickets/$ticketNo/messages" -Token $adminToken -Body @{
  content = "Smoke reply from support."
}
if ($reply.status -ne "WAITING_USER") { throw "Ops reply expected WAITING_USER, got $($reply.status)." }

$detail = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/system/support/tickets/$ticketNo" -Token $userToken
if (@($detail.messages).Count -lt 2) { throw "User detail expected at least 2 messages." }

$notifications = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/notifications?type=SUPPORT&pageSize=20" -Token $userToken
if (@($notifications.records | Where-Object { $_.type -eq "SUPPORT" }).Count -lt 1) {
  throw "Support notification was not created."
}

$otherLoginPhone = "9$(Get-Date -Format "MMddHHmmss")$(Get-Random -Minimum 100 -Maximum 999)"
$null = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/users/register" -Body @{
  countryCode = $CountryCode
  phone = $otherLoginPhone
  password = $Password
  referralCode = $ReferralCode
}
$otherLogin = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/auth/users/login" -Body @{
  countryCode = $CountryCode
  phone = $otherLoginPhone
  password = $Password
}
$otherStatus = Invoke-Raw -Method Get -Uri "$GatewayUrl/api/system/support/tickets/$ticketNo" -Token $otherLogin.token
if ($otherStatus -eq 200) { throw "Cross-user ticket detail should not be accessible." }

Write-Host "OK support ticket smoke passed: ticketNo=$ticketNo"

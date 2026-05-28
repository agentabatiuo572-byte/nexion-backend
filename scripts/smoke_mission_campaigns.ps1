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
$userId = $userLogin.userId
if (-not $userToken -or -not $userId) { throw "User login did not return token and user id." }

$suffix = Get-Date -Format "MMddHHmmss"
$monthlyCode = "MONTHLY_SMOKE_$suffix"
$eventCode = "EVENT_SMOKE_$suffix"

$monthly = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/missions/ops/monthly-challenges" -Token $adminToken -Body @{
  challengeCode = $monthlyCode
  challengeName = "Smoke Monthly $suffix"
  description = "Smoke monthly challenge"
  theme = "SMOKE"
  monthsFrom = 0
  monthsTo = 999
  targetType = "MISSION_ACTIONS"
  targetValue = 3
  rewardType = "POINTS"
  rewardAmount = 321
  rewardName = "+321 Points"
  sortOrder = 999
  status = 1
}
if ($monthly.challengeCode -ne $monthlyCode) { throw "Monthly create returned unexpected code." }

$event = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/missions/ops/event-quests" -Token $adminToken -Body @{
  questCode = $eventCode
  questName = "Smoke Event $suffix"
  description = "Smoke event quest"
  startsAt = (Get-Date).AddMinutes(-5).ToString("yyyy-MM-ddTHH:mm:ss")
  endsAt = (Get-Date).AddDays(1).ToString("yyyy-MM-ddTHH:mm:ss")
  targetType = "EVENT_ACTIONS"
  targetValue = 2
  rewardType = "POINTS"
  rewardAmount = 123
  rewardName = "+123 Points"
  sortOrder = 999
  status = 1
}
if ($event.questCode -ne $eventCode) { throw "Event create returned unexpected code." }

$monthlyProgress = Invoke-NexionJson -Method Patch -Uri "$GatewayUrl/api/missions/ops/monthly-challenges/$monthlyCode/users/$userId/progress" -Token $adminToken -Body @{
  progressValue = 3
}
if ($monthlyProgress.status -ne "UNLOCKED") { throw "Monthly progress expected UNLOCKED, got $($monthlyProgress.status)." }

$eventProgress = Invoke-NexionJson -Method Patch -Uri "$GatewayUrl/api/missions/ops/event-quests/$eventCode/users/$userId/progress" -Token $adminToken -Body @{
  progressValue = 2
}
if ($eventProgress.status -ne "UNLOCKED") { throw "Event progress expected UNLOCKED, got $($eventProgress.status)." }

$monthlyList = @(Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/missions/monthly" -Token $userToken)
if (@($monthlyList | Where-Object { $_.challengeCode -eq $monthlyCode -and $_.status -eq "UNLOCKED" }).Count -lt 1) {
  throw "User monthly list did not include unlocked smoke challenge."
}

$eventList = @(Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/missions/events" -Token $userToken)
if (@($eventList | Where-Object { $_.questCode -eq $eventCode -and $_.status -eq "UNLOCKED" }).Count -lt 1) {
  throw "User event list did not include unlocked smoke quest."
}

$monthlyClaim = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/missions/monthly/$monthlyCode/claim" -Token $userToken
if (-not $monthlyClaim.claimed -or $monthlyClaim.awardedPoints -ne 321) {
  throw "Monthly claim failed. claimed=$($monthlyClaim.claimed), points=$($monthlyClaim.awardedPoints)"
}

$monthlyDuplicate = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/missions/monthly/$monthlyCode/claim" -Token $userToken
if ($monthlyDuplicate.status -ne "ALREADY_CLAIMED" -or $monthlyDuplicate.awardedPoints -ne 0) {
  throw "Monthly duplicate claim should be idempotent."
}

$eventClaim = Invoke-NexionJson -Method Post -Uri "$GatewayUrl/api/missions/events/$eventCode/claim" -Token $userToken
if (-not $eventClaim.claimed -or $eventClaim.awardedPoints -ne 123) {
  throw "Event claim failed. claimed=$($eventClaim.claimed), points=$($eventClaim.awardedPoints)"
}

$points = Invoke-NexionJson -Method Get -Uri "$GatewayUrl/api/missions/points/summary?pageNum=1&pageSize=20" -Token $userToken
$ledgerBizNos = @($points.recentLedgers.records | ForEach-Object { $_.bizNo })
if ($ledgerBizNos -notcontains "MONTHLY-CHALLENGE-$monthlyCode-$userId") {
  throw "Points ledger did not include monthly campaign claim."
}
if ($ledgerBizNos -notcontains "EVENT-QUEST-$eventCode-$userId") {
  throw "Points ledger did not include event quest claim."
}

Write-Host "OK mission campaign smoke passed: userId=$userId monthly=$monthlyCode event=$eventCode"

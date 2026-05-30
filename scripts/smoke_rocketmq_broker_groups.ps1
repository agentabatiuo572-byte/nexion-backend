param(
  [string]$GatewaySecret = "nexion-local-gateway-secret",
  [switch]$SkipLive,
  [switch]$RequireOk,
  [switch]$IncludeDlq = $true,
  [string]$TeamUrl = "http://127.0.0.1:8106",
  [string]$ComputeUrl = "http://127.0.0.1:8102",
  [string]$EarningsUrl = "http://127.0.0.1:8108",
  [string]$WalletUrl = "http://127.0.0.1:8105",
  [string]$NotificationUrl = "http://127.0.0.1:8107",
  [string]$MissionUrl = "http://127.0.0.1:8103"
)

$ErrorActionPreference = "Stop"
$Root = Split-Path -Parent (Split-Path -Parent $PSCommandPath)

$Expected = @(
  @{ Name = "team-order-paid"; Topic = "nexion-order-paid"; Group = "nexion-team-order-paid"; Url = "$TeamUrl/team/outbox/broker/consumer/status?includeDlq=$IncludeDlq" },
  @{ Name = "compute-order-paid"; Topic = "nexion-order-paid"; Group = "nexion-compute-order-paid"; Url = "$ComputeUrl/compute/outbox/broker/consumer/status?includeDlq=$IncludeDlq" },
  @{ Name = "earnings-compute-task-completed"; Topic = "nexion-compute-task-completed"; Group = "nexion-earnings-compute-task-completed"; Url = "$EarningsUrl/earnings/outbox/broker/consumer/status?includeDlq=$IncludeDlq" },
  @{ Name = "wallet-earning-generated"; Topic = "nexion-earning-generated"; Group = "nexion-wallet-earning-generated"; Url = "$WalletUrl/wallet/outbox/broker/consumer/statuses?includeDlq=$IncludeDlq" },
  @{ Name = "wallet-risk-decision-finalized"; Topic = "nexion-risk-decision-finalized"; Group = "nexion-wallet-risk-decision-finalized"; Url = "$WalletUrl/wallet/outbox/broker/consumer/statuses?includeDlq=$IncludeDlq" },
  @{ Name = "notification-earning-generated"; Topic = "nexion-earning-generated"; Group = "nexion-notification-earning-generated"; Url = "$NotificationUrl/notifications/outbox/broker/consumer/status?includeDlq=$IncludeDlq" },
  @{ Name = "mission-earning-generated"; Topic = "nexion-earning-generated"; Group = "nexion-mission-earning-generated"; Url = "$MissionUrl/missions/outbox/broker/consumer/status?includeDlq=$IncludeDlq" }
)

$InternalHeaders = @{
  "X-Nexion-Gateway-Secret" = $GatewaySecret
  "X-Nexion-Subject-Id" = "0"
  "X-Nexion-Subject-Type" = "SERVICE"
  "X-Nexion-Username" = "smoke-rocketmq-broker-groups"
  "X-Nexion-Authorities" = "PERM_TEAM_READ,PERM_COMPUTE_READ,PERM_EARNINGS_READ,PERM_WALLET_READ,PERM_NOTIFICATION_READ,PERM_MISSION_READ"
}

function Assert-FileContains {
  param(
    [Parameter(Mandatory = $true)][string]$Path,
    [Parameter(Mandatory = $true)][string]$Pattern
  )
  if (-not (Test-Path $Path)) {
    throw "Missing expected file: $Path"
  }
  if (-not (Select-String -Path $Path -SimpleMatch -Pattern $Pattern -Quiet)) {
    throw "Expected '$Pattern' in $Path"
  }
}

function Invoke-NexionJson {
  param(
    [Parameter(Mandatory = $true)][string]$Uri
  )
  $response = Invoke-RestMethod -Method Get -Uri $Uri -Headers $InternalHeaders -TimeoutSec 20
  if ($null -ne $response.code -and $response.code -ne 0) {
    throw "Request failed: GET $Uri -> code=$($response.code), message=$($response.message)"
  }
  return $response.data
}

Write-Host "Checking static RocketMQ topic/group configuration..."
$readme = Join-Path $Root "README.md"
$startScript = Join-Path $Root "scripts\start_gateway_chain_services.ps1"
foreach ($item in $Expected) {
  Assert-FileContains -Path $readme -Pattern $item.Topic
  Assert-FileContains -Path $readme -Pattern $item.Group
  Assert-FileContains -Path $startScript -Pattern $item.Topic
  Assert-FileContains -Path $startScript -Pattern $item.Group
}

$codeChecks = @(
  @{ Path = "nexion-team-service\src\main\resources\application.yml"; Pattern = "nexion-team-order-paid" },
  @{ Path = "nexion-compute-service\src\main\resources\application.yml"; Pattern = "nexion-compute-order-paid" },
  @{ Path = "nexion-earnings-service\src\main\resources\application.yml"; Pattern = "nexion-earnings-compute-task-completed" },
  @{ Path = "nexion-wallet-service\src\main\resources\application.yml"; Pattern = "nexion-wallet-earning-generated" },
  @{ Path = "nexion-wallet-service\src\main\resources\application.yml"; Pattern = "nexion-wallet-risk-decision-finalized" },
  @{ Path = "nexion-notification-service\src\main\resources\application.yml"; Pattern = "nexion-notification-earning-generated" },
  @{ Path = "nexion-mission-service\src\main\resources\application.yml"; Pattern = "nexion-mission-earning-generated" }
)
foreach ($check in $codeChecks) {
  Assert-FileContains -Path (Join-Path $Root $check.Path) -Pattern $check.Pattern
}
Write-Host "Static RocketMQ topic/group configuration ok."

if ($SkipLive) {
  Write-Host "Skipping live broker endpoint checks."
  exit 0
}

Write-Host "Checking live RocketMQ broker monitor endpoints..."
$seen = @{}
foreach ($item in $Expected) {
  $data = Invoke-NexionJson -Uri $item.Url
  $rows = @($data)
  $monitor = $rows | Where-Object { $_.consumerGroup -eq $item.Group -and $_.topic -eq $item.Topic } | Select-Object -First 1
  if ($null -eq $monitor) {
    throw "Expected monitor row for $($item.Name), topic=$($item.Topic), group=$($item.Group)."
  }
  $seen[$item.Name] = $true
  Write-Host "Broker monitor $($item.Name): ok=$($monitor.ok), topic=$($monitor.topic), group=$($monitor.consumerGroup), lag=$($monitor.totalLag), dlq=$($monitor.dlqMessages), acl=$($monitor.acl)"
  if ($RequireOk -and -not $monitor.ok) {
    throw "Expected broker monitor ok for $($item.Name), errors=$($monitor.errors -join '; ')"
  }
  if ($null -ne $monitor.totalLag -and [long]$monitor.totalLag -lt 0) {
    throw "Expected non-negative lag for $($item.Name), got $($monitor.totalLag)."
  }
  if ($null -ne $monitor.dlqMessages -and [long]$monitor.dlqMessages -lt 0) {
    throw "Expected non-negative DLQ count for $($item.Name), got $($monitor.dlqMessages)."
  }
  if ($monitor.acl -match "secret|password|token-" -or $monitor.acl -match "should-not") {
    throw "Broker monitor ACL diagnostics appears to contain secret material for $($item.Name)."
  }
}

if ($seen.Count -ne $Expected.Count) {
  throw "Expected $($Expected.Count) broker monitor rows, got $($seen.Count)."
}

Write-Host "RocketMQ broker group smoke completed."

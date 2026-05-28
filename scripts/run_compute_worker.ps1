param(
  [string]$BaseUrl = "http://127.0.0.1:8102",
  [string]$GatewaySecret = "nexion-local-gateway-secret",
  [string]$BearerToken = "",
  [string]$ClientName = "local-compute-worker",
  [string]$TaskType = "LOCAL_MOCK_INFERENCE",
  [long]$PreferredDeviceId = 0,
  [long]$UserId = 0,
  [int]$MaxAttempts = 3,
  [int]$LeaseSeconds = 30,
  [int]$Iterations = 1,
  [int]$LeaseIntervalSeconds = 5,
  [int]$AckIntervalSeconds = 5,
  [int]$MockExecutionSeconds = 2,
  [decimal]$RewardUsdt = 0.018,
  [decimal]$RewardNex = 3.2,
  [int]$FailRatePercent = 0,
  [int]$TimeoutSec = 20
)

$ErrorActionPreference = "Stop"

if ($FailRatePercent -lt 0 -or $FailRatePercent -gt 100) {
  throw "FailRatePercent must be between 0 and 100."
}
if ($Iterations -lt 1) {
  throw "Iterations must be at least 1."
}

$Headers = @{}
if ($BearerToken) {
  $Headers["Authorization"] = "Bearer $BearerToken"
} else {
  $Headers["X-Nexion-Gateway-Secret"] = $GatewaySecret
  $Headers["X-Nexion-Subject-Id"] = "0"
  $Headers["X-Nexion-Subject-Type"] = "SERVICE"
  $Headers["X-Nexion-Username"] = $ClientName
  $Headers["X-Nexion-Authorities"] = "PERM_COMPUTE_READ,PERM_COMPUTE_WRITE"
}

function Join-NexionUrl {
  param(
    [Parameter(Mandatory = $true)][string]$Root,
    [Parameter(Mandatory = $true)][string]$Path
  )
  return $Root.TrimEnd("/") + "/" + $Path.TrimStart("/")
}

function Invoke-NexionJson {
  param(
    [Parameter(Mandatory = $true)][string]$Method,
    [Parameter(Mandatory = $true)][string]$Path,
    [object]$Body = $null
  )

  $args = @{
    Method = $Method
    Uri = Join-NexionUrl -Root $BaseUrl -Path $Path
    Headers = $Headers
    TimeoutSec = $TimeoutSec
  }
  if ($null -ne $Body) {
    $args.ContentType = "application/json"
    $args.Body = ($Body | ConvertTo-Json -Depth 8 -Compress)
  }

  $response = Invoke-RestMethod @args
  if ($null -ne $response.code -and $response.code -ne 0) {
    throw "Request failed: $Method $($args.Uri) -> code=$($response.code), message=$($response.message)"
  }
  return $response.data
}

function New-LeaseBody {
  $body = @{
    taskType = $TaskType
    clientName = $ClientName
    maxAttempts = $MaxAttempts
    leaseSeconds = $LeaseSeconds
  }
  if ($PreferredDeviceId -gt 0) {
    $body.preferredDeviceId = $PreferredDeviceId
  }
  if ($UserId -gt 0) {
    $body.userId = $UserId
  }
  return $body
}

function Invoke-Heartbeat {
  param([Parameter(Mandatory = $true)][string]$TaskNo)
  return Invoke-NexionJson -Method Post -Path "/compute/tasks/$TaskNo/ack" -Body @{
    clientName = $ClientName
    leaseSeconds = $LeaseSeconds
  }
}

try {
  $health = Invoke-RestMethod -Method Get -Uri (Join-NexionUrl -Root $BaseUrl -Path "/actuator/health") -TimeoutSec 8
  Write-Host "OK health compute target -> $($health.status)"
} catch {
  Write-Host "WARN health check failed for $BaseUrl. Continuing because Gateway base URLs may not expose actuator."
}

$completed = 0
$failed = 0
$skipped = 0

for ($i = 1; $i -le $Iterations; $i++) {
  Write-Host "[$i/$Iterations] Leasing task taskType=$TaskType clientName=$ClientName..."
  try {
    $task = Invoke-NexionJson -Method Post -Path "/compute/tasks/worker/lease" -Body (New-LeaseBody)
  } catch {
    $skipped++
    Write-Host "WARN lease failed: $($_.Exception.Message)"
    if ($i -lt $Iterations) {
      Start-Sleep -Seconds $LeaseIntervalSeconds
    }
    continue
  }

  if (-not $task -or -not $task.taskNo) {
    $skipped++
    Write-Host "WARN lease returned no task."
    if ($i -lt $Iterations) {
      Start-Sleep -Seconds $LeaseIntervalSeconds
    }
    continue
  }

  Write-Host "Leased taskNo=$($task.taskNo), deviceId=$($task.userDeviceId), leaseExpiresAt=$($task.leaseExpiresAt)"
  Invoke-Heartbeat -TaskNo $task.taskNo | Out-Null
  Write-Host "Acked taskNo=$($task.taskNo)"

  $elapsed = 0
  while ($elapsed -lt $MockExecutionSeconds) {
    $sleep = [Math]::Min($AckIntervalSeconds, $MockExecutionSeconds - $elapsed)
    if ($sleep -gt 0) {
      Start-Sleep -Seconds $sleep
      $elapsed += $sleep
    }
    if ($elapsed -lt $MockExecutionSeconds) {
      Invoke-Heartbeat -TaskNo $task.taskNo | Out-Null
      Write-Host "Heartbeat taskNo=$($task.taskNo), elapsed=${elapsed}s"
    }
  }

  $roll = Get-Random -Minimum 1 -Maximum 101
  if ($roll -le $FailRatePercent) {
    $result = Invoke-NexionJson -Method Post -Path "/compute/tasks/$($task.taskNo)/fail" -Body @{
      clientName = $ClientName
      reason = "Mock worker failure roll=$roll failRate=$FailRatePercent"
    }
    $failed++
    Write-Host "Failed taskNo=$($result.taskNo), status=$($result.status)"
  } else {
    $receipt = Invoke-NexionJson -Method Post -Path "/compute/tasks/$($task.taskNo)/complete" -Body @{
      rewardUsdt = $RewardUsdt
      rewardNex = $RewardNex
      clientName = $ClientName
    }
    $completed++
    Write-Host "Completed taskNo=$($receipt.taskNo), receiptNo=$($receipt.receiptNo), earningStatus=$($receipt.earningStatus)"
  }

  if ($i -lt $Iterations) {
    Start-Sleep -Seconds $LeaseIntervalSeconds
  }
}

Write-Host "Compute worker finished. completed=$completed, failed=$failed, skipped=$skipped"

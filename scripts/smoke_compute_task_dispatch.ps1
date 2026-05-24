param(
  [string]$ComputeUrl = "http://127.0.0.1:8102",
  [string]$GatewaySecret = "nexion-local-gateway-secret",
  [long]$DeviceId = 1,
  [decimal]$RewardUsdt = 0.018,
  [decimal]$RewardNex = 3.2
)

$ErrorActionPreference = "Stop"

$InternalHeaders = @{
  "X-Nexion-Gateway-Secret" = $GatewaySecret
  "X-Nexion-Subject-Id" = "0"
  "X-Nexion-Subject-Type" = "SERVICE"
  "X-Nexion-Username" = "smoke-compute-task-dispatch"
  "X-Nexion-Authorities" = "PERM_COMPUTE_READ,PERM_COMPUTE_WRITE"
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
    $args.Body = ($Body | ConvertTo-Json -Depth 8 -Compress)
  }

  $response = Invoke-RestMethod @args
  if ($null -ne $response.code -and $response.code -ne 0) {
    throw "Request failed: $Method $Uri -> code=$($response.code), message=$($response.message)"
  }
  return $response.data
}

try {
  $health = Invoke-RestMethod -Method Get -Uri "$ComputeUrl/actuator/health" -TimeoutSec 8
  Write-Host "OK health compute -> $($health.status)"
} catch {
  throw "Compute service is not reachable at $ComputeUrl. Start nexion-compute-service before running this smoke script."
}

Write-Host "Leasing task to worker for deviceId=$DeviceId..."
$task = Invoke-NexionJson -Method Post -Uri "$ComputeUrl/compute/tasks/worker/lease" -Body @{
  preferredDeviceId = $DeviceId
  taskType = "SMOKE_SCHEDULED_INFERENCE"
  clientName = "smoke-compute-task-dispatch"
}
if ($task.status -ne "RUNNING" -or $task.userDeviceId -ne $DeviceId -or -not $task.workerAckAt -or -not $task.leaseExpiresAt) {
  throw "Expected RUNNING task on device $DeviceId, got task=$($task.taskNo), status=$($task.status), userDeviceId=$($task.userDeviceId)"
}
Write-Host "Leased task $($task.taskNo), leaseExpiresAt=$($task.leaseExpiresAt)"

Write-Host "Renewing the worker lease idempotently..."
$renewed = Invoke-NexionJson -Method Post -Uri "$ComputeUrl/compute/tasks/worker/lease" -Body @{
  preferredDeviceId = $DeviceId
  taskType = "SMOKE_SCHEDULED_INFERENCE"
  clientName = "smoke-compute-task-dispatch"
}
if ($renewed.taskNo -ne $task.taskNo -or -not $renewed.workerAckAt -or -not $renewed.leaseExpiresAt) {
  throw "Expected renewed lease for same task, got taskNo=$($renewed.taskNo), workerAckAt=$($renewed.workerAckAt), leaseExpiresAt=$($renewed.leaseExpiresAt)"
}
Write-Host "Renewed task $($task.taskNo), leaseExpiresAt=$($renewed.leaseExpiresAt)"

Write-Host "Checking busy device state..."
$busy = Invoke-NexionJson -Method Get -Uri "$ComputeUrl/compute/devices/$DeviceId/status"
if ($busy.status -ne "BUSY") {
  throw "Expected device BUSY after dispatch, got status=$($busy.status), cacheStatus=$($busy.cacheStatus)"
}

Write-Host "Completing task..."
$receipt = Invoke-NexionJson -Method Post -Uri "$ComputeUrl/compute/tasks/$($task.taskNo)/complete" -Body @{
  rewardUsdt = $RewardUsdt
  rewardNex = $RewardNex
  clientName = "smoke-compute-task-dispatch"
}
if ($receipt.taskNo -ne $task.taskNo -or -not $receipt.receiptNo) {
  throw "Expected receipt for task $($task.taskNo), got taskNo=$($receipt.taskNo), receiptNo=$($receipt.receiptNo)"
}
Write-Host "Completed task $($task.taskNo), receipt=$($receipt.receiptNo), earningStatus=$($receipt.earningStatus)"

Write-Host "Checking released device state..."
$online = Invoke-NexionJson -Method Get -Uri "$ComputeUrl/compute/devices/$DeviceId/status"
if ($online.status -ne "ONLINE") {
  throw "Expected device ONLINE after completion, got status=$($online.status), cacheStatus=$($online.cacheStatus)"
}

Write-Host "Compute task dispatch smoke completed. taskNo=$($task.taskNo), receiptNo=$($receipt.receiptNo)"

param(
  [string]$ComputeUrl = "http://127.0.0.1:8102",
  [string]$GatewaySecret = "nexion-local-gateway-secret",
  [long]$DeviceId = 1,
  [decimal]$RewardUsdt = 0.018,
  [decimal]$RewardNex = 3.2,
  [int]$RetryBackoffWaitSeconds = 31
)

$ErrorActionPreference = "Stop"
$WorkerScript = Join-Path $PSScriptRoot "run_compute_worker.ps1"

$InternalHeaders = @{
  "X-Nexion-Gateway-Secret" = $GatewaySecret
  "X-Nexion-Subject-Id" = "0"
  "X-Nexion-Subject-Type" = "SERVICE"
  "X-Nexion-Username" = "smoke-compute-worker-executor"
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

function Assert-ServiceHealth {
  try {
    $health = Invoke-RestMethod -Method Get -Uri "$ComputeUrl/actuator/health" -TimeoutSec 8
    Write-Host "OK health compute -> $($health.status)"
  } catch {
    throw "Compute service is not reachable at $ComputeUrl. Start nexion-compute-service before running this smoke script."
  }
}

function Ensure-OnlineDevice {
  param([Parameter(Mandatory = $true)][long]$DeviceId)
  $device = Invoke-NexionJson -Method Post -Uri "$ComputeUrl/compute/devices/$DeviceId/activate"
  if ($device.status -ne "ONLINE") {
    throw "Expected device $DeviceId ONLINE after activation, got status=$($device.status)"
  }
  return $device
}

function Assert-DeviceStatus {
  param(
    [Parameter(Mandatory = $true)][long]$DeviceId,
    [Parameter(Mandatory = $true)][string]$Expected
  )
  $status = Invoke-NexionJson -Method Get -Uri "$ComputeUrl/compute/devices/$DeviceId/status"
  if ($status.status -ne $Expected) {
    throw "Expected device $DeviceId status $Expected, got status=$($status.status), cacheStatus=$($status.cacheStatus)"
  }
  Write-Host "Device $DeviceId status=$($status.status), cacheStatus=$($status.cacheStatus)"
  return $status
}

Assert-ServiceHealth
Ensure-OnlineDevice -DeviceId $DeviceId | Out-Null

Write-Host "Running worker complete path on deviceId=$DeviceId..."
& powershell -NoProfile -ExecutionPolicy Bypass -File $WorkerScript `
  -BaseUrl $ComputeUrl `
  -GatewaySecret $GatewaySecret `
  -ClientName "smoke-compute-worker-complete" `
  -TaskType "SMOKE_WORKER_COMPLETE" `
  -PreferredDeviceId $DeviceId `
  -Iterations 1 `
  -LeaseSeconds 30 `
  -AckIntervalSeconds 1 `
  -MockExecutionSeconds 1 `
  -RewardUsdt $RewardUsdt `
  -RewardNex $RewardNex `
  -FailRatePercent 0
if ($LASTEXITCODE -ne 0) {
  throw "Compute worker complete path failed with exit code $LASTEXITCODE"
}
Assert-DeviceStatus -DeviceId $DeviceId -Expected "ONLINE" | Out-Null

$completedTasks = Invoke-NexionJson -Method Get -Uri "$ComputeUrl/compute/tasks?userDeviceId=$DeviceId&pageNum=1&pageSize=20"
$completedTask = $completedTasks.records | Where-Object { $_.taskType -eq "SMOKE_WORKER_COMPLETE" } | Select-Object -First 1
if ($null -eq $completedTask -or $completedTask.status -ne "COMPLETED") {
  throw "Expected a COMPLETED SMOKE_WORKER_COMPLETE task for device $DeviceId"
}
$receiptPage = Invoke-NexionJson -Method Get -Uri "$ComputeUrl/compute/receipts?userDeviceId=$DeviceId&pageNum=1&pageSize=20"
$receipt = $receiptPage.records | Where-Object { $_.taskNo -eq $completedTask.taskNo } | Select-Object -First 1
if ($null -eq $receipt) {
  throw "Expected receipt for completed worker task $($completedTask.taskNo)"
}
Write-Host "Complete path verified taskNo=$($completedTask.taskNo), receiptNo=$($receipt.receiptNo)"

Write-Host "Running worker fail path on deviceId=$DeviceId..."
Ensure-OnlineDevice -DeviceId $DeviceId | Out-Null
& powershell -NoProfile -ExecutionPolicy Bypass -File $WorkerScript `
  -BaseUrl $ComputeUrl `
  -GatewaySecret $GatewaySecret `
  -ClientName "smoke-compute-worker-fail" `
  -TaskType "SMOKE_WORKER_FAIL" `
  -PreferredDeviceId $DeviceId `
  -Iterations 1 `
  -LeaseSeconds 30 `
  -AckIntervalSeconds 1 `
  -MockExecutionSeconds 1 `
  -RewardUsdt $RewardUsdt `
  -RewardNex $RewardNex `
  -FailRatePercent 100
if ($LASTEXITCODE -ne 0) {
  throw "Compute worker fail path failed with exit code $LASTEXITCODE"
}
Assert-DeviceStatus -DeviceId $DeviceId -Expected "ONLINE" | Out-Null

$failedTasks = Invoke-NexionJson -Method Get -Uri "$ComputeUrl/compute/tasks?userDeviceId=$DeviceId&pageNum=1&pageSize=20"
$failedTask = $failedTasks.records | Where-Object { $_.taskType -eq "SMOKE_WORKER_FAIL" } | Select-Object -First 1
if ($null -eq $failedTask -or $failedTask.status -ne "FAILED") {
  throw "Expected a FAILED SMOKE_WORKER_FAIL task for device $DeviceId"
}
Write-Host "Fail path verified taskNo=$($failedTask.taskNo), status=$($failedTask.status)"

Write-Host "Verifying timeout -> retry maintenance path on deviceId=$DeviceId..."
Ensure-OnlineDevice -DeviceId $DeviceId | Out-Null
$retryTask = Invoke-NexionJson -Method Post -Uri "$ComputeUrl/compute/tasks/worker/lease" -Body @{
  preferredDeviceId = $DeviceId
  taskType = "SMOKE_WORKER_RETRY"
  clientName = "smoke-compute-worker-timeout"
  maxAttempts = 2
  leaseSeconds = 1
}
Write-Host "Leased retry task taskNo=$($retryTask.taskNo), leaseExpiresAt=$($retryTask.leaseExpiresAt)"
Start-Sleep -Seconds 2
$timeouts = Invoke-NexionJson -Method Post -Uri "$ComputeUrl/compute/tasks/maintenance/timeouts?limit=20"
if ($timeouts.retryScheduled -lt 1 -or ($timeouts.taskNos -notcontains $retryTask.taskNo)) {
  throw "Expected timeout maintenance to schedule retry for $($retryTask.taskNo), retryScheduled=$($timeouts.retryScheduled)"
}
Assert-DeviceStatus -DeviceId $DeviceId -Expected "ONLINE" | Out-Null
Write-Host "Waiting $RetryBackoffWaitSeconds seconds for retry backoff to become due..."
Start-Sleep -Seconds $RetryBackoffWaitSeconds
$retries = Invoke-NexionJson -Method Post -Uri "$ComputeUrl/compute/tasks/maintenance/retries?limit=20"
if ($retries.retried -lt 1 -or ($retries.taskNos -notcontains $retryTask.taskNo)) {
  throw "Expected retry maintenance to rerun $($retryTask.taskNo), retried=$($retries.retried)"
}
$retriedPage = Invoke-NexionJson -Method Get -Uri "$ComputeUrl/compute/tasks?pageNum=1&pageSize=5&status=RUNNING"
$retriedTask = $retriedPage.records | Where-Object { $_.taskNo -eq $retryTask.taskNo } | Select-Object -First 1
if ($null -eq $retriedTask) {
  throw "Expected task $($retryTask.taskNo) to be RUNNING after retry maintenance"
}

$retryReceipt = Invoke-NexionJson -Method Post -Uri "$ComputeUrl/compute/tasks/$($retryTask.taskNo)/complete" -Body @{
  rewardUsdt = $RewardUsdt
  rewardNex = $RewardNex
  clientName = "smoke-compute-worker-timeout"
}
Assert-DeviceStatus -DeviceId $DeviceId -Expected "ONLINE" | Out-Null
Write-Host "Retry path verified taskNo=$($retryTask.taskNo), receiptNo=$($retryReceipt.receiptNo)"

Write-Host "Compute worker executor smoke completed. completeTask=$($completedTask.taskNo), failedTask=$($failedTask.taskNo), retryTask=$($retryTask.taskNo)"

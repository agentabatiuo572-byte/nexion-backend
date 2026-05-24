param(
  [string]$ComputeUrl = "http://127.0.0.1:8102",
  [string]$GatewaySecret = "nexion-local-gateway-secret",
  [long]$DeviceId = 1
)

$ErrorActionPreference = "Stop"

$InternalHeaders = @{
  "X-Nexion-Gateway-Secret" = $GatewaySecret
  "X-Nexion-Subject-Id" = "0"
  "X-Nexion-Subject-Type" = "SERVICE"
  "X-Nexion-Username" = "smoke-compute-device-status"
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

$reportedAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss")
Write-Host "Reporting device state for deviceId=$DeviceId..."
$updated = Invoke-NexionJson -Method Patch -Uri "$ComputeUrl/compute/devices/$DeviceId/status" -Body @{
  status = "BUSY"
  region = "North America"
  country = "US"
  city = "San Jose"
  latitude = 37.3382
  longitude = -121.8863
  temperatureC = 61.5
  powerW = 320.0
  gpuUsage = 87.5
  activeTaskNo = "SMOKE-TASK-$([DateTimeOffset]::UtcNow.ToUnixTimeSeconds())"
  clientName = "smoke-compute-device-status"
  reportedAt = $reportedAt
}
if ($updated.status -ne "BUSY" -or $updated.cacheStatus -ne "UPDATED") {
  throw "Expected status BUSY and cacheStatus UPDATED, got status=$($updated.status), cacheStatus=$($updated.cacheStatus)"
}

Write-Host "Reading cached device state..."
$status = Invoke-NexionJson -Method Get -Uri "$ComputeUrl/compute/devices/$DeviceId/status"
if ($status.status -ne "BUSY" -or $status.cacheStatus -ne "HIT") {
  throw "Expected cached BUSY state, got status=$($status.status), cacheStatus=$($status.cacheStatus)"
}

Write-Host "Reading node map..."
$nodeMap = Invoke-NexionJson -Method Get -Uri "$ComputeUrl/compute/devices/node-map?limit=20"
if ($nodeMap.total -lt 1 -or $nodeMap.busy -lt 1) {
  throw "Expected node map to include at least one busy node, got total=$($nodeMap.total), busy=$($nodeMap.busy)"
}

Write-Host "Compute device status smoke completed. deviceId=$DeviceId, nodeMapCache=$($nodeMap.cacheStatus)"

param(
  [string]$Root = "D:\workspace\nexion-backend",
  [string]$Maven = "D:\software\apache-maven-3.9.9\bin\mvn.cmd",
  [string]$JwtSecret = "change-me-change-me-change-me-change-me",
  [string]$GatewaySecret = "nexion-local-gateway-secret",
  [string]$RedisHost = "127.0.0.1",
  [int]$RedisPort = 6379,
  [string]$RedisPassword = "A123456789Z!@#",
  [string]$GatewayRateLimitEnabled = "true",
  [int]$GatewayAnonymousRateLimit = 20,
  [int]$GatewayUserRateLimit = 120,
  [int]$GatewayRateLimitWindowSeconds = 60,
  [switch]$UseNacosConfig
)

$ErrorActionPreference = "Stop"
$nacosConfigEnabled = if ($UseNacosConfig) { "true" } else { "false" }

$services = @(
  @{ Name = "nexion-bff-service"; Port = 8100 },
  @{ Name = "nexion-auth-service"; Port = 8101 },
  @{ Name = "nexion-compute-service"; Port = 8102 },
  @{ Name = "nexion-commerce-service"; Port = 8104 },
  @{ Name = "nexion-wallet-service"; Port = 8105 },
  @{ Name = "nexion-team-service"; Port = 8106 },
  @{ Name = "nexion-earnings-service"; Port = 8108 },
  @{ Name = "nexion-openapi-service"; Port = 8111 },
  @{ Name = "nexion-gateway"; Port = 8090 }
)

New-Item -ItemType Directory -Force -Path (Join-Path $Root "logs") | Out-Null

foreach ($service in $services) {
  $existing = Get-NetTCPConnection -LocalPort $service.Port -State Listen -ErrorAction SilentlyContinue
  if ($existing) {
    Write-Host "$($service.Name) already has port $($service.Port) listening."
    continue
  }

  $workDir = Join-Path $Root $service.Name
  $outLog = Join-Path $Root "logs\$($service.Name).out.log"
  $errLog = Join-Path $Root "logs\$($service.Name).err.log"
  $inner = "cd /d `"$workDir`" && set `"SPRING_CLOUD_NACOS_CONFIG_ENABLED=$nacosConfigEnabled`" && set `"NEXION_JWT_SECRET=$JwtSecret`" && set `"NEXION_GATEWAY_INTERNAL_SECRET=$GatewaySecret`" && set `"SPRING_DATA_REDIS_HOST=$RedisHost`" && set `"SPRING_DATA_REDIS_PORT=$RedisPort`" && set `"SPRING_DATA_REDIS_PASSWORD=$RedisPassword`" && set `"NEXION_GATEWAY_RATE_LIMIT_ENABLED=$GatewayRateLimitEnabled`" && set `"NEXION_GATEWAY_RATE_LIMIT_ANONYMOUS_PER_MINUTE=$GatewayAnonymousRateLimit`" && set `"NEXION_GATEWAY_RATE_LIMIT_USER_PER_MINUTE=$GatewayUserRateLimit`" && set `"NEXION_GATEWAY_RATE_LIMIT_WINDOW_SECONDS=$GatewayRateLimitWindowSeconds`" && call `"$Maven`" spring-boot:run > `"$outLog`" 2> `"$errLog`""
  & cmd.exe /c "start `"$($service.Name)`" /B cmd.exe /c `"$inner`""
  Write-Host "Started $($service.Name), logs: $outLog / $errLog"
}

$deadline = (Get-Date).AddMinutes(5)
do {
  $rows = foreach ($service in $services) {
    try {
      $health = Invoke-RestMethod -Method Get -Uri "http://127.0.0.1:$($service.Port)/actuator/health" -TimeoutSec 3
      [pscustomobject]@{ Service = $service.Name; Port = $service.Port; Status = $health.status }
    } catch {
      [pscustomobject]@{ Service = $service.Name; Port = $service.Port; Status = "WAITING" }
    }
  }
  $rows | Format-Table -AutoSize
  if (($rows | Where-Object { $_.Status -ne "UP" }).Count -eq 0) {
    exit 0
  }
  Start-Sleep -Seconds 5
} while ((Get-Date) -lt $deadline)

Write-Error "Timed out waiting for gateway chain services. Check logs under $Root\logs."

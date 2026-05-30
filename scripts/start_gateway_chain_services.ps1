param(
  [string]$Root = "D:\workspace\nexion-backend",
  [string]$Maven = "D:\software\apache-maven-3.9.9\bin\mvn.cmd",
  [string]$JwtSecret = "change-me-change-me-change-me-change-me",
  [string]$GatewaySecret = "nexion-local-gateway-secret",
  [string]$RedisHost = "127.0.0.1",
  [int]$RedisPort = 6379,
  [string]$RedisPassword = "A123456789Z!@#",
  [string]$GatewayRateLimitEnabled = "true",
  [string]$GatewayRedisRateLimitEnabled = "true",
  [int]$GatewayRedisRateLimitTimeoutMs = 50,
  [string]$GatewaySentinelEnabled = "true",
  [string]$SentinelDashboard = "127.0.0.1:8858",
  [int]$SentinelTransportPort = 8719,
  [int]$GatewaySentinelDefaultFlowQps = 1000,
  [int]$GatewaySentinelCommerceQps = 1000,
  [int]$GatewayAnonymousRateLimit = 20,
  [int]$GatewayUserRateLimit = 120,
  [int]$GatewayRateLimitWindowSeconds = 60,
  [string]$GatewayCanaryEnabled = "false",
  [string]$GatewayCanaryCommerceEnabled = "false",
  [string]$GatewayCanaryCommerceUri = "http://localhost:18104",
  [int]$GatewayCanaryCommercePercent = 0,
  [string]$GatewayCanaryCommerceVersions = "",
  [string]$OutboxRocketMqEnabled = "false",
  [string]$RocketMqNameServer = "127.0.0.1:9876",
  [string]$OutboxRocketMqAclEnabled = "false",
  [string]$OutboxRocketMqAccessKey = "",
  [string]$OutboxRocketMqSecretKey = "",
  [string]$OutboxRocketMqSecurityToken = "",
  [string]$OutboxRocketMqOrderPaidTopic = "nexion-order-paid",
  [string]$OutboxRocketMqComputeTaskCompletedTopic = "nexion-compute-task-completed",
  [string]$OutboxRocketMqEarningGeneratedTopic = "nexion-earning-generated",
  [string]$OutboxRocketMqRiskDecisionFinalizedTopic = "nexion-risk-decision-finalized",
  [string]$OutboxRocketMqOrderPaidGroup = "nexion-team-order-paid",
  [string]$OutboxRocketMqComputeGroup = "nexion-compute-order-paid",
  [string]$OutboxRocketMqEarningsGroup = "nexion-earnings-compute-task-completed",
  [string]$OutboxRocketMqWalletGroup = "nexion-wallet-earning-generated",
  [string]$OutboxRocketMqWalletRiskGroup = "nexion-wallet-risk-decision-finalized",
  [string]$OutboxRocketMqNotificationGroup = "nexion-notification-earning-generated",
  [string]$OutboxRocketMqMissionGroup = "nexion-mission-earning-generated",
  [int]$OutboxRocketMqConsumerMaxRetries = 5,
  [string]$TeamOutboxWorkerEnabled = "true",
  [string]$EarningsTickWorkerEnabled = "false",
  [int]$EarningsTickIntervalSeconds = 3600,
  [int]$EarningsTickBatchSize = 100,
  [int]$EarningsTickWorkerInitialDelayMs = 60000,
  [int]$EarningsTickWorkerFixedDelayMs = 3600000,
  [decimal]$ComplianceGenesisReviewAmount = 10000.000000,
  [switch]$UseNacosConfig
)

$ErrorActionPreference = "Stop"
$nacosConfigEnabled = if ($UseNacosConfig) { "true" } else { "false" }

$services = @(
  @{ Name = "nexion-bff-service"; Port = 8100 },
  @{ Name = "nexion-auth-service"; Port = 8101 },
  @{ Name = "nexion-compute-service"; Port = 8102 },
  @{ Name = "nexion-mission-service"; Port = 8103 },
  @{ Name = "nexion-commerce-service"; Port = 8104 },
  @{ Name = "nexion-wallet-service"; Port = 8105 },
  @{ Name = "nexion-team-service"; Port = 8106 },
  @{ Name = "nexion-notification-service"; Port = 8107 },
  @{ Name = "nexion-earnings-service"; Port = 8108 },
  @{ Name = "nexion-compliance-service"; Port = 8109 },
  @{ Name = "nexion-system-service"; Port = 8110 },
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
  $inner = "cd /d `"$workDir`" && set `"SPRING_CLOUD_NACOS_CONFIG_ENABLED=$nacosConfigEnabled`" && set `"NEXION_JWT_SECRET=$JwtSecret`" && set `"NEXION_GATEWAY_INTERNAL_SECRET=$GatewaySecret`" && set `"SPRING_DATA_REDIS_HOST=$RedisHost`" && set `"SPRING_DATA_REDIS_PORT=$RedisPort`" && set `"SPRING_DATA_REDIS_PASSWORD=$RedisPassword`" && set `"NEXION_SENTINEL_ENABLED=$GatewaySentinelEnabled`" && set `"NEXION_GATEWAY_SENTINEL_ENABLED=$GatewaySentinelEnabled`" && set `"SENTINEL_DASHBOARD=$SentinelDashboard`" && set `"SENTINEL_TRANSPORT_PORT=$SentinelTransportPort`" && set `"NEXION_GATEWAY_SENTINEL_DEFAULT_FLOW_QPS=$GatewaySentinelDefaultFlowQps`" && set `"NEXION_GATEWAY_SENTINEL_COMMERCE_QPS=$GatewaySentinelCommerceQps`" && set `"NEXION_GATEWAY_RATE_LIMIT_ENABLED=$GatewayRateLimitEnabled`" && set `"NEXION_GATEWAY_RATE_LIMIT_REDIS_ENABLED=$GatewayRedisRateLimitEnabled`" && set `"NEXION_GATEWAY_RATE_LIMIT_REDIS_TIMEOUT_MS=$GatewayRedisRateLimitTimeoutMs`" && set `"NEXION_GATEWAY_RATE_LIMIT_ANONYMOUS_PER_MINUTE=$GatewayAnonymousRateLimit`" && set `"NEXION_GATEWAY_RATE_LIMIT_USER_PER_MINUTE=$GatewayUserRateLimit`" && set `"NEXION_GATEWAY_RATE_LIMIT_WINDOW_SECONDS=$GatewayRateLimitWindowSeconds`" && set `"NEXION_GATEWAY_CANARY_ENABLED=$GatewayCanaryEnabled`" && set `"NEXION_GATEWAY_CANARY_COMMERCE_ENABLED=$GatewayCanaryCommerceEnabled`" && set `"NEXION_GATEWAY_CANARY_COMMERCE_URI=$GatewayCanaryCommerceUri`" && set `"NEXION_GATEWAY_CANARY_COMMERCE_PERCENT=$GatewayCanaryCommercePercent`" && set `"NEXION_GATEWAY_CANARY_COMMERCE_VERSIONS=$GatewayCanaryCommerceVersions`" && set `"NEXION_COMPLIANCE_GENESIS_REVIEW_AMOUNT=$ComplianceGenesisReviewAmount`" && set `"NEXION_OUTBOX_ROCKETMQ_ENABLED=$OutboxRocketMqEnabled`" && set `"NEXION_OUTBOX_ROCKETMQ_NAME_SERVER=$RocketMqNameServer`" && set `"ROCKETMQ_NAME_SERVER=$RocketMqNameServer`" && set `"NEXION_OUTBOX_ROCKETMQ_ACL_ENABLED=$OutboxRocketMqAclEnabled`" && set `"NEXION_OUTBOX_ROCKETMQ_ACCESS_KEY=$OutboxRocketMqAccessKey`" && set `"NEXION_OUTBOX_ROCKETMQ_SECRET_KEY=$OutboxRocketMqSecretKey`" && set `"NEXION_OUTBOX_ROCKETMQ_SECURITY_TOKEN=$OutboxRocketMqSecurityToken`" && set `"NEXION_OUTBOX_ROCKETMQ_ORDER_PAID_TOPIC=$OutboxRocketMqOrderPaidTopic`" && set `"NEXION_OUTBOX_ROCKETMQ_COMPUTE_TASK_COMPLETED_TOPIC=$OutboxRocketMqComputeTaskCompletedTopic`" && set `"NEXION_OUTBOX_ROCKETMQ_EARNING_GENERATED_TOPIC=$OutboxRocketMqEarningGeneratedTopic`" && set `"NEXION_OUTBOX_ROCKETMQ_RISK_DECISION_FINALIZED_TOPIC=$OutboxRocketMqRiskDecisionFinalizedTopic`" && set `"NEXION_OUTBOX_ROCKETMQ_ORDER_PAID_GROUP=$OutboxRocketMqOrderPaidGroup`" && set `"NEXION_OUTBOX_ROCKETMQ_COMPUTE_GROUP=$OutboxRocketMqComputeGroup`" && set `"NEXION_OUTBOX_ROCKETMQ_EARNINGS_GROUP=$OutboxRocketMqEarningsGroup`" && set `"NEXION_OUTBOX_ROCKETMQ_WALLET_GROUP=$OutboxRocketMqWalletGroup`" && set `"NEXION_OUTBOX_ROCKETMQ_WALLET_RISK_GROUP=$OutboxRocketMqWalletRiskGroup`" && set `"NEXION_OUTBOX_ROCKETMQ_NOTIFICATION_GROUP=$OutboxRocketMqNotificationGroup`" && set `"NEXION_OUTBOX_ROCKETMQ_MISSION_GROUP=$OutboxRocketMqMissionGroup`" && set `"NEXION_OUTBOX_ROCKETMQ_CONSUMER_MAX_RETRIES=$OutboxRocketMqConsumerMaxRetries`" && set `"NEXION_TEAM_OUTBOX_WORKER_ENABLED=$TeamOutboxWorkerEnabled`" && set `"NEXION_EARNINGS_TICK_WORKER_ENABLED=$EarningsTickWorkerEnabled`" && set `"NEXION_EARNINGS_TICK_INTERVAL_SECONDS=$EarningsTickIntervalSeconds`" && set `"NEXION_EARNINGS_TICK_BATCH_SIZE=$EarningsTickBatchSize`" && set `"NEXION_EARNINGS_TICK_WORKER_INITIAL_DELAY_MS=$EarningsTickWorkerInitialDelayMs`" && set `"NEXION_EARNINGS_TICK_WORKER_FIXED_DELAY_MS=$EarningsTickWorkerFixedDelayMs`" && call `"$Maven`" spring-boot:run > `"$outLog`" 2> `"$errLog`""
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

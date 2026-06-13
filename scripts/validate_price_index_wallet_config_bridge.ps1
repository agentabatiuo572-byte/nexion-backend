$ErrorActionPreference = "Stop"

$root = Split-Path -Parent $PSScriptRoot
$servicePath = Join-Path $root "nexion-commerce-service\src\main\java\ffdd\commerce\service\ProductContentService.java"
$clientPath = Join-Path $root "nexion-commerce-service\src\main\java\ffdd\commerce\client\SystemConfigClient.java"
$feignConfigPath = Join-Path $root "nexion-commerce-service\src\main\java\ffdd\commerce\client\config\InternalFeignConfig.java"
$testPath = Join-Path $root "nexion-commerce-service\src\test\java\ffdd\commerce\service\ProductContentServiceTest.java"

$service = Get-Content -LiteralPath $servicePath -Raw
$client = Get-Content -LiteralPath $clientPath -Raw
$feignConfig = Get-Content -LiteralPath $feignConfigPath -Raw
$test = Get-Content -LiteralPath $testPath -Raw

$checks = @(
  @{
    Name = "commerce internal feign has system read/write authorities"
    Ok = $feignConfig.Contains("PERM_SYSTEM_READ") -and $feignConfig.Contains("PERM_SYSTEM_WRITE")
  },
  @{
    Name = "SystemConfigClient exposes protected config CRUD"
    Ok = $client.Contains("configuration = InternalFeignConfig.class") -and
      $client.Contains("@GetMapping(""/system/configs"")") -and
      $client.Contains("@PostMapping(""/system/configs"")") -and
      $client.Contains("@PatchMapping(""/system/configs/{id}"")") -and
      $client.Contains("record ConfigItemSaveRequest") -and
      $client.Contains("record ConfigItemUpdateRequest")
  },
  @{
    Name = "ProductContentService syncs active NEX price index to wallet config"
    Ok = $service.Contains("NEX_USDT_PRICE_KEY") -and
      $service.Contains("NEX_24H_VOLUME_KEY") -and
      $service.Contains("syncNexMarketConfig(row)") -and
      $service.Contains("isActiveNexPriceIndex") -and
      $service.Contains("upsertWalletConfig(")
  },
  @{
    Name = "Tests cover sync and non-sync cases"
    Ok = $test.Contains("saveActiveNexPriceIndexSynchronizesWalletPublicConfig") -and
      $test.Contains("saveInactiveNexPriceIndexDoesNotSynchronizeWalletPublicConfig") -and
      $test.Contains("wallet.exchange.nex_usdt_price") -and
      $test.Contains("wallet.nex_market.volume_24h_usdt")
  }
)

$failed = $checks | Where-Object { -not $_.Ok }
if ($failed.Count -gt 0) {
  Write-Error ("Price index wallet config bridge validation failed: " + (($failed | ForEach-Object { $_.Name }) -join "; "))
  exit 1
}

Write-Output "Price index wallet config bridge validation passed."

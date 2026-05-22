param(
  [string]$Root = "D:\workspace\nexion-backend",
  [string]$Maven = "D:\software\apache-maven-3.9.9\bin\mvn.cmd"
)

$ErrorActionPreference = "Stop"

$services = @(
  @{ Name = "nexion-compute-service"; Port = 8102 },
  @{ Name = "nexion-commerce-service"; Port = 8104 },
  @{ Name = "nexion-earnings-service"; Port = 8108 },
  @{ Name = "nexion-wallet-service"; Port = 8105 }
)

foreach ($service in $services) {
  $existing = Get-NetTCPConnection -LocalPort $service.Port -ErrorAction SilentlyContinue
  if ($existing) {
    Write-Host "$($service.Name) already has port $($service.Port) listening."
    continue
  }

  $workDir = Join-Path $Root $service.Name
  $outLog = Join-Path $Root "logs\$($service.Name).out.log"
  $errLog = Join-Path $Root "logs\$($service.Name).err.log"
  $inner = "cd /d `"$workDir`" && call `"$Maven`" spring-boot:run > `"$outLog`" 2> `"$errLog`""
  & cmd.exe /c "start `"$($service.Name)`" /B cmd.exe /c `"$inner`""
  Write-Host "Started $($service.Name), logs: $outLog / $errLog"
}

$deadline = (Get-Date).AddMinutes(4)
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

Write-Error "Timed out waiting for services. Check logs under $Root\logs."

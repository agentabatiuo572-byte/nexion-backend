function Assert-OpsConsoleMonolithPortAvailable {
  [CmdletBinding()]
  param(
    [Parameter(Mandatory = $true)]
    [ValidateRange(1, 65535)]
    [int]$Port,
    [scriptblock]$ListenerProbe = {
      param([int]$LocalPort)
      Get-NetTCPConnection -State Listen -ErrorAction Stop | Where-Object { $_.LocalPort -eq $LocalPort }
    }
  )

  try {
    $listeners = @(& $ListenerProbe $Port)
  } catch {
    throw "Unable to verify whether TCP port $Port is available; startup aborted. $($_.Exception.Message)"
  }

  if ($listeners.Count -eq 0) {
    return
  }

  $endpoints = @(
    $listeners | ForEach-Object {
      $address = if ($null -eq $_.LocalAddress -or [string]::IsNullOrWhiteSpace([string]$_.LocalAddress)) {
        "unknown-address"
      } else {
        [string]$_.LocalAddress
      }
      $owner = if ($null -eq $_.OwningProcess) { "unknown-pid" } else { "PID $($_.OwningProcess)" }
      "$address ($owner)"
    }
  ) -join ", "

  throw "TCP port $Port is already listening on $endpoints; startup aborted."
}

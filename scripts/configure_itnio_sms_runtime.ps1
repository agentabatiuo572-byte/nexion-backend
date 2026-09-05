param(
  [Security.SecureString]$ApiKey,
  [Security.SecureString]$ApiSecret,
  [Security.SecureString]$AppId,
  [string]$StateDirectory = "$env:LOCALAPPDATA\NexionRuntimeWatchdog"
)

$ErrorActionPreference = 'Stop'

function Protect-RuntimeSecretFile {
  param([string]$Path, [string]$IdentityName)
  & "$env:SystemRoot\System32\icacls.exe" $Path '/inheritance:r' '/grant:r' "$IdentityName`:(F)" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "failed to restrict runtime secret ACL: $Path" }
}

if ($null -eq $ApiKey) { $ApiKey = Read-Host 'ITNIO API key' -AsSecureString }
if ($null -eq $ApiSecret) { $ApiSecret = Read-Host 'ITNIO API secret' -AsSecureString }
if ($null -eq $AppId) { $AppId = Read-Host 'ITNIO app id' -AsSecureString }
if ($ApiKey.Length -eq 0 -or $ApiSecret.Length -eq 0 -or $AppId.Length -eq 0) {
  throw 'ITNIO SMS credentials must not be empty'
}

$resolvedStateDirectory = [IO.Path]::GetFullPath($StateDirectory)
if (-not (Test-Path -LiteralPath $resolvedStateDirectory -PathType Container)) {
  throw "runtime state directory is missing: $resolvedStateDirectory"
}
$secretsPath = Join-Path $resolvedStateDirectory 'runtime-secrets.clixml'
if (-not (Test-Path -LiteralPath $secretsPath -PathType Leaf)) {
  throw "encrypted runtime credential bundle is missing: $secretsPath"
}

$mutex = [Threading.Mutex]::new($false, 'Local\NexGridItnioSmsRuntimeConfig')
$mutexAcquired = $false
try {
  try {
    $mutexAcquired = $mutex.WaitOne([TimeSpan]::FromSeconds(15))
  } catch [Threading.AbandonedMutexException] {
    $mutexAcquired = $true
  }
  if (-not $mutexAcquired) { throw 'timed out waiting for the runtime configuration lock' }

  $current = Import-Clixml -LiteralPath $secretsPath
  $values = [ordered]@{}
  foreach ($property in $current.PSObject.Properties) {
    $values[$property.Name] = $property.Value
  }
  $values['SmsItnioApiKey'] = $ApiKey
  $values['SmsItnioApiSecret'] = $ApiSecret
  $values['SmsItnioAppId'] = $AppId

  $temporaryPath = Join-Path $resolvedStateDirectory ("runtime-secrets.$PID.tmp")
  try {
    [pscustomobject]$values | Export-Clixml -LiteralPath $temporaryPath
    Protect-RuntimeSecretFile -Path $temporaryPath -IdentityName ([Security.Principal.WindowsIdentity]::GetCurrent().Name)
    Move-Item -LiteralPath $temporaryPath -Destination $secretsPath -Force
    Protect-RuntimeSecretFile -Path $secretsPath -IdentityName ([Security.Principal.WindowsIdentity]::GetCurrent().Name)
  } finally {
    Remove-Item -LiteralPath $temporaryPath -Force -ErrorAction SilentlyContinue
  }
} finally {
  if ($mutexAcquired) { $mutex.ReleaseMutex() }
  $mutex.Dispose()
}

[pscustomobject]@{
  Provider = 'ITNIO'
  EnabledOnNextBackendStart = $true
  SecretStorage = 'Windows DPAPI encrypted runtime bundle'
}

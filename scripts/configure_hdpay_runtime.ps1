param(
  [Security.SecureString]$MerchantId,
  [Security.SecureString]$Md5Key,
  [string]$StateDirectory = "$env:LOCALAPPDATA\NexionRuntimeWatchdog"
)

$ErrorActionPreference = 'Stop'

function Protect-RuntimeSecretFile {
  param([string]$Path, [string]$IdentityName)
  & "$env:SystemRoot\System32\icacls.exe" $Path '/inheritance:r' '/grant:r' "$IdentityName`:(F)" | Out-Null
  if ($LASTEXITCODE -ne 0) { throw "failed to restrict runtime secret ACL: $Path" }
}

function Test-SecureDigits {
  param([Security.SecureString]$Value)
  $pointer = [IntPtr]::Zero
  try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($Value)
    $plain = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
    return $plain -match '^[0-9]{6,32}$'
  } finally {
    $plain = $null
    if ($pointer -ne [IntPtr]::Zero) { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer) }
  }
}

if ($null -eq $MerchantId) { $MerchantId = Read-Host 'HDPay merchant id' -AsSecureString }
if ($null -eq $Md5Key) { $Md5Key = Read-Host 'HDPay MD5 signing key' -AsSecureString }
if (-not (Test-SecureDigits -Value $MerchantId)) {
  throw 'HDPay merchant id must contain 6 to 32 digits'
}
if ($Md5Key.Length -lt 16) {
  throw 'HDPay MD5 signing key must contain at least 16 characters'
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
  $values['HdPayMerchantId'] = $MerchantId
  $values['HdPayMd5Key'] = $Md5Key

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
  Provider = 'HDPAY'
  EnabledOnNextBackendStart = $true
  SecretStorage = 'Windows DPAPI encrypted runtime bundle'
}

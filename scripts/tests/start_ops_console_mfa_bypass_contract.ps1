$ErrorActionPreference = "Stop"
$root = Resolve-Path (Join-Path $PSScriptRoot "..\..")
$resolver = Join-Path $root "scripts\resolve_ops_console_mfa_bypass.ps1"
$launcher = Join-Path $root "scripts\start_ops_console_monolith.ps1"
$previousBypass = $env:NEXION_ADMIN_MFA_TEMPORARY_SUPERADMIN_BYPASS

try {
  $env:NEXION_ADMIN_MFA_TEMPORARY_SUPERADMIN_BYPASS = "true"

  $defaultValue = & $resolver
  if ($defaultValue -cne "false") {
    throw "The local monolith launcher must default to MFA bypass=false even when its parent inherited true."
  }

  $explicitRecoveryValue = & $resolver -Enabled $true
  if ($explicitRecoveryValue -cne "true") {
    throw "The recovery override must require an explicit true argument."
  }

  $launcherSource = Get-Content -Raw $launcher
  if ($launcherSource -notmatch '\[bool\]\$TemporarySuperadminMfaBypass\s*=\s*\$false') {
    throw "The launcher must expose an explicit opt-in parameter whose default is false."
  }
  if ($launcherSource -notmatch 'NEXION_ADMIN_MFA_TEMPORARY_SUPERADMIN_BYPASS=\{0\}') {
    throw "The child process command must overwrite the inherited MFA bypass value."
  }

  "ops console MFA bypass startup contract: PASS"
} finally {
  if ($null -eq $previousBypass) {
    Remove-Item Env:NEXION_ADMIN_MFA_TEMPORARY_SUPERADMIN_BYPASS -ErrorAction SilentlyContinue
  } else {
    $env:NEXION_ADMIN_MFA_TEMPORARY_SUPERADMIN_BYPASS = $previousBypass
  }
}

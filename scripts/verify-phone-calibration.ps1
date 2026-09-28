param([ValidateSet('Guard','Catalog','Activation','Integration')][string]$Suite = 'Integration')
$ErrorActionPreference = 'Stop'
$taskToolRoot = 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools'
$taskJava = Get-ChildItem -LiteralPath $taskToolRoot -Directory -Filter 'jdk-*' | Select-Object -First 1
if (-not $env:JAVA_HOME -and $taskJava) { $env:JAVA_HOME = $taskJava.FullName }
$taskMaven = Join-Path $taskToolRoot 'apache-maven-3.9.9/bin/mvn.cmd'
if (-not (Test-Path -LiteralPath $taskMaven)) { $taskMaven = (Get-Command mvn -ErrorAction Stop).Source }
$testSet = switch ($Suite) {
  'Guard' { 'OpsDeviceServiceTest' }
  'Catalog' { 'PhoneCalibrationPolicyTest,PhoneCalibrationConfigServiceTest' }
  'Activation' { 'Onboarding*Test,PhoneCalibration*Test,PhoneNative*Test,AndroidPhoneAttestation*Test,AppTaskAssignmentControllerTest,AppCanonicalBoundaryServiceTest' }
  'Integration' { 'OpsDeviceServiceTest,Onboarding*Test,PhoneCalibration*Test,PhoneNative*Test,AndroidPhoneAttestation*Test,AppTaskAssignmentControllerTest,AppCanonicalBoundaryServiceTest,AuditReplayBusinessPermissionGuardTest,OpsPlatformParamRegistryServiceTest,OpsAuditCenter*Test,OpsAuditControllerTest' }
}
Push-Location (Join-Path $PSScriptRoot '..')
try {
  & $taskMaven '-B' "-Dtest=$testSet" test
  if ($LASTEXITCODE -ne 0) { throw "Phone calibration $Suite failed: $LASTEXITCODE" }
} finally { Pop-Location }

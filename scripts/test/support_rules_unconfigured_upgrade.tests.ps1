[CmdletBinding()]
param([Parameter(Mandatory)][string]$EvidenceDirectory)
$ErrorActionPreference='Stop'
$evidence=[IO.Path]::GetFullPath($EvidenceDirectory)
if(-not(Test-Path -LiteralPath $evidence -PathType Container)){throw 'An existing task evidence directory is required'}
$testRoot=Join-Path $evidence ('support-rules-script-'+[guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $testRoot | Out-Null
$upgrade=Join-Path $PSScriptRoot '../manual_migrations/20261009_support_rules_unconfigured_upgrade.ps1'
$previous=Get-Item Function:global:Invoke-RestMethod -ErrorAction SilentlyContinue
$token=ConvertTo-SecureString 'test-only-token' -AsPlainText -Force
function Assert-True([bool]$value,[string]$message){if(-not $value){throw $message}}
function Invoke-Case([string]$name,[string]$mode,$depth,[bool]$failure=$false,[bool]$missing=$false){
  $global:upgradeTestState=[pscustomobject]@{rules=[pscustomobject]@{version=9;inheritanceMode=$mode;maxInheritanceDepth=$depth;dormantDays=30;maintenanceDays=15;activityWindowDays=7;unboundAssignmentMode='SUPERVISOR'};puts=0;keys=@();recoveries=0;failure=$failure;missing=$missing}
  $case=Join-Path $testRoot $name;New-Item -ItemType Directory -Path $case | Out-Null
  return Join-Path $case 'proof.json'
}
function Invoke-Upgrade([string]$proof){& $upgrade -BaseUri 'http://127.0.0.1:18161/' -Token $token -OperationId 'original-operation-key' -Reason 'Controlled installation reason' -ProofFile $proof | Out-Null}
function Assert-Fails([scriptblock]$action){$failed=$false;try{& $action}catch{$failed=$true};Assert-True $failed 'Expected a closed failure'}
function global:Invoke-RestMethod {
  param($Uri,$Headers,$Method,$TimeoutSec,$ContentType,$Body)
  $state=$global:upgradeTestState
  if($Method -eq 'Put') {
    $state.puts++;$state.keys+= $Headers['Idempotency-Key']
    $payload=$Body | ConvertFrom-Json
    if(-not($payload.expectedVersion -eq 9 -and $payload.inheritanceMode -ceq 'UNLIMITED' -and $null -eq $payload.maxInheritanceDepth)){throw 'Upgrade must change the exact sentinel by expected version'}
    if(-not($payload.unboundAssignmentMode -ceq 'SUPERVISOR' -and $payload.dormantDays -eq 30 -and $payload.maintenanceDays -eq 15 -and $payload.activityWindowDays -eq 7)){throw 'Existing pool and time parameters must survive'}
    if($state.failure){throw 'Simulated unknown network outcome'}
    $state.rules.inheritanceMode='UNLIMITED';$state.rules.version=10
    return [pscustomobject]@{code=0;data=$state.rules}
  }
  if([string]$Uri -like '*/commands/*'){$state.recoveries++;return [pscustomobject]@{code=0;data=[pscustomobject]@{status= $(if($state.failure){'PROCESSING'}else{'SUCCEEDED'})}}}
  if($state.missing){return [pscustomobject]@{code=503;data=$null}}
  return [pscustomobject]@{code=0;data=($state.rules | ConvertTo-Json | ConvertFrom-Json)}
}
try {
  $proof=Invoke-Case 'limited-zero' 'LIMITED' 0;Invoke-Upgrade $proof;Invoke-Upgrade $proof
  Assert-True ($global:upgradeTestState.puts -eq 0 -and $global:upgradeTestState.rules.version -eq 9 -and $global:upgradeTestState.rules.maxInheritanceDepth -eq 0) 'LIMITED 0 must remain unchanged on both runs'
  $proof=Invoke-Case 'unlimited' 'UNLIMITED' $null;Invoke-Upgrade $proof
  Assert-True ($global:upgradeTestState.puts -eq 0) 'Existing UNLIMITED must not write'
  $proof=Invoke-Case 'upgrade-twice' 'UNCONFIGURED' $null;Invoke-Upgrade $proof;Invoke-Upgrade $proof
  Assert-True ($global:upgradeTestState.puts -eq 1 -and $global:upgradeTestState.rules.version -eq 10 -and $global:upgradeTestState.recoveries -eq 2) 'Second run must only recover and read back'
  Assert-True ($global:upgradeTestState.keys[0] -ceq 'original-operation-key') 'Original key must be used'
  $proof=Invoke-Case 'unknown-outcome' 'UNCONFIGURED' $null $true
  Assert-Fails {Invoke-Upgrade $proof};Assert-Fails {Invoke-Upgrade $proof}
  Assert-True ($global:upgradeTestState.puts -eq 1 -and $global:upgradeTestState.recoveries -eq 1) 'Unknown outcome must be recovered without another PUT'
  Assert-True ((Get-Content -LiteralPath $proof -Raw | ConvertFrom-Json).operationId -ceq 'original-operation-key') 'Unknown command proof must preserve key'
  $proof=Invoke-Case 'missing-config' 'UNCONFIGURED' $null $false $true;Assert-Fails {Invoke-Upgrade $proof}
  Assert-True ($global:upgradeTestState.puts -eq 0) 'Failed reads are not sentinel configuration'
  $proof=Invoke-Case 'missing-field' 'UNCONFIGURED' $null
  $global:upgradeTestState.rules.PSObject.Properties.Remove('inheritanceMode');Assert-Fails {Invoke-Upgrade $proof}
  Assert-True ($global:upgradeTestState.puts -eq 0) 'Missing mode must not create or upgrade a record'
  $proof=Invoke-Case 'version-changed' 'UNCONFIGURED' $null $true;Assert-Fails {Invoke-Upgrade $proof}
  $global:upgradeTestState.rules.version=11;Assert-Fails {Invoke-Upgrade $proof}
  Assert-True ($global:upgradeTestState.puts -eq 1) 'Version conflict must not retry or overwrite'
  [pscustomobject]@{status='PASSED';cases=7;network='MOCKED';database='NONE';evidence=$testRoot} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $testRoot 'result.json') -Encoding utf8
} finally {
  if($previous){Set-Item Function:global:Invoke-RestMethod -Value $previous.ScriptBlock}else{Remove-Item Function:global:Invoke-RestMethod -ErrorAction SilentlyContinue}
  Remove-Variable upgradeTestState -Scope Global -ErrorAction SilentlyContinue
}

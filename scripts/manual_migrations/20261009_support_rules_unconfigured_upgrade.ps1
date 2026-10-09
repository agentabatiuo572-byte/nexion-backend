[CmdletBinding()]
param(
  [Parameter(Mandatory)][uri]$BaseUri,
  [Parameter(Mandatory)][securestring]$Token,
  [Parameter(Mandatory)][ValidateLength(8,128)][string]$OperationId,
  [Parameter(Mandatory)][ValidateLength(8,200)][string]$Reason,
  [Parameter(Mandatory)][string]$ProofFile
)
$ErrorActionPreference='Stop'
if ($BaseUri.Scheme -notin @('http','https') -or $BaseUri.AbsolutePath -ne '/' -or $BaseUri.UserInfo -or $BaseUri.Query -or $BaseUri.Fragment) { throw 'Explicit HTTP(S) API origin required' }
if ($Reason.Trim().Length -lt 8) { throw 'Existing service-rule reason requires at least 8 characters' }
$origin=$BaseUri.AbsoluteUri.TrimEnd('/')
$rulesUri="$origin/api/admin/content/support-agents/rules"
$proofPath=[IO.Path]::GetFullPath($ProofFile)
if(-not(Test-Path -LiteralPath (Split-Path $proofPath))) { throw 'Create the task proof directory before this controlled upgrade' }
$secret=[Runtime.InteropServices.Marshal]::SecureStringToBSTR($Token)
try { $authorization=[Runtime.InteropServices.Marshal]::PtrToStringBSTR($secret) }
finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secret) }
$headers=@{Authorization="Bearer $authorization"}
$authorization=$null

function Read-Result([string]$uri) {
  $response=Invoke-RestMethod -Uri $uri -Headers $headers -Method Get -TimeoutSec 30
  if ($null -eq $response -or $response.code -ne 0 -or $null -eq $response.data) { throw 'Support API read failed; configuration remains unchanged' }
  return $response.data
}
function Check-Rules($rules) {
  foreach($field in @('version','inheritanceMode','maxInheritanceDepth','dormantDays','maintenanceDays','activityWindowDays','unboundAssignmentMode')) {
    if($field -notin $rules.PSObject.Properties.Name) { throw "Missing support-rule field: $field" }
  }
  if($rules.version -isnot [long] -and $rules.version -isnot [int]) { throw 'Configuration version must be an integer' }
  if($rules.version -lt 1 -or $rules.inheritanceMode -cnotin @('UNCONFIGURED','LIMITED','UNLIMITED')) { throw 'Unknown configuration; no upgrade applied' }
  if($rules.unboundAssignmentMode -cnotin @('AUTO_RANDOM','SUPERVISOR')) { throw 'Unknown pool policy; no upgrade applied' }
  if($rules.inheritanceMode -ceq 'LIMITED') {
    if(($rules.maxInheritanceDepth -isnot [long] -and $rules.maxInheritanceDepth -isnot [int]) -or $rules.maxInheritanceDepth -lt 0) { throw 'Invalid limited depth; no upgrade applied' }
  } elseif($null -ne $rules.maxInheritanceDepth) { throw 'Unexpected depth; no upgrade applied' }
}
function Save-Proof($record) {
  $record | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $proofPath -Encoding utf8
}

$before=Read-Result $rulesUri
Check-Rules $before
$record=$null
if(Test-Path -LiteralPath $proofPath) {
  $record=Get-Content -LiteralPath $proofPath -Raw | ConvertFrom-Json
  if($record.origin -ne $origin -or $record.operationId -ne $OperationId -or $record.reason -ne $Reason.Trim()) { throw 'Proof belongs to another command; retain original operation ID and reason' }
}
if($before.inheritanceMode -cne 'UNCONFIGURED') {
  if($record -and $record.payload) {
    $receipt=Read-Result "$origin/api/admin/content/support-workbench/commands/$([uri]::EscapeDataString($OperationId))"
    $record.status=$receipt.status
    $record.after=$before
    Save-Proof $record
    if($receipt.status -ne 'SUCCEEDED') { throw 'Original upgrade result is unresolved; no second command issued' }
  } elseif(-not $record) {
    $record=[pscustomobject]@{origin=$origin;operationId=$OperationId;reason=$Reason.Trim();before=$before;after=$before;status='NOT_NEEDED';payload=$null}
    Save-Proof $record
  }
  Write-Output "Support rules ready: existing $($before.inheritanceMode), version $($before.version), unchanged"
  return
}
if(-not $record) {
  $record=[pscustomobject]@{
    origin=$origin;operationId=$OperationId;reason=$Reason.Trim();before=$before;after=$null;status='PREPARED'
    payload=[ordered]@{
      dormantDays=$before.dormantDays;maintenanceDays=$before.maintenanceDays;activityWindowDays=$before.activityWindowDays
      inheritanceMode='UNLIMITED';maxInheritanceDepth=$null;unboundAssignmentMode=$before.unboundAssignmentMode
      expectedVersion=$before.version;reason=$Reason.Trim()
    }
  }
  Save-Proof $record
} elseif($null -eq $record.payload -or $record.payload.expectedVersion -ne $before.version) {
  throw 'Configuration changed since the saved upgrade; inspect differences, do not overwrite or mint a retry key'
}
if($record.status -ne 'PREPARED') {
  # SUBMITTING is durable before the network call. Recovery never reclaims an uncertain command.
  $receipt=Read-Result "$origin/api/admin/content/support-workbench/commands/$([uri]::EscapeDataString($OperationId))"
  $record.status=$receipt.status
  Save-Proof $record
  throw 'Original submitted command requires investigation; recovery did not confirm a configured read-back, no PUT repeated'
}
$headers['Idempotency-Key']=$OperationId
$record.status='SUBMITTING'
Save-Proof $record
$response=Invoke-RestMethod -Uri $rulesUri -Headers $headers -Method Put -ContentType 'application/json; charset=utf-8' -Body ($record.payload | ConvertTo-Json -Depth 6) -TimeoutSec 30
if($null -eq $response -or $response.code -ne 0) { throw 'Upgrade was rejected or outcome is unknown; saved payload and operation ID remain available for recovery' }
$after=Read-Result $rulesUri
Check-Rules $after
$receipt=Read-Result "$origin/api/admin/content/support-workbench/commands/$([uri]::EscapeDataString($OperationId))"
if($receipt.status -ne 'SUCCEEDED' -or $after.inheritanceMode -ne 'UNLIMITED' -or $after.version -ne $record.payload.expectedVersion+1) { throw 'Upgrade read-back is not confirmed; retain original command for investigation' }
$record.status='SUCCEEDED'
$record.after=$after
Save-Proof $record
Write-Output "Support rules upgraded once to UNLIMITED, version $($after.version). Existing bindings and pool were not touched by this command."

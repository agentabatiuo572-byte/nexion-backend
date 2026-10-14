$ErrorActionPreference='Stop'
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile("$PSScriptRoot/../support-analytics-runtime.ps1",[ref]$tokens,[ref]$errors)
if($errors.Count){throw 'Runtime script syntax error'}
$function=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -ceq 'Compare-SharedCaptures'},$true)
if(!$function){throw 'Shared capture comparison missing'}
Invoke-Expression $function.Extent.Text
function Document([int]$Count,[string]$Hash) {
    return ([ordered]@{schemaVersion=2;tables=@{nx_wallet=@{columns=@('id','amount');rowCount=$Count;digestAlgorithm='SORTED_ROW_SHA256_V1';sha256=$Hash}}}|ConvertTo-Json -Depth 10|ConvertFrom-Json)
}
function Must-Fail([scriptblock]$Call) { $failed=$false;try{& $Call|Out-Null}catch{$failed=$true};if(!$failed){throw 'Invalid capture unexpectedly accepted'} }
$before=Document 1 ('a'*64);$after=Document 1 ('a'*64)
if(@((Compare-SharedCaptures $before $after).changes).Count -ne 0){throw 'Unchanged summary rejected'}
$after.tables.nx_wallet.sha256='b'*64
$changed=Compare-SharedCaptures $before $after
if(@($changed.changes).Count -ne 1 -or $changed.changes[0].beforeCount -ne $changed.changes[0].afterCount){throw 'Same-count value change not visible'}
$after.tables.nx_wallet.rowCount=2
if((Compare-SharedCaptures $before $after).changes[0].afterCount -ne 2){throw 'Row count change not visible'}
$after.tables.nx_wallet.columns=@('id','different');Must-Fail {Compare-SharedCaptures $before $after}
$after=Document 1 ('a'*64);$after.schemaVersion=3;Must-Fail {Compare-SharedCaptures $before $after}
foreach($version in @('2',2.5,$true)) { $after=Document 1 ('a'*64);$after.schemaVersion=$version;Must-Fail {Compare-SharedCaptures $before $after} }
$after=Document 1 ('a'*64);$after.tables.nx_wallet.columns=@('id','id');Must-Fail {Compare-SharedCaptures $before $after}
$after=Document 1 ('a'*64);$after.tables.nx_wallet.rowCount='1';Must-Fail {Compare-SharedCaptures $before $after}
$after=Document 1 ('a'*64);$after.tables.nx_wallet.digestAlgorithm='unknown';Must-Fail {Compare-SharedCaptures $before $after}
$after=Document 1 ('a'*64);$after.tables.nx_wallet.sha256='invalid';Must-Fail {Compare-SharedCaptures $before $after}
$after=Document 1 ('a'*64);$after.tables=[pscustomobject]@{};Must-Fail {Compare-SharedCaptures $before $after}
$legacy='{"schemaVersion":1,"tables":{"nx_wallet":{"rows":[{"id":"1","amount":"10"}]}}}'|ConvertFrom-Json
if(@((Compare-SharedCaptures $legacy $legacy).changes).Count -ne 0){throw 'Legacy readback failed'}
Must-Fail {Compare-SharedCaptures $legacy $before}
Write-Output 'Shared capture format and full-table comparison contract passed; no external services used.'

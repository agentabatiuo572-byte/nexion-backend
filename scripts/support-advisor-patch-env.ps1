$ErrorActionPreference='Stop'
. "$PSScriptRoot/support-redesign-s4-env.ps1"
$env:S4_EVIDENCE_DIR='C:/Users/jason/.codex/workflow-runs/customer-service-20260929/advisor-patch'
New-Item -ItemType Directory -Path $env:S4_EVIDENCE_DIR -Force | Out-Null
$env:NEXION_DB_URL=$env:NEXION_DB_URL.Replace('/cs_redesign?','/cs_advisor_patch?')
$env:SPRING_DATA_REDIS_DATABASE='14'
$env:NEXION_MINIO_BUCKET='cs-advisor-patch-private'
$patchStorage=Get-Content "$env:S4_EVIDENCE_DIR/storage-credentials.json" -Raw | ConvertFrom-Json
if($patchStorage.endpoint -ne 'http://127.0.0.1:19030' -or $patchStorage.bucket -ne 'cs-advisor-patch-private'){throw 'Patch storage boundary mismatch'}
$env:NEXION_MINIO_ENDPOINT=$patchStorage.endpoint
$env:NEXION_MINIO_ACCESS_KEY=$patchStorage.accessKey
$env:NEXION_MINIO_SECRET_KEY=$patchStorage.secretKey
$env:SUPPORT_PATCH_ISOLATED='true'
$env:S4_HTTP_PORT='18130'
$env:SERVER_PORT='18130'
$env:SERVER_ADDRESS='127.0.0.1'
$env:NEXION_LOG_FILE="$env:S4_EVIDENCE_DIR/backend.log"
# Only Spring tests import the scheduler-disabling configuration; this does not start a second service.

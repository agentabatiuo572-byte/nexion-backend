param([switch]$Migrate)
$ErrorActionPreference='Stop'
$evidence='C:/Users/jason/.codex/workflow-runs/customer-service-20260929'
$db=Get-Content -Raw -LiteralPath "$evidence/local-db-credentials.json" | ConvertFrom-Json
if($db.host -ne '127.0.0.1' -or $db.port -ne 33329 -or $db.database -ne 'cs_redesign' -or $db.redisPort -ne 16329){throw 'Isolation boundary mismatch'}
$env:JAVA_HOME='D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/jdk-17.0.20.1+1'
$env:NEXION_DB_URL='jdbc:mysql://127.0.0.1:33329/cs_redesign?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:NEXION_DB_USERNAME=$db.username
$env:NEXION_DB_PASSWORD=$db.password
$env:NEXION_REDIS_HOST='127.0.0.1'
$env:NEXION_REDIS_PORT='16329'
$env:NEXION_REDIS_PASSWORD=$db.redisPassword
$env:SPRING_PROFILES_ACTIVE='dev'
$env:SERVER_PORT='18129'
$env:NEXION_TREASURY_DEVELOPMENTRESERVE_ENABLED='false'
$env:NEXION_HOME_DEVELOPMENTSETTLEMENT_ENABLED='false'
$env:NEXION_LOG_FILE="$evidence/s3/backend.log"
New-Item -ItemType Directory -Path "$evidence/s3" -Force | Out-Null
$keyPath="$evidence/s3/local-runtime-secrets.json"
if(!(Test-Path -LiteralPath $keyPath)) {
 $data=@{financeKey=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(32));jwt=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(48));password=[Convert]::ToBase64String([Security.Cryptography.RandomNumberGenerator]::GetBytes(24))}
 $data | ConvertTo-Json | Set-Content -LiteralPath $keyPath
}
$secrets=Get-Content -Raw -LiteralPath $keyPath | ConvertFrom-Json
$env:NEXION_FINANCE_DATA_KEY=$secrets.financeKey
$env:NEXION_JWT_SECRET=$secrets.jwt
$env:NEXION_MINIO_ENDPOINT='http://127.0.0.1:19029'
$env:NEXION_MINIO_ACCESS_KEY='cs-local-not-enabled'
$env:NEXION_MINIO_SECRET_KEY=$secrets.password
$storagePath="$evidence/local-storage-credentials.json"
if(Test-Path -LiteralPath $storagePath) {
 $storage=Get-Content -Raw -LiteralPath $storagePath | ConvertFrom-Json
 if($storage.endpoint -ne 'http://127.0.0.1:19029'){throw 'Storage isolation boundary mismatch'}
 $env:NEXION_MINIO_ENDPOINT=$storage.endpoint
 $env:NEXION_MINIO_ACCESS_KEY=$storage.accessKey
 $env:NEXION_MINIO_SECRET_KEY=$storage.secretKey
 $env:NEXION_MINIO_BUCKET=$storage.bucket
}
$env:S3_FIXTURE_PASSWORD=$secrets.password
$env:S3_EVIDENCE_DIR="$evidence/s3"
if($Migrate) {
 $env:MYSQL_PWD=$db.password
 $client='D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/mysql-verified/mysql-8.4.6-winx64/bin/mysql.exe'
 try { foreach($file in @('20260929_support_binding_s3_preflight.sql','20260929_support_binding_s3.sql','20260929_support_ticket_source_s3.sql')) {
  $sql=(Join-Path $PSScriptRoot "migrations/$file").Replace('\','/')
  $stamp=Get-Date -Format 'yyyyMMddTHHmmssfff'
  Get-Content -Raw -LiteralPath $sql | & $client --no-defaults --default-character-set=utf8mb4 --protocol=tcp --host=127.0.0.1 --port=33329 --user=cs_runner cs_redesign *> "$evidence/s3/$stamp-$file.log"
  if($LASTEXITCODE -ne 0) {throw "Isolated migration failed: $file; inspect saved log"}
 } } finally { Remove-Item Env:MYSQL_PWD }
}

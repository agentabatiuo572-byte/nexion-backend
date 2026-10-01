$ErrorActionPreference = 'Stop'
$evidence = 'C:/Users/jason/.codex/workflow-runs/customer-service-enhancements-20261001/backend-core'
$source = 'C:/Users/jason/.codex/workflow-runs/customer-service-20260929'
$local = Get-Content -Raw -LiteralPath "$evidence/local-runtime-private.json" | ConvertFrom-Json
if ($local.host -ne '127.0.0.1' -or $local.port -ne 33329 -or $local.database -ne 'cs_enhance_20261001' -or $local.redisPort -ne 16341) {
    throw 'Enhancement runtime isolation mismatch'
}
$sourceStorage = Get-Content -Raw -LiteralPath "$source/local-storage-credentials.json" | ConvertFrom-Json
if ($sourceStorage.endpoint -ne 'http://127.0.0.1:19029' -or $sourceStorage.bucket -ne 'cs-redesign-private') { throw 'Local source storage mismatch' }
$storage = Get-Content -Raw -LiteralPath "$evidence/local-storage-private.json" | ConvertFrom-Json
if ($storage.endpoint -ne 'http://127.0.0.1:19041' -or $storage.bucket -ne 'cs-enhance-20261001-private') { throw 'Independent storage mismatch' }
$oldKeys = Get-Content -Raw -LiteralPath "$source/s3/local-runtime-secrets.json" | ConvertFrom-Json
$oldRuntime = Get-Content -Raw -LiteralPath "$source/root-runtime-private.json" | ConvertFrom-Json
$env:JAVA_HOME = 'D:/WORKS/PLAN/.local-runtime/phone-calibration-tools/jdk-17.0.20.1+1'
$env:NEXION_DB_URL = 'jdbc:mysql://127.0.0.1:33329/cs_enhance_20261001?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true'
$env:NEXION_DB_USERNAME = $local.username
$env:NEXION_DB_PASSWORD = $local.password
$env:NEXION_REDIS_HOST = '127.0.0.1'
$env:NEXION_REDIS_PORT = '16341'
$env:NEXION_REDIS_PASSWORD = $local.redisPassword
$env:SPRING_DATA_REDIS_DATABASE = '0'
$env:NEXION_JWT_SECRET = $local.jwtSecret
# Copied encrypted local data requires its existing encryption keys; tokens use a new secret.
$env:NEXION_FINANCE_DATA_KEY = $oldKeys.financeKey
$env:NEXION_ADMIN_MFA_ENCRYPTION_KEY = $oldRuntime.adminMfaEncryptionKey
$env:NEXION_MINIO_ENDPOINT = $storage.endpoint
$env:NEXION_MINIO_ACCESS_KEY = $storage.accessKey
$env:NEXION_MINIO_SECRET_KEY = $storage.secretKey
$env:NEXION_MINIO_BUCKET = 'cs-enhance-20261001-private'
$env:CS_ENHANCE_SOURCE_STORAGE_ACCESS_KEY = $sourceStorage.accessKey
$env:CS_ENHANCE_SOURCE_STORAGE_SECRET_KEY = $sourceStorage.secretKey
$env:SERVER_ADDRESS = '127.0.0.1'
$env:SERVER_PORT = '18141'
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:NEXION_TREASURY_DEVELOPMENTRESERVE_ENABLED = 'false'
$env:NEXION_HOME_DEVELOPMENTSETTLEMENT_ENABLED = 'false'
$env:NEXION_CREGIS_MODE = 'DISABLED'
$env:NEXION_HDPAY_MODE = 'DISABLED'
$env:NEXGRID_SMS_ITNIO_ENABLED = 'false'
$env:NEXION_LOG_FILE = "$evidence/backend.log"
$env:NEXION_SUPPORT_ATTACHMENTS_ALLOWEDMIMETYPES = 'image/png,image/jpeg'
$env:NEXION_SUPPORT_ATTACHMENTS_MAXBYTES = '1048576'
$env:NEXION_SUPPORT_ATTACHMENTS_MAXPIXELS = '1000000'
$env:NEXION_SUPPORT_ATTACHMENTS_TTLSECONDS = '300'
$env:CS_ENHANCE_PREP_ENABLED = 'true'
$env:CS_ENHANCE_EVIDENCE_DIR = $evidence
$env:CS_ENHANCE_CORE_ENABLED = 'true'
$env:NEXION_C1_AUDIT_MYSQL = 'true'
$env:SUPPORT_PATCH_ISOLATED = 'true'
$env:S4_HTTP_PORT = '18141'
$env:S3_EVIDENCE_DIR = "$evidence/legacy-s3"
$env:S4_EVIDENCE_DIR = "$evidence/legacy-s4"
$env:S3_FIXTURE_PASSWORD = 'Aa1!' + [Guid]::NewGuid().ToString('N')
New-Item -ItemType Directory -Force -Path $env:S3_EVIDENCE_DIR,$env:S4_EVIDENCE_DIR | Out-Null
$env:LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_BOOT_AUTOCONFIGURE_SECURITY_SERVLET_USERDETAILSSERVICEAUTOCONFIGURATION = 'ERROR'

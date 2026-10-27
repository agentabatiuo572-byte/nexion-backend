#!/usr/bin/env bash
# macOS / Linux equivalent of scripts/start_ops_console_monolith.ps1 (dev profile, port 8110). C-3
# Applies the idempotent startup migrations first, then runs the backend in the foreground.
set -euo pipefail
source "$(dirname "$0")/env.sh"

"$(dirname "$0")/bootstrap_db.sh"

cd "$BACKEND"
export SERVER_PORT=8110
export SPRING_PROFILES_ACTIVE=dev
export NEXION_ARCHITECTURE_DISTRIBUTED_RUNTIME_ENABLED=false
export NEXION_NOVA_AI_MODE="${NEXION_NOVA_AI_MODE:-DISABLED}"   # set ENABLED when local Ollama/Qdrant RAG is running
export NEXION_ADMIN_MFA_TEMPORARY_SUPERADMIN_BYPASS=false
# Fresh local DB has no valuation/business data, so the dev auto-injected treasury reserve and
# dev settlement cannot initialise (same switches as scripts/support-redesign-s3-env.ps1).
export NEXION_TREASURY_DEVELOPMENTRESERVE_ENABLED=false
export NEXION_HOME_DEVELOPMENTSETTLEMENT_ENABLED=false
export NEXION_LOG_FILE="${NEXION_LOG_FILE:-$BACKEND/target/nexion-backend.log}"
exec mvn -B spring-boot:run

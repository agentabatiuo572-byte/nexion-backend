#!/usr/bin/env bash
# Shared environment for running nexion-backend on macOS / Linux (C-3). Source it from bash.
# Secrets are never stored in the repo: put them in a chmod-600 file outside it and point
# NEXION_LOCAL_ENV at that file (default: ~/.nexion-local.env). Expected keys:
#   NEXION_DB_URL NEXION_DB_USERNAME NEXION_DB_PASSWORD NEXION_FINANCE_DATA_KEY NEXION_JWT_SECRET
#   NEXION_ADMIN_MFA_ENCRYPTION_KEY  (optional: NEXION_MINIO_ACCESS_KEY NEXION_MINIO_SECRET_KEY)
# Optional tool locations: JAVA_HOME, MYSQL_HOME (dir with bin/mysql), MAVEN_HOME.

BACKEND="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
NEXION_LOCAL_ENV="${NEXION_LOCAL_ENV:-$HOME/.nexion-local.env}"

if [[ ! -f "$NEXION_LOCAL_ENV" ]]; then
  echo "Missing $NEXION_LOCAL_ENV (see scripts/local/README.md)" >&2
  return 1 2>/dev/null || exit 1
fi
set -a
# shellcheck disable=SC1090
. "$NEXION_LOCAL_ENV"
set +a

[[ -n "${JAVA_HOME:-}" ]] && PATH="$JAVA_HOME/bin:$PATH"
[[ -n "${MYSQL_HOME:-}" ]] && PATH="$MYSQL_HOME/bin:$PATH"
[[ -n "${MAVEN_HOME:-}" ]] && PATH="$MAVEN_HOME/bin:$PATH"
export PATH

# Host/port come from NEXION_DB_URL (jdbc:mysql://host:port/db?...).
NEXION_DB_HOST="$(sed -E 's#^jdbc:mysql://([^:/]+).*#\1#' <<<"${NEXION_DB_URL:-jdbc:mysql://127.0.0.1:3306/nexion}")"
NEXION_DB_PORT="$(sed -nE 's#^jdbc:mysql://[^:/]+:([0-9]+).*#\1#p' <<<"${NEXION_DB_URL:-}")"
NEXION_DB_PORT="${NEXION_DB_PORT:-3306}"

mysql_nexion() {
  MYSQL_PWD="$NEXION_DB_PASSWORD" mysql --default-character-set=utf8mb4 \
    --protocol=tcp -h "$NEXION_DB_HOST" -P "$NEXION_DB_PORT" -u "$NEXION_DB_USERNAME" "$@"
}

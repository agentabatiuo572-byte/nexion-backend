#!/usr/bin/env bash
# C-2: scripts/seed.sql ships a superadmin bcrypt hash whose password is not documented. On a local or
# freshly initialised database, set a random password you control. Prints the password once; store it
# in your own secret file (for example NEXION_LOCAL_SUPERADMIN_PASSWORD in ~/.nexion-local.env).
# The first login then asks for MFA enrolment, and the console may require a password change.
# Requires htpasswd (macOS: built in; Debian/Ubuntu: apache2-utils).
set -euo pipefail
source "$(dirname "$0")/env.sh"

USERNAME="${1:-superadmin}"
command -v htpasswd >/dev/null || { echo "htpasswd not found (install apache2-utils)" >&2; exit 1; }
PASSWORD="$(LC_ALL=C tr -dc 'A-Za-z0-9' </dev/urandom | head -c 20)Aa1!"
# Spring's BCryptPasswordEncoder reads $2a$; htpasswd writes the equivalent $2y$ variant.
HASH="$(htpasswd -nbBC 10 "$USERNAME" "$PASSWORD" | cut -d: -f2 | sed 's/^\$2y\$/$2a$/')"
[[ "$HASH" == \$2a\$10\$* ]] || { echo "Unexpected hash format" >&2; exit 1; }

UPDATED="$(mysql_nexion -N -B nexion -e "UPDATE nx_admin SET password_hash='${HASH}' WHERE username='${USERNAME//\'/}' AND is_deleted=0; SELECT ROW_COUNT();")"
[[ "$UPDATED" == "1" ]] || { echo "No active admin named $USERNAME" >&2; exit 1; }
echo "Password for $USERNAME set to: $PASSWORD"

#!/usr/bin/env bash
# macOS / Linux port of scripts/apply_startup_schema_migrations.ps1 (C-1, C-3), plus what an EMPTY
# database needs before that chain can run: the one-time S3 support bootstrap and the structure-only
# prerequisites listed in scripts/fresh_db/*.txt. Re-runnable: every step is idempotent, and the
# S3 migrations only run while the owner preflight is not READY.
# Usage: scripts/local/bootstrap_db.sh [--baseline]   (--baseline first loads scripts/schema.sql + seed.sql)
set -euo pipefail
source "$(dirname "$0")/env.sh"
FRESH=$BACKEND/scripts/fresh_db

LOGS=${NEXION_BOOTSTRAP_LOGS:-$BACKEND/target/bootstrap-logs}/$(date +%Y%m%dT%H%M%S)
mkdir -p "$LOGS"
MIG=$BACKEND/scripts/migrations
cd "$BACKEND"  # migrations `source` repo-relative paths (the .ps1 does Push-Location $root)

run_file() { # label path [db]  (schema.sql creates the database, so it passes db="")
  local label=$1 file=$2 db=${3-nexion}
  echo "-> $label"
  if ! mysql_nexion $db < "$file" > "$LOGS/$label.log" 2>&1; then
    echo "FAILED: $label (log: $LOGS/$label.log)"; tail -5 "$LOGS/$label.log"; exit 1
  fi
}

scalar() { mysql_nexion -N -B nexion -e "$1"; }

if [[ "${1:-}" == "--baseline" ]]; then
  run_file 01-schema "$BACKEND/scripts/schema.sql" ""
  run_file 02-seed "$BACKEND/scripts/seed.sql" ""
fi

owner_preflight() { mysql_nexion -N -B nexion < "$BACKEND/scripts/support_ticket_owner_preflight.sql"; }

if [[ "$(owner_preflight)" != "READY" ]]; then
  echo "Support owner schema not READY -> fresh S3 bootstrap"
  run_file 10-app-support-schema "$FRESH/00_app_support_schema.sql"
  for f in 20260929_support_binding_s3_preflight.sql 20260929_support_binding_rules_schema_20261015.sql \
           20260929_support_binding_s3.sql 20260929_support_ticket_source_s3.sql; do
    run_file "11-$f" "$MIG/$f"
  done
  pf=$(owner_preflight)
  [[ "$pf" == "READY" ]] || { echo "Owner preflight still not READY: $pf"; exit 1; }
fi

# Same ordered list as the $migrations block in apply_startup_schema_migrations.ps1.
files=()
while IFS= read -r f; do files+=("$f"); done < <(awk '/^\$migrations = @\(/{on=1; next} on && /^\)/{exit} on && $0 !~ /^[[:space:]]*#/' \
  "$BACKEND/scripts/apply_startup_schema_migrations.ps1" | grep -oE 'scripts\\migrations\\[^"]+\.sql' | sed 's#.*\\##')
echo "Startup migrations: ${#files[@]}"
i=0
for f in "${files[@]}"; do
  i=$((i+1))
  [[ -f "$MIG/$f" ]] || { echo "Missing migration: $f"; exit 1; }
  if entry=$(grep "^$f|" "$FRESH/skip.txt"); then
    IFS='|' read -r _ reason run_when <<<"$entry"
    if [[ -z "$run_when" || "$(scalar "$run_when")" == "0" ]]; then
      echo "SKIP $f ($reason)"; continue
    fi
    echo "RUN  $f (its business data now exists)"
  fi
  while IFS='|' read -r target pre; do
    [[ "$target" == "$f" ]] || continue
    case "$pre" in
      migrations/*) p="$MIG/${pre#migrations/}" ;;
      fresh_db/*) p="$FRESH/${pre#fresh_db/}" ;;
    esac
    run_file "$(printf '20-%03d-pre-%s' $i "$(basename "$pre")")" "$p"
  done < <(grep -v '^#' "$FRESH/prereqs.txt" | grep -v '^$')
  run_file "$(printf '20-%03d-%s' $i "$f")" "$MIG/$f"
done

j=0
while IFS= read -r pre; do
  j=$((j+1))
  case "$pre" in
    migrations/*) p="$MIG/${pre#migrations/}" ;;
    fresh_db/*) p="$FRESH/${pre#fresh_db/}" ;;
  esac
  run_file "$(printf '30-%03d-%s' $j "$(basename "$pre")")" "$p"
done < <(grep -v '^#' "$FRESH/post.txt" | grep -v '^$')

# Postconditions (same checks as the PowerShell runner).
wcols="'id','user_id','withdrawal_no','asset','chain','amount','fee','target_address','status','d2_idempotency_key','d2_version','d2_hold_until','d5_payout_due_at','d2_penalty_fee_rate','d2_gross_fee','d2_nex_burned','d2_network_fee_rate','d2_network_fee_min','d2_network_fee_max','d2_network_fee','d2_nex_fee_offset_rate','d2_fee_waived','d2_actual_fee','d2_net_receive','d5_policy_version','d5_use_nex_fee_offset','d2_lifecycle_owner','d2_freeze_period','d2_routing_priority','d2_k3_risk_route','d2_k4_risk_score','d2_k4_model_version','d2_k4_as_of','d2_k4_band_low_max','d2_k4_band_high_min','d2_k4_auto_escalate_score','failure_reason','d2_previous_status','terminal_reason','retriable','created_at','updated_at','is_deleted'"
check() { # name expected actual
  if [[ "$3" == "$2" ]]; then echo "OK   $1"; else echo "FAIL $1 (expected $2, got $3)"; fail=1; fi
}
fail=0
check "withdrawal order columns" 43 "$(scalar "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_withdrawal_order' AND COLUMN_NAME IN ($wcols);")"
check "quote expiry columns" 2 "$(scalar "SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_bank_payout_quote_expiry' AND COLUMN_NAME IN ('quote_no','expired_at');")"
check "legal terms locales" 3 "$(scalar "SELECT COUNT(DISTINCT locale) FROM nx_legal_terms_version WHERE locale IN ('vi','zh','en') AND jurisdiction='GLOBAL' AND status='PUBLISHED' AND is_deleted=0 AND JSON_LENGTH(sections_json) >= 1 AND NOT (locale='en' AND version_label='v4' AND title='Nexion Acceptance Terms seven-closures-20260817 post-fix-v4' AND summary='QA acceptance fixture') AND NOT (version_label='v5' AND last_operator='migration:formal-terms-v5');")"
check "idempotency indexes" 2 "$(scalar "SELECT COUNT(*) FROM (SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) AS indexed_columns FROM INFORMATION_SCHEMA.STATISTICS WHERE TABLE_SCHEMA='nexion' AND TABLE_NAME='nx_admin_idempotency_record' AND INDEX_NAME IN ('idx_admin_idem_status_expires_deleted','idx_admin_idem_expiry_claim') GROUP BY INDEX_NAME) actual WHERE (INDEX_NAME='idx_admin_idem_status_expires_deleted' AND indexed_columns='status,expires_at,is_deleted') OR (INDEX_NAME='idx_admin_idem_expiry_claim' AND indexed_columns='status,is_deleted,expires_at,id');")"
[[ $fail == 0 ]] && echo "Startup schema ready. Logs: $LOGS" || { echo "Postconditions failed. Logs: $LOGS"; exit 1; }

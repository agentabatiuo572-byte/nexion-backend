-- BUG-556: the first-install BEP20 primary/mirror pair must agree.
-- 20260809 inserted both keys in one statement, so a factory pair has the same
-- updated_at. If an operator later changed either row, the timestamps differ;
-- this repair leaves that operator-owned state untouched. A5 still exposes any
-- remaining mismatch as a PARTIAL row instead of failing the whole registry.
UPDATE nx_config_item canonical
JOIN nx_config_item mirror
  ON mirror.config_key = 'wallet.withdrawal.bep20.enabled'
 AND mirror.status = 1
 AND mirror.is_deleted = 0
SET mirror.config_value = canonical.config_value,
    mirror.updated_at = NOW()
WHERE canonical.config_key = 'withdrawal.bep20.enabled'
  AND canonical.status = 1
  AND canonical.is_deleted = 0
  AND LOWER(TRIM(canonical.config_value)) = 'true'
  AND LOWER(TRIM(mirror.config_value)) = 'false'
  AND canonical.updated_at = mirror.updated_at;

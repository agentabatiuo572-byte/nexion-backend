-- Disable the small-withdrawal bypass at launch and publish the D5 quote revision.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

START TRANSACTION;

INSERT INTO nx_schema_migration_state (migration_key, phase)
VALUES ('20261127_d5_small_amount_launch_default', 'PENDING')
ON DUPLICATE KEY UPDATE migration_key = migration_key;

SELECT phase = 'PENDING' INTO @d5_small_pending
  FROM nx_schema_migration_state
 WHERE migration_key = '20261127_d5_small_amount_launch_default' FOR UPDATE;

-- Lock the aggregate revision first, as the D5 writer does.
SET @d5_small_version = (
  SELECT config_value FROM nx_config_item
   WHERE config_key = 'withdrawal.d5.version' AND status = 1 AND is_deleted = 0
     AND @d5_small_pending FOR UPDATE
);
SET @d5_small_open = @d5_small_version REGEXP '^[0-9]+$' AND NOT EXISTS (
  SELECT 1 FROM nx_audit_object_lock
   WHERE target_domain = 'D' AND target_type = 'withdrawal_limits'
     AND target_id = 'current' AND is_deleted = 0
);

UPDATE nx_config_item canonical
JOIN nx_config_item mirror ON mirror.config_key = 'wallet.withdrawal.small_amount_threshold_usd'
   SET canonical.config_value = '0', mirror.config_value = '0'
 WHERE @d5_small_open AND canonical.config_key = 'withdrawal.small_amount_threshold_usd'
   AND canonical.status = 1 AND canonical.is_deleted = 0
   AND mirror.status = 1 AND mirror.is_deleted = 0
   AND CAST(canonical.config_value AS BINARY) = '50'
   AND CAST(mirror.config_value AS BINARY) = '50';
SET @d5_small_changed = ROW_COUNT() > 0;

UPDATE nx_config_item
   SET config_value = CAST(@d5_small_version AS UNSIGNED) + 1
 WHERE config_key = 'withdrawal.d5.version' AND @d5_small_changed;

-- The normal D5 writer publishes the aggregate and the quote revision together.
UPDATE nx_config_item quote
JOIN nx_config_item revision ON revision.config_key = 'withdrawal.d5.version'
   SET quote.config_value = revision.config_value
 WHERE @d5_small_changed AND quote.config_key = 'withdrawal.d5.quote_version'
   AND quote.status = 1 AND quote.is_deleted = 0;

UPDATE nx_schema_migration_state SET phase = 'FINAL'
 WHERE migration_key = '20261127_d5_small_amount_launch_default' AND phase = 'PENDING';

COMMIT;

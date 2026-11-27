-- B142 finance launch defaults. Preserve operator values and pending A2 work.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

START TRANSACTION;

INSERT INTO nx_schema_migration_state (migration_key, phase)
VALUES ('20261127_b142_finance_launch_defaults', 'PENDING')
ON DUPLICATE KEY UPDATE migration_key = migration_key;

SELECT phase = 'PENDING' INTO @b142_pending
  FROM nx_schema_migration_state
 WHERE migration_key = '20261127_b142_finance_launch_defaults' FOR UPDATE;

-- D1 snapshots join their original policy version. Append the new revision.
SET @b142_deposit_version = NULL;
SELECT id FROM nx_cregis_deposit_policy_lock WHERE id = 1 AND @b142_pending FOR UPDATE;
SELECT version, min_deposit_usdt, fee_usdt, confirmations
  INTO @b142_deposit_version, @b142_deposit_min, @b142_deposit_fee, @b142_confirmations
  FROM nx_cregis_deposit_policy WHERE @b142_pending ORDER BY version DESC LIMIT 1 FOR UPDATE;

SET @b142_lower_deposit_min = @b142_deposit_min = 10 AND @b142_deposit_fee < 5;
SET @b142_lower_deposit_fee = @b142_deposit_fee = 1;
INSERT INTO nx_cregis_deposit_policy (version, min_deposit_usdt, fee_usdt, confirmations, reason)
SELECT @b142_deposit_version + 1,
       IF(@b142_lower_deposit_min, 5, @b142_deposit_min),
       IF(@b142_lower_deposit_fee, 0, @b142_deposit_fee),
       @b142_confirmations, 'B142 finance launch defaults'
 WHERE @b142_pending AND @b142_deposit_version IS NOT NULL
   AND (@b142_lower_deposit_min OR @b142_lower_deposit_fee)
   AND NOT EXISTS (
     SELECT 1 FROM nx_audit_object_lock
      WHERE target_domain = 'D' AND target_type = 'cregis_deposit_policy'
        AND target_id = 'current' AND is_deleted = 0
   );

-- The order-payment reader overlays all chain minima from the D1 policy above.
-- HDPAY_BANK has its own minimum and the existing fee policy version.
SET @b142_bank_min_path = NULL;
SELECT REPLACE(JSON_UNQUOTE(JSON_SEARCH(policy_json, 'one', 'HDPAY_BANK', NULL, '$[*].paymentMethod')),
               '.paymentMethod', '.minTopupPrincipal')
  INTO @b142_bank_min_path
  FROM nx_purchase_payment_policy WHERE id = 1 AND @b142_pending FOR UPDATE;

UPDATE nx_purchase_payment_policy
   SET policy_json = JSON_SET(policy_json, @b142_bank_min_path, '5'),
       version = version + 1, updated_at = NOW(6)
 WHERE @b142_pending AND id = 1
   AND BINARY JSON_UNQUOTE(JSON_EXTRACT(policy_json, @b142_bank_min_path)) = '10';

-- Lock the D5 aggregate revision first, as its normal writer does.
SET @b142_d5_version = NULL;
SELECT config_value INTO @b142_d5_version FROM nx_config_item
 WHERE config_key = 'withdrawal.d5.version' AND status = 1 AND is_deleted = 0
   AND @b142_pending FOR UPDATE;

SET @b142_d5_open = @b142_d5_version REGEXP '^[0-9]+$' AND NOT EXISTS (
  SELECT 1 FROM nx_audit_object_lock
   WHERE target_domain = 'D' AND target_type = 'withdrawal_limits'
     AND target_id = 'current' AND is_deleted = 0
);
-- The floor has a wallet mirror. The existing writer owns only canonical confirmation fees.
SET @b142_d5_min = @b142_d5_open AND EXISTS (
  SELECT 1 FROM nx_config_item canonical
  LEFT JOIN nx_config_item mirror ON mirror.config_key = 'wallet.withdrawal.min_usdt'
   WHERE canonical.config_key = 'withdrawal.min_usdt' AND canonical.status = 1 AND canonical.is_deleted = 0
     AND BINARY canonical.config_value IN ('20', '3')
     AND (mirror.id IS NULL OR (mirror.status = 1 AND mirror.is_deleted = 0
          AND BINARY mirror.config_value IN ('20', '3')))
     AND (BINARY canonical.config_value = '20' OR mirror.id IS NULL OR BINARY mirror.config_value = '20')
);

UPDATE nx_config_item SET config_value = '3'
 WHERE @b142_d5_min AND config_key = 'withdrawal.min_usdt' AND BINARY config_value = '20';

INSERT INTO nx_config_item (config_key, config_value, value_type, config_group, visibility, remark)
SELECT 'wallet.withdrawal.min_usdt', '3', 'NUMBER', 'wallet', 'ADMIN', 'D5 minimum withdrawal USDT mirror'
 WHERE @b142_d5_min
ON DUPLICATE KEY UPDATE config_value = '3';

UPDATE nx_config_item SET config_value = '1'
 WHERE @b142_d5_open AND config_key = 'withdrawal.network_confirm_fee_usd.erc20'
   AND status = 1 AND is_deleted = 0 AND BINARY config_value = '5';
SET @b142_d5_fee_changed = ROW_COUNT() > 0;

UPDATE nx_config_item SET config_value = CAST(@b142_d5_version AS UNSIGNED) + 1
 WHERE config_key = 'withdrawal.d5.version' AND (@b142_d5_min OR @b142_d5_fee_changed);

-- D7 keeps the JSON document intact except for eligible amount fields.
SET @b142_d7_version = NULL;
SELECT config_value INTO @b142_d7_version FROM nx_config_item
 WHERE config_key = 'finance.payout_vnd.version' AND status = 1 AND is_deleted = 0
   AND @b142_pending FOR UPDATE;

SET @b142_d7_values = NULL;
SELECT IF(JSON_VALID(config_value), config_value, NULL) INTO @b142_d7_values FROM nx_config_item
 WHERE config_key = 'finance.payout_vnd.values' AND status = 1 AND is_deleted = 0
   AND @b142_d7_version REGEXP '^[0-9]+$'
   AND NOT EXISTS (
     SELECT 1 FROM nx_audit_object_lock
      WHERE target_domain = 'D' AND target_type = 'payout_vnd_config'
        AND target_id = 'current' AND is_deleted = 0
   ) FOR UPDATE;

SET @b142_d7_min = BINARY JSON_UNQUOTE(JSON_EXTRACT(@b142_d7_values, '$.minAmountUsd')) = '20'
 AND CAST(JSON_UNQUOTE(JSON_EXTRACT(@b142_d7_values, '$.feeMinUsd')) AS DECIMAL(18,6)) < 3;
SET @b142_d7_max = BINARY JSON_UNQUOTE(JSON_EXTRACT(@b142_d7_values, '$.maxAmountUsd')) = '5000';
SET @b142_d7_next = IF(@b142_d7_min, JSON_SET(@b142_d7_values, '$.minAmountUsd', 3), @b142_d7_values);
SET @b142_d7_next = IF(@b142_d7_max, JSON_SET(@b142_d7_next, '$.maxAmountUsd', 100000), @b142_d7_next);

UPDATE nx_config_item SET config_value = @b142_d7_next
 WHERE config_key = 'finance.payout_vnd.values' AND (@b142_d7_min OR @b142_d7_max);

UPDATE nx_config_item SET config_value = CAST(@b142_d7_version AS UNSIGNED) + 1
 WHERE config_key = 'finance.payout_vnd.version' AND (@b142_d7_min OR @b142_d7_max);

UPDATE nx_schema_migration_state SET phase = 'FINAL'
 WHERE migration_key = '20261127_b142_finance_launch_defaults' AND phase = 'PENDING';

COMMIT;

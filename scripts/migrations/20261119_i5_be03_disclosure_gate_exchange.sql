-- I5-BE-03: register the exchange action in the I5 disclosure-gate catalog.
-- New databases get the unrestricted row from scripts/schema.sql; this forward
-- migration registers the same row on existing databases.
--
-- Default is unrestricted. An explicit disclosure.gate.exchange restriction
-- already present in nx_config_item, or the legacy emergency fallback when no
-- active config row exists, is preserved as the initial action state so the I5
-- page can surface and subsequently lift it through the existing approval flow.
-- Existing action rows are never overwritten (only a soft-deleted row is revived).
SET NAMES utf8mb4;
START TRANSACTION;

SET @i5_exchange_gate_active = (
  SELECT CASE
    WHEN EXISTS (
      SELECT 1 FROM nx_config_item
       WHERE config_key = 'disclosure.gate.exchange' AND status = 1 AND is_deleted = 0
    ) THEN (
      SELECT CASE WHEN LOWER(TRIM(config_value)) IN ('enabled','enable','on','true','1','blocked','required')
                  THEN 1 ELSE 0 END
        FROM nx_config_item
       WHERE config_key = 'disclosure.gate.exchange' AND status = 1 AND is_deleted = 0
       ORDER BY id DESC LIMIT 1
    )
    WHEN EXISTS (
      SELECT 1 FROM nx_emergency_control_setting
       WHERE setting_key = 'disclosure.gate.exchange' AND is_deleted = 0
    ) THEN (
      SELECT CASE WHEN LOWER(TRIM(setting_value)) IN ('enabled','enable','on','true','1','blocked','required')
                  THEN 1 ELSE 0 END
        FROM nx_emergency_control_setting
       WHERE setting_key = 'disclosure.gate.exchange' AND is_deleted = 0
       ORDER BY id DESC LIMIT 1
    )
    ELSE 0
  END
);

INSERT INTO nx_disclosure_gate_action
  (action_key,action_name,description,status_label,tone,active,sort_order,last_operator,is_deleted)
VALUES
  ('exchange','兑换','兑换（App 新兑换与 G2 排队处理）的风险披露确认范围',
   IF(@i5_exchange_gate_active = 1, 'ACTIVE', 'REMOVED'),
   IF(@i5_exchange_gate_active = 1, 'warn', 'dim'),
   @i5_exchange_gate_active, 60, 'migration:i5-exchange-disclosure', 0)
ON DUPLICATE KEY UPDATE
  is_deleted = 0;

COMMIT;

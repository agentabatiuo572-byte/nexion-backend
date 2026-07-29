-- Repair existing F5 databases where the audit tables were created with
-- utf8mb4_unicode_ci while nx_commission_event uses utf8mb4_0900_ai_ci.
-- Converting the durable F5 tables and rebuilding the trigger keeps every
-- commission producer on one collation and preserves the fail-closed guard.

ALTER TABLE nx_commission_operation
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

ALTER TABLE nx_commission_user_suspension
  CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

DROP TRIGGER IF EXISTS trg_nx_commission_event_suspension;

DELIMITER $$
CREATE TRIGGER trg_nx_commission_event_suspension
BEFORE INSERT ON nx_commission_event
FOR EACH ROW
BEGIN
  IF EXISTS (
    SELECT 1
      FROM nx_commission_user_suspension s
     WHERE s.user_id = NEW.user_id
       AND s.kind = LOWER(NEW.commission_type) COLLATE utf8mb4_0900_ai_ci
       AND s.status = 'SUSPENDED'
  ) THEN
    SET NEW.status = 'FROZEN';
    SET NEW.unlock_at = NULL;
    SET NEW.remark = CONCAT(
      COALESCE(NEW.remark, ''),
      CASE WHEN COALESCE(NEW.remark, '') = '' THEN '' ELSE ' | ' END,
      'F5 user-kind suspension'
    );
  END IF;
END$$
DELIMITER ;

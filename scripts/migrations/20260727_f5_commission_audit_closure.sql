-- F5 commission audit: durable operation history, user-kind suspension and
-- a database-side fail-closed guard for every commission producer.

CREATE TABLE IF NOT EXISTS nx_commission_operation (
  id BIGINT NOT NULL AUTO_INCREMENT,
  operation_no VARCHAR(64) NOT NULL,
  operation_type VARCHAR(32) NOT NULL,
  source_commission_id BIGINT NULL,
  result_commission_id BIGINT NULL,
  user_id BIGINT NULL,
  kinds VARCHAR(255) NULL,
  amount DECIMAL(18,6) NULL,
  currency VARCHAR(16) NULL,
  evidence_ref VARCHAR(128) NULL,
  reason VARCHAR(255) NOT NULL,
  operator VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'SUCCESS',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_commission_operation_no (operation_no),
  KEY idx_commission_operation_source (source_commission_id, operation_type),
  KEY idx_commission_operation_user (user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nx_commission_user_suspension (
  id BIGINT NOT NULL AUTO_INCREMENT,
  user_id BIGINT NOT NULL,
  kind VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL DEFAULT 'SUSPENDED',
  reason VARCHAR(255) NOT NULL,
  operator VARCHAR(64) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (id),
  UNIQUE KEY uk_commission_user_kind (user_id, kind),
  KEY idx_commission_suspension_status (status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

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

USE nexion;

DELIMITER //

CREATE PROCEDURE patch_legacy_ops_tables()
BEGIN
  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'nx_notification' AND column_name = 'deleted'
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'nx_notification' AND column_name = 'is_deleted'
  ) THEN
    ALTER TABLE nx_notification CHANGE COLUMN deleted is_deleted TINYINT NOT NULL DEFAULT 0;
  ELSEIF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'nx_notification' AND column_name = 'is_deleted'
  ) THEN
    ALTER TABLE nx_notification ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'nx_notification' AND column_name = 'updated_at'
  ) THEN
    ALTER TABLE nx_notification ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt' AND column_name = 'device_id'
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt' AND column_name = 'user_device_id'
  ) THEN
    ALTER TABLE nx_compute_receipt CHANGE COLUMN device_id user_device_id BIGINT NULL;
  ELSEIF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt' AND column_name = 'user_device_id'
  ) THEN
    ALTER TABLE nx_compute_receipt ADD COLUMN user_device_id BIGINT NULL AFTER user_id;
  END IF;

  IF EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt' AND column_name = 'deleted'
  ) AND NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt' AND column_name = 'is_deleted'
  ) THEN
    ALTER TABLE nx_compute_receipt CHANGE COLUMN deleted is_deleted TINYINT NOT NULL DEFAULT 0;
  ELSEIF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt' AND column_name = 'is_deleted'
  ) THEN
    ALTER TABLE nx_compute_receipt ADD COLUMN is_deleted TINYINT NOT NULL DEFAULT 0;
  END IF;

  IF NOT EXISTS (
    SELECT 1 FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'nx_compute_receipt' AND column_name = 'updated_at'
  ) THEN
    ALTER TABLE nx_compute_receipt ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP AFTER created_at;
  END IF;
END//

DELIMITER ;

CALL patch_legacy_ops_tables();
DROP PROCEDURE patch_legacy_ops_tables;

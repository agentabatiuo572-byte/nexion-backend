-- Trial eligibility is an explicit E1 product capability. It is default-off so
-- adding stock or listing a physical product can never silently expose it in H2.

CREATE TABLE IF NOT EXISTS nx_schema_migration_state (
  migration_key VARCHAR(128) PRIMARY KEY,
  phase VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

SET @trial_eligible_column_added = (
  SELECT COUNT(*) = 0
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_product'
     AND COLUMN_NAME = 'trial_eligible'
);

SET @trial_eligible_existing_comment = (
  SELECT COLUMN_COMMENT
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_product'
     AND COLUMN_NAME = 'trial_eligible'
   LIMIT 1
);
SET @legacy_pending_s1_value = NULL;
SET @sql = IF(
  @trial_eligible_column_added,
  'SELECT 1',
  'SELECT MAX(trial_eligible) INTO @legacy_pending_s1_value FROM nx_product WHERE product_no = ''stellarbox-s1'' AND is_deleted = 0'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  @trial_eligible_column_added,
  'ALTER TABLE nx_product ADD COLUMN trial_eligible TINYINT NOT NULL DEFAULT 0 COMMENT ''trial-eligibility-migration-pending'' AFTER inventory_mode',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- A separate phase row makes the one-time compatibility seed atomic with its
-- durable SEEDED marker. If the process stops after the seed, a later operator
-- can turn the flag off without a rerun restoring it. Existing installations
-- that already have the column but no marker are adopted as FINAL. The one
-- ambiguous legacy state (old pending comment + S1=false) fails closed for
-- explicit operator resolution: it may mean either "seed never ran" or "seed
-- ran and an operator turned it off", so silently choosing either would lose
-- intent. Old pending + S1=true is safely adopted as SEEDED.
INSERT INTO nx_schema_migration_state (migration_key, phase)
VALUES (
  '20260826_product_trial_eligibility',
  IF(
    @trial_eligible_column_added,
    'PENDING',
    IF(
      @trial_eligible_existing_comment = 'trial-eligibility-migration-pending',
      IF(@legacy_pending_s1_value = 1, 'SEEDED', 'LEGACY_PENDING_REVIEW'),
      'FINAL'
    )
  )
)
ON DUPLICATE KEY UPDATE migration_key = VALUES(migration_key);

SET @trial_eligibility_phase = (
  SELECT phase
    FROM nx_schema_migration_state
   WHERE migration_key = '20260826_product_trial_eligibility'
   LIMIT 1
);
SET @sql = IF(
  @trial_eligibility_phase = 'LEGACY_PENDING_REVIEW',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''TRIAL_ELIGIBILITY_LEGACY_PENDING_REQUIRES_EXPLICIT_RESOLUTION''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

START TRANSACTION;
UPDATE nx_product p
  JOIN nx_schema_migration_state m
    ON m.migration_key = '20260826_product_trial_eligibility'
   AND m.phase = 'PENDING'
   SET p.trial_eligible = 1,
       p.updated_at = NOW()
 WHERE p.product_no = 'stellarbox-s1'
   AND p.is_deleted = 0;
UPDATE nx_schema_migration_state
   SET phase = 'SEEDED',
       updated_at = NOW()
 WHERE migration_key = '20260826_product_trial_eligibility'
   AND phase = 'PENDING';
COMMIT;

SET @trial_eligible_comment = (
  SELECT COLUMN_COMMENT
    FROM information_schema.COLUMNS
   WHERE TABLE_SCHEMA = DATABASE()
     AND TABLE_NAME = 'nx_product'
     AND COLUMN_NAME = 'trial_eligible'
   LIMIT 1
);
SET @sql = IF(
  COALESCE(@trial_eligible_comment, '') <> 'trial-eligibility-explicit-v1',
  'ALTER TABLE nx_product MODIFY COLUMN trial_eligible TINYINT NOT NULL DEFAULT 0 COMMENT ''trial-eligibility-explicit-v1'' AFTER inventory_mode',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE nx_schema_migration_state
   SET phase = 'FINAL',
       updated_at = NOW()
 WHERE migration_key = '20260826_product_trial_eligibility'
   AND phase = 'SEEDED';

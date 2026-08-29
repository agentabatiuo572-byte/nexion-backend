-- Classified retirement of the deployable Sandbox rail.
--
-- Invariant:
--   * every exact Sandbox fact is retained in the development-owned archive;
--   * only account identity is promoted to the canonical development rail;
--   * imported wallets are retained only as zero-balance identity scaffolding;
--   * consent, OAuth, device, money, webhook and every other Sandbox fact stay
--     archive-only and can never be relabelled as a canonical business fact;
--   * Sandbox-only tables are dropped from the active schema.

-- Keep migration and application ledger timestamps on the same canonical
-- business clock, independent of the Windows host timezone.
SET time_zone = '+08:00';

CREATE DATABASE IF NOT EXISTS nexion_development_archive_20260828
  CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS nexion_development_archive_20260828.sandbox_retirement_manifest (
  migration_id VARCHAR(64) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  archive_predicate VARCHAR(255) NOT NULL,
  source_count BIGINT NOT NULL,
  archive_count BIGINT NOT NULL,
  archived_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (migration_id, table_name)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS nexion_development_archive_20260828.sandbox_retirement_classification (
  migration_id VARCHAR(64) NOT NULL,
  table_name VARCHAR(128) NOT NULL,
  disposition VARCHAR(48) NOT NULL,
  reason VARCHAR(500) NOT NULL,
  source_count BIGINT NOT NULL,
  applied_count BIGINT NOT NULL DEFAULT 0,
  status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  classified_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  applied_at DATETIME(6) NULL,
  PRIMARY KEY (migration_id, table_name)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof (
  migration_id VARCHAR(64) NOT NULL,
  source_count BIGINT NOT NULL,
  zero_balance_count BIGINT NOT NULL,
  mock_amounts_reused TINYINT(1) NOT NULL DEFAULT 0,
  completed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  canonical_ledger_cutoff_at DATETIME(6) NULL,
  PRIMARY KEY (migration_id)
) ENGINE=InnoDB;

SET @wallet_proof_cutoff_ddl=(SELECT IF(
  EXISTS(SELECT 1 FROM information_schema.columns
          WHERE table_schema='nexion_development_archive_20260828'
            AND table_name='sandbox_retirement_wallet_reset_proof'
            AND column_name='canonical_ledger_cutoff_at'),
  'DO 0',
  'ALTER TABLE nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof ADD COLUMN canonical_ledger_cutoff_at DATETIME(6) NULL AFTER completed_at'));
PREPARE stmt FROM @wallet_proof_cutoff_ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;

CREATE TABLE IF NOT EXISTS nexion_development_archive_20260828.sandbox_retirement_wallet_reset_item (
  migration_id VARCHAR(64) NOT NULL,
  wallet_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  zero_balance_verified TINYINT(1) NOT NULL,
  proof_origin VARCHAR(48) NOT NULL,
  reset_at DATETIME(6) NOT NULL,
  reconciled_at DATETIME(6) NOT NULL,
  PRIMARY KEY (migration_id, wallet_id),
  KEY idx_wallet_reset_item_user (migration_id, user_id)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS nexion.nx_data_environment_migration (
  migration_id VARCHAR(64) NOT NULL,
  source_environment VARCHAR(32) NOT NULL,
  target_environment VARCHAR(32) NOT NULL,
  archive_schema VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,
  completed_at DATETIME(6) NOT NULL,
  PRIMARY KEY (migration_id)
) ENGINE=InnoDB;

DROP PROCEDURE IF EXISTS nexion.retire_sandbox_to_development_v2;
DELIMITER $$
CREATE PROCEDURE nexion.retire_sandbox_to_development_v2()
main: BEGIN
  DECLARE v_done INT DEFAULT 0;
  DECLARE v_table VARCHAR(128);
  DECLARE v_has_source INT DEFAULT 0;
  DECLARE v_has_sandbox INT DEFAULT 0;
  DECLARE v_is_sandbox_table INT DEFAULT 0;
  DECLARE v_predicate VARCHAR(255);
  DECLARE v_columns LONGTEXT;
  DECLARE v_disposition VARCHAR(48);
  DECLARE v_source_count BIGINT DEFAULT 0;
  DECLARE v_lock INT DEFAULT 0;
  DECLARE v_residual BIGINT DEFAULT 0;
  DECLARE v_index_exists INT DEFAULT 0;

  DECLARE active_cursor CURSOR FOR
    SELECT t.table_name,
           EXISTS(SELECT 1 FROM information_schema.columns c
                   WHERE c.table_schema='nexion' AND c.table_name=t.table_name
                     AND c.column_name='source_environment'),
           EXISTS(SELECT 1 FROM information_schema.columns c
                   WHERE c.table_schema='nexion' AND c.table_name=t.table_name
                     AND c.column_name='sandbox'),
           t.table_name LIKE '%sandbox%'
      FROM information_schema.tables t
     WHERE t.table_schema='nexion'
       AND t.table_type='BASE TABLE'
       AND t.table_name<>'nx_data_environment_migration'
       AND (t.table_name LIKE '%sandbox%'
            OR EXISTS(SELECT 1 FROM information_schema.columns c
                       WHERE c.table_schema='nexion' AND c.table_name=t.table_name
                         AND c.column_name IN ('source_environment','sandbox')))
     ORDER BY t.table_name;
  DECLARE classification_cursor CURSOR FOR
    SELECT table_name,disposition,source_count
      FROM nexion_development_archive_20260828.sandbox_retirement_classification
     WHERE migration_id='sandbox-to-development-v2-classified'
     ORDER BY CASE disposition
       WHEN 'PROMOTE_ACCOUNT_IDENTITY' THEN 1
       WHEN 'RESET_WALLET_SCAFFOLD' THEN 2
       WHEN 'ARCHIVE_ONLY_DELETE' THEN 3
       ELSE 4 END,
       CASE table_name WHEN 'nx_developer_webhook_delivery' THEN 0 ELSE 1 END,
       table_name;
  DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done=1;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    -- DDL in this migration commits implicitly, so recovery is deliberately
    -- checkpointed and idempotent instead of pretending to be cross-table
    -- transactional. A rerun reuses the archive/manifest and revalidates every
    -- completed classification before restoring the COMPLETED marker.
    UPDATE nexion.nx_data_environment_migration
       SET source_environment='RETIRED_SOURCE',target_environment='DEVELOPMENT',
           archive_schema='nexion_development_archive_20260828',status='FAILED',
           completed_at=NOW(6)
     WHERE migration_id='sandbox-to-development-v2-classified';
    DO RELEASE_LOCK('nexion:sandbox-retirement:v2');
    RESIGNAL;
  END;

  SELECT GET_LOCK('nexion:sandbox-retirement:v2',10) INTO v_lock;
  IF v_lock<>1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SANDBOX_RETIREMENT_LOCK_UNAVAILABLE';
  END IF;

  INSERT INTO nexion.nx_data_environment_migration(
    migration_id,source_environment,target_environment,archive_schema,status,completed_at)
  VALUES('sandbox-to-development-v2-classified','RETIRED_SOURCE','DEVELOPMENT',
         'nexion_development_archive_20260828','IN_PROGRESS',NOW(6))
  ON DUPLICATE KEY UPDATE source_environment=VALUES(source_environment),
    target_environment=VALUES(target_environment),archive_schema=VALUES(archive_schema),
    status=VALUES(status),completed_at=VALUES(completed_at);

  -- Reuse the already frozen v1 inventory when upgrading an installation that
  -- ran the original retirement. Fresh installations populate the same v2
  -- manifest from the active rows in the archive loop below.
  INSERT IGNORE INTO nexion_development_archive_20260828.sandbox_retirement_manifest(
    migration_id,table_name,archive_predicate,source_count,archive_count,archived_at)
  SELECT 'sandbox-to-development-v2-classified',table_name,archive_predicate,
         source_count,archive_count,archived_at
    FROM nexion_development_archive_20260828.sandbox_retirement_manifest
   WHERE migration_id='sandbox-to-development-v1'
  ;
  UPDATE nexion_development_archive_20260828.sandbox_retirement_manifest v2
  JOIN nexion_development_archive_20260828.sandbox_retirement_manifest v1
    ON v1.migration_id='sandbox-to-development-v1'
   AND v2.migration_id='sandbox-to-development-v2-classified'
   AND v2.table_name=v1.table_name
     SET v2.source_count=GREATEST(v2.source_count,v1.source_count),
         v2.archive_count=GREATEST(v2.archive_count,v1.archive_count);
  DELETE FROM nexion_development_archive_20260828.sandbox_retirement_manifest
   WHERE migration_id='sandbox-to-development-v2-classified'
     AND table_name='nx_data_environment_migration';
  DELETE FROM nexion_development_archive_20260828.sandbox_retirement_classification
   WHERE migration_id='sandbox-to-development-v2-classified'
     AND table_name='nx_data_environment_migration';

  SET v_done=0;
  OPEN active_cursor;
  archive_loop: LOOP
    FETCH active_cursor INTO v_table,v_has_source,v_has_sandbox,v_is_sandbox_table;
    IF v_done=1 THEN LEAVE archive_loop; END IF;
    SET v_predicate=CASE
      WHEN v_is_sandbox_table=1 THEN '1=1'
      WHEN v_has_source=1 THEN "source_environment='SANDBOX'"
      ELSE 'sandbox=1'
    END;
    SET @ddl=CONCAT('CREATE TABLE IF NOT EXISTS nexion_development_archive_20260828.`',
                    v_table,'` LIKE nexion.`',v_table,'`');
    PREPARE stmt FROM @ddl; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    SELECT GROUP_CONCAT(CONCAT('`',column_name,'`') ORDER BY ordinal_position SEPARATOR ',')
      INTO v_columns
      FROM information_schema.columns
     WHERE table_schema='nexion' AND table_name=v_table
       AND extra NOT LIKE '%GENERATED%';
    SET @copy=CONCAT('INSERT IGNORE INTO nexion_development_archive_20260828.`',v_table,
                     '` (',v_columns,') SELECT ',v_columns,' FROM nexion.`',v_table,
                     '` WHERE ',v_predicate);
    SET @count_source=CONCAT('SELECT COUNT(*) INTO @src_count FROM nexion.`',
      v_table,'` WHERE ',v_predicate);
    PREPARE stmt FROM @count_source; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    PREPARE stmt FROM @copy; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    SET @count_archive=CONCAT('SELECT COUNT(*) INTO @arc_count FROM ',
      'nexion_development_archive_20260828.`',v_table,'` WHERE ',v_predicate);
    PREPARE stmt FROM @count_archive; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    INSERT INTO nexion_development_archive_20260828.sandbox_retirement_manifest(
      migration_id,table_name,archive_predicate,source_count,archive_count,archived_at)
    VALUES('sandbox-to-development-v2-classified',v_table,v_predicate,@src_count,@arc_count,NOW(6))
    ON DUPLICATE KEY UPDATE
      archive_predicate=VALUES(archive_predicate),
      source_count=GREATEST(sandbox_retirement_manifest.source_count,VALUES(source_count)),
      archive_count=GREATEST(sandbox_retirement_manifest.archive_count,VALUES(archive_count)),
      archived_at=VALUES(archived_at);
  END LOOP;
  CLOSE active_cursor;

  IF EXISTS(
    SELECT 1 FROM nexion_development_archive_20260828.sandbox_retirement_manifest
     WHERE migration_id='sandbox-to-development-v2-classified'
       AND source_count<>archive_count
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SANDBOX_ARCHIVE_COUNT_MISMATCH';
  END IF;

  -- Explicit, fail-closed classification. No business table is promoted by a
  -- wildcard. Account identity is the only allowlisted active fact. Wallet
  -- rows survive solely so canonical code has a row to lock; every money field
  -- is reset, while its exact former values remain recoverable in the archive.
  INSERT INTO nexion_development_archive_20260828.sandbox_retirement_classification(
    migration_id,table_name,disposition,reason,source_count)
  SELECT 'sandbox-to-development-v2-classified',m.table_name,
         CASE
           WHEN m.table_name='nx_user' THEN 'PROMOTE_ACCOUNT_IDENTITY'
           WHEN m.table_name='nx_user_wallet' THEN 'RESET_WALLET_SCAFFOLD'
           WHEN m.table_name LIKE '%sandbox%' THEN 'ARCHIVE_ONLY_DROP_TABLE'
           ELSE 'ARCHIVE_ONLY_DELETE'
         END,
         CASE
           WHEN m.table_name='nx_user' THEN
             'Account identity is imported so existing development logins remain reachable.'
           WHEN m.table_name='nx_user_wallet' THEN
             'Original balances are mock financial facts; keep only a zero-balance development wallet row.'
           WHEN m.table_name LIKE '%sandbox%' THEN
             'Sandbox-only table is preserved exactly in the archive and removed from the active schema.'
           ELSE
             'Non-identity Sandbox fact is quarantined; it must not be relabelled as canonical business truth.'
         END,
         m.source_count
    FROM nexion_development_archive_20260828.sandbox_retirement_manifest m
   WHERE m.migration_id='sandbox-to-development-v2-classified'
  ON DUPLICATE KEY UPDATE source_count=GREATEST(
    sandbox_retirement_classification.source_count,VALUES(source_count));

  IF EXISTS(SELECT 1 FROM (
      SELECT country_code,phone,COUNT(*) c
        FROM nexion.nx_user
       WHERE is_deleted=0
       GROUP BY country_code,phone HAVING COUNT(*)>1
  ) duplicate_phone) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SANDBOX_USER_CANONICALIZATION_COLLISION';
  END IF;

  SET v_done=0;
  OPEN classification_cursor;
  apply_loop: LOOP
    FETCH classification_cursor INTO v_table,v_disposition,v_source_count;
    IF v_done=1 THEN LEAVE apply_loop; END IF;

    IF EXISTS(SELECT 1 FROM information_schema.tables
               WHERE table_schema='nexion' AND table_name=v_table
                 AND table_type='BASE TABLE') THEN
      IF v_disposition='PROMOTE_ACCOUNT_IDENTITY' THEN
        UPDATE nexion.nx_user u
        JOIN nexion_development_archive_20260828.nx_user a ON a.id=u.id
           SET u.sandbox=0
         WHERE a.sandbox=1;
      ELSEIF v_disposition='RESET_WALLET_SCAFFOLD' THEN
        -- Reset mock balances exactly once. After promotion the development
        -- account may legitimately earn or deposit, so an idempotent rerun must
        -- never erase later canonical activity.
        IF NOT EXISTS(
          SELECT 1
            FROM nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof
           WHERE migration_id='sandbox-to-development-v2-classified'
        ) THEN
          UPDATE nexion.nx_user_wallet w
          JOIN nexion_development_archive_20260828.nx_user_wallet a ON a.id=w.id
             SET w.sandbox=0,
                 w.usdt_available=0,
                 w.nex_available=0,
                 w.pending_withdraw=0,
                 w.lifetime_earned=0,
                 w.cumulative_deposit_usdt=0,
                 w.version=w.version+1,
                 w.updated_at=NOW()
           WHERE a.sandbox=1
             AND (w.sandbox<>0 OR w.usdt_available<>0 OR w.nex_available<>0
               OR w.pending_withdraw<>0 OR w.lifetime_earned<>0
               OR w.cumulative_deposit_usdt<>0);
          SELECT COUNT(*) INTO @zero_balance_count
            FROM nexion.nx_user_wallet w
            JOIN nexion_development_archive_20260828.nx_user_wallet a ON a.id=w.id
           WHERE a.sandbox=1 AND w.sandbox=0
             AND w.usdt_available=0 AND w.nex_available=0
             AND w.pending_withdraw=0 AND w.lifetime_earned=0
             AND w.cumulative_deposit_usdt=0;
          IF @zero_balance_count<>v_source_count THEN
            SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SANDBOX_WALLET_RESET_INCOMPLETE';
          END IF;
          INSERT INTO nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof(
            migration_id,source_count,zero_balance_count,mock_amounts_reused,completed_at,
            canonical_ledger_cutoff_at)
          VALUES('sandbox-to-development-v2-classified',v_source_count,@zero_balance_count,0,NOW(6),NOW(6));
          INSERT INTO nexion_development_archive_20260828.sandbox_retirement_wallet_reset_item(
            migration_id,wallet_id,user_id,zero_balance_verified,proof_origin,reset_at,reconciled_at)
          SELECT 'sandbox-to-development-v2-classified',w.id,w.user_id,1,'DIRECT_RESET',
                 p.canonical_ledger_cutoff_at,NOW(6)
            FROM nexion.nx_user_wallet w
            JOIN nexion_development_archive_20260828.nx_user_wallet a ON a.id=w.id
            JOIN nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof p
              ON p.migration_id='sandbox-to-development-v2-classified'
           WHERE a.sandbox=1 AND w.sandbox=0
             AND w.usdt_available=0 AND w.nex_available=0
             AND w.pending_withdraw=0 AND w.lifetime_earned=0
             AND w.cumulative_deposit_usdt=0;
        ELSE
          UPDATE nexion.nx_user_wallet w
          JOIN nexion_development_archive_20260828.nx_user_wallet a ON a.id=w.id
             SET w.sandbox=0
           WHERE a.sandbox=1 AND w.sandbox<>0;
        END IF;
      ELSEIF v_disposition='ARCHIVE_ONLY_DELETE' THEN
        IF NOT EXISTS(SELECT 1 FROM information_schema.columns
                       WHERE table_schema='nexion' AND table_name=v_table
                         AND column_name='id') THEN
          SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SANDBOX_SHARED_TABLE_WITHOUT_ID';
        END IF;
        SET @quarantine=CONCAT('DELETE a FROM nexion.`',v_table,
          '` a JOIN nexion_development_archive_20260828.`',v_table,'` z ON z.id=a.id');
        PREPARE stmt FROM @quarantine; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      ELSEIF v_disposition='ARCHIVE_ONLY_DROP_TABLE' THEN
        SET @drop_table=CONCAT('DROP TABLE nexion.`',v_table,'`');
        PREPARE stmt FROM @drop_table; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      ELSE
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SANDBOX_CLASSIFICATION_UNKNOWN';
      END IF;
    END IF;

    UPDATE nexion_development_archive_20260828.sandbox_retirement_classification
       SET applied_count=source_count,status='COMPLETED',applied_at=COALESCE(applied_at,NOW(6))
     WHERE migration_id='sandbox-to-development-v2-classified' AND table_name=v_table;
  END LOOP;
  CLOSE classification_cursor;

  SELECT COUNT(DISTINCT index_name) INTO v_index_exists
    FROM information_schema.statistics
   WHERE table_schema='nexion' AND table_name='nx_user'
     AND index_name='uk_user_phone_sandbox';
  IF v_index_exists>0 THEN
    ALTER TABLE nexion.nx_user
      DROP INDEX uk_user_phone_sandbox,
      ADD UNIQUE INDEX uk_user_phone(country_code,phone);
  END IF;

  -- Every run, including an idempotent rerun, revalidates the archive and the
  -- active schema. A completed marker never bypasses these gates.
  SET v_residual=(SELECT COUNT(*) FROM nexion.nx_user WHERE sandbox<>0)
    +(SELECT COUNT(*) FROM nexion.nx_user_wallet WHERE sandbox<>0);
  SELECT v_residual+COUNT(*) INTO v_residual
    FROM information_schema.tables
   WHERE table_schema='nexion' AND table_type='BASE TABLE'
     AND table_name LIKE '%sandbox%';
  IF v_residual<>0 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SANDBOX_ACTIVE_DATA_REMAINS';
  END IF;
  IF EXISTS(
    SELECT 1 FROM nexion_development_archive_20260828.sandbox_retirement_classification
     WHERE migration_id='sandbox-to-development-v2-classified'
       AND (status<>'COMPLETED' OR applied_count<>source_count)
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SANDBOX_CLASSIFICATION_INCOMPLETE';
  END IF;
  IF NOT EXISTS(
    SELECT 1
      FROM nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof p
      JOIN nexion_development_archive_20260828.sandbox_retirement_classification c
        ON c.migration_id=p.migration_id AND c.table_name='nx_user_wallet'
     WHERE p.migration_id='sandbox-to-development-v2-classified'
       AND p.source_count=c.source_count
       AND p.zero_balance_count=p.source_count
       AND p.mock_amounts_reused=0
       AND p.canonical_ledger_cutoff_at IS NOT NULL
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SANDBOX_WALLET_RESET_PROOF_MISSING';
  END IF;
  IF (SELECT COUNT(*)
        FROM nexion_development_archive_20260828.sandbox_retirement_wallet_reset_item
       WHERE migration_id='sandbox-to-development-v2-classified'
         AND zero_balance_verified=1
         AND proof_origin IN ('DIRECT_RESET','RECONCILED_POST_RESET_LEDGER'))
     <> (SELECT source_count
           FROM nexion_development_archive_20260828.sandbox_retirement_wallet_reset_proof
          WHERE migration_id='sandbox-to-development-v2-classified') THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SANDBOX_WALLET_RESET_ITEM_PROOF_MISSING';
  END IF;

  SET v_done=0;
  OPEN classification_cursor;
  verify_quarantine_loop: LOOP
    FETCH classification_cursor INTO v_table,v_disposition,v_source_count;
    IF v_done=1 THEN LEAVE verify_quarantine_loop; END IF;
    IF v_disposition='ARCHIVE_ONLY_DELETE' THEN
      SET @verify=CONCAT('SELECT COUNT(*) INTO @remaining FROM nexion.`',v_table,
        '` a JOIN nexion_development_archive_20260828.`',v_table,'` z ON z.id=a.id');
      PREPARE stmt FROM @verify; EXECUTE stmt; DEALLOCATE PREPARE stmt;
      IF @remaining<>0 THEN
        SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='SANDBOX_QUARANTINE_ROW_REMAINS';
      END IF;
    END IF;
  END LOOP;
  CLOSE classification_cursor;

  UPDATE nexion.nx_data_environment_migration
     SET source_environment='RETIRED_SOURCE',target_environment='DEVELOPMENT',
         status='SUPERSEDED',completed_at=NOW(6)
   WHERE migration_id='sandbox-to-development-v1';
  INSERT INTO nexion.nx_data_environment_migration(
    migration_id,source_environment,target_environment,archive_schema,status,completed_at)
  VALUES('sandbox-to-development-v2-classified','RETIRED_SOURCE','DEVELOPMENT',
         'nexion_development_archive_20260828','COMPLETED',NOW(6))
  ON DUPLICATE KEY UPDATE source_environment=VALUES(source_environment),
    target_environment=VALUES(target_environment),archive_schema=VALUES(archive_schema),
    status=VALUES(status),completed_at=VALUES(completed_at);
  DO RELEASE_LOCK('nexion:sandbox-retirement:v2');
END$$
DELIMITER ;

CALL nexion.retire_sandbox_to_development_v2();
DROP PROCEDURE IF EXISTS nexion.retire_sandbox_to_development_v2;

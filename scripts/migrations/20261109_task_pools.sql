-- PLAN-v3.1: stop application, migrate once, then start new code.
-- Versions remain append-only; pool labels never touch epoch or wallet triggers.
CREATE TABLE IF NOT EXISTS nx_sim_task_pool (
  code VARCHAR(32) NOT NULL PRIMARY KEY,
  kind VARCHAR(8) NOT NULL,
  level INT NULL,
  name VARCHAR(128) NOT NULL,
  sort_order INT NOT NULL,
  CHECK(kind IN ('DEVICE','PHONE')),
  UNIQUE KEY uk_sim_pool_level(level)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
CREATE TABLE IF NOT EXISTS nx_sim_task_pool_cutover (
  id TINYINT NOT NULL PRIMARY KEY,
  effective_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
SET @pool_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_sim_task_type_version' AND column_name='pool_code'), 'SELECT 1',
  'ALTER TABLE nx_sim_task_type_version ADD COLUMN pool_code VARCHAR(32) NULL');
PREPARE pool_stmt FROM @pool_ddl;
EXECUTE pool_stmt;
DEALLOCATE PREPARE pool_stmt;
SET @pool_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_sim_product_task_policy_version' AND column_name='allowed_pool_codes'), 'SELECT 1',
  'ALTER TABLE nx_sim_product_task_policy_version ADD COLUMN allowed_pool_codes JSON NULL');
PREPARE pool_stmt FROM @pool_ddl;
EXECUTE pool_stmt;
DEALLOCATE PREPARE pool_stmt;
SET @pool_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_admin_device_sku' AND column_name='allowed_pool_codes_json'), 'SELECT 1',
  'ALTER TABLE nx_admin_device_sku ADD COLUMN allowed_pool_codes_json JSON NULL');
PREPARE pool_stmt FROM @pool_ddl;
EXECUTE pool_stmt;
DEALLOCATE PREPARE pool_stmt;
SET @pool_ddl = IF(EXISTS(SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE()
  AND table_name='nx_simulated_device_runtime' AND index_name='idx_sim_runtime_product_state'), 'SELECT 1',
  'ALTER TABLE nx_simulated_device_runtime ADD KEY idx_sim_runtime_product_state(product_code,state)');
PREPARE pool_stmt FROM @pool_ddl;
EXECUTE pool_stmt;
DEALLOCATE PREPARE pool_stmt;

DROP PROCEDURE IF EXISTS cutover_sim_task_pools;
DELIMITER $$
CREATE PROCEDURE cutover_sim_task_pools()
BEGIN
  DECLARE cutover_at DATETIME(6);
  IF NOT EXISTS(SELECT 1 FROM nx_sim_task_pool_cutover WHERE id=1) THEN
    SET cutover_at=CONVERT_TZ(UTC_TIMESTAMP(6),'+00:00','+08:00');
    START TRANSACTION;
    INSERT INTO nx_sim_task_pool_cutover VALUES(1,cutover_at);
    INSERT INTO nx_sim_task_pool(code,kind,level,name,sort_order) VALUES
      ('L1','DEVICE',1,'初级',10),('L2','DEVICE',2,'中级',20),
      ('L3','DEVICE',3,'高级',30),('L4','DEVICE',4,'旗舰',40),('PHONE','PHONE',NULL,'手机任务池',0);

    -- Resolve the phone's published concrete candidates BEFORE appending any version.
    CREATE TEMPORARY TABLE sim_pool_phone_candidates AS
    SELECT t.* FROM (
      SELECT v.*, ROW_NUMBER() OVER(PARTITION BY task_type_id ORDER BY effective_at DESC,version DESC) rn
      FROM nx_sim_task_type_version v WHERE effective_at<=cutover_at
    ) t JOIN (
      SELECT * FROM nx_sim_product_task_policy_version WHERE product_key='phone' AND effective_at<=cutover_at
      ORDER BY effective_at DESC,version DESC LIMIT 1
    ) p ON IF(p.allowed_task_classes IS NULL,
      JSON_CONTAINS(p.allowed_task_type_ids,JSON_QUOTE(t.task_type_id)),
      JSON_CONTAINS(p.allowed_task_classes,JSON_QUOTE(t.task_class)))
    WHERE t.rn=1;
    INSERT INTO nx_sim_task_type_version(task_type_id,version,effective_at,name,task_class,duration_seconds,base_reward,asset,enabled,pool_code)
      SELECT t.task_type_id,(SELECT MAX(v.version)+1 FROM nx_sim_task_type_version v WHERE v.task_type_id=t.task_type_id),
        cutover_at,t.name,t.task_class,t.duration_seconds,t.base_reward,t.asset,t.enabled,'PHONE'
      FROM sim_pool_phone_candidates t ORDER BY t.task_type_id;
    DROP TEMPORARY TABLE sim_pool_phone_candidates;

    -- All published non-phone keys, including delisted/deleted keys, retain their published T/b.
    CREATE TEMPORARY TABLE sim_pool_published AS
      SELECT p.*, (SELECT MAX(n.version) FROM nx_sim_product_task_policy_version n WHERE n.product_key=p.product_key) max_version
      FROM (SELECT v.*,ROW_NUMBER() OVER(PARTITION BY product_key ORDER BY effective_at DESC,version DESC) rn
        FROM nx_sim_product_task_policy_version v WHERE effective_at<=cutover_at) p WHERE p.rn=1;
    INSERT INTO nx_sim_product_task_policy_version(product_key,version,effective_at,allowed_task_type_ids,allowed_task_classes,
      allowed_pool_codes,monthly_target_usdt,monthly_target_band_pct)
      SELECT product_key,max_version+1,cutover_at,JSON_ARRAY(),NULL,
        IF(product_key='phone',JSON_ARRAY('PHONE'),JSON_ARRAY()),monthly_target_usdt,monthly_target_band_pct
      FROM sim_pool_published ORDER BY product_key;
    IF NOT EXISTS(SELECT 1 FROM sim_pool_published WHERE product_key='phone') THEN
      INSERT INTO nx_sim_product_task_policy_version(product_key,version,effective_at,allowed_task_type_ids,allowed_pool_codes)
        VALUES('phone',1,cutover_at,JSON_ARRAY(),JSON_ARRAY('PHONE'));
    END IF;
    DROP TEMPORARY TABLE sim_pool_published;
    -- Drafts get no published history. The old columns are no longer writable by the application.
    UPDATE nx_admin_device_sku SET allowed_task_type_ids_json=NULL,allowed_task_classes_json=NULL,allowed_pool_codes_json=JSON_ARRAY();

    -- Only old task/scope tickets; datacenter retirement is owned by the separate migration.
    CREATE TEMPORARY TABLE sim_pool_old_tickets AS
      SELECT operation_id FROM nx_audit_operation_ticket WHERE status='pending' AND is_deleted=0
        AND JSON_VALID(command_json)
        AND JSON_UNQUOTE(JSON_EXTRACT(command_json,'$.domain'))='E'
        AND JSON_UNQUOTE(JSON_EXTRACT(command_json,'$.op')) IN
          ('e1_sku_create','e1_sku_update','e1_sku_status','e1_phone_task_policy','e2_task_create','e2_task_update','e2_task_status');
    UPDATE nx_audit_operation_ticket t JOIN sim_pool_old_tickets o ON o.operation_id=t.operation_id
      SET t.status='rejected',t.decision_reason='TASK_POOL_FORMAT_RETIRED',t.decided_at=cutover_at;
    DELETE l FROM nx_audit_object_lock l JOIN sim_pool_old_tickets o ON o.operation_id=l.ticket_id;
    DROP TEMPORARY TABLE sim_pool_old_tickets;
    COMMIT;
  END IF;
END$$
DELIMITER ;
CALL cutover_sim_task_pools();
DROP PROCEDURE cutover_sim_task_pools;

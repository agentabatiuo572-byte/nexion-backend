-- Old versions retain their exact IDs. Only new policy versions grant classes.
SET @class_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_sim_product_task_policy_version' AND column_name='allowed_task_classes'),
  'SELECT 1','ALTER TABLE nx_sim_product_task_policy_version ADD COLUMN allowed_task_classes JSON NULL');
PREPARE class_stmt FROM @class_ddl;
EXECUTE class_stmt;
DEALLOCATE PREPARE class_stmt;
SET @class_ddl = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_admin_device_sku' AND column_name='allowed_task_classes_json'),
  'SELECT 1','ALTER TABLE nx_admin_device_sku ADD COLUMN allowed_task_classes_json JSON NULL');
PREPARE class_stmt FROM @class_ddl;
EXECUTE class_stmt;
DEALLOCATE PREPARE class_stmt;

SET @class_cut = UTC_TIMESTAMP(6) + INTERVAL 8 HOUR;
CREATE TEMPORARY TABLE scope_sources (
  kind CHAR(1), product_key VARCHAR(64), version BIGINT, ids JSON NOT NULL,
  PRIMARY KEY(kind,product_key)
) CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
-- A task-pool publication is already in the new format. Never republish it as an empty legacy scope
-- on a later startup (which would also discard its target). The column is absent on the first upgrade.
SET @class_pool_filter = IF(EXISTS(SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
  AND table_name='nx_sim_product_task_policy_version' AND column_name='allowed_pool_codes'),
  ' AND p.allowed_pool_codes IS NULL','');
SET @class_sources = CONCAT('INSERT INTO scope_sources
SELECT ''P'',p.product_key,p.version,p.allowed_task_type_ids FROM nx_sim_product_task_policy_version p
WHERE p.allowed_task_classes IS NULL',@class_pool_filter,' AND NOT EXISTS
  (SELECT 1 FROM nx_sim_product_task_policy_version n WHERE n.product_key=p.product_key AND n.version>p.version)');
PREPARE class_stmt FROM @class_sources;
EXECUTE class_stmt;
DEALLOCATE PREPARE class_stmt;
INSERT INTO scope_sources
SELECT 'D',sku_id,0,allowed_task_type_ids_json FROM nx_admin_device_sku
WHERE is_deleted=0 AND allowed_task_classes_json IS NULL AND allowed_task_type_ids_json IS NOT NULL;

CREATE TEMPORARY TABLE scope_catalog AS
SELECT task_type_id,task_class FROM (
  SELECT t.*,ROW_NUMBER() OVER(PARTITION BY task_type_id ORDER BY effective_at DESC,version DESC) AS pos
  FROM nx_sim_task_type_version t WHERE effective_at<=@class_cut
) ranked WHERE pos=1;
CREATE TEMPORARY TABLE scope_counts AS
SELECT s.kind,s.product_key,c.task_class,COUNT(*) AS total,
  SUM(JSON_CONTAINS(s.ids,JSON_QUOTE(c.task_type_id))) AS selected_count
FROM scope_sources s CROSS JOIN scope_catalog c GROUP BY s.kind,s.product_key,c.task_class;

-- A partial class or missing ID cannot be converted without granting new rights.
CREATE TEMPORARY TABLE scope_check (valid TINYINT NOT NULL,
  CONSTRAINT chk_task_class_cutover_requires_whole_known_classes CHECK(valid=1));
INSERT INTO scope_check SELECT 0 FROM nx_admin_device_sku WHERE is_deleted=0 AND sku_id='phone' LIMIT 1;
INSERT INTO scope_check SELECT 0 FROM nx_product WHERE is_deleted=0 AND product_no='phone' LIMIT 1;
INSERT INTO scope_check SELECT 0 FROM scope_counts
 WHERE selected_count>0 AND (selected_count<>total OR task_class NOT IN ('EM','SP','IG','LL','FT','VG')) LIMIT 1;
INSERT INTO scope_check SELECT 0 FROM scope_sources s LEFT JOIN
  (SELECT kind,product_key,SUM(selected_count) AS matched FROM scope_counts GROUP BY kind,product_key) m
  ON m.kind=s.kind AND m.product_key=s.product_key WHERE JSON_LENGTH(s.ids)<>COALESCE(m.matched,0) LIMIT 1;
INSERT INTO scope_check SELECT 0 FROM scope_sources s JOIN nx_sim_product_task_policy_version p
  ON s.kind='P' AND s.product_key=p.product_key AND s.version=p.version WHERE p.effective_at>=@class_cut LIMIT 1;
CREATE TEMPORARY TABLE scope_classes AS
SELECT kind,product_key,JSON_ARRAYAGG(task_class) AS classes FROM scope_counts WHERE selected_count=total
GROUP BY kind,product_key;

START TRANSACTION;
INSERT INTO nx_sim_product_task_policy_version(product_key,version,effective_at,allowed_task_type_ids,allowed_task_classes)
SELECT s.product_key,s.version+1,@class_cut,s.ids,COALESCE(c.classes,JSON_ARRAY())
FROM scope_sources s LEFT JOIN scope_classes c ON c.kind=s.kind AND c.product_key=s.product_key WHERE s.kind='P';
UPDATE nx_admin_device_sku d JOIN scope_sources s ON s.kind='D' AND d.sku_id COLLATE utf8mb4_unicode_ci=s.product_key
LEFT JOIN scope_classes c ON c.kind=s.kind AND c.product_key=s.product_key
SET d.allowed_task_classes_json=COALESCE(c.classes,JSON_ARRAY());
UPDATE nx_product p JOIN (SELECT DISTINCT product_key FROM scope_sources) s
  ON p.product_no COLLATE utf8mb4_unicode_ci=s.product_key
SET p.updated_at=GREATEST(@class_cut,p.updated_at + INTERVAL 1 SECOND) WHERE p.is_deleted=0;
COMMIT;
DROP TEMPORARY TABLE scope_classes,scope_check,scope_counts,scope_catalog,scope_sources;

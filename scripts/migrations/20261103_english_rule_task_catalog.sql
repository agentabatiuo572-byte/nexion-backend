-- Expand the six previously published rule configurations without changing their economics.
-- A fresh database or an already expanded/operator-customized catalog keeps its configuration.
-- The E2 base table is normally initialized by Java after startup migrations.
CREATE TABLE IF NOT EXISTS nx_admin_device_task (
  id BIGINT PRIMARY KEY AUTO_INCREMENT,
  task_id VARCHAR(64) NOT NULL,
  name VARCHAR(128) NOT NULL,
  price DECIMAL(18,4) NOT NULL DEFAULT 0,
  unit_text VARCHAR(32) NOT NULL DEFAULT '/job',
  requirement VARCHAR(128) NOT NULL DEFAULT 'S1+',
  saturation DECIMAL(7,4) NOT NULL DEFAULT 0,
  status VARCHAR(32) NOT NULL DEFAULT 'active',
  task_class VARCHAR(64) NOT NULL DEFAULT 'LL',
  model_name VARCHAR(128) NOT NULL DEFAULT '',
  min_reward DECIMAL(18,6) NOT NULL DEFAULT 0,
  max_reward DECIMAL(18,6) NOT NULL DEFAULT 0,
  min_vram VARCHAR(64) NOT NULL DEFAULT '',
  kill_init VARCHAR(32) NOT NULL DEFAULT '派发中',
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_admin_device_task (task_id),
  KEY idx_admin_device_task_status (status,is_deleted),
  KEY idx_admin_device_task_name (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TEMPORARY TABLE catalog_vocabulary (
  task_class VARCHAR(8) PRIMARY KEY, label VARCHAR(32) NOT NULL, qualifiers JSON NOT NULL, subjects JSON NOT NULL
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
INSERT INTO catalog_vocabulary VALUES
  ('IG', 'image gen', JSON_ARRAY('ink', 'watercolor', 'oil paint', 'pixel art', 'pencil', 'low poly', 'photoreal'), JSON_ARRAY('forest', 'cityscape', 'portrait', 'coast', 'still life', 'observatory', 'night sky')),
  ('VG', 'video gen', JSON_ARRAY('pan', 'tracking shot', 'flyover', 'orbit', 'time lapse', 'slow motion', 'dolly shot'), JSON_ARRAY('harbor', 'forest canopy', 'city square', 'coastline', 'desert dunes', 'mountain ridge', 'coral reef')),
  ('LL', 'language inference', JSON_ARRAY('summarize', 'classify', 'translate', 'outline', 'tag', 'rewrite', 'compare'), JSON_ARRAY('support notes', 'news briefs', 'product reviews', 'release notes', 'meeting minutes', 'research abstracts', 'help articles')),
  ('FT', 'fine tuning', JSON_ARRAY('travel', 'commerce', 'education', 'support', 'publishing', 'science', 'software'), JSON_ARRAY('intent classification', 'tone matching', 'entity tagging', 'query routing', 'response ranking', 'style adaptation', 'summary tuning')),
  ('EM', 'vector embedding', JSON_ARRAY('caption', 'query', 'article', 'product title', 'support ticket', 'review', 'paragraph'), JSON_ARRAY('similarity', 'indexing', 'deduplication', 'clustering', 'semantic search', 'nearest neighbor', 'ranking')),
  ('SP', 'speech processing', JSON_ARRAY('interview', 'lecture', 'podcast', 'meeting', 'voice note', 'call', 'news audio'), JSON_ARRAY('transcription', 'captioning', 'speaker tracking', 'keyword spotting', 'speech detection', 'timestamp alignment', 'segment labeling'));

CREATE TEMPORARY TABLE catalog_templates AS
SELECT v.* FROM nx_sim_task_type_version v
JOIN nx_admin_device_task d ON v.task_type_id = d.task_id COLLATE utf8mb4_unicode_ci
JOIN catalog_vocabulary c ON c.task_class = v.task_class
WHERE d.is_deleted = 0 AND d.status = 'active' AND v.enabled = 1
  AND v.task_type_id = CASE v.task_class
    WHEN 'EM' THEN 'TK-1793637201929' WHEN 'SP' THEN 'TK-1793637202097' WHEN 'IG' THEN 'TK-1793637202239'
    WHEN 'LL' THEN 'TK-1793637202389' WHEN 'FT' THEN 'TK-1793637202543' WHEN 'VG' THEN 'TK-1793637202675' END
  AND NOT EXISTS (SELECT 1 FROM nx_sim_task_type_version n WHERE n.task_type_id = v.task_type_id AND n.version > v.version);
SET @expand_catalog = (SELECT COUNT(*) = 6 AND COUNT(DISTINCT task_class) = 6 FROM catalog_templates)
  AND (SELECT COUNT(*) FROM nx_sim_task_type_version v WHERE v.enabled=1
    AND NOT EXISTS (SELECT 1 FROM nx_sim_task_type_version newer WHERE newer.task_type_id=v.task_type_id AND newer.version>v.version))=6
  AND NOT EXISTS (SELECT 1 FROM nx_sim_task_type_version WHERE task_type_id LIKE 'RULE-EN-%');
SET @catalog_at = UTC_TIMESTAMP(6) + INTERVAL 8 HOUR;

CREATE TEMPORARY TABLE catalog_names (task_class VARCHAR(8), ordinal INT, name VARCHAR(128))
DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
INSERT INTO catalog_names SELECT task_class, 1, label FROM catalog_vocabulary;
INSERT INTO catalog_names
SELECT task_class, 1 + ROW_NUMBER() OVER (PARTITION BY task_class ORDER BY SHA2(name,256), name), name
FROM (
  SELECT c.task_class, CONCAT(c.label, ' - ', a.word COLLATE utf8mb4_unicode_ci, ' ', b.word COLLATE utf8mb4_unicode_ci) AS name FROM catalog_vocabulary c
  CROSS JOIN JSON_TABLE(c.qualifiers, '$[*]' COLUMNS(word VARCHAR(48) PATH '$')) a
  CROSS JOIN JSON_TABLE(c.subjects, '$[*]' COLUMNS(word VARCHAR(48) PATH '$')) b
) names;
CREATE TEMPORARY TABLE catalog_tasks AS
SELECT t.task_type_id AS template_id, t.version AS template_version, n.ordinal, n.name,
  IF(n.ordinal=1,t.task_type_id,CONCAT('RULE-EN-',t.task_class,'-',LPAD(n.ordinal,2,'0'))) AS task_id,
  t.task_class, t.duration_seconds, t.base_reward, CASE t.asset WHEN 'NEX' THEN 'UVT' ELSE t.asset END AS asset, t.enabled
FROM catalog_templates t JOIN catalog_names n ON n.task_class=t.task_class WHERE @expand_catalog;

CREATE TEMPORARY TABLE catalog_pools (
  kind CHAR(1), product_key VARCHAR(64), version BIGINT, ids JSON
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
INSERT INTO catalog_pools
SELECT 'P',p.product_key,p.version,p.allowed_task_type_ids FROM nx_sim_product_task_policy_version p
WHERE NOT EXISTS (SELECT 1 FROM nx_sim_product_task_policy_version n WHERE n.product_key=p.product_key AND n.version>p.version);
INSERT INTO catalog_pools
SELECT 'D',sku_id,0,allowed_task_type_ids_json FROM nx_admin_device_sku
WHERE is_deleted=0 AND allowed_task_type_ids_json IS NOT NULL;
CREATE TEMPORARY TABLE catalog_expanded_pools AS
SELECT p.kind,p.product_key,p.version,JSON_ARRAYAGG(COALESCE(v.task_id,m.allowed_id)) AS ids
FROM catalog_pools p
CROSS JOIN JSON_TABLE(p.ids, '$[*]' COLUMNS(allowed_id VARCHAR(64) PATH '$')) m
LEFT JOIN catalog_tasks v ON v.template_id=m.allowed_id COLLATE utf8mb4_unicode_ci
GROUP BY p.kind,p.product_key,p.version HAVING COUNT(v.task_id)>0;

START TRANSACTION;
INSERT INTO nx_admin_device_task
  (task_id,name,price,unit_text,requirement,saturation,status,task_class,model_name,min_reward,max_reward,min_vram,kill_init,created_at,updated_at,is_deleted)
SELECT v.task_id,v.name,t.price,t.unit_text,t.requirement,t.saturation,t.status,t.task_class,t.model_name,
  t.min_reward,t.max_reward,t.min_vram,t.kill_init,@catalog_at,@catalog_at,0
FROM catalog_tasks v JOIN nx_admin_device_task t ON v.template_id=t.task_id COLLATE utf8mb4_unicode_ci
WHERE v.ordinal>1;
UPDATE nx_admin_device_task t JOIN catalog_tasks v ON v.task_id=t.task_id COLLATE utf8mb4_unicode_ci
SET t.name=v.name,t.updated_at=@catalog_at WHERE v.ordinal=1;
INSERT INTO nx_sim_task_type_version
  (task_type_id,version,effective_at,name,task_class,duration_seconds,base_reward,asset,enabled)
SELECT task_id,IF(ordinal=1,template_version+1,1),@catalog_at,name,task_class,duration_seconds,base_reward,asset,enabled
FROM catalog_tasks;
INSERT INTO nx_sim_product_task_policy_version(product_key,version,effective_at,allowed_task_type_ids)
SELECT product_key,version+1,@catalog_at,ids FROM catalog_expanded_pools WHERE kind='P';
UPDATE nx_admin_device_sku s JOIN catalog_expanded_pools p ON p.product_key=s.sku_id COLLATE utf8mb4_unicode_ci AND p.kind='D'
SET s.allowed_task_type_ids_json=p.ids;
UPDATE nx_product product JOIN (SELECT DISTINCT product_key FROM catalog_expanded_pools) changed
  ON changed.product_key=product.product_no COLLATE utf8mb4_unicode_ci
SET product.updated_at=GREATEST(@catalog_at,product.updated_at + INTERVAL 1 SECOND)
WHERE product.is_deleted=0;
COMMIT;
DROP TEMPORARY TABLE catalog_expanded_pools,catalog_pools,catalog_tasks,catalog_names,catalog_templates,catalog_vocabulary;

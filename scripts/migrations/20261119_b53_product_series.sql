-- B53: explicit product series, purchased-device series and onboarding comparison SKU.

SET @sql = IF(EXISTS (SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_product' AND COLUMN_NAME='series_code'),
  'SELECT 1', 'ALTER TABLE nx_product ADD COLUMN series_code VARCHAR(24) NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(EXISTS (SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_device' AND COLUMN_NAME='series_code'),
  'SELECT 1', 'ALTER TABLE nx_user_device ADD COLUMN series_code VARCHAR(24) NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql = IF(EXISTS (SELECT 1 FROM information_schema.COLUMNS
  WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_onboarding_yield_comparison_config' AND COLUMN_NAME='product_no'),
  'SELECT 1', 'ALTER TABLE nx_onboarding_yield_comparison_config ADD COLUMN product_no VARCHAR(64) NULL');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- Materialize legacy E1 rows before backfilling the canonical product series.
INSERT INTO nx_product (
  product_no,name,product_type,tier,status,price_usdt,hashrate,estimated_daily_usdt,daily_nex,stock,
  badge,tagline,store_status,store_visible,sort_order,generation,gpu_model,vram_total_gb,
  share_yield_min,share_yield_max,superseded_by_product_no,unlock_phase,sold_count,rating_value,review_count,
  created_at,updated_at,is_deleted
)
SELECT s.sku_id,s.name,CASE WHEN s.tier='Share' THEN 'SHARE' ELSE 'DEVICE' END,s.tier,
       CASE WHEN LOWER(s.status) IN ('on','active') THEN 'ON_SALE' WHEN LOWER(s.status)='pending' THEN 'PENDING' ELSE 'OFF_SALE' END,
       s.price,
       CASE WHEN TRIM(COALESCE(s.hash_rate,'')) REGEXP '^[0-9]+([.][0-9]+)?$'
                  AND CHAR_LENGTH(SUBSTRING_INDEX(TRIM(s.hash_rate),'.',1)) <= 11
            THEN CAST(s.hash_rate AS DECIMAL(18,6)) ELSE 0 END,
       s.daily_earn,s.daily_earn_nex,
       CASE WHEN TRIM(COALESCE(s.stock_text,'')) REGEXP '^[0-9]+$'
                  AND (CHAR_LENGTH(TRIM(s.stock_text)) < 10
                       OR (CHAR_LENGTH(TRIM(s.stock_text)) = 10 AND TRIM(s.stock_text) <= '2147483647'))
            THEN CAST(s.stock_text AS UNSIGNED) ELSE 0 END,
       s.badge,s.tagline,
       CASE WHEN LOWER(s.status) IN ('on','active') THEN 'on' ELSE LOWER(s.status) END,
       CASE WHEN LOWER(s.status) IN ('on','active') THEN 1 ELSE 0 END,s.id,
       COALESCE(s.generation,1),s.gpu,
       CASE WHEN TRIM(COALESCE(s.vram,'')) REGEXP '^[0-9]+(GB|gb)?$'
                  AND CHAR_LENGTH(REGEXP_SUBSTR(TRIM(s.vram),'^[0-9]+')) <= 3
            THEN CAST(REGEXP_SUBSTR(s.vram,'^[0-9]+') AS UNSIGNED) ELSE NULL END,
       s.share_yield_min,s.share_yield_max,s.superseded_by,
       COALESCE(CAST(s.unlock_phase_id AS CHAR),NULLIF(s.unlock_phase,'')),COALESCE(s.sold,0),COALESCE(s.rating,0),COALESCE(s.reviews,0),
       s.created_at,s.updated_at,0
  FROM nx_admin_device_sku s
 WHERE s.is_deleted=0
   AND NOT EXISTS (SELECT 1 FROM nx_product p WHERE p.product_no=s.sku_id);

-- Preserve the six established SKU associations. For older merchant-assigned
-- SKU numbers, preserve the recognized legacy category only when compatible
-- with the stored product type. An unrecognized SKU requires an explicit value.
UPDATE nx_product p
SET p.series_code = CASE
  WHEN p.product_no IN ('cloud-share') AND UPPER(p.product_type) IN ('SHARE','CLOUD_SHARE') THEN 'CLOUD_SHARE'
  WHEN p.product_no = 'stellarbox-s1' THEN 'S1'
  WHEN p.product_no = 'stellarbox-pro' THEN 'PRO'
  WHEN p.product_no = 'stellarbox-pro-v2' THEN 'PRO_V2'
  WHEN p.product_no = 'stellarrack-p1' THEN 'RACK_P1'
  WHEN p.product_no = 'stellarrack-p2' THEN 'RACK_P2'
  WHEN UPPER(p.product_type) IN ('SHARE','CLOUD_SHARE') THEN 'CLOUD_SHARE'
  WHEN LOWER(p.product_no) REGEXP 'phone|mobile|cloud|share|pc|gpu' THEN NULL
  WHEN LOWER(p.product_no) REGEXP 'rack[-_]?p2' THEN 'RACK_P2'
  WHEN LOWER(p.product_no) REGEXP 'rack' THEN 'RACK_P1'
  WHEN LOWER(p.product_no) REGEXP 'pro[-_]?v2' THEN 'PRO_V2'
  WHEN LOWER(p.product_no) REGEXP 'pro' THEN 'PRO'
  WHEN LOWER(p.product_no) REGEXP 's1|box' THEN 'S1'
END,
p.updated_at = p.updated_at
WHERE p.series_code IS NULL AND UPPER(p.product_type) IN ('DEVICE','SERVER','SHARE','CLOUD_SHARE');

UPDATE nx_user_device d
JOIN nx_product p ON p.id=d.product_id OR (d.product_id IS NULL AND p.product_no=d.product_code)
SET d.series_code=p.series_code,d.updated_at=d.updated_at
WHERE d.series_code IS NULL AND p.series_code IS NOT NULL
  AND UPPER(d.device_type) NOT IN ('MOBILE','PHONE','PC','PC_GPU','PC-GPU','GPU');

-- Some pre-catalog device snapshots have a product code but no longer have a
-- matching catalog row. Preserve their legacy classification in this migration.
UPDATE nx_user_device d
SET d.series_code=CASE
  WHEN UPPER(d.device_type) IN ('SHARE','CLOUD_SHARE','CLOUD-SHARE') THEN 'CLOUD_SHARE'
  WHEN LOWER(d.product_code) REGEXP 'phone|mobile|cloud|share|pc|gpu' THEN NULL
  WHEN LOWER(d.product_code) REGEXP 'rack[-_]?p2' THEN 'RACK_P2'
  WHEN LOWER(d.product_code) REGEXP 'rack' THEN 'RACK_P1'
  WHEN LOWER(d.product_code) REGEXP 'pro[-_]?v2' THEN 'PRO_V2'
  WHEN LOWER(d.product_code) REGEXP 'pro' THEN 'PRO'
  WHEN LOWER(d.product_code) REGEXP 's1|box' THEN 'S1'
END,
d.updated_at=d.updated_at
WHERE d.series_code IS NULL AND d.product_code IS NOT NULL
  AND UPPER(d.device_type) NOT IN ('MOBILE','PHONE','PC','PC_GPU','PC-GPU','GPU')
  AND NOT EXISTS (SELECT 1 FROM nx_product p WHERE p.id=d.product_id
                  OR (d.product_id IS NULL AND p.product_no=d.product_code));

UPDATE nx_onboarding_yield_comparison_config
SET product_no = CASE config_key
  WHEN 's1' THEN 'stellarbox-s1' WHEN 'pro' THEN 'stellarbox-pro' WHEN 'rack' THEN 'stellarrack-p1' END,
  updated_at = updated_at
WHERE product_no IS NULL AND config_key IN ('s1','pro','rack');

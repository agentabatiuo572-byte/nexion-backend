-- Launch catalog: use the E1/E2 write shapes without recording an operator action.
-- Use the migration session's storage clock for catalog and policy timestamps.
CREATE TEMPORARY TABLE lc2_catalog (
  product_no VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  series_code VARCHAR(24) NOT NULL,
  tier VARCHAR(32) NOT NULL,
  pool_code VARCHAR(32) NOT NULL,
  price DECIMAL(18,2) NOT NULL,
  monthly_usdt DECIMAL(12,2) NOT NULL,
  daily_uvt DECIMAL(18,4) NOT NULL,
  stock INT NULL,
  gpu_model VARCHAR(128) NOT NULL,
  gpu_count INT NOT NULL,
  vram_gb INT NOT NULL,
  tflops DECIMAL(6,1) NULL,
  power_text VARCHAR(64) NULL,
  uptime VARCHAR(64) NOT NULL,
  warranty VARCHAR(128) NOT NULL,
  images_per_min INT NULL,
  tokens_per_sec INT NULL,
  video_min_per_hour INT NULL,
  fine_tune_min INT NULL,
  badge VARCHAR(64) NOT NULL,
  tagline VARCHAR(255) NOT NULL,
  features JSON NOT NULL,
  document_path VARCHAR(128) NOT NULL
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;

-- Prices/targets use the approved payback assumptions (days in SKU order):
-- 50, 49, 48, 47, 46, 44, 43, 42, 40. E1 stores the earning targets, not payback.
INSERT INTO lc2_catalog VALUES
  ('cloud-share', 'UVEL Cloud Share', 'CLOUD_SHARE', 'Share', 'L1', 29, 12.18, 1.45, NULL,
   'RTX 4090', 1, 24, NULL, NULL, '99.5%', 'Covered by the hosting pool',
   NULL, NULL, NULL, NULL, 'Starter', 'A share of a hosted GPU compute pool',
   JSON_ARRAY('Shared access to a hosted GPU pool', 'No hardware to set up', 'Daily UVT credits', 'Move up to a full device anytime'),
   '40-series/rtx-4090/'),
  ('stellarbox-s1-mini', 'UVELBox S1 Mini', 'S1', 'Entry', 'L1', 59, 25.58, 3.05, 500,
   'RTX 4060', 1, 8, 15.1, '115 W', '99.5%', '12 months',
   4, 40, 6, 75, 'Entry', 'An entry hosted compute plan for everyday AI tasks',
   JSON_ARRAY('Dedicated entry GPU in our data center', 'Installed, powered and maintained for you', 'Image, speech and embedding tasks', 'Daily credits shown in Earnings'),
   '40-series/rtx-4060-4060ti/'),
  ('stellarbox-s1', 'UVELBox S1', 'S1', 'Entry', 'L1', 119, 52.65, 6.27, 500,
   'RTX 4060 Ti', 1, 16, 22.1, '165 W', '99.5%', '12 months',
   6, 45, 8, 55, 'Popular', 'The balanced entry plan, hosted for you',
   JSON_ARRAY('Dedicated GPU with 16 GB VRAM', 'Free 3-day trial for new users', 'Image, speech and embedding tasks', 'Daily credits shown in Earnings'),
   '40-series/rtx-4060-4060ti/'),
  ('stellarbox-s1-plus', 'UVELBox S1 Plus', 'S1', 'Entry', 'L2', 229, 103.44, 12.31, 300,
   'RTX 4070 Ti SUPER', 1, 16, 44.1, '285 W', '99.5%', '12 months',
   10, 85, 14, 35, 'Upgrade', 'More compute for image and language tasks',
   JSON_ARRAY('Dedicated GPU with 16 GB VRAM', 'Unlocks language model tasks', '12-month free hardware warranty', 'Daily credits shown in Earnings'),
   '40-series/rtx-4070-family/'),
  ('stellarbox-pro', 'UVELBox Pro', 'PRO', 'Pro', 'L2', 449, 207.14, 24.66, 200,
   'RTX 4090', 1, 24, 82.6, '450 W', '99.7%', '18 months',
   17, 130, 24, 20, 'Pro', 'A high-end hosted GPU for heavier AI workloads',
   JSON_ARRAY('Dedicated high-end GPU with 24 GB VRAM', 'Language model and image tasks', '18-month free hardware warranty', 'Daily credits shown in Earnings'),
   '40-series/rtx-4090/'),
  ('stellarbox-pro-v2', 'UVELBox Pro v2', 'PRO_V2', 'Pro', 'L3', 899, 433.20, 51.57, 100,
   'RTX 5090', 1, 32, 104.8, '575 W', '99.7%', '18 months',
   22, 180, 32, 15, 'New', 'A next-generation GPU with 32 GB VRAM',
   JSON_ARRAY('Dedicated next-generation GPU with 32 GB VRAM', 'Unlocks fine-tuning tasks', '18-month free hardware warranty', 'Daily credits shown in Earnings'),
   '50-series/rtx-5090/'),
  ('stellarrack-p1-lite', 'UVELRack P1 Lite', 'RACK_P1', 'Flagship', 'L3', 1799, 886.62, 105.55, 50,
   'RTX 4090', 2, 48, 165.2, '900 W (combined GPU rated)', '99.9%', '24 months',
   34, 260, 48, 11, 'Rack', 'Your first dual-GPU rack node',
   JSON_ARRAY('2 GPUs, 48 GB VRAM in total', 'Fine-tuning and language model tasks', '24-month free hardware warranty', 'Daily credits shown in Earnings'),
   '40-series/rtx-4090/'),
  ('stellarrack-p1', 'UVELRack P1', 'RACK_P1', 'Flagship', 'L3', 3499, 1764.63, 210.07, 30,
   'RTX 4090', 4, 96, 330.4, '1,800 W (combined GPU rated)', '99.9%', '24 months',
   68, 520, 96, 6, 'Flagship', 'A four-GPU rack node in our data center',
   JSON_ARRAY('4 GPUs, 96 GB VRAM in total', 'Fine-tuning and language model tasks', '24-month free hardware warranty', 'Daily credits shown in Earnings'),
   '40-series/rtx-4090/'),
  ('stellarrack-p2', 'UVELRack P2', 'RACK_P2', 'Flagship', 'L4', 6999, 3702.24, 440.74, 20,
   'RTX 5090', 8, 256, 838.4, '4,600 W (combined GPU rated)', '99.9%', '24 months',
   176, 1440, 256, 3, 'Flagship', 'An eight-GPU next-generation rack node for every task type',
   JSON_ARRAY('8 GPUs, 256 GB VRAM in total', 'All six task types unlocked', '24-month free hardware warranty', 'Daily credits shown in Earnings'),
   '50-series/rtx-5090/');

START TRANSACTION;
SET @lc2_catalog_at = NOW(6);
-- A creation proposal can already own the target even when the catalog is empty.
SET @lc2_catalog_create_pending = EXISTS (
  SELECT 1
  FROM nx_audit_object_lock l
  JOIN nx_audit_operation_ticket t ON t.operation_id = l.ticket_id
  WHERE l.target_domain = 'E' AND l.is_deleted = 0
    AND l.target_type IN ('device_sku', 'device_phase')
    AND t.status = 'pending' AND t.is_deleted = 0
    AND CASE WHEN JSON_VALID(t.command_json) THEN JSON_UNQUOTE(JSON_EXTRACT(t.command_json, '$.op')) END
        IN ('e1_sku_create', 'e1_phase_create')
);
INSERT INTO nx_admin_phase_config (scope, label, sort_order, status, created_at, updated_at, is_deleted)
SELECT 'E1', 'P1', 0, 'active', @lc2_catalog_at, @lc2_catalog_at, 0
WHERE NOT @lc2_catalog_create_pending
  AND NOT EXISTS (SELECT 1 FROM nx_admin_phase_config WHERE scope = 'E1');

SET @lc2_p1 = (SELECT id FROM nx_admin_phase_config
               WHERE scope = 'E1' AND label = 'P1' AND status = 'active' AND is_deleted = 0);
SET @lc2_empty_catalog = NOT @lc2_catalog_create_pending AND @lc2_p1 IS NOT NULL
  AND NOT EXISTS (SELECT 1 FROM nx_product)
  AND NOT EXISTS (SELECT 1 FROM nx_admin_device_sku);

-- E2 Random Add publishes directly to simulation history. The six class codes
-- match seed_e2_task_pricing.sql; duration, reward and version match Random Add.
-- Keep any existing publication, including disabled or scheduled versions.
INSERT INTO nx_sim_task_type_version
  (task_type_id, version, effective_at, name, task_class, duration_seconds, base_reward, asset, enabled, pool_code)
WITH RECURSIVE slots(n) AS (
  SELECT 0 UNION ALL SELECT n + 1 FROM slots WHERE n < 19
), task_classes AS (
  SELECT 0 AS class_no, 'EM' AS code, 'embedding' AS label, 30 AS min_minutes, 0.100000 AS base_reward,
         JSON_ARRAY('document indexing', 'product search', 'semantic retrieval', 'article matching', 'topic clustering', 'intent matching', 'catalog indexing') AS topics
  UNION ALL SELECT 1, 'SP', 'speech processing', 45, 0.120000,
         JSON_ARRAY('voice transcription', 'meeting notes', 'speech recognition', 'audio cleanup', 'podcast captions', 'speaker alignment', 'voice segmentation')
  UNION ALL SELECT 2, 'IG', 'image gen', 60, 0.150000,
         JSON_ARRAY('portrait', 'landscape', 'product scene', 'interior concept', 'character sketch', 'botanical study', 'city illustration')
  UNION ALL SELECT 3, 'LL', 'language inference', 60, 0.850000,
         JSON_ARRAY('long context chat', 'document summary', 'answer generation', 'language translation', 'content drafting')
  UNION ALL SELECT 4, 'FT', 'fine tuning', 90, 0.420000,
         JSON_ARRAY('instruction tuning', 'dialogue adaptation', 'domain tuning', 'response alignment')
  UNION ALL SELECT 5, 'VG', 'video gen', 90, 1.050000,
         JSON_ARRAY('city timelapse', 'nature sequence', 'character animation')
)
SELECT CONCAT('DT-LC2-', p.code, '-', LPAD(s.n + 1, 2, '0')), 1, @lc2_catalog_at,
       CONCAT(c.label, ' - ', ELT(p.level, 'focused', 'structured', 'technical', 'scientific'), ' ',
              JSON_UNQUOTE(JSON_EXTRACT(c.topics, CONCAT('$[', s.n DIV (p.level + 2), ']')))),
       c.code, 60 * (c.min_minutes + 5 * (s.n DIV (p.level + 2))), c.base_reward, 'USDT', 1, p.code
FROM nx_sim_task_pool p
CROSS JOIN slots s
JOIN task_classes c ON c.class_no = MOD(s.n, p.level + 2)
WHERE p.kind = 'DEVICE' AND p.code IN ('L1', 'L2', 'L3', 'L4')
  AND NOT EXISTS (SELECT 1 FROM nx_sim_task_type_version published WHERE published.pool_code = p.code)
ORDER BY p.level, s.n;

-- E1 derives the points and estimated yield from price and monthly target.
-- Its Share write path clears the phase; the eight hardware SKUs use the P1 ID.
INSERT INTO nx_product (
  product_no, name, product_type, tier, series_code, status, price_usdt, compute_points, hashrate,
  estimated_daily_usdt, daily_nex, monthly_target_usdt, monthly_target_band_pct, stock, inventory_mode,
  trial_eligible, badge, tagline, store_status, store_visible, gpu_model, vram_total_gb,
  share_yield_min, share_yield_max, unlock_phase, created_at, updated_at
)
SELECT product_no, name, IF(tier = 'Share', 'SHARE', 'DEVICE'), tier, series_code, 'OFF_SALE',
       price, ROUND(price * 10), COALESCE(tflops, 0), ROUND(monthly_usdt / 30, 6), daily_uvt,
       monthly_usdt, 10, COALESCE(stock, 0), IF(stock IS NULL, 'UNLIMITED', 'FINITE'),
       product_no = 'stellarbox-s1', badge, tagline, 'off', 0,
       CONCAT(gpu_count, '× ', gpu_model), vram_gb,
       IF(tier = 'Share', ROUND(monthly_usdt * 0.9 * 36500 / (30 * price), 4), NULL),
       IF(tier = 'Share', ROUND(monthly_usdt * 1.1 * 36500 / (30 * price), 4), NULL),
       IF(tier = 'Share', NULL, CAST(@lc2_p1 AS CHAR)), @lc2_catalog_at, @lc2_catalog_at
FROM lc2_catalog
WHERE @lc2_empty_catalog
ORDER BY price;

INSERT INTO nx_admin_device_sku (
  sku_id, name, tier, tagline, badge, gpu, vram, hash_rate, power_text, uptime, warranty, price,
  daily_earn, daily_earn_nex, share_yield_min, share_yield_max, monthly_target_usdt, monthly_target_band_pct,
  base_rate, sold, stock_text, ai_image_gen_per_min, ai_llm_tokens_per_sec, ai_video_min_per_hour, ai_fine_tune_mins,
  spec_document_title, spec_document_url, features_json, generation, lifecycle, unlock_phase_id,
  allowed_pool_codes_json, status, created_at, updated_at
)
SELECT c.product_no, c.name, c.tier, c.tagline, c.badge, p.gpu_model,
       CONCAT(c.vram_gb, ' GB', IF(c.tier = 'Share', ' (shared pool)', '')),
       CONCAT(c.tflops, ' TFLOPS (FP32)'), c.power_text, c.uptime, c.warranty, c.price,
       p.estimated_daily_usdt, c.daily_uvt, p.share_yield_min, p.share_yield_max, c.monthly_usdt, 10,
       CAST(p.estimated_daily_usdt AS CHAR), 0, CAST(p.stock AS CHAR),
       c.images_per_min, c.tokens_per_sec, c.video_min_per_hour, c.fine_tune_min,
       CONCAT('NVIDIA GeForce ', c.gpu_model, ' specifications'),
       CONCAT('https://www.nvidia.com/en-us/geforce/graphics-cards/', c.document_path),
       c.features, 1, 'active', IF(c.tier = 'Share', NULL, @lc2_p1),
       JSON_ARRAY(c.pool_code), 'off', @lc2_catalog_at, @lc2_catalog_at
FROM lc2_catalog c JOIN nx_product p ON p.product_no = c.product_no
WHERE @lc2_empty_catalog
ORDER BY c.price;

-- Install the approved initial target and scope. An E1 status change still
-- requires A2 and publishes the next version through the normal application path.
INSERT INTO nx_sim_product_task_policy_version
  (product_key, version, effective_at, allowed_task_type_ids, allowed_task_classes,
   allowed_pool_codes, monthly_target_usdt, monthly_target_band_pct)
SELECT product_no, 1, @lc2_catalog_at, JSON_ARRAY(), NULL, JSON_ARRAY(pool_code), monthly_usdt, 10
FROM lc2_catalog
WHERE @lc2_empty_catalog
ORDER BY price;

COMMIT;
DROP TEMPORARY TABLE lc2_catalog;

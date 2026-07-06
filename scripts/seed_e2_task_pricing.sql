-- ======================================================================
-- E2 收益 & 任务引擎 — 初始 seed 数据
-- ----------------------------------------------------------------------
-- 目的：向 E2 模块两张运营表灌入初始数据，使后台 E2 页面有数据可展示。
--
-- 数据口径(单一真源，与代码自洽)：
--   1. nx_admin_device_task 的 task_class / model_name / min_reward / max_reward / min_vram
--      —— 严格采用后端 DeviceCatalogMapper.backfillDefaultTaskExtensions() 写死的
--      TK-1~TK-6 期望值，保证 INSERT 后即便触发 backfill 也不会被覆盖、口径一致。
--      其余展示字段(name/price/unit_text/requirement/saturation)为合理填充，
--      满足 OpsDeviceService.requireTaskCommand 的全部枚举/区间校验。
--      注：后端 TASK_CLASSES 枚举仅 5 类(无 speech)，故按后端 5 类口径，与 PRD
--      §6.3 的 IG/VG/LL/FT/EM/SP 6 类存在已知偏差，以后端实现为准。
--   2. nx_admin_phone_tier_reward 的 daily_usdt / daily_nex —— 采用 PRD E2 ③ 现状值
--      (T1 0.04/6 · T2 0.05/8 · T3 0.06/10 · T4 0.08/13 · T5 0.095/16)，档间单调非降。
--
-- 幂等：两张表均走 INSERT ... ON DUPLICATE KEY UPDATE，可重复执行不报错。
--   执行：mysql -uroot -p nexion < scripts/seed_e2_task_pricing.sql
-- ======================================================================

-- ------------------------------------------------------------------
-- 1) 任务定价表 nx_admin_device_task (6 类，TK-1 ~ TK-6)
-- ------------------------------------------------------------------
INSERT INTO nx_admin_device_task (
  task_id, name, price, unit_text, requirement, saturation, status,
  task_class, model_name, min_reward, max_reward, min_vram, kill_init,
  created_at, updated_at, is_deleted
) VALUES
  ('TK-1', '405B 大模型推理', 2.4000, '/1k',  '需 NexionRack',    0.5500, 'active', 'llm-inference',  'Llama-3.1-405B', 0.8000, 2.4000, '80GB', '派发中', NOW(), NOW(), 0),
  ('TK-2', '70B 模型推理',    0.9000, '/1k',  '需 NexionBox Pro', 0.6500, 'active', 'llm-inference',  'Llama-3.1-70B',  0.3000, 0.9000, '24GB', '派发中', NOW(), NOW(), 0),
  ('TK-3', '图像生成',        0.7000, '/job', 'S1+',              0.7200, 'active', 'image-gen',      'SDXL',          0.2000, 0.7000, '12GB', '派发中', NOW(), NOW(), 0),
  ('TK-4', '视频生成',        4.2000, '/job', '需 NexionBox Pro', 0.3800, 'active', 'video-render',   'HunyuanVideo',  1.6000, 4.2000, '48GB', '派发中', NOW(), NOW(), 0),
  ('TK-5', '模型微调',        7.5000, '/job', '需 NexionBox Pro', 0.2500, 'active', 'fine-tune',      'LoRA',          3.0000, 7.5000, '48GB', '派发中', NOW(), NOW(), 0),
  ('TK-6', '向量嵌入',        0.2200, '/1k',  '手机+',            0.8000, 'active', 'embedding',      'BGE-M3',        0.0600, 0.2200, '8GB',  '派发中', NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
  name        = VALUES(name),
  price       = VALUES(price),
  unit_text   = VALUES(unit_text),
  requirement = VALUES(requirement),
  saturation  = VALUES(saturation),
  status      = VALUES(status),
  task_class  = VALUES(task_class),
  model_name  = VALUES(model_name),
  min_reward  = VALUES(min_reward),
  max_reward  = VALUES(max_reward),
  min_vram    = VALUES(min_vram),
  kill_init   = VALUES(kill_init),
  updated_at  = NOW();

-- ------------------------------------------------------------------
-- 2) 手机算力档位收益表 nx_admin_phone_tier_reward (Tier 1 ~ 5)
--    daily_usdt / daily_nex 取 PRD E2 ③ 现状值(档间单调非降)。
-- ------------------------------------------------------------------
INSERT INTO nx_admin_phone_tier_reward (
  tier, name, note, daily_usdt, daily_nex, status, created_at, updated_at, is_deleted
) VALUES
  (1, 'T1 入门机',   '校准能力最低档，锚定低端机型',     0.0400,  6.0000, 'active', NOW(), NOW(), 0),
  (2, 'T2 普通机',   '入门偏上，覆盖主流入门机型',       0.0500,  8.0000, 'active', NOW(), NOW(), 0),
  (3, 'T3 典型机',   '典型机型，锚定营销基准 $0.06/日',  0.0600, 10.0000, 'active', NOW(), NOW(), 0),
  (4, 'T4 高性能机', '高性能机型，日产放大',             0.0800, 13.0000, 'active', NOW(), NOW(), 0),
  (5, 'T5 旗舰机',   '顶级校准，旗舰机型满档产出',       0.0950, 16.0000, 'active', NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
  name       = VALUES(name),
  note       = VALUES(note),
  daily_usdt = VALUES(daily_usdt),
  daily_nex  = VALUES(daily_nex),
  status     = VALUES(status),
  updated_at = NOW();

-- ------------------------------------------------------------------
-- 校验(执行后人工核对行数)
-- ------------------------------------------------------------------
SELECT 'nx_admin_device_task' AS tbl, COUNT(*) AS rows_cnt FROM nx_admin_device_task WHERE is_deleted = 0
UNION ALL
SELECT 'nx_admin_phone_tier_reward', COUNT(*) FROM nx_admin_phone_tier_reward WHERE is_deleted = 0;

-- BUG-526: install the launch PHONE catalog before starting the task engine.
-- Any PHONE publication, including a disabled or future version, leaves the pool to operations.
CREATE TEMPORARY TABLE lc_phone_tasks (
  task_type_id VARCHAR(64) PRIMARY KEY,
  name VARCHAR(128) NOT NULL,
  task_class VARCHAR(8) NOT NULL
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

INSERT INTO lc_phone_tasks VALUES
  ('PHONE-LAUNCH-01', 'Transcribe voice memo segments', 'SP'),
  ('PHONE-LAUNCH-02', 'Align interview transcript timestamps', 'SP'),
  ('PHONE-LAUNCH-03', 'Detect speech in short recordings', 'SP'),
  ('PHONE-LAUNCH-04', 'Tag speakers in meeting clips', 'SP'),
  ('PHONE-LAUNCH-05', 'Spot keywords in voice commands', 'SP'),
  ('PHONE-LAUNCH-06', 'Classify ambient sound clips', 'SP'),
  ('PHONE-LAUNCH-07', 'Remove noise from voice notes', 'SP'),
  ('PHONE-LAUNCH-08', 'Identify language in audio snippets', 'SP'),
  ('PHONE-LAUNCH-09', 'Segment short podcast excerpts', 'SP'),
  ('PHONE-LAUNCH-10', 'Tag intents in support messages', 'LL'),
  ('PHONE-LAUNCH-11', 'Extract dates from appointment notes', 'LL'),
  ('PHONE-LAUNCH-12', 'Summarize notification batches', 'LL'),
  ('PHONE-LAUNCH-13', 'Classify product review sentiment', 'LL'),
  ('PHONE-LAUNCH-14', 'Normalize short address strings', 'LL'),
  ('PHONE-LAUNCH-15', 'Extract entities from news snippets', 'LL'),
  ('PHONE-LAUNCH-16', 'Score topic matches for chat messages', 'LL'),
  ('PHONE-LAUNCH-17', 'Translate short menu descriptions', 'LL'),
  ('PHONE-LAUNCH-18', 'Categorize feedback form responses', 'LL'),
  ('PHONE-LAUNCH-19', 'Generate receipt thumbnail labels', 'IG'),
  ('PHONE-LAUNCH-20', 'Segment objects in product thumbnails', 'IG'),
  ('PHONE-LAUNCH-21', 'Denoise low-light photo crops', 'IG'),
  ('PHONE-LAUNCH-22', 'Upscale icon previews', 'IG'),
  ('PHONE-LAUNCH-23', 'Extract masks from street sign crops', 'IG'),
  ('PHONE-LAUNCH-24', 'Restore small document images', 'IG'),
  ('PHONE-LAUNCH-25', 'Generate low-resolution material previews', 'IG'),
  ('PHONE-LAUNCH-26', 'Remove backgrounds from small item photos', 'IG'),
  ('PHONE-LAUNCH-27', 'Generate preview frames for short clips', 'VG'),
  ('PHONE-LAUNCH-28', 'Stabilize low-resolution clip segments', 'VG'),
  ('PHONE-LAUNCH-29', 'Interpolate frames in short motion loops', 'VG'),
  ('PHONE-LAUNCH-30', 'Render caption overlays for short clips', 'VG'),
  ('PHONE-LAUNCH-31', 'Render thumbnail strips from sample videos', 'VG'),
  ('PHONE-LAUNCH-32', 'Denoise video preview frames', 'VG'),
  ('PHONE-LAUNCH-33', 'Tune a compact intent classifier', 'FT'),
  ('PHONE-LAUNCH-34', 'Calibrate a motion sensor denoiser', 'FT'),
  ('PHONE-LAUNCH-35', 'Tune a gesture classifier on sample windows', 'FT'),
  ('PHONE-LAUNCH-36', 'Adapt a small keyword spotting model', 'FT'),
  ('PHONE-LAUNCH-37', 'Calibrate a thumbnail quality model', 'FT'),
  ('PHONE-LAUNCH-38', 'Tune a compact language detector', 'FT'),
  ('PHONE-LAUNCH-39', 'Adapt a small speech activity classifier', 'FT'),
  ('PHONE-LAUNCH-40', 'Embed short product descriptions', 'EM'),
  ('PHONE-LAUNCH-41', 'Index search query vectors', 'EM'),
  ('PHONE-LAUNCH-42', 'Embed help-center article excerpts', 'EM'),
  ('PHONE-LAUNCH-43', 'Cluster duplicate feedback snippets', 'EM'),
  ('PHONE-LAUNCH-44', 'Score similarity between image captions', 'EM'),
  ('PHONE-LAUNCH-45', 'Embed motion sensor windows', 'EM'),
  ('PHONE-LAUNCH-46', 'Rank related support questions', 'EM'),
  ('PHONE-LAUNCH-47', 'Generate vectors for short listing titles', 'EM'),
  ('PHONE-LAUNCH-48', 'Match near-duplicate document crops', 'EM'),
  ('PHONE-LAUNCH-49', 'Embed short music preview fingerprints', 'EM'),
  ('PHONE-LAUNCH-50', 'Index caption fragments for semantic search', 'EM');

START TRANSACTION;
SET @lc_phone_seed = NOT EXISTS (SELECT 1 FROM nx_sim_task_type_version WHERE pool_code = 'PHONE');
SET @lc_phone_at = UTC_TIMESTAMP(6) + INTERVAL 8 HOUR;
-- Launch E6 uses a 60-minute slot and 0.0025 USDT per completion, or 0.06 USDT
-- in 24 continuous slots (Tier 3). E2 PHONE has no device VRAM or local runtime gate.
SET @lc_phone_seconds = 3600;
SET @lc_phone_reward = 0.002500;

INSERT INTO nx_admin_device_task (
  task_id, name, price, unit_text, requirement, saturation, status, task_class,
  model_name, min_reward, max_reward, min_vram, kill_init, created_at, updated_at, is_deleted
)
SELECT task_type_id, name, @lc_phone_reward, '/job', '手机+', 0, 'active', task_class,
       '', @lc_phone_reward, @lc_phone_reward, '0GB', '派发中', @lc_phone_at, @lc_phone_at, 0
FROM lc_phone_tasks
WHERE @lc_phone_seed
ORDER BY task_type_id;

INSERT INTO nx_compute_task_policy_v2 (
  task_id, selection_weight, expected_duration_min_sec, expected_duration_max_sec,
  execution_timeout_sec, runtime_key, validator_key, workload_config_json, revision
)
SELECT task_type_id, 1, @lc_phone_seconds, @lc_phone_seconds,
       @lc_phone_seconds, '', '', JSON_OBJECT(), 1
FROM lc_phone_tasks
WHERE @lc_phone_seed;

-- Match the effective E3 map read by MybatisDeviceOpsRepository.e3Config():
-- stored settings override defaults, with both represented as JSON strings.
-- The task-pools migration already publishes the phone product's PHONE grant.
SET @lc_phone_e3_defaults = JSON_OBJECT(
  'capacityBand1DeltaPct', '-3', 'capacityBand2DeltaPct', '-6', 'capacityBand3DeltaPct', '-23.7',
  'stageEarlyEnd', '3', 'stageMidEnd', '8', 'cycleMonths', '12',
  'capacityFloorPct', '22', 'capacitySubsidyDays', '30',
  'capacityApplyToPhone', 'false', 'capacityApplyToCloudShare', 'false', 'capacityApplyToPcGpu', 'false',
  'capacityApplyToS1', 'true', 'capacityApplyToPro', 'true', 'capacityApplyToProV2', 'true',
  'capacityApplyToRackP1', 'true', 'capacityApplyToRackP2', 'true',
  'tradeinEnabled', 'true', 'tradeinLadderCut1', '25', 'tradeinLadderCut2', '50',
  'tradeinLadderCut3', '75', 'tradeinLadderCut4', '100',
  'tradeinLadderCredit1', '75', 'tradeinLadderCredit2', '60', 'tradeinLadderCredit3', '45',
  'tradeinLadderCredit4', '30', 'tradeinLadderCredit5', '15',
  'tradeinRequireHigherPrice', 'true', 'tradeinMaxDevicesPerOrder', '1',
  'eligibility', 'L4_PLUS', 'promoMult', '1.5', 'promoCooldownDays', '14',
  'promoMaxPerSession', '3', 'promoDelaySeconds', '6', 'promoMinAgeDays', '30',
  'promoRoutes', '/me/devices', 'inventorySoftMax', '0',
  'taskLockS1', '30', 'taskLockPro', '150', 'taskLockRack', '480',
  'earlyAccessEnabled', 'false', 'earlyAccessLeadDays', '30'
);

INSERT INTO nx_sim_e3_version (version, effective_at, config_json)
SELECT 1, @lc_phone_at, JSON_MERGE_PATCH(@lc_phone_e3_defaults, COALESCE((
  SELECT JSON_OBJECTAGG(config_key, config_value)
  FROM nx_compute_e3_config
  WHERE is_deleted = 0
    AND JSON_CONTAINS_PATH(@lc_phone_e3_defaults, 'one', CONCAT('$.', JSON_QUOTE(config_key)))
), JSON_OBJECT()))
WHERE @lc_phone_seed AND NOT EXISTS (SELECT 1 FROM nx_sim_e3_version);

INSERT INTO nx_sim_task_type_version (
  task_type_id, version, effective_at, name, task_class, duration_seconds, base_reward, asset, enabled, pool_code
)
SELECT task_type_id, 1, @lc_phone_at, name, task_class, @lc_phone_seconds, @lc_phone_reward, 'USDT', 1, 'PHONE'
FROM lc_phone_tasks
WHERE @lc_phone_seed
ORDER BY task_type_id;

COMMIT;
DROP TEMPORARY TABLE lc_phone_tasks;

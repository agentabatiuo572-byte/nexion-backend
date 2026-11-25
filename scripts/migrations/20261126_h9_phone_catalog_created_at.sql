-- PT-03: the launch seed rounded fractional seconds when writing the old catalog.
-- Require the complete 50-row launch policy batch and a still untouched catalog row.
WITH launch_policy AS (
  SELECT task_type_id, effective_at, name, task_class, base_reward
  FROM nx_sim_task_type_version
  WHERE version = 1 AND pool_code = 'PHONE' AND enabled = 1
    AND duration_seconds = 3600 AND base_reward = 0.002500 AND asset = 'USDT'
    AND REGEXP_LIKE(task_type_id, '^PHONE-LAUNCH-(0[1-9]|[1-4][0-9]|50)$', 'c')
    AND task_class = CASE
      WHEN task_type_id <= 'PHONE-LAUNCH-09' THEN 'SP'
      WHEN task_type_id <= 'PHONE-LAUNCH-18' THEN 'LL'
      WHEN task_type_id <= 'PHONE-LAUNCH-26' THEN 'IG'
      WHEN task_type_id <= 'PHONE-LAUNCH-32' THEN 'VG'
      WHEN task_type_id <= 'PHONE-LAUNCH-39' THEN 'FT'
      ELSE 'EM'
    END
), launch_seed AS (
  SELECT MIN(effective_at) AS effective_at,
         TIMESTAMPADD(MICROSECOND, -MICROSECOND(MIN(effective_at)), MIN(effective_at)) AS whole_second
  FROM launch_policy
  HAVING COUNT(*) = 50 AND MIN(effective_at) = MAX(effective_at)
     AND MICROSECOND(MIN(effective_at)) >= 500000
)
UPDATE nx_admin_device_task task
JOIN launch_policy policy ON CAST(task.task_id AS BINARY) = CAST(policy.task_type_id AS BINARY)
JOIN launch_seed seed ON seed.effective_at = policy.effective_at
SET task.created_at = seed.whole_second, task.updated_at = task.updated_at
WHERE task.created_at = seed.whole_second + INTERVAL 1 SECOND
  AND task.updated_at = task.created_at
  AND CAST(task.name AS BINARY) = CAST(policy.name AS BINARY)
  AND CAST(task.task_class AS BINARY) = CAST(policy.task_class AS BINARY)
  AND task.price = policy.base_reward AND task.min_reward = policy.base_reward
  AND task.max_reward = policy.base_reward AND task.saturation = 0
  AND CAST(task.status AS BINARY) = 'active' AND task.is_deleted = 0
  AND CAST(task.unit_text AS BINARY) = '/job' AND CAST(task.requirement AS BINARY) = '手机+'
  AND task.model_name = '' AND CAST(task.min_vram AS BINARY) = '0GB'
  AND CAST(task.kill_init AS BINARY) = '派发中';

-- B86-BE-01: extend the untouched B85 PHONE catalog with 11–20 minute tasks.
-- Use the task engine's storage time zone for the common effective time.
START TRANSACTION;

-- Serialize the default-pool check with E6 task publication.
SELECT COUNT(*) INTO @b86_config_rows FROM nx_compute_e3_config WHERE is_deleted = 0 FOR UPDATE;
SET @b86_phone_at = NOW(6);
SET @b86_phone_unchanged = (
  SELECT COUNT(*) = 100
  FROM nx_sim_task_type_version
  WHERE pool_code = 'PHONE' OR task_type_id LIKE 'PHONE-LAUNCH-%'
) AND (
  SELECT COUNT(*) = 50
    AND COUNT(DISTINCT launch.effective_at) = 1
    AND COUNT(DISTINCT b85.effective_at) = 1
  FROM nx_sim_task_type_version b85
  JOIN nx_sim_task_type_version launch ON launch.task_type_id = b85.task_type_id AND launch.version = 1
  WHERE b85.version = 2 AND b85.pool_code = 'PHONE' AND launch.pool_code = 'PHONE'
    AND REGEXP_LIKE(b85.task_type_id, '^PHONE-LAUNCH-(0[1-9]|[1-4][0-9]|50)$', 'c')
    AND launch.effective_at <= b85.effective_at AND b85.effective_at <= @b86_phone_at
    AND launch.duration_seconds = 3600 AND launch.base_reward = 0.002500
    AND launch.asset = 'USDT' AND launch.enabled = 1
    AND launch.task_class = CASE
      WHEN b85.task_type_id <= 'PHONE-LAUNCH-09' THEN 'SP'
      WHEN b85.task_type_id <= 'PHONE-LAUNCH-18' THEN 'LL'
      WHEN b85.task_type_id <= 'PHONE-LAUNCH-26' THEN 'IG'
      WHEN b85.task_type_id <= 'PHONE-LAUNCH-32' THEN 'VG'
      WHEN b85.task_type_id <= 'PHONE-LAUNCH-39' THEN 'FT'
      ELSE 'EM'
    END
    AND CAST(b85.name AS BINARY) = CAST(launch.name AS BINARY)
    AND b85.task_class = launch.task_class AND b85.asset = launch.asset
    AND b85.duration_seconds = IF(launch.task_class = 'EM', 600, 3600)
    AND b85.base_reward = IF(launch.task_class = 'EM', 0.000417, 0.002500)
    AND b85.enabled = (launch.task_class = 'EM')
);

INSERT INTO nx_sim_task_type_version (
  task_type_id, version, effective_at, name, task_class, duration_seconds, base_reward, asset, enabled, pool_code
)
SELECT task_type_id, 3, @b86_phone_at, name, task_class, duration_minutes * 60,
       ROUND(duration_minutes * 0.002500 / 60, 6), asset, enabled, pool_code
FROM (
  SELECT task_type_id, name, task_class, CAST(RIGHT(task_type_id, 2) AS UNSIGNED) - 30 AS duration_minutes,
         asset, enabled, pool_code
  FROM nx_sim_task_type_version
  WHERE @b86_phone_unchanged AND pool_code = 'PHONE' AND version = 2
    AND task_type_id BETWEEN 'PHONE-LAUNCH-41' AND 'PHONE-LAUNCH-50'
) AS tiers
ORDER BY task_type_id;

COMMIT;

SELECT IF(@b86_phone_unchanged, 'B86_PHONE_POOL_APPLIED',
          'B86_PHONE_POOL_SKIPPED_NON_DEFAULT_OR_ALREADY_APPLIED') AS migration_result;

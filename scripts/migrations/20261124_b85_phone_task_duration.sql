-- B85-BE-04 / BUG-688: only convert the untouched launch PHONE pool.
-- The migration session must use the same storage time zone as the task engine.
START TRANSACTION;

SET @b85_phone_at = NOW(6);
SET @b85_phone_original = (
  SELECT COUNT(*) = 50 AND COUNT(DISTINCT effective_at) = 1 AND COUNT(CASE
    WHEN pool_code = 'PHONE'
      AND REGEXP_LIKE(task_type_id, '^PHONE-LAUNCH-(0[1-9]|[1-4][0-9]|50)$', 'c')
      AND version = 1 AND effective_at <= @b85_phone_at
      AND duration_seconds = 3600 AND base_reward = 0.002500 AND asset = 'USDT' AND enabled = 1
      AND task_class = CASE
        WHEN task_type_id <= 'PHONE-LAUNCH-09' THEN 'SP'
        WHEN task_type_id <= 'PHONE-LAUNCH-18' THEN 'LL'
        WHEN task_type_id <= 'PHONE-LAUNCH-26' THEN 'IG'
        WHEN task_type_id <= 'PHONE-LAUNCH-32' THEN 'VG'
        WHEN task_type_id <= 'PHONE-LAUNCH-39' THEN 'FT'
        ELSE 'EM'
      END
    THEN 1 END) = 50
  FROM nx_sim_task_type_version
  WHERE pool_code = 'PHONE' OR task_type_id LIKE 'PHONE-LAUNCH-%'
);

INSERT INTO nx_sim_task_type_version (
  task_type_id, version, effective_at, name, task_class, duration_seconds, base_reward, asset, enabled, pool_code
)
SELECT task_type_id, 2, @b85_phone_at, name, task_class,
       IF(task_class = 'EM', 600, duration_seconds),
       IF(task_class = 'EM', 0.000417, base_reward), asset, task_class = 'EM', pool_code
FROM nx_sim_task_type_version
WHERE pool_code = 'PHONE' AND @b85_phone_original
ORDER BY task_type_id;

COMMIT;

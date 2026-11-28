-- B85-BE-01: seed empty DEVICE pools or supersede the untouched LC2 / TEST samples.
-- The migration session uses the task engine's storage time zone.
CREATE TEMPORARY TABLE b85_test_samples (
  pool_code VARCHAR(32) NOT NULL,
  task_type_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (pool_code, task_type_id)
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

-- The 24 original IDs from the 2026-11-14 E2 TEST execution ledger.
INSERT INTO b85_test_samples VALUES
  ('L1', 'DT-bd2e66ed90ba4e3eb798afb6140a4a92'),
  ('L1', 'DT-d3ea211855284e528a3ad117759ec13a'),
  ('L1', 'DT-ab3d2b3dbb724c3b97a630d86b4eaffe'),
  ('L1', 'DT-94e9a4a6ea59432db2bde3bca42067ba'),
  ('L1', 'DT-b7d73e041dd44735a9b1046a699b4964'),
  ('L1', 'DT-aece4ecc9b1a4f9b92df7c07d5d713f0'),
  ('L2', 'DT-b41c432113e14abeb15ade368a20e7c1'),
  ('L2', 'DT-134a368403164a19814953b14e069d7b'),
  ('L2', 'DT-fbe8baaffd734d5bb4f0218cd4c4ba7a'),
  ('L2', 'DT-880ae3263584462fb3cfaef9cae80306'),
  ('L2', 'DT-4cc6bcc88fee4b9da708b813044e5c28'),
  ('L2', 'DT-3df5c04389c74cb09c5c20852efb37cd'),
  ('L3', 'DT-4f91338f991e46599fd592eed60ed161'),
  ('L3', 'DT-5f3f7c17d5154bfeb0c6da97c4c5f2f8'),
  ('L3', 'DT-fcf9aca02ad447b7a0549fafe62439d6'),
  ('L3', 'DT-6c879062da4949a1855201c7883f1d7c'),
  ('L3', 'DT-8b5e95a49e274043a53e8dd1d6e5d8ed'),
  ('L3', 'DT-6dec836a8994454f8d0bd0f9ee302c57'),
  ('L4', 'DT-e333547e8ea84aa9b0f0ad039d2babd3'),
  ('L4', 'DT-b38e1278964c41f48c374ad017733bfe'),
  ('L4', 'DT-a49b15cbaa5a4dbfa394b2216224ce28'),
  ('L4', 'DT-dffea1be26f644708748d0f4de966003'),
  ('L4', 'DT-0da6a388bc4e4da5b8d7b5b2d15e0305'),
  ('L4', 'DT-e139e79548dc4895b7e3fcc7c1c04867');

CREATE TEMPORARY TABLE b85_pool_plan (
  pool_code VARCHAR(32) PRIMARY KEY,
  pool_level INT NOT NULL,
  source_history VARCHAR(24) NOT NULL
) DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

START TRANSACTION;
SET @b85_device_at = NOW(6);

INSERT INTO b85_pool_plan (pool_code, pool_level, source_history)
SELECT p.code, p.level, CASE
  WHEN COUNT(v.task_type_id) = 0 THEN 'EMPTY'
  WHEN COUNT(v.task_type_id) = 20 AND COUNT(DISTINCT v.effective_at) = 1
    AND SUM(v.version = 1 AND v.enabled = 1 AND v.asset = 'USDT' AND v.effective_at <= @b85_device_at
        AND REGEXP_LIKE(v.task_type_id, CONCAT('^DT-LC2-', p.code, '-(0[1-9]|1[0-9]|20)$'), 'c')) = 20
    THEN 'LC2'
  WHEN COUNT(v.task_type_id) = 6
    AND SUM(v.version = 1 AND v.enabled = 1 AND v.asset = 'USDT' AND v.effective_at <= @b85_device_at
        AND s.task_type_id IS NOT NULL) = 6
    THEN 'TEST_SAMPLE'
  ELSE 'SKIPPED_EXISTING_HISTORY'
END
FROM nx_sim_task_pool p
LEFT JOIN nx_sim_task_type_version v ON v.pool_code = p.code
LEFT JOIN b85_test_samples s ON s.pool_code = p.code AND BINARY s.task_type_id = BINARY v.task_type_id
WHERE p.kind = 'DEVICE' AND p.code IN ('L1', 'L2', 'L3', 'L4')
GROUP BY p.code, p.level;

INSERT INTO nx_sim_task_type_version (
  task_type_id, version, effective_at, name, task_class, duration_seconds, base_reward, asset, enabled, pool_code
)
SELECT v.task_type_id, 2, @b85_device_at, v.name, v.task_class, v.duration_seconds, v.base_reward, v.asset, 0, v.pool_code
FROM nx_sim_task_type_version v
JOIN b85_pool_plan p ON p.pool_code = v.pool_code AND p.source_history IN ('LC2', 'TEST_SAMPLE')
ORDER BY v.pool_code, v.task_type_id;

INSERT INTO nx_sim_task_type_version (
  task_type_id, version, effective_at, name, task_class, duration_seconds, base_reward, asset, enabled, pool_code
)
WITH RECURSIVE slots(n) AS (
  SELECT 0 UNION ALL SELECT n + 1 FROM slots WHERE n < 9
), task_classes AS (
  SELECT 1 AS min_level, 'EM' AS code, 'embedding' AS label, 0.0010 AS weight_per_minute,
         JSON_ARRAY(30,35,35,40,45,45,50,55,55,60) AS minutes,
         JSON_ARRAY('product search passages', 'support topic clusters', 'article title matching', 'inventory descriptions',
                    'documentation index', 'review similarity', 'training article retrieval', 'query suggestions',
                    'catalog identifiers', 'knowledge base passages') AS topics
  UNION ALL SELECT 1, 'SP', 'speech processing', 0.0011,
         JSON_ARRAY(45,50,55,60,65,70,75,80,85,90),
         JSON_ARRAY('meeting transcript', 'interview cleanup', 'lecture captions', 'podcast transcription',
                    'voice segment alignment', 'narration timing', 'call recording captions', 'speaker turn detection',
                    'subtitle timing', 'audio classification')
  UNION ALL SELECT 1, 'IG', 'image gen', 0.0012,
         JSON_ARRAY(60,65,75,80,85,95,100,105,115,120),
         JSON_ARRAY('studio portrait', 'coastal landscape', 'travel postcard', 'interior concept',
                    'storybook character', 'botanical illustration', 'city skyline', 'product scene',
                    'garden layout', 'wildlife cover')
  UNION ALL SELECT 2, 'LL', 'language inference', 0.0013,
         JSON_ARRAY(60,65,75,80,85,95,100,105,115,120),
         JSON_ARRAY('document summary', 'product question answering', 'article translation', 'report analysis',
                    'technical explanation', 'topic classification', 'research outline', 'review synthesis',
                    'instruction response', 'catalog copy')
  UNION ALL SELECT 3, 'FT', 'fine tuning', 0.0014,
         JSON_ARRAY(90,95,95,100,105,105,110,115,115,120),
         JSON_ARRAY('instruction following', 'domain classification', 'retrieval ranking', 'conversation adaptation',
                    'translation alignment', 'summary style', 'intent detection', 'caption matching',
                    'answer evaluation', 'document reasoning')
  UNION ALL SELECT 4, 'VG', 'video gen', 0.0015,
         JSON_ARRAY(90,95,95,100,105,105,110,115,115,120),
         JSON_ARRAY('city timelapse', 'waterfront sequence', 'character animation', 'product showcase',
                    'landscape flyover', 'garden walkthrough', 'camera transition', 'wildlife scene',
                    'interior tour', 'studio motion study')
), catalog AS (
  SELECT p.pool_code, c.code, c.label, c.weight_per_minute, s.n,
         CAST(JSON_UNQUOTE(JSON_EXTRACT(c.minutes, CONCAT('$[', s.n, ']'))) AS UNSIGNED) AS duration_minutes,
         JSON_UNQUOTE(JSON_EXTRACT(c.topics, CONCAT('$[', s.n, ']'))) AS topic
  FROM b85_pool_plan p
  JOIN task_classes c ON c.min_level <= p.pool_level
  CROSS JOIN slots s
  WHERE p.source_history <> 'SKIPPED_EXISTING_HISTORY'
)
SELECT CONCAT('DT-B85-', pool_code, '-', code, '-', LPAD(n + 1, 2, '0')), 1, @b85_device_at,
       CONCAT(label, ' - ', topic), code, 60 * duration_minutes, duration_minutes * weight_per_minute,
       'USDT', 1, pool_code
FROM catalog
ORDER BY pool_code, code, n;

COMMIT;

SELECT CONCAT('B85_DEVICE_POOL ', pool_code, ' ', source_history) AS migration_result
FROM b85_pool_plan
ORDER BY pool_code;

DROP TEMPORARY TABLE b85_pool_plan;
DROP TEMPORARY TABLE b85_test_samples;

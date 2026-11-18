-- BUG-496/497: translate only the original labels of known mutable seed rows.
SET NAMES utf8mb4;
START TRANSACTION;

UPDATE nx_disclosure_jurisdiction_catalog AS catalog
JOIN (
    SELECT 'CN' AS code, '中国大陆' AS old_name, 'Mainland China' AS new_name
    UNION ALL SELECT 'US', '美国', 'United States'
    UNION ALL SELECT 'EU', '欧盟', 'European Union'
    UNION ALL SELECT 'SG', '新加坡', 'Singapore'
    UNION ALL SELECT 'SBV', '越南国家银行', 'State Bank of Vietnam'
) AS seed ON catalog.jurisdiction_code = seed.code
         AND BINARY catalog.jurisdiction_name = BINARY seed.old_name
SET catalog.jurisdiction_name = seed.new_name,
    catalog.revision = catalog.revision + 1,
    catalog.updated_at = CURRENT_TIMESTAMP
WHERE catalog.is_deleted = 0;

UPDATE nx_disclosure_jurisdiction AS mapping
JOIN (
    SELECT 'CN' AS code, '中国大陆' AS old_name, 'Mainland China' AS new_name
    UNION ALL SELECT 'US', '美国', 'United States'
    UNION ALL SELECT 'EU', '欧盟', 'European Union'
    UNION ALL SELECT 'SG', '新加坡', 'Singapore'
    UNION ALL SELECT 'SBV', '越南国家银行', 'State Bank of Vietnam'
) AS seed ON mapping.jurisdiction_code = seed.code
         AND BINARY mapping.jurisdiction_name = BINARY seed.old_name
SET mapping.jurisdiction_name = seed.new_name
WHERE mapping.is_deleted = 0;

-- Both historical seed families. Published policy versions and task evidence retain their labels.
UPDATE nx_admin_device_task AS task
JOIN (
    SELECT 'TK-1' AS task_id, 'LLM 推理' AS old_name, 'LLM inference' AS new_name
    UNION ALL SELECT 'TK-2', '语音识别', 'Speech recognition'
    UNION ALL SELECT 'TK-3', '图像生成', 'Image generation'
    UNION ALL SELECT 'TK-4', '视频生成', 'Video generation'
    UNION ALL SELECT 'TK-5', '模型微调', 'Model fine-tuning'
    UNION ALL SELECT 'TK-6', '向量嵌入', 'Vector embedding'
    UNION ALL SELECT 'E2-20260924-LL', 'LLM 推理', 'LLM inference'
    UNION ALL SELECT 'E2-20260924-SP', '语音识别', 'Speech recognition'
    UNION ALL SELECT 'E2-20260924-IG', '图像生成', 'Image generation'
    UNION ALL SELECT 'E2-20260924-VG', '视频生成', 'Video generation'
    UNION ALL SELECT 'E2-20260924-FT', '模型微调', 'Model fine-tuning'
    UNION ALL SELECT 'E2-20260924-EM', '向量嵌入', 'Vector embedding'
) AS seed ON task.task_id = seed.task_id AND BINARY task.name = BINARY seed.old_name
SET task.name = seed.new_name,
    task.updated_at = task.updated_at
WHERE task.is_deleted = 0;

COMMIT;

-- TEST can have the phone tier ladder while the six authoritative E2 task rows
-- are absent. Seed only missing classes; preserve every existing operator value,
-- status, and task ID. The new IDs do not reuse soft-deleted TK-1..TK-6 IDs.
INSERT INTO nx_admin_device_task (
  task_id, name, price, unit_text, requirement, saturation, status,
  task_class, model_name, min_reward, max_reward, min_vram, kill_init,
  created_at, updated_at, is_deleted
)
SELECT CONCAT('E2-20260924-', seed.task_class), seed.name, seed.price,
       seed.unit_text, seed.requirement, 0.35, 'active', seed.task_class,
       seed.model_name, seed.min_reward, seed.max_reward, seed.min_vram,
       '派发中', NOW(), NOW(), 0
  FROM (
    SELECT 'IG' AS task_class, '图像生成' AS name, 0.0450 AS price, '/job' AS unit_text,
           'S1+' AS requirement, 'SDXL Turbo,Flux Schnell' AS model_name,
           0.00010 AS min_reward, 0.0450 AS max_reward, '12GB' AS min_vram
    UNION ALL SELECT 'VG', '视频生成', 1.8000, '/job', '需 NexGridBox Pro', 'Sora-class', 0.45000, 1.8000, '48GB'
    UNION ALL SELECT 'LL', 'LLM 推理', 0.8500, '/job', '需 NexGridRack', 'Llama 70B,Phi-3-mini', 0.00005, 0.8500, '80GB'
    UNION ALL SELECT 'FT', '模型微调', 0.4200, '/job', '需 NexGridBox Pro', 'LoRA', 0.06000, 0.4200, '48GB'
    UNION ALL SELECT 'EM', '向量嵌入', 0.0900, '/1k', '手机+', 'BGE-M3', 0.00001, 0.0900, '8GB'
    UNION ALL SELECT 'SP', '语音识别', 0.0720, '/job', '手机+', 'Whisper', 0.00005, 0.0720, '8GB'
  ) AS seed
 WHERE NOT EXISTS (
   SELECT 1 FROM nx_admin_device_task AS current_task
    WHERE BINARY current_task.task_class = BINARY seed.task_class
      AND current_task.is_deleted = 0
 );

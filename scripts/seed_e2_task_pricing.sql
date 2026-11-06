-- Bootstrap the six E2 task classes after the task and simulation tables exist.
-- Phone-tier configuration is provisioned by the onboarding migrations.

UPDATE nx_admin_device_task
   SET task_class = CASE LOWER(task_class)
         WHEN 'llm-inference' THEN 'LL'
         WHEN 'image-gen' THEN 'IG'
         WHEN 'video-render' THEN 'VG'
         WHEN 'fine-tune' THEN 'FT'
         WHEN 'embedding' THEN 'EM'
         WHEN 'speech' THEN 'SP'
         ELSE task_class END
 WHERE is_deleted = 0
   AND LOWER(task_class) IN ('llm-inference','image-gen','video-render','fine-tune','embedding','speech');

INSERT INTO nx_admin_device_task (
  task_id, name, price, unit_text, requirement, saturation, status,
  task_class, model_name, min_reward, max_reward, min_vram, kill_init,
  created_at, updated_at, is_deleted
)
SELECT seed.task_id, seed.name, seed.max_reward, seed.unit_text, seed.requirement, 0.35, 'active',
       seed.task_class, seed.model_name, seed.min_reward, seed.max_reward, seed.min_vram, '派发中',
       NOW(), NOW(), 0
  FROM (
    SELECT 'TK-1' AS task_id, 'LLM 推理' AS name, '/job' AS unit_text, '需 UVELRack' AS requirement,
           'LL' AS task_class, 'Llama 70B,Phi-3-mini' AS model_name,
           0.00005 AS min_reward, 0.8500 AS max_reward, '80GB' AS min_vram
    UNION ALL SELECT 'TK-2','语音识别','/job','手机+','SP','Whisper',0.00005,0.0720,'8GB'
    UNION ALL SELECT 'TK-3','图像生成','/job','S1+','IG','SDXL Turbo,Flux Schnell',0.00010,0.0450,'12GB'
    UNION ALL SELECT 'TK-4','视频生成','/job','需 UVELBox Pro','VG','Sora-class',0.45000,1.8000,'48GB'
    UNION ALL SELECT 'TK-5','模型微调','/job','需 UVELBox Pro','FT','LoRA',0.06000,0.4200,'48GB'
    UNION ALL SELECT 'TK-6','向量嵌入','/1k','手机+','EM','BGE-M3',0.00001,0.0900,'8GB'
  ) AS seed
 -- A new canonical ID would take precedence over a configured fallback of the same class.
 WHERE NOT EXISTS (
   SELECT 1 FROM nx_admin_device_task existing
    WHERE BINARY existing.task_class = BINARY seed.task_class
      AND NOT EXISTS (SELECT 1 FROM nx_sim_task_type_version simulated
                       WHERE simulated.task_type_id = existing.task_id COLLATE utf8mb4_unicode_ci)
 )
ON DUPLICATE KEY UPDATE task_id = nx_admin_device_task.task_id;

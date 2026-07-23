-- E2 closure: canonical six task classes, global saturation and governed A4 event.
SET NAMES utf8mb4;

ALTER TABLE nx_admin_device_task
  MODIFY COLUMN min_reward DECIMAL(18,5) NOT NULL DEFAULT 0,
  MODIFY COLUMN max_reward DECIMAL(18,5) NOT NULL DEFAULT 0;

-- Preserve existing tasks while converging their taxonomy on the six
-- server-authoritative task-class codes.
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

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('E.task.queueSaturation','0.35','DECIMAL','E2','ADMIN','E2 locked teaser global saturation',1,0)
ON DUPLICATE KEY UPDATE value_type='DECIMAL', config_group='E2', visibility='ADMIN',
  remark=VALUES(remark), status=1, is_deleted=0, updated_at=NOW();

INSERT INTO nx_admin_device_task (
  task_id, name, price, unit_text, requirement, saturation, status,
  task_class, model_name, min_reward, max_reward, min_vram, kill_init,
  created_at, updated_at, is_deleted
) VALUES
  ('TK-1','LLM 推理',       0.8500,'/job','需 NexionRack',    0.35,'active','LL','Llama 70B,Phi-3-mini',      0.00005,0.8500,'80GB','派发中',NOW(),NOW(),0),
  ('TK-2','语音识别',       0.0720,'/job','手机+',            0.35,'active','SP','Whisper',                    0.00005,0.0720,'8GB', '派发中',NOW(),NOW(),0),
  ('TK-3','图像生成',       0.0450,'/job','S1+',              0.35,'active','IG','SDXL Turbo,Flux Schnell',    0.00010,0.0450,'12GB','派发中',NOW(),NOW(),0),
  ('TK-4','视频生成',       1.8000,'/job','需 NexionBox Pro', 0.35,'active','VG','Sora-class',                 0.45000,1.8000,'48GB','派发中',NOW(),NOW(),0),
  ('TK-5','模型微调',       0.4200,'/job','需 NexionBox Pro', 0.35,'active','FT','LoRA',                       0.06000,0.4200,'48GB','派发中',NOW(),NOW(),0),
  ('TK-6','向量嵌入',       0.0900,'/1k', '手机+',            0.35,'active','EM','BGE-M3',                     0.00001,0.0900,'8GB', '派发中',NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE
  name=VALUES(name), price=VALUES(price), unit_text=VALUES(unit_text), requirement=VALUES(requirement),
  saturation=VALUES(saturation), status=VALUES(status), task_class=VALUES(task_class),
  model_name=VALUES(model_name), min_reward=VALUES(min_reward), max_reward=VALUES(max_reward),
  min_vram=VALUES(min_vram), kill_init=VALUES(kill_init), updated_at=NOW(), is_deleted=0;

INSERT INTO nx_event_schema_registry
  (event_name, owner_domain, family_key, producer, consumers, is_server_authoritative,
   sampling_policy, current_revision, status, created_by, reason, is_deleted)
VALUES
  ('admin.task_pricing_changed','admin','phase_admin','server','E2/A2/B4/L3',1,'100%',43,
   'ACTIVE','migration:e2','E2 authoritative task pricing change event',0)
ON DUPLICATE KEY UPDATE
  owner_domain='admin', family_key='phase_admin', producer='server', consumers='E2/A2/B4/L3',
  is_server_authoritative=1, sampling_policy='100%', current_revision=43,
  status='ACTIVE', updated_by='migration:e2', reason=VALUES(reason), is_deleted=0;

UPDATE nx_event_schema_property p
JOIN nx_event_schema_registry s ON s.id=p.schema_id
   SET p.is_deleted=1, p.updated_at=NOW()
 WHERE s.event_name='admin.task_pricing_changed'
   AND p.property_name NOT IN ('task_class','field','before','after','effective_at','operator','reason','ts');

INSERT INTO nx_event_schema_property
  (schema_id, property_name, property_type, pii, required_field, registry_revision, is_deleted)
SELECT s.id,p.property_name,p.property_type,0,1,s.current_revision,0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'task_class' property_name,'enum' property_type UNION ALL
    SELECT 'field','enum' UNION ALL
    SELECT 'before','json' UNION ALL
    SELECT 'after','json' UNION ALL
    SELECT 'effective_at','timestamp' UNION ALL
    SELECT 'operator','id' UNION ALL
    SELECT 'reason','string' UNION ALL
    SELECT 'ts','timestamp'
  ) p
 WHERE s.event_name='admin.task_pricing_changed'
ON DUPLICATE KEY UPDATE property_type=VALUES(property_type), pii=0, required_field=1,
  registry_revision=VALUES(registry_revision), is_deleted=0;

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,43)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,43);

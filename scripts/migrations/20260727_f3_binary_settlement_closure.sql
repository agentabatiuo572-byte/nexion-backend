-- F3 closure: complete fail-closed policy seeds and register the authoritative
-- commission.paid payload produced in the same transaction as D4/A2 writes.
USE nexion;
SET NAMES utf8mb4;

-- Immutable per-pair consumption cursor. A successful "每次对碰清零" settlement
-- appends one row; no UPDATE path exists. The previous through-volume id is part
-- of the INSERT predicate, so a stale/concurrent writer cannot consume the same
-- paid-volume window twice.
CREATE TABLE IF NOT EXISTS nx_binary_volume_cursor (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  owner_user_id BIGINT NOT NULL,
  settlement_date DATE NOT NULL,
  from_volume_id_exclusive BIGINT NOT NULL,
  through_volume_id_inclusive BIGINT NOT NULL,
  left_volume DECIMAL(18,6) NOT NULL,
  right_volume DECIMAL(18,6) NOT NULL,
  matched_volume DECIMAL(18,6) NOT NULL,
  policy VARCHAR(32) NOT NULL,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uk_binary_cursor_owner_date (owner_user_id,settlement_date),
  KEY idx_binary_cursor_owner_id (owner_user_id,id),
  CONSTRAINT chk_binary_cursor_range CHECK (through_volume_id_inclusive >= from_volume_id_exclusive),
  CONSTRAINT chk_binary_cursor_amounts CHECK (
    left_volume >= 0 AND right_volume >= 0 AND matched_volume >= 0
  )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,
   created_at,updated_at,is_deleted)
VALUES
  ('team.ui.F.binary.threshold','1000','DECIMAL','team','ADMIN',
   'F3 minimum monthly paid volume required on each track',1,NOW(),NOW(),0),
  ('team.ui.F.binary.matchRate','10%','STRING','team','ADMIN',
   'F3 smaller-track balance match rate',1,NOW(),NOW(),0),
  ('team.ui.F.binary.spillover','已启用','ENUM','team','ADMIN',
   'F3 automatic placement policy',1,NOW(),NOW(),0),
  ('team.ui.F.binary.settlePeriod','每月','ENUM','team','ADMIN',
   'F3 settlement cadence: 每日/每周/每月',1,NOW(),NOW(),0),
  ('team.ui.F.binary.residualPolicy','每月清零','ENUM','team','ADMIN',
   'F3 unmatched-volume policy: 每月清零/每次对碰清零/转结',1,NOW(),NOW(),0),
  ('team.ui.F.binary.gvResetCron','每月 1 日 00:00 UTC','STRING','team','ADMIN',
   'F3 natural-month GV reset boundary; read-only operations contract',1,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE
  value_type=VALUES(value_type),config_group='team',visibility='ADMIN',
  remark=VALUES(remark),status=1,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_registry
  (event_name,owner_domain,family_key,producer,consumers,is_server_authoritative,
   sampling_policy,current_revision,status,created_by,reason,is_deleted)
VALUES
  ('commission.paid','commission','money','F3BinarySettlement',
   'A4/D4/B1/B2/F4/F5/L1/L4',1,'100%',277,'ACTIVE',
   'migration:f3-binary-settlement',
   'F3 authoritative binary commission settlement completed',0)
ON DUPLICATE KEY UPDATE
  owner_domain='commission',family_key='money',
  producer='F3BinarySettlement',
  consumers='A4/D4/B1/B2/F4/F5/L1/L4',
  is_server_authoritative=1,sampling_policy='100%',current_revision=277,
  status='ACTIVE',updated_by='migration:f3-binary-settlement',
  reason=VALUES(reason),is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_property
  (schema_id,property_name,property_type,pii,required_field,registry_revision,
   created_at,updated_at,is_deleted)
SELECT s.id,p.property_name,p.property_type,0,p.required_field,277,NOW(),NOW(),0
  FROM nx_event_schema_registry s
  JOIN (
    SELECT 'kind' property_name,'enum' property_type,1 required_field UNION ALL
    SELECT 'currency','enum',1 UNION ALL
    SELECT 'amount','number',1 UNION ALL
    SELECT 'smaller_track_gv','number',0 UNION ALL
    SELECT 'match_rate','number',0 UNION ALL
    SELECT 'daily_cap_applied','boolean',0 UNION ALL
    SELECT 'commission_event_id','id',0
  ) p
 WHERE s.event_name='commission.paid'
ON DUPLICATE KEY UPDATE
  property_type=VALUES(property_type),pii=0,required_field=VALUES(required_field),
  registry_revision=277,is_deleted=0,updated_at=NOW();

INSERT INTO nx_event_schema_revision (id,current_revision) VALUES (1,277)
ON DUPLICATE KEY UPDATE current_revision=GREATEST(current_revision,277);

-- H3 trusted fact intake: durable canonical-event routing with no user completion endpoint.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_growth_quest_event_binding (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  binding_code VARCHAR(48) NOT NULL,
  producer VARCHAR(32) NOT NULL,
  event_type VARCHAR(128) NOT NULL,
  quest_code VARCHAR(64) NOT NULL,
  user_id_field VARCHAR(64) NOT NULL,
  status TINYINT NOT NULL DEFAULT 1,
  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  is_deleted TINYINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_growth_quest_event_binding_code (binding_code),
  UNIQUE KEY uk_growth_quest_event_binding (producer,event_type,quest_code,user_id_field),
  KEY idx_growth_quest_event_type (event_type,status,is_deleted)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

INSERT INTO nx_mission
  (mission_code,mission_name,mission_type,reward_points,status,created_at,updated_at,is_deleted)
VALUES
  ('H3_FIRST_ORDER_STARTED','Start your first order','DAY_ONE',50,1,NOW(),NOW(),0),
  ('H3_REFERRAL_SETTLED','Complete a qualified referral','DAY_ONE',200,1,NOW(),NOW(),0),
  ('H3_LEARNING_COMPLETED','Complete a learning course','WEEKLY_T1',30,1,NOW(),NOW(),0),
  ('H3_DEVICE_ACTIVATED','Activate a device','WEEKLY_T1',100,1,NOW(),NOW(),0),
  ('H3_COMMISSION_UNLOCKED','Unlock a commission reward','WEEKLY_T2',250,1,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE
  mission_name=VALUES(mission_name),mission_type=VALUES(mission_type),
  reward_points=VALUES(reward_points),status=1,updated_at=NOW(),is_deleted=0;

INSERT INTO nx_growth_quest_event_binding
  (binding_code,producer,event_type,quest_code,user_id_field,status,is_deleted)
VALUES
  ('ORDER_STARTED','ORDER','checkout.started','H3_FIRST_ORDER_STARTED','user_id',1,0),
  ('REFERRAL_SETTLED','REFERRAL','H8_REFERRAL_REWARD_SETTLED','H3_REFERRAL_SETTLED','inviter_user_id',1,0),
  ('LEARNING_COMPLETED','LEARNING','LEARNING_COURSE_COMPLETED','H3_LEARNING_COMPLETED','user_id',1,0),
  ('DEVICE_ACTIVATED','DEVICE','admin.device_activated','H3_DEVICE_ACTIVATED','user_id',1,0),
  ('COMMISSION_UNLOCKED','COMMISSION','COMMISSION_UNLOCKED','H3_COMMISSION_UNLOCKED','user_id',1,0)
ON DUPLICATE KEY UPDATE
  producer=VALUES(producer),event_type=VALUES(event_type),quest_code=VALUES(quest_code),
  user_id_field=VALUES(user_id_field),status=1,updated_at=NOW(),is_deleted=0;

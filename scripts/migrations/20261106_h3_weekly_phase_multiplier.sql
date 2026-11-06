-- Old publications and templates used H1 alone. Their phase stays unknown and the H3 factor defaults to one.
SET NAMES utf8mb4;
SET @weekly_phase_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_growth_weekly_definition' AND COLUMN_NAME='rhythm_phase')=0,
  'ALTER TABLE nx_growth_weekly_definition ADD COLUMN rhythm_phase VARCHAR(2) NULL', 'SELECT 1');
PREPARE weekly_phase_stmt FROM @weekly_phase_sql;
EXECUTE weekly_phase_stmt;
DEALLOCATE PREPARE weekly_phase_stmt;

SET @weekly_phase_sql = IF((SELECT COUNT(*) FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
  AND TABLE_NAME='nx_growth_weekly_definition' AND COLUMN_NAME='weekly_phase_multiplier')=0,
  'ALTER TABLE nx_growth_weekly_definition ADD COLUMN weekly_phase_multiplier DECIMAL(18,6) NOT NULL DEFAULT 1', 'SELECT 1');
PREPARE weekly_phase_stmt FROM @weekly_phase_sql;
EXECUTE weekly_phase_stmt;
DEALLOCATE PREPARE weekly_phase_stmt;

-- Install the established H3 defaults on realms that have never configured these keys.
INSERT INTO nx_config_item(config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
VALUES ('growth.quest.weekly.mult.P1','1.0×','STRING','growth','ADMIN','H3 weekly phase multiplier',1,0),
       ('growth.quest.weekly.mult.P2','1.0×','STRING','growth','ADMIN','H3 weekly phase multiplier',1,0),
       ('growth.quest.weekly.mult.P3','1.1×','STRING','growth','ADMIN','H3 weekly phase multiplier',1,0),
       ('growth.quest.weekly.mult.P4','1.2×','STRING','growth','ADMIN','H3 weekly phase multiplier',1,0),
       ('growth.quest.weekly.mult.P5','1.3×','STRING','growth','ADMIN','H3 weekly phase multiplier',1,0),
       ('growth.quest.weekly.mult.P6','1.5×','STRING','growth','ADMIN','H3 weekly phase multiplier',1,0)
ON DUPLICATE KEY UPDATE config_key=nx_config_item.config_key;

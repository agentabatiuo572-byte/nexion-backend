-- P1 closure: H3 mission progress is bound to a server-owned eligibility/period
-- instance, and H8 rewards require an explicit operator-controlled enable gate.
-- The migration is rerunnable and preserves historical task/reward records.

INSERT INTO nx_config_item
  (config_key,config_value,value_type,config_group,visibility,remark,status,is_deleted)
SELECT seed.config_key,seed.config_value,seed.value_type,seed.config_group,'ADMIN',seed.remark,1,0
  FROM (
    SELECT 'growth.quest.day_one.eligibility_hours' config_key,'72' config_value,'INTEGER' value_type,
           'growth' config_group,'H3 server-authoritative Day-One eligibility duration in hours' remark
    UNION ALL
    SELECT 'K.rewards.referral.enabled','false','BOOLEAN','GROWTH_REFERRAL',
           'H8 referral rewards are disabled until an authorized operator explicitly enables them'
    UNION ALL
    SELECT 'K.rewards.referral.effectiveAt','1970-01-01T00:00:00Z','DATETIME','GROWTH_REFERRAL',
           'H8 reward eligibility starts here; enabling rewards resets this value to the enable time'
  ) seed
 WHERE NOT EXISTS (
   SELECT 1 FROM nx_config_item current WHERE current.config_key=seed.config_key
 );

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_mission' AND COLUMN_NAME='instance_key')=0,
  'ALTER TABLE nx_user_mission ADD COLUMN instance_key VARCHAR(48) NULL AFTER mission_id',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

-- Backfill each historical row into the period in which it was last active.
-- DAY_ONE remains tied to the user's original registration window.
UPDATE nx_user_mission um
JOIN nx_mission m ON m.id=um.mission_id
JOIN nx_user u ON u.id=um.user_id
   SET um.instance_key=CASE
     WHEN m.mission_type='DAY_ONE'
       THEN CONCAT('DAY_ONE:',DATE_FORMAT(u.created_at,'%Y%m%dT%H%i%s'))
     WHEN m.mission_type IN ('WEEKLY_T1','WEEKLY_T2')
       THEN CONCAT('WEEK:',DATE_FORMAT(COALESCE(um.completed_at,um.updated_at,um.created_at),'%x-W%v'))
     ELSE CONCAT('LEGACY:',um.id)
   END
 WHERE um.instance_key IS NULL OR TRIM(um.instance_key)='';

-- Historical orphan rows can exist after account/definition cleanup.  They
-- remain readable for audit, but must not prevent the instance column from
-- becoming non-null or collide with a future canonical instance.
UPDATE nx_user_mission
   SET instance_key=CONCAT('LEGACY:',id)
 WHERE instance_key IS NULL OR TRIM(instance_key)='';

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_mission' AND COLUMN_NAME='instance_key'
      AND (IS_NULLABLE<>'NO' OR COLUMN_DEFAULT<>'LEGACY'))>0,
  'ALTER TABLE nx_user_mission MODIFY COLUMN instance_key VARCHAR(48) NOT NULL DEFAULT ''LEGACY''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @old_user_mission_index = (
  SELECT GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX)
    FROM information_schema.STATISTICS
   WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_mission' AND INDEX_NAME='uk_user_mission'
);
SET @sql = IF(@old_user_mission_index IS NOT NULL AND @old_user_mission_index<>'user_id,mission_id,instance_key',
  'ALTER TABLE nx_user_mission DROP INDEX uk_user_mission','SELECT 1');
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM (
     SELECT INDEX_NAME,GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) columns_list
       FROM information_schema.STATISTICS
      WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_mission' AND NON_UNIQUE=0
      GROUP BY INDEX_NAME
   ) indexes_found WHERE columns_list='user_id,mission_id,instance_key')=0,
  'ALTER TABLE nx_user_mission ADD UNIQUE KEY uk_user_mission (user_id,mission_id,instance_key)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_growth_quest_completion_fact' AND COLUMN_NAME='instance_key')=0,
  'ALTER TABLE nx_growth_quest_completion_fact ADD COLUMN instance_key VARCHAR(48) NULL AFTER quest_code',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

UPDATE nx_growth_quest_completion_fact fact
JOIN nx_mission mission ON mission.id=fact.mission_id
JOIN nx_user user_row ON user_row.id=fact.user_id
   SET fact.instance_key=CASE
     WHEN mission.mission_type='DAY_ONE'
       THEN CONCAT('DAY_ONE:',DATE_FORMAT(user_row.created_at,'%Y%m%dT%H%i%s'))
     WHEN mission.mission_type IN ('WEEKLY_T1','WEEKLY_T2')
       THEN CONCAT('WEEK:',DATE_FORMAT(fact.created_at,'%x-W%v'))
     ELSE 'LEGACY'
   END
 WHERE fact.instance_key IS NULL OR TRIM(fact.instance_key)='';

UPDATE nx_growth_quest_completion_fact
   SET instance_key=CONCAT('LEGACY:',id)
 WHERE instance_key IS NULL OR TRIM(instance_key)='';

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_growth_quest_completion_fact' AND COLUMN_NAME='instance_key'
      AND (IS_NULLABLE<>'NO' OR COLUMN_DEFAULT<>'LEGACY'))>0,
  'ALTER TABLE nx_growth_quest_completion_fact MODIFY COLUMN instance_key VARCHAR(48) NOT NULL DEFAULT ''LEGACY''',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

SET @sql = IF(
  (SELECT COUNT(*) FROM information_schema.STATISTICS
    WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_user_mission'
      AND INDEX_NAME='idx_user_mission_current')=0,
  'CREATE INDEX idx_user_mission_current ON nx_user_mission(user_id,instance_key,is_deleted,mission_status)',
  'SELECT 1'
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

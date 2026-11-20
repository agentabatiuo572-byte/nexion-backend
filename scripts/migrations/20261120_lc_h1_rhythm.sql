-- Run before the historical monthly matrix fills its missing rows.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

START TRANSACTION;

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('H1.rhythm.totalMonths','9','NUMBER','growth','ADMIN','H1 launch rhythm length',1,0),
  ('H1.rhythm.currentMonth','1','NUMBER','growth','ADMIN','H1 launch rhythm month',1,0),
  ('H1.rhythm.phaseProgressPct','1','NUMBER','growth','ADMIN','H1 launch phase progress',1,0),
  ('growth.phase.current_month','1','NUMBER','growth','ADMIN','H1 active month mirror',1,0),
  ('growth.phase.current','P1','STRING','growth','ADMIN','H1 active phase',1,0)
ON DUPLICATE KEY UPDATE config_key = nx_config_item.config_key;

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
SELECT 'platform.phase.config',
       JSON_OBJECT('calendar', CONCAT(rhythm.config_value, '_MONTH'),
                   'activePhase', phase.config_value, 'activeDialCount', 8),
       'JSON','platform','ADMIN','H1 phase configuration',1,0
  FROM nx_config_item rhythm
  JOIN nx_config_item phase ON phase.config_key = 'growth.phase.current'
 WHERE rhythm.config_key = 'H1.rhythm.totalMonths'
ON DUPLICATE KEY UPDATE config_key = nx_config_item.config_key;

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
WITH RECURSIVE months(month_no) AS (
  SELECT 1 UNION ALL SELECT month_no + 1 FROM months WHERE month_no < 24
), launch_dials(dial_key, dial_value, last_month) AS (
  SELECT 'withdrawCooldownDays', '3', 24 UNION ALL
  SELECT 'binaryDailyCap', '120', 24 UNION ALL
  SELECT 'questBonusMultiplier', '2', 2
)
SELECT CONCAT('growth.phase.month.', month_no, '.', dial_key),
       dial_value, 'NUMBER','growth','ADMIN','H1 launch monthly dial',1,0
  FROM months JOIN launch_dials ON month_no <= last_month
ON DUPLICATE KEY UPDATE config_key = nx_config_item.config_key;

COMMIT;

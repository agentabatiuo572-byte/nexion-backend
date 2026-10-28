-- H9 network pulse members: publish the requested cadence (every 60 s, +3 to +10 per tick).
-- Applies only while no cadence is stored, so a value an operator saves later is never overwritten.
-- The estimate restarts from registeredUsersBase at apply time. MySQL 8, rerunnable.
SET NAMES utf8mb4;

UPDATE nx_config_item
   SET config_value = JSON_REMOVE(JSON_SET(config_value,
         '$.registeredUsersTickIntervalSec', 60,
         '$.registeredUsersTickStepMin', 3,
         '$.registeredUsersTickStepMax', 10,
         '$.registeredUsersAnchorAt', CAST(ROUND(UNIX_TIMESTAMP(NOW(3)) * 1000) AS UNSIGNED),
         '$.effectiveAt', CAST(ROUND(UNIX_TIMESTAMP(NOW(3)) * 1000) AS UNSIGNED)),
         '$.registeredUsersTickStep'),
       updated_at = NOW()
 WHERE config_key = 'growth.public_stats.values' AND is_deleted = 0 AND JSON_VALID(config_value)
   AND JSON_EXTRACT(config_value, '$.registeredUsersTickIntervalSec') IS NULL;
SET @h9_member_tick_rows = ROW_COUNT();

-- Consoles holding the old version must re-read before saving over the new cadence.
UPDATE nx_config_item
   SET config_value = CAST(CAST(config_value AS UNSIGNED) + 1 AS CHAR), updated_at = NOW()
 WHERE config_key = 'growth.public_stats.version' AND is_deleted = 0 AND @h9_member_tick_rows > 0;

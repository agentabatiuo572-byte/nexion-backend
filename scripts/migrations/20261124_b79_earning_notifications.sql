-- B79: one-time earning-credit cutover and dailySummary retirement.
-- Rerunnable, and it never alters nx_business_notification_control, whose historical seed
-- inserts three bare values on every startup.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS nx_earning_notification_cutover (
  id TINYINT NOT NULL PRIMARY KEY,
  cutover_at DATETIME(6) NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- The cutover and the retirement commit together: a failed retirement must not leave a T behind.
START TRANSACTION;

-- The whole second that contains T belongs to the new side; later runs only read this row.
INSERT IGNORE INTO nx_earning_notification_cutover(id, cutover_at)
VALUES (1, DATE_FORMAT(UTC_TIMESTAMP(), '%Y-%m-%d %H:%i:%s'));

-- The channel is retired for good; the adapter catalog no longer offers it and enabling is refused.
UPDATE nx_nova_channel
   SET enabled = 0,
       operator = 'system:migration',
       reason = 'B79 dailySummary retired in favour of earning_credited',
       updated_at = NOW()
 WHERE channel_key = 'dailySummary' AND is_deleted = 0 AND enabled <> 0;

COMMIT;

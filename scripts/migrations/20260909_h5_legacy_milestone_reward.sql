-- The legacy POINTS reward is NEX in the canonical H5 product.
-- Preserve configured quantities, status and deletion. Do not replay old event revisions.
SET NAMES utf8mb4;
UPDATE nx_streak_milestone
   SET reward_type='NEX',
       reward_name=CONCAT('+', TRIM(TRAILING '.' FROM TRIM(TRAILING '0' FROM CAST(reward_amount AS CHAR))), ' NEX'),
       updated_at=NOW()
 WHERE milestone_day IN (3,7)
   AND UPPER(reward_type)='POINTS'
   AND is_deleted=0;

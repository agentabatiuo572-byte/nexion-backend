-- Rebrand the V-Rank global prize name away from the retired "Nexion" brand.
--
-- Why: `team.ui.F.prize.name` still carries the pre-rename copy "Nexion V-Rank"
-- in this environment. The PC F1 page renders it verbatim (f1-vrank.tsx), and
-- AppVRankController falls back to "NexGrid V-Rank" only when the key is absent,
-- so the stale stored value wins and the retired brand keeps shipping to both
-- the console and the App.
--
-- Scope is deliberately narrow: only rows whose value still contains the old
-- brand token are rewritten, matched with LIKE instead of a hard-coded full
-- string. A row an operator already corrected to any other copy is left alone.
--
-- Idempotent: after the first run no row matches the predicate, so a second
-- startup pass is a no-op. There is no historical version table for
-- nx_config_item (the canonical table holds one row per config_key under
-- uk_config_key), so nothing else needs to be kept in sync.

-- 1. Rebrand only rows that still carry the retired brand token.
UPDATE nx_config_item
   SET config_value = REPLACE(config_value, 'Nexion', 'NexGrid'),
       updated_at = NOW()
 WHERE config_key = 'team.ui.F.prize.name'
   AND config_value LIKE '%Nexion%';

-- 2. Postcondition: no active V-Rank prize name may still carry the old brand.
--    A bare SELECT would only print the verdict and the controlled runner does
--    not parse it, so the check raises SQLSTATE 45000 instead: mysql exits
--    non-zero and the runner stops backend startup rather than letting a
--    half-applied rebrand present as success.
SET @vrank_prize_rebranded = (
  SELECT COUNT(*) = 0
    FROM nx_config_item
   WHERE config_key = 'team.ui.F.prize.name'
     AND config_value LIKE '%Nexion%'
);
SET @sql = IF(
  @vrank_prize_rebranded,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''VRANK_PRIZE_BRAND_REBRAND_INCOMPLETE'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

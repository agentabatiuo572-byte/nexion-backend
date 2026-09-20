-- Provision the single ACTIVE Genesis series so the G4 subscription flow is usable.
--
-- Why: 20260807_nexion_hard_blockers.sql seeded only the first nx_genesis_tier row
-- (its range_to fell back to the constant 10000 because no series existed to read
-- MAX(total_supply) from) and never inserted a nx_genesis_series row. With
-- activeSeriesCount()==0 GenesisCatalogService.readiness() returns
-- GENESIS_SERIES_UNAVAILABLE, catalogAvailable/tradeAvailable stay false, and the
-- whole G4 primary purchase path is dead for both the console and the App.
--
-- Derivation, never invention: the series is derived only from the authoritative
-- tier ladder that operators already maintain. total_supply = the last ACTIVE
-- tier's range_to and price_usdt = the first ACTIVE tier's price_usdt, which is
-- exactly the invariant GenesisCatalogService.initializeSeriesOnce applies and
-- readiness() re-checks (a mismatch is GENESIS_TIERS_SUPPLY_MISMATCH). Royalty,
-- daily emission, and dividend formula use the same safe-initial values the
-- service writes. No price, supply, or holder is fabricated here.
--
-- Refuses to act unless the database is in the state the service itself accepts
-- for series initialization: no ACTIVE series, no holdings (soldCount()==0),
-- a contiguous ladder that starts at 0, positive prices, and a series_code that
-- has never existed. Anything else (a ladder with a gap, an existing deleted row
-- on the chosen code, or holdings with no series) is a recovery situation that
-- needs an operator decision, so the migration no-ops and the postcondition
-- explicitly allows "no series" as a legal state instead of failing startup.
--
-- The market is deliberately left as-is: the seeded catalog state is
-- 'closed' fail-closed, and GenesisCatalogService.updateMarketStateOnce requires
-- an explicit, reason-carrying operator reopen (requireReadyForOpen) before trade
-- is allowed. Opening the market is a risk decision, not a data repair.
--
-- Idempotent: the INSERT is guarded by NOT EXISTS on an ACTIVE series, so a
-- second startup pass is a no-op.

SET @genesis_tier_count = (
  SELECT COUNT(*) FROM nx_genesis_tier WHERE status = 'ACTIVE' AND is_deleted = 0);

SET @genesis_tier_gaps = (
  SELECT COUNT(*) FROM (
    SELECT range_from, LAG(range_to) OVER (ORDER BY range_from, id) AS prev_range_to
      FROM nx_genesis_tier WHERE status = 'ACTIVE' AND is_deleted = 0
  ) ladder
  WHERE prev_range_to IS NOT NULL AND prev_range_to <> range_from);

SET @genesis_ladder_ok = (
  @genesis_tier_count >= 1
  AND @genesis_tier_gaps = 0
  AND (SELECT COALESCE(MIN(range_from), -1) FROM nx_genesis_tier
        WHERE status = 'ACTIVE' AND is_deleted = 0) = 0
  AND (SELECT COUNT(*) FROM nx_genesis_tier
        WHERE status = 'ACTIVE' AND is_deleted = 0 AND (range_to <= range_from OR price_usdt <= 0)) = 0
  AND (SELECT COUNT(*) FROM nx_genesis_series WHERE UPPER(status) = 'ACTIVE' AND is_deleted = 0) = 0
  AND (SELECT COUNT(*) FROM nx_genesis_holding WHERE is_deleted = 0) = 0
  AND (SELECT COUNT(*) FROM nx_genesis_series WHERE series_code = 'NEXGRID-GENESIS-PRIMARY') = 0);

INSERT INTO nx_genesis_series
  (series_code, name, total_supply, sold_supply, price_usdt, status,
   royalty_bps, daily_dividend_rate_pct, dividend_base_formula, is_deleted)
SELECT 'NEXGRID-GENESIS-PRIMARY',
       'NexGrid Genesis 首发系列',
       (SELECT range_to FROM nx_genesis_tier
         WHERE status = 'ACTIVE' AND is_deleted = 0 ORDER BY range_from DESC, id DESC LIMIT 1),
       0,
       (SELECT price_usdt FROM nx_genesis_tier
         WHERE status = 'ACTIVE' AND is_deleted = 0 ORDER BY range_from ASC, id ASC LIMIT 1),
       'ACTIVE', 0, 0, '', 0
 WHERE @genesis_ladder_ok = 1;

-- Postcondition: the derived series may never disagree with the ladder it came
-- from, and it may never duplicate the single-ACTIVE-series contract. A bare
-- SELECT would only print the verdict and the controlled runner does not parse
-- it, so the check raises SQLSTATE 45000 instead: mysql exits non-zero and the
-- runner stops backend startup rather than letting a half-applied provisioning
-- present as success. "No series" stays legal (an empty or gapped ladder is an
-- operator recovery state, not a migration failure).
SET @genesis_series_ok = (
  (SELECT COUNT(*) FROM nx_genesis_series WHERE UPPER(status) = 'ACTIVE' AND is_deleted = 0) <= 1
  AND (SELECT COUNT(*) FROM nx_genesis_series s
        WHERE UPPER(s.status) = 'ACTIVE' AND s.is_deleted = 0
          AND @genesis_tier_count >= 1
          AND s.total_supply <> (SELECT range_to FROM nx_genesis_tier
                                  WHERE status = 'ACTIVE' AND is_deleted = 0
                                  ORDER BY range_from DESC, id DESC LIMIT 1)) = 0
);
SET @sql = IF(
  @genesis_series_ok,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''GENESIS_SERIES_PROVISIONING_INCOMPLETE'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

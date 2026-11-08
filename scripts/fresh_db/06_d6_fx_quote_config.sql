-- Restore the D6 singleton omitted by the DDL-only fresh prerequisite.
-- Keep existing operator values, versions, timestamps and soft-delete tombstones.
INSERT IGNORE INTO nx_finance_fx_quote_config (
  config_code, base_rate_vnd_per_usdt, buy_spread_pct, lock_window_minutes, version, is_deleted
) VALUES ('VND_USDT', 26000, 1.50, 30, 0, 0);

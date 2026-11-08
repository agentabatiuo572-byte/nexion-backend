-- Missing canonical D5 values from 20260720_d5_withdrawal_limits_closure.sql.
-- New TRC20/ERC20 switches start closed; existing values, states and tombstones win.
INSERT IGNORE INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('withdrawal.daily_count_limit','2','NUMBER','wallet','ADMIN','D5 daily withdrawal count',1,0),
  ('withdrawal.max_balance_pct','0.80','NUMBER','wallet','ADMIN','D5 withdrawable balance ratio',1,0),
  ('withdrawal.fee_rate','0.02','NUMBER','wallet','ADMIN','D5 network fee ratio',1,0),
  ('withdrawal.fee_min_usdt','0.50','NUMBER','wallet','ADMIN','D5 minimum network fee',1,0),
  ('withdrawal.fee_max_usdt','20.00','NUMBER','wallet','ADMIN','D5 maximum network fee',1,0),
  ('withdrawal.d5.version','1','NUMBER','wallet','ADMIN','D5 aggregate config version',1,0),
  ('withdrawal.trc20.enabled','false','BOOLEAN','wallet','ADMIN','D5 TRC20 withdrawal switch; fresh default closed',1,0),
  ('withdrawal.erc20.enabled','false','BOOLEAN','wallet','ADMIN','D5 ERC20 withdrawal switch; fresh default closed',1,0);

-- D1 充值通道真实口径：TRC20/BEP20/ERC20/VietQR/国际卡。
-- 国际卡最低 $30、单笔上限 $5,000；旧 BTC/ETH 展示通道退出 D1 目录。

INSERT INTO nx_config_item
  (config_key, config_value, value_type, config_group, visibility, remark, status, is_deleted)
VALUES
  ('finance.topup.channel.trc20.fee','1','NUMBER','finance-topup','ADMIN','D1 TRC20 fixed fee',1,0),
  ('finance.topup.channel.trc20.fee_unit','USDT_FIXED','STRING','finance-topup','ADMIN','D1 TRC20 fee unit',1,0),
  ('finance.topup.channel.trc20.min_amount','10','NUMBER','finance-topup','ADMIN','D1 TRC20 minimum',1,0),
  ('finance.topup.channel.trc20.min_amount_unit','USD','STRING','finance-topup','ADMIN','D1 TRC20 minimum unit',1,0),
  ('finance.topup.channel.bep20.fee','1','NUMBER','finance-topup','ADMIN','D1 BEP20 fixed fee',1,0),
  ('finance.topup.channel.bep20.fee_unit','USDT_FIXED','STRING','finance-topup','ADMIN','D1 BEP20 fee unit',1,0),
  ('finance.topup.channel.bep20.min_amount','10','NUMBER','finance-topup','ADMIN','D1 BEP20 minimum',1,0),
  ('finance.topup.channel.bep20.min_amount_unit','USD','STRING','finance-topup','ADMIN','D1 BEP20 minimum unit',1,0),
  ('finance.topup.channel.erc20.fee','5','NUMBER','finance-topup','ADMIN','D1 ERC20 fixed fee',1,0),
  ('finance.topup.channel.erc20.fee_unit','USDT_FIXED','STRING','finance-topup','ADMIN','D1 ERC20 fee unit',1,0),
  ('finance.topup.channel.erc20.min_amount','10','NUMBER','finance-topup','ADMIN','D1 ERC20 minimum',1,0),
  ('finance.topup.channel.erc20.min_amount_unit','USD','STRING','finance-topup','ADMIN','D1 ERC20 minimum unit',1,0),
  ('finance.topup.channel.vietqr.fee','0','NUMBER','finance-topup','ADMIN','D1 VietQR platform fee',1,0),
  ('finance.topup.channel.vietqr.fee_unit','PERCENT','STRING','finance-topup','ADMIN','D1 VietQR fee unit',1,0),
  ('finance.topup.channel.vietqr.min_amount','10','NUMBER','finance-topup','ADMIN','D1 VietQR minimum',1,0),
  ('finance.topup.channel.vietqr.min_amount_unit','USD','STRING','finance-topup','ADMIN','D1 VietQR minimum unit',1,0),
  ('finance.topup.channel.card.fee','3.5','NUMBER','finance-topup','ADMIN','D1 international card fee',1,0),
  ('finance.topup.channel.card.fee_unit','PERCENT','STRING','finance-topup','ADMIN','D1 card fee unit',1,0),
  ('finance.topup.channel.card.min_amount','30','NUMBER','finance-topup','ADMIN','D1 international card minimum',1,0),
  ('finance.topup.channel.card.min_amount_unit','USD','STRING','finance-topup','ADMIN','D1 card minimum unit',1,0),
  ('finance.topup.channel.card.max_amount','5000','NUMBER','finance-topup','ADMIN','D1 international card single-transaction cap',1,0),
  ('finance.topup.channel.card.max_amount_unit','USD','STRING','finance-topup','ADMIN','D1 card maximum unit',1,0)
ON DUPLICATE KEY UPDATE
  -- Existing values are operator-owned. Migration only adds missing rails/fields.
  config_value=nx_config_item.config_value,
  value_type=VALUES(value_type),
  config_group=VALUES(config_group),
  visibility=VALUES(visibility),
  remark=VALUES(remark),
  status=nx_config_item.status,
  is_deleted=nx_config_item.is_deleted,
  updated_at=NOW();

UPDATE nx_config_item
   SET status=0, is_deleted=1, updated_at=NOW()
 WHERE (config_key LIKE 'finance.topup.channel.btc.%'
     OR config_key LIKE 'finance.topup.channel.eth.%')
   AND is_deleted=0;

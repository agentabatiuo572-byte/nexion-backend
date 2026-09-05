-- Manual rollback only after confirming these exact before/after values still describe the intended rows.
START TRANSACTION;
UPDATE nx_user_device SET base_power_w=0,row_version=row_version+1,updated_at=NOW(6)
WHERE ((id=821 AND product_code='stellarbox-pro') OR (id=822 AND product_code='stellarbox-pro-v2'))
AND source_channel='ORDER' AND base_power_w=250 AND is_deleted=0;
UPDATE nx_user_device SET daily_usdt=38.52,daily_nex=65,row_version=row_version+1,updated_at=NOW(6)
WHERE id=1142 AND product_code='stellarbox-s1' AND source_channel='TRIAL'
AND daily_usdt=1 AND daily_nex=1 AND is_deleted=0;
COMMIT;

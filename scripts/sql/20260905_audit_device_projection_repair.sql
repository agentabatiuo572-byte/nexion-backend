-- Manual, narrowly scoped repair of three verified audit rows. Never updates wallet/ledger/order amounts.
-- Before: 821/822 power=0; 1142 daily_usdt=38.52,daily_nex=65 (copied trial shadow).
-- Catalogue readback: pro/pro-v2=250W; stellarbox-s1=1 USDT/1 NEX per day.
START TRANSACTION;
SELECT id,product_code,base_power_w,daily_usdt,daily_nex,row_version
FROM nx_user_device WHERE id IN (821,822,1142) AND is_deleted=0 FOR UPDATE;
UPDATE nx_user_device d JOIN nx_admin_device_sku s ON s.sku_id=d.product_code AND s.is_deleted=0
SET d.base_power_w=250,d.row_version=d.row_version+1,d.updated_at=NOW(6)
WHERE ((d.id=821 AND d.product_code='stellarbox-pro') OR (d.id=822 AND d.product_code='stellarbox-pro-v2'))
AND d.source_channel='ORDER' AND d.base_power_w=0 AND d.is_deleted=0 AND TRIM(s.power_text)='250W';
SELECT ROW_COUNT() AS corrected_power_rows;
UPDATE nx_user_device d JOIN nx_product p ON p.id=d.product_id AND p.is_deleted=0
SET d.daily_usdt=p.estimated_daily_usdt,d.daily_nex=p.daily_nex,d.row_version=d.row_version+1,d.updated_at=NOW(6)
WHERE d.id=1142 AND d.product_code='stellarbox-s1' AND d.source_channel='TRIAL' AND d.is_deleted=0
AND d.daily_usdt=38.52 AND d.daily_nex=65 AND p.estimated_daily_usdt=1 AND p.daily_nex=1;
SELECT ROW_COUNT() AS corrected_trial_projection_rows;
COMMIT;
SELECT id,product_code,base_power_w,daily_usdt,daily_nex FROM nx_user_device WHERE id IN (821,822,1142);

-- Scope: one verified TRIAL device with missing immutable specification fields.
-- Source: its linked PC product/SKU. No order, balance or yield fields are changed.
-- Before: gpu_model=NULL, vram_total_gb=NULL, dc_location=NULL, base_power_w=0.
START TRANSACTION;
SELECT id, gpu_model, vram_total_gb, dc_location, base_power_w
FROM nx_user_device
WHERE id=1142 AND instance_no='TRIAL-DEV-0C1CA2DC0E574D6E931D'
FOR UPDATE;
UPDATE nx_user_device d
JOIN nx_product p ON p.id=d.product_id AND p.product_no=d.product_code AND p.is_deleted=0
JOIN nx_admin_device_sku s ON s.sku_id=p.product_no AND s.is_deleted=0
SET d.gpu_model=p.gpu_model, d.vram_total_gb=p.vram_total_gb,
    d.dc_location=s.datacenter,
    d.base_power_w=CAST(TRIM(REPLACE(REPLACE(s.power_text,'W',''),'w','')) AS DECIMAL(18,6)),
    d.updated_at=NOW()
WHERE d.id=1142 AND d.instance_no='TRIAL-DEV-0C1CA2DC0E574D6E931D'
  AND d.source_channel='TRIAL' AND d.source_order_no='TRC-7173E6A7B07C4739904F7E4590753AC2'
  AND d.product_code='stellarbox-s1' AND d.is_deleted=0
  AND d.gpu_model IS NULL AND d.vram_total_gb IS NULL AND d.dc_location IS NULL
  AND d.base_power_w=0
  AND TRIM(p.gpu_model)<>'' AND p.vram_total_gb>0 AND TRIM(s.datacenter)<>''
  AND s.power_text REGEXP '^[0-9]+([.][0-9]+)?[[:space:]]*[Ww]?$'
  AND CAST(TRIM(REPLACE(REPLACE(s.power_text,'W',''),'w','')) AS DECIMAL(18,6))>0;
SELECT ROW_COUNT() AS repaired_rows;
SELECT id,gpu_model,vram_total_gb,base_power_w,dc_location,daily_usdt,daily_nex
FROM nx_user_device WHERE id=1142;
-- Correct the same device's physical classification, without changing earnings.
UPDATE nx_user_device d
JOIN nx_product p ON p.id=d.product_id AND p.product_no=d.product_code AND p.is_deleted=0
SET d.device_type=UPPER(p.product_type), d.updated_at=NOW()
WHERE d.id=1142 AND d.instance_no='TRIAL-DEV-0C1CA2DC0E574D6E931D'
  AND d.source_channel='TRIAL' AND d.source_order_no='TRC-7173E6A7B07C4739904F7E4590753AC2'
  AND d.product_code='stellarbox-s1' AND d.is_deleted=0 AND d.device_type='CLOUD'
  AND UPPER(p.product_type) IN ('DEVICE','SERVER')
  AND TRIM(d.gpu_model)<>'' AND d.vram_total_gb>0 AND TRIM(d.dc_location)<>'' AND d.base_power_w>0;
SELECT ROW_COUNT() AS repaired_type_rows;
SELECT id,device_type,daily_usdt,daily_nex FROM nx_user_device WHERE id=1142;
COMMIT;

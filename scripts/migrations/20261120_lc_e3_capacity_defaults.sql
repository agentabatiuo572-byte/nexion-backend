-- Install the complete E3 device capacity policy used by earnings and task settlement.
-- The values match the existing capacity policy and the E3 admin defaults.
-- Preserve existing rows, including soft-deleted rows and their metadata.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

INSERT INTO nx_compute_e3_config
  (config_key,config_value,value_type,updated_by,sort_order,is_deleted)
SELECT defaults.config_key,defaults.config_value,defaults.value_type,'migration:lc-e3-capacity',defaults.sort_order,0
  FROM (
    SELECT 'capacityBand1DeltaPct' AS config_key,'-3' AS config_value,'NUMBER' AS value_type,10 AS sort_order UNION ALL
    SELECT 'capacityBand2DeltaPct','-6','NUMBER',20 UNION ALL
    SELECT 'capacityBand3DeltaPct','-23.7','NUMBER',30 UNION ALL
    SELECT 'stageEarlyEnd','3','NUMBER',40 UNION ALL
    SELECT 'stageMidEnd','8','NUMBER',50 UNION ALL
    SELECT 'cycleMonths','12','NUMBER',60 UNION ALL
    SELECT 'capacityFloorPct','22','NUMBER',70 UNION ALL
    SELECT 'capacitySubsidyDays','30','NUMBER',75 UNION ALL
    SELECT 'taskLockS1','30','NUMBER',80 UNION ALL
    SELECT 'taskLockPro','150','NUMBER',90 UNION ALL
    SELECT 'taskLockRack','480','NUMBER',100 UNION ALL
    SELECT 'capacityApplyToPhone','false','BOOLEAN',210 UNION ALL
    SELECT 'capacityApplyToCloudShare','false','BOOLEAN',220 UNION ALL
    SELECT 'capacityApplyToPcGpu','false','BOOLEAN',230 UNION ALL
    SELECT 'capacityApplyToS1','true','BOOLEAN',240 UNION ALL
    SELECT 'capacityApplyToPro','true','BOOLEAN',250 UNION ALL
    SELECT 'capacityApplyToProV2','true','BOOLEAN',260 UNION ALL
    SELECT 'capacityApplyToRackP1','true','BOOLEAN',270 UNION ALL
    SELECT 'capacityApplyToRackP2','true','BOOLEAN',280
  ) defaults
 WHERE NOT EXISTS (
   SELECT 1 FROM nx_compute_e3_config existing WHERE existing.config_key = defaults.config_key
 )
 ORDER BY defaults.sort_order;

-- LC-04: install the complete E3 trade-in policy and default to one device per order.
-- Existing rows, including their values, deletion status and metadata, are preserved.
SET NAMES utf8mb4;

INSERT INTO nx_compute_e3_config
  (config_key,config_value,value_type,updated_by,sort_order,created_at,updated_at,is_deleted)
VALUES
  ('eligibility','L4_PLUS','STRING','migration:lc-tradein-rules',120,NOW(),NOW(),0),
  ('tradeinEnabled','true','BOOLEAN','migration:lc-tradein-rules',300,NOW(),NOW(),0),
  ('tradeinLadderCut1','25','NUMBER','migration:lc-tradein-rules',310,NOW(),NOW(),0),
  ('tradeinLadderCut2','50','NUMBER','migration:lc-tradein-rules',320,NOW(),NOW(),0),
  ('tradeinLadderCut3','75','NUMBER','migration:lc-tradein-rules',330,NOW(),NOW(),0),
  ('tradeinLadderCut4','100','NUMBER','migration:lc-tradein-rules',340,NOW(),NOW(),0),
  ('tradeinLadderCredit1','75','NUMBER','migration:lc-tradein-rules',350,NOW(),NOW(),0),
  ('tradeinLadderCredit2','60','NUMBER','migration:lc-tradein-rules',360,NOW(),NOW(),0),
  ('tradeinLadderCredit3','45','NUMBER','migration:lc-tradein-rules',370,NOW(),NOW(),0),
  ('tradeinLadderCredit4','30','NUMBER','migration:lc-tradein-rules',380,NOW(),NOW(),0),
  ('tradeinLadderCredit5','15','NUMBER','migration:lc-tradein-rules',390,NOW(),NOW(),0),
  ('tradeinRequireHigherPrice','true','BOOLEAN','migration:lc-tradein-rules',400,NOW(),NOW(),0),
  ('tradeinMaxDevicesPerOrder','1','NUMBER','migration:lc-tradein-rules',410,NOW(),NOW(),0),
  ('earlyAccessEnabled','false','BOOLEAN','migration:lc-tradein-rules',420,NOW(),NOW(),0),
  ('earlyAccessLeadDays','30','NUMBER','migration:lc-tradein-rules',430,NOW(),NOW(),0)
ON DUPLICATE KEY UPDATE config_key = config_key;

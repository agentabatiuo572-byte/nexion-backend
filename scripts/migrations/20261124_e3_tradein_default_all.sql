-- E3-BE-07: replace the original launch eligibility, preserving operator choices.
SET NAMES utf8mb4;

UPDATE nx_compute_e3_config
   SET config_value = 'ALL',
       updated_by = 'migration:e3-tradein-default-all'
 WHERE config_key = 'eligibility'
   AND is_deleted = 0
   AND BINARY config_value IN (BINARY 'L4_PLUS', BINARY 'L4+ 持有者')
   AND BINARY updated_by IN (BINARY 'migration:lc-tradein-rules',
                             BINARY 'migration:e3-admin-closure',
                             BINARY 'migration:e3-user-tradein');

-- MySQL 8 read-only CTE fixtures. No table writes, DDL, credentials, or provider calls.
-- Each statement executes the production @Select unresolvedExposure SQL with projectId=88.
-- The four fixture CTEs shadow every physical table referenced by that SQL.
-- Signature flags represent already-verified ingress facts; fixture signatures are synthetic.
-- NULL exposure is deliberately unavailable, never zero. All passed/threshold_passed columns must be 1.
-- Production annotation SHA256: 08f985b9e0373ec47e5616b9e235a062cb7c854d819a98df183bf50f04190026




WITH nx_cregis_deposit_delivery AS (
SELECT CAST(NULL AS SIGNED) AS id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS accepted,CAST(NULL AS DATETIME) AS processed_at,CAST(NULL AS CHAR(64)) AS reason,CAST(NULL AS CHAR(2000)) AS raw_json,CAST(NULL AS SIGNED) AS signature_valid,CAST(NULL AS SIGNED) AS timestamp_valid,CAST(NULL AS SIGNED) AS ip_valid WHERE 0
UNION ALL SELECT 1,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"+100","status":1,"txid":"0XAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","block_height":"101","nonce":"A00001","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 2,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"1e2","status":1,"txid":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 3,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100.","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00003","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 4,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100.000000","status":1,"txid":"0xAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","block_height":"101","nonce":"A00004","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 5,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA","block_height":"101","nonce":"A00005","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
),
nx_cregis_deposit_event AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
nx_cregis_chain_observation AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS CHAR(80)) AS raw_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
nx_withdrawal_order AS (
SELECT CAST(NULL AS DECIMAL(18,6)) AS amount,CAST(NULL AS CHAR(16)) AS asset,CAST(NULL AS SIGNED) AS is_deleted,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
fixture_actual(actual) AS (
WITH delivery_json AS (
  SELECT d.*, IF(JSON_VALID(raw_json),raw_json,'{}') AS payload
    FROM nx_cregis_deposit_delivery d
   WHERE accepted=1 AND (processed_at IS NULL OR reason IN
     ('PROVIDER_FAILED_HOLD','PROVIDER_MISMATCH_HOLD','CHAIN_MISMATCH_HOLD','PROVIDER_CONFLICT_HOLD'))
), delivery_snapshots AS (
  SELECT d.*,
         CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) AS UNSIGNED) END AS snapshot_block,
         CASE WHEN signature_valid=1 AND timestamp_valid=1 AND ip_valid=1
              AND JSON_VALID(raw_json)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.pid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.pid'))=CAST(88 AS CHAR)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.cid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.cid'))=CAST(cid AS CHAR)
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.chain_id'))='2510'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.token_id')))='0x55d398326f99059ff775485246999027b3197955'
              AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.currency'))) IN
                ('USDT','USDT-BEP20','2510@0X55D398326F99059FF775485246999027B3197955')
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status'))='1'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))) REGEXP '^(0x)?[0-9a-f]{64}$'
              AND CONCAT('0x',RIGHT(LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))),64))=LOWER(txid)
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.address')))=LOWER(address)
              AND gross_amount>0
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.nonce')) REGEXP '^[A-Za-z0-9]{6}$'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.sign')) REGEXP '^[0-9a-fA-F]{32}$'
              THEN 1 ELSE 0 END AS snapshot_valid
    FROM delivery_json d
), delivery_exposure AS (
  SELECT MAX(CASE WHEN gross_amount>0 THEN gross_amount END) AS amount
    FROM delivery_snapshots d
   WHERE snapshot_valid=0 OR (
     NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                 WHERE e.project_id=88 AND e.cid=d.cid
                   AND e.txid=d.txid AND e.address=d.address
                   AND e.gross_amount=d.gross_amount AND e.block_number=d.snapshot_block)
     AND NOT EXISTS(SELECT 1 FROM nx_cregis_chain_observation o
                     WHERE o.project_id=88 AND o.txid=d.txid AND o.address=d.address
                       AND CAST(o.raw_amount AS DECIMAL(18,6))=d.gross_amount
                       AND o.block_number=d.snapshot_block))
   GROUP BY CASE WHEN snapshot_valid=1 THEN 0 ELSE id END,
            cid,txid,address,gross_amount,snapshot_block
)
SELECT
  COALESCE((SELECT SUM(gross_amount) FROM nx_cregis_deposit_event
             WHERE project_id=88 AND status IN
             ('DUST_HOLD','REVIEW_HOLD','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')),0)
  + COALESCE((SELECT SUM(CAST(o.raw_amount AS DECIMAL(18,6)))
                FROM nx_cregis_chain_observation o
               WHERE o.project_id=88 AND o.status<>'MATCHED'
                 AND NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                                 WHERE e.project_id=o.project_id
                                   AND e.txid=o.txid AND e.log_index=o.log_index)),0)
  + (SELECT CASE WHEN COUNT(*)<>COUNT(amount) THEN NULL ELSE COALESCE(SUM(amount),0) END
       FROM delivery_exposure)
  + COALESCE((SELECT SUM(w.amount) FROM nx_withdrawal_order w
               WHERE w.asset='USDT' AND w.is_deleted=0
                 AND w.status NOT IN ('CONFIRMED','FAILED','REFUNDED','CANCELLED','REJECTED')),0)
)
SELECT 'five_resigned_duplicates' AS test_case, 100 AS expected, actual,
       actual <=> 100 AS passed,
       (actual >= 500) <=> 0 AS threshold_passed
  FROM fixture_actual;

WITH nx_cregis_deposit_delivery AS (
SELECT CAST(NULL AS SIGNED) AS id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS accepted,CAST(NULL AS DATETIME) AS processed_at,CAST(NULL AS CHAR(64)) AS reason,CAST(NULL AS CHAR(2000)) AS raw_json,CAST(NULL AS SIGNED) AS signature_valid,CAST(NULL AS SIGNED) AS timestamp_valid,CAST(NULL AS SIGNED) AS ip_valid WHERE 0
UNION ALL SELECT 1,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00001","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 2,78,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":78,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 3,79,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":79,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00003","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 4,80,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":80,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00004","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 5,81,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":81,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00005","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
),
nx_cregis_deposit_event AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
nx_cregis_chain_observation AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS CHAR(80)) AS raw_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
nx_withdrawal_order AS (
SELECT CAST(NULL AS DECIMAL(18,6)) AS amount,CAST(NULL AS CHAR(16)) AS asset,CAST(NULL AS SIGNED) AS is_deleted,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
fixture_actual(actual) AS (
WITH delivery_json AS (
  SELECT d.*, IF(JSON_VALID(raw_json),raw_json,'{}') AS payload
    FROM nx_cregis_deposit_delivery d
   WHERE accepted=1 AND (processed_at IS NULL OR reason IN
     ('PROVIDER_FAILED_HOLD','PROVIDER_MISMATCH_HOLD','CHAIN_MISMATCH_HOLD','PROVIDER_CONFLICT_HOLD'))
), delivery_snapshots AS (
  SELECT d.*,
         CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) AS UNSIGNED) END AS snapshot_block,
         CASE WHEN signature_valid=1 AND timestamp_valid=1 AND ip_valid=1
              AND JSON_VALID(raw_json)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.pid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.pid'))=CAST(88 AS CHAR)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.cid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.cid'))=CAST(cid AS CHAR)
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.chain_id'))='2510'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.token_id')))='0x55d398326f99059ff775485246999027b3197955'
              AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.currency'))) IN
                ('USDT','USDT-BEP20','2510@0X55D398326F99059FF775485246999027B3197955')
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status'))='1'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))) REGEXP '^(0x)?[0-9a-f]{64}$'
              AND CONCAT('0x',RIGHT(LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))),64))=LOWER(txid)
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.address')))=LOWER(address)
              AND gross_amount>0
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.nonce')) REGEXP '^[A-Za-z0-9]{6}$'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.sign')) REGEXP '^[0-9a-fA-F]{32}$'
              THEN 1 ELSE 0 END AS snapshot_valid
    FROM delivery_json d
), delivery_exposure AS (
  SELECT MAX(CASE WHEN gross_amount>0 THEN gross_amount END) AS amount
    FROM delivery_snapshots d
   WHERE snapshot_valid=0 OR (
     NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                 WHERE e.project_id=88 AND e.cid=d.cid
                   AND e.txid=d.txid AND e.address=d.address
                   AND e.gross_amount=d.gross_amount AND e.block_number=d.snapshot_block)
     AND NOT EXISTS(SELECT 1 FROM nx_cregis_chain_observation o
                     WHERE o.project_id=88 AND o.txid=d.txid AND o.address=d.address
                       AND CAST(o.raw_amount AS DECIMAL(18,6))=d.gross_amount
                       AND o.block_number=d.snapshot_block))
   GROUP BY CASE WHEN snapshot_valid=1 THEN 0 ELSE id END,
            cid,txid,address,gross_amount,snapshot_block
)
SELECT
  COALESCE((SELECT SUM(gross_amount) FROM nx_cregis_deposit_event
             WHERE project_id=88 AND status IN
             ('DUST_HOLD','REVIEW_HOLD','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')),0)
  + COALESCE((SELECT SUM(CAST(o.raw_amount AS DECIMAL(18,6)))
                FROM nx_cregis_chain_observation o
               WHERE o.project_id=88 AND o.status<>'MATCHED'
                 AND NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                                 WHERE e.project_id=o.project_id
                                   AND e.txid=o.txid AND e.log_index=o.log_index)),0)
  + (SELECT CASE WHEN COUNT(*)<>COUNT(amount) THEN NULL ELSE COALESCE(SUM(amount),0) END
       FROM delivery_exposure)
  + COALESCE((SELECT SUM(w.amount) FROM nx_withdrawal_order w
               WHERE w.asset='USDT' AND w.is_deleted=0
                 AND w.status NOT IN ('CONFIRMED','FAILED','REFUNDED','CANCELLED','REJECTED')),0)
)
SELECT 'same_amount_five_distinct_events_and_500_trip' AS test_case, 500 AS expected, actual,
       actual <=> 500 AS passed,
       (actual >= 500) <=> 1 AS threshold_passed
  FROM fixture_actual;

WITH nx_cregis_deposit_delivery AS (
SELECT CAST(NULL AS SIGNED) AS id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS accepted,CAST(NULL AS DATETIME) AS processed_at,CAST(NULL AS CHAR(64)) AS reason,CAST(NULL AS CHAR(2000)) AS raw_json,CAST(NULL AS SIGNED) AS signature_valid,CAST(NULL AS SIGNED) AS timestamp_valid,CAST(NULL AS SIGNED) AS ip_valid WHERE 0
UNION ALL SELECT 1,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00001","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 2,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 3,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00003","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 4,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00004","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 5,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00005","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
),
nx_cregis_deposit_event AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
UNION ALL SELECT 88,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',0,'0x1111111111111111111111111111111111111111','100',101,'CREDITED'
),
nx_cregis_chain_observation AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS CHAR(80)) AS raw_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
nx_withdrawal_order AS (
SELECT CAST(NULL AS DECIMAL(18,6)) AS amount,CAST(NULL AS CHAR(16)) AS asset,CAST(NULL AS SIGNED) AS is_deleted,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
fixture_actual(actual) AS (
WITH delivery_json AS (
  SELECT d.*, IF(JSON_VALID(raw_json),raw_json,'{}') AS payload
    FROM nx_cregis_deposit_delivery d
   WHERE accepted=1 AND (processed_at IS NULL OR reason IN
     ('PROVIDER_FAILED_HOLD','PROVIDER_MISMATCH_HOLD','CHAIN_MISMATCH_HOLD','PROVIDER_CONFLICT_HOLD'))
), delivery_snapshots AS (
  SELECT d.*,
         CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) AS UNSIGNED) END AS snapshot_block,
         CASE WHEN signature_valid=1 AND timestamp_valid=1 AND ip_valid=1
              AND JSON_VALID(raw_json)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.pid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.pid'))=CAST(88 AS CHAR)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.cid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.cid'))=CAST(cid AS CHAR)
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.chain_id'))='2510'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.token_id')))='0x55d398326f99059ff775485246999027b3197955'
              AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.currency'))) IN
                ('USDT','USDT-BEP20','2510@0X55D398326F99059FF775485246999027B3197955')
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status'))='1'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))) REGEXP '^(0x)?[0-9a-f]{64}$'
              AND CONCAT('0x',RIGHT(LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))),64))=LOWER(txid)
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.address')))=LOWER(address)
              AND gross_amount>0
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.nonce')) REGEXP '^[A-Za-z0-9]{6}$'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.sign')) REGEXP '^[0-9a-fA-F]{32}$'
              THEN 1 ELSE 0 END AS snapshot_valid
    FROM delivery_json d
), delivery_exposure AS (
  SELECT MAX(CASE WHEN gross_amount>0 THEN gross_amount END) AS amount
    FROM delivery_snapshots d
   WHERE snapshot_valid=0 OR (
     NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                 WHERE e.project_id=88 AND e.cid=d.cid
                   AND e.txid=d.txid AND e.address=d.address
                   AND e.gross_amount=d.gross_amount AND e.block_number=d.snapshot_block)
     AND NOT EXISTS(SELECT 1 FROM nx_cregis_chain_observation o
                     WHERE o.project_id=88 AND o.txid=d.txid AND o.address=d.address
                       AND CAST(o.raw_amount AS DECIMAL(18,6))=d.gross_amount
                       AND o.block_number=d.snapshot_block))
   GROUP BY CASE WHEN snapshot_valid=1 THEN 0 ELSE id END,
            cid,txid,address,gross_amount,snapshot_block
)
SELECT
  COALESCE((SELECT SUM(gross_amount) FROM nx_cregis_deposit_event
             WHERE project_id=88 AND status IN
             ('DUST_HOLD','REVIEW_HOLD','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')),0)
  + COALESCE((SELECT SUM(CAST(o.raw_amount AS DECIMAL(18,6)))
                FROM nx_cregis_chain_observation o
               WHERE o.project_id=88 AND o.status<>'MATCHED'
                 AND NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                                 WHERE e.project_id=o.project_id
                                   AND e.txid=o.txid AND e.log_index=o.log_index)),0)
  + (SELECT CASE WHEN COUNT(*)<>COUNT(amount) THEN NULL ELSE COALESCE(SUM(amount),0) END
       FROM delivery_exposure)
  + COALESCE((SELECT SUM(w.amount) FROM nx_withdrawal_order w
               WHERE w.asset='USDT' AND w.is_deleted=0
                 AND w.status NOT IN ('CONFIRMED','FAILED','REFUNDED','CANCELLED','REJECTED')),0)
)
SELECT 'credited_event_absorbs_pending_duplicates' AS test_case, 0 AS expected, actual,
       actual <=> 0 AS passed,
       (actual >= 500) <=> 0 AS threshold_passed
  FROM fixture_actual;

WITH nx_cregis_deposit_delivery AS (
SELECT CAST(NULL AS SIGNED) AS id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS accepted,CAST(NULL AS DATETIME) AS processed_at,CAST(NULL AS CHAR(64)) AS reason,CAST(NULL AS CHAR(2000)) AS raw_json,CAST(NULL AS SIGNED) AS signature_valid,CAST(NULL AS SIGNED) AS timestamp_valid,CAST(NULL AS SIGNED) AS ip_valid WHERE 0
UNION ALL SELECT 1,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00001","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 2,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 3,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00003","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 4,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00004","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 5,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00005","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
),
nx_cregis_deposit_event AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
UNION ALL SELECT 88,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',0,'0x1111111111111111111111111111111111111111','100',101,'REVIEW_HOLD'
),
nx_cregis_chain_observation AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS CHAR(80)) AS raw_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
nx_withdrawal_order AS (
SELECT CAST(NULL AS DECIMAL(18,6)) AS amount,CAST(NULL AS CHAR(16)) AS asset,CAST(NULL AS SIGNED) AS is_deleted,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
fixture_actual(actual) AS (
WITH delivery_json AS (
  SELECT d.*, IF(JSON_VALID(raw_json),raw_json,'{}') AS payload
    FROM nx_cregis_deposit_delivery d
   WHERE accepted=1 AND (processed_at IS NULL OR reason IN
     ('PROVIDER_FAILED_HOLD','PROVIDER_MISMATCH_HOLD','CHAIN_MISMATCH_HOLD','PROVIDER_CONFLICT_HOLD'))
), delivery_snapshots AS (
  SELECT d.*,
         CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) AS UNSIGNED) END AS snapshot_block,
         CASE WHEN signature_valid=1 AND timestamp_valid=1 AND ip_valid=1
              AND JSON_VALID(raw_json)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.pid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.pid'))=CAST(88 AS CHAR)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.cid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.cid'))=CAST(cid AS CHAR)
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.chain_id'))='2510'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.token_id')))='0x55d398326f99059ff775485246999027b3197955'
              AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.currency'))) IN
                ('USDT','USDT-BEP20','2510@0X55D398326F99059FF775485246999027B3197955')
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status'))='1'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))) REGEXP '^(0x)?[0-9a-f]{64}$'
              AND CONCAT('0x',RIGHT(LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))),64))=LOWER(txid)
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.address')))=LOWER(address)
              AND gross_amount>0
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.nonce')) REGEXP '^[A-Za-z0-9]{6}$'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.sign')) REGEXP '^[0-9a-fA-F]{32}$'
              THEN 1 ELSE 0 END AS snapshot_valid
    FROM delivery_json d
), delivery_exposure AS (
  SELECT MAX(CASE WHEN gross_amount>0 THEN gross_amount END) AS amount
    FROM delivery_snapshots d
   WHERE snapshot_valid=0 OR (
     NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                 WHERE e.project_id=88 AND e.cid=d.cid
                   AND e.txid=d.txid AND e.address=d.address
                   AND e.gross_amount=d.gross_amount AND e.block_number=d.snapshot_block)
     AND NOT EXISTS(SELECT 1 FROM nx_cregis_chain_observation o
                     WHERE o.project_id=88 AND o.txid=d.txid AND o.address=d.address
                       AND CAST(o.raw_amount AS DECIMAL(18,6))=d.gross_amount
                       AND o.block_number=d.snapshot_block))
   GROUP BY CASE WHEN snapshot_valid=1 THEN 0 ELSE id END,
            cid,txid,address,gross_amount,snapshot_block
)
SELECT
  COALESCE((SELECT SUM(gross_amount) FROM nx_cregis_deposit_event
             WHERE project_id=88 AND status IN
             ('DUST_HOLD','REVIEW_HOLD','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')),0)
  + COALESCE((SELECT SUM(CAST(o.raw_amount AS DECIMAL(18,6)))
                FROM nx_cregis_chain_observation o
               WHERE o.project_id=88 AND o.status<>'MATCHED'
                 AND NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                                 WHERE e.project_id=o.project_id
                                   AND e.txid=o.txid AND e.log_index=o.log_index)),0)
  + (SELECT CASE WHEN COUNT(*)<>COUNT(amount) THEN NULL ELSE COALESCE(SUM(amount),0) END
       FROM delivery_exposure)
  + COALESCE((SELECT SUM(w.amount) FROM nx_withdrawal_order w
               WHERE w.asset='USDT' AND w.is_deleted=0
                 AND w.status NOT IN ('CONFIRMED','FAILED','REFUNDED','CANCELLED','REJECTED')),0)
)
SELECT 'held_event_counts_once' AS test_case, 100 AS expected, actual,
       actual <=> 100 AS passed,
       (actual >= 500) <=> 0 AS threshold_passed
  FROM fixture_actual;

WITH nx_cregis_deposit_delivery AS (
SELECT CAST(NULL AS SIGNED) AS id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS accepted,CAST(NULL AS DATETIME) AS processed_at,CAST(NULL AS CHAR(64)) AS reason,CAST(NULL AS CHAR(2000)) AS raw_json,CAST(NULL AS SIGNED) AS signature_valid,CAST(NULL AS SIGNED) AS timestamp_valid,CAST(NULL AS SIGNED) AS ip_valid WHERE 0
UNION ALL SELECT 1,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','0.5',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":".5","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00001","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 2,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','0.5',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"+.5","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 3,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','0.5',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"5e-1","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00003","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 4,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','0.5',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"0.500000","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00004","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 5,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','0.5',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"0.5","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00005","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
),
nx_cregis_deposit_event AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
nx_cregis_chain_observation AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS CHAR(80)) AS raw_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
UNION ALL SELECT 88,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',0,'0x1111111111111111111111111111111111111111','0.5',101,'PROVIDER_MISSING'
),
nx_withdrawal_order AS (
SELECT CAST(NULL AS DECIMAL(18,6)) AS amount,CAST(NULL AS CHAR(16)) AS asset,CAST(NULL AS SIGNED) AS is_deleted,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
fixture_actual(actual) AS (
WITH delivery_json AS (
  SELECT d.*, IF(JSON_VALID(raw_json),raw_json,'{}') AS payload
    FROM nx_cregis_deposit_delivery d
   WHERE accepted=1 AND (processed_at IS NULL OR reason IN
     ('PROVIDER_FAILED_HOLD','PROVIDER_MISMATCH_HOLD','CHAIN_MISMATCH_HOLD','PROVIDER_CONFLICT_HOLD'))
), delivery_snapshots AS (
  SELECT d.*,
         CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) AS UNSIGNED) END AS snapshot_block,
         CASE WHEN signature_valid=1 AND timestamp_valid=1 AND ip_valid=1
              AND JSON_VALID(raw_json)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.pid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.pid'))=CAST(88 AS CHAR)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.cid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.cid'))=CAST(cid AS CHAR)
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.chain_id'))='2510'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.token_id')))='0x55d398326f99059ff775485246999027b3197955'
              AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.currency'))) IN
                ('USDT','USDT-BEP20','2510@0X55D398326F99059FF775485246999027B3197955')
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status'))='1'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))) REGEXP '^(0x)?[0-9a-f]{64}$'
              AND CONCAT('0x',RIGHT(LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))),64))=LOWER(txid)
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.address')))=LOWER(address)
              AND gross_amount>0
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.nonce')) REGEXP '^[A-Za-z0-9]{6}$'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.sign')) REGEXP '^[0-9a-fA-F]{32}$'
              THEN 1 ELSE 0 END AS snapshot_valid
    FROM delivery_json d
), delivery_exposure AS (
  SELECT MAX(CASE WHEN gross_amount>0 THEN gross_amount END) AS amount
    FROM delivery_snapshots d
   WHERE snapshot_valid=0 OR (
     NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                 WHERE e.project_id=88 AND e.cid=d.cid
                   AND e.txid=d.txid AND e.address=d.address
                   AND e.gross_amount=d.gross_amount AND e.block_number=d.snapshot_block)
     AND NOT EXISTS(SELECT 1 FROM nx_cregis_chain_observation o
                     WHERE o.project_id=88 AND o.txid=d.txid AND o.address=d.address
                       AND CAST(o.raw_amount AS DECIMAL(18,6))=d.gross_amount
                       AND o.block_number=d.snapshot_block))
   GROUP BY CASE WHEN snapshot_valid=1 THEN 0 ELSE id END,
            cid,txid,address,gross_amount,snapshot_block
)
SELECT
  COALESCE((SELECT SUM(gross_amount) FROM nx_cregis_deposit_event
             WHERE project_id=88 AND status IN
             ('DUST_HOLD','REVIEW_HOLD','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')),0)
  + COALESCE((SELECT SUM(CAST(o.raw_amount AS DECIMAL(18,6)))
                FROM nx_cregis_chain_observation o
               WHERE o.project_id=88 AND o.status<>'MATCHED'
                 AND NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                                 WHERE e.project_id=o.project_id
                                   AND e.txid=o.txid AND e.log_index=o.log_index)),0)
  + (SELECT CASE WHEN COUNT(*)<>COUNT(amount) THEN NULL ELSE COALESCE(SUM(amount),0) END
       FROM delivery_exposure)
  + COALESCE((SELECT SUM(w.amount) FROM nx_withdrawal_order w
               WHERE w.asset='USDT' AND w.is_deleted=0
                 AND w.status NOT IN ('CONFIRMED','FAILED','REFUNDED','CANCELLED','REJECTED')),0)
)
SELECT 'observed_chain_event_counts_once' AS test_case, 0.5 AS expected, actual,
       actual <=> 0.5 AS passed,
       (actual >= 500) <=> 0 AS threshold_passed
  FROM fixture_actual;

WITH nx_cregis_deposit_delivery AS (
SELECT CAST(NULL AS SIGNED) AS id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS accepted,CAST(NULL AS DATETIME) AS processed_at,CAST(NULL AS CHAR(64)) AS reason,CAST(NULL AS CHAR(2000)) AS raw_json,CAST(NULL AS SIGNED) AS signature_valid,CAST(NULL AS SIGNED) AS timestamp_valid,CAST(NULL AS SIGNED) AS ip_valid WHERE 0
UNION ALL SELECT 1,78,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":78,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 2,77,'0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 3,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x2222222222222222222222222222222222222222','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x2222222222222222222222222222222222222222","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 4,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','101',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"101","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 5,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"102","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 6,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":2,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 7,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":89,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
),
nx_cregis_deposit_event AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
UNION ALL SELECT 88,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',0,'0x1111111111111111111111111111111111111111','100',101,'CREDITED'
),
nx_cregis_chain_observation AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS CHAR(80)) AS raw_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
nx_withdrawal_order AS (
SELECT CAST(NULL AS DECIMAL(18,6)) AS amount,CAST(NULL AS CHAR(16)) AS asset,CAST(NULL AS SIGNED) AS is_deleted,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
fixture_actual(actual) AS (
WITH delivery_json AS (
  SELECT d.*, IF(JSON_VALID(raw_json),raw_json,'{}') AS payload
    FROM nx_cregis_deposit_delivery d
   WHERE accepted=1 AND (processed_at IS NULL OR reason IN
     ('PROVIDER_FAILED_HOLD','PROVIDER_MISMATCH_HOLD','CHAIN_MISMATCH_HOLD','PROVIDER_CONFLICT_HOLD'))
), delivery_snapshots AS (
  SELECT d.*,
         CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) AS UNSIGNED) END AS snapshot_block,
         CASE WHEN signature_valid=1 AND timestamp_valid=1 AND ip_valid=1
              AND JSON_VALID(raw_json)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.pid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.pid'))=CAST(88 AS CHAR)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.cid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.cid'))=CAST(cid AS CHAR)
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.chain_id'))='2510'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.token_id')))='0x55d398326f99059ff775485246999027b3197955'
              AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.currency'))) IN
                ('USDT','USDT-BEP20','2510@0X55D398326F99059FF775485246999027B3197955')
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status'))='1'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))) REGEXP '^(0x)?[0-9a-f]{64}$'
              AND CONCAT('0x',RIGHT(LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))),64))=LOWER(txid)
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.address')))=LOWER(address)
              AND gross_amount>0
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.nonce')) REGEXP '^[A-Za-z0-9]{6}$'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.sign')) REGEXP '^[0-9a-fA-F]{32}$'
              THEN 1 ELSE 0 END AS snapshot_valid
    FROM delivery_json d
), delivery_exposure AS (
  SELECT MAX(CASE WHEN gross_amount>0 THEN gross_amount END) AS amount
    FROM delivery_snapshots d
   WHERE snapshot_valid=0 OR (
     NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                 WHERE e.project_id=88 AND e.cid=d.cid
                   AND e.txid=d.txid AND e.address=d.address
                   AND e.gross_amount=d.gross_amount AND e.block_number=d.snapshot_block)
     AND NOT EXISTS(SELECT 1 FROM nx_cregis_chain_observation o
                     WHERE o.project_id=88 AND o.txid=d.txid AND o.address=d.address
                       AND CAST(o.raw_amount AS DECIMAL(18,6))=d.gross_amount
                       AND o.block_number=d.snapshot_block))
   GROUP BY CASE WHEN snapshot_valid=1 THEN 0 ELSE id END,
            cid,txid,address,gross_amount,snapshot_block
)
SELECT
  COALESCE((SELECT SUM(gross_amount) FROM nx_cregis_deposit_event
             WHERE project_id=88 AND status IN
             ('DUST_HOLD','REVIEW_HOLD','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')),0)
  + COALESCE((SELECT SUM(CAST(o.raw_amount AS DECIMAL(18,6)))
                FROM nx_cregis_chain_observation o
               WHERE o.project_id=88 AND o.status<>'MATCHED'
                 AND NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                                 WHERE e.project_id=o.project_id
                                   AND e.txid=o.txid AND e.log_index=o.log_index)),0)
  + (SELECT CASE WHEN COUNT(*)<>COUNT(amount) THEN NULL ELSE COALESCE(SUM(amount),0) END
       FROM delivery_exposure)
  + COALESCE((SELECT SUM(w.amount) FROM nx_withdrawal_order w
               WHERE w.asset='USDT' AND w.is_deleted=0
                 AND w.status NOT IN ('CONFIRMED','FAILED','REFUNDED','CANCELLED','REJECTED')),0)
)
SELECT 'conflicting_snapshots_cannot_hide_behind_credited_event' AS test_case, 701 AS expected, actual,
       actual <=> 701 AS passed,
       (actual >= 500) <=> 1 AS threshold_passed
  FROM fixture_actual;

WITH nx_cregis_deposit_delivery AS (
SELECT CAST(NULL AS SIGNED) AS id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS accepted,CAST(NULL AS DATETIME) AS processed_at,CAST(NULL AS CHAR(64)) AS reason,CAST(NULL AS CHAR(2000)) AS raw_json,CAST(NULL AS SIGNED) AS signature_valid,CAST(NULL AS SIGNED) AS timestamp_valid,CAST(NULL AS SIGNED) AS ip_valid WHERE 0
UNION ALL SELECT 1,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00001","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',0,1,1
UNION ALL SELECT 2,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',0,1,1
UNION ALL SELECT 3,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00001","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,0
UNION ALL SELECT 4,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,0
UNION ALL SELECT 5,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{',1,1,1
UNION ALL SELECT 6,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{',1,1,1
UNION ALL SELECT 7,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00001","timestamp":1790000000000,"sign":"invalid"}',1,1,1
UNION ALL SELECT 8,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":77,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"invalid"}',1,1,1
UNION ALL SELECT 9,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":78,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00001","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
UNION ALL SELECT 10,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111','100',1,NULL,'OK','{"pid":88,"cid":78,"chain_id":"2510","token_id":"0x55d398326f99059ff775485246999027b3197955","currency":"USDT","address":"0x1111111111111111111111111111111111111111","amount":"100","status":1,"txid":"0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","block_height":"101","nonce":"A00002","timestamp":1790000000000,"sign":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}',1,1,1
),
nx_cregis_deposit_event AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
UNION ALL SELECT 88,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',0,'0x1111111111111111111111111111111111111111','100',101,'CREDITED'
),
nx_cregis_chain_observation AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS CHAR(80)) AS raw_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
nx_withdrawal_order AS (
SELECT CAST(NULL AS DECIMAL(18,6)) AS amount,CAST(NULL AS CHAR(16)) AS asset,CAST(NULL AS SIGNED) AS is_deleted,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
fixture_actual(actual) AS (
WITH delivery_json AS (
  SELECT d.*, IF(JSON_VALID(raw_json),raw_json,'{}') AS payload
    FROM nx_cregis_deposit_delivery d
   WHERE accepted=1 AND (processed_at IS NULL OR reason IN
     ('PROVIDER_FAILED_HOLD','PROVIDER_MISMATCH_HOLD','CHAIN_MISMATCH_HOLD','PROVIDER_CONFLICT_HOLD'))
), delivery_snapshots AS (
  SELECT d.*,
         CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) AS UNSIGNED) END AS snapshot_block,
         CASE WHEN signature_valid=1 AND timestamp_valid=1 AND ip_valid=1
              AND JSON_VALID(raw_json)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.pid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.pid'))=CAST(88 AS CHAR)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.cid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.cid'))=CAST(cid AS CHAR)
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.chain_id'))='2510'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.token_id')))='0x55d398326f99059ff775485246999027b3197955'
              AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.currency'))) IN
                ('USDT','USDT-BEP20','2510@0X55D398326F99059FF775485246999027B3197955')
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status'))='1'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))) REGEXP '^(0x)?[0-9a-f]{64}$'
              AND CONCAT('0x',RIGHT(LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))),64))=LOWER(txid)
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.address')))=LOWER(address)
              AND gross_amount>0
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.nonce')) REGEXP '^[A-Za-z0-9]{6}$'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.sign')) REGEXP '^[0-9a-fA-F]{32}$'
              THEN 1 ELSE 0 END AS snapshot_valid
    FROM delivery_json d
), delivery_exposure AS (
  SELECT MAX(CASE WHEN gross_amount>0 THEN gross_amount END) AS amount
    FROM delivery_snapshots d
   WHERE snapshot_valid=0 OR (
     NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                 WHERE e.project_id=88 AND e.cid=d.cid
                   AND e.txid=d.txid AND e.address=d.address
                   AND e.gross_amount=d.gross_amount AND e.block_number=d.snapshot_block)
     AND NOT EXISTS(SELECT 1 FROM nx_cregis_chain_observation o
                     WHERE o.project_id=88 AND o.txid=d.txid AND o.address=d.address
                       AND CAST(o.raw_amount AS DECIMAL(18,6))=d.gross_amount
                       AND o.block_number=d.snapshot_block))
   GROUP BY CASE WHEN snapshot_valid=1 THEN 0 ELSE id END,
            cid,txid,address,gross_amount,snapshot_block
)
SELECT
  COALESCE((SELECT SUM(gross_amount) FROM nx_cregis_deposit_event
             WHERE project_id=88 AND status IN
             ('DUST_HOLD','REVIEW_HOLD','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')),0)
  + COALESCE((SELECT SUM(CAST(o.raw_amount AS DECIMAL(18,6)))
                FROM nx_cregis_chain_observation o
               WHERE o.project_id=88 AND o.status<>'MATCHED'
                 AND NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                                 WHERE e.project_id=o.project_id
                                   AND e.txid=o.txid AND e.log_index=o.log_index)),0)
  + (SELECT CASE WHEN COUNT(*)<>COUNT(amount) THEN NULL ELSE COALESCE(SUM(amount),0) END
       FROM delivery_exposure)
  + COALESCE((SELECT SUM(w.amount) FROM nx_withdrawal_order w
               WHERE w.asset='USDT' AND w.is_deleted=0
                 AND w.status NOT IN ('CONFIRMED','FAILED','REFUNDED','CANCELLED','REJECTED')),0)
)
SELECT 'unverified_receipts_remain_separate_exposure' AS test_case, 1000 AS expected, actual,
       actual <=> 1000 AS passed,
       (actual >= 500) <=> 1 AS threshold_passed
  FROM fixture_actual;

WITH nx_cregis_deposit_delivery AS (
SELECT CAST(NULL AS SIGNED) AS id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS accepted,CAST(NULL AS DATETIME) AS processed_at,CAST(NULL AS CHAR(64)) AS reason,CAST(NULL AS CHAR(2000)) AS raw_json,CAST(NULL AS SIGNED) AS signature_valid,CAST(NULL AS SIGNED) AS timestamp_valid,CAST(NULL AS SIGNED) AS ip_valid WHERE 0
UNION ALL SELECT 1,77,'0xaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa','0x1111111111111111111111111111111111111111',NULL,1,NULL,'OK','{',1,1,1
),
nx_cregis_deposit_event AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS SIGNED) AS cid,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS DECIMAL(18,6)) AS gross_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
nx_cregis_chain_observation AS (
SELECT CAST(NULL AS SIGNED) AS project_id,CAST(NULL AS CHAR(66)) AS txid,CAST(NULL AS SIGNED) AS log_index,CAST(NULL AS CHAR(64)) AS address,CAST(NULL AS CHAR(80)) AS raw_amount,CAST(NULL AS SIGNED) AS block_number,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
nx_withdrawal_order AS (
SELECT CAST(NULL AS DECIMAL(18,6)) AS amount,CAST(NULL AS CHAR(16)) AS asset,CAST(NULL AS SIGNED) AS is_deleted,CAST(NULL AS CHAR(64)) AS status WHERE 0
),
fixture_actual(actual) AS (
WITH delivery_json AS (
  SELECT d.*, IF(JSON_VALID(raw_json),raw_json,'{}') AS payload
    FROM nx_cregis_deposit_delivery d
   WHERE accepted=1 AND (processed_at IS NULL OR reason IN
     ('PROVIDER_FAILED_HOLD','PROVIDER_MISMATCH_HOLD','CHAIN_MISMATCH_HOLD','PROVIDER_CONFLICT_HOLD'))
), delivery_snapshots AS (
  SELECT d.*,
         CASE WHEN JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              THEN CAST(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) AS UNSIGNED) END AS snapshot_block,
         CASE WHEN signature_valid=1 AND timestamp_valid=1 AND ip_valid=1
              AND JSON_VALID(raw_json)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.pid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.pid'))=CAST(88 AS CHAR)
              AND JSON_TYPE(JSON_EXTRACT(payload,'$.cid'))='INTEGER'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.cid'))=CAST(cid AS CHAR)
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.chain_id'))='2510'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.token_id')))='0x55d398326f99059ff775485246999027b3197955'
              AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.currency'))) IN
                ('USDT','USDT-BEP20','2510@0X55D398326F99059FF775485246999027B3197955')
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.status'))='1'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.block_height')) REGEXP '^[0-9]{1,16}$'
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))) REGEXP '^(0x)?[0-9a-f]{64}$'
              AND CONCAT('0x',RIGHT(LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.txid'))),64))=LOWER(txid)
              AND LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.address')))=LOWER(address)
              AND gross_amount>0
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.nonce')) REGEXP '^[A-Za-z0-9]{6}$'
              AND JSON_UNQUOTE(JSON_EXTRACT(payload,'$.sign')) REGEXP '^[0-9a-fA-F]{32}$'
              THEN 1 ELSE 0 END AS snapshot_valid
    FROM delivery_json d
), delivery_exposure AS (
  SELECT MAX(CASE WHEN gross_amount>0 THEN gross_amount END) AS amount
    FROM delivery_snapshots d
   WHERE snapshot_valid=0 OR (
     NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                 WHERE e.project_id=88 AND e.cid=d.cid
                   AND e.txid=d.txid AND e.address=d.address
                   AND e.gross_amount=d.gross_amount AND e.block_number=d.snapshot_block)
     AND NOT EXISTS(SELECT 1 FROM nx_cregis_chain_observation o
                     WHERE o.project_id=88 AND o.txid=d.txid AND o.address=d.address
                       AND CAST(o.raw_amount AS DECIMAL(18,6))=d.gross_amount
                       AND o.block_number=d.snapshot_block))
   GROUP BY CASE WHEN snapshot_valid=1 THEN 0 ELSE id END,
            cid,txid,address,gross_amount,snapshot_block
)
SELECT
  COALESCE((SELECT SUM(gross_amount) FROM nx_cregis_deposit_event
             WHERE project_id=88 AND status IN
             ('DUST_HOLD','REVIEW_HOLD','REORG_INVESTIGATING','PROVIDER_CONFLICT_HOLD')),0)
  + COALESCE((SELECT SUM(CAST(o.raw_amount AS DECIMAL(18,6)))
                FROM nx_cregis_chain_observation o
               WHERE o.project_id=88 AND o.status<>'MATCHED'
                 AND NOT EXISTS(SELECT 1 FROM nx_cregis_deposit_event e
                                 WHERE e.project_id=o.project_id
                                   AND e.txid=o.txid AND e.log_index=o.log_index)),0)
  + (SELECT CASE WHEN COUNT(*)<>COUNT(amount) THEN NULL ELSE COALESCE(SUM(amount),0) END
       FROM delivery_exposure)
  + COALESCE((SELECT SUM(w.amount) FROM nx_withdrawal_order w
               WHERE w.asset='USDT' AND w.is_deleted=0
                 AND w.status NOT IN ('CONFIRMED','FAILED','REFUNDED','CANCELLED','REJECTED')),0)
)
SELECT 'unknown_accepted_amount_is_unavailable_not_zero' AS test_case, NULL AS expected, actual,
       actual <=> NULL AS passed,
       (actual >= 500) <=> NULL AS threshold_passed
  FROM fixture_actual;

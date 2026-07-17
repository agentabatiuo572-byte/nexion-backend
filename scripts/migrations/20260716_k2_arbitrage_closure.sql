-- K2 local closure: retire the deleted holding gate and add optimistic concurrency.
ALTER TABLE nx_admin_risk_arbitrage_param
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER note_text;

ALTER TABLE nx_admin_risk_arbitrage_row
    ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER disposition;

UPDATE nx_admin_risk_arbitrage_param
   SET is_deleted = 1,
       updated_at = NOW()
 WHERE param_key = 'minHoldingMonths'
   AND is_deleted = 0;

-- Only linked K1 freeze remains an A2 high-risk operation. The three signal /
-- preventive actions execute directly with exact authorities and required audit.
UPDATE nx_admin_permission
   SET permission_name = CASE permission_code
         WHEN 'risk_k2_row_flag' THEN '标记套利账户(直接预防性处置·进评分)'
         WHEN 'risk_k2_row_blockgift' THEN '拦截新人礼(直接预防性阻断)'
         WHEN 'risk_k2_row_boardflag' THEN '标记刷榜账户(直接预防性处置·产信号)'
       END,
       perm_type = 'WRITE',
       amplifies = 0
 WHERE permission_code IN ('risk_k2_row_flag', 'risk_k2_row_blockgift', 'risk_k2_row_boardflag');

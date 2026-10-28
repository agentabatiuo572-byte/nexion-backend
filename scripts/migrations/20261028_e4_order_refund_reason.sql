-- E4 退款原因：运营退款时必选一个用户可见原因，App 退款订单按原因码三语展示。
-- 运营手填的退款备注仍只进审计。历史退款与升级前提交的 A2 单保持 NULL，App 不显示原因行。
DROP PROCEDURE IF EXISTS add_e4_refund_reason_column;
DELIMITER $$
CREATE PROCEDURE add_e4_refund_reason_column()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_order' AND COLUMN_NAME='refund_reason_code'
  ) THEN
    ALTER TABLE nx_order ADD COLUMN refund_reason_code VARCHAR(32) NULL;
  END IF;
END$$
DELIMITER ;

CALL add_e4_refund_reason_column();
DROP PROCEDURE add_e4_refund_reason_column;

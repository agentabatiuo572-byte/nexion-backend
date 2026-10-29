-- E4 关闭原因：订单进入 已取消 / 过期 / 支付失败 / 开通失败 / 拒付 时记录一个用户可见原因码，App 订单详情三语展示。
-- 系统写入：App 用户取消 USER_CANCELLED，超时过期 PAYMENT_TIMEOUT；运营在 E4 取消/终结时按目标状态选择。
-- 历史订单与升级前提交的 A2 单保持 NULL，App 不显示原因行。
DROP PROCEDURE IF EXISTS add_e4_close_reason_column;
DELIMITER $$
CREATE PROCEDURE add_e4_close_reason_column()
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME='nx_order' AND COLUMN_NAME='close_reason_code'
  ) THEN
    ALTER TABLE nx_order ADD COLUMN close_reason_code VARCHAR(32) NULL;
  END IF;
END$$
DELIMITER ;

CALL add_e4_close_reason_column();
DROP PROCEDURE add_e4_close_reason_column;

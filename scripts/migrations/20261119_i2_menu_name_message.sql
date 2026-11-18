SET NAMES utf8mb4;
START TRANSACTION;

UPDATE nx_admin_menu
SET menu_name = '消息推送', menu_name_zh = '消息推送', updated_at = NOW()
WHERE is_deleted = 0
  AND menu_code IN ('I2', 'MENU_CONTENT_I2')
  AND (NOT (CAST(menu_name AS BINARY) <=> CAST('消息推送' AS BINARY))
    OR NOT (CAST(menu_name_zh AS BINARY) <=> CAST('消息推送' AS BINARY)));

COMMIT;
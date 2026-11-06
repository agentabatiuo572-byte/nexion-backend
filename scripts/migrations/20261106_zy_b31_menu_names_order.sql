SET NAMES utf8mb4;
START TRANSACTION;

UPDATE nx_admin_menu
SET sort_order = 14, updated_at = NOW()
WHERE menu_code = 'A' AND is_deleted = 0 AND sort_order <> 14;

UPDATE nx_admin_menu m
JOIN (
    SELECT 'A5' code, '平台参数目录' label
    UNION ALL SELECT 'B3', '用户转化概览'
    UNION ALL SELECT 'D1', '充值与收款对账'
    UNION ALL SELECT 'D2', '提现审核'
    UNION ALL SELECT 'D5', 'USDT 提现参数'
    UNION ALL SELECT 'D7', '越南盾提现参数'
    UNION ALL SELECT 'E6', '手机算力配置'
    UNION ALL SELECT 'G2', '兑换额度与规则'
    UNION ALL SELECT 'G3', 'UVT价格管理'
    UNION ALL SELECT 'H5', '签到与UVT奖励'
    UNION ALL SELECT 'H9', '首页与网络展示设置'
    UNION ALL SELECT 'I5', '法律条款与风险披露'
    UNION ALL SELECT 'MENU_CONTENT_I5', '法律条款与风险披露'
    UNION ALL SELECT 'I6', '多语言与课程'
    UNION ALL SELECT 'L4', '设备、任务与团队报表'
) names ON CAST(names.code AS BINARY) = CAST(m.menu_code AS BINARY)
SET m.menu_name = names.label, m.menu_name_zh = names.label, m.updated_at = NOW()
WHERE m.is_deleted = 0
  AND (NOT (CAST(m.menu_name AS BINARY) <=> CAST(names.label AS BINARY))
    OR NOT (CAST(m.menu_name_zh AS BINARY) <=> CAST(names.label AS BINARY)));

COMMIT;

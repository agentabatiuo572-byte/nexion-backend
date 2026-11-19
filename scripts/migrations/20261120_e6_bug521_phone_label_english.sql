-- BUG-521: 新手引导收益对比卡里手机那一行的名称来自
-- nx_onboarding_yield_comparison_config.label，经 GET /api/config/phone-tiers 的
-- comparisons[].label 原样下发。建库种子与已投递的 20260816 迁移写入的都是中文「手机」，
-- 英文、越南语用户因此可能读到中文。
--
-- 只把仍是旧默认「手机」的 phone 行改成英文 Phone：
--   * 运营手动写库改成其它值的 label 一律不动（WHERE label='手机'），
--   * 费率/排序/revision/active 等字段与名称消费者的读取方式不变，
--     手机档率配置(nx_onboarding_phone_tier_config)与 SKU/商品绑定也不改，
--   * 不重跑、不改写已投递的 20260816 迁移文件。
-- 通过 nx_schema_migration_state 只执行一次，避免每次启动把运营后来设置的 label 再改回去。

SET NAMES utf8mb4;

START TRANSACTION;

INSERT INTO nx_schema_migration_state (migration_key, phase)
VALUES ('20261120_e6_bug521_phone_label_english', 'PENDING')
ON DUPLICATE KEY UPDATE migration_key = migration_key;

UPDATE nx_onboarding_yield_comparison_config c
JOIN nx_schema_migration_state st
  ON st.migration_key = '20261120_e6_bug521_phone_label_english'
 AND st.phase = 'PENDING'
   SET c.label = 'Phone',
       c.updated_at = CURRENT_TIMESTAMP(6)
 WHERE c.config_key = 'phone'
   AND c.label = '手机';

UPDATE nx_schema_migration_state
   SET phase = 'FINAL'
 WHERE migration_key = '20261120_e6_bug521_phone_label_english'
   AND phase = 'PENDING';

COMMIT;

-- BUG-522: E3 置换资格 eligibility 原先存中文文本（全部用户 / L2+ 持有者 … L6+ 持有者），
-- 并原样出现在 App 的置换配置接口里。后端改为稳定代码存储与下发：
--   ALL / L2_PLUS / L3_PLUS / L4_PLUS / L5_PLUS / L6_PLUS
-- 后台(PC)既有的六档中文名继续由接口映射兼容，历史中文输入也仍可识别。
--
-- 只转换**已确认的原值**，并且按字节精确比较：库的默认排序规则 utf8mb4_0900_ai_ci 大小写不敏感，
-- 普通 =/IN/CASE 会把 'l4+ 持有者' 之类未识别文本也当成已知值；这里对过滤与映射都加 BINARY，
-- 未知、大小写变体或自定义值一律原样保留，不猜。
-- 已生效的历史快照(nx_sim_e3_version.config_json)不重写；读取侧对两代取值都能识别。

SET NAMES utf8mb4;

START TRANSACTION;

INSERT INTO nx_schema_migration_state (migration_key, phase)
VALUES ('20261120_e6_bug522_tradein_eligibility_codes', 'PENDING')
ON DUPLICATE KEY UPDATE migration_key = migration_key;

UPDATE nx_compute_e3_config c
JOIN nx_schema_migration_state st
  ON st.migration_key = '20261120_e6_bug522_tradein_eligibility_codes'
 AND st.phase = 'PENDING'
   SET c.config_value = CASE
         WHEN BINARY c.config_value = BINARY '全部用户' THEN 'ALL'
         WHEN BINARY c.config_value = BINARY 'L2+ 持有者' THEN 'L2_PLUS'
         WHEN BINARY c.config_value = BINARY 'L3+ 持有者' THEN 'L3_PLUS'
         WHEN BINARY c.config_value = BINARY 'L4+ 持有者' THEN 'L4_PLUS'
         WHEN BINARY c.config_value = BINARY 'L5+ 持有者' THEN 'L5_PLUS'
         WHEN BINARY c.config_value = BINARY 'L6+ 持有者' THEN 'L6_PLUS'
         ELSE c.config_value
       END,
       c.updated_at = CURRENT_TIMESTAMP(6)
 WHERE c.config_key = 'eligibility'
   AND BINARY c.config_value IN (
         BINARY '全部用户',
         BINARY 'L2+ 持有者',
         BINARY 'L3+ 持有者',
         BINARY 'L4+ 持有者',
         BINARY 'L5+ 持有者',
         BINARY 'L6+ 持有者');

UPDATE nx_schema_migration_state
   SET phase = 'FINAL'
 WHERE migration_key = '20261120_e6_bug522_tradein_eligibility_codes'
   AND phase = 'PENDING';

COMMIT;

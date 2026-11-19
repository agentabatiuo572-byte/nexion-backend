-- I4 launch section metadata and field schema from the July catalog.
-- Only new sections receive their first version and field rows.
-- Keep sections requiring source evidence as drafts with their original field schema.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TEMPORARY TABLE lc_trust_sections (
  section_key VARCHAR(64) PRIMARY KEY,
  description VARCHAR(255) NOT NULL,
  struct_text VARCHAR(255) NOT NULL,
  role_gate VARCHAR(64) NOT NULL,
  high_sensitivity TINYINT NOT NULL,
  sort_order INT NOT NULL,
  status VARCHAR(32) NOT NULL,
  seed_operator VARCHAR(64) NOT NULL,
  fields_json JSON NOT NULL
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

START TRANSACTION;

INSERT INTO lc_trust_sections
  (section_key, description, struct_text, role_gate, high_sensitivity, sort_order,
   status, seed_operator, fields_json)
SELECT metadata.section_key, metadata.description, metadata.struct_text,
       metadata.role_gate, metadata.high_sensitivity, metadata.sort_order,
       IF(metadata.section_key IN ('leadership', 'nexNarrative'), 'PUBLISHED', 'DRAFT'),
       IF(metadata.section_key IN ('leadership', 'nexNarrative'), 'migration', 'migration:lc-trust-v1'),
       content.fields_json
FROM (
  SELECT 'financials' section_key, '财务与储备数据' description, '指标卡片与数据源说明' struct_text, '合规 / 超管' role_gate, 1 high_sensitivity, 1 sort_order
  UNION ALL SELECT 'leadership', '核心团队与治理信息', '人员与职责列表', '内容运营', 0, 2
  UNION ALL SELECT 'nexNarrative', 'NEX 叙事与机制说明', '叙事摘要与事实依据', '合规 / 超管', 1, 3
  UNION ALL SELECT 'complianceBadges', '合规资质与徽章', '资质卡片与有效期', '合规 / 超管', 1, 4
  UNION ALL SELECT 'auditsReserves', '审计与储备证明', '审计报告与储备证明', '合规 / 超管', 1, 5
  UNION ALL SELECT 'listings', '上架与市场信息', '市场及上架状态列表', '内容运营', 0, 6
) metadata
JOIN (
  SELECT 'financials' section_key,
         '[{"key":"tvlOnChain","label":"链上锁仓价值","value":""},{"key":"mrrValue","label":"月经常性收入","value":""},{"key":"mrrDelta","label":"月经常性收入变化","value":""},{"key":"activeAccountsValue","label":"活跃账户","value":""},{"key":"activeAccountsDelta","label":"活跃账户变化","value":""},{"key":"devicesOnlineValue","label":"在线设备","value":""},{"key":"devicesOnlineDelta","label":"在线设备变化","value":""},{"key":"payoutsProcessedValue","label":"已处理兑付","value":""},{"key":"payoutsProcessedDelta","label":"已处理兑付变化","value":""},{"key":"footnote.vi","label":"越南语脚注","value":""},{"key":"footnote.zh","label":"中文脚注","value":""},{"key":"footnote.en","label":"英文脚注","value":""}]' fields_json
  UNION ALL SELECT 'leadership',
         '[{"key":"leader1Name","label":"负责人姓名","value":"Nexion Leadership Team"},{"key":"leader1Role","label":"负责人角色","value":"Executive Leadership"},{"key":"leader1Previous","label":"过往经历","value":"Fintech, compliance and infrastructure"},{"key":"leader1Url","label":"负责人详情","value":""}]'
  UNION ALL SELECT 'nexNarrative',
         '[{"key":"hero.vi","label":"越南语主标题","value":"NEX kết nối AI với hạ tầng kinh tế số."},{"key":"hero.zh","label":"中文主标题","value":"NEX 连接 AI 与数字经济基础设施。"},{"key":"hero.en","label":"英文主标题","value":"NEX connects AI with digital economic infrastructure."},{"key":"subhero.vi","label":"越南语副标题","value":"Minh bạch, có thể kiểm chứng và hướng đến tiện ích thực tế."},{"key":"subhero.zh","label":"中文副标题","value":"透明、可验证，并服务于真实使用场景。"},{"key":"subhero.en","label":"英文副标题","value":"Transparent, verifiable and built for real utility."}]'
  UNION ALL SELECT 'complianceBadges',
         '[{"key":"badge1Label","label":"资质名称","value":""},{"key":"badge1Body","label":"资质说明","value":""}]'
  UNION ALL SELECT 'auditsReserves',
         '[{"key":"document1Primary","label":"报告名称","value":""},{"key":"document1Secondary","label":"报告说明","value":""},{"key":"document1Url","label":"报告地址","value":""}]'
  UNION ALL SELECT 'listings',
         '[{"key":"listing1Exchange","label":"交易市场","value":""},{"key":"listing1State","label":"上架状态","value":""},{"key":"listing1Url","label":"市场地址","value":""}]'
) content ON content.section_key = metadata.section_key
WHERE NOT EXISTS (SELECT 1 FROM nx_trust_section s WHERE s.section_key = metadata.section_key)
  AND NOT EXISTS (SELECT 1 FROM nx_trust_section_version v WHERE v.section_key = metadata.section_key)
  AND NOT EXISTS (SELECT 1 FROM nx_trust_section_field f WHERE f.section_key = metadata.section_key);

INSERT INTO nx_trust_section
  (section_key, description, struct_text, version_label, status, role_gate, high_sensitivity,
   last_change, sort_order, last_operator, is_deleted)
SELECT section_key, description, struct_text, 'v1', status, role_gate, high_sensitivity,
       DATE_FORMAT(NOW(), '%m-%d'), sort_order, seed_operator, 0
FROM lc_trust_sections;

INSERT INTO nx_trust_section_version
  (section_key, version_label, description, struct_text, fields_json, status, revision,
   last_operator, is_deleted)
SELECT section_key, 'v1', description, struct_text, fields_json, status, 1, seed_operator, 0
FROM lc_trust_sections;

INSERT INTO nx_trust_section_field
  (section_key, field_key, field_value, field_delta, sort_order, last_operator, is_deleted)
SELECT section_row.section_key, fields.field_key, fields.field_value, fields.field_label,
       fields.field_order, section_row.seed_operator, 0
FROM lc_trust_sections section_row
CROSS JOIN JSON_TABLE(section_row.fields_json, '$[*]' COLUMNS (
  field_order FOR ORDINALITY,
  field_key VARCHAR(128) PATH '$.key',
  field_value VARCHAR(500) PATH '$.value',
  field_label VARCHAR(64) PATH '$.label'
)) fields;

COMMIT;

DROP TEMPORARY TABLE lc_trust_sections;

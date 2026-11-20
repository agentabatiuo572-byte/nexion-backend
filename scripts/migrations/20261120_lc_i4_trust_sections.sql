-- I4 launch section metadata and field schema from the July catalog.
-- Only new sections receive their first version and field rows.
-- Launch copy stays in draft until it passes the existing I4 approval flow.
SET NAMES utf8mb4 COLLATE utf8mb4_0900_ai_ci;

CREATE TEMPORARY TABLE lc_trust_sections (
  section_key VARCHAR(64) PRIMARY KEY,
  description VARCHAR(255) NOT NULL,
  struct_text VARCHAR(255) NOT NULL,
  role_gate VARCHAR(64) NOT NULL,
  high_sensitivity TINYINT NOT NULL,
  sort_order INT NOT NULL,
  fields_json JSON NOT NULL
) DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

START TRANSACTION;

INSERT INTO lc_trust_sections
  (section_key, description, struct_text, role_gate, high_sensitivity, sort_order, fields_json)
SELECT metadata.section_key, metadata.description, metadata.struct_text,
       metadata.role_gate, metadata.high_sensitivity, metadata.sort_order,
       content.fields_json
FROM (
  SELECT 'financials' section_key, '财务与储备数据' description, '指标卡片与数据源说明' struct_text, '合规 / 超管' role_gate, 1 high_sensitivity, 1 sort_order
  UNION ALL SELECT 'leadership', '核心团队与治理信息', '人员与职责列表', '内容运营', 0, 2
  UNION ALL SELECT 'nexNarrative', 'UVT 叙事与机制说明', '叙事摘要与事实依据', '合规 / 超管', 1, 3
  UNION ALL SELECT 'complianceBadges', '合规资质与徽章', '资质卡片与有效期', '合规 / 超管', 1, 4
  UNION ALL SELECT 'auditsReserves', '审计与储备证明', '审计报告与储备证明', '合规 / 超管', 1, 5
  UNION ALL SELECT 'listings', '上架与市场信息', '市场及上架状态列表', '内容运营', 0, 6
) metadata
JOIN (
  SELECT 'financials' section_key,
         '[{"key":"tvlOnChain","label":"链上锁仓价值","value":""},{"key":"mrrValue","label":"月经常性收入","value":""},{"key":"mrrDelta","label":"月经常性收入变化","value":""},{"key":"activeAccountsValue","label":"活跃账户","value":""},{"key":"activeAccountsDelta","label":"活跃账户变化","value":""},{"key":"devicesOnlineValue","label":"在线设备","value":""},{"key":"devicesOnlineDelta","label":"在线设备变化","value":""},{"key":"payoutsProcessedValue","label":"已处理兑付","value":""},{"key":"payoutsProcessedDelta","label":"已处理兑付变化","value":""},{"key":"footnote.vi","label":"越南语脚注","value":""},{"key":"footnote.zh","label":"中文脚注","value":""},{"key":"footnote.en","label":"英文脚注","value":""}]' fields_json
  UNION ALL SELECT 'leadership',
         '[{"key":"leader1Name","label":"负责人姓名","value":""},{"key":"leader1Role","label":"负责人角色","value":""},{"key":"leader1Previous","label":"过往经历","value":""},{"key":"leader1Url","label":"负责人详情","value":""}]'
  UNION ALL SELECT 'nexNarrative',
         JSON_ARRAY(
           JSON_OBJECT('key','hero.zh','label','主标题（中文）','value','UVT powers the UVEL compute network'),
           JSON_OBJECT('key','hero.vi','label','主标题（越南语）','value','UVT vận hành mạng lưới tính toán UVEL'),
           JSON_OBJECT('key','hero.en','label','主标题（英语）','value','UVT powers the UVEL compute network'),
           JSON_OBJECT('key','subhero.zh','label','副标题（中文）','value','The token of the UVEL hosted GPU compute network.'),
           JSON_OBJECT('key','subhero.vi','label','副标题（越南语）','value','Token của mạng lưới tính toán GPU UVEL đặt tại trung tâm dữ liệu.'),
           JSON_OBJECT('key','subhero.en','label','副标题（英语）','value','The token of the UVEL hosted GPU compute network.'),
           JSON_OBJECT('key','hostingContent.zh','label','设备托管说明（中文）','value','Every UVEL compute plan runs on real GPU hardware in our data centers. Each device is assigned to a data center at delivery, and we install, power and maintain it. You do not need to set up or ship any hardware. Device status and task activity are shown in the app.'),
           JSON_OBJECT('key','hostingContent.vi','label','设备托管说明（越南语）','value','Mỗi gói điện toán UVEL chạy trên phần cứng GPU thật tại các trung tâm dữ liệu của chúng tôi. Mỗi thiết bị được phân bổ vào một trung tâm dữ liệu khi giao, và chúng tôi lắp đặt, cấp điện, bảo trì. Bạn không cần cài đặt hay vận chuyển phần cứng. Trạng thái thiết bị và hoạt động nhiệm vụ hiển thị trong ứng dụng.'),
           JSON_OBJECT('key','hostingContent.en','label','设备托管说明（英语）','value','Every UVEL compute plan runs on real GPU hardware in our data centers. Each device is assigned to a data center at delivery, and we install, power and maintain it. You do not need to set up or ship any hardware. Device status and task activity are shown in the app.'),
           JSON_OBJECT('key','warrantyContent.zh','label','保修与售后说明（中文）','value','Hardware faults during the warranty period are repaired free of charge by our data center team. If a device needs repair, its status is updated in the app and support can help with any questions.'),
           JSON_OBJECT('key','warrantyContent.vi','label','保修与售后说明（越南语）','value','Lỗi phần cứng trong thời gian bảo hành được đội trung tâm dữ liệu sửa miễn phí. Khi thiết bị cần sửa chữa, trạng thái được cập nhật trong ứng dụng và bộ phận hỗ trợ sẵn sàng giải đáp.'),
           JSON_OBJECT('key','warrantyContent.en','label','保修与售后说明（英语）','value','Hardware faults during the warranty period are repaired free of charge by our data center team. If a device needs repair, its status is updated in the app and support can help with any questions.'),
           JSON_OBJECT('key','securityContent.zh','label','账户与资金安全说明（中文）','value','Withdrawals are protected by a payment password and risk checks. Larger withdrawals are reviewed manually. UVEL staff will never ask for your password or verification codes.'),
           JSON_OBJECT('key','securityContent.vi','label','账户与资金安全说明（越南语）','value','Lệnh rút được bảo vệ bằng mật khẩu thanh toán và kiểm tra rủi ro. Lệnh rút lớn được xem xét thủ công. Nhân viên UVEL không bao giờ hỏi mật khẩu hay mã xác minh của bạn.'),
           JSON_OBJECT('key','securityContent.en','label','账户与资金安全说明（英语）','value','Withdrawals are protected by a payment password and risk checks. Larger withdrawals are reviewed manually. UVEL staff will never ask for your password or verification codes.'))
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
SELECT section_key, description, struct_text, 'v1', 'DRAFT', role_gate, high_sensitivity,
       DATE_FORMAT(NOW(), '%m-%d'), sort_order, 'migration:lc-trust-v1', 0
FROM lc_trust_sections;

INSERT INTO nx_trust_section_version
  (section_key, version_label, description, struct_text, fields_json, status, revision,
   last_operator, is_deleted)
SELECT section_key, 'v1', description, struct_text, fields_json, 'DRAFT', 1, 'migration:lc-trust-v1', 0
FROM lc_trust_sections;

INSERT INTO nx_trust_section_field
  (section_key, field_key, field_value, field_delta, sort_order, last_operator, is_deleted)
SELECT section_row.section_key, fields.field_key, fields.field_value, fields.field_label,
       fields.field_order, 'migration:lc-trust-v1', 0
FROM lc_trust_sections section_row
CROSS JOIN JSON_TABLE(section_row.fields_json, '$[*]' COLUMNS (
  field_order FOR ORDINALITY,
  field_key VARCHAR(128) PATH '$.key',
  field_value VARCHAR(500) PATH '$.value',
  field_label VARCHAR(64) PATH '$.label'
)) fields;

COMMIT;

DROP TEMPORARY TABLE lc_trust_sections;

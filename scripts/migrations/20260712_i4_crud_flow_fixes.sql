START TRANSACTION;

-- I4 发布不是“勾选即双语”：补齐迁移自带的 v1 中越字段，人工版本绝不覆盖。
SET @leadership_fields = '[{"key":"leader1Name","label":"负责人姓名","value":"Nexion Leadership Team"},{"key":"leader1Role.zh","label":"负责人角色（中文）","value":"执行领导团队"},{"key":"leader1Role.vi","label":"负责人角色（越南语）","value":"Ban lãnh đạo điều hành"},{"key":"leader1Role.en","label":"负责人角色（英语）","value":"Executive Leadership"},{"key":"leader1Previous.zh","label":"过往经历（中文）","value":"金融科技、合规与基础设施"},{"key":"leader1Previous.vi","label":"过往经历（越南语）","value":"Công nghệ tài chính, tuân thủ và hạ tầng"},{"key":"leader1Previous.en","label":"过往经历（英语）","value":"Fintech, compliance and infrastructure"},{"key":"leader1Url","label":"负责人详情","value":""}]';
UPDATE nx_trust_section_version
SET updated_at = IF(BINARY fields_json = BINARY @leadership_fields, updated_at, NOW()),
    fields_json = @leadership_fields
WHERE section_key = 'leadership' AND version_label = 'v1' AND status = 'PUBLISHED'
  AND last_operator = 'migration' AND is_deleted = 0;

SET @compliance_fields = '[{"key":"badge1Label","label":"资质名称","value":"合规运营体系"},{"key":"badge1Body.zh","label":"资质说明（中文）","value":"资质与适用范围以当前有效监管文件为准。"},{"key":"badge1Body.vi","label":"资质说明（越南语）","value":"Phạm vi chứng nhận tuân theo văn bản quản lý còn hiệu lực."},{"key":"badge1Body.en","label":"资质说明（英语）","value":"Scope is governed by currently effective regulatory documents."}]';
UPDATE nx_trust_section_version
SET updated_at = IF(BINARY fields_json = BINARY @compliance_fields, updated_at, NOW()),
    fields_json = @compliance_fields
WHERE section_key = 'complianceBadges' AND version_label = 'v1' AND status = 'PUBLISHED'
  AND last_operator = 'migration' AND is_deleted = 0;

SET @audits_fields = '[{"key":"document1Primary.zh","label":"报告名称（中文）","value":"储备与资金审计报告"},{"key":"document1Primary.vi","label":"报告名称（越南语）","value":"Báo cáo kiểm toán dự trữ và nguồn vốn"},{"key":"document1Primary.en","label":"报告名称（英语）","value":"Reserve and Funds Audit Report"},{"key":"document1Secondary.zh","label":"报告说明（中文）","value":"最新已发布审计周期"},{"key":"document1Secondary.vi","label":"报告说明（越南语）","value":"Kỳ kiểm toán được công bố mới nhất"},{"key":"document1Secondary.en","label":"报告说明（英语）","value":"Latest published audit period"},{"key":"document1Url","label":"报告地址","value":""}]';
UPDATE nx_trust_section_version
SET updated_at = IF(BINARY fields_json = BINARY @audits_fields, updated_at, NOW()),
    fields_json = @audits_fields
WHERE section_key = 'auditsReserves' AND version_label = 'v1' AND status = 'PUBLISHED'
  AND last_operator = 'migration' AND is_deleted = 0;

SET @listings_fields = '[{"key":"listing1Exchange","label":"交易市场","value":"NEX Market"},{"key":"listing1State.zh","label":"上架状态（中文）","value":"已上架"},{"key":"listing1State.vi","label":"上架状态（越南语）","value":"Đang niêm yết"},{"key":"listing1State.en","label":"上架状态（英语）","value":"ACTIVE"},{"key":"listing1Url","label":"市场地址","value":"/pages/market/market"}]';
UPDATE nx_trust_section_version
SET updated_at = IF(BINARY fields_json = BINARY @listings_fields, updated_at, NOW()),
    fields_json = @listings_fields
WHERE section_key = 'listings' AND version_label = 'v1' AND status = 'PUBLISHED'
  AND last_operator = 'migration' AND is_deleted = 0;

INSERT INTO nx_trust_section_field
  (section_key, field_key, field_value, field_delta, sort_order, last_operator, created_at, updated_at, is_deleted)
VALUES
  ('leadership', 'leader1Role.zh', '执行领导团队', '负责人角色（中文）', 20, 'migration', NOW(), NOW(), 0),
  ('leadership', 'leader1Role.vi', 'Ban lãnh đạo điều hành', '负责人角色（越南语）', 30, 'migration', NOW(), NOW(), 0),
  ('leadership', 'leader1Role.en', 'Executive Leadership', '负责人角色（英语）', 40, 'migration', NOW(), NOW(), 0),
  ('leadership', 'leader1Previous.zh', '金融科技、合规与基础设施', '过往经历（中文）', 50, 'migration', NOW(), NOW(), 0),
  ('leadership', 'leader1Previous.vi', 'Công nghệ tài chính, tuân thủ và hạ tầng', '过往经历（越南语）', 60, 'migration', NOW(), NOW(), 0),
  ('leadership', 'leader1Previous.en', 'Fintech, compliance and infrastructure', '过往经历（英语）', 70, 'migration', NOW(), NOW(), 0),
  ('complianceBadges', 'badge1Body.zh', '资质与适用范围以当前有效监管文件为准。', '资质说明（中文）', 20, 'migration', NOW(), NOW(), 0),
  ('complianceBadges', 'badge1Body.vi', 'Phạm vi chứng nhận tuân theo văn bản quản lý còn hiệu lực.', '资质说明（越南语）', 30, 'migration', NOW(), NOW(), 0),
  ('complianceBadges', 'badge1Body.en', 'Scope is governed by currently effective regulatory documents.', '资质说明（英语）', 40, 'migration', NOW(), NOW(), 0),
  ('auditsReserves', 'document1Primary.zh', '储备与资金审计报告', '报告名称（中文）', 10, 'migration', NOW(), NOW(), 0),
  ('auditsReserves', 'document1Primary.vi', 'Báo cáo kiểm toán dự trữ và nguồn vốn', '报告名称（越南语）', 20, 'migration', NOW(), NOW(), 0),
  ('auditsReserves', 'document1Primary.en', 'Reserve and Funds Audit Report', '报告名称（英语）', 30, 'migration', NOW(), NOW(), 0),
  ('auditsReserves', 'document1Secondary.zh', '最新已发布审计周期', '报告说明（中文）', 40, 'migration', NOW(), NOW(), 0),
  ('auditsReserves', 'document1Secondary.vi', 'Kỳ kiểm toán được công bố mới nhất', '报告说明（越南语）', 50, 'migration', NOW(), NOW(), 0),
  ('auditsReserves', 'document1Secondary.en', 'Latest published audit period', '报告说明（英语）', 60, 'migration', NOW(), NOW(), 0),
  ('listings', 'listing1State.zh', '已上架', '上架状态（中文）', 20, 'migration', NOW(), NOW(), 0),
  ('listings', 'listing1State.vi', 'Đang niêm yết', '上架状态（越南语）', 30, 'migration', NOW(), NOW(), 0),
  ('listings', 'listing1State.en', 'ACTIVE', '上架状态（英语）', 40, 'migration', NOW(), NOW(), 0)
ON DUPLICATE KEY UPDATE
  updated_at = IF(nx_trust_section_field.last_operator = 'migration' AND nx_trust_section_field.is_deleted = 0
                  AND (nx_trust_section_field.field_value <> VALUES(field_value)
                       OR NOT (nx_trust_section_field.field_delta <=> VALUES(field_delta))
                       OR nx_trust_section_field.sort_order <> VALUES(sort_order)),
                  NOW(), nx_trust_section_field.updated_at),
  field_value = IF(nx_trust_section_field.last_operator = 'migration' AND nx_trust_section_field.is_deleted = 0, VALUES(field_value), nx_trust_section_field.field_value),
  field_delta = IF(nx_trust_section_field.last_operator = 'migration' AND nx_trust_section_field.is_deleted = 0, VALUES(field_delta), nx_trust_section_field.field_delta),
  sort_order = IF(nx_trust_section_field.last_operator = 'migration' AND nx_trust_section_field.is_deleted = 0, VALUES(sort_order), nx_trust_section_field.sort_order),
  is_deleted = nx_trust_section_field.is_deleted;

COMMIT;

START TRANSACTION;

-- The public App snapshot and the I4 editor share these canonical business rows.
-- Upgrade only the untouched migration-owned v1 snapshot; later PC-published
-- versions are authoritative and must never be overwritten by startup.
SET @homepage_trust_operator = 'migration:homepage-trust-v2';
SET @compliance_fields = '[{"key":"badge1Label","label":"徽章 1","value":"NVIDIA"},{"key":"badge1Body.zh","label":"徽章说明（中文）","value":"资质与适用范围以当前有效监管文件为准。"},{"key":"badge1Body.vi","label":"Mô tả huy hiệu","value":"Phạm vi chứng nhận tuân theo văn bản quản lý còn hiệu lực."},{"key":"badge1Body.en","label":"Badge description","value":"Scope is governed by currently effective regulatory documents."},{"key":"badge2Label","label":"徽章 2","value":"Intel"},{"key":"badge3Label","label":"徽章 3","value":"AMD"},{"key":"badge4Label","label":"徽章 4","value":"CertiK ✓"},{"key":"badge5Label","label":"徽章 5","value":"SOC 2"},{"key":"badge6Label","label":"徽章 6","value":"GDPR"},{"key":"badge7Label","label":"徽章 7","value":"ISO 27001"}]';
SET @audits_fields = '[{"key":"document1Primary.zh","label":"报告名称（中文）","value":"储备与资金审计报告"},{"key":"document1Primary.vi","label":"Tên báo cáo","value":"Báo cáo kiểm toán dự trữ và nguồn vốn"},{"key":"document1Primary.en","label":"Report name","value":"Reserve and Funds Audit Report"},{"key":"document1Secondary.zh","label":"报告说明（中文）","value":"最新已发布审计周期"},{"key":"document1Secondary.vi","label":"Mô tả báo cáo","value":"Kỳ kiểm toán được công bố mới nhất"},{"key":"document1Secondary.en","label":"Report description","value":"Latest published audit period"},{"key":"document1Url","label":"报告地址","value":""},{"key":"homepageProof.zh","label":"首页储备证明（中文）","value":"储备链上证明 · 102.4% 超额储备 · 信任中心 →"},{"key":"homepageProof.vi","label":"Bằng chứng dự trữ trang chủ","value":"Bằng chứng dự trữ on-chain · bảo chứng 102.4% · Trung tâm tin cậy →"},{"key":"homepageProof.en","label":"Homepage reserve proof","value":"Reserve proof on-chain · 102.4% backed · Trust Center →"}]';

SET @upgrade_compliance = (
  SELECT IF(COUNT(*) = 1, 1, 0)
  FROM nx_trust_section section_row
  JOIN nx_trust_section_version version_row
    ON version_row.section_key COLLATE utf8mb4_unicode_ci = section_row.section_key COLLATE utf8mb4_unicode_ci
   AND version_row.version_label COLLATE utf8mb4_unicode_ci = section_row.version_label COLLATE utf8mb4_unicode_ci
   AND version_row.is_deleted = 0
  WHERE section_row.section_key = 'complianceBadges'
    AND section_row.version_label = 'v1'
    AND section_row.status = 'PUBLISHED'
    AND section_row.last_operator = 'migration'
    AND section_row.is_deleted = 0
    AND version_row.status = 'PUBLISHED'
    AND version_row.last_operator = 'migration'
    AND NOT EXISTS (
      SELECT 1 FROM nx_trust_section_version existing
      WHERE existing.section_key = 'complianceBadges'
        AND existing.version_label = 'v2'
        AND existing.is_deleted = 0
    )
);

SET @upgrade_audits = (
  SELECT IF(COUNT(*) = 1, 1, 0)
  FROM nx_trust_section section_row
  JOIN nx_trust_section_version version_row
    ON version_row.section_key COLLATE utf8mb4_unicode_ci = section_row.section_key COLLATE utf8mb4_unicode_ci
   AND version_row.version_label COLLATE utf8mb4_unicode_ci = section_row.version_label COLLATE utf8mb4_unicode_ci
   AND version_row.is_deleted = 0
  WHERE section_row.section_key = 'auditsReserves'
    AND section_row.version_label = 'v1'
    AND section_row.status = 'PUBLISHED'
    AND section_row.last_operator = 'migration'
    AND section_row.is_deleted = 0
    AND version_row.status = 'PUBLISHED'
    AND version_row.last_operator = 'migration'
    AND NOT EXISTS (
      SELECT 1 FROM nx_trust_section_version existing
      WHERE existing.section_key = 'auditsReserves'
        AND existing.version_label = 'v2'
        AND existing.is_deleted = 0
    )
);

INSERT INTO nx_trust_section_version
  (section_key, version_label, description, struct_text, fields_json, status, revision,
   last_operator, created_at, updated_at, is_deleted)
SELECT 'complianceBadges', 'v2', '首页 Trust 专项徽章', '七项合作与合规徽章',
       @compliance_fields, 'PUBLISHED', 1, @homepage_trust_operator, NOW(), NOW(), 0
WHERE @upgrade_compliance = 1;

INSERT INTO nx_trust_section_version
  (section_key, version_label, description, struct_text, fields_json, status, revision,
   last_operator, created_at, updated_at, is_deleted)
SELECT 'auditsReserves', 'v2', '审计与储备证明', '审计报告与首页储备证明',
       @audits_fields, 'PUBLISHED', 1, @homepage_trust_operator, NOW(), NOW(), 0
WHERE @upgrade_audits = 1;

UPDATE nx_trust_section_version
SET status = 'SUPERSEDED', updated_at = NOW()
WHERE @upgrade_compliance = 1
  AND section_key = 'complianceBadges' AND version_label = 'v1'
  AND status = 'PUBLISHED' AND last_operator = 'migration' AND is_deleted = 0;

UPDATE nx_trust_section_version
SET status = 'SUPERSEDED', updated_at = NOW()
WHERE @upgrade_audits = 1
  AND section_key = 'auditsReserves' AND version_label = 'v1'
  AND status = 'PUBLISHED' AND last_operator = 'migration' AND is_deleted = 0;

UPDATE nx_trust_section
SET description = '首页 Trust 专项徽章', struct_text = '七项合作与合规徽章',
    version_label = 'v2', status = 'PUBLISHED', last_change = DATE_FORMAT(NOW(), '%m-%d'),
    last_operator = @homepage_trust_operator, updated_at = NOW()
WHERE @upgrade_compliance = 1
  AND section_key = 'complianceBadges' AND version_label = 'v1'
  AND status = 'PUBLISHED' AND last_operator = 'migration' AND is_deleted = 0;

UPDATE nx_trust_section
SET description = '审计与储备证明', struct_text = '审计报告与首页储备证明',
    version_label = 'v2', status = 'PUBLISHED', last_change = DATE_FORMAT(NOW(), '%m-%d'),
    last_operator = @homepage_trust_operator, updated_at = NOW()
WHERE @upgrade_audits = 1
  AND section_key = 'auditsReserves' AND version_label = 'v1'
  AND status = 'PUBLISHED' AND last_operator = 'migration' AND is_deleted = 0;

UPDATE nx_trust_section_field
SET is_deleted = 1, last_operator = @homepage_trust_operator, updated_at = NOW()
WHERE @upgrade_compliance = 1 AND section_key = 'complianceBadges' AND is_deleted = 0;

INSERT INTO nx_trust_section_field
  (section_key, field_key, field_value, field_delta, sort_order, last_operator, created_at, updated_at, is_deleted)
SELECT 'complianceBadges', seed.field_key, seed.field_value, seed.field_label,
       seed.sort_order, @homepage_trust_operator, NOW(), NOW(), 0
FROM (
  SELECT 'badge1Label' field_key, 'NVIDIA' field_value, '徽章 1' field_label, 10 sort_order
  UNION ALL SELECT 'badge1Body.zh', '资质与适用范围以当前有效监管文件为准。', '徽章说明（中文）', 20
  UNION ALL SELECT 'badge1Body.vi', 'Phạm vi chứng nhận tuân theo văn bản quản lý còn hiệu lực.', 'Mô tả huy hiệu', 30
  UNION ALL SELECT 'badge1Body.en', 'Scope is governed by currently effective regulatory documents.', 'Badge description', 40
  UNION ALL SELECT 'badge2Label', 'Intel', '徽章 2', 50
  UNION ALL SELECT 'badge3Label', 'AMD', '徽章 3', 60
  UNION ALL SELECT 'badge4Label', 'CertiK ✓', '徽章 4', 70
  UNION ALL SELECT 'badge5Label', 'SOC 2', '徽章 5', 80
  UNION ALL SELECT 'badge6Label', 'GDPR', '徽章 6', 90
  UNION ALL SELECT 'badge7Label', 'ISO 27001', '徽章 7', 100
) seed
WHERE @upgrade_compliance = 1
ON DUPLICATE KEY UPDATE
  field_value = VALUES(field_value), field_delta = VALUES(field_delta), sort_order = VALUES(sort_order),
  last_operator = VALUES(last_operator), updated_at = NOW(), is_deleted = 0;

UPDATE nx_trust_section_field
SET is_deleted = 1, last_operator = @homepage_trust_operator, updated_at = NOW()
WHERE @upgrade_audits = 1 AND section_key = 'auditsReserves' AND is_deleted = 0;

INSERT INTO nx_trust_section_field
  (section_key, field_key, field_value, field_delta, sort_order, last_operator, created_at, updated_at, is_deleted)
SELECT 'auditsReserves', seed.field_key, seed.field_value, seed.field_label,
       seed.sort_order, @homepage_trust_operator, NOW(), NOW(), 0
FROM (
  SELECT 'document1Primary.zh' field_key, '储备与资金审计报告' field_value, '报告名称（中文）' field_label, 10 sort_order
  UNION ALL SELECT 'document1Primary.vi', 'Báo cáo kiểm toán dự trữ và nguồn vốn', 'Tên báo cáo', 20
  UNION ALL SELECT 'document1Primary.en', 'Reserve and Funds Audit Report', 'Report name', 30
  UNION ALL SELECT 'document1Secondary.zh', '最新已发布审计周期', '报告说明（中文）', 40
  UNION ALL SELECT 'document1Secondary.vi', 'Kỳ kiểm toán được công bố mới nhất', 'Mô tả báo cáo', 50
  UNION ALL SELECT 'document1Secondary.en', 'Latest published audit period', 'Report description', 60
  UNION ALL SELECT 'document1Url', '', '报告地址', 70
  UNION ALL SELECT 'homepageProof.zh', '储备链上证明 · 102.4% 超额储备 · 信任中心 →', '首页储备证明（中文）', 80
  UNION ALL SELECT 'homepageProof.vi', 'Bằng chứng dự trữ on-chain · bảo chứng 102.4% · Trung tâm tin cậy →', 'Bằng chứng dự trữ trang chủ', 90
  UNION ALL SELECT 'homepageProof.en', 'Reserve proof on-chain · 102.4% backed · Trust Center →', 'Homepage reserve proof', 100
) seed
WHERE @upgrade_audits = 1
ON DUPLICATE KEY UPDATE
  field_value = VALUES(field_value), field_delta = VALUES(field_delta), sort_order = VALUES(sort_order),
  last_operator = VALUES(last_operator), updated_at = NOW(), is_deleted = 0;

COMMIT;

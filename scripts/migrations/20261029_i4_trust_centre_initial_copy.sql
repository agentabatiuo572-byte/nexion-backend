START TRANSACTION;

-- Initial trust-centre copy for the redesigned App page: the chief executive named in the New York
-- filing (no prior roles are claimed) and the device hosting, warranty and account-security notes,
-- which the App reads from optional nexNarrative fields. The I4 editor keeps a published field
-- schema fixed, so these keys can only be introduced here. Only the untouched migration-owned v1
-- snapshots are upgraded; versions published from the console are authoritative.
SET @trust_centre_operator = 'migration:trust-centre-v2';
SET @leadership_fields = '[{"key":"leader1Name","label":"负责人姓名","value":"Jordan Nicholas Parkhurst"},{"key":"leader1Role.zh","label":"负责人角色（中文）","value":"总裁兼首席执行官 · President & CEO"},{"key":"leader1Role.vi","label":"负责人角色（越南语）","value":"Chủ tịch kiêm Tổng giám đốc điều hành"},{"key":"leader1Role.en","label":"负责人角色（英语）","value":"President & Chief Executive Officer"},{"key":"leader1Url","label":"负责人详情","value":"https://apps.dos.ny.gov/publicInquiry/"}]';
SET @narrative_additions = '[{"key":"hostingContent.zh","label":"设备托管说明（中文）","value":"Uvel Node 与 Uvel Rack 部署在专业数据中心，运行环境、网络配置和现场维护统一管理，无需寄送实体设备。购买后在 App 中激活和管理设备，订单与设备记录随时可查。"},{"key":"hostingContent.vi","label":"设备托管说明（越南语）","value":"Uvel Node và Uvel Rack được đặt tại trung tâm dữ liệu chuyên nghiệp; môi trường vận hành, mạng và bảo trì tại chỗ đều được quản lý tập trung, không cần giao thiết bị vật lý. Bạn kích hoạt và quản lý thiết bị trong App, hồ sơ đơn hàng và thiết bị luôn sẵn sàng tra cứu."},{"key":"hostingContent.en","label":"设备托管说明（英语）","value":"Uvel Node and Uvel Rack run in professional data centres, where the operating environment, network and on-site maintenance are managed for you; nothing is shipped. Activate and manage devices in the App, with order and device records always available."},{"key":"warrantyContent.zh","label":"保修与售后说明（中文）","value":"设备故障、更换或重新配置、退款均按下单时的服务条款处理。遇到问题时，在 App 内联系专属客服并提供订单号，处理进度全程可查。"},{"key":"warrantyContent.vi","label":"保修与售后说明（越南语）","value":"Sự cố thiết bị, thay thế hoặc cấu hình lại và hoàn tiền được xử lý theo điều khoản dịch vụ khi đặt hàng. Khi cần hỗ trợ, hãy liên hệ chuyên viên riêng trong App kèm mã đơn hàng và theo dõi tiến độ xử lý."},{"key":"warrantyContent.en","label":"保修与售后说明（英语）","value":"Faults, replacements or reconfiguration and refunds follow the service terms agreed when you ordered. Contact your dedicated advisor in the App with the order number and follow progress at every step."},{"key":"securityContent.zh","label":"账户与资金安全说明（中文）","value":"参与算力任务无需提供钱包私钥或助记词。登录、修改收款地址、提现等重要操作都需要身份确认并留有操作记录；处理中、锁定与可用资金分开显示。"},{"key":"securityContent.vi","label":"账户与资金安全说明（越南语）","value":"Tham gia tác vụ điện toán không bao giờ yêu cầu khóa riêng tư hay cụm từ khôi phục ví. Đăng nhập, thay đổi địa chỉ nhận và rút tiền đều cần xác minh danh tính và được ghi nhật ký; số tiền đang xử lý, bị khóa và khả dụng được hiển thị riêng."},{"key":"securityContent.en","label":"账户与资金安全说明（英语）","value":"Taking part in compute tasks never requires your wallet private key or recovery phrase. Sign-in, receiving-address changes and withdrawals all require identity confirmation and are recorded; funds in progress, locked and available are shown separately."}]';

SET @upgrade_leadership = (
  SELECT IF(COUNT(*) = 1, 1, 0)
  FROM nx_trust_section section_row
  JOIN nx_trust_section_version version_row
    ON version_row.section_key COLLATE utf8mb4_unicode_ci = section_row.section_key COLLATE utf8mb4_unicode_ci
   AND version_row.version_label COLLATE utf8mb4_unicode_ci = section_row.version_label COLLATE utf8mb4_unicode_ci
   AND version_row.is_deleted = 0
  WHERE section_row.section_key = 'leadership'
    AND section_row.version_label = 'v1'
    AND section_row.status = 'PUBLISHED'
    AND section_row.last_operator = 'migration'
    AND section_row.is_deleted = 0
    AND version_row.status = 'PUBLISHED'
    AND version_row.last_operator = 'migration'
    AND NOT EXISTS (
      SELECT 1 FROM nx_trust_section_version existing
      WHERE existing.section_key = 'leadership'
        AND existing.version_label = 'v2'
        AND existing.is_deleted = 0
    )
);

SET @upgrade_narrative = (
  SELECT IF(COUNT(*) = 1, 1, 0)
  FROM nx_trust_section section_row
  JOIN nx_trust_section_version version_row
    ON version_row.section_key COLLATE utf8mb4_unicode_ci = section_row.section_key COLLATE utf8mb4_unicode_ci
   AND version_row.version_label COLLATE utf8mb4_unicode_ci = section_row.version_label COLLATE utf8mb4_unicode_ci
   AND version_row.is_deleted = 0
  WHERE section_row.section_key = 'nexNarrative'
    AND section_row.version_label = 'v1'
    AND section_row.status = 'PUBLISHED'
    AND section_row.last_operator = 'migration'
    AND section_row.is_deleted = 0
    AND version_row.status = 'PUBLISHED'
    AND version_row.last_operator = 'migration'
    AND NOT EXISTS (
      SELECT 1 FROM nx_trust_section_version existing
      WHERE existing.section_key = 'nexNarrative'
        AND existing.version_label = 'v2'
        AND existing.is_deleted = 0
    )
);

INSERT INTO nx_trust_section_version
  (section_key, version_label, description, struct_text, fields_json, status, revision,
   last_operator, created_at, updated_at, is_deleted)
SELECT section_key, 'v2', description, struct_text, @leadership_fields, 'PUBLISHED', 1,
       @trust_centre_operator, NOW(), NOW(), 0
FROM nx_trust_section
WHERE @upgrade_leadership = 1 AND section_key = 'leadership' AND is_deleted = 0;

-- The narrative keeps every v1 field and appends the three service notes.
INSERT INTO nx_trust_section_version
  (section_key, version_label, description, struct_text, fields_json, status, revision,
   last_operator, created_at, updated_at, is_deleted)
SELECT version_row.section_key, 'v2', version_row.description, version_row.struct_text,
       JSON_MERGE_PRESERVE(version_row.fields_json, CAST(@narrative_additions AS JSON)), 'PUBLISHED', 1,
       @trust_centre_operator, NOW(), NOW(), 0
FROM nx_trust_section_version version_row
WHERE @upgrade_narrative = 1 AND version_row.section_key = 'nexNarrative'
  AND version_row.version_label = 'v1' AND version_row.status = 'PUBLISHED'
  AND version_row.last_operator = 'migration' AND version_row.is_deleted = 0;

UPDATE nx_trust_section_version
SET status = 'SUPERSEDED', updated_at = NOW()
WHERE @upgrade_leadership = 1
  AND section_key = 'leadership' AND version_label = 'v1'
  AND status = 'PUBLISHED' AND last_operator = 'migration' AND is_deleted = 0;

UPDATE nx_trust_section
SET version_label = 'v2', status = 'PUBLISHED', last_change = DATE_FORMAT(NOW(), '%m-%d'),
    last_operator = @trust_centre_operator, updated_at = NOW()
WHERE @upgrade_leadership = 1
  AND section_key = 'leadership' AND version_label = 'v1'
  AND status = 'PUBLISHED' AND last_operator = 'migration' AND is_deleted = 0;

UPDATE nx_trust_section_version
SET status = 'SUPERSEDED', updated_at = NOW()
WHERE @upgrade_narrative = 1
  AND section_key = 'nexNarrative' AND version_label = 'v1'
  AND status = 'PUBLISHED' AND last_operator = 'migration' AND is_deleted = 0;

UPDATE nx_trust_section
SET version_label = 'v2', status = 'PUBLISHED', last_change = DATE_FORMAT(NOW(), '%m-%d'),
    last_operator = @trust_centre_operator, updated_at = NOW()
WHERE @upgrade_narrative = 1
  AND section_key = 'nexNarrative' AND version_label = 'v1'
  AND status = 'PUBLISHED' AND last_operator = 'migration' AND is_deleted = 0;

UPDATE nx_trust_section_field
SET is_deleted = 1, last_operator = @trust_centre_operator, updated_at = NOW()
WHERE @upgrade_leadership = 1 AND section_key = 'leadership' AND is_deleted = 0;

INSERT INTO nx_trust_section_field
  (section_key, field_key, field_value, field_delta, sort_order, last_operator, created_at, updated_at, is_deleted)
SELECT 'leadership', seed.field_key, seed.field_value, seed.field_label,
       seed.sort_order, @trust_centre_operator, NOW(), NOW(), 0
FROM (
  SELECT 'leader1Name' field_key, 'Jordan Nicholas Parkhurst' field_value, '负责人姓名' field_label, 10 sort_order
  UNION ALL SELECT 'leader1Role.zh', '总裁兼首席执行官 · President & CEO', '负责人角色（中文）', 20
  UNION ALL SELECT 'leader1Role.vi', 'Chủ tịch kiêm Tổng giám đốc điều hành', '负责人角色（越南语）', 30
  UNION ALL SELECT 'leader1Role.en', 'President & Chief Executive Officer', '负责人角色（英语）', 40
  UNION ALL SELECT 'leader1Url', 'https://apps.dos.ny.gov/publicInquiry/', '负责人详情', 80
) seed
WHERE @upgrade_leadership = 1
ON DUPLICATE KEY UPDATE
  field_value = VALUES(field_value), field_delta = VALUES(field_delta), sort_order = VALUES(sort_order),
  last_operator = VALUES(last_operator), updated_at = NOW(), is_deleted = 0;

INSERT INTO nx_trust_section_field
  (section_key, field_key, field_value, field_delta, sort_order, last_operator, created_at, updated_at, is_deleted)
SELECT 'nexNarrative', seed.field_key, seed.field_value, seed.field_label,
       seed.sort_order, @trust_centre_operator, NOW(), NOW(), 0
FROM (
  SELECT 'hostingContent.zh' field_key, 'Uvel Node 与 Uvel Rack 部署在专业数据中心，运行环境、网络配置和现场维护统一管理，无需寄送实体设备。购买后在 App 中激活和管理设备，订单与设备记录随时可查。' field_value, '设备托管说明（中文）' field_label, 900 sort_order
  UNION ALL SELECT 'hostingContent.vi', 'Uvel Node và Uvel Rack được đặt tại trung tâm dữ liệu chuyên nghiệp; môi trường vận hành, mạng và bảo trì tại chỗ đều được quản lý tập trung, không cần giao thiết bị vật lý. Bạn kích hoạt và quản lý thiết bị trong App, hồ sơ đơn hàng và thiết bị luôn sẵn sàng tra cứu.', '设备托管说明（越南语）', 910
  UNION ALL SELECT 'hostingContent.en', 'Uvel Node and Uvel Rack run in professional data centres, where the operating environment, network and on-site maintenance are managed for you; nothing is shipped. Activate and manage devices in the App, with order and device records always available.', '设备托管说明（英语）', 920
  UNION ALL SELECT 'warrantyContent.zh', '设备故障、更换或重新配置、退款均按下单时的服务条款处理。遇到问题时，在 App 内联系专属客服并提供订单号，处理进度全程可查。', '保修与售后说明（中文）', 930
  UNION ALL SELECT 'warrantyContent.vi', 'Sự cố thiết bị, thay thế hoặc cấu hình lại và hoàn tiền được xử lý theo điều khoản dịch vụ khi đặt hàng. Khi cần hỗ trợ, hãy liên hệ chuyên viên riêng trong App kèm mã đơn hàng và theo dõi tiến độ xử lý.', '保修与售后说明（越南语）', 940
  UNION ALL SELECT 'warrantyContent.en', 'Faults, replacements or reconfiguration and refunds follow the service terms agreed when you ordered. Contact your dedicated advisor in the App with the order number and follow progress at every step.', '保修与售后说明（英语）', 950
  UNION ALL SELECT 'securityContent.zh', '参与算力任务无需提供钱包私钥或助记词。登录、修改收款地址、提现等重要操作都需要身份确认并留有操作记录；处理中、锁定与可用资金分开显示。', '账户与资金安全说明（中文）', 960
  UNION ALL SELECT 'securityContent.vi', 'Tham gia tác vụ điện toán không bao giờ yêu cầu khóa riêng tư hay cụm từ khôi phục ví. Đăng nhập, thay đổi địa chỉ nhận và rút tiền đều cần xác minh danh tính và được ghi nhật ký; số tiền đang xử lý, bị khóa và khả dụng được hiển thị riêng.', '账户与资金安全说明（越南语）', 970
  UNION ALL SELECT 'securityContent.en', 'Taking part in compute tasks never requires your wallet private key or recovery phrase. Sign-in, receiving-address changes and withdrawals all require identity confirmation and are recorded; funds in progress, locked and available are shown separately.', '账户与资金安全说明（英语）', 980
) seed
WHERE @upgrade_narrative = 1
ON DUPLICATE KEY UPDATE
  field_value = VALUES(field_value), field_delta = VALUES(field_delta), sort_order = VALUES(sort_order),
  last_operator = VALUES(last_operator), updated_at = NOW(), is_deleted = 0;

COMMIT;

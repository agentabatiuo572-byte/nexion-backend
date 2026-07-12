-- I4 信任中心 / I5 风险披露拆分（可重复执行，不覆盖既有业务数据）
START TRANSACTION;

INSERT INTO nx_admin_permission
  (permission_code, permission_name, resource_type, resource_path, perm_type, amplifies, status, is_deleted)
VALUES
  ('content_i4_publish_standard',   '信任版块发布/回滚/下架(普通版块)',             'API', '/content/trust',       'HIGH', 0, 1, 0),
  ('content_i5_read',               '风险披露版本-读',                            'API', '/content/disclosures', 'READ', 0, 1, 0),
  ('content_i5_write',              '风险披露草稿/矩阵CRUD',                      'API', '/content/disclosures', 'WRITE', 0, 1, 0),
  ('content_i5_disclosure_publish', '披露版本发布(高敏·触发re-ack·条款重签字)',    'API', '/content/disclosures', 'HIGH', 0, 1, 0),
  ('content_i5_gate_adjust',        '受限动作范围调整(高敏·放松资金类动作合规拦截)', 'API', '/content/disclosures', 'HIGH', 1, 1, 0)
ON DUPLICATE KEY UPDATE
  permission_name = VALUES(permission_name),
  resource_type = VALUES(resource_type),
  resource_path = VALUES(resource_path),
  perm_type = VALUES(perm_type),
  amplifies = VALUES(amplifies),
  status = 1,
  is_deleted = 0,
  updated_at = NOW();

-- 高敏 I4 管理角色同时获得普通版块发布权。
INSERT INTO nx_admin_role_permission (role_id, permission_id, created_at, updated_at, is_deleted)
SELECT old_grant.role_id, new_permission.id, NOW(), NOW(), 0
FROM nx_admin_role_permission old_grant
JOIN nx_admin_permission old_permission
  ON old_permission.id = old_grant.permission_id
 AND old_permission.permission_code = 'content_i4_trust_section_manage'
JOIN nx_admin_permission new_permission
  ON new_permission.permission_code = 'content_i4_publish_standard'
WHERE old_grant.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

-- 将旧 I4 披露授权等价复制到独立 I5 权限，不改变既有角色边界。
INSERT INTO nx_admin_role_permission (role_id, permission_id, created_at, updated_at, is_deleted)
SELECT old_grant.role_id, new_permission.id, NOW(), NOW(), 0
FROM nx_admin_role_permission old_grant
JOIN nx_admin_permission old_permission ON old_permission.id = old_grant.permission_id
JOIN nx_admin_permission new_permission
  ON new_permission.permission_code = CASE old_permission.permission_code
    WHEN 'content_i4_read' THEN 'content_i5_read'
    WHEN 'content_i4_write' THEN 'content_i5_write'
    WHEN 'content_i4_disclosure_publish' THEN 'content_i5_disclosure_publish'
    WHEN 'content_i4_gate_adjust' THEN 'content_i5_gate_adjust'
  END
WHERE old_grant.is_deleted = 0
  AND old_permission.permission_code IN (
    'content_i4_read', 'content_i4_write',
    'content_i4_disclosure_publish', 'content_i4_gate_adjust'
  )
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

-- SUPER_ADMIN 始终获得拆分后的全部权限。
INSERT INTO nx_admin_role_permission (role_id, permission_id, created_at, updated_at, is_deleted)
SELECT role_row.id, permission_row.id, NOW(), NOW(), 0
FROM nx_admin_role role_row
JOIN nx_admin_permission permission_row
  ON permission_row.permission_code IN (
    'content_i4_publish_standard', 'content_i5_read', 'content_i5_write',
    'content_i5_disclosure_publish', 'content_i5_gate_adjust'
  )
WHERE role_row.role_code = 'SUPER_ADMIN'
  AND role_row.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

-- 旧权限仅用于识别历史授权；运行时改用 I5 权限。
UPDATE nx_admin_permission
SET status = 0, is_deleted = 1, updated_at = NOW()
WHERE permission_code IN ('content_i4_disclosure_publish', 'content_i4_gate_adjust');

-- I4 同样收敛为规范菜单码，保留原 menu_id 与角色授权。
UPDATE nx_admin_menu legacy_menu
SET legacy_menu.menu_code = 'MENU_CONTENT_I4',
    legacy_menu.menu_name = '信任中心 CMS',
    legacy_menu.menu_name_zh = '信任中心 CMS',
    legacy_menu.menu_name_en = 'I4',
    legacy_menu.route_path = '/content/trust',
    legacy_menu.status = 1,
    legacy_menu.is_deleted = 0,
    legacy_menu.updated_at = NOW()
WHERE legacy_menu.menu_code = 'I4'
  AND NOT EXISTS (
    SELECT 1 FROM (SELECT menu_code FROM nx_admin_menu) existing_menu
    WHERE existing_menu.menu_code = 'MENU_CONTENT_I4'
  );

INSERT INTO nx_admin_menu
  (menu_code, menu_name, menu_name_zh, menu_name_en, parent_id, route_path, icon, sort_order, remark, status, is_deleted)
SELECT 'MENU_CONTENT_I4', '信任中心 CMS', '信任中心 CMS', 'I4', parent_menu.id,
       '/content/trust', 'Document',
       CASE WHEN parent_menu.menu_code = 'MENU_CONTENT' THEN 1804 ELSE 4 END,
       'I4 信任中心 CMS', 1, 0
FROM nx_admin_menu parent_menu
WHERE parent_menu.menu_code IN ('MENU_CONTENT', 'I')
  AND parent_menu.is_deleted = 0
ORDER BY CASE WHEN parent_menu.menu_code = 'MENU_CONTENT' THEN 0 ELSE 1 END
LIMIT 1
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name), menu_name_zh = VALUES(menu_name_zh), menu_name_en = VALUES(menu_name_en),
  parent_id = VALUES(parent_id), route_path = VALUES(route_path), icon = VALUES(icon),
  sort_order = VALUES(sort_order), remark = VALUES(remark), status = 1, is_deleted = 0, updated_at = NOW();

-- 将经典 I5 原地迁移为唯一规范码，保留原 menu_id 与全部角色授权，避免生成重复菜单。
UPDATE nx_admin_menu legacy_menu
SET legacy_menu.menu_code = 'MENU_CONTENT_I5',
    legacy_menu.menu_name = '风险披露版本',
    legacy_menu.menu_name_zh = '风险披露版本',
    legacy_menu.menu_name_en = 'I5',
    legacy_menu.route_path = '/content/disclosures',
    legacy_menu.status = 1,
    legacy_menu.is_deleted = 0,
    legacy_menu.updated_at = NOW()
WHERE legacy_menu.menu_code = 'I5'
  AND NOT EXISTS (
    SELECT 1 FROM (SELECT menu_code FROM nx_admin_menu) existing_menu
    WHERE existing_menu.menu_code = 'MENU_CONTENT_I5'
  );

-- 固定唯一菜单码 MENU_CONTENT_I5；兼容 MENU_CONTENT 与经典 I 两种父目录。
INSERT INTO nx_admin_menu
  (menu_code, menu_name, menu_name_zh, menu_name_en, parent_id, route_path, icon, sort_order, remark, status, is_deleted)
SELECT 'MENU_CONTENT_I5', '风险披露版本', '风险披露版本', 'I5', parent_menu.id,
       '/content/disclosures', 'Document',
       CASE WHEN parent_menu.menu_code = 'MENU_CONTENT' THEN 1805 ELSE 5 END,
       'I5 风险披露版本', 1, 0
FROM nx_admin_menu parent_menu
WHERE parent_menu.menu_code IN ('MENU_CONTENT', 'I')
  AND parent_menu.is_deleted = 0
ORDER BY CASE WHEN parent_menu.menu_code = 'MENU_CONTENT' THEN 0 ELSE 1 END
LIMIT 1
ON DUPLICATE KEY UPDATE
  menu_name = VALUES(menu_name), menu_name_zh = VALUES(menu_name_zh), menu_name_en = VALUES(menu_name_en),
  parent_id = VALUES(parent_id), route_path = VALUES(route_path), icon = VALUES(icon),
  sort_order = VALUES(sort_order), remark = VALUES(remark), status = 1, is_deleted = 0, updated_at = NOW();

INSERT INTO nx_admin_role_menu (role_id, menu_id, created_at, updated_at, is_deleted)
SELECT DISTINCT role_permission.role_id, menu_row.id, NOW(), NOW(), 0
FROM nx_admin_role_permission role_permission
JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
JOIN nx_admin_menu menu_row ON menu_row.menu_code = 'MENU_CONTENT_I5'
WHERE role_permission.is_deleted = 0
  AND permission_row.permission_code IN (
    'content_i5_read', 'content_i5_write', 'content_i5_disclosure_publish', 'content_i5_gate_adjust'
  )
  AND menu_row.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

INSERT INTO nx_admin_role_menu (role_id, menu_id, created_at, updated_at, is_deleted)
SELECT DISTINCT role_permission.role_id, menu_row.id, NOW(), NOW(), 0
FROM nx_admin_role_permission role_permission
JOIN nx_admin_permission permission_row ON permission_row.id = role_permission.permission_id
JOIN nx_admin_menu menu_row ON menu_row.menu_code = 'MENU_CONTENT_I4'
WHERE role_permission.is_deleted = 0
  AND permission_row.permission_code IN (
    'content_i4_read', 'content_i4_write', 'content_i4_publish_standard', 'content_i4_trust_section_manage'
  )
  AND menu_row.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

-- 六个固定信任版块：仅缺失时创建，绝不覆盖现有配置。
INSERT INTO nx_trust_section
  (section_key, description, struct_text, version_label, status, role_gate, high_sensitivity,
   last_change, sort_order, last_operator, created_at, updated_at, is_deleted)
SELECT seed.section_key, seed.description, seed.struct_text, 'v1', 'PUBLISHED', seed.role_gate,
       seed.high_sensitivity, '初始化首个已发布版本', seed.sort_order, 'migration', NOW(), NOW(), 0
FROM (
  SELECT 'financials' section_key, '财务与储备数据' description, '指标卡片与数据源说明' struct_text, '合规 / 超管' role_gate, 1 high_sensitivity, 1 sort_order
  UNION ALL SELECT 'leadership', '核心团队与治理信息', '人员与职责列表', '内容运营', 0, 2
  UNION ALL SELECT 'nexNarrative', 'NEX 叙事与机制说明', '叙事摘要与事实依据', '合规 / 超管', 1, 3
  UNION ALL SELECT 'complianceBadges', '合规资质与徽章', '资质卡片与有效期', '合规 / 超管', 1, 4
  UNION ALL SELECT 'auditsReserves', '审计与储备证明', '审计报告与储备证明', '合规 / 超管', 1, 5
  UNION ALL SELECT 'listings', '上架与市场信息', '市场及上架状态列表', '内容运营', 0, 6
) seed
WHERE NOT EXISTS (
  SELECT 1 FROM nx_trust_section existing
  WHERE existing.section_key = seed.section_key AND existing.is_deleted = 0
);

INSERT INTO nx_trust_section_version
  (section_key, version_label, description, struct_text, fields_json, status, revision,
   last_operator, created_at, updated_at, is_deleted)
SELECT seed.section_key, 'v1', seed.description, seed.struct_text, seed.fields_json, 'PUBLISHED', 1,
       'migration', NOW(), NOW(), 0
FROM (
  SELECT 'financials' section_key, '财务与储备数据' description, '指标卡片与数据源说明' struct_text,
         '[{"key":"tvlOnChain","label":"链上锁仓价值","value":"$128.4M"},{"key":"mrrValue","label":"月经常性收入","value":"$4.82M"},{"key":"mrrDelta","label":"月经常性收入变化","value":"+12.6%"},{"key":"activeAccountsValue","label":"活跃账户","value":"184,200"},{"key":"activeAccountsDelta","label":"活跃账户变化","value":"+8.4%"},{"key":"devicesOnlineValue","label":"在线设备","value":"72,640"},{"key":"devicesOnlineDelta","label":"在线设备变化","value":"+5.1%"},{"key":"payoutsProcessedValue","label":"已处理兑付","value":"$36.8M"},{"key":"payoutsProcessedDelta","label":"已处理兑付变化","value":"+9.7%"},{"key":"footnote.vi","label":"越南语脚注","value":"Dữ liệu được đối chiếu với sổ cái đã kiểm toán."},{"key":"footnote.zh","label":"中文脚注","value":"数据已与审计账本完成核对。"},{"key":"footnote.en","label":"英文脚注","value":"Data reconciled against the audited ledger."}]' fields_json
  UNION ALL SELECT 'leadership', '核心团队与治理信息', '人员与职责列表',
         '[{"key":"leader1Name","label":"负责人姓名","value":"Nexion Leadership Team"},{"key":"leader1Role","label":"负责人角色","value":"Executive Leadership"},{"key":"leader1Previous","label":"过往经历","value":"Fintech, compliance and infrastructure"},{"key":"leader1Url","label":"负责人详情","value":""}]'
  UNION ALL SELECT 'nexNarrative', 'NEX 叙事与机制说明', '叙事摘要与事实依据',
         '[{"key":"hero.vi","label":"越南语主标题","value":"NEX kết nối AI với hạ tầng kinh tế số."},{"key":"hero.zh","label":"中文主标题","value":"NEX 连接 AI 与数字经济基础设施。"},{"key":"hero.en","label":"英文主标题","value":"NEX connects AI with digital economic infrastructure."},{"key":"subhero.vi","label":"越南语副标题","value":"Minh bạch, có thể kiểm chứng và hướng đến tiện ích thực tế."},{"key":"subhero.zh","label":"中文副标题","value":"透明、可验证，并服务于真实使用场景。"},{"key":"subhero.en","label":"英文副标题","value":"Transparent, verifiable and built for real utility."},{"key":"activeAiClients","label":"活跃 AI 客户端","value":"18,420"}]'
  UNION ALL SELECT 'complianceBadges', '合规资质与徽章', '资质卡片与有效期',
         '[{"key":"badge1Label","label":"资质名称","value":"合规运营体系"},{"key":"badge1Body","label":"资质说明","value":"资质与适用范围以当前有效监管文件为准。"}]'
  UNION ALL SELECT 'auditsReserves', '审计与储备证明', '审计报告与储备证明',
         '[{"key":"document1Primary","label":"报告名称","value":"储备与资金审计报告"},{"key":"document1Secondary","label":"报告说明","value":"最新已发布审计周期"},{"key":"document1Url","label":"报告地址","value":""}]'
  UNION ALL SELECT 'listings', '上架与市场信息', '市场及上架状态列表',
         '[{"key":"listing1Exchange","label":"交易市场","value":"NEX Market"},{"key":"listing1State","label":"上架状态","value":"ACTIVE"},{"key":"listing1Url","label":"市场地址","value":"/pages/market/market"}]'
) seed
WHERE NOT EXISTS (
  SELECT 1 FROM nx_trust_section_version existing
  WHERE existing.section_key = seed.section_key
    AND existing.version_label = 'v1'
    AND existing.is_deleted = 0
);

-- 仅升级本迁移自己创建的旧单字段快照；任何人工/业务快照都不覆盖。
UPDATE nx_trust_section_version version_row
JOIN (
  SELECT 'financials' section_key,
         '[{"key":"tvlOnChain","label":"链上锁仓价值","value":"$128.4M"},{"key":"mrrValue","label":"月经常性收入","value":"$4.82M"},{"key":"mrrDelta","label":"月经常性收入变化","value":"+12.6%"},{"key":"activeAccountsValue","label":"活跃账户","value":"184,200"},{"key":"activeAccountsDelta","label":"活跃账户变化","value":"+8.4%"},{"key":"devicesOnlineValue","label":"在线设备","value":"72,640"},{"key":"devicesOnlineDelta","label":"在线设备变化","value":"+5.1%"},{"key":"payoutsProcessedValue","label":"已处理兑付","value":"$36.8M"},{"key":"payoutsProcessedDelta","label":"已处理兑付变化","value":"+9.7%"},{"key":"footnote.vi","label":"越南语脚注","value":"Dữ liệu được đối chiếu với sổ cái đã kiểm toán."},{"key":"footnote.zh","label":"中文脚注","value":"数据已与审计账本完成核对。"},{"key":"footnote.en","label":"英文脚注","value":"Data reconciled against the audited ledger."}]' fields_json
  UNION ALL SELECT 'leadership', '[{"key":"leader1Name","label":"负责人姓名","value":"Nexion Leadership Team"},{"key":"leader1Role","label":"负责人角色","value":"Executive Leadership"},{"key":"leader1Previous","label":"过往经历","value":"Fintech, compliance and infrastructure"},{"key":"leader1Url","label":"负责人详情","value":""}]'
  UNION ALL SELECT 'nexNarrative', '[{"key":"hero.vi","label":"越南语主标题","value":"NEX kết nối AI với hạ tầng kinh tế số."},{"key":"hero.zh","label":"中文主标题","value":"NEX 连接 AI 与数字经济基础设施。"},{"key":"hero.en","label":"英文主标题","value":"NEX connects AI with digital economic infrastructure."},{"key":"subhero.vi","label":"越南语副标题","value":"Minh bạch, có thể kiểm chứng và hướng đến tiện ích thực tế."},{"key":"subhero.zh","label":"中文副标题","value":"透明、可验证，并服务于真实使用场景。"},{"key":"subhero.en","label":"英文副标题","value":"Transparent, verifiable and built for real utility."},{"key":"activeAiClients","label":"活跃 AI 客户端","value":"18,420"}]'
  UNION ALL SELECT 'complianceBadges', '[{"key":"badge1Label","label":"资质名称","value":"合规运营体系"},{"key":"badge1Body","label":"资质说明","value":"资质与适用范围以当前有效监管文件为准。"}]'
  UNION ALL SELECT 'auditsReserves', '[{"key":"document1Primary","label":"报告名称","value":"储备与资金审计报告"},{"key":"document1Secondary","label":"报告说明","value":"最新已发布审计周期"},{"key":"document1Url","label":"报告地址","value":""}]'
  UNION ALL SELECT 'listings', '[{"key":"listing1Exchange","label":"交易市场","value":"NEX Market"},{"key":"listing1State","label":"上架状态","value":"ACTIVE"},{"key":"listing1Url","label":"市场地址","value":"/pages/market/market"}]'
) seed ON seed.section_key = version_row.section_key
SET version_row.fields_json = seed.fields_json,
    version_row.updated_at = NOW()
WHERE version_row.version_label = 'v1'
  AND version_row.status = 'PUBLISHED'
  AND version_row.last_operator = 'migration'
  AND version_row.is_deleted = 0
  AND JSON_VALID(version_row.fields_json) = 1
  AND JSON_LENGTH(version_row.fields_json) <= 1;

-- 可选链接只写已核实目标：无真实落点保持空值，已存在的 App 市场页使用真实 uni-app 路由。
UPDATE nx_trust_section_version
SET fields_json = JSON_SET(
      fields_json,
      REPLACE(JSON_UNQUOTE(JSON_SEARCH(fields_json, 'one', 'leader1Url', NULL, '$[*].key')), '.key', '.value'),
      ''),
    updated_at = NOW()
WHERE section_key = 'leadership' AND version_label = 'v1' AND last_operator = 'migration' AND is_deleted = 0
  AND JSON_SEARCH(fields_json, 'one', 'leader1Url', NULL, '$[*].key') IS NOT NULL;

UPDATE nx_trust_section_version
SET fields_json = JSON_SET(
      fields_json,
      REPLACE(JSON_UNQUOTE(JSON_SEARCH(fields_json, 'one', 'document1Url', NULL, '$[*].key')), '.key', '.value'),
      ''),
    updated_at = NOW()
WHERE section_key = 'auditsReserves' AND version_label = 'v1' AND last_operator = 'migration' AND is_deleted = 0
  AND JSON_SEARCH(fields_json, 'one', 'document1Url', NULL, '$[*].key') IS NOT NULL;

UPDATE nx_trust_section_version
SET fields_json = JSON_SET(
      fields_json,
      REPLACE(JSON_UNQUOTE(JSON_SEARCH(fields_json, 'one', 'listing1Url', NULL, '$[*].key')), '.key', '.value'),
      '/pages/market/market'),
    updated_at = NOW()
WHERE section_key = 'listings' AND version_label = 'v1' AND last_operator = 'migration' AND is_deleted = 0
  AND JSON_SEARCH(fields_json, 'one', 'listing1Url', NULL, '$[*].key') IS NOT NULL;

INSERT INTO nx_trust_section_field
  (section_key, field_key, field_value, field_delta, sort_order, last_operator, created_at, updated_at, is_deleted)
SELECT seed.section_key, seed.field_key, seed.field_value, NULL, 1, 'migration', NOW(), NOW(), 0
FROM (
  SELECT 'financials' section_key, 'tvlOnChain' field_key, '$128.4M' field_value
  UNION ALL SELECT 'financials', 'mrrValue', '$4.82M'
  UNION ALL SELECT 'financials', 'mrrDelta', '+12.6%'
  UNION ALL SELECT 'financials', 'activeAccountsValue', '184,200'
  UNION ALL SELECT 'financials', 'activeAccountsDelta', '+8.4%'
  UNION ALL SELECT 'financials', 'devicesOnlineValue', '72,640'
  UNION ALL SELECT 'financials', 'devicesOnlineDelta', '+5.1%'
  UNION ALL SELECT 'financials', 'payoutsProcessedValue', '$36.8M'
  UNION ALL SELECT 'financials', 'payoutsProcessedDelta', '+9.7%'
  UNION ALL SELECT 'financials', 'footnote.vi', 'Dữ liệu được đối chiếu với sổ cái đã kiểm toán.'
  UNION ALL SELECT 'financials', 'footnote.zh', '数据已与审计账本完成核对。'
  UNION ALL SELECT 'financials', 'footnote.en', 'Data reconciled against the audited ledger.'
  UNION ALL SELECT 'leadership', 'leader1Name', 'Nexion Leadership Team'
  UNION ALL SELECT 'leadership', 'leader1Role', 'Executive Leadership'
  UNION ALL SELECT 'leadership', 'leader1Previous', 'Fintech, compliance and infrastructure'
  UNION ALL SELECT 'leadership', 'leader1Url', ''
  UNION ALL SELECT 'nexNarrative', 'hero.vi', 'NEX kết nối AI với hạ tầng kinh tế số.'
  UNION ALL SELECT 'nexNarrative', 'hero.zh', 'NEX 连接 AI 与数字经济基础设施。'
  UNION ALL SELECT 'nexNarrative', 'hero.en', 'NEX connects AI with digital economic infrastructure.'
  UNION ALL SELECT 'nexNarrative', 'subhero.vi', 'Minh bạch, có thể kiểm chứng và hướng đến tiện ích thực tế.'
  UNION ALL SELECT 'nexNarrative', 'subhero.zh', '透明、可验证，并服务于真实使用场景。'
  UNION ALL SELECT 'nexNarrative', 'subhero.en', 'Transparent, verifiable and built for real utility.'
  UNION ALL SELECT 'nexNarrative', 'activeAiClients', '18,420'
  UNION ALL SELECT 'complianceBadges', 'badge1Label', '合规运营体系'
  UNION ALL SELECT 'complianceBadges', 'badge1Body', '资质与适用范围以当前有效监管文件为准。'
  UNION ALL SELECT 'auditsReserves', 'document1Primary', '储备与资金审计报告'
  UNION ALL SELECT 'auditsReserves', 'document1Secondary', '最新已发布审计周期'
  UNION ALL SELECT 'auditsReserves', 'document1Url', ''
  UNION ALL SELECT 'listings', 'listing1Exchange', 'NEX Market'
  UNION ALL SELECT 'listings', 'listing1State', 'ACTIVE'
  UNION ALL SELECT 'listings', 'listing1Url', '/pages/market/market'
) seed
WHERE NOT EXISTS (
  SELECT 1 FROM nx_trust_section_field existing
  WHERE existing.section_key = seed.section_key
    AND existing.field_key = seed.field_key
    AND existing.is_deleted = 0
);

UPDATE nx_trust_section_field
SET field_value = '', updated_at = NOW()
WHERE section_key = 'leadership' AND field_key = 'leader1Url'
  AND last_operator = 'migration' AND field_value = '/trust/leadership' AND is_deleted = 0;

UPDATE nx_trust_section_field
SET field_value = '', updated_at = NOW()
WHERE section_key = 'auditsReserves' AND field_key = 'document1Url'
  AND last_operator = 'migration' AND field_value = '/trust/audits/latest' AND is_deleted = 0;

UPDATE nx_trust_section_field
SET field_value = '/pages/market/market', updated_at = NOW()
WHERE section_key = 'listings' AND field_key = 'listing1Url'
  AND last_operator = 'migration' AND field_value = '/markets' AND is_deleted = 0;

COMMIT;

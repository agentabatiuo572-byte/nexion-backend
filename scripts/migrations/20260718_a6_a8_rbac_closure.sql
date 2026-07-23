-- A6/A7/A8 closure: merge legacy I7 into I6 and repair active permission menu ownership.
-- Idempotent and safe to run repeatedly on MySQL 8.

INSERT INTO nx_admin_menu (
    menu_code, menu_name, menu_name_zh, parent_id, route_path, sort_order, status, is_deleted
)
SELECT 'I6', 'i18n copy and courses', 'i18n 文案与教程', parent.id,
       '/content/i18n', 6, 1, 0
  FROM nx_admin_menu parent
 WHERE parent.menu_code = 'I'
   AND parent.is_deleted = 0
 LIMIT 1
ON DUPLICATE KEY UPDATE
    menu_name = VALUES(menu_name),
    menu_name_zh = VALUES(menu_name_zh),
    parent_id = VALUES(parent_id),
    route_path = VALUES(route_path),
    sort_order = VALUES(sort_order),
    status = 1,
    is_deleted = 0,
    updated_at = NOW();

INSERT INTO nx_admin_role_menu (role_id, menu_id, is_deleted)
SELECT legacy.role_id, canonical.id, 0
  FROM nx_admin_role_menu legacy
  JOIN nx_admin_menu old_menu
    ON old_menu.id = legacy.menu_id
   AND old_menu.menu_code = 'I7'
  JOIN nx_admin_menu canonical
    ON canonical.menu_code = 'I6'
   AND canonical.is_deleted = 0
 WHERE legacy.is_deleted = 0
ON DUPLICATE KEY UPDATE is_deleted = 0, updated_at = NOW();

UPDATE nx_admin_role_menu legacy
JOIN nx_admin_menu old_menu
  ON old_menu.id = legacy.menu_id
 AND old_menu.menu_code = 'I7'
SET legacy.is_deleted = 1,
    legacy.updated_at = NOW()
WHERE legacy.is_deleted = 0;

UPDATE nx_admin_permission permission
JOIN nx_admin_menu canonical
  ON canonical.menu_code = 'I6'
 AND canonical.is_deleted = 0
SET permission.menu_id = canonical.id,
    permission.updated_at = NOW()
WHERE permission.permission_code IN (
    'content_i7_read', 'content_i7_write', 'content_i7_course_reward_adjust'
)
  AND permission.is_deleted = 0;

UPDATE nx_admin_menu
   SET status = 0,
       is_deleted = 1,
       updated_at = NOW()
 WHERE menu_code = 'I7';

-- Older installations kept I4/I5 under their classic alias codes.
UPDATE nx_admin_permission permission
JOIN nx_admin_menu canonical
  ON canonical.menu_code = CASE
       WHEN permission.permission_code LIKE 'content_i4_%' THEN 'MENU_CONTENT_I4'
       WHEN permission.permission_code LIKE 'content_i5_%' THEN 'MENU_CONTENT_I5'
     END
 AND canonical.status = 1
 AND canonical.is_deleted = 0
SET permission.menu_id = canonical.id,
    permission.updated_at = NOW()
WHERE permission.status = 1
  AND permission.is_deleted = 0
  AND permission.resource_type = 'API'
  AND permission.permission_code REGEXP '^content_i[45]_';

-- Repair active API permissions whose menu is missing/deleted by their embedded A1-M9 page code.
UPDATE nx_admin_permission permission
LEFT JOIN nx_admin_menu current_menu
  ON current_menu.id = permission.menu_id
 AND current_menu.is_deleted = 0
JOIN nx_admin_menu canonical
  ON canonical.menu_code = REGEXP_SUBSTR(UPPER(permission.permission_code), '[A-M][0-9]+')
 AND canonical.status = 1
 AND canonical.is_deleted = 0
SET permission.menu_id = canonical.id,
    permission.updated_at = NOW()
WHERE permission.status = 1
  AND permission.is_deleted = 0
  AND permission.resource_type = 'API'
  AND current_menu.id IS NULL;

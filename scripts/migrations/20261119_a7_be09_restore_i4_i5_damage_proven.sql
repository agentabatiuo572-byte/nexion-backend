-- A7-BE-09: re-enable the current I4/I5 permission catalog entries that the
-- pinned A7 legacy-menu removal disabled, and nothing else.
--
-- Positive attribution only. The delivered old script
-- 20261119_a7_be01_legacy_menu_removal.sql:8-26 does exactly three things:
--   * soft-deletes role-menu bindings of the six legacy nodes
--     (MENU_HOME, MENU_UMS, MENU_UMS_ADMIN, MENU_UMS_ROLE, MENU_UMS_MENU,
--     MENU_UMS_PERMISSION),
--   * soft-deletes the nx_admin_permission rows whose menu_id points at one of
--     those six nodes, setting is_deleted=1 and status=0 in the same statement,
--   * soft-deletes those six nodes.
-- It never writes nx_admin_role_permission, and MENU_CONTENT_I4 / MENU_CONTENT_I5
-- are not among the six nodes. So the only damage this file may repair is a
-- current content_i4_*/i5_* catalog row that carries BOTH damage markers
-- (is_deleted=1 and status=0, as that statement writes them at once) *while still
-- bound to one of those six legacy nodes*: that is exactly the row set the pinned
-- statement would have hit, so the attribution is provable. A row with only one of
-- the two markers is not attributable and is left alone (reported below).
--
-- Everything else stays as it is:
--   * a deleted role->permission binding is never restored here. The old script
--     did not touch that table, so such a deletion is an A6 revocation or an
--     unknown source, and the current authorization follows A6.
--   * role->menu bindings of the canonical nodes are never restored here. The old
--     script only cleared bindings of the six legacy nodes, so a deleted I4/I5
--     binding is not attributable to it.
--   * the six legacy nodes and their bindings stay retired (A7-BE-01 is the
--     intended behaviour for them), and an unknown removal of any menu row is not
--     undone.
-- Restored rows are rebound to their canonical menu only when that menu exists and
-- is live; otherwise they are left alone and reported.
--
-- A7-BE-07 (20261119_a7_be07_restore_i4_i5_permissions.sql) restored every
-- soft-deleted binding for the eight codes, so it could not tell this damage from
-- a legitimate A6 role save, and it sat in the startup list so every start
-- re-applied that broad repair. It is kept unchanged as delivered history; this
-- file supersedes it in the normal startup chain and runs exactly once through
-- nx_schema_migration_state. The public-test rail pins the same file as a retired
-- forward migration (deploy/public-test/database_migrations.py, RETIRED_FORWARD_SQL)
-- so a target whose state never recorded it cannot apply it either, while the
-- archive entry stays so recorded history still validates. Targets that already
-- applied it keep that broad restore: this file never reverses a grant from source
-- alone, and the last result set says so.
--
-- The retired content_i4_disclosure_publish / content_i4_gate_adjust codes stay
-- retired (A7-BE-08) and are not in the repaired set.

SET NAMES utf8mb4;

START TRANSACTION;

INSERT INTO nx_schema_migration_state (migration_key, phase)
VALUES ('20261119_a7_be09_restore_i4_i5_damage_proven', 'PENDING')
ON DUPLICATE KEY UPDATE migration_key = migration_key;

-- Repair: current I4/I5 catalog rows disabled while bound to a legacy node.
UPDATE nx_admin_permission p
JOIN nx_admin_menu legacy ON legacy.id = p.menu_id
JOIN nx_admin_menu canonical
  ON canonical.menu_code = CASE
       WHEN p.permission_code LIKE 'content_i4_%' THEN 'MENU_CONTENT_I4'
       WHEN p.permission_code LIKE 'content_i5_%' THEN 'MENU_CONTENT_I5'
     END
 AND canonical.status = 1
 AND canonical.is_deleted = 0
JOIN nx_schema_migration_state st
  ON st.migration_key = '20261119_a7_be09_restore_i4_i5_damage_proven'
 AND st.phase = 'PENDING'
   SET p.menu_id = canonical.id,
       p.status = 1,
       p.is_deleted = 0,
       p.updated_at = NOW()
 WHERE p.resource_type = 'API'
   AND p.permission_code IN (
       'content_i4_read',
       'content_i4_write',
       'content_i4_publish_standard',
       'content_i4_trust_section_manage',
       'content_i5_read',
       'content_i5_write',
       'content_i5_disclosure_publish',
       'content_i5_gate_adjust'
     )
   -- 旧脚本在一条语句里同时写入 is_deleted=1 与 status=0(:15-20)，两项必须同时成立；
   -- 只有其中一项的行无法归因于那次写入，保持原样并出现在下面的只读诊断里。
   AND p.is_deleted = 1
   AND p.status = 0
   AND legacy.menu_code IN (
       'MENU_HOME',
       'MENU_UMS',
       'MENU_UMS_ADMIN',
       'MENU_UMS_ROLE',
       'MENU_UMS_MENU',
       'MENU_UMS_PERMISSION'
     );

-- Read-only result sets. No writes follow them inside this marker.

-- 1. Every still-damaged row the repair above did not take: only one of the two
--    markers matched, or the row is no longer bound to a legacy node, or the row
--    was damaged by some other writer. Kept as is for operator review.
SELECT 'CATALOG_DAMAGE_WITHOUT_SOURCE_EVIDENCE' AS check_name,
       p.permission_code,
       p.menu_id,
       COALESCE(m.menu_code, '<no menu row>') AS current_menu_code,
       p.status,
       p.is_deleted,
       'kept as is: not provably the legacy-menu run' AS resolution
  FROM nx_admin_permission p
  LEFT JOIN nx_admin_menu m ON m.id = p.menu_id
  JOIN nx_schema_migration_state st
    ON st.migration_key = '20261119_a7_be09_restore_i4_i5_damage_proven'
   AND st.phase = 'PENDING'
 WHERE p.resource_type = 'API'
   AND p.permission_code IN (
       'content_i4_read',
       'content_i4_write',
       'content_i4_publish_standard',
       'content_i4_trust_section_manage',
       'content_i5_read',
       'content_i5_write',
       'content_i5_disclosure_publish',
       'content_i5_gate_adjust'
     )
   AND (p.is_deleted = 1 OR p.status = 0);

-- 2. Role bindings for these codes that are (or stay) soft-deleted. The pinned
--    legacy-menu statement never wrote this table, so these are A6 revocations or
--    an unknown source and must not be revived from source.
SELECT 'ROLE_BINDING_DELETED_NO_MIGRATION_SOURCE' AS check_name,
       r.role_code,
       rp.role_id,
       p.permission_code,
       rp.updated_at AS binding_deleted_at,
       'kept as is: A6 revocation or unknown source, current grants follow A6' AS resolution
  FROM nx_admin_role_permission rp
  JOIN nx_admin_role r ON r.id = rp.role_id
  JOIN nx_admin_permission p ON p.id = rp.permission_id
  JOIN nx_schema_migration_state st
    ON st.migration_key = '20261119_a7_be09_restore_i4_i5_damage_proven'
   AND st.phase = 'PENDING'
 WHERE rp.is_deleted = 1
   AND p.resource_type = 'API'
   AND p.permission_code IN (
       'content_i4_read',
       'content_i4_write',
       'content_i4_publish_standard',
       'content_i4_trust_section_manage',
       'content_i5_read',
       'content_i5_write',
       'content_i5_disclosure_publish',
       'content_i5_gate_adjust'
     );

-- 3. Operator review boundary for targets that already ran the old broad restore.
SELECT 'A7_BE07_ALREADY_APPLIED_OPERATOR_REVIEW' AS check_name,
       COUNT(*) AS active_bindings_for_the_eight_codes,
       'a broad restore cannot be reversed from source; review A6_ROLE_GRANTS_CHANGED audit and re-issue a new grant change' AS resolution
  FROM nx_admin_role_permission rp
  JOIN nx_admin_permission p ON p.id = rp.permission_id
  JOIN nx_schema_migration_state st
    ON st.migration_key = '20261119_a7_be09_restore_i4_i5_damage_proven'
   AND st.phase = 'PENDING'
 WHERE rp.is_deleted = 0
   AND p.is_deleted = 0
   AND p.status = 1
   AND p.resource_type = 'API'
   AND p.permission_code IN (
       'content_i4_read',
       'content_i4_write',
       'content_i4_publish_standard',
       'content_i4_trust_section_manage',
       'content_i5_read',
       'content_i5_write',
       'content_i5_disclosure_publish',
       'content_i5_gate_adjust'
     );

UPDATE nx_schema_migration_state
   SET phase = 'FINAL'
 WHERE migration_key = '20261119_a7_be09_restore_i4_i5_damage_proven'
   AND phase = 'PENDING';

COMMIT;

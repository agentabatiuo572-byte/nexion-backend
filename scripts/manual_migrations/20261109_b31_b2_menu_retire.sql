-- MANUAL ONLY. Do not add this file to scripts/migrations or any startup/release list.
-- Before execution: verify SPM-01 on the paired PC release, approve this retirement,
-- save the actual acceptance/approval receipts and original B2 menu/bindings/permission owners.
-- This file checks database prerequisites only; executing it is not an approval or proof of SPM-01.
SET NAMES utf8mb4;

DELIMITER $$
DROP PROCEDURE IF EXISTS b31_retire_b2_menu$$
CREATE PROCEDURE b31_retire_b2_menu()
BEGIN
  DECLARE b2_menu_id BIGINT DEFAULT NULL;
  DECLARE d3_menu_id BIGINT DEFAULT NULL;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION BEGIN ROLLBACK; RESIGNAL; END;

  START TRANSACTION;
  SELECT d3.id INTO d3_menu_id
    FROM nx_admin_menu d3
    JOIN nx_admin_menu domain_menu ON domain_menu.id=d3.parent_id
     AND domain_menu.menu_code='D' AND domain_menu.status=1 AND domain_menu.is_deleted=0
   WHERE d3.menu_code='D3' AND d3.route_path='/finance/pool'
     AND d3.status=1 AND d3.is_deleted=0
   FOR UPDATE;
  IF d3_menu_id IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B31_ACTIVE_D3_MENU_REQUIRED';
  END IF;

  SELECT id INTO b2_menu_id FROM nx_admin_menu WHERE menu_code='B2' FOR UPDATE;
  IF b2_menu_id IS NULL THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B31_B2_MENU_REQUIRED';
  END IF;
  IF (SELECT COUNT(*) FROM nx_admin_permission
       WHERE permission_code IN ('overview_b2_read','overview_b2_write','overview_b2_export','finance_d3_read')
         AND status=1 AND is_deleted=0) <> 4 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B31_CLASSIC_PERMISSIONS_REQUIRED';
  END IF;

  -- Same stop condition as preflight_b2_retirement.sql: legal roles count even with no active accounts.
  IF EXISTS (
    SELECT 1 FROM nx_admin_role r
    JOIN nx_admin_role_permission rp ON rp.role_id=r.id AND rp.is_deleted=0
    JOIN nx_admin_permission p ON p.id=rp.permission_id AND p.status=1 AND p.is_deleted=0
     AND p.permission_code IN ('overview_b2_read','overview_b2_write','overview_b2_export')
    WHERE r.status=1 AND r.is_deleted=0
      AND (NOT EXISTS (
        SELECT 1 FROM nx_admin_role_menu rm
        WHERE rm.role_id=r.id AND rm.menu_id=d3_menu_id AND rm.is_deleted=0
      ) OR NOT EXISTS (
        SELECT 1 FROM nx_admin_role_permission d3_rp
        JOIN nx_admin_permission d3_p ON d3_p.id=d3_rp.permission_id
         AND d3_p.permission_code='finance_d3_read' AND d3_p.status=1 AND d3_p.is_deleted=0
        WHERE d3_rp.role_id=r.id AND d3_rp.is_deleted=0
      ))
  ) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='B31_B2_ONLY_ROLE_BLOCKS_RETIREMENT';
  END IF;

  UPDATE nx_admin_permission
     SET menu_id=d3_menu_id, updated_at=NOW()
   WHERE permission_code IN ('overview_b2_read','overview_b2_write','overview_b2_export')
     AND NOT (menu_id <=> d3_menu_id);
  UPDATE nx_admin_role_menu
     SET is_deleted=1, updated_at=NOW()
   WHERE menu_id=b2_menu_id AND is_deleted=0;
  UPDATE nx_admin_menu
     SET status=0, is_deleted=1, updated_at=NOW()
   WHERE id=b2_menu_id AND (status<>0 OR is_deleted<>1);
  COMMIT;

  SELECT 'B31_B2_MENU_RETIRED' AS result, b2_menu_id, d3_menu_id;
END$$
CALL b31_retire_b2_menu()$$
DROP PROCEDURE b31_retire_b2_menu$$
DELIMITER ;

SET NAMES utf8mb4;

UPDATE nx_admin_role
SET remark = '查看运营总览的部分页面'
WHERE role_code = 'CONFIG_ADMIN' AND status = 1 AND is_deleted = 0
  AND NOT (remark <=> '查看运营总览的部分页面');

UPDATE nx_admin_role
SET remark = '全域只读，可导出脱敏文件'
WHERE role_code = 'AUDITOR' AND status = 1 AND is_deleted = 0
  AND NOT (remark <=> '全域只读，可导出脱敏文件');

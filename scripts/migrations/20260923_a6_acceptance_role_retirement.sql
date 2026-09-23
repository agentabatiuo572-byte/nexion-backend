-- Zentao #101: retire only the named acceptance roles left in the live A6 catalog.
-- Keep rows and grants for audit. A role with any account relation, even an
-- inactive historical one, is outside this migration's authority.
START TRANSACTION;

UPDATE nx_admin_role AS role_row
   SET role_row.status = 0,
       role_row.is_deleted = 1,
       role_row.updated_at = NOW()
 WHERE role_row.is_deleted = 0
   AND role_row.role_code IN (
       'M3RAMRP0ZDPJ',
       'M3RAMRP0ZDPJ_RO',
       'F2_RO_MRUBHW1P',
       'K2_FLAG_2202430',
       'K2_FLAG_2202700',
       'K2_FLAG_2203000'
   )
   AND NOT EXISTS (
       SELECT 1
         FROM nx_admin_role_relation AS relation_row
        WHERE relation_row.role_id = role_row.id
   );

COMMIT;

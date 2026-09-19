-- Retire the plaintext-PII decrypt-export permission so the A8 dictionary, role
-- bindings, and the L5 page agree that no such capability exists.
--
-- Why: the management API permanently blocks plaintext export (OpsBiService
-- rejects includeDecrypted=true and maskingPolicy='DECRYPTED' with
-- RETIRED_FEATURE/DECRYPTED_PII_EXPORT_BLOCKED). Registering
-- bi_l5_decrypt_export anyway advertised a bound, high-risk capability with no
-- callable server implementation, which misleads role design and audit and is a
-- latent path back to a plaintext export.
--
-- Fail-closed direction: the permission is removed, never granted. Any role that
-- previously carried it loses it, so no role can request plaintext PII even if a
-- future implementation accidentally reintroduces a decrypt path.
--
-- Idempotent: every statement is a no-op on a second run. Historical audit rows
-- and the permission row itself are retained (soft delete) so past grants stay
-- explainable; only active authorization is withdrawn.

-- 1. Withdraw every live role binding before disabling the permission, so no
--    session can be re-derived into a decrypt capability while it is retired.
UPDATE nx_admin_role_permission rp
  JOIN nx_admin_permission p ON p.id = rp.permission_id
   SET rp.is_deleted = 1,
       rp.updated_at = NOW()
 WHERE p.permission_code = 'bi_l5_decrypt_export'
   AND rp.is_deleted = 0;

-- 2. Retire the permission point itself. Historical rows stay queryable for audit.
UPDATE nx_admin_permission
   SET status = 0,
       is_deleted = 1,
       updated_at = NOW()
 WHERE permission_code = 'bi_l5_decrypt_export'
   AND (status = 1 OR is_deleted = 0);

-- 3. Postcondition: no active binding and no active permission may remain. A bare
--    SELECT would only print the verdict, and the controlled runner does not
--    parse it, so the check raises SQLSTATE 45000 instead: mysql exits non-zero
--    and the runner stops backend startup rather than letting a partially
--    applied retirement present as success.
SET @l5_decrypt_export_retired = (
  (SELECT COUNT(*) FROM nx_admin_permission
    WHERE permission_code = 'bi_l5_decrypt_export' AND status = 1 AND is_deleted = 0) = 0
  AND (SELECT COUNT(*) FROM nx_admin_role_permission rp
        JOIN nx_admin_permission p ON p.id = rp.permission_id
       WHERE p.permission_code = 'bi_l5_decrypt_export' AND rp.is_deleted = 0) = 0
);
SET @sql = IF(
  @l5_decrypt_export_retired,
  'SELECT 1',
  'SIGNAL SQLSTATE ''45000'' SET MESSAGE_TEXT = ''L5_DECRYPT_EXPORT_RETIREMENT_INCOMPLETE'''
);
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;

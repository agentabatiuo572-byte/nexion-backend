package ffdd.opsconsole.auth.application;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import org.junit.jupiter.api.Test;

class AdminRbacBaselineInitializerTest {

    private final AdminRolePermissionMapper mapper = mock(AdminRolePermissionMapper.class);
    private final AdminRbacBaselineInitializer initializer = new AdminRbacBaselineInitializer(mapper);

    @Test
    void ensuresRuntimePermissionsAndRoleGrants() {
        initializer.ensureBaseline();

        verify(mapper).ensurePermission(
                eq("PERM_WITHDRAWAL_READ"),
                eq("Read withdrawal operations"),
                eq("/api/admin/finance/**"),
                anyString());
        verify(mapper).ensurePermission(
                eq("PERM_RISK_WRITE"),
                eq("Write risk operations"),
                eq("/api/admin/risk/**"),
                anyString());
        verify(mapper).ensurePermission(
                eq("PERM_BI_EXPORT"),
                eq("Export BI operations"),
                eq("/api/admin/bi/exports/**"),
                anyString());

        verify(mapper).insertMissingRolePermission("FINANCE", "PERM_WITHDRAWAL_REVIEW");
        verify(mapper).insertMissingRolePermission("RISK", "PERM_RISK_WRITE");
        verify(mapper).insertMissingRolePermission("SUPPORT", "PERM_USER_WRITE");
        verify(mapper).insertMissingRolePermission("AUDITOR", "PERM_AUDIT_EXPORT");
        verify(mapper).insertMissingRolePermission("AUDITOR", "PERM_BI_EXPORT");
    }
}

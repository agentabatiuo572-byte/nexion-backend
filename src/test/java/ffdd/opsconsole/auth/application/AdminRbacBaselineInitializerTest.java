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

        verify(mapper).ensureRole("SUPPORT_MANAGER", "客服主管", "客服坐席与服务用户分配管理");
        verify(mapper).ensureRole("SUPPORT_DEDICATED", "专属客服", "专属服务用户跟进坐席");
        verify(mapper).ensureRole("SUPPORT_GENERAL", "通用客服", "通用工单与会话接待坐席");

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
        verify(mapper).ensurePermission(
                eq("PERM_SUPPORT_SEAT_WRITE"),
                eq("Assign support seat roles"),
                eq("/api/admin/platform/accounts/*/role"),
                anyString());

        verify(mapper).insertMissingRolePermission("FINANCE", "PERM_WITHDRAWAL_REVIEW");
        verify(mapper).insertMissingRolePermission("CONFIG_ADMIN", "PERM_GROWTH_WRITE");
        verify(mapper).insertMissingRolePermission("CONFIG_ADMIN", "PERM_MARKET_WRITE");
        verify(mapper).insertMissingRolePermission("CONFIG_ADMIN", "PERM_CONTENT_WRITE");
        verify(mapper).insertMissingRolePermission("CONFIG_ADMIN", "PERM_EMERGENCY_WRITE");
        verify(mapper).insertMissingRolePermission("CONFIG_ADMIN", "PERM_AUDIT_EXPORT");
        verify(mapper).insertMissingRolePermission("CONFIG_ADMIN", "PERM_BI_EXPORT");
        verify(mapper).insertMissingRolePermission("RISK", "PERM_RISK_WRITE");
        verify(mapper).insertMissingRolePermission("SUPPORT", "PERM_USER_WRITE");
        verify(mapper).insertMissingRolePermission("SUPPORT", "PERM_GROWTH_READ");
        verify(mapper).insertMissingRolePermission("SUPPORT", "PERM_GROWTH_WRITE");
        verify(mapper).insertMissingRolePermission("SUPPORT_MANAGER", "PERM_SUPPORT_SEAT_WRITE");
        verify(mapper).disableRolePermission("SUPPORT_MANAGER", "PERM_SYSTEM_WRITE");
        verify(mapper).insertMissingRolePermission("SUPPORT_DEDICATED", "PERM_USER_WRITE");
        verify(mapper).insertMissingRolePermission("SUPPORT_GENERAL", "PERM_CONTENT_WRITE");
        verify(mapper).insertMissingRolePermission("AUDITOR", "PERM_AUDIT_EXPORT");
        verify(mapper).insertMissingRolePermission("AUDITOR", "PERM_BI_EXPORT");
    }
}

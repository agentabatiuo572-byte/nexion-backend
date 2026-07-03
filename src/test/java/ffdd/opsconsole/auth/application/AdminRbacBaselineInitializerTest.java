package ffdd.opsconsole.auth.application;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import org.junit.jupiter.api.Test;

class AdminRbacBaselineInitializerTest {

    private final AdminRolePermissionMapper mapper = mock(AdminRolePermissionMapper.class);
    private final AdminRbacBaselineInitializer initializer = new AdminRbacBaselineInitializer(mapper);

    @Test
    void ensuresRuntimePermissionsAndRoleGrants() {
        initializer.ensureBaseline();

        verify(mapper).ensureRole("SUPPORT", "客服", "客服中心全局后台角色;主管、专属、通用在 M1 坐席业务表内配置");
        verify(mapper).restoreSupportRelationsForLegacy("SUPPORT_MANAGER", "SUPPORT");
        verify(mapper).restoreSupportRelationsForLegacy("SUPPORT_DEDICATED", "SUPPORT");
        verify(mapper).restoreSupportRelationsForLegacy("SUPPORT_GENERAL", "SUPPORT");
        verify(mapper).migrateRoleRelations("SUPPORT_MANAGER", "SUPPORT");
        verify(mapper).migrateRoleRelations("SUPPORT_DEDICATED", "SUPPORT");
        verify(mapper).migrateRoleRelations("SUPPORT_GENERAL", "SUPPORT");
        verify(mapper).disableRoleRelations("SUPPORT_MANAGER");
        verify(mapper).disableRoleRelations("SUPPORT_DEDICATED");
        verify(mapper).disableRoleRelations("SUPPORT_GENERAL");
        verify(mapper).disableRole("SUPPORT_MANAGER");
        verify(mapper).disableRole("SUPPORT_DEDICATED");
        verify(mapper).disableRole("SUPPORT_GENERAL");

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
        verify(mapper, never()).ensurePermission(
                eq("PERM_SUPPORT_SEAT_WRITE"),
                anyString(),
                anyString(),
                anyString());
        verify(mapper).disablePermission("PERM_SUPPORT_SEAT_WRITE");

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
        verify(mapper, never()).insertMissingRolePermission("SUPPORT_MANAGER", "PERM_SUPPORT_SEAT_WRITE");
        verify(mapper, never()).insertMissingRolePermission("SUPPORT_DEDICATED", "PERM_USER_WRITE");
        verify(mapper, never()).insertMissingRolePermission("SUPPORT_GENERAL", "PERM_CONTENT_WRITE");
        verify(mapper).insertMissingRolePermission("AUDITOR", "PERM_AUDIT_EXPORT");
        verify(mapper).insertMissingRolePermission("AUDITOR", "PERM_BI_EXPORT");
    }
}

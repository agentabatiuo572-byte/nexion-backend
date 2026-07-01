package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;

@ApplicationService
@RequiredArgsConstructor
public class AdminRbacBaselineInitializer {
    private static final String SUPER_ADMIN = "SUPER_ADMIN";
    private static final String CONFIG_ADMIN = "CONFIG_ADMIN";
    private static final String FINANCE = "FINANCE";
    private static final String RISK = "RISK";
    private static final String CONTENT = "CONTENT";
    private static final String GROWTH = "GROWTH";
    private static final String SUPPORT = "SUPPORT";
    private static final String AUDITOR = "AUDITOR";

    private static final List<PermissionDef> PERMISSIONS = List.of(
            perm("PERM_SYSTEM_READ", "Read platform operations", "/api/admin/platform/**"),
            perm("PERM_SYSTEM_WRITE", "Write platform operations", "/api/admin/platform/**"),
            perm("PERM_AUDIT_READ", "Read audit operations", "/api/admin/platform/audit/**"),
            perm("PERM_AUDIT_EXPORT", "Export audit operations", "/api/admin/platform/audit/exports/**"),
            perm("PERM_TREASURY_READ", "Read treasury operations", "/api/admin/treasury/**"),
            perm("PERM_TREASURY_WRITE", "Write treasury operations", "/api/admin/treasury/**"),
            perm("PERM_USER_READ", "Read user operations", "/api/admin/users/**"),
            perm("PERM_USER_WRITE", "Write user operations", "/api/admin/users/**"),
            perm("PERM_WITHDRAWAL_READ", "Read withdrawal operations", "/api/admin/finance/**"),
            perm("PERM_WITHDRAWAL_REVIEW", "Review withdrawal operations", "/api/admin/finance/**"),
            perm("PERM_DEVICE_READ", "Read device operations", "/api/admin/devices/**"),
            perm("PERM_DEVICE_WRITE", "Write device operations", "/api/admin/devices/**"),
            perm("PERM_DEVICE_RESTORE", "Restore device operations", "/api/admin/devices/*/restore"),
            perm("PERM_TEAM_READ", "Read team operations", "/api/admin/teams/**"),
            perm("PERM_TEAM_WRITE", "Write team operations", "/api/admin/teams/**"),
            perm("PERM_MARKET_READ", "Read market operations", "/api/admin/market/**"),
            perm("PERM_MARKET_WRITE", "Write market operations", "/api/admin/market/**"),
            perm("PERM_GROWTH_READ", "Read growth operations", "/api/admin/growth/**"),
            perm("PERM_GROWTH_WRITE", "Write growth operations", "/api/admin/growth/**"),
            perm("PERM_CONTENT_READ", "Read content operations", "/api/admin/content/**"),
            perm("PERM_CONTENT_WRITE", "Write content operations", "/api/admin/content/**"),
            perm("PERM_EMERGENCY_READ", "Read emergency operations", "/api/admin/emergency/**"),
            perm("PERM_EMERGENCY_WRITE", "Write emergency operations", "/api/admin/emergency/**"),
            perm("PERM_RISK_READ", "Read risk operations", "/api/admin/risk/**"),
            perm("PERM_RISK_WRITE", "Write risk operations", "/api/admin/risk/**"),
            perm("PERM_BI_READ", "Read BI operations", "/api/admin/bi/**"),
            perm("PERM_BI_EXPORT", "Export BI operations", "/api/admin/bi/exports/**"));

    private static final List<RoleGrant> ROLE_GRANTS = List.of(
            grant(SUPER_ADMIN, permissionCodes()),
            grant(CONFIG_ADMIN,
                    "PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE",
                    "PERM_AUDIT_READ", "PERM_AUDIT_EXPORT",
                    "PERM_TREASURY_READ", "PERM_USER_READ", "PERM_WITHDRAWAL_READ",
                    "PERM_DEVICE_READ", "PERM_TEAM_READ", "PERM_MARKET_READ", "PERM_MARKET_WRITE",
                    "PERM_GROWTH_READ", "PERM_GROWTH_WRITE",
                    "PERM_CONTENT_READ", "PERM_CONTENT_WRITE",
                    "PERM_EMERGENCY_READ", "PERM_EMERGENCY_WRITE",
                    "PERM_RISK_READ", "PERM_BI_READ", "PERM_BI_EXPORT"),
            grant(FINANCE,
                    "PERM_TREASURY_READ", "PERM_TREASURY_WRITE",
                    "PERM_WITHDRAWAL_READ", "PERM_WITHDRAWAL_REVIEW",
                    "PERM_USER_READ", "PERM_RISK_READ", "PERM_AUDIT_READ", "PERM_BI_READ", "PERM_BI_EXPORT"),
            grant(RISK,
                    "PERM_RISK_READ", "PERM_RISK_WRITE",
                    "PERM_USER_READ", "PERM_USER_WRITE",
                    "PERM_WITHDRAWAL_READ", "PERM_WITHDRAWAL_REVIEW",
                    "PERM_TREASURY_READ", "PERM_EMERGENCY_READ", "PERM_EMERGENCY_WRITE",
                    "PERM_AUDIT_READ", "PERM_BI_READ", "PERM_BI_EXPORT"),
            grant(CONTENT,
                    "PERM_CONTENT_READ", "PERM_CONTENT_WRITE",
                    "PERM_EMERGENCY_READ", "PERM_EMERGENCY_WRITE",
                    "PERM_RISK_READ", "PERM_USER_READ", "PERM_AUDIT_READ", "PERM_BI_READ", "PERM_BI_EXPORT"),
            grant(GROWTH,
                    "PERM_GROWTH_READ", "PERM_GROWTH_WRITE",
                    "PERM_DEVICE_READ", "PERM_DEVICE_WRITE", "PERM_DEVICE_RESTORE",
                    "PERM_TEAM_READ", "PERM_TEAM_WRITE",
                    "PERM_MARKET_READ", "PERM_MARKET_WRITE",
                    "PERM_TREASURY_READ", "PERM_WITHDRAWAL_READ",
                    "PERM_CONTENT_READ", "PERM_CONTENT_WRITE",
                    "PERM_AUDIT_READ", "PERM_BI_READ", "PERM_BI_EXPORT"),
            grant(SUPPORT,
                    "PERM_USER_READ", "PERM_USER_WRITE",
                    "PERM_WITHDRAWAL_READ", "PERM_RISK_READ",
                    "PERM_GROWTH_READ", "PERM_GROWTH_WRITE",
                    "PERM_CONTENT_READ", "PERM_CONTENT_WRITE",
                    "PERM_AUDIT_READ", "PERM_BI_READ"),
            grant(AUDITOR,
                    "PERM_SYSTEM_READ", "PERM_AUDIT_READ", "PERM_TREASURY_READ", "PERM_USER_READ",
                    "PERM_WITHDRAWAL_READ", "PERM_DEVICE_READ", "PERM_TEAM_READ", "PERM_MARKET_READ",
                    "PERM_GROWTH_READ", "PERM_CONTENT_READ", "PERM_EMERGENCY_READ", "PERM_RISK_READ",
                    "PERM_BI_READ", "PERM_BI_EXPORT", "PERM_AUDIT_EXPORT"));
    private static final List<RoleGrant> REVOKED_ROLE_GRANTS = List.of();

    private final AdminRolePermissionMapper mapper;

    @PostConstruct
    void ensureBaseline() {
        for (PermissionDef permission : PERMISSIONS) {
            mapper.ensurePermission(
                    permission.code(),
                    permission.name(),
                    permission.resourcePath(),
                    "ops-console runtime RBAC baseline");
        }
        for (RoleGrant grant : ROLE_GRANTS) {
            for (String permissionCode : grant.permissionCodes()) {
                mapper.restoreRolePermission(grant.roleCode(), permissionCode);
                mapper.insertMissingRolePermission(grant.roleCode(), permissionCode);
            }
        }
        for (RoleGrant grant : REVOKED_ROLE_GRANTS) {
            for (String permissionCode : grant.permissionCodes()) {
                mapper.disableRolePermission(grant.roleCode(), permissionCode);
            }
        }
    }

    private static PermissionDef perm(String code, String name, String resourcePath) {
        return new PermissionDef(code, name, resourcePath);
    }

    private static RoleGrant grant(String roleCode, String... permissionCodes) {
        return new RoleGrant(roleCode, List.of(permissionCodes));
    }

    private static RoleGrant grant(String roleCode, List<String> permissionCodes) {
        return new RoleGrant(roleCode, List.copyOf(permissionCodes));
    }

    private static List<String> permissionCodes() {
        return PERMISSIONS.stream().map(PermissionDef::code).toList();
    }

    private record PermissionDef(String code, String name, String resourcePath) {
    }

    private record RoleGrant(String roleCode, List<String> permissionCodes) {
    }
}

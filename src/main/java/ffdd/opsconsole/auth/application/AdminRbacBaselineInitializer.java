package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import jakarta.annotation.PostConstruct;
import java.util.List;
import lombok.RequiredArgsConstructor;

/** Ensures built-in roles exist and refuses startup until the classic RBAC migration is complete. */
@ApplicationService
@RequiredArgsConstructor
public class AdminRbacBaselineInitializer {
    private static final long MIN_CLASSIC_PERMISSION_COUNT = 273;
    private static final List<RoleDef> ROLES = List.of(
            new RoleDef("SUPER_ADMIN", "超级管理员", "平台全域管理员"),
            new RoleDef("CONFIG_ADMIN", "配置运营", "平台配置与系统参数管理员"),
            new RoleDef("FINANCE", "财务", "资金、账务与提现审核"),
            new RoleDef("RISK", "风控", "风控、KYC 与紧急处置"),
            new RoleDef("CONTENT", "内容运营", "内容、公告与披露管理"),
            new RoleDef("GROWTH", "增长运营", "增长、设备与网络运营"),
            new RoleDef("SUPPORT", "客服", "客服中心全局后台角色;主管、专属、通用在 M1 坐席业务表内配置"),
            new RoleDef("AUDITOR", "只读审计", "审计与合规只读观察"));

    private final AdminRolePermissionMapper mapper;

    @PostConstruct
    void ensureBaseline() {
        for (RoleDef role : ROLES) {
            mapper.ensureRole(role.code(), role.name(), role.remark());
        }
        long classicPermissions = mapper.countActiveClassicPermissions();
        long superAdminPermissions = mapper.countActiveSuperAdminClassicPermissions();
        if (classicPermissions < MIN_CLASSIC_PERMISSION_COUNT || superAdminPermissions < classicPermissions) {
            throw new IllegalStateException(
                    "RBAC_CLASSIC_MIGRATION_REQUIRED: run scripts/migrations/20260712_rbac_classic.sql before startup");
        }
    }

    private record RoleDef(String code, String name, String remark) {
    }
}

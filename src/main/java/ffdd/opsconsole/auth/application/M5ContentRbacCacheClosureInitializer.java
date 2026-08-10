package ffdd.opsconsole.auth.application;

import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import jakarta.annotation.PostConstruct;
import java.util.LinkedHashSet;
import java.util.List;
import lombok.RequiredArgsConstructor;

/**
 * Closes the Redis half of the M5 CONTENT RBAC migration before this process accepts requests.
 *
 * <p>The controlled SQL migration runs before the JVM. This initializer verifies that durable
 * closure, then evicts only accounts actively related to CONTENT, so their next authorization
 * reads the new MySQL grants immediately rather than serving the former 30-minute Redis set.
 */
@ApplicationService
@RequiredArgsConstructor
public class M5ContentRbacCacheClosureInitializer {
    private static final String CONTENT_ROLE_CODE = "CONTENT";

    private final AdminRolePermissionMapper permissionMapper;
    private final AdminRoleRelationMapper roleRelationMapper;
    private final AdminPermissionCache permissionCache;

    @PostConstruct
    void enforceContentM5Closure() {
        if (!permissionMapper.isM5ContentRbacClosureApplied()) {
            throw new IllegalStateException(
                    "M5_CONTENT_RBAC_CLOSURE_REQUIRED: apply scripts/migrations/20260809_m5_content_rbac_closure.sql before startup");
        }
        List<Long> contentAdminIds = roleRelationMapper.selectAdminIdsByRoleCode(CONTENT_ROLE_CODE);
        for (Long adminId : new LinkedHashSet<>(contentAdminIds == null ? List.<Long>of() : contentAdminIds)) {
            permissionCache.evict(adminId);
        }
    }
}

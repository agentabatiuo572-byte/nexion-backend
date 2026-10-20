package ffdd.opsconsole.shared.security;

import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.shared.exception.PhoneReplacementException;
import java.util.Arrays;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/** Current database permissions also guard internal replay and durable command recovery. */
@Component
@RequiredArgsConstructor
public class PhoneReplacementAdminAccess {
    private final AdminPermissionCache permissions;
    private final AdminMapper admins;

    public long require(String... anyPermission) {
        var principal = current();
        if (Arrays.stream(anyPermission).noneMatch(principal.permissions()::contains)) throw new PhoneReplacementException(403, "PERMISSION_DENIED");
        return principal.id();
    }

    public long requirePolicyApproval() {
        var principal=current();
        if (!canApprovePolicy(principal.permissions())) throw new PhoneReplacementException(403,"PERMISSION_DENIED");
        return principal.id();
    }

    public long requirePolicyReceiptRead() {
        var principal=current();
        var rights=principal.permissions();
        if (!rights.contains("device_e6_write") && !rights.contains("device_e6_flag_toggle") && !canApprovePolicy(rights))
            throw new PhoneReplacementException(403,"PERMISSION_DENIED");
        return principal.id();
    }

    private static boolean canApprovePolicy(java.util.Set<String> rights) {
        return rights.contains("platform_a2_operation_approve") && rights.contains("device_e6_read");
    }
    private record Principal(long id,java.util.Set<String> permissions) {}
    private Principal current() {
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || !(auth.getDetails() instanceof Map<?, ?> details)
                || !"ADMIN".equals(details.get("subjectType"))) throw new PhoneReplacementException(403, "ADMIN_AUTH_REQUIRED");
        long id;
        try { id = Long.parseLong(auth.getName()); }
        catch (NumberFormatException invalid) { throw new PhoneReplacementException(403, "ADMIN_AUTH_REQUIRED"); }
        var admin = admins.selectById(id);
        if (admin == null || !Integer.valueOf(1).equals(admin.getStatus())
                || !Integer.valueOf(0).equals(admin.getIsDeleted())) throw new PhoneReplacementException(403, "ADMIN_AUTH_REQUIRED");
        var current = permissions.getPermissionCodes(id);
        return new Principal(id,current);
    }
}

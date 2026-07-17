package ffdd.opsconsole.shared.security;

import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/** Authoritative guard for data that is intentionally visible only to super administrators. */
@Component("superAdminAuthorization")
@RequiredArgsConstructor
public class SuperAdminAuthorization {
    private final AdminMapper adminMapper;

    public boolean isSuperAdmin(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || !isAdminSubject(authentication)) {
            return false;
        }
        Long adminId = parseAdminId(authentication.getPrincipal());
        if (adminId == null) {
            return false;
        }
        AdminEntity admin = adminMapper.selectById(adminId);
        return admin != null
                && !Integer.valueOf(1).equals(admin.getIsDeleted())
                && Integer.valueOf(1).equals(admin.getStatus())
                && Integer.valueOf(1).equals(admin.getSuperAdmin());
    }

    private boolean isAdminSubject(Authentication authentication) {
        Object details = authentication.getDetails();
        if (!(details instanceof Map<?, ?> values)) {
            return false;
        }
        return "ADMIN".equals(String.valueOf(values.get("subjectType")));
    }

    private Long parseAdminId(Object principal) {
        try {
            return principal == null ? null : Long.valueOf(String.valueOf(principal));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}

package ffdd.opsconsole.auth.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.AdminLoginRequest;
import ffdd.opsconsole.auth.dto.AdminLoginResponse;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsAdminAuthService {
    private static final String SUBJECT_TYPE_ADMIN = "ADMIN";
    private static final List<String> SUPER_ADMIN_MONOLITH_AUTHORITIES = List.of(
            "PERM_SYSTEM_READ",
            "PERM_SYSTEM_WRITE",
            "PERM_AUDIT_READ",
            "PERM_AUDIT_EXPORT",
            "PERM_TREASURY_READ",
            "PERM_TREASURY_WRITE",
            "PERM_USER_READ",
            "PERM_USER_WRITE",
            "PERM_WITHDRAWAL_READ",
            "PERM_WITHDRAWAL_REVIEW",
            "PERM_DEVICE_READ",
            "PERM_DEVICE_WRITE",
            "PERM_DEVICE_RESTORE",
            "PERM_TEAM_READ",
            "PERM_TEAM_WRITE",
            "PERM_MARKET_READ",
            "PERM_MARKET_WRITE",
            "PERM_GROWTH_READ",
            "PERM_GROWTH_WRITE",
            "PERM_CONTENT_READ",
            "PERM_CONTENT_WRITE",
            "PERM_EMERGENCY_READ",
            "PERM_EMERGENCY_WRITE",
            "PERM_RISK_READ",
            "PERM_RISK_WRITE",
            "PERM_BI_READ",
            "PERM_BI_EXPORT");

    private final AdminMapper adminMapper;
    private final AdminRolePermissionMapper permissionMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final AdminSessionRegistry adminSessionRegistry;

    public ApiResult<AdminLoginResponse> login(AdminLoginRequest request) {
        if (request == null
                || !StringUtils.hasText(request.username())
                || !StringUtils.hasText(request.password())) {
            return invalidCredential();
        }
        AdminEntity admin = findByUsername(request.username());
        if (admin == null || !activeRecord(admin)) {
            return invalidCredential();
        }
        if (!passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            return invalidCredential();
        }
        if (!enabled(admin)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "ADMIN_DISABLED");
        }

        List<String> authorities = effectiveAuthorities(admin);
        String sessionId;
        try {
            sessionId = adminSessionRegistry.createSession(admin.getId(), admin.getUsername());
        } catch (RuntimeException ex) {
            return ApiResult.fail(503, "ADMIN_SESSION_STORE_UNAVAILABLE");
        }
        String token = tokenProvider.createToken(admin.getId(), SUBJECT_TYPE_ADMIN, admin.getUsername(), authorities, sessionId);
        return ApiResult.ok(new AdminLoginResponse(
                token,
                "Bearer",
                session(admin, authorities)));
    }

    public ApiResult<AdminLoginResponse.AdminSession> current(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), OpsErrorCode.UNAUTHORIZED.name());
        }
        Long adminId = parseAdminId(authentication.getPrincipal());
        if (adminId == null) {
            return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), OpsErrorCode.UNAUTHORIZED.name());
        }
        AdminEntity admin = adminMapper.selectById(adminId);
        if (admin == null || !activeRecord(admin)) {
            return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), OpsErrorCode.UNAUTHORIZED.name());
        }
        if (!enabled(admin)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "ADMIN_DISABLED");
        }
        List<String> authorities = authentication.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(StringUtils::hasText)
                .toList();
        return ApiResult.ok(session(admin, authorities));
    }

    private AdminEntity findByUsername(String username) {
        String normalized = username.trim().toLowerCase(Locale.ROOT);
        return adminMapper.selectOne(new LambdaQueryWrapper<AdminEntity>()
                .eq(AdminEntity::getUsername, normalized)
                .eq(AdminEntity::getIsDeleted, 0)
                .last("LIMIT 1"));
    }

    private ApiResult<AdminLoginResponse> invalidCredential() {
        return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), "ADMIN_CREDENTIAL_INVALID");
    }

    private List<String> effectiveAuthorities(AdminEntity admin) {
        Set<String> authorities = new LinkedHashSet<>();
        authorities.addAll(permissionMapper.selectActivePermissionCodes(admin.getId()));
        if (superAdmin(admin)) {
            authorities.addAll(SUPER_ADMIN_MONOLITH_AUTHORITIES);
        }
        return List.copyOf(authorities);
    }

    private AdminLoginResponse.AdminSession session(AdminEntity admin, List<String> authorities) {
        return new AdminLoginResponse.AdminSession(
                admin.getId(),
                admin.getUsername(),
                StringUtils.hasText(admin.getNickname()) ? admin.getNickname() : admin.getUsername(),
                superAdmin(admin) ? "superadmin" : "auditor",
                List.copyOf(authorities));
    }

    private boolean activeRecord(AdminEntity admin) {
        return !Integer.valueOf(1).equals(admin.getIsDeleted());
    }

    private boolean enabled(AdminEntity admin) {
        return Integer.valueOf(1).equals(admin.getStatus());
    }

    private boolean superAdmin(AdminEntity admin) {
        return Integer.valueOf(1).equals(admin.getSuperAdmin());
    }

    private Long parseAdminId(Object principal) {
        if (principal == null) {
            return null;
        }
        try {
            return Long.valueOf(String.valueOf(principal));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

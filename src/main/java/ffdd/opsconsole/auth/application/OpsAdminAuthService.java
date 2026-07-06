package ffdd.opsconsole.auth.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.AdminLoginRequest;
import ffdd.opsconsole.auth.dto.AdminPasswordChangeRequest;
import ffdd.opsconsole.auth.dto.AdminLoginResponse;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.infrastructure.AdminAccountStateEntity;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsAdminAuthService {
    private static final String SUBJECT_TYPE_ADMIN = "ADMIN";
    private static final String PASSWORD_CHANGE_REQUIRED = "PASSWORD_CHANGE_REQUIRED";
    private static final String PASSWORD_ACTIVE = "ACTIVE";
    private static final Set<String> PASSWORD_CHANGE_REQUIRED_STATUSES = Set.of(
            PASSWORD_CHANGE_REQUIRED,
            "MAIL_DISPATCHED",
            "HANDOFF_PENDING");
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
    private final AdminRoleRelationMapper roleRelationMapper;
    private final AdminAccountStateMapper accountStateMapper;
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

        boolean mustChangePassword = passwordChangeRequired(admin.getId());
        List<String> authorities = mustChangePassword ? List.of() : effectiveAuthorities(admin);
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
                session(admin, authorities, mustChangePassword)));
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

    @Transactional
    public ApiResult<AdminLoginResponse> changePassword(Authentication authentication, AdminPasswordChangeRequest request) {
        if (authentication == null || !authentication.isAuthenticated()) {
            return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), OpsErrorCode.UNAUTHORIZED.name());
        }
        Long adminId = parseAdminId(authentication.getPrincipal());
        if (adminId == null) {
            return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), OpsErrorCode.UNAUTHORIZED.name());
        }
        if (request == null
                || !StringUtils.hasText(request.currentPassword())
                || !StringUtils.hasText(request.newPassword())) {
            return ApiResult.fail(422, "ADMIN_PASSWORD_REQUIRED");
        }
        AdminEntity admin = adminMapper.selectById(adminId);
        if (admin == null || !activeRecord(admin)) {
            return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), OpsErrorCode.UNAUTHORIZED.name());
        }
        if (!enabled(admin)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "ADMIN_DISABLED");
        }
        if (!passwordEncoder.matches(request.currentPassword(), admin.getPasswordHash())) {
            return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), "ADMIN_PASSWORD_CURRENT_INVALID");
        }
        String newPassword = request.newPassword().trim();
        if (!strongAdminPassword(newPassword)) {
            return ApiResult.fail(422, "ADMIN_PASSWORD_WEAK");
        }
        if (passwordEncoder.matches(newPassword, admin.getPasswordHash())) {
            return ApiResult.fail(422, "ADMIN_PASSWORD_REUSED");
        }

        List<String> authorities = effectiveAuthorities(admin);
        String sessionId;
        try {
            sessionId = adminSessionRegistry.createSession(admin.getId(), admin.getUsername());
        } catch (RuntimeException ex) {
            return ApiResult.fail(503, "ADMIN_SESSION_STORE_UNAVAILABLE");
        }

        AdminEntity patch = new AdminEntity();
        patch.setId(adminId);
        patch.setPasswordHash(passwordEncoder.encode(newPassword));
        try {
            adminMapper.updateById(patch);
            accountStateMapper.upsertCredentialStatus(adminId, PASSWORD_ACTIVE);
            adminSessionRegistry.revokeSessionsExcept(adminId, sessionId);
        } catch (RuntimeException ex) {
            adminSessionRegistry.revokeSessions(adminId);
            throw ex;
        }
        String token = tokenProvider.createToken(admin.getId(), SUBJECT_TYPE_ADMIN, admin.getUsername(), authorities, sessionId);
        return ApiResult.ok(new AdminLoginResponse(token, "Bearer", session(admin, authorities, false)));
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
        return session(admin, authorities, passwordChangeRequired(admin.getId()));
    }

    private AdminLoginResponse.AdminSession session(
            AdminEntity admin,
            List<String> authorities,
            boolean passwordChangeRequired) {
        return new AdminLoginResponse.AdminSession(
                admin.getId(),
                admin.getUsername(),
                StringUtils.hasText(admin.getNickname()) ? admin.getNickname() : admin.getUsername(),
                frontendRole(admin),
                List.copyOf(authorities),
                passwordChangeRequired);
    }

    private boolean passwordChangeRequired(Long adminId) {
        AdminAccountStateEntity state = accountStateMapper.selectActiveByAdminId(adminId);
        return state != null && PASSWORD_CHANGE_REQUIRED_STATUSES.contains(state.getCredentialDeliveryStatus());
    }

    private String frontendRole(AdminEntity admin) {
        if (superAdmin(admin)) {
            return "superadmin";
        }
        String roleCode = roleRelationMapper.activeRoleCode(admin.getId());
        if (!StringUtils.hasText(roleCode)) {
            return "auditor";
        }
        String normalized = roleCode.trim().toUpperCase(Locale.ROOT);
        if ("FINANCE".equals(normalized)) {
            return "finance";
        }
        if ("RISK".equals(normalized)) {
            return "risk";
        }
        if ("CONTENT".equals(normalized)) {
            return "content";
        }
        if ("GROWTH".equals(normalized)) {
            return "growth";
        }
        if ("SUPPORT".equals(normalized)) {
            return "support";
        }
        if ("AUDITOR".equals(normalized) || "AUDIT".equals(normalized)) {
            return "auditor";
        }
        if ("CONFIG_ADMIN".equals(normalized)) {
            return "config";
        }
        return "auditor";
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

    private boolean strongAdminPassword(String password) {
        String normalized = StringUtils.hasText(password) ? password.trim() : "";
        return normalized.length() >= 8;
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

package ffdd.opsconsole.auth.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.AdminLoginRequest;
import ffdd.opsconsole.auth.dto.AdminPasswordChangeRequest;
import ffdd.opsconsole.auth.dto.AdminLoginResponse;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.infrastructure.AdminAccountStateEntity;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
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
    // SUPER_ADMIN 旧 PERM_* 桥接白名单已移至 AdminPermissionCache（#11 对齐新码后整段删除）

    private final AdminMapper adminMapper;
    private final AdminRoleRelationMapper roleRelationMapper;
    private final AdminAccountStateMapper accountStateMapper;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider tokenProvider;
    private final AdminPermissionCache permissionCache;
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

        // A fresh login is an explicit authorization refresh boundary. This also
        // closes the cache gap after SQL migrations or out-of-band RBAC changes.
        permissionCache.evict(admin.getId());
        boolean mustChangePassword = passwordChangeRequired(admin.getId());
        List<String> authorities = mustChangePassword ? List.of() : effectiveAuthorities(admin);
        String sessionId;
        try {
            sessionId = adminSessionRegistry.createSession(admin.getId(), admin.getUsername());
        } catch (RuntimeException ex) {
            return ApiResult.fail(503, "ADMIN_SESSION_STORE_UNAVAILABLE");
        }
        String token = tokenProvider.createToken(admin.getId(), SUBJECT_TYPE_ADMIN, admin.getUsername(), List.of(), sessionId);
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
        String token = tokenProvider.createToken(admin.getId(), SUBJECT_TYPE_ADMIN, admin.getUsername(), List.of(), sessionId);
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
        // 权限来源委托 AdminPermissionCache（Redis 缓存 MySQL + super 桥接旧码，#11 后桥接删除）
        return List.copyOf(permissionCache.getPermissionCodes(admin.getId()));
    }

    private AdminLoginResponse.AdminSession session(AdminEntity admin, List<String> authorities) {
        return session(admin, authorities, passwordChangeRequired(admin.getId()));
    }

    private AdminLoginResponse.AdminSession session(
            AdminEntity admin,
            List<String> authorities,
            boolean passwordChangeRequired) {
        String roleCode = effectiveRoleCode(admin);
        List<AdminLoginResponse.EffectiveMenuNode> effectiveMenuNodes = passwordChangeRequired
                ? List.of()
                : superAdmin(admin)
                        ? roleRelationMapper.selectAllActiveMenuNodes()
                        : roleRelationMapper.selectActiveMenuNodes(admin.getId());
        List<AdminLoginResponse.EffectiveMenuNode> safeMenuNodes =
                effectiveMenuNodes == null ? List.of() : List.copyOf(effectiveMenuNodes);
        List<String> effectiveMenus = safeMenuNodes.stream()
                .map(AdminLoginResponse.EffectiveMenuNode::menuCode)
                .toList();
        return new AdminLoginResponse.AdminSession(
                admin.getId(),
                admin.getUsername(),
                StringUtils.hasText(admin.getNickname()) ? admin.getNickname() : admin.getUsername(),
                frontendRole(roleCode),
                roleCode,
                List.copyOf(authorities),
                effectiveMenus,
                safeMenuNodes,
                passwordChangeRequired);
    }

    private boolean passwordChangeRequired(Long adminId) {
        AdminAccountStateEntity state = accountStateMapper.selectActiveByAdminId(adminId);
        return state != null && PASSWORD_CHANGE_REQUIRED_STATUSES.contains(state.getCredentialDeliveryStatus());
    }

    private String effectiveRoleCode(AdminEntity admin) {
        if (superAdmin(admin)) {
            return "SUPER_ADMIN";
        }
        String roleCode = roleRelationMapper.activeRoleCode(admin.getId());
        if (!StringUtils.hasText(roleCode)) {
            return "AUDITOR";
        }
        return roleCode.trim().toUpperCase(Locale.ROOT);
    }

    private String frontendRole(String roleCode) {
        String normalized = roleCode.trim().toUpperCase(Locale.ROOT);
        if ("SUPER_ADMIN".equals(normalized)) {
            return "superadmin";
        }
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
        return normalized.toLowerCase(Locale.ROOT);
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

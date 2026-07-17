package ffdd.opsconsole.auth.application;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import ffdd.opsconsole.auth.dto.AdminLoginRequest;
import ffdd.opsconsole.auth.dto.AdminMfaVerifyRequest;
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
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
@Slf4j
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
    private final AdminTotpService totpService;
    private final AdminMfaCipher mfaCipher;
    private final AdminMfaChallengeRegistry mfaChallenges;
    private final AdminMfaProperties mfaProperties;
    private final AdminLoginGuard loginGuard;

    public ApiResult<AdminLoginResponse> login(AdminLoginRequest request) {
        return login(request, "unknown", "unknown");
    }

    public ApiResult<AdminLoginResponse> login(
            AdminLoginRequest request, String ipAddress, String userAgent) {
        if (request == null
                || !StringUtils.hasText(request.username())
                || !StringUtils.hasText(request.password())) {
            return invalidCredential();
        }
        String normalizedUsername = request.username().trim().toLowerCase(Locale.ROOT);
        if (loginLocked(normalizedUsername)) {
            return invalidCredential();
        }
        AdminEntity admin = findByUsername(normalizedUsername);
        if (admin == null || !activeRecord(admin)) {
            recordLoginFailure(normalizedUsername);
            return invalidCredential();
        }
        if (!passwordEncoder.matches(request.password(), admin.getPasswordHash())) {
            recordLoginFailure(normalizedUsername);
            return invalidCredential();
        }
        if (!enabled(admin)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "ADMIN_DISABLED");
        }

        if (mfaProperties.isTemporarySuperadminBypassEnabled()
                && superAdmin(admin)
                && "superadmin".equals(normalizedUsername)) {
            log.warn("Temporary super-admin MFA bypass used for adminId={}", admin.getId());
            return issueAuthenticatedSession(
                    admin,
                    normalizedUsername,
                    ipAddress,
                    userAgent,
                    "ADMIN_AUTH_UNAVAILABLE");
        }

        AdminAccountStateEntity state = accountStateMapper.selectActiveByAdminId(admin.getId());
        boolean enrollment = state == null
                || !StringUtils.hasText(state.getTfaSecretEncrypted())
                || state.getTfaBoundAt() == null;
        String secret;
        String encryptedSecret;
        String challengeId;
        try {
            secret = enrollment ? totpService.generateSecret() : mfaCipher.decrypt(state.getTfaSecretEncrypted());
            encryptedSecret = mfaCipher.encrypt(secret);
            challengeId = mfaChallenges.create(
                    admin.getId(), admin.getUsername(), encryptedSecret, enrollment);
        } catch (RuntimeException ex) {
            log.warn("Admin MFA challenge creation unavailable for adminId={}: {}",
                    admin.getId(), ex.getClass().getSimpleName(), ex);
            return ApiResult.fail(503, "ADMIN_MFA_UNAVAILABLE");
        }
        return ApiResult.ok(new AdminLoginResponse(
                null,
                null,
                null,
                new AdminLoginResponse.MfaChallenge(
                        challengeId,
                        enrollment ? "ENROLL" : "VERIFY",
                        Math.max(60, mfaProperties.getChallengeTtlSeconds()),
                        enrollment ? totpService.provisioningUri(mfaProperties.getIssuer(), admin.getUsername(), secret) : null,
                        enrollment ? secret : null)));
    }

    @Transactional
    public ApiResult<AdminLoginResponse> verifyMfa(AdminMfaVerifyRequest request) {
        return verifyMfa(request, "unknown", "unknown");
    }

    @Transactional
    public ApiResult<AdminLoginResponse> verifyMfa(
            AdminMfaVerifyRequest request, String ipAddress, String userAgent) {
        if (request == null || !StringUtils.hasText(request.challengeId()) || !StringUtils.hasText(request.code())) {
            return ApiResult.fail(422, "ADMIN_MFA_CODE_REQUIRED");
        }
        AdminMfaChallengeRegistry.Challenge challenge;
        try {
            challenge = mfaChallenges.read(request.challengeId());
        } catch (RuntimeException ex) {
            return ApiResult.fail(503, "ADMIN_MFA_UNAVAILABLE");
        }
        if (challenge == null || loginLocked(challenge.username())) {
            return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), "ADMIN_MFA_CHALLENGE_INVALID");
        }
        String secret;
        try {
            secret = mfaCipher.decrypt(challenge.encryptedSecret());
        } catch (RuntimeException ex) {
            return ApiResult.fail(503, "ADMIN_MFA_UNAVAILABLE");
        }
        Long totpCounter = totpService.matchingCounter(secret, request.code().trim());
        if (totpCounter == null) {
            try {
                recordLoginFailure(challenge.username());
                mfaChallenges.recordFailure(request.challengeId());
            } catch (RuntimeException ex) {
                return ApiResult.fail(503, "ADMIN_MFA_UNAVAILABLE");
            }
            return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), "ADMIN_MFA_CODE_INVALID");
        }
        try {
            challenge = mfaChallenges.consume(request.challengeId());
            if (challenge == null) {
                return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), "ADMIN_MFA_CHALLENGE_INVALID");
            }
            if (!mfaChallenges.claimTotpCounter(challenge.adminId(), totpCounter)) {
                return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), "ADMIN_MFA_CODE_REPLAYED");
            }
        } catch (RuntimeException ex) {
            return ApiResult.fail(503, "ADMIN_MFA_UNAVAILABLE");
        }
        AdminEntity admin = adminMapper.selectById(challenge.adminId());
        if (admin == null || !activeRecord(admin) || !enabled(admin)) {
            return ApiResult.fail(OpsErrorCode.UNAUTHORIZED.httpStatus(), "ADMIN_MFA_CHALLENGE_INVALID");
        }
        if (challenge.enrollment()) {
            accountStateMapper.upsertMfaBinding(admin.getId(), challenge.encryptedSecret(), LocalDateTime.now());
        }
        return issueAuthenticatedSession(
                admin,
                challenge.username(),
                ipAddress,
                userAgent,
                "ADMIN_MFA_UNAVAILABLE");
    }

    public ApiResult<Void> logout(Authentication authentication) {
        Long adminId = authentication == null ? null : parseAdminId(authentication.getPrincipal());
        String sessionId = sessionId(authentication);
        if (adminId != null && StringUtils.hasText(sessionId)) {
            adminSessionRegistry.revokeSession(adminId, sessionId);
        }
        return ApiResult.ok(null);
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

    public ApiResult<AdminLoginResponse> changePassword(Authentication authentication, AdminPasswordChangeRequest request) {
        return changePassword(authentication, request, "unknown", "unknown");
    }

    @Transactional
    public ApiResult<AdminLoginResponse> changePassword(
            Authentication authentication,
            AdminPasswordChangeRequest request,
            String ipAddress,
            String userAgent) {
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
            sessionId = adminSessionRegistry.createSession(admin.getId(), admin.getUsername(), ipAddress, userAgent);
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
            return "UNASSIGNED";
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
        return normalized.length() >= 16
                && Pattern.compile("[a-z]").matcher(normalized).find()
                && Pattern.compile("[A-Z]").matcher(normalized).find()
                && Pattern.compile("\\d").matcher(normalized).find()
                && Pattern.compile("[^A-Za-z0-9]").matcher(normalized).find();
    }

    private ApiResult<AdminLoginResponse> issueAuthenticatedSession(
            AdminEntity admin,
            String normalizedUsername,
            String ipAddress,
            String userAgent,
            String successRecordingFailureCode) {
        permissionCache.evict(admin.getId());
        boolean mustChangePassword = passwordChangeRequired(admin.getId());
        List<String> authorities = mustChangePassword ? List.of() : effectiveAuthorities(admin);
        String sessionId;
        try {
            sessionId = adminSessionRegistry.createSession(admin.getId(), admin.getUsername(), ipAddress, userAgent);
        } catch (RuntimeException ex) {
            return ApiResult.fail(503, "ADMIN_SESSION_STORE_UNAVAILABLE");
        }
        try {
            loginGuard.recordSuccess(normalizedUsername);
        } catch (RuntimeException ex) {
            adminSessionRegistry.revokeSession(admin.getId(), sessionId);
            return ApiResult.fail(503, successRecordingFailureCode);
        }
        String token = tokenProvider.createToken(
                admin.getId(), SUBJECT_TYPE_ADMIN, admin.getUsername(), List.of(), sessionId);
        return ApiResult.ok(new AdminLoginResponse(
                token,
                "Bearer",
                session(admin, authorities, mustChangePassword)));
    }

    private boolean loginLocked(String username) {
        try {
            return loginGuard.locked(username);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private void recordLoginFailure(String username) {
        try {
            loginGuard.recordFailure(username);
        } catch (RuntimeException ignored) {
            // Authentication fails closed even when the Redis-backed counter is unavailable.
        }
    }

    private String sessionId(Authentication authentication) {
        if (authentication == null || !(authentication.getDetails() instanceof Map<?, ?> details)) {
            return null;
        }
        Object value = details.get("sessionId");
        return value == null ? null : String.valueOf(value);
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

package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.AdminLoginRequest;
import ffdd.opsconsole.auth.dto.AdminLoginResponse;
import ffdd.opsconsole.auth.dto.AdminPasswordChangeRequest;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.infrastructure.AdminAccountStateEntity;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import ffdd.opsconsole.shared.security.JwtProperties;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class OpsAdminAuthServiceTest {

    private final AdminMapper adminMapper = mock(AdminMapper.class);
    private final AdminRoleRelationMapper roleRelationMapper = mock(AdminRoleRelationMapper.class);
    private final AdminAccountStateMapper accountStateMapper = mock(AdminAccountStateMapper.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(jwtProperties());
    private final AdminPermissionCache permissionCache = mock(AdminPermissionCache.class);
    private final AdminSessionRegistry adminSessionRegistry = mock(AdminSessionRegistry.class);
    private final OpsAdminAuthService service =
            new OpsAdminAuthService(
                    adminMapper,
                    roleRelationMapper,
                    accountStateMapper,
                    passwordEncoder,
                    tokenProvider,
                    permissionCache,
                    adminSessionRegistry);

    private JwtProperties jwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("ops-console-auth-test-secret-32chars");
        properties.setTtlMinutes(60);
        return properties;
    }

    @Test
    void loginIssuesAdminTokenFromStoredHashAndEffectiveAuthorities() {
        AdminEntity admin = activeSuperAdmin(passwordEncoder.encode("Admin@123456"));
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(permissionCache.getPermissionCodes(1L))
                .thenReturn(new LinkedHashSet<>(List.of("PERM_SYSTEM_READ", "PERM_USER_READ")));
        when(adminSessionRegistry.createSession(1L, "superadmin")).thenReturn("admin-session-1");

        ApiResult<AdminLoginResponse> result =
                service.login(new AdminLoginRequest("superadmin", "Admin@123456"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isNotBlank();
        assertThat(result.getData().tokenType()).isEqualTo("Bearer");
        assertThat(result.getData().session().adminId()).isEqualTo(1L);
        assertThat(result.getData().session().username()).isEqualTo("superadmin");
        assertThat(result.getData().session().operator()).isEqualTo("Super Admin");
        assertThat(result.getData().session().role()).isEqualTo("superadmin");
        assertThat(result.getData().session().passwordChangeRequired()).isFalse();
        // super 桥接已删除：authorities 完全来自 permissionCache（DB/Redis），不再追加旧 PERM_SYSTEM_WRITE
        assertThat(result.getData().session().authorities())
                .contains("PERM_SYSTEM_READ", "PERM_USER_READ")
                .doesNotContain("PERM_SYSTEM_WRITE", "PERM_SUPPORT_SEAT_WRITE");
        Claims claims = tokenProvider.parse(result.getData().accessToken());
        assertThat(claims.get("subjectType")).isEqualTo("ADMIN");
        assertThat(claims.get("sessionId")).isEqualTo("admin-session-1");
    }

    @Test
    void loginUsesRoleRelationForNonSuperAdminSessions() {
        AdminEntity admin = activeAdmin(4L, "risk.shift", "风险值班", passwordEncoder.encode("RiskShift@123"));
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(roleRelationMapper.activeRoleCode(4L)).thenReturn("RISK");
        when(permissionCache.getPermissionCodes(4L))
                .thenReturn(new LinkedHashSet<>(List.of("PERM_RISK_READ", "PERM_RISK_WRITE")));
        when(roleRelationMapper.selectActiveMenuCodes(4L)).thenReturn(List.of("risk", "risk_cases"));
        when(adminSessionRegistry.createSession(4L, "risk.shift")).thenReturn("admin-session-4");

        ApiResult<AdminLoginResponse> result =
                service.login(new AdminLoginRequest("risk.shift", "RiskShift@123"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().session().role()).isEqualTo("risk");
        assertThat(result.getData().session().roleCode()).isEqualTo("RISK");
        assertThat(result.getData().session().effectiveMenus()).containsExactly("risk", "risk_cases");
        assertThat(result.getData().session().authorities())
                .containsExactly("PERM_RISK_READ", "PERM_RISK_WRITE");
    }

    @Test
    void loginPreservesCustomRoleCodeInsteadOfDegradingToAuditor() {
        AdminEntity admin = activeAdmin(9L, "custom.ops", "Custom Ops", passwordEncoder.encode("CustomOps@123"));
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(roleRelationMapper.activeRoleCode(9L)).thenReturn("CUSTOM_SETTLEMENT");
        when(roleRelationMapper.selectActiveMenuCodes(9L)).thenReturn(List.of("treasury", "settlement"));
        when(permissionCache.getPermissionCodes(9L))
                .thenReturn(new LinkedHashSet<>(List.of("finance_d4_read")));
        when(adminSessionRegistry.createSession(9L, "custom.ops")).thenReturn("admin-session-9");

        ApiResult<AdminLoginResponse> result =
                service.login(new AdminLoginRequest("custom.ops", "CustomOps@123"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().session().role()).isEqualTo("custom_settlement");
        assertThat(result.getData().session().roleCode()).isEqualTo("CUSTOM_SETTLEMENT");
        assertThat(result.getData().session().authorities()).containsExactly("finance_d4_read");
        assertThat(result.getData().session().effectiveMenus()).containsExactly("treasury", "settlement");
    }

    @Test
    void loginMarksSessionWhenInitialPasswordMustBeChanged() {
        AdminEntity admin = activeAdmin(4L, "risk.shift", "风险值班", passwordEncoder.encode("RiskShift@123"));
        AdminAccountStateEntity state = new AdminAccountStateEntity();
        state.setAdminId(4L);
        state.setCredentialDeliveryStatus("PASSWORD_CHANGE_REQUIRED");
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(accountStateMapper.selectActiveByAdminId(4L)).thenReturn(state);
        when(roleRelationMapper.activeRoleCode(4L)).thenReturn("RISK");
        when(permissionCache.getPermissionCodes(4L)).thenReturn(new LinkedHashSet<>(List.of("PERM_RISK_READ")));
        when(adminSessionRegistry.createSession(4L, "risk.shift")).thenReturn("admin-session-4");

        ApiResult<AdminLoginResponse> result =
                service.login(new AdminLoginRequest("risk.shift", "RiskShift@123"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().session().passwordChangeRequired()).isTrue();
        assertThat(result.getData().session().authorities()).isEmpty();
        Claims claims = tokenProvider.parse(result.getData().accessToken());
        assertThat(claims.get("authorities", List.class)).isEmpty();
    }

    @Test
    void loginTreatsLegacyCredentialDeliveryStatesAsPasswordChangeRequired() {
        AdminEntity admin = activeAdmin(4L, "risk.shift", "风险值班", passwordEncoder.encode("RiskShift@123"));
        AdminAccountStateEntity state = new AdminAccountStateEntity();
        state.setAdminId(4L);
        state.setCredentialDeliveryStatus("HANDOFF_PENDING");
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(accountStateMapper.selectActiveByAdminId(4L)).thenReturn(state);
        when(roleRelationMapper.activeRoleCode(4L)).thenReturn("RISK");
        when(permissionCache.getPermissionCodes(4L)).thenReturn(new LinkedHashSet<>(List.of("PERM_RISK_READ")));
        when(adminSessionRegistry.createSession(4L, "risk.shift")).thenReturn("admin-session-4");

        ApiResult<AdminLoginResponse> result =
                service.login(new AdminLoginRequest("risk.shift", "RiskShift@123"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().session().passwordChangeRequired()).isTrue();
        assertThat(result.getData().session().authorities()).isEmpty();
    }

    @Test
    void changePasswordVerifiesCurrentPasswordStoresHashAndIssuesFreshSession() {
        AdminEntity admin = activeAdmin(4L, "risk.shift", "风险值班", passwordEncoder.encode("RiskShift@123"));
        when(adminMapper.selectById(4L)).thenReturn(admin);
        when(roleRelationMapper.activeRoleCode(4L)).thenReturn("RISK");
        when(permissionCache.getPermissionCodes(4L)).thenReturn(new LinkedHashSet<>(List.of("PERM_RISK_READ")));
        when(adminSessionRegistry.createSession(4L, "risk.shift")).thenReturn("admin-session-new");
        when(adminMapper.updateById(any(AdminEntity.class))).thenAnswer(invocation -> {
            AdminEntity patch = invocation.getArgument(0);
            admin.setPasswordHash(patch.getPasswordHash());
            return 1;
        });
        var authentication = new UsernamePasswordAuthenticationToken("4", null, List.of());

        ApiResult<AdminLoginResponse> result =
                service.changePassword(authentication, new AdminPasswordChangeRequest("RiskShift@123", "87654321"));

        assertThat(result.getCode()).isZero();
        assertThat(passwordEncoder.matches("87654321", admin.getPasswordHash())).isTrue();
        assertThat(result.getData().accessToken()).isNotBlank();
        assertThat(result.getData().session().passwordChangeRequired()).isFalse();
        verify(accountStateMapper).upsertCredentialStatus(4L, "ACTIVE");
        verify(adminSessionRegistry).revokeSessionsExcept(4L, "admin-session-new");
    }

    @Test
    void changePasswordDoesNotMutatePasswordWhenFreshSessionCannotBeCreated() {
        AdminEntity admin = activeAdmin(4L, "risk.shift", "风险值班", passwordEncoder.encode("RiskShift@123"));
        String originalHash = admin.getPasswordHash();
        when(adminMapper.selectById(4L)).thenReturn(admin);
        when(adminSessionRegistry.createSession(4L, "risk.shift"))
                .thenThrow(new IllegalStateException("redis down"));
        var authentication = new UsernamePasswordAuthenticationToken("4", null, List.of());

        ApiResult<AdminLoginResponse> result =
                service.changePassword(authentication, new AdminPasswordChangeRequest("RiskShift@123", "87654321"));

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("ADMIN_SESSION_STORE_UNAVAILABLE");
        assertThat(admin.getPasswordHash()).isEqualTo(originalHash);
        verify(adminMapper, never()).updateById(any(AdminEntity.class));
        verify(accountStateMapper, never()).upsertCredentialStatus(4L, "ACTIVE");
        verify(adminSessionRegistry, never()).revokeSessions(4L);
        verify(adminSessionRegistry, never()).revokeSessionsExcept(any(Long.class), any(String.class));
    }

    @Test
    void changePasswordRejectsWrongCurrentPasswordAndWeakReplacement() {
        AdminEntity admin = activeAdmin(4L, "risk.shift", "风险值班", passwordEncoder.encode("RiskShift@123"));
        when(adminMapper.selectById(4L)).thenReturn(admin);
        var authentication = new UsernamePasswordAuthenticationToken("4", null, List.of());

        ApiResult<AdminLoginResponse> wrongCurrent =
                service.changePassword(authentication, new AdminPasswordChangeRequest("wrong", "RiskShift@456"));
        ApiResult<AdminLoginResponse> weak =
                service.changePassword(authentication, new AdminPasswordChangeRequest("RiskShift@123", "short"));

        assertThat(wrongCurrent.getCode()).isEqualTo(OpsErrorCode.UNAUTHORIZED.httpStatus());
        assertThat(wrongCurrent.getMessage()).isEqualTo("ADMIN_PASSWORD_CURRENT_INVALID");
        assertThat(weak.getCode()).isEqualTo(422);
        assertThat(weak.getMessage()).isEqualTo("ADMIN_PASSWORD_WEAK");
    }

    @Test
    void loginRejectsWrongPasswordWithoutLeakingAccountState() {
        when(adminMapper.selectOne(any())).thenReturn(activeSuperAdmin(passwordEncoder.encode("Admin@123456")));

        ApiResult<AdminLoginResponse> result =
                service.login(new AdminLoginRequest("superadmin", "wrong"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.UNAUTHORIZED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("ADMIN_CREDENTIAL_INVALID");
        assertThat(result.getData()).isNull();
    }

    @Test
    void loginRejectsDisabledAdmin() {
        AdminEntity admin = activeSuperAdmin(passwordEncoder.encode("Admin@123456"));
        admin.setStatus(0);
        when(adminMapper.selectOne(any())).thenReturn(admin);

        ApiResult<AdminLoginResponse> result =
                service.login(new AdminLoginRequest("superadmin", "Admin@123456"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("ADMIN_DISABLED");
    }

    @Test
    void currentSessionLoadsAdminByTokenSubjectAndKeepsTokenAuthorities() {
        when(adminMapper.selectById(1L)).thenReturn(activeSuperAdmin(passwordEncoder.encode("Admin@123456")));
        var authentication = new UsernamePasswordAuthenticationToken(
                "1",
                null,
                List.of(
                        new SimpleGrantedAuthority("PERM_SYSTEM_READ"),
                        new SimpleGrantedAuthority("PERM_USER_READ")));

        ApiResult<AdminLoginResponse.AdminSession> result = service.current(authentication);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().adminId()).isEqualTo(1L);
        assertThat(result.getData().authorities()).containsExactly("PERM_SYSTEM_READ", "PERM_USER_READ");
    }

    private AdminEntity activeSuperAdmin(String hash) {
        AdminEntity admin = new AdminEntity();
        admin.setId(1L);
        admin.setUsername("superadmin");
        admin.setPasswordHash(hash);
        admin.setNickname("Super Admin");
        admin.setSuperAdmin(1);
        admin.setStatus(1);
        admin.setIsDeleted(0);
        return admin;
    }

    private AdminEntity activeAdmin(Long id, String username, String nickname, String hash) {
        AdminEntity admin = new AdminEntity();
        admin.setId(id);
        admin.setUsername(username);
        admin.setPasswordHash(hash);
        admin.setNickname(nickname);
        admin.setSuperAdmin(0);
        admin.setStatus(1);
        admin.setIsDeleted(0);
        return admin;
    }
}

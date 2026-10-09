package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.AdminLoginRequest;
import ffdd.opsconsole.auth.dto.AdminLoginResponse;
import ffdd.opsconsole.auth.dto.AdminMfaVerifyRequest;
import ffdd.opsconsole.auth.dto.AdminPasswordChangeRequest;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.infrastructure.AdminAccountStateEntity;
import ffdd.opsconsole.platform.mapper.AdminAccountStateMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminPermissionCache;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import ffdd.opsconsole.shared.security.JwtProperties;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import java.util.LinkedHashSet;
import java.util.List;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

class OpsAdminAuthServiceTest {

    private final AdminMapper adminMapper = mock(AdminMapper.class);
    private final AdminRoleRelationMapper roleRelationMapper = mock(AdminRoleRelationMapper.class);
    private final AdminAccountStateMapper accountStateMapper = mock(AdminAccountStateMapper.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtTokenProvider tokenProvider = spy(new JwtTokenProvider(jwtProperties()));
    private final AdminPermissionCache permissionCache = mock(AdminPermissionCache.class);
    private final AdminSessionRegistry adminSessionRegistry = mock(AdminSessionRegistry.class);
    private final AdminTotpService totpService = mock(AdminTotpService.class);
    private final AdminMfaCipher mfaCipher = mock(AdminMfaCipher.class);
    private final AdminMfaChallengeRegistry mfaChallenges = mock(AdminMfaChallengeRegistry.class);
    private final AdminMfaProperties mfaProperties = mock(AdminMfaProperties.class);
    private final AdminLoginGuard loginGuard = mock(AdminLoginGuard.class);
    private final OpsAdminAuthService service =
            new OpsAdminAuthService(
                    adminMapper,
                    roleRelationMapper,
                    accountStateMapper,
                    passwordEncoder,
                    tokenProvider,
                    permissionCache,
                    adminSessionRegistry,
                    totpService,
                    mfaCipher,
                    mfaChallenges,
                    mfaProperties,
                    loginGuard);

    private JwtProperties jwtProperties() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret("ops-console-auth-test-secret-32chars");
        properties.setTtlMinutes(60);
        return properties;
    }

    @Test
    void loginIssuesAdminTokenFromStoredHashAndEffectiveAuthorities() {
        AdminEntity admin = activeSuperAdmin(passwordEncoder.encode("ValidPass@123"));
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(permissionCache.getPermissionCodes(1L))
                .thenReturn(new LinkedHashSet<>(List.of("PERM_SYSTEM_READ", "PERM_USER_READ")));
        when(adminSessionRegistry.createSession(1L, "superadmin")).thenReturn("admin-session-1");

        ApiResult<AdminLoginResponse> result = loginWithMfa(admin, "superadmin", "ValidPass@123", null);

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
        verify(permissionCache).evict(1L);
        Claims claims = tokenProvider.parse(result.getData().accessToken());
        assertThat(claims.get("subjectType")).isEqualTo("ADMIN");
        assertThat(claims.get("sessionId")).isEqualTo("admin-session-1");
    }

    @Test
    void temporarySuperadminMfaBypassIssuesSessionWithoutChangingMfaBinding() {
        AdminEntity admin = activeSuperAdmin(passwordEncoder.encode("ValidPass@123"));
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(mfaProperties.isTemporarySuperadminBypassEnabled()).thenReturn(true);
        when(permissionCache.getPermissionCodes(1L))
                .thenReturn(new LinkedHashSet<>(List.of("PERM_SYSTEM_READ", "PERM_USER_READ")));
        when(adminSessionRegistry.createSession(1L, "superadmin", "10.0.0.8", "A1-Test-UA"))
                .thenReturn("admin-session-bypass");

        ApiResult<AdminLoginResponse> result = service.login(
                new AdminLoginRequest("superadmin", "ValidPass@123"),
                "10.0.0.8",
                "A1-Test-UA");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isNotBlank();
        assertThat(result.getData().session().username()).isEqualTo("superadmin");
        assertThat(result.getData().mfa()).isNull();
        verify(mfaChallenges, never()).create(any(), anyString(), anyString(), any(Boolean.class));
        verify(accountStateMapper, never()).upsertMfaBinding(any(), anyString(), any());
        verify(adminSessionRegistry).createSession(1L, "superadmin", "10.0.0.8", "A1-Test-UA");
        verify(loginGuard).recordSuccess("superadmin");
    }

    @Test
    void temporarySuperadminMfaBypassNeverAppliesToRegularAdmins() {
        AdminEntity admin = activeAdmin(4L, "superadmin", "Lookalike", passwordEncoder.encode("ValidPass@123"));
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(mfaProperties.isTemporarySuperadminBypassEnabled()).thenReturn(true);
        when(accountStateMapper.selectActiveByAdminId(4L)).thenReturn(boundState(4L));
        when(mfaCipher.decrypt("encrypted-secret")).thenReturn("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        when(mfaCipher.encrypt(anyString())).thenReturn("encrypted-secret");
        when(mfaProperties.getChallengeTtlSeconds()).thenReturn(300L);
        when(mfaChallenges.create(4L, "superadmin", "encrypted-secret", false))
                .thenReturn("regular-admin-challenge");

        ApiResult<AdminLoginResponse> result = service.login(
                new AdminLoginRequest("superadmin", "ValidPass@123"),
                "10.0.0.8",
                "A1-Test-UA");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isNull();
        assertThat(result.getData().session()).isNull();
        assertThat(result.getData().mfa().challengeId()).isEqualTo("regular-admin-challenge");
        verify(adminSessionRegistry, never()).createSession(any(), anyString(), anyString(), anyString());
    }

    @Test
    void temporarySuperadminMfaBypassDoesNotApplyToOtherSuperAdminAccounts() {
        AdminEntity admin = activeSuperAdmin(passwordEncoder.encode("ValidPass@123"));
        admin.setId(2L);
        admin.setUsername("backup.superadmin");
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(mfaProperties.isTemporarySuperadminBypassEnabled()).thenReturn(true);
        when(accountStateMapper.selectActiveByAdminId(2L)).thenReturn(boundState(2L));
        when(mfaCipher.decrypt("encrypted-secret")).thenReturn("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        when(mfaCipher.encrypt(anyString())).thenReturn("encrypted-secret");
        when(mfaProperties.getChallengeTtlSeconds()).thenReturn(300L);
        when(mfaChallenges.create(2L, "backup.superadmin", "encrypted-secret", false))
                .thenReturn("backup-super-challenge");

        ApiResult<AdminLoginResponse> result = service.login(
                new AdminLoginRequest("backup.superadmin", "ValidPass@123"),
                "10.0.0.8",
                "A1-Test-UA");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isNull();
        assertThat(result.getData().mfa().challengeId()).isEqualTo("backup-super-challenge");
        verify(adminSessionRegistry, never()).createSession(any(), anyString(), anyString(), anyString());
    }

    @Test
    void loginUsesRoleRelationForNonSuperAdminSessions() {
        AdminEntity admin = activeAdmin(4L, "risk.shift", "风险值班", passwordEncoder.encode("RiskShift@123"));
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(roleRelationMapper.activeRoleCode(4L)).thenReturn("RISK");
        when(permissionCache.getPermissionCodes(4L))
                .thenReturn(new LinkedHashSet<>(List.of("PERM_RISK_READ", "PERM_RISK_WRITE")));
        when(roleRelationMapper.selectActiveMenuNodes(4L)).thenReturn(List.of(
                new AdminLoginResponse.EffectiveMenuNode("risk", "风控", null, null, 1),
                new AdminLoginResponse.EffectiveMenuNode("risk_cases", "风险案件", "/risk/cases", "risk", 2)));
        when(adminSessionRegistry.createSession(4L, "risk.shift")).thenReturn("admin-session-4");

        ApiResult<AdminLoginResponse> result = loginWithMfa(admin, "risk.shift", "RiskShift@123", null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().session().role()).isEqualTo("risk");
        assertThat(result.getData().session().roleCode()).isEqualTo("RISK");
        assertThat(result.getData().session().effectiveMenus()).containsExactly("risk", "risk_cases");
        assertThat(result.getData().session().authorities())
                .containsExactly("PERM_RISK_READ", "PERM_RISK_WRITE");
    }

    @Test
    void loginReturnsOnlyEffectiveA7MenuMetadataForA6GrantedMenus() {
        AdminEntity admin = activeAdmin(4L, "content.shift", "内容值班", passwordEncoder.encode("ContentShift@123"));
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(roleRelationMapper.activeRoleCode(4L)).thenReturn("CONTENT");
        when(permissionCache.getPermissionCodes(4L)).thenReturn(new LinkedHashSet<>(List.of("content_i7_read")));
        when(roleRelationMapper.selectActiveMenuNodes(4L)).thenReturn(List.of(
                new AdminLoginResponse.EffectiveMenuNode("I", "内容与合规 CMS", null, null, 9),
                new AdminLoginResponse.EffectiveMenuNode("I7", "教程中心", "/content/learn", "I", 6)));
        when(adminSessionRegistry.createSession(4L, "content.shift")).thenReturn("admin-session-content");

        ApiResult<AdminLoginResponse> result = loginWithMfa(admin, "content.shift", "ContentShift@123", null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().session().effectiveMenus()).containsExactly("I", "I7");
        assertThat(result.getData().session().effectiveMenuNodes())
                .extracting(AdminLoginResponse.EffectiveMenuNode::menuCode)
                .containsExactly("I", "I7");
    }

    @Test
    void loginPreservesCustomRoleCodeInsteadOfDegradingToAuditor() {
        AdminEntity admin = activeAdmin(9L, "custom.ops", "Custom Ops", passwordEncoder.encode("CustomOps@123"));
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(roleRelationMapper.activeRoleCode(9L)).thenReturn("CUSTOM_SETTLEMENT");
        when(roleRelationMapper.selectActiveMenuNodes(9L)).thenReturn(List.of(
                new AdminLoginResponse.EffectiveMenuNode("treasury", "资金", null, null, 1),
                new AdminLoginResponse.EffectiveMenuNode("settlement", "结算", "/treasury/settlement", "treasury", 2)));
        when(permissionCache.getPermissionCodes(9L))
                .thenReturn(new LinkedHashSet<>(List.of("finance_d4_read")));
        when(adminSessionRegistry.createSession(9L, "custom.ops")).thenReturn("admin-session-9");

        ApiResult<AdminLoginResponse> result = loginWithMfa(admin, "custom.ops", "CustomOps@123", null);

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
        state.setTfaRequired(1);
        state.setTfaSecretEncrypted("encrypted-secret");
        state.setTfaBoundAt(LocalDateTime.now());
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(accountStateMapper.selectActiveByAdminId(4L)).thenReturn(state);
        when(roleRelationMapper.activeRoleCode(4L)).thenReturn("RISK");
        when(permissionCache.getPermissionCodes(4L)).thenReturn(new LinkedHashSet<>(List.of("PERM_RISK_READ")));
        when(adminSessionRegistry.createSession(4L, "risk.shift")).thenReturn("admin-session-4");

        ApiResult<AdminLoginResponse> result = loginWithMfa(admin, "risk.shift", "RiskShift@123", state);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().session().passwordChangeRequired()).isTrue();
        assertThat(result.getData().session().authorities()).isEmpty();
        assertThat(result.getData().session().effectiveMenus()).isEmpty();
        assertThat(result.getData().session().effectiveMenuNodes()).isEmpty();
        Claims claims = tokenProvider.parse(result.getData().accessToken());
        assertThat(claims.get("authorities", List.class)).isEmpty();
    }

    @Test
    void loginTreatsLegacyCredentialDeliveryStatesAsPasswordChangeRequired() {
        AdminEntity admin = activeAdmin(4L, "risk.shift", "风险值班", passwordEncoder.encode("RiskShift@123"));
        AdminAccountStateEntity state = new AdminAccountStateEntity();
        state.setAdminId(4L);
        state.setCredentialDeliveryStatus("HANDOFF_PENDING");
        state.setTfaRequired(1);
        state.setTfaSecretEncrypted("encrypted-secret");
        state.setTfaBoundAt(LocalDateTime.now());
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(accountStateMapper.selectActiveByAdminId(4L)).thenReturn(state);
        when(roleRelationMapper.activeRoleCode(4L)).thenReturn("RISK");
        when(permissionCache.getPermissionCodes(4L)).thenReturn(new LinkedHashSet<>(List.of("PERM_RISK_READ")));
        when(adminSessionRegistry.createSession(4L, "risk.shift")).thenReturn("admin-session-4");

        ApiResult<AdminLoginResponse> result = loginWithMfa(admin, "risk.shift", "RiskShift@123", state);

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
        when(adminSessionRegistry.createSession(4L, "risk.shift", "10.0.0.8", "A1-Test-UA"))
                .thenReturn("admin-session-new");
        when(adminMapper.updateById(any(AdminEntity.class))).thenAnswer(invocation -> {
            AdminEntity patch = invocation.getArgument(0);
            admin.setPasswordHash(patch.getPasswordHash());
            return 1;
        });
        var authentication = new UsernamePasswordAuthenticationToken("4", null, List.of());

        ApiResult<AdminLoginResponse> result =
                service.changePassword(
                        authentication,
                        new AdminPasswordChangeRequest("RiskShift@123", "NewRiskShift@4567"),
                        "10.0.0.8",
                        "A1-Test-UA");

        assertThat(result.getCode()).isZero();
        assertThat(passwordEncoder.matches("NewRiskShift@4567", admin.getPasswordHash())).isTrue();
        assertThat(result.getData().accessToken()).isNotBlank();
        assertThat(result.getData().session().passwordChangeRequired()).isFalse();
        verify(accountStateMapper).upsertCredentialStatus(4L, "ACTIVE");
        verify(accountStateMapper, never()).recordAuthenticatedLogin(any());
        verify(adminSessionRegistry).revokeSessionsExcept(4L, "admin-session-new");
        verify(adminSessionRegistry).createSession(4L, "risk.shift", "10.0.0.8", "A1-Test-UA");
    }

    @Test
    void changePasswordDoesNotMutatePasswordWhenFreshSessionCannotBeCreated() {
        AdminEntity admin = activeAdmin(4L, "risk.shift", "风险值班", passwordEncoder.encode("RiskShift@123"));
        String originalHash = admin.getPasswordHash();
        when(adminMapper.selectById(4L)).thenReturn(admin);
        when(adminSessionRegistry.createSession(4L, "risk.shift", "unknown", "unknown"))
                .thenThrow(new IllegalStateException("redis down"));
        var authentication = new UsernamePasswordAuthenticationToken("4", null, List.of());

        ApiResult<AdminLoginResponse> result =
                service.changePassword(authentication, new AdminPasswordChangeRequest("RiskShift@123", "NewRiskShift@4567"));

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
        when(adminMapper.selectOne(any())).thenReturn(activeSuperAdmin(passwordEncoder.encode("ValidPass@123")));

        ApiResult<AdminLoginResponse> result =
                service.login(new AdminLoginRequest("superadmin", "wrong"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.UNAUTHORIZED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("ADMIN_CREDENTIAL_INVALID");
        assertThat(result.getData()).isNull();
        verify(permissionCache, never()).evict(1L);
        verify(accountStateMapper, never()).recordAuthenticatedLogin(any());
    }

    @Test
    void verifyMfaRejectsAnAlreadyConsumedPasswordChallenge() {
        AdminMfaChallengeRegistry.Challenge challenge = new AdminMfaChallengeRegistry.Challenge(
                4L, "risk.shift", "encrypted-secret", false, 0);
        when(mfaChallenges.read("already-used")).thenReturn(challenge);
        when(mfaCipher.decrypt("encrypted-secret")).thenReturn("secret");
        when(totpService.matchingCounter("secret", "123456")).thenReturn(42L);
        when(mfaChallenges.consume("already-used")).thenReturn(null);

        ApiResult<AdminLoginResponse> result = service.verifyMfa(
                new AdminMfaVerifyRequest("already-used", "123456"), "10.0.0.8", "A1-Test-UA");

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.UNAUTHORIZED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("ADMIN_MFA_CHALLENGE_INVALID");
        verify(adminSessionRegistry, never()).createSession(any(), anyString(), anyString(), anyString());
    }

    @Test
    void verifyMfaRejectsAReplayedTotpCounterAcrossChallenges() {
        AdminMfaChallengeRegistry.Challenge challenge = new AdminMfaChallengeRegistry.Challenge(
                4L, "risk.shift", "encrypted-secret", false, 0);
        when(mfaChallenges.read("fresh-challenge")).thenReturn(challenge);
        when(mfaCipher.decrypt("encrypted-secret")).thenReturn("secret");
        when(totpService.matchingCounter("secret", "123456")).thenReturn(42L);
        when(mfaChallenges.consume("fresh-challenge")).thenReturn(challenge);
        when(mfaChallenges.claimTotpCounter(4L, 42L)).thenReturn(false);

        ApiResult<AdminLoginResponse> result = service.verifyMfa(
                new AdminMfaVerifyRequest("fresh-challenge", "123456"), "10.0.0.8", "A1-Test-UA");

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.UNAUTHORIZED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("ADMIN_MFA_CODE_REPLAYED");
        verify(adminSessionRegistry, never()).createSession(any(), anyString(), anyString(), anyString());
    }

    @Test
    void loginRejectsDisabledAdmin() {
        AdminEntity admin = activeSuperAdmin(passwordEncoder.encode("ValidPass@123"));
        admin.setStatus(0);
        when(adminMapper.selectOne(any())).thenReturn(admin);

        ApiResult<AdminLoginResponse> result =
                service.login(new AdminLoginRequest("superadmin", "ValidPass@123"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.FORBIDDEN.httpStatus());
        assertThat(result.getMessage()).isEqualTo("ADMIN_DISABLED");
    }

    @Test
    void currentSessionLoadsAdminByTokenSubjectAndKeepsTokenAuthorities() {
        when(adminMapper.selectById(1L)).thenReturn(activeSuperAdmin(passwordEncoder.encode("ValidPass@123")));
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

    private UsernamePasswordAuthenticationToken activityIdentity(String type, String sid) {
        var authentication = new UsernamePasswordAuthenticationToken("1", null, List.of());
        authentication.setDetails(sid == null ? java.util.Map.of("subjectType", type)
                : java.util.Map.of("subjectType", type, "sessionId", sid));
        return authentication;
    }

    @Test void activityUpgradesTheSameActiveSessionWithoutExpiration() {
        when(adminMapper.selectById(1L)).thenReturn(activeSuperAdmin("synthetic"));
        when(adminSessionRegistry.recordActivity(1L, "legacy-sid")).thenReturn(true);
        var result=service.activity(activityIdentity("ADMIN", "legacy-sid"), new ffdd.opsconsole.auth.dto.AdminActivityRequest(1L));
        assertThat(result.getCode()).isZero();
        var claims=tokenProvider.parse(result.getData().accessToken());
        assertThat(claims.getExpiration()).isNull();
        assertThat(claims.get("sessionId",String.class)).isEqualTo("legacy-sid");
        verify(adminSessionRegistry,never()).createSession(any(),anyString(),anyString(),anyString());
        verify(accountStateMapper, never()).recordAuthenticatedLogin(any());
    }

    @Test void activityRejectsStaleTabIdentityBeforeAnyStoreAccess() {
        var result=service.activity(activityIdentity("ADMIN", "sid"), new ffdd.opsconsole.auth.dto.AdminActivityRequest(2L));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("ADMIN_ACTIVITY_IDENTITY_MISMATCH");
        org.mockito.Mockito.verifyNoInteractions(adminSessionRegistry,adminMapper);
    }

    @Test void activityCannotRestoreRevokedOrExpiredRedisSession() {
        when(adminMapper.selectById(1L)).thenReturn(activeSuperAdmin("synthetic"));
        when(adminSessionRegistry.recordActivity(1L, "revoked")).thenReturn(false);
        var result=service.activity(activityIdentity("ADMIN", "revoked"), new ffdd.opsconsole.auth.dto.AdminActivityRequest(1L));
        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getData()).isNull();
        verify(adminSessionRegistry,never()).createSession(any(),anyString(),anyString(),anyString());
    }

    @Test void activityRequiresAdminSidAndPositiveExpectedIdentity() {
        for (var identity : List.of(activityIdentity("USER","sid"), activityIdentity("IMPERSONATION","sid"),activityIdentity("ADMIN",null))) {
            assertThat(service.activity(identity,new ffdd.opsconsole.auth.dto.AdminActivityRequest(1L)).getCode()).isEqualTo(401);
        }
        for (var body : List.of(new ffdd.opsconsole.auth.dto.AdminActivityRequest(0L),new ffdd.opsconsole.auth.dto.AdminActivityRequest(-1L),new ffdd.opsconsole.auth.dto.AdminActivityRequest(null))) {
            assertThat(service.activity(activityIdentity("ADMIN","sid"),body).getCode()).isEqualTo(422);
        }
        assertThat(service.activity(activityIdentity("ADMIN","sid"),null).getCode()).isEqualTo(422);
        org.mockito.Mockito.verifyNoInteractions(adminSessionRegistry);
    }

    @Test void activityPreservesDisabledAndPasswordChangeGates() {
        var admin=activeSuperAdmin("synthetic");admin.setStatus(0);
        when(adminMapper.selectById(1L)).thenReturn(admin);
        assertThat(service.activity(activityIdentity("ADMIN","sid"),new ffdd.opsconsole.auth.dto.AdminActivityRequest(1L)).getMessage()).isEqualTo("ADMIN_DISABLED");
        admin.setStatus(1);
        var state=new AdminAccountStateEntity();state.setCredentialDeliveryStatus("PASSWORD_CHANGE_REQUIRED");
        when(accountStateMapper.selectActiveByAdminId(1L)).thenReturn(state);
        assertThat(service.activity(activityIdentity("ADMIN","sid"),new ffdd.opsconsole.auth.dto.AdminActivityRequest(1L)).getMessage()).isEqualTo("ADMIN_PASSWORD_CHANGE_REQUIRED");
        org.mockito.Mockito.verifyNoInteractions(adminSessionRegistry);
    }

    @Test void activityStoreOutageReturnsNoTokenOrCookieMaterial() {
        when(adminMapper.selectById(1L)).thenReturn(activeSuperAdmin("synthetic"));
        when(adminSessionRegistry.recordActivity(1L,"sid")).thenThrow(new IllegalStateException("synthetic unavailable"));
        var result=service.activity(activityIdentity("ADMIN","sid"),new ffdd.opsconsole.auth.dto.AdminActivityRequest(1L));
        assertThat(result.getCode()).isEqualTo(503);assertThat(result.getData()).isNull();
    }

    @Test void meNeverRecordsExplicitActivity() {
        when(adminMapper.selectById(1L)).thenReturn(activeSuperAdmin("synthetic"));
        assertThat(service.current(activityIdentity("ADMIN","sid")).getCode()).isZero();
        org.mockito.Mockito.verifyNoInteractions(adminSessionRegistry);
        verify(accountStateMapper, never()).recordAuthenticatedLogin(any());
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

    @Test
    void completedMfaAuthenticationRecordsLoginAfterChallengeOnly() {
        AdminEntity admin = activeSuperAdmin(passwordEncoder.encode("ValidPass@123"));
        when(adminMapper.selectOne(any())).thenReturn(admin);

        var result = loginWithMfa(admin, "superadmin", "ValidPass@123", null);

        assertThat(result.getCode()).isZero();
        verify(accountStateMapper).recordAuthenticatedLogin(1L);
    }

    @Test
    void completedBypassAuthenticationRecordsLoginEvenWhenSameSecondWriteIsNoop() {
        prepareBypassLogin();
        when(accountStateMapper.recordAuthenticatedLogin(1L)).thenReturn(0);

        var result = service.login(new AdminLoginRequest("superadmin", "ValidPass@123"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().accessToken()).isNotBlank();
        verify(accountStateMapper).recordAuthenticatedLogin(1L);
    }

    @Test
    void invalidMfaDoesNotRecordLogin() {
        var challenge = new AdminMfaChallengeRegistry.Challenge(1L, "superadmin", "encrypted-secret", false, 0);
        when(mfaChallenges.read("invalid-code")).thenReturn(challenge);
        when(mfaCipher.decrypt("encrypted-secret")).thenReturn("synthetic-secret");

        var result = service.verifyMfa(new AdminMfaVerifyRequest("invalid-code", "000000"));

        assertThat(result.getCode()).isEqualTo(401);
        assertThat(result.getData()).isNull();
        verify(accountStateMapper, never()).recordAuthenticatedLogin(any());
        verify(adminSessionRegistry, never()).createSession(any(), anyString(), anyString(), anyString());
    }

    @Test
    void failedSessionCreationDoesNotRecordLogin() {
        prepareBypassLogin();
        when(adminSessionRegistry.createSession(1L, "superadmin", "unknown", "unknown"))
                .thenThrow(new IllegalStateException("synthetic store failure"));

        var result = service.login(new AdminLoginRequest("superadmin", "ValidPass@123"));

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getData()).isNull();
        verify(accountStateMapper, never()).recordAuthenticatedLogin(any());
    }

    @Test
    void signingFailureDoesNotRecordLoginAndRevokesOnlyFreshSession() {
        prepareBypassLogin();
        doThrow(new IllegalStateException("synthetic signer failure")).when(tokenProvider)
                .createToken(any(), anyString(), anyString(), any(), anyString());

        var result = service.login(new AdminLoginRequest("superadmin", "ValidPass@123"));

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getData()).isNull();
        verify(accountStateMapper, never()).recordAuthenticatedLogin(any());
        verify(adminSessionRegistry).revokeSession(1L, "fresh-login-session");
        verify(adminSessionRegistry, never()).revokeSessions(any());
    }

    @Test
    void responseMenuFailureDoesNotRecordLoginAndRevokesFreshSession() {
        prepareBypassLogin();
        when(roleRelationMapper.selectAllActiveMenuNodes()).thenThrow(new IllegalStateException("synthetic menu failure"));

        var result = service.login(new AdminLoginRequest("superadmin", "ValidPass@123"));

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getData()).isNull();
        verify(accountStateMapper, never()).recordAuthenticatedLogin(any());
        verify(adminSessionRegistry).revokeSession(1L, "fresh-login-session");
    }

    @Test
    void successGuardFailureDoesNotRecordLoginAndRevokesFreshSession() {
        prepareBypassLogin();
        doThrow(new IllegalStateException("synthetic guard failure")).when(loginGuard).recordSuccess("superadmin");

        var result = service.login(new AdminLoginRequest("superadmin", "ValidPass@123"));

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getData()).isNull();
        verify(accountStateMapper, never()).recordAuthenticatedLogin(any());
        verify(adminSessionRegistry).revokeSession(1L, "fresh-login-session");
    }

    @Test
    void loginTimestampStoreFailureHasNoSuccessfulResponseAndRevokesOnlyFreshSession() {
        prepareBypassLogin();
        when(accountStateMapper.recordAuthenticatedLogin(1L))
                .thenThrow(new DataAccessResourceFailureException("synthetic database failure"));

        assertThatThrownBy(() -> service.login(new AdminLoginRequest("superadmin", "ValidPass@123")))
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.getCode()).isEqualTo(503));
        verify(adminSessionRegistry).revokeSession(1L, "fresh-login-session");
        verify(adminSessionRegistry, never()).revokeSessions(any());
        verify(adminSessionRegistry, never()).revokeSessionsExcept(any(), anyString());
    }

    @Test
    void timestampFailureStillReturnsUnavailableWhenFreshSessionCleanupStoreAlsoFails() {
        prepareBypassLogin();
        when(accountStateMapper.recordAuthenticatedLogin(1L))
                .thenThrow(new DataAccessResourceFailureException("synthetic database failure"));
        doThrow(new IllegalStateException("synthetic cleanup failure"))
                .when(adminSessionRegistry).revokeSession(1L, "fresh-login-session");

        assertThatThrownBy(() -> service.login(new AdminLoginRequest("superadmin", "ValidPass@123")))
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.getCode()).isEqualTo(503));
        verify(adminSessionRegistry, never()).revokeSessions(any());
    }

    @Test
    void authenticatedTransactionCommitKeepsFreshSession() {
        prepareBypassLogin();
        var result = new TransactionTemplate(new AuthTestTransactionManager(false))
                .execute(status -> service.login(new AdminLoginRequest("superadmin", "ValidPass@123")));

        assertThat(result.getCode()).isZero();
        verify(accountStateMapper).recordAuthenticatedLogin(1L);
        verify(adminSessionRegistry, never()).revokeSession(any(), anyString());
    }

    @Test
    void authenticatedTransactionRollbackRevokesOnlyFreshSession() {
        prepareBypassLogin();
        var transaction = new TransactionTemplate(new AuthTestTransactionManager(false));

        assertThatThrownBy(() -> transaction.execute(status -> {
            assertThat(service.login(new AdminLoginRequest("superadmin", "ValidPass@123")).getCode()).isZero();
            throw new IllegalStateException("synthetic outer rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("synthetic outer rollback");
        verify(adminSessionRegistry).revokeSession(1L, "fresh-login-session");
        verify(adminSessionRegistry, never()).revokeSessions(any());
    }

    @Test
    void authenticatedTransactionCommitExceptionCannotReturnSuccessAndRevokesFreshSession() {
        prepareBypassLogin();
        var transaction = new TransactionTemplate(new AuthTestTransactionManager(true));

        assertThatThrownBy(() -> transaction.execute(status ->
                service.login(new AdminLoginRequest("superadmin", "ValidPass@123"))))
                .isInstanceOf(TransactionSystemException.class);
        verify(adminSessionRegistry).revokeSession(1L, "fresh-login-session");
        verify(adminSessionRegistry, never()).revokeSessions(any());
    }

    @Test
    void mfaTimestampWriteFailureRollsBackAndCompensatesFreshSession() {
        AdminEntity admin = activeSuperAdmin(passwordEncoder.encode("ValidPass@123"));
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(accountStateMapper.recordAuthenticatedLogin(1L))
                .thenThrow(new DataAccessResourceFailureException("synthetic database failure"));
        var transaction = new TransactionTemplate(new AuthTestTransactionManager(false));

        assertThatThrownBy(() -> transaction.execute(status -> loginWithMfa(admin, "superadmin", "ValidPass@123", null)))
                .isInstanceOfSatisfying(BizException.class, failure -> assertThat(failure.getCode()).isEqualTo(503));
        verify(adminSessionRegistry, atLeastOnce()).revokeSession(1L, "admin-session-1");
        verify(adminSessionRegistry, never()).revokeSessions(any());
    }

    private static final class AuthTestTransactionManager extends AbstractPlatformTransactionManager {
        private final boolean failCommit;

        private AuthTestTransactionManager(boolean failCommit) { this.failCommit = failCommit; }
        @Override protected Object doGetTransaction() { return new Object(); }
        @Override protected void doBegin(Object transaction, TransactionDefinition definition) { }
        @Override protected void doCommit(DefaultTransactionStatus status) {
            if (failCommit) throw new TransactionSystemException("synthetic commit failure");
        }
        @Override protected void doRollback(DefaultTransactionStatus status) { }
    }

    private void prepareBypassLogin() {
        when(adminMapper.selectOne(any())).thenReturn(activeSuperAdmin(passwordEncoder.encode("ValidPass@123")));
        when(mfaProperties.isTemporarySuperadminBypassEnabled()).thenReturn(true);
        when(adminSessionRegistry.createSession(1L, "superadmin", "unknown", "unknown"))
                .thenReturn("fresh-login-session");
    }

    private ApiResult<AdminLoginResponse> loginWithMfa(
            AdminEntity admin, String username, String password, AdminAccountStateEntity suppliedState) {
        when(adminMapper.selectById(admin.getId())).thenReturn(admin);
        when(adminSessionRegistry.createSession(
                org.mockito.ArgumentMatchers.eq(admin.getId()),
                org.mockito.ArgumentMatchers.eq(admin.getUsername()),
                anyString(),
                anyString())).thenReturn("admin-session-" + admin.getId());
        AdminAccountStateEntity state = suppliedState == null ? boundState(admin.getId()) : suppliedState;
        when(accountStateMapper.selectActiveByAdminId(admin.getId())).thenReturn(state);
        when(mfaCipher.decrypt("encrypted-secret")).thenReturn("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ");
        when(mfaCipher.encrypt(anyString())).thenReturn("encrypted-secret");
        when(mfaProperties.getChallengeTtlSeconds()).thenReturn(300L);
        when(mfaChallenges.create(admin.getId(), admin.getUsername(), "encrypted-secret", false))
                .thenReturn("challenge-" + admin.getId());
        when(mfaChallenges.read("challenge-" + admin.getId())).thenReturn(
                new AdminMfaChallengeRegistry.Challenge(
                        admin.getId(), admin.getUsername(), "encrypted-secret", false, 0));
        when(mfaChallenges.consume("challenge-" + admin.getId())).thenReturn(
                new AdminMfaChallengeRegistry.Challenge(
                        admin.getId(), admin.getUsername(), "encrypted-secret", false, 0));
        when(mfaChallenges.claimTotpCounter(admin.getId(), 42L)).thenReturn(true);
        when(totpService.matchingCounter(anyString(), anyString())).thenReturn(42L);

        ApiResult<AdminLoginResponse> challenged = service.login(new AdminLoginRequest(username, password));
        assertThat(challenged.getCode()).isZero();
        assertThat(challenged.getData().accessToken()).isNull();
        assertThat(challenged.getData().session()).isNull();
        assertThat(challenged.getData().mfa().challengeId()).isEqualTo("challenge-" + admin.getId());
        verify(accountStateMapper, never()).recordAuthenticatedLogin(any());

        return service.verifyMfa(new AdminMfaVerifyRequest("challenge-" + admin.getId(), "123456"));
    }

    private AdminAccountStateEntity boundState(Long adminId) {
        AdminAccountStateEntity state = new AdminAccountStateEntity();
        state.setAdminId(adminId);
        state.setTfaRequired(1);
        state.setTfaSecretEncrypted("encrypted-secret");
        state.setTfaBoundAt(LocalDateTime.now());
        state.setCredentialDeliveryStatus("ACTIVE");
        return state;
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

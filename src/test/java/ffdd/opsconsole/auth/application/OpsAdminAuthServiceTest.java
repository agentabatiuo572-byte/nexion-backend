package ffdd.opsconsole.auth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.dto.AdminLoginRequest;
import ffdd.opsconsole.auth.dto.AdminLoginResponse;
import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRolePermissionMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.security.AdminSessionRegistry;
import ffdd.opsconsole.shared.security.JwtProperties;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import io.jsonwebtoken.Claims;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

class OpsAdminAuthServiceTest {

    private final AdminMapper adminMapper = mock(AdminMapper.class);
    private final AdminRolePermissionMapper permissionMapper = mock(AdminRolePermissionMapper.class);
    private final AdminRoleRelationMapper roleRelationMapper = mock(AdminRoleRelationMapper.class);
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();
    private final JwtTokenProvider tokenProvider = new JwtTokenProvider(jwtProperties());
    private final AdminSessionRegistry adminSessionRegistry = mock(AdminSessionRegistry.class);
    private final OpsAdminAuthService service =
            new OpsAdminAuthService(
                    adminMapper,
                    permissionMapper,
                    roleRelationMapper,
                    passwordEncoder,
                    tokenProvider,
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
        when(permissionMapper.selectActivePermissionCodes(1L))
                .thenReturn(List.of("PERM_SYSTEM_READ", "PERM_USER_READ"));
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
        assertThat(result.getData().session().authorities())
                .contains("PERM_SYSTEM_READ", "PERM_SYSTEM_WRITE", "PERM_SUPPORT_SEAT_WRITE", "PERM_USER_READ");
        Claims claims = tokenProvider.parse(result.getData().accessToken());
        assertThat(claims.get("subjectType")).isEqualTo("ADMIN");
        assertThat(claims.get("sessionId")).isEqualTo("admin-session-1");
    }

    @Test
    void loginUsesRoleRelationForNonSuperAdminSessions() {
        AdminEntity admin = activeAdmin(4L, "risk.shift", "风险值班", passwordEncoder.encode("RiskShift@123"));
        when(adminMapper.selectOne(any())).thenReturn(admin);
        when(roleRelationMapper.activeRoleCode(4L)).thenReturn("RISK");
        when(permissionMapper.selectActivePermissionCodes(4L))
                .thenReturn(List.of("PERM_RISK_READ", "PERM_RISK_WRITE"));
        when(adminSessionRegistry.createSession(4L, "risk.shift")).thenReturn("admin-session-4");

        ApiResult<AdminLoginResponse> result =
                service.login(new AdminLoginRequest("risk.shift", "RiskShift@123"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().session().role()).isEqualTo("risk");
        assertThat(result.getData().session().authorities())
                .containsExactly("PERM_RISK_READ", "PERM_RISK_WRITE");
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

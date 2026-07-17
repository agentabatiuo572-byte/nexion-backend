package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

class SuperAdminAuthorizationTest {
    private final AdminMapper adminMapper = mock(AdminMapper.class);
    private final SuperAdminAuthorization authorization = new SuperAdminAuthorization(adminMapper);

    @Test
    void activeSuperAdminIsAllowed() {
        when(adminMapper.selectById(1L)).thenReturn(admin(1, 1, 0));

        assertThat(authorization.isSuperAdmin(authentication("1", "ADMIN"))).isTrue();
    }

    @Test
    void readPermissionDoesNotElevateARegularAdmin() {
        when(adminMapper.selectById(2L)).thenReturn(admin(0, 1, 0));

        assertThat(authorization.isSuperAdmin(authentication("2", "ADMIN"))).isFalse();
    }

    @Test
    void nonAdminAndDisabledOrDeletedAccountsFailClosed() {
        when(adminMapper.selectById(3L)).thenReturn(admin(1, 0, 0));
        when(adminMapper.selectById(4L)).thenReturn(admin(1, 1, 1));

        assertThat(authorization.isSuperAdmin(authentication("3", "ADMIN"))).isFalse();
        assertThat(authorization.isSuperAdmin(authentication("4", "ADMIN"))).isFalse();
        assertThat(authorization.isSuperAdmin(authentication("1", "USER"))).isFalse();
        assertThat(authorization.isSuperAdmin(authentication("not-an-id", "ADMIN"))).isFalse();
    }

    private UsernamePasswordAuthenticationToken authentication(String principal, String subjectType) {
        var authentication = new UsernamePasswordAuthenticationToken(
                principal, "n/a", List.of(new SimpleGrantedAuthority("emergency_j2_read")));
        authentication.setDetails(Map.of("subjectType", subjectType));
        return authentication;
    }

    private AdminEntity admin(int superAdmin, int status, int deleted) {
        AdminEntity admin = new AdminEntity();
        admin.setSuperAdmin(superAdmin);
        admin.setStatus(status);
        admin.setIsDeleted(deleted);
        return admin;
    }
}

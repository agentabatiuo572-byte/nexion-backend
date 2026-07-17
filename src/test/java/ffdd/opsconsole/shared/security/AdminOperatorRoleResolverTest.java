package ffdd.opsconsole.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.infrastructure.AdminEntity;
import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.auth.mapper.AdminRoleRelationMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class AdminOperatorRoleResolverTest {
    private final AdminMapper adminMapper = mock(AdminMapper.class);
    private final AdminRoleRelationMapper roleRelationMapper = mock(AdminRoleRelationMapper.class);
    private final AdminOperatorRoleResolver resolver =
            new AdminOperatorRoleResolver(adminMapper, roleRelationMapper);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesSuperAdminFromAuthenticatedDatabaseRecord() {
        authenticate("41");
        AdminEntity admin = activeAdmin(41L, 1);
        when(adminMapper.selectById(41L)).thenReturn(admin);

        assertThat(resolver.resolve()).isEqualTo("超管");
    }

    @Test
    void resolvesRiskRoleFromAuthenticatedRoleRelation() {
        authenticate("52");
        AdminEntity admin = activeAdmin(52L, 0);
        when(adminMapper.selectById(52L)).thenReturn(admin);
        when(roleRelationMapper.activeRoleCode(52L)).thenReturn("RISK");

        assertThat(resolver.resolve()).isEqualTo("风控");
    }

    @Test
    void resolvesMissingRoleRelationAsAuditorLikeTheAuthenticationSession() {
        authenticate("61");
        AdminEntity admin = activeAdmin(61L, 0);
        when(adminMapper.selectById(61L)).thenReturn(admin);
        when(roleRelationMapper.activeRoleCode(61L)).thenReturn(null);

        assertThat(resolver.resolve()).isEqualTo("审计");
    }

    @Test
    void neverFallsBackToClientRoleWhenAuthenticationCannotBeResolved() {
        authenticate("not-an-admin-id");

        assertThat(resolver.resolve()).isEqualTo("认证运营员");
    }

    private void authenticate(String principal) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    private AdminEntity activeAdmin(Long id, int superAdmin) {
        AdminEntity admin = new AdminEntity();
        admin.setId(id);
        admin.setSuperAdmin(superAdmin);
        admin.setStatus(1);
        admin.setIsDeleted(0);
        return admin;
    }
}

package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.dto.PlatformMenuNodeCreateRequest;
import ffdd.opsconsole.platform.infrastructure.AdminMenuEntity;
import ffdd.opsconsole.platform.mapper.AdminMenuMapper;
import ffdd.opsconsole.platform.mapper.AdminRoleMenuMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsPlatformMenuServiceTest {
    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void emptyDictionaryProducesAnEmptyTreeWithoutSyntheticMenus() {
        AdminMenuMapper menuMapper = mock(AdminMenuMapper.class);
        when(menuMapper.selectAllActive()).thenReturn(List.of());
        OpsPlatformMenuService service = new OpsPlatformMenuService(
                menuMapper, mock(AdminRoleMenuMapper.class), mock(AuditLogService.class));

        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().tree()).isEmpty();
        assertThat(result.getData().domainCount()).isZero();
    }

    @Test
    void menuAuditUsesAuthenticatedAdminInsteadOfSpoofedBodyOperator() {
        AdminMenuMapper menuMapper = mock(AdminMenuMapper.class);
        AuditLogService auditLogService = mock(AuditLogService.class);
        when(menuMapper.selectCount(any())).thenReturn(0L);
        doAnswer(invocation -> {
            AdminMenuEntity entity = invocation.getArgument(0);
            entity.setId(91L);
            return 1;
        }).when(menuMapper).insert(any(AdminMenuEntity.class));
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("41", null, List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN", "username", "alice.admin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        OpsPlatformMenuService service = new OpsPlatformMenuService(
                menuMapper, mock(AdminRoleMenuMapper.class), auditLogService);

        var result = service.createNode("idem-menu-create",
                new PlatformMenuNodeCreateRequest("Z9", "Test Menu", "测试菜单", null,
                        "/test", null, 99, "create menu for test", "mallory"));

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<AuditLogWriteRequest> audit = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(audit.capture());
        assertThat(audit.getValue().getActorUsername()).isEqualTo("alice.admin");
    }
}

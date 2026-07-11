package ffdd.opsconsole.platform.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.mapper.AdminMenuMapper;
import ffdd.opsconsole.platform.mapper.AdminRoleMenuMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsPlatformMenuServiceTest {
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
}

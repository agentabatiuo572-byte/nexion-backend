package ffdd.opsconsole.auth.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.auth.application.AccountDeletionAdminService;
import ffdd.opsconsole.auth.dto.AccountDeletionAdminPage;
import ffdd.opsconsole.auth.dto.AccountDeletionAdminView;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;

class AccountDeletionAdminContractTest {
    @Test
    void exposesDiscoverableListAndAuditedCommandsBehindUserPermissions() {
        assertThat(Arrays.stream(OpsAccountDeletionController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(GetMapping.class))
                .map(method -> method.getAnnotation(PreAuthorize.class).value())
                .toList()).contains("hasAuthority('user_c1_read')");
        assertThat(Arrays.stream(OpsAccountDeletionController.class.getDeclaredMethods())
                .filter(method -> method.isAnnotationPresent(PostMapping.class))
                .map(method -> method.getAnnotation(PreAuthorize.class).value())
                .distinct().toList()).containsExactly("hasAuthority('user_c1_write')");
    }

    @Test
    void listReturnsTheServiceAuthorityPageRatherThanAnUnboundedList() {
        AccountDeletionAdminService service = mock(AccountDeletionAdminService.class);
        OpsAccountDeletionController controller = new OpsAccountDeletionController(service);
        AccountDeletionAdminView record = new AccountDeletionAdminView("ADR-0123456789abcdef0123456789abcdef", 42L,
                "REQUESTED", 1L, LocalDateTime.of(2026, 9, 9, 0, 0), null, null, null, null, false, false);
        AccountDeletionAdminPage page = new AccountDeletionAdminPage(List.of(record), 3L, 2, 10);
        when(service.page("REQUESTED", 2, 10)).thenReturn(page);

        var result = controller.list("REQUESTED", 2, 10);

        assertThat(result.getData()).isEqualTo(page);
        verify(service).page("REQUESTED", 2, 10);
    }
}

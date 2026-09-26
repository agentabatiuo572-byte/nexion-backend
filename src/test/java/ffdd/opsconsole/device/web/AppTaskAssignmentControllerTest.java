package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.application.AppTaskAssignmentService;
import ffdd.opsconsole.device.dto.AppPhoneRuntimeRequest;
import ffdd.opsconsole.shared.exception.BizException;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;

class AppTaskAssignmentControllerTest {
    private final AppTaskAssignmentService service = mock(AppTaskAssignmentService.class);
    private final AppTaskAssignmentController controller = new AppTaskAssignmentController(service);

    @Test
    void receiptPaginationRejectsNonIntegerQueryValuesAsA422BusinessError() {
        Authentication authentication = userAuthentication();

        assertThatThrownBy(() -> controller.receipts("abc", "20", null, authentication))
                .isInstanceOf(BizException.class)
                .hasMessage("TASK_RECEIPT_PAGE_INVALID");
        assertThatThrownBy(() -> controller.receipts("0", "99999999999", null, authentication))
                .isInstanceOf(BizException.class)
                .hasMessage("TASK_RECEIPT_PAGE_INVALID");
        verify(service, never()).receipts(7L, 0, 20, null);
    }

    @Test
    void receiptPaginationPassesTheOpaqueCursorWithoutAttemptingToParseIt() {
        Authentication authentication = userAuthentication();
        String cursor = "djJ8MjAyNi0wOC0xMFQxMTo1OXwxMDB8MTA1";

        controller.receipts("0", "20", cursor, authentication);

        verify(service).receipts(7L, 0, 20, cursor);
    }

    @Test
    void phoneRuntimeUsesTheAuthenticatedUserSubject() {
        var request = new AppPhoneRuntimeRequest(11L, 20, true, false);
        controller.phoneRuntime(request, userAuthentication());
        verify(service).phoneRuntime(7L, request);

        Authentication admin = mock(Authentication.class);
        when(admin.isAuthenticated()).thenReturn(true);
        when(admin.getPrincipal()).thenReturn("7");
        when(admin.getDetails()).thenReturn(Map.of("subjectType", "ADMIN"));
        assertThat(controller.phoneRuntime(request, admin).getCode()).isEqualTo(403);
        verify(service, never()).phoneRuntime(null, request);
    }

    private Authentication userAuthentication() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("7");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER"));
        return authentication;
    }
}

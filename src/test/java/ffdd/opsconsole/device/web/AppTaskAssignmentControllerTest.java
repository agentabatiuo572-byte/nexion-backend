package ffdd.opsconsole.device.web;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.device.application.AppTaskAssignmentService;
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

        assertThatThrownBy(() -> controller.receipts("abc", "20", authentication))
                .isInstanceOf(BizException.class)
                .hasMessage("TASK_RECEIPT_PAGE_INVALID");
        assertThatThrownBy(() -> controller.receipts("0", "99999999999", authentication))
                .isInstanceOf(BizException.class)
                .hasMessage("TASK_RECEIPT_PAGE_INVALID");
        verify(service, never()).receipts(7L, 0, 20);
    }

    private Authentication userAuthentication() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.isAuthenticated()).thenReturn(true);
        when(authentication.getPrincipal()).thenReturn("7");
        when(authentication.getDetails()).thenReturn(Map.of("subjectType", "USER"));
        return authentication;
    }
}

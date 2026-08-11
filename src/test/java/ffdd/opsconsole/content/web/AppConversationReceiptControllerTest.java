package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.AppSupportService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppConversationReceiptControllerTest {
    private final AppSupportService service = mock(AppSupportService.class);
    private final AppConversationReceiptController controller = new AppConversationReceiptController(service);

    @Test
    void authenticatedUserMarksOwnConversationReadAndPublishesReceipt() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("1001", null, List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        when(service.markConversationRead(1001L, "CV-1", 1L)).thenReturn(ApiResult.ok(null));

        ApiResult<Void> result = controller.markReadReceipt("CV-1", new AppConversationReceiptRequest(1L), authentication);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isNull();

        verify(service).markConversationRead(1001L, "CV-1", 1L);
    }

    @Test
    void adminSubjectCannotForgeAUserReceipt() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("1", null, List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN"));

        assertThat(controller.markReadReceipt("CV-1", new AppConversationReceiptRequest(1L), authentication).getCode()).isEqualTo(403);

        verify(service, never()).markConversationRead(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }
}

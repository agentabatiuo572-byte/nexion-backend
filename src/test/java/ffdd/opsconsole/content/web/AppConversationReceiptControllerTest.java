package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.ConversationMessageEvent;
import ffdd.opsconsole.content.application.OpsConversationService;
import ffdd.opsconsole.shared.api.ApiResult;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class AppConversationReceiptControllerTest {
    private final OpsConversationService service = mock(OpsConversationService.class);
    private final ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
    private final AppConversationReceiptController controller = new AppConversationReceiptController(service, publisher);

    @Test
    void authenticatedUserMarksOwnConversationReadAndPublishesReceipt() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("1001", null, List.of());
        authentication.setDetails(Map.of("subjectType", "USER"));
        when(service.markReadReceipt("CV-1", 1L, 1001L)).thenReturn(ApiResult.ok(null));

        ApiResult<Void> result = controller.markReadReceipt("CV-1", new AppConversationReceiptRequest(1L), authentication);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isNull();

        verify(service).markReadReceipt("CV-1", 1L, 1001L);
        verify(publisher).publishEvent(org.mockito.ArgumentMatchers.any(ConversationMessageEvent.class));
    }

    @Test
    void adminSubjectCannotForgeAUserReceipt() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("1", null, List.of());
        authentication.setDetails(Map.of("subjectType", "ADMIN"));

        assertThat(controller.markReadReceipt("CV-1", new AppConversationReceiptRequest(1L), authentication).getCode()).isEqualTo(403);

        verify(service, never()).markReadReceipt(
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyLong());
        verify(publisher, never()).publishEvent(org.mockito.ArgumentMatchers.any());
    }
}

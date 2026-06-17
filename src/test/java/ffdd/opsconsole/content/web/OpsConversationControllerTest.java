package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.content.application.OpsConversationService;
import ffdd.opsconsole.content.dto.ConversationTransferRequest;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsConversationControllerTest {
    private final OpsConversationService conversationService = mock(OpsConversationService.class);
    private final OpsConversationController controller = new OpsConversationController(conversationService);

    @Test
    void overviewDelegatesToService() {
        when(conversationService.overview()).thenReturn(ApiResult.ok(Map.of("open", 1)));

        assertThat(controller.overview().getData()).containsEntry("open", 1);

        verify(conversationService).overview();
    }

    @Test
    void transferDelegatesWithIdempotencyHeader() {
        ConversationTransferRequest request =
                new ConversationTransferRequest("agent", "agent-2", "Agent Two", "needs specialist", "agent-1");
        when(conversationService.transfer("CV-1", "idem-i9", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.transfer("CV-1", "idem-i9", request).getCode()).isZero();

        verify(conversationService).transfer("CV-1", "idem-i9", request);
    }
}

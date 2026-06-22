package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.content.application.OpsConversationService;
import ffdd.opsconsole.content.dto.ConversationArchiveRequest;
import ffdd.opsconsole.content.dto.ConversationFallbackRequest;
import ffdd.opsconsole.content.dto.ConversationInitiateRequest;
import ffdd.opsconsole.content.dto.ConversationReplyRequest;
import ffdd.opsconsole.content.dto.ConversationStatusRequest;
import ffdd.opsconsole.content.dto.ConversationTicketRequest;
import ffdd.opsconsole.content.dto.ConversationTransferDecisionRequest;
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

    @Test
    void replyDelegatesWithIdempotencyHeader() {
        ConversationReplyRequest request = new ConversationReplyRequest("hello", "agent reply", "agent-1");
        when(conversationService.reply("CV-1", "idem-i9-reply", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.reply("CV-1", "idem-i9-reply", request).getCode()).isZero();

        verify(conversationService).reply("CV-1", "idem-i9-reply", request);
    }

    @Test
    void updateStatusDelegatesWithIdempotencyHeader() {
        ConversationStatusRequest request = new ConversationStatusRequest("RESOLVED", "finish conversation", "agent-1");
        when(conversationService.updateStatus("CV-1", "idem-i9-status", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.updateStatus("CV-1", "idem-i9-status", request).getCode()).isZero();

        verify(conversationService).updateStatus("CV-1", "idem-i9-status", request);
    }

    @Test
    void archiveDelegatesWithIdempotencyHeader() {
        ConversationArchiveRequest request = new ConversationArchiveRequest(true, "archive resolved session", "agent-1");
        when(conversationService.archive("CV-1", "idem-i9-archive", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.archive("CV-1", "idem-i9-archive", request).getCode()).isZero();

        verify(conversationService).archive("CV-1", "idem-i9-archive", request);
    }

    @Test
    void fallbackDelegatesWithIdempotencyHeader() {
        ConversationFallbackRequest request = new ConversationFallbackRequest("pending transfer timeout", "agent-1");
        when(conversationService.fallbackTransfer("CV-1", "idem-i9-fallback", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.fallbackTransfer("CV-1", "idem-i9-fallback", request).getCode()).isZero();

        verify(conversationService).fallbackTransfer("CV-1", "idem-i9-fallback", request);
    }

    @Test
    void waitTransferDelegatesWithIdempotencyHeader() {
        ConversationTransferDecisionRequest request = new ConversationTransferDecisionRequest("continue waiting", "agent-1");
        when(conversationService.waitTransfer("CV-1", "idem-i9-wait", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.waitTransfer("CV-1", "idem-i9-wait", request).getCode()).isZero();

        verify(conversationService).waitTransfer("CV-1", "idem-i9-wait", request);
    }

    @Test
    void convertToTicketDelegatesWithIdempotencyHeader() {
        ConversationTicketRequest request =
                new ConversationTicketRequest("account", "HIGH", "Need follow-up", 11L, "Tessa", "escalate to ticket", "agent-1");
        when(conversationService.convertToTicket("CV-1", "idem-i9-ticket", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.convertToTicket("CV-1", "idem-i9-ticket", request).getCode()).isZero();

        verify(conversationService).convertToTicket("CV-1", "idem-i9-ticket", request);
    }

    @Test
    void initiateDelegatesWithIdempotencyHeader() {
        ConversationInitiateRequest request = new ConversationInitiateRequest(
                "support", 1001L, "agent-1", "Agent One", "hello", null, "agent-1");
        when(conversationService.initiate("idem-i9-init", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.initiate("idem-i9-init", request).getCode()).isZero();

        verify(conversationService).initiate("idem-i9-init", request);
    }
}

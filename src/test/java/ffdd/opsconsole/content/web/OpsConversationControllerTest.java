package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.content.application.OpsConversationService;
import ffdd.opsconsole.content.dto.ConversationArchiveRequest;
import ffdd.opsconsole.content.dto.ConversationArchiveBatchRequest;
import ffdd.opsconsole.content.dto.ConversationFallbackRequest;
import ffdd.opsconsole.content.dto.ConversationInitiateRequest;
import ffdd.opsconsole.content.dto.ConversationReplyRequest;
import ffdd.opsconsole.content.dto.ConversationStatusRequest;
import ffdd.opsconsole.content.dto.ConversationTicketRequest;
import ffdd.opsconsole.content.dto.ConversationTransferDecisionRequest;
import ffdd.opsconsole.content.dto.ConversationTransferRequest;
import ffdd.opsconsole.content.dto.CustomerNoteRemoveRequest;
import ffdd.opsconsole.content.dto.CustomerNoteRequest;
import ffdd.opsconsole.content.dto.CustomerTagRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsConversationControllerTest {
    private final OpsConversationService conversationService = mock(OpsConversationService.class);
    private final org.springframework.context.ApplicationEventPublisher eventPublisher = mock(org.springframework.context.ApplicationEventPublisher.class);
    private final ffdd.opsconsole.shared.idempotency.AdminIdempotencyService idempotencyService = idempotencyService();
    private final OpsConversationController controller = new OpsConversationController(conversationService, eventPublisher, idempotencyService);

    private ffdd.opsconsole.shared.idempotency.AdminIdempotencyService idempotencyService() {
        var service = mock(ffdd.opsconsole.shared.idempotency.AdminIdempotencyService.class);
        doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(4)).get())
                .when(service).execute(any(), any(), any(), any(), any());
        return service;
    }

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
    void archiveBatchMapsTransactionalStateConflictTo409() {
        ConversationArchiveBatchRequest request = new ConversationArchiveBatchRequest(
                List.of("CV-1", "CV-2"), "archive resolved sessions", "agent-1");
        doThrow(new OpsConversationService.ConversationStateConflictException())
                .when(conversationService).archiveBatch("idem-i9-batch", request);

        ApiResult<List<ffdd.opsconsole.content.domain.ContentConversationView>> result =
                controller.archiveBatch("idem-i9-batch", request);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("INVALID_STATE_TRANSITION");
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

    @Test
    void addCustomTagDelegatesWithIdempotencyHeader() {
        CustomerTagRequest request = new CustomerTagRequest("高净值", "add vip tag", "agent-1");
        when(conversationService.addCustomTag("CV-1", "idem-i9-tag", request)).thenReturn(ApiResult.ok(List.of("高净值")));

        assertThat(controller.addCustomTag("CV-1", "idem-i9-tag", request).getCode()).isZero();

        verify(conversationService).addCustomTag("CV-1", "idem-i9-tag", request);
    }

    @Test
    void removeCustomTagDelegatesWithIdempotencyHeader() {
        CustomerTagRequest request = new CustomerTagRequest("高净值", "remove vip tag", "agent-1");
        when(conversationService.removeCustomTag("CV-1", "idem-i9-tag", request)).thenReturn(ApiResult.ok(List.of()));

        assertThat(controller.removeCustomTag("CV-1", "idem-i9-tag", request).getCode()).isZero();

        verify(conversationService).removeCustomTag("CV-1", "idem-i9-tag", request);
    }

    @Test
    void addNoteDelegatesWithIdempotencyHeader() {
        CustomerNoteRequest request = new CustomerNoteRequest("测试备注", "add note reason", "agent-1");
        when(conversationService.addNote("CV-1", "idem-i9-note", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.addNote("CV-1", "idem-i9-note", request).getCode()).isZero();

        verify(conversationService).addNote("CV-1", "idem-i9-note", request);
    }

    @Test
    void removeNoteDelegatesWithIdempotencyHeader() {
        CustomerNoteRemoveRequest request = new CustomerNoteRemoveRequest("remove note reason", "agent-1");
        when(conversationService.removeNote("CV-1", 5L, "idem-i9-note", request)).thenReturn(ApiResult.ok(null));

        assertThat(controller.removeNote("CV-1", 5L, "idem-i9-note", request).getCode()).isZero();

        verify(conversationService).removeNote("CV-1", 5L, "idem-i9-note", request);
    }
}

package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.ContentConversationMessageView;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.domain.SupportTicketMessageView;
import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.dto.ConversationArchiveRequest;
import ffdd.opsconsole.content.dto.ConversationFallbackRequest;
import ffdd.opsconsole.content.dto.ConversationInitiateRequest;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import ffdd.opsconsole.content.dto.ConversationReplyRequest;
import ffdd.opsconsole.content.dto.ConversationStatusRequest;
import ffdd.opsconsole.content.dto.ConversationTicketRequest;
import ffdd.opsconsole.content.dto.ConversationTransferDecisionRequest;
import ffdd.opsconsole.content.dto.ConversationTransferRequest;
import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsConversationServiceTest {
    private final FakeConversationRepository conversationRepository = new FakeConversationRepository();
    private final FakeSupportTicketRepository ticketRepository = new FakeSupportTicketRepository();
    private final OpsSupportAgentService supportAgentService = mock(OpsSupportAgentService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsConversationService service = service();

    private OpsConversationService service() {
        when(supportAgentService.transferTargets())
                .thenReturn(List.of(Map.of("targetType", "agent", "targetId", "agent-2", "targetName", "Agent Two")));
        return new OpsConversationService(conversationRepository, ticketRepository, supportAgentService, auditLogService, clock);
    }

    @Test
    void transferRequiresReasonAtLeastSixCharacters() {
        var result = service.transfer(
                "CV-1",
                "idem-i9",
                new ConversationTransferRequest("agent", "agent-2", "Agent Two", "short", "agent-1"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void transferOpenConversationToIncomingPendingAndAudits() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");

        var result = service.transfer(
                "CV-1",
                "idem-i9",
                new ConversationTransferRequest("agent", "agent-2", "Agent Two", "needs specialist", "agent-1"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("TRANSFERRED");
        assertThat(result.getData().transferToId()).isEqualTo("agent-2");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I9_CONVERSATION_TRANSFERRED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-i9");
    }

    @Test
    void transferRejectsClosedConversationWith409() {
        conversationRepository.conversation = conversation("CV-1", "CLOSED");

        var result = service.transfer(
                "CV-1",
                "idem-i9",
                new ConversationTransferRequest("agent", "agent-2", "Agent Two", "needs specialist", "agent-1"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void acceptTransferMovesConversationBackToOpen() {
        conversationRepository.conversation = transferredConversation("CV-1");

        var result = service.acceptTransfer(
                "CV-1",
                "idem-i9-accept",
                new ConversationTransferDecisionRequest("accept incoming", "agent-2"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("OPEN");
        assertThat(result.getData().ownerAgentId()).isEqualTo("agent-2");
    }

    @Test
    void waitTransferKeepsConversationTransferredAndAudits() {
        conversationRepository.conversation = transferredConversation("CV-1");

        var result = service.waitTransfer(
                "CV-1",
                "idem-i9-wait",
                new ConversationTransferDecisionRequest("continue waiting for receiving agent", "agent-2"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("TRANSFERRED");
        assertThat(result.getData().lastMessage()).contains("continue waiting");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I9_CONVERSATION_TRANSFER_WAITED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-i9-wait");
    }

    @Test
    void replyUpdatesConversationHeaderAndAudits() {
        conversationRepository.conversation = conversation("CV-1", "RESOLVED");

        var result = service.reply(
                "CV-1",
                "idem-i9-reply",
                new ConversationReplyRequest("We are checking the payment desk.", "agent reply", "Marina K."));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("OPEN");
        assertThat(result.getData().unreadCount()).isZero();
        assertThat(result.getData().lastMessage()).isEqualTo("We are checking the payment desk.");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I9_CONVERSATION_REPLIED");
    }

    @Test
    void replyRejectsTransferredConversationWith409() {
        conversationRepository.conversation = transferredConversation("CV-1");

        var result = service.reply(
                "CV-1",
                "idem-i9-reply",
                new ConversationReplyRequest("Please wait", "agent reply", "Marina K."));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateStatusRejectsTransferredConversationWith409() {
        conversationRepository.conversation = transferredConversation("CV-1");

        var result = service.updateStatus(
                "CV-1",
                "idem-i9-status",
                new ConversationStatusRequest("RESOLVED", "finish conversation", "Marina K."));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateStatusMovesOpenConversationToResolved() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");

        var result = service.updateStatus(
                "CV-1",
                "idem-i9-status",
                new ConversationStatusRequest("RESOLVED", "finish conversation", "Marina K."));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("RESOLVED");
    }

    @Test
    void initiateCreatesBackendConversationAndAudits() {
        var result = service.initiate(
                "idem-i9-init",
                new ConversationInitiateRequest("support", 1001L, "agent-1", "Agent One",
                        "Hello, I can help check your withdrawal.", null, "Agent One"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().conversationNo()).startsWith("CV-OUT-");
        assertThat(result.getData().lastMessage()).isEqualTo("Hello, I can help check your withdrawal.");
    }

    @Test
    void initiateAudienceWithoutReasonIsRejected() {
        var result = service.initiate(
                "idem-i9-init",
                new ConversationInitiateRequest("support", null, "agent-1", "Agent One",
                        "Hello audience", null, "Agent One"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void detailReturnsConversationMessages() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");

        var result = service.detail("CV-1");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().conversation().conversationNo()).isEqualTo("CV-1");
        assertThat(result.getData().messages()).hasSize(1);
    }

    @Test
    void archiveResolvedConversationClosesIt() {
        conversationRepository.conversation = conversation("CV-1", "RESOLVED");

        var result = service.archive(
                "CV-1",
                "idem-i9-archive",
                new ConversationArchiveRequest(true, "archive resolved session", "Marina K."));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("CLOSED");
    }

    @Test
    void fallbackTransferredConversationMovesToStandby() {
        conversationRepository.conversation = transferredConversation("CV-1");

        var result = service.fallbackTransfer(
                "CV-1",
                "idem-i9-fallback",
                new ConversationFallbackRequest("pending transfer timeout", "Marina K."));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().transferToType()).isEqualTo("standby");
        assertThat(result.getData().transferToId()).isEqualTo("standby-pool");
    }

    @Test
    void convertToTicketCreatesSupportTicketAndResolvesConversation() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");

        var result = service.convertToTicket(
                "CV-1",
                "idem-i9-ticket",
                new ConversationTicketRequest("account", "HIGH", "Need account follow-up", 11L, "Tessa", "escalate to ticket", "Marina K."));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().conversation().status()).isEqualTo("RESOLVED");
        assertThat(result.getData().ticket().ticket().ticketNo()).startsWith("TK-");
        assertThat(ticketRepository.ticket.category()).isEqualTo("account");
        assertThat(ticketRepository.ticket.priority()).isEqualTo("HIGH");
    }

    @Test
    void overviewExposesI9TransferStateMachine() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(conversationRepository.seedCalls).isGreaterThan(0);
        assertThat(result.getData().get("transferStateMachine"))
                .asList()
                .contains("OPEN->TRANSFERRED", "TRANSFERRED->OPEN");
    }

    @Test
    void conversationQueryPreservesUnreadOnlyAndPagination() {
        var result = service.conversations(new ConversationQueryRequest(null, null, null, null, "alice", true, 3L, 8L));

        assertThat(result.getCode()).isZero();
        assertThat(conversationRepository.lastQuery.keyword()).isEqualTo("alice");
        assertThat(conversationRepository.lastQuery.unreadOnly()).isTrue();
        assertThat(conversationRepository.lastQuery.pageNum()).isEqualTo(3L);
        assertThat(conversationRepository.lastQuery.pageSize()).isEqualTo(8L);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static ContentConversationView conversation(String no, String status) {
        return new ContentConversationView(
                1L,
                no,
                1001L,
                "support",
                status,
                "agent-1",
                "Agent One",
                2,
                "please help",
                LocalDateTime.now(),
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                LocalDateTime.now());
    }

    private static ContentConversationView transferredConversation(String no) {
        return new ContentConversationView(
                1L,
                no,
                1001L,
                "support",
                "TRANSFERRED",
                "agent-2",
                "Agent Two",
                2,
                "please help",
                LocalDateTime.now(),
                "agent-1",
                "Agent One",
                "agent",
                "agent-2",
                "Agent Two",
                "needs specialist",
                LocalDateTime.now(),
                LocalDateTime.now());
    }

    private static final class FakeConversationRepository implements ConversationRepository {
        private ContentConversationView conversation;
        private ConversationQueryRequest lastQuery;
        private int seedCalls;

        @Override
        public void ensureSeedData(LocalDateTime now) {
            seedCalls += 1;
        }

        @Override
        public Map<String, Object> counters() {
            return new LinkedHashMap<>(Map.of("open", 1L, "incomingPending", 1L));
        }

        @Override
        public PageResult<ContentConversationView> pageConversations(ConversationQueryRequest request) {
            lastQuery = request;
            long pageNum = request == null || request.pageNum() == null ? 1 : request.pageNum();
            long pageSize = request == null || request.pageSize() == null ? 20 : request.pageSize();
            return new PageResult<>(1, pageNum, pageSize, List.of(conversation("CV-1", "OPEN")));
        }

        @Override
        public Optional<ContentConversationView> findByConversationNo(String conversationNo) {
            return Optional.ofNullable(conversation);
        }

        @Override
        public List<ContentConversationMessageView> messages(String conversationNo) {
            return List.of(new ContentConversationMessageView(
                    1L,
                    conversation == null ? 1L : conversation.id(),
                    conversationNo,
                    1001L,
                    "user",
                    "User",
                    "please help",
                    LocalDateTime.now()));
        }

        @Override
        public void transferToPending(
                ContentConversationView conversation,
                String targetType,
                String targetId,
                String targetName,
                String reason,
                String operator,
                LocalDateTime now) {
            this.conversation = new ContentConversationView(
                    conversation.id(),
                    conversation.conversationNo(),
                    conversation.userId(),
                    conversation.conversationType(),
                    "TRANSFERRED",
                    targetId,
                    targetName,
                    conversation.unreadCount(),
                    conversation.lastMessage(),
                    conversation.lastMessageAt(),
                    conversation.ownerAgentId(),
                    conversation.ownerAgentName(),
                    targetType,
                    targetId,
                    targetName,
                    reason,
                    now,
                    now);
        }

        @Override
        public void acceptTransfer(ContentConversationView conversation, String operator, LocalDateTime now) {
            this.conversation = new ContentConversationView(
                    conversation.id(),
                    conversation.conversationNo(),
                    conversation.userId(),
                    conversation.conversationType(),
                    "OPEN",
                    operator,
                    operator,
                    conversation.unreadCount(),
                    conversation.lastMessage(),
                    conversation.lastMessageAt(),
                    conversation.transferFromAgentId(),
                    conversation.transferFromAgentName(),
                    conversation.transferToType(),
                    conversation.transferToId(),
                    conversation.transferToName(),
                    conversation.transferReason(),
                    conversation.transferredAt(),
                    now);
        }

        @Override
        public void returnTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
            this.conversation = new ContentConversationView(
                    conversation.id(),
                    conversation.conversationNo(),
                    conversation.userId(),
                    conversation.conversationType(),
                    "OPEN",
                    conversation.transferFromAgentId(),
                    conversation.transferFromAgentName(),
                    conversation.unreadCount(),
                    conversation.lastMessage(),
                    conversation.lastMessageAt(),
                    conversation.transferFromAgentId(),
                    conversation.transferFromAgentName(),
                    conversation.transferToType(),
                    conversation.transferToId(),
                    conversation.transferToName(),
                    reason,
                    conversation.transferredAt(),
                    now);
        }

        @Override
        public void waitTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
            this.conversation = new ContentConversationView(
                    conversation.id(),
                    conversation.conversationNo(),
                    conversation.userId(),
                    conversation.conversationType(),
                    "TRANSFERRED",
                    conversation.ownerAgentId(),
                    conversation.ownerAgentName(),
                    conversation.unreadCount(),
                    "转入会话继续等待: " + reason,
                    now,
                    conversation.transferFromAgentId(),
                    conversation.transferFromAgentName(),
                    conversation.transferToType(),
                    conversation.transferToId(),
                    conversation.transferToName(),
                    conversation.transferReason(),
                    conversation.transferredAt(),
                    now);
        }

        @Override
        public void reply(ContentConversationView conversation, String body, String operator, LocalDateTime now) {
            this.conversation = new ContentConversationView(
                    conversation.id(),
                    conversation.conversationNo(),
                    conversation.userId(),
                    conversation.conversationType(),
                    "OPEN",
                    conversation.ownerAgentId(),
                    conversation.ownerAgentName(),
                    0,
                    body,
                    now,
                    conversation.transferFromAgentId(),
                    conversation.transferFromAgentName(),
                    conversation.transferToType(),
                    conversation.transferToId(),
                    conversation.transferToName(),
                    conversation.transferReason(),
                    conversation.transferredAt(),
                    now);
        }

        @Override
        public void updateStatus(ContentConversationView conversation, String status, String operator, LocalDateTime now) {
            this.conversation = new ContentConversationView(
                    conversation.id(),
                    conversation.conversationNo(),
                    conversation.userId(),
                    conversation.conversationType(),
                    status,
                    conversation.ownerAgentId(),
                    conversation.ownerAgentName(),
                    conversation.unreadCount(),
                    conversation.lastMessage(),
                    conversation.lastMessageAt(),
                    conversation.transferFromAgentId(),
                    conversation.transferFromAgentName(),
                    conversation.transferToType(),
                    conversation.transferToId(),
                    conversation.transferToName(),
                    conversation.transferReason(),
                    conversation.transferredAt(),
                    now);
        }

        @Override
        public void archive(ContentConversationView conversation, boolean archived, String operator, LocalDateTime now) {
            updateStatus(conversation, archived ? "CLOSED" : "RESOLVED", operator, now);
        }

        @Override
        public void fallbackTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
            transferToPending(conversation, "standby", "standby-pool", "Standby pool", reason, operator, now);
        }

        @Override
        public void markConvertedToTicket(ContentConversationView conversation, String ticketNo, String operator, LocalDateTime now) {
            updateStatus(conversation, "RESOLVED", operator, now);
        }

        @Override
        public ContentConversationView createConversation(String conversationNo, Long userId, String conversationType,
                                                         String ownerAgentId, String ownerAgentName,
                                                         String openingText, LocalDateTime now) {
            this.conversation = new ContentConversationView(
                    2L,
                    conversationNo,
                    userId,
                    conversationType,
                    "OPEN",
                    ownerAgentId,
                    ownerAgentName,
                    0,
                    openingText,
                    now,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    now);
            return this.conversation;
        }
    }

    private static final class FakeSupportTicketRepository implements SupportTicketRepository {
        private SupportTicketView ticket;
        private List<SupportTicketMessageView> messages = List.of();

        @Override
        public void ensureSeedData(LocalDateTime now) {
        }

        @Override
        public Map<String, Object> counters() {
            return Map.of("active", 1L);
        }

        @Override
        public PageResult<SupportTicketView> pageTickets(SupportTicketQueryRequest request) {
            return new PageResult<>(ticket == null ? 0 : 1, 1, 20, ticket == null ? List.of() : List.of(ticket));
        }

        @Override
        public Optional<SupportTicketView> findByTicketNo(String ticketNo) {
            return Optional.ofNullable(ticket);
        }

        @Override
        public List<SupportTicketMessageView> messages(String ticketNo) {
            return messages;
        }

        @Override
        public SupportTicketView createTicket(
                String ticketNo,
                Long userId,
                String category,
                String priority,
                String title,
                String body,
                Long assignedAdminId,
                String assignedAdminName,
                String operator,
                LocalDateTime now) {
            ticket = new SupportTicketView(
                    10L,
                    ticketNo,
                    userId,
                    category,
                    priority,
                    "OPEN",
                    title,
                    body,
                    assignedAdminId,
                    assignedAdminName,
                    0,
                    1,
                    1,
                    now,
                    null,
                    now,
                    now);
            messages = List.of(new SupportTicketMessageView(
                    10L,
                    10L,
                    ticketNo,
                    userId,
                    "user",
                    "User",
                    body,
                    now));
            return ticket;
        }

        @Override
        public void appendReply(SupportTicketView ticket, String body, String operator, LocalDateTime now) {
        }

        @Override
        public void updateStatus(SupportTicketView ticket, String status, String operator, LocalDateTime now) {
        }

        @Override
        public void updatePriority(SupportTicketView ticket, String priority, LocalDateTime now) {
        }

        @Override
        public void assign(SupportTicketView ticket, Long assignedAdminId, String assignedAdminName, LocalDateTime now) {
        }
    }
}

package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import ffdd.opsconsole.content.dto.ConversationTransferDecisionRequest;
import ffdd.opsconsole.content.dto.ConversationTransferRequest;
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
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsConversationService service = new OpsConversationService(conversationRepository, auditLogService, clock);

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
    void overviewExposesI9TransferStateMachine() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("transferStateMachine"))
                .asList()
                .contains("OPEN->TRANSFERRED", "TRANSFERRED->OPEN");
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

        @Override
        public Map<String, Object> counters() {
            return new LinkedHashMap<>(Map.of("open", 1L, "incomingPending", 1L));
        }

        @Override
        public PageResult<ContentConversationView> pageConversations(ConversationQueryRequest request) {
            return new PageResult<>(1, 1, 20, List.of(conversation("CV-1", "OPEN")));
        }

        @Override
        public Optional<ContentConversationView> findByConversationNo(String conversationNo) {
            return Optional.ofNullable(conversation);
        }

        @Override
        public List<Map<String, Object>> transferTargets() {
            return List.of(Map.of("targetType", "agent", "targetId", "agent-2", "targetName", "Agent Two"));
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
    }
}

package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import ffdd.opsconsole.content.domain.ContentConversationMessageView;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.content.domain.SupportTicketMessageView;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class AppSupportServiceTest {
    private final SupportTicketRepository tickets = mock(SupportTicketRepository.class);
    private final ConversationRepository conversations = mock(ConversationRepository.class);
    private final SupportKnowledgeRepository knowledge = mock(SupportKnowledgeRepository.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
    private final ProductionSupportPathGuard productionPathGuard = mock(ProductionSupportPathGuard.class);
    private AppSupportService service;

    @BeforeEach
    void setUp() {
        service = new AppSupportService(tickets, conversations, knowledge, idempotency, audit, eventPublisher, clock, productionPathGuard);
        when(idempotency.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> action = invocation.getArgument(4);
            return action.get();
        });
    }

    @Test
    void ticketListAlwaysForcesAuthenticatedUserOwnershipAndBoundsPageSize() {
        when(tickets.pageTickets(any())).thenReturn(new PageResult<>(0, 1, 100, List.of()));

        ApiResult<PageResult<SupportTicketView>> result = service.tickets(42L, null, 1L, 1000L);

        assertThat(result.getCode()).isZero();
        ArgumentCaptor<ffdd.opsconsole.content.dto.SupportTicketQueryRequest> query =
                ArgumentCaptor.forClass(ffdd.opsconsole.content.dto.SupportTicketQueryRequest.class);
        verify(tickets).pageTickets(query.capture());
        assertThat(query.getValue().userId()).isEqualTo(42L);
        assertThat(query.getValue().pageSize()).isEqualTo(100L);
    }

    @Test
    void ticketReplyRejectsCrossUserAccessBeforeAnyMutation() {
        when(tickets.findByTicketNo("TK-1")).thenReturn(Optional.of(ticket(7L, "OPEN", 3L)));

        ApiResult<?> result = service.replyTicket(
                42L, "TK-1", "idem-1", new AppSupportService.ReplyRequest("hello", "OPEN", 3L));

        assertThat(result.getCode()).isEqualTo(404);
        verify(tickets, never()).appendUserReplyCas(any(), any(), any());
    }

    @Test
    void ticketReplyUsesServerVersionCasAndReportsConflict() {
        when(tickets.findByTicketNo("TK-1")).thenReturn(Optional.of(ticket(42L, "OPEN", 3L)));
        when(tickets.appendUserReplyCas(any(), any(), any())).thenReturn(false);

        ApiResult<?> result = service.replyTicket(
                42L, "TK-1", "idem-1", new AppSupportService.ReplyRequest("hello", "OPEN", 3L));

        assertThat(result.getCode()).isEqualTo(409);
    }

    @Test
    void conversationDetailRejectsCrossUserAccess() {
        ContentConversationView conversation = new ContentConversationView(
                1L, "CV-1", 7L, "support", "OPEN", "a1", "Agent", 0, "hello",
                LocalDateTime.now(clock), null, null, null, null, null, null, null,
                LocalDateTime.now(clock), 0L);
        when(conversations.findByConversationNo("CV-1")).thenReturn(Optional.of(conversation));

        assertThat(service.conversation(42L, "CV-1").getCode()).isEqualTo(404);
        verify(conversations, never()).messages(any());
    }

    @Test
    void ticketDetailUsesOnlyTheRepositoryUserVisibleProjection() {
        when(tickets.findByTicketNo("TK-1")).thenReturn(Optional.of(ticket(42L, "OPEN", 3L)));
        SupportTicketMessageView visible = new SupportTicketMessageView(
                1L, 1L, "TK-1", 42L, "user", "User", "hello", LocalDateTime.now(clock));
        when(tickets.userVisibleMessages("TK-1")).thenReturn(List.of(visible));

        var result = service.ticket(42L, "TK-1");

        assertThat(result.getData().messages()).containsExactly(visible);
        verify(tickets, never()).messages("TK-1");
    }

    @Test
    void conversationUnreadCountsOnlyUnreadPublicAgentMessages() {
        ContentConversationView conversation = conversation(42L, "CV-1");
        when(conversations.findByConversationNo("CV-1")).thenReturn(Optional.of(conversation));
        LocalDateTime now = LocalDateTime.now(clock);
        List<ContentConversationMessageView> visible = List.of(
                new ContentConversationMessageView(1L, 1L, "CV-1", 9L, "agent", "A", "one", "sent", now),
                new ContentConversationMessageView(2L, 1L, "CV-1", 9L, "agent", "A", "two", "read", now),
                new ContentConversationMessageView(3L, 1L, "CV-1", 42L, "user", "User", "three", null, now));
        when(conversations.userVisibleMessages("CV-1")).thenReturn(visible);

        var result = service.conversation(42L, "CV-1");

        assertThat(result.getData().conversation().unreadCount()).isEqualTo(1);
        assertThat(result.getData().messages()).containsExactlyElementsOf(visible);
        verify(conversations, never()).messages("CV-1");
    }

    @Test
    void idempotentConversationRetryPublishesOnlyTheFirstPersistedMutation() {
        ContentConversationView conversation = conversation(42L, "CV-1");
        when(conversations.createUserConversation(any(), any(), any(), any(), any())).thenReturn(conversation);
        when(conversations.findByConversationNo(conversation.conversationNo())).thenReturn(Optional.of(conversation));
        when(conversations.userVisibleMessages(conversation.conversationNo())).thenReturn(List.of(
                new ContentConversationMessageView(7L, 1L, "CV-1", 42L, "user", "User", "help", null, LocalDateTime.now(clock))));
        java.util.concurrent.atomic.AtomicReference<Object> cached = new java.util.concurrent.atomic.AtomicReference<>();
        org.mockito.Mockito.doAnswer(invocation -> {
            if (cached.get() != null) return cached.get();
            java.util.function.Supplier<?> action = invocation.getArgument(4);
            Object result = action.get(); cached.set(result); return result;
        }).when(idempotency).execute(any(), any(), any(), any(), any());
        var request = new AppSupportService.StartConversationRequest("SUPPORT", "help");

        service.startConversation(42L, "stable-idempotency", request);
        service.startConversation(42L, "stable-idempotency", request);

        verify(conversations, times(1)).createUserConversation(any(), any(), any(), any(), any());
        verify(eventPublisher, times(1)).publishEvent(any(ConversationMessageEvent.class));
    }

    @Test
    void directServiceCallFailsClosedBeforeAnyFormalSupportDependencyForSandboxUser() {
        doThrow(new RuntimeException("SUPPORT_PRODUCTION_PATH_FORBIDDEN"))
                .when(productionPathGuard).requireAllowed(42L);

        assertThatThrownBy(() -> service.startConversation(
                42L, "direct-bypass", new AppSupportService.StartConversationRequest("SUPPORT", "help")))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(tickets, conversations, knowledge, idempotency, audit, eventPublisher);
    }

    @Test
    void conversationSsePublishesOnlyAfterTheEnclosingTransactionCommits() {
        ContentConversationView conversation = conversation(42L, "CV-AFTER-COMMIT");
        when(conversations.createUserConversation(any(), any(), any(), any(), any())).thenReturn(conversation);
        when(conversations.findByConversationNo(conversation.conversationNo())).thenReturn(Optional.of(conversation));
        when(conversations.userVisibleMessages(conversation.conversationNo())).thenReturn(List.of(
                new ContentConversationMessageView(7L, 1L, conversation.conversationNo(), 42L, "user", "User", "help", null, LocalDateTime.now(clock))));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.startConversation(42L, "after-commit-key", new AppSupportService.StartConversationRequest("SUPPORT", "help"));
            verifyNoInteractions(eventPublisher);
            TransactionSynchronizationManager.getSynchronizations().forEach(sync -> sync.afterCommit());
            verify(eventPublisher, times(1)).publishEvent(any(ConversationMessageEvent.class));
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void rollbackAfterAConversationWriteEmitsNoPhantomSse() {
        ContentConversationView conversation = conversation(42L, "CV-ROLLBACK");
        when(conversations.createUserConversation(any(), any(), any(), any(), any())).thenReturn(conversation);
        when(conversations.findByConversationNo(conversation.conversationNo())).thenReturn(Optional.of(conversation));
        when(conversations.userVisibleMessages(conversation.conversationNo())).thenReturn(List.of(
                new ContentConversationMessageView(8L, 1L, conversation.conversationNo(), 42L, "user", "User", "help", null, LocalDateTime.now(clock))));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.startConversation(42L, "rollback-key", new AppSupportService.StartConversationRequest("SUPPORT", "help"));
            // A rollback calls neither afterCommit nor the SSE publisher.
            verifyNoInteractions(eventPublisher);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void markReadPersistsOnlyTheAuthenticatedUsersVisibleAgentReceipt() {
        ContentConversationView conversation = conversation(42L, "CV-READ");
        when(conversations.findByConversationNoForUpdate("CV-READ")).thenReturn(Optional.of(conversation));
        when(conversations.findByConversationNo("CV-READ")).thenReturn(Optional.of(conversation));
        LocalDateTime now = LocalDateTime.now(clock);
        ContentConversationMessageView sent = new ContentConversationMessageView(
                11L, 1L, "CV-READ", 9L, "agent", "A", "reply", "sent", now);
        ContentConversationMessageView read = new ContentConversationMessageView(
                11L, 1L, "CV-READ", 9L, "agent", "A", "reply", "read", now);
        when(conversations.userVisibleMessages("CV-READ"))
                .thenReturn(List.of(sent), List.of(read));
        when(conversations.markAgentMessagesReadThrough("CV-READ", 11L, "user:42", now, "OPEN", 9L)).thenReturn(true);

        var result = service.markConversationRead(42L, "CV-READ", 11L, "OPEN", 9L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().conversation().unreadCount()).isZero();
        verify(conversations).markAgentMessagesReadThrough("CV-READ", 11L, "user:42", now, "OPEN", 9L);
    }

    @Test
    void repeatedReadIsIdempotentAndDoesNotPublishAnotherReceiptEvent() {
        ContentConversationView conversation = conversation(42L, "CV-READ");
        when(conversations.findByConversationNoForUpdate("CV-READ")).thenReturn(Optional.of(conversation));
        when(conversations.findByConversationNo("CV-READ")).thenReturn(Optional.of(conversation));
        LocalDateTime now = LocalDateTime.now(clock);
        ContentConversationMessageView read = new ContentConversationMessageView(
                11L, 1L, "CV-READ", 9L, "agent", "A", "reply", "read", now);
        when(conversations.userVisibleMessages("CV-READ")).thenReturn(List.of(read));
        when(conversations.markAgentMessagesReadThrough("CV-READ", 11L, "user:42", now, "OPEN", 9L)).thenReturn(false);

        var result = service.markConversationRead(42L, "CV-READ", 11L, "OPEN", 9L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().conversation().unreadCount()).isZero();
        verify(eventPublisher, never()).publishEvent(any(ConversationMessageEvent.class));
    }

    @Test
    void faqsFallBackToPublishedDefaultLanguageInsteadOfHidingTheOnlyAppKnowledge() {
        LocalDateTime now = LocalDateTime.now(clock);
        when(knowledge.listFaqs()).thenReturn(List.of(
                new ffdd.opsconsole.content.domain.SupportFaqView(
                        "FAQ-zh", "technical", "如何开始", "请先登录", "PUBLISHED", "Help Center",
                        "zh-CN", 10, 1, now),
                new ffdd.opsconsole.content.domain.SupportFaqView(
                        "FAQ-draft", "technical", "draft", "draft", "DRAFT", "Help Center",
                        "en-US", 20, 1, now)));

        var result = service.faqs(42L, "en-US", null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).extracting(ffdd.opsconsole.content.domain.SupportFaqView::id)
                .containsExactly("FAQ-zh");
    }

    @Test
    void secondUserCannotAcknowledgeTheFirstUsersAgentMessage() {
        when(conversations.findByConversationNo("CV-READ"))
                .thenReturn(Optional.of(conversation(7L, "CV-READ")));

        var result = service.markConversationRead(42L, "CV-READ", 11L, "OPEN", 9L);

        assertThat(result.getCode()).isEqualTo(404);
        verify(conversations, never()).markAgentMessagesReadThrough(any(), any(), any(), any(), any(), any());
    }

    @Test
    void staleConversationReadIsRejectedBeforeChangingAnyReceipt() {
        when(conversations.findByConversationNoForUpdate("CV-READ"))
                .thenReturn(Optional.of(conversation(42L, "CV-READ")));

        var result = service.markConversationRead(42L, "CV-READ", 11L, "OPEN", 8L);

        assertThat(result.getCode()).isEqualTo(409);
        verify(conversations, never()).markAgentMessagesReadThrough(any(), any(), any(), any(), any(), any());
    }

    @Test
    void closedConversationCannotAdvanceAReadReceiptEvenWithMatchingCas() {
        ContentConversationView closed = new ContentConversationView(
                1L, "CV-CLOSED", 42L, "support", "CLOSED", null, "Unassigned", 1, "done",
                LocalDateTime.now(clock), null, null, null, null, null, null, null, LocalDateTime.now(clock), 9L);
        when(conversations.findByConversationNoForUpdate("CV-CLOSED")).thenReturn(Optional.of(closed));

        var result = service.markConversationRead(42L, "CV-CLOSED", 11L, "CLOSED", 9L);

        assertThat(result.getCode()).isEqualTo(409);
        verify(conversations, never()).markAgentMessagesReadThrough(any(), any(), any(), any(), any(), any());
    }

    private ContentConversationView conversation(Long userId, String number) {
        LocalDateTime now = LocalDateTime.now(clock);
        return new ContentConversationView(1L, number, userId, "support", "OPEN", null, "Unassigned",
                9, "help", now, null, null, null, null, null, null, null, now, 9L);
    }

    private SupportTicketView ticket(Long userId, String status, Long version) {
        LocalDateTime now = LocalDateTime.now(clock);
        return new SupportTicketView(
                1L, "TK-1", userId, "technical", "NORMAL", status, "title", "body",
                null, "Unassigned", 0, 1, 1, now, null, now, now, false, null, version, true);
    }
}

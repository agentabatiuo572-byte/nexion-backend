package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;

import ffdd.opsconsole.content.domain.ContentConversationMessageView;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.domain.DedicatedAdvisorBindingView;
import ffdd.opsconsole.content.domain.SupportKnowledgeRepository;
import ffdd.opsconsole.content.domain.SupportAgentRepository;
import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.content.domain.SupportTicketMessageView;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.domain.SupportSlaView;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    private final SupportAgentRepository supportAgents = mock(SupportAgentRepository.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-11T00:00:00Z"), ZoneOffset.UTC);
    private final ProductionSupportPathGuard productionPathGuard = mock(ProductionSupportPathGuard.class);
    private final AdminIdempotencyRecordMapper idempotencyRecords = mock(AdminIdempotencyRecordMapper.class);
    private final PlatformConfigFacade configFacade = mock(PlatformConfigFacade.class);
    private AppSupportService service;

    @BeforeEach
    void setUp() {
        service = new AppSupportService(tickets, conversations, knowledge, idempotency, audit, eventPublisher, clock,
                productionPathGuard, idempotencyRecords, new ObjectMapper().findAndRegisterModules(), supportAgents,
                configFacade);
        when(idempotency.execute(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> action = invocation.getArgument(4);
            return action.get();
        });
        when(tickets.recentUserVisibleMessages(anyString(), anyInt())).thenAnswer(invocation ->
                tickets.userVisibleMessages(invocation.getArgument(0)));
        when(conversations.recentUserVisibleMessages(anyString(), anyInt())).thenAnswer(invocation ->
                conversations.userVisibleMessages(invocation.getArgument(0)));
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
    void disabledM5ConversationCategoryBlocksNewAppSessionsBeforeIdempotencyOrRouting() {
        when(configFacade.activeValue("I.session.cat.advisor.enabled")).thenReturn(Optional.of("off"));

        ApiResult<?> result = service.startConversation(
                42L, "disabled-category-key", new AppSupportService.StartConversationRequest("ADVISOR", "hello"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(result.getMessage()).isEqualTo("CONVERSATION_CATEGORY_DISABLED");
        verifyNoInteractions(supportAgents);
        verifyNoInteractions(idempotency);
    }

    @Test
    void appCategoryReadModelProjectsTheSameM5ConfigKeysThatGateConversationCreation() {
        when(configFacade.activeValue("I.session.cat.advisor.enabled")).thenReturn(Optional.of("off"));
        when(configFacade.activeValue("I.session.cat.support.enabled")).thenReturn(Optional.of("enabled"));
        when(configFacade.activeValue("I.session.cat.ai.enabled")).thenReturn(Optional.of("true"));

        var result = service.conversationCategories(42L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsExactly(
                new AppSupportService.ConversationCategoryAvailability("advisor", false),
                new AppSupportService.ConversationCategoryAvailability("support", true),
                new AppSupportService.ConversationCategoryAvailability("ai", true));
    }

    @Test
    void ticketReadClearsOnlyTheAuthenticatedOwnersUnreadHeaderAtItsOpenedVersion() {
        SupportTicketView opened = ticket(42L, "OPEN", 3L, 1);
        when(tickets.findByTicketNo("TK-1")).thenReturn(Optional.of(opened));
        when(tickets.markUserReadCas(opened, LocalDateTime.now(clock))).thenReturn(true);

        ApiResult<?> result = service.markTicketRead(
                42L, "TK-1", new AppSupportService.CloseRequest("OPEN", 3L));

        assertThat(result.getCode()).isZero();
        verify(tickets).markUserReadCas(opened, LocalDateTime.now(clock));
    }

    @Test
    void ticketReadRejectsAnotherUsersTicketBeforeAnyReadMutation() {
        when(tickets.findByTicketNo("TK-1")).thenReturn(Optional.of(ticket(7L, "OPEN", 3L)));

        ApiResult<?> result = service.markTicketRead(
                42L, "TK-1", new AppSupportService.CloseRequest("OPEN", 3L));

        assertThat(result.getCode()).isEqualTo(404);
        verify(tickets, never()).markUserReadCas(any(), any());
    }

    @Test
    void ticketReadRejectsAStaleOpenedVersionBeforeAnyReadMutation() {
        when(tickets.findByTicketNo("TK-1")).thenReturn(Optional.of(ticket(42L, "OPEN", 4L)));

        ApiResult<?> result = service.markTicketRead(
                42L, "TK-1", new AppSupportService.CloseRequest("OPEN", 3L));

        assertThat(result.getCode()).isEqualTo(409);
        verify(tickets, never()).markUserReadCas(any(), any());
    }

    @Test
    void ticketReadDoesNotClearAReplyThatArrivedAfterTheUserOpenedTheTicket() {
        SupportTicketView newerAgentReply = ticket(42L, "OPEN", 4L);
        when(tickets.findByTicketNo("TK-1")).thenReturn(Optional.of(newerAgentReply));

        ApiResult<?> result = service.markTicketRead(
                42L, "TK-1", new AppSupportService.CloseRequest("OPEN", 3L));

        assertThat(result.getCode()).isEqualTo(409);
        verify(tickets, never()).markUserReadCas(any(), any());
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
        when(conversations.unreadUserVisibleAgentMessageCount("CV-1")).thenReturn(1);

        var result = service.conversation(42L, "CV-1");

        assertThat(result.getData().conversation().unreadCount()).isEqualTo(1);
        assertThat(result.getData().messages()).containsExactlyElementsOf(visible);
        verify(conversations, never()).messages("CV-1");
    }

    @Test
    void conversationListCountsEveryUnreadAgentMessageRatherThanOnlyItsLatestPreview() {
        ContentConversationView conversation = conversation(42L, "CV-1");
        when(conversations.pageConversations(any())).thenReturn(new PageResult<>(1, 1, 50, List.of(conversation)));
        LocalDateTime now = LocalDateTime.now(clock);
        when(conversations.recentUserVisibleMessages("CV-1", 1)).thenReturn(List.of(
                new ContentConversationMessageView(3L, 1L, "CV-1", 9L, "agent", "A", "latest", "read", now)));
        when(conversations.userVisibleMessages("CV-1")).thenReturn(List.of(
                new ContentConversationMessageView(1L, 1L, "CV-1", 9L, "agent", "A", "first", "sent", now),
                new ContentConversationMessageView(2L, 1L, "CV-1", 9L, "agent", "A", "second", "sent", now),
                new ContentConversationMessageView(3L, 1L, "CV-1", 9L, "agent", "A", "latest", "read", now)));
        when(conversations.unreadUserVisibleAgentMessageCount("CV-1")).thenReturn(2);

        var result = service.conversations(42L, null, 1L, 50L);

        assertThat(result.getData().getRecords()).singleElement()
                .extracting(ContentConversationView::unreadCount).isEqualTo(2);
    }

    @Test
    void olderConversationPageUsesOnlyTheOwnedCursorWindowAndSuppliesTheNextCursor() {
        ContentConversationView conversation = conversation(42L, "CV-1");
        when(conversations.findByConversationNo("CV-1")).thenReturn(Optional.of(conversation));
        LocalDateTime now = LocalDateTime.now(clock);
        List<ContentConversationMessageView> fetched = java.util.stream.LongStream.rangeClosed(10, 110)
                .mapToObj(id -> new ContentConversationMessageView(
                        id, 1L, "CV-1", 42L, "user", "User", "message-" + id, null, now)).toList();
        when(conversations.recentUserVisibleMessagesBefore("CV-1", 111L, 101)).thenReturn(fetched);

        var result = service.conversation(42L, "CV-1", 111L);

        assertThat(result.getData().messages()).hasSize(100);
        assertThat(result.getData().messages().get(0).id()).isEqualTo(11L);
        assertThat(result.getData().nextCursor()).isEqualTo(11L);
    }

    @Test
    void conversationDetailReturnsOnlyTheLatestHundredMessagesAndMarksTruncation() {
        ContentConversationView conversation = conversation(42L, "CV-1");
        when(conversations.findByConversationNo("CV-1")).thenReturn(Optional.of(conversation));
        LocalDateTime now = LocalDateTime.now(clock);
        List<ContentConversationMessageView> rows = java.util.stream.LongStream.rangeClosed(1, 101)
                .mapToObj(id -> new ContentConversationMessageView(
                        id, 1L, "CV-1", 42L, "user", "User", "message-" + id, null, now))
                .toList();
        when(conversations.recentUserVisibleMessages("CV-1", 101)).thenReturn(rows);

        var result = service.conversation(42L, "CV-1");

        assertThat(result.getData().historyTruncated()).isTrue();
        assertThat(result.getData().messages()).hasSize(100);
        assertThat(result.getData().messages().get(0).id()).isEqualTo(2L);
    }

    @Test
    void idempotentConversationRetryPublishesOnlyTheFirstPersistedMutation() {
        ContentConversationView conversation = conversation(42L, "CV-1");
        when(conversations.createUserConversation(any(), any(), any(), any(), any(), any(), any())).thenReturn(conversation);
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

        verify(conversations, times(1)).createUserConversation(any(), any(), any(), any(), any(), any(), any());
        verify(eventPublisher, times(1)).publishEvent(any(ConversationMessageEvent.class));
    }

    @Test
    void advisorConversationStartsWithTheActiveM5DedicatedAdvisorOwner() {
        ContentConversationView conversation = conversation(42L, "CV-ADVISOR");
        when(supportAgents.findActiveDedicatedAdvisor(42L))
                .thenReturn(Optional.of(new DedicatedAdvisorBindingView(9L, "Advisor A")));
        when(conversations.createUserConversation(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(conversation);
        when(conversations.findByConversationNo(conversation.conversationNo())).thenReturn(Optional.of(conversation));
        when(conversations.userVisibleMessages(conversation.conversationNo())).thenReturn(List.of());

        service.startConversation(42L, "advisor-binding-key",
                new AppSupportService.StartConversationRequest("ADVISOR", "help"));

        verify(conversations).createUserConversation(
                any(), org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq("advisor"),
                org.mockito.ArgumentMatchers.eq("help"), org.mockito.ArgumentMatchers.eq("9"),
                org.mockito.ArgumentMatchers.eq("Advisor A"), any());
        var ordering = inOrder(supportAgents);
        ordering.verify(supportAgents).ensureSchema();
        ordering.verify(supportAgents).findActiveDedicatedAdvisor(42L);
    }

    @Test
    void advisorConversationFallsBackToStandbyPoolWhenNoAdvisorIsAvailable() {
        ContentConversationView conversation = conversation(42L, "CV-STANDBY");
        when(supportAgents.findActiveDedicatedAdvisor(42L)).thenReturn(Optional.empty());
        when(conversations.createUserConversation(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(conversation);
        when(conversations.findByConversationNo(conversation.conversationNo())).thenReturn(Optional.of(conversation));
        when(conversations.userVisibleMessages(conversation.conversationNo())).thenReturn(List.of());

        service.startConversation(42L, "advisor-standby-key",
                new AppSupportService.StartConversationRequest("ADVISOR", "help"));

        verify(conversations).createUserConversation(
                any(), org.mockito.ArgumentMatchers.eq(42L), org.mockito.ArgumentMatchers.eq("advisor"),
                org.mockito.ArgumentMatchers.eq("help"), org.mockito.ArgumentMatchers.eq("standby-pool"),
                org.mockito.ArgumentMatchers.eq("备勤池"), any());
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
        when(conversations.createUserConversation(any(), any(), any(), any(), any(), any(), any())).thenReturn(conversation);
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
        when(conversations.createUserConversation(any(), any(), any(), any(), any(), any(), any())).thenReturn(conversation);
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
        var fallback = new ffdd.opsconsole.content.domain.SupportFaqView(
                "FAQ-zh", "technical", "如何开始", "请先登录", "PUBLISHED", "Help Center",
                "zh-CN", 10, 1, now);
        when(knowledge.countPublishedFaqs("en-US", "Help Center", "")).thenReturn(0L);
        when(knowledge.countPublishedFaqs("zh-CN", "Help Center", "")).thenReturn(1L);
        when(knowledge.listPublishedFaqPage("zh-CN", "Help Center", "", 0L, 50))
                .thenReturn(List.of(fallback));

        var result = service.faqs(42L, "en-US", null);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).extracting(ffdd.opsconsole.content.domain.SupportFaqView::id)
                .containsExactly("FAQ-zh");
    }

    @Test
    void faqPageUsesBoundedDatabasePagination() {
        LocalDateTime now = LocalDateTime.now(clock);
        var faq = new ffdd.opsconsole.content.domain.SupportFaqView(
                "FAQ-51", "technical", "Q", "A", "PUBLISHED", "Help Center", "en-US", 51, 1, now);
        when(knowledge.countPublishedFaqs("en-US", "Help Center", "")).thenReturn(120L);
        when(knowledge.listPublishedFaqPage("en-US", "Help Center", "", 50L, 50))
                .thenReturn(List.of(faq));

        var result = service.faqPage(42L, "en-US", null, "Help Center", 2L, 500L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().getTotal()).isEqualTo(120L);
        assertThat(result.getData().getPageNum()).isEqualTo(2L);
        assertThat(result.getData().getPageSize()).isEqualTo(50L);
        assertThat(result.getData().getRecords()).containsExactly(faq);
    }

    @Test
    void legacyFaqListFailsClosedInsteadOfSilentlyTruncatingPublishedContent() {
        when(knowledge.countPublishedFaqs("en-US", "Help Center", "")).thenReturn(51L);
        when(knowledge.listPublishedFaqPage("en-US", "Help Center", "", 0L, 50))
                .thenReturn(java.util.Collections.nCopies(50, mock(ffdd.opsconsole.content.domain.SupportFaqView.class)));

        var result = service.faqs(42L, "en-US", null);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FAQ_PAGE_REQUIRED");
    }

    @Test
    void configuredSlaTargetsNeverClaimToBeHistoricalResponseStatistics() {
        when(knowledge.listSla()).thenReturn(List.of(new SupportSlaView(
                "withdrawal", 15, 12, "payments", "review", 4L, LocalDateTime.now(clock))));

        var result = service.slaTargets(42L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsExactly(new AppSupportService.AppSupportSlaTarget(
                "withdrawal", 15, 12, false));
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
        return ticket(userId, status, version, 0);
    }

    private SupportTicketView ticket(Long userId, String status, Long version, int userUnreadCount) {
        LocalDateTime now = LocalDateTime.now(clock);
        return new SupportTicketView(
                1L, "TK-1", userId, "technical", "NORMAL", status, "title", "body",
                null, "Unassigned", userUnreadCount, 1, 1, now, null, now, now, false, null, version, true);
    }
}

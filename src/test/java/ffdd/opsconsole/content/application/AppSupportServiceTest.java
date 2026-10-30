package ffdd.opsconsole.content.application;

import static org.mockito.ArgumentMatchers.eq;

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
import ffdd.opsconsole.content.domain.SupportSlaView;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
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
        service = new AppSupportService(conversations, knowledge, idempotency, audit, eventPublisher, clock,
                productionPathGuard, idempotencyRecords, new ObjectMapper().findAndRegisterModules(), supportAgents,
                configFacade, ffdd.opsconsole.content.SupportTestDependencies.ownership(), ffdd.opsconsole.content.SupportTestDependencies.humanMessages(),
                ffdd.opsconsole.content.SupportTestDependencies.visibility(conversations), org.mockito.Mockito.mock(ffdd.opsconsole.content.application.SupportUnboundReceipt.class));
        when(idempotency.executeRetained(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            java.util.function.Supplier<?> action = invocation.getArgument(4);
            return action.get();
        });
        when(conversations.recentUserVisibleMessages(anyString(), anyInt())).thenAnswer(invocation ->
                conversations.userVisibleMessages(invocation.getArgument(0)));
        when(conversations.currentRecentUserVisibleMessages(anyString(), anyInt())).thenAnswer(invocation ->
                conversations.recentUserVisibleMessages(invocation.getArgument(0), invocation.getArgument(1)));
        when(conversations.findByConversationNoForUpdate(anyString())).thenAnswer(invocation ->
                conversations.findByConversationNo(invocation.getArgument(0)));
    }

    @Test
    void disabledHumanCategoryThrowsRetryableFailureAfterLegacyReplayBeforeRouting() {
        when(configFacade.activeValue("I.session.cat.support.enabled")).thenReturn(Optional.of("on"));
        when(configFacade.activeValue("I.session.cat.advisor.enabled")).thenReturn(Optional.of("off"));
        assertThatThrownBy(() -> service.startConversation(42L, "disabled-category-key",
                new AppSupportService.StartConversationRequest("SUPPORT", "hello")))
                .isInstanceOf(BizException.class).hasMessage("CONVERSATION_CATEGORY_DISABLED")
                .satisfies(cause -> assertThat(((BizException) cause).getCode()).isEqualTo(409));
        verifyNoInteractions(supportAgents);
        verify(idempotency).executeRetained(any(), any(), any(), any(), any());
    }

    @Test
    void realRetainedExecutorDoesNotCacheGateRejectionAndRetriesOriginalSupportKeyHash() {
        var ledger = mock(AdminIdempotencyRecordMapper.class);
        var row = new java.util.concurrent.atomic.AtomicReference<ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity>();
        when(ledger.selectActive(any(), any())).thenAnswer(i -> row.get());
        when(ledger.selectCurrent(any(), any())).thenAnswer(i -> row.get());
        when(ledger.insert(any(ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity.class))).thenAnswer(i -> {
            var entity = i.<ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity>getArgument(0);
            entity.setId(9L); row.set(entity); return 1;
        });
        when(ledger.markFailed(any(), any())).thenAnswer(i -> { row.get().setStatus("FAILED"); return 1; });
        when(ledger.resetFailedById(any(), any(), any())).thenAnswer(i -> { row.get().setStatus("PROCESSING"); return 1; });
        when(ledger.markSucceeded(any(), any())).thenAnswer(i -> {
            row.get().setStatus("SUCCEEDED"); row.get().setResponseJson(i.getArgument(1)); return 1;
        });
        when(ledger.selectSupportCommand(any(), any())).thenAnswer(i -> List.of(row.get()));
        var mapper = new ObjectMapper().findAndRegisterModules();
        var executor = new ffdd.opsconsole.shared.idempotency.AdminIdempotencyTransactionExecutor(ledger, mapper,
                mock(ffdd.opsconsole.shared.idempotency.AdminIdempotencyExpiryTransitionExecutor.class));
        var retained = new AdminIdempotencyService(executor, clock);
        service = new AppSupportService(conversations, knowledge, retained, audit, eventPublisher, clock,
                productionPathGuard, ledger, mapper, supportAgents, configFacade,
                ffdd.opsconsole.content.SupportTestDependencies.ownership(), ffdd.opsconsole.content.SupportTestDependencies.humanMessages(),
                ffdd.opsconsole.content.SupportTestDependencies.visibility(conversations), org.mockito.Mockito.mock(ffdd.opsconsole.content.application.SupportUnboundReceipt.class));
        var request = new AppSupportService.StartConversationRequest("SUPPORT", "original");
        when(configFacade.activeValue("I.session.cat.advisor.enabled")).thenReturn(Optional.of("off"));
        assertThatThrownBy(() -> service.startConversation(42L, "original-retained-key", request))
                .isInstanceOf(BizException.class).hasMessage("CONVERSATION_CATEGORY_DISABLED");
        assertThat(row.get().getStatus()).isEqualTo("FAILED");
        verify(ledger, never()).markSucceeded(any(), any());
        assertThat(service.commandResult(42L, "original-retained-key").getMessage()).isEqualTo("SUPPORT_COMMAND_FAILED_RETRYABLE");
        String originalHash = row.get().getRequestHash();
        var historical = conversation(42L, "CV-original");
        when(conversations.createUserConversation(any(), any(), any(), any(), any(), any(), any())).thenReturn(historical);
        when(conversations.findByConversationNo("CV-original")).thenReturn(Optional.of(historical));
        when(conversations.userVisibleMessages("CV-original")).thenReturn(List.of());
        when(configFacade.activeValue("I.session.cat.advisor.enabled")).thenReturn(Optional.of("on"));
        assertThat(service.startConversation(42L, "original-retained-key", request).getCode()).isZero();
        assertThat(row.get().getRequestHash()).isEqualTo(originalHash);
        assertThat(row.get().getStatus()).isEqualTo("SUCCEEDED");
        assertThat(service.commandResult(42L, "original-retained-key").getCode()).isZero();
        when(configFacade.activeValue("I.session.cat.advisor.enabled")).thenReturn(Optional.of("off"));
        assertThat(service.startConversation(42L, "original-retained-key", request).getCode()).isZero();
        assertThat(request.conversationType()).isEqualTo("SUPPORT");
        verify(ledger).markSucceeded(any(), any());
        verify(conversations).createUserConversation(any(), any(), org.mockito.ArgumentMatchers.eq("advisor"), any(), any(), any(), any());
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
                new AppSupportService.ConversationCategoryAvailability("support", false),
                new AppSupportService.ConversationCategoryAvailability("ai", true));
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
    void appListAndDetailKeepTypedIdleCloseProvenance() {
        LocalDateTime now = LocalDateTime.now(clock);
        ContentConversationView timeout = new ContentConversationView(1L, "CV-1", 42L, "support", "CLOSED",
                null, "Unassigned", 0, "会话已因用户闲置 5 分钟自动结束,可重新发起会话。", now,
                null, null, null, null, null, null, null, now, 9L, 1L, "IDLE_TIMEOUT_CLOSE");
        when(conversations.pageConversations(any())).thenReturn(new PageResult<>(1, 1, 50, List.of(timeout)));
        when(conversations.findByConversationNo("CV-1")).thenReturn(Optional.of(timeout));

        assertThat(service.conversations(42L, null, 1L, 50L).getData().getRecords())
                .singleElement().extracting(ContentConversationView::lastMessageKind).isEqualTo("IDLE_TIMEOUT_CLOSE");
        assertThat(service.conversation(42L, "CV-1").getData().conversation().lastMessageKind())
                .isEqualTo("IDLE_TIMEOUT_CLOSE");
    }

    @Test
    void manuallyClosedExactCopyHasNoSystemProvenance() {
        LocalDateTime now = LocalDateTime.now(clock);
        ContentConversationView manual = new ContentConversationView(1L, "CV-1", 42L, "support", "CLOSED",
                null, "Unassigned", 0, "会话已因用户闲置 5 分钟自动结束,可重新发起会话。", now,
                null, null, null, null, null, null, null, now, 9L, 1L);
        when(conversations.pageConversations(any())).thenReturn(new PageResult<>(1, 1, 50, List.of(manual)));
        when(conversations.findByConversationNo("CV-1")).thenReturn(Optional.of(manual));

        assertThat(service.conversations(42L, null, 1L, 50L).getData().getRecords())
                .singleElement().extracting(ContentConversationView::lastMessageKind).isNull();
        assertThat(service.conversation(42L, "CV-1").getData().conversation().lastMessageKind()).isNull();
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
    void idempotentConversationRetryPublishesOnlyTheFirstPersistedMutation() throws Exception {
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
        }).when(idempotency).executeRetained(any(), any(), any(), any(), any());
        var request = new AppSupportService.StartConversationRequest("SUPPORT", "help");

        var first = service.startConversation(42L, "stable-idempotency", request);
        when(configFacade.activeValue("I.session.cat.advisor.enabled")).thenReturn(Optional.of("off"));
        var replay = service.startConversation(42L, "stable-idempotency", request);
        assertThat(replay).isEqualTo(first).isNotSameAs(first);
        assertThat(request.conversationType()).isEqualTo("SUPPORT");
        var digest = java.security.MessageDigest.getInstance("SHA-256");
        digest.update(request.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        digest.update((byte) 0);
        String originalHash = java.util.HexFormat.of().formatHex(digest.digest());
        verify(idempotency, times(2)).executeRetained(org.mockito.ArgumentMatchers.eq("APP_CONVERSATION_CREATE:42"),
                org.mockito.ArgumentMatchers.eq("stable-idempotency"), org.mockito.ArgumentMatchers.eq(originalHash), any(), any());

        verify(conversations, times(1)).createUserConversation(any(), any(), org.mockito.ArgumentMatchers.eq("advisor"), any(), any(), any(), any());
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
        verify(supportAgents,never()).ensureSchema();
        ordering.verify(supportAgents).findActiveDedicatedAdvisor(42L);
    }

    @Test
    void unboundAdvisorConversationStoresNoInventedOwner() {
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
                org.mockito.ArgumentMatchers.eq("help"), org.mockito.ArgumentMatchers.isNull(),
                org.mockito.ArgumentMatchers.eq("待分配"), any());
    }

    @Test
    void directServiceCallFailsClosedBeforeAnyFormalSupportDependencyForSandboxUser() {
        doThrow(new RuntimeException("SUPPORT_PRODUCTION_PATH_FORBIDDEN"))
                .when(productionPathGuard).requireAllowed(42L);

        assertThatThrownBy(() -> service.startConversation(
                42L, "direct-bypass", new AppSupportService.StartConversationRequest("SUPPORT", "help")))
                .isInstanceOf(RuntimeException.class);

        verifyNoInteractions(conversations, knowledge, idempotency, audit, eventPublisher);
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

    @Test void changedStaffRequiresARefreshedEntryBeforeAnyNewUserReply() {
        var row = conversation(42L, "CV-OLD-PEER");
        when(conversations.findByConversationNo("CV-OLD-PEER")).thenReturn(Optional.of(row));
        org.mockito.Mockito.doThrow(new BizException(409, "CONVERSATION_PEER_CHANGED"))
                .when(conversations).requirePeerForWrite(org.mockito.ArgumentMatchers.eq("CV-OLD-PEER"),
                        org.mockito.ArgumentMatchers.nullable(String.class));
        assertThatThrownBy(() -> service.replyConversation(42L, "CV-OLD-PEER", "new-reply-key",
                new AppSupportService.ReplyRequest("hello", "OPEN", row.version())))
                .isInstanceOf(BizException.class).hasMessage("CONVERSATION_PEER_CHANGED");
        verify(conversations, never()).replyAsUser(any(), any(), any(), any());
        verifyNoInteractions(audit, eventPublisher);
    }

    @Test void staleUserReplyCannotClaimAnUnassignedPeer() {
        var row = conversation(42L, "CV-UNBOUND");
        when(conversations.findByConversationNo("CV-UNBOUND")).thenReturn(Optional.of(row));
        var result = service.replyConversation(42L, "CV-UNBOUND", "stale-unbound-reply",
                new AppSupportService.ReplyRequest("hello", "OPEN", 99L));
        assertThat(result.getCode()).isEqualTo(409);
        verify(conversations, never()).claimUnassignedPeerForWrite(any(), org.mockito.ArgumentMatchers.nullable(String.class));
    }

    @Test void failedUserReplyAfterClaimThrowsToRollBackPeerMutation() {
        var row = conversation(42L, "CV-UNBOUND");
        when(conversations.findByConversationNo("CV-UNBOUND")).thenReturn(Optional.of(row));
        when(supportAgents.findActiveDedicatedAdvisor(42L)).thenReturn(Optional.of(new DedicatedAdvisorBindingView(7L, "Advisor")));
        when(conversations.claimUnassignedPeerForWrite("CV-UNBOUND", "7")).thenReturn(true);
        assertThatThrownBy(() -> service.replyConversation(42L, "CV-UNBOUND", "racing-unbound-reply",
                new AppSupportService.ReplyRequest("hello", "OPEN", row.version()))).hasMessage("CONVERSATION_CONFLICT");
        verifyNoInteractions(audit, eventPublisher);
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
    void retiredTicketCreateFaqSurfaceIsRejectedLikeAnyUnknownSurface() {
        for (String surface : List.of("Ticket Create", "ticket create", "Unknown Surface")) {
            var result = service.faqPage(42L, "en-US", null, surface, 1L, 20L);
            assertThat(result.getCode()).as(surface).isEqualTo(422);
            assertThat(result.getMessage()).as(surface).isEqualTo("SUPPORT_FAQ_SURFACE_UNSUPPORTED");
        }
        verifyNoInteractions(knowledge);
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
    void staleClosedConversationReadIsRejectedBeforeChangingAnyReceipt() {
        when(conversations.findByConversationNoForUpdate("CV-CLOSED"))
                .thenReturn(Optional.of(closedConversation(42L, "CV-CLOSED")));

        var result = service.markConversationRead(42L, "CV-CLOSED", 11L, "CLOSED", 8L);

        assertThat(result.getCode()).isEqualTo(409);
        verify(conversations, never()).markAgentMessagesReadThrough(any(), any(), any(), any(), any(), any());
    }

    @Test
    void repeatedClosedConversationReadIsIdempotentAndDoesNotPublishAnotherReceiptEvent() {
        ContentConversationView conversation = closedConversation(42L, "CV-CLOSED");
        when(conversations.findByConversationNoForUpdate("CV-CLOSED")).thenReturn(Optional.of(conversation));
        when(conversations.findByConversationNo("CV-CLOSED")).thenReturn(Optional.of(conversation));
        LocalDateTime now = LocalDateTime.now(clock);
        ContentConversationMessageView read = new ContentConversationMessageView(
                11L, 1L, "CV-CLOSED", 9L, "agent", "A", "reply", "read", now);
        when(conversations.userVisibleMessages("CV-CLOSED")).thenReturn(List.of(read));
        when(conversations.markAgentMessagesReadThrough("CV-CLOSED", 11L, "user:42", now, "CLOSED", 9L))
                .thenReturn(true, false);

        var first = service.markConversationRead(42L, "CV-CLOSED", 11L, "CLOSED", 9L);
        var replay = service.markConversationRead(42L, "CV-CLOSED", 11L, "CLOSED", 9L);

        assertThat(first.getCode()).isZero();
        assertThat(replay.getCode()).isZero();
        assertThat(replay.getData().conversation().unreadCount()).isZero();
        assertThat(replay.getData().conversation().status()).isEqualTo("CLOSED");
        assertThat(replay.getData().conversation().version()).isEqualTo(9L);
        verify(conversations, times(2)).markAgentMessagesReadThrough("CV-CLOSED", 11L, "user:42", now, "CLOSED", 9L);
        verify(eventPublisher, times(1)).publishEvent(any(ConversationMessageEvent.class));
    }

    @Test
    void closedConversationReplyResumesTheOriginalWindow() {
        ContentConversationView closed = closedConversation(42L, "CV-CLOSED");
        when(conversations.findByConversationNo("CV-CLOSED")).thenReturn(Optional.of(closed));
        when(conversations.replyAsUser(eq(closed),eq(42L),eq("continue"),any())).thenReturn(true);
        var result = service.replyConversation(42L, "CV-CLOSED", "closed-reply-key",
                new AppSupportService.ReplyRequest("continue", "CLOSED", 9L));
        assertThat(result.getCode()).isZero();
        assertThat(result.getData().conversation().conversationNo()).isEqualTo("CV-CLOSED");
        verify(conversations).replyAsUser(eq(closed),eq(42L),eq("continue"),any());
    }

    @Test
    void closedConversationReadAdvancesOnlyVisibleAgentMessagesWithoutChangingConversationState() {
        ContentConversationView closed = closedConversation(42L, "CV-CLOSED");
        when(conversations.findByConversationNoForUpdate("CV-CLOSED")).thenReturn(Optional.of(closed));
        when(conversations.findByConversationNo("CV-CLOSED")).thenReturn(Optional.of(closed));
        LocalDateTime now = LocalDateTime.now(clock);
        ContentConversationMessageView readTarget = new ContentConversationMessageView(
                11L, 1L, "CV-CLOSED", 9L, "agent", "A", "read target", "read", now);
        ContentConversationMessageView laterAgent = new ContentConversationMessageView(
                12L, 1L, "CV-CLOSED", 9L, "agent", "A", "later unread", "sent", now);
        ContentConversationMessageView userMessage = new ContentConversationMessageView(
                13L, 1L, "CV-CLOSED", 42L, "user", "User", "not a receipt target", "sent", now);
        when(conversations.userVisibleMessages("CV-CLOSED")).thenReturn(List.of(readTarget, laterAgent, userMessage));
        when(conversations.unreadUserVisibleAgentMessageCount("CV-CLOSED")).thenReturn(1);
        when(conversations.markAgentMessagesReadThrough("CV-CLOSED", 11L, "user:42", now, "CLOSED", 9L))
                .thenReturn(true);

        var result = service.markConversationRead(42L, "CV-CLOSED", 11L, "CLOSED", 9L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().conversation().status()).isEqualTo("CLOSED");
        assertThat(result.getData().conversation().version()).isEqualTo(9L);
        assertThat(result.getData().conversation().unreadCount()).isEqualTo(1);
        verify(conversations).markAgentMessagesReadThrough("CV-CLOSED", 11L, "user:42", now, "CLOSED", 9L);
    }

    private ContentConversationView conversation(Long userId, String number) {
        LocalDateTime now = LocalDateTime.now(clock);
        return new ContentConversationView(1L, number, userId, "support", "OPEN", null, "Unassigned",
                9, "help", now, null, null, null, null, null, null, null, now, 9L);
    }

    private ContentConversationView closedConversation(Long userId, String number) {
        LocalDateTime now = LocalDateTime.now(clock);
        return new ContentConversationView(1L, number, userId, "support", "CLOSED", null, "Unassigned",
                1, "done", now, null, null, null, null, null, null, null, now, 9L);
    }
}

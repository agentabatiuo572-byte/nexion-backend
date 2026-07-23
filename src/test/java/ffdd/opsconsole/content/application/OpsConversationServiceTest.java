package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.AdvisorRoutingDecision;
import ffdd.opsconsole.content.domain.ContentConversationMessageView;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationCustomerProfile;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.domain.CustomerProfileRepository;
import ffdd.opsconsole.content.domain.SupportTicketMessageView;
import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.dto.ConversationArchiveRequest;
import ffdd.opsconsole.content.dto.ConversationArchiveBatchRequest;
import ffdd.opsconsole.content.dto.ConversationFallbackRequest;
import ffdd.opsconsole.content.dto.ConversationInitiateRequest;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import ffdd.opsconsole.content.dto.ConversationReplyRequest;
import ffdd.opsconsole.content.dto.ConversationStatusRequest;
import ffdd.opsconsole.content.dto.ConversationTicketRequest;
import ffdd.opsconsole.content.dto.ConversationTransferDecisionRequest;
import ffdd.opsconsole.content.dto.ConversationTransferRequest;
import ffdd.opsconsole.content.dto.CustomerNoteRemoveRequest;
import ffdd.opsconsole.content.dto.CustomerNoteRequest;
import ffdd.opsconsole.content.dto.CustomerTagRequest;
import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

class OpsConversationServiceTest {
    private final FakeConversationRepository conversationRepository = new FakeConversationRepository();
    private final FakeSupportTicketRepository ticketRepository = new FakeSupportTicketRepository();
    private final OpsSupportAgentService supportAgentService = mock(OpsSupportAgentService.class);
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final CustomerProfileRepository customerProfileRepository = mock(CustomerProfileRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsConversationService service = service();

    private OpsConversationService service() {
        var assignable = new ffdd.opsconsole.content.domain.SupportAgentProfileView(
                "agent-2", 11L, "Agent Two", "agent2@example.com", "support", "enabled",
                "support", "客服", List.of("support"), List.of(), 8, true, true, false, 0L, "2026-06-17T00:00:00");
        when(supportAgentService.transferTargets())
                .thenReturn(List.of(Map.of("targetType", "agent", "targetId", "agent-2", "targetName", "Agent Two")));
        when(supportAgentService.currentAssignableSupportAgent()).thenReturn(java.util.Optional.of(assignable));
        when(supportAgentService.assignableSupportAgent(11L)).thenReturn(java.util.Optional.of(assignable));
        return new OpsConversationService(
                conversationRepository,
                ticketRepository,
                supportAgentService,
                configFacade,
                auditLogService,
                clock,
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                mock(ffdd.opsconsole.user.application.OpsUserService.class),
                mock(ffdd.opsconsole.finance.application.OpsFinanceService.class),
                mock(ffdd.opsconsole.device.application.OpsDeviceService.class),
                mock(ffdd.opsconsole.risk.application.OpsRiskService.class),
                customerProfileRepository);
    }

    @Test
    void transferRequiresReasonAtLeastEightCharacters() {
        var result = service.transfer(
                "CV-1",
                "idem-i9",
                new ConversationTransferRequest("agent", "agent-2", "Agent Two", "1234567", "agent-1"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void transferRejectsReasonLongerThanTwoHundredCharacters() {
        var result = service.transfer(
                "CV-1",
                "idem-i9",
                new ConversationTransferRequest("agent", "agent-2", "Agent Two", "r".repeat(201), "agent-1"));

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
    void competingTransferThatLosesHeaderClaimReturns409WithoutMessageOrAudit() {
        conversationRepository.conversation = conversation("CV-RACE", "OPEN");
        conversationRepository.stateClaimSucceeds = false;

        var result = service.transfer(
                "CV-RACE",
                "idem-i9-transfer-race",
                new ConversationTransferRequest("agent", "agent-2", "Agent Two", "needs specialist", "agent-1"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isEqualTo(1);
        verifyNoInteractions(auditLogService);
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
    void transferRejectsUnknownAgentInsteadOfCreatingGhostOwner() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");

        var result = service.transfer(
                "CV-1",
                "idem-i9-ghost",
                new ConversationTransferRequest("agent", "ghost-agent", "Ghost Agent", "send to unknown agent", "agent-1"));

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("CONVERSATION_TRANSFER_TARGET_NOT_AVAILABLE");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
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
    void acceptTransferThatLosesPendingClaimReturns409WithoutMessageOrAudit() {
        conversationRepository.conversation = transferredConversation("CV-ACCEPT-RACE");
        conversationRepository.stateClaimSucceeds = false;

        var result = service.acceptTransfer(
                "CV-ACCEPT-RACE",
                "idem-i9-accept-race",
                new ConversationTransferDecisionRequest("accept incoming", "agent-2"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(conversationRepository.messageWrites).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void returnTransferThatLosesPendingClaimReturns409WithoutMessageOrAudit() {
        conversationRepository.conversation = transferredConversation("CV-RETURN-RACE");
        conversationRepository.stateClaimSucceeds = false;

        var result = service.returnTransfer(
                "CV-RETURN-RACE",
                "idem-i9-return-race",
                new ConversationTransferDecisionRequest("return to original owner", "agent-2"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(conversationRepository.messageWrites).isZero();
        verifyNoInteractions(auditLogService);
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
    void waitTransferThatLosesToDecisionReturns409WithoutMessageOrAudit() {
        conversationRepository.conversation = transferredConversation("CV-WAIT-RACE");
        conversationRepository.stateClaimSucceeds = false;

        var result = service.waitTransfer(
                "CV-WAIT-RACE",
                "idem-i9-wait-race",
                new ConversationTransferDecisionRequest("continue waiting for receiving agent", "agent-2"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(conversationRepository.messageWrites).isZero();
        verifyNoInteractions(auditLogService);
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
    void replyRejectsAuditReasonShorterThanEightCharactersWithoutWriting() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");

        var result = service.reply(
                "CV-1",
                "idem-i9-reply-short-reason",
                new ConversationReplyRequest("This must not be appended.", "too few", "Marina K."));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.REASON_REQUIRED.name());
        assertThat(conversationRepository.conversation.lastMessage()).isNotEqualTo("This must not be appended.");
        verifyNoInteractions(auditLogService);
    }

    @Test
    void replyRejectsAuditReasonLongerThanTwoHundredCharactersWithoutWriting() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");

        var result = service.reply(
                "CV-1",
                "idem-i9-reply-long-reason",
                new ConversationReplyRequest("This must not be appended.", "r".repeat(201), "Marina K."));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.REASON_REQUIRED.name());
        assertThat(conversationRepository.conversation.lastMessage()).isNotEqualTo("This must not be appended.");
        verifyNoInteractions(auditLogService);
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
    void replyThatLosesToConcurrentCloseReturns409WithoutMessageOrAudit() {
        conversationRepository.conversation = conversation("CV-REPLY-RACE", "OPEN");
        conversationRepository.stateClaimSucceeds = false;

        var result = service.reply(
                "CV-REPLY-RACE",
                "idem-i9-reply-race",
                new ConversationReplyRequest("Please wait", "agent reply", "Marina K."));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(conversationRepository.messageWrites).isZero();
        verifyNoInteractions(auditLogService);
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
    void updateStatusRejectsStaleExpectedStatusBeforeClaimAndAudit() {
        conversationRepository.conversation = conversation("CV-STATUS-STALE", "RESOLVED");

        var result = service.updateStatus(
                "CV-STATUS-STALE",
                "idem-i9-status-stale",
                new ConversationStatusRequest("CLOSED", "OPEN", "close after resolution", "Marina K."));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(conversationRepository.stateWriteAttempts).isZero();
        verifyNoInteractions(auditLogService);
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
    void initiateAdvisorConversationRoutesToM5DedicatedAdvisorInsteadOfRequestOwner() {
        when(supportAgentService.routeAdvisorForUser(1001L))
                .thenReturn(new AdvisorRoutingDecision(
                        "agent",
                        "88",
                        "Dedicated Advisor",
                        88L,
                        true,
                        false,
                        "M5_ASSIGNED_ADVISOR"));

        var result = service.initiate(
                "idem-m3-advisor",
                new ConversationInitiateRequest("advisor", 1001L, "spoofed-agent", "Spoofed Agent",
                        "Please check my advisor session.", "enter advisor session", "user"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().ownerAgentId()).isEqualTo("88");
        assertThat(result.getData().ownerAgentName()).isEqualTo("Dedicated Advisor");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("routingReason", "M5_ASSIGNED_ADVISOR")
                .containsEntry("dedicatedAdvisor", true);
    }

    @Test
    void initiateAdvisorConversationCreatesM2TicketWhenRoutedToStandbyPool() {
        when(supportAgentService.routeAdvisorForUser(1001L))
                .thenReturn(new AdvisorRoutingDecision(
                        "standby",
                        "standby-pool",
                        "备勤池",
                        null,
                        false,
                        true,
                        "NO_ADVISOR_AVAILABLE"));

        var result = service.initiate(
                "idem-m3-standby",
                new ConversationInitiateRequest("advisor", 1001L, "agent-1", "Agent One",
                        "No advisor is online.", "route to standby", "user"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().ownerAgentId()).isEqualTo("standby-pool");
        assertThat(ticketRepository.ticket).isNotNull();
        assertThat(ticketRepository.ticket.ticketNo()).startsWith("TK-");
        assertThat(ticketRepository.ticket.assignedAdminName()).isEqualTo("备勤池");
        assertThat(ticketRepository.ticket.lastMessage()).contains("Advisor conversation routed to 备勤池");
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
    void readReceiptPersistsOnLatestAgentMessageAndReturnsMinimalAck() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");

        var result = service.markReadReceipt("CV-1", 1L, 1001L);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isNull();
        assertThat(conversationRepository.receiptStatus).isEqualTo("read");
    }

    @Test
    void readReceiptRejectsAUserWhoDoesNotOwnTheConversation() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");

        var result = service.markReadReceipt("CV-1", 1L, 9999L);

        assertThat(result.getCode()).isEqualTo(403);
        assertThat(conversationRepository.receiptStatus).isEqualTo("sent");
    }

    @Test
    void addCustomTagPersistsAndReturnsLatestList() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");
        when(customerProfileRepository.findCustomTags(1001L)).thenReturn(List.of("高净值"));

        var result = service.addCustomTag("CV-1", "idem-tag", new CustomerTagRequest("高净值", "add vip tag", "agent-1"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsExactly("高净值");
        verify(customerProfileRepository).addCustomTag(eq(1001L), eq("高净值"), eq("agent-1"), any());

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I9_CUSTOMER_TAG_ADDED");
    }

    @Test
    void addCustomTagIsIdempotentOnDuplicateKey() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");
        doThrow(new DuplicateKeyException("dup")).when(customerProfileRepository)
                .addCustomTag(eq(1001L), any(), any(), any());
        when(customerProfileRepository.findCustomTags(1001L)).thenReturn(List.of("高净值"));

        var result = service.addCustomTag("CV-1", "idem-tag", new CustomerTagRequest("高净值", "add vip tag", "agent-1"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsExactly("高净值");
    }

    @Test
    void addCustomTagRejectsConversationWithoutUserId() {
        conversationRepository.conversation = new ContentConversationView(
                1L, "CV-BATCH", null, "support", "OPEN",
                "agent-1", "Agent One", 0, "audience broadcast", LocalDateTime.now(),
                null, null, null, null, null, null, null, LocalDateTime.now());

        var result = service.addCustomTag("CV-BATCH", "idem-tag", new CustomerTagRequest("高净值", "add vip tag", "agent-1"));

        assertThat(result.getCode()).isEqualTo(404);
    }

    @Test
    void addCustomTagRequiresReasonAtLeastSixCharacters() {
        var result = service.addCustomTag("CV-1", "idem-tag", new CustomerTagRequest("高净值", "short", "agent-1"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void removeCustomTagReturns404WhenTagMissing() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");
        when(customerProfileRepository.removeCustomTag(1001L, "高净值")).thenReturn(false);

        var result = service.removeCustomTag("CV-1", "idem-tag", new CustomerTagRequest("高净值", "remove vip tag", "agent-1"));

        assertThat(result.getCode()).isEqualTo(404);
    }

    @Test
    void addNoteReturnsCreatedNoteAndAudits() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");
        ConversationCustomerProfile.CustomerNote created = new ConversationCustomerProfile.CustomerNote("1", 0L, "agent-1", "测试备注");
        when(customerProfileRepository.addNote(eq(1001L), eq("agent-1"), eq("测试备注"), eq("agent-1"), any())).thenReturn(created);

        var result = service.addNote("CV-1", "idem-note", new CustomerNoteRequest("测试备注", "add note reason", "agent-1"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).isEqualTo("1");
        assertThat(result.getData().text()).isEqualTo("测试备注");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I9_CUSTOMER_NOTE_ADDED");
    }

    @Test
    void removeNoteSoftDeletesAndAudits() {
        when(customerProfileRepository.removeNote(eq(5L), eq("agent-1"), any())).thenReturn(true);

        var result = service.removeNote("CV-1", 5L, "idem-note", new CustomerNoteRemoveRequest("remove note reason", "agent-1"));

        assertThat(result.getCode()).isZero();
        verify(customerProfileRepository).removeNote(eq(5L), eq("agent-1"), any());

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I9_CUSTOMER_NOTE_REMOVED");
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
    void archiveRejectsStaleExpectedStatusBeforeClaimAndAudit() {
        conversationRepository.conversation = conversation("CV-ARCHIVE-STALE", "OPEN");

        var result = service.archive(
                "CV-ARCHIVE-STALE",
                "idem-i9-archive-stale",
                new ConversationArchiveRequest(true, "RESOLVED", "archive resolved session", "Marina K."));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(conversationRepository.stateWriteAttempts).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void archiveBatchThrowsAfterAnyLostClaimSoTransactionCanRollBackAllRows() throws Exception {
        ContentConversationView first = conversation("CV-BATCH-1", "RESOLVED");
        ContentConversationView second = conversation("CV-BATCH-2", "RESOLVED");
        conversationRepository.conversations.put(first.conversationNo(), first);
        conversationRepository.conversations.put(second.conversationNo(), second);
        conversationRepository.failStateClaimOnAttempt = 2;

        assertThatThrownBy(() -> service.archiveBatch(
                "idem-i9-archive-batch-race",
                new ConversationArchiveBatchRequest(
                        List.of("CV-BATCH-2", "CV-BATCH-1"),
                        "archive resolved sessions",
                        "Marina K.")))
                .isInstanceOf(OpsConversationService.ConversationStateConflictException.class);

        assertThat(conversationRepository.lockOrder).containsExactly("CV-BATCH-1", "CV-BATCH-2");
        assertThat(OpsConversationService.class
                .getMethod("archiveBatch", String.class, ConversationArchiveBatchRequest.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class)
                .rollbackFor()).contains(Exception.class);
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
    void fallbackThatLosesToAcceptOrReturnReturns409WithoutMessageOrAudit() {
        conversationRepository.conversation = transferredConversation("CV-FALLBACK-RACE");
        conversationRepository.fallbackClaimSucceeds = false;

        var result = service.fallbackTransfer(
                "CV-FALLBACK-RACE",
                "idem-i9-fallback-race",
                new ConversationFallbackRequest("pending transfer timeout", "Marina K."));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
        assertThat(conversationRepository.messageWrites).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void timeoutFallbackDoesNothingWhenWorkbenchPolicyOff() {
        configFacade.values.put("I.session.workbench.timeoutFallback", "off");
        conversationRepository.overdueConversations = List.of(transferredConversation("CV-OVER"));

        int changed = service.runTimeoutFallback();

        assertThat(changed).isZero();
        assertThat(conversationRepository.seedCalls).isZero();
        assertThat(conversationRepository.fallbackCount).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void timeoutFallbackMovesOverdueTransfersToStandbyAndAudits() {
        configFacade.values.put("I.session.workbench.timeoutFallback", "on");
        ContentConversationView overdue = transferredConversation("CV-OVER");
        conversationRepository.conversation = overdue;
        conversationRepository.overdueConversations = List.of(overdue);

        int changed = service.runTimeoutFallback();

        assertThat(changed).isEqualTo(1);
        assertThat(conversationRepository.fallbackCount).isEqualTo(1);
        assertThat(conversationRepository.lastCutoff).isEqualTo(LocalDateTime.of(2026, 6, 16, 23, 30));
        assertThat(conversationRepository.lastLimit).isEqualTo(50);
        assertThat(conversationRepository.conversation.transferToId()).isEqualTo("standby-pool");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I9_CONVERSATION_TRANSFER_AUTO_FALLBACK");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("timeoutMinutes", 30);
    }

    @Test
    void timeoutFallbackDoesNotAuditWhenRepositoryClaimFails() {
        configFacade.values.put("I.session.workbench.timeoutFallback", "on");
        conversationRepository.fallbackClaimSucceeds = false;
        conversationRepository.overdueConversations = List.of(transferredConversation("CV-RACE"));

        int changed = service.runTimeoutFallback();

        assertThat(changed).isZero();
        assertThat(conversationRepository.fallbackCount).isZero();
        assertThat(conversationRepository.lastCutoff).isEqualTo(LocalDateTime.of(2026, 6, 16, 23, 30));
        verifyNoInteractions(auditLogService);
    }

    @Test
    void timeoutFallbackRereadsUnderLockAndSkipsAConversationAlreadyAccepted() {
        configFacade.values.put("I.session.workbench.timeoutFallback", "on");
        ContentConversationView stale = transferredConversation("CV-TIMEOUT-RACE");
        conversationRepository.overdueConversations = List.of(stale);
        conversationRepository.conversation = conversation("CV-TIMEOUT-RACE", "OPEN");

        int changed = service.runTimeoutFallback();

        assertThat(changed).isZero();
        assertThat(conversationRepository.fallbackCount).isZero();
        assertThat(conversationRepository.lockedReads).isEqualTo(1);
        verifyNoInteractions(auditLogService);
    }

    @Test
    void convertToTicketCreatesSupportTicketAndClosesConversation() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");

        var result = service.convertToTicket(
                "CV-1",
                "idem-i9-ticket",
                new ConversationTicketRequest("account", "HIGH", "Need account follow-up", 11L, "Tessa", "escalate to ticket", "Marina K."));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().conversation().status()).isEqualTo("CLOSED");
        assertThat(result.getData().ticket().ticket().ticketNo()).startsWith("TK-");
        assertThat(ticketRepository.ticket.category()).isEqualTo("account");
        assertThat(ticketRepository.ticket.priority()).isEqualTo("HIGH");
    }

    @Test
    void convertToTicketRejectsASecondConversionForTheSameConversation() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");
        ConversationTicketRequest request = new ConversationTicketRequest(
                "account", "HIGH", "Need account follow-up", 11L, "Tessa", "escalate to ticket", "Marina K.");

        var first = service.convertToTicket("CV-1", "idem-i9-ticket-1", request);
        var second = service.convertToTicket("CV-1", "idem-i9-ticket-2", request);

        assertThat(first.getCode()).isZero();
        assertThat(second.getCode()).isEqualTo(409);
        assertThat(second.getMessage()).isEqualTo("INVALID_STATE_TRANSITION");
        assertThat(conversationRepository.conversation.status()).isEqualTo("CLOSED");
    }

    @Test
    void convertToTicketRejectsAConcurrentRequestThatLosesTheAtomicClaim() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");
        conversationRepository.conversionClaimSucceeds = false;

        var result = service.convertToTicket(
                "CV-1",
                "idem-i9-ticket-race",
                new ConversationTicketRequest(
                        "account", "HIGH", "Need account follow-up", 11L, "Tessa", "escalate to ticket", "Marina K."));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("CONVERSATION_ALREADY_CONVERTED_TO_TICKET");
        assertThat(ticketRepository.ticket).isNull();
    }

    @Test
    void overviewExposesI9TransferStateMachine() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(conversationRepository.seedCalls).isZero();
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
                LocalDateTime.of(2026, 6, 16, 23, 0),
                LocalDateTime.now());
    }

    private static final class FakeConversationRepository implements ConversationRepository {
        private ContentConversationView conversation;
        private List<ContentConversationView> overdueConversations = List.of();
        private ConversationQueryRequest lastQuery;
        private int seedCalls;
        private int fallbackCount;
        private LocalDateTime lastCutoff;
        private int lastLimit;
        private boolean fallbackClaimSucceeds = true;
        private boolean conversionClaimSucceeds = true;
        private String receiptStatus = "sent";
        private boolean stateClaimSucceeds = true;
        private int failStateClaimOnAttempt = Integer.MAX_VALUE;
        private int stateWriteAttempts;
        private int messageWrites;
        private int lockedReads;
        private final Map<String, ContentConversationView> conversations = new LinkedHashMap<>();
        private final List<String> lockOrder = new ArrayList<>();

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
            ContentConversationView mapped = conversations.get(conversationNo);
            if (mapped != null) return Optional.of(mapped);
            return Optional.ofNullable(conversation != null && conversationNo.equals(conversation.conversationNo())
                    ? conversation
                    : null);
        }

        @Override
        public Optional<ContentConversationView> findByConversationNoForUpdate(String conversationNo) {
            lockedReads += 1;
            lockOrder.add(conversationNo);
            return findByConversationNo(conversationNo);
        }

        @Override
        public List<ContentConversationMessageView> messages(String conversationNo) {
            return List.of(new ContentConversationMessageView(
                    1L,
                    conversation == null ? 1L : conversation.id(),
                    conversationNo,
                    1001L,
                    "agent",
                    "Agent One",
                    "please help",
                    receiptStatus,
                    LocalDateTime.now()));
        }

        @Override
        public boolean markAgentMessagesReadThrough(String conversationNo, Long lastSeenMessageId, String operator, LocalDateTime now) {
            receiptStatus = "read";
            return true;
        }

        @Override
        public List<ContentConversationView> overdueTransferredConversations(LocalDateTime cutoff, int limit) {
            lastCutoff = cutoff;
            lastLimit = limit;
            return overdueConversations;
        }

        @Override
        public boolean transferToPending(
                ContentConversationView conversation,
                String targetType,
                String targetId,
                String targetName,
                String reason,
                String operator,
                LocalDateTime now) {
            if (!claimState()) return false;
            store(new ContentConversationView(
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
                    now));
            messageWrites += 1;
            return true;
        }

        @Override
        public boolean acceptTransfer(ContentConversationView conversation, String ownerAgentId, String ownerAgentName, String operator, LocalDateTime now) {
            if (!claimState()) return false;
            store(new ContentConversationView(
                    conversation.id(),
                    conversation.conversationNo(),
                    conversation.userId(),
                    conversation.conversationType(),
                    "OPEN",
                    ownerAgentId,
                    ownerAgentName,
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
                    now));
            messageWrites += 1;
            return true;
        }

        @Override
        public boolean returnTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
            if (!claimState()) return false;
            store(new ContentConversationView(
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
                    now));
            messageWrites += 1;
            return true;
        }

        @Override
        public boolean waitTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
            if (!claimState()) return false;
            store(new ContentConversationView(
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
                    now));
            messageWrites += 1;
            return true;
        }

        @Override
        public boolean reply(ContentConversationView conversation, String body, String operator, LocalDateTime now) {
            if (!claimState()) return false;
            store(new ContentConversationView(
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
                    now));
            messageWrites += 1;
            return true;
        }

        @Override
        public boolean updateStatus(ContentConversationView conversation, String status, String operator, LocalDateTime now) {
            if (!claimState()) return false;
            store(new ContentConversationView(
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
                    now));
            messageWrites += 1;
            return true;
        }

        @Override
        public boolean archive(ContentConversationView conversation, boolean archived, String operator, LocalDateTime now) {
            return updateStatus(conversation, archived ? "CLOSED" : "RESOLVED", operator, now);
        }

        @Override
        public boolean fallbackTransfer(ContentConversationView conversation, String reason, String operator, LocalDateTime now) {
            if (!fallbackClaimSucceeds || !claimState()) {
                return false;
            }
            fallbackCount += 1;
            store(new ContentConversationView(
                    conversation.id(), conversation.conversationNo(), conversation.userId(), conversation.conversationType(),
                    "TRANSFERRED", "standby-pool", "Standby pool", conversation.unreadCount(),
                    conversation.lastMessage(), conversation.lastMessageAt(), conversation.transferFromAgentId(),
                    conversation.transferFromAgentName(), "standby", "standby-pool", "Standby pool", reason,
                    conversation.transferredAt(), now));
            messageWrites += 1;
            return true;
        }

        @Override
        public boolean markConvertedToTicket(ContentConversationView conversation, String ticketNo, String operator, LocalDateTime now) {
            if (!conversionClaimSucceeds || "CLOSED".equalsIgnoreCase(conversation.status())) {
                return false;
            }
            return updateStatus(conversation, "CLOSED", operator, now);
        }

        @Override
        public ContentConversationView createConversation(String conversationNo, Long userId, String conversationType,
                                                         String ownerAgentId, String ownerAgentName,
                                                         String openingText, LocalDateTime now) {
            store(new ContentConversationView(
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
                    now));
            return this.conversation;
        }

        private boolean claimState() {
            stateWriteAttempts += 1;
            return stateClaimSucceeds && stateWriteAttempts != failStateClaimOnAttempt;
        }

        private void store(ContentConversationView updated) {
            this.conversation = updated;
            if (conversations.containsKey(updated.conversationNo())) {
                conversations.put(updated.conversationNo(), updated);
            }
        }
    }

    private static final class FakePlatformConfigFacade implements PlatformConfigFacade {
        private final Map<String, String> values = new LinkedHashMap<>();

        @Override
        public Optional<String> activeValue(String configKey) {
            return Optional.ofNullable(values.get(configKey));
        }

        @Override
        public void upsertAdminValue(String configKey, String configValue, String valueType, String configGroup, String remark) {
            values.put(configKey, configValue);
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
                    now,
                    false,
                    null);
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

        @Override
        public void archive(SupportTicketView ticket, boolean archived, String operator, LocalDateTime now) {
        }

        @Override
        public void appendSystemTrace(SupportTicketView ticket, String body, LocalDateTime now) {
            messages = java.util.stream.Stream.concat(
                    messages.stream(),
                    java.util.stream.Stream.of(new SupportTicketMessageView(
                            (long) messages.size() + 1, ticket.id(), ticket.ticketNo(), null,
                            "system", "系统", body, now)))
                    .toList();
        }
    }
}

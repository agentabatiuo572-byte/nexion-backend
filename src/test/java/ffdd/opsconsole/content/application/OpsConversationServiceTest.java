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
import ffdd.opsconsole.content.dto.ConversationArchiveRequest;
import ffdd.opsconsole.content.dto.ConversationArchiveBatchRequest;
import ffdd.opsconsole.content.dto.ConversationInitiateRequest;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import ffdd.opsconsole.content.dto.ConversationReplyRequest;
import ffdd.opsconsole.content.dto.ConversationStatusRequest;
import ffdd.opsconsole.content.dto.ConversationTransferDecisionRequest;
import ffdd.opsconsole.content.dto.ConversationTransferRequest;
import ffdd.opsconsole.content.dto.CustomerNoteRemoveRequest;
import ffdd.opsconsole.content.dto.CustomerNoteRequest;
import ffdd.opsconsole.content.dto.CustomerTagRequest;
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
    @org.junit.jupiter.api.BeforeEach void authenticatedEditor() {
        var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("1",null,
                List.of(new org.springframework.security.core.authority.SimpleGrantedAuthority("service_m3_write")));
        auth.setDetails(Map.of("subjectType","ADMIN","username","agent-1"));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
    }
    @org.junit.jupiter.api.AfterEach void clearAuthentication(){org.springframework.security.core.context.SecurityContextHolder.clearContext();}
    private final FakeConversationRepository conversationRepository = new FakeConversationRepository();
    private final OpsSupportAgentService supportAgentService = mock(OpsSupportAgentService.class);
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final CustomerProfileRepository customerProfileRepository = mock(CustomerProfileRepository.class);
    private final SupportCustomerProfileService customerProfiles=mock(SupportCustomerProfileService.class);
    private final SupportOwnershipService ownership = ffdd.opsconsole.content.SupportTestDependencies.ownership();
    private final SupportHumanMessageService humanMessages = ffdd.opsconsole.content.SupportTestDependencies.humanMessages();
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-17T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsConversationService service = service();

    private OpsConversationService service() {
        when(ownership.defaultQueryScope(null,null)).thenReturn(new ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope(
                1L,ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.ALL,null,null));
        var assignable = new ffdd.opsconsole.content.domain.SupportAgentProfileView(
                "agent-2", 11L, "Agent Two", "agent2@example.com", "support", "enabled",
                "support", "客服", List.of("support"), List.of(), 8, true, true, false, 0L, "2026-06-17T00:00:00");
        when(supportAgentService.transferTargets())
                .thenReturn(List.of(Map.of("targetType", "agent", "targetId", "agent-2", "targetName", "Agent Two")));
        when(supportAgentService.currentAssignableSupportAgent()).thenReturn(java.util.Optional.of(assignable));
        when(supportAgentService.assignableSupportAgent(11L)).thenReturn(java.util.Optional.of(assignable));
        return new OpsConversationService(
                conversationRepository,
                supportAgentService,
                configFacade,
                auditLogService,
                clock,
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                mock(ffdd.opsconsole.user.application.OpsUserService.class),
                mock(ffdd.opsconsole.finance.application.OpsFinanceService.class),
                mock(ffdd.opsconsole.device.application.OpsDeviceService.class),
                mock(ffdd.opsconsole.risk.application.OpsRiskService.class),
                customerProfileRepository,
                mock(ProductionSupportPathGuard.class), ownership, humanMessages, org.mockito.Mockito.mock(ffdd.opsconsole.content.application.SupportReplyService.class), customerProfiles);
    }

    @Test
    void directOpsServiceGatewayFailsClosedBeforeAnyFormalConversationDependency() {
        ConversationRepository conversations = mock(ConversationRepository.class);
        OpsSupportAgentService agents = mock(OpsSupportAgentService.class);
        PlatformConfigFacade config = mock(PlatformConfigFacade.class);
        AuditLogService audit = mock(AuditLogService.class);
        CustomerProfileRepository profiles = mock(CustomerProfileRepository.class);
        ProductionSupportPathGuard productionGuard = mock(ProductionSupportPathGuard.class);
        doThrow(new RuntimeException("SUPPORT_PRODUCTION_PATH_FORBIDDEN"))
                .when(productionGuard).requireOpsWriteAllowed();
        OpsConversationService direct = new OpsConversationService(
                conversations, agents, config, audit, clock,
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction(),
                mock(ffdd.opsconsole.user.application.OpsUserService.class),
                mock(ffdd.opsconsole.finance.application.OpsFinanceService.class),
                mock(ffdd.opsconsole.device.application.OpsDeviceService.class),
                mock(ffdd.opsconsole.risk.application.OpsRiskService.class), profiles, productionGuard, ffdd.opsconsole.content.SupportTestDependencies.ownership(), ffdd.opsconsole.content.SupportTestDependencies.humanMessages(), org.mockito.Mockito.mock(ffdd.opsconsole.content.application.SupportReplyService.class), mock(SupportCustomerProfileService.class));

        assertThatThrownBy(direct::runTimeoutFallback).isInstanceOf(RuntimeException.class);

        verifyNoInteractions(conversations, agents, config, audit, profiles);
    }

    @Test
    void transferRequiresReasonAtLeastEightCharacters() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.transfer("CV-1","retired-transfer-key", new ConversationTransferRequest("agent","agent-2","Agent Two","formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void transferRejectsReasonLongerThanTwoHundredCharacters() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.transfer("CV-1","retired-transfer-key", new ConversationTransferRequest("agent","agent-2","Agent Two","formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void transferOpenConversationToIncomingPendingWithoutDuplicatingTheTransferLedgerInA2() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.transfer("CV-1","retired-transfer-key", new ConversationTransferRequest("agent","agent-2","Agent Two","formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void competingTransferThatLosesHeaderClaimReturns409WithoutMessageOrAudit() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.transfer("CV-1","retired-transfer-key", new ConversationTransferRequest("agent","agent-2","Agent Two","formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void transferRejectsClosedConversationWith409() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.transfer("CV-1","retired-transfer-key", new ConversationTransferRequest("agent","agent-2","Agent Two","formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void transferRejectsUnknownAgentInsteadOfCreatingGhostOwner() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.transfer("CV-1","retired-transfer-key", new ConversationTransferRequest("agent","agent-2","Agent Two","formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void transferRejectsTheCurrentOwnerEvenWhenTheAgentIsOtherwiseTransferable() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.transfer("CV-1","retired-transfer-key", new ConversationTransferRequest("agent","agent-2","Agent Two","formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void transferRechecksAvailabilityWhenPreviouslyListedAgentHasPaused() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.transfer("CV-1","retired-transfer-key", new ConversationTransferRequest("agent","agent-2","Agent Two","formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void acceptTransferMovesConversationBackToOpen() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.acceptTransfer("CV-1","retired-transfer-key", new ConversationTransferDecisionRequest("formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void acceptTransferThatLosesPendingClaimReturns409WithoutMessageOrAudit() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.acceptTransfer("CV-1","retired-transfer-key", new ConversationTransferDecisionRequest("formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void pausedRecipientCannotAcceptPreviouslyPendingTransfer() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.acceptTransfer("CV-1","retired-transfer-key", new ConversationTransferDecisionRequest("formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void returnTransferThatLosesPendingClaimReturns409WithoutMessageOrAudit() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.returnTransfer("CV-1","retired-transfer-key", new ConversationTransferDecisionRequest("formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void waitTransferKeepsConversationTransferredWithoutDuplicatingTheTransferLedgerInA2() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.waitTransfer("CV-1","retired-transfer-key", new ConversationTransferDecisionRequest("formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void waitTransferThatLosesToDecisionReturns409WithoutMessageOrAudit() {
        conversationRepository.conversation = conversation("CV-1","OPEN");
        var result=service.waitTransfer("CV-1","retired-transfer-key", new ConversationTransferDecisionRequest("formal transfer required","agent-1"));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_FORMAL_TRANSFER_REQUIRED");
        assertThat(conversationRepository.conversation.status()).isEqualTo("OPEN");
        assertThat(conversationRepository.messageWrites).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void replyUpdatesConversationHeaderAndAudits() {
        conversationRepository.conversation = conversation("CV-1", "RESOLVED");

        var result = service.reply(
                "CV-1",
                "idem-i9-reply",
                new ConversationReplyRequest(
                        "We are checking the payment desk.",
                        "RESOLVED",
                        0L,
                        "agent reply",
                        "Marina K."));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("OPEN");
        assertThat(result.getData().unreadCount()).isZero();
        assertThat(result.getData().lastMessage()).isEqualTo("We are checking the payment desk.");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I9_CONVERSATION_REPLIED");
    }

    @Test void changedStaffCannotAppendToPreviousStaffsConversation() {
        conversationRepository.conversation = conversation("CV-1", "OPEN");
        conversationRepository.peerChanged = true;
        assertThatThrownBy(() -> service.reply("CV-1", "new-agent-reply",
                new ConversationReplyRequest("New agent reply", "OPEN", 0L, "agent reply", "Marina K.")))
                .hasMessage("CONVERSATION_PEER_CHANGED");
        assertThat(conversationRepository.messageWrites).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test void staleSnapshotCannotClaimAnUnassignedPeer() {
        conversationRepository.conversation = conversation("CV-UNBOUND", "OPEN");
        conversationRepository.peerClaimSucceeds = true;
        var result = service.reply("CV-UNBOUND", "stale-first-reply",
                new ConversationReplyRequest("Please wait", "OPEN", 99L, "agent reply", "Marina K."));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(conversationRepository.peerClaimAttempts).isZero();
        assertThat(conversationRepository.messageWrites).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test void failedReplyAfterFirstClaimThrowsToRollBackPeerMutation() {
        conversationRepository.conversation = conversation("CV-UNBOUND", "OPEN");
        conversationRepository.peerClaimSucceeds = true;
        conversationRepository.stateClaimSucceeds = false;
        assertThatThrownBy(() -> service.reply("CV-UNBOUND", "racing-first-reply",
                new ConversationReplyRequest("Please wait", "OPEN", 0L, "agent reply", "Marina K.")))
                .hasMessage("INVALID_STATE_TRANSITION");
        assertThat(conversationRepository.peerClaimAttempts).isEqualTo(1);
        assertThat(conversationRepository.messageWrites).isZero();
        verifyNoInteractions(auditLogService);
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

    @Test void archivedReplyResumesAndStaleRetryCannotAppendAgain() {
        conversationRepository.conversation=conversation("CV-ARCHIVED","OPEN");
        conversationRepository.archive(conversationRepository.conversation,true,"agent-1",LocalDateTime.now(clock));
        var request=new ConversationReplyRequest("hello","OPEN",1L,"Archived reply regression","spoofed",List.of(),null,
            "TEXT",null,"SERVICE","bulk-client-01",3L);
        when(ownership.conversationCustomer("CV-ARCHIVED")).thenReturn(1001L);
        var p=new SupportHumanMessageService.Prepared(1001L,"ADMIN",7L,"bulk-client-01","TEXT","SERVICE",null,null,"hash",null,null,null,null,null);
        when(humanMessages.prepareForActor(eq(7L),eq(1001L),any(),any(),eq(request),any(),any(),any(),org.mockito.ArgumentMatchers.isNull(),eq(3L))).thenReturn(p);
        assertThat(service.replyWithMessageId("CV-ARCHIVED","archive-http-key",request).result().getCode()).isZero();
        assertThat(service.replyWithMessageIdForActor(7L,"CV-ARCHIVED","archive-bulk-key",request).result().getCode()).isEqualTo(409);
        assertThat(conversationRepository.messageWrites).isEqualTo(1);
        verify(auditLogService).recordRequired(any());
    }

    @Test void persistedActorReplyIgnoresMissingOrStaleSecurityContext() {
        when(ownership.conversationCustomer("CV-BULK")).thenReturn(1001L);
        var request=new ConversationReplyRequest("hello","OPEN",0L,"Persisted actor regression","spoofed",List.of(),null,
            "TEXT",null,"SERVICE","bulk-client-01",3L);
        var p=new SupportHumanMessageService.Prepared(1001L,"ADMIN",7L,"bulk-client-01","TEXT","SERVICE",null,null,"hash",null,null,null,null,null);
        when(humanMessages.prepareForActor(eq(7L),eq(1001L),any(),any(),eq(request),any(),any(),any(),org.mockito.ArgumentMatchers.isNull(),eq(3L))).thenReturn(p);
        for(boolean stale:List.of(false,true)) {
            org.springframework.security.core.context.SecurityContextHolder.clearContext();
            if(stale) {
                var auth=new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("999",null,List.of());
                auth.setDetails(Map.of("subjectType","ADMIN","username","wrong-admin"));
                org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(auth);
            }
            conversationRepository.conversation=conversation("CV-BULK","OPEN");
            var result=service.replyWithMessageIdForActor(7L,"CV-BULK","bulk-reply-"+stale,request);
            assertThat(result.result().getCode()).isZero();
            assertThat(conversationRepository.lastSenderId).isEqualTo(7L);
            assertThat(conversationRepository.lastSenderName).isEqualTo("admin:7");
            verify(humanMessages).committedForActor(p,result.messageId(),"bulk-reply-"+stale);
        }
        var audit=ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        org.mockito.Mockito.verify(auditLogService,org.mockito.Mockito.times(2)).recordRequiredForTrustedActor(audit.capture());
        assertThat(audit.getAllValues()).allSatisfy(row->{assertThat(row.getActorUsername()).isEqualTo("admin:7");assertThat(row.getActorId()).isEqualTo(7L);});
        verify(ownership,org.mockito.Mockito.never()).actorId();
    }

    @Test void persistedActorInitiationWritesAndAuditsTheTaskActor() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        var request=new ConversationInitiateRequest("support",1001L,"spoofed-owner","Spoofed Owner","hello","Persisted contact regression",
            "spoofed-operator","TEXT",null,"SERVICE","bulk-client-01",3L,List.of());
        var p=new SupportHumanMessageService.Prepared(1001L,"ADMIN",7L,"bulk-client-01","TEXT","SERVICE",null,null,"hash",null,null,null,null,null);
        when(humanMessages.prepareForActor(eq(7L),eq(1001L),any(),eq("CREATE"),eq(request),any(),any(),any(),org.mockito.ArgumentMatchers.isNull(),eq(3L))).thenReturn(p);
        when(supportAgentService.routeAdvisorForUser(1001L)).thenReturn(new AdvisorRoutingDecision("agent","7","Task Advisor",7L,true,"CURRENT_ASSIGNMENT"));
        var result=service.initiateWithMessageIdForActor(7L,"bulk-initiate-key",request);
        assertThat(result.result().getCode()).isZero();
        assertThat(result.result().getData().ownerAgentId()).isEqualTo("7");
        assertThat(conversationRepository.lastSenderId).isEqualTo(7L);
        assertThat(conversationRepository.lastSenderName).isEqualTo("admin:7");
        var audit=ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequiredForTrustedActor(audit.capture());
        assertThat(audit.getValue().getActorUsername()).isEqualTo("admin:7");
        assertThat(audit.getValue().getActorId()).isEqualTo(7L);
        verify(ownership,org.mockito.Mockito.never()).actorId();
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
        when(supportAgentService.routeAdvisorForUser(any())).thenReturn(new ffdd.opsconsole.content.domain.AdvisorRoutingDecision("agent","1","Agent One",1L,true,"CURRENT_ASSIGNMENT"));
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
                        "M5_ASSIGNED_ADVISOR"));

        var result = service.initiate(
                "idem-m3-advisor",
                new ConversationInitiateRequest("advisor", 1001L, "spoofed-agent", "Spoofed Agent",
                        "Please check my advisor session.", "enter advisor session", "user"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().ownerAgentId()).isEqualTo("88");
        assertThat(result.getData().ownerAgentName()).isEqualTo("Dedicated Advisor");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("routingReason", "M5_ASSIGNED_ADVISOR")
                .containsEntry("dedicatedAdvisor", true);
    }

    @Test
    void initiateAdvisorConversationDoesNotCreateRetiredTicketWhenRoutedToStandbyPool() {
        when(supportAgentService.routeAdvisorForUser(1001L))
                .thenReturn(new AdvisorRoutingDecision(
                        "standby",
                        "standby-pool",
                        "备勤池",
                        null,
                        false,
                        "NO_ADVISOR_AVAILABLE"));

        var result = service.initiate(
                "idem-m3-standby",
                new ConversationInitiateRequest("advisor", 1001L, "agent-1", "Agent One",
                        "No advisor is online.", "route to standby", "user"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().ownerAgentId()).isEqualTo("standby-pool");
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
        verify(customerProfiles).conversationProfile(1001L);
    }

    @Test void lightDetailKeepsTheCompleteOrderedMessagesAndSkipsCustomerProfile() {
        conversationRepository.conversation=conversation("CV-1","CLOSED");
        var time=LocalDateTime.of(2026,10,29,0,0);
        conversationRepository.history=java.util.stream.LongStream.rangeClosed(1,2500).mapToObj(id->new ContentConversationMessageView(
            id,1L,"CV-1",id%2==0?1001L:1L,id%2==0?"user":"agent",id%2==0?"Customer":"Agent One","same content","read",time)).toList();
        var result=service.detail("CV-1",false).getData();
        assertThat(result.conversation().conversationNo()).isEqualTo("CV-1");
        assertThat(result.messages()).containsExactlyElementsOf(conversationRepository.history);
        assertThat(result.messages()).hasSize(2500);
        assertThat(result.messages().get(0).senderName()).isEqualTo("Agent One");
        assertThat(result.customerProfile()).isNull();assertThat(result.historyTruncated()).isFalse();assertThat(result.nextCursor()).isNull();
        verify(ownership).readConversation("CV-1");verifyNoInteractions(customerProfiles);
    }

    @Test void lightDetailStillRejectsCurrentAuthorizationBeforeReadingMessagesOrProfile() {
        doThrow(new ffdd.opsconsole.shared.exception.BizException(404,"SUPPORT_CUSTOMER_NOT_FOUND")).when(ownership).readConversation("CV-1");
        assertThatThrownBy(()->service.detail("CV-1",false)).hasMessage("SUPPORT_CUSTOMER_NOT_FOUND");
        verifyNoInteractions(customerProfiles);assertThat(conversationRepository.messageReads).isZero();
    }

    @Test void lightDetailSourceFailurePropagatesWithoutAnEmptyHistoryFallback() {
        conversationRepository.conversation=conversation("CV-1","CLOSED");conversationRepository.messageFailure=true;
        assertThatThrownBy(()->service.detail("CV-1",false)).hasMessage("MESSAGE_READ_FAILED");
        verifyNoInteractions(customerProfiles);
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
        verify(auditLogService).recordRequired(captor.capture());
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
        when(customerProfileRepository.addNote(eq(1001L),eq(1L), eq("agent-1"), eq("测试备注"), eq("agent-1"), any())).thenReturn(created);

        var result = service.addNote("CV-1", "idem-note", new CustomerNoteRequest("测试备注", "add note reason", "agent-1"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().id()).isEqualTo("1");
        assertThat(result.getData().text()).isEqualTo("测试备注");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I9_CUSTOMER_NOTE_ADDED");
    }

    @Test
    void removeNoteSoftDeletesAndAudits() {
        conversationRepository.conversation=conversation("CV-1","OPEN");
        when(customerProfileRepository.findNotes(1001L)).thenReturn(List.of(new ConversationCustomerProfile.CustomerNote("5",0L,"agent-1","owned note")));
        when(customerProfileRepository.removeNote(eq(5L), eq("agent-1"), any())).thenReturn(true);

        var result = service.removeNote("CV-1", 5L, "idem-note", new CustomerNoteRemoveRequest("remove note reason", "agent-1"));

        assertThat(result.getCode()).isZero();
        verify(customerProfileRepository).removeNote(eq(5L), eq("agent-1"), any());

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).recordRequired(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("I9_CUSTOMER_NOTE_REMOVED");
    }

    @Test
    void archiveResolvedConversationKeepsItsLifecycleStatus() {
        conversationRepository.conversation = conversation("CV-1", "RESOLVED");

        var result = service.archive(
                "CV-1",
                "idem-i9-archive",
                new ConversationArchiveRequest(true, "archive resolved session", "Marina K."));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().status()).isEqualTo("RESOLVED");assertThat(result.getData().archived()).isTrue();
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
    void timeoutFallbackMovesOverdueTransfersToStandbyWithoutDuplicatingTheTransferLedgerInA2() {
        configFacade.values.put("I.session.workbench.timeoutFallback","on");
        conversationRepository.overdueConversations=List.of(transferredConversation("CV-OLD"));
        assertThat(service.runTimeoutFallbackConversationNos()).isEmpty();
        assertThat(conversationRepository.fallbackCount).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void timeoutFallbackDoesNotAuditWhenRepositoryClaimFails() {
        configFacade.values.put("I.session.workbench.timeoutFallback","on");
        conversationRepository.overdueConversations=List.of(transferredConversation("CV-OLD"));
        assertThat(service.runTimeoutFallbackConversationNos()).isEmpty();
        assertThat(conversationRepository.fallbackCount).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void timeoutFallbackReturnsOnlyConversationNosWhoseCompareAndSetSucceeded() {
        configFacade.values.put("I.session.workbench.timeoutFallback","on");
        conversationRepository.overdueConversations=List.of(transferredConversation("CV-OLD"));
        assertThat(service.runTimeoutFallbackConversationNos()).isEmpty();
        assertThat(conversationRepository.fallbackCount).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void timeoutFallbackRereadsUnderLockAndSkipsAConversationAlreadyAccepted() {
        configFacade.values.put("I.session.workbench.timeoutFallback","on");
        conversationRepository.overdueConversations=List.of(transferredConversation("CV-OLD"));
        assertThat(service.runTimeoutFallbackConversationNos()).isEmpty();
        assertThat(conversationRepository.fallbackCount).isZero();
        assertThat(conversationRepository.lockedReads).isZero();
        verifyNoInteractions(auditLogService);
    }

    @Test
    void overviewExposesI9TransferStateMachine() {
        when(ownership.supervisor(1L)).thenReturn(true);
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

    @Test void explicitGroupReadModeAndAgentReachTheSameRepositoryScope() {
        var scope=new ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope(1L,
                ffdd.opsconsole.content.domain.SupportGroupFacts.ReadMode.MANAGED,9L,7L);
        when(ownership.queryScope(scope.mode(),9L,7L)).thenReturn(scope);
        service.conversations(new ConversationQueryRequest(null,null,"7",null,"needle",null,1L,20L,null,scope.mode(),9L));
        assertThat(conversationRepository.lastScope).isSameAs(scope);
        verify(ownership).queryScope(scope.mode(),9L,7L);
    }

    @Test void deniedGroupQueryDoesNotReachACollectionRepository() {
        doThrow(new ffdd.opsconsole.shared.exception.BizException(404,"SUPPORT_GROUP_NOT_FOUND"))
                .when(ownership).defaultQueryScope(9L,null);
        assertThatThrownBy(()->service.conversations(new ConversationQueryRequest(null,null,null,null,null,null,1L,20L,null,null,9L)))
                .isInstanceOf(ffdd.opsconsole.shared.exception.BizException.class);
        assertThat(conversationRepository.lastScope).isNull();
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
        // These legacy service fixtures isolate ownership; real stable-peer enforcement is tested against MyBatis.
        private boolean peerChanged;
        private boolean peerClaimSucceeds;
        private int peerClaimAttempts;
        @Override public boolean claimUnassignedPeerForWrite(String conversationNo, String staffId) {
            peerClaimAttempts++;
            return peerClaimSucceeds;
        }
        @Override public void requirePeerForWrite(String conversationNo, String staffId) {
            if (peerChanged) throw new ffdd.opsconsole.shared.exception.BizException(409, "CONVERSATION_PEER_CHANGED");
        }
        private ContentConversationView conversation;
        private List<ContentConversationView> overdueConversations = List.of();
        private ConversationQueryRequest lastQuery;
        private int seedCalls;
        private int fallbackCount;
        private LocalDateTime lastCutoff;
        private int lastLimit;
        private boolean fallbackClaimSucceeds = true;
        private String receiptStatus = "sent";
        private boolean stateClaimSucceeds = true;
        private int failStateClaimOnAttempt = Integer.MAX_VALUE;
        private int stateWriteAttempts;
        private int messageWrites;
        private int messageReads;
        private boolean messageFailure;
        private List<ContentConversationMessageView> history;
        private long nextMessageId = 100L;
        private Long lastSenderId;
        private String lastSenderName;
        private int lockedReads;
        private final Map<String, ContentConversationView> conversations = new LinkedHashMap<>();
        private final List<String> lockOrder = new ArrayList<>();
        private ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope lastScope;

        @Override public Map<String,Object> counters(ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
            lastScope=java.util.Objects.requireNonNull(scope);return counters();
        }
        @Override public PageResult<ContentConversationView> pageConversations(ConversationQueryRequest request,ffdd.opsconsole.content.domain.SupportGroupFacts.ReadScope scope) {
            lastScope=java.util.Objects.requireNonNull(scope);return pageConversations(request);
        }

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
            messageReads++;
            if(messageFailure)throw new IllegalStateException("MESSAGE_READ_FAILED");
            if(history!=null)return history;
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
        public boolean markAgentMessagesReadThrough(
                String conversationNo, Long lastSeenMessageId, String operator, LocalDateTime now,
                String expectedStatus, Long expectedVersion) {
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
        public Long replyAndReturnMessageId(ContentConversationView conversation, String body, String operator, LocalDateTime now) {
            return reply(conversation, body, operator, now) ? ++nextMessageId : null;
        }

        @Override
        public Long replyAndReturnMessageId(ContentConversationView conversation,String body,Long senderId,String senderName,LocalDateTime now) {
            lastSenderId=senderId;lastSenderName=senderName;
            return reply(conversation,body,senderName,now) ? ++nextMessageId : null;
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
            if(!claimState())return false;
            store(new ContentConversationView(conversation.id(),conversation.conversationNo(),conversation.userId(),conversation.conversationType(),conversation.status(),
                conversation.ownerAgentId(),conversation.ownerAgentName(),conversation.unreadCount(),conversation.lastMessage(),conversation.lastMessageAt(),
                conversation.transferFromAgentId(),conversation.transferFromAgentName(),conversation.transferToType(),conversation.transferToId(),conversation.transferToName(),
                conversation.transferReason(),conversation.transferredAt(),now,conversation.version()+1,conversation.lastPublicMessageId(),conversation.lastMessageKind(),archived));return true;
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

        @Override
        public PersistedConversation createConversationWithMessage(
                String conversationNo, Long userId, String conversationType, String ownerAgentId,
                String ownerAgentName, String openingText, LocalDateTime now) {
            return new PersistedConversation(
                    createConversation(conversationNo, userId, conversationType, ownerAgentId, ownerAgentName, openingText, now),
                    ++nextMessageId);
        }

        @Override
        public PersistedConversation createConversationWithMessage(String no,Long user,String type,String owner,String ownerName,
                String text,Long senderId,String senderName,LocalDateTime now) {
            lastSenderId=senderId;lastSenderName=senderName;
            return new PersistedConversation(createConversation(no,user,type,owner,ownerName,text,now),++nextMessageId);
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
}

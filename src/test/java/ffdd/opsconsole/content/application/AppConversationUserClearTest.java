package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.content.application.AppConversationManagementService.ClearRequest;
import ffdd.opsconsole.content.domain.*;
import ffdd.opsconsole.content.mapper.AppConversationClearMapper;
import ffdd.opsconsole.content.mapper.AppConversationClearMapper.PeerRow;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyRecordEntity;
import ffdd.opsconsole.shared.idempotency.mapper.AdminIdempotencyRecordMapper;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.*;
import org.springframework.context.ApplicationEventPublisher;

class AppConversationUserClearTest {
    final AppConversationClearMapper mapper = mock(AppConversationClearMapper.class);
    final SupportOwnershipService ownership = mock(SupportOwnershipService.class);
    final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    final AuditLogService audit = mock(AuditLogService.class);
    final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    final AppConversationManagementService service = new AppConversationManagementService(mapper, ownership,
            mock(ProductionSupportPathGuard.class), idempotency, json, audit, events);
    final ConversationRepository conversations = mock(ConversationRepository.class);
    final AppConversationVisibilityService visibility = new AppConversationVisibilityService(mapper, conversations);
    final LocalDateTime now = LocalDateTime.of(2026, 10, 27, 0, 0);

    @BeforeEach void setup() {
        when(idempotency.executeRetained(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(call -> ((Supplier<?>) call.getArgument(4)).get());
        when(mapper.lockPeer(42L, "agent:7")).thenReturn(List.of(peer("CV-A", 10), peer("CV-B", 20)));
        when(mapper.clearedConversations(42L, "agent:7")).thenReturn(List.of("CV-A", "CV-B"));
    }

    PeerRow peer(String no, long lastId) { return new PeerRow(no, "agent:7", "7", "same name", 3L, 0, lastId, false, false); }
    ClearRequest request() { return new ClearRequest("USER_VIEW", "agent:7", List.of("CV-A", "CV-B"), Map.of("CV-A", 3L, "CV-B", 3L)); }
    ContentConversationView header(String no) {
        // Staff header reflects current assignment 9; APP identity must remain the stored peer 7.
        return new ContentConversationView(1L, no, 42L, "advisor", "OPEN", "9", "same name", 9,
                "old private text", now, null, null, null, null, null, null, null, now, 3L, 20L, "TEXT");
    }
    ContentConversationMessageView message(long id) {
        return new ContentConversationMessageView(id, 1L, "CV-A", 7L, "agent", "same name", "new visible text", "sent", now);
    }

    @Test void clearsAccountPeerAndEveryLegacyCidWithoutEditingStaffMessages() {
        var result = service.clear(42L, "CV-A", "clear-request-1", request());
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).isEqualTo(new ConversationUserClear("agent:7", 20, List.of("CV-A", "CV-B")));
        var ordered = inOrder(ownership, mapper);
        ordered.verify(ownership).lockCustomer(42L);
        ordered.verify(mapper).lockPeer(42L, "agent:7");
        verify(mapper).advancePeer(42L, "agent:7", 20);
        verify(mapper).advanceMember(42L, "agent:7", "CV-A", 20);
        verify(mapper).advanceMember(42L, "agent:7", "CV-B", 20);
        verify(events).publishEvent(new AppConversationManagementService.UserViewCleared(42L, List.of("CV-A", "CV-B")));
        verifyNoInteractions(conversations);
    }

    @Test void newSessionReadsPersistedPeerReceipts() {
        when(mapper.markers(42L)).thenReturn(List.of(new AppConversationClearMapper.Marker("agent:7", 20)));
        var nextSession = new AppConversationManagementService(mapper, ownership,
                mock(ProductionSupportPathGuard.class), idempotency, json, audit, events);
        assertThat(nextSession.management(42L).getData().clearedPeers())
                .containsExactly(new ConversationUserClear("agent:7", 20, List.of("CV-A", "CV-B")));
        assertThat(nextSession.management(99L).getData().clearedPeers()).isEmpty();
    }

    @Test void differentAccountAndDifferentPeerCannotClearMembers() {
        assertThat(service.clear(99L, "CV-A", "clear-request-1", request()).getCode()).isEqualTo(404);
        var otherPeer = new ClearRequest("USER_VIEW", "agent:8", request().conversationIds(), request().expectedVersions());
        assertThat(service.clear(42L, "CV-A", "clear-request-2", otherPeer).getCode()).isEqualTo(404);
        verify(mapper, never()).advancePeer(anyLong(), anyString(), anyLong());
    }

    @Test void staleVersionOrUnseenVisibleSiblingRequiresRefreshedConfirmation() {
        var stale = new ClearRequest("USER_VIEW", "agent:7", request().conversationIds(), Map.of("CV-A", 2L, "CV-B", 3L));
        assertThat(service.clear(42L, "CV-A", "clear-request-1", stale).getCode()).isEqualTo(409);
        var missing = new ClearRequest("USER_VIEW", "agent:7", List.of("CV-A"), Map.of("CV-A", 3L));
        assertThat(service.clear(42L, "CV-A", "clear-request-2", missing).getMessage()).isEqualTo("CONVERSATION_USER_CLEAR_MEMBERS_CHANGED");
        verify(mapper, never()).advancePeer(anyLong(), anyString(), anyLong());
    }

    @Test void emptyConversationHasARealZeroBoundaryAndHiddenSiblingIsStillCovered() {
        when(mapper.lockPeer(42L, "agent:7")).thenReturn(List.of(peer("CV-A", 0),
                new PeerRow("CV-B", "agent:7", "7", "same name", 3L, 0, 0, true, false)));
        var single = new ClearRequest("USER_VIEW", "agent:7", List.of("CV-A"), Map.of("CV-A", 3L));
        assertThat(service.clear(42L, "CV-A", "clear-empty-1", single).getData().throughMessageId()).isZero();
        verify(mapper).advanceMember(42L, "agent:7", "CV-B", 0);
    }

    @Test void originalClearReplayDoesNotAdvanceToNewMessage30() {
        var original = service.clear(42L, "CV-A", "clear-request-1", request());
        var replay = json.convertValue(original, ApiResult.class);
        doReturn(replay).when(idempotency).executeRetained(anyString(), anyString(), anyString(), any(), any());
        clearInvocations(mapper, ownership, events);
        assertThat(service.clear(42L, "CV-A", "clear-request-1", request()).getData().throughMessageId()).isEqualTo(20);
        verifyNoInteractions(mapper, ownership, events);
    }

    @Test void requestFingerprintIsOrderIndependentButBindsPeerVersionsAndTarget() {
        service.clear(42L, "CV-A", "clear-request-1", request());
        service.clear(42L, "CV-A", "clear-request-1", new ClearRequest("USER_VIEW", "agent:7",
                List.of("CV-B", "CV-A"), new LinkedHashMap<>(Map.of("CV-B", 3L, "CV-A", 3L))));
        service.clear(42L, "CV-A", "clear-request-1", new ClearRequest("USER_VIEW", "agent:7",
                List.of("CV-B", "CV-A"), Map.of("CV-B", 4L, "CV-A", 3L)));
        var hashes = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(idempotency, times(3)).executeRetained(eq("APP_CONVERSATION_USER_CLEAR:42"), eq("clear-request-1"), hashes.capture(), any(), any());
        assertThat(hashes.getAllValues().get(0)).isEqualTo(hashes.getAllValues().get(1)).isNotEqualTo(hashes.getAllValues().get(2));
    }

    @Test void malformedAndPhysicalDeleteRequestsNeverReachIdempotency() {
        assertThat(service.clear(42L, "CV-A", "clear-request-1", new ClearRequest("ALL", "agent:7", List.of("CV-A"), Map.of("CV-A", 3L))).getCode()).isEqualTo(422);
        assertThat(service.clear(42L, "CV-A", "clear-request-1", new ClearRequest("USER_VIEW", "same name", List.of("CV-A"), Map.of("CV-A", 3L))).getCode()).isEqualTo(422);
        assertThat(service.clear(42L, "CV-A", "clear-request-1", new ClearRequest("USER_VIEW", "agent:7", List.of("CV-A", "CV-A"), Map.of("CV-A", 3L))).getCode()).isEqualTo(422);
        assertThat(service.clear(null, "CV-A", "clear-request-1", request()).getCode()).isEqualTo(403);
        verifyNoInteractions(idempotency);
    }

    @Test void clearedHeaderNeverReplaysPreviewUnreadOrLatestIdAndRetainsStableStaff() {
        when(mapper.peer(42L, "CV-A")).thenReturn(new PeerRow("CV-A", "agent:7", "7", "same name", 3L, 20, 20, true, false));
        var hidden = visibility.project(header("CV-A"), false);
        assertThat(hidden.ownerAgentId()).isEqualTo("7");
        assertThat(hidden.userClearedThroughMessageId()).isEqualTo(20L);
        assertThat(hidden.lastMessage()).isNull();
        assertThat(hidden.lastMessageAt()).isNull();
        assertThat(hidden.lastPublicMessageId()).isZero();
        assertThat(hidden.unreadCount()).isZero();
        when(conversations.recentUserVisibleMessages("CV-A", 1)).thenReturn(List.of(message(30)));
        when(conversations.unreadUserVisibleAgentMessageCount("CV-A")).thenReturn(1);
        var fresh = visibility.project(header("CV-A"), false);
        assertThat(fresh.lastPublicMessageId()).isEqualTo(30L);
        assertThat(fresh.lastMessage()).isEqualTo("new visible text");
        assertThat(fresh.unreadCount()).isOne();
    }

    @Test void oldAcknowledgement20ReadbackHydratesCurrent30AndClearReadbackKeepsOriginalBoundary() throws Exception {
        var records = mock(AdminIdempotencyRecordMapper.class);
        var app = new AppSupportService(mock(SupportTicketRepository.class), conversations, mock(SupportKnowledgeRepository.class),
                idempotency, audit, events, Clock.systemUTC(), mock(ProductionSupportPathGuard.class), records, json,
                mock(SupportAgentRepository.class), mock(ffdd.opsconsole.platform.facade.PlatformConfigFacade.class), ownership,
                mock(SupportHumanMessageService.class), visibility, org.mockito.Mockito.mock(ffdd.opsconsole.content.application.SupportUnboundReceipt.class));
        when(mapper.peer(42L, "CV-A")).thenReturn(new PeerRow("CV-A", "agent:7", "7", "same name", 3L, 20, 30, true, false));
        when(conversations.findByConversationNo("CV-A")).thenReturn(Optional.of(header("CV-A")));
        when(conversations.findByConversationNoForUpdate("CV-A")).thenReturn(Optional.of(header("CV-A")));
        when(conversations.recentUserVisibleMessages(eq("CV-A"), anyInt())).thenReturn(List.of(message(30)));
        when(conversations.currentRecentUserVisibleMessages(eq("CV-A"), anyInt())).thenReturn(List.of(message(30)));
        var old = ApiResult.ok(new ContentConversationDetail(header("CV-A"), List.of(message(20)), null));
        var record = new AdminIdempotencyRecordEntity();
        record.setScope("APP_CONVERSATION_CREATE:42"); record.setStatus("SUCCEEDED"); record.setResponseJson(json.writeValueAsString(old));
        when(records.selectSupportCommand(any(), anyString())).thenReturn(List.of(record));
        var current = json.valueToTree(app.commandResult(42L, "old-create-key").getData().get("result"));
        assertThat(current.path("messages").get(0).path("id").asLong()).isEqualTo(30);
        doReturn(json.convertValue(old, ApiResult.class)).when(idempotency).executeRetained(anyString(), anyString(), anyString(), any(), any());
        var retry = app.startConversation(42L, "old-create-key", new AppSupportService.StartConversationRequest("advisor", "original body"));
        assertThat(retry.getData().messages()).extracting(ContentConversationMessageView::id).containsExactly(30L);
        var replyRetry = app.replyConversation(42L, "CV-A", "old-reply-key", new AppSupportService.ReplyRequest("original body", "OPEN", 3L));
        assertThat(replyRetry.getData().messages()).extracting(ContentConversationMessageView::id).containsExactly(30L);
        verify(conversations, never()).requirePeerForWrite(anyString(), any());
        verify(conversations, never()).createUserConversation(anyString(), anyLong(), anyString(), anyString(), any(), any(), any());
        record.setScope("APP_CONVERSATION_USER_CLEAR:42");
        record.setResponseJson(json.writeValueAsString(ApiResult.ok(new ConversationUserClear("agent:7", 20, List.of("CV-A", "CV-B")))));
        var clear = app.commandResult(42L, "old-clear-key");
        assertThat(clear.getData()).containsEntry("resultType", "CONVERSATION_USER_CLEAR");
        assertThat(json.valueToTree(clear.getData().get("result")).path("throughMessageId").asLong()).isEqualTo(20);
    }

    @Test void httpContractRequiresUserIdentityAndReturnsOnlyUserViewReceiptFields() throws Exception {
        var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                new ffdd.opsconsole.content.web.AppConversationManagementController(service))
                .setControllerAdvice(new ffdd.opsconsole.shared.api.ApiResultHttpStatusAdvice()).build();
        var user = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("42", null, List.of());
        user.setDetails(Map.of("subjectType", "USER"));
        var admin = new org.springframework.security.authentication.UsernamePasswordAuthenticationToken("42", null, List.of());
        admin.setDetails(Map.of("subjectType", "ADMIN"));
        String url = "/api/app/support/conversations/CV-A";
        var get = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/app/support/conversation-management");
        mvc.perform(get).andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/app/support/conversation-management").principal(user))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.clearUserHistory").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.header().string("Cache-Control", "no-store"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url).principal(admin)
                .header("Idempotency-Key", "http-clear-key").contentType("application/json").content(json.writeValueAsString(request())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(url).principal(user)
                .header("Idempotency-Key", "http-clear-key").contentType("application/json").content(json.writeValueAsString(request())))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.scope").value("USER_VIEW"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.userScopeCleared").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.throughMessageId").value(20))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.historyCleared").doesNotExist())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.data.deletedConversationNos").doesNotExist());
    }
}

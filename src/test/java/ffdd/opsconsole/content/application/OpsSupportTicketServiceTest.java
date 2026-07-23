package ffdd.opsconsole.content.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.domain.SupportTicketMessageView;
import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.domain.SupportAgentProfileView;
import ffdd.opsconsole.content.dto.SupportTicketAssigneeRequest;
import ffdd.opsconsole.content.dto.SupportTicketArchiveRequest;
import ffdd.opsconsole.content.dto.SupportAgentLoadStateRequest;
import ffdd.opsconsole.content.dto.SupportLoadConfigUpdateRequest;
import ffdd.opsconsole.content.dto.SupportLoadRebalanceRequest;
import ffdd.opsconsole.content.dto.SupportTicketCreateRequest;
import ffdd.opsconsole.content.dto.SupportTicketEscalateRequest;
import ffdd.opsconsole.content.dto.SupportTicketPriorityRequest;
import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import ffdd.opsconsole.content.dto.SupportTicketReplyRequest;
import ffdd.opsconsole.content.dto.SupportTicketStatusRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsSupportTicketServiceTest {
    private final FakeSupportTicketRepository ticketRepository = new FakeSupportTicketRepository();
    private final ConversationRepository conversationRepository = mock(ConversationRepository.class);
    private final OpsSupportAgentService supportAgentService = mock(OpsSupportAgentService.class);
    private final FakePlatformConfigFacade configFacade = new FakePlatformConfigFacade();
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotencyService = mock(AdminIdempotencyService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T00:00:00Z"), ZoneId.of("UTC"));
    private final OpsSupportTicketService service = service();

    private OpsSupportTicketService service() {
        doAnswer(invocation -> ((Supplier<?>) invocation.getArgument(4)).get())
                .when(idempotencyService)
                .execute(anyString(), anyString(), anyString(), any(), any());
        when(supportAgentService.assignableSupportAgent(1L)).thenReturn(Optional.of(agent(1L, "Marina K.")));
        when(supportAgentService.assignableSupportAgent(7L)).thenReturn(Optional.of(agent(7L, "Tomas R.")));
        when(supportAgentService.assignableSupportAgent("7")).thenReturn(Optional.of(agent(7L, "Tomas R.")));
        return new OpsSupportTicketService(
                ticketRepository,
                conversationRepository,
                supportAgentService,
                configFacade,
                auditLogService,
                idempotencyService,
                clock,
                ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy.enabledForDirectConstruction());
    }

    @Test
    void createRequiresIdempotencyKey() {
        var result = service.create(null, createRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void createPersistsTicketAndAudits() {
        var result = service.create("idem-m2-create", createRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().ticket().ticketNo()).startsWith("TK-");
        assertThat(result.getData().ticket().status()).isEqualTo("OPEN");
        assertThat(result.getData().messages()).hasSize(1);

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("M2_SUPPORT_TICKET_CREATED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-m2-create");
        verify(idempotencyService).execute(eq("M2_SUPPORT_TICKET_CREATE"), eq("idem-m2-create"), anyString(), eq(ffdd.opsconsole.shared.api.ApiResult.class), any());
    }

    @Test
    void createRejectsUnknownAssignedSupportAgent() {
        var request = new SupportTicketCreateRequest(
                1001L, "withdrawal", "high", "Withdrawal pending", "Still pending.",
                999999L, "Ghost Operator", "Marina K.", "invalid owner validation");

        var result = service.create("idem-m2-create-ghost", request);

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_AGENT_NOT_ASSIGNABLE");
    }

    @Test
    void archiveIsIndependentFromResolutionAndCanBeReversed() {
        ticketRepository.ticket = ticket("TK-1", "RESOLVED", "NORMAL");

        var archived = service.archive("TK-1", "idem-m2-archive", new SupportTicketArchiveRequest(true, "Marina K.", "routine archive"));

        assertThat(archived.getCode()).isZero();
        assertThat(archived.getData().ticket().archived()).isTrue();
        assertThat(archived.getData().ticket().status()).isEqualTo("RESOLVED");

        var restored = service.archive("TK-1", "idem-m2-unarchive", new SupportTicketArchiveRequest(false, "Marina K.", "routine restore"));

        assertThat(restored.getCode()).isZero();
        assertThat(restored.getData().ticket().archived()).isFalse();
        assertThat(restored.getData().ticket().status()).isEqualTo("RESOLVED");
    }

    @Test
    void archiveRejectsAnActiveTicket() {
        ticketRepository.ticket = ticket("TK-1", "OPEN", "NORMAL");

        var result = service.archive("TK-1", "idem-m2-archive-active", new SupportTicketArchiveRequest(true, "Marina K.", "routine archive"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void escalateCreatesARealConversationForTheTicketUserAndLeavesTicketTrace() {
        ticketRepository.ticket = ticket("TK-1", "OPEN", "HIGH");
        ContentConversationView created = new ContentConversationView(
                9L, "CV-TK-1", 1001L, "support", "OPEN", "7", "Tomas R.",
                0, "工单 TK-1 已升级为即时会话", clock.instant().atZone(clock.getZone()).toLocalDateTime(),
                null, null, null, null, null, null, null, clock.instant().atZone(clock.getZone()).toLocalDateTime());
        when(conversationRepository.findByConversationNo("CV-TK-1")).thenReturn(Optional.empty());
        when(conversationRepository.createConversation(
                eq("CV-TK-1"), eq(1001L), eq("support"), eq("7"), eq("Tomas R."), anyString(), any()))
                .thenReturn(created);

        var result = service.escalate(
                "TK-1",
                "idem-m2-escalate",
                new SupportTicketEscalateRequest("7", "Ghost Name", "Marina K.", "customer needs realtime help"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().conversation().conversationNo()).isEqualTo("CV-TK-1");
        assertThat(result.getData().conversation().userId()).isEqualTo(1001L);
        assertThat(result.getData().conversation().ownerAgentName()).isEqualTo("Tomas R.");
        assertThat(result.getData().ticket().messages()).extracting(SupportTicketMessageView::content)
                .anyMatch(text -> text.contains("CV-TK-1"));
    }

    @Test
    void escalateRejectsUnknownAssignedSupportAgent() {
        ticketRepository.ticket = ticket("TK-1", "OPEN", "HIGH");

        var result = service.escalate(
                "TK-1", "idem-m2-escalate-ghost",
                new SupportTicketEscalateRequest("999999", "Ghost Operator", "Marina K.", "invalid owner validation"));

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_AGENT_NOT_ASSIGNABLE");
    }

    @Test
    void replyMovesResolvedTicketToPendingUserAndWritesMessage() {
        ticketRepository.ticket = ticket("TK-1", "RESOLVED", "NORMAL");

        var result = service.reply("TK-1", "idem-m2-reply", new SupportTicketReplyRequest("We are checking now.", "Marina K.", null));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().ticket().status()).isEqualTo("PENDING_USER");
        assertThat(result.getData().ticket().lastMessage()).isEqualTo("We are checking now.");
        assertThat(result.getData().messages()).extracting(SupportTicketMessageView::content).contains("We are checking now.");
    }

    @Test
    void replyRejectsClosedTicketWith409() {
        ticketRepository.ticket = ticket("TK-1", "CLOSED", "NORMAL");

        var result = service.reply("TK-1", "idem-m2-reply", new SupportTicketReplyRequest("hello", "Marina K.", null));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateStatusMovesOpenTicketToResolved() {
        ticketRepository.ticket = ticket("TK-1", "OPEN", "HIGH");

        var result = service.updateStatus("TK-1", "idem-m2-status", new SupportTicketStatusRequest("RESOLVED", "Marina K.", "issue finished"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().ticket().status()).isEqualTo("RESOLVED");
        assertThat(result.getData().messages()).extracting(SupportTicketMessageView::content)
                .anyMatch(text -> text.contains("待处理") && text.contains("已解决"));
    }

    @Test
    void updateStatusRejectsUnsupportedJumpFromResolvedToInProgress() {
        ticketRepository.ticket = ticket("TK-1", "RESOLVED", "HIGH");

        var result = service.updateStatus("TK-1", "idem-m2-status-jump",
                new SupportTicketStatusRequest("IN_PROGRESS", "Marina K.", "invalid stale transition"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updateStatusRejectsSameState() {
        ticketRepository.ticket = ticket("TK-1", "OPEN", "HIGH");

        var result = service.updateStatus("TK-1", "idem-m2-status", new SupportTicketStatusRequest("OPEN", "Marina K.", "same state check"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus());
    }

    @Test
    void updatePriorityRequiresReason() {
        ticketRepository.ticket = ticket("TK-1", "OPEN", "NORMAL");

        var result = service.updatePriority("TK-1", "idem-m2-priority", new SupportTicketPriorityRequest("URGENT", "Marina K.", "short"));

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
    }

    @Test
    void updatePriorityChangesNonTerminalTicket() {
        ticketRepository.ticket = ticket("TK-1", "OPEN", "NORMAL");

        var result = service.updatePriority("TK-1", "idem-m2-priority", new SupportTicketPriorityRequest("URGENT", "Marina K.", "payment escalation"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().ticket().priority()).isEqualTo("URGENT");
    }

    @Test
    void assignChangesOwnerAndAudits() {
        ticketRepository.ticket = ticket("TK-1", "OPEN", "NORMAL");

        var result = service.assign("TK-1", "idem-m2-assign", new SupportTicketAssigneeRequest(7L, "Tomas R.", "Marina K.", "kyc specialist"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().ticket().assignedAdminId()).isEqualTo(7L);
        assertThat(result.getData().ticket().assignedAdminName()).isEqualTo("Tomas R.");
    }

    @Test
    void assignRejectsUnknownSupportAgent() {
        ticketRepository.ticket = ticket("TK-1", "OPEN", "NORMAL");

        var result = service.assign("TK-1", "idem-m2-assign-ghost",
                new SupportTicketAssigneeRequest(999999L, "Ghost Operator", "Marina K.", "invalid owner validation"));

        assertThat(result.getCode()).isEqualTo(404);
        assertThat(result.getMessage()).isEqualTo("SUPPORT_AGENT_NOT_ASSIGNABLE");
        assertThat(ticketRepository.ticket.assignedAdminName()).isEqualTo("Marina K.");
    }

    @Test
    void overviewExposesM2Sources() {
        var result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(ticketRepository.seedCalls).isZero();
        assertThat(result.getData().get("sources")).asList().contains("nx_support_ticket", "nx_support_ticket_message");
    }

    @Test
    void loadConfigUsesPersistedPlatformConfigAndAgentState() {
        configFacade.values.put("content.support.load.autoBalance", "true");
        configFacade.values.put("content.support.load.defaultCap", "6");
        configFacade.values.put("content.support.load.burstCap", "10");
        configFacade.values.put("content.support.load.warnPct", "75");
        configFacade.values.put("content.support.load.quietHourBalance", "true");
        configFacade.values.put("content.support.load.overflowQueue", "夜间备勤队列");
        configFacade.values.put("content.support.load.lastRebalanceAt", "2026-06-18T00:00:00");
        configFacade.values.put("content.support.load.agent.agent-1.cap", "5");
        configFacade.values.put("content.support.load.agent.agent-1.busy", "true");

        var result = service.loadConfig();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("loadConfig")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("autoBalance", true)
                .containsEntry("defaultCap", 6)
                .containsEntry("burstCap", 10)
                .containsEntry("warnPct", 75)
                .containsEntry("quietHourBalance", true)
                .containsEntry("overflowQueue", "夜间备勤队列");
        assertThat(result.getData().get("agentState").toString()).contains("agent-1", "cap=5", "busy=true");
        assertThat(result.getData()).containsEntry("lastRebalanceAt", "2026-06-18T00:00:00");
        assertThat(result.getData().get("sources")).asList()
                .contains("nx_config_item:content_support_load", "nx_support_ticket", "nx_conversation");
    }

    @Test
    void loadConfigUsesSafeBusinessDefaultsWhenNoOverrideExists() {
        var result = service.loadConfig();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData().get("loadConfig")).asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.MAP)
                .containsEntry("autoBalance", false)
                .containsEntry("defaultCap", 8)
                .containsEntry("burstCap", 12)
                .containsEntry("warnPct", 80)
                .containsEntry("quietHourBalance", false)
                .containsEntry("overflowQueue", "转人工备勤队列");
    }

    @Test
    void updateLoadConfigRequiresIdempotencyKey() {
        var result = service.updateLoadConfig(null, loadConfigRequest());

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
    }

    @Test
    void updateLoadConfigPersistsConfigAndAudits() {
        var result = service.updateLoadConfig("idem-m1-load", loadConfigRequest());

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("content.support.load.defaultCap", "7")
                .containsEntry("content.support.load.burstCap", "11")
                .containsEntry("content.support.load.warnPct", "75")
                .containsEntry("content.support.load.agent.agent-1.cap", "5")
                .containsEntry("content.support.load.agent.agent-1.busy", "true");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("M1_SUPPORT_LOAD_CONFIG_CHANGED");
        assertThat(detailMap(captor.getValue().getDetail())).containsEntry("idempotencyKey", "idem-m1-load");
    }

    @Test
    void rebalanceLoadPersistsTimestampAndAudits() {
        var result = service.rebalanceLoad(
                "idem-m1-rebalance",
                new SupportLoadRebalanceRequest(
                        List.of(Map.of("id", "agent-1", "cap", 4, "busy", false)),
                        "superadmin",
                        "shift load to backup team"));

        assertThat(result.getCode()).isZero();
        assertThat(configFacade.values)
                .containsEntry("content.support.load.agent.agent-1.cap", "4")
                .containsEntry("content.support.load.agent.agent-1.busy", "false")
                .containsKey("content.support.load.lastRebalanceAt");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("M1_SUPPORT_LOAD_REBALANCED");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }

    private static SupportTicketCreateRequest createRequest() {
        return new SupportTicketCreateRequest(
                1001L,
                "withdrawal",
                "high",
                "Withdrawal pending",
                "Withdrawal pending more than 24 hours.",
                1L,
                "Marina K.",
                "Marina K.",
                "customer reported pending withdrawal");
    }

    private static SupportLoadConfigUpdateRequest loadConfigRequest() {
        return new SupportLoadConfigUpdateRequest(
                true,
                7,
                11,
                75,
                true,
                "备勤队列",
                Map.of("agent-1", new SupportAgentLoadStateRequest(5, true)),
                "superadmin",
                "rebalance support capacity");
    }

    private static SupportTicketView ticket(String ticketNo, String status, String priority) {
        LocalDateTime now = LocalDateTime.of(2026, 6, 18, 0, 0);
        return new SupportTicketView(
                1L,
                ticketNo,
                1001L,
                "withdrawal",
                priority,
                status,
                "Withdrawal pending",
                "initial body",
                1L,
                "Marina K.",
                0,
                1,
                1,
                now,
                null,
                now,
                now,
                false,
                null);
    }

    private static SupportAgentProfileView agent(Long adminId, String name) {
        return new SupportAgentProfileView(
                String.valueOf(adminId), adminId, name, name + "@nexion.test", "support", "enabled",
                "GENERAL", "通用客服", List.of("support"), List.of(), 12,
                true, true, false, 0L, "2026-06-18T00:00:00");
    }

    private static final class FakeSupportTicketRepository implements SupportTicketRepository {
        private SupportTicketView ticket = ticket("TK-1", "OPEN", "NORMAL");
        private int seedCalls;
        private final List<SupportTicketMessageView> messages = new ArrayList<>(List.of(
                new SupportTicketMessageView(1L, 1L, "TK-1", 1001L, "user", "用户", "initial body", LocalDateTime.now())));

        @Override
        public void ensureSeedData(LocalDateTime now) {
            seedCalls += 1;
        }

        @Override
        public Map<String, Object> counters() {
            return new LinkedHashMap<>(Map.of("active", 1L, "pendingUser", 0L));
        }

        @Override
        public PageResult<SupportTicketView> pageTickets(SupportTicketQueryRequest request) {
            return new PageResult<>(1, 1, 20, List.of(ticket));
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
            ticket = new SupportTicketView(2L, ticketNo, userId, category, priority, "OPEN", title, body,
                    assignedAdminId, assignedAdminName, 0, 1, 1, now, null, now, now, false, null);
            messages.clear();
            messages.add(new SupportTicketMessageView(2L, 2L, ticketNo, userId, "user", "用户", body, now));
            return ticket;
        }

        @Override
        public void appendReply(SupportTicketView ticket, String body, String operator, LocalDateTime now) {
            this.ticket = replace(ticket, ticket.priority(), "CLOSED".equals(ticket.status()) ? "CLOSED" : "PENDING_USER",
                    body, ticket.assignedAdminId(), ticket.assignedAdminName(), now);
            messages.add(new SupportTicketMessageView((long) messages.size() + 1, ticket.id(), ticket.ticketNo(), null, "agent", operator, body, now));
        }

        @Override
        public void updateStatus(SupportTicketView ticket, String status, String operator, LocalDateTime now) {
            this.ticket = replace(ticket, ticket.priority(), status, ticket.lastMessage(), ticket.assignedAdminId(), ticket.assignedAdminName(), now);
        }

        @Override
        public void updatePriority(SupportTicketView ticket, String priority, LocalDateTime now) {
            this.ticket = replace(ticket, priority, ticket.status(), ticket.lastMessage(), ticket.assignedAdminId(), ticket.assignedAdminName(), now);
        }

        @Override
        public void assign(SupportTicketView ticket, Long assignedAdminId, String assignedAdminName, LocalDateTime now) {
            this.ticket = replace(ticket, ticket.priority(), ticket.status(), ticket.lastMessage(), assignedAdminId, assignedAdminName, now);
        }

        @Override
        public void archive(SupportTicketView ticket, boolean archived, String operator, LocalDateTime now) {
            this.ticket = new SupportTicketView(
                    ticket.id(), ticket.ticketNo(), ticket.userId(), ticket.category(), ticket.priority(), ticket.status(),
                    ticket.title(), ticket.lastMessage(), ticket.assignedAdminId(), ticket.assignedAdminName(),
                    ticket.userUnreadCount(), ticket.opsUnreadCount(), ticket.messageCount(), ticket.lastMessageAt(),
                    ticket.closedAt(), ticket.createdAt(), now, archived, archived ? now : null);
        }

        @Override
        public void appendSystemTrace(SupportTicketView ticket, String body, LocalDateTime now) {
            SupportTicketView current = this.ticket == null ? ticket : this.ticket;
            this.ticket = replace(current, current.priority(), current.status(), body,
                    current.assignedAdminId(), current.assignedAdminName(), now);
            messages.add(new SupportTicketMessageView((long) messages.size() + 1, current.id(), current.ticketNo(),
                    null, "system", "系统", body, now));
        }

        private SupportTicketView replace(SupportTicketView source, String priority, String status, String lastMessage,
                                          Long assignedAdminId, String assignedAdminName, LocalDateTime now) {
            return new SupportTicketView(
                    source.id(),
                    source.ticketNo(),
                    source.userId(),
                    source.category(),
                    priority,
                    status,
                    source.title(),
                    lastMessage,
                    assignedAdminId,
                    assignedAdminName,
                    source.userUnreadCount(),
                    source.opsUnreadCount(),
                    source.messageCount() + 1,
                    now,
                    "CLOSED".equals(status) || "RESOLVED".equals(status) ? now : null,
                    source.createdAt(),
                    now,
                    source.archived(),
                    source.archivedAt());
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

        @Override
        public Map<String, String> activeValuesByGroup(String configGroup) {
            Map<String, String> matched = new LinkedHashMap<>();
            if ("content_support_load".equals(configGroup)) {
                values.forEach((key, value) -> {
                    if (key.startsWith("content.support.load.")) {
                        matched.put(key, value);
                    }
                });
            }
            return matched;
        }
    }
}

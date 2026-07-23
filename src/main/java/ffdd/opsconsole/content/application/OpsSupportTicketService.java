package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.SupportTicketDetail;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.domain.SupportTicketRepository;
import ffdd.opsconsole.content.domain.SupportTicketEscalationResult;
import ffdd.opsconsole.content.domain.SupportTicketView;
import ffdd.opsconsole.content.domain.SupportAgentProfileView;
import ffdd.opsconsole.content.dto.SupportTicketAssigneeRequest;
import ffdd.opsconsole.content.dto.SupportTicketArchiveRequest;
import ffdd.opsconsole.content.dto.SupportTicketCreateRequest;
import ffdd.opsconsole.content.dto.SupportTicketEscalateRequest;
import ffdd.opsconsole.content.dto.SupportTicketPriorityRequest;
import ffdd.opsconsole.content.dto.SupportTicketQueryRequest;
import ffdd.opsconsole.content.dto.SupportTicketReplyRequest;
import ffdd.opsconsole.content.dto.SupportTicketStatusRequest;
import ffdd.opsconsole.content.dto.SupportAgentLoadStateRequest;
import ffdd.opsconsole.content.dto.SupportLoadConfigUpdateRequest;
import ffdd.opsconsole.content.dto.SupportLoadRebalanceRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class OpsSupportTicketService {
    private static final Set<String> CATEGORIES = Set.of("account", "withdrawal", "deposit", "kyc", "hardware", "earnings", "genesis", "technical", "other");
    private static final Set<String> PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final Set<String> STATUSES = Set.of("OPEN", "IN_PROGRESS", "PENDING_USER", "RESOLVED", "CLOSED");
    private static final Set<String> TERMINAL_STATUSES = Set.of("RESOLVED", "CLOSED");
    private static final Map<String, Set<String>> STATUS_TRANSITIONS = Map.of(
            "OPEN", Set.of("IN_PROGRESS", "PENDING_USER", "RESOLVED", "CLOSED"),
            "IN_PROGRESS", Set.of("OPEN", "PENDING_USER", "RESOLVED", "CLOSED"),
            "PENDING_USER", Set.of("OPEN", "IN_PROGRESS", "RESOLVED", "CLOSED"),
            "RESOLVED", Set.of("OPEN", "PENDING_USER", "CLOSED"),
            "CLOSED", Set.of("OPEN"));
    private static final DateTimeFormatter TICKET_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final String LOAD_GROUP = "content_support_load";
    private static final String LOAD_PREFIX = "content.support.load.";
    private static final Pattern LOAD_AGENT_KEY = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    private final SupportTicketRepository ticketRepository;
    private final ConversationRepository conversationRepository;
    private final OpsSupportAgentService supportAgentService;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public ApiResult<Map<String, Object>> overview() {
        ensureSeedData();
        Map<String, Object> response = new LinkedHashMap<>(ticketRepository.counters());
        response.put("domain", "M2");
        response.put("statuses", List.copyOf(STATUSES));
        response.put("priorities", List.copyOf(PRIORITIES));
        response.put("categories", List.copyOf(CATEGORIES));
        response.put("stateMachine", STATUS_TRANSITIONS);
        response.put("sources", List.of("nx_support_ticket", "nx_support_ticket_message", "nx_support_ticket_attachment"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> loadConfig() {
        ensureSeedData();
        return ApiResult.ok(loadConfigView());
    }

    public ApiResult<Map<String, Object>> updateLoadConfig(String idempotencyKey, SupportLoadConfigUpdateRequest request) {
        ensureSeedData();
        ApiResult<Map<String, Object>> guard = requireSupportCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        Map<String, SupportAgentLoadStateRequest> agentState = request.agentState() == null ? Map.of() : request.agentState();
        for (String agentId : agentState.keySet()) {
            if (!isSafeAgentKey(agentId)) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_LOAD_AGENT_ID_INVALID");
            }
        }
        return idempotentCommand(
                "M1_SUPPORT_LOAD_CONFIG_UPDATE",
                idempotencyKey,
                requestHash(String.valueOf(request)),
                () -> updateLoadConfigOnce(idempotencyKey, request, agentState));
    }

    private ApiResult<Map<String, Object>> updateLoadConfigOnce(
            String idempotencyKey,
            SupportLoadConfigUpdateRequest request,
            Map<String, SupportAgentLoadStateRequest> agentState) {
        int defaultCap = boundedInt(request.defaultCap(), 0, 40, 8);
        int burstCap = boundedInt(request.burstCap(), 0, 40, Math.max(defaultCap, 12));
        int warnPct = boundedInt(request.warnPct(), 50, 100, 80);
        String overflowQueue = StringUtils.hasText(request.overflowQueue()) ? request.overflowQueue().trim() : "转人工备勤队列";
        if (overflowQueue.length() > 80) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_LOAD_OVERFLOW_QUEUE_TOO_LONG");
        }
        String remark = request.reason().trim();
        upsertLoadValue("autoBalance", Boolean.TRUE.equals(request.autoBalance()) ? "true" : "false", "BOOLEAN", remark);
        upsertLoadValue("defaultCap", String.valueOf(defaultCap), "NUMBER", remark);
        upsertLoadValue("burstCap", String.valueOf(burstCap), "NUMBER", remark);
        upsertLoadValue("warnPct", String.valueOf(warnPct), "NUMBER", remark);
        upsertLoadValue("quietHourBalance", Boolean.TRUE.equals(request.quietHourBalance()) ? "true" : "false", "BOOLEAN", remark);
        upsertLoadValue("overflowQueue", overflowQueue, "STRING", remark);
        agentState.forEach((agentId, state) -> {
            int cap = boundedInt(state == null ? null : state.cap(), 0, 40, defaultCap);
            boolean busy = state != null && Boolean.TRUE.equals(state.busy());
            upsertLoadValue("agent." + agentId + ".cap", String.valueOf(cap), "NUMBER", remark);
            upsertLoadValue("agent." + agentId + ".busy", String.valueOf(busy), "BOOLEAN", remark);
        });
        audit("M1_SUPPORT_LOAD_CONFIG_CHANGED", "SUPPORT_LOAD_CONFIG", "m1.support.load", request.operator(), Map.of(
                "reason", remark,
                "idempotencyKey", idempotencyKey.trim(),
                "agentCount", agentState.size(),
                "defaultCap", defaultCap,
                "burstCap", burstCap,
                "warnPct", warnPct));
        return loadConfig();
    }

    public ApiResult<Map<String, Object>> rebalanceLoad(String idempotencyKey, SupportLoadRebalanceRequest request) {
        ensureSeedData();
        ApiResult<Map<String, Object>> guard = requireSupportCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        List<Map<String, Object>> agents = request.agents() == null ? List.of() : request.agents();
        for (Map<String, Object> agent : agents) {
            if (!isSafeAgentKey(loadAgentId(agent))) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_LOAD_AGENT_ID_INVALID");
            }
        }
        return idempotentCommand(
                "M1_SUPPORT_LOAD_REBALANCE",
                idempotencyKey,
                requestHash(String.valueOf(request)),
                () -> rebalanceLoadOnce(idempotencyKey, request, agents));
    }

    private ApiResult<Map<String, Object>> rebalanceLoadOnce(
            String idempotencyKey,
            SupportLoadRebalanceRequest request,
            List<Map<String, Object>> agents) {
        LocalDateTime now = LocalDateTime.now(clock);
        String remark = request.reason().trim();
        for (Map<String, Object> agent : agents) {
            String agentId = loadAgentId(agent);
            int cap = boundedInt(parseInt(stringValue(agent.get("cap"))), 0, 40, 8);
            boolean busy = Boolean.parseBoolean(stringValue(agent.get("busy")));
            upsertLoadValue("agent." + agentId + ".cap", String.valueOf(cap), "NUMBER", remark);
            upsertLoadValue("agent." + agentId + ".busy", String.valueOf(busy), "BOOLEAN", remark);
        }
        configFacade.upsertAdminValue(LOAD_PREFIX + "lastRebalanceAt", now.toString(), "STRING", LOAD_GROUP, remark);
        audit("M1_SUPPORT_LOAD_REBALANCED", "SUPPORT_LOAD_CONFIG", "m1.support.load", request.operator(), Map.of(
                "reason", remark,
                "idempotencyKey", idempotencyKey.trim(),
                "agentCount", agents.size(),
                "rebalancedAt", now.toString()));
        Map<String, Object> view = loadConfigView();
        view.put("rebalanced", true);
        return ApiResult.ok(view);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ApiResult<T> idempotentCommand(
            String scope,
            String idempotencyKey,
            String requestHash,
            java.util.function.Supplier<ApiResult<T>> action) {
        return (ApiResult<T>) idempotencyService.execute(
                scope,
                idempotencyKey.trim(),
                requestHash,
                ApiResult.class,
                (java.util.function.Supplier) action);
    }

    private String requestHash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    public ApiResult<PageResult<SupportTicketView>> tickets(SupportTicketQueryRequest request) {
        ensureSeedData();
        return ApiResult.ok(ticketRepository.pageTickets(request));
    }

    public ApiResult<SupportTicketDetail> detail(String ticketNo) {
        ensureSeedData();
        if (!StringUtils.hasText(ticketNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TICKET_NO_REQUIRED");
        }
        return ticketRepository.findByTicketNo(ticketNo.trim())
                .map(ticket -> ApiResult.ok(new SupportTicketDetail(ticket, ticketRepository.messages(ticket.ticketNo()))))
                .orElseGet(() -> ApiResult.fail(404, "SUPPORT_TICKET_NOT_FOUND"));
    }

    @Transactional
    public ApiResult<SupportTicketDetail> create(String idempotencyKey, SupportTicketCreateRequest request) {
        ensureSeedData();
        ApiResult<SupportTicketDetail> guard = requireCreateCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return idempotentCommand(
                "M2_SUPPORT_TICKET_CREATE",
                idempotencyKey,
                requestHash(String.valueOf(request)),
                () -> createOnce(idempotencyKey, request));
    }

    private ApiResult<SupportTicketDetail> createOnce(String idempotencyKey, SupportTicketCreateRequest request) {
        SupportAgentProfileView assignedAgent = null;
        if (request.assignedAdminId() != null) {
            assignedAgent = supportAgentService.assignableSupportAgent(request.assignedAdminId()).orElse(null);
            if (assignedAgent == null) {
                return ApiResult.fail(404, "SUPPORT_AGENT_NOT_ASSIGNABLE");
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        String category = normalizeCategory(request.category());
        String priority = normalizePriority(request.priority());
        String ticketNo = "TK-" + now.format(TICKET_NO_TIME);
        SupportTicketView created = ticketRepository.createTicket(
                ticketNo,
                request.userId(),
                category,
                priority,
                request.title().trim(),
                request.body().trim(),
                request.assignedAdminId(),
                assignedAgent == null ? "Unassigned" : assignedAgent.name(),
                authenticatedOperator(request.operator()),
                now);
        String actor = authenticatedOperator(request.operator());
        audit("M2_SUPPORT_TICKET_CREATED", created.ticketNo(), actor, Map.of(
                "category", category,
                "priority", priority,
                "userId", created.userId(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return detail(created.ticketNo());
    }

    @Transactional
    public ApiResult<SupportTicketDetail> reply(String ticketNo, String idempotencyKey, SupportTicketReplyRequest request) {
        ensureSeedData();
        ApiResult<SupportTicketDetail> guard = requireReplyCommand(ticketNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return idempotentCommand(
                "M2_SUPPORT_TICKET_REPLY",
                idempotencyKey,
                requestHash(ticketNo, String.valueOf(request)),
                () -> replyOnce(ticketNo, idempotencyKey, request));
    }

    private ApiResult<SupportTicketDetail> replyOnce(String ticketNo, String idempotencyKey, SupportTicketReplyRequest request) {
        SupportTicketView ticket = find(ticketNo);
        if (ticket == null) {
            return ApiResult.fail(404, "SUPPORT_TICKET_NOT_FOUND");
        }
        if (Boolean.TRUE.equals(ticket.archived()) || "CLOSED".equalsIgnoreCase(ticket.status())) {
            return invalidState();
        }
        String actor = authenticatedOperator(request.operator());
        ticketRepository.appendReply(ticket, request.body().trim(), actor, LocalDateTime.now(clock));
        audit("M2_SUPPORT_TICKET_REPLIED", ticket.ticketNo(), actor, Map.of(
                "bodyLength", request.body().trim().length(),
                "reason", reasonOrDefault(request.reason(), "agent reply"),
                "idempotencyKey", idempotencyKey.trim()));
        return detail(ticket.ticketNo());
    }

    @Transactional
    public ApiResult<SupportTicketDetail> updateStatus(String ticketNo, String idempotencyKey, SupportTicketStatusRequest request) {
        ensureSeedData();
        ApiResult<SupportTicketDetail> guard = requireStatusCommand(ticketNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return idempotentCommand(
                "M2_SUPPORT_TICKET_STATUS",
                idempotencyKey,
                requestHash(ticketNo, String.valueOf(request)),
                () -> updateStatusOnce(ticketNo, idempotencyKey, request));
    }

    private ApiResult<SupportTicketDetail> updateStatusOnce(String ticketNo, String idempotencyKey, SupportTicketStatusRequest request) {
        SupportTicketView ticket = find(ticketNo);
        if (ticket == null) {
            return ApiResult.fail(404, "SUPPORT_TICKET_NOT_FOUND");
        }
        if (Boolean.TRUE.equals(ticket.archived())) {
            return invalidState();
        }
        String target = normalizeStatus(request.status());
        if (!canChangeStatus(ticket.status(), target)) {
            return invalidState();
        }
        String actor = authenticatedOperator(request.operator());
        LocalDateTime now = LocalDateTime.now(clock);
        ticketRepository.updateStatus(ticket, target, actor, now);
        ticketRepository.appendSystemTrace(ticket, "状态由" + statusLabel(ticket.status()) + "变更为" + statusLabel(target) + "（" + actor + "）", now);
        audit("M2_SUPPORT_TICKET_STATUS_CHANGED", ticket.ticketNo(), actor, Map.of(
                "from", ticket.status(),
                "to", target,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return detail(ticket.ticketNo());
    }

    @Transactional
    public ApiResult<SupportTicketDetail> updatePriority(String ticketNo, String idempotencyKey, SupportTicketPriorityRequest request) {
        ensureSeedData();
        ApiResult<SupportTicketDetail> guard = requirePriorityCommand(ticketNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return idempotentCommand(
                "M2_SUPPORT_TICKET_PRIORITY",
                idempotencyKey,
                requestHash(ticketNo, String.valueOf(request)),
                () -> updatePriorityOnce(ticketNo, idempotencyKey, request));
    }

    private ApiResult<SupportTicketDetail> updatePriorityOnce(String ticketNo, String idempotencyKey, SupportTicketPriorityRequest request) {
        SupportTicketView ticket = find(ticketNo);
        if (ticket == null) {
            return ApiResult.fail(404, "SUPPORT_TICKET_NOT_FOUND");
        }
        if (Boolean.TRUE.equals(ticket.archived()) || TERMINAL_STATUSES.contains(normalizeStatus(ticket.status()))) {
            return invalidState();
        }
        String target = normalizePriority(request.priority());
        String actor = authenticatedOperator(request.operator());
        LocalDateTime now = LocalDateTime.now(clock);
        ticketRepository.updatePriority(ticket, target, now);
        ticketRepository.appendSystemTrace(ticket, "优先级由" + priorityLabel(ticket.priority()) + "变更为" + priorityLabel(target) + "（" + actor + "）", now);
        audit("M2_SUPPORT_TICKET_PRIORITY_CHANGED", ticket.ticketNo(), actor, Map.of(
                "from", ticket.priority(),
                "to", target,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return detail(ticket.ticketNo());
    }

    @Transactional
    public ApiResult<SupportTicketDetail> assign(String ticketNo, String idempotencyKey, SupportTicketAssigneeRequest request) {
        ensureSeedData();
        ApiResult<SupportTicketDetail> guard = requireAssignCommand(ticketNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return idempotentCommand(
                "M2_SUPPORT_TICKET_ASSIGN",
                idempotencyKey,
                requestHash(ticketNo, String.valueOf(request)),
                () -> assignOnce(ticketNo, idempotencyKey, request));
    }

    private ApiResult<SupportTicketDetail> assignOnce(String ticketNo, String idempotencyKey, SupportTicketAssigneeRequest request) {
        SupportTicketView ticket = find(ticketNo);
        if (ticket == null) {
            return ApiResult.fail(404, "SUPPORT_TICKET_NOT_FOUND");
        }
        if (Boolean.TRUE.equals(ticket.archived()) || "CLOSED".equalsIgnoreCase(ticket.status())) {
            return invalidState();
        }
        SupportAgentProfileView targetAgent = supportAgentService.assignableSupportAgent(request.assignedAdminId()).orElse(null);
        if (targetAgent == null) {
            return ApiResult.fail(404, "SUPPORT_AGENT_NOT_ASSIGNABLE");
        }
        String actor = authenticatedOperator(request.operator());
        String targetName = targetAgent.name();
        LocalDateTime now = LocalDateTime.now(clock);
        ticketRepository.assign(ticket, request.assignedAdminId(), targetName, now);
        ticketRepository.appendSystemTrace(ticket, "负责人由 " + assignedName(ticket.assignedAdminName()) + " 转交给 " + targetName + "（" + actor + "）", now);
        audit("M2_SUPPORT_TICKET_ASSIGNED", ticket.ticketNo(), actor, Map.of(
                "assignedAdminId", request.assignedAdminId(),
                "assignedAdminName", targetName,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return detail(ticket.ticketNo());
    }

    @Transactional
    public ApiResult<SupportTicketDetail> archive(String ticketNo, String idempotencyKey, SupportTicketArchiveRequest request) {
        ensureSeedData();
        ApiResult<SupportTicketDetail> guard = requireArchiveCommand(ticketNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return idempotentCommand(
                "M2_SUPPORT_TICKET_ARCHIVE",
                idempotencyKey,
                requestHash(ticketNo, String.valueOf(request)),
                () -> archiveOnce(ticketNo, idempotencyKey, request));
    }

    private ApiResult<SupportTicketDetail> archiveOnce(String ticketNo, String idempotencyKey, SupportTicketArchiveRequest request) {
        SupportTicketView ticket = find(ticketNo);
        if (ticket == null) {
            return ApiResult.fail(404, "SUPPORT_TICKET_NOT_FOUND");
        }
        boolean target = !Boolean.FALSE.equals(request.archived());
        boolean current = Boolean.TRUE.equals(ticket.archived());
        if (target == current || (target && !TERMINAL_STATUSES.contains(normalizeStatus(ticket.status())))) {
            return invalidState();
        }
        String actor = authenticatedOperator(request.operator());
        LocalDateTime now = LocalDateTime.now(clock);
        ticketRepository.archive(ticket, target, actor, now);
        ticketRepository.appendSystemTrace(ticket, target ? "工单已归档（" + actor + "）" : "工单已恢复到已解决队列（" + actor + "）", now);
        audit(target ? "M2_SUPPORT_TICKET_ARCHIVED" : "M2_SUPPORT_TICKET_UNARCHIVED", ticket.ticketNo(), actor, Map.of(
                "from", current,
                "to", target,
                "status", ticket.status(),
                "reason", reasonOrDefault(request.reason(), target ? "routine archive" : "routine restore"),
                "idempotencyKey", idempotencyKey.trim()));
        return detail(ticket.ticketNo());
    }

    @Transactional
    public ApiResult<SupportTicketEscalationResult> escalate(
            String ticketNo,
            String idempotencyKey,
            SupportTicketEscalateRequest request) {
        ensureSeedData();
        ApiResult<SupportTicketEscalationResult> guard = requireEscalateCommand(ticketNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        return idempotentCommand(
                "M2_SUPPORT_TICKET_ESCALATE",
                idempotencyKey,
                requestHash(ticketNo, String.valueOf(request)),
                () -> escalateOnce(ticketNo, idempotencyKey, request));
    }

    private ApiResult<SupportTicketEscalationResult> escalateOnce(
            String ticketNo,
            String idempotencyKey,
            SupportTicketEscalateRequest request) {
        SupportTicketView ticket = find(ticketNo);
        if (ticket == null) {
            return ApiResult.fail(404, "SUPPORT_TICKET_NOT_FOUND");
        }
        if (Boolean.TRUE.equals(ticket.archived()) || TERMINAL_STATUSES.contains(normalizeStatus(ticket.status()))) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "SUPPORT_TICKET_ESCALATION_STATE_INVALID");
        }
        if (ticket.userId() == null || ticket.userId() <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_TICKET_USER_REQUIRED_FOR_ESCALATION");
        }
        SupportAgentProfileView targetAgent = supportAgentService.assignableSupportAgent(request.ownerAgentId()).orElse(null);
        if (targetAgent == null) {
            return ApiResult.fail(404, "SUPPORT_AGENT_NOT_ASSIGNABLE");
        }
        String conversationNo = "CV-" + ticket.ticketNo();
        if (conversationRepository.findByConversationNo(conversationNo).isPresent()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "SUPPORT_TICKET_ALREADY_ESCALATED");
        }
        String actor = authenticatedOperator(request.operator());
        String ownerName = targetAgent.name();
        String openingText = "工单 " + ticket.ticketNo() + " 已升级为即时会话：" + ticket.title();
        LocalDateTime now = LocalDateTime.now(clock);
        ContentConversationView conversation = conversationRepository.createConversation(
                conversationNo,
                ticket.userId(),
                "support",
                String.valueOf(targetAgent.adminId()),
                ownerName,
                openingText,
                now);
        ticketRepository.appendSystemTrace(ticket, "已升级为即时会话 " + conversationNo + "，请在会话台继续接待。", now);
        SupportTicketDetail updatedTicket = detail(ticket.ticketNo()).getData();
        audit("M2_SUPPORT_TICKET_ESCALATED", ticket.ticketNo(), actor, Map.of(
                "conversationNo", conversationNo,
                "userId", ticket.userId(),
                "ownerAgentId", String.valueOf(targetAgent.adminId()),
                "ownerAgentName", ownerName,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(new SupportTicketEscalationResult(updatedTicket, conversation));
    }

    private ApiResult<SupportTicketDetail> requireCreateCommand(String idempotencyKey, SupportTicketCreateRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.title()) || !StringUtils.hasText(request.body())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TITLE_AND_BODY_REQUIRED");
        }
        if (request.title().trim().length() > 160 || request.body().trim().length() > 2000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_TICKET_TEXT_TOO_LONG");
        }
        if (!CATEGORIES.contains(normalizeCategory(request.category()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_TICKET_CATEGORY_UNSUPPORTED");
        }
        if (!PRIORITIES.contains(normalizePriority(request.priority()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_TICKET_PRIORITY_UNSUPPORTED");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private void ensureSeedData() {
    }

    private ApiResult<Map<String, Object>> requireSupportCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private Map<String, Object> loadConfigView() {
        Map<String, String> values = configFacade.activeValuesByGroup(LOAD_GROUP);
        int defaultCap = boundedInt(parseInt(values.get(LOAD_PREFIX + "defaultCap")), 1, 40, 8);
        Map<String, Object> loadConfig = new LinkedHashMap<>();
        loadConfig.put("autoBalance", boolValue(values, "autoBalance", false));
        loadConfig.put("defaultCap", defaultCap);
        loadConfig.put("burstCap", boundedInt(parseInt(values.get(LOAD_PREFIX + "burstCap")), defaultCap, 40, 12));
        loadConfig.put("warnPct", boundedInt(parseInt(values.get(LOAD_PREFIX + "warnPct")), 50, 100, 80));
        loadConfig.put("quietHourBalance", boolValue(values, "quietHourBalance", false));
        loadConfig.put("overflowQueue", textValue(values, "overflowQueue", "转人工备勤队列"));

        Map<String, Map<String, Object>> agentState = new LinkedHashMap<>();
        String agentPrefix = LOAD_PREFIX + "agent.";
        values.forEach((key, value) -> {
            if (!key.startsWith(agentPrefix)) {
                return;
            }
            String suffix = key.substring(agentPrefix.length());
            int split = suffix.lastIndexOf('.');
            if (split <= 0 || split >= suffix.length() - 1) {
                return;
            }
            String agentId = suffix.substring(0, split);
            String field = suffix.substring(split + 1);
            if (!isSafeAgentKey(agentId)) {
                return;
            }
            Map<String, Object> state = agentState.computeIfAbsent(agentId, ignored -> new LinkedHashMap<>());
            if ("cap".equals(field)) {
                state.put("cap", boundedInt(parseInt(value), 1, 40, defaultCap));
            } else if ("busy".equals(field)) {
                state.put("busy", Boolean.parseBoolean(value));
            }
        });
        agentState.values().forEach(state -> {
            state.putIfAbsent("cap", defaultCap);
            state.putIfAbsent("busy", false);
        });

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("domain", "M1");
        response.put("loadConfig", loadConfig);
        response.put("agentState", agentState);
        response.put("lastRebalanceAt", values.getOrDefault(LOAD_PREFIX + "lastRebalanceAt", ""));
        response.put("sources", List.of("nx_config_item:" + LOAD_GROUP, "nx_support_ticket", "nx_conversation"));
        return response;
    }

    private void upsertLoadValue(String suffix, String value, String valueType, String remark) {
        configFacade.upsertAdminValue(LOAD_PREFIX + suffix, value, valueType, LOAD_GROUP, remark);
    }

    private boolean boolValue(Map<String, String> values, String suffix, boolean fallback) {
        String value = values.get(LOAD_PREFIX + suffix);
        return StringUtils.hasText(value) ? Boolean.parseBoolean(value) : fallback;
    }

    private int intValue(Map<String, String> values, String suffix, int fallback) {
        return boundedInt(parseInt(values.get(LOAD_PREFIX + suffix)), 0, 1000, fallback);
    }

    private String textValue(Map<String, String> values, String suffix, String fallback) {
        String value = values.get(LOAD_PREFIX + suffix);
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private Integer parseInt(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private int boundedInt(Integer value, int min, int max, int fallback) {
        int raw = value == null ? fallback : value;
        return Math.max(min, Math.min(max, raw));
    }

    private String loadAgentId(Map<String, Object> agent) {
        if (agent == null) {
            return "";
        }
        String id = stringValue(agent.get("id"));
        if (!StringUtils.hasText(id)) {
            id = stringValue(agent.get("agentId"));
        }
        if (!StringUtils.hasText(id)) {
            id = stringValue(agent.get("value"));
        }
        return StringUtils.hasText(id) ? id.trim() : "";
    }

    private String stringValue(Object value) {
        return value == null ? "" : value.toString().trim();
    }

    private boolean isSafeAgentKey(String agentId) {
        return StringUtils.hasText(agentId) && LOAD_AGENT_KEY.matcher(agentId.trim()).matches();
    }

    private ApiResult<SupportTicketDetail> requireReplyCommand(String ticketNo, String idempotencyKey, SupportTicketReplyRequest request) {
        if (!StringUtils.hasText(ticketNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TICKET_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.body())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REPLY_BODY_REQUIRED");
        }
        if (request.body().trim().length() > 2000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REPLY_BODY_TOO_LONG");
        }
        return null;
    }

    private ApiResult<SupportTicketDetail> requireStatusCommand(String ticketNo, String idempotencyKey, SupportTicketStatusRequest request) {
        if (!StringUtils.hasText(ticketNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TICKET_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !STATUSES.contains(normalizeStatus(request.status()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_TICKET_STATUS_UNSUPPORTED");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<SupportTicketDetail> requirePriorityCommand(String ticketNo, String idempotencyKey, SupportTicketPriorityRequest request) {
        if (!StringUtils.hasText(ticketNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TICKET_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !PRIORITIES.contains(normalizePriority(request.priority()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_TICKET_PRIORITY_UNSUPPORTED");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<SupportTicketDetail> requireAssignCommand(String ticketNo, String idempotencyKey, SupportTicketAssigneeRequest request) {
        if (!StringUtils.hasText(ticketNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TICKET_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || request.assignedAdminId() == null || !StringUtils.hasText(request.assignedAdminName())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ASSIGNEE_REQUIRED");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<SupportTicketDetail> requireArchiveCommand(
            String ticketNo,
            String idempotencyKey,
            SupportTicketArchiveRequest request) {
        if (!StringUtils.hasText(ticketNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TICKET_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || request.archived() == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_TICKET_ARCHIVE_TARGET_REQUIRED");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<SupportTicketEscalationResult> requireEscalateCommand(
            String ticketNo,
            String idempotencyKey,
            SupportTicketEscalateRequest request) {
        if (!StringUtils.hasText(ticketNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TICKET_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.ownerAgentId()) || !isSafeAgentKey(request.ownerAgentId())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_TICKET_ESCALATION_OWNER_REQUIRED");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private SupportTicketView find(String ticketNo) {
        return ticketRepository.findByTicketNo(ticketNo.trim()).orElse(null);
    }

    private ApiResult<SupportTicketDetail> invalidState() {
        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
    }

    private boolean canChangeStatus(String currentStatus, String targetStatus) {
        String current = normalizeStatus(currentStatus);
        return STATUS_TRANSITIONS.getOrDefault(current, Set.of()).contains(targetStatus);
    }

    private String normalizeCategory(String category) {
        return StringUtils.hasText(category) ? category.trim().toLowerCase(Locale.ROOT) : "other";
    }

    private String normalizePriority(String priority) {
        return StringUtils.hasText(priority) ? priority.trim().toUpperCase(Locale.ROOT) : "NORMAL";
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String statusLabel(String status) {
        return switch (normalizeStatus(status)) {
            case "IN_PROGRESS" -> "处理中";
            case "PENDING_USER" -> "待用户补充";
            case "RESOLVED" -> "已解决";
            case "CLOSED" -> "已关闭";
            default -> "待处理";
        };
    }

    private String priorityLabel(String priority) {
        return switch (normalizePriority(priority)) {
            case "URGENT" -> "紧急";
            case "HIGH" -> "高";
            case "LOW" -> "低";
            default -> "普通";
        };
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private String authenticatedOperator(String fallback) {
        return AdminActorResolver.resolve(operator(fallback));
    }

    private String assignedName(String assignedAdminName) {
        return StringUtils.hasText(assignedAdminName) ? assignedAdminName.trim() : "Unassigned";
    }

    private String reasonOrDefault(String reason, String fallback) {
        return StringUtils.hasText(reason) ? reason.trim() : fallback;
    }

    private void audit(String action, String ticketNo, String operator, Map<String, Object> detail) {
        audit(action, "SUPPORT_TICKET", ticketNo, operator, detail);
    }

    private void audit(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail)
                .build());
    }
}

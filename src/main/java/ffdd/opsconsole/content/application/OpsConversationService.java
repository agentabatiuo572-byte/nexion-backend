package ffdd.opsconsole.content.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.AdvisorRoutingDecision;
import ffdd.opsconsole.content.domain.ContentConversationDetail;
import ffdd.opsconsole.content.domain.ContentConversationMessageView;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.domain.ConversationTicketResult;
import ffdd.opsconsole.content.domain.SupportTicketDetail;
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
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsConversationService {
    private static final Set<String> TRANSFER_TARGET_TYPES = Set.of("agent", "queue", "standby");
    private static final Set<String> CONVERSATION_TYPES = Set.of("advisor", "support");
    private static final Set<String> DIRECT_STATUS_TARGETS = Set.of("OPEN", "RESOLVED", "CLOSED");
    private static final Set<String> TICKET_CATEGORIES = Set.of("account", "withdrawal", "deposit", "kyc", "hardware", "earnings", "genesis", "technical", "other");
    private static final Set<String> TICKET_PRIORITIES = Set.of("LOW", "NORMAL", "HIGH", "URGENT");
    private static final DateTimeFormatter CONVERSATION_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final DateTimeFormatter TICKET_NO_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");
    private static final String TIMEOUT_FALLBACK_CONFIG_KEY = "I.session.workbench.timeoutFallback";
    private static final int TRANSFER_TIMEOUT_MINUTES = 30;
    private static final int AUTO_FALLBACK_BATCH_SIZE = 50;

    private final ConversationRepository conversationRepository;
    private final SupportTicketRepository ticketRepository;
    private final OpsSupportAgentService supportAgentService;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public ApiResult<Map<String, Object>> overview() {
        ensureSeedData();
        Map<String, Object> response = new LinkedHashMap<>(conversationRepository.counters());
        response.put("domain", "I9");
        response.put("statuses", List.of("OPEN", "TRANSFERRED", "RESOLVED", "CLOSED"));
        response.put("conversationTypes", List.of("advisor", "support", "ai"));
        response.put("pendingMeaning", "unread or transferred conversations not archived");
        response.put("transferStateMachine", List.of("OPEN->TRANSFERRED", "TRANSFERRED->TRANSFERRED(wait)", "TRANSFERRED->OPEN", "OPEN->RESOLVED", "RESOLVED->CLOSED"));
        response.put("sources", List.of("nx_conversation", "nx_conversation_transfer"));
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<ContentConversationView>> conversations(ConversationQueryRequest request) {
        ensureSeedData();
        return ApiResult.ok(conversationRepository.pageConversations(request));
    }

    public ApiResult<ContentConversationDetail> detail(String conversationNo) {
        ensureSeedData();
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        return conversationRepository.findByConversationNo(conversationNo.trim())
                .map(conversation -> ApiResult.ok(new ContentConversationDetail(
                        conversation,
                        conversationRepository.messages(conversation.conversationNo()))))
                .orElseGet(() -> ApiResult.fail(404, "CONVERSATION_NOT_FOUND"));
    }

    public ApiResult<List<Map<String, Object>>> transferTargets() {
        ensureSeedData();
        return ApiResult.ok(supportAgentService.transferTargets());
    }

    public ApiResult<ContentConversationView> transfer(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferRequest request) {
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireTransferCommand(conversationNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        ContentConversationView conversation = conversationRepository.findByConversationNo(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        if (!"OPEN".equalsIgnoreCase(conversation.status())) {
            return invalidState();
        }
        String targetType = normalizeTargetType(request.targetType());
        String targetId = requireText(request.targetId(), "targetId is required");
        String targetName = StringUtils.hasText(request.targetName()) ? request.targetName().trim() : targetId;
        LocalDateTime now = LocalDateTime.now(clock);
        conversationRepository.transferToPending(
                conversation,
                targetType,
                targetId,
                targetName,
                request.reason().trim(),
                operator(request.operator()),
                now);
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        audit("I9_CONVERSATION_TRANSFERRED", conversation.conversationNo(), request.operator(), Map.of(
                "fromAgentId", conversation.ownerAgentId(),
                "fromAgentName", conversation.ownerAgentName(),
                "toType", targetType,
                "toId", targetId,
                "toName", targetName,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<ContentConversationView> acceptTransfer(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferDecisionRequest request) {
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireDecisionCommand(conversationNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        ContentConversationView conversation = conversationRepository.findByConversationNo(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        if (!"TRANSFERRED".equalsIgnoreCase(conversation.status())) {
            return invalidState();
        }
        conversationRepository.acceptTransfer(conversation, operator(request.operator()), LocalDateTime.now(clock));
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        audit("I9_CONVERSATION_TRANSFER_ACCEPTED", conversation.conversationNo(), request.operator(), Map.of(
                "fromAgentId", conversation.transferFromAgentId(),
                "acceptedBy", operator(request.operator()),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<ContentConversationView> returnTransfer(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferDecisionRequest request) {
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireDecisionCommand(conversationNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        ContentConversationView conversation = conversationRepository.findByConversationNo(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        if (!"TRANSFERRED".equalsIgnoreCase(conversation.status())) {
            return invalidState();
        }
        conversationRepository.returnTransfer(conversation, request.reason().trim(), operator(request.operator()), LocalDateTime.now(clock));
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        audit("I9_CONVERSATION_TRANSFER_RETURNED", conversation.conversationNo(), request.operator(), Map.of(
                "returnToAgentId", conversation.transferFromAgentId(),
                "returnedBy", operator(request.operator()),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<ContentConversationView> waitTransfer(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferDecisionRequest request) {
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireDecisionCommand(conversationNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        ContentConversationView conversation = conversationRepository.findByConversationNo(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        if (!"TRANSFERRED".equalsIgnoreCase(conversation.status())) {
            return invalidState();
        }
        String actor = operator(request.operator());
        conversationRepository.waitTransfer(conversation, request.reason().trim(), actor, LocalDateTime.now(clock));
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        audit("I9_CONVERSATION_TRANSFER_WAITED", conversation.conversationNo(), actor, Map.of(
                "fromAgentId", conversation.transferFromAgentId(),
                "toTargetId", conversation.transferToId(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<ContentConversationView> reply(
            String conversationNo,
            String idempotencyKey,
            ConversationReplyRequest request) {
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireReplyCommand(conversationNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        ContentConversationView conversation = conversationRepository.findByConversationNo(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        if ("TRANSFERRED".equalsIgnoreCase(conversation.status()) || "CLOSED".equalsIgnoreCase(conversation.status())) {
            return invalidState();
        }
        String body = request.body().trim();
        String actor = operator(request.operator());
        LocalDateTime now = LocalDateTime.now(clock);
        conversationRepository.reply(conversation, body, actor, now);
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        audit("I9_CONVERSATION_REPLIED", conversation.conversationNo(), actor, Map.of(
                "bodyLength", body.length(),
                "reason", reasonOrDefault(request.reason(), "agent reply"),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<ContentConversationView> updateStatus(
            String conversationNo,
            String idempotencyKey,
            ConversationStatusRequest request) {
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireStatusCommand(conversationNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        ContentConversationView conversation = conversationRepository.findByConversationNo(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        String targetStatus = normalizeStatus(request.status());
        if (!canDirectStatusChange(conversation.status(), targetStatus)) {
            return invalidState();
        }
        String actor = operator(request.operator());
        conversationRepository.updateStatus(conversation, targetStatus, actor, LocalDateTime.now(clock));
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        audit("I9_CONVERSATION_STATUS_CHANGED", conversation.conversationNo(), actor, Map.of(
                "from", conversation.status(),
                "to", targetStatus,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<ContentConversationView> archive(
            String conversationNo,
            String idempotencyKey,
            ConversationArchiveRequest request) {
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireReasonCommand(conversationNo, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ContentConversationView conversation = conversationRepository.findByConversationNo(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        boolean archived = request == null || request.archived() == null || request.archived();
        if (archived && "TRANSFERRED".equalsIgnoreCase(conversation.status())) {
            return invalidState();
        }
        if (!archived && !"CLOSED".equalsIgnoreCase(conversation.status())) {
            return invalidState();
        }
        String actor = operator(request.operator());
        LocalDateTime now = LocalDateTime.now(clock);
        conversationRepository.archive(conversation, archived, actor, now);
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        audit(archived ? "I9_CONVERSATION_ARCHIVED" : "I9_CONVERSATION_UNARCHIVED", conversation.conversationNo(), actor, Map.of(
                "from", conversation.status(),
                "to", archived ? "CLOSED" : "RESOLVED",
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    @Transactional
    public ApiResult<ContentConversationView> fallbackTransfer(
            String conversationNo,
            String idempotencyKey,
            ConversationFallbackRequest request) {
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireReasonCommand(conversationNo, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ContentConversationView conversation = conversationRepository.findByConversationNo(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        if (!"TRANSFERRED".equalsIgnoreCase(conversation.status())) {
            return invalidState();
        }
        String actor = operator(request.operator());
        LocalDateTime now = LocalDateTime.now(clock);
        boolean changed = conversationRepository.fallbackTransfer(conversation, request.reason().trim(), actor, now);
        if (!changed) {
            return invalidState();
        }
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        audit("I9_CONVERSATION_TRANSFER_FALLBACK", conversation.conversationNo(), actor, Map.of(
                "fromTargetId", conversation.transferToId(),
                "toTargetId", "standby-pool",
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    @Transactional
    public int runTimeoutFallback() {
        if (!timeoutFallbackEnabled()) {
            return 0;
        }
        ensureSeedData();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minusMinutes(TRANSFER_TIMEOUT_MINUTES);
        List<ContentConversationView> overdue = conversationRepository.overdueTransferredConversations(cutoff, AUTO_FALLBACK_BATCH_SIZE);
        int changed = 0;
        for (ContentConversationView conversation : overdue) {
            String reason = "Transfer pending over " + TRANSFER_TIMEOUT_MINUTES + " minutes; server fallback to standby pool";
            if (!conversationRepository.fallbackTransfer(conversation, reason, "system", now)) {
                continue;
            }
            changed += 1;
            audit("I9_CONVERSATION_TRANSFER_AUTO_FALLBACK", conversation.conversationNo(), "system", auditDetail(
                    "fromTargetId", conversation.transferToId(),
                    "toTargetId", "standby-pool",
                    "transferredAt", conversation.transferredAt(),
                    "timeoutMinutes", TRANSFER_TIMEOUT_MINUTES,
                    "reason", reason,
                    "idempotencyKey", "system:auto-timeout-fallback:" + conversation.conversationNo() + ":" + now));
        }
        return changed;
    }

    public ApiResult<ConversationTicketResult> convertToTicket(
            String conversationNo,
            String idempotencyKey,
            ConversationTicketRequest request) {
        ensureSeedData();
        ApiResult<ConversationTicketResult> guard = requireReasonCommand(conversationNo, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ContentConversationView conversation = conversationRepository.findByConversationNo(conversationNo.trim()).orElse(null);
        if (conversation == null) {
            return ApiResult.fail(404, "CONVERSATION_NOT_FOUND");
        }
        if ("CLOSED".equalsIgnoreCase(conversation.status())) {
            return invalidState();
        }
        String category = normalizeTicketCategory(request.category());
        String priority = normalizeTicketPriority(request.priority());
        if (!TICKET_CATEGORIES.contains(category)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_TICKET_CATEGORY_UNSUPPORTED");
        }
        if (!TICKET_PRIORITIES.contains(priority)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SUPPORT_TICKET_PRIORITY_UNSUPPORTED");
        }
        String actor = operator(request.operator());
        LocalDateTime now = LocalDateTime.now(clock);
        List<ContentConversationMessageView> messages = conversationRepository.messages(conversation.conversationNo());
        String ticketNo = "TK-" + now.format(TICKET_NO_TIME);
        SupportTicketView created = ticketRepository.createTicket(
                ticketNo,
                conversation.userId(),
                category,
                priority,
                titleOrDefault(request.title(), conversation),
                transcriptBody(conversation, messages),
                request.assignedAdminId(),
                assignedName(request.assignedAdminName(), actor),
                actor,
                now);
        conversationRepository.markConvertedToTicket(conversation, created.ticketNo(), actor, now);
        ContentConversationView updated = conversationRepository.findByConversationNo(conversation.conversationNo()).orElse(conversation);
        SupportTicketDetail ticketDetail = new SupportTicketDetail(created, ticketRepository.messages(created.ticketNo()));
        audit("I9_CONVERSATION_CONVERTED_TO_TICKET", conversation.conversationNo(), actor, auditDetail(
                "ticketNo", created.ticketNo(),
                "category", category,
                "priority", priority,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(new ConversationTicketResult(updated, ticketDetail));
    }

    public ApiResult<ContentConversationView> initiate(
            String idempotencyKey,
            ConversationInitiateRequest request) {
        ensureSeedData();
        ApiResult<ContentConversationView> guard = requireInitiateCommand(idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        String type = normalizeConversationType(request.conversationType());
        String actor = operator(request.operator());
        AdvisorRoutingDecision routing = routingDecision(type, request, actor);
        String ownerName = routing.targetName();
        String ownerId = routing.targetId();
        String text = request.openingText().trim();
        LocalDateTime now = LocalDateTime.now(clock);
        String conversationNo = "CV-OUT-" + now.format(CONVERSATION_NO_TIME);
        ContentConversationView created = conversationRepository.createConversation(
                conversationNo,
                request.userId(),
                type,
                ownerId,
                ownerName,
                text,
                now);
        SupportTicketView fallbackTicket = routing.fallbackTicket()
                ? createAdvisorFallbackTicket(created, text, routing, actor, now)
                : null;
        Map<String, Object> detail = auditDetail(
                "conversationType", type,
                "userId", request.userId() == null ? "audience" : request.userId(),
                "openingLength", text.length(),
                "ownerAgentId", ownerId,
                "ownerAgentName", ownerName,
                "routingTargetType", routing.targetType(),
                "routingReason", routing.reason(),
                "dedicatedAdvisor", routing.dedicated(),
                "fallbackTicket", routing.fallbackTicket(),
                "reason", reasonOrDefault(request.reason(), "single-user routine"),
                "idempotencyKey", idempotencyKey.trim());
        if (fallbackTicket != null) {
            detail.put("fallbackTicketNo", fallbackTicket.ticketNo());
        }
        audit("I9_CONVERSATION_INITIATED", created.conversationNo(), actor, detail);
        return ApiResult.ok(created);
    }

    private AdvisorRoutingDecision routingDecision(String type, ConversationInitiateRequest request, String actor) {
        if ("advisor".equals(type) && request.userId() != null) {
            AdvisorRoutingDecision decision = supportAgentService.routeAdvisorForUser(request.userId());
            if (decision != null && StringUtils.hasText(decision.targetId())) {
                return decision;
            }
        }
        String ownerName = StringUtils.hasText(request.ownerAgentName()) ? request.ownerAgentName().trim() : actor;
        String ownerId = StringUtils.hasText(request.ownerAgentId()) ? request.ownerAgentId().trim() : ownerName;
        return new AdvisorRoutingDecision(
                "agent",
                ownerId,
                ownerName,
                parseLong(ownerId).orElse(null),
                false,
                false,
                "REQUEST_OWNER");
    }

    private SupportTicketView createAdvisorFallbackTicket(
            ContentConversationView conversation,
            String openingText,
            AdvisorRoutingDecision routing,
            String actor,
            LocalDateTime now) {
        String ticketNo = "TK-" + now.format(TICKET_NO_TIME);
        return ticketRepository.createTicket(
                ticketNo,
                conversation.userId(),
                "account",
                "NORMAL",
                "Advisor standby fallback for " + conversation.conversationNo(),
                "Advisor conversation routed to " + routing.targetName() + ".\n" + openingText,
                routing.agentAdminId(),
                routing.targetName(),
                actor,
                now);
    }

    private void ensureSeedData() {
    }

    private ApiResult<ContentConversationView> requireTransferCommand(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferRequest request) {
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!StringUtils.hasText(request.targetId())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TARGET_REQUIRED");
        }
        return null;
    }

    private ApiResult<ContentConversationView> requireDecisionCommand(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferDecisionRequest request) {
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<ContentConversationView> requireReplyCommand(
            String conversationNo,
            String idempotencyKey,
            ConversationReplyRequest request) {
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
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

    private ApiResult<ContentConversationView> requireStatusCommand(
            String conversationNo,
            String idempotencyKey,
            ConversationStatusRequest request) {
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.status())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "STATUS_REQUIRED");
        }
        if (!DIRECT_STATUS_TARGETS.contains(normalizeStatus(request.status()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "STATUS_UNSUPPORTED");
        }
        if (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<ContentConversationView> requireInitiateCommand(
            String idempotencyKey,
            ConversationInitiateRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.openingText())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OPENING_TEXT_REQUIRED");
        }
        if (request.openingText().trim().length() > 2000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OPENING_TEXT_TOO_LONG");
        }
        String type = normalizeConversationType(request.conversationType());
        if (!CONVERSATION_TYPES.contains(type)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_TYPE_UNSUPPORTED");
        }
        if (request.userId() == null && (!StringUtils.hasText(request.reason()) || request.reason().trim().length() < 6)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private <T> ApiResult<T> requireReasonCommand(String conversationNo, String idempotencyKey, String reason) {
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private <T> ApiResult<T> invalidState() {
        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
    }

    private String normalizeTargetType(String targetType) {
        String normalized = StringUtils.hasText(targetType)
                ? targetType.trim().toLowerCase(Locale.ROOT)
                : "agent";
        if (!TRANSFER_TARGET_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported transfer target type");
        }
        return normalized;
    }

    private String normalizeConversationType(String conversationType) {
        return StringUtils.hasText(conversationType) ? conversationType.trim().toLowerCase(Locale.ROOT) : "support";
    }

    private String normalizeStatus(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "";
    }

    private String normalizeTicketCategory(String category) {
        return StringUtils.hasText(category) ? category.trim().toLowerCase(Locale.ROOT) : "account";
    }

    private String normalizeTicketPriority(String priority) {
        return StringUtils.hasText(priority) ? priority.trim().toUpperCase(Locale.ROOT) : "NORMAL";
    }

    private boolean canDirectStatusChange(String currentStatus, String targetStatus) {
        String current = normalizeStatus(currentStatus);
        if ("TRANSFERRED".equals(current)) {
            return false;
        }
        if ("CLOSED".equals(current) && !"CLOSED".equals(targetStatus)) {
            return false;
        }
        return DIRECT_STATUS_TARGETS.contains(targetStatus);
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private String reasonOrDefault(String reason, String fallback) {
        return StringUtils.hasText(reason) ? reason.trim() : fallback;
    }

    private String assignedName(String assignedAdminName, String fallback) {
        return StringUtils.hasText(assignedAdminName) ? assignedAdminName.trim() : fallback;
    }

    private boolean timeoutFallbackEnabled() {
        return configFacade.activeValue(TIMEOUT_FALLBACK_CONFIG_KEY)
                .map(value -> value.trim().toLowerCase(Locale.ROOT))
                .filter(value -> Set.of("on", "true", "1", "enabled", "yes").contains(value))
                .isPresent();
    }

    private Optional<Long> parseLong(String value) {
        if (!StringUtils.hasText(value)) {
            return Optional.empty();
        }
        try {
            return Optional.of(Long.parseLong(value.trim()));
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
    }

    private String titleOrDefault(String title, ContentConversationView conversation) {
        String value = StringUtils.hasText(title)
                ? title.trim()
                : "Conversation " + conversation.conversationNo() + " follow-up";
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

    private String transcriptBody(ContentConversationView conversation, List<ContentConversationMessageView> messages) {
        StringBuilder body = new StringBuilder();
        body.append("Conversation: ").append(conversation.conversationNo()).append('\n');
        body.append("User: ").append(conversation.userId()).append('\n');
        body.append("Status: ").append(conversation.status()).append('\n');
        for (ContentConversationMessageView message : messages) {
            body.append('[')
                    .append(message.senderType())
                    .append("] ")
                    .append(message.senderName())
                    .append(": ")
                    .append(message.content())
                    .append('\n');
            if (body.length() >= 1997) {
                return body.substring(0, 1997) + "...";
            }
        }
        if (messages.isEmpty() && StringUtils.hasText(conversation.lastMessage())) {
            body.append("[last] ").append(conversation.lastMessage());
        }
        String result = body.toString().trim();
        return result.length() > 2000 ? result.substring(0, 1997) + "..." : result;
    }

    private Map<String, Object> auditDetail(Object... values) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int index = 0; index + 1 < values.length; index += 2) {
            detail.put(String.valueOf(values[index]), values[index + 1]);
        }
        return detail;
    }

    private void audit(String action, String conversationNo, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("CONVERSATION")
                .resourceId(conversationNo)
                .bizNo(conversationNo)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("MEDIUM")
                .detail(detail)
                .build());
    }
}

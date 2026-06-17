package ffdd.opsconsole.content.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.ContentConversationView;
import ffdd.opsconsole.content.domain.ConversationRepository;
import ffdd.opsconsole.content.dto.ConversationQueryRequest;
import ffdd.opsconsole.content.dto.ConversationTransferDecisionRequest;
import ffdd.opsconsole.content.dto.ConversationTransferRequest;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

@ApplicationService
public class OpsConversationService {
    private static final Set<String> TRANSFER_TARGET_TYPES = Set.of("agent", "queue", "standby");

    private final ConversationRepository conversationRepository;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public OpsConversationService(ConversationRepository conversationRepository, AuditLogService auditLogService) {
        this(conversationRepository, auditLogService, Clock.systemDefaultZone());
    }

    OpsConversationService(ConversationRepository conversationRepository, AuditLogService auditLogService, Clock clock) {
        this.conversationRepository = conversationRepository;
        this.auditLogService = auditLogService;
        this.clock = clock;
    }

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>(conversationRepository.counters());
        response.put("domain", "I9");
        response.put("statuses", List.of("OPEN", "TRANSFERRED", "RESOLVED", "CLOSED"));
        response.put("conversationTypes", List.of("advisor", "support", "ai"));
        response.put("pendingMeaning", "unread or transferred conversations not archived");
        response.put("transferStateMachine", List.of("OPEN->TRANSFERRED", "TRANSFERRED->OPEN", "OPEN->RESOLVED", "RESOLVED->CLOSED"));
        response.put("sources", List.of("nx_conversation", "nx_conversation_transfer"));
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<ContentConversationView>> conversations(ConversationQueryRequest request) {
        return ApiResult.ok(conversationRepository.pageConversations(request));
    }

    public ApiResult<ContentConversationView> detail(String conversationNo) {
        if (!StringUtils.hasText(conversationNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CONVERSATION_NO_REQUIRED");
        }
        return conversationRepository.findByConversationNo(conversationNo.trim())
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "CONVERSATION_NOT_FOUND"));
    }

    public ApiResult<List<Map<String, Object>>> transferTargets() {
        return ApiResult.ok(conversationRepository.transferTargets());
    }

    public ApiResult<ContentConversationView> transfer(
            String conversationNo,
            String idempotencyKey,
            ConversationTransferRequest request) {
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

    private ApiResult<ContentConversationView> invalidState() {
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

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
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

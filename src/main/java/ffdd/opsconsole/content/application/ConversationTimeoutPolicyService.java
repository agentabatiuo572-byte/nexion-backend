package ffdd.opsconsole.content.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.domain.ConversationTimeoutPolicy;
import ffdd.opsconsole.content.dto.ConversationTimeoutPolicyUpdateRequest;
import ffdd.opsconsole.content.mapper.ConversationTimeoutPolicyMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class ConversationTimeoutPolicyService {
    private static final int MIN_WARN_MINUTES = 1;
    private static final int MAX_WARN_MINUTES = 30;
    private static final int MIN_CLOSE_MINUTES = 2;
    private static final int MAX_CLOSE_MINUTES = 120;
    private static final int MIN_REASON_LENGTH = 8;
    private static final int MAX_REASON_LENGTH = 200;

    private final ConversationTimeoutPolicyMapper mapper;
    private final AuditLogService auditLogService;
    private final Clock clock;

    public ApiResult<ConversationTimeoutPolicy> current() {
        ConversationTimeoutPolicy policy = mapper.selectPolicy();
        if (policy == null) {
            return ApiResult.fail(OpsErrorCode.INTERNAL_ERROR.httpStatus(), "M3_TIMEOUT_POLICY_NOT_CONFIGURED");
        }
        return ApiResult.ok(policy);
    }

    @Transactional
    public ApiResult<ConversationTimeoutPolicy> update(ConversationTimeoutPolicyUpdateRequest request) {
        ApiResult<ConversationTimeoutPolicy> invalid = validate(request);
        if (invalid != null) {
            return invalid;
        }

        int warnMinutes = request.warnMinutesExact();
        int closeMinutes = request.closeMinutesExact();
        String reason = request.reason().trim();
        String actor = AdminActorResolver.resolve(request.operator());
        ConversationTimeoutPolicy before = mapper.selectPolicyForUpdate();
        if (before == null) {
            return ApiResult.fail(OpsErrorCode.INTERNAL_ERROR.httpStatus(), "M3_TIMEOUT_POLICY_NOT_CONFIGURED");
        }
        if (!before.version().equals(request.expectedVersion())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "M3_TIMEOUT_POLICY_STALE");
        }
        if (before.warnMinutes().equals(warnMinutes)
                && before.closeMinutes().equals(closeMinutes)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "M3_TIMEOUT_POLICY_UNCHANGED");
        }

        LocalDateTime now = LocalDateTime.now(clock);
        int updated = mapper.updatePolicy(
                warnMinutes,
                closeMinutes,
                request.expectedVersion(),
                actor,
                reason,
                now);
        if (updated != 1) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "M3_TIMEOUT_POLICY_STALE");
        }

        ConversationTimeoutPolicy after = mapper.selectPolicy();
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("M3_CONVERSATION_TIMEOUT_POLICY_UPDATED")
                .resourceType("CONVERSATION_TIMEOUT_POLICY")
                .resourceId("GLOBAL")
                .actorType("ADMIN")
                .actorUsername(actor)
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of(
                        "beforeWarnMinutes", before.warnMinutes(),
                        "beforeCloseMinutes", before.closeMinutes(),
                        "afterWarnMinutes", warnMinutes,
                        "afterCloseMinutes", closeMinutes,
                        "beforeVersion", before.version(),
                        "afterVersion", before.version() + 1,
                        "reason", reason))
                .build());
        return ApiResult.ok(after);
    }

    private ApiResult<ConversationTimeoutPolicy> validate(ConversationTimeoutPolicyUpdateRequest request) {
        if (request == null
                || request.warnMinutes() == null
                || request.closeMinutes() == null
                || request.expectedVersion() == null
                || request.expectedVersion() < 1) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "M3_TIMEOUT_POLICY_REQUIRED");
        }
        if (!request.hasIntegralMinutes()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                    "M3_TIMEOUT_MINUTES_INTEGER_REQUIRED");
        }
        int warnMinutes = request.warnMinutesExact();
        int closeMinutes = request.closeMinutesExact();
        if (warnMinutes < MIN_WARN_MINUTES || warnMinutes > MAX_WARN_MINUTES) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "M3_TIMEOUT_WARN_RANGE_INVALID");
        }
        if (closeMinutes < MIN_CLOSE_MINUTES || closeMinutes > MAX_CLOSE_MINUTES) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "M3_TIMEOUT_CLOSE_RANGE_INVALID");
        }
        if (closeMinutes <= warnMinutes) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "M3_TIMEOUT_ORDER_INVALID");
        }
        if (!StringUtils.hasText(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), "M3_TIMEOUT_REASON_REQUIRED");
        }
        int length = request.reason().trim().length();
        if (length < MIN_REASON_LENGTH || length > MAX_REASON_LENGTH) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "M3_TIMEOUT_REASON_LENGTH_INVALID");
        }
        return null;
    }
}

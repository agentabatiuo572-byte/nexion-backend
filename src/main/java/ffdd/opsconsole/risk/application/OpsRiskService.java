package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.risk.domain.RiskCaseView;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.dto.RiskDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskSignalRequest;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.util.StringUtils;

@ApplicationService
public class OpsRiskService {
    private static final Set<String> FINAL_DECISIONS = Set.of("ALLOW", "BLOCK", "REJECT", "DENY");
    private static final Set<String> MANUAL_DECISIONS = Set.of("ALLOW", "BLOCK", "HOLD");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private final RiskOpsRepository riskRepository;
    private final AuditLogService auditLogService;

    public OpsRiskService(RiskOpsRepository riskRepository, AuditLogService auditLogService) {
        this.riskRepository = riskRepository;
        this.auditLogService = auditLogService;
    }

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>(riskRepository.overview());
        response.put("domain", "K");
        response.put("capabilities", List.of("RiskCase", "FraudSignal", "DeviceFingerprint", "DecisionEvidence"));
        response.put("decisionStates", List.of("REVIEWING", "FINALIZED"));
        response.put("redlines", List.of("finalized cases cannot be re-decided", "manual decisions require reason and A2 audit"));
        response.put("sources", List.of("nx_risk_decision", "nx_risk_signal"));
        return ApiResult.ok(response);
    }

    public ApiResult<List<RiskCaseView>> cases(RiskCaseQueryRequest request) {
        int limit = normalizeLimit(request == null ? null : request.limit(), 50, 200);
        return ApiResult.ok(riskRepository.search(
                request == null ? null : request.userId(),
                request == null ? null : request.status(),
                request == null ? null : request.decision(),
                limit));
    }

    public ApiResult<RiskCaseView> detail(String caseNo) {
        if (!StringUtils.hasText(caseNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RISK_CASE_NO_REQUIRED");
        }
        return riskRepository.findByCaseNo(caseNo.trim())
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "RISK_CASE_NOT_FOUND"));
    }

    public ApiResult<RiskCaseView> decide(String caseNo, String idempotencyKey, RiskDecisionRequest request) {
        ApiResult<RiskCaseView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!StringUtils.hasText(caseNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RISK_CASE_NO_REQUIRED");
        }
        RiskCaseView before = riskRepository.findByCaseNo(caseNo.trim()).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "RISK_CASE_NOT_FOUND");
        }
        if (isFinal(before)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        String decision = normalizeDecision(request.decision());
        riskRepository.updateDecision(caseNo.trim(), decision, request.reason().trim(), operator(request.operator()));
        RiskCaseView updated = riskRepository.findByCaseNo(caseNo.trim())
                .orElse(new RiskCaseView(
                        before.caseNo(), before.userId(), before.bizType(), before.bizNo(), before.region(), before.userLevel(),
                        decision, request.reason().trim(), before.riskScore(), before.ruleCodes(), "FINALIZED", operator(request.operator()),
                        LocalDateTime.now(), before.createdAt()));
        audit("K_RISK_CASE_DECIDED", "RISK_CASE", before.caseNo(), before.userId(), operator(request.operator()), Map.of(
                "fromDecision", before.decision(),
                "toDecision", decision,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> recordSignal(String idempotencyKey, RiskSignalRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.userId() == null || request.userId() <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        String signalType = requireText(request.signalType(), "SIGNAL_TYPE_REQUIRED").toUpperCase(Locale.ROOT);
        String severity = normalizeSeverity(request.severity());
        String signalNo = "SIG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        riskRepository.recordSignal(signalNo, request.userId(), signalType, severity, sanitizeEvidence(request.evidence()), operator(request.operator()));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("signalNo", signalNo);
        response.put("userId", request.userId());
        response.put("signalType", signalType);
        response.put("severity", severity);
        response.put("status", "RECORDED");
        audit("K_RISK_SIGNAL_RECORDED", "RISK_SIGNAL", signalNo, request.userId(), operator(request.operator()), Map.of(
                "signalType", signalType,
                "severity", severity,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(response);
    }

    private <T> ApiResult<T> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private boolean isFinal(RiskCaseView riskCase) {
        return "FINALIZED".equalsIgnoreCase(riskCase.status())
                || FINAL_DECISIONS.contains(normalizeText(riskCase.decision()));
    }

    private String normalizeDecision(String decision) {
        String normalized = requireText(decision, "RISK_DECISION_REQUIRED").toUpperCase(Locale.ROOT);
        if (!MANUAL_DECISIONS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported K manual risk decision");
        }
        return normalized;
    }

    private String normalizeSeverity(String severity) {
        String normalized = requireText(severity, "SEVERITY_REQUIRED").toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported K risk severity");
        }
        return normalized;
    }

    private String sanitizeEvidence(String evidence) {
        if (!StringUtils.hasText(evidence)) {
            return "{}";
        }
        return evidence.length() > 4000 ? evidence.substring(0, 4000) : evidence.trim();
    }

    private int normalizeLimit(Integer limit, int fallback, int max) {
        int value = limit == null ? fallback : limit;
        if (value < 1) {
            return fallback;
        }
        return Math.min(value, max);
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
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

    private void audit(String action, String resourceType, String resourceId, Long userId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(operator)
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }
}

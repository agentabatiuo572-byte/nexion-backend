package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.KycReviewTicketContext;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Closes every K4 score mutation path into K5 using the current K5 threshold.
 * The open-user unique key is the final concurrency guard; the locked read keeps
 * normal merges deterministic and the duplicate-key branch joins a concurrent winner.
 */
@Service
@RequiredArgsConstructor
public class K4KycReviewTriggerService {
    public static final String SOURCE_SCORE_OVERRIDE = "K4_SCORE_OVERRIDE";
    public static final String SOURCE_SCORE_RECOMPUTE = "K4_SCORE_RECOMPUTE";
    public static final String SOURCE_BATCH_RECOMPUTE = "K4_SCORE_BATCH_RECOMPUTE";
    public static final String SOURCE_MODEL_PUBLISH = "K4_MODEL_PUBLISH";
    public static final String SOURCE_FACT_REFRESH = "K4_FACT_REFRESH";
    public static final String SOURCE_REVIEW_THRESHOLD_CHANGE = "K5_REVIEW_TRIGGER_THRESHOLD_CHANGED";

    private static final Set<String> ALLOWED_SOURCES = Set.of(
            SOURCE_SCORE_OVERRIDE,
            SOURCE_SCORE_RECOMPUTE,
            SOURCE_BATCH_RECOMPUTE,
            SOURCE_MODEL_PUBLISH,
            SOURCE_FACT_REFRESH,
            SOURCE_REVIEW_THRESHOLD_CHANGE);

    private final RiskOpsRepository riskRepository;
    private final AuditLogService auditLogService;

    public boolean triggerIfThresholdReached(
            RiskScoreUserView before,
            RiskScoreUserView after,
            String source,
            String reason,
            String operator,
            String idempotencyKey) {
        requireValidSource(source);
        if (after == null || after.effectiveScore() == null || !StringUtils.hasText(after.userNo())) {
            return false;
        }
        int threshold = riskRepository.kycReviewTriggerScore();
        if (threshold < 70 || threshold > 100) {
            return false;
        }
        String normalizedReason = requiredText(reason, "K4_K5_TRIGGER_REASON_REQUIRED");
        String normalizedOperator = requiredText(operator, "K4_K5_TRIGGER_OPERATOR_REQUIRED");
        String normalizedIdempotencyKey = requiredText(idempotencyKey, "K4_K5_TRIGGER_IDEMPOTENCY_REQUIRED");
        boolean beforeAbove = before != null && before.effectiveScore() != null
                && before.effectiveScore() >= threshold;
        boolean afterAbove = after.effectiveScore() >= threshold;
        boolean persistedCrossing = riskRepository.transitionK4KycReviewTriggerState(
                after.userNo(), after.effectiveScore(), threshold, normalizedId(normalizedIdempotencyKey));
        if (!afterAbove || !persistedCrossing) {
            return false;
        }
        // beforeAbove is deliberately evaluated for the transition contract. A true persistedCrossing
        // with beforeAbove means this is the first durable observation after deploying the state table.
        KycReviewTicketContext open = riskRepository.findOpenKycReviewTicketByUserForUpdate(after.userNo())
                .orElse(null);
        if (open != null) {
            mergeAndAudit(open, beforeAbove, after, threshold, source, normalizedReason,
                    normalizedOperator, normalizedIdempotencyKey);
            return true;
        }

        String ticketId = "KR-K4-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8).toUpperCase(Locale.ROOT);
        try {
            riskRepository.createScoreTriggeredKycReviewTicket(
                    ticketId, after.userNo(), after.effectiveScore(), threshold,
                    source, normalizedReason, normalizedOperator);
            audit("K5_KYC_REVIEW_TRIGGERED_BY_SCORE", ticketId, beforeAbove, after, threshold, source,
                    normalizedReason, normalizedOperator, normalizedIdempotencyKey);
        } catch (DuplicateKeyException race) {
            KycReviewTicketContext winner = riskRepository.findOpenKycReviewTicketByUserForUpdate(after.userNo())
                    .orElse(null);
            if (winner == null) {
                throw race;
            }
            mergeAndAudit(winner, beforeAbove, after, threshold, source, normalizedReason,
                    normalizedOperator, normalizedIdempotencyKey);
        }
        return true;
    }

    private void mergeAndAudit(
            KycReviewTicketContext open,
            boolean beforeAbove,
            RiskScoreUserView user,
            int threshold,
            String source,
            String reason,
            String operator,
            String idempotencyKey) {
        if (!riskRepository.mergeOpenKycReviewTicket(open.ticketId(), open.version(), reason, operator)) {
            throw new IllegalStateException("K5_REVIEW_SCORE_TRIGGER_MERGE_CONFLICT");
        }
        audit("K5_KYC_REVIEW_SCORE_TRIGGER_MERGED", open.ticketId(), beforeAbove, user, threshold, source,
                reason, operator, idempotencyKey);
    }

    private void audit(
            String action,
            String ticketId,
            boolean beforeAbove,
            RiskScoreUserView user,
            int threshold,
            String source,
            String reason,
            String operator,
            String idempotencyKey) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("RISK_KYC_REVIEW_TICKET")
                .resourceId(ticketId)
                .bizNo(ticketId)
                .actorType("ADMIN")
                .actorUsername(operator)
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of(
                        "ticketId", ticketId,
                        "userNo", user.userNo(),
                        "effectiveScore", user.effectiveScore(),
                        "beforeAboveThreshold", beforeAbove,
                        "threshold", threshold,
                        "source", source,
                        "reason", reason,
                        "idempotencyKey", idempotencyKey))
                .build());
    }

    private void requireValidSource(String source) {
        if (!ALLOWED_SOURCES.contains(source)) {
            throw new IllegalArgumentException("K4_K5_TRIGGER_SOURCE_INVALID");
        }
    }

    private String requiredText(String value, String code) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(code);
        }
        return value.trim();
    }

    private String normalizedId(String value) {
        return value.length() <= 160 ? value : value.substring(0, 160);
    }
}

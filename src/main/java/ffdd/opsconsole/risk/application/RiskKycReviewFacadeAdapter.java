package ffdd.opsconsole.risk.application;

import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RiskKycReviewFacadeAdapter implements RiskKycReviewFacade {
    private final RiskOpsRepository riskRepository;
    private final AuditLogService auditLogService;

    @Override
    @Transactional
    public KycReviewTriggerResult triggerManualReview(String userNo, String operator, String reason) {
        if (!StringUtils.hasText(userNo) || !StringUtils.hasText(reason)) {
            throw new IllegalArgumentException("K5_MANUAL_REVIEW_INPUT_REQUIRED");
        }
        var open = riskRepository.findOpenKycReviewTicketByUserForUpdate(userNo.trim()).orElse(null);
        if (open != null) {
            return merge(open, userNo.trim(), "C4", userNo.trim(), BigDecimal.ZERO, 0, operator, reason.trim());
        }
        String ticketId = "KR-C4-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        try {
            riskRepository.createManualKycReviewTicket(ticketId, userNo.trim(), reason.trim(), actor(operator));
        } catch (DuplicateKeyException race) {
            var winner = riskRepository.findOpenKycReviewTicketByUserForUpdate(userNo.trim()).orElseThrow(() -> race);
            return merge(winner, userNo.trim(), "C4", userNo.trim(), BigDecimal.ZERO, 0, operator, reason.trim());
        }
        riskRepository.linkKycReviewSource(ticketId, "C4", userNo.trim());
        audit("K5_KYC_REVIEW_TRIGGERED_BY_C4", ticketId, userNo.trim(), "C4", userNo.trim(),
                BigDecimal.ZERO, 0, operator, reason.trim());
        return new KycReviewTriggerResult(true, true, ticketId, "K5_MANUAL_REVIEW_CREATED");
    }

    @Override
    @Transactional
    public KycReviewTriggerResult triggerC5IdentityReview(
            String userNo, String action, String operator, String reason) {
        if (!StringUtils.hasText(userNo) || !StringUtils.hasText(action) || !StringUtils.hasText(reason)) {
            throw new IllegalArgumentException("K5_C5_IDENTITY_REVIEW_INPUT_REQUIRED");
        }
        String normalizedUserNo = userNo.trim();
        String sourceNo = normalizedUserNo + ":" + action.trim();
        var open = riskRepository.findOpenKycReviewTicketByUserForUpdate(normalizedUserNo).orElse(null);
        if (open != null) {
            return merge(open, normalizedUserNo, "C5", sourceNo, BigDecimal.ZERO, 0, operator, reason.trim());
        }
        String ticketId = "KR-C5-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8).toUpperCase(Locale.ROOT);
        try {
            riskRepository.createManualKycReviewTicket(
                    ticketId, normalizedUserNo, "C5 " + action.trim() + " · " + reason.trim(), actor(operator));
        } catch (DuplicateKeyException race) {
            var winner = riskRepository.findOpenKycReviewTicketByUserForUpdate(normalizedUserNo)
                    .orElseThrow(() -> race);
            return merge(winner, normalizedUserNo, "C5", sourceNo, BigDecimal.ZERO, 0,
                    operator, reason.trim());
        }
        riskRepository.linkKycReviewSource(ticketId, "C5", sourceNo);
        audit("K5_KYC_REVIEW_TRIGGERED_BY_C5", ticketId, normalizedUserNo,
                "C5", sourceNo, BigDecimal.ZERO, 0, operator, reason.trim());
        return new KycReviewTriggerResult(true, true, ticketId, "K5_C5_IDENTITY_REVIEW_CREATED");
    }

    @Override
    @Transactional
    public KycReviewTriggerResult triggerLargeWithdrawalReview(
            String userNo,
            BigDecimal amountUsdt,
            String kycStatus,
            String withdrawalNo,
            String operator,
            String reason) {
        int threshold = riskRepository.kycLargeWithdrawReviewUsdt();
        if (!requiresReview(userNo, amountUsdt, threshold)) {
            return KycReviewTriggerResult.notRequired();
        }
        var open = riskRepository.findOpenKycReviewTicketByUserForUpdate(userNo).orElse(null);
        if (open != null) {
            return merge(open, userNo, "D2", withdrawalNo, amountUsdt, threshold, operator, reason);
        }
        String ticketId = "KR-D2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        try {
            riskRepository.createLargeWithdrawalKycReviewTicket(ticketId, userNo, amountUsdt, withdrawalNo, kycStatus, reason, actor(operator));
        } catch (DuplicateKeyException race) {
            var winner = riskRepository.findOpenKycReviewTicketByUserForUpdate(userNo).orElseThrow(() -> race);
            return merge(winner, userNo, "D2", withdrawalNo, amountUsdt, threshold, operator, reason);
        }
        audit("K5_KYC_REVIEW_TRIGGERED_BY_D2", ticketId, userNo, "D2", withdrawalNo, amountUsdt, threshold, operator, reason);
        return new KycReviewTriggerResult(true, true, ticketId, "K5_LARGE_WITHDRAWAL_REVIEW_REQUIRED");
    }

    @Override
    @Transactional
    public KycReviewTriggerResult triggerLargeExchangeReview(
            String userNo,
            BigDecimal amountUsdt,
            String kycStatus,
            String exchangeNo,
            String operator,
            String reason) {
        int threshold = riskRepository.kycLargeExchangeReviewUsdt();
        if (!requiresReview(userNo, amountUsdt, threshold)) {
            return KycReviewTriggerResult.notRequired();
        }
        var open = riskRepository.findOpenKycReviewTicketByUserForUpdate(userNo).orElse(null);
        if (open != null) {
            return merge(open, userNo, "G2", exchangeNo, amountUsdt, threshold, operator, reason);
        }
        String ticketId = "KR-G2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        try {
            riskRepository.createLargeExchangeKycReviewTicket(ticketId, userNo, amountUsdt, exchangeNo, kycStatus, reason, actor(operator));
        } catch (DuplicateKeyException race) {
            var winner = riskRepository.findOpenKycReviewTicketByUserForUpdate(userNo).orElseThrow(() -> race);
            return merge(winner, userNo, "G2", exchangeNo, amountUsdt, threshold, operator, reason);
        }
        audit("K5_KYC_REVIEW_TRIGGERED_BY_G2", ticketId, userNo, "G2", exchangeNo, amountUsdt, threshold, operator, reason);
        return new KycReviewTriggerResult(true, true, ticketId, "K5_LARGE_EXCHANGE_REVIEW_REQUIRED");
    }

    @Override
    @Transactional
    public KycReviewTriggerResult triggerCumulativeExchangeReview(
            String userNo,
            BigDecimal amountUsdt,
            BigDecimal cumulativeUsdt,
            BigDecimal thresholdUsdt,
            String kycStatus,
            String exchangeNo,
            String operator,
            String reason) {
        if (!StringUtils.hasText(userNo) || !StringUtils.hasText(exchangeNo) || !StringUtils.hasText(reason)
                || amountUsdt == null || amountUsdt.compareTo(BigDecimal.ZERO) <= 0
                || cumulativeUsdt == null || cumulativeUsdt.compareTo(BigDecimal.ZERO) <= 0
                || thresholdUsdt == null || thresholdUsdt.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("K5_CUMULATIVE_EXCHANGE_REVIEW_INPUT_REQUIRED");
        }
        String normalizedUserNo = userNo.trim();
        String normalizedExchangeNo = exchangeNo.trim();
        var open = riskRepository.findOpenKycReviewTicketByUserForUpdate(normalizedUserNo).orElse(null);
        if (open != null) {
            return mergeCumulativeExchange(open, normalizedUserNo, normalizedExchangeNo, amountUsdt,
                    cumulativeUsdt, thresholdUsdt, operator, reason.trim());
        }
        String ticketId = "KR-G2-" + UUID.randomUUID().toString().replace("-", "")
                .substring(0, 8).toUpperCase(Locale.ROOT);
        try {
            riskRepository.createCumulativeExchangeKycReviewTicket(
                    ticketId, normalizedUserNo, amountUsdt, cumulativeUsdt, thresholdUsdt,
                    normalizedExchangeNo, kycStatus, reason.trim(), actor(operator));
        } catch (DuplicateKeyException race) {
            var winner = riskRepository.findOpenKycReviewTicketByUserForUpdate(normalizedUserNo)
                    .orElseThrow(() -> race);
            return mergeCumulativeExchange(winner, normalizedUserNo, normalizedExchangeNo, amountUsdt,
                    cumulativeUsdt, thresholdUsdt, operator, reason.trim());
        }
        auditCumulativeExchange("K5_KYC_REVIEW_TRIGGERED_BY_G2_CUMULATIVE", ticketId,
                normalizedUserNo, normalizedExchangeNo, amountUsdt, cumulativeUsdt, thresholdUsdt,
                operator, reason.trim());
        return new KycReviewTriggerResult(
                true, true, ticketId, "K5_CUMULATIVE_EXCHANGE_REVIEW_REQUIRED");
    }

    private boolean requiresReview(String userNo, BigDecimal amountUsdt, int threshold) {
        if (!StringUtils.hasText(userNo) || amountUsdt == null || amountUsdt.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        if (threshold <= 0) {
            return true;
        }
        return amountUsdt.compareTo(BigDecimal.valueOf(threshold)) >= 0;
    }

    private KycReviewTriggerResult merge(
            ffdd.opsconsole.risk.domain.KycReviewTicketContext open, String userNo, String sourceDomain,
            String sourceNo, BigDecimal amountUsdt, int threshold, String operator, String reason) {
        String mergeReason = sourceDomain + ":" + sourceNo + " · " + reason;
        var current = open;
        if (!riskRepository.mergeOpenKycReviewTicket(current.ticketId(), current.version(), mergeReason, actor(operator))) {
            current = riskRepository.findOpenKycReviewTicketByUserForUpdate(userNo).orElse(null);
            if (current == null || !riskRepository.mergeOpenKycReviewTicket(
                    current.ticketId(), current.version(), mergeReason, actor(operator))) {
                throw new IllegalStateException("K5_REVIEW_MERGE_CONFLICT");
            }
        }
        riskRepository.linkKycReviewSource(current.ticketId(), sourceDomain, sourceNo);
        audit("K5_KYC_REVIEW_TRIGGER_MERGED_" + sourceDomain, current.ticketId(), userNo,
                sourceDomain, sourceNo, amountUsdt, threshold, operator, reason);
        return new KycReviewTriggerResult(true, false, current.ticketId(), "K5_REVIEW_TRIGGER_MERGED");
    }

    private KycReviewTriggerResult mergeCumulativeExchange(
            ffdd.opsconsole.risk.domain.KycReviewTicketContext open,
            String userNo,
            String exchangeNo,
            BigDecimal amountUsdt,
            BigDecimal cumulativeUsdt,
            BigDecimal thresholdUsdt,
            String operator,
            String reason) {
        String mergeReason = "G2:" + exchangeNo + " · cumulative=" + cumulativeUsdt
                + " threshold=" + thresholdUsdt + " · " + reason;
        var current = open;
        if (!riskRepository.mergeOpenKycReviewTicket(
                current.ticketId(), current.version(), mergeReason, actor(operator))) {
            current = riskRepository.findOpenKycReviewTicketByUserForUpdate(userNo).orElse(null);
            if (current == null || !riskRepository.mergeOpenKycReviewTicket(
                    current.ticketId(), current.version(), mergeReason, actor(operator))) {
                throw new IllegalStateException("K5_REVIEW_MERGE_CONFLICT");
            }
        }
        riskRepository.linkKycReviewSource(current.ticketId(), "G2", exchangeNo);
        auditCumulativeExchange("K5_KYC_REVIEW_TRIGGER_MERGED_G2_CUMULATIVE", current.ticketId(),
                userNo, exchangeNo, amountUsdt, cumulativeUsdt, thresholdUsdt, operator, reason);
        return new KycReviewTriggerResult(true, false, current.ticketId(), "K5_REVIEW_TRIGGER_MERGED");
    }

    private void auditCumulativeExchange(
            String action,
            String ticketId,
            String userNo,
            String exchangeNo,
            BigDecimal amountUsdt,
            BigDecimal cumulativeUsdt,
            BigDecimal thresholdUsdt,
            String operator,
            String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ticketId", ticketId);
        detail.put("userNo", userNo);
        detail.put("sourceDomain", "G2");
        detail.put("sourceNo", exchangeNo);
        detail.put("triggerType", "cumulative");
        detail.put("amountUsdt", amountUsdt);
        detail.put("cumulativeUsdt", cumulativeUsdt);
        detail.put("thresholdUsdt", thresholdUsdt);
        detail.put("reason", reason);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("RISK_KYC_REVIEW_TICKET")
                .resourceId(ticketId)
                .bizNo(exchangeNo)
                .actorType("SYSTEM")
                .actorUsername(actor(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private void audit(String action, String ticketId, String userNo, String sourceDomain, String sourceNo,
                       BigDecimal amountUsdt, int threshold, String operator, String reason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ticketId", ticketId);
        detail.put("userNo", userNo);
        detail.put("sourceDomain", sourceDomain);
        detail.put("sourceNo", sourceNo);
        detail.put("amountUsdt", amountUsdt);
        detail.put("thresholdUsdt", threshold);
        detail.put("reason", reason);
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("RISK_KYC_REVIEW_TICKET")
                .resourceId(ticketId)
                .bizNo(sourceNo)
                .actorType("ADMIN")
                .actorUsername(actor(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private String actor(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }
}

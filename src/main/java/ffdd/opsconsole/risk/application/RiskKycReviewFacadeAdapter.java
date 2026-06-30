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
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class RiskKycReviewFacadeAdapter implements RiskKycReviewFacade {
    private final RiskOpsRepository riskRepository;
    private final AuditLogService auditLogService;

    @Override
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
        if (riskRepository.hasOpenKycReviewTicket(userNo)) {
            return new KycReviewTriggerResult(true, false, null, "K5_REVIEW_ALREADY_OPEN");
        }
        String ticketId = "KR-D2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        riskRepository.createLargeWithdrawalKycReviewTicket(ticketId, userNo, amountUsdt, withdrawalNo, kycStatus, reason, actor(operator));
        audit("K5_KYC_REVIEW_TRIGGERED_BY_D2", ticketId, userNo, "D2", withdrawalNo, amountUsdt, threshold, operator, reason);
        return new KycReviewTriggerResult(true, true, ticketId, "K5_LARGE_WITHDRAWAL_REVIEW_REQUIRED");
    }

    @Override
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
        if (riskRepository.hasOpenKycReviewTicket(userNo)) {
            return new KycReviewTriggerResult(true, false, null, "K5_REVIEW_ALREADY_OPEN");
        }
        String ticketId = "KR-G2-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        riskRepository.createLargeExchangeKycReviewTicket(ticketId, userNo, amountUsdt, exchangeNo, kycStatus, reason, actor(operator));
        audit("K5_KYC_REVIEW_TRIGGERED_BY_G2", ticketId, userNo, "G2", exchangeNo, amountUsdt, threshold, operator, reason);
        return new KycReviewTriggerResult(true, true, ticketId, "K5_LARGE_EXCHANGE_REVIEW_REQUIRED");
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
        auditLogService.record(AuditLogWriteRequest.builder()
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

package ffdd.opsconsole.risk.facade;

import java.math.BigDecimal;

public interface RiskKycReviewFacade {
    default KycReviewTriggerResult triggerManualReview(
            String userNo,
            String operator,
            String reason) {
        throw new UnsupportedOperationException("K5_MANUAL_REVIEW_NOT_IMPLEMENTED");
    }

    default KycReviewTriggerResult triggerC5IdentityReview(
            String userNo,
            String action,
            String operator,
            String reason) {
        throw new UnsupportedOperationException("K5_C5_IDENTITY_REVIEW_NOT_IMPLEMENTED");
    }

    KycReviewTriggerResult triggerLargeWithdrawalReview(
            String userNo,
            BigDecimal amountUsdt,
            String kycStatus,
            String withdrawalNo,
            String operator,
            String reason);

    KycReviewTriggerResult triggerLargeExchangeReview(
            String userNo,
            BigDecimal amountUsdt,
            String kycStatus,
            String exchangeNo,
            String operator,
            String reason);

    default KycReviewTriggerResult triggerCumulativeExchangeReview(
            String userNo,
            BigDecimal amountUsdt,
            BigDecimal cumulativeUsdt,
            BigDecimal thresholdUsdt,
            String kycStatus,
            String exchangeNo,
            String operator,
            String reason) {
        throw new UnsupportedOperationException("K5_CUMULATIVE_EXCHANGE_REVIEW_NOT_IMPLEMENTED");
    }
}

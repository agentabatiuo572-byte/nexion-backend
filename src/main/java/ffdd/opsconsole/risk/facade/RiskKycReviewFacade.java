package ffdd.opsconsole.risk.facade;

import java.math.BigDecimal;

public interface RiskKycReviewFacade {
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
}

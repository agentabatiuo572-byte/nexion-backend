package ffdd.opsconsole.finance.dto;

import com.fasterxml.jackson.annotation.JsonUnwrapped;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import java.math.BigDecimal;

public record WithdrawalReviewView(
        @JsonUnwrapped WithdrawalOrderView order,
        BigDecimal strongReviewThresholdUsdt,
        Boolean addressVerificationRequired) {}

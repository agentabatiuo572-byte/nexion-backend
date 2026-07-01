package ffdd.opsconsole.risk.domain;

import java.math.BigDecimal;

public record RiskWithdrawCandidateView(
        String withdrawalNo,
        String userNo,
        BigDecimal amount,
        Integer withdrawalCount24h,
        String existingSignals
) {
}

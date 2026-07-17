package ffdd.opsconsole.risk.domain;

import java.math.BigDecimal;

public record RiskWithdrawCandidateView(
        String withdrawalNo,
        String userNo,
        BigDecimal amount,
        Integer withdrawalCount24h,
        BigDecimal withdrawalSum24h,
        Integer accountAgeDays,
        String addressReputation,
        String existingSignals
) {
    public RiskWithdrawCandidateView(
            String withdrawalNo, String userNo, BigDecimal amount,
            Integer withdrawalCount24h, String existingSignals) {
        this(withdrawalNo, userNo, amount, withdrawalCount24h, amount, null, "unknown", existingSignals);
    }
}

package ffdd.opsconsole.risk.facade;

import java.math.BigDecimal;

/** Server-canonical facts captured for one withdrawal at its D2 queue entry. */
public record WithdrawalRiskContext(
        Long userId,
        String withdrawalNo,
        String userNo,
        BigDecimal amountUsdt,
        Integer withdrawalCount24h,
        BigDecimal withdrawalSum24h,
        Integer accountAgeDays,
        String addressReputation,
        String chain,
        String targetAddress,
        BigDecimal thirdPartyAddressReputationScore) {

    public WithdrawalRiskContext(
            Long userId, String withdrawalNo, String userNo, BigDecimal amountUsdt,
            Integer withdrawalCount24h, BigDecimal withdrawalSum24h,
            Integer accountAgeDays, String addressReputation) {
        this(userId, withdrawalNo, userNo, amountUsdt, withdrawalCount24h, withdrawalSum24h,
                accountAgeDays, addressReputation, null, null, null);
    }

    public WithdrawalRiskContext withThirdPartyAddressReputationScore(BigDecimal score) {
        return new WithdrawalRiskContext(
                userId, withdrawalNo, userNo, amountUsdt, withdrawalCount24h, withdrawalSum24h,
                accountAgeDays, addressReputation, chain, targetAddress, score);
    }
}

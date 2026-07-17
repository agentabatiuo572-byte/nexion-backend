package ffdd.opsconsole.risk.domain;

/** Canonical raw facts used by the deterministic K4 scorer. */
public record RiskScoreRawInput(
        String userNo,
        Integer multiAccountClusterSize,
        Boolean multiAccountFraud,
        Integer arbitrageSignals,
        Boolean severeArbitrage,
        String kycStatus,
        Integer withdrawalCount24h,
        java.math.BigDecimal withdrawalAmount24h,
        Integer withdrawalCount7d,
        java.math.BigDecimal withdrawalAmount7d,
        java.math.BigDecimal withdrawalBaselineDailyCount,
        java.math.BigDecimal withdrawalBaselineDailyAmount,
        java.math.BigDecimal maxWithdrawal24h,
        Integer accountAgeDays,
        Integer anomalySignals,
        Boolean tamperDetected
) {
    public RiskScoreRawInput(
            String userNo, Integer multiAccountClusterSize, Boolean multiAccountFraud,
            Integer arbitrageSignals, Boolean severeArbitrage, String kycStatus,
            Integer withdrawalCount24h, java.math.BigDecimal withdrawalAmount24h,
            Integer accountAgeDays, Integer anomalySignals, Boolean tamperDetected) {
        this(userNo, multiAccountClusterSize, multiAccountFraud, arbitrageSignals, severeArbitrage, kycStatus,
                withdrawalCount24h, withdrawalAmount24h,
                withdrawalCount24h, withdrawalAmount24h,
                java.math.BigDecimal.ZERO, java.math.BigDecimal.ZERO, withdrawalAmount24h,
                accountAgeDays, anomalySignals, tamperDetected);
    }
}

package ffdd.opsconsole.user.domain;

import java.math.BigDecimal;

public record User360Seed(
        String lookupKey,
        String referralCode,
        String nickname,
        String countryCode,
        String phone,
        String email,
        String kycStatus,
        String userLevel,
        String vRank,
        String accountStatus,
        String language,
        String region,
        BigDecimal walletUsdt,
        BigDecimal walletNex,
        BigDecimal pendingWithdraw,
        BigDecimal lifetimeEarned,
        BigDecimal depositedUsd,
        BigDecimal withdrawnUsd,
        BigDecimal dailyUsdt,
        BigDecimal dailyNex,
        Integer deviceCount,
        Integer teamSize,
        Integer riskScore,
        Boolean twoFactorEnabled) {
}

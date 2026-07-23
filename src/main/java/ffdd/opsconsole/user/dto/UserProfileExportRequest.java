package ffdd.opsconsole.user.dto;

import java.math.BigDecimal;

public record UserProfileExportRequest(
        String keyword,
        String status,
        String kycStatus,
        Integer riskMin,
        String reason,
        String operator,
        Long userId,
        String phoneHash,
        String phoneMasked,
        String tier,
        String vRank,
        String referralCode,
        BigDecimal depositMin,
        BigDecimal depositMax,
        BigDecimal walletUsdtMin,
        BigDecimal walletUsdtMax,
        BigDecimal walletNexMin,
        BigDecimal walletNexMax,
        String riskBand,
        String joinedFrom,
        String joinedTo) {

    public static UserProfileExportRequest basic(
            String keyword,
            String status,
            String kycStatus,
            Integer riskMin,
            String reason,
            String operator) {
        return new UserProfileExportRequest(keyword, status, kycStatus, riskMin, reason, operator,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }
}

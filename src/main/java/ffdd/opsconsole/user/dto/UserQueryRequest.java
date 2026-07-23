package ffdd.opsconsole.user.dto;

import java.math.BigDecimal;

public record UserQueryRequest(
        String keyword,
        String status,
        String kycStatus,
        Integer riskMin,
        Integer pageNum,
        Integer pageSize,
        Integer limit,
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

    public static UserQueryRequest basic(
            String keyword,
            String status,
            String kycStatus,
            Integer riskMin,
            Integer pageNum,
            Integer pageSize,
            Integer limit) {
        return new UserQueryRequest(keyword, status, kycStatus, riskMin, pageNum, pageSize, limit,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null);
    }
}

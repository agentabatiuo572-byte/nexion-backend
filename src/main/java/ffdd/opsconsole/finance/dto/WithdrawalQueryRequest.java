package ffdd.opsconsole.finance.dto;

import java.math.BigDecimal;

public record WithdrawalQueryRequest(
        String status,
        Long userId,
        String keyword,
        Integer pageNum,
        Integer pageSize,
        BigDecimal minAmount,
        BigDecimal maxAmount,
        Integer minRiskScore,
        String ipSegment,
        String sortBy,
        String sortDirection) {
    public WithdrawalQueryRequest(String status, Long userId, String keyword, Integer pageNum, Integer pageSize) {
        this(status, userId, keyword, pageNum, pageSize, null, null, null, null, null, null);
    }

    public WithdrawalQueryRequest(
            String status, Long userId, String keyword, Integer pageNum, Integer pageSize,
            BigDecimal minAmount, BigDecimal maxAmount, Integer minRiskScore) {
        this(status, userId, keyword, pageNum, pageSize, minAmount, maxAmount, minRiskScore, null, null, null);
    }
}

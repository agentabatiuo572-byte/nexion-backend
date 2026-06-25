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
        Integer minRiskScore) {
    public WithdrawalQueryRequest(String status, Long userId, String keyword, Integer pageNum, Integer pageSize) {
        this(status, userId, keyword, pageNum, pageSize, null, null, null);
    }
}

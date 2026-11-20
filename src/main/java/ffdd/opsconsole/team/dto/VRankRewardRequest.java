package ffdd.opsconsole.team.dto;

import java.math.BigDecimal;

public record VRankRewardRequest(
        String type,
        BigDecimal amount,
        String voucherId,
        String skuId,
        String custom,
        String reason,
        String operator,
        String expectedRevision) {
    public VRankRewardRequest(String type, BigDecimal amount, String voucherId, String skuId,
                              String custom, String reason, String operator) {
        this(type, amount, voucherId, skuId, custom, reason, operator, null);
    }
}

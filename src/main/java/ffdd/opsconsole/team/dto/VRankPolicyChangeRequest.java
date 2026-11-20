package ffdd.opsconsole.team.dto;

import java.math.BigDecimal;

/** The business values carried by an F1 rank-policy approval. */
public record VRankPolicyChangeRequest(
        String rank,
        String expectedRevision,
        String action,
        String field,
        String value,
        String rewardId,
        Reward reward) {

    public record Reward(String type, BigDecimal amount, String voucherId, String skuId, String custom) { }
}

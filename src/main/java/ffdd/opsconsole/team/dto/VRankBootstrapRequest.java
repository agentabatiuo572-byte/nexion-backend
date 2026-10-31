package ffdd.opsconsole.team.dto;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/** Explicit operator values only; zero and an absent value are different inputs. */
public record VRankBootstrapRequest(List<Rank> ranks) {
    public record Rank(String rankCode, String titleCn, String titleEn,
            Long selfComputePoints, Integer directRefs, Long teamComputePoints,
            String requiredDownlineRank, Integer requiredDownlineCount,
            String unilevelDepth, BigDecimal peerBonusRate, Integer leadershipVotes,
            List<Object> rewards) { }

    public static VRankBootstrapRequest from(Map<String, Object> params) {
        try {
            var request = new ObjectMapper()
                    .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
                    .convertValue(params, VRankBootstrapRequest.class);
            request.validate();
            return request;
        } catch (IllegalArgumentException | NullPointerException invalid) {
            throw new BizException(422, "VRANK_BOOTSTRAP_INVALID");
        }
    }

    public void validate() {
        if (ranks == null || ranks.size() != 9) throw invalid();
        for (int index = 0; index < 9; index++) {
            Rank rank = ranks.get(index);
            if (rank == null || !("V" + index).equals(rank.rankCode())
                    || !title(rank.titleCn()) || !title(rank.titleEn())
                    || rank.selfComputePoints() == null || rank.selfComputePoints() < 0 || rank.selfComputePoints() > 9_007_199_254_740_991L
                    || rank.teamComputePoints() == null || rank.teamComputePoints() < 0 || rank.teamComputePoints() > 9_007_199_254_740_991L
                    || rank.directRefs() == null || rank.directRefs() < 0
                    || rank.requiredDownlineCount() == null || rank.requiredDownlineCount() < 0
                    || rank.leadershipVotes() == null || rank.leadershipVotes() < 0
                    || rank.peerBonusRate() == null || rank.peerBonusRate().signum() < 0
                    || rank.peerBonusRate().compareTo(BigDecimal.ONE) > 0 || rank.peerBonusRate().stripTrailingZeros().scale() > 4
                    || rank.unilevelDepth() == null || !rank.unilevelDepth().matches("0|L1(?:-L[1-7])?")) throw invalid();
            if (rank.rewards() == null || !rank.rewards().isEmpty()) {
                throw new BizException(422, "VRANK_BOOTSTRAP_USE_EXISTING_REWARD_EDITOR");
            }
            if (rank.requiredDownlineCount() == 0) {
                if (rank.requiredDownlineRank() != null) throw invalid();
            } else if (rank.requiredDownlineRank() == null || !rank.requiredDownlineRank().matches("V[0-8]")
                    || Integer.parseInt(rank.requiredDownlineRank().substring(1)) >= index) throw invalid();
            // Empty thresholds would silently promote every account on its next event.
            if (index > 0 && rank.selfComputePoints() == 0 && rank.teamComputePoints() == 0
                    && rank.directRefs() == 0 && rank.requiredDownlineCount() == 0) throw invalid();
        }
    }

    private static boolean title(String value) {
        return value != null && !value.isBlank() && value.equals(value.trim()) && value.length() <= 64;
    }
    private static BizException invalid() { return new BizException(422, "VRANK_BOOTSTRAP_INVALID"); }
}

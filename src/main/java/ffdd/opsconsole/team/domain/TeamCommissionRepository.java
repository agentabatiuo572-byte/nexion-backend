package ffdd.opsconsole.team.domain;

import java.util.List;
import java.util.Map;

public interface TeamCommissionRepository {
    List<Map<String, Object>> binarySettlements(int limit);

    Map<String, Object> binarySettlementSummary();

    List<Map<String, Object>> vRankRows();

    boolean updateVRankThreshold(String rank, String field, Object value);

    List<Map<String, Object>> f2Metrics();

    List<Map<String, Object>> unilevelRates();

    boolean updateUnilevelRule(int layerNo, String field, Object value);

    List<Map<String, Object>> rateTiers();

    List<Map<String, Object>> vRankRewards(String rank);

    boolean addVRankReward(String rank, Map<String, Object> reward);

    boolean updateVRankReward(String rank, String rewardId, Map<String, Object> reward);

    boolean deleteVRankReward(String rank, String rewardId);

    Map<String, Object> leadershipPoolSummary();

    List<Map<String, Object>> leadershipRanks();

    List<Map<String, Object>> quotaRows();

    List<Map<String, Object>> ambassadorBands();

    Map<String, Object> ambassadorSummary();

    List<Map<String, Object>> leaderboardPodium(int limit);

    Map<String, Object> leaderboardSummary();

    List<Map<String, Object>> commissionEvents(int limit);

    List<Map<String, Object>> commissionKindSummary();

    List<Map<String, Object>> commissionAuditFeed(int limit);

    boolean updateCommissionStatus(String eventId, String status);
}

package ffdd.opsconsole.team.infrastructure;

import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class MybatisTeamCommissionRepository implements TeamCommissionRepository {
    private final TeamCommissionMapper mapper;

    @Override
    public List<Map<String, Object>> binarySettlements(int limit) {
        return mapper.binarySettlements(Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public Map<String, Object> binarySettlementSummary() {
        Map<String, Object> row = mapper.binarySettlementSummary();
        return row == null ? new LinkedHashMap<>() : row;
    }

    @Override
    public List<Map<String, Object>> vRankRows() {
        return mapper.vRankRows();
    }

    @Override
    public boolean updateVRankThreshold(String rank, String field, Object value) {
        return switch (field) {
            case "selfBuy" -> mapper.updateVRankSelfBuy(rank, value) > 0;
            case "directRefs" -> mapper.updateVRankDirectRefs(rank, value) > 0;
            case "teamGv" -> mapper.updateVRankTeamGv(rank, value) > 0;
            case "legCount" -> mapper.updateVRankLegCount(rank, value) > 0;
            case "legRank" -> mapper.updateVRankLegRank(rank, value == null ? null : String.valueOf(value)) > 0;
            default -> false;
        };
    }

    @Override
    public List<Map<String, Object>> f2Metrics() {
        return mapper.f2Metrics();
    }

    @Override
    public List<Map<String, Object>> unilevelRates() {
        return mapper.unilevelRates();
    }

    @Override
    public boolean updateUnilevelRule(int layerNo, String field, Object value) {
        return switch (field) {
            case "usdtRate" -> mapper.updateUnilevelUsdtRate(layerNo, value) > 0;
            case "nexPerUsd" -> mapper.updateUnilevelNexPerUsd(layerNo, value) > 0;
            default -> false;
        };
    }

    @Override
    public List<Map<String, Object>> rateTiers() {
        return mapper.rateTiers();
    }

    @Override
    public List<Map<String, Object>> vRankRewards(String rank) {
        return mapper.vRankRewards(rank);
    }

    @Override
    public boolean addVRankReward(String rank, Map<String, Object> reward) {
        return mapper.insertVRankReward(
                rank,
                text(reward, "id"),
                text(reward, "type"),
                reward.get("amount"),
                text(reward, "voucherId"),
                text(reward, "skuId"),
                text(reward, "custom")) > 0;
    }

    @Override
    public boolean updateVRankReward(String rank, String rewardId, Map<String, Object> reward) {
        return mapper.updateVRankReward(
                rank,
                rewardId,
                text(reward, "type"),
                reward.get("amount"),
                text(reward, "voucherId"),
                text(reward, "skuId"),
                text(reward, "custom")) > 0;
    }

    @Override
    public boolean deleteVRankReward(String rank, String rewardId) {
        return mapper.deleteVRankReward(rank, rewardId) > 0;
    }

    @Override
    public Map<String, Object> leadershipPoolSummary() {
        Map<String, Object> row = mapper.leadershipPoolSummary();
        return row == null ? new LinkedHashMap<>() : row;
    }

    @Override
    public List<Map<String, Object>> leadershipRanks() {
        return mapper.leadershipRanks();
    }

    @Override
    public List<Map<String, Object>> quotaRows() {
        return mapper.quotaRows();
    }

    @Override
    public List<Map<String, Object>> ambassadorBands() {
        return mapper.ambassadorBands();
    }

    @Override
    public Map<String, Object> ambassadorSummary() {
        Map<String, Object> row = mapper.ambassadorSummary();
        return row == null ? new LinkedHashMap<>() : row;
    }

    @Override
    public List<Map<String, Object>> leaderboardPodium(int limit) {
        return mapper.leaderboardPodium(Math.max(1, Math.min(limit, 50)));
    }

    @Override
    public Map<String, Object> leaderboardSummary() {
        Map<String, Object> row = mapper.leaderboardSummary();
        return row == null ? new LinkedHashMap<>() : row;
    }

    @Override
    public List<Map<String, Object>> commissionEvents(int limit) {
        return mapper.commissionEvents(Math.max(1, Math.min(limit, 200)));
    }

    @Override
    public List<Map<String, Object>> commissionKindSummary() {
        return mapper.commissionKindSummary();
    }

    @Override
    public List<Map<String, Object>> commissionAuditFeed(int limit) {
        return mapper.commissionAuditFeed(Math.max(1, Math.min(limit, 50)));
    }

    @Override
    public boolean updateCommissionStatus(String eventId, String status) {
        return mapper.updateCommissionStatus(eventId, normalizeStatus(status)) > 0;
    }

    private String normalizeStatus(String status) {
        if (status == null) {
            return "PENDING";
        }
        String normalized = status.trim().toLowerCase();
        return switch (normalized) {
            case "unlocked", "available", "可提", "已解锁" -> "UNLOCKED";
            case "frozen", "已冻结" -> "FROZEN";
            case "rejected", "已驳回" -> "REJECTED";
            case "rollback", "reversed", "异常回退" -> "REVERSED";
            case "pending", "计提", "冷却中" -> "PENDING";
            default -> status.trim().toUpperCase();
        };
    }

    private String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }
}

package ffdd.opsconsole.team.infrastructure;

import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.VRankConfigRow;
import ffdd.opsconsole.team.domain.VRankRewardPayout;
import ffdd.opsconsole.team.domain.VRankRewardRuleRow;
import ffdd.opsconsole.team.mapper.TeamCommissionMapper;
import java.math.BigDecimal;
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

    // F4 · 修复2:V-Rank 票权写业务表。votes 由 service 校验非负后传入。
    @Override
    public boolean updateVRankLeadershipVotes(String rankCode, int votes) {
        return mapper.updateVRankLeadershipVotes(rankCode, votes) > 0;
    }

    // F4 · 修复3:大使审批写业务表。applicationId 数字 → 按 id;非数字 → fallback 最新 PENDING。
    // 两条分支任一影响行数 > 0 视为成功。
    @Override
    public boolean updateAmbassadorStatus(String applicationId, String status, String reviewer, String reason) {
        String canonical = normalizeStatus(status);
        Long numericId = parseLongOrNull(applicationId);
        if (numericId != null) {
            return mapper.updateAmbassadorStatusById(numericId, canonical, reviewer, reason) > 0;
        }
        return mapper.updateLatestPendingAmbassadorStatus(canonical, reviewer, reason) > 0;
    }

    // F4 · 修复4:榜单处置 INSERT 流水。
    @Override
    public boolean insertLeaderboardAction(String period, String actionType, String reason, String operator) {
        return mapper.insertLeaderboardAction(period, actionType, reason, operator) > 0;
    }

    // ============================================================
    // F1 V-Rank 晋升引擎(Sprint 1+2)
    // ============================================================

    @Override
    public List<VRankConfigRow> vRankConfigRows() {
        return mapper.vRankConfigRows().stream()
                .map(this::toVRankConfigRow)
                .toList();
    }

    private VRankConfigRow toVRankConfigRow(Map<String, Object> raw) {
        return new VRankConfigRow(
                text(raw, "rankCode"),
                decimal(raw.get("selfBuyUsd")),
                integer(raw.get("directRefs"), 0),
                decimal(raw.get("teamVolumeUsd")),
                textOr(raw.get("requiredDownlineRank"), null),
                integer(raw.get("requiredDownlineCount"), 0),
                integer(raw.get("sortOrder"), 0),
                textOr(raw.get("unilevelDepth"), null),
                decimal(raw.get("peerBonusRate")),
                integer(raw.get("leadershipVotes"), 0));
    }

    @Override
    public String currentMemberVRank(Long userId) {
        String rank = mapper.currentMemberVRank(userId);
        return rank == null || rank.isBlank() ? "V0" : rank;
    }

    @Override
    public boolean updateMemberVRank(Long userId, String newRank) {
        return mapper.updateMemberVRank(userId, newRank) > 0;
    }

    @Override
    public boolean insertUserLevelLog(Long userId,
                                      String fromCode,
                                      String toCode,
                                      String reason,
                                      String operator,
                                      String snapshotJson,
                                      String triggerEventId,
                                      String auditNo,
                                      boolean isManual) {
        return mapper.insertUserLevelLog(
                userId,
                fromCode,
                toCode,
                reason,
                operator,
                snapshotJson,
                triggerEventId,
                auditNo,
                isManual ? 1 : 0) > 0;
    }

    // ============================================================
    // F1 V-Rank 奖励派发(Sprint 3)
    // ============================================================

    @Override
    public List<VRankRewardRuleRow> selectVRankRewardRulesByRank(String rankCode) {
        return mapper.selectVRankRewardRulesByRank(rankCode).stream()
                .map(this::toVRankRewardRuleRow)
                .toList();
    }

    private VRankRewardRuleRow toVRankRewardRuleRow(Map<String, Object> raw) {
        return new VRankRewardRuleRow(
                text(raw, "rewardId"),
                text(raw, "rankCode"),
                text(raw, "rewardType"),
                decimal(raw.get("amount")),
                textOr(raw.get("voucherId"), null),
                textOr(raw.get("skuId"), null),
                textOr(raw.get("customLabel"), null),
                integer(raw.get("sortOrder"), 0));
    }

    @Override
    public Long findSponsorUserId(Long userId) {
        return mapper.findSponsorUserId(userId);
    }

    @Override
    public boolean existsVRankRewardPayout(Long userId, String rankCode, String rewardType) {
        return mapper.countVRankRewardPayout(userId, rankCode, rewardType) > 0;
    }

    @Override
    public Long insertCommissionEvent(Long userId,
                                      String commissionType,
                                      Long sourceUserId,
                                      String currency,
                                      BigDecimal amountUsdt,
                                      BigDecimal amountNex,
                                      String status,
                                      int coolingDays,
                                      String remark) {
        int rows = mapper.insertCommissionEvent(
                userId,
                commissionType,
                sourceUserId,
                currency,
                amountUsdt == null ? BigDecimal.ZERO : amountUsdt,
                amountNex == null ? BigDecimal.ZERO : amountNex,
                status,
                coolingDays,
                remark);
        if (rows == 0) {
            return null;
        }
        Long id = mapper.selectLastInsertId();
        return id;
    }

    @Override
    public Long insertNetworkCommissionEvent(Long userId,
                                             String commissionType,
                                             Long sourceUserId,
                                             Integer layerNo,
                                             String orderNo,
                                             BigDecimal orderAmountUsdt,
                                             String currency,
                                             BigDecimal amountUsdt,
                                             BigDecimal amountNex,
                                             String status,
                                             int coolingDays,
                                             String remark) {
        int rows = mapper.insertNetworkCommissionEvent(
                userId,
                commissionType,
                sourceUserId,
                layerNo,
                orderNo,
                orderAmountUsdt,
                currency,
                amountUsdt == null ? BigDecimal.ZERO : amountUsdt,
                amountNex == null ? BigDecimal.ZERO : amountNex,
                status,
                coolingDays,
                remark);
        if (rows == 0) {
            return null;
        }
        return mapper.selectLastInsertId();
    }

    @Override
    public int countNetworkCommissionByOrder(Long userId, String orderNo) {
        return mapper.countNetworkCommissionByOrder(userId, orderNo);
    }

    @Override
    public boolean insertVRankRewardPayout(VRankRewardPayout payout) {
        return mapper.insertVRankRewardPayout(
                payout.payoutId(),
                payout.userId(),
                payout.rankCode(),
                payout.rewardType(),
                payout.amount(),
                payout.voucherId(),
                payout.skuId(),
                payout.customLabel(),
                payout.sponsorUserId(),
                payout.status(),
                payout.commissionEventId(),
                payout.billId(),
                payout.triggerEventId(),
                payout.operator(),
                payout.reason()) > 0;
    }

    // Sprint5 端点 3:promotion-log 查询委托 mapper。SQL 用 IF(...) 兼容 MyBatis Boolean 映射,
    // 这里直接透传 mapper 返回的 Map 列表,字段 normalize 由 OpsTeamService.normalizePromotionLogRow 处理。
    @Override
    public List<Map<String, Object>> queryPromotionLog(Long userId,
                                                       String v,
                                                       String cohort,
                                                       String from,
                                                       String to) {
        return mapper.queryPromotionLog(userId, v, cohort, from, to);
    }

    // ============================================================
    // F1 V-Rank 派发流水查询/补发/撤销(Sprint 6 端点第二组)
    // ============================================================

    @Override
    public List<Map<String, Object>> queryRewardPayouts(String type,
                                                        String v,
                                                        String status,
                                                        Long userId,
                                                        String cursor) {
        return mapper.queryRewardPayouts(type, v, status, userId, cursor);
    }

    @Override
    public Map<String, Object> findRewardPayoutByPayoutId(String payoutId) {
        return mapper.findRewardPayoutByPayoutId(payoutId);
    }

    @Override
    public boolean updateRewardPayoutStatus(String payoutId, String newStatus, String operator, String reason) {
        return mapper.updateRewardPayoutStatus(payoutId, newStatus, operator, reason) > 0;
    }

    @Override
    public int reverseCommissionEvent(Long commissionEventId) {
        if (commissionEventId == null) {
            return 0;
        }
        return mapper.reverseCommissionEvent(commissionEventId);
    }

    private String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** textOr:text 的 "非空或 fallback" 变体,用于可空字段(如 required_downline_rank)。 */
    private String textOr(Object raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = String.valueOf(raw).trim();
        return value.isEmpty() || "null".equalsIgnoreCase(value) ? fallback : value;
    }

    private BigDecimal decimal(Object raw) {
        if (raw == null) {
            return BigDecimal.ZERO;
        }
        if (raw instanceof BigDecimal decimal) {
            return decimal;
        }
        if (raw instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        try {
            return new BigDecimal(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return BigDecimal.ZERO;
        }
    }

    private int integer(Object raw, int fallback) {
        if (raw == null) {
            return fallback;
        }
        if (raw instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(raw).trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private Long parseLongOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
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
}

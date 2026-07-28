package ffdd.opsconsole.team.web;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.team.domain.TeamCommissionRepository;
import ffdd.opsconsole.team.domain.VRankEvaluationSnapshot;
import ffdd.opsconsole.team.domain.VRankPerformanceRepository;
import ffdd.opsconsole.team.domain.VRankRewardRuleRow;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * App 侧 V-Rank 单一事实源。
 *
 * <p>等级定义来自 {@code nx_v_rank_config}，当前等级来自 {@code nx_team_member}，
 * 业绩进度复用晋升引擎的 {@link VRankPerformanceRepository} 聚合口径。客户端不得
 * 在 remote 模式继续以本地 seed/localStorage 作为等级结论。
 */
@RestController
@RequiredArgsConstructor
public class AppVRankController {
    private final TeamCommissionRepository commissionRepository;
    private final VRankPerformanceRepository performanceRepository;

    @GetMapping("/api/config/v-ranks")
    public ApiResult<Map<String, Object>> ranks() {
        return ApiResult.ok(Map.of(
                "source", "nx_v_rank_config + nx_v_rank_reward_rule + nx_commission_rule",
                "ranks", rankRows()));
    }

    @GetMapping("/api/team/rank")
    public ApiResult<Map<String, Object>> current(Authentication authentication) {
        Long userId = userId(authentication);
        if (userId == null) {
            return ApiResult.fail(403, "USER_SUBJECT_REQUIRED");
        }
        VRankEvaluationSnapshot snapshot = performanceRepository.computeSnapshot(userId);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("selfBuyUSD", snapshot.selfBuyUSD());
        progress.put("directRefs", snapshot.directRefs());
        progress.put("teamVolumeUSD", snapshot.teamVolumeUSD());
        progress.put("vDownlineCounts", snapshot.legCounts());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("source", "nx_team_member + server VRankPerformanceRepository");
        response.put("rankCode", commissionRepository.currentMemberVRank(userId));
        response.put("progress", progress);
        return ApiResult.ok(response);
    }

    private List<Map<String, Object>> rankRows() {
        BigDecimal directBonus = directBonus();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> source : commissionRepository.vRankRows()) {
            String rankCode = String.valueOf(source.getOrDefault("v", ""));
            if (!rankCode.matches("V(?:[0-9]|1[0-2])")) {
                continue;
            }
            int rank = Integer.parseInt(rankCode.substring(1));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("v", rank);
            row.put("title", String.valueOf(source.getOrDefault("label", rankCode)));
            row.put("cnTitle", String.valueOf(source.getOrDefault("label", rankCode)));
            row.put("selfBuyUSD", source.get("selfBuyUsd"));
            row.put("directRefs", source.get("directRefs"));
            row.put("teamVolumeUSD", source.get("teamGvUsd"));
            row.put("requiredDownlineRank", source.get("legRank"));
            row.put("requiredDownlineCount", source.get("legCount"));
            row.put("directBonus", directBonus);
            row.put("unilevelDepth", parseDepth(source.get("unilevelDepth")));
            row.put("peerBonus", decimal(source.get("peerBonusRate")));
            row.put("leadershipVotes", integer(source.get("votes")));
            row.put("cultivationBonus", cultivationBonus(rankCode));
            row.put("visible", booleanValue(source.get("visible")));
            rows.add(row);
        }
        return rows;
    }

    private BigDecimal directBonus() {
        return commissionRepository.unilevelRates().stream()
                .filter(row -> "L1".equalsIgnoreCase(String.valueOf(row.get("level"))))
                .map(row -> decimal(row.get("usdtPct")).movePointLeft(2))
                .findFirst()
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal cultivationBonus(String rankCode) {
        return commissionRepository.selectVRankRewardRulesByRank(rankCode).stream()
                .filter(rule -> "nex".equalsIgnoreCase(rule.rewardType()))
                .map(VRankRewardRuleRow::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private int parseDepth(Object value) {
        String text = String.valueOf(value == null ? "" : value).trim().toUpperCase();
        if (text.isEmpty()) return 0;
        if (text.contains("UNLIMITED")) return 99;
        int dash = text.lastIndexOf('L');
        String candidate = dash >= 0 ? text.substring(dash + 1) : text.replaceAll("[^0-9]", "");
        try {
            return Integer.parseInt(candidate.replaceAll("[^0-9]", ""));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal number) return number;
        try {
            return new BigDecimal(String.valueOf(value));
        } catch (RuntimeException ignored) {
            return BigDecimal.ZERO;
        }
    }

    private int integer(Object value) {
        return decimal(value).intValue();
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        String text = String.valueOf(value);
        return "1".equals(text) || "true".equalsIgnoreCase(text);
    }

    private Long userId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || authentication.getPrincipal() == null
                || !(authentication.getDetails() instanceof Map<?, ?> details)
                || !"USER".equals(String.valueOf(details.get("subjectType")))) {
            return null;
        }
        try {
            long value = Long.parseLong(String.valueOf(authentication.getPrincipal()));
            return value > 0 ? value : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

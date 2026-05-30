package ffdd.team.service;

import ffdd.team.dto.TeamRankEvaluateRequest;
import ffdd.team.dto.TeamUserSearchResponse;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TeamRankService {
    private final JdbcTemplate jdbcTemplate;

    public List<TeamUserSearchResponse> searchUsers(String keyword, int limit) {
        String normalized = keyword == null ? "" : keyword.trim();
        if (normalized.length() < 2 || normalized.length() > 64) {
            return List.of();
        }
        int safeLimit = Math.max(1, Math.min(limit, 10));
        String pattern = "%" + normalized + "%";
        return jdbcTemplate.query("""
                SELECT id,
                       nickname,
                       country_code,
                       phone,
                       referral_code,
                       user_level,
                       v_rank,
                       status
                  FROM nx_user
                 WHERE is_deleted = 0
                   AND (
                        phone LIKE ?
                     OR nickname LIKE ?
                     OR referral_code LIKE ?
                     OR CONCAT(country_code, phone) LIKE ?
                   )
                 ORDER BY id DESC
                 LIMIT ?
                """, (rs, rowNum) -> new TeamUserSearchResponse(
                rs.getLong("id"),
                rs.getString("nickname"),
                maskPhone(rs.getString("country_code"), rs.getString("phone")),
                rs.getString("referral_code"),
                rs.getString("user_level"),
                rs.getString("v_rank"),
                rs.getString("status")), pattern, pattern, pattern, pattern, safeLimit);
    }

    public List<Map<String, Object>> userLevels() {
        return jdbcTemplate.queryForList("""
                SELECT level_code AS levelCode,
                       level_name AS levelName,
                       entry_condition AS entryCondition,
                       core_goal AS coreGoal,
                       sort_order AS sortOrder,
                       status
                  FROM nx_user_level_config
                 WHERE is_deleted = 0
                 ORDER BY sort_order ASC, id ASC
                """);
    }

    public List<Map<String, Object>> vRanks() {
        return jdbcTemplate.queryForList("""
                SELECT rank_code AS rankCode,
                       title_en AS titleEn,
                       title_cn AS titleCn,
                       self_buy_usd AS selfBuyUsd,
                       direct_refs AS directRefs,
                       team_volume_usd AS teamVolumeUsd,
                       required_downline_rank AS requiredDownlineRank,
                       required_downline_count AS requiredDownlineCount,
                       downline_requirement AS downlineRequirement,
                       unilevel_depth AS unilevelDepth,
                       peer_bonus_rate AS peerBonusRate,
                       leadership_votes AS leadershipVotes,
                       physical_reward AS physicalReward,
                       sort_order AS sortOrder,
                       status
                  FROM nx_v_rank_config
                 WHERE is_deleted = 0
                 ORDER BY sort_order ASC, id ASC
                """);
    }

    public Map<String, Object> myRank(Long userId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT u.id AS userId,
                       u.user_level AS userLevel,
                       l.level_name AS userLevelName,
                       u.v_rank AS vRank,
                       r.title_cn AS vRankTitleCn,
                       r.title_en AS vRankTitleEn,
                       COALESCE(SUM(tm.volume), 0) AS teamVolumeUsd,
                       COUNT(DISTINCT CASE WHEN tm.level = 1 THEN tm.member_user_id END) AS directRefs,
                       COUNT(DISTINCT tm.member_user_id) AS teamCount
                  FROM nx_user u
             LEFT JOIN nx_user_level_config l
                    ON l.level_code = u.user_level
                   AND l.is_deleted = 0
             LEFT JOIN nx_v_rank_config r
                    ON r.rank_code = u.v_rank
                   AND r.is_deleted = 0
             LEFT JOIN nx_team_member tm
                    ON tm.user_id = u.id
                   AND tm.is_deleted = 0
                 WHERE u.id = ?
                   AND u.is_deleted = 0
              GROUP BY u.id, u.user_level, l.level_name, u.v_rank, r.title_cn, r.title_en
                """, userId);
        if (!rows.isEmpty()) {
            return rows.get(0);
        }
        Map<String, Object> fallback = new LinkedHashMap<>();
        fallback.put("userId", userId);
        fallback.put("userLevel", "L0");
        fallback.put("userLevelName", "Visitor");
        fallback.put("vRank", "V0");
        fallback.put("vRankTitleCn", "Cadet");
        fallback.put("vRankTitleEn", "Cadet");
        fallback.put("teamVolumeUsd", BigDecimal.ZERO);
        fallback.put("directRefs", 0);
        fallback.put("teamCount", 0);
        return fallback;
    }

    public Map<String, Object> evaluate(TeamRankEvaluateRequest request) {
        String nextUserLevel = evaluateUserLevel(request);
        String nextVRank = evaluateVRank(request);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", request.getUserId());
        result.put("eventType", request.getEventType());
        result.put("suggestedUserLevel", nextUserLevel);
        result.put("suggestedVRank", nextVRank);
        result.put("matchedAt", java.time.LocalDateTime.now().toString());
        return result;
    }

    private String evaluateUserLevel(TeamRankEvaluateRequest request) {
        if (positive(request.getDirectRefs()) && request.getDirectRefs() >= 3) {
            return "L5";
        }
        if (positive(request.getPurchasedDeviceCount())) {
            return "L4";
        }
        if (Boolean.TRUE.equals(request.getViewedStore())) {
            return "L3";
        }
        if (amount(request.getLifetimeEarnedUsdt()).compareTo(new BigDecimal("5")) >= 0) {
            return "L2";
        }
        if (Boolean.TRUE.equals(request.getRegistered())) {
            return "L1";
        }
        return "L0";
    }

    private String evaluateVRank(TeamRankEvaluateRequest request) {
        List<Map<String, Object>> ranks = vRanks();
        String matched = "V0";
        for (Map<String, Object> rank : ranks) {
            if (!Integer.valueOf(1).equals(asInt(rank.get("status")))) {
                continue;
            }
            if (matchesRank(request, rank)) {
                matched = String.valueOf(rank.get("rankCode"));
            }
        }
        return matched;
    }

    private boolean matchesRank(TeamRankEvaluateRequest request, Map<String, Object> rank) {
        if (amount(request.getSelfBuyUsd()).compareTo(amount(rank.get("selfBuyUsd"))) < 0) {
            return false;
        }
        if (safeInt(request.getDirectRefs()) < safeInt(rank.get("directRefs"))) {
            return false;
        }
        if (amount(request.getTeamVolumeUsd()).compareTo(amount(rank.get("teamVolumeUsd"))) < 0) {
            return false;
        }
        String requiredDownlineRank = rank.get("requiredDownlineRank") == null ? null : String.valueOf(rank.get("requiredDownlineRank"));
        if (requiredDownlineRank != null && !requiredDownlineRank.isBlank()) {
            int requiredCount = safeInt(rank.get("requiredDownlineCount"));
            int actualCount = request.getDownlineRankCounts() == null ? 0 : request.getDownlineRankCounts().getOrDefault(requiredDownlineRank, 0);
            return actualCount >= requiredCount;
        }
        return true;
    }

    private BigDecimal amount(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return BigDecimal.ZERO;
    }

    private boolean positive(Integer value) {
        return value != null && value > 0;
    }

    private int safeInt(Object value) {
        Integer converted = asInt(value);
        return converted == null ? 0 : converted;
    }

    private Integer asInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value instanceof String text && !text.isBlank()) {
            return Integer.valueOf(text);
        }
        return null;
    }

    private String maskPhone(String countryCode, String phone) {
        if (phone == null || phone.length() <= 4) {
            return (countryCode == null ? "" : countryCode + " ") + "****";
        }
        String suffix = phone.substring(phone.length() - 4);
        return (countryCode == null ? "" : countryCode + " ") + "****" + suffix;
    }
}

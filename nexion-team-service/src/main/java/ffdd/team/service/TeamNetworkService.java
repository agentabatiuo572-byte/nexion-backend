package ffdd.team.service;

import ffdd.common.api.PageResult;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class TeamNetworkService {
    private final JdbcTemplate jdbcTemplate;
    private final TeamGovernanceService governanceService;

    public PageResult<Map<String, Object>> pageMembers(Long userId, Integer level, long pageNum, long pageSize) {
        long normalizedPageNum = pageNum < 1 ? 1 : pageNum;
        long normalizedPageSize = pageSize < 1 ? 20 : Math.min(pageSize, 100);
        long offset = (normalizedPageNum - 1) * normalizedPageSize;
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM nx_team_member
                 WHERE user_id = ?
                   AND is_deleted = 0
                   AND (? IS NULL OR level = ?)
                """, Long.class, userId, level, level);
        List<Map<String, Object>> records = jdbcTemplate.query("""
                SELECT id, user_id, member_user_id, member_no, nickname, v_rank,
                       level, volume, created_at, updated_at
                  FROM nx_team_member
                 WHERE user_id = ?
                   AND is_deleted = 0
                   AND (? IS NULL OR level = ?)
                 ORDER BY level ASC, volume DESC, updated_at DESC, id DESC
                 LIMIT ? OFFSET ?
                """, this::mapMember, userId, level, level, normalizedPageSize, offset);
        return new PageResult<>(total == null ? 0 : total, normalizedPageNum, normalizedPageSize, records);
    }

    public List<Map<String, Object>> leaderboard(Long userId, String period, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        String normalizedPeriod = period == null || period.isBlank() ? "month" : period.trim().toLowerCase();
        List<Long> disqualifiedIds = governanceService.disqualifiedMemberIds(userId, normalizedPeriod);
        int days = switch (normalizedPeriod) {
            case "today" -> 1;
            case "week" -> 7;
            case "all" -> 3650;
            default -> 30;
        };
        return jdbcTemplate.query("""
                SELECT tm.member_user_id AS memberUserId,
                       tm.member_no AS memberNo,
                       tm.nickname,
                       tm.v_rank AS vRank,
                       tm.level,
                       tm.volume AS teamVolumeUsd,
                       COALESCE(SUM(ce.amount_usdt), 0) AS earnedUsdt,
                       COALESCE(SUM(ce.amount_nex), 0) AS earnedNex,
                       COUNT(ce.id) AS commissionCount,
                       MAX(ce.created_at) AS lastCommissionAt
                  FROM nx_team_member tm
             LEFT JOIN nx_commission_event ce
                    ON ce.user_id = tm.user_id
                   AND ce.source_user_id = tm.member_user_id
                   AND ce.is_deleted = 0
                   AND ce.created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                 WHERE tm.user_id = ?
                   AND tm.is_deleted = 0
                   AND NOT EXISTS (
                        SELECT 1
                          FROM nx_team_leaderboard_action act
                         WHERE act.user_id = tm.user_id
                           AND act.member_user_id = tm.member_user_id
                           AND act.period = ?
                           AND act.action_type = 'DISQUALIFIED'
                           AND act.is_deleted = 0
                   )
              GROUP BY tm.member_user_id, tm.member_no, tm.nickname, tm.v_rank, tm.level, tm.volume
              ORDER BY earnedUsdt DESC, tm.volume DESC, tm.updated_at DESC, tm.member_user_id DESC
                 LIMIT ?
                """, (rs, rowNum) -> mapLeaderboardRow(rs, rowNum, disqualifiedIds.size()), days, userId, normalizedPeriod, safeLimit);
    }

    private Map<String, Object> mapMember(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("userId", rs.getLong("user_id"));
        row.put("memberUserId", rs.getLong("member_user_id"));
        row.put("memberNo", rs.getString("member_no"));
        row.put("nickname", rs.getString("nickname"));
        row.put("vRank", rs.getString("v_rank"));
        row.put("level", rs.getInt("level"));
        row.put("volume", rs.getBigDecimal("volume"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        row.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
        return row;
    }

    private Map<String, Object> mapLeaderboardRow(ResultSet rs, int rowNum, int disqualifiedBefore) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("rankNo", rowNum + 1);
        row.put("disqualifiedBefore", disqualifiedBefore);
        row.put("memberUserId", rs.getLong("memberUserId"));
        row.put("memberNo", rs.getString("memberNo"));
        row.put("nickname", rs.getString("nickname"));
        row.put("vRank", rs.getString("vRank"));
        row.put("level", rs.getInt("level"));
        row.put("teamVolumeUsd", rs.getBigDecimal("teamVolumeUsd"));
        row.put("earnedUsdt", rs.getBigDecimal("earnedUsdt"));
        row.put("earnedNex", rs.getBigDecimal("earnedNex"));
        row.put("commissionCount", rs.getLong("commissionCount"));
        row.put("lastCommissionAt", rs.getTimestamp("lastCommissionAt") == null
                ? null
                : rs.getTimestamp("lastCommissionAt").toLocalDateTime());
        return row;
    }
}

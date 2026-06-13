package ffdd.team.service;

import ffdd.common.api.PageResult;
import ffdd.common.exception.BizException;
import ffdd.team.dto.TeamAmbassadorApplicationCreateRequest;
import ffdd.team.dto.TeamGovernanceActionRequest;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class TeamGovernanceService {
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_APPROVED = "APPROVED";
    private static final String STATUS_REJECTED = "REJECTED";
    private static final String ACTION_DISQUALIFIED = "DISQUALIFIED";

    private final JdbcTemplate jdbcTemplate;
    private final TeamRankService teamRankService;

    public List<Map<String, Object>> myAmbassadorApplications(Long userId) {
        if (userId == null || userId <= 0) {
            throw new BizException("User id is required");
        }
        return jdbcTemplate.query("""
                SELECT id, user_id, applicant_name, region, city, event_date, contact_method, application_reason,
                       event_plan, expected_attendees, current_rank, requested_budget_usdt,
                       kol_budget_pct, status, reviewer, review_reason, reviewed_at, created_at, updated_at
                  FROM nx_team_ambassador_application
                 WHERE user_id = ?
                   AND is_deleted = 0
                 ORDER BY created_at DESC, id DESC
                 LIMIT 20
                """, this::mapAmbassadorApplication, userId);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> createAmbassadorApplication(Long userId, TeamAmbassadorApplicationCreateRequest request) {
        if (userId == null || userId <= 0) {
            throw new BizException("User id is required");
        }
        if (request == null) {
            throw new BizException("Ambassador application payload is required");
        }
        String applicantName = requiredText(request.getApplicantName(), "Applicant name", 64);
        String region = requiredText(request.getRegion(), "Region", 64);
        String city = requiredText(request.getCity(), "City", 64);
        String contactMethod = requiredText(request.getContactMethod(), "Contact method", 128);
        String applicationReason = requiredText(request.getApplicationReason(), "Application reason", 255);
        String eventPlan = requiredText(request.getEventPlan(), "Event plan", 2000);
        int expectedAttendees = requireNonNegativeInt(request.getExpectedAttendees(), "Expected attendees");
        BigDecimal budget = requireNonNegative(request.getRequestedBudgetUsdt(), "Requested budget");
        Long pending = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM nx_team_ambassador_application
                 WHERE user_id = ?
                   AND status = ?
                   AND is_deleted = 0
                """, Long.class, userId, STATUS_PENDING);
        if (pending != null && pending > 0) {
            throw new BizException("You already have a pending ambassador application");
        }
        Map<String, Object> rank = teamRankService.myRank(userId);
        String currentRank = String.valueOf(rank.getOrDefault("vRank", "V0"));
        BigDecimal kolBudgetPct = teamConfigDecimal("team.agent.kol_budget_pct", BigDecimal.ZERO);
        jdbcTemplate.update("""
                INSERT INTO nx_team_ambassador_application
                    (user_id, applicant_name, region, city, event_date, contact_method, application_reason, event_plan,
                     expected_attendees, current_rank, requested_budget_usdt, kol_budget_pct,
                     status, created_at, updated_at, is_deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), 0)
                """,
                userId,
                applicantName,
                region,
                city,
                request.getEventDate(),
                contactMethod,
                applicationReason,
                eventPlan,
                expectedAttendees,
                currentRank,
                budget,
                kolBudgetPct,
                STATUS_PENDING);
        Long id = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        return ambassadorApplicationById(id);
    }

    public PageResult<Map<String, Object>> pageAmbassadorApplications(String status, long pageNum, long pageSize) {
        long normalizedPageNum = pageNum < 1 ? 1 : pageNum;
        long normalizedPageSize = pageSize < 1 ? 10 : Math.min(pageSize, 100);
        long offset = (normalizedPageNum - 1) * normalizedPageSize;
        String normalizedStatus = normalizeOptional(status);
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM nx_team_ambassador_application
                 WHERE is_deleted = 0
                   AND (? IS NULL OR status = ?)
                """, Long.class, normalizedStatus, normalizedStatus);
        List<Map<String, Object>> records = jdbcTemplate.query("""
                SELECT id, user_id, applicant_name, region, city, event_date, contact_method, application_reason,
                       event_plan, expected_attendees, current_rank, requested_budget_usdt,
                       kol_budget_pct, status, reviewer, review_reason, reviewed_at, created_at, updated_at
                  FROM nx_team_ambassador_application
                 WHERE is_deleted = 0
                   AND (? IS NULL OR status = ?)
                 ORDER BY FIELD(status, 'PENDING', 'APPROVED', 'REJECTED'), created_at DESC, id DESC
                 LIMIT ? OFFSET ?
                """, this::mapAmbassadorApplication, normalizedStatus, normalizedStatus, normalizedPageSize, offset);
        return new PageResult<>(total == null ? 0 : total, normalizedPageNum, normalizedPageSize, records);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> updateAmbassadorApplication(Long id, TeamGovernanceActionRequest request, String reviewer) {
        if (id == null || id <= 0) {
            throw new BizException("Ambassador application id is required");
        }
        String target = normalizeRequiredStatus(request, STATUS_APPROVED, STATUS_REJECTED);
        int updated = jdbcTemplate.update("""
                UPDATE nx_team_ambassador_application
                   SET status = ?,
                       reviewer = ?,
                       review_reason = ?,
                       reviewed_at = NOW(),
                       updated_at = NOW()
                 WHERE id = ?
                   AND status = ?
                   AND is_deleted = 0
                """, target, reviewerName(reviewer), reason(request), id, STATUS_PENDING);
        if (updated == 0) {
            throw new BizException("Ambassador application cannot be updated from current status");
        }
        return ambassadorApplicationById(id);
    }

    public PageResult<Map<String, Object>> pageLeaderboardActions(String period, long pageNum, long pageSize) {
        long normalizedPageNum = pageNum < 1 ? 1 : pageNum;
        long normalizedPageSize = pageSize < 1 ? 10 : Math.min(pageSize, 100);
        long offset = (normalizedPageNum - 1) * normalizedPageSize;
        String normalizedPeriod = normalizePeriod(period);
        Long total = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM nx_team_leaderboard_action
                 WHERE is_deleted = 0
                   AND period = ?
                """, Long.class, normalizedPeriod);
        List<Map<String, Object>> records = jdbcTemplate.query("""
                SELECT id, period, user_id, member_user_id, member_no, nickname, action_type,
                       reason, operator, created_at, updated_at
                  FROM nx_team_leaderboard_action
                 WHERE is_deleted = 0
                   AND period = ?
                 ORDER BY created_at DESC, id DESC
                 LIMIT ? OFFSET ?
                """, this::mapLeaderboardAction, normalizedPeriod, normalizedPageSize, offset);
        return new PageResult<>(total == null ? 0 : total, normalizedPageNum, normalizedPageSize, records);
    }

    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> disqualifyTopLeaderboardEntry(String period, TeamGovernanceActionRequest request, String operator) {
        String normalizedPeriod = normalizePeriod(period);
        Map<String, Object> target = firstEligibleLeaderboardRow(normalizedPeriod);
        long userId = asLong(target.get("userId"));
        long memberUserId = asLong(target.get("memberUserId"));
        jdbcTemplate.update("""
                INSERT INTO nx_team_leaderboard_action
                    (period, user_id, member_user_id, member_no, nickname, action_type, reason, operator, created_at, updated_at, is_deleted)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW(), 0)
                ON DUPLICATE KEY UPDATE
                    action_type = VALUES(action_type),
                    reason = VALUES(reason),
                    operator = VALUES(operator),
                    updated_at = NOW(),
                    is_deleted = 0
                """,
                normalizedPeriod,
                userId,
                memberUserId,
                target.get("memberNo"),
                target.get("nickname"),
                ACTION_DISQUALIFIED,
                reason(request),
                reviewerName(operator));
        return leaderboardActionByPeriodAndMember(normalizedPeriod, userId, memberUserId);
    }

    public List<Long> disqualifiedMemberIds(Long userId, String period) {
        String normalizedPeriod = normalizePeriod(period);
        return jdbcTemplate.query("""
                SELECT member_user_id
                  FROM nx_team_leaderboard_action
                 WHERE user_id = ?
                   AND period = ?
                   AND action_type = ?
                   AND is_deleted = 0
                """, (rs, rowNum) -> rs.getLong("member_user_id"), userId, normalizedPeriod, ACTION_DISQUALIFIED);
    }

    private Map<String, Object> firstEligibleLeaderboardRow(String period) {
        int days = periodDays(period);
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT tm.user_id AS userId,
                       tm.member_user_id AS memberUserId,
                       tm.member_no AS memberNo,
                       tm.nickname,
                       tm.volume AS teamVolumeUsd,
                       COALESCE(SUM(ce.amount_usdt), 0) AS earnedUsdt
                  FROM nx_team_member tm
             LEFT JOIN nx_commission_event ce
                    ON ce.user_id = tm.user_id
                   AND ce.source_user_id = tm.member_user_id
                   AND ce.is_deleted = 0
                   AND ce.created_at >= DATE_SUB(NOW(), INTERVAL ? DAY)
                 WHERE tm.is_deleted = 0
                   AND NOT EXISTS (
                        SELECT 1
                          FROM nx_team_leaderboard_action act
                         WHERE act.user_id = tm.user_id
                           AND act.member_user_id = tm.member_user_id
                           AND act.period = ?
                           AND act.action_type = ?
                           AND act.is_deleted = 0
                   )
              GROUP BY tm.user_id, tm.member_user_id, tm.member_no, tm.nickname, tm.volume
              ORDER BY earnedUsdt DESC, tm.volume DESC, tm.updated_at DESC, tm.member_user_id DESC
                 LIMIT 1
                """, this::mapLeaderboardCandidate, days, period, ACTION_DISQUALIFIED);
        if (rows.isEmpty()) {
            throw new BizException("No eligible leaderboard entry found");
        }
        return rows.get(0);
    }

    private Map<String, Object> ambassadorApplicationById(Long id) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT id, user_id, applicant_name, region, city, event_date, contact_method, application_reason,
                       event_plan, expected_attendees, current_rank, requested_budget_usdt,
                       kol_budget_pct, status, reviewer, review_reason, reviewed_at, created_at, updated_at
                  FROM nx_team_ambassador_application
                 WHERE id = ?
                   AND is_deleted = 0
                 LIMIT 1
                """, this::mapAmbassadorApplication, id);
        if (rows.isEmpty()) {
            throw new BizException(404, "Ambassador application not found");
        }
        return rows.get(0);
    }

    private Map<String, Object> leaderboardActionByPeriodAndMember(String period, long userId, long memberUserId) {
        List<Map<String, Object>> rows = jdbcTemplate.query("""
                SELECT id, period, user_id, member_user_id, member_no, nickname, action_type,
                       reason, operator, created_at, updated_at
                  FROM nx_team_leaderboard_action
                 WHERE period = ?
                   AND user_id = ?
                   AND member_user_id = ?
                   AND is_deleted = 0
                 LIMIT 1
                """, this::mapLeaderboardAction, period, userId, memberUserId);
        if (rows.isEmpty()) {
            throw new BizException(404, "Leaderboard action not found");
        }
        return rows.get(0);
    }

    private Map<String, Object> mapAmbassadorApplication(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("userId", rs.getLong("user_id"));
        row.put("applicantName", rs.getString("applicant_name"));
        row.put("region", rs.getString("region"));
        row.put("city", rs.getString("city"));
        row.put("eventDate", rs.getDate("event_date") == null ? null : rs.getDate("event_date").toLocalDate());
        row.put("contactMethod", rs.getString("contact_method"));
        row.put("applicationReason", rs.getString("application_reason"));
        row.put("eventPlan", rs.getString("event_plan"));
        row.put("expectedAttendees", rs.getInt("expected_attendees"));
        row.put("currentRank", rs.getString("current_rank"));
        row.put("requestedBudgetUsdt", rs.getBigDecimal("requested_budget_usdt"));
        row.put("kolBudgetPct", rs.getBigDecimal("kol_budget_pct"));
        row.put("status", rs.getString("status"));
        row.put("reviewer", rs.getString("reviewer"));
        row.put("reviewReason", rs.getString("review_reason"));
        row.put("reviewedAt", rs.getTimestamp("reviewed_at") == null ? null : rs.getTimestamp("reviewed_at").toLocalDateTime());
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        row.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
        return row;
    }

    private Map<String, Object> mapLeaderboardAction(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("period", rs.getString("period"));
        row.put("userId", rs.getLong("user_id"));
        row.put("memberUserId", rs.getLong("member_user_id"));
        row.put("memberNo", rs.getString("member_no"));
        row.put("nickname", rs.getString("nickname"));
        row.put("actionType", rs.getString("action_type"));
        row.put("reason", rs.getString("reason"));
        row.put("operator", rs.getString("operator"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        row.put("updatedAt", rs.getTimestamp("updated_at").toLocalDateTime());
        return row;
    }

    private Map<String, Object> mapLeaderboardCandidate(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("userId", rs.getLong("userId"));
        row.put("memberUserId", rs.getLong("memberUserId"));
        row.put("memberNo", rs.getString("memberNo"));
        row.put("nickname", rs.getString("nickname"));
        row.put("teamVolumeUsd", rs.getBigDecimal("teamVolumeUsd"));
        row.put("earnedUsdt", rs.getBigDecimal("earnedUsdt"));
        return row;
    }

    private String normalizeRequiredStatus(TeamGovernanceActionRequest request, String... allowed) {
        if (request == null || !StringUtils.hasText(request.getStatus())) {
            throw new BizException("Target status is required");
        }
        String target = request.getStatus().trim().toUpperCase();
        for (String item : allowed) {
            if (item.equals(target)) {
                return target;
            }
        }
        throw new BizException("Unsupported status: " + target);
    }

    private String reason(TeamGovernanceActionRequest request) {
        String raw = request == null ? "" : request.getReason();
        String trimmed = StringUtils.hasText(raw) ? raw.trim() : "PC governance action";
        return trimmed.length() > 220 ? trimmed.substring(0, 220) : trimmed;
    }

    private String reviewerName(String value) {
        return StringUtils.hasText(value) ? value.trim() : "system";
    }

    private String requiredText(String value, String name, int maxLength) {
        if (!StringUtils.hasText(value)) {
            throw new BizException(name + " is required");
        }
        String trimmed = value.trim();
        return trimmed.length() > maxLength ? trimmed.substring(0, maxLength) : trimmed;
    }

    private BigDecimal requireNonNegative(BigDecimal value, String name) {
        BigDecimal normalized = value == null ? BigDecimal.ZERO : value;
        if (normalized.compareTo(BigDecimal.ZERO) < 0) {
            throw new BizException(name + " must be non-negative");
        }
        return normalized;
    }

    private int requireNonNegativeInt(Integer value, String name) {
        int normalized = value == null ? 0 : value;
        if (normalized < 0) {
            throw new BizException(name + " must be non-negative");
        }
        return normalized;
    }

    private BigDecimal teamConfigDecimal(String configKey, BigDecimal fallback) {
        List<BigDecimal> rows = jdbcTemplate.query("""
                SELECT config_value
                  FROM nx_config_item
                 WHERE config_key = ?
                   AND is_deleted = 0
                 LIMIT 1
                """, (rs, rowNum) -> {
            try {
                return new BigDecimal(rs.getString("config_value"));
            } catch (Exception ignored) {
                return fallback;
            }
        }, configKey);
        return rows.isEmpty() ? fallback : rows.get(0);
    }

    private String normalizeOptional(String status) {
        return StringUtils.hasText(status) ? status.trim().toUpperCase() : null;
    }

    private String normalizePeriod(String period) {
        return StringUtils.hasText(period) ? period.trim().toLowerCase() : "week";
    }

    private int periodDays(String period) {
        return switch (normalizePeriod(period)) {
            case "today" -> 1;
            case "week" -> 7;
            case "all" -> 3650;
            default -> 30;
        };
    }

    private long asLong(Object value) {
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(String.valueOf(value));
    }
}

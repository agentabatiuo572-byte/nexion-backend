package ffdd.team.service;

import ffdd.common.exception.BizException;
import ffdd.team.dto.TeamBinarySettlementResult;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class TeamBinaryCommissionService {
    private static final String COMMISSION_BINARY = "BINARY";
    private static final String STATUS_PENDING = "PENDING";
    private static final int MAX_SETTLEMENT_LIMIT = 500;

    private final JdbcTemplate jdbcTemplate;

    public TeamBinaryCommissionService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public TeamBinarySettlementResult settle(LocalDate settlementDate, int limit) {
        LocalDate normalizedDate = settlementDate == null ? LocalDate.now() : settlementDate;
        int normalizedLimit = normalizeLimit(limit);
        BinaryRule rule = binaryRule();
        TeamBinarySettlementResult result = new TeamBinarySettlementResult();
        result.setSettlementDate(normalizedDate);

        List<Long> userIds = eligibleUserIds(normalizedLimit);
        result.setScanned(userIds.size());
        for (Long userId : userIds) {
            try {
                if (hasBinarySettlement(userId, normalizedDate)) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }
                List<Long> directLegs = firstTwoDirectLegs(userId);
                if (directLegs.size() < 2) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }

                Long leftUserId = directLegs.get(0);
                Long rightUserId = directLegs.get(1);
                BigDecimal previousMatchedVolume = previousMatchedVolume(userId, normalizedDate);
                BigDecimal leftVolume = positive(scale(branchVolume(userId, leftUserId)).subtract(previousMatchedVolume));
                BigDecimal rightVolume = positive(scale(branchVolume(userId, rightUserId)).subtract(previousMatchedVolume));
                BigDecimal matchedVolume = leftVolume.min(rightVolume);
                if (matchedVolume.compareTo(BigDecimal.ZERO) <= 0) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }

                BigDecimal amountUsdt = cap(matchedVolume.multiply(rule.usdtRate()), rule.dailyCapUsdt());
                if (amountUsdt.compareTo(BigDecimal.ZERO) <= 0) {
                    result.setSkipped(result.getSkipped() + 1);
                    continue;
                }

                Long commissionEventId = createBinaryCommission(
                        userId,
                        leftUserId,
                        rightUserId,
                        settlementOrderNo(normalizedDate),
                        leftVolume,
                        rightVolume,
                        matchedVolume,
                        amountUsdt,
                        rule);
                createBinarySettlement(
                        userId,
                        normalizedDate,
                        leftUserId,
                        rightUserId,
                        leftVolume,
                        rightVolume,
                        matchedVolume,
                        amountUsdt,
                        rule.dailyCapUsdt(),
                        commissionEventId);
                result.setCreated(result.getCreated() + 1);
                result.getCommissionIds().add(commissionEventId);
            } catch (DuplicateKeyException ex) {
                result.setSkipped(result.getSkipped() + 1);
            } catch (RuntimeException ex) {
                result.setFailed(result.getFailed() + 1);
            }
        }
        return result;
    }

    public List<Map<String, Object>> summary(LocalDate settlementDate, Long userId, int limit) {
        int normalizedLimit = normalizeLimit(limit);
        StringBuilder sql = new StringBuilder("""
                SELECT id, user_id, settlement_date, left_user_id, right_user_id,
                       left_volume, right_volume, matched_volume, amount_usdt,
                       daily_cap_usdt, commission_event_id, status, created_at
                  FROM nx_binary_commission_settlement
                 WHERE is_deleted = 0
                """);
        ArrayList<Object> args = new ArrayList<>();
        if (settlementDate != null) {
            sql.append("   AND settlement_date = ?\n");
            args.add(settlementDate);
        }
        if (userId != null) {
            sql.append("   AND user_id = ?\n");
            args.add(userId);
        }
        sql.append(" ORDER BY settlement_date DESC, id DESC LIMIT ?");
        args.add(normalizedLimit);
        return jdbcTemplate.query(sql.toString(), this::mapSettlementSummary, args.toArray());
    }

    protected BinaryRule binaryRule() {
        List<BinaryRule> rules = jdbcTemplate.query("""
                SELECT usdt_rate, daily_cap_usdt, cooldown_days
                  FROM nx_commission_rule
                 WHERE commission_type = 'BINARY'
                   AND status = 1
                   AND is_deleted = 0
                 LIMIT 1
                """, (rs, rowNum) -> new BinaryRule(
                rs.getBigDecimal("usdt_rate"),
                rs.getBigDecimal("daily_cap_usdt"),
                rs.getInt("cooldown_days")));
        if (rules.isEmpty()) {
            throw new BizException("Binary commission rule is not configured");
        }
        return rules.get(0);
    }

    protected List<Long> eligibleUserIds(int limit) {
        return jdbcTemplate.query("""
                SELECT user_id
                  FROM nx_team_member
                 WHERE level = 1
                   AND is_deleted = 0
                 GROUP BY user_id
                HAVING COUNT(DISTINCT member_user_id) >= 2
                 ORDER BY user_id ASC
                 LIMIT ?
                """, (rs, rowNum) -> rs.getLong("user_id"), limit);
    }

    protected boolean hasBinarySettlement(Long userId, LocalDate settlementDate) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM nx_binary_commission_settlement
                 WHERE user_id = ?
                   AND settlement_date = ?
                   AND is_deleted = 0
                """, Long.class, userId, settlementDate);
        return count != null && count > 0;
    }

    protected List<Long> firstTwoDirectLegs(Long userId) {
        return jdbcTemplate.query("""
                SELECT member_user_id
                  FROM nx_team_member
                 WHERE user_id = ?
                   AND level = 1
                   AND is_deleted = 0
                 ORDER BY created_at ASC, id ASC
                 LIMIT 2
                """, (rs, rowNum) -> rs.getLong("member_user_id"), userId);
    }

    protected BigDecimal branchVolume(Long userId, Long directLegUserId) {
        BigDecimal volume = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(tm.volume), 0)
                  FROM nx_team_member tm
                 WHERE tm.user_id = ?
                   AND tm.is_deleted = 0
                   AND (
                        tm.member_user_id = ?
                        OR tm.member_user_id IN (
                            SELECT child.member_user_id
                              FROM nx_team_member child
                             WHERE child.user_id = ?
                               AND child.is_deleted = 0
                        )
                   )
                """, BigDecimal.class, userId, directLegUserId, directLegUserId);
        return volume == null ? BigDecimal.ZERO : volume;
    }

    protected BigDecimal previousMatchedVolume(Long userId, LocalDate settlementDate) {
        BigDecimal volume = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(matched_volume), 0)
                  FROM nx_binary_commission_settlement
                 WHERE user_id = ?
                   AND settlement_date < ?
                   AND is_deleted = 0
                """, BigDecimal.class, userId, settlementDate);
        return scale(volume);
    }

    protected Long createBinaryCommission(
            Long userId,
            Long leftUserId,
            Long rightUserId,
            String orderNo,
            BigDecimal leftVolume,
            BigDecimal rightVolume,
            BigDecimal matchedVolume,
            BigDecimal amountUsdt,
            BinaryRule rule) {
        jdbcTemplate.update("""
                INSERT INTO nx_commission_event (
                  user_id, commission_type, source_user_id, source_user_name, layer_no,
                  order_no, order_amount_usd, amount_usdt, amount_nex, currency,
                  status, unlock_at, remark, created_at, updated_at, is_deleted
                ) VALUES (?, 'BINARY', NULL, ?, 0, ?, ?, ?, 0, 'USDT', ?, ?, ?, NOW(), NOW(), 0)
                """,
                userId,
                "Binary " + leftUserId + "/" + rightUserId,
                orderNo,
                matchedVolume,
                amountUsdt,
                STATUS_PENDING,
                LocalDateTime.now().plusDays(rule.cooldownDays()),
                "Binary daily collision left=" + leftVolume + ", right=" + rightVolume);
        return findBinaryCommissionId(userId, orderNo);
    }

    protected void createBinarySettlement(
            Long userId,
            LocalDate settlementDate,
            Long leftUserId,
            Long rightUserId,
            BigDecimal leftVolume,
            BigDecimal rightVolume,
            BigDecimal matchedVolume,
            BigDecimal amountUsdt,
            BigDecimal dailyCapUsdt,
            Long commissionEventId) {
        jdbcTemplate.update("""
                INSERT INTO nx_binary_commission_settlement (
                  user_id, settlement_date, left_user_id, right_user_id,
                  left_volume, right_volume, matched_volume, amount_usdt, daily_cap_usdt,
                  commission_event_id, status, created_at, updated_at, is_deleted
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CREATED', NOW(), NOW(), 0)
                """,
                userId,
                settlementDate,
                leftUserId,
                rightUserId,
                leftVolume,
                rightVolume,
                matchedVolume,
                amountUsdt,
                dailyCapUsdt,
                commissionEventId);
    }

    private Long findBinaryCommissionId(Long userId, String orderNo) {
        Long id = jdbcTemplate.queryForObject("""
                SELECT id
                  FROM nx_commission_event
                 WHERE user_id = ?
                   AND commission_type = 'BINARY'
                   AND order_no = ?
                   AND layer_no = 0
                   AND is_deleted = 0
                 LIMIT 1
                """, Long.class, userId, orderNo);
        if (id == null) {
            throw new BizException("Binary commission event was not created");
        }
        return id;
    }

    private Map<String, Object> mapSettlementSummary(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", rs.getLong("id"));
        row.put("userId", rs.getLong("user_id"));
        row.put("settlementDate", rs.getDate("settlement_date").toLocalDate());
        row.put("leftUserId", rs.getLong("left_user_id"));
        row.put("rightUserId", rs.getLong("right_user_id"));
        row.put("leftVolume", rs.getBigDecimal("left_volume"));
        row.put("rightVolume", rs.getBigDecimal("right_volume"));
        row.put("matchedVolume", rs.getBigDecimal("matched_volume"));
        row.put("amountUsdt", rs.getBigDecimal("amount_usdt"));
        row.put("dailyCapUsdt", rs.getBigDecimal("daily_cap_usdt"));
        row.put("commissionEventId", rs.getLong("commission_event_id"));
        row.put("status", rs.getString("status"));
        row.put("createdAt", rs.getTimestamp("created_at").toLocalDateTime());
        return row;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1) {
            return 100;
        }
        return Math.min(limit, MAX_SETTLEMENT_LIMIT);
    }

    private BigDecimal cap(BigDecimal amount, BigDecimal dailyCap) {
        BigDecimal scaled = scale(amount);
        if (dailyCap == null || dailyCap.compareTo(BigDecimal.ZERO) <= 0) {
            return scaled;
        }
        return scaled.min(scale(dailyCap));
    }

    private BigDecimal scale(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(6, RoundingMode.HALF_UP);
    }

    private BigDecimal positive(BigDecimal value) {
        return value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO.setScale(6, RoundingMode.HALF_UP) : scale(value);
    }

    private String settlementOrderNo(LocalDate settlementDate) {
        return "BINARY-" + settlementDate;
    }

    protected record BinaryRule(BigDecimal usdtRate, BigDecimal dailyCapUsdt, int cooldownDays) {
    }
}

package ffdd.opsconsole.risk.infrastructure;

import ffdd.opsconsole.risk.domain.RiskCaseView;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JdbcRiskOpsRepository implements RiskOpsRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcRiskOpsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalCases", count("SELECT COUNT(*) FROM nx_risk_decision WHERE is_deleted = 0"));
        overview.put("manualReview", count("""
                SELECT COUNT(*) FROM nx_risk_decision
                 WHERE is_deleted = 0 AND UPPER(COALESCE(decision, 'REVIEW')) IN ('REVIEW', 'MANUAL_REVIEW', 'PENDING_REVIEW')
                """));
        overview.put("blocked", count("""
                SELECT COUNT(*) FROM nx_risk_decision
                 WHERE is_deleted = 0 AND UPPER(COALESCE(decision, '')) IN ('BLOCK', 'REJECT', 'DENY')
                """));
        overview.put("highRisk", count("SELECT COUNT(*) FROM nx_risk_decision WHERE is_deleted = 0 AND COALESCE(risk_score, 0) >= 80"));
        return overview;
    }

    @Override
    public List<RiskCaseView> search(Long userId, String status, String decision, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(baseSql());
        sql.append(" WHERE is_deleted = 0 ");
        if (userId != null) {
            sql.append(" AND user_id = ? ");
            args.add(userId);
        }
        if (StringUtils.hasText(decision)) {
            sql.append(" AND UPPER(COALESCE(decision, 'REVIEW')) = ? ");
            args.add(decision.trim().toUpperCase());
        }
        if (StringUtils.hasText(status)) {
            if ("OPEN".equalsIgnoreCase(status) || "REVIEWING".equalsIgnoreCase(status)) {
                sql.append(" AND reviewed_at IS NULL ");
            } else if ("FINALIZED".equalsIgnoreCase(status) || "CLOSED".equalsIgnoreCase(status)) {
                sql.append(" AND reviewed_at IS NOT NULL ");
            }
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? ");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapCase, args.toArray());
    }

    @Override
    public Optional<RiskCaseView> findByCaseNo(String caseNo) {
        List<RiskCaseView> rows = jdbcTemplate.query(baseSql() + """
                 WHERE decision_no = ? AND is_deleted = 0
                 LIMIT 1
                """, this::mapCase, caseNo);
        return rows.stream().findFirst();
    }

    @Override
    public void updateDecision(String caseNo, String decision, String reason, String operator) {
        jdbcTemplate.update("""
                UPDATE nx_risk_decision
                   SET decision = ?,
                       reason = ?,
                       reviewed_by = ?,
                       reviewed_at = NOW(),
                       updated_at = NOW()
                 WHERE decision_no = ? AND is_deleted = 0
                """, decision, reason, operator, caseNo);
    }

    @Override
    public void recordSignal(String signalNo, Long userId, String signalType, String severity, String evidence, String operator) {
        jdbcTemplate.update("""
                INSERT INTO nx_risk_signal (
                    signal_no, user_id, signal_type, severity, evidence, created_by, created_at, updated_at, is_deleted
                ) VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW(), 0)
                """, signalNo, userId, signalType, severity, evidence, operator);
    }

    private String baseSql() {
        return """
                SELECT decision_no,
                       user_id,
                       biz_type,
                       biz_no,
                       region,
                       user_level,
                       COALESCE(decision, 'REVIEW') AS decision,
                       reason,
                       risk_score,
                       rule_codes,
                       CASE WHEN reviewed_at IS NULL THEN 'REVIEWING' ELSE 'FINALIZED' END AS status,
                       reviewed_by,
                       reviewed_at,
                       created_at
                  FROM nx_risk_decision
                """;
    }

    private RiskCaseView mapCase(ResultSet rs, int rowNum) throws SQLException {
        return new RiskCaseView(
                rs.getString("decision_no"),
                rs.getLong("user_id"),
                rs.getString("biz_type"),
                rs.getString("biz_no"),
                rs.getString("region"),
                rs.getString("user_level"),
                rs.getString("decision"),
                rs.getString("reason"),
                rs.getInt("risk_score"),
                rs.getString("rule_codes"),
                rs.getString("status"),
                rs.getString("reviewed_by"),
                time(rs.getTimestamp("reviewed_at")),
                time(rs.getTimestamp("created_at")));
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private LocalDateTime time(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}

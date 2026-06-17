package ffdd.opsconsole.bi.infrastructure;

import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.bi.domain.BiReportView;
import jakarta.annotation.PostConstruct;
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
public class JdbcBiReportRepository implements BiReportRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcBiReportRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @PostConstruct
    void ensureSchema() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS nx_admin_fourth_batch_report (
                  id BIGINT PRIMARY KEY AUTO_INCREMENT,
                  module_code VARCHAR(16) NOT NULL,
                  report_id VARCHAR(64) NOT NULL,
                  report_name VARCHAR(128) NOT NULL,
                  report_type VARCHAR(64) NOT NULL,
                  cycle VARCHAR(32) NOT NULL,
                  file_format VARCHAR(16) NOT NULL,
                  scope_text VARCHAR(255) NOT NULL,
                  field_text VARCHAR(255) NOT NULL,
                  row_count BIGINT NOT NULL DEFAULT 0,
                  contains_pii TINYINT NOT NULL DEFAULT 0,
                  masking_policy VARCHAR(32) NOT NULL,
                  status VARCHAR(32) NOT NULL,
                  note VARCHAR(255) DEFAULT NULL,
                  last_action VARCHAR(32) DEFAULT NULL,
                  last_action_at DATETIME DEFAULT NULL,
                  reason VARCHAR(255) DEFAULT NULL,
                  created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
                  is_deleted TINYINT NOT NULL DEFAULT 0,
                  UNIQUE KEY uk_fourth_report (module_code, report_id),
                  KEY idx_fourth_report_module_status (module_code, status)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
                """);
        seedDefaults();
    }

    @Override
    public Map<String, Object> overview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("totalReports", count("SELECT COUNT(*) FROM nx_admin_fourth_batch_report WHERE module_code = 'L5' AND is_deleted = 0"));
        overview.put("sensitiveReports", count("""
                SELECT COUNT(*) FROM nx_admin_fourth_batch_report
                 WHERE module_code = 'L5' AND contains_pii = 1 AND is_deleted = 0
                """));
        overview.put("pendingConfirm", count("""
                SELECT COUNT(*) FROM nx_admin_fourth_batch_report
                 WHERE module_code = 'L5' AND status IN ('PENDING_CONFIRM', 'PENDING_SPLIT_CONFIRM') AND is_deleted = 0
                """));
        overview.put("readyReports", count("""
                SELECT COUNT(*) FROM nx_admin_fourth_batch_report
                 WHERE module_code = 'L5' AND status = 'READY' AND is_deleted = 0
                """));
        return overview;
    }

    @Override
    public List<BiReportView> reports(String type, String status, int limit) {
        List<Object> args = new ArrayList<>();
        StringBuilder sql = new StringBuilder(baseSql());
        sql.append(" WHERE module_code = 'L5' AND is_deleted = 0 ");
        if (StringUtils.hasText(type)) {
            sql.append(" AND report_type = ? ");
            args.add(type.trim());
        }
        if (StringUtils.hasText(status)) {
            sql.append(" AND status = ? ");
            args.add(status.trim().toUpperCase());
        }
        sql.append("""
                 ORDER BY FIELD(status, 'PENDING_SPLIT_CONFIRM', 'PENDING_CONFIRM', 'GENERATING', 'READY', 'EXPIRED', 'FAILED'), updated_at DESC, id DESC
                 LIMIT ?
                """);
        args.add(limit);
        return jdbcTemplate.query(sql.toString(), this::mapReport, args.toArray());
    }

    @Override
    public Optional<BiReportView> findReport(String reportId) {
        List<BiReportView> rows = jdbcTemplate.query(baseSql() + """
                 WHERE module_code = 'L5' AND report_id = ? AND is_deleted = 0
                 LIMIT 1
                """, this::mapReport, reportId);
        return rows.stream().findFirst();
    }

    @Override
    public void updateAction(String reportId, String action, String nextStatus, String reason) {
        jdbcTemplate.update("""
                UPDATE nx_admin_fourth_batch_report
                   SET status = ?, last_action = ?, last_action_at = NOW(), reason = ?, updated_at = NOW()
                 WHERE module_code = 'L5' AND report_id = ? AND is_deleted = 0
                """, nextStatus, action, reason, reportId);
    }

    private String baseSql() {
        return """
                SELECT report_id,
                       report_name,
                       report_type,
                       cycle,
                       file_format,
                       scope_text,
                       field_text,
                       row_count,
                       contains_pii,
                       masking_policy,
                       status,
                       note,
                       last_action,
                       last_action_at,
                       reason
                  FROM nx_admin_fourth_batch_report
                """;
    }

    private void seedDefaults() {
        insertReport("EXP-2214", "Bill CSV", "DetailExport", "OnDemand", "CSV", "2026-05 full bills", "8 bill types with PII masked", 1284200, true, "masked", "PENDING_CONFIRM", "PII masked by default");
        insertReport("EXP-2213", "Team Tree Detail", "OpsReport", "OnDemand", "XLSX", "W17-W22 teams", "team structure and commission", 3180000, true, "partial", "PENDING_SPLIT_CONFIRM", "split confirm required");
        insertReport("EXP-2211", "Finance Aggregate", "FinanceReport", "Monthly", "XLSX", "2026-05 aggregate", "amount and ratio fields", 318, false, "none", "READY", "downloadable");
        insertReport("REG-AML-Q2", "AML Package", "RegulatoryReport", "Quarterly", "PDF", "2026-Q2", "KYC AML sanctions decisions", 6800, true, "masked", "GENERATING", "regulatory package");
        insertReport("REG-GEO-Q2", "Cross Border Data List", "RegulatoryReport", "Quarterly", "PDF", "2026-Q2 jurisdictions", "jurisdiction field masking audit", 240, true, "masked", "READY", "new version available");
    }

    private void insertReport(
            String id,
            String name,
            String type,
            String cycle,
            String format,
            String scope,
            String fields,
            long rows,
            boolean pii,
            String masking,
            String status,
            String note) {
        jdbcTemplate.update("""
                INSERT IGNORE INTO nx_admin_fourth_batch_report (
                  module_code, report_id, report_name, report_type, cycle, file_format,
                  scope_text, field_text, row_count, contains_pii, masking_policy, status, note
                ) VALUES ('L5', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id, name, type, cycle, format, scope, fields, rows, pii ? 1 : 0, masking, status, note);
    }

    private BiReportView mapReport(ResultSet rs, int rowNum) throws SQLException {
        return new BiReportView(
                rs.getString("report_id"),
                rs.getString("report_name"),
                rs.getString("report_type"),
                rs.getString("cycle"),
                rs.getString("file_format"),
                rs.getString("scope_text"),
                rs.getString("field_text"),
                rs.getLong("row_count"),
                rs.getInt("contains_pii") == 1,
                rs.getString("masking_policy"),
                rs.getString("status"),
                rs.getString("note"),
                rs.getString("last_action"),
                time(rs.getTimestamp("last_action_at")),
                rs.getString("reason"));
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        return value == null ? 0L : value;
    }

    private LocalDateTime time(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}

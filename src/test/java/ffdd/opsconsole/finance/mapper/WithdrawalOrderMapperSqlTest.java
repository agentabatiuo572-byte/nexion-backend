package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.apache.ibatis.annotations.Select;
import org.junit.jupiter.api.Test;

class WithdrawalOrderMapperSqlTest {
    @Test
    void findByWithdrawalNoAnnotationEscapesXmlUnsafeComparisonOperators() throws Exception {
        String sql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod("findByWithdrawalNo", String.class)
                .getAnnotation(Select.class)
                .value());
        String runtimeSql = sql.replace("&lt;", "<");

        assertThat(sql)
                .contains("LENGTH(u.phone) &lt; 7")
                .contains("w2.created_at &lt;= w.created_at")
                .doesNotContain("LENGTH(u.phone) < 7")
                .doesNotContain("w2.created_at <= w.created_at");
        assertThat(runtimeSql)
                .contains("LENGTH(u.phone) < 7")
                .contains("w2.created_at <= w.created_at");
    }

    @Test
    void pageSqlSupportsAmountUpperBoundFilter() throws Exception {
        String countSql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod(
                        "countPage",
                        String.class,
                        Long.class,
                        String.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        Integer.class,
                        String.class)
                .getAnnotation(Select.class)
                .value());
        String pageSql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod(
                        "page",
                        String.class,
                        Long.class,
                        String.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        Integer.class,
                        String.class,
                        String.class,
                        String.class,
                        int.class,
                        int.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(countSql).contains("w.amount &lt;= #{maxAmount}");
        assertThat(pageSql).contains("w.amount &lt;= #{maxAmount}");
    }

    @Test
    void withdrawalQueriesExposeK3RiskReasonForD2() throws Exception {
        String pageSql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod(
                        "page",
                        String.class,
                        Long.class,
                        String.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        Integer.class,
                        String.class,
                        String.class,
                        String.class,
                        int.class,
                        int.class)
                .getAnnotation(Select.class)
                .value());
        String detailSql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod("findByWithdrawalNo", String.class)
                .getAnnotation(Select.class)
                .value());

        assertThat(pageSql)
                .contains("AS riskReason")
                .contains("rd2.reason")
                .contains("hit.hit_reasons")
                .contains("rd2.biz_no = w.withdrawal_no")
                .contains("hit.withdrawal_no = w.withdrawal_no")
                .doesNotContain("SEED")
                .doesNotContain("REPLACE(w.withdrawal_no");
        assertThat(detailSql)
                .contains("AS riskReason")
                .contains("rd2.reason")
                .contains("hit.hit_reasons")
                .contains("rd2.biz_no = w.withdrawal_no")
                .contains("hit.withdrawal_no = w.withdrawal_no")
                .doesNotContain("SEED")
                .doesNotContain("REPLACE(w.withdrawal_no");
    }

    @Test
    void withdrawalQueriesUseOnlyFreshCurrentK4EffectiveRiskScore() throws Exception {
        String countSql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod(
                        "countPage",
                        String.class,
                        Long.class,
                        String.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        Integer.class,
                        String.class)
                .getAnnotation(Select.class)
                .value());
        String pageSql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod(
                        "page",
                        String.class,
                        Long.class,
                        String.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        Integer.class,
                        String.class,
                        String.class,
                        String.class,
                        int.class,
                        int.class)
                .getAnnotation(Select.class)
                .value());
        String detailSql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod("findByWithdrawalNo", String.class)
                .getAnnotation(Select.class)
                .value());
        String normalizedCountSql = countSql.replaceAll("\\s+", " ");
        String normalizedPageSql = pageSql.replaceAll("\\s+", " ");
        String normalizedDetailSql = detailSql.replaceAll("\\s+", " ");

        assertThat(countSql)
                .contains("LEFT JOIN nx_admin_risk_score_user k4")
                .contains("LEFT JOIN nx_admin_risk_score_override k4o")
                .contains("k4.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
                .contains("nx_admin_risk_score_model k4m")
                .contains("k4m.state = 'active'");
        assertThat(normalizedCountSql)
                .contains("COALESCE(k4o.override_score, k4.model_score)")
                .doesNotContain("rd.risk_score")
                .doesNotContain("rd2.risk_score");
        assertThat(pageSql)
                .contains("LEFT JOIN nx_admin_risk_score_user k4")
                .contains("LEFT JOIN nx_admin_risk_score_override k4o")
                .contains("k4.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
                .contains("nx_admin_risk_score_model k4m");
        assertThat(normalizedPageSql)
                .contains("COALESCE(k4o.override_score, k4.model_score) AS riskScore")
                .contains("k4m.auto_escalate_score THEN 0")
                .contains("k4m.band_high_min THEN 1")
                .contains("k4m.band_low_max THEN 2")
                .doesNotContain("rd.risk_score")
                .doesNotContain("rd2.risk_score");
        assertThat(detailSql)
                .contains("LEFT JOIN nx_admin_risk_score_user k4")
                .contains("LEFT JOIN nx_admin_risk_score_override k4o")
                .contains("k4.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
                .contains("nx_admin_risk_score_model k4m");
        assertThat(normalizedDetailSql)
                .contains("COALESCE(k4o.override_score, k4.model_score) AS riskScore")
                .doesNotContain("rd.risk_score")
                .doesNotContain("rd2.risk_score");
    }

    @Test
    void freezePendingByUserIdIncludesFreshPendingWithdrawals() throws Exception {
        String sql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod("freezePendingByUserId", Long.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class)
                .value());

        assertThat(sql)
                .contains("'PENDING'")
                .contains("'SUBMITTED'")
                .contains("'REVIEWING'");
    }

    @Test
    void freezePendingByUserIdCapturesPreviousStatusBeforeMySqlChangesStatus() throws Exception {
        String sql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod("freezePendingByUserId", Long.class, String.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class)
                .value());
        String normalized = sql.replaceAll("\\s+", " ").trim();

        assertThat(normalized.indexOf("c2_previous_status = status"))
                .isLessThan(normalized.indexOf("status = 'FROZEN'"));
    }

    @Test
    void defaultQueueOrderingUsesCurrentK4PriorityBeforePagination() throws Exception {
        String pageSql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod(
                        "page",
                        String.class,
                        Long.class,
                        String.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        Integer.class,
                        String.class,
                        String.class,
                        String.class,
                        int.class,
                        int.class)
                .getAnnotation(Select.class)
                .value());
        String normalized = pageSql.replaceAll("\\s+", " ").trim();

        assertThat(pageSql).contains("END AS routingPriority");
        assertThat(normalized)
                .contains("k4m.auto_escalate_score THEN 0")
                .contains("k4m.band_high_min THEN 1")
                .contains("k4m.band_low_max THEN 2")
                .contains("LIMIT #{pageSize} OFFSET #{offset}");
    }

    @Test
    void expiredLifecycleCasWritesTheDecidedStatusAndFailureReason() throws Exception {
        String sql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod("releaseExpiredLifecycle", String.class, String.class,
                        String.class, String.class, java.time.LocalDateTime.class)
                .getAnnotation(org.apache.ibatis.annotations.Update.class)
                .value());

        assertThat(sql)
                .contains("SET status = #{newStatus}")
                .contains("failure_reason = #{failureReason}")
                .contains("d2_previous_status = 'REVIEW_PASSED'")
                .contains("d2_lifecycle_owner = 'H1_PHASE_COOLDOWN'");
    }
}

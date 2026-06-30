package ffdd.opsconsole.finance.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.apache.ibatis.annotations.Insert;
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
    void seedWithdrawalInsertDoesNotEmitExtraClosingParenthesisBeforeValues() throws Exception {
        String sql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod(
                        "insertD2SeedWithdrawal",
                        String.class,
                        Long.class,
                        String.class,
                        String.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        String.class,
                        String.class,
                        String.class,
                        boolean.class,
                        boolean.class,
                        boolean.class,
                        String.class,
                        int.class,
                        Integer.class,
                        String.class,
                        int.class)
                .getAnnotation(Insert.class)
                .value());

        assertThat(sql).doesNotContain(")\n            ) VALUES");
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
                        Integer.class)
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
                .contains("REPLACE(w.withdrawal_no, 'D2-SEED-', '')")
                .contains("hit.withdrawal_no = REPLACE(w.withdrawal_no, 'D2-SEED-', '')");
        assertThat(detailSql)
                .contains("AS riskReason")
                .contains("rd2.reason")
                .contains("hit.hit_reasons")
                .contains("REPLACE(w.withdrawal_no, 'D2-SEED-', '')")
                .contains("hit.withdrawal_no = REPLACE(w.withdrawal_no, 'D2-SEED-', '')");
    }

    @Test
    void withdrawalQueriesReadK4EffectiveRiskScoreBeforeLegacyDecisionScore() throws Exception {
        String countSql = String.join("\n", WithdrawalOrderMapper.class
                .getMethod(
                        "countPage",
                        String.class,
                        Long.class,
                        String.class,
                        BigDecimal.class,
                        BigDecimal.class,
                        Integer.class)
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
                .contains("LEFT JOIN nx_admin_risk_score_override k4o");
        assertThat(normalizedCountSql)
                .contains("COALESCE( k4o.override_score, k4.model_score, rd.risk_score,");
        assertThat(pageSql)
                .contains("LEFT JOIN nx_admin_risk_score_user k4")
                .contains("LEFT JOIN nx_admin_risk_score_override k4o");
        assertThat(normalizedPageSql)
                .contains("COALESCE( k4o.override_score, k4.model_score, rd.risk_score,");
        assertThat(detailSql)
                .contains("LEFT JOIN nx_admin_risk_score_user k4")
                .contains("LEFT JOIN nx_admin_risk_score_override k4o");
        assertThat(normalizedDetailSql)
                .contains("COALESCE( k4o.override_score, k4.model_score, rd.risk_score,");
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
}

package ffdd.opsconsole.treasury.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.scripting.xmltags.XMLLanguageDriver;
import org.apache.ibatis.session.Configuration;
import org.junit.jupiter.api.Test;

class TreasuryLedgerMapperSqlContractTest {
    @Test
    void reconciliationGapIncludesWalletOnlyAndLedgerOnlyUsers() throws Exception {
        Method method = TreasuryLedgerMapper.class.getMethod("walletLedgerReconciliationGapUsdt");
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("UNION")
                .contains("FROM nx_user_wallet")
                .contains("FROM nx_wallet_ledger")
                .contains("COALESCE(w.usdt_available, 0)")
                .contains("COALESCE(latest.balance_after, 0)")
                .doesNotContain("'PENDING'");
    }

    @Test
    void ledgerDeepLinkUsesExactBusinessNumberPredicate() throws Exception {
        Method count = TreasuryLedgerMapper.class.getMethod(
                "countLedgerBills", String.class, Long.class, String.class, String.class,
                String.class, LocalDateTime.class, LocalDateTime.class);
        Method page = TreasuryLedgerMapper.class.getMethod(
                "pageLedgerBills", String.class, Long.class, String.class, String.class,
                String.class, LocalDateTime.class, LocalDateTime.class, int.class, int.class);

        String countSql = String.join("\n", count.getAnnotation(Select.class).value());
        String pageSql = String.join("\n", page.getAnnotation(Select.class).value());
        assertThat(countSql).contains("AND l.biz_no = #{bizNo}", "l.created_at", "UPPER(l.status)");
        assertThat(pageSql).contains("AND l.biz_no = #{bizNo}", "l.created_at", "UPPER(l.status)");
    }

    @Test
    void d4CountAndPageRenderTheSameCanonicalCategoryAndScopePredicates() throws Exception {
        Method count = TreasuryLedgerMapper.class.getMethod(
                "countLedgerBills", String.class, Long.class, String.class, String.class,
                String.class, LocalDateTime.class, LocalDateTime.class);
        Method page = TreasuryLedgerMapper.class.getMethod(
                "pageLedgerBills", String.class, Long.class, String.class, String.class,
                String.class, LocalDateTime.class, LocalDateTime.class, int.class, int.class);
        Configuration configuration = new Configuration();
        XMLLanguageDriver driver = new XMLLanguageDriver();
        var countSource = driver.createSqlSource(configuration,
                String.join("\n", count.getAnnotation(Select.class).value()), Map.class);
        var pageSource = driver.createSqlSource(configuration,
                String.join("\n", page.getAnnotation(Select.class).value()), Map.class);

        for (String type : List.of("swap", "topup", "withdraw", "earning", "commission", "refund", "bonus", "purchase")) {
            Map<String, Object> parameters = Map.of(
                    "type", type, "userId", 10001L, "keyword", "task", "bizNo", "TASK-1", "status", "SUCCESS",
                    "from", LocalDateTime.parse("2026-10-01T00:00:00"),
                    "to", LocalDateTime.parse("2026-10-05T00:00:00"), "pageSize", 20, "offset", 0);
            String countSql = countSource.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
            String pageSql = pageSource.getBoundSql(parameters).getSql().replaceAll("\\s+", " ").trim();
            String countWhere = countSql.substring(countSql.indexOf("WHERE l.is_deleted"));
            String pageWhere = pageSql.substring(pageSql.indexOf("WHERE l.is_deleted"), pageSql.indexOf(" ORDER BY"));

            assertThat(pageWhere).as("count/page scope for %s", type).isEqualTo(countWhere);
            assertThat(countWhere)
                    .contains("WHEN UPPER(TRIM(l.biz_type)) = 'ORDER_PURCHASE' AND UPPER(TRIM(l.direction)) IN ('OUT', 'DEBIT') THEN 'purchase'")
                    .contains("WHEN UPPER(TRIM(l.biz_type)) = 'COMPUTE_TASK_REWARD' THEN 'earning'")
                    .contains("WHEN UPPER(TRIM(l.biz_type)) = 'DAILY_CHECK_IN' THEN 'bonus'")
                    .contains("AND l.user_id = ?", "AND l.biz_no = ?", "AND UPPER(l.status) = ?",
                            "AND l.created_at >= ?", "AND l.created_at < ?")
                    .doesNotContain("l.direction =");
            assertThat(countWhere.indexOf("= 'COMPUTE_TASK_REWARD'"))
                    .isLessThan(countWhere.indexOf("LIKE '%REWARD%'"));
            assertThat(countWhere.indexOf("= 'DAILY_CHECK_IN'"))
                    .isLessThan(countWhere.indexOf("LIKE '%REWARD%'"));
            assertThat(countWhere.indexOf("= 'ORDER_PURCHASE'"))
                    .isLessThan(countWhere.indexOf("ELSE 'earning'"));
        }

        Map<String, Object> rawType = Map.of("type", "COMPUTE_TASK_REWARD", "pageSize", 20, "offset", 0);
        assertThat(countSource.getBoundSql(rawType).getSql())
                .contains("AND UPPER(l.biz_type) = UPPER(?)").doesNotContain("CASE");
        assertThat(pageSource.getBoundSql(rawType).getSql())
                .contains("AND UPPER(l.biz_type) = UPPER(?)").doesNotContain("CASE");
    }

    @Test
    void d3WithdrawalLiabilityAndMaturityUseTheD2CanonicalOutstandingLifecycle() throws Exception {
        Method queue = TreasuryLedgerMapper.class.getMethod("sumActiveWithdrawalQueueUsdt");
        Method maturity = TreasuryLedgerMapper.class.getMethod(
                "maturityBuckets", LocalDateTime.class, LocalDateTime.class, int.class, String.class);
        String queueSql = String.join("\n", queue.getAnnotation(Select.class).value());
        String maturitySql = String.join("\n", maturity.getAnnotation(Select.class).value());

        assertThat(queueSql)
                .contains("'SUBMITTED'", "'REVIEW_PENDING'", "'EXTENDED_HOLD'", "'REVIEW_PASSED'",
                        "'PROCESSING'", "'SENT'", "'FROZEN'", "'TX_ORPHANED'");
        assertThat(maturitySql)
                .contains("d2_hold_until")
                .contains("DATE_ADD(created_at, INTERVAL #{withdrawCooldownDays} DAY)")
                .contains("#{interestMode} = 'AT_MATURITY'")
                .contains("#{interestMode} = 'LINEAR'")
                .doesNotContain("next_broadcast_at");
    }

    @Test
    void d3CategoryEightComesFromTheDedicatedLegacyLockLedger() throws Exception {
        Method method = TreasuryLedgerMapper.class.getMethod("legacyLockOtherLiabilityUsd");
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("nx_treasury_legacy_lock_liability")
                .contains("principal_usdt + accrued_interest_usdt")
                .doesNotContain("nx_user_wallet", "nex_available");
    }

    @Test
    void vietQrReserveAndCategoryNineUseTheSameReceiptFactsWithDifferentLifecycleCuts() throws Exception {
        String liabilitySql = String.join("\n", TreasuryLedgerMapper.class
                .getMethod("pendingUnverifiedDepositUsdt").getAnnotation(Select.class).value());
        String reserveSql = String.join("\n", TreasuryLedgerMapper.class
                .getMethod("vietQrHeldReserveUsdt").getAnnotation(Select.class).value());

        assertThat(liabilitySql)
                .contains("FROM nx_vietqr_reconciliation")
                .contains("status = 'OPEN'")
                .contains("received_vnd > 0")
                .doesNotContain("view_type");
        assertThat(reserveSql)
                .contains("FROM nx_vietqr_reconciliation")
                .contains("'OPEN', 'CREDITED', 'RETURN_PENDING'")
                .doesNotContain("'RETURNED'");
        assertThat(liabilitySql).contains("received_vnd / NULLIF(locked_fx_rate_vnd_per_usdt, 0)");
        assertThat(reserveSql).contains("received_vnd / NULLIF(locked_fx_rate_vnd_per_usdt, 0)");
    }

    @Test
    void netReserveFlowComesFromCanonicalTreasuryReserveLedger() throws Exception {
        Method method = TreasuryLedgerMapper.class.getMethod(
                "sumNetUsdtFlowBetween", LocalDateTime.class, LocalDateTime.class);
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("FROM nx_treasury_reserve_ledger")
                .contains("amount_usd")
                .contains("status = 'CONFIRMED'")
                .doesNotContain("FROM nx_wallet_ledger");
    }

    @Test
    void b5K4SnapshotUsesTheActiveModelsNonDefaultThresholdsAndFreshScoresOnly() throws Exception {
        Method method = TreasuryLedgerMapper.class.getMethod("currentK4RiskScoreSnapshot");
        String sql = String.join("\n", method.getAnnotation(Select.class).value());

        assertThat(sql)
                .contains("m.band_low_max AS bandLowMax")
                .contains("m.band_high_min AS bandHighMin")
                .contains("m.auto_escalate_score AS autoEscalateScore")
                .contains("COALESCE(o.override_score,s.model_score) >= m.auto_escalate_score")
                .contains("s.as_of >= DATE_SUB(NOW(), INTERVAL 1 DAY)")
                .contains("stale.as_of IS NULL")
                .contains("stale.as_of < DATE_SUB(NOW(), INTERVAL 1 DAY)")
                .contains("state='active'")
                .doesNotContain("COALESCE(o.override_score,s.model_score) >= 70")
                .doesNotContain("COALESCE(o.override_score,s.model_score) < 40");
    }
}

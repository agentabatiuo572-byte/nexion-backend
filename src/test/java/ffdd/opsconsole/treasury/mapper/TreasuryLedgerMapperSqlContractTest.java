package ffdd.opsconsole.treasury.mapper;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Select;
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

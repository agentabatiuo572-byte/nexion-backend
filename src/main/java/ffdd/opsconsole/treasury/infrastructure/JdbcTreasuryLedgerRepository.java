package ffdd.opsconsole.treasury.infrastructure;

import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class JdbcTreasuryLedgerRepository implements TreasuryLedgerRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcTreasuryLedgerRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public long countDeposits(LocalDateTime since, String status) {
        return countSince("nx_deposit_order", since, status);
    }

    @Override
    public long countWithdrawals(LocalDateTime since, String status) {
        return countSince("nx_withdrawal_order", since, status);
    }

    @Override
    public long countExchanges(LocalDateTime since, String status) {
        return countSince("nx_exchange_order", since, status);
    }

    @Override
    public long countLedgers(LocalDateTime since, String direction) {
        if (StringUtils.hasText(direction)) {
            return countLong("""
                    SELECT COUNT(*)
                    FROM nx_wallet_ledger
                    WHERE is_deleted = 0 AND created_at >= ? AND direction = ?
                    """, since, direction);
        }
        return countSince("nx_wallet_ledger", since, null);
    }

    @Override
    public BigDecimal sumUsdtAvailable() {
        return decimal("SELECT COALESCE(SUM(usdt_available), 0) FROM nx_user_wallet WHERE is_deleted = 0");
    }

    @Override
    public BigDecimal sumPendingWithdraw() {
        return decimal("SELECT COALESCE(SUM(pending_withdraw), 0) FROM nx_user_wallet WHERE is_deleted = 0");
    }

    @Override
    public BigDecimal sumNexAvailable() {
        return decimal("SELECT COALESCE(SUM(nex_available), 0) FROM nx_user_wallet WHERE is_deleted = 0");
    }

    @Override
    public BigDecimal sumActiveStakingPrincipalUsdt() {
        return decimal("""
                SELECT COALESCE(SUM(amount_usdt), 0)
                FROM nx_staking_position
                WHERE is_deleted = 0 AND status IN ('ACTIVE', 'LOCKED')
                """);
    }

    @Override
    public BigDecimal sumActiveStakingInterestUsdt() {
        return decimal("""
                SELECT COALESCE(SUM(estimated_interest_usdt), 0)
                FROM nx_staking_position
                WHERE is_deleted = 0 AND status IN ('ACTIVE', 'LOCKED')
                """);
    }

    @Override
    public BigDecimal sumActiveNexLocked() {
        return decimal("""
                SELECT COALESCE(SUM(amount_nex), 0)
                FROM nx_nex_lock_order
                WHERE is_deleted = 0 AND status IN ('ACTIVE', 'LOCKED')
                """);
    }

    @Override
    public BigDecimal sumActiveNexReward() {
        return decimal("""
                SELECT COALESCE(SUM(estimated_reward_nex), 0)
                FROM nx_nex_lock_order
                WHERE is_deleted = 0 AND status IN ('ACTIVE', 'LOCKED')
                """);
    }

    @Override
    public BigDecimal sumActiveWithdrawalQueueUsdt() {
        return decimal("""
                SELECT COALESCE(SUM(amount), 0)
                FROM nx_withdrawal_order
                WHERE is_deleted = 0
                  AND asset = 'USDT'
                  AND status IN ('PENDING', 'REVIEWING', 'PENDING_CHAIN', 'CHAIN_SUBMITTED')
                """);
    }

    @Override
    public long countActiveWithdrawalQueue() {
        return countLong("""
                SELECT COUNT(*)
                FROM nx_withdrawal_order
                WHERE is_deleted = 0
                  AND asset = 'USDT'
                  AND status IN ('PENDING', 'REVIEWING', 'PENDING_CHAIN', 'CHAIN_SUBMITTED')
                """);
    }

    @Override
    public BigDecimal avgActiveWithdrawalQueueRiskScore() {
        return decimal("""
                SELECT COALESCE(AVG(COALESCE(rd.risk_score, 0)), 0)
                FROM nx_withdrawal_order w
                LEFT JOIN nx_risk_decision rd ON rd.id = w.risk_decision_id AND rd.is_deleted = 0
                WHERE w.is_deleted = 0
                  AND w.asset = 'USDT'
                  AND w.status IN ('PENDING', 'REVIEWING', 'PENDING_CHAIN', 'CHAIN_SUBMITTED')
                """);
    }

    @Override
    public BigDecimal sumPendingCommissionUsdt() {
        return decimal("""
                SELECT COALESCE(SUM(amount), 0)
                FROM nx_wallet_ledger
                WHERE is_deleted = 0
                  AND asset = 'USDT'
                  AND direction = 'IN'
                  AND status = 'PENDING'
                  AND biz_type IN ('REFERRAL_COMMISSION', 'COMMISSION', 'TEAM_COMMISSION')
                """);
    }

    @Override
    public BigDecimal sumNetUsdtFlowBetween(LocalDateTime startAt, LocalDateTime endAt) {
        return decimal("""
                SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount ELSE -amount END), 0)
                FROM nx_wallet_ledger
                WHERE is_deleted = 0
                  AND asset = 'USDT'
                  AND status IN ('SUCCESS', 'PENDING')
                  AND created_at >= ?
                  AND created_at < ?
                """, startAt, endAt);
    }

    private long countSince(String table, LocalDateTime since, String status) {
        if (StringUtils.hasText(status)) {
            return countLong("SELECT COUNT(*) FROM " + table + " WHERE is_deleted = 0 AND created_at >= ? AND status = ?", since, status);
        }
        return countLong("SELECT COUNT(*) FROM " + table + " WHERE is_deleted = 0 AND created_at >= ?", since);
    }

    private long countLong(String sql, Object... args) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class, args);
        return value == null ? 0L : value;
    }

    private BigDecimal decimal(String sql, Object... args) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }
}

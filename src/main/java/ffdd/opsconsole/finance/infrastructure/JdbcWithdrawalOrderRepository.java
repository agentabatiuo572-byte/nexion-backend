package ffdd.opsconsole.finance.infrastructure;

import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcWithdrawalOrderRepository implements WithdrawalOrderRepository {
    private final JdbcTemplate jdbcTemplate;

    public JdbcWithdrawalOrderRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<WithdrawalOrderView> findByWithdrawalNo(String withdrawalNo) {
        return jdbcTemplate.query("""
                        SELECT id, user_id, withdrawal_no, asset, chain, amount, fee, target_address,
                               risk_decision_id, chain_tx_hash, status, chain_submitted_at, completed_at,
                               failed_at, failure_reason, chain_broadcast_attempts, next_broadcast_at,
                               last_broadcast_error, broadcast_dead_at, created_at, updated_at
                        FROM nx_withdrawal_order
                        WHERE withdrawal_no = ? AND is_deleted = 0
                        LIMIT 1
                        """,
                (rs, rowNum) -> map(rs),
                withdrawalNo)
                .stream()
                .findFirst();
    }

    @Override
    public void updateStatus(String withdrawalNo, String status, String failureReason) {
        jdbcTemplate.update("""
                UPDATE nx_withdrawal_order
                SET status = ?,
                    failure_reason = COALESCE(?, failure_reason),
                    updated_at = CURRENT_TIMESTAMP
                WHERE withdrawal_no = ? AND is_deleted = 0
                """, status, failureReason, withdrawalNo);
    }

    private WithdrawalOrderView map(ResultSet rs) throws SQLException {
        return new WithdrawalOrderView(
                rs.getLong("id"),
                rs.getLong("user_id"),
                rs.getString("withdrawal_no"),
                rs.getString("asset"),
                rs.getString("chain"),
                rs.getBigDecimal("amount"),
                rs.getBigDecimal("fee"),
                rs.getString("target_address"),
                rs.getObject("risk_decision_id", Long.class),
                rs.getString("chain_tx_hash"),
                rs.getString("status"),
                rs.getTimestamp("chain_submitted_at") == null ? null : rs.getTimestamp("chain_submitted_at").toLocalDateTime(),
                rs.getTimestamp("completed_at") == null ? null : rs.getTimestamp("completed_at").toLocalDateTime(),
                rs.getTimestamp("failed_at") == null ? null : rs.getTimestamp("failed_at").toLocalDateTime(),
                rs.getString("failure_reason"),
                rs.getObject("chain_broadcast_attempts", Integer.class),
                rs.getTimestamp("next_broadcast_at") == null ? null : rs.getTimestamp("next_broadcast_at").toLocalDateTime(),
                rs.getString("last_broadcast_error"),
                rs.getTimestamp("broadcast_dead_at") == null ? null : rs.getTimestamp("broadcast_dead_at").toLocalDateTime(),
                rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toLocalDateTime(),
                rs.getTimestamp("updated_at") == null ? null : rs.getTimestamp("updated_at").toLocalDateTime());
    }
}

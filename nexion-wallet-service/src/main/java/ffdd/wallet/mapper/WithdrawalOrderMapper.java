package ffdd.wallet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.wallet.domain.WithdrawalOrder;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Select;

public interface WithdrawalOrderMapper extends BaseMapper<WithdrawalOrder> {
    @Select("""
            SELECT COALESCE(SUM(amount), 0)
            FROM nx_withdrawal_order
            WHERE is_deleted = 0
              AND asset = 'USDT'
              AND status IN ('PENDING', 'REVIEWING', 'PENDING_CHAIN', 'CHAIN_SUBMITTED')
            """)
    BigDecimal sumActiveQueueUsdt();

    @Select("""
            SELECT COUNT(*)
            FROM nx_withdrawal_order
            WHERE is_deleted = 0
              AND asset = 'USDT'
              AND status IN ('PENDING', 'REVIEWING', 'PENDING_CHAIN', 'CHAIN_SUBMITTED')
            """)
    Long countActiveQueue();

    @Select("""
            SELECT COALESCE(AVG(COALESCE(rd.risk_score, 0)), 0)
            FROM nx_withdrawal_order w
            LEFT JOIN nx_risk_decision rd ON rd.id = w.risk_decision_id AND rd.is_deleted = 0
            WHERE w.is_deleted = 0
              AND w.asset = 'USDT'
              AND w.status IN ('PENDING', 'REVIEWING', 'PENDING_CHAIN', 'CHAIN_SUBMITTED')
            """)
    BigDecimal avgActiveQueueRiskScore();
}

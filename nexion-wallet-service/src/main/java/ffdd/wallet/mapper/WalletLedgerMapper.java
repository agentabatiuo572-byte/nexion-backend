package ffdd.wallet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.wallet.domain.WalletLedger;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface WalletLedgerMapper extends BaseMapper<WalletLedger> {
    @Select("""
            SELECT COALESCE(SUM(CASE WHEN direction = 'IN' THEN amount ELSE -amount END), 0)
            FROM nx_wallet_ledger
            WHERE is_deleted = 0
              AND asset = 'USDT'
              AND status IN ('SUCCESS', 'PENDING')
              AND created_at >= #{startAt}
              AND created_at < #{endAt}
            """)
    BigDecimal sumNetUsdtFlowBetween(@Param("startAt") LocalDateTime startAt, @Param("endAt") LocalDateTime endAt);

    @Select("""
            SELECT COALESCE(SUM(amount), 0)
            FROM nx_wallet_ledger
            WHERE is_deleted = 0
              AND asset = 'USDT'
              AND direction = 'IN'
              AND status = 'PENDING'
              AND biz_type IN ('REFERRAL_COMMISSION', 'COMMISSION', 'TEAM_COMMISSION')
            """)
    BigDecimal sumPendingCommissionUsdt();
}

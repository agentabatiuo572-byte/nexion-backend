package ffdd.wallet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.wallet.domain.StakingPosition;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Select;

public interface StakingPositionMapper extends BaseMapper<StakingPosition> {
    @Select("""
            SELECT COALESCE(SUM(amount_usdt), 0)
            FROM nx_staking_position
            WHERE is_deleted = 0 AND status IN ('ACTIVE', 'LOCKED')
            """)
    BigDecimal sumActivePrincipalUsdt();

    @Select("""
            SELECT COALESCE(SUM(estimated_interest_usdt), 0)
            FROM nx_staking_position
            WHERE is_deleted = 0 AND status IN ('ACTIVE', 'LOCKED')
            """)
    BigDecimal sumActiveInterestUsdt();
}

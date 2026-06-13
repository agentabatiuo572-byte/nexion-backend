package ffdd.wallet.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.wallet.domain.NexLockOrder;
import java.math.BigDecimal;
import org.apache.ibatis.annotations.Select;

public interface NexLockOrderMapper extends BaseMapper<NexLockOrder> {
    @Select("""
            SELECT COALESCE(SUM(amount_nex), 0)
            FROM nx_nex_lock_order
            WHERE is_deleted = 0 AND status IN ('ACTIVE', 'LOCKED')
            """)
    BigDecimal sumActiveAmountNex();

    @Select("""
            SELECT COALESCE(SUM(estimated_reward_nex), 0)
            FROM nx_nex_lock_order
            WHERE is_deleted = 0 AND status IN ('ACTIVE', 'LOCKED')
            """)
    BigDecimal sumActiveRewardNex();
}

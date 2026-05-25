package ffdd.earnings.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.earnings.domain.EarningTickDevice;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EarningTickDeviceMapper extends BaseMapper<EarningTickDevice> {
    @Select("""
            SELECT id,
                   user_id,
                   status,
                   daily_usdt,
                   daily_nex,
                   created_at,
                   updated_at,
                   is_deleted
            FROM nx_user_device
            WHERE is_deleted = 0
              AND status IN ('ONLINE', 'BUSY')
              AND (daily_usdt > 0 OR daily_nex > 0)
            ORDER BY id ASC
            LIMIT #{limit}
            """)
    List<EarningTickDevice> selectTickableDevices(@Param("limit") int limit);
}

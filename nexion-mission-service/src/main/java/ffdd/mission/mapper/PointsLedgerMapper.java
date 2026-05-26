package ffdd.mission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.mission.domain.PointsLedger;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface PointsLedgerMapper extends BaseMapper<PointsLedger> {
    @Select("""
            SELECT COALESCE(SUM(points), 0)
            FROM nx_points_ledger
            WHERE user_id = #{userId}
              AND is_deleted = 0
            """)
    Integer sumPointsByUser(@Param("userId") Long userId);
}

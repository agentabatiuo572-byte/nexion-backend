package ffdd.earnings.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.earnings.domain.EarningSummary;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface EarningSummaryMapper extends BaseMapper<EarningSummary> {
    @Select("""
            SELECT id,
                   user_id,
                   summary_date,
                   usdt_amount,
                   nex_amount,
                   created_at,
                   updated_at,
                   is_deleted
            FROM nx_earning_summary
            WHERE user_id = #{userId}
              AND summary_date BETWEEN #{startDate} AND #{endDate}
              AND is_deleted = 0
            ORDER BY summary_date ASC
            """)
    List<EarningSummary> selectByUserDateRange(
            @Param("userId") Long userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate") LocalDate endDate);

    @Select("""
            SELECT COALESCE(SUM(usdt_amount), 0)
            FROM nx_earning_summary
            WHERE user_id = #{userId}
              AND is_deleted = 0
            """)
    BigDecimal sumLifetimeUsdtByUser(@Param("userId") Long userId);
}

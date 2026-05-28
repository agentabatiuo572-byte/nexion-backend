package ffdd.mission.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.mission.domain.MonthlyChallenge;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

public interface MonthlyChallengeMapper extends BaseMapper<MonthlyChallenge> {
    @Select("""
            SELECT GREATEST(TIMESTAMPDIFF(MONTH, created_at, NOW()), 0)
            FROM nx_user
            WHERE id = #{userId}
              AND is_deleted = 0
            """)
    Integer selectUserMonthsSinceRegistration(@Param("userId") Long userId);
}

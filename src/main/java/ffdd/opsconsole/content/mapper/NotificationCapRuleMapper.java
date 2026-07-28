package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.infrastructure.NotificationCapRuleEntity;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

public interface NotificationCapRuleMapper extends BaseMapper<NotificationCapRuleEntity> {
    @Update("""
            UPDATE nx_notification_cap_rule
            SET cap_label = #{cap},
                last_operator = #{operator},
                updated_at = #{now}
            WHERE tier = #{tier}
              AND cap_label = #{expectedCap}
              AND locked = 0
              AND status = 1
              AND is_deleted = 0
            """)
    int updateIfCurrent(
            @Param("tier") String tier,
            @Param("cap") String cap,
            @Param("expectedCap") String expectedCap,
            @Param("operator") String operator,
            @Param("now") LocalDateTime now);
}

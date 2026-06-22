package ffdd.opsconsole.content.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.content.domain.SupportSlaView;
import ffdd.opsconsole.content.infrastructure.SupportSlaRuleEntity;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface SupportSlaRuleMapper extends BaseMapper<SupportSlaRuleEntity> {
    @Select("""
            SELECT
              category,
              first_response_mins AS firstResponseMins,
              resolution_hours AS resolutionHours,
              queue,
              escalation,
              updated_at AS updatedAt
            FROM nx_support_sla_rule
            WHERE is_deleted=0 AND status=1
            ORDER BY FIELD(category,'withdrawal','deposit','kyc','hardware','account','earnings','genesis','technical','other'), category
            """)
    List<SupportSlaView> listActive();

    @Select("SELECT id FROM nx_support_sla_rule WHERE is_deleted=0 AND category=#{category} LIMIT 1")
    Long findIdByCategory(@Param("category") String category);

    @Update("""
            UPDATE nx_support_sla_rule
               SET first_response_mins=#{firstResponseMins},
                   resolution_hours=#{resolutionHours},
                   queue=#{queue},
                   escalation=#{escalation},
                   status=1,
                   updated_at=#{now}
             WHERE category=#{category} AND is_deleted=0
            """)
    int updateRule(
            @Param("category") String category,
            @Param("firstResponseMins") Integer firstResponseMins,
            @Param("resolutionHours") Integer resolutionHours,
            @Param("queue") String queue,
            @Param("escalation") String escalation,
            @Param("now") LocalDateTime now);
}

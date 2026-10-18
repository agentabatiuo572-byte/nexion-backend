package ffdd.opsconsole.growth.mapper;

import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only legacy calendar evidence; this boundary never publishes a definition. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface WeeklyRegionPolicyMapper {
    record LegacyWindow(String instanceKey, LocalDateTime eligibleFrom, LocalDateTime eligibleUntil) {}

    @Select("""
            SELECT instance_key instanceKey,eligible_from eligibleFrom,eligible_until eligibleUntil
              FROM nx_growth_weekly_definition
             WHERE instance_key REGEXP '^WEEK:[0-9]{4}-W[0-9]{2}$'
               AND eligible_from<=#{now} AND eligible_until>#{now}
             ORDER BY eligible_from DESC LIMIT 2
            """)
    List<LegacyWindow> openLegacy(@Param("now") LocalDateTime now);

}

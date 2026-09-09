package ffdd.opsconsole.growth.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/** One durable source receipt per user, Day One instance, and rendered surface. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface H3DayOnePageObservationReceiptMapper {
    @Insert("""
            INSERT IGNORE INTO nx_growth_day_one_page_observation_receipt
              (user_id,instance_key,surface,created_at,updated_at,is_deleted)
            VALUES (#{userId},#{instanceKey},#{surface},NOW(3),NOW(3),0)
            """)
    int insertIfAbsent(
            @Param("userId") Long userId,
            @Param("instanceKey") String instanceKey,
            @Param("surface") String surface);
}

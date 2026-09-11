package ffdd.opsconsole.growth.mapper;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface H3DayOneBusinessFactReceiptMapper {
    @Insert("""
            INSERT IGNORE INTO nx_growth_day_one_business_fact_receipt
              (user_id,instance_key,event_type,created_at)
            VALUES (#{userId},#{instanceKey},#{eventType},NOW(3))
            """)
    int insertIfAbsent(@Param("userId") Long userId, @Param("instanceKey") String instanceKey,
                       @Param("eventType") String eventType);
}

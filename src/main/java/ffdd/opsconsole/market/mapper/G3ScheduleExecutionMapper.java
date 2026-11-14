package ffdd.opsconsole.market.mapper;

import java.time.LocalDate;
import java.util.Map;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
// Statement-only claim/update mutex; it intentionally exposes no generic entity CRUD surface.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface G3ScheduleExecutionMapper {
    @Insert("""
            INSERT IGNORE INTO nx_g3_schedule_execution(run_date,status,created_at,updated_at)
            VALUES(#{runDate},'PROCESSING',NOW(),NOW())
            """)
    int claimRunDate(@Param("runDate") LocalDate runDate);

    @Update("UPDATE nx_g3_schedule_execution SET status=#{status},updated_at=NOW() WHERE run_date=#{runDate}")
    int mark(@Param("runDate") LocalDate runDate,@Param("status") String status);

    @Select("""
            SELECT run_date AS runDate,status,created_at AS createdAt,updated_at AS updatedAt
            FROM nx_g3_schedule_execution
            ORDER BY run_date DESC,id DESC LIMIT 1
            """)
    Map<String,Object> latest();
}

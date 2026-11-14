package ffdd.opsconsole.market.mapper;

import java.time.LocalDate;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
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
}

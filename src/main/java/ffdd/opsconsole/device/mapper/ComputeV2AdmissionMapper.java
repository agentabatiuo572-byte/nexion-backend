package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** Narrow SQL authority checks used only inside the trusted execution adapter's short transactions. */
@Mapper
public interface ComputeV2AdmissionMapper extends BaseMapper<Object> {
    @Select("SELECT pending_deactivate FROM nx_user_device WHERE id=#{deviceId} AND user_id=#{userId} AND is_deleted=0")
    Integer pendingDeactivate(@Param("deviceId")long deviceId,@Param("userId")long userId);

    @Select("""
            SELECT (SELECT COUNT(*) FROM nx_compute_task WHERE user_id=#{userId} AND user_device_id=#{deviceId}
              AND is_deleted=0 AND source_environment='PRODUCTION' AND status IN ('CLAIMED','RUNNING')
              AND task_no NOT LIKE 'CV2-%')
              +(SELECT COUNT(*) FROM nx_test_cloud_request WHERE owner_id=#{userId} AND device_id=#{deviceId}
              AND state IN ('PROCESSING','UNKNOWN'))
            """)
    int unresolvedLegacy(@Param("userId")long userId,@Param("deviceId")long deviceId);

    @Select("SELECT id,fund_incarnation AS incarnation FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0 AND sandbox=0 FOR UPDATE")
    List<Map<String,Object>> lockWallets(@Param("userId")long userId);

    @Select("""
            SELECT payload FROM nx_compute_v2_event WHERE task_no=#{taskNo} AND deployment_scope='TEST'
            ORDER BY FIELD(kind,'VERIFIED','STARTED','ISSUED','FAILED','FENCED','EXITED') LIMIT 1
            """)
    String retainedPayload(@Param("taskNo")String taskNo);

    @Select("""
            SELECT COUNT(*) FROM nx_compute_v2_event WHERE task_no=#{taskNo} AND generation=#{generation}
              AND deployment_scope='TEST' AND kind='EXITED'
            """)
    int exitCount(@Param("taskNo")String taskNo,@Param("generation")long generation);
}

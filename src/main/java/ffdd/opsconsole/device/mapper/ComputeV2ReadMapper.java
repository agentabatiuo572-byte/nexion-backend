package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only, account/scope-bound projections; callers supply values, never SQL fragments. */
@Mapper
public interface ComputeV2ReadMapper extends BaseMapper<Object> {
    @Select("""
            <script>
            SELECT a.event_id,a.asset,a.amount_micros,a.task_no,a.receipt_no,a.user_device_id
            FROM nx_compute_v2_allocation a
            JOIN nx_compute_v2_settlement s ON s.settlement_id=a.settlement_id AND s.user_id=a.user_id AND s.asset=a.asset
            JOIN nx_compute_v2_event e ON e.event_id=a.event_id AND e.user_id=a.user_id
            WHERE a.user_id=#{userId} AND e.deployment_scope=#{scope} AND a.event_id IN
            <foreach collection="eventIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            </script>
            """)
    List<Map<String,Object>> pendingAllocations(@Param("userId") long userId,@Param("scope") String scope,
            @Param("eventIds") List<String> eventIds);

    @Select("""
            <script>
            SELECT a.task_no,a.settlement_id,a.posted_at_utc,e.payload
            FROM nx_compute_v2_allocation a
            JOIN nx_compute_v2_settlement s ON s.settlement_id=a.settlement_id AND s.user_id=a.user_id
            JOIN nx_compute_v2_event e ON e.event_id=a.event_id AND e.user_id=a.user_id
            WHERE a.user_id=#{userId} AND e.deployment_scope=#{scope} AND a.asset='USDT' AND a.task_no IN
            <foreach collection="taskNos" item="taskNo" open="(" separator="," close=")">#{taskNo}</foreach>
            </script>
            """)
    List<Map<String,Object>> postedTasks(@Param("userId") long userId,@Param("scope") String scope,
            @Param("taskNos") List<String> taskNos);

    @Select("""
            <script>
            SELECT s.settlement_id,s.asset,s.total_micros,s.window_start_utc,s.window_end_utc,s.posted_at_utc,
                   COUNT(*) task_count
            FROM nx_compute_v2_settlement s
            JOIN nx_compute_v2_allocation a ON a.settlement_id=s.settlement_id AND a.user_id=s.user_id
            JOIN nx_compute_v2_event e ON e.event_id=a.event_id AND e.user_id=a.user_id AND e.deployment_scope=#{scope}
            WHERE s.user_id=#{userId}
            <if test="beforeId != null">
              AND (s.posted_at_utc &lt; #{beforePostedAt}
                   OR (s.posted_at_utc=#{beforePostedAt} AND s.settlement_id &lt; #{beforeId}))
            </if>
            GROUP BY s.settlement_id,s.asset,s.total_micros,s.window_start_utc,s.window_end_utc,s.posted_at_utc
            ORDER BY s.posted_at_utc DESC,s.settlement_id DESC LIMIT #{limit}
            </script>
            """)
    List<Map<String,Object>> batches(@Param("userId") long userId,@Param("scope") String scope,
            @Param("beforePostedAt") Object beforePostedAt,@Param("beforeId") String beforeId,@Param("limit") int limit);

    @Select("""
            SELECT s.settlement_id,s.asset,s.total_micros,s.window_start_utc,s.window_end_utc,s.posted_at_utc,
                   COUNT(*) task_count
            FROM nx_compute_v2_settlement s
            JOIN nx_compute_v2_allocation a ON a.settlement_id=s.settlement_id AND a.user_id=s.user_id
            JOIN nx_compute_v2_event e ON e.event_id=a.event_id AND e.user_id=a.user_id AND e.deployment_scope=#{scope}
            WHERE s.user_id=#{userId} AND s.settlement_id=#{batchNo}
            GROUP BY s.settlement_id,s.asset,s.total_micros,s.window_start_utc,s.window_end_utc,s.posted_at_utc
            """)
    List<Map<String,Object>> batch(@Param("userId") long userId,@Param("scope") String scope,@Param("batchNo") String batchNo);

    @Select("""
            SELECT a.task_no,a.receipt_no,a.user_device_id,a.amount_micros,a.completed_at_utc,
                   JSON_UNQUOTE(JSON_EXTRACT(e.payload,'$.task.taskClass')) task_type
            FROM nx_compute_v2_allocation a
            JOIN nx_compute_v2_event e ON e.event_id=a.event_id AND e.user_id=a.user_id AND e.deployment_scope=#{scope}
            WHERE a.user_id=#{userId} AND a.settlement_id=#{batchNo} ORDER BY a.allocation_id LIMIT 201
            """)
    List<Map<String,Object>> allocations(@Param("userId") long userId,@Param("scope") String scope,@Param("batchNo") String batchNo);
}

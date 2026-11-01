package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Read-only, account/scope-bound projections; callers supply values, never SQL fragments. */
@Mapper
public interface ComputeV2ReadMapper extends BaseMapper<Object> {
    record LegacyCompletionRequest(String taskNo,long deviceId,String instanceNo,String state,LocalDateTime updatedAt) { }

    @Select("""
            <script>
            SELECT DISTINCT t.task_no taskNo,t.user_device_id deviceId,d.instance_no instanceNo,r.state,r.updated_at updatedAt
            FROM nx_compute_task t
            JOIN nx_user u ON u.id=t.user_id AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=0
            JOIN nx_user_device d ON d.id=t.user_device_id AND d.user_id=t.user_id
            JOIN nx_test_cloud_request r ON r.task_no=t.task_no AND r.owner_id=t.user_id
              AND r.device_id=t.user_device_id AND r.instance_no=d.instance_no AND r.task_config_id=t.task_config_id
            WHERE t.user_id=#{userId} AND t.source_environment='PRODUCTION' AND t.is_deleted=0
              AND t.status&lt;&gt;'COMPLETED' AND t.completed_at IS NULL AND t.task_no NOT LIKE 'CV2-%'
              AND d.source_environment='PRODUCTION' AND d.run_id='' AND d.is_deleted=0 AND UPPER(d.ownership_status)='OWNED'
              AND r.operation='COMPLETE' AND r.state IN ('UNKNOWN','PROCESSING')
              AND NOT EXISTS (
            """+LegacyZeroCreditResolutionSql.RESOLVED_REQUEST+")"+"""
              AND NOT EXISTS (SELECT 1 FROM nx_compute_receipt receipt
                              WHERE receipt.task_no=t.task_no AND receipt.source_environment='PRODUCTION' AND receipt.is_deleted=0)
              AND t.user_device_id IN
            <foreach collection="deviceIds" item="deviceId" open="(" separator="," close=")">#{deviceId}</foreach>
            </script>
            """)
    List<LegacyCompletionRequest> legacyCompletionRequests(@Param("userId") long userId,@Param("deviceIds") List<Long> deviceIds);

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

package ffdd.opsconsole.device.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Ordinary snapshot reads only; ownership is classified without dropping unknown or stopped-owned rows. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportDeviceReadMapper {
    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("""
        <script>
        SELECT d.id deviceId,d.user_id customerId,d.source_order_no sourceOrderNo,
               d.source_channel sourceChannel,d.device_type deviceType,d.hashrate,
               d.ownership_status ownershipStatus,d.status lifecycleStatus,
               d.activated_at activatedAt,d.deactivated_at deactivatedAt,d.pending_deactivate pendingDeactivate,
               d.source_environment sourceEnvironment,d.run_id runId,
               r.id runtimeId,r.online_status runtimeStatus,r.heartbeat_at heartbeatAt,
               r.paused_reason pausedReason,r.active_task_no activeTaskNo,r.network_reachable networkReachable,
               r.updated_at runtimeUpdatedAt,NOW(6) evaluatedDbAt,
               CASE WHEN """ + DeviceOpsMapper.E5_HEARTBEAT_FRESH + """
                    THEN 1 ELSE 0 END heartbeatFresh
          FROM nx_user_device d
          LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0
         WHERE d.is_deleted=0 AND d.user_id IN
         <foreach collection='customerIds' item='customer' open='(' separator=',' close=')'>#{customer}</foreach>
         ORDER BY d.id
        </script>
        """)
    List<Row> readCurrent(@Param("customerIds") List<Long> customerIds);

    @Options(useCache = false, flushCache = Options.FlushCachePolicy.TRUE)
    @Select("SELECT NOW(6)")
    LocalDateTime currentDbTime();

    record Row(Long deviceId, Long customerId, String sourceOrderNo, String sourceChannel,
        String deviceType, BigDecimal hashrate, String ownershipStatus, String lifecycleStatus,
        LocalDateTime activatedAt, LocalDateTime deactivatedAt, Integer pendingDeactivate,
        String sourceEnvironment, String runId, Long runtimeId, String runtimeStatus, LocalDateTime heartbeatAt,
        String pausedReason, String activeTaskNo, Integer networkReachable, LocalDateTime runtimeUpdatedAt,
        LocalDateTime evaluatedDbAt, Integer heartbeatFresh) { }
}

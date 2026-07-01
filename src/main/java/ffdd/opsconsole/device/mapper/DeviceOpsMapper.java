package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.infrastructure.UserDeviceEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface DeviceOpsMapper extends BaseMapper<UserDeviceEntity> {
    String DEVICE_COLUMNS = """
            d.id,
            d.user_id AS userId,
            CONCAT('U', LPAD(d.user_id, 8, '0')) AS userNo,
            COALESCE(NULLIF(u.nickname, ''), CONCAT('user-', d.user_id), '未绑定用户') AS nickname,
            d.instance_no AS instanceNo,
            d.name,
            d.product_tier AS productTier,
            d.product_code AS productCode,
            d.status,
            d.dc_location AS dcLocation,
            d.hashrate,
            d.daily_usdt AS dailyUsdt,
            d.daily_nex AS dailyNex,
            d.last_seen_at AS lastSeenAt,
            d.activated_at AS activatedAt,
            d.deactivated_at AS deactivatedAt,
            d.pending_deactivate AS pendingDeactivate,
            r.online_status AS runtimeStatus,
            r.gpu_usage AS gpuUsage,
            r.gpu_temp_c AS gpuTempC,
            r.gpu_power_w AS gpuPowerW,
            r.paused_reason AS pausedReason,
            r.active_task_no AS activeTaskNo,
            r.heartbeat_at AS heartbeatAt
            """;

    @Select("SELECT COUNT(*) FROM nx_user_device WHERE is_deleted = 0")
    long countTotalDevices();

    @Select("SELECT COUNT(*) FROM nx_user_device WHERE is_deleted = 0 AND status IN ('ONLINE','BUSY')")
    long countOnlineDevices();

    @Select("SELECT COUNT(*) FROM nx_user_device WHERE is_deleted = 0 AND status = 'OFFLINE'")
    long countOfflineDevices();

    @Select("""
            SELECT COUNT(*) FROM nx_user_device
             WHERE is_deleted = 0
               AND (status IN ('RECYCLED','DEACTIVATED','INACTIVE','RETIRED') OR deactivated_at IS NOT NULL)
            """)
    long countRecycledDevices();

    @Select("SELECT COUNT(*) FROM nx_user_device WHERE is_deleted = 0 AND pending_deactivate = 1")
    long countPendingRecycleDevices();

    @Select("""
            SELECT COUNT(*)
              FROM nx_user_device d
              LEFT JOIN nx_user_device_runtime r ON r.user_device_id = d.id AND r.is_deleted = 0
             WHERE d.is_deleted = 0
               AND (
                 d.status NOT IN ('ONLINE','BUSY')
                 OR r.online_status IN ('OFFLINE','ERROR','ABNORMAL','LOST')
                OR DATE_SUB(NOW(), INTERVAL 10 MINUTE) > r.heartbeat_at
               )
            """)
    long countAbnormalDevices();

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_user_device d
              LEFT JOIN nx_user u ON u.id = d.user_id AND u.is_deleted = 0
             WHERE d.is_deleted = 0
             <if test='status != null and status != ""'>AND d.status = #{status}</if>
             <if test='dcLocation != null and dcLocation != ""'>AND COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED') = #{dcLocation}</if>
             <if test='keyword != null and keyword != ""'>
               AND (d.instance_no LIKE CONCAT('%', #{keyword}, '%')
                    OR d.name LIKE CONCAT('%', #{keyword}, '%')
                    OR d.product_code LIKE CONCAT('%', #{keyword}, '%')
                    OR d.source_order_no LIKE CONCAT('%', #{keyword}, '%')
                    OR CONCAT('U', LPAD(d.user_id, 8, '0')) LIKE CONCAT('%', #{keyword}, '%')
                    OR u.nickname LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            </script>
            """)
    long countDevices(@Param("status") String status, @Param("dcLocation") String dcLocation, @Param("keyword") String keyword);

    @Select("""
            <script>
            SELECT
            """ + DEVICE_COLUMNS + """
              FROM nx_user_device d
              LEFT JOIN nx_user u ON u.id = d.user_id AND u.is_deleted = 0
              LEFT JOIN nx_user_device_runtime r ON r.user_device_id = d.id AND r.is_deleted = 0
             WHERE d.is_deleted = 0
             <if test='status != null and status != ""'>AND d.status = #{status}</if>
             <if test='dcLocation != null and dcLocation != ""'>AND COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED') = #{dcLocation}</if>
             <if test='keyword != null and keyword != ""'>
               AND (d.instance_no LIKE CONCAT('%', #{keyword}, '%')
                    OR d.name LIKE CONCAT('%', #{keyword}, '%')
                    OR d.product_code LIKE CONCAT('%', #{keyword}, '%')
                    OR d.source_order_no LIKE CONCAT('%', #{keyword}, '%')
                    OR CONCAT('U', LPAD(d.user_id, 8, '0')) LIKE CONCAT('%', #{keyword}, '%')
                    OR u.nickname LIKE CONCAT('%', #{keyword}, '%'))
             </if>
             ORDER BY COALESCE(d.last_seen_at, d.updated_at, d.created_at) DESC
             LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DeviceOpsView> pageDevices(@Param("status") String status, @Param("dcLocation") String dcLocation,
                                    @Param("keyword") String keyword, @Param("limit") long limit,
                                    @Param("offset") long offset);

    @Select("""
            SELECT
            """ + DEVICE_COLUMNS + """
              FROM nx_user_device d
              LEFT JOIN nx_user u ON u.id = d.user_id AND u.is_deleted = 0
              LEFT JOIN nx_user_device_runtime r ON r.user_device_id = d.id AND r.is_deleted = 0
             WHERE d.is_deleted = 0
               AND d.user_id = #{userId}
             ORDER BY COALESCE(d.last_seen_at, d.activated_at, d.updated_at, d.created_at) DESC
             LIMIT #{limit}
            """)
    List<DeviceOpsView> listUserDevices(@Param("userId") Long userId, @Param("limit") long limit);

    @Select("""
            SELECT
            """ + DEVICE_COLUMNS + """
              FROM nx_user_device d
              LEFT JOIN nx_user u ON u.id = d.user_id AND u.is_deleted = 0
              LEFT JOIN nx_user_device_runtime r ON r.user_device_id = d.id AND r.is_deleted = 0
             WHERE d.is_deleted = 0 AND d.id = #{deviceId}
             LIMIT 1
            """)
    DeviceOpsView findDevice(@Param("deviceId") Long deviceId);

    @Update("""
            UPDATE nx_user_device
               SET status = 'OFFLINE',
                   pending_deactivate = 0,
                   deactivated_at = NULL,
                   last_seen_at = #{restoredAt},
                   updated_at = NOW()
             WHERE id = #{deviceId} AND is_deleted = 0
            """)
    int restoreDevice(@Param("deviceId") Long deviceId, @Param("restoredAt") LocalDateTime restoredAt);

    @Update("""
            UPDATE nx_user_device_runtime
               SET online_status = 'OFFLINE',
                   paused_reason = NULL,
                   heartbeat_at = #{restoredAt},
                   updated_at = NOW()
             WHERE user_device_id = #{deviceId} AND is_deleted = 0
            """)
    int restoreDeviceRuntime(@Param("deviceId") Long deviceId, @Param("restoredAt") LocalDateTime restoredAt);

    @Select("""
            SELECT COALESCE(AVG(TIMESTAMPDIFF(MONTH, COALESCE(d.purchased_at, d.activated_at, d.created_at), NOW())), 0) AS averageAgeMonths,
                   SUM(CASE WHEN TIMESTAMPDIFF(MONTH, COALESCE(d.purchased_at, d.activated_at, d.created_at), NOW()) >= #{cliffMonth}
                            THEN 1 ELSE 0 END) AS cliffDeviceCount
              FROM nx_user_device d
             WHERE d.is_deleted = 0
               AND d.status NOT IN ('RECYCLED','DEACTIVATED','INACTIVE','RETIRED')
            """)
    TradeinOverviewMetrics tradeinOverviewMetrics(@Param("cliffMonth") int cliffMonth);

    @Select("""
            SELECT COUNT(*)
              FROM nx_tradein_application
             WHERE is_deleted = 0
               AND created_at >= #{monthStart}
            """)
    long countTradeinApplicationsSince(@Param("monthStart") LocalDateTime monthStart);

    @Select("""
            SELECT COALESCE(SUM(tradein_discount_usdt), 0)
              FROM nx_tradein_application
             WHERE is_deleted = 0
               AND created_at >= #{monthStart}
            """)
    BigDecimal sumTradeinDiscountSince(@Param("monthStart") LocalDateTime monthStart);

    @Select("""
            <script>
            SELECT COUNT(*)
              FROM nx_tradein_application
             WHERE is_deleted = 0
               AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
              AND months_owned &lt; #{cliffMonth}
               AND status IN ('REJECTED','RISK_REJECTED','BLOCKED')
            </script>
            """)
    long countK2ArbitrageHits(@Param("cliffMonth") int cliffMonth);

    @Select("""
            <script>
            SELECT
              CASE
                WHEN #{operation} = 'replace' THEN (
                  SELECT COUNT(*)
                    FROM nx_trade_in_order
                   WHERE is_deleted = 0
                     AND created_at >= #{since}
                     AND status IN ('COMPLETED','SUCCESS','RECYCLED')
                )
                WHEN #{operation} = 'recycle' THEN (
                  SELECT COUNT(*)
                    FROM nx_user_device
                   WHERE is_deleted = 0
                     AND status = 'RECYCLED'
                     AND updated_at >= #{since}
                )
                WHEN #{operation} = 'deactivate' THEN (
                  SELECT COUNT(*)
                    FROM nx_user_device
                   WHERE is_deleted = 0
                     AND status = 'DEACTIVATED'
                     AND updated_at >= #{since}
                )
                ELSE 0
              END AS successCount,
              0 AS failureCount,
              0 AS rollbackCount,
              CASE
                WHEN #{operation} = 'replace' THEN (
                  SELECT MAX(created_at)
                    FROM nx_trade_in_order
                   WHERE is_deleted = 0
                     AND created_at >= #{since}
                )
                WHEN #{operation} = 'recycle' THEN (
                  SELECT MAX(updated_at)
                    FROM nx_user_device
                   WHERE is_deleted = 0
                     AND status = 'RECYCLED'
                     AND updated_at >= #{since}
                )
                WHEN #{operation} = 'deactivate' THEN (
                  SELECT MAX(updated_at)
                    FROM nx_user_device
                   WHERE is_deleted = 0
                     AND status = 'DEACTIVATED'
                     AND updated_at >= #{since}
                )
                ELSE NULL
              END AS latestAt
            </script>
            """)
    TradeinTxMetrics tradeinTxMetrics(@Param("operation") String operation, @Param("since") LocalDateTime since);

    @Select("""
            SELECT d.id AS deviceId,
                   d.instance_no AS instanceNo,
                   d.user_id AS userId,
                   d.status AS status
              FROM nx_user_device d
             WHERE d.is_deleted = 0
               AND d.status NOT IN ('RECYCLED','DEACTIVATED','INACTIVE','RETIRED')
             ORDER BY COALESCE(d.purchased_at, d.activated_at, d.created_at) ASC, d.id ASC
             LIMIT 1
            """)
    TradeinCandidateRow tradeinActionCandidate();

    @Insert("""
            INSERT INTO nx_trade_in_order(
                user_id, trade_in_no, source_device_id, target_product_id, valuation_usdt, status
            )
            SELECT d.user_id,
                   #{tradeInNo},
                   d.id,
                   COALESCE(d.product_id, 0),
                   COALESCE(d.price_usdt_snapshot, 0),
                   'COMPLETED'
              FROM nx_user_device d
             WHERE d.id = #{deviceId}
               AND d.is_deleted = 0
               AND d.status NOT IN ('RECYCLED','DEACTIVATED','INACTIVE','RETIRED')
            """)
    int insertTradeInOrderFromDevice(@Param("tradeInNo") String tradeInNo, @Param("deviceId") Long deviceId);

    @Update("""
            UPDATE nx_user_device
               SET status = 'RECYCLED',
                   pending_deactivate = 0,
                   deactivated_at = #{now},
                   updated_at = NOW()
             WHERE id = #{deviceId}
               AND is_deleted = 0
               AND status NOT IN ('RECYCLED','DEACTIVATED','INACTIVE','RETIRED')
            """)
    int recycleDevice(@Param("deviceId") Long deviceId, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_user_device
               SET status = 'DEACTIVATED',
                   pending_deactivate = 0,
                   deactivated_at = #{now},
                   updated_at = NOW()
             WHERE id = #{deviceId}
               AND is_deleted = 0
               AND status NOT IN ('RECYCLED','DEACTIVATED','INACTIVE','RETIRED')
            """)
    int deactivateDevice(@Param("deviceId") Long deviceId, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_user_device_runtime(user_device_id, online_status, region, paused_reason, heartbeat_at)
            SELECT d.id,
                   'OFFLINE',
                   COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED'),
                   #{operation},
                   #{now}
              FROM nx_user_device d
             WHERE d.id = #{deviceId}
               AND d.is_deleted = 0
            ON DUPLICATE KEY UPDATE
                   online_status = VALUES(online_status),
                   paused_reason = VALUES(paused_reason),
                   heartbeat_at = VALUES(heartbeat_at),
                   updated_at = NOW()
            """)
    int markRuntimeOffline(@Param("deviceId") Long deviceId, @Param("now") LocalDateTime now,
                           @Param("operation") String operation);

    @Select("""
            SELECT config_key AS configKey, config_value AS configValue
              FROM nx_compute_e3_config
             WHERE is_deleted = 0
             ORDER BY sort_order, id
            """)
    List<E3ConfigRow> e3ConfigRows();

    @Select("""
            SELECT start_month AS startMonth,
                   end_month AS endMonth,
                   monthly_decay_rate AS monthlyDecayRate,
                   floor_efficiency AS floorEfficiency
              FROM nx_device_lifecycle_rule
             WHERE is_deleted = 0
               AND status = 1
               AND scope_type = 'DEFAULT'
             ORDER BY start_month
            """)
    List<LifecycleRuleRow> defaultLifecycleRules();

    @Update("""
            UPDATE nx_compute_e3_config
               SET config_value = #{value},
                   value_type = #{valueType},
                   updated_by = #{operator},
                   updated_at = NOW()
             WHERE config_key = #{key} AND is_deleted = 0
            """)
    int updateE3Config(@Param("key") String key, @Param("value") String value,
                       @Param("valueType") String valueType, @Param("operator") String operator);

    @Insert("""
            INSERT INTO nx_compute_e3_config(config_key, config_value, value_type, updated_by, sort_order, is_deleted)
            VALUES(#{key}, #{value}, #{valueType}, #{operator}, #{sortOrder}, 0)
            """)
    int insertE3Config(@Param("key") String key, @Param("value") String value,
                       @Param("valueType") String valueType, @Param("operator") String operator,
                       @Param("sortOrder") int sortOrder);

    @Select("""
            SELECT dc.dc_location AS dcLocation,
                   dc.region_label AS regionLabel,
                   dc.status,
                   dc.sort_order AS sortOrder,
                   COALESCE(m.totalDevices, 0) AS totalDevices,
                   COALESCE(m.onlineDevices, 0) AS onlineDevices,
                   COALESCE(m.pendingRecycleDevices, 0) AS pendingRecycleDevices,
                   COALESCE(m.abnormalDevices, 0) AS abnormalDevices,
                   COALESCE(m.avgGpuUsage, 0) AS avgGpuUsage,
                   COALESCE(m.avgGpuTempC, 0) AS avgGpuTempC,
                   COALESCE(m.avgGpuPowerW, 0) AS avgGpuPowerW,
                   COALESCE(s.dispatch_paused, 0) AS dispatchPaused,
                   s.paused_reason AS pausedReason,
                   s.paused_at AS pausedAt,
                   s.resumed_at AS resumedAt,
                   dc.created_at AS createdAt,
                   dc.updated_at AS updatedAt
              FROM nx_compute_datacenter dc
              LEFT JOIN (
                    SELECT COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED') AS dcLocation,
                           COUNT(*) AS totalDevices,
                           SUM(CASE WHEN d.status IN ('ONLINE','BUSY') THEN 1 ELSE 0 END) AS onlineDevices,
                           SUM(CASE WHEN d.pending_deactivate = 1 THEN 1 ELSE 0 END) AS pendingRecycleDevices,
                           SUM(CASE WHEN d.status NOT IN ('ONLINE','BUSY') OR r.online_status IN ('OFFLINE','ERROR','ABNORMAL','LOST') THEN 1 ELSE 0 END) AS abnormalDevices,
                           COALESCE(AVG(r.gpu_usage), 0) AS avgGpuUsage,
                           COALESCE(AVG(r.gpu_temp_c), 0) AS avgGpuTempC,
                           COALESCE(AVG(r.gpu_power_w), 0) AS avgGpuPowerW
                      FROM nx_user_device d
                      LEFT JOIN nx_user_device_runtime r ON r.user_device_id = d.id AND r.is_deleted = 0
                     WHERE d.is_deleted = 0
                     GROUP BY COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED')
              ) m ON m.dcLocation = dc.dc_location
              LEFT JOIN nx_compute_dc_ops_state s ON s.dc_location = dc.dc_location AND s.is_deleted = 0
             WHERE dc.is_deleted = 0
             ORDER BY dc.sort_order ASC, dc.dc_location ASC
            """)
    List<DatacenterSummaryRow> datacenterSummaries();

    @Select("""
            SELECT dc.dc_location AS dcLocation,
                   dc.region_label AS regionLabel,
                   dc.status,
                   dc.sort_order AS sortOrder,
                   COALESCE(m.totalDevices, 0) AS totalDevices,
                   COALESCE(m.onlineDevices, 0) AS onlineDevices,
                   COALESCE(m.pendingRecycleDevices, 0) AS pendingRecycleDevices,
                   COALESCE(m.abnormalDevices, 0) AS abnormalDevices,
                   COALESCE(m.avgGpuUsage, 0) AS avgGpuUsage,
                   COALESCE(m.avgGpuTempC, 0) AS avgGpuTempC,
                   COALESCE(m.avgGpuPowerW, 0) AS avgGpuPowerW,
                   COALESCE(s.dispatch_paused, 0) AS dispatchPaused,
                   s.paused_reason AS pausedReason,
                   s.paused_at AS pausedAt,
                   s.resumed_at AS resumedAt,
                   dc.created_at AS createdAt,
                   dc.updated_at AS updatedAt
              FROM nx_compute_datacenter dc
              LEFT JOIN (
                    SELECT COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED') AS dcLocation,
                           COUNT(*) AS totalDevices,
                           SUM(CASE WHEN d.status IN ('ONLINE','BUSY') THEN 1 ELSE 0 END) AS onlineDevices,
                           SUM(CASE WHEN d.pending_deactivate = 1 THEN 1 ELSE 0 END) AS pendingRecycleDevices,
                           SUM(CASE WHEN d.status NOT IN ('ONLINE','BUSY') OR r.online_status IN ('OFFLINE','ERROR','ABNORMAL','LOST') THEN 1 ELSE 0 END) AS abnormalDevices,
                           COALESCE(AVG(r.gpu_usage), 0) AS avgGpuUsage,
                           COALESCE(AVG(r.gpu_temp_c), 0) AS avgGpuTempC,
                           COALESCE(AVG(r.gpu_power_w), 0) AS avgGpuPowerW
                      FROM nx_user_device d
                      LEFT JOIN nx_user_device_runtime r ON r.user_device_id = d.id AND r.is_deleted = 0
                     WHERE d.is_deleted = 0
                     GROUP BY COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED')
              ) m ON m.dcLocation = dc.dc_location
              LEFT JOIN nx_compute_dc_ops_state s ON s.dc_location = dc.dc_location AND s.is_deleted = 0
             WHERE dc.is_deleted = 0
               AND dc.dc_location = #{dcLocation}
            """)
    DatacenterSummaryRow findDatacenter(@Param("dcLocation") String dcLocation);

    @Insert("""
            INSERT INTO nx_compute_datacenter(dc_location, region_label, status, sort_order, updated_by, is_deleted)
            VALUES(#{dcLocation}, #{regionLabel}, #{status}, #{sortOrder}, #{operator}, 0)
            ON DUPLICATE KEY UPDATE
                   region_label = VALUES(region_label),
                   status = VALUES(status),
                   sort_order = VALUES(sort_order),
                   updated_by = VALUES(updated_by),
                   is_deleted = 0,
                   updated_at = NOW()
            """)
    int insertDatacenter(@Param("dcLocation") String dcLocation,
                         @Param("regionLabel") String regionLabel,
                         @Param("status") String status,
                         @Param("sortOrder") int sortOrder,
                         @Param("operator") String operator);

    @Update("""
            UPDATE nx_compute_datacenter
               SET region_label = #{regionLabel},
                   status = #{status},
                   sort_order = #{sortOrder},
                   updated_by = #{operator},
                   updated_at = NOW()
             WHERE dc_location = #{dcLocation}
               AND is_deleted = 0
            """)
    int updateDatacenter(@Param("dcLocation") String dcLocation,
                         @Param("regionLabel") String regionLabel,
                         @Param("status") String status,
                         @Param("sortOrder") int sortOrder,
                         @Param("operator") String operator);

    @Update("""
            UPDATE nx_compute_datacenter
               SET is_deleted = 1,
                   updated_by = #{operator},
                   updated_at = #{now}
             WHERE dc_location = #{dcLocation}
               AND is_deleted = 0
            """)
    int softDeleteDatacenter(@Param("dcLocation") String dcLocation,
                             @Param("operator") String operator,
                             @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_compute_dc_ops_state
               SET dispatch_paused = #{paused},
                   paused_reason = #{reason},
                   paused_at = CASE WHEN #{paused} = 1 THEN #{now} ELSE paused_at END,
                   resumed_at = CASE WHEN #{paused} = 0 THEN #{now} ELSE resumed_at END,
                   updated_by = #{operator},
                   updated_at = NOW()
             WHERE dc_location = #{dcLocation} AND is_deleted = 0
            """)
    int updateDatacenterState(@Param("dcLocation") String dcLocation, @Param("paused") int paused,
                              @Param("reason") String reason, @Param("operator") String operator,
                              @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_compute_dc_ops_state(
                dc_location, dispatch_paused, paused_reason, paused_at, resumed_at, updated_by, is_deleted
            ) VALUES(
                #{dcLocation}, #{paused}, #{reason}, #{pausedAt}, #{resumedAt}, #{operator}, 0
            )
            """)
    int insertDatacenterState(@Param("dcLocation") String dcLocation, @Param("paused") int paused,
                              @Param("reason") String reason, @Param("pausedAt") LocalDateTime pausedAt,
                              @Param("resumedAt") LocalDateTime resumedAt, @Param("operator") String operator);

    @Insert("""
            INSERT INTO nx_user_device_runtime(user_device_id, online_status, region, paused_reason, heartbeat_at)
            SELECT d.id, d.status, COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED'), #{reason}, COALESCE(d.last_seen_at, #{now})
              FROM nx_user_device d
             WHERE d.is_deleted = 0
               AND COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED') = #{dcLocation}
            ON DUPLICATE KEY UPDATE paused_reason = VALUES(paused_reason), updated_at = NOW()
            """)
    int pauseRuntimeByDatacenter(@Param("dcLocation") String dcLocation, @Param("reason") String reason,
                                 @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_user_device_runtime r
              JOIN nx_user_device d ON d.id = r.user_device_id
               SET r.paused_reason = NULL,
                   r.updated_at = NOW()
             WHERE d.is_deleted = 0
               AND r.is_deleted = 0
               AND COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED') = #{dcLocation}
            """)
    int resumeRuntimeByDatacenter(@Param("dcLocation") String dcLocation);

    record E3ConfigRow(String configKey, String configValue) {
    }

    record LifecycleRuleRow(Integer startMonth, Integer endMonth, BigDecimal monthlyDecayRate, BigDecimal floorEfficiency) {
    }

    record DatacenterSummaryRow(
            String dcLocation,
            String regionLabel,
            String status,
            Integer sortOrder,
            Long totalDevices,
            Long onlineDevices,
            Long pendingRecycleDevices,
            Long abnormalDevices,
            BigDecimal avgGpuUsage,
            BigDecimal avgGpuTempC,
            BigDecimal avgGpuPowerW,
            Integer dispatchPaused,
            String pausedReason,
            LocalDateTime pausedAt,
            LocalDateTime resumedAt,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    record TradeinOverviewMetrics(BigDecimal averageAgeMonths, Long cliffDeviceCount) {
    }

    record TradeinTxMetrics(Long successCount, Long failureCount, Long rollbackCount, LocalDateTime latestAt) {
    }

    record TradeinCandidateRow(Long deviceId, String instanceNo, Long userId, String status) {
    }
}

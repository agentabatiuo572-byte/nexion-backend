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
    // @Select text blocks concatenate these fragments directly after SQL keywords.
    // Keep the leading spaces so MyBatis does not emit tokens such as ANDd.
    String E5_OCCUPIED_SLOT = " " + """
            d.status IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE')
            AND d.activated_at IS NOT NULL
            AND d.deactivated_at IS NULL
            AND UPPER(d.ownership_status) = 'OWNED'
            """;
    String E5_ACTIVATED_OWNED = E5_OCCUPIED_SLOT + " AND d.pending_deactivate = 0 ";
    // Effective read states use database time; a persisted ONLINE flag alone is not live proof.
    String E5_HEARTBEAT_FRESH = " (r.heartbeat_at BETWEEN DATE_SUB(NOW(6), INTERVAL 10 MINUTE) AND NOW(6))";
    String E5_HEARTBEAT_STALE = " (r.heartbeat_at IS NULL OR NOT" + E5_HEARTBEAT_FRESH + ")";
    String E5_REPORTED_ONLINE = " UPPER(TRIM(COALESCE(r.online_status, ''))) = 'ONLINE'";
    String E5_RUNTIME_ONLINE = " (" + E5_REPORTED_ONLINE + " AND" + E5_HEARTBEAT_FRESH + ")";
    String E5_RUNTIME_OFFLINE = " (UPPER(TRIM(COALESCE(r.online_status, ''))) = 'OFFLINE' OR ("
            + E5_REPORTED_ONLINE + " AND" + E5_HEARTBEAT_STALE + "))";
    String E5_RUNTIME_UNKNOWN = " UPPER(TRIM(COALESCE(r.online_status, ''))) NOT IN ('ONLINE','OFFLINE','ERROR','ABNORMAL','LOST')";
    String E5_RUNTIME_ABNORMAL = " (" + E5_RUNTIME_OFFLINE
            + " OR UPPER(TRIM(COALESCE(r.online_status, ''))) IN ('ERROR','ABNORMAL','LOST'))";
    String PHONE_IDENTITY = " (UPPER(d.device_type) IN ('MOBILE','PHONE')"
            + " OR LOWER(COALESCE(d.product_code,'')) IN ('phone','mobile')"
            + " OR UPPER(COALESCE(d.product_tier,'')) IN ('PHONE','MOBILE')) ";
    String E5_SCOPE = " d.is_deleted = 0 AND d.source_environment = 'PRODUCTION' AND d.run_id = '' AND u.sandbox = 0 ";
    String STORE_SCOPE = E5_SCOPE + " AND d.source_channel IN ('ORDER','TRADE_IN','TRIAL','PROMOTION_GIFT') AND NOT " + PHONE_IDENTITY;
    String SIM_RUNTIME_JOIN = """
              LEFT JOIN nx_simulated_device_runtime sim ON sim.user_device_id = d.id
                   AND sim.user_id = d.user_id AND BINARY sim.instance_no = BINARY d.instance_no
            """;
    String SIM_TASK_JOIN = """
              LEFT JOIN nx_simulated_task current_sim ON current_sim.task_no = sim.current_task_no
                   AND current_sim.user_device_id = d.id AND current_sim.user_id = d.user_id
                   AND current_sim.generation = sim.generation AND BINARY current_sim.instance_no = BINARY d.instance_no
                   AND current_sim.status = 'RUNNING'
            """;
    String SIM_JOINS = SIM_RUNTIME_JOIN + SIM_TASK_JOIN;
    // Same three blocking conditions as SimulatedTaskService.hasLegacyPending.
    // All branches are correlated to the current device/owner; no history-wide aggregation.
    String LEGACY_PENDING = """
            (EXISTS (SELECT 1 FROM nx_compute_task legacy
               WHERE legacy.user_id=d.user_id AND legacy.user_device_id=d.id
                 AND legacy.source_environment='PRODUCTION' AND legacy.is_deleted=0
                 AND legacy.status IN ('CLAIMED','RUNNING','PAUSED')
                 AND legacy.task_no NOT LIKE 'CV2-%' AND legacy.task_no NOT LIKE 'SIM-%')
             OR EXISTS (SELECT 1 FROM nx_test_cloud_request r
               WHERE r.owner_id=d.user_id AND r.device_id=d.id AND r.state IN ('PROCESSING','UNKNOWN')
                 AND NOT EXISTS (
            """ + LegacyZeroCreditResolutionSql.RESOLVED_REQUEST + """
                 ))
             OR EXISTS (SELECT 1 FROM nx_compute_v2_event e
               WHERE e.user_id=d.user_id AND e.user_device_id=d.id AND e.kind IN ('ISSUED','STARTED','VERIFIED')
                 AND NOT EXISTS (SELECT 1 FROM nx_compute_v2_event x
                   WHERE x.task_no=e.task_no AND x.generation=e.generation AND x.kind='EXITED')
                 AND (EXISTS (SELECT 1 FROM nx_compute_v2_event s
                   WHERE s.task_no=e.task_no AND s.generation=e.generation AND s.kind='STARTED')
                   OR NOT EXISTS (SELECT 1 FROM nx_compute_v2_event f
                     WHERE f.task_no=e.task_no AND f.generation=e.generation AND f.kind='FAILED'))))
            """;
    String SIM_STATE_PREFIX = " " + """
            CASE
              WHEN d.status = 'UNBOUND' OR d.ownership_status = 'UNBOUND' THEN 'UNBOUND'
              WHEN d.pending_deactivate = 1 THEN 'CLOSING'
              WHEN d.deactivated_at IS NOT NULL OR d.status IN ('DEACTIVATED','RECYCLED','RETIRED') THEN 'STOPPED'
              WHEN d.activated_at IS NULL THEN 'WAITING_ACTIVATION'
              WHEN NULLIF(TRIM(r.paused_reason),'') IS NOT NULL OR d.status = 'SUSPENDED' THEN 'PAUSED'
              WHEN sim.state = 'ERROR' THEN 'ERROR'
              WHEN sim.state IN ('STOPPED','EXPIRED') AND (d.activated_at > sim.interval_started_at
                   OR (sim.state = 'STOPPED' AND sim.last_error = 'DEVICE_PAUSED')) THEN 'WAITING_INIT'
              WHEN sim.state IN ('STOPPED','EXPIRED') THEN 'STOPPED'
              WHEN sim.stop_after_current = 1 OR sim.state = 'CLOSING' THEN 'CLOSING'
            """;
    String SIM_STATE_SUFFIX = """
              WHEN sim.state = 'WAITING_CONFIG' THEN 'WAITING_CONFIG'
              ELSE 'RUNNING'
            END
            """;
    // The page projection resolves missing-runtime diagnostics in one batch after pagination.
    String SIM_STATE_NULL_RUNTIME = " WHEN sim.user_device_id IS NULL THEN 'WAITING_INIT' ";
    String SIM_STATE = SIM_STATE_PREFIX + SIM_STATE_NULL_RUNTIME + SIM_STATE_SUFFIX;
    // State filters resolve missing-runtime diagnostics before pagination.
    String RESOLVED_SIM_STATE_NULL_RUNTIME = " WHEN sim.user_device_id IS NULL AND " + LEGACY_PENDING + " THEN 'LEGACY_PENDING' "
            + " WHEN sim.user_device_id IS NULL THEN 'WAITING_INIT' ";
    String RESOLVED_SIM_STATE = SIM_STATE_PREFIX + RESOLVED_SIM_STATE_NULL_RUNTIME + SIM_STATE_SUFFIX;
    String VISIBLE_SIM_STATE = " CASE WHEN #{simEnabled} = 0 THEN 'SIM_OFF' ELSE " + RESOLVED_SIM_STATE + " END ";
    // Both missing-runtime states share the waiting tile, so fleet counts need no legacy-history probes.
    String OVERVIEW_SIM_STATE = " CASE WHEN #{simEnabled} = 0 THEN 'SIM_OFF' ELSE " + SIM_STATE + " END ";
    String DEVICE_FILTERS = """
             <if test='simState != null'>AND """ + VISIBLE_SIM_STATE + """
                = #{simState}</if>
             <if test='source != null'>AND d.source_channel = #{source}</if>
             <if test='productCode != null'>AND d.product_code = #{productCode}</if>
             <if test='userId != null'>AND d.user_id = #{userId}</if>
             <if test='keyword != null'>
               AND (d.instance_no LIKE CONCAT('%', #{keyword}, '%')
                    OR d.name LIKE CONCAT('%', #{keyword}, '%')
                    OR d.product_code LIKE CONCAT('%', #{keyword}, '%')
                    OR d.source_order_no LIKE CONCAT('%', #{keyword}, '%')
                    OR CONCAT('U', LPAD(d.user_id, GREATEST(8, LENGTH(CAST(d.user_id AS CHAR))), '0')) LIKE CONCAT('%', #{keyword}, '%')
                    OR u.nickname LIKE CONCAT('%', #{keyword}, '%'))
             </if>
            """;
    String RUNTIME_FROM = """
              FROM nx_user_device d
              LEFT JOIN nx_user u ON u.id = d.user_id
              LEFT JOIN nx_user_device_runtime r ON r.user_device_id = d.id AND r.is_deleted = 0
            """ + SIM_RUNTIME_JOIN;
    String LIST_FROM = RUNTIME_FROM + SIM_TASK_JOIN;
    String LIST_SCOPE = """
             <choose>
               <when test='phones'>""" + E5_SCOPE + " AND " + PHONE_IDENTITY + """
               </when>
               <otherwise>""" + STORE_SCOPE + """
               </otherwise>
             </choose>
            """;

    String DEVICE_COLUMNS = """
            d.id,
            d.user_id AS userId,
            CONCAT('U', LPAD(d.user_id, GREATEST(8, LENGTH(CAST(d.user_id AS CHAR))), '0')) AS userNo,
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
            d.purchased_at AS purchasedAt,
            d.activated_at AS activatedAt,
            d.deactivated_at AS deactivatedAt,
            COALESCE(NULLIF(sku.base_rate, ''), CAST(d.daily_usdt AS CHAR)) AS baseRate,
            COALESCE((SELECT t.current_efficiency FROM nx_tradein_application t
                       WHERE t.source_device_id = d.id AND t.is_deleted = 0
                       ORDER BY t.created_at DESC LIMIT 1), 1) AS currentEfficiency,
            d.pending_deactivate AS pendingDeactivate,
            CASE WHEN """ + E5_REPORTED_ONLINE + " AND" + E5_HEARTBEAT_STALE + """
                 THEN 'OFFLINE' ELSE r.online_status END AS runtimeStatus,
            r.gpu_usage AS gpuUsage,
            r.gpu_temp_c AS gpuTempC,
            r.gpu_power_w AS gpuPowerW,
            r.paused_reason AS pausedReason,
            r.active_task_no AS activeTaskNo,
            r.heartbeat_at AS heartbeatAt,
            r.battery_level AS batteryLevel,
            r.is_charging AS isCharging,
            r.network_reachable AS networkReachable,
            r.thermal_state AS thermalState,
            (SELECT COUNT(*) FROM nx_user_device a
              WHERE a.user_id=d.user_id AND a.is_deleted=0
                AND a.status IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE')
                AND a.ownership_status = 'OWNED'
                AND a.activated_at IS NOT NULL AND a.deactivated_at IS NULL) AS activeDevicesForUser,
            CASE
              WHEN d.status NOT IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE') OR d.activated_at IS NULL
                OR d.deactivated_at IS NOT NULL OR d.ownership_status != 'OWNED' THEN NULL
              ELSE (
                SELECT COUNT(*)
                  FROM nx_user_device s
                 WHERE s.is_deleted = 0
                   AND s.user_id = d.user_id
                   AND s.status IN ('ONLINE','BUSY','RUNNING','ACTIVE','OFFLINE')
                   AND s.ownership_status = 'OWNED'
                   AND s.activated_at IS NOT NULL AND s.deactivated_at IS NULL
                   AND NOT s.id > d.id
              )
            END AS userDeviceSlotNo,
            CASE WHEN r.heartbeat_at IS NOT NULL AND NOT r.heartbeat_at > NOW(6)
                 THEN TIMESTAMPDIFF(MICROSECOND, r.heartbeat_at, NOW(6)) * 0.000001 ELSE NULL END AS heartbeatAgeSeconds,
            d.source_channel AS sourceChannel,
            """ + SIM_STATE + """
            AS simState, sim.last_error AS simLastError, sim.next_action_at AS simNextActionAt,
            current_sim.task_no AS simTaskNo, current_sim.task_name AS simTaskName,
            current_sim.task_class AS simTaskClass, current_sim.started_at AS simTaskStartedAt,
            current_sim.due_at AS simTaskDueAt
            """;

    @Select("SELECT COUNT(*) FROM nx_user_device WHERE is_deleted = 0")
    long countTotalDevices();

    @Select("""
            SELECT COUNT(*)
              FROM nx_user_device d
              LEFT JOIN nx_user_device_runtime r ON r.user_device_id = d.id AND r.is_deleted = 0
             WHERE d.is_deleted = 0
               AND """ + E5_ACTIVATED_OWNED + """
               AND """ + E5_RUNTIME_ONLINE)
    long countOnlineDevices();

    /** 已完成的 USDT 提现本金，沿用提现对账的完成状态和完成时间。 */
    @Select("""
            SELECT COALESCE(SUM(w.amount), 0)
              FROM nx_withdrawal_order w
             WHERE w.is_deleted = 0
               AND w.asset = 'USDT'
               AND w.status IN ('CONFIRMED', 'SUCCESS')
               AND w.completed_at IS NOT NULL
               AND (w.d5_payout_source IS NULL OR w.d5_payout_source NOT IN ('mock', 'dev-simulator'))
               AND NOT EXISTS (SELECT 1 FROM nx_user u WHERE u.id = w.user_id AND u.sandbox = 1)
            """)
    BigDecimal completedWithdrawalTotal();


    // 概览一次分组查询同时产出两列:旧口径 OVERVIEW_SIM_STATE 与状态筛选同解析的 VISIBLE_SIM_STATE(有效状态)。
    // 旧四数只读 legacyState(与改动前完全一致);新增 errorRetry/legacyPending/waitingConfig 读 resolvedState,
    // 与列表状态筛选同一判据,不新增也不改写状态优先级。simEnabled=false 时两列同为 SIM_OFF。
    String OVERVIEW_GROUP_BY = " GROUP BY legacyState, resolvedState ";
    String OVERVIEW_STATE = " CAST(" + OVERVIEW_SIM_STATE + " AS CHAR) AS legacyState, "
            + " CAST(" + VISIBLE_SIM_STATE + " AS CHAR) AS resolvedState ";

    @Select("SELECT " + OVERVIEW_STATE + ", COUNT(*) AS deviceCount "
            + RUNTIME_FROM + " WHERE " + STORE_SCOPE + OVERVIEW_GROUP_BY)
    List<SimStateCount> storeStateCounts(@Param("simEnabled") boolean simEnabled);

    record SimStateCount(String legacyState, String resolvedState, long deviceCount) { }

    @Select("""
            <script>
            SELECT d.id FROM nx_user_device d
            """ + SIM_RUNTIME_JOIN + """
             WHERE sim.user_device_id IS NULL AND d.id IN
               <foreach collection='deviceIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
               AND """ + LEGACY_PENDING + """
            </script>
            """)
    java.util.Set<Long> legacyPendingDeviceIds(@Param("deviceIds") List<Long> deviceIds);

    @Select("""
            SELECT event_type AS eventType, aggregate_type AS aggregateType,
                   aggregate_id AS aggregateId, created_at AS createdAt
              FROM nx_event_outbox
             WHERE is_deleted = 0
               AND created_at >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
               AND aggregate_type IN ('E5_DEVICE','E5_DEVICE_BATCH')
             ORDER BY created_at DESC, id DESC
             LIMIT 50
            """)
    List<E5ActivityRow> e5Activity24h();

    @Select("<script>SELECT COUNT(*) " + RUNTIME_FROM + " WHERE " + LIST_SCOPE + DEVICE_FILTERS + "</script>")
    long countDevices(@Param("phones") boolean phones, @Param("simEnabled") boolean simEnabled,
                      @Param("simState") String simState, @Param("source") String source,
                      @Param("productCode") String productCode, @Param("keyword") String keyword,
                      @Param("userId") Long userId);

    @Select("<script>SELECT " + DEVICE_COLUMNS + LIST_FROM + """
              LEFT JOIN nx_admin_device_sku sku ON sku.sku_id = d.product_code AND sku.is_deleted = 0
             WHERE """ + LIST_SCOPE + DEVICE_FILTERS + """
             ORDER BY d.id DESC LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DeviceOpsView> pageDevices(@Param("phones") boolean phones, @Param("simEnabled") boolean simEnabled,
                                    @Param("simState") String simState, @Param("source") String source,
                                    @Param("productCode") String productCode, @Param("keyword") String keyword,
                                    @Param("userId") Long userId,
                                    @Param("limit") long limit, @Param("offset") long offset);

    @Select("""
            SELECT
            """ + DEVICE_COLUMNS + """
              FROM nx_user_device d
              LEFT JOIN nx_user u ON u.id = d.user_id AND u.is_deleted = 0
              LEFT JOIN nx_user_device_runtime r ON r.user_device_id = d.id AND r.is_deleted = 0
              LEFT JOIN nx_admin_device_sku sku ON sku.sku_id = d.product_code AND sku.is_deleted = 0
            """ + SIM_JOINS + """
             WHERE d.is_deleted = 0
               AND d.user_id = #{userId}
             ORDER BY COALESCE(d.last_seen_at, d.activated_at, d.updated_at, d.created_at) DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<DeviceOpsView> listUserDevices(@Param("userId") Long userId, @Param("limit") long limit, @Param("offset") long offset);

    @Select("SELECT COUNT(*) FROM nx_user_device WHERE user_id=#{userId} AND is_deleted=0")
    long countUserDevices(@Param("userId") Long userId);

    @Select("""
            SELECT
            """ + DEVICE_COLUMNS + """
              FROM nx_user_device d
              LEFT JOIN nx_user u ON u.id = d.user_id AND u.is_deleted = 0
              LEFT JOIN nx_user_device_runtime r ON r.user_device_id = d.id AND r.is_deleted = 0
              LEFT JOIN nx_admin_device_sku sku ON sku.sku_id = d.product_code AND sku.is_deleted = 0
            """ + SIM_JOINS + """
             WHERE d.is_deleted = 0 AND d.id = #{deviceId}
             LIMIT 1
            """)
    DeviceOpsView findDevice(@Param("deviceId") Long deviceId);

    @Select("""
            SELECT COUNT(*) FROM nx_user_device d
             WHERE d.user_id = #{userId} AND d.is_deleted = 0
               AND """ + E5_OCCUPIED_SLOT + """
            """)
    long countActiveDevicesByUser(@Param("userId") Long userId);

    @Select("""
            SELECT id FROM nx_user
             WHERE id = #{userId} AND is_deleted = 0
             FOR UPDATE
            """)
    Long lockUserForE5Activation(@Param("userId") Long userId);

    @Select("SELECT COALESCE(sandbox,0) FROM nx_user WHERE id=#{userId} AND is_deleted=0 FOR SHARE")
    Boolean lockUserSandboxForE5Activation(@Param("userId") Long userId);

    @Update("""
            UPDATE nx_user_device d
               SET status = CASE WHEN #{simEnabled} AND NOT """ + PHONE_IDENTITY + """
                   THEN 'ACTIVE' ELSE 'OFFLINE' END,
                   activated_at = COALESCE(activated_at, #{now}),
                   deactivated_at = NULL, pending_deactivate = 0, ownership_status = 'OWNED',
                   last_seen_at = #{now}, updated_at = NOW()
             WHERE id = #{deviceId} AND is_deleted = 0
               AND status IN ('INVENTORY','DEACTIVATED','RECYCLED','INACTIVE','RETIRED','UNBOUND')
            """)
    int activateE5Device(@Param("deviceId") Long deviceId, @Param("now") LocalDateTime now, @Param("simEnabled") boolean simEnabled);

    @Update("""
            UPDATE nx_user_device
               SET status = CASE
                     WHEN #{unbind} = 0 AND EXISTS (
                       SELECT 1 FROM nx_compute_task t
                        WHERE t.user_device_id = nx_user_device.id AND t.is_deleted = 0
                          AND t.source_environment = 'PRODUCTION'
                          AND UPPER(t.status) IN ('CLAIMED','RUNNING')
                     ) THEN status
                     WHEN #{unbind} = 1 THEN 'UNBOUND' ELSE 'DEACTIVATED' END,
                   ownership_status = CASE WHEN #{unbind} = 1 THEN 'UNBOUND' ELSE ownership_status END,
                   deactivated_at = CASE
                     WHEN #{unbind} = 0 AND EXISTS (
                       SELECT 1 FROM nx_compute_task t
                        WHERE t.user_device_id = nx_user_device.id AND t.is_deleted = 0
                          AND t.source_environment = 'PRODUCTION'
                          AND UPPER(t.status) IN ('CLAIMED','RUNNING')
                     ) THEN deactivated_at ELSE #{now} END,
                   pending_deactivate = CASE
                     WHEN #{unbind} = 0 AND EXISTS (
                       SELECT 1 FROM nx_compute_task t
                        WHERE t.user_device_id = nx_user_device.id AND t.is_deleted = 0
                          AND t.source_environment = 'PRODUCTION'
                          AND UPPER(t.status) IN ('CLAIMED','RUNNING')
                     ) THEN 1 ELSE 0 END,
                   updated_at = NOW()
             WHERE id = #{deviceId} AND is_deleted = 0
               AND status IN ('ONLINE','BUSY','RUNNING','OFFLINE','ACTIVE')
               AND (#{unbind} = 0 OR NOT EXISTS (
                 SELECT 1 FROM nx_compute_task t
                  WHERE t.user_device_id = nx_user_device.id AND t.is_deleted = 0
                    AND t.source_environment = 'PRODUCTION'
                    AND UPPER(t.status) IN ('CLAIMED','RUNNING')
               ))
            """)
    int deactivateE5Device(@Param("deviceId") Long deviceId, @Param("unbind") int unbind,
                           @Param("now") LocalDateTime now);

    String USER_PAUSE_CANDIDATE = " " + """
            d.status IN ('ONLINE','BUSY','RUNNING','OFFLINE','ACTIVE')
            AND (r.id IS NULL OR r.is_deleted = 1 OR r.paused_reason IS NULL
                 OR (#{simEnabled} = 1 AND UPPER(d.device_type) IN ('MOBILE','PHONE')
                     AND r.paused_reason IN ('PHONE_LOW_BATTERY','PHONE_OFFLINE')))
            """;

    // System pauses are written by other flows with these fixed values. An operator pause
    // stores the E5-A07 reason text instead. User-level resume only clears operator pauses on
    // an effective owned device, so refund, unbind/deactivate/trade-in, phone and account
    // pauses remain in force. Keep this list aligned with the write sites:
    // ORDER_REFUND (DeviceCatalogMapper), USER_DEACTIVATED (AppTaskAssignmentMapper,
    // CanonicalStateMapper, DevelopmentHomeSettlementMapper), PHONE_REPLACED
    // (OnboardingCalibrationMapper), PHONE_OFFLINE/PHONE_LOW_BATTERY (phone heartbeat),
    // unbind/deactivate (deactivateE5Device), replace/recycle/deactivate (trade-in actions).
    String RESUMABLE_OPERATIONAL_PAUSE = E5_OCCUPIED_SLOT + """
            AND r.id IS NOT NULL AND r.is_deleted = 0
            AND NULLIF(TRIM(r.paused_reason), '') IS NOT NULL
            AND UPPER(TRIM(r.paused_reason)) NOT IN (
                'ORDER_REFUND','USER_DEACTIVATED','PHONE_REPLACED','PHONE_OFFLINE',
                'PHONE_LOW_BATTERY','UNBIND','DEACTIVATE','REPLACE','RECYCLE')
            """;

    @Select("""
            SELECT d.id
              FROM nx_user_device d
              LEFT JOIN nx_user_device_runtime r
                ON r.user_device_id = d.id
             WHERE d.user_id = #{userId} AND d.is_deleted = 0
               AND (
                    (#{pause} = 1 AND """ + USER_PAUSE_CANDIDATE + """
                    )
                    OR
                    (#{pause} = 0 AND """ + RESUMABLE_OPERATIONAL_PAUSE + """
                    )
               )
             ORDER BY d.id ASC
             LIMIT #{limit}
             FOR UPDATE
            """)
    List<Long> lockE5BatchCandidateIds(@Param("userId") Long userId, @Param("pause") int pause,
                                       @Param("limit") int limit, @Param("simEnabled") boolean simEnabled);

    @Insert("""
            INSERT INTO nx_user_device_runtime(user_device_id, online_status, region, paused_reason, heartbeat_at)
            SELECT d.id, 'UNKNOWN', COALESCE(NULLIF(d.dc_location,''),'UNASSIGNED'), #{reason}, COALESCE(d.last_seen_at, #{now})
              FROM nx_user_device d
              LEFT JOIN nx_user_device_runtime r ON r.user_device_id = d.id
             WHERE d.user_id = #{userId} AND d.is_deleted = 0
               AND """ + USER_PAUSE_CANDIDATE + """
            ON DUPLICATE KEY UPDATE is_deleted = 0, paused_reason = VALUES(paused_reason), updated_at = NOW()
            """)
    int pauseDevicesByUser(@Param("userId") Long userId, @Param("reason") String reason,
                           @Param("now") LocalDateTime now, @Param("simEnabled") boolean simEnabled);

    // Persist the pause cutoff before a quick resume can hide it from a sleeping SIM slot.
    // Resume only wakes that slot; its recorded cutoff and phone validity remain unchanged.
    @Update("""
            <script>
            UPDATE nx_simulated_device_runtime
               SET stop_at = CASE WHEN #{pause} AND (stop_at IS NULL OR stop_at &gt; #{now})
                                  THEN #{now} ELSE stop_at END,
                   last_error = CASE WHEN #{pause} THEN 'DEVICE_PAUSED' ELSE last_error END,
                   next_action_at = #{now}
             WHERE user_id = #{userId} AND user_device_id IN
               <foreach collection='deviceIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
            </script>
            """)
    int syncSimulatedPause(@Param("userId") Long userId, @Param("deviceIds") List<Long> deviceIds,
                           @Param("pause") boolean pause, @Param("now") LocalDateTime now);

    @Update("""
            <script>
            UPDATE nx_user_device_runtime r
              JOIN nx_user_device d ON d.id = r.user_device_id
               SET r.paused_reason = NULL, r.updated_at = NOW()
             WHERE d.user_id = #{userId} AND d.is_deleted = 0
               AND d.id IN
                 <foreach collection='deviceIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>
               AND """ + RESUMABLE_OPERATIONAL_PAUSE + """
            </script>
            """)
    int resumeDevicesByUser(@Param("userId") Long userId, @Param("deviceIds") List<Long> deviceIds);

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
            SELECT AVG(ageMonths) AS averageAgeMonths,
                   COALESCE(SUM(CASE WHEN ageMonths > #{midEnd} THEN 1 ELSE 0 END), 0) AS cliffDeviceCount,
                   COALESCE(SUM(CASE WHEN ageMonths <= #{earlyEnd} THEN 1 ELSE 0 END), 0) AS earlyDeviceCount,
                   COALESCE(SUM(CASE WHEN ageMonths > #{earlyEnd} AND ageMonths <= #{midEnd} THEN 1 ELSE 0 END), 0) AS midDeviceCount
              FROM (
                  SELECT TIMESTAMPDIFF(MONTH, COALESCE(d.purchased_at, d.activated_at, d.created_at), NOW()) AS ageMonths
                    FROM nx_user_device d
                   WHERE d.is_deleted = 0
                     AND d.status NOT IN ('RECYCLED','DEACTIVATED','INACTIVE','RETIRED')
              ) device_age
            """)
    TradeinOverviewMetrics tradeinOverviewMetrics(@Param("earlyEnd") int earlyEnd, @Param("midEnd") int midEnd);

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
            SELECT COUNT(*)
              FROM (
                    SELECT user_id
                      FROM nx_tradein_application
                     WHERE is_deleted = 0
                       AND UPPER(status) = 'COMPLETED'
                       AND completed_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
                     GROUP BY user_id
                    HAVING COUNT(*) >= 3
                   ) frequent
             WHERE EXISTS (
                       SELECT 1 FROM nx_commission_event c
                        WHERE c.is_deleted = 0 AND c.user_id = frequent.user_id
                          AND c.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
                          AND UPPER(c.status) NOT IN ('FAILED','REJECTED','CANCELLED')
                          AND c.amount_usdt > 0
                   )
                OR EXISTS (
                       SELECT 1 FROM nx_wallet_ledger l
                        WHERE l.is_deleted = 0 AND l.user_id = frequent.user_id
                          AND l.created_at >= DATE_SUB(NOW(), INTERVAL 30 DAY)
                          AND UPPER(l.biz_type) LIKE '%GIFT%'
                          AND UPPER(l.status) IN ('POSTED','SUCCESS','COMPLETED')
                          AND UPPER(l.direction) = 'IN'
                          AND l.amount > 0
                   )
            """)
    long countK2ArbitrageHits();

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
              CASE
                WHEN #{operation} = 'replace' THEN (
                  SELECT COUNT(*) FROM nx_tradein_application
                   WHERE is_deleted = 0 AND created_at >= #{since}
                     AND UPPER(status) IN ('FAILED','REJECTED','RISK_REJECTED','BLOCKED')
                )
                ELSE (
                  SELECT COUNT(*) FROM nx_audit_log
                   WHERE is_deleted = 0 AND created_at >= #{since}
                     AND action = 'admin.tradein_action_executed' AND UPPER(result) != 'SUCCESS'
                     AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.operation')) = #{operation}
                )
              END AS failureCount,
              CASE
                WHEN #{operation} = 'replace' THEN (
                  SELECT COUNT(*) FROM nx_tradein_application
                   WHERE is_deleted = 0 AND updated_at >= #{since} AND UPPER(status) = 'ROLLED_BACK'
                )
                WHEN #{operation} = 'recycle' THEN (
                  SELECT COUNT(*) FROM nx_audit_log
                   WHERE is_deleted = 0 AND created_at >= #{since} AND action = 'E3B_DEVICE_RESTORE'
                     AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.fromStatus'))) = 'RECYCLED'
                )
                WHEN #{operation} = 'deactivate' THEN (
                  SELECT COUNT(*) FROM nx_audit_log
                   WHERE is_deleted = 0 AND created_at >= #{since} AND action = 'E3B_DEVICE_RESTORE'
                     AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.fromStatus'))) = 'DEACTIVATED'
                )
                ELSE 0
              END AS rollbackCount,
              CASE
                WHEN #{operation} = 'replace' THEN (
                  SELECT MAX(observed_at) FROM (
                    SELECT created_at AS observed_at FROM nx_tradein_application
                     WHERE is_deleted = 0 AND created_at >= #{since}
                    UNION ALL
                    SELECT created_at AS observed_at FROM nx_audit_log
                     WHERE is_deleted = 0 AND created_at >= #{since}
                       AND action = 'admin.tradein_action_executed'
                       AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.operation')) = 'replace'
                  ) replace_events
                )
                WHEN #{operation} = 'recycle' THEN (
                  SELECT MAX(created_at) FROM nx_audit_log
                   WHERE is_deleted = 0 AND created_at >= #{since}
                     AND ((action = 'admin.tradein_action_executed'
                           AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.operation')) = 'recycle')
                          OR (action = 'E3B_DEVICE_RESTORE'
                              AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.fromStatus'))) = 'RECYCLED'))
                )
                WHEN #{operation} = 'deactivate' THEN (
                  SELECT MAX(created_at) FROM nx_audit_log
                   WHERE is_deleted = 0 AND created_at >= #{since}
                     AND ((action = 'admin.tradein_action_executed'
                           AND JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.operation')) = 'deactivate')
                          OR (action = 'E3B_DEVICE_RESTORE'
                              AND UPPER(JSON_UNQUOTE(JSON_EXTRACT(detail_json, '$.fromStatus'))) = 'DEACTIVATED'))
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
                   active_task_no = NULL,
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

    record E3ConfigRow(String configKey, String configValue) {
    }

    record E5ActivityRow(
            String eventType,
            String aggregateType,
            String aggregateId,
            LocalDateTime createdAt) {
    }

    record LifecycleRuleRow(Integer startMonth, Integer endMonth, BigDecimal monthlyDecayRate, BigDecimal floorEfficiency) {
    }

    record TradeinOverviewMetrics(BigDecimal averageAgeMonths, Long cliffDeviceCount,
                                  Long earlyDeviceCount, Long midDeviceCount) {
    }

    record TradeinTxMetrics(Long successCount, Long failureCount, Long rollbackCount, LocalDateTime latestAt) {
    }

    record TradeinCandidateRow(Long deviceId, String instanceNo, Long userId, String status) {
    }
}

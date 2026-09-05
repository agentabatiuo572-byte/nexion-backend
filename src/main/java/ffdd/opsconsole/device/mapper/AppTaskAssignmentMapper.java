package ffdd.opsconsole.device.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import ffdd.opsconsole.device.infrastructure.UserDeviceEntity;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface AppTaskAssignmentMapper extends BaseMapper<UserDeviceEntity> {

    @Select("""
            SELECT d.id, d.instance_no AS instanceNo, d.device_type AS deviceType,
                   d.product_tier AS productTier, d.name, d.status,
                   d.product_code AS productCode, d.purchased_at AS purchasedAt, d.activated_at AS activatedAt,
                   d.vram_total_gb AS vramTotalGb, d.dc_location AS dcLocation,
                   r.online_status AS onlineStatus, r.paused_reason AS pausedReason,
                   COALESCE(dc.dispatch_paused, 0) AS dispatchPaused
              FROM nx_user_device d
              JOIN nx_user u ON u.id = d.user_id AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
              LEFT JOIN nx_user_device_runtime r
                ON r.user_device_id = d.id AND r.is_deleted = 0
              LEFT JOIN nx_compute_dc_ops_state dc
                ON dc.dc_location = d.dc_location AND dc.is_deleted = 0
             WHERE d.id = #{deviceId} AND d.user_id = #{userId}
               AND d.is_deleted = 0 AND d.source_environment='PRODUCTION' AND d.run_id=''
               AND UPPER(d.ownership_status) = 'OWNED'
             LIMIT 1 FOR UPDATE
            """)
    DeviceRow lockOwnedDevice(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

    @Select("""
            SELECT d.id, d.instance_no AS instanceNo, d.device_type AS deviceType,
                   d.product_tier AS productTier, d.name, d.status,
                   d.product_code AS productCode, d.purchased_at AS purchasedAt, d.activated_at AS activatedAt,
                   d.vram_total_gb AS vramTotalGb, d.dc_location AS dcLocation,
                   r.online_status AS onlineStatus, r.paused_reason AS pausedReason,
                   COALESCE(dc.dispatch_paused, 0) AS dispatchPaused
              FROM nx_user_device d
              JOIN nx_user u ON u.id = d.user_id AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
              LEFT JOIN nx_user_device_runtime r
                ON r.user_device_id = d.id AND r.is_deleted = 0
              LEFT JOIN nx_compute_dc_ops_state dc
                ON dc.dc_location = d.dc_location AND dc.is_deleted = 0
             WHERE d.user_id = #{userId} AND d.is_deleted = 0
               AND d.source_environment='PRODUCTION' AND d.run_id=''
               AND UPPER(d.ownership_status) = 'OWNED'
             ORDER BY d.id
            """)
    List<DeviceRow> ownedDevices(@Param("userId") Long userId);

    @Select("""
            SELECT d.user_id AS userId, d.id AS deviceId
              FROM nx_user_device d
              JOIN nx_user u ON u.id = d.user_id
                AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
              LEFT JOIN nx_user_device_runtime r
                ON r.user_device_id = d.id AND r.is_deleted = 0
              LEFT JOIN nx_compute_dc_ops_state dc
                ON dc.dc_location = d.dc_location AND dc.is_deleted = 0
             WHERE d.is_deleted = 0
               AND d.source_environment = 'PRODUCTION' AND COALESCE(d.run_id, '') = ''
               AND UPPER(d.ownership_status) = 'OWNED'
               AND d.activated_at IS NOT NULL AND d.deactivated_at IS NULL
               AND d.pending_deactivate = 0
               AND UPPER(d.status) IN ('ACTIVE','ONLINE')
               AND d.vram_total_gb IS NOT NULL AND d.vram_total_gb >= 0
               AND COALESCE(dc.dispatch_paused, 0) = 0
               AND COALESCE(TRIM(r.paused_reason), '') = ''
               AND (r.online_status IS NULL OR UPPER(r.online_status) = 'ONLINE')
               AND (UPPER(d.device_type) NOT IN ('MOBILE','PHONE')
                    OR EXISTS (SELECT 1 FROM nx_onboarding_calibration oc
                                WHERE oc.user_device_id = d.id AND oc.user_id = d.user_id
                                  AND oc.activation_status = 'ACTIVE'
                                  AND oc.source_environment = 'PRODUCTION' AND oc.run_id = ''
                                  AND oc.is_deleted = 0))
               AND NOT EXISTS (SELECT 1 FROM nx_compute_task t
                                WHERE t.user_id = d.user_id AND t.user_device_id = d.id
                                  AND t.source_environment = 'PRODUCTION' AND t.is_deleted = 0
                                  AND UPPER(t.status) IN ('CLAIMED','RUNNING')
                                  AND (t.lease_expires_at IS NULL OR t.lease_expires_at > CURRENT_TIMESTAMP))
               AND NOT EXISTS (SELECT 1 FROM nx_compute_device_task_lock l
                                WHERE l.user_id = d.user_id AND l.user_device_id = d.id
                                  AND l.source_environment = 'PRODUCTION' AND l.is_deleted = 0
                                  AND l.lock_until > CURRENT_TIMESTAMP)
               AND d.id > #{afterDeviceId}
             ORDER BY d.id
             LIMIT #{limit}
            """)
    List<AssignmentCandidate> assignmentCandidates(@Param("afterDeviceId") Long afterDeviceId,
                                                    @Param("limit") int limit);

    @Select("""
            SELECT d.id, d.instance_no AS instanceNo, d.device_type AS deviceType,
                   d.product_tier AS productTier, d.name, d.status,
                   d.product_code AS productCode, d.purchased_at AS purchasedAt, d.activated_at AS activatedAt,
                   d.vram_total_gb AS vramTotalGb, d.dc_location AS dcLocation,
                   NULL AS onlineStatus, NULL AS pausedReason, 0 AS dispatchPaused
              FROM nx_user_device d
              JOIN nx_user u ON u.id=d.user_id AND u.status='ACTIVE' AND u.is_deleted=0 AND u.sandbox=1
             WHERE d.user_id=#{userId} AND d.source_environment='SANDBOX' AND d.run_id=#{runId}
               AND d.is_deleted=0 AND UPPER(d.ownership_status)='OWNED'
             ORDER BY d.id
            """)
    List<DeviceRow> sandboxOwnedDevices(@Param("userId") Long userId, @Param("runId") String runId);

    @Select("""
            SELECT d.id, d.instance_no AS instanceNo, d.device_type AS deviceType,
                   d.product_tier AS productTier, d.name, d.status,
                   d.product_code AS productCode, d.purchased_at AS purchasedAt, d.activated_at AS activatedAt,
                   d.vram_total_gb AS vramTotalGb, d.dc_location AS dcLocation,
                   r.online_status AS onlineStatus, r.paused_reason AS pausedReason,
                   COALESCE(dc.dispatch_paused, 0) AS dispatchPaused
              FROM nx_user_device d
              JOIN nx_user u ON u.id = d.user_id
                            AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 1
              LEFT JOIN nx_user_device_runtime r
                ON r.user_device_id = d.id AND r.is_deleted = 0
              LEFT JOIN nx_compute_dc_ops_state dc
                ON dc.dc_location = d.dc_location AND dc.is_deleted = 0
             WHERE d.user_id = #{userId} AND d.is_deleted = 0
               AND d.source_environment = 'PRODUCTION' AND COALESCE(d.run_id, '') = ''
               AND UPPER(d.ownership_status) = 'OWNED'
             ORDER BY d.id
            """)
    List<DeviceRow> developmentOwnedDevices(@Param("userId") Long userId);

    @Select("""
            SELECT task_id AS taskId, name, task_class AS taskClass, model_name AS modelName,
                   min_reward AS minReward, max_reward AS maxReward,
                   CASE WHEN UPPER(min_vram) REGEXP '^(0|[1-9][0-9]{0,3})(GB)?$'
                        THEN CAST(min_vram AS UNSIGNED) ELSE NULL END AS minVram,
                   status, kill_init AS killInit
              FROM nx_admin_device_task
             WHERE is_deleted = 0 AND LOWER(TRIM(status)) = 'active'
               AND LOWER(TRIM(COALESCE(kill_init, ''))) NOT IN ('kill','killed','已 kill','已kill')
             ORDER BY updated_at DESC, id DESC
             FOR UPDATE
            """)
    List<TaskConfigRow> eligibleTasks(@Param("vramTotalGb") Integer vramTotalGb);

    @Select("""
            SELECT config_key AS configKey, config_value AS configValue
              FROM nx_compute_e3_config
             WHERE config_key IN ('taskLockS1','taskLockPro','taskLockRack') AND is_deleted = 0
            """)
    List<ConfigRow> taskLockConfig();

    @Select("""
            SELECT config_key AS configKey, config_value AS configValue
              FROM nx_compute_e3_config
             WHERE config_key IN (
                   'capacityBand1DeltaPct','capacityBand2DeltaPct','capacityBand3DeltaPct',
                   'stageEarlyEnd','stageMidEnd','cycleMonths','capacityFloorPct','capacitySubsidyDays',
                   'taskLockS1','taskLockPro','taskLockRack',
                   'capacityApplyToPhone','capacityApplyToCloudShare','capacityApplyToPcGpu',
                   'capacityApplyToS1','capacityApplyToPro','capacityApplyToProV2',
                   'capacityApplyToRackP1','capacityApplyToRackP2')
               AND is_deleted = 0
            """)
    List<ConfigRow> e3CapacityConfig();

    @Select("""
            SELECT lock_until AS lockUntil, last_task_no AS lastTaskNo
             FROM nx_compute_device_task_lock
             WHERE user_device_id = #{deviceId} AND user_id = #{userId}
               AND source_environment = 'PRODUCTION' AND source_environment = #{sourceEnvironment} AND is_deleted = 0
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id = #{userId}
                            AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0)
             LIMIT 1 FOR UPDATE
            """)
    DeviceLockRow lockDeviceTaskLock(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                                     @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            SELECT lock_until AS lockUntil, last_task_no AS lastTaskNo
             FROM nx_compute_device_task_lock
             WHERE user_device_id = #{deviceId} AND user_id = #{userId}
               AND source_environment = 'PRODUCTION' AND source_environment = #{sourceEnvironment} AND is_deleted = 0
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id = #{userId}
                            AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0)
             LIMIT 1
            """)
    DeviceLockRow deviceTaskLock(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                                 @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            SELECT t.task_no AS taskNo, t.user_device_id AS deviceId, t.task_config_id AS taskId,
                   t.task_name AS taskName, t.task_type AS taskClass, t.model_name AS modelName,
                   t.client_name AS clientName, t.status, t.reward_usdt AS rewardUsdt,
                   t.required_seconds AS requiredSeconds, t.task_lock_minutes AS taskLockMinutes,
                   t.started_at AS startedAt, t.lease_expires_at AS leaseExpiresAt,
                   t.completed_at AS completedAt, r.receipt_no AS receiptNo,
                   t.completion_nonce AS completionNonce, t.proof_expires_at AS proofExpiresAt
              FROM nx_compute_task t
              JOIN nx_user u ON u.id = t.user_id AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
              LEFT JOIN nx_compute_receipt r ON r.task_no = t.task_no
                AND r.source_environment = t.source_environment AND r.is_deleted = 0
             WHERE t.user_id = #{userId} AND t.user_device_id = #{deviceId}
               AND t.source_environment = 'PRODUCTION' AND t.source_environment = #{sourceEnvironment}
               AND t.is_deleted = 0 AND UPPER(t.status) IN ('CLAIMED','RUNNING')
             ORDER BY t.created_at DESC LIMIT 1 FOR UPDATE
            """)
    AssignmentRow lockActiveAssignment(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                                       @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            WITH ranked_tasks AS (
                SELECT t.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY t.user_device_id,
                               CASE WHEN UPPER(t.status) IN ('CLAIMED','RUNNING')
                                    THEN 'ACTIVE' ELSE 'COMPLETED' END
                           ORDER BY t.created_at DESC, t.id DESC
                       ) AS device_rank
                  FROM nx_compute_task t
                  JOIN nx_user u ON u.id = t.user_id
                    AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
                 WHERE t.user_id = #{userId}
                   AND t.source_environment = 'PRODUCTION'
                   AND t.source_environment = #{sourceEnvironment}
                   AND t.is_deleted = 0
                   AND UPPER(t.status) IN ('CLAIMED','RUNNING','COMPLETED')
            )
            SELECT t.task_no AS taskNo, t.user_device_id AS deviceId, t.task_config_id AS taskId,
                   t.task_name AS taskName, t.task_type AS taskClass, t.model_name AS modelName,
                   t.client_name AS clientName, t.status, t.reward_usdt AS rewardUsdt,
                   t.required_seconds AS requiredSeconds, t.task_lock_minutes AS taskLockMinutes,
                   t.started_at AS startedAt, t.lease_expires_at AS leaseExpiresAt,
                   t.completed_at AS completedAt, r.receipt_no AS receiptNo,
                   t.completion_nonce AS completionNonce, t.proof_expires_at AS proofExpiresAt
              FROM ranked_tasks t
              LEFT JOIN nx_compute_receipt r ON r.task_no = t.task_no
                AND r.source_environment = t.source_environment AND r.is_deleted = 0
             WHERE UPPER(t.status) IN ('CLAIMED','RUNNING')
                OR (UPPER(t.status) = 'COMPLETED' AND t.device_rank <= 10)
             ORDER BY t.created_at DESC, t.id DESC
            """)
    List<AssignmentRow> assignments(@Param("userId") Long userId,
                                    @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            WITH ranked_tasks AS (
                SELECT t.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY t.user_device_id,
                               CASE WHEN UPPER(t.status) IN ('CLAIMED','RUNNING')
                                    THEN 'ACTIVE' ELSE 'COMPLETED' END
                           ORDER BY t.created_at DESC, t.id DESC
                       ) AS device_rank
                  FROM nx_compute_task t
                  JOIN nx_user u ON u.id = t.user_id
                    AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 1
                 WHERE t.user_id = #{userId}
                   AND t.source_environment = 'PRODUCTION'
                   AND t.source_environment = #{sourceEnvironment}
                   AND t.is_deleted = 0
                   AND UPPER(t.status) IN ('CLAIMED','RUNNING','COMPLETED')
            )
            SELECT t.task_no AS taskNo, t.user_device_id AS deviceId, t.task_config_id AS taskId,
                   t.task_name AS taskName, t.task_type AS taskClass, t.model_name AS modelName,
                   t.client_name AS clientName, t.status, t.reward_usdt AS rewardUsdt,
                   t.required_seconds AS requiredSeconds, t.task_lock_minutes AS taskLockMinutes,
                   t.started_at AS startedAt, t.lease_expires_at AS leaseExpiresAt,
                   t.completed_at AS completedAt, r.receipt_no AS receiptNo,
                   t.completion_nonce AS completionNonce, t.proof_expires_at AS proofExpiresAt
              FROM ranked_tasks t
              LEFT JOIN nx_compute_receipt r ON r.task_no = t.task_no
                AND r.source_environment = t.source_environment AND r.is_deleted = 0
             WHERE UPPER(t.status) IN ('CLAIMED','RUNNING')
                OR (UPPER(t.status) = 'COMPLETED' AND t.device_rank <= 10)
             ORDER BY t.created_at DESC, t.id DESC
            """)
    List<AssignmentRow> developmentAssignments(@Param("userId") Long userId,
                                               @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            SELECT r.receipt_no AS receiptNo, r.task_no AS taskNo,
                   d.id AS deviceId, d.instance_no AS deviceInstanceNo, d.name AS deviceName,
                   d.device_type AS deviceType, d.gpu_model AS deviceGpu,
                   d.vram_total_gb AS vramTotalGb,
                   t.task_config_id AS taskId, t.task_name AS taskName, t.task_type AS taskClass,
                   t.model_name AS modelName, r.client_name AS clientName,
                   r.reward_usdt AS rewardUsdt, r.reward_nex AS rewardNex,
                   r.earning_status AS earningStatus, r.proof_hash AS proofHash,
                   t.started_at AS startedAt, r.completed_at AS completedAt,
                   GREATEST(TIMESTAMPDIFF(SECOND, t.started_at, r.completed_at), 0) AS durationSec
              FROM nx_compute_receipt r
              JOIN nx_compute_task t ON t.task_no = r.task_no
                AND t.user_id = r.user_id AND t.is_deleted = 0
                AND t.source_environment = 'PRODUCTION'
                AND t.user_device_id = r.user_device_id
                AND UPPER(t.status) = 'COMPLETED' AND t.completed_at IS NOT NULL
                AND t.completed_at = r.completed_at AND t.task_type = r.task_type
              JOIN nx_user_device d ON d.id = r.user_device_id
                AND d.user_id = r.user_id AND d.is_deleted = 0
                AND d.source_environment = 'PRODUCTION' AND COALESCE(d.run_id, '') = ''
              JOIN nx_user u ON u.id = r.user_id
                AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
             WHERE r.user_id = #{userId} AND r.receipt_no = #{receiptNo}
                AND r.source_environment = 'PRODUCTION' AND r.is_deleted = 0
                AND UPPER(r.earning_status) IN ('POSTED','SUCCESS','SETTLED','CREDITED','PAID')
                AND r.reward_usdt IS NOT NULL AND r.reward_usdt >= 0
                AND r.reward_nex IS NOT NULL AND r.reward_nex >= 0
             LIMIT 1
            """)
    ReceiptRow receipt(@Param("userId") Long userId, @Param("receiptNo") String receiptNo);

    @Select("""
            SELECT r.receipt_no AS receiptNo, r.task_no AS taskNo,
                   d.id AS deviceId, d.instance_no AS deviceInstanceNo, d.name AS deviceName,
                   d.device_type AS deviceType, d.gpu_model AS deviceGpu,
                   d.vram_total_gb AS vramTotalGb,
                   t.task_config_id AS taskId, t.task_name AS taskName, t.task_type AS taskClass,
                   t.model_name AS modelName, r.client_name AS clientName,
                   r.reward_usdt AS rewardUsdt, r.reward_nex AS rewardNex,
                   r.earning_status AS earningStatus, r.proof_hash AS proofHash,
                   t.started_at AS startedAt, r.completed_at AS completedAt,
                   GREATEST(TIMESTAMPDIFF(SECOND, t.started_at, r.completed_at), 0) AS durationSec
              FROM nx_compute_receipt r
              JOIN nx_compute_task t ON t.task_no = r.task_no
                AND t.user_id = r.user_id AND t.is_deleted = 0
                AND t.source_environment = 'PRODUCTION'
                AND t.user_device_id = r.user_device_id
                AND UPPER(t.status) = 'COMPLETED' AND t.completed_at IS NOT NULL
                AND t.completed_at = r.completed_at AND t.task_type = r.task_type
              JOIN nx_user_device d ON d.id = r.user_device_id
                AND d.user_id = r.user_id AND d.is_deleted = 0
                AND d.source_environment = 'PRODUCTION' AND COALESCE(d.run_id, '') = ''
              JOIN nx_user u ON u.id = r.user_id
                AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 1
             WHERE r.user_id = #{userId} AND r.receipt_no = #{receiptNo}
                AND r.source_environment = 'PRODUCTION' AND r.is_deleted = 0
                AND UPPER(r.earning_status) IN ('POSTED','SUCCESS','SETTLED','CREDITED','PAID')
                AND r.reward_usdt IS NOT NULL AND r.reward_usdt >= 0
                AND r.reward_nex IS NOT NULL AND r.reward_nex >= 0
             LIMIT 1
            """)
    ReceiptRow developmentReceipt(@Param("userId") Long userId, @Param("receiptNo") String receiptNo);

    @Select("""
            SELECT r.receipt_no AS receiptNo, r.task_no AS taskNo,
                   d.id AS deviceId, d.instance_no AS deviceInstanceNo, d.name AS deviceName,
                   d.device_type AS deviceType, d.gpu_model AS deviceGpu,
                   d.vram_total_gb AS vramTotalGb,
                   t.task_config_id AS taskId, t.task_name AS taskName, t.task_type AS taskClass,
                   t.model_name AS modelName, r.client_name AS clientName,
                   r.reward_usdt AS rewardUsdt, r.reward_nex AS rewardNex,
                   r.earning_status AS earningStatus, r.proof_hash AS proofHash,
                   t.started_at AS startedAt, r.completed_at AS completedAt,
                   GREATEST(TIMESTAMPDIFF(SECOND, t.started_at, r.completed_at), 0) AS durationSec
              FROM nx_compute_receipt r
              JOIN nx_compute_task t ON t.task_no = r.task_no
                AND t.user_id = r.user_id AND t.is_deleted = 0
                AND t.source_environment = 'PRODUCTION'
                AND t.user_device_id = r.user_device_id
                AND UPPER(t.status) = 'COMPLETED' AND t.completed_at IS NOT NULL
                AND t.completed_at = r.completed_at AND t.task_type = r.task_type
              JOIN nx_user_device d ON d.id = r.user_device_id
                AND d.user_id = r.user_id AND d.is_deleted = 0
                AND d.source_environment = 'PRODUCTION' AND COALESCE(d.run_id, '') = ''
              JOIN nx_user u ON u.id = r.user_id
                AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
             WHERE r.user_id = #{userId}
               AND r.source_environment = 'PRODUCTION' AND r.is_deleted = 0
               AND UPPER(r.earning_status) IN ('POSTED','SUCCESS','SETTLED','CREDITED','PAID')
               AND r.reward_usdt IS NOT NULL AND r.reward_usdt >= 0
               AND r.reward_nex IS NOT NULL AND r.reward_nex >= 0
             ORDER BY r.completed_at DESC, r.id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<ReceiptRow> receipts(@Param("userId") Long userId, @Param("offset") int offset,
                              @Param("limit") int limit);

    @Select("""
            SELECT r.receipt_no AS receiptNo, r.task_no AS taskNo,
                   d.id AS deviceId, d.instance_no AS deviceInstanceNo, d.name AS deviceName,
                   d.device_type AS deviceType, d.gpu_model AS deviceGpu,
                   d.vram_total_gb AS vramTotalGb,
                   t.task_config_id AS taskId, t.task_name AS taskName, t.task_type AS taskClass,
                   t.model_name AS modelName, r.client_name AS clientName,
                   r.reward_usdt AS rewardUsdt, r.reward_nex AS rewardNex,
                   r.earning_status AS earningStatus, r.proof_hash AS proofHash,
                   t.started_at AS startedAt, r.completed_at AS completedAt,
                   GREATEST(TIMESTAMPDIFF(SECOND, t.started_at, r.completed_at), 0) AS durationSec
              FROM nx_compute_receipt r
              JOIN nx_compute_task t ON t.task_no = r.task_no
                AND t.user_id = r.user_id AND t.is_deleted = 0
                AND t.source_environment = 'PRODUCTION'
                AND t.user_device_id = r.user_device_id
                AND UPPER(t.status) = 'COMPLETED' AND t.completed_at IS NOT NULL
                AND t.completed_at = r.completed_at AND t.task_type = r.task_type
              JOIN nx_user_device d ON d.id = r.user_device_id
                AND d.user_id = r.user_id AND d.is_deleted = 0
                AND d.source_environment = 'PRODUCTION' AND COALESCE(d.run_id, '') = ''
              JOIN nx_user u ON u.id = r.user_id
                AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 1
             WHERE r.user_id = #{userId}
               AND r.source_environment = 'PRODUCTION' AND r.is_deleted = 0
               AND UPPER(r.earning_status) IN ('POSTED','SUCCESS','SETTLED','CREDITED','PAID')
               AND r.reward_usdt IS NOT NULL AND r.reward_usdt >= 0
               AND r.reward_nex IS NOT NULL AND r.reward_nex >= 0
             ORDER BY r.completed_at DESC, r.id DESC
             LIMIT #{limit} OFFSET #{offset}
            """)
    List<ReceiptRow> developmentReceipts(@Param("userId") Long userId, @Param("offset") int offset,
                                         @Param("limit") int limit);

    @Select("""
            SELECT lock_until AS lockUntil, last_task_no AS lastTaskNo
              FROM nx_compute_device_task_lock
             WHERE user_device_id = #{deviceId} AND user_id = #{userId}
               AND source_environment = 'PRODUCTION' AND is_deleted = 0
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id = #{userId}
                            AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 1)
             LIMIT 1
            """)
    DeviceLockRow developmentDeviceTaskLock(
            @Param("userId") Long userId,
            @Param("deviceId") Long deviceId);

    @Select("""
            SELECT t.task_no AS taskNo, t.user_device_id AS deviceId, t.task_config_id AS taskId,
                   t.task_name AS taskName, t.task_type AS taskClass, t.model_name AS modelName,
                   t.client_name AS clientName, t.status, t.reward_usdt AS rewardUsdt,
                   t.required_seconds AS requiredSeconds, t.task_lock_minutes AS taskLockMinutes,
                   t.started_at AS startedAt, t.lease_expires_at AS leaseExpiresAt,
                   t.completed_at AS completedAt, r.receipt_no AS receiptNo,
                   t.completion_nonce AS completionNonce, t.proof_expires_at AS proofExpiresAt
              FROM nx_compute_task t
              JOIN nx_user u ON u.id = t.user_id AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
              LEFT JOIN nx_compute_receipt r ON r.task_no = t.task_no
                AND r.source_environment = t.source_environment AND r.is_deleted = 0
             WHERE t.task_no = #{taskNo} AND t.user_id = #{userId}
               AND t.source_environment = 'PRODUCTION' AND t.source_environment = #{sourceEnvironment}
               AND t.is_deleted = 0
             LIMIT 1 FOR UPDATE
            """)
    AssignmentRow lockAssignment(@Param("userId") Long userId, @Param("taskNo") String taskNo,
                                 @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            SELECT id FROM nx_user
             WHERE id = #{userId} AND status = 'ACTIVE' AND is_deleted = 0 AND sandbox = 0
             LIMIT 1 FOR UPDATE
            """)
    Long lockProductionUser(@Param("userId") Long userId);

    // Routing hint only; revalidate the task after locking its device.
    @Select("""
            SELECT user_device_id FROM nx_compute_task
             WHERE user_id = #{userId} AND task_no = #{taskNo} AND is_deleted = 0
               AND source_environment = 'PRODUCTION' AND source_environment = #{sourceEnvironment}
             LIMIT 1
            """)
    Long assignmentDeviceId(@Param("userId") Long userId, @Param("taskNo") String taskNo,
                            @Param("sourceEnvironment") String sourceEnvironment);

    @Select("SELECT sandbox FROM nx_user WHERE id = #{userId} AND status = 'ACTIVE' AND is_deleted = 0 LIMIT 1")
    UserScope userScope(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_compute_task(
              task_no, user_id, user_device_id, task_type, task_config_id, task_name, model_name,
              reward_usdt, required_seconds, task_lock_minutes, completion_nonce, proof_expires_at,
              source_environment, client_name, status,
              started_at, worker_ack_at, lease_expires_at, attempt_count, max_attempts,
              created_at, updated_at, is_deleted)
            SELECT #{taskNo}, #{userId}, #{deviceId}, #{task.taskClass}, #{task.taskId}, #{task.name},
              #{task.modelName}, #{rewardUsdt}, #{requiredSeconds}, #{taskLockMinutes}, #{completionNonce},
              #{proofExpiresAt}, 'PRODUCTION', 'Nexion App',
              'RUNNING', #{now}, #{now}, #{leaseExpiresAt}, 1, 3, #{now}, #{now}, 0
              FROM nx_user u
             WHERE u.id = #{userId} AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
               AND EXISTS (SELECT 1 FROM nx_user_device d
                            WHERE d.id=#{deviceId} AND d.user_id=#{userId} AND d.is_deleted=0
                               AND d.source_environment='PRODUCTION' AND d.run_id=''
                               AND UPPER(d.ownership_status) = 'OWNED' AND d.activated_at IS NOT NULL
                               AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
                               AND d.deactivated_at IS NULL AND d.pending_deactivate = 0
                               AND (UPPER(d.device_type) NOT IN ('MOBILE','PHONE')
                                    OR EXISTS (SELECT 1 FROM nx_onboarding_calibration oc
                                                WHERE oc.user_device_id=d.id AND oc.user_id=#{userId}
                                                  AND oc.activation_status='ACTIVE'
                                                  AND oc.source_environment='PRODUCTION' AND oc.run_id=''
                                                  AND oc.is_deleted=0)))
            """)
    int insertAssignment(@Param("taskNo") String taskNo, @Param("userId") Long userId,
                         @Param("deviceId") Long deviceId, @Param("task") TaskConfigRow task,
                         @Param("rewardUsdt") BigDecimal rewardUsdt,
                         @Param("requiredSeconds") Integer requiredSeconds,
                         @Param("taskLockMinutes") Integer taskLockMinutes,
                         @Param("completionNonce") String completionNonce,
                         @Param("proofExpiresAt") LocalDateTime proofExpiresAt,
                         @Param("sourceEnvironment") String sourceEnvironment,
                         @Param("now") LocalDateTime now,
                         @Param("leaseExpiresAt") LocalDateTime leaseExpiresAt);

    @Insert("""
            INSERT INTO nx_user_device_runtime(user_device_id, online_status, active_task_no, client_name,
              heartbeat_at, created_at, updated_at, is_deleted)
            SELECT d.id, 'ONLINE', #{taskNo}, 'Nexion App', #{now}, #{now}, #{now}, 0
              FROM nx_user_device d
              JOIN nx_user u ON u.id = d.user_id AND u.status = 'ACTIVE'
                AND u.is_deleted = 0 AND u.sandbox = 0
             WHERE d.id = #{deviceId} AND d.user_id = #{userId} AND d.is_deleted = 0
               AND d.source_environment='PRODUCTION' AND d.run_id=''
            ON DUPLICATE KEY UPDATE active_task_no = VALUES(active_task_no), client_name = VALUES(client_name),
              heartbeat_at = VALUES(heartbeat_at), updated_at = VALUES(updated_at), is_deleted = 0
            """)
    int bindRuntimeTask(@Param("deviceId") Long deviceId, @Param("taskNo") String taskNo,
                        @Param("userId") Long userId,
                        @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_compute_task SET status = 'COMPLETED', completed_at = #{now},
                   proof_consumed_at = #{now}, updated_at = #{now}
             WHERE task_no = #{taskNo} AND user_id = #{userId}
               AND source_environment = #{sourceEnvironment} AND source_environment = 'PRODUCTION'
               AND UPPER(status) IN ('CLAIMED','RUNNING')
               AND completion_nonce = #{proofNonce} AND proof_consumed_at IS NULL AND is_deleted = 0
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id = #{userId}
                            AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0)
            """)
    int completeAssignment(@Param("userId") Long userId, @Param("taskNo") String taskNo,
                           @Param("proofNonce") String proofNonce,
                           @Param("sourceEnvironment") String sourceEnvironment,
                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_compute_task
               SET status = 'EXPIRED', last_error = 'LEASE_EXPIRED', updated_at = #{now}
             WHERE task_no = #{taskNo} AND user_id = #{userId}
               AND source_environment = #{sourceEnvironment} AND source_environment = 'PRODUCTION'
               AND UPPER(status) IN ('CLAIMED','RUNNING') AND lease_expires_at <= #{now}
               AND proof_consumed_at IS NULL AND is_deleted = 0
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id = #{userId}
                            AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0)
            """)
    int expireAssignment(@Param("userId") Long userId, @Param("taskNo") String taskNo,
                         @Param("sourceEnvironment") String sourceEnvironment,
                         @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_compute_receipt(user_id, user_device_id, task_no, receipt_no, task_type,
              client_name, reward_usdt, reward_nex, earning_status, source_environment, proof_hash, completed_at,
              created_at, updated_at, is_deleted)
            SELECT #{userId}, #{deviceId}, #{task.taskNo}, #{receiptNo}, #{task.taskClass},
              #{task.clientName}, #{task.rewardUsdt}, 0, #{earningStatus}, 'PRODUCTION',
              #{proofHash}, #{now}, #{now}, #{now}, 0
              FROM nx_user u
             WHERE u.id = #{userId} AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
               AND EXISTS (SELECT 1 FROM nx_user_device d
                            WHERE d.id=#{deviceId} AND d.user_id=#{userId} AND d.is_deleted=0
                               AND d.source_environment='PRODUCTION' AND d.run_id=''
                               AND UPPER(d.ownership_status) = 'OWNED' AND d.activated_at IS NOT NULL
                               AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
                               AND d.deactivated_at IS NULL AND d.pending_deactivate IN (0, 1)
                               AND (UPPER(d.device_type) NOT IN ('MOBILE','PHONE')
                                    OR EXISTS (SELECT 1 FROM nx_onboarding_calibration oc
                                                WHERE oc.user_device_id=d.id AND oc.user_id=#{userId}
                                                  AND oc.activation_status='ACTIVE'
                                                  AND oc.source_environment='PRODUCTION' AND oc.run_id=''
                                                  AND oc.is_deleted=0)))
            """)
    int insertReceipt(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                      @Param("task") AssignmentRow task, @Param("receiptNo") String receiptNo,
                      @Param("proofHash") String proofHash, @Param("earningStatus") String earningStatus,
                      @Param("sourceEnvironment") String sourceEnvironment,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_user_wallet
               SET usdt_available = usdt_available + #{amount}, lifetime_earned = lifetime_earned + #{amount},
                   version = version + 1, updated_at = #{now}
             WHERE user_id = #{userId} AND is_deleted = 0
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id = #{userId}
                             AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0)
               AND EXISTS (SELECT 1 FROM nx_user_device d
                            WHERE d.id=#{deviceId} AND d.user_id=#{userId} AND d.is_deleted=0
                               AND d.source_environment='PRODUCTION' AND d.run_id=''
                               AND UPPER(d.ownership_status) = 'OWNED' AND d.activated_at IS NOT NULL
                               AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
                               AND d.deactivated_at IS NULL AND d.pending_deactivate IN (0, 1)
                               AND (UPPER(d.device_type) NOT IN ('MOBILE','PHONE')
                                    OR EXISTS (SELECT 1 FROM nx_onboarding_calibration oc
                                                WHERE oc.user_device_id=d.id AND oc.user_id=#{userId}
                                                  AND oc.activation_status='ACTIVE'
                                                  AND oc.source_environment='PRODUCTION' AND oc.run_id=''
                                                  AND oc.is_deleted=0)))
            """)
    int creditWallet(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                     @Param("amount") BigDecimal amount,
                     @Param("now") LocalDateTime now);

    @Select("SELECT w.usdt_available FROM nx_user_wallet w JOIN nx_user u ON u.id = w.user_id "
            + "AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0 "
            + "WHERE w.user_id = #{userId} AND w.is_deleted = 0 LIMIT 1")
    BigDecimal walletUsdt(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_wallet_ledger(user_id, biz_no, biz_type, asset, direction, amount,
              balance_after, status, remark, created_at, updated_at, is_deleted)
            SELECT #{userId}, #{taskNo}, 'COMPUTE_TASK_REWARD', 'USDT', 'IN', #{amount},
              #{balanceAfter}, 'SUCCESS', 'server-authoritative compute task reward', #{now}, #{now}, 0
              FROM nx_user u
             WHERE u.id = #{userId} AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
               AND EXISTS (SELECT 1 FROM nx_user_device d
                            WHERE d.id=#{deviceId} AND d.user_id=#{userId} AND d.is_deleted=0
                               AND d.source_environment='PRODUCTION' AND d.run_id=''
                               AND UPPER(d.ownership_status) = 'OWNED' AND d.activated_at IS NOT NULL
                               AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
                               AND d.deactivated_at IS NULL AND d.pending_deactivate IN (0, 1)
                               AND (UPPER(d.device_type) NOT IN ('MOBILE','PHONE')
                                    OR EXISTS (SELECT 1 FROM nx_onboarding_calibration oc
                                                WHERE oc.user_device_id=d.id AND oc.user_id=#{userId}
                                                  AND oc.activation_status='ACTIVE'
                                                  AND oc.source_environment='PRODUCTION' AND oc.run_id=''
                                                  AND oc.is_deleted=0)))
            """)
    int insertWalletLedger(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                           @Param("taskNo") String taskNo,
                           @Param("amount") BigDecimal amount, @Param("balanceAfter") BigDecimal balanceAfter,
                           @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_earning_event(event_no, user_id, user_device_id, receipt_no, asset, amount,
              status, wallet_posted_at, created_at, updated_at, is_deleted)
            SELECT #{eventNo}, #{userId}, #{deviceId}, #{receiptNo}, 'USDT', #{amount},
              'POSTED', #{now}, #{now}, #{now}, 0
              FROM nx_user u
             WHERE u.id = #{userId} AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
               AND EXISTS (SELECT 1 FROM nx_user_device d
                            WHERE d.id=#{deviceId} AND d.user_id=#{userId} AND d.is_deleted=0
                               AND d.source_environment='PRODUCTION' AND d.run_id=''
                               AND UPPER(d.ownership_status) = 'OWNED' AND d.activated_at IS NOT NULL
                               AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
                               AND d.deactivated_at IS NULL AND d.pending_deactivate IN (0, 1)
                               AND (UPPER(d.device_type) NOT IN ('MOBILE','PHONE')
                                    OR EXISTS (SELECT 1 FROM nx_onboarding_calibration oc
                                                WHERE oc.user_device_id=d.id AND oc.user_id=#{userId}
                                                  AND oc.activation_status='ACTIVE'
                                                  AND oc.source_environment='PRODUCTION' AND oc.run_id=''
                                                  AND oc.is_deleted=0)))
            """)
    int insertEarningEvent(@Param("eventNo") String eventNo, @Param("userId") Long userId,
                           @Param("deviceId") Long deviceId, @Param("receiptNo") String receiptNo,
                           @Param("amount") BigDecimal amount, @Param("now") LocalDateTime now);

    @Select("SELECT d.instance_no FROM nx_user_device d JOIN nx_user u ON u.id = d.user_id "
            + "AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0 "
            + "WHERE d.id = #{deviceId} AND d.user_id = #{userId} AND d.source_environment='PRODUCTION' "
            + "AND d.run_id='' AND d.is_deleted = 0 LIMIT 1")
    String deviceInstanceNo(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

    @Select("""
            SELECT c.status, c.kill_init AS killInit,
                   CASE WHEN UPPER(c.min_vram) REGEXP '^(0|[1-9][0-9]{0,3})(GB)?$'
                         THEN CAST(c.min_vram AS UNSIGNED) ELSE NULL END AS minVram,
                   CASE WHEN UPPER(TRIM(d.device_type)) IN ('SHARE','CLOUD_SHARE','CLOUD-SHARE')
                              OR LOWER(TRIM(d.product_code)) = 'cloud-share'
                        THEN 8 ELSE d.vram_total_gb END AS deviceVram
              FROM nx_admin_device_task c
              JOIN nx_user_device d ON d.id = #{deviceId} AND d.user_id = #{userId} AND d.is_deleted = 0
                AND d.source_environment='PRODUCTION' AND d.run_id=''
              JOIN nx_user u ON u.id = d.user_id AND u.status = 'ACTIVE'
                AND u.is_deleted = 0 AND u.sandbox = 0
             WHERE c.task_id = #{taskId} AND c.is_deleted = 0
             LIMIT 1 FOR UPDATE
            """)
    TaskRuntimeGateRow taskRuntimeGate(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                                       @Param("taskId") String taskId);

    @Update("""
            UPDATE nx_user_device_runtime SET active_task_no = NULL, updated_at = #{now}
             WHERE user_device_id = #{deviceId} AND active_task_no = #{taskNo} AND is_deleted = 0
               AND EXISTS (SELECT 1 FROM nx_user_device d JOIN nx_user u ON u.id = d.user_id
                            AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
                           WHERE d.id = #{deviceId} AND d.user_id = #{userId}
                             AND d.source_environment='PRODUCTION' AND d.run_id='')
            """)
    int clearRuntimeTask(@Param("userId") Long userId, @Param("deviceId") Long deviceId, @Param("taskNo") String taskNo,
                         @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_user_device SET status='DEACTIVATED',activated_at=NULL,deactivated_at=#{now},
                   pending_deactivate=0,row_version=row_version+1,updated_at=#{now}
             WHERE id=#{deviceId} AND user_id=#{userId} AND is_deleted=0
               AND source_environment='PRODUCTION' AND run_id=''
               AND UPPER(status) IN ('ACTIVE','ONLINE','BUSY','RUNNING','OFFLINE')
               AND pending_deactivate=1
               AND EXISTS (SELECT 1 FROM nx_user u WHERE u.id = #{userId}
                            AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0)
            """)
    int deactivatePendingDevice(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                                @Param("now") LocalDateTime now);

    @Select("SELECT row_version FROM nx_user_device WHERE id=#{deviceId} AND user_id=#{userId} "
            + "AND status='DEACTIVATED' AND pending_deactivate=0 AND is_deleted=0 "
            + "AND source_environment='PRODUCTION' AND run_id='' LIMIT 1")
    Long deviceRowVersion(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

    @Update("""
            UPDATE nx_user_device_runtime SET online_status='OFFLINE',paused_reason='USER_DEACTIVATED',updated_at=#{now}
             WHERE user_device_id=#{deviceId} AND is_deleted=0
               AND EXISTS (SELECT 1 FROM nx_user_device d JOIN nx_user u ON u.id = d.user_id
                            AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
                           WHERE d.id = #{deviceId} AND d.user_id = #{userId}
                             AND d.source_environment='PRODUCTION' AND d.run_id='')
            """)
    int markRuntimeDeactivated(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                               @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_compute_device_task_lock(user_id, user_device_id, source_environment, lock_until, last_task_no,
              created_at, updated_at, is_deleted)
            SELECT #{userId}, #{deviceId}, 'PRODUCTION', #{lockUntil}, #{taskNo}, #{now}, #{now}, 0
              FROM nx_user u
             WHERE u.id = #{userId} AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
            ON DUPLICATE KEY UPDATE user_id = VALUES(user_id), lock_until = VALUES(lock_until),
              last_task_no = VALUES(last_task_no), updated_at = VALUES(updated_at), is_deleted = 0
            """)
    int upsertDeviceTaskLock(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                             @Param("sourceEnvironment") String sourceEnvironment,
                             @Param("lockUntil") LocalDateTime lockUntil, @Param("taskNo") String taskNo,
                             @Param("now") LocalDateTime now);

    @Select("""
            SELECT COALESCE((SELECT config_value FROM nx_config_item
                              WHERE config_key = 'growth.phase.current' AND status = 1 AND is_deleted = 0 LIMIT 1), 'P1') AS phase,
                   GREATEST(TIMESTAMPDIFF(MONTH, u.created_at, NOW()), 0) AS accountAgeMonths,
                   DATE_FORMAT(u.created_at, '%x-W%v') AS cohort
              FROM nx_user u
             WHERE u.id = #{userId} AND u.status = 'ACTIVE' AND u.is_deleted = 0 AND u.sandbox = 0
            """)
    UserEventAttribution userEventAttribution(@Param("userId") Long userId);

    record DeviceRow(Long id, String instanceNo, String deviceType, String productTier, String name,
                     String status, String productCode, LocalDateTime purchasedAt,
                     LocalDateTime activatedAt, Integer vramTotalGb, String dcLocation,
                     String onlineStatus, String pausedReason, Boolean dispatchPaused) {}
    record AssignmentCandidate(Long userId, Long deviceId) {}
    record TaskConfigRow(String taskId, String name, String taskClass, String modelName,
                         BigDecimal minReward, BigDecimal maxReward, Integer minVram,
                         String status, String killInit) {}
    record ConfigRow(String configKey, String configValue) {}
    record DeviceLockRow(LocalDateTime lockUntil, String lastTaskNo) {}
    record UserScope(Integer sandbox) {}
    record AssignmentRow(String taskNo, Long deviceId, String taskId, String taskName, String taskClass,
                         String modelName, String clientName, String status, BigDecimal rewardUsdt,
                         Integer requiredSeconds, Integer taskLockMinutes, LocalDateTime startedAt,
                         LocalDateTime leaseExpiresAt,
                         LocalDateTime completedAt, String receiptNo, String completionNonce,
                         LocalDateTime proofExpiresAt) {}
    record ReceiptRow(String receiptNo, String taskNo, Long deviceId, String deviceInstanceNo,
                      String deviceName, String deviceType, String deviceGpu, Integer vramTotalGb,
                      String taskId, String taskName, String taskClass, String modelName,
                      String clientName, BigDecimal rewardUsdt, BigDecimal rewardNex,
                      String earningStatus, String proofHash, LocalDateTime startedAt,
                      LocalDateTime completedAt, Integer durationSec) {}
    record UserEventAttribution(String phase, Integer accountAgeMonths, String cohort) {}
    record TaskRuntimeGateRow(String status, String killInit, Integer minVram, Integer deviceVram) {}
}

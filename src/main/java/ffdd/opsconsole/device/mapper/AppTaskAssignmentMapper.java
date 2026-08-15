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
                   d.product_tier AS productTier, d.name, d.status, d.activated_at AS activatedAt,
                   d.vram_total_gb AS vramTotalGb, d.dc_location AS dcLocation,
                   r.online_status AS onlineStatus, r.paused_reason AS pausedReason,
                   COALESCE(dc.dispatch_paused, 0) AS dispatchPaused
              FROM nx_user_device d
              LEFT JOIN nx_user_device_runtime r
                ON r.user_device_id = d.id AND r.is_deleted = 0
              LEFT JOIN nx_compute_dc_ops_state dc
                ON dc.dc_location = d.dc_location AND dc.is_deleted = 0
             WHERE d.id = #{deviceId} AND d.user_id = #{userId}
               AND d.is_deleted = 0 AND UPPER(d.ownership_status) = 'OWNED'
             LIMIT 1 FOR UPDATE
            """)
    DeviceRow lockOwnedDevice(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

    @Select("""
            SELECT d.id, d.instance_no AS instanceNo, d.device_type AS deviceType,
                   d.product_tier AS productTier, d.name, d.status, d.activated_at AS activatedAt,
                   d.vram_total_gb AS vramTotalGb, d.dc_location AS dcLocation,
                   r.online_status AS onlineStatus, r.paused_reason AS pausedReason,
                   COALESCE(dc.dispatch_paused, 0) AS dispatchPaused
              FROM nx_user_device d
              LEFT JOIN nx_user_device_runtime r
                ON r.user_device_id = d.id AND r.is_deleted = 0
              LEFT JOIN nx_compute_dc_ops_state dc
                ON dc.dc_location = d.dc_location AND dc.is_deleted = 0
             WHERE d.user_id = #{userId} AND d.is_deleted = 0
               AND UPPER(d.ownership_status) = 'OWNED'
             ORDER BY d.id
            """)
    List<DeviceRow> ownedDevices(@Param("userId") Long userId);

    @Select("""
            SELECT task_id AS taskId, name, task_class AS taskClass, model_name AS modelName,
                   min_reward AS minReward, max_reward AS maxReward, min_vram AS minVram,
                   status, kill_init AS killInit
              FROM nx_admin_device_task
             WHERE is_deleted = 0 AND LOWER(TRIM(status)) = 'active'
               AND min_vram <= #{vramTotalGb}
               AND LOWER(TRIM(COALESCE(kill_init, ''))) NOT IN ('kill','killed','已 kill','已kill')
             ORDER BY updated_at DESC, id DESC
            """)
    List<TaskConfigRow> eligibleTasks(@Param("vramTotalGb") Integer vramTotalGb);

    @Select("""
            SELECT config_key AS configKey, config_value AS configValue
              FROM nx_compute_e3_config
             WHERE config_key IN ('taskLockS1','taskLockPro','taskLockRack') AND is_deleted = 0
            """)
    List<ConfigRow> taskLockConfig();

    @Select("""
            SELECT lock_until AS lockUntil, last_task_no AS lastTaskNo
              FROM nx_compute_device_task_lock
             WHERE user_device_id = #{deviceId} AND user_id = #{userId}
               AND source_environment = #{sourceEnvironment} AND is_deleted = 0
             LIMIT 1 FOR UPDATE
            """)
    DeviceLockRow lockDeviceTaskLock(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                                     @Param("sourceEnvironment") String sourceEnvironment);

    @Select("""
            SELECT lock_until AS lockUntil, last_task_no AS lastTaskNo
              FROM nx_compute_device_task_lock
             WHERE user_device_id = #{deviceId} AND user_id = #{userId}
               AND source_environment = #{sourceEnvironment} AND is_deleted = 0
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
              LEFT JOIN nx_compute_receipt r ON r.task_no = t.task_no
                AND r.source_environment = t.source_environment AND r.is_deleted = 0
             WHERE t.user_id = #{userId} AND t.user_device_id = #{deviceId}
               AND t.source_environment = #{sourceEnvironment}
               AND t.is_deleted = 0 AND UPPER(t.status) IN ('CLAIMED','RUNNING')
             ORDER BY t.created_at DESC LIMIT 1 FOR UPDATE
            """)
    AssignmentRow lockActiveAssignment(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
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
              LEFT JOIN nx_compute_receipt r ON r.task_no = t.task_no
                AND r.source_environment = t.source_environment AND r.is_deleted = 0
             WHERE t.user_id = #{userId} AND t.source_environment = #{sourceEnvironment} AND t.is_deleted = 0
             ORDER BY t.created_at DESC, t.id DESC LIMIT 100
            """)
    List<AssignmentRow> assignments(@Param("userId") Long userId,
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
              LEFT JOIN nx_compute_receipt r ON r.task_no = t.task_no
                AND r.source_environment = t.source_environment AND r.is_deleted = 0
             WHERE t.task_no = #{taskNo} AND t.user_id = #{userId}
               AND t.source_environment = #{sourceEnvironment} AND t.is_deleted = 0
             LIMIT 1 FOR UPDATE
            """)
    AssignmentRow lockAssignment(@Param("userId") Long userId, @Param("taskNo") String taskNo,
                                 @Param("sourceEnvironment") String sourceEnvironment);

    @Insert("""
            INSERT INTO nx_compute_task(
              task_no, user_id, user_device_id, task_type, task_config_id, task_name, model_name,
              reward_usdt, required_seconds, task_lock_minutes, completion_nonce, proof_expires_at,
              source_environment, client_name, status,
              started_at, worker_ack_at, lease_expires_at, attempt_count, max_attempts,
              created_at, updated_at, is_deleted)
            VALUES(#{taskNo}, #{userId}, #{deviceId}, #{task.taskClass}, #{task.taskId}, #{task.name},
              #{task.modelName}, #{rewardUsdt}, #{requiredSeconds}, #{taskLockMinutes}, #{completionNonce},
              #{proofExpiresAt}, #{sourceEnvironment}, 'Nexion App',
              'RUNNING', #{now}, #{now}, #{leaseExpiresAt}, 1, 3, #{now}, #{now}, 0)
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
            VALUES(#{deviceId}, 'ONLINE', #{taskNo}, 'Nexion App', #{now}, #{now}, #{now}, 0)
            ON DUPLICATE KEY UPDATE active_task_no = VALUES(active_task_no), client_name = VALUES(client_name),
              heartbeat_at = VALUES(heartbeat_at), updated_at = VALUES(updated_at), is_deleted = 0
            """)
    int bindRuntimeTask(@Param("deviceId") Long deviceId, @Param("taskNo") String taskNo,
                        @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_compute_task SET status = 'COMPLETED', completed_at = #{now},
                   proof_consumed_at = #{now}, updated_at = #{now}
             WHERE task_no = #{taskNo} AND user_id = #{userId}
               AND source_environment = #{sourceEnvironment} AND UPPER(status) IN ('CLAIMED','RUNNING')
               AND completion_nonce = #{proofNonce} AND proof_consumed_at IS NULL AND is_deleted = 0
            """)
    int completeAssignment(@Param("userId") Long userId, @Param("taskNo") String taskNo,
                           @Param("proofNonce") String proofNonce,
                           @Param("sourceEnvironment") String sourceEnvironment,
                           @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_compute_task
               SET status = 'EXPIRED', last_error = 'LEASE_EXPIRED', updated_at = #{now}
             WHERE task_no = #{taskNo} AND user_id = #{userId}
               AND source_environment = #{sourceEnvironment}
               AND UPPER(status) IN ('CLAIMED','RUNNING') AND lease_expires_at <= #{now}
               AND proof_consumed_at IS NULL AND is_deleted = 0
            """)
    int expireAssignment(@Param("userId") Long userId, @Param("taskNo") String taskNo,
                         @Param("sourceEnvironment") String sourceEnvironment,
                         @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_compute_receipt(user_id, user_device_id, task_no, receipt_no, task_type,
              client_name, reward_usdt, reward_nex, earning_status, source_environment, proof_hash, completed_at,
              created_at, updated_at, is_deleted)
            VALUES(#{userId}, #{deviceId}, #{task.taskNo}, #{receiptNo}, #{task.taskClass},
              #{task.clientName}, #{task.rewardUsdt}, 0, #{earningStatus}, #{sourceEnvironment},
              #{proofHash}, #{now}, #{now}, #{now}, 0)
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
            """)
    int creditWallet(@Param("userId") Long userId, @Param("amount") BigDecimal amount,
                     @Param("now") LocalDateTime now);

    @Select("SELECT usdt_available FROM nx_user_wallet WHERE user_id = #{userId} AND is_deleted = 0 LIMIT 1")
    BigDecimal walletUsdt(@Param("userId") Long userId);

    @Insert("""
            INSERT INTO nx_wallet_ledger(user_id, biz_no, biz_type, asset, direction, amount,
              balance_after, status, remark, created_at, updated_at, is_deleted)
            VALUES(#{userId}, #{taskNo}, 'COMPUTE_TASK_REWARD', 'USDT', 'IN', #{amount},
              #{balanceAfter}, 'SUCCESS', 'server-authoritative compute task reward', #{now}, #{now}, 0)
            """)
    int insertWalletLedger(@Param("userId") Long userId, @Param("taskNo") String taskNo,
                           @Param("amount") BigDecimal amount, @Param("balanceAfter") BigDecimal balanceAfter,
                           @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_earning_event(event_no, user_id, user_device_id, receipt_no, asset, amount,
              status, wallet_posted_at, created_at, updated_at, is_deleted)
            VALUES(#{eventNo}, #{userId}, #{deviceId}, #{receiptNo}, 'USDT', #{amount},
              'POSTED', #{now}, #{now}, #{now}, 0)
            """)
    int insertEarningEvent(@Param("eventNo") String eventNo, @Param("userId") Long userId,
                           @Param("deviceId") Long deviceId, @Param("receiptNo") String receiptNo,
                           @Param("amount") BigDecimal amount, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_compute_sandbox_reward(task_no, user_id, user_device_id, source_environment, receipt_no,
              simulated_reward_usdt, proof_hash, created_at)
            VALUES(#{taskNo}, #{userId}, #{deviceId}, 'SANDBOX', #{receiptNo}, #{amount}, #{proofHash}, #{now})
            """)
    int insertSandboxReward(@Param("taskNo") String taskNo, @Param("userId") Long userId,
                            @Param("deviceId") Long deviceId, @Param("receiptNo") String receiptNo,
                            @Param("amount") BigDecimal amount, @Param("proofHash") String proofHash,
                            @Param("now") LocalDateTime now);

    @Select("SELECT instance_no FROM nx_user_device WHERE id = #{deviceId} AND user_id = #{userId} AND is_deleted = 0 LIMIT 1")
    String deviceInstanceNo(@Param("userId") Long userId, @Param("deviceId") Long deviceId);

    @Select("""
            SELECT c.status, c.kill_init AS killInit, c.min_vram AS minVram,
                   d.vram_total_gb AS deviceVram
              FROM nx_admin_device_task c
              JOIN nx_user_device d ON d.id = #{deviceId} AND d.user_id = #{userId} AND d.is_deleted = 0
             WHERE c.task_id = #{taskId} AND c.is_deleted = 0
             LIMIT 1
            """)
    TaskRuntimeGateRow taskRuntimeGate(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                                       @Param("taskId") String taskId);

    @Update("""
            UPDATE nx_user_device_runtime SET active_task_no = NULL, updated_at = #{now}
             WHERE user_device_id = #{deviceId} AND active_task_no = #{taskNo} AND is_deleted = 0
            """)
    int clearRuntimeTask(@Param("deviceId") Long deviceId, @Param("taskNo") String taskNo,
                         @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_user_device SET status='DEACTIVATED',activated_at=NULL,deactivated_at=#{now},
                   pending_deactivate=0,row_version=row_version+1,updated_at=#{now}
             WHERE id=#{deviceId} AND user_id=#{userId} AND is_deleted=0
               AND UPPER(status)='ACTIVE' AND pending_deactivate=1
            """)
    int deactivatePendingDevice(@Param("userId") Long userId, @Param("deviceId") Long deviceId,
                                @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_user_device_runtime SET online_status='OFFLINE',paused_reason='USER_DEACTIVATED',updated_at=#{now}
             WHERE user_device_id=#{deviceId} AND is_deleted=0
            """)
    int markRuntimeDeactivated(@Param("deviceId") Long deviceId, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT INTO nx_compute_device_task_lock(user_id, user_device_id, source_environment, lock_until, last_task_no,
              created_at, updated_at, is_deleted)
            VALUES(#{userId}, #{deviceId}, #{sourceEnvironment}, #{lockUntil}, #{taskNo}, #{now}, #{now}, 0)
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
             WHERE u.id = #{userId} AND u.status = 'ACTIVE' AND u.is_deleted = 0
            """)
    UserEventAttribution userEventAttribution(@Param("userId") Long userId);

    record DeviceRow(Long id, String instanceNo, String deviceType, String productTier, String name,
                     String status, LocalDateTime activatedAt, Integer vramTotalGb, String dcLocation,
                     String onlineStatus, String pausedReason, Boolean dispatchPaused) {}
    record TaskConfigRow(String taskId, String name, String taskClass, String modelName,
                         BigDecimal minReward, BigDecimal maxReward, Integer minVram,
                         String status, String killInit) {}
    record ConfigRow(String configKey, String configValue) {}
    record DeviceLockRow(LocalDateTime lockUntil, String lastTaskNo) {}
    record AssignmentRow(String taskNo, Long deviceId, String taskId, String taskName, String taskClass,
                         String modelName, String clientName, String status, BigDecimal rewardUsdt,
                         Integer requiredSeconds, Integer taskLockMinutes, LocalDateTime startedAt,
                         LocalDateTime leaseExpiresAt,
                         LocalDateTime completedAt, String receiptNo, String completionNonce,
                         LocalDateTime proofExpiresAt) {}
    record UserEventAttribution(String phase, Integer accountAgeMonths, String cohort) {}
    record TaskRuntimeGateRow(String status, String killInit, Integer minVram, Integer deviceVram) {}
}

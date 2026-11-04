package ffdd.opsconsole.device.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

/** SQL authority for one current simulated task per device and immutable task quotes. */
@Mapper
public interface SimulatedTaskMapper {
    record TaskRow(String taskNo,String receiptNo,long userId,long deviceId,String instanceNo,long generation,
            Long productId,String productCode,String productTier,String deviceName,String deviceType,String gpuModel,Integer vramTotalGb,
            String taskTypeId,long taskTypeVersion,long productPolicyVersion,long e3Version,String taskName,String taskClass,
            int durationSeconds,String asset,BigDecimal baseReward,BigDecimal rewardAmount,BigDecimal e3Multiplier,BigDecimal cloudDailyNex,
            long walletId,String walletIncarnation,LocalDateTime startedAt,LocalDateTime dueAt,String status,
            LocalDateTime completedAt,LocalDateTime postedAt,String cancelReason) { }

    record DeviceRow(long deviceId,long userId,Long productId,String productCode,String productTier,String instanceNo,
            String deviceName,String deviceType,String gpuModel,Integer vramTotalGb,LocalDateTime purchasedAt,
            LocalDateTime activatedAt,LocalDateTime deactivatedAt,boolean pendingDeactivate,String status,
            boolean dispatchPaused,String pausedReason,LocalDateTime updatedAt,LocalDateTime runtimeUpdatedAt,LocalDateTime dcUpdatedAt) { }
    record RuntimeRow(long deviceId,long userId,String instanceNo,String productCode,boolean phone,long generation,
            LocalDateTime intervalStartedAt,LocalDateTime phoneValidUntil,LocalDateTime stopAt,boolean stopAfterCurrent,
            LocalDateTime finishAfterAt,LocalDateTime nextStartAt,String currentTaskNo,LocalDateTime nextActionAt,String state,String lastError) { }
    record Candidate(long userId,long deviceId) { }
    record WalletRow(long walletId,String incarnation) { }

    String DEVICE="""
            SELECT d.id deviceId,d.user_id userId,d.product_id productId,d.product_code productCode,d.product_tier productTier,
              d.instance_no instanceNo,d.name deviceName,d.device_type deviceType,d.gpu_model gpuModel,d.vram_total_gb vramTotalGb,
              d.purchased_at purchasedAt,d.activated_at activatedAt,d.deactivated_at deactivatedAt,
              d.pending_deactivate pendingDeactivate,d.status,COALESCE(dc.dispatch_paused,0) dispatchPaused,r.paused_reason pausedReason,
              d.updated_at updatedAt,r.updated_at runtimeUpdatedAt,dc.updated_at dcUpdatedAt
            FROM nx_user_device d
            LEFT JOIN nx_user_device_runtime r ON r.user_device_id=d.id AND r.is_deleted=0
            LEFT JOIN nx_compute_dc_ops_state dc ON dc.dc_location=d.dc_location AND dc.is_deleted=0
            WHERE d.id=#{deviceId} AND d.user_id=#{userId} AND d.is_deleted=0
              AND d.source_environment='PRODUCTION' AND d.run_id='' AND UPPER(d.ownership_status)='OWNED'
            """;
    String RUNTIME="""
            SELECT user_device_id deviceId,user_id userId,instance_no instanceNo,product_code productCode,phone,generation,
              interval_started_at intervalStartedAt,phone_valid_until phoneValidUntil,stop_at stopAt,stop_after_current stopAfterCurrent,
              finish_after_at finishAfterAt,next_start_at nextStartAt,current_task_no currentTaskNo,next_action_at nextActionAt,state,last_error lastError
            FROM nx_simulated_device_runtime
            """;
    String TASK="""
            SELECT task_no taskNo,receipt_no receiptNo,user_id userId,user_device_id deviceId,instance_no instanceNo,generation,
              product_id productId,product_code productCode,product_tier productTier,device_name deviceName,device_type deviceType,
              gpu_model gpuModel,vram_total_gb vramTotalGb,task_type_id taskTypeId,task_type_version taskTypeVersion,
              product_policy_version productPolicyVersion,e3_version e3Version,task_name taskName,task_class taskClass,
              duration_seconds durationSeconds,asset,base_reward baseReward,reward_amount rewardAmount,e3_multiplier e3Multiplier,cloud_daily_nex cloudDailyNex,
              wallet_id walletId,wallet_incarnation walletIncarnation,started_at startedAt,due_at dueAt,status,
              completed_at completedAt,posted_at postedAt,cancel_reason cancelReason
            FROM nx_simulated_task
            """;

    @Select("SELECT id FROM nx_user WHERE id=#{userId} AND status='ACTIVE' AND sandbox=0 AND is_deleted=0 FOR UPDATE")
    Long lockUser(@Param("userId")long userId);
    @Select(DEVICE+" FOR UPDATE")
    DeviceRow lockDevice(@Param("userId")long userId,@Param("deviceId")long deviceId);
    @Select("SELECT user_id userId,id deviceId FROM nx_user_device WHERE id=#{deviceId} AND is_deleted=0 AND source_environment='PRODUCTION' AND run_id='' AND UPPER(ownership_status)='OWNED'")
    Candidate subject(@Param("deviceId")long deviceId);
    @Select("SELECT id walletId,fund_incarnation incarnation FROM nx_user_wallet WHERE user_id=#{userId} AND sandbox=0 AND is_deleted=0 FOR UPDATE")
    List<WalletRow> wallets(@Param("userId")long userId);
    @Select(RUNTIME+" WHERE user_device_id=#{deviceId} FOR UPDATE")
    RuntimeRow lockRuntime(@Param("deviceId")long deviceId);
    @Select(RUNTIME+" WHERE user_device_id=#{deviceId}")
    RuntimeRow runtime(@Param("deviceId")long deviceId);
    @Select(RUNTIME+" WHERE user_id=#{userId} ORDER BY user_device_id")
    List<RuntimeRow> runtimesByUser(@Param("userId")long userId);
    @Select(TASK+" WHERE task_no=#{taskNo} FOR UPDATE")
    TaskRow lockTask(@Param("taskNo")String taskNo);
    @Select(TASK+" WHERE task_no=#{taskNo}")
    TaskRow task(@Param("taskNo")String taskNo);
    @Select("<script>"+TASK+" WHERE user_id=#{userId} AND task_no IN <foreach collection='taskNos' item='no' open='(' separator=',' close=')'>#{no}</foreach></script>")
    List<TaskRow> tasksByUser(@Param("userId")long userId,@Param("taskNos")List<String> taskNos);
    @Select("<script>"+TASK+"""
            WHERE user_id=#{userId} AND task_no IN (SELECT task_no FROM (
              <foreach collection='deviceIds' item='id' separator=' UNION ALL '>
                (SELECT task_no FROM nx_visible_simulated_task
                 WHERE user_id=#{userId} AND user_device_id=#{id} AND status='COMPLETED'
                 ORDER BY completed_at DESC,task_no DESC LIMIT #{perDeviceLimit})
              </foreach>
            ) recent) ORDER BY completed_at DESC,task_no DESC
            </script>
            """)
    List<TaskRow> recentTasksByUser(@Param("userId")long userId,@Param("deviceIds")List<Long> deviceIds,@Param("perDeviceLimit")int perDeviceLimit);
    @Select(TASK+" WHERE user_id=#{userId} AND task_no IN (SELECT current_task_no FROM nx_simulated_device_runtime WHERE user_id=#{userId}) ORDER BY user_device_id")
    List<TaskRow> currentTasks(@Param("userId")long userId);
    @Select("SELECT user_id userId,user_device_id deviceId FROM nx_simulated_device_runtime WHERE next_action_at<=#{now} ORDER BY next_action_at,user_device_id LIMIT #{limit}")
    List<Candidate> due(@Param("now")LocalDateTime now,@Param("limit")int limit);
    @Select("""
            SELECT d.user_id userId,d.id deviceId FROM nx_user_device d
            JOIN nx_user u ON u.id=d.user_id AND u.status='ACTIVE' AND u.sandbox=0 AND u.is_deleted=0
            LEFT JOIN nx_simulated_device_runtime r ON r.user_device_id=d.id
            WHERE d.id>#{afterId} AND d.is_deleted=0 AND d.source_environment='PRODUCTION' AND d.run_id=''
              AND UPPER(d.ownership_status)='OWNED' AND UPPER(d.device_type) NOT IN ('PHONE','MOBILE')
              AND d.activated_at IS NOT NULL AND d.deactivated_at IS NULL AND d.pending_deactivate=0
              AND UPPER(d.status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
              AND (r.user_device_id IS NULL OR (r.state IN ('STOPPED','EXPIRED') AND d.activated_at>r.interval_started_at)
                OR (r.state='STOPPED' AND r.last_error='DEVICE_PAUSED'))
            ORDER BY d.id LIMIT #{limit}
            """)
    List<Candidate> initialDevices(@Param("afterId")long afterId,@Param("limit")int limit);
    @Select("""
            SELECT (SELECT COUNT(*) FROM nx_compute_task WHERE user_id=#{userId} AND user_device_id=#{deviceId}
              AND source_environment='PRODUCTION' AND is_deleted=0 AND status IN ('CLAIMED','RUNNING','PAUSED')
              AND task_no NOT LIKE 'CV2-%' AND task_no NOT LIKE 'SIM-%')
              +(SELECT COUNT(*) FROM nx_test_cloud_request r WHERE r.owner_id=#{userId} AND r.device_id=#{deviceId}
                AND r.state IN ('PROCESSING','UNKNOWN') AND NOT EXISTS (
            """+LegacyZeroCreditResolutionSql.RESOLVED_REQUEST+"))")
    int unresolvedLegacy(@Param("userId")long userId,@Param("deviceId")long deviceId);
    @Select("""
            SELECT COUNT(DISTINCT e.task_no,e.generation) FROM nx_compute_v2_event e
            WHERE e.user_id=#{userId} AND e.user_device_id=#{deviceId} AND e.kind IN ('ISSUED','STARTED','VERIFIED')
              AND NOT EXISTS(SELECT 1 FROM nx_compute_v2_event x WHERE x.task_no=e.task_no AND x.generation=e.generation AND x.kind='EXITED')
              AND (EXISTS(SELECT 1 FROM nx_compute_v2_event s WHERE s.task_no=e.task_no AND s.generation=e.generation AND s.kind='STARTED')
                OR NOT EXISTS(SELECT 1 FROM nx_compute_v2_event f WHERE f.task_no=e.task_no AND f.generation=e.generation AND f.kind='FAILED'))
            """)
    int unresolvedV2(@Param("userId")long userId,@Param("deviceId")long deviceId);

    @Insert("""
            INSERT INTO nx_simulated_device_runtime(user_device_id,user_id,instance_no,product_code,phone,generation,
              interval_started_at,phone_valid_until,stop_at,stop_after_current,finish_after_at,next_start_at,current_task_no,next_action_at,state,last_error)
            VALUES(#{r.deviceId},#{r.userId},#{r.instanceNo},#{r.productCode},#{r.phone},#{r.generation},#{r.intervalStartedAt},
              #{r.phoneValidUntil},#{r.stopAt},#{r.stopAfterCurrent},#{r.finishAfterAt},#{r.nextStartAt},#{r.currentTaskNo},#{r.nextActionAt},#{r.state},#{r.lastError})
            """)
    int insertRuntime(@Param("r")RuntimeRow row);
    @Update("""
            UPDATE nx_simulated_device_runtime SET generation=#{r.generation},interval_started_at=#{r.intervalStartedAt},
              phone_valid_until=#{r.phoneValidUntil},stop_at=#{r.stopAt},stop_after_current=#{r.stopAfterCurrent},
              finish_after_at=#{r.finishAfterAt},next_start_at=#{r.nextStartAt},current_task_no=#{r.currentTaskNo},next_action_at=#{r.nextActionAt},state=#{r.state},last_error=#{r.lastError}
            WHERE user_device_id=#{r.deviceId} AND user_id=#{r.userId} AND instance_no=#{r.instanceNo}
            """)
    int updateRuntime(@Param("r")RuntimeRow row);
    @Insert("""
            INSERT INTO nx_simulated_task(task_no,receipt_no,user_id,user_device_id,instance_no,generation,product_id,product_code,
              product_tier,device_name,device_type,gpu_model,vram_total_gb,task_type_id,task_type_version,product_policy_version,e3_version,
              task_name,task_class,duration_seconds,asset,base_reward,reward_amount,e3_multiplier,cloud_daily_nex,wallet_id,wallet_incarnation,
              started_at,due_at,status,completed_at,posted_at,cancel_reason)
            VALUES(#{t.taskNo},#{t.receiptNo},#{t.userId},#{t.deviceId},#{t.instanceNo},#{t.generation},#{t.productId},#{t.productCode},
              #{t.productTier},#{t.deviceName},#{t.deviceType},#{t.gpuModel},#{t.vramTotalGb},#{t.taskTypeId},#{t.taskTypeVersion},
              #{t.productPolicyVersion},#{t.e3Version},#{t.taskName},#{t.taskClass},#{t.durationSeconds},#{t.asset},#{t.baseReward},
              #{t.rewardAmount},#{t.e3Multiplier},#{t.cloudDailyNex},#{t.walletId},#{t.walletIncarnation},#{t.startedAt},#{t.dueAt},#{t.status},
              #{t.completedAt},#{t.postedAt},#{t.cancelReason})
            """)
    int insertTask(@Param("t")TaskRow task);
    @Update("UPDATE nx_simulated_task SET status='COMPLETED',completed_at=due_at,posted_at=#{postedAt} WHERE task_no=#{taskNo} AND status='RUNNING'")
    int completeTask(@Param("taskNo")String taskNo,@Param("postedAt")LocalDateTime postedAt);
    @Update("UPDATE nx_simulated_task SET status='CANCELLED',cancel_reason=#{reason} WHERE task_no=#{taskNo} AND status='RUNNING'")
    int cancelTask(@Param("taskNo")String taskNo,@Param("reason")String reason);
    @Update("""
            UPDATE nx_simulated_device_runtime SET state='ERROR',last_error=#{error},next_action_at=#{retryAt}
            WHERE user_device_id=#{deviceId} AND NOT (state='STOPPED' AND last_error <=> 'PROMOTION_REFUND')
            """)
    int recordError(@Param("deviceId")long deviceId,@Param("error")String error,@Param("retryAt")LocalDateTime retryAt);
}

package ffdd.opsconsole.home.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Writes deterministic local-development settlements into the canonical business tables. */
@Mapper
public interface DevelopmentHomeSettlementMapper extends BaseMapper<Object> {

    @Select("""
            SELECT id FROM nx_user
             WHERE REPLACE(TRIM(COALESCE(country_code, '')), '+', '') = REPLACE(#{countryCode}, '+', '')
               AND phone=#{phone} AND sandbox=1
               AND UPPER(COALESCE(status, 'ACTIVE'))='ACTIVE' AND is_deleted=0
             LIMIT 1
            """)
    Long findDevelopmentUserId(@Param("countryCode") String countryCode, @Param("phone") String phone);

    @Select("""
            SELECT id FROM nx_user_device
             WHERE user_id=#{userId} AND is_deleted=0
               AND UPPER(ownership_status)='OWNED'
               AND UPPER(status) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND UPPER(COALESCE(source_channel,''))='DEVELOPMENT_HOME'
               AND UPPER(COALESCE(source_environment,''))='PRODUCTION'
               AND COALESCE(run_id,'')=''
             ORDER BY CASE WHEN activated_at IS NULL THEN 1 ELSE 0 END, id
             LIMIT 1
            """)
    Long findDevelopmentHomeDeviceId(@Param("userId") Long userId);

    @Insert("""
            INSERT IGNORE INTO nx_user_device(
              user_id,source_order_no,product_id,product_code,product_tier,instance_no,name,device_type,generation,
              gpu_model,vram_total_gb,base_power_w,dc_location,price_usdt_snapshot,ownership_status,source_channel,
              source_environment,run_id,status,hashrate,daily_usdt,daily_nex,last_seen_at,purchased_at,activated_at,
              pending_deactivate,row_version,created_at,updated_at,is_deleted)
            VALUES(
              #{userId},#{instanceNo},NULL,'phone','DEVELOPMENT',#{instanceNo},'Development phone','MOBILE',1,
              'Local accelerator',8,0,'User device',0,'OWNED','DEVELOPMENT_HOME',
              'PRODUCTION','','ACTIVE',0,0,0,NOW(6),NOW(6),NOW(6),0,0,NOW(6),NOW(6),0)
            """)
    int ensureDevelopmentDevice(@Param("userId") Long userId, @Param("instanceNo") String instanceNo);

    @Insert("""
            INSERT INTO nx_compute_task
              (task_no,user_id,user_device_id,task_type,task_name,model_name,reward_usdt,
               required_seconds,task_lock_minutes,source_environment,client_name,status,
               started_at,worker_ack_at,proof_consumed_at,completed_at,created_at,updated_at,is_deleted)
            VALUES
              (#{taskNo},#{userId},#{userDeviceId},#{taskType},#{taskName},#{modelName},#{rewardUsdt},
               #{requiredSeconds},0,#{sourceEnvironment},#{clientName},'COMPLETED',
               #{startedAt},#{startedAt},#{completedAt},#{completedAt},#{completedAt},#{completedAt},0)
            ON DUPLICATE KEY UPDATE
              user_device_id=VALUES(user_device_id), updated_at=VALUES(updated_at),
              reward_usdt=VALUES(reward_usdt)
            """)
    int insertCompletedTask(DevelopmentSettlement row);

    @Insert("""
            INSERT INTO nx_compute_receipt
              (user_id,user_device_id,task_no,receipt_no,task_type,client_name,reward_usdt,reward_nex,
               earning_status,source_environment,proof_hash,completed_at,created_at,updated_at,is_deleted)
            SELECT #{userId},#{userDeviceId},#{taskNo},#{receiptNo},#{taskType},#{clientName},#{rewardUsdt},0,
                   'SETTLED',#{sourceEnvironment},#{proofHash},#{completedAt},#{completedAt},#{completedAt},0
              FROM nx_compute_task t
             WHERE t.task_no=#{taskNo} AND t.user_id=#{userId} AND t.user_device_id=#{userDeviceId}
               AND t.status='COMPLETED' AND t.is_deleted=0
            ON DUPLICATE KEY UPDATE
              user_device_id=VALUES(user_device_id), updated_at=VALUES(updated_at),
              reward_usdt=VALUES(reward_usdt)
            """)
    int insertSettledReceipt(DevelopmentSettlement row);

    @Update("""
            UPDATE nx_compute_task
               SET user_device_id=#{userDeviceId}, reward_usdt=#{rewardUsdt}, updated_at=NOW(6)
             WHERE user_id=#{userId}
               AND task_no LIKE 'DEV-HOME-%'
               AND UPPER(COALESCE(source_environment,''))='PRODUCTION'
               AND UPPER(COALESCE(status,''))='COMPLETED' AND is_deleted=0
            """)
    int normalizeDevelopmentHomeTaskRewards(@Param("userId") Long userId,
                                            @Param("userDeviceId") Long userDeviceId,
                                            @Param("rewardUsdt") BigDecimal rewardUsdt);

    @Update("""
            UPDATE nx_compute_receipt
               SET user_device_id=#{userDeviceId}, reward_usdt=#{rewardUsdt}, updated_at=NOW(6)
             WHERE user_id=#{userId}
               AND task_no LIKE 'DEV-HOME-%' AND receipt_no LIKE 'R-DEV-HOME-%'
               AND UPPER(COALESCE(source_environment,''))='PRODUCTION'
               AND UPPER(COALESCE(earning_status,''))='SETTLED' AND is_deleted=0
            """)
    int normalizeDevelopmentHomeReceiptRewards(@Param("userId") Long userId,
                                               @Param("userDeviceId") Long userDeviceId,
                                               @Param("rewardUsdt") BigDecimal rewardUsdt);

    @Select("""
            SELECT d.user_id AS userId, d.id AS userDeviceId, d.instance_no AS instanceNo,
                   COALESCE(NULLIF(d.name, ''), NULLIF(d.product_code, ''), 'NexGrid Box') AS deviceName,
                   COALESCE(NULLIF(d.gpu_model, ''), NULLIF(d.product_code, ''), 'Nexion accelerator') AS modelName,
                   d.product_code AS productCode, d.device_type AS deviceType,
                   d.purchased_at AS purchasedAt, d.activated_at AS activatedAt,
                   GREATEST(COALESCE(d.daily_usdt, 0), 0) AS dailyUsdt
              FROM nx_user_device d
              JOIN nx_user u ON u.id=d.user_id AND u.sandbox=1
                            AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
              JOIN nx_order o ON o.order_no=d.source_order_no AND o.user_id=d.user_id AND o.is_deleted=0
             WHERE d.is_deleted=0 AND UPPER(COALESCE(d.ownership_status, ''))='OWNED'
               AND UPPER(COALESCE(d.status, '')) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND d.activated_at IS NOT NULL AND UPPER(COALESCE(d.source_channel, ''))='ORDER'
               AND UPPER(COALESCE(d.source_environment, ''))='PRODUCTION' AND COALESCE(d.run_id, '')=''
               AND UPPER(COALESCE(o.payment_status, ''))='PAID'
               AND UPPER(COALESCE(o.order_status, ''))='COMPLETED'
               AND UPPER(COALESCE(o.activation_status, ''))='ACTIVATED'
             ORDER BY d.user_id, d.id
            """)
    List<DevelopmentDeviceTarget> developmentPurchasedDevices();

    @Select("""
            SELECT d.user_id AS userId, d.id AS userDeviceId, d.instance_no AS instanceNo,
                   COALESCE(NULLIF(d.name, ''), NULLIF(d.product_code, ''), 'NexGrid device') AS deviceName,
                   COALESCE(NULLIF(d.gpu_model, ''), NULLIF(d.product_code, ''), 'Nexion accelerator') AS modelName,
                   d.product_code AS productCode, d.device_type AS deviceType,
                   COALESCE(d.vram_total_gb, 0) AS vramTotalGb,
                   d.purchased_at AS purchasedAt, d.activated_at AS activatedAt,
                   GREATEST(COALESCE(d.daily_usdt, 0), 0) AS dailyUsdt
              FROM nx_user_device d
              JOIN nx_user u ON u.id=d.user_id AND u.sandbox=1
                            AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
             WHERE d.is_deleted=0 AND UPPER(COALESCE(d.ownership_status, ''))='OWNED'
               AND UPPER(COALESCE(d.status, '')) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND d.activated_at IS NOT NULL
               AND UPPER(COALESCE(d.source_environment, ''))='PRODUCTION' AND COALESCE(d.run_id, '')=''
               AND (
                    UPPER(COALESCE(d.source_channel, ''))='DEVELOPMENT_HOME'
                    OR (UPPER(COALESCE(d.source_channel, ''))='ORDER'
                        AND EXISTS (SELECT 1 FROM nx_order o
                                     WHERE o.order_no=d.source_order_no AND o.user_id=d.user_id
                                       AND UPPER(COALESCE(o.payment_status, ''))='PAID'
                                       AND UPPER(COALESCE(o.order_status, ''))='COMPLETED'
                                       AND UPPER(COALESCE(o.activation_status, ''))='ACTIVATED'
                                       AND o.is_deleted=0))
               )
             ORDER BY d.user_id, d.id
            """)
    List<DevelopmentTaskDevice> developmentTaskDevices();

    @Select("""
            SELECT task_id AS taskId, name, UPPER(TRIM(task_class)) AS taskClass,
                   model_name AS modelName, min_reward AS minReward, max_reward AS maxReward,
                   CASE WHEN UPPER(min_vram) REGEXP '^(0|[1-9][0-9]{0,3})(GB)?$'
                         THEN CAST(min_vram AS UNSIGNED) ELSE NULL END AS minVram
              FROM nx_admin_device_task
             WHERE is_deleted=0 AND LOWER(TRIM(status))='active'
               AND LOWER(TRIM(COALESCE(kill_init, ''))) NOT IN ('kill','killed','已 kill','已kill')
             ORDER BY minVram, updated_at DESC, id DESC
            """)
    List<DevelopmentTaskConfig> developmentTaskPool();

    @Select("""
            SELECT d.user_id AS userId, d.id AS userDeviceId, d.instance_no AS instanceNo,
                   COALESCE(NULLIF(d.name, ''), NULLIF(d.product_code, ''), 'NexGrid device') AS deviceName,
                   COALESCE(NULLIF(d.gpu_model, ''), NULLIF(d.product_code, ''), 'Nexion accelerator') AS modelName,
                   d.product_code AS productCode, d.device_type AS deviceType,
                   COALESCE(d.vram_total_gb, 0) AS vramTotalGb,
                   d.purchased_at AS purchasedAt, d.activated_at AS activatedAt,
                   GREATEST(COALESCE(d.daily_usdt, 0), 0) AS dailyUsdt
              FROM nx_user_device d
              JOIN nx_user u ON u.id=d.user_id AND u.sandbox=1
                            AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
             WHERE d.id=#{userDeviceId} AND d.user_id=#{userId} AND d.is_deleted=0
               AND UPPER(COALESCE(d.ownership_status, ''))='OWNED'
               AND UPPER(COALESCE(d.status, '')) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND d.activated_at IS NOT NULL
                AND UPPER(COALESCE(d.source_environment, ''))='PRODUCTION'
                AND COALESCE(d.run_id, '')=''
                AND (
                     UPPER(COALESCE(d.source_channel, ''))='DEVELOPMENT_HOME'
                     OR (UPPER(COALESCE(d.source_channel, ''))='ORDER'
                         AND EXISTS (SELECT 1 FROM nx_order o
                                      WHERE o.order_no=d.source_order_no AND o.user_id=d.user_id
                                        AND UPPER(COALESCE(o.payment_status, ''))='PAID'
                                        AND UPPER(COALESCE(o.order_status, ''))='COMPLETED'
                                        AND UPPER(COALESCE(o.activation_status, ''))='ACTIVATED'
                                        AND o.is_deleted=0))
                )
              LIMIT 1 FOR UPDATE
            """)
    DevelopmentTaskDevice lockDevelopmentTaskDevice(@Param("userId") Long userId,
                                                     @Param("userDeviceId") Long userDeviceId);

    @Select("""
            SELECT t.task_no AS taskNo, t.user_id AS userId, t.user_device_id AS userDeviceId,
                   t.task_config_id AS taskId, t.task_name AS taskName, t.task_type AS taskClass,
                   t.model_name AS modelName, t.client_name AS clientName, t.reward_usdt AS rewardUsdt,
                   t.required_seconds AS requiredSeconds, t.started_at AS startedAt
              FROM nx_compute_task t
              JOIN nx_user u ON u.id=t.user_id AND u.sandbox=1
                            AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
             WHERE t.user_id=#{userId} AND t.user_device_id=#{userDeviceId}
               AND t.source_environment='PRODUCTION' AND t.status IN ('CLAIMED','RUNNING')
               AND t.task_no LIKE 'DEV-TASK-%' AND t.is_deleted=0
             ORDER BY t.created_at DESC, t.id DESC LIMIT 1 FOR UPDATE
            """)
    DevelopmentActiveTask lockDevelopmentActiveTask(@Param("userId") Long userId,
                                                     @Param("userDeviceId") Long userDeviceId);

    @Select("""
            SELECT COUNT(*) FROM nx_compute_task t
              JOIN nx_user u ON u.id=t.user_id AND u.sandbox=1 AND u.is_deleted=0
             WHERE t.user_id=#{userId} AND t.user_device_id=#{userDeviceId}
               AND t.task_no LIKE 'DEV-TASK-%' AND t.status='COMPLETED'
               AND t.source_environment='PRODUCTION' AND t.is_deleted=0
            """)
    long developmentCompletedTaskCount(@Param("userId") Long userId,
                                       @Param("userDeviceId") Long userDeviceId);

    @Insert("""
            INSERT INTO nx_compute_task(
              task_no,user_id,user_device_id,task_type,task_config_id,task_name,model_name,
              reward_usdt,required_seconds,task_lock_minutes,completion_nonce,proof_expires_at,
              source_environment,client_name,status,started_at,worker_ack_at,lease_expires_at,
              attempt_count,max_attempts,created_at,updated_at,is_deleted)
            SELECT #{taskNo},#{userId},#{userDeviceId},#{taskClass},#{taskId},#{taskName},#{modelName},
                   #{rewardUsdt},#{requiredSeconds},0,#{completionNonce},#{leaseExpiresAt},
                   'PRODUCTION',#{clientName},'RUNNING',#{startedAt},#{startedAt},#{leaseExpiresAt},
                   1,3,#{startedAt},#{startedAt},0
              FROM nx_user_device d
              JOIN nx_user u ON u.id=d.user_id AND u.sandbox=1
                            AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
             WHERE d.id=#{userDeviceId} AND d.user_id=#{userId} AND d.is_deleted=0
               AND UPPER(COALESCE(d.ownership_status, ''))='OWNED'
               AND UPPER(COALESCE(d.status, '')) IN ('ACTIVE','ONLINE','BUSY','RUNNING')
               AND d.activated_at IS NOT NULL AND UPPER(COALESCE(d.source_environment, ''))='PRODUCTION'
               AND COALESCE(d.run_id, '')=''
               AND NOT EXISTS (SELECT 1 FROM nx_compute_task active
                                WHERE active.user_id=#{userId} AND active.user_device_id=#{userDeviceId}
                                  AND active.source_environment='PRODUCTION'
                                  AND active.status IN ('CLAIMED','RUNNING') AND active.is_deleted=0)
            """)
    int insertDevelopmentRunningTask(DevelopmentRunningTask row);

    @Update("""
            UPDATE nx_compute_task t
              JOIN nx_user u ON u.id=t.user_id AND u.sandbox=1
                            AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
               SET t.status='COMPLETED',t.proof_consumed_at=#{completedAt},t.completed_at=#{completedAt},
                   t.updated_at=#{completedAt}
             WHERE t.task_no=#{taskNo} AND t.user_id=#{userId} AND t.user_device_id=#{userDeviceId}
               AND t.task_no LIKE 'DEV-TASK-%' AND t.source_environment='PRODUCTION'
               AND t.status IN ('CLAIMED','RUNNING') AND t.proof_consumed_at IS NULL AND t.is_deleted=0
               AND TIMESTAMPADD(SECOND, t.required_seconds, t.started_at) <= #{completedAt}
            """)
    int completeDevelopmentTask(@Param("userId") Long userId,
                                @Param("userDeviceId") Long userDeviceId,
                                @Param("taskNo") String taskNo,
                                @Param("completedAt") LocalDateTime completedAt);

    @Insert("""
            INSERT IGNORE INTO nx_compute_receipt(
              user_id,user_device_id,task_no,receipt_no,task_type,client_name,reward_usdt,reward_nex,
              earning_status,source_environment,proof_hash,completed_at,created_at,updated_at,is_deleted)
            SELECT #{userId},#{userDeviceId},#{taskNo},#{receiptNo},#{taskClass},#{clientName},
                   #{rewardUsdt},0,'SETTLED','PRODUCTION',#{proofHash},#{completedAt},#{completedAt},#{completedAt},0
              FROM nx_compute_task t
              JOIN nx_user u ON u.id=t.user_id AND u.sandbox=1
                            AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
             WHERE t.task_no=#{taskNo} AND t.user_id=#{userId} AND t.user_device_id=#{userDeviceId}
               AND t.task_no LIKE 'DEV-TASK-%' AND t.status='COMPLETED'
               AND t.source_environment='PRODUCTION' AND t.is_deleted=0
            """)
    int insertDevelopmentTaskReceipt(DevelopmentTaskSettlement row);

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
               AND is_deleted=0
             ORDER BY config_key
            """)
    List<DevelopmentE3CapacityConfig> developmentE3CapacityConfig();

    @Insert("""
            INSERT IGNORE INTO nx_user_wallet(
              user_id,usdt_available,nex_available,pending_withdraw,lifetime_earned,
              cumulative_deposit_usdt,version,created_at,updated_at,is_deleted)
            SELECT #{userId},0,0,0,0,0,0,#{now},#{now},0
              FROM nx_user u
             WHERE u.id=#{userId} AND u.sandbox=1
               AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
            """)
    int ensureDevelopmentWallet(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT IGNORE INTO nx_compute_task
              (task_no,user_id,user_device_id,task_type,task_name,model_name,reward_usdt,
               required_seconds,task_lock_minutes,source_environment,client_name,status,
               started_at,worker_ack_at,proof_consumed_at,completed_at,created_at,updated_at,is_deleted)
            SELECT #{taskNo},#{userId},#{userDeviceId},'LLM_INFERENCE','Development device compute settlement',
                   #{modelName},#{rewardUsdt},60,0,'PRODUCTION','NexGrid Development Workload','COMPLETED',
                   #{startedAt},#{startedAt},#{completedAt},#{completedAt},#{completedAt},#{completedAt},0
              FROM nx_user_device d
              JOIN nx_user u ON u.id=d.user_id AND u.sandbox=1
             WHERE d.id=#{userDeviceId} AND d.user_id=#{userId} AND d.is_deleted=0
               AND UPPER(COALESCE(d.ownership_status, ''))='OWNED' AND d.activated_at IS NOT NULL
               AND UPPER(COALESCE(d.source_channel, ''))='ORDER'
               AND UPPER(COALESCE(d.source_environment, ''))='PRODUCTION' AND COALESCE(d.run_id, '')=''
               AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
            """)
    int insertDevelopmentPurchasedTask(DevelopmentPurchasedSettlement row);

    @Insert("""
            INSERT IGNORE INTO nx_compute_receipt
              (user_id,user_device_id,task_no,receipt_no,task_type,client_name,reward_usdt,reward_nex,
               earning_status,source_environment,proof_hash,completed_at,created_at,updated_at,is_deleted)
            SELECT #{userId},#{userDeviceId},#{taskNo},#{receiptNo},'LLM_INFERENCE',
                   'NexGrid Development Workload',#{rewardUsdt},0,'SETTLED','PRODUCTION',#{proofHash},
                   #{completedAt},#{completedAt},#{completedAt},0
              FROM nx_compute_task t
             WHERE t.task_no=#{taskNo} AND t.user_id=#{userId} AND t.user_device_id=#{userDeviceId}
               AND t.status='COMPLETED' AND t.is_deleted=0
            """)
    int insertDevelopmentPurchasedReceipt(DevelopmentPurchasedSettlement row);

    @Update("""
            UPDATE nx_compute_task t
              JOIN nx_user u ON u.id=t.user_id AND u.sandbox=1
                           AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
               SET t.started_at=CASE WHEN t.started_at IS NULL OR t.started_at<#{dayStart}
                                     THEN #{dayStart} ELSE t.started_at END,
                   t.worker_ack_at=CASE WHEN t.worker_ack_at IS NULL OR t.worker_ack_at<#{dayStart}
                                        THEN #{dayStart} ELSE t.worker_ack_at END,
                   t.proof_consumed_at=CASE WHEN t.proof_consumed_at IS NULL OR t.proof_consumed_at<#{dayStart}
                                           THEN #{dayStart} ELSE t.proof_consumed_at END,
                   t.completed_at=#{dayStart},
                   t.created_at=CASE WHEN t.created_at<#{dayStart} THEN #{dayStart} ELSE t.created_at END,
                   t.updated_at=NOW(6)
             WHERE t.user_id=#{userId} AND t.user_device_id=#{userDeviceId} AND t.task_no=#{taskNo}
               AND UPPER(COALESCE(t.status, ''))='COMPLETED'
               AND UPPER(COALESCE(t.source_environment, ''))='PRODUCTION'
               AND t.completed_at<#{dayStart} AND t.is_deleted=0
            """)
    int repairDevelopmentPurchasedTaskBusinessDay(@Param("userId") Long userId,
                                                   @Param("userDeviceId") Long userDeviceId,
                                                   @Param("taskNo") String taskNo,
                                                   @Param("dayStart") LocalDateTime dayStart);

    @Update("""
            UPDATE nx_compute_receipt r
              JOIN nx_user u ON u.id=r.user_id AND u.sandbox=1
                           AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
               SET r.completed_at=#{dayStart},
                   r.created_at=CASE WHEN r.created_at<#{dayStart} THEN #{dayStart} ELSE r.created_at END,
                   r.updated_at=NOW(6)
             WHERE r.user_id=#{userId} AND r.user_device_id=#{userDeviceId}
               AND r.task_no=#{taskNo} AND r.receipt_no=#{receiptNo}
               AND UPPER(COALESCE(r.earning_status, ''))='SETTLED'
               AND UPPER(COALESCE(r.source_environment, ''))='PRODUCTION'
               AND r.completed_at<#{dayStart} AND r.is_deleted=0
            """)
    int repairDevelopmentPurchasedReceiptBusinessDay(@Param("userId") Long userId,
                                                      @Param("userDeviceId") Long userDeviceId,
                                                      @Param("taskNo") String taskNo,
                                                      @Param("receiptNo") String receiptNo,
                                                      @Param("dayStart") LocalDateTime dayStart);

    @Update("""
            UPDATE nx_user_wallet w
              JOIN nx_user u ON u.id=w.user_id AND u.sandbox=1
               SET w.usdt_available=w.usdt_available+#{amount},
                   w.lifetime_earned=w.lifetime_earned+#{amount},
                   w.version=w.version+1,w.updated_at=#{now}
             WHERE w.user_id=#{userId} AND w.is_deleted=0
               AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
               AND NOT EXISTS (SELECT 1 FROM nx_wallet_ledger l
                                WHERE l.biz_no=#{taskNo} AND l.asset='USDT'
                                  AND l.direction='IN' AND l.is_deleted=0)
            """)
    int creditDevelopmentWallet(@Param("userId") Long userId,
                                @Param("taskNo") String taskNo,
                                @Param("amount") BigDecimal amount,
                                @Param("now") LocalDateTime now);

    @Select("""
            SELECT w.usdt_available
              FROM nx_user_wallet w
              JOIN nx_user u ON u.id=w.user_id AND u.sandbox=1
             WHERE w.user_id=#{userId} AND w.is_deleted=0
               AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
             LIMIT 1
            """)
    BigDecimal developmentWalletUsdt(@Param("userId") Long userId);

    @Insert("""
            INSERT IGNORE INTO nx_wallet_ledger
              (user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,
               created_at,updated_at,is_deleted)
            SELECT #{userId},#{taskNo},'COMPUTE_TASK_REWARD','USDT','IN',#{amount},#{balanceAfter},
                   'SUCCESS','development server-authoritative compute task reward',#{now},#{now},0
              FROM nx_user u
             WHERE u.id=#{userId} AND u.sandbox=1
               AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
            """)
    int insertDevelopmentWalletLedger(@Param("userId") Long userId,
                                      @Param("taskNo") String taskNo,
                                      @Param("amount") BigDecimal amount,
                                      @Param("balanceAfter") BigDecimal balanceAfter,
                                      @Param("now") LocalDateTime now);

    @Insert("""
            INSERT IGNORE INTO nx_earning_event
              (event_no,user_id,user_device_id,receipt_no,asset,amount,status,wallet_posted_at,
               created_at,updated_at,is_deleted)
            SELECT #{eventNo},#{userId},#{userDeviceId},#{receiptNo},'USDT',#{amount},'POSTED',
                   #{now},#{now},#{now},0
              FROM nx_user u
             WHERE u.id=#{userId} AND u.sandbox=1
               AND UPPER(COALESCE(u.status, 'ACTIVE'))='ACTIVE' AND u.is_deleted=0
            """)
    int insertDevelopmentEarningEvent(@Param("eventNo") String eventNo,
                                      @Param("userId") Long userId,
                                      @Param("userDeviceId") Long userDeviceId,
                                      @Param("receiptNo") String receiptNo,
                                      @Param("amount") BigDecimal amount,
                                      @Param("now") LocalDateTime now);

    record DevelopmentSettlement(
            String taskNo,
            String receiptNo,
            Long userId,
            Long userDeviceId,
            String taskType,
            String taskName,
            String modelName,
            String clientName,
            BigDecimal rewardUsdt,
            int requiredSeconds,
            String sourceEnvironment,
            String proofHash,
            LocalDateTime startedAt,
            LocalDateTime completedAt) {
    }

    record DevelopmentDeviceTarget(
            Long userId,
            Long userDeviceId,
            String instanceNo,
            String deviceName,
            String modelName,
            String productCode,
            String deviceType,
            LocalDateTime purchasedAt,
            LocalDateTime activatedAt,
            BigDecimal dailyUsdt) {
    }

    record DevelopmentE3CapacityConfig(String configKey, String configValue) {
    }

    record DevelopmentTaskDevice(
            Long userId,
            Long userDeviceId,
            String instanceNo,
            String deviceName,
            String modelName,
            String productCode,
            String deviceType,
            Integer vramTotalGb,
            LocalDateTime purchasedAt,
            LocalDateTime activatedAt,
            BigDecimal dailyUsdt) {
    }

    record DevelopmentTaskConfig(
            String taskId,
            String name,
            String taskClass,
            String modelName,
            BigDecimal minReward,
            BigDecimal maxReward,
            Integer minVram) {
    }

    record DevelopmentActiveTask(
            String taskNo,
            Long userId,
            Long userDeviceId,
            String taskId,
            String taskName,
            String taskClass,
            String modelName,
            String clientName,
            BigDecimal rewardUsdt,
            Integer requiredSeconds,
            LocalDateTime startedAt) {
    }

    record DevelopmentRunningTask(
            String taskNo,
            Long userId,
            Long userDeviceId,
            String taskId,
            String taskName,
            String taskClass,
            String modelName,
            String clientName,
            BigDecimal rewardUsdt,
            Integer requiredSeconds,
            String completionNonce,
            LocalDateTime startedAt,
            LocalDateTime leaseExpiresAt) {
    }

    record DevelopmentTaskSettlement(
            String taskNo,
            String receiptNo,
            Long userId,
            Long userDeviceId,
            String taskClass,
            String clientName,
            BigDecimal rewardUsdt,
            String proofHash,
            LocalDateTime completedAt) {
    }

    record DevelopmentPurchasedSettlement(
            String taskNo,
            String receiptNo,
            Long userId,
            Long userDeviceId,
            String modelName,
            BigDecimal rewardUsdt,
            String proofHash,
            LocalDateTime startedAt,
            LocalDateTime completedAt) {
    }
}

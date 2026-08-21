package ffdd.opsconsole.home.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

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
            ON DUPLICATE KEY UPDATE task_no=VALUES(task_no)
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
            ON DUPLICATE KEY UPDATE receipt_no=VALUES(receipt_no)
            """)
    int insertSettledReceipt(DevelopmentSettlement row);

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
}

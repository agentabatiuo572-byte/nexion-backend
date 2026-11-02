package ffdd.opsconsole.device.mapper;

import ffdd.opsconsole.device.mapper.SimulatedTaskMapper.TaskRow;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;
import org.apache.ibatis.annotations.*;

/** Writes original task receipts and wallet records inside the simulated device transaction. */
@Mapper
public interface SimulatedTaskSettlementMapper {
    @Select("SELECT id,fund_incarnation incarnation FROM nx_user_wallet WHERE user_id=#{userId} AND sandbox=0 AND is_deleted=0 FOR UPDATE")
    Map<String,Object> wallet(@Param("userId") long userId);

    @Insert("""
        INSERT INTO nx_compute_task(task_no,user_id,user_device_id,task_type,task_config_id,task_name,
          model_name,reward_usdt,required_seconds,task_lock_minutes,source_environment,client_name,status,
          started_at,completed_at,created_at,updated_at,is_deleted)
        VALUES(#{t.taskNo},#{t.userId},#{t.deviceId},#{t.taskClass},#{t.taskTypeId},#{t.taskName},NULL,
          #{usdt},#{t.durationSeconds},0,'PRODUCTION','UVEL','COMPLETED',#{t.startedAt},#{t.dueAt},#{t.startedAt},#{posted},0)
        """)
    int task(@Param("t") TaskRow task,@Param("usdt") BigDecimal usdt,@Param("posted") LocalDateTime posted);

    @Insert("""
        INSERT INTO nx_compute_receipt(user_id,user_device_id,task_no,receipt_no,task_type,client_name,
          reward_usdt,reward_nex,earning_status,source_environment,proof_hash,completed_at,created_at,updated_at,is_deleted)
        VALUES(#{t.userId},#{t.deviceId},#{t.taskNo},#{t.receiptNo},#{t.taskClass},'UVEL',#{usdt},#{nex},
          'CREDITED','PRODUCTION',NULL,#{t.dueAt},#{posted},#{posted},0)
        """)
    int receipt(@Param("t") TaskRow task,@Param("usdt") BigDecimal usdt,@Param("nex") BigDecimal nex,@Param("posted") LocalDateTime posted);

    @Update("""
        <script>UPDATE nx_user_wallet SET
        <choose><when test='asset == "USDT"'>usdt_available=usdt_available+#{amount}</when>
        <otherwise>nex_available=nex_available+#{amount}</otherwise></choose>,
        lifetime_earned=lifetime_earned+#{amount},version=version+1,updated_at=#{posted}
        WHERE id=#{t.walletId} AND user_id=#{t.userId} AND fund_incarnation=#{t.walletIncarnation}
          AND sandbox=0 AND is_deleted=0 AND EXISTS(SELECT 1 FROM nx_user u
            WHERE u.id=#{t.userId} AND u.status='ACTIVE' AND u.sandbox=0 AND u.is_deleted=0)
        </script>
        """)
    int credit(@Param("t") TaskRow task,@Param("asset") String asset,@Param("amount") BigDecimal amount,@Param("posted") LocalDateTime posted);

    @Select("SELECT usdt_available FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0")
    BigDecimal usdt(@Param("userId") long userId);
    @Select("SELECT nex_available FROM nx_user_wallet WHERE user_id=#{userId} AND is_deleted=0")
    BigDecimal nex(@Param("userId") long userId);

    @Insert("""
        INSERT INTO nx_wallet_ledger(user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,
          remark,created_at,updated_at,is_deleted)
        VALUES(#{userId},#{bizNo},'COMPUTE_TASK_REWARD',#{asset},'IN',#{amount},#{balance},'SUCCESS',
          'server-authoritative task reward',#{posted},#{posted},0)
        """)
    int ledger(@Param("userId") long userId,@Param("bizNo") String bizNo,@Param("asset") String asset,
            @Param("amount") BigDecimal amount,@Param("balance") BigDecimal balance,@Param("posted") LocalDateTime posted);

    @Insert("""
        INSERT INTO nx_earning_event(event_no,user_id,user_device_id,receipt_no,asset,amount,status,
          wallet_posted_at,created_at,updated_at,is_deleted)
        VALUES(#{eventNo},#{t.userId},#{t.deviceId},#{t.receiptNo},#{asset},#{amount},'POSTED',#{posted},#{posted},#{posted},0)
        """)
    int earning(@Param("t") TaskRow task,@Param("eventNo") String eventNo,@Param("asset") String asset,
            @Param("amount") BigDecimal amount,@Param("posted") LocalDateTime posted);
}

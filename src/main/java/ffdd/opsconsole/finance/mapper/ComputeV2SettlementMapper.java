package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import org.apache.ibatis.annotations.*;

/** Explicit SQL ownership for V2 posting and independent originals. No caller-supplied SQL is executed. */
@Mapper
public interface ComputeV2SettlementMapper extends BaseMapper<ComputeV2EventEntity> {
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("SELECT id,status,sandbox,is_deleted FROM nx_user WHERE id=#{user} FOR UPDATE")
    List<Map<String,Object>> lockUser(@Param("user") long user);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM nx_compute_v2_checkpoint WHERE batch_id=#{id}")
    List<Map<String,Object>> checkpoint(@Param("id") String id);

    @Select("SELECT UTC_TIMESTAMP(6)")
    LocalDateTime nowUtc();

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT event_id,payload_sha256 FROM nx_compute_v2_event WHERE event_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> FOR UPDATE</script>")
    List<Map<String,Object>> lockEvents(@Param("ids") List<String> ids);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT payload FROM nx_compute_v2_event WHERE task_no IN <foreach collection='tasks' item='task' open='(' separator=',' close=')'>#{task}</foreach> ORDER BY task_no,occurred_at_utc,event_id FOR UPDATE</script>")
    List<Map<String,Object>> taskHistory(@Param("tasks") List<String> tasks);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT id,user_id,instance_no,source_environment,run_id,is_deleted FROM nx_user_device WHERE id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY id FOR UPDATE</script>")
    List<Map<String,Object>> lockDevices(@Param("ids") List<Long> ids);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("SELECT id,user_id customerId,fund_incarnation incarnation,fund_revision revision,version,usdt_available usdtAvailable,nex_available nexAvailable,cregis_risk_held cregisRiskHeld,pending_withdraw pendingWithdraw,sandbox,is_deleted deleted,created_at createdAt FROM nx_user_wallet WHERE user_id=#{user} FOR UPDATE")
    List<Map<String,Object>> wallet(@Param("user") long user);

    @Update("""
        <script>UPDATE nx_user_wallet SET
        <choose><when test='asset == "USDT"'>usdt_available=usdt_available+#{amount}</when>
        <otherwise>nex_available=nex_available+#{amount}</otherwise></choose>,
        lifetime_earned=lifetime_earned+#{amount},version=version+1,updated_at=#{posted}
        WHERE id=#{wallet} AND user_id=#{user} AND fund_incarnation=#{incarnation} AND fund_revision=#{revision}
          AND sandbox=0 AND is_deleted=0 AND #{asset} IN ('USDT','UVT')</script>
        """)
    int creditWallet(@Param("wallet") long wallet,@Param("user") long user,@Param("incarnation") String incarnation,
            @Param("revision") long revision,@Param("asset") String asset,@Param("amount") BigDecimal amount,@Param("posted") LocalDateTime posted);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT allocation_id,task_no,asset,amount_micros,event_id FROM nx_compute_v2_allocation WHERE task_no IN <foreach collection='tasks' item='task' open='(' separator=',' close=')'>#{task}</foreach> ORDER BY task_no,asset</script>")
    List<Map<String,Object>> allocationsForTasks(@Param("tasks") List<String> tasks);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM nx_compute_v2_settlement WHERE settlement_id=#{id}")
    List<Map<String,Object>> settlement(@Param("id") String id);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("SELECT * FROM nx_compute_v2_allocation WHERE settlement_id=#{id} ORDER BY allocation_id")
    List<Map<String,Object>> allocations(@Param("id") String id);

    @Select("SELECT COALESCE((SELECT config_value FROM nx_config_item WHERE config_key='growth.phase.current' AND status=1 AND is_deleted=0 LIMIT 1),'P1') phase,GREATEST(TIMESTAMPDIFF(MONTH,created_at,NOW()),0) age,DATE_FORMAT(created_at,'%x-W%v') cohort FROM nx_user WHERE id=#{user}")
    List<Map<String,Object>> attribution(@Param("user") long user);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("""
        SELECT e.payload FROM nx_compute_v2_event e
        JOIN (SELECT current_event.event_id FROM nx_compute_v2_event current_event
            JOIN (SELECT user_device_id,MAX(generation) generation FROM nx_compute_v2_event
                  WHERE deployment_scope=#{scope} GROUP BY user_device_id) latest
              ON current_event.deployment_scope=#{scope} AND current_event.user_device_id=latest.user_device_id
                 AND current_event.generation=latest.generation
            UNION
            SELECT a.event_id FROM nx_compute_v2_allocation a
            JOIN nx_compute_v2_event daily_event ON daily_event.event_id=a.event_id AND daily_event.deployment_scope=#{scope}
            WHERE a.asset='UVT' AND a.completed_at_utc>=UTC_DATE()) needed ON needed.event_id=e.event_id
        ORDER BY e.occurred_at_utc,e.event_id
        """)
    List<Map<String,Object>> recover(@Param("scope") String scope);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT * FROM nx_compute_task WHERE task_no IN <foreach collection='tasks' item='task' open='(' separator=',' close=')'>#{task}</foreach> ORDER BY id</script>")
    List<Map<String,Object>> originalTasks(@Param("tasks") List<String> tasks);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT * FROM nx_compute_receipt WHERE receipt_no IN <foreach collection='receipts' item='receipt' open='(' separator=',' close=')'>#{receipt}</foreach> ORDER BY id</script>")
    List<Map<String,Object>> originalReceipts(@Param("receipts") List<String> receipts);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT * FROM nx_wallet_ledger WHERE biz_no IN <foreach collection='numbers' item='number' open='(' separator=',' close=')'>#{number}</foreach> AND asset=#{asset} ORDER BY id</script>")
    List<Map<String,Object>> originalLedgers(@Param("numbers") List<String> numbers,@Param("asset") String asset);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT * FROM nx_earning_event WHERE receipt_no IN <foreach collection='receipts' item='receipt' open='(' separator=',' close=')'>#{receipt}</foreach> AND asset=#{asset} ORDER BY id</script>")
    List<Map<String,Object>> originalEarnings(@Param("receipts") List<String> receipts,@Param("asset") String asset);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("SELECT payload,payload_sha256 FROM nx_compute_v2_event WHERE event_id=#{id}")
    List<Map<String,Object>> event(@Param("id") String id);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("SELECT payload,payload_sha256 FROM nx_compute_v2_event WHERE user_id=#{user} AND user_device_id=#{device} AND request_id=#{request} AND kind='ISSUED'")
    List<Map<String,Object>> issued(@Param("user") long user,@Param("device") long device,@Param("request") String request);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("<script>SELECT settlement_id FROM nx_compute_v2_settlement WHERE user_id IN <foreach collection='users' item='user' open='(' separator=',' close=')'>#{user}</foreach></script>")
    List<Map<String,Object>> settlementsForUsers(@Param("users") List<Long> users);

    /** Only these fixed posting tables/columns are writable; every value remains a bound parameter. */
    enum WriteTarget {
        TASKS("nx_compute_task","task_no,user_id,user_device_id,task_type,task_config_id,task_name,model_name,reward_usdt,required_seconds,task_lock_minutes,proof_expires_at,proof_consumed_at,source_environment,client_name,status,started_at,worker_ack_at,lease_expires_at,attempt_count,max_attempts,completed_at,created_at,updated_at,is_deleted"),
        RECEIPTS("nx_compute_receipt","user_id,user_device_id,task_no,receipt_no,task_type,client_name,reward_usdt,reward_nex,earning_status,source_environment,proof_hash,completed_at,created_at,updated_at,is_deleted"),
        EVENTS("nx_compute_v2_event","event_id,batch_id,deployment_scope,task_no,generation,user_id,user_device_id,kind,occurred_at_utc,payload_sha256,payload"),
        ALLOCATIONS("nx_compute_v2_allocation","allocation_id,settlement_id,event_id,task_no,receipt_no,user_id,user_device_id,asset,amount_micros,daily_entitlement_key,completed_at_utc,posted_at_utc"),
        LEDGERS("nx_wallet_ledger","user_id,biz_no,biz_type,asset,direction,amount,balance_after,status,remark,created_at,updated_at,is_deleted"),
        EARNINGS("nx_earning_event","event_no,user_id,user_device_id,receipt_no,asset,amount,status,wallet_posted_at,created_at,updated_at,is_deleted"),
        SETTLEMENTS("nx_compute_v2_settlement","settlement_id,batch_id,user_id,asset,total_micros,wallet_id,wallet_incarnation,before_revision,after_revision,operation_key,window_start_utc,window_end_utc,posted_at_utc"),
        CHECKPOINTS("nx_compute_v2_checkpoint","batch_id,user_id,content_sha256,event_ids,allocation_ids,window_start_utc,window_end_utc,posted_at_utc");
        final String table,columns;
        WriteTarget(String table,String columns){this.table=table;this.columns=columns;}
    }

    @InsertProvider(type=Sql.class,method="insertRows")
    int insertRows(@Param("target") WriteTarget target,@Param("rows") List<Map<String,Object>> rows);

    final class Sql {
        public static String insertRows(Map<String,Object> parameters){
            var target=(WriteTarget)parameters.get("target");
            var rows=(List<?>)parameters.get("rows");
            int count=target.columns.split(",").length;
            var values=new ArrayList<String>();
            for(int i=0;i<rows.size();i++){
                if(((Map<?,?>)rows.get(i)).size()!=count)throw new IllegalArgumentException("Compute posting row shape changed");
                var columns=new ArrayList<String>();
                for(int j=0;j<count;j++)columns.add("#{rows["+i+"].c"+j+"}");
                values.add("("+String.join(",",columns)+")");
            }
            return "INSERT INTO "+target.table+"("+target.columns+") VALUES"+String.join(",",values);
        }
    }
}

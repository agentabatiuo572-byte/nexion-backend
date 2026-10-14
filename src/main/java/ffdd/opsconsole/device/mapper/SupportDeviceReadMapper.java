package ffdd.opsconsole.device.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Ordinary snapshot reads only; ownership is classified without dropping unknown or stopped-owned rows. */
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportDeviceReadMapper {
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("""
        <script>SELECT r.id,r.user_id customerId,r.user_device_id deviceId,r.task_no taskNo,
        r.receipt_no receiptNo,r.task_type taskType,r.client_name clientName,r.reward_usdt rewardUsdt,
        r.reward_nex rewardNex,r.earning_status earningStatus,r.source_environment sourceEnvironment,
        r.proof_hash proofHash,r.completed_at completedAt,r.created_at createdAt,r.updated_at updatedAt,
        r.is_deleted deleted,u.sandbox userSandbox,u.is_deleted userDeleted
        FROM nx_compute_receipt r LEFT JOIN nx_user u ON u.id=r.user_id WHERE r.user_id IN
        <foreach collection='scope' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY r.id</script>
        """)
    List<Map<String,Object>> fundReceipts(@Param("scope") List<Long> scope);
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("""
        <script>SELECT t.id,t.user_id customerId,t.user_device_id deviceId,t.task_no taskNo,t.task_config_id taskId,t.task_type taskType,
        t.reward_usdt rewardUsdt,t.status,t.source_environment sourceEnvironment,t.client_name clientName,
        t.started_at startedAt,t.completed_at completedAt,t.proof_consumed_at proofConsumedAt,t.is_deleted deleted
        FROM nx_compute_task t WHERE t.user_id IN
        <foreach collection='scope' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY t.id</script>
        """)
    List<Map<String,Object>> fundTasks(@Param("scope") List<Long> scope);
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("""
        <script>SELECT l.id,l.user_id customerId,l.biz_no bizNo,l.biz_type bizType,l.asset,l.direction,
        l.amount,l.balance_after balanceAfter,l.status,l.created_at createdAt,l.updated_at updatedAt,l.is_deleted deleted
        FROM nx_wallet_ledger l WHERE l.user_id IN
        <foreach collection='scope' item='id' open='(' separator=',' close=')'>#{id}</foreach>
        AND (l.biz_type='COMPUTE_TASK_REWARD' OR l.biz_no LIKE 'CLOUD_SHARE_DAILY:%'
        OR EXISTS(SELECT 1 FROM nx_compute_receipt r WHERE r.user_id=l.user_id AND r.task_no=l.biz_no))
        ORDER BY l.id</script>
        """)
    List<Map<String,Object>> fundLedgers(@Param("scope") List<Long> scope);
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("""
        <script>SELECT e.id,e.event_no eventNo,e.user_id customerId,e.user_device_id deviceId,e.receipt_no receiptNo,
        e.asset,e.amount,e.status,e.wallet_posted_at walletPostedAt,e.created_at createdAt,e.updated_at updatedAt,e.is_deleted deleted
        FROM nx_earning_event e WHERE e.user_id IN
        <foreach collection='scope' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY e.id</script>
        """)
    List<Map<String,Object>> fundEvents(@Param("scope") List<Long> scope);
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("SELECT TABLE_NAME tableName,COLUMN_NAME columnName,DATETIME_PRECISION `precision` FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE() AND ((TABLE_NAME='nx_compute_receipt' AND COLUMN_NAME='completed_at') OR (TABLE_NAME='nx_compute_task' AND COLUMN_NAME IN ('completed_at','proof_consumed_at')) OR (TABLE_NAME='nx_wallet_ledger' AND COLUMN_NAME='created_at') OR (TABLE_NAME='nx_earning_event' AND COLUMN_NAME IN ('wallet_posted_at','created_at')))")
    List<Map<String,Object>> fundTimePrecisions();
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("SELECT id,user_id customerId,fund_incarnation incarnation,fund_revision revision,version,usdt_available usdtAvailable,nex_available nexAvailable,cregis_risk_held cregisRiskHeld,pending_withdraw pendingWithdraw,sandbox,is_deleted deleted,created_at createdAt FROM nx_user_wallet WHERE user_id=#{customer}")
    List<Map<String,Object>> fundWallets(@Param("customer") long customer);
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

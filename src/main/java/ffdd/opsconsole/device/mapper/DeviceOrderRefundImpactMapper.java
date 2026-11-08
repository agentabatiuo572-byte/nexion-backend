package ffdd.opsconsole.device.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface DeviceOrderRefundImpactMapper {
    record Totals(long completedPendingCount,BigDecimal pendingUsdt,BigDecimal pendingUvt,
                  long unfinishedCount,long stoppedCount,long creditedCount,BigDecimal creditedUsdt,BigDecimal creditedUvt,
                  long unresolvedSimulatedCount) { }

    @Select("""
            WITH devices AS (
              SELECT d.* FROM nx_user_device d
              JOIN nx_order o ON o.order_no=d.source_order_no AND o.user_id=d.user_id AND o.is_deleted=0
              WHERE d.source_order_no=#{orderNo} AND d.user_id=#{userId} AND d.is_deleted=0
                AND d.source_environment='PRODUCTION' AND d.run_id='' AND d.source_channel<>'PROMOTION_GIFT'
                AND UPPER(o.order_type)<>'TRIAL_CONVERT'
            ), simulated AS (
              SELECT t.*,LEAST(#{asOf},COALESCE(d.deactivated_at,#{asOf}),COALESCE(r.stop_at,#{asOf}),
                                COALESCE(r.phone_valid_until,#{asOf})) AS cutoff
              FROM devices d JOIN nx_simulated_task t ON t.user_device_id=d.id AND t.user_id=d.user_id
              JOIN nx_simulated_device_runtime r ON r.user_device_id=d.id AND r.user_id=d.user_id
                AND r.instance_no=t.instance_no AND d.instance_no=t.instance_no COLLATE utf8mb4_0900_ai_ci
                AND r.generation=t.generation AND r.current_task_no=t.task_no
              WHERE t.status='RUNNING' AND t.completed_at IS NULL AND t.posted_at IS NULL
                AND NOT EXISTS (SELECT 1 FROM nx_compute_retired_task rt
                  WHERE rt.user_id=t.user_id AND rt.task_no=t.task_no COLLATE utf8mb4_0900_ai_ci)
            ), unresolved_simulated AS (
              SELECT t.task_no FROM devices d
              JOIN nx_simulated_task t ON t.user_device_id=d.id AND t.user_id=d.user_id
              WHERE t.status='RUNNING' AND t.completed_at IS NULL AND t.posted_at IS NULL
                AND NOT EXISTS (SELECT 1 FROM nx_compute_retired_task rt
                  WHERE rt.user_id=t.user_id AND rt.task_no=t.task_no COLLATE utf8mb4_0900_ai_ci)
                AND NOT EXISTS (SELECT 1 FROM nx_simulated_device_runtime r
                  WHERE r.user_device_id=d.id AND r.user_id=d.user_id AND r.instance_no=t.instance_no
                    AND d.instance_no=t.instance_no COLLATE utf8mb4_0900_ai_ci
                    AND r.generation=t.generation AND r.current_task_no=t.task_no)
            ), facts AS (
              SELECT 'PENDING' bucket,t.task_no,COALESCE(r.reward_usdt,t.reward_usdt) usdt,COALESCE(r.reward_nex,0) uvt
              FROM devices d JOIN nx_compute_task t ON t.user_device_id=d.id AND t.user_id=d.user_id
              LEFT JOIN nx_compute_receipt r ON r.task_no=t.task_no AND r.user_id=t.user_id
                AND r.source_environment='PRODUCTION' AND r.is_deleted=0
              WHERE t.source_environment='PRODUCTION' AND t.is_deleted=0
                AND t.task_no NOT LIKE 'CV2-%' AND t.task_no NOT LIKE 'SIM-%'
                AND t.status='COMPLETED' AND t.completed_at IS NOT NULL
                AND (r.id IS NULL OR UPPER(r.earning_status) NOT IN ('POSTED','SUCCESS','SETTLED','CREDITED','PAID'))
                AND NOT EXISTS (SELECT 1 FROM nx_compute_retired_task rt WHERE rt.task_id=t.id)
              UNION ALL
              SELECT 'PENDING',t.task_no COLLATE utf8mb4_0900_ai_ci,CASE WHEN t.asset='USDT' THEN t.reward_amount ELSE 0 END,
                (CASE WHEN t.asset='UVT' THEN t.reward_amount ELSE 0 END)
                + CASE WHEN t.cloud_daily_nex>0 AND NOT EXISTS (
                    SELECT 1 FROM nx_wallet_ledger l WHERE l.user_id=t.user_id AND l.asset='UVT' AND l.direction='IN'
                      AND l.biz_no=CONCAT('CLOUD_SHARE_DAILY:',t.user_device_id,':',DATE(CONVERT_TZ(t.due_at,'+08:00','+00:00')))
                  ) THEN t.cloud_daily_nex ELSE 0 END
              FROM simulated t WHERE t.due_at<=t.cutoff
              UNION ALL
              SELECT 'UNFINISHED',t.task_no,0,0 FROM devices d
              JOIN nx_compute_task t ON t.user_device_id=d.id AND t.user_id=d.user_id
              WHERE t.source_environment='PRODUCTION' AND t.is_deleted=0
                AND t.task_no NOT LIKE 'CV2-%' AND t.task_no NOT LIKE 'SIM-%'
                AND t.status IN ('CLAIMED','RUNNING','PAUSED') AND t.completed_at IS NULL AND t.proof_consumed_at IS NULL
                AND NOT EXISTS (SELECT 1 FROM nx_compute_retired_task rt WHERE rt.task_id=t.id)
              UNION ALL
              SELECT 'UNFINISHED',task_no COLLATE utf8mb4_0900_ai_ci,0,0 FROM simulated WHERE due_at>cutoff
              UNION ALL
              SELECT 'STOPPED',t.task_no,0,0 FROM devices d
              JOIN nx_compute_task t ON t.user_device_id=d.id AND t.user_id=d.user_id
              WHERE t.is_deleted=0 AND t.source_environment='PRODUCTION' AND t.status='CANCELLED' AND t.last_error='ORDER_REFUND'
                AND t.task_no NOT LIKE 'CV2-%' AND t.task_no NOT LIKE 'SIM-%'
                AND NOT EXISTS (SELECT 1 FROM nx_compute_retired_task rt WHERE rt.task_id=t.id)
              UNION ALL
              SELECT 'STOPPED',t.task_no COLLATE utf8mb4_0900_ai_ci,0,0 FROM devices d
              JOIN nx_simulated_task t ON t.user_device_id=d.id AND t.user_id=d.user_id
              WHERE t.status='CANCELLED' AND t.cancel_reason='ORDER_REFUND'
                AND NOT EXISTS (SELECT 1 FROM nx_compute_retired_task rt
                  WHERE rt.user_id=t.user_id AND rt.task_no=t.task_no COLLATE utf8mb4_0900_ai_ci)
              UNION ALL
              SELECT 'CREDITED',r.task_no,
                     SUM(CASE WHEN e.asset='USDT' THEN e.amount ELSE 0 END),
                     SUM(CASE WHEN e.asset='UVT' THEN e.amount ELSE 0 END)
              FROM devices d JOIN nx_compute_receipt r ON r.user_device_id=d.id AND r.user_id=d.user_id
              JOIN nx_earning_event e ON e.receipt_no=r.receipt_no AND e.user_id=r.user_id AND e.user_device_id=d.id
                AND e.status='POSTED' AND e.wallet_posted_at IS NOT NULL AND e.is_deleted=0
              WHERE r.source_environment='PRODUCTION' AND r.is_deleted=0
                AND NOT EXISTS (SELECT 1 FROM nx_compute_retired_task rt
                  WHERE rt.user_id=r.user_id AND rt.task_no=r.task_no COLLATE utf8mb4_0900_ai_ci)
              GROUP BY r.receipt_no,r.task_no
            )
            SELECT COALESCE(SUM(bucket='PENDING'),0) completedPendingCount,
                   COALESCE(SUM(CASE WHEN bucket='PENDING' THEN usdt ELSE 0 END),0) pendingUsdt,
                   COALESCE(SUM(CASE WHEN bucket='PENDING' THEN uvt ELSE 0 END),0) pendingUvt,
                   COALESCE(SUM(bucket='UNFINISHED'),0) unfinishedCount,
                   COALESCE(SUM(bucket='STOPPED'),0) stoppedCount,
                   COALESCE(SUM(bucket='CREDITED'),0) creditedCount,
                   COALESCE(SUM(CASE WHEN bucket='CREDITED' THEN usdt ELSE 0 END),0) creditedUsdt,
                   COALESCE(SUM(CASE WHEN bucket='CREDITED' THEN uvt ELSE 0 END),0) creditedUvt,
                   (SELECT COUNT(*) FROM unresolved_simulated) unresolvedSimulatedCount
            FROM facts
            """)
    Totals totals(@Param("orderNo")String orderNo,@Param("userId")long userId,@Param("asOf")LocalDateTime asOf);
}

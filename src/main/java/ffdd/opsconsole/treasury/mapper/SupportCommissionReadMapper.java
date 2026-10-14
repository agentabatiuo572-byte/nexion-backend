package ffdd.opsconsole.treasury.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** Fixed original Treasury SELECTs; no generic entity writes are exposed. */
@Mapper @SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportCommissionReadMapper {
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("""
        <script>
        SELECT e.id,e.user_id customerId,e.commission_type commissionType,
          e.source_user_id sourceUserId,e.source_user_name sourceUserName,e.layer_no layerNo,
          e.order_no orderNo,e.order_amount_usd orderAmountUsd,e.amount_usdt amountUsdt,
          e.amount_nex amountNex,UPPER(e.currency) asset,UPPER(e.status) status,e.version,
          e.unlock_at unlockAt,e.frozen_from_status frozenFromStatus,e.remark,
          e.created_at createdAt,e.updated_at updatedAt,e.is_deleted deleted,
          u.sandbox userSandbox,u.is_deleted userDeleted
        FROM nx_commission_event e LEFT JOIN nx_user u ON u.id=e.user_id
        WHERE e.user_id IN
        <foreach item="id" collection="scope" open="(" separator="," close=")">#{id}</foreach>
        ORDER BY e.id
        </script>
        """)
    List<Map<String,Object>> fundEvents(@Param("scope") List<Long> scope);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("""
        <script>
        SELECT id,user_id customerId,biz_no bizNo,biz_type bizType,asset,direction,
          amount,balance_after balanceAfter,status,remark,created_at createdAt,
          updated_at updatedAt,is_deleted deleted
        FROM nx_wallet_ledger
        WHERE user_id IN
        <foreach item="id" collection="scope" open="(" separator="," close=")">#{id}</foreach>
          AND biz_no LIKE 'F5-COMMISSION-%'
        ORDER BY id
        </script>
        """)
    List<Map<String,Object>> fundLedgers(@Param("scope") List<Long> scope);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("""
        SELECT w.id,w.user_id customerId,w.fund_incarnation incarnation,w.fund_revision revision,
          w.version,w.usdt_available usdtAvailable,w.nex_available nexAvailable,
          w.cregis_risk_held cregisRiskHeld,w.pending_withdraw pendingWithdraw,
          w.sandbox,w.is_deleted deleted,w.created_at createdAt,
          u.sandbox userSandbox,u.is_deleted userDeleted
        FROM nx_user_wallet w LEFT JOIN nx_user u ON u.id=w.user_id
        WHERE w.user_id=#{customer}
        FOR UPDATE
        """)
    List<Map<String,Object>> fundWallets(@Param("customer") long customerId);

    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    @Select("""
        SELECT TABLE_NAME tableName,COLUMN_NAME columnName,DATETIME_PRECISION precision
        FROM information_schema.COLUMNS WHERE TABLE_SCHEMA=DATABASE()
          AND ((TABLE_NAME='nx_wallet_ledger' AND COLUMN_NAME='created_at')
            OR (TABLE_NAME='nx_commission_event' AND COLUMN_NAME IN ('created_at','updated_at')))
        """)
    List<Map<String,Object>> fundTimePrecisions();
}

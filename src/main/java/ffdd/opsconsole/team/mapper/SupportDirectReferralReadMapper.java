package ffdd.opsconsole.team.mapper;

import java.util.*;
import org.apache.ibatis.annotations.*;

/** Scoped originals, deliberately retaining contradictory, reversed and deleted rows. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportDirectReferralReadMapper {
    String SETTLEMENT="SELECT s.*,u.sandbox support_user_sandbox,u.is_deleted support_user_deleted FROM nx_direct_referral_settlement s LEFT JOIN nx_user u ON u.id=s.beneficiary_user_id ";
    String EVENT="SELECT e.*,u.sandbox support_user_sandbox,u.is_deleted support_user_deleted FROM nx_commission_event e LEFT JOIN nx_user u ON u.id=e.user_id ";
    String ENTRY="SELECT id,entry_no entryNo,user_id userId,cluster_id clusterId,source_type sourceType,source_ref sourceRef,asset,amount,bucket,status,idempotency_key idempotencyKey,source_environment sourceEnvironment,is_deleted isDeleted,recovered_amount recoveredAmount,source_debit_baseline debitBaseline,source_wallet_id sourceWalletId,released_at releasedAt,release_source releaseSource,created_at createdAt,updated_at updatedAt FROM nx_earnings_release_entry ";
    String LEDGER="SELECT id,user_id customerId,biz_no bizNo,biz_type bizType,asset,direction,amount,balance_after balanceAfter,status,remark,created_at createdAt,updated_at updatedAt,is_deleted deleted FROM nx_wallet_ledger ";
    @Select("<script>"+SETTLEMENT+"WHERE s.beneficiary_user_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY s.id</script>")
    List<Map<String,Object>> settlementsByCustomers(@Param("ids") Collection<Long> ids);
    @Select("<script>"+SETTLEMENT+"WHERE s.settlement_no IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY s.id</script>")
    List<Map<String,Object>> settlementsByNos(@Param("ids") Collection<String> ids);
    @Select("<script>"+EVENT+"WHERE e.user_id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> AND (EXISTS(SELECT 1 FROM nx_commission_operation p WHERE p.operation_type='REISSUE' AND p.result_commission_id=e.id) OR EXISTS(SELECT 1 FROM nx_unilevel_event_recovery r WHERE r.event_id=e.id)) ORDER BY e.id</script>")
    List<Map<String,Object>> reissuesByCustomers(@Param("ids") Collection<Long> ids);
    @Select("<script>"+EVENT+"WHERE e.id IN <foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY e.id</script>")
    List<Map<String,Object>> eventsByIds(@Param("ids") Collection<Long> ids);
    @Select(ENTRY+"WHERE source_ref IN (CONCAT(#{no},':USDT'),CONCAT(#{no},':NEX')) ORDER BY id")
    List<Map<String,Object>> entries(@Param("no") String no);
    @Select("SELECT * FROM nx_unilevel_event_recovery WHERE event_id=#{id} ORDER BY event_id")
    List<Map<String,Object>> recoveries(@Param("id") long id);
    @Select("SELECT o.id,o.order_no,o.user_id,o.order_status,o.payment_status,o.paid_at,o.is_deleted,0 support_root_depth FROM nx_order o WHERE o.order_no=#{no} ORDER BY o.id")
    List<Map<String,Object>> orders(@Param("no") String no);
    @Select("WITH RECURSIVE roots AS (SELECT e.id,e.order_no,e.is_deleted,0 depth FROM nx_commission_event e WHERE e.id=#{id} UNION ALL SELECT e.id,e.order_no,e.is_deleted,r.depth+1 FROM roots r JOIN nx_commission_operation p ON p.result_commission_id=r.id AND p.operation_type='REISSUE' JOIN nx_commission_event e ON e.id=p.source_commission_id WHERE r.depth<32) SELECT o.id,o.order_no,o.user_id,o.order_status,o.payment_status,o.paid_at,o.is_deleted,r.id support_original_event_id,r.depth support_root_depth,(SELECT MAX(depth) FROM roots) support_traversal_max_depth,(SELECT MAX(is_deleted) FROM roots) support_invalid_ancestor,(SELECT COUNT(*) FROM roots x JOIN nx_commission_operation p ON p.result_commission_id=x.id AND p.operation_type='REISSUE' LEFT JOIN nx_commission_event a ON a.id=p.source_commission_id WHERE a.id IS NULL) support_missing_ancestor,(SELECT MAX((SELECT COUNT(*) FROM nx_commission_operation p WHERE p.result_commission_id=x.id AND p.operation_type='REISSUE')) FROM roots x) support_max_parent_count,(SELECT COUNT(*) FROM nx_commission_operation p WHERE p.result_commission_id=#{id} AND p.operation_type='REISSUE') support_initial_parent_count FROM roots r JOIN nx_order o ON o.order_no=r.order_no ORDER BY r.depth DESC,o.id")
    List<Map<String,Object>> reissueRootOrders(@Param("id") long id);
    @Select(LEDGER+"WHERE biz_no=#{release} OR biz_no=CONCAT(SUBSTRING(#{release},1,CHAR_LENGTH(#{release})-8),'-REVERSE') OR biz_no LIKE CONCAT(#{prefix},'%') ORDER BY id")
    List<Map<String,Object>> ledgers(@Param("release") String release,@Param("prefix") String prefix);
    @Select("SELECT operation_id FROM nx_support_fund_operation_party WHERE customer_id=#{id} ORDER BY operation_id DESC LIMIT 1 FOR SHARE")
    Long previousOperation(@Param("id") long id);
    @Select("SELECT id,user_id userId,usdt_available usdtAvailable,nex_available nexAvailable,pending_withdraw pendingWithdraw,cregis_risk_held cregisRiskHeld,version,sandbox,is_deleted isDeleted,created_at createdAt,fund_incarnation incarnation,fund_revision revision FROM nx_user_wallet WHERE user_id=#{id} ORDER BY id FOR UPDATE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> wallets(@Param("id") long id);
    @Select("SELECT id,wallet_incarnation incarnation,revision,observation,old_id oldId,new_id newId,old_user_id oldUserId,new_user_id newUserId,old_usdt_available oldUsdtAvailable,new_usdt_available newUsdtAvailable,old_nex_available oldNexAvailable,new_nex_available newNexAvailable,old_pending_withdraw oldPendingWithdraw,new_pending_withdraw newPendingWithdraw,old_cregis_risk_held oldCregisRiskHeld,new_cregis_risk_held newCregisRiskHeld,old_version oldVersion,new_version newVersion,old_sandbox oldSandbox,new_sandbox newSandbox,old_is_deleted oldIsDeleted,new_is_deleted newIsDeleted,old_created_at oldCreatedAt,new_created_at newCreatedAt,recorded_db_utc recordedDbUtc,protocol_sha256 protocolSha256 FROM nx_support_wallet_journal WHERE wallet_incarnation=#{incarnation} AND revision>#{before} AND revision<=#{after} ORDER BY revision FOR SHARE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> journal(@Param("incarnation") String incarnation,@Param("before") long before,@Param("after") long after);
    @Select("SELECT table_name tableName,column_name columnName,datetime_precision `precision` FROM information_schema.COLUMNS WHERE table_schema=DATABASE() AND (table_name IN ('nx_direct_referral_settlement','nx_unilevel_event_recovery','nx_commission_event') AND column_name='updated_at' OR table_name='nx_earnings_release_entry' AND column_name='created_at')")
    List<Map<String,Object>> timePrecisions();
}

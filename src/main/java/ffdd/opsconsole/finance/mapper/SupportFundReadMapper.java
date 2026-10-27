package ffdd.opsconsole.finance.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import org.apache.ibatis.annotations.*;

/** Fixed scope MVCC projections. No locking read or hidden production/deletion filter. */
@Mapper
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportFundReadMapper {
    @Select("<script>SELECT o.operation_no operationNo,o.incident_id incidentId,o.resolution_version resolutionVersion,o.phase,"
        +"o.original_fact_id originalFactId,o.attribution_effect attributionEffect,o.snapshot_json snapshotJson,o.proof_json proofJson,"
        +"o.events_json eventsJson,o.created_at createdAt,r.user_id userId,r.event_id eventId,r.net_amount netAmount,"
        +"(SELECT COUNT(*) FROM nx_treasury_reserve_ledger t WHERE t.idempotency_key=o.operation_no AND t.is_deleted=0 "
        +"AND t.status='CONFIRMED' AND t.amount_usd=r.net_amount AND t.direction=CASE o.phase WHEN 'REVERSE' THEN 'OUT' WHEN 'RESTORE' THEN 'IN' ELSE 'NONE' END) reserveCount,"
        +"(SELECT COUNT(*) FROM nx_audit_log a WHERE a.action=CONCAT('CREGIS_REORG_OPERATION_',o.phase) "
        +"AND a.resource_type='CREGIS_INCIDENT' AND a.resource_id=CAST(o.incident_id AS CHAR) AND a.result='SUCCESS' "
        +"AND JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.operationNo'))=o.operation_no AND a.is_deleted=0) auditCount "
        +"FROM nx_cregis_reorg_operation o JOIN nx_cregis_reorg_resolution r ON r.incident_id=o.incident_id WHERE r.user_id IN "
        +"<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> ORDER BY o.incident_id,o.resolution_version</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> cregisReorgOperations(@Param("ids") List<Long> ids);
    @Select("<script>SELECT incident_id incidentId,user_id userId,held_amount heldAmount,status FROM nx_cregis_reorg_resolution WHERE user_id IN "
        +"<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach> AND held_amount>0 ORDER BY incident_id</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> cregisReorgHoldings(@Param("ids") List<Long> ids);
    String IDS="<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>";
    String WALLET="SELECT id,user_id userId,usdt_available usdtAvailable,nex_available nexAvailable,"
        +"pending_withdraw pendingWithdraw,cregis_risk_held cregisRiskHeld,version,sandbox,is_deleted isDeleted,"
        +"created_at createdAt,fund_incarnation incarnation,fund_revision revision FROM nx_user_wallet";
    String RAW="SELECT id,wallet_incarnation incarnation,revision,observation,old_id oldId,new_id newId,"
        +"old_user_id oldUserId,new_user_id newUserId,old_usdt_available oldUsdtAvailable,new_usdt_available newUsdtAvailable,"
        +"old_nex_available oldNexAvailable,new_nex_available newNexAvailable,old_pending_withdraw oldPendingWithdraw,"
        +"new_pending_withdraw newPendingWithdraw,old_cregis_risk_held oldCregisRiskHeld,new_cregis_risk_held newCregisRiskHeld,"
        +"old_version oldVersion,new_version newVersion,old_sandbox oldSandbox,new_sandbox newSandbox,"
        +"old_is_deleted oldIsDeleted,new_is_deleted newIsDeleted,old_created_at oldCreatedAt,new_created_at newCreatedAt,"
        +"recorded_db_utc recordedDbUtc,protocol_sha256 protocolSha256 FROM nx_support_wallet_journal";
    String OPS="SELECT id,op_key operationKey,business_type businessType,phase,source_table sourceTable,source_key sourceKey,"
        +"source_environment sourceEnvironment,ordered_events orderedEventsJson,source_snapshot sourceSnapshotJson,"
        +"source_proof sourceProofJson,receipt_sha256 receiptSha256,protocol_version protocolVersion,recorded_db_utc recordedDbUtc "
        +"FROM nx_support_fund_operation";

    @Select("SELECT UTC_TIMESTAMP(6)") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    LocalDateTime observedAt();
    @Select("SELECT nx_support_fund_schema_ready_v1()") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Boolean schemaReady();
    @Select("SELECT datetime_precision FROM information_schema.COLUMNS WHERE table_schema=DATABASE() AND table_name='nx_withdrawal_order' AND column_name='completed_at'")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Integer withdrawalSuccessPrecision();
    @Select("<script>"+WALLET+" WHERE user_id IN "+IDS+" ORDER BY user_id,id</script>") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> wallets(@Param("ids") List<Long> ids);
    @Select("<script>"+RAW+" WHERE old_user_id IN "+IDS+" OR new_user_id IN "+IDS
        +" OR old_id IN <foreach collection='walletIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        +" OR new_id IN <foreach collection='walletIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
        +" ORDER BY wallet_incarnation,revision,id</script>") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> raw(@Param("ids") List<Long> ids,@Param("walletIds") List<Long> walletIds);
    @Select("<script>SELECT DISTINCT operation_id FROM nx_support_fund_operation_party WHERE customer_id IN "+IDS
        +" ORDER BY operation_id</script>") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Long> operationIds(@Param("ids") List<Long> ids);
    @Select("<script>"+OPS+" WHERE id IN "+IDS+" ORDER BY id</script>") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> operations(@Param("ids") List<Long> ids);
    @Select("<script>SELECT operation_id operationId,customer_id customerId FROM nx_support_fund_operation_party WHERE operation_id IN "
        +IDS+" ORDER BY operation_id,customer_id</script>") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> parties(@Param("ids") List<Long> ids);
    @Select("<script>SELECT operation_id operationId,wallet_incarnation incarnation,revision FROM nx_support_fund_operation_leg WHERE operation_id IN "
        +IDS+" ORDER BY operation_id,wallet_incarnation,revision</script>") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> legs(@Param("ids") List<Long> ids);
    @Select("<script>SELECT id,user_id userId,withdrawal_no withdrawalNo,asset,chain,amount,d2_net_receive netReceive,"
        +"d2_actual_fee actualFee,d2_nex_burned nexBurned,d2_idempotency_key idempotencyKey,status,created_at createdAt,"
        +"completed_at completedAt,failed_at failedAt,is_deleted isDeleted FROM nx_withdrawal_order WHERE user_id IN "
        +IDS+" ORDER BY user_id,id</script>") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> withdrawals(@Param("ids") List<Long> ids);
    @Select("<script>SELECT id,user_id userId,biz_no bizNo,biz_type bizType,asset,direction,amount,balance_after balanceAfter,"
        +"status,created_at createdAt,is_deleted isDeleted FROM nx_wallet_ledger WHERE user_id IN "+IDS+" ORDER BY user_id,id</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE) List<Map<String,Object>> ledgers(@Param("ids") List<Long> ids);
    @Select("<script>SELECT id,user_id userId,actor_id actorId,actor_type actorType,result,detail_json detailJson,created_at createdAt,"
        +"resource_id resourceId,biz_no bizNo,is_deleted isDeleted FROM nx_audit_log WHERE action='D2_WITHDRAWAL_SUBMITTED'"
        +" AND resource_type='WITHDRAWAL' AND user_id IN "+IDS+" ORDER BY user_id,id</script>") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> withdrawalAudits(@Param("ids") List<Long> ids);
    @Select("<script>SELECT p.withdrawal_no withdrawalNo,p.user_id userId,p.quote_no quoteNo,p.state,q.amount_usdt amount,"
        +"q.fee_usdt fee,q.net_usdt net,q.created_at quoteCreatedAt,p.created_at createdAt,q.beneficiary_no beneficiaryNo,"
        +"q.d7_version d7Version,q.d5_version d5Version FROM nx_hdpay_payout p JOIN nx_bank_payout_quote q"
        +" ON q.quote_no=p.quote_no AND q.user_id=p.user_id AND q.withdrawal_no=p.withdrawal_no WHERE p.user_id IN "
        +IDS+" ORDER BY p.user_id,p.withdrawal_no</script>") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> bankReservations(@Param("ids") List<Long> ids);
    @Select("<script>SELECT user_id userId,idempotency_key idempotencyKey,status,withdrawal_no withdrawalNo,"
        +"created_at createdAt FROM nx_withdrawal_attempt_control WHERE user_id IN "+IDS+" ORDER BY user_id,idempotency_key</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE) List<Map<String,Object>> withdrawalAttempts(@Param("ids") List<Long> ids);
    @Select("<script>SELECT id,CONCAT('nx_wallet_ledger:',id) sourceId,user_id customerId,biz_no businessId,biz_type ledgerType,"
        +"status,created_at successAt,is_deleted deleted,asset currency,direction,amount,balance_after balanceAfter,remark,"
        +"updated_at updatedAt FROM nx_wallet_ledger WHERE user_id IN "+IDS+" ORDER BY id</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE) List<Map<String,Object>> paymentLedgers(@Param("ids") List<Long> ids);
    @Select("<script>SELECT entry_no entryNo,user_id userId,source_type sourceType,source_ref sourceRef,asset,amount,bucket,status,"
        +"source_environment sourceEnvironment,is_deleted isDeleted,recovered_amount recoveredAmount,source_debit_baseline debitBaseline,"
        +"source_wallet_id sourceWalletId,released_at releasedAt,release_source releaseSource FROM nx_earnings_release_entry WHERE user_id IN "
        +IDS+" ORDER BY id</script>") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> earnings(@Param("ids") List<Long> ids);
    @Select("<script>"+EarningsReleaseMapper.FUND_ENTRY+" WHERE user_id IN "+IDS+" ORDER BY user_id,id</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> earningsSources(@Param("ids") List<Long> ids);
    @Select("<script>"+EarningsReleaseMapper.FUND_RECOVERY+" WHERE user_id IN "+IDS+" ORDER BY user_id,id</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> earningsRecoveries(@Param("ids") List<Long> ids);
    @Select("<script>"+EarningsReleaseMapper.FUND_APPLIED_PROOF+" WHERE user_id IN "+IDS+" ORDER BY user_id,proof_id</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> earningsAppliedProofs(@Param("ids") List<Long> ids);
    @Select("<script>"+EarningsReleaseMapper.FUND_RELEASE_AUDIT+" WHERE resource_type='EARNINGS_RELEASE_ENTRY'"
        +" AND action IN ('K1_EARNINGS_ATTESTATION_RELEASED','K1_EARNINGS_MANUALLY_RELEASED','EARNINGS_SOURCE_RECOVERED')"
        +" AND resource_id IN (SELECT entry_no FROM nx_earnings_release_entry WHERE user_id IN "+IDS+") ORDER BY id</script>")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> earningsAudits(@Param("ids") List<Long> ids);
    @Select("SELECT table_name tableName,column_name columnName,datetime_precision `precision` FROM information_schema.COLUMNS"
        +" WHERE table_schema=DATABASE() AND ((table_name='nx_earnings_release_entry' AND column_name IN ('created_at','released_at'))"
        +" OR (table_name='nx_earnings_source_recovery' AND column_name='created_at')) ORDER BY table_name,column_name")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> earningsTimePrecisions();
    @SelectProvider(type=PaymentSourceSql.class,method="batch") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> paymentSources(@Param("source") Source source,@Param("customerIds") List<Long> ids);
    @Select(SupportPaymentSourceSql.HISTORY_PAYMENT_AUDITS) @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> paymentAudits(@Param("customerIds") List<Long> ids);
    @SelectProvider(type=PaymentSourceSql.class,method="trials") @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> trialSources(@Param("ids") List<Long> ids);
    final class PaymentSourceSql {
        private PaymentSourceSql() {}
        public static String trials() {
            String sql=SupportPaymentSourceSql.TRIAL_FUND_SOURCE.stripTrailing();
            String suffix="WHERE c.user_id=#{customerId} AND c.claim_no=#{claimNo} FOR SHARE OF c,d,o,u";
            if(!sql.endsWith(suffix))throw new IllegalStateException("Trial source projection changed");
            return "<script>"+sql.substring(0,sql.length()-suffix.length())+"WHERE c.user_id IN "+IDS+"</script>";
        }
        public static String batch(Map<String,Object> parameters) {
            var source=(Source)parameters.get("source");
            if(source==null)throw new IllegalArgumentException("Unsupported payment source");
            String predicate=switch(source) {
                case DEPOSIT_ORDER->"d.deposit_no=#{key}";case CARD_TOPUP->"p.payment_no=#{key}";
                case VIETQR->"r.reconciliation_no=#{rawKey}";case HDPAY->"i.intent_no=#{key}";
                case WALLET_ORDER,TRADE_IN,CAPACITY_KEEP->"o.order_no=#{key}";
                case TRIAL_CONVERT->"c.claim_no=#{rawKey}";case ORDER_REFUND->"l.biz_no=#{key}";
                default->throw new IllegalArgumentException("Unsupported payment source");
            };
            var request=new HashMap<>(parameters);
            request.put("key",switch(source) {case VIETQR->"D1-VIETQR-BATCH";case TRIAL_CONVERT->"BATCH:CHARGE";case ORDER_REFUND->"E4-REFUND-BATCH";default->"BATCH";});
            String sql=SupportPaymentSourceSql.settled(request).stripTrailing();
            String suffix=" AND "+predicate+"</script>";
            if(!sql.endsWith(suffix))throw new IllegalStateException("Payment source projection changed");
            return sql.substring(0,sql.length()-suffix.length())+"</script>";
        }
    }
}

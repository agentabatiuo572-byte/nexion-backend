package ffdd.opsconsole.team.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.*;

@Mapper
public interface DirectReferralMapper extends BaseMapper<Object> {
    @Select("SELECT usdt_rate*100 usdtPct,nex_per_usd nexReward FROM nx_commission_rule WHERE commission_type='UNILEVEL' AND layer_no=1 AND status=1 AND is_deleted=0 ORDER BY id LIMIT 1")
    List<Map<String,Object>> sevenLayerReference();
    @Select("SELECT policy_version policyVersion,effective_at effectiveAt,purchase_json purchaseJson,device_earning_json deviceEarningJson FROM nx_direct_referral_policy WHERE effective_at<=#{at} ORDER BY effective_at DESC,policy_version DESC LIMIT 1")
    PolicyRow policyAt(LocalDateTime at);
    @Select("SELECT policy_version policyVersion,effective_at effectiveAt,purchase_json purchaseJson,device_earning_json deviceEarningJson FROM nx_direct_referral_policy WHERE effective_at<=#{at} ORDER BY effective_at DESC,policy_version DESC LIMIT 1 FOR UPDATE")
    PolicyRow policyAtForUpdate(LocalDateTime at);
    @Select("SELECT COALESCE(MAX(policy_version),0) FROM nx_direct_referral_policy") long latestVersion();
    @Select("SELECT COALESCE(MAX(policy_version),0) FROM nx_direct_referral_policy FOR UPDATE") long latestVersionForUpdate();
    @Select("SELECT usdt_rate*100 usdtPct,nex_per_usd nexReward FROM nx_commission_rule WHERE commission_type='UNILEVEL' AND layer_no=1 AND status=1 AND is_deleted=0 ORDER BY id LIMIT 1 FOR UPDATE")
    List<Map<String,Object>> sevenLayerReferenceForUpdate();
    @Insert("INSERT INTO nx_direct_referral_policy(policy_version,effective_at,purchase_json,device_earning_json,operation_id,reason) VALUES(#{policyVersion},#{effectiveAt},#{purchaseJson},#{deviceEarningJson},#{operationId},#{reason})")
    int insertPolicy(@Param("policyVersion") long version,@Param("effectiveAt") LocalDateTime at,@Param("purchaseJson") String purchase,
                     @Param("deviceEarningJson") String earning,@Param("operationId") String operationId,@Param("reason") String reason);
    record PolicyRow(long policyVersion,LocalDateTime effectiveAt,String purchaseJson,String deviceEarningJson) { }

    @Select("""
        SELECT o.user_id userId,u.sponsor_user_id sponsorUserId,u.nickname sourceUserName,o.paid_at sourceOccurredAt,
               o.amount_usdt amountUsdt,0 amountNex,NULL sourceDeviceId,u.sandbox sandbox,
               CASE WHEN o.payment_status='REFUNDED' OR o.order_status='REFUNDED' THEN 1 ELSE 0 END refunded,
               CASE WHEN o.payment_status IN ('PAID','REFUNDED') AND o.paid_at IS NOT NULL THEN 1 ELSE 0 END valid
          FROM nx_order o JOIN nx_user u ON u.id=o.user_id AND u.is_deleted=0
         WHERE o.order_no=#{ref} AND o.is_deleted=0 FOR UPDATE
        """) Source purchase(String ref);
    @Select("""
        SELECT r.user_id userId,u.sponsor_user_id sponsorUserId,u.nickname sourceUserName,r.completed_at sourceOccurredAt,
               COALESCE((SELECT SUM(e.amount) FROM nx_earning_event e WHERE e.receipt_no=r.receipt_no AND e.user_id=r.user_id
                 AND e.user_device_id=r.user_device_id AND e.asset='USDT' AND e.status='POSTED' AND e.wallet_posted_at IS NOT NULL AND e.is_deleted=0),0) amountUsdt,
               COALESCE((SELECT SUM(e.amount) FROM nx_earning_event e WHERE e.receipt_no=r.receipt_no AND e.user_id=r.user_id
                 AND e.user_device_id=r.user_device_id AND e.asset='NEX' AND e.status='POSTED' AND e.wallet_posted_at IS NOT NULL AND e.is_deleted=0),0) amountNex,
               r.user_device_id sourceDeviceId,u.sandbox sandbox,0 refunded,
               CASE WHEN r.earning_status='CREDITED' AND r.source_environment='PRODUCTION'
                 AND r.task_no IS NOT NULL AND r.task_no NOT LIKE 'DEV-TASK%'
                 AND d.source_environment='PRODUCTION' AND d.run_id='' AND d.user_id=r.user_id
                 AND NOT EXISTS(SELECT 1 FROM nx_audit_log a WHERE a.resource_type='COMPUTE_TASK' AND a.resource_id=r.task_no
                       AND (a.actor_type='TEST_COMPUTE_WORKER' OR JSON_UNQUOTE(JSON_EXTRACT(a.detail_json,'$.proofMode'))='TEST_COMPUTE_WORKER'))
                 THEN 1 ELSE 0 END valid
          FROM nx_compute_receipt r JOIN nx_user u ON u.id=r.user_id AND u.is_deleted=0
          JOIN nx_user_device d ON d.id=r.user_device_id AND d.is_deleted=0
         WHERE r.receipt_no=#{ref} AND r.is_deleted=0 FOR UPDATE
        """) Source earning(String ref);
    record Source(Long userId,Long sponsorUserId,String sourceUserName,LocalDateTime sourceOccurredAt,
                  BigDecimal amountUsdt,BigDecimal amountNex,Long sourceDeviceId,Integer sandbox,Integer refunded,Integer valid) { }
    @Select("SELECT subtotal_usdt FROM nx_order WHERE order_no=#{ref} AND is_deleted=0") BigDecimal purchaseSubtotal(String ref);
    @Select("""
      WITH RECURSIVE roots AS (
       SELECT id,order_no,0 depth FROM nx_commission_event WHERE id=#{id} AND is_deleted=0
       UNION ALL SELECT e.id,e.order_no,r.depth+1 FROM roots r JOIN nx_commission_operation p ON p.result_commission_id=r.id AND p.operation_type='REISSUE'
        JOIN nx_commission_event e ON e.id=p.source_commission_id AND e.is_deleted=0 WHERE r.depth<32)
      SELECT r.order_no FROM roots r JOIN nx_order o ON o.order_no=r.order_no AND o.is_deleted=0 ORDER BY r.depth DESC LIMIT 1
      """) String sourceOrderForEvent(Long id);
    @Select("""
      WITH RECURSIVE descendants AS (
       SELECT e.id,0 depth FROM nx_commission_event e WHERE e.order_no=#{ref} AND e.commission_type IN ('network','direct_purchase') AND e.is_deleted=0
       UNION ALL SELECT p.result_commission_id,d.depth+1 FROM descendants d JOIN nx_commission_operation p ON p.source_commission_id=d.id AND p.operation_type='REISSUE' WHERE d.depth<32)
      SELECT DISTINCT e.id,e.user_id,e.currency,CASE WHEN e.currency='NEX' THEN e.amount_nex ELSE e.amount_usdt END amount,
       EXISTS(SELECT 1 FROM nx_wallet_ledger l WHERE l.biz_no=CONCAT('F5-COMMISSION-',e.id,'-RELEASE') AND l.direction='IN' AND l.status='SUCCESS') credited,
       EXISTS(SELECT 1 FROM nx_wallet_ledger l WHERE l.biz_no=CONCAT('F5-COMMISSION-',e.id,'-REVERSE') AND l.direction='OUT' AND l.status='SUCCESS') reversed
      FROM descendants d JOIN nx_commission_event e ON e.id=d.id AND e.is_deleted=0 WHERE d.depth>0 ORDER BY e.user_id,e.id
      """) List<Map<String,Object>> reissueDescendants(String ref);
    @Select("SELECT * FROM nx_unilevel_event_recovery WHERE event_id=#{id} FOR UPDATE") Map<String,Object> eventRecovery(Long id);
    @Insert("""
      INSERT INTO nx_unilevel_event_recovery(event_id,order_no,source_environment,run_id,user_id,asset,target_amount,recovered_amount,pending_amount)
       VALUES(#{id},#{ref},#{env},#{run},#{user},#{asset},#{target},#{recovered},#{pending})
      ON DUPLICATE KEY UPDATE recovered_amount=#{recovered},pending_amount=#{pending},updated_at=NOW()
      """) int saveEventRecovery(@Param("id") Long id,@Param("ref") String ref,@Param("env") String env,@Param("run") String run,@Param("user") Long user,@Param("asset") String asset,
        @Param("target") BigDecimal target,@Param("recovered") BigDecimal recovered,@Param("pending") BigDecimal pending);
    @Update("UPDATE nx_commission_event SET status=#{status},version=version+1,updated_at=NOW() WHERE id=#{id}") int recoveredEventStatus(@Param("id") Long id,@Param("status") String status);
    @Select("SELECT id FROM nx_user WHERE id=#{id} AND sandbox=#{sandbox} AND status='ACTIVE' AND is_deleted=0")
    Long activeUser(@Param("id") Long id,@Param("sandbox") int sandbox);
    @Select("SELECT id FROM nx_user WHERE id=#{id} AND sandbox=#{sandbox} AND status='ACTIVE' AND is_deleted=0 FOR UPDATE")
    Long lockUser(@Param("id") Long id,@Param("sandbox") int sandbox);
    @Select("SELECT w.usdt_available usdt,w.nex_available nex FROM nx_user_wallet w JOIN nx_user u ON u.id=w.user_id AND u.sandbox=#{sandbox} AND u.is_deleted=0 WHERE w.user_id=#{id} AND w.sandbox=#{sandbox} AND w.is_deleted=0 FOR UPDATE")
    Wallet lockWallet(@Param("id") Long id,@Param("sandbox") int sandbox);
    record Wallet(BigDecimal usdt,BigDecimal nex) { }
    @Select("SELECT settlement_no FROM nx_direct_referral_settlement WHERE source_environment=#{env} AND run_id=#{runId} AND source_type=#{kind} AND source_ref=#{ref} AND layer_no=1")
    String existing(@Param("env") String env,@Param("runId") String runId,@Param("kind") String kind,@Param("ref") String ref);
    @Insert("""
        INSERT INTO nx_direct_referral_settlement(settlement_no,source_environment,run_id,source_type,source_ref,
          source_user_id,beneficiary_user_id,source_user_name,source_device_id,source_occurred_at,policy_version,policy_snapshot,
          basis_usdt,nex_usdt_price,amount_usdt,amount_nex,status,release_at,reason,layer_no,price_locked_at)
        VALUES(#{settlementNo},#{sourceEnvironment},#{runId},#{sourceType},#{sourceRef},#{sourceUserId},#{beneficiaryUserId},
          #{sourceUserName},#{sourceDeviceId},#{sourceOccurredAt},#{policyVersion},#{policySnapshot},#{basisUsdt},#{nexUsdtPrice},
          #{amountUsdt},#{amountNex},#{status},#{releaseAt},#{reason},COALESCE(#{layerNo},1),#{priceLockedAt})
        """) int insertSettlement(Map<String,Object> value);
    @Insert("""
        INSERT INTO nx_commission_event(user_id,commission_type,source_user_id,source_user_name,layer_no,order_no,
          order_amount_usd,amount_usdt,amount_nex,currency,status,unlock_at,remark)
        VALUES(#{recipient},#{kind},#{source},#{name},1,#{ref},#{basis},#{usdt},#{nex},#{asset},#{status},#{releaseAt},#{settlementNo})
        """) int insertCommission(@Param("recipient") Long recipient,@Param("kind") String kind,@Param("source") Long source,@Param("name") String name,
                                   @Param("ref") String ref,@Param("basis") BigDecimal basis,@Param("usdt") BigDecimal usdt,@Param("nex") BigDecimal nex,
                                   @Param("asset") String asset,@Param("status") String status,@Param("releaseAt") LocalDateTime releaseAt,@Param("settlementNo") String no);
    @Select("SELECT LAST_INSERT_ID()") Long lastId();
    @Select("SELECT * FROM nx_unilevel_order_settlement WHERE source_environment=#{env} AND run_id=#{runId} AND order_no=#{ref} FOR UPDATE")
    Map<String,Object> lockOrder(@Param("env") String env,@Param("runId") String run,@Param("ref") String ref);
    @Select("SELECT COUNT(*) FROM nx_commission_event WHERE order_no=#{ref} AND commission_type='network' AND is_deleted=0") int historicalNetwork(String ref);
    @Select("SELECT COUNT(*) FROM nx_direct_referral_settlement WHERE source_ref=#{ref} AND source_type='direct_purchase'") int historicalDirect(String ref);
    @Insert("""
      INSERT INTO nx_unilevel_order_settlement(source_environment,run_id,order_no,source_user_id,source_occurred_at,prepared_at,
       settlement_mode,split_enabled,policy_version,seven_layer_revision,policy_snapshot,chain_and_rules,allocated_budget_usdt,status,reason)
      VALUES(#{sourceEnvironment},#{runId},#{orderNo},#{sourceUserId},#{sourceOccurredAt},#{preparedAt},#{settlementMode},#{splitEnabled},
       #{policyVersion},#{sevenLayerRevision},#{policySnapshot},#{chainAndRules},#{allocatedBudgetUsdt},#{status},#{reason})
      """) int insertOrder(Map<String,Object> value);
    @Update("UPDATE nx_unilevel_order_settlement SET status=#{status},refund_confirmed=GREATEST(refund_confirmed,#{refunded}),updated_at=NOW() WHERE id=#{id}")
    int orderStatus(@Param("id") Long id,@Param("status") String status,@Param("refunded") int refunded);
    @Select("SELECT settlement_no FROM nx_direct_referral_settlement WHERE source_environment=#{env} AND run_id=#{runId} AND source_ref=#{ref} AND source_type IN ('direct_purchase','network') ORDER BY beneficiary_user_id,layer_no,id")
    List<String> orderGroups(@Param("env") String env,@Param("runId") String run,@Param("ref") String ref);
    @Insert("""
       INSERT INTO nx_commission_event(user_id,commission_type,source_user_id,source_user_name,layer_no,order_no,
       order_amount_usd,amount_usdt,amount_nex,currency,status,unlock_at,remark)
       VALUES(#{recipient},#{kind},#{source},#{name},#{layer},#{ref},#{basis},#{usdt},#{nex},#{asset},#{status},#{releaseAt},#{settlementNo})
       """) int insertLayerCommission(@Param("recipient") Long recipient,@Param("kind") String kind,@Param("source") Long source,@Param("name") String name,
       @Param("layer") int layer,@Param("ref") String ref,@Param("basis") BigDecimal basis,@Param("usdt") BigDecimal usdt,@Param("nex") BigDecimal nex,
       @Param("asset") String asset,@Param("status") String status,@Param("releaseAt") LocalDateTime at,@Param("settlementNo") String no);
    @Update("UPDATE nx_direct_referral_settlement SET nex_usdt_price=#{price},price_locked_at=#{at},basis_usdt=#{basis},amount_usdt=#{usdt},amount_nex=#{nex},status=#{status},reason=#{reason},updated_at=NOW() WHERE settlement_no=#{no} AND status='WAITING_CALCULATION' AND nex_usdt_price IS NULL")
    int calculated(@Param("no") String no,@Param("price") BigDecimal price,@Param("at") LocalDateTime at,@Param("basis") BigDecimal basis,
       @Param("usdt") BigDecimal usdt,@Param("nex") BigDecimal nex,@Param("status") String status,@Param("reason") String reason);
    @Select("SELECT settlement_no FROM nx_direct_referral_settlement WHERE source_environment=#{env} AND run_id=#{runId} AND settlement_no>#{cursor} AND (status='WAITING_CALCULATION' OR status IN ('COOLING','FROZEN') AND usdt_event_id IS NULL) ORDER BY settlement_no LIMIT 100")
    List<String> waitingCalculation(@Param("env") String env,@Param("runId") String run,@Param("cursor") String cursor);
    @Select("""
      WITH RECURSIVE descendants AS (
       SELECT s.order_no,e.id,0 depth FROM nx_unilevel_order_settlement s JOIN nx_commission_event e ON e.order_no=s.order_no AND e.is_deleted=0
        WHERE s.source_environment=#{env} AND s.run_id=#{run} AND s.settlement_mode='SEVEN_V2' AND s.refund_confirmed=1
         AND e.commission_type IN ('network','direct_purchase')
       UNION ALL SELECT d.order_no,p.result_commission_id,d.depth+1 FROM descendants d JOIN nx_commission_operation p ON p.source_commission_id=d.id AND p.operation_type='REISSUE' WHERE d.depth<32)
      SELECT DISTINCT d.order_no FROM descendants d LEFT JOIN nx_unilevel_event_recovery r ON r.event_id=d.id
       WHERE d.depth>0 AND d.order_no>#{cursor} AND (r.event_id IS NULL OR r.pending_amount>0) ORDER BY d.order_no LIMIT 100
      """)
    List<String> reissueRecoveryOrders(@Param("env") String env,@Param("run") String run,@Param("cursor") String cursor);
    @Select("""
        <script>SELECT * FROM nx_direct_referral_settlement WHERE beneficiary_user_id=#{id} AND source_environment=#{env} AND run_id=#{runId}
         AND source_type IN ('direct_purchase','direct_device_earning') AND policy_version>0 AND created_at &lt;= #{snapshotAt}
         <if test="from != null"> AND created_at &gt;= #{from}</if><if test="kind != null"> AND source_type=#{kind}</if>
         ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>
        """) List<Map<String,Object>> filteredEvents(@Param("id") Long id,@Param("env") String env,@Param("runId") String run,@Param("snapshotAt") LocalDateTime snapshot,
            @Param("from") LocalDateTime from,@Param("kind") String kind,@Param("offset") long offset,@Param("limit") long limit);
    @Select("""
        <script>SELECT source_type kind,COUNT(*) count,
         COALESCE(SUM(CASE WHEN reversal_recorded=1 OR status='REJECTED' THEN 0 ELSE amount_usdt-recovered_usdt END),0) amountUSDT,
         COALESCE(SUM(CASE WHEN reversal_recorded=1 OR status='REJECTED' THEN 0 ELSE amount_nex-recovered_nex END),0) amountNEX,
         COALESCE(SUM(CASE WHEN credited_at IS NOT NULL AND reversal_recorded=0 THEN amount_usdt-recovered_usdt ELSE 0 END),0) creditedUSDT,
         COALESCE(SUM(CASE WHEN credited_at IS NOT NULL AND reversal_recorded=0 THEN amount_nex-recovered_nex ELSE 0 END),0) creditedNEX,
         COALESCE(SUM(CASE WHEN credited_at IS NULL AND status IN ('WAITING_CALCULATION','COOLING','FROZEN') THEN amount_usdt ELSE 0 END),0) pendingUSDT,
         COALESCE(SUM(CASE WHEN credited_at IS NULL AND status IN ('WAITING_CALCULATION','COOLING','FROZEN') THEN amount_nex ELSE 0 END),0) pendingNEX
         FROM nx_direct_referral_settlement WHERE beneficiary_user_id=#{id} AND source_environment=#{env} AND run_id=#{runId}
         AND source_type IN ('direct_purchase','direct_device_earning') AND policy_version>0 AND created_at &lt;= #{snapshotAt}
         <if test="from != null"> AND created_at &gt;= #{from}</if><if test="kind != null"> AND source_type=#{kind}</if> GROUP BY source_type</script>
        """) List<Map<String,Object>> filteredSums(@Param("id") Long id,@Param("env") String env,@Param("runId") String run,@Param("snapshotAt") LocalDateTime snapshot,@Param("from") LocalDateTime from,@Param("kind") String kind);
    @Select("""
        <script>SELECT * FROM nx_direct_referral_settlement s JOIN nx_user u ON u.id=s.beneficiary_user_id WHERE s.status='WAITING_CALCULATION' AND s.source_environment=#{env} AND s.run_id=#{runId}
        <if test="kind != null"> AND s.source_type=#{kind}</if><if test="user != null"> AND s.beneficiary_user_id=#{user}</if>
        <if test="cohort != null"> AND DATE_FORMAT(u.created_at,'%Y-%m')=#{cohort}</if> ORDER BY s.id DESC LIMIT 100</script>
        """) List<Map<String,Object>> pendingForOps(@Param("env") String env,@Param("runId") String run,@Param("kind") String kind,@Param("user") Long user,@Param("cohort") String cohort);
    @Select("SELECT id FROM nx_user WHERE id=#{id} AND sandbox=#{sandbox} AND is_deleted=0")
    Long sourceMember(@Param("id") Long id,@Param("sandbox") int sandbox);
    @Update("UPDATE nx_direct_referral_settlement SET usdt_event_id=#{usdt},nex_event_id=#{nex} WHERE settlement_no=#{no}")
    int linkEvents(@Param("no") String no,@Param("usdt") Long usdt,@Param("nex") Long nex);
    @Select("SELECT settlement_no FROM nx_direct_referral_settlement WHERE usdt_event_id=#{id} OR nex_event_id=#{id}") String groupForEvent(Long id);
    @Select("SELECT * FROM nx_direct_referral_settlement WHERE settlement_no=#{no} FOR UPDATE") Map<String,Object> lockGroup(String no);
    @Select("SELECT * FROM nx_direct_referral_settlement WHERE settlement_no=#{no}") Map<String,Object> group(String no);
    @Select("SELECT biz_no FROM nx_wallet_ledger WHERE user_id=#{userId} AND biz_type='TEAM_COMMISSION' AND direction='OUT' AND biz_no LIKE CONCAT(#{no},'-RECOVER-%') ORDER BY id")
    List<String> recoveryLedgerRefs(@Param("no") String no,@Param("userId") Long userId);
    @Select("SELECT id,status,version,currency FROM nx_commission_event WHERE id IN (#{usdt},#{nex}) ORDER BY id FOR UPDATE")
    List<Map<String,Object>> lockEvents(@Param("usdt") Long usdt,@Param("nex") Long nex);
    @Select("SELECT id,status,version,currency FROM nx_commission_event WHERE id=#{id}") Map<String,Object> eventView(Long id);
    @Select("SELECT COUNT(*) FROM nx_commission_user_suspension WHERE user_id=#{userId} AND kind=#{kind} AND status='SUSPENDED'")
    int suspended(@Param("userId") Long userId,@Param("kind") String kind);
    @Update("UPDATE nx_commission_event SET status=#{status},version=version+1,updated_at=NOW() WHERE id IN (#{usdt},#{nex})")
    int eventStatus(@Param("usdt") Long usdt,@Param("nex") Long nex,@Param("status") String status);
    @Update("UPDATE nx_direct_referral_settlement SET status=#{status},reason=#{reason},updated_at=NOW() WHERE settlement_no=#{no}")
    int groupStatus(@Param("no") String no,@Param("status") String status,@Param("reason") String reason);
    @Update("UPDATE nx_direct_referral_settlement SET credited_at=NOW(),status='UNLOCKED',updated_at=NOW() WHERE settlement_no=#{no} AND credited_at IS NULL")
    int credited(String no);
    @Update("UPDATE nx_direct_referral_settlement SET credited_usdt_at=CASE WHEN #{asset}='USDT' THEN NOW() ELSE credited_usdt_at END,credited_nex_at=CASE WHEN #{asset}='NEX' THEN NOW() ELSE credited_nex_at END,updated_at=NOW() WHERE settlement_no=#{no}")
    int creditedAsset(@Param("no") String no,@Param("asset") String asset);
    @Update("""
      UPDATE nx_direct_referral_settlement SET cancelled_usdt=#{targetUsdt},cancelled_nex=#{targetNex},reversal_recorded=#{complete},refund_ratio=#{ratio},
       recovered_usdt=#{usdt},recovered_nex=#{nex},recovery_pending_usdt=#{pendingUsdt},recovery_pending_nex=#{pendingNex},status=#{status},updated_at=NOW() WHERE settlement_no=#{no}
      """) int assetRecovery(@Param("no") String no,@Param("targetUsdt") BigDecimal targetUsdt,@Param("targetNex") BigDecimal targetNex,@Param("complete") int complete,@Param("ratio") BigDecimal ratio,
       @Param("usdt") BigDecimal usdt,@Param("nex") BigDecimal nex,@Param("pendingUsdt") BigDecimal pendingUsdt,@Param("pendingNex") BigDecimal pendingNex,@Param("status") String status);
    @Update("""
        UPDATE nx_direct_referral_settlement SET reversal_recorded=1,refund_ratio=#{ratio},
           recovered_usdt=#{usdt},recovered_nex=#{nex},recovery_pending_usdt=#{pendingUsdt},recovery_pending_nex=#{pendingNex},
           status=#{status},updated_at=NOW() WHERE settlement_no=#{no}
        """) int recovery(@Param("no") String no,@Param("ratio") BigDecimal ratio,@Param("usdt") BigDecimal usdt,@Param("nex") BigDecimal nex,
                             @Param("pendingUsdt") BigDecimal pendingUsdt,@Param("pendingNex") BigDecimal pendingNex,@Param("status") String status);
    @Update("UPDATE nx_user_wallet w JOIN nx_user u ON u.id=w.user_id AND u.sandbox=#{sandbox} AND u.is_deleted=0 SET w.usdt_available=w.usdt_available-#{usdt},w.nex_available=w.nex_available-#{nex},w.version=w.version+1,w.updated_at=NOW() WHERE w.user_id=#{id} AND w.sandbox=#{sandbox} AND w.is_deleted=0 AND w.usdt_available>=#{usdt} AND w.nex_available>=#{nex}")
    int debit(@Param("id") Long id,@Param("sandbox") int sandbox,@Param("usdt") BigDecimal usdt,@Param("nex") BigDecimal nex);
    @Update("UPDATE nx_earnings_release_entry SET status='REVERSED',updated_at=NOW() WHERE source_ref IN (#{usdt},#{nex}) AND user_id=#{id} AND source_type IN ('DIRECT_REFERRAL','MOCK_DIRECT_REFERRAL')")
    int reverseReleaseEntries(@Param("id") Long id,@Param("usdt") String usdt,@Param("nex") String nex);
    @Select("SELECT COUNT(*) FROM nx_earnings_release_entry WHERE user_id=#{id} AND source_ref IN (#{usdt},#{nex}) AND source_type IN ('DIRECT_REFERRAL','MOCK_DIRECT_REFERRAL') AND status='ACTIVE' AND bucket='withdrawable' AND is_deleted=0")
    int withdrawableReleaseCount(@Param("id") Long id,@Param("usdt") String usdt,@Param("nex") String nex);
    @Select("SELECT settlement_no FROM nx_direct_referral_settlement WHERE source_type='direct_purchase' AND source_ref=#{ref} ORDER BY id") List<String> purchaseGroups(String ref);
    @Select("SELECT id,settlement_no settlementNo,refund_ratio refundRatio FROM nx_direct_referral_settlement WHERE (recovery_pending_usdt>0 OR recovery_pending_nex>0) AND source_environment=#{env} AND run_id=#{runId} AND id>#{cursor} ORDER BY id LIMIT 100")
    List<RecoveryRow> pendingRecovery(@Param("env") String env,@Param("runId") String runId,@Param("cursor") long cursor);
    record RecoveryRow(long id,String settlementNo,BigDecimal refundRatio) { }
    @Select("SELECT settlement_no FROM nx_direct_referral_settlement WHERE beneficiary_user_id=#{id} AND source_type=#{kind} AND source_environment=#{env} AND run_id=#{runId} AND status IN ('COOLING','FROZEN') ORDER BY id")
    List<String> openGroups(@Param("id") Long id,@Param("kind") String kind,@Param("env") String env,@Param("runId") String runId);
    @Select("""
        <script>SELECT * FROM nx_direct_referral_settlement WHERE beneficiary_user_id=#{id} AND source_environment=#{env} AND run_id=#{runId}
         AND policy_version>0 AND nex_usdt_price>0 AND created_at &lt;= #{snapshotAt}<if test="from != null"> AND created_at &gt;= #{from}</if>
         ORDER BY id DESC LIMIT #{limit} OFFSET #{offset}</script>
        """) List<Map<String,Object>> events(@Param("id") Long id,@Param("env") String env,@Param("runId") String runId,@Param("snapshotAt") LocalDateTime snapshot,
                                               @Param("from") LocalDateTime from,@Param("offset") long offset,@Param("limit") long limit);
    @Select("""
        <script>SELECT source_type kind,COUNT(*) count,
         COALESCE(SUM(CASE WHEN status IN ('REJECTED','REVERSED','RECOVERY_PENDING') THEN 0 ELSE amount_usdt-recovered_usdt END),0) amountUSDT,
         COALESCE(SUM(CASE WHEN status IN ('REJECTED','REVERSED','RECOVERY_PENDING') THEN 0 ELSE amount_nex-recovered_nex END),0) amountNEX
         FROM nx_direct_referral_settlement WHERE beneficiary_user_id=#{id} AND source_environment=#{env} AND run_id=#{runId}
         AND policy_version>0 AND nex_usdt_price>0 AND created_at &lt;= #{snapshotAt}<if test="from != null"> AND created_at &gt;= #{from}</if> GROUP BY source_type</script>
        """) List<Map<String,Object>> sums(@Param("id") Long id,@Param("env") String env,@Param("runId") String runId,@Param("snapshotAt") LocalDateTime snapshot,@Param("from") LocalDateTime from);
}

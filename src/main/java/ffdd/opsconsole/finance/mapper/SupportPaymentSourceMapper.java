package ffdd.opsconsole.finance.mapper;

import ffdd.opsconsole.finance.facade.SupportPaymentFacts.Source;
import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.SelectProvider;
import org.apache.ibatis.annotations.InsertProvider;

// Exact source observations, not generic CRUD; every read bypasses both MyBatis caches.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface SupportPaymentSourceMapper {
    record BankTopupAmount(Long ledgerId, java.math.BigDecimal amountVnd) { }

    @Select("""
        <script>
        SELECT l.id ledgerId,r.received_vnd amountVnd
          FROM nx_wallet_ledger l JOIN nx_vietqr_reconciliation r
            ON l.biz_no COLLATE utf8mb4_0900_ai_ci=CONCAT('D1-VIETQR-',r.reconciliation_no) COLLATE utf8mb4_0900_ai_ci
           AND l.user_id=r.user_id AND l.amount=r.credited_usdt
         WHERE l.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
           AND r.status='CREDITED' AND r.is_deleted=0 AND r.received_at IS NOT NULL AND r.received_vnd&gt;0
        UNION ALL
        SELECT l.id ledgerId,i.received_vnd amountVnd
          FROM nx_wallet_ledger l JOIN nx_vietqr_intent i
            ON l.biz_no COLLATE utf8mb4_0900_ai_ci=i.intent_no COLLATE utf8mb4_0900_ai_ci AND l.user_id=i.user_id
          JOIN nx_hdpay_payin_order h ON h.merchant_order_id COLLATE utf8mb4_0900_ai_ci=i.intent_no COLLATE utf8mb4_0900_ai_ci
            AND h.wallet_ledger_biz_no COLLATE utf8mb4_0900_ai_ci=l.biz_no COLLATE utf8mb4_0900_ai_ci
         WHERE l.id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
           AND i.is_deleted=0 AND i.payment_rail='HDPAY' AND i.settlement_target_type='WALLET_TOPUP'
           AND h.settled_at IS NOT NULL AND h.settled_usdt=l.amount AND i.credited_usdt=l.amount
           AND i.received_vnd=h.amount_vnd AND i.received_vnd&gt;0
        </script>
        """)
    List<BankTopupAmount> bankTopupVnd(@Param("ids") List<Long> ids);

    record WalletReferenceRow(long ledgerId, long customerId, String businessNo, String kind,
                              java.math.BigDecimal amount, LocalDateTime at) { }

    @Select("""
        <script>
        SELECT l.id ledgerId,l.user_id customerId,l.biz_no businessNo,
               IF(l.biz_type IN ('MANUAL_CREDIT','ADMIN_MANUAL_CREDIT'),'MANUAL_CREDIT','REWARD') kind,
               l.amount,l.created_at at
          FROM nx_visible_wallet_ledger l JOIN nx_user u ON u.id=l.user_id AND u.sandbox=0
         WHERE l.user_id IN <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
           AND l.created_at &gt;= #{from} AND l.created_at &lt; #{to}
           AND l.asset='USDT' AND l.direction='IN' AND l.status='SUCCESS' AND l.amount&gt;0 AND l.is_deleted=0
           AND l.biz_type IN ('MANUAL_CREDIT','ADMIN_MANUAL_CREDIT','COMPUTE_TASK_REWARD','TRIAL_BONUS',
                              'DAILY_CHECK_IN','TEAM_COMMISSION','REFERRAL_REWARD','MILESTONE_REWARD')
           AND NOT EXISTS (SELECT 1 FROM nx_simulated_task t WHERE t.user_id=l.user_id
                 AND t.task_no COLLATE utf8mb4_0900_ai_ci=l.biz_no COLLATE utf8mb4_0900_ai_ci)
         ORDER BY l.id
        </script>
        """)
    List<WalletReferenceRow> walletReferences(@Param("ids") List<Long> ids,
            @Param("from") LocalDateTime from, @Param("to") LocalDateTime to);

    @Select("SELECT UTC_TIMESTAMP(6)")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    LocalDateTime historyNowUtc();
    @Select(SupportPaymentSourceSql.HISTORY_PAYMENT_AUDITS)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> historyPaymentAudits(@Param("customerIds") List<Long> customers);

    @Select(SupportPaymentSourceSql.HISTORY_REFUND_ORIGINS)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> historyRefundOrigins(@Param("customerIds") List<Long> customers);
    enum Marker { CREGIS_EVENT, LEDGER, PAYMENT, CARD_SETTLEMENT, INTENT, REFUND_BILL, DEVICE }

    @SelectProvider(type=SupportPaymentSourceSql.class,method="historyBeforeBatch")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> historyBeforeBatch(@Param("source") Source source,@Param("customerIds") List<Long> customers,@Param("keys") List<String> keys);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="historySettledBatch")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> historySettledBatch(@Param("source") Source source,@Param("customerIds") List<Long> customers,@Param("keys") List<String> keys);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="historyMarkerBatch")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> historyMarkerBatch(@Param("marker") Marker marker,@Param("customerIds") List<Long> customers,@Param("refs") List<Map<String,Object>> refs);

    @Select(SupportPaymentSourceSql.TRIAL_FUND_SOURCE)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> trialFundSource(@Param("customerId") long customer,@Param("claimNo") String claimNo);

    @Select(SupportPaymentSourceSql.PROOF+" FOR SHARE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> currentSourceProof(@Param("factId") String factId);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="currentMarker")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> currentMarker(@Param("marker") Marker marker,@Param("id") long id);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="currentBefore")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> currentBefore(@Param("source") Source source,@Param("customerId") long customer,@Param("key") String key,@Param("id") long id);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="currentSettled")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> currentSettled(@Param("source") Source source,@Param("customerIds") List<Long> customers,@Param("key") String key,@Param("ids") Map<String,Object> ids);

    @InsertProvider(type=SupportPaymentSourceSql.class,method="insertLedger")
    @Options(useGeneratedKeys=true,keyProperty="ledger.id",keyColumn="id",flushCache=Options.FlushCachePolicy.TRUE)
    int insertLedger(@Param("source") Source source,@Param("ledger") Map<String,Object> ledger);

    @Select(SupportPaymentSourceSql.PROOF)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    Map<String,Object> originalSourceProof(@Param("factId") String factId);

    @Select(SupportPaymentSourceSql.HISTORY_LEDGERS)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> historyLedgers(@Param("customerIds") List<Long> customers,@Param("ledgerIds") List<Long> ledgers);

    @Select(SupportPaymentSourceSql.CREGIS_EVENT)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> cregisEvents(@Param("partition") long partition,@Param("cid") long cid);
    @SelectProvider(type=SupportPaymentSourceSql.class,method="before")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> before(@Param("source") Source source,@Param("customerId") long customer,@Param("key") String key);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="settled")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> settled(@Param("source") Source source,@Param("customerIds") List<Long> customers,@Param("key") String key);

    @Select(SupportPaymentSourceSql.LEDGER)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> ledgers(@Param("key") String key);

    // Only after the canonical positive ledger has been written; absence never authorizes NEW.
    @Select(SupportPaymentSourceSql.LEDGER+" FOR SHARE")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> settledVietqrCounterpart(@Param("key") String key);

    @Select(SupportPaymentSourceSql.PAYMENT)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> payments(@Param("orderNo") String order);

    @Select(SupportPaymentSourceSql.CARD_SETTLEMENT)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> cardSettlements(@Param("key") String key);

    @Select(SupportPaymentSourceSql.INTENT)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> intent(@Param("key") String key);

    @Select(SupportPaymentSourceSql.REFUND_BILL)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> refundBills(@Param("key") String key);

    @Select(SupportPaymentSourceSql.DEVICE)
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> device(@Param("id") long id);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="refunds")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> refunds(@Param("customerIds") List<Long> customers,@Param("orderNo") String order);

    @SelectProvider(type=SupportPaymentSourceSql.class,method="trialOrder")
    @Options(useCache=false,flushCache=Options.FlushCachePolicy.TRUE)
    List<Map<String,Object>> trialOrder(@Param("customerIds") List<Long> customers,@Param("orderNo") String order);
}

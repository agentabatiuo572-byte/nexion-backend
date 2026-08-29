package ffdd.opsconsole.finance.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface WithdrawalPayoutMapper extends BaseMapper<Object> {

    @Select("""
            SELECT withdrawal_no withdrawalNo,user_id userId,chain,target_address targetAddress,
                   amount,d2_net_receive netReceive,d2_nex_burned nexBurned,status,
                   d5_payout_due_at payoutDueAt,d5_provider_cid providerCid,
                   COALESCE(d5_provider_idempotency_key,withdrawal_no) providerIdempotencyKey,
                   d5_payout_source payoutSource,chain_broadcast_attempts attempts
             FROM nx_withdrawal_order
             WHERE is_deleted=0
               AND EXISTS (
                   SELECT 1 FROM nx_user u
                    WHERE u.id=nx_withdrawal_order.user_id
                      AND u.is_deleted=0 AND u.sandbox=0
               )
               AND ((status='REVIEW_PASSED' AND (next_broadcast_at IS NULL OR next_broadcast_at<=#{now}))
                 OR (status='PROCESSING' AND d5_payout_lease_until<=#{now}))
             ORDER BY COALESCE(d5_payout_due_at,created_at),id LIMIT #{limit}
            """)
    List<PayoutRow> claimable(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
            SELECT withdrawal_no withdrawalNo,user_id userId,chain,target_address targetAddress,
                   amount,d2_net_receive netReceive,d2_nex_burned nexBurned,status,
                   d5_payout_due_at payoutDueAt,d5_provider_cid providerCid,
                   COALESCE(d5_provider_idempotency_key,withdrawal_no) providerIdempotencyKey,
                   d5_payout_source payoutSource,chain_broadcast_attempts attempts
              FROM nx_withdrawal_order
             WHERE is_deleted=0
               AND EXISTS (
                   SELECT 1 FROM nx_user u
                    WHERE u.id=nx_withdrawal_order.user_id
                      AND u.is_deleted=0 AND u.sandbox=0
               )
               AND ((status='REVIEW_PASSED' AND (next_broadcast_at IS NULL OR next_broadcast_at<=#{now}))
                 OR (status='PROCESSING' AND d5_payout_lease_until<=#{now}))
             ORDER BY COALESCE(d5_payout_due_at,created_at),id LIMIT #{limit}
            """)
    List<PayoutRow> claimableDevelopment(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
            SELECT withdrawal_no withdrawalNo,user_id userId,chain,target_address targetAddress,
                   amount,d2_net_receive netReceive,d2_nex_burned nexBurned,status,
                   d5_payout_due_at payoutDueAt,d5_provider_cid providerCid,
                   d5_provider_idempotency_key providerIdempotencyKey,
                   d5_payout_source payoutSource,chain_broadcast_attempts attempts
              FROM nx_withdrawal_order
             WHERE status='SENT' AND d5_payout_source='mock' AND completed_at IS NULL
               AND d5_provider_cid IS NOT NULL AND is_deleted=0
             ORDER BY chain_submitted_at,id LIMIT #{limit}
            """)
    List<PayoutRow> incompleteSandboxSubmissions(@Param("limit") int limit);

    @Select("""
            SELECT withdrawal_no withdrawalNo,user_id userId,chain,target_address targetAddress,
                   amount,d2_net_receive netReceive,d2_nex_burned nexBurned,status,
                   d5_payout_due_at payoutDueAt,d5_provider_cid providerCid,
                   d5_provider_idempotency_key providerIdempotencyKey,
                   d5_payout_source payoutSource,chain_broadcast_attempts attempts
              FROM nx_withdrawal_order
             WHERE status IN ('SENT','TX_ORPHANED') AND d5_payout_source='provider' AND completed_at IS NULL
               AND d5_provider_cid IS NOT NULL AND is_deleted=0
             ORDER BY chain_submitted_at,id LIMIT #{limit}
            """)
    List<PayoutRow> incompleteProviderSubmissions(@Param("limit") int limit);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status='PROCESSING',d5_payout_lease_until=#{leaseUntil},
                   d5_provider_idempotency_key=COALESCE(d5_provider_idempotency_key,withdrawal_no),
                   chain_broadcast_attempts=chain_broadcast_attempts+1,
                   last_broadcast_error=NULL,updated_at=#{now}
             WHERE withdrawal_no=#{withdrawalNo} AND is_deleted=0
               AND ((status='REVIEW_PASSED' AND (next_broadcast_at IS NULL OR next_broadcast_at<=#{now}))
                 OR (status='PROCESSING' AND d5_payout_lease_until<=#{now}))
            """)
    int claim(@Param("withdrawalNo") String withdrawalNo, @Param("now") LocalDateTime now,
              @Param("leaseUntil") LocalDateTime leaseUntil);

    @Select("""
            SELECT withdrawal_no withdrawalNo,user_id userId,chain,target_address targetAddress,
                   amount,d2_net_receive netReceive,d2_nex_burned nexBurned,status,
                   d5_payout_due_at payoutDueAt,d5_provider_cid providerCid,
                   COALESCE(d5_provider_idempotency_key,withdrawal_no) providerIdempotencyKey,
                   d5_payout_source payoutSource,chain_broadcast_attempts attempts
              FROM nx_withdrawal_order WHERE withdrawal_no=#{withdrawalNo} AND is_deleted=0 LIMIT 1
            """)
    PayoutRow payout(@Param("withdrawalNo") String withdrawalNo);

    @Select("""
            SELECT withdrawal_no withdrawalNo,user_id userId,chain,target_address targetAddress,
                   amount,d2_net_receive netReceive,d2_nex_burned nexBurned,status,
                   d5_payout_due_at payoutDueAt,d5_provider_cid providerCid,
                   COALESCE(d5_provider_idempotency_key,withdrawal_no) providerIdempotencyKey,
                   d5_payout_source payoutSource,chain_broadcast_attempts attempts
              FROM nx_withdrawal_order
             WHERE d5_provider_idempotency_key=#{providerIdempotencyKey} AND is_deleted=0 LIMIT 1
            """)
    PayoutRow payoutByProviderKey(@Param("providerIdempotencyKey") String providerIdempotencyKey);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status='SENT',d5_provider_cid=#{providerCid},d5_payout_source=#{source},
                   d5_payout_lease_until=NULL,chain_submitted_at=#{now},next_broadcast_at=NULL,
                   failure_reason=NULL,updated_at=#{now}
             WHERE withdrawal_no=#{withdrawalNo} AND status='PROCESSING' AND is_deleted=0
            """)
    int markSubmitted(@Param("withdrawalNo") String withdrawalNo, @Param("providerCid") long providerCid,
                      @Param("source") String source, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status='REVIEW_PASSED',d5_payout_lease_until=NULL,
                   next_broadcast_at=DATE_ADD(#{now},INTERVAL 5 MINUTE),
                   last_broadcast_error=#{error},failure_reason=#{error},updated_at=#{now}
             WHERE withdrawal_no=#{withdrawalNo} AND status='PROCESSING' AND is_deleted=0
            """)
    int releaseRetry(@Param("withdrawalNo") String withdrawalNo, @Param("error") String error,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status='TX_ORPHANED',d5_payout_lease_until=NULL,d5_provider_cid=#{providerCid},
                   d5_payout_source=#{source},last_broadcast_error=#{error},failure_reason=#{error},updated_at=#{now}
             WHERE withdrawal_no=#{withdrawalNo} AND status='PROCESSING' AND is_deleted=0
            """)
    int markOrphaned(@Param("withdrawalNo") String withdrawalNo, @Param("providerCid") Long providerCid,
                     @Param("source") String source, @Param("error") String error,
                     @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status=#{status},chain_tx_hash=#{txid},completed_at=CASE WHEN #{status}='CONFIRMED' THEN #{now} ELSE completed_at END,
                   failed_at=CASE WHEN #{status}='FAILED' THEN #{now} ELSE failed_at END,
                   failure_reason=#{failureReason},d5_payout_lease_until=NULL,updated_at=#{now}
             WHERE withdrawal_no=#{withdrawalNo} AND status='SENT' AND d5_provider_cid=#{providerCid}
               AND is_deleted=0
            """)
    int terminalOrder(@Param("withdrawalNo") String withdrawalNo, @Param("providerCid") long providerCid,
                      @Param("status") String status, @Param("txid") String txid,
                      @Param("failureReason") String failureReason, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status='TX_ORPHANED',failure_reason=#{error},last_broadcast_error=#{error},updated_at=#{now}
             WHERE withdrawal_no=#{withdrawalNo} AND status='SENT' AND d5_provider_cid=#{providerCid}
               AND is_deleted=0
            """)
    int holdAmbiguousCallback(@Param("withdrawalNo") String withdrawalNo,
                              @Param("providerCid") long providerCid,
                              @Param("error") String error, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_withdrawal_order
               SET status='SENT',d5_provider_cid=#{providerCid},failure_reason=NULL,
                   last_broadcast_error=NULL,updated_at=#{now}
             WHERE withdrawal_no=#{withdrawalNo} AND status='TX_ORPHANED'
               AND d5_payout_source='provider' AND d5_provider_idempotency_key=#{providerKey}
               AND is_deleted=0
            """)
    int recoverOrphanToSent(@Param("withdrawalNo") String withdrawalNo,
                            @Param("providerCid") long providerCid,
                            @Param("providerKey") String providerKey,
                            @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_user_wallet SET pending_withdraw=pending_withdraw-#{amount},version=version+1,updated_at=#{now}
             WHERE user_id=#{userId} AND is_deleted=0 AND pending_withdraw>=#{amount}
            """)
    int settlePending(@Param("userId") Long userId, @Param("amount") BigDecimal amount,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE nx_user_wallet
               SET usdt_available=usdt_available+#{amount},nex_available=nex_available+#{nexBurned},
                   pending_withdraw=pending_withdraw-#{amount},version=version+1,updated_at=#{now}
             WHERE user_id=#{userId} AND is_deleted=0 AND pending_withdraw>=#{amount}
            """)
    int refundPending(@Param("userId") Long userId, @Param("amount") BigDecimal amount,
                      @Param("nexBurned") BigDecimal nexBurned, @Param("now") LocalDateTime now);

    @Insert("""
            INSERT IGNORE INTO nx_withdrawal_payout_ledger
              (event_no,withdrawal_no,provider_cid,event_type,status,source,amount_usdt,txid,payload_hash,created_at)
            VALUES (#{eventNo},#{withdrawalNo},#{providerCid},#{eventType},#{status},#{source},#{amount},#{txid},#{payloadHash},#{now})
            """)
    int insertPayoutLedger(@Param("eventNo") String eventNo, @Param("withdrawalNo") String withdrawalNo,
                           @Param("providerCid") long providerCid, @Param("eventType") String eventType,
                           @Param("status") String status, @Param("source") String source,
                           @Param("amount") BigDecimal amount, @Param("txid") String txid,
                           @Param("payloadHash") String payloadHash, @Param("now") LocalDateTime now);

    @Select("SELECT payload_hash FROM nx_withdrawal_payout_ledger WHERE event_no=#{eventNo} LIMIT 1")
    String payoutLedgerPayloadHash(@Param("eventNo") String eventNo);

    @Select("SELECT COUNT(*)>0 FROM nx_withdrawal_payout_ledger WHERE withdrawal_no=#{withdrawalNo} AND status='PROVIDER_6'")
    boolean hasProviderSuccessEvidence(@Param("withdrawalNo") String withdrawalNo);

    @Insert("""
            INSERT IGNORE INTO nx_withdrawal_payout_callback_inbox
              (event_no,withdrawal_no,provider_cid,provider_status,txid,payload_hash,status,attempts,next_attempt_at,created_at,updated_at)
            VALUES (#{eventNo},#{withdrawalNo},#{providerCid},#{providerStatus},#{txid},#{payloadHash},'PENDING',0,#{now},#{now},#{now})
            """)
    int insertCallbackInbox(@Param("eventNo") String eventNo, @Param("withdrawalNo") String withdrawalNo,
                            @Param("providerCid") long providerCid, @Param("providerStatus") int providerStatus,
                            @Param("txid") String txid, @Param("payloadHash") String payloadHash,
                            @Param("now") LocalDateTime now);

    @Select("""
            SELECT event_no eventNo,withdrawal_no withdrawalNo,provider_cid providerCid,
                   provider_status providerStatus,txid,payload_hash payloadHash
              FROM nx_withdrawal_payout_callback_inbox
             WHERE (status='PENDING' AND next_attempt_at<=#{now})
                OR (status='PROCESSING' AND lease_until<=#{now})
             ORDER BY id LIMIT #{limit}
            """)
    List<CallbackInboxRow> claimableCallbackInbox(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Update("""
            UPDATE nx_withdrawal_payout_callback_inbox
               SET status='PROCESSING',attempts=attempts+1,lease_until=#{leaseUntil},updated_at=#{now}
             WHERE event_no=#{eventNo} AND ((status='PENDING' AND next_attempt_at<=#{now})
                OR (status='PROCESSING' AND lease_until<=#{now}))
            """)
    int claimCallbackInbox(@Param("eventNo") String eventNo, @Param("now") LocalDateTime now,
                           @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("UPDATE nx_withdrawal_payout_callback_inbox SET status='PROCESSED',lease_until=NULL,last_error=NULL,updated_at=#{now} WHERE event_no=#{eventNo} AND status='PROCESSING'")
    int finishCallbackInbox(@Param("eventNo") String eventNo, @Param("now") LocalDateTime now);

    @Update("UPDATE nx_withdrawal_payout_callback_inbox SET status='PENDING',lease_until=NULL,next_attempt_at=DATE_ADD(#{now},INTERVAL 2 MINUTE),last_error=#{error},updated_at=#{now} WHERE event_no=#{eventNo} AND status='PROCESSING'")
    int retryCallbackInbox(@Param("eventNo") String eventNo, @Param("error") String error,
                           @Param("now") LocalDateTime now);

    record PayoutRow(String withdrawalNo, Long userId, String chain, String targetAddress,
                     BigDecimal amount, BigDecimal netReceive, BigDecimal nexBurned, String status,
                     LocalDateTime payoutDueAt, Long providerCid, String providerIdempotencyKey,
                     String payoutSource, Integer attempts) { }

    record CallbackInboxRow(String eventNo, String withdrawalNo, Long providerCid,
                            Integer providerStatus, String txid, String payloadHash) { }
}

package ffdd.opsconsole.finance.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.*;

@Mapper
// Statement-only command boundary spanning beneficiaries, quotes, payouts and withdrawal orders.
@SuppressWarnings("MybatisPlusBaseMapper")
public interface BankWithdrawalMapper {
    @Select("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN ('nx_bank_payout_beneficiary','nx_bank_payout_quote','nx_hdpay_payout','nx_hdpay_payout_callback')")
    int schemaTables();
    // Immediate activation also applies to existing bindings. Keep the historical delay column intact.
    String BENEFICIARY = "SELECT user_id userId,beneficiary_no beneficiaryNo,bank_code bankCode,masked_account maskedAccount,"
            + "recipient_cipher recipientCipher,updated_at effectiveAt,next_change_at nextChangeAt,version FROM nx_bank_payout_beneficiary ";
    @Select(BENEFICIARY + "WHERE user_id=#{userId}") Beneficiary beneficiary(Long userId);
    @Select(BENEFICIARY + "WHERE user_id=#{userId} FOR UPDATE") Beneficiary lockBeneficiary(Long userId);
    @Insert("""
            INSERT INTO nx_bank_payout_beneficiary(user_id,beneficiary_no,bank_code,masked_account,recipient_cipher,
              effective_at,next_change_at,version,updated_at)
            VALUES(#{userId},#{beneficiaryNo},#{bankCode},#{maskedAccount},#{recipientCipher},#{effectiveAt},#{nextChangeAt},#{version},#{now})
            ON DUPLICATE KEY UPDATE beneficiary_no=VALUES(beneficiary_no),bank_code=VALUES(bank_code),
              masked_account=VALUES(masked_account),recipient_cipher=VALUES(recipient_cipher),
              effective_at=VALUES(effective_at),next_change_at=VALUES(next_change_at),version=VALUES(version),updated_at=VALUES(updated_at)
            """)
    int saveBeneficiary(@Param("userId") long userId, @Param("beneficiaryNo") String beneficiaryNo,
            @Param("bankCode") String bankCode, @Param("maskedAccount") String maskedAccount,
            @Param("recipientCipher") String recipientCipher, @Param("effectiveAt") LocalDateTime effectiveAt,
            @Param("nextChangeAt") LocalDateTime nextChangeAt, @Param("version") long version, @Param("now") LocalDateTime now);

    @Select("""
            SELECT beneficiary_no beneficiaryNo,user_id userId,beneficiary_version beneficiaryVersion,
              verification_status verificationStatus,payout_capability payoutCapability,ownership_status ownershipStatus,
              account_type accountType,reason_code reasonCode,checked_at checkedAt,expires_at expiresAt,
              evidence_ref evidenceRef,capability_version capabilityVersion,provider,requested_at requestedAt
            FROM nx_bank_beneficiary_verification WHERE beneficiary_no=#{beneficiaryNo}
            """) Verification verification(String beneficiaryNo);
    @Insert("""
            INSERT INTO nx_bank_beneficiary_verification(beneficiary_no,user_id,beneficiary_version,verification_status,
              payout_capability,ownership_status,account_type,reason_code,checked_at,expires_at,evidence_ref,
              capability_version,provider,requested_at)
            VALUES(#{beneficiaryNo},#{userId},#{beneficiaryVersion},#{verificationStatus},#{payoutCapability},#{ownershipStatus},
              #{accountType},#{reasonCode},#{checkedAt},#{expiresAt},#{evidenceRef},#{capabilityVersion},#{provider},#{requestedAt})
            ON DUPLICATE KEY UPDATE verification_status=VALUES(verification_status),payout_capability=VALUES(payout_capability),
              ownership_status=VALUES(ownership_status),account_type=VALUES(account_type),reason_code=VALUES(reason_code),
              checked_at=VALUES(checked_at),expires_at=VALUES(expires_at),evidence_ref=VALUES(evidence_ref),
              capability_version=VALUES(capability_version),provider=VALUES(provider),requested_at=VALUES(requested_at)
            """) int saveVerification(Verification verification);

    @Insert("""
            INSERT IGNORE INTO nx_bank_payout_quote_expiry(quote_no,expired_at)
            SELECT quote_no,#{now} FROM nx_bank_payout_quote WHERE user_id=#{userId}
              AND withdrawal_no IS NULL AND cancelled_at IS NULL AND expires_at <= #{now}
            """) int sealExpiredQuotes(@Param("userId") long userId, @Param("now") LocalDateTime now);
    @Select("SELECT COUNT(*) FROM nx_bank_payout_quote_expiry WHERE quote_no=#{quoteNo}") int expired(String quoteNo);

    String QUOTE = "SELECT quote_no quoteNo,user_id userId,beneficiary_no beneficiaryNo,beneficiary_version beneficiaryVersion,"
            + "bank_code bankCode,masked_account maskedAccount,recipient_cipher recipientCipher,amount_usdt amountUsdt,fee_usdt feeUsdt,"
            + "net_usdt netUsdt,rate_vnd rateVnd,amount_vnd amountVnd,d7_version d7Version,d5_version d5Version,"
            + "created_at createdAt,expires_at expiresAt,withdrawal_no withdrawalNo FROM nx_bank_payout_quote ";
    @Select(QUOTE + "WHERE quote_no=#{quoteNo} AND user_id=#{userId} FOR UPDATE")
    Quote lockQuote(@Param("quoteNo") String quoteNo, @Param("userId") long userId);
    @Select(QUOTE + "WHERE quote_no=#{quoteNo}") Quote quote(String quoteNo);
    @Select(QUOTE + "WHERE user_id=#{userId} AND withdrawal_no IS NULL AND cancelled_at IS NULL "
            + "AND NOT EXISTS(SELECT 1 FROM nx_bank_payout_quote_expiry e WHERE e.quote_no=nx_bank_payout_quote.quote_no) ORDER BY created_at,quote_no")
    List<Quote> activeQuotes(long userId);
    @Select("SELECT COUNT(*) FROM nx_bank_payout_quote WHERE quote_no=#{quoteNo} AND cancelled_at IS NOT NULL") int cancelled(String quoteNo);
    @Update("UPDATE nx_bank_payout_quote SET cancelled_at=CURRENT_TIMESTAMP(6) WHERE quote_no=#{quoteNo} AND withdrawal_no IS NULL") int cancelQuote(String quoteNo);
    @Select("SELECT COUNT(*) FROM nx_bank_payout_quote WHERE user_id=#{userId} AND created_at > DATE_SUB(#{now},INTERVAL 1 MINUTE)")
    int recentQuotes(@Param("userId") long userId, @Param("now") LocalDateTime now);
    @Insert("""
            INSERT INTO nx_bank_payout_quote(quote_no,user_id,beneficiary_no,beneficiary_version,bank_code,masked_account,recipient_cipher,
              amount_usdt,fee_usdt,net_usdt,rate_vnd,amount_vnd,d7_version,d5_version,created_at,expires_at)
            VALUES(#{quoteNo},#{userId},#{beneficiaryNo},#{beneficiaryVersion},#{bankCode},#{maskedAccount},#{recipientCipher},
              #{amountUsdt},#{feeUsdt},#{netUsdt},#{rateVnd},#{amountVnd},#{d7Version},#{d5Version},#{createdAt},#{expiresAt})
            """) int insertQuote(Quote quote);
    @Update("UPDATE nx_bank_payout_quote SET withdrawal_no=#{order} WHERE quote_no=#{quote} AND withdrawal_no IS NULL AND cancelled_at IS NULL "
            + "AND NOT EXISTS(SELECT 1 FROM nx_bank_payout_quote_expiry e WHERE e.quote_no=#{quote})")
    int useQuote(@Param("quote") String quote, @Param("order") String order);
    @Insert("""
            INSERT INTO nx_hdpay_payout(withdrawal_no,quote_no,user_id,state,created_at,updated_at)
            VALUES(#{order},#{quote},#{userId},'READY',#{now},#{now})
            """)
    int insertOrder(@Param("order") String order, @Param("quote") String quote, @Param("userId") long userId, @Param("now") LocalDateTime now);

    String ORDER = "SELECT withdrawal_no withdrawalNo,quote_no quoteNo,user_id userId,state,provider_order_id providerOrderId,"
            + "provider_status providerStatus,last_error lastError FROM nx_hdpay_payout ";
    @Select(ORDER + "WHERE withdrawal_no=#{order} FOR UPDATE") Order lockOrder(String order);
    @Select(ORDER + "WHERE withdrawal_no=#{order}") Order order(String order);
    // Shared by receipt projection and unresolved-intent filtering: no weaker terminal-state shortcut.
    String PROVIDER_SETTLEMENT_PROOF = """
            l.withdrawal_no=p.withdrawal_no AND p.provider_order_id=l.provider_cid
              AND w.withdrawal_no=p.withdrawal_no AND w.user_id=p.user_id
              AND w.is_deleted=0 AND w.chain='BANK-VND' AND w.d5_payout_source='hdpay'
              AND w.d5_provider_cid=p.provider_order_id AND l.source='hdpay' AND l.status=w.status
              AND l.amount_usdt=w.d2_net_receive AND l.event_type='CALLBACK'
              AND ((p.state='PAID' AND p.provider_status=3 AND w.status='CONFIRMED')
                OR (p.state='FAILED' AND p.provider_status IN (4,5) AND w.status='FAILED' AND EXISTS(
                  SELECT 1 FROM nx_wallet_ledger r WHERE r.biz_no=CONCAT(w.withdrawal_no,':PAYOUT:USDT:REFUND')
                    AND r.user_id=w.user_id AND r.biz_type='WITHDRAW_PAYOUT_REFUND' AND r.asset='USDT'
                    AND r.direction='IN' AND r.amount=w.amount AND r.status='POSTED' AND r.is_deleted=0)))
            """;
    String D2_SETTLEMENT_PROOF = """
            w.withdrawal_no=p.withdrawal_no AND w.user_id=p.user_id
              AND r.biz_no=CONCAT('D2-REFUND-',w.withdrawal_no) AND r.user_id=w.user_id
              AND p.state='READY' AND p.provider_order_id IS NULL
              AND w.chain='BANK-VND' AND w.status='REFUNDED' AND w.is_deleted=0 AND COALESCE(w.chain_broadcast_attempts,0)=0
              AND r.biz_type='WITHDRAW_REFUND' AND r.asset='USDT' AND r.direction='IN'
              AND r.amount=w.amount AND r.status='SUCCESS' AND r.is_deleted=0
            """;
    @Select("""
            SELECT p.withdrawal_no withdrawalNo,p.quote_no quoteNo,p.user_id userId,p.state,
              p.provider_order_id providerOrderId,p.provider_status providerStatus,p.last_error lastError
            FROM nx_hdpay_payout p WHERE p.user_id=#{userId}
              AND NOT EXISTS(SELECT 1 FROM nx_withdrawal_order w JOIN nx_withdrawal_payout_ledger l
                ON l.withdrawal_no=w.withdrawal_no WHERE
            """ + PROVIDER_SETTLEMENT_PROOF + ") AND NOT EXISTS(SELECT 1 FROM nx_withdrawal_order w "
            + "JOIN nx_wallet_ledger r ON r.user_id=w.user_id WHERE " + D2_SETTLEMENT_PROOF
            + ") ORDER BY p.created_at,p.withdrawal_no") List<Order> unresolvedOrders(long userId);
    @Select("""
            SELECT l.event_no evidenceRef,l.status,l.created_at checkedAt,w.amount amountUsdt,
              p.provider_order_id providerOrderId,p.provider_status providerStatus
            FROM nx_hdpay_payout p JOIN nx_withdrawal_order w ON w.withdrawal_no=p.withdrawal_no
            JOIN nx_withdrawal_payout_ledger l ON l.withdrawal_no=p.withdrawal_no
            WHERE p.withdrawal_no=#{order} AND
            """ + PROVIDER_SETTLEMENT_PROOF + " UNION ALL " + """
            SELECT r.biz_no evidenceRef,'REFUNDED' status,r.created_at checkedAt,w.amount amountUsdt,
              p.provider_order_id providerOrderId,p.provider_status providerStatus
            FROM nx_hdpay_payout p JOIN nx_withdrawal_order w ON w.withdrawal_no=p.withdrawal_no
            JOIN nx_wallet_ledger r ON r.user_id=w.user_id
            WHERE p.withdrawal_no=#{order} AND
            """ + D2_SETTLEMENT_PROOF + " ORDER BY checkedAt,evidenceRef LIMIT 1")
    SettlementEvidence settlementEvidence(String order);
    @Select("SELECT version FROM nx_hdpay_payout WHERE withdrawal_no=#{order}") Long version(String order);
    @Select("SELECT approved_risk_hash FROM nx_hdpay_payout WHERE withdrawal_no=#{order}") String approvedRiskHash(String order);
    @Update("UPDATE nx_hdpay_payout SET approved_risk_hash=#{hash} WHERE withdrawal_no=#{order} AND state='READY'")
    int approveRisk(@Param("order") String order, @Param("hash") String hash);
    @Update("""
            UPDATE nx_withdrawal_order SET status='REVIEW_PENDING',failure_reason='BANK_PAYOUT_RISK_REVIEW_REQUIRED',
              updated_at=#{now},version=version+1
            WHERE withdrawal_no=#{order} AND chain='BANK-VND' AND status='REVIEW_PASSED' AND is_deleted=0
            """) int returnForReview(@Param("order") String order, @Param("now") LocalDateTime now);
    @Select("""
            SELECT p.withdrawal_no FROM nx_hdpay_payout p JOIN nx_withdrawal_order w ON w.withdrawal_no=p.withdrawal_no
            JOIN nx_user u ON u.id=w.user_id AND u.status='ACTIVE' AND u.is_deleted=0 AND COALESCE(u.sandbox,0)=0
            WHERE p.state='READY' AND w.chain='BANK-VND' AND w.status='REVIEW_PASSED' AND w.is_deleted=0
              AND w.d2_hold_until <= #{now} ORDER BY w.id LIMIT 10
            """) List<String> ready(LocalDateTime now);
    @Update("""
            UPDATE nx_hdpay_payout SET state='DISPATCHING',next_query_at=DATE_ADD(#{now},INTERVAL 1 MINUTE),updated_at=#{now}
            WHERE withdrawal_no=#{order} AND state='READY'
            """) int dispatch(@Param("order") String order, @Param("now") LocalDateTime now);
    @Update("""
            UPDATE nx_withdrawal_order SET status='PROCESSING',d5_payout_source='hdpay',d5_provider_idempotency_key=withdrawal_no,
              chain_broadcast_attempts=chain_broadcast_attempts+1,updated_at=#{now},version=version+1
            WHERE withdrawal_no=#{order} AND chain='BANK-VND' AND status='REVIEW_PASSED' AND d2_hold_until <= #{now} AND is_deleted=0
            """) int processing(@Param("order") String order, @Param("now") LocalDateTime now);
    @Select("""
            SELECT withdrawal_no FROM nx_hdpay_payout
            WHERE state IN ('DISPATCHING','PENDING') AND next_query_at <= #{now} ORDER BY next_query_at LIMIT 20
            """) List<String> queryDue(LocalDateTime now);
    @Update("""
            UPDATE nx_hdpay_payout SET state=#{state},provider_order_id=COALESCE(provider_order_id,#{providerId}),version=version+1,
              provider_status=#{providerStatus},last_error=#{error},next_query_at=DATE_ADD(#{now},INTERVAL 2 MINUTE),updated_at=#{now}
            WHERE withdrawal_no=#{order}
            """)
    int progress(@Param("order") String order, @Param("state") String state, @Param("providerId") Long providerId,
                 @Param("providerStatus") Integer providerStatus, @Param("error") String error, @Param("now") LocalDateTime now);
    @Update("""
            UPDATE nx_withdrawal_order SET status='TX_ORPHANED',failure_reason=#{error},updated_at=#{now},version=version+1
            WHERE withdrawal_no=#{order} AND chain='BANK-VND' AND status IN ('PROCESSING','SENT','TX_ORPHANED') AND is_deleted=0
            """) int hold(@Param("order") String order, @Param("error") String error, @Param("now") LocalDateTime now);
    @Update("""
            UPDATE nx_withdrawal_order SET status=IF(d5_provider_cid IS NULL,'PROCESSING','SENT'),failure_reason=NULL,
              updated_at=#{now},version=version+1
            WHERE withdrawal_no=#{order} AND chain='BANK-VND' AND d5_payout_source='hdpay'
              AND status='TX_ORPHANED' AND is_deleted=0
            """) int resumeHeld(@Param("order") String order, @Param("now") LocalDateTime now);
    @Insert("""
            INSERT IGNORE INTO nx_hdpay_payout_callback(event_hash,withdrawal_no,provider_order_id,provider_status,amount_vnd,received_at)
            VALUES(#{hash},#{merchantOrderId},#{providerOrderId},#{status},#{amount},CURRENT_TIMESTAMP(6))
            """) int callback(ffdd.opsconsole.finance.hdpay.HdPayPayoutCallbackVerifier.Callback callback);
    @Select("""
            SELECT COUNT(*) FROM nx_hdpay_payout_callback WHERE withdrawal_no=#{order}
              AND (provider_order_id != #{providerId} OR amount_vnd != #{amount}
                   OR (#{status}=3 AND provider_status IN (4,5)) OR (#{status} IN (4,5) AND provider_status=3))
            """)
    int conflictingCallbacks(@Param("order") String order, @Param("providerId") long providerId,
                @Param("amount") BigDecimal amount, @Param("status") int status);

    record Beneficiary(Long userId, String beneficiaryNo, String bankCode, String maskedAccount, String recipientCipher,
            LocalDateTime effectiveAt, LocalDateTime nextChangeAt, Long version) {
        @Override public String toString() { return "BankBeneficiary[REDACTED]"; }
    }
    record Quote(String quoteNo, Long userId, String beneficiaryNo, Long beneficiaryVersion, String bankCode,
            String maskedAccount, String recipientCipher, BigDecimal amountUsdt, BigDecimal feeUsdt, BigDecimal netUsdt,
            BigDecimal rateVnd, BigDecimal amountVnd, Long d7Version, String d5Version,
            LocalDateTime createdAt, LocalDateTime expiresAt, String withdrawalNo) {
        @Override public String toString() { return "BankQuote[REDACTED]"; }
    }
    record Order(String withdrawalNo, String quoteNo, Long userId, String state, Long providerOrderId, Integer providerStatus, String lastError) {}
    record Verification(String beneficiaryNo, Long userId, Long beneficiaryVersion, String verificationStatus,
            String payoutCapability, String ownershipStatus, String accountType, String reasonCode,
            LocalDateTime checkedAt, LocalDateTime expiresAt, String evidenceRef, String capabilityVersion,
            String provider, LocalDateTime requestedAt) { }
    record SettlementEvidence(String evidenceRef, String status, LocalDateTime checkedAt, BigDecimal amountUsdt,
            Long providerOrderId, Integer providerStatus) { }
}

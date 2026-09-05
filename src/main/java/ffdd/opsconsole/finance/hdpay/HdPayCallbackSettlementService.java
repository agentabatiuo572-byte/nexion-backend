package ffdd.opsconsole.finance.hdpay;

import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class HdPayCallbackSettlementService {
    private static final String SUCCESS = "success";

    public enum ClaimDisposition {
        QUERY_PROVIDER,
        ACKNOWLEDGED,
        RETRY_LATER
    }

    public record PaidCallbackFact(
            String payloadHash,
            String merchantOrderId,
            String providerOrderId,
            int orderStatus,
            BigDecimal transAmt) {}

    public record QueryClaim(
            ClaimDisposition disposition,
            PaidCallbackFact fact,
            String claimToken) {}

    private final HdPayOrderMapper hdPayMapper;
    private final AppVietQrIntentMapper intentMapper;
    private final VietnamPaymentMapper paymentMapper;
    private final EventOutboxService outbox;
    private final AuditLogService audit;
    private final Clock clock;

    @Autowired
    public HdPayCallbackSettlementService(
            HdPayOrderMapper hdPayMapper,
            AppVietQrIntentMapper intentMapper,
            VietnamPaymentMapper paymentMapper,
            EventOutboxService outbox,
            AuditLogService audit,
            Clock clock) {
        this.hdPayMapper = hdPayMapper;
        this.intentMapper = intentMapper;
        this.paymentMapper = paymentMapper;
        this.outbox = outbox;
        this.audit = audit;
        this.clock = clock;
    }

    /**
     * Persists and claims a signed paid callback before the provider query. This short
     * transaction is the durable hand-off that allows crash recovery without trusting
     * the provider to deliver the callback forever.
     */
    @Transactional(rollbackFor = Exception.class)
    public QueryClaim claimForProviderQuery(HdPayCallbackVerifier.VerifiedCallback callback) {
        PaidCallbackFact fact = fact(callback);
        String claimToken = newClaimToken();
        Map<String, Object> order = lockOrder(fact.merchantOrderId());
        requireProviderOrderCompatible(order, fact.providerOrderId());
        int inserted = hdPayMapper.insertCallbackInbox(
                fact.payloadHash(), fact.merchantOrderId(), fact.providerOrderId(),
                fact.orderStatus(), fact.transAmt(), "PROCESSING", claimToken);
        if (inserted == 1) {
            requireOne(hdPayMapper.updateCallbackObservation(
                    fact.merchantOrderId(), fact.providerOrderId(), fact.orderStatus()),
                    "HDPAY_CALLBACK_ORDER_CONFLICT");
            if ("CREDITED".equals(code(order.get("settlementStatus")))) {
                postCreditReview(fact, claimToken, "HDPAY_POST_CREDIT_CALLBACK_REVIEW");
                return new QueryClaim(ClaimDisposition.ACKNOWLEDGED, fact, null);
            }
            if ("MANUAL_REVIEW".equals(code(order.get("settlementStatus")))) {
                createReviewFact(fact, "HDPAY_SETTLEMENT_ALREADY_UNDER_REVIEW");
                requireOne(hdPayMapper.markCallbackProcessedOwned(
                        fact.payloadHash(), claimToken, "MANUAL_REVIEW", fact.orderStatus(),
                        "HDPAY_SETTLEMENT_ALREADY_UNDER_REVIEW"),
                        "HDPAY_CALLBACK_INBOX_UPDATE_FAILED");
                return new QueryClaim(ClaimDisposition.ACKNOWLEDGED, fact, null);
            }
            return new QueryClaim(ClaimDisposition.QUERY_PROVIDER, fact, claimToken);
        }
        requireZero(inserted, "HDPAY_CALLBACK_INBOX_WRITE_FAILED");
        Map<String, Object> existing = hdPayMapper.findCallbackInboxForUpdate(fact.payloadHash());
        if (existing == null || existing.isEmpty()) {
            throw new BizException(503, "HDPAY_CALLBACK_INBOX_READ_FAILED");
        }
        String status = code(existing.get("processingStatus"));
        if ("CREDITED".equals(status) || "MANUAL_REVIEW".equals(status)) {
            return new QueryClaim(ClaimDisposition.ACKNOWLEDGED, fact, null);
        }
        return new QueryClaim(ClaimDisposition.RETRY_LATER, fact, null);
    }

    @Transactional(rollbackFor = Exception.class)
    public void releaseProviderQueryClaim(
            PaidCallbackFact fact,
            String claimToken,
            Integer providerQueryStatus,
            String resultCode) {
        hdPayMapper.markCallbackProcessedOwned(
                fact.payloadHash(), claimToken, "OBSERVED", providerQueryStatus, resultCode);
    }

    @Transactional(rollbackFor = Exception.class)
    public void reviewProviderQueryClaim(PaidCallbackFact fact, String claimToken, String reason) {
        Map<String, Object> order = lockOrder(fact.merchantOrderId());
        if ("CREDITED".equals(code(order.get("settlementStatus")))) {
            postCreditReview(fact, claimToken, reason);
            return;
        }
        manualReview(fact, claimToken, reason);
    }

    @Transactional(rollbackFor = Exception.class)
    public String claimStoredCallbackForRetry(String payloadHash, LocalDateTime staleBefore) {
        String claimToken = newClaimToken();
        return hdPayMapper.claimCallbackForRetry(payloadHash, staleBefore, claimToken) == 1
                ? claimToken : null;
    }

    public java.util.List<PaidCallbackFact> listRecoverablePaidCallbacks(
            LocalDateTime staleBefore,
            int limit) {
        return hdPayMapper.listRecoverablePaidCallbacks(staleBefore, Math.max(1, Math.min(limit, 50)))
                .stream()
                .map(this::storedFact)
                .toList();
    }

    @Transactional(rollbackFor = Exception.class)
    public String observe(HdPayCallbackVerifier.VerifiedCallback callback) {
        Map<String, Object> order = lockOrder(callback.merchantOrderId());
        BigDecimal expectedVnd = decimal(order.get("amountVnd"), "HDPAY_ORDER_AMOUNT_INVALID");
        String processingStatus = expectedVnd.compareTo(callback.transAmt()) == 0
                ? "OBSERVED" : "AMOUNT_MISMATCH";
        String payloadHash = payloadHash(callback);
        int inserted = hdPayMapper.insertCallbackInbox(
                payloadHash,
                callback.merchantOrderId(),
                callback.orderId(),
                callback.orderStatus(),
                callback.transAmt(),
                processingStatus,
                null);
        if (inserted == 0) return SUCCESS;
        requireOne(inserted, "HDPAY_CALLBACK_INBOX_WRITE_FAILED");
        requireOne(hdPayMapper.updateCallbackObservation(
                callback.merchantOrderId(), callback.orderId(), callback.orderStatus()),
                "HDPAY_CALLBACK_ORDER_CONFLICT");
        if ("CREDITED".equals(code(order.get("settlementStatus")))) {
            String reason = "HDPAY_POST_CREDIT_PROVIDER_STATUS_REVIEW";
            requireOne(hdPayMapper.markPostCreditReview(
                    callback.merchantOrderId(), callback.orderId(), reason),
                    "HDPAY_POST_CREDIT_REVIEW_WRITE_FAILED");
            createReviewFact(fact(callback), reason);
            requireOne(hdPayMapper.markCallbackProcessed(
                    payloadHash, "MANUAL_REVIEW", callback.orderStatus(), reason),
                    "HDPAY_CALLBACK_INBOX_UPDATE_FAILED");
            return SUCCESS;
        }
        requireOne(hdPayMapper.markCallbackProcessed(
                payloadHash, processingStatus, callback.orderStatus(), processingStatus),
                "HDPAY_CALLBACK_INBOX_UPDATE_FAILED");
        return SUCCESS;
    }

    @Transactional(rollbackFor = Exception.class)
    public String settleConfirmed(
            HdPayCallbackVerifier.VerifiedCallback callback,
            HdPayGateway.PayOrder confirmed) {
        QueryClaim claim = claimForProviderQuery(callback);
        if (claim.disposition() == ClaimDisposition.ACKNOWLEDGED) return SUCCESS;
        if (claim.disposition() == ClaimDisposition.RETRY_LATER) {
            throw new BizException(503, "HDPAY_CALLBACK_QUERY_ALREADY_CLAIMED");
        }
        return settleConfirmed(claim.fact(), claim.claimToken(), confirmed);
    }

    @Transactional(rollbackFor = Exception.class)
    public String settleConfirmed(
            PaidCallbackFact fact,
            String claimToken,
            HdPayGateway.PayOrder confirmed) {
        requireConfirmedIdentity(fact, confirmed);
        Map<String, Object> order = lockOrder(fact.merchantOrderId());
        requireProviderOrderCompatible(order, fact.providerOrderId());
        BigDecimal expectedVnd = decimal(order.get("amountVnd"), "HDPAY_ORDER_AMOUNT_INVALID");
        String payloadHash = fact.payloadHash();
        requireOwnedProcessing(payloadHash, claimToken);

        if (expectedVnd.compareTo(fact.transAmt()) != 0) {
            return manualReview(fact, claimToken, "HDPAY_AMOUNT_MISMATCH");
        }

        String settlementStatus = code(order.get("settlementStatus"));
        if ("CREDITED".equals(settlementStatus)) {
            postCreditReview(fact, claimToken, "HDPAY_POST_CREDIT_CALLBACK_REVIEW");
            return SUCCESS;
        }
        if ("MANUAL_REVIEW".equals(settlementStatus)) {
            return manualReview(fact, claimToken, "HDPAY_SETTLEMENT_ALREADY_UNDER_REVIEW");
        }
        String submissionStatus = code(order.get("submissionStatus"));
        if (!"CREATED".equals(submissionStatus) && !"SUBMIT_UNKNOWN".equals(submissionStatus)) {
            return manualReview(fact, claimToken, "HDPAY_ORDER_NOT_SUBMITTED");
        }

        Map<String, Object> intent = intentMapper.findIntentForUpdate(fact.merchantOrderId());
        if (intent == null || intent.isEmpty()) {
            return manualReview(fact, claimToken, "HDPAY_INTENT_NOT_FOUND");
        }
        if (!"AWAITING_PAYMENT".equals(code(intent.get("status")))) {
            return manualReview(fact, claimToken, "HDPAY_INTENT_NOT_AWAITING_PAYMENT");
        }
        LocalDateTime expiresAt = localDateTime(intent.get("expiresAt"));
        LocalDateTime now = LocalDateTime.now(clock);
        if (expiresAt == null || !expiresAt.isAfter(now)) {
            return manualReview(fact, claimToken, "HDPAY_INTENT_EXPIRED");
        }
        BigDecimal payableVnd = decimal(intent.get("payableVnd"), "HDPAY_INTENT_AMOUNT_INVALID");
        if (payableVnd.compareTo(expectedVnd) != 0) {
            return manualReview(fact, claimToken, "HDPAY_INTENT_AMOUNT_MISMATCH");
        }

        long userId = positiveLong(intent.get("userId"), "HDPAY_INTENT_USER_INVALID");
        long intentVersion = nonNegativeLong(intent.get("version"), "HDPAY_INTENT_VERSION_INVALID");
        BigDecimal amountUsdt = decimal(
                intent.get("requestedUsdt"), "HDPAY_INTENT_AMOUNT_INVALID")
                .setScale(6, RoundingMode.UNNECESSARY);
        if (amountUsdt.signum() <= 0) {
            throw new BizException(503, "HDPAY_INTENT_AMOUNT_INVALID");
        }
        String settlementTarget = code(intent.get("settlementTargetType"));
        if (settlementTarget.isEmpty()) settlementTarget = "WALLET_TOPUP";
        if ("COMMERCE_ORDER".equals(settlementTarget)) {
            // Direct HDPay commerce checkout has been retired. Legacy callbacks
            // are durable review facts only; they never credit a wallet, mark an
            // order paid, or activate a device automatically.
            return manualReview(fact, claimToken, "HDPAY_COMMERCE_DIRECT_PAYMENT_RETIRED");
        }
        if (!"WALLET_TOPUP".equals(settlementTarget)) {
            return manualReview(fact, claimToken, "HDPAY_SETTLEMENT_TARGET_INVALID");
        }
        Map<String, Object> wallet = paymentMapper.findUsdtWalletForUpdate(userId);
        if (wallet == null || wallet.isEmpty()) {
            throw new BizException(503, "HDPAY_TARGET_WALLET_NOT_FOUND");
        }
        long walletVersion = nonNegativeLong(wallet.get("version"), "HDPAY_WALLET_VERSION_INVALID");
        BigDecimal balanceAfter = decimal(
                wallet.get("usdtAvailable"), "HDPAY_WALLET_BALANCE_INVALID")
                .add(amountUsdt)
                .setScale(6, RoundingMode.UNNECESSARY);
        requireOne(paymentMapper.creditUsdtWallet(userId, amountUsdt, walletVersion),
                "HDPAY_WALLET_VERSION_CONFLICT");
        String ledgerBizNo = fact.merchantOrderId();
        requireOne(paymentMapper.insertVietQrWalletLedger(
                ledgerBizNo,
                userId,
                amountUsdt,
                balanceAfter,
                "HDPay BANKQR deposit " + fact.merchantOrderId()),
                "HDPAY_LEDGER_WRITE_FAILED");
        requireOne(intentMapper.transitionIntent(
                fact.merchantOrderId(),
                intentVersion,
                "AWAITING_PAYMENT",
                "CREDITED",
                expectedVnd,
                amountUsdt,
                now),
                "HDPAY_INTENT_VERSION_CONFLICT");
        requireOne(intentMapper.closeInFlightReconciliation(fact.merchantOrderId(), "CREDITED"),
                "HDPAY_RECONCILIATION_CLOSE_FAILED");
        requireOne(hdPayMapper.insertDepositNotification(
                "HDPAY:" + fact.merchantOrderId(), userId, amountUsdt),
                "HDPAY_NOTIFICATION_WRITE_FAILED");
        outbox.publish("WALLET", fact.merchantOrderId(), "wallet.topup_confirmed", Map.of(
                "transaction_id", fact.merchantOrderId(),
                "user_id", userId,
                "amount", amountUsdt,
                "currency", "USDT",
                "channel", "BANKQR",
                "topup_id", fact.merchantOrderId(),
                "psp", "HDPAY"));
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("D1_HDPAY_TOPUP_SETTLED")
                .resourceType("HDPAY_PAYIN_ORDER")
                .resourceId(fact.merchantOrderId())
                .bizNo(fact.merchantOrderId())
                .userId(userId)
                .actorType("PAYMENT_GATEWAY")
                .actorUsername("payment-gateway:hdpay")
                .result("SUCCESS")
                .riskLevel("CRITICAL")
                .detail(Map.of(
                        "providerOrderId", fact.providerOrderId(),
                        "amountVnd", expectedVnd.toPlainString(),
                        "creditedUsdt", amountUsdt.toPlainString(),
                        "walletBalanceAfter", balanceAfter.toPlainString(),
                        "confirmation", "SIGNED_CALLBACK_AND_PROVIDER_QUERY"))
                .build());
        requireOne(hdPayMapper.markSettlementCredited(
                fact.merchantOrderId(),
                fact.providerOrderId(),
                confirmed.orderStatus(),
                amountUsdt,
                ledgerBizNo),
                "HDPAY_SETTLEMENT_STATE_CONFLICT");
        requireOne(hdPayMapper.markCallbackProcessedOwned(
                payloadHash, claimToken, "CREDITED", confirmed.orderStatus(), "CREDITED"),
                "HDPAY_CALLBACK_INBOX_UPDATE_FAILED");
        return SUCCESS;
    }

    private String manualReview(PaidCallbackFact fact, String claimToken, String reason) {
        requireOne(hdPayMapper.markSettlementReview(
                fact.merchantOrderId(), fact.providerOrderId(), fact.orderStatus(), reason),
                "HDPAY_SETTLEMENT_REVIEW_WRITE_FAILED");
        createReviewFact(fact, reason);
        requireOne(hdPayMapper.markCallbackProcessedOwned(
                fact.payloadHash(), claimToken, "MANUAL_REVIEW", fact.orderStatus(), reason),
                "HDPAY_CALLBACK_INBOX_UPDATE_FAILED");
        return SUCCESS;
    }

    private void postCreditReview(PaidCallbackFact fact, String claimToken, String reason) {
        requireOne(hdPayMapper.markPostCreditReview(
                fact.merchantOrderId(), fact.providerOrderId(), reason),
                "HDPAY_POST_CREDIT_REVIEW_WRITE_FAILED");
        createReviewFact(fact, reason);
        requireOne(hdPayMapper.markCallbackProcessedOwned(
                fact.payloadHash(), claimToken, "MANUAL_REVIEW", fact.orderStatus(), reason),
                "HDPAY_CALLBACK_INBOX_UPDATE_FAILED");
    }

    private void createReviewFact(PaidCallbackFact fact, String reason) {
        requireOne(hdPayMapper.insertSettlementReview(
                fact.payloadHash(), fact.merchantOrderId(), fact.providerOrderId(), reason),
                "HDPAY_SETTLEMENT_REVIEW_FACT_WRITE_FAILED");
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action("D1_HDPAY_SETTLEMENT_REVIEW_CREATED")
                .resourceType("HDPAY_SETTLEMENT_REVIEW")
                .resourceId(fact.payloadHash())
                .bizNo(fact.merchantOrderId())
                .actorType("PAYMENT_GATEWAY")
                .actorUsername("payment-gateway:hdpay")
                .result("REVIEW_REQUIRED")
                .riskLevel("CRITICAL")
                .detail(Map.of(
                        "merchantOrderId", fact.merchantOrderId(),
                        "providerOrderId", fact.providerOrderId(),
                        "amountVnd", fact.transAmt().toPlainString(),
                        "reason", reason))
                .build());
    }

    private Map<String, Object> lockOrder(String merchantOrderId) {
        Map<String, Object> order = hdPayMapper.findByMerchantOrderIdForUpdate(merchantOrderId);
        if (order == null || order.isEmpty()) {
            throw new BizException(404, "HDPAY_CALLBACK_ORDER_NOT_FOUND");
        }
        return order;
    }

    private void requireConfirmedIdentity(PaidCallbackFact fact, HdPayGateway.PayOrder confirmed) {
        if (fact.orderStatus() != HdPayCallbackService.PAID_STATUS
                || confirmed.orderStatus() != HdPayCallbackService.PAID_STATUS
                || !fact.merchantOrderId().equals(confirmed.merchantOrderId())
                || !fact.providerOrderId().equals(confirmed.providerOrderId())
                || fact.transAmt().compareTo(confirmed.transAmt()) != 0
                || !"BANKQR".equalsIgnoreCase(confirmed.payType())) {
            throw new BizException(409, "HDPAY_CALLBACK_QUERY_MISMATCH");
        }
    }

    private void requireProviderOrderCompatible(Map<String, Object> order, String providerOrderId) {
        String existing = text(order.get("providerOrderId"));
        if (!existing.isEmpty() && !existing.equals(providerOrderId)) {
            throw new BizException(409, "HDPAY_CALLBACK_PROVIDER_ORDER_CONFLICT");
        }
    }

    private void requireOwnedProcessing(String payloadHash, String claimToken) {
        Map<String, Object> inbox = hdPayMapper.findCallbackInboxForUpdate(payloadHash);
        if (inbox == null || inbox.isEmpty()
                || !"PROCESSING".equals(code(inbox.get("processingStatus")))
                || claimToken == null
                || !claimToken.equals(text(inbox.get("claimToken")))) {
            throw new BizException(503, "HDPAY_CALLBACK_CLAIM_LOST");
        }
    }

    private String newClaimToken() {
        return UUID.randomUUID().toString();
    }

    private void requireOne(int rows, String error) {
        if (rows != 1) throw new BizException(503, error);
    }

    private void requireZero(int rows, String error) {
        if (rows != 0) throw new BizException(503, error);
    }

    private PaidCallbackFact fact(HdPayCallbackVerifier.VerifiedCallback callback) {
        return new PaidCallbackFact(
                payloadHash(callback), callback.merchantOrderId(), callback.orderId(),
                callback.orderStatus(), callback.transAmt());
    }

    private PaidCallbackFact storedFact(Map<String, Object> row) {
        return new PaidCallbackFact(
                text(row.get("payloadHash")),
                text(row.get("merchantOrderId")),
                text(row.get("providerOrderId")),
                (int) nonNegativeLong(row.get("providerStatus"), "HDPAY_CALLBACK_STATUS_INVALID"),
                decimal(row.get("amountVnd"), "HDPAY_CALLBACK_AMOUNT_INVALID"));
    }

    private String payloadHash(HdPayCallbackVerifier.VerifiedCallback callback) {
        return sha256(String.join("|",
                callback.merchantOrderId(),
                callback.orderId(),
                String.valueOf(callback.orderStatus()),
                callback.transAmt().toPlainString(),
                callback.createTime() == null ? "" : callback.createTime(),
                callback.payTime() == null ? "" : callback.payTime(),
                callback.sign().toLowerCase(Locale.ROOT)));
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private BigDecimal decimal(Object value, String error) {
        try {
            BigDecimal result = value instanceof BigDecimal decimal
                    ? decimal : new BigDecimal(String.valueOf(value));
            if (result.scale() < -20 || result.scale() > 20 || result.precision() > 32) {
                throw new ArithmeticException("unsafe decimal");
            }
            return result;
        } catch (RuntimeException ex) {
            throw new BizException(503, error);
        }
    }

    private long positiveLong(Object value, String error) {
        long result = nonNegativeLong(value, error);
        if (result == 0) throw new BizException(503, error);
        return result;
    }

    private long nonNegativeLong(Object value, String error) {
        try {
            long result = value instanceof Number number
                    ? number.longValue() : Long.parseLong(String.valueOf(value));
            if (result < 0) throw new NumberFormatException("negative");
            return result;
        } catch (RuntimeException ex) {
            throw new BizException(503, error);
        }
    }

    private LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime dateTime) return dateTime;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        return null;
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String code(Object value) {
        return text(value).toUpperCase(Locale.ROOT);
    }
}

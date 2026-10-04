package ffdd.opsconsole.finance.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.dto.HdPayManualCreditRequest;
import ffdd.opsconsole.finance.hdpay.HdPayOrderMapper;
import ffdd.opsconsole.finance.hdpay.HdPayProperties;
import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.D1BankOrderMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class D1BankOrderService {
    private static final Set<String> STATUSES = Set.of("CREATING", "AWAITING_PAYMENT", "UNKNOWN", "FAILED",
            "PROCESSING", "EXPIRED", "CREDITED", "CANCELLED", "RETURNED", "RECEIPT_REVIEW",
            "MISMATCH_REVIEW", "LATE_REVIEW", "RETURN_PENDING");
    private static final Set<String> MANUAL_STATES = Set.of("AWAITING_PAYMENT", "EXPIRED", "RECEIPT_REVIEW",
            "MISMATCH_REVIEW", "LATE_REVIEW");
    private static final Set<String> MANUAL_CREDIT_STATES = Set.of("AWAITING_PAYMENT", "EXPIRED", "RECEIPT_REVIEW",
            "MISMATCH_REVIEW", "LATE_REVIEW", "CANCELLED");
    private final D1BankOrderMapper orders;
    private final HdPayOrderMapper hdPay;
    private final AppVietQrIntentMapper intents;
    private final VietnamPaymentMapper payments;
    private final VietQrReceiptEvidenceService evidence;
    private final TreasuryLedgerRepository treasury;
    private final AdminIdempotencyService idempotency;
    private final EventOutboxService outbox;
    private final AuditLogService audit;
    private final HdPayProperties properties;
    private final ObjectMapper json;
    private final Clock clock;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public ApiResult<Map<String, Object>> list(String paymentRail, String status, String keyword,
                                              Integer pageNum, Integer pageSize) {
        String rail = filter(paymentRail, Set.of("MANUAL", "HDPAY"), "D1_BANK_ORDER_RAIL_INVALID");
        String state = filter(status, STATUSES, "D1_BANK_ORDER_STATUS_INVALID");
        String search = text(keyword);
        if (search.length() > 128) validation("D1_BANK_ORDER_KEYWORD_INVALID");
        int page = pageNum == null ? 1 : Math.max(1, pageNum);
        int size = pageSize == null ? 20 : Math.max(1, Math.min(100, pageSize));
        long offset = (long) (page - 1) * size;
        if (offset > Integer.MAX_VALUE) validation("D1_BANK_ORDER_PAGE_INVALID");
        LocalDateTime asOf = LocalDateTime.now(clock);
        long total = orders.countOrders(rail, state, search.isEmpty() ? null : search, asOf);
        List<Map<String, Object>> records = orders.listOrders(
                rail, state, search.isEmpty() ? null : search, asOf, size, (int) offset)
                .stream().map(this::project).toList();
        return ApiResult.ok(Map.of("records", records, "pageNum", page, "pageSize", size,
                "total", total, "source", "nx_vietqr_intent", "asOf", asOf));
    }

    public ApiResult<Map<String, Object>> manualCredit(String intentNo, String key, HdPayManualCreditRequest request) {
        if (!text(intentNo).matches("VQR-[A-Za-z0-9-]{1,60}")) validation("D1_BANK_ORDER_NUMBER_INVALID");
        if (text(key).isEmpty()) throw new BizException(400, "IDEMPOTENCY_KEY_REQUIRED");
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0
                || request.providerVersion() == null || request.providerVersion() < 0) {
            validation("HDPAY_MANUAL_VERSION_REQUIRED");
        }
        BigDecimal received = safeDecimal(request.receivedVnd(), "VIETQR_RECEIVED_AMOUNT_INVALID");
        if (received.signum() <= 0 || received.compareTo(new BigDecimal("10000000000")) > 0
                || received.stripTrailingZeros().scale() > 0) validation("VIETQR_RECEIVED_AMOUNT_INVALID");
        String reference = text(request.paymentReference());
        if (reference.length() < 6 || reference.length() > 128
                || !reference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) validation("VIETQR_PAYMENT_REFERENCE_INVALID");
        if (text(request.reason()).isEmpty() || text(request.reason()).length() > 1000) validation("REASON_REQUIRED");
        evidence.validateReferenceSyntax(request.evidenceRef());
        if (request.receivedAt() == null) validation("VIETQR_RECEIVED_AT_REQUIRED");
        LocalDateTime receivedAt = request.receivedAt().atZoneSameInstant(clock.getZone()).toLocalDateTime()
                .truncatedTo(ChronoUnit.SECONDS);
        if (receivedAt.isAfter(LocalDateTime.now(clock).plusMinutes(5))) validation("VIETQR_RECEIVED_AT_INVALID");
        String actor = AdminActorResolver.resolve("authenticated-finance-admin");
        String hash = hash(List.of(intentNo, request.expectedVersion(), request.providerVersion(),
                received.stripTrailingZeros().toPlainString(), reference, request.receivedAt().toInstant().toString(),
                text(request.evidenceRef()), text(request.reason()), actor));
        Map<String, Object> result = idempotency.executeRetainedRepeatableRead(
                "D1_HDPAY_MANUAL_CREDIT", key, hash, Map.class,
                () -> credit(intentNo, key, request, receivedAt, actor));
        return ApiResult.ok(result);
    }

    // AdminIdempotencyTransactionExecutor.runClaimedRepeatableRead owns this entire transaction.
    private Map<String, Object> credit(String intentNo, String key, HdPayManualCreditRequest request,
                                       LocalDateTime receivedAt, String actor) {
        Map<String, Object> provider = required(hdPay.findByMerchantOrderIdForUpdate(intentNo), "HDPAY_ORDER_NOT_FOUND");
        List<Long> referenceReceipts = orders.lockBankReference(text(request.paymentReference()));
        List<Map<String,Object>> receipts = orders.lockBankReceipts(intentNo);
        Map<String, Object> intent = required(intents.findIntentForUpdate(intentNo), "HDPAY_INTENT_NOT_FOUND");
        String block = blockReason(intent, provider);
        if (block != null) throw new BizException(409, block);
        long intentVersion = number(intent.get("version"));
        long providerVersion = number(provider.get("version"));
        if (intentVersion != request.expectedVersion() || providerVersion != request.providerVersion()) {
            throw new BizException(409, "HDPAY_MANUAL_VERSION_CONFLICT");
        }
        if (orders.countSettlementFacts(intentNo) != 0) throw new BizException(409, "HDPAY_LOCAL_SETTLEMENT_CONFLICT");
        BigDecimal payable = safeDecimal(intent.get("payableVnd"), "HDPAY_INTENT_AMOUNT_INVALID");
        if (payable.compareTo(request.receivedVnd()) != 0) throw new BizException(409, "HDPAY_MANUAL_AMOUNT_MISMATCH");
        LocalDateTime createdAt = time(intent.get("createdAt"));
        if (createdAt == null || receivedAt.isBefore(createdAt)) validation("VIETQR_RECEIPT_PREDATES_INTENT");
        BigDecimal amount = safeDecimal(intent.get("requestedUsdt"), "HDPAY_INTENT_AMOUNT_INVALID")
                .setScale(6, RoundingMode.UNNECESSARY);
        long userId = number(intent.get("userId"));
        if (amount.signum() <= 0 || userId <= 0) validation("HDPAY_INTENT_AMOUNT_INVALID");
        Map<String,Object> receipt = reusableReceipt(receipts, intent, amount, request, receivedAt);
        Long receiptId = receipt == null ? null : number(receipt.get("id"));
        Long receiptVersion = receipt == null ? null : number(receipt.get("version"));
        String reconciliationNo = receipt == null ? null : text(receipt.get("reconciliationNo"));
        String reserveSource = receipt == null ? "RESERVE_LEDGER" : "EXISTING_BANK_RECEIPT";
        if (referenceReceipts.stream().anyMatch(id -> !id.equals(receiptId))) {
            throw new BizException(409, "VIETQR_PAYMENT_REFERENCE_CONFLICT");
        }
        String confirmationNo = "HPM-" + UUID.randomUUID().toString().replace("-", "");
        evidence.claimForHdPayManualCredit(text(request.evidenceRef()), intentNo, actor);
        try {
            one(orders.insertManualConfirmation(confirmationNo, intentNo, userId, payable, amount,
                    text(request.paymentReference()), receivedAt, text(request.evidenceRef()), text(request.reason()),
                    actor, key, reserveSource, receiptId, receiptVersion, reconciliationNo,
                    text(intent.get("status"))), "HDPAY_MANUAL_CONFIRMATION_WRITE_FAILED");
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BizException(409, "HDPAY_MANUAL_CONFIRMATION_CONFLICT");
        }
        Map<String, Object> wallet = required(payments.findUsdtWalletForUpdate(userId), "HDPAY_TARGET_WALLET_NOT_FOUND");
        BigDecimal balanceAfter = safeDecimal(wallet.get("usdtAvailable"), "HDPAY_WALLET_BALANCE_INVALID").add(amount);
        one(payments.creditUsdtWallet(userId, amount, number(wallet.get("version"))), "HDPAY_WALLET_VERSION_CONFLICT");
        one(payments.insertVietQrWalletLedger(intentNo, userId, amount, balanceAfter,
                "ADMIN manual HDPay BANKQR deposit " + intentNo), "HDPAY_LEDGER_WRITE_FAILED");
        if (receipt == null) treasury.recordManualTopupReserve(intentNo, amount, "HDPAY:" + intentNo, actor);
        else one(payments.completeVietQrReconciliation(receiptId, receiptVersion, "CREDITED",
                text(receipt.get("viewType")), userId, intentNo, amount,
                receipt.get("note") == null ? null : String.valueOf(receipt.get("note"))), "VIETQR_VERSION_CONFLICT");
        LocalDateTime now = LocalDateTime.now(clock);
        one(intents.transitionIntent(intentNo, intentVersion, text(intent.get("status")), "CREDITED",
                payable, amount, now), "HDPAY_INTENT_VERSION_CONFLICT");
        // An expired/review intent's APP projection may already be closed.
        intents.closeInFlightReconciliation(intentNo, "ADMIN_MANUAL_CREDITED");
        one(hdPay.insertDepositNotification("HDPAY:" + intentNo, userId, amount), "HDPAY_NOTIFICATION_WRITE_FAILED");
        outbox.publish("WALLET", intentNo, "wallet.topup_confirmed", Map.of(
                "transaction_id", intentNo, "user_id", userId, "amount", amount, "currency", "USDT",
                "channel", "BANKQR", "topup_id", intentNo, "psp", "HDPAY"));
        audit.recordRequired(AuditLogWriteRequest.builder().action("D1_HDPAY_MANUAL_CREDITED")
                .resourceType("HDPAY_PAYIN_ORDER").resourceId(intentNo).bizNo(intentNo).userId(userId)
                .actorType("ADMIN").actorUsername(actor).result("SUCCESS").riskLevel("CRITICAL")
                .detail(Map.ofEntries(Map.entry("confirmationSource", "ADMIN_MANUAL"),
                        Map.entry("manualConfirmationNo", confirmationNo),
                        Map.entry("reserveSource", reserveSource), Map.entry("bankReceiptId", receiptId == null ? "" : receiptId),
                        Map.entry("bankReceiptVersion", receiptVersion == null ? "" : receiptVersion),
                        Map.entry("previousIntentStatus", text(intent.get("status"))),
                        Map.entry("previousReceiptStatus", receipt == null ? "" : text(receipt.get("status"))),
                        Map.entry("receivedVnd", payable), Map.entry("creditedUsdt", amount),
                        Map.entry("paymentReference", text(request.paymentReference())),
                        Map.entry("receivedAt", receivedAt.toString()), Map.entry("evidenceRef", text(request.evidenceRef())),
                        Map.entry("reason", text(request.reason())), Map.entry("idempotencyKey", key))).build());
        one(orders.markManualCredited(intentNo, providerVersion, amount, now), "HDPAY_SETTLEMENT_STATE_CONFLICT");
        return Map.of("intentNo", intentNo, "status", "CREDITED", "creditedUsdt", amount,
                "version", intentVersion + 1, "providerVersion", providerVersion + 1,
                "confirmationSource", "ADMIN_MANUAL", "manualConfirmationNo", confirmationNo);
    }

    private Map<String, Object> project(Map<String, Object> source) {
        Map<String, Object> row = new LinkedHashMap<>(source);
        Map<String, Object> intent = new LinkedHashMap<>(row);
        intent.put("status", row.get("intentStatus"));
        Map<String, Object> provider = new LinkedHashMap<>(row);
        provider.put("amountVnd", row.get("providerAmountVnd"));
        provider.put("version", row.get("providerVersion"));
        String block = blockReason(intent, row.get("providerVersion") == null ? Map.of() : provider);
        if (block == null && number(row.get("bankReceiptCount")) > 0
                && (number(row.get("bankReceiptCount")) != 1 || number(row.get("reusableReceiptCount")) != 1)) {
            block = "HDPAY_BANK_RECEIPT_REQUIRES_REVIEW";
        }
        if (block == null && number(row.get("settlementFactCount")) != 0) block = "HDPAY_LOCAL_SETTLEMENT_CONFLICT";
        row.put("manualCreditAllowed", block == null);
        row.put("manualCreditBlockReason", block);
        row.put("manualRegistrationAllowed", "MANUAL".equals(row.get("paymentRail"))
                && MANUAL_STATES.contains(text(row.get("intentStatus")))
                && row.get("bankAccountId") != null && flag(row.get("bankAccountActive"))
                && number(row.get("openReceiptCount")) == 0);
        String url = text(row.get("paymentUrl"));
        boolean usable = "HDPAY".equals(row.get("paymentRail")) && "AWAITING_PAYMENT".equals(row.get("status"))
                && "CREATED".equals(row.get("submissionStatus")) && "UNSETTLED".equals(row.get("settlementStatus"))
                && (row.get("providerStatus") == null || number(row.get("providerStatus")) == 1)
                && properties.isTrustedPaymentPage(url);
        row.put("paymentUrl", usable ? url : null);
        for (String field : List.of("receivedVnd", "receivedAt", "providerVersion", "submissionStatus",
                "providerStatus", "settlementStatus", "bankAccountId", "memoCode", "targetOrderNo", "manualConfirmationNo",
                "reserveSource", "bankReceiptId")) {
            row.putIfAbsent(field, null);
        }
        row.remove("providerAmountVnd");
        row.remove("openReceiptCount");
        row.remove("bankReceiptCount");
        row.remove("reusableReceiptCount");
        row.remove("settlementFactCount");
        row.remove("bankAccountActive");
        return row;
    }

    private String blockReason(Map<String, Object> intent, Map<String, Object> provider) {
        if (!"HDPAY".equals(intent.get("paymentRail"))) return "MANUAL_BANK_RECEIPT_FLOW";
        if (!"WALLET_TOPUP".equals(intent.get("settlementTargetType"))) return "HDPAY_SETTLEMENT_TARGET_INVALID";
        if ("CREDITED".equals(intent.get("status")) || "CREDITED".equals(provider.get("settlementStatus"))) {
            return "HDPAY_ORDER_ALREADY_CREDITED";
        }
        if (!MANUAL_CREDIT_STATES.contains(text(intent.get("status")))) return "HDPAY_MANUAL_INTENT_STATE_CONFLICT";
        if (provider.isEmpty()) return "HDPAY_ORDER_NOT_FOUND";
        if (!Set.of("UNSETTLED", "MANUAL_REVIEW").contains(text(provider.get("settlementStatus")))
                || !Set.of("PENDING", "CREATED", "SUBMIT_UNKNOWN", "REJECTED").contains(text(provider.get("submissionStatus")))) {
            return "HDPAY_MANUAL_PROVIDER_STATE_CONFLICT";
        }
        if (safeDecimal(intent.get("payableVnd"), "HDPAY_INTENT_AMOUNT_INVALID")
                .compareTo(safeDecimal(provider.get("amountVnd"), "HDPAY_ORDER_AMOUNT_INVALID")) != 0) {
            return "HDPAY_MANUAL_AMOUNT_MISMATCH";
        }
        return null;
    }

    private Map<String,Object> reusableReceipt(List<Map<String,Object>> receipts, Map<String,Object> intent,
                                              BigDecimal amount, HdPayManualCreditRequest request,
                                              LocalDateTime receivedAt) {
        if (receipts.isEmpty()) return null;
        if (receipts.size() != 1) throw new BizException(409, "HDPAY_BANK_RECEIPT_REQUIRES_REVIEW");
        Map<String,Object> receipt = receipts.get(0);
        BigDecimal fx = safeDecimal(receipt.get("lockedFxRateVndPerUsdt"), "HDPAY_BANK_RECEIPT_REQUIRES_REVIEW");
        LocalDateTime actualReceivedAt = time(receipt.get("receivedAt"));
        if (!"OPEN".equals(receipt.get("status"))
                || !Set.of("MATCHED", "MISMATCH", "LATE", "ORPHAN").contains(text(receipt.get("viewType")))
                || receipt.get("bankAccountId") == null
                || number(receipt.get("userId")) != number(intent.get("userId"))
                || !text(receipt.get("intentNo")).equals(text(intent.get("intentNo")))
                || safeDecimal(receipt.get("payableVnd"), "HDPAY_BANK_RECEIPT_REQUIRES_REVIEW").compareTo(request.receivedVnd()) != 0
                || safeDecimal(receipt.get("receivedVnd"), "HDPAY_BANK_RECEIPT_REQUIRES_REVIEW").compareTo(request.receivedVnd()) != 0
                || fx.signum() <= 0 || amount.multiply(fx).compareTo(request.receivedVnd()) != 0
                || safeDecimal(receipt.get("creditedUsdt"), "HDPAY_BANK_RECEIPT_REQUIRES_REVIEW").signum() != 0
                || !text(receipt.get("paymentReference")).equals(text(request.paymentReference()))
                || actualReceivedAt == null || !actualReceivedAt.equals(receivedAt)
                || actualReceivedAt.isBefore(time(intent.get("createdAt")))) {
            throw new BizException(409, "HDPAY_BANK_RECEIPT_REQUIRES_REVIEW");
        }
        return receipt;
    }

    private String filter(String value, Set<String> values, String error) {
        String normalized = text(value).toUpperCase(Locale.ROOT);
        if (normalized.isEmpty() || "ALL".equals(normalized)) return null;
        if (!values.contains(normalized)) validation(error);
        return normalized;
    }

    private String hash(Object value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(value)));
        } catch (JsonProcessingException | NoSuchAlgorithmException ex) {
            throw new IllegalStateException("HDPAY_MANUAL_REQUEST_HASH_FAILED", ex);
        }
    }

    private static BigDecimal safeDecimal(Object value, String error) {
        try {
            BigDecimal number = value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
            if (number.precision() > 32 || number.scale() < -20 || number.scale() > 20) throw new ArithmeticException();
            return number;
        } catch (RuntimeException ex) {
            throw new BizException(400, error);
        }
    }

    private static long number(Object value) {
        if (value == null) return 0;
        try { return new BigDecimal(String.valueOf(value)).longValueExact(); }
        catch (RuntimeException ex) { throw new BizException(409, "HDPAY_LOCAL_VERSION_INVALID"); }
    }

    private static LocalDateTime time(Object value) {
        if (value instanceof LocalDateTime local) return local;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        return null;
    }

    private static Map<String, Object> required(Map<String, Object> value, String error) {
        if (value == null || value.isEmpty()) throw new BizException(404, error);
        return value;
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value).trim(); }
    private static boolean flag(Object value) { return Boolean.TRUE.equals(value) || "1".equals(text(value)); }
    private static void one(int result, String error) { if (result != 1) throw new BizException(409, error); }
    private static void validation(String error) { throw new BizException(400, error); }
}

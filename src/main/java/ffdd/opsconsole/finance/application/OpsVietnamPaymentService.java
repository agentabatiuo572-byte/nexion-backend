package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.finance.dto.FxQuoteUpdateRequest;
import ffdd.opsconsole.finance.dto.VietQrBankAccountCommandRequest;
import ffdd.opsconsole.finance.dto.VietQrBankAccountCreateRequest;
import ffdd.opsconsole.finance.dto.VietQrConfigUpdateRequest;
import ffdd.opsconsole.finance.dto.VietQrReconciliationCommandRequest;
import ffdd.opsconsole.finance.dto.VietQrReceiptRegistrationRequest;
import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsVietnamPaymentService {
    private static final Set<String> VIEW_TYPES = Set.of("INFLIGHT", "MATCHED", "ORPHAN", "MISMATCH", "LATE");
    private static final Set<String> ACCOUNT_ACTIONS = Set.of("ENABLE", "DISABLE", "RECOVER", "UPDATE_CAP");
    private static final Set<String> ROTATION_STRATEGIES = Set.of("ROUND_ROBIN", "REMAINING_CAPACITY");
    private static final ZoneId VIETNAM_BANK_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private final VietnamPaymentMapper mapper;
    private final AuditLogService audit;
    private final AdminIdempotencyService idempotency;
    private final FinanceSensitiveDataCipher sensitiveDataCipher;
    private final AppVietQrIntentMapper appIntentMapper;
    private final EventOutboxService outboxService;
    private final VietQrReceiptEvidenceService receiptEvidenceService;
    private final Clock clock;

    @Transactional
    public ApiResult<Map<String, Object>> vietQrOverview(String view, Integer pageNum, Integer pageSize) {
        appIntentMapper.expireAllIntents();
        appIntentMapper.closeAllInactiveInFlightReconciliations();
        String normalizedView = normalizeView(view);
        int safePage = pageNum == null ? 1 : Math.max(1, pageNum);
        int safeSize = pageSize == null ? 20 : Math.min(100, Math.max(1, pageSize));
        Map<String, Object> config = requiredMap(mapper.findVietQrConfig(), "VIETQR_CONFIG_UNAVAILABLE");
        List<Map<String, Object>> accounts = safeList(mapper.listVietQrBankAccounts());
        long total = mapper.countVietQrReconciliations(normalizedView);
        List<Map<String, Object>> items = safeList(mapper.listVietQrReconciliations(
                normalizedView, safeSize, (safePage - 1) * safeSize));
        BigDecimal pending = money(mapper.sumPendingUnverifiedDepositUsdt());

        Map<String, Object> page = new LinkedHashMap<>();
        page.put("items", items);
        page.put("pageNum", safePage);
        page.put("pageSize", safeSize);
        page.put("total", total);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("view", normalizedView == null ? "all" : normalizedView.toLowerCase(Locale.ROOT));
        response.put("config", config);
        response.put("accounts", accounts);
        response.put("page", page);
        response.put("pendingUnverifiedDepositUsdt", pending);
        response.put("source", "nx_vietqr_reconciliation");
        response.put("asOf", LocalDateTime.now(clock));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> reconcile(
            Long id, String action, String idempotencyKey, VietQrReconciliationCommandRequest request) {
        requireId(id, "VIETQR_RECONCILIATION_ID_REQUIRED");
        validateMutation(idempotencyKey, request == null ? null : request.expectedVersion(),
                request == null ? null : request.reason());
        validateReceiptUploadEvidence(request == null ? null : request.evidenceRef());
        String normalizedAction = normalizeAction(action);
        String requestHash = hash(id + ":" + normalizedAction + ":" + request.expectedVersion() + ":"
                + request.userId() + ":" + clean(request.intentNo()) + ":"
                + request.evidenceRef().trim() + ":" + request.reason().trim());
        @SuppressWarnings({"rawtypes", "unchecked"})
        ApiResult<Map<String, Object>> result = (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "D1_VIETQR_RECONCILIATION_" + normalizedAction,
                idempotencyKey, requestHash, ApiResult.class,
                () -> doReconcile(id, normalizedAction, idempotencyKey, request));
        return result;
    }

    @Transactional
    public ApiResult<Map<String, Object>> registerVietQrReceipt(
            String idempotencyKey, VietQrReceiptRegistrationRequest request) {
        validateReceiptRegistration(idempotencyKey, request);
        String paymentReference = request.paymentReference().trim();
        String memoCode = clean(request.memoCode()).toUpperCase(Locale.ROOT);
        String requestHash = hash(request.bankAccountId() + ":" + paymentReference + ":"
                + memoCode + ":" + request.receivedVnd().toPlainString() + ":"
                + request.receivedAt().toInstant() + ":" + request.evidenceRef().trim() + ":"
                + request.reason().trim());
        @SuppressWarnings({"rawtypes", "unchecked"})
        ApiResult<Map<String, Object>> result = (ApiResult<Map<String, Object>>) (ApiResult)
                idempotency.execute(
                        "D1_VIETQR_RECEIPT_REGISTER",
                        idempotencyKey, requestHash, ApiResult.class,
                        () -> doRegisterVietQrReceipt(
                                idempotencyKey, request, paymentReference, memoCode));
        return result;
    }

    @Transactional
    public ApiResult<Map<String, Object>> createBankAccount(
            String idempotencyKey, VietQrBankAccountCreateRequest request) {
        requireKeyAndReason(idempotencyKey, request == null ? null : request.reason());
        validateBankAccount(request);
        String accountNumber = request.accountNumber().trim();
        String requestHash = hash(request.bankCode().trim().toUpperCase(Locale.ROOT) + ":"
                + accountNumber + ":" + request.dailyCapVnd() + ":" + request.reason().trim());
        @SuppressWarnings({"rawtypes", "unchecked"})
        ApiResult<Map<String, Object>> result = (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "D1_VIETQR_BANK_ACCOUNT_CREATE", idempotencyKey, requestHash, ApiResult.class, () -> {
                    try {
                        String accountHash = hash(accountNumber);
                        if (mapper.insertVietQrBankAccount(
                                request.bankCode().trim().toUpperCase(Locale.ROOT),
                                request.bankName().trim(), request.accountHolder().trim(),
                                sensitiveDataCipher.encrypt(accountNumber, accountHash),
                                accountHash, last4(accountNumber),
                                request.dailyCapVnd().setScale(0, RoundingMode.UNNECESSARY)) != 1) {
                            conflict("VIETQR_BANK_ACCOUNT_CREATE_FAILED");
                        }
                    } catch (DuplicateKeyException ex) {
                        throw new BizException(409, "VIETQR_BANK_ACCOUNT_ALREADY_EXISTS");
                    }
                    String actor = operator(request.operator());
                    requiredAudit("VIETQR_BANK_ACCOUNT_CREATED", "VIETQR_BANK_ACCOUNT",
                            request.bankCode().trim().toUpperCase(Locale.ROOT) + ":" + last4(accountNumber),
                            actor, request.reason(), idempotencyKey,
                            Map.of("bankCode", request.bankCode().trim().toUpperCase(Locale.ROOT),
                                    "accountLast4", last4(accountNumber),
                                    "dailyCapVnd", request.dailyCapVnd()));
                    return ApiResult.ok(Map.of(
                            "status", "CREATED",
                            "bankCode", request.bankCode().trim().toUpperCase(Locale.ROOT),
                            "accountLast4", last4(accountNumber)));
                });
        return result;
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateBankAccount(
            Long id, String idempotencyKey, VietQrBankAccountCommandRequest request) {
        requireId(id, "VIETQR_BANK_ACCOUNT_ID_REQUIRED");
        validateMutation(idempotencyKey, request == null ? null : request.expectedVersion(),
                request == null ? null : request.reason());
        String action = request.action() == null ? "" : request.action().trim().toUpperCase(Locale.ROOT);
        if (!ACCOUNT_ACTIONS.contains(action)) {
            validation("VIETQR_BANK_ACCOUNT_ACTION_INVALID");
        }
        if ("UPDATE_CAP".equals(action)) {
            requireIntegerRange(request.dailyCapVnd(), BigDecimal.valueOf(1_000_000),
                    BigDecimal.valueOf(10_000_000_000L), "VIETQR_DAILY_CAP_OUT_OF_RANGE");
        }
        String requestHash = hash(id + ":" + action + ":" + request.dailyCapVnd() + ":"
                + request.expectedVersion() + ":" + request.reason().trim());
        @SuppressWarnings({"rawtypes", "unchecked"})
        ApiResult<Map<String, Object>> result = (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "D1_VIETQR_BANK_ACCOUNT_UPDATE", idempotencyKey, requestHash, ApiResult.class, () -> {
                    Map<String, Object> before = requiredMap(mapper.findVietQrBankAccount(id),
                            "VIETQR_BANK_ACCOUNT_NOT_FOUND", 404);
                    BigDecimal cap = "UPDATE_CAP".equals(action)
                            ? request.dailyCapVnd().setScale(0, RoundingMode.UNNECESSARY)
                            : decimal(before.get("dailyCapVnd"));
                    if (mapper.updateVietQrBankAccount(id, action, cap, request.expectedVersion()) != 1) {
                        conflict("VIETQR_BANK_ACCOUNT_VERSION_OR_STATE_CONFLICT");
                    }
                    if ("DISABLE".equals(action)) {
                        appIntentMapper.cancelActiveIntentsForBankAccount(id);
                        appIntentMapper.closeInFlightReconciliationsForBankAccount(id);
                    }
                    Map<String, Object> updated = requiredMap(mapper.findVietQrBankAccount(id),
                            "VIETQR_BANK_ACCOUNT_NOT_FOUND", 404);
                    requiredAudit("VIETQR_BANK_ACCOUNT_" + action, "VIETQR_BANK_ACCOUNT",
                            String.valueOf(id), operator(request.operator()), request.reason(), idempotencyKey,
                            Map.of("beforeStatus", text(before.get("status")),
                                    "afterStatus", text(updated.get("status")),
                                    "beforeDailyCapVnd", decimal(before.get("dailyCapVnd")),
                                    "afterDailyCapVnd", decimal(updated.get("dailyCapVnd"))));
                    return ApiResult.ok(updated);
                });
        return result;
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateVietQrConfig(
            String idempotencyKey, VietQrConfigUpdateRequest request) {
        validateMutation(idempotencyKey, request == null ? null : request.expectedVersion(),
                request == null ? null : request.reason());
        validateVietQrConfig(request);
        String rotation = request.rotationStrategy().trim().toUpperCase(Locale.ROOT);
        String requestHash = hash(request.toString());
        @SuppressWarnings({"rawtypes", "unchecked"})
        ApiResult<Map<String, Object>> result = (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "D1_VIETQR_CONFIG_UPDATE", idempotencyKey, requestHash, ApiResult.class, () -> {
                    Map<String, Object> before = requiredMap(mapper.findVietQrConfig(), "VIETQR_CONFIG_UNAVAILABLE");
                    if (mapper.updateVietQrConfig(
                            request.toleranceVnd().setScale(0, RoundingMode.UNNECESSARY),
                            request.graceMinutes(),
                            request.perTxLimitUsd().setScale(2, RoundingMode.UNNECESSARY),
                            request.trc20Confirmations(), request.erc20Confirmations(), request.bep20Confirmations(),
                            rotation, request.expectedVersion(), operator(request.operator()),
                            request.reason().trim()) != 1) {
                        conflict("VIETQR_CONFIG_VERSION_CONFLICT");
                    }
                    Map<String, Object> updated = requiredMap(mapper.findVietQrConfig(), "VIETQR_CONFIG_UNAVAILABLE");
                    requiredAudit("VIETQR_CONFIG_UPDATED", "VIETQR_CONFIG", "1",
                            operator(request.operator()), request.reason(), idempotencyKey,
                            Map.of("beforeVersion", longValue(before.get("version")),
                                    "afterVersion", longValue(updated.get("version"))));
                    return ApiResult.ok(updated);
                });
        return result;
    }

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> fxQuote() {
        Map<String, Object> config = requiredMap(mapper.findFxQuoteConfig(), "FX_QUOTE_CONFIG_UNAVAILABLE");
        Map<String, Object> response = fxSnapshot(config);
        response.put("history", safeList(mapper.listFxQuoteHistory()));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateFxQuote(String idempotencyKey, FxQuoteUpdateRequest request) {
        validateMutation(idempotencyKey, request == null ? null : request.expectedVersion(),
                request == null ? null : request.reason());
        validateFx(request);
        String requestHash = hash(request.toString());
        @SuppressWarnings({"rawtypes", "unchecked"})
        ApiResult<Map<String, Object>> result = (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "D6_FX_QUOTE_UPDATE", idempotencyKey, requestHash, ApiResult.class, () -> {
                    Map<String, Object> before = requiredMap(mapper.findFxQuoteConfig(), "FX_QUOTE_CONFIG_UNAVAILABLE");
                    String actor = operator(request.operator());
                    if (longValue(before.get("version")) != request.expectedVersion()) {
                        conflict("FX_QUOTE_VERSION_CONFLICT");
                    }
                    if (decimal(before.get("baseRateVndPerUsdt"))
                                    .compareTo(request.baseRateVndPerUsdt()) == 0
                            && decimal(before.get("buySpreadPct"))
                                    .compareTo(request.buySpreadPct()) == 0
                            && intValue(before.get("lockWindowMinutes")) == request.lockWindowMinutes()) {
                        throw new BizException(422, "FX_QUOTE_NO_CHANGES");
                    }
                    if (mapper.updateFxQuoteConfig(
                            request.baseRateVndPerUsdt().setScale(0, RoundingMode.UNNECESSARY),
                            request.buySpreadPct().setScale(2, RoundingMode.UNNECESSARY),
                            request.lockWindowMinutes(), request.expectedVersion(),
                            actor, request.reason().trim()) != 1) {
                        conflict("FX_QUOTE_VERSION_CONFLICT");
                    }
                    if (mapper.insertFxQuoteHistory(
                            decimal(before.get("baseRateVndPerUsdt")), request.baseRateVndPerUsdt(),
                            decimal(before.get("buySpreadPct")), request.buySpreadPct(),
                            intValue(before.get("lockWindowMinutes")), request.lockWindowMinutes(),
                            actor, request.reason().trim(), idempotencyKey) != 1) {
                        throw new IllegalStateException("FX_QUOTE_HISTORY_WRITE_FAILED");
                    }
                    Map<String, Object> updated = requiredMap(mapper.findFxQuoteConfig(), "FX_QUOTE_CONFIG_UNAVAILABLE");
                    requiredAudit("FX_QUOTE_UPDATED", "FX_QUOTE_CONFIG", "VND_USDT",
                            actor, request.reason(), idempotencyKey,
                            Map.of("before", fxAuditValues(before), "after", fxAuditValues(updated)));
                    outboxService.publish("FX_QUOTE_CONFIG", "VND_USDT", "admin.fx_quote_updated", Map.of(
                            "configCode", "VND_USDT",
                            "beforeVersion", longValue(before.get("version")),
                            "version", longValue(updated.get("version")),
                            "baseRateVndPerUsdt", decimal(updated.get("baseRateVndPerUsdt")),
                            "buySpreadPct", decimal(updated.get("buySpreadPct")),
                            "quoteRateVndPerUsdt", VietnamPaymentPolicy.quoteRate(
                                    decimal(updated.get("baseRateVndPerUsdt")),
                                    decimal(updated.get("buySpreadPct"))),
                            "lockWindowMinutes", intValue(updated.get("lockWindowMinutes")),
                            "operator", actor));
                    Map<String, Object> response = fxSnapshot(updated);
                    response.put("history", safeList(mapper.listFxQuoteHistory()));
                    return ApiResult.ok(response);
                });
        return result;
    }

    private ApiResult<Map<String, Object>> doReconcile(
            Long id, String action, String idempotencyKey, VietQrReconciliationCommandRequest request) {
        Map<String, Object> row = requiredMap(mapper.findVietQrReconciliationForUpdate(id),
                "VIETQR_RECONCILIATION_NOT_FOUND", 404);
        if (!"OPEN".equals(text(row.get("status")))) {
            conflict("VIETQR_RECONCILIATION_ALREADY_TERMINAL");
        }
        if (longValue(row.get("version")) != request.expectedVersion()) {
            conflict("VIETQR_RECONCILIATION_VERSION_CONFLICT");
        }
        if (!StringUtils.hasText(text(row.get("paymentReference")))) {
            conflict("VIETQR_PAYMENT_EVIDENCE_MISSING");
        }
        requiredMap(mapper.findVietQrBankAccountForUpdate(longValue(row.get("bankAccountId"))),
                "VIETQR_BANK_ACCOUNT_NOT_FOUND", 404);
        String viewType = text(row.get("viewType"));
        Long userId = row.get("userId") instanceof Number number ? number.longValue() : request.userId();
        String boundIntentNo = text(row.get("intentNo"));
        boolean intentTransitionRequired = row.get("intentTransitionRequired") == null
                || booleanValue(row.get("intentTransitionRequired"));
        String requestedIntentNo = clean(request.intentNo());
        if (StringUtils.hasText(boundIntentNo)
                && StringUtils.hasText(requestedIntentNo)
                && !boundIntentNo.equals(requestedIntentNo)) {
            conflict("VIETQR_BOUND_INTENT_OVERRIDE_NOT_ALLOWED");
        }
        String intentNo = StringUtils.hasText(boundIntentNo) ? boundIntentNo : requestedIntentNo;
        Map<String, Object> canonicalIntent = null;
        boolean transitionIntent = false;
        boolean credit;
        if ("MATCH_CREDIT".equals(action)) {
            if (!Set.of("MATCHED", "ORPHAN").contains(viewType)) {
                conflict("VIETQR_MATCH_CREDIT_NOT_ALLOWED");
            }
            if (!StringUtils.hasText(intentNo)) {
                validation("VIETQR_MATCH_TARGET_REQUIRED");
            }
            canonicalIntent = canonicalIntent(intentNo);
            userId = canonicalUserId(canonicalIntent, userId, request.userId());
            transitionIntent = !StringUtils.hasText(boundIntentNo) || intentTransitionRequired;
            validateCanonicalIntent(
                    canonicalIntent, row, viewType,
                    true, "ORPHAN".equals(viewType), transitionIntent);
            credit = true;
        } else if ("WRITE_OFF".equals(action)) {
            if (!"MISMATCH".equals(viewType) || !StringUtils.hasText(intentNo)) {
                conflict("VIETQR_WRITE_OFF_NOT_ALLOWED");
            }
            canonicalIntent = canonicalIntent(intentNo);
            userId = canonicalUserId(canonicalIntent, userId, request.userId());
            transitionIntent = intentTransitionRequired;
            validateCanonicalIntent(
                    canonicalIntent, row, viewType,
                    true, false, transitionIntent);
            credit = true;
        } else {
            if (!Set.of("ORPHAN", "MISMATCH", "LATE").contains(viewType)) {
                conflict("VIETQR_RETURN_NOT_ALLOWED");
            }
            boolean unboundOrphan = "ORPHAN".equals(viewType)
                    && !StringUtils.hasText(boundIntentNo);
            if (unboundOrphan
                    && (StringUtils.hasText(requestedIntentNo)
                    || request.userId() != null)) {
                conflict("VIETQR_ORPHAN_RETURN_TARGET_NOT_ALLOWED");
            }
            if (unboundOrphan) {
                userId = null;
                intentNo = null;
            } else if (StringUtils.hasText(intentNo)) {
                canonicalIntent = canonicalIntent(intentNo);
                userId = canonicalUserId(canonicalIntent, userId, request.userId());
                transitionIntent = !StringUtils.hasText(boundIntentNo) || intentTransitionRequired;
                validateCanonicalIntent(
                        canonicalIntent, row, viewType,
                        false, false, transitionIntent);
            }
            credit = false;
        }

        BigDecimal amount = credit
                ? reconciliationAmount(row, canonicalIntent, viewType)
                : BigDecimal.ZERO.setScale(6);
        receiptEvidenceService.claim(
                request.evidenceRef(),
                text(row.get("reconciliationNo")) + ":" + action + ":v" + request.expectedVersion(),
                operator(request.operator()));
        if (credit) {
            Map<String, Object> wallet = requiredMap(mapper.findUsdtWalletForUpdate(userId),
                    "VIETQR_TARGET_WALLET_NOT_FOUND", 404);
            long walletVersion = longValue(wallet.get("version"));
            BigDecimal balanceAfter = decimal(wallet.get("usdtAvailable")).add(amount);
            if (mapper.creditUsdtWallet(userId, amount, walletVersion) != 1) {
                conflict("VIETQR_TARGET_WALLET_VERSION_CONFLICT");
            }
            if (mapper.insertVietQrWalletLedger(
                    "D1-VIETQR-" + text(row.get("reconciliationNo")),
                    userId, amount, balanceAfter,
                    "VietQR settlement " + text(row.get("reconciliationNo"))) != 1) {
                throw new IllegalStateException("VIETQR_LEDGER_WRITE_FAILED");
            }
        }
        String nextStatus = credit ? "CREDITED" : "RETURNED";
        String nextView = credit ? "MATCHED" : viewType;
        if (canonicalIntent != null && transitionIntent) {
            BigDecimal receivedVnd = decimal(row.get("receivedVnd"));
            if (appIntentMapper.transitionIntent(
                    intentNo,
                    longValue(canonicalIntent.get("version")),
                    text(canonicalIntent.get("status")),
                    nextStatus,
                    receivedVnd.signum() > 0 ? receivedVnd : null,
                    amount,
                    LocalDateTime.now(clock)) != 1) {
                conflict("VIETQR_INTENT_VERSION_CONFLICT");
            }
            appIntentMapper.closeInFlightReconciliation(intentNo, nextStatus);
        }
        if (mapper.completeVietQrReconciliation(id, request.expectedVersion(), nextStatus, nextView,
                userId, intentNo, amount, request.reason().trim()) != 1) {
            conflict("VIETQR_RECONCILIATION_VERSION_CONFLICT");
        }
        requiredAudit("VIETQR_RECONCILIATION_" + action, "VIETQR_RECONCILIATION",
                String.valueOf(id), operator(request.operator()), request.reason(), idempotencyKey,
                Map.of("beforeStatus", "OPEN", "afterStatus", nextStatus,
                        "amountUsdt", amount, "viewType", viewType,
                        "evidenceRef", request.evidenceRef().trim(),
                        "paymentReference", text(row.get("paymentReference"))));
        return ApiResult.ok(Map.of(
                "id", id, "status", nextStatus, "viewType", nextView,
                "creditedUsdt", amount, "version", request.expectedVersion() + 1));
    }

    private Map<String, Object> canonicalIntent(String intentNo) {
        return requiredMap(appIntentMapper.findIntentForUpdate(intentNo),
                "VIETQR_INTENT_NOT_FOUND", 404);
    }

    private ApiResult<Map<String, Object>> doRegisterVietQrReceipt(
            String idempotencyKey,
            VietQrReceiptRegistrationRequest request,
            String paymentReference,
            String memoCode) {
        requiredMap(
                mapper.findVietQrBankAccountForUpdate(request.bankAccountId()),
                "VIETQR_BANK_ACCOUNT_NOT_FOUND", 404);
        LocalDateTime receivedAt = receiptBusinessTime(request);
        LocalDate receivedBankDate = request.receivedAt()
                .atZoneSameInstant(VIETNAM_BANK_ZONE)
                .toLocalDate();
        Map<String, Object> intent = StringUtils.hasText(memoCode)
                ? appIntentMapper.findIntentByMemoForUpdate(memoCode)
                : null;
        Map<String, Object> config = requiredMap(
                mapper.findVietQrConfig(), "VIETQR_CONFIG_UNAVAILABLE");
        String viewType = "ORPHAN";
        Long userId = null;
        String intentNo = null;
        BigDecimal payableVnd = null;
        BigDecimal rate;
        LocalDateTime expiresAt = null;
        String nextIntentStatus = null;
        boolean intentTransitionRequired = false;
        if (intent == null) {
            Map<String, Object> fx = requiredMap(
                    mapper.findFxQuoteConfig(), "FX_QUOTE_CONFIG_UNAVAILABLE");
            rate = VietnamPaymentPolicy.quoteRate(
                    decimal(fx.get("baseRateVndPerUsdt")),
                    decimal(fx.get("buySpreadPct")));
        } else {
            LocalDateTime createdAt = localDateTime(intent.get("createdAt"));
            if (createdAt == null || receivedAt.isBefore(createdAt)) {
                conflict("VIETQR_RECEIPT_PREDATES_INTENT");
            }
            userId = longValue(intent.get("userId"));
            intentNo = text(intent.get("intentNo"));
            payableVnd = decimal(intent.get("payableVnd"));
            rate = decimal(intent.get("lockedFxRateVndPerUsdt"));
            expiresAt = localDateTime(intent.get("expiresAt"));
            String intentStatus = text(intent.get("status"));
            boolean alreadyClaimedOrTerminal = Set.of(
                    "RECEIPT_REVIEW", "MISMATCH_REVIEW", "LATE_REVIEW",
                    "CREDITED", "CANCELLED", "RETURN_PENDING", "RETURNED")
                    .contains(intentStatus);
            if (!alreadyClaimedOrTerminal
                    && !Set.of("AWAITING_PAYMENT", "EXPIRED").contains(intentStatus)) {
                conflict("VIETQR_INTENT_STATUS_INVALID");
            }
            boolean late = expiresAt == null
                    || receivedAt.isAfter(expiresAt.plusMinutes(
                            intValue(config.get("graceMinutes"))));
            boolean mismatch = longValue(intent.get("bankAccountId"))
                    != request.bankAccountId()
                    || request.receivedVnd().subtract(payableVnd).abs()
                            .compareTo(decimal(config.get("toleranceVnd"))) > 0;
            if (alreadyClaimedOrTerminal) {
                viewType = "LATE";
            } else if (late) {
                viewType = "LATE";
                nextIntentStatus = "LATE_REVIEW";
                intentTransitionRequired = true;
            } else if (mismatch) {
                viewType = "MISMATCH";
                nextIntentStatus = "MISMATCH_REVIEW";
                intentTransitionRequired = true;
            } else {
                viewType = "MATCHED";
                nextIntentStatus = "RECEIPT_REVIEW";
                intentTransitionRequired = true;
            }
        }

        String reconciliationNo = "VQR-REC-"
                + UUID.randomUUID().toString().replace("-", "")
                        .substring(0, 20).toUpperCase(Locale.ROOT);
        String actor = operator(request.operator());
        receiptEvidenceService.claim(request.evidenceRef(), reconciliationNo, actor);
        try {
            if (mapper.insertVietQrReceipt(
                    reconciliationNo, intentNo, userId, request.bankAccountId(),
                    viewType, payableVnd, request.receivedVnd(), rate,
                    paymentReference,
                    "REGISTERED evidence=" + request.evidenceRef().trim(),
                    expiresAt, receivedAt,
                    intentTransitionRequired) != 1) {
                conflict("VIETQR_RECEIPT_REGISTER_FAILED");
            }
        } catch (DuplicateKeyException ex) {
            throw new BizException(409, "VIETQR_PAYMENT_REFERENCE_ALREADY_REGISTERED");
        }
        if (mapper.addVietQrBankReceivedToday(
                request.bankAccountId(),
                request.receivedVnd(),
                receivedBankDate) != 1) {
            conflict("VIETQR_BANK_ACCOUNT_RECEIPT_TOTAL_UPDATE_FAILED");
        }
        if (intent != null && intentTransitionRequired) {
            appIntentMapper.closeInFlightReconciliation(intentNo, "RECEIPT_REGISTERED");
            if (appIntentMapper.transitionIntent(
                            intentNo,
                            longValue(intent.get("version")),
                            text(intent.get("status")),
                            nextIntentStatus,
                            request.receivedVnd(),
                            BigDecimal.ZERO.setScale(6),
                            receivedAt) != 1) {
                conflict("VIETQR_INTENT_VERSION_CONFLICT");
            }
        }
        Map<String, Object> accountAfterReceipt = requiredMap(
                mapper.findVietQrBankAccountForUpdate(request.bankAccountId()),
                "VIETQR_BANK_ACCOUNT_NOT_FOUND", 404);
        if ("FUSED".equals(text(accountAfterReceipt.get("status")))) {
            appIntentMapper.cancelAwaitingIntentsForFusedAccount(
                    request.bankAccountId(), intentNo);
            appIntentMapper.closeCancelledInFlightReconciliationsForFusedAccount(
                    request.bankAccountId(), intentNo);
        }
        requiredAudit("VIETQR_RECEIPT_REGISTERED", "VIETQR_RECONCILIATION",
                reconciliationNo, actor, request.reason(), idempotencyKey,
                Map.of(
                        "bankAccountId", request.bankAccountId(),
                        "paymentReference", paymentReference,
                        "evidenceRef", request.evidenceRef().trim(),
                        "viewType", viewType,
                        "receivedVnd", request.receivedVnd()));
        return ApiResult.ok(requiredMap(
                mapper.findVietQrReceiptByPaymentReference(paymentReference),
                "VIETQR_RECEIPT_READ_AFTER_WRITE_FAILED"));
    }

    private Long canonicalUserId(
            Map<String, Object> intent, Long reconciliationUserId, Long requestedUserId) {
        Long canonicalUserId = longValue(intent.get("userId"));
        if ((reconciliationUserId != null && !canonicalUserId.equals(reconciliationUserId))
                || (requestedUserId != null && !canonicalUserId.equals(requestedUserId))) {
            conflict("VIETQR_INTENT_USER_MISMATCH");
        }
        return canonicalUserId;
    }

    private void validateCanonicalIntent(
            Map<String, Object> intent, Map<String, Object> reconciliation,
            String viewType, boolean requireBankMatch,
            boolean requireWithinTolerance, boolean transitionIntent) {
        String status = text(intent.get("status"));
        LocalDateTime expiresAt = localDateTime(intent.get("expiresAt"));
        LocalDateTime receivedAt = localDateTime(reconciliation.get("receivedAt"));
        if (expiresAt == null || receivedAt == null) {
            conflict("VIETQR_RECEIPT_TIME_SNAPSHOT_INVALID");
        }
        if (transitionIntent) {
            Set<String> expectedStatuses = switch (viewType) {
                case "MATCHED" -> Set.of("RECEIPT_REVIEW");
                case "MISMATCH" -> Set.of("MISMATCH_REVIEW");
                case "LATE" -> Set.of("LATE_REVIEW");
                case "ORPHAN" -> Set.of("AWAITING_PAYMENT", "EXPIRED");
                default -> Set.of();
            };
            if (!expectedStatuses.contains(status)) {
                conflict("VIETQR_INTENT_ALREADY_TERMINAL");
            }
            if ("ORPHAN".equals(viewType)) {
                Map<String, Object> config = requiredMap(
                        mapper.findVietQrConfig(), "VIETQR_CONFIG_UNAVAILABLE");
                LocalDateTime createdAt = localDateTime(intent.get("createdAt"));
                if (createdAt == null
                        || receivedAt.isBefore(createdAt)) {
                    conflict("VIETQR_RECEIPT_PREDATES_INTENT");
                }
                boolean arrivedLate = receivedAt.isAfter(expiresAt.plusMinutes(
                        intValue(config.get("graceMinutes"))));
                if (arrivedLate) {
                    conflict("VIETQR_INTENT_EXPIRED");
                }
            }
        } else if (!"LATE".equals(viewType)) {
            conflict("VIETQR_SUPPLEMENTAL_RECEIPT_ACTION_NOT_ALLOWED");
        }
        BigDecimal intentRate = decimal(intent.get("lockedFxRateVndPerUsdt"));
        if (intentRate.signum() <= 0) {
            conflict("VIETQR_INTENT_FX_SNAPSHOT_INVALID");
        }
        if (requireBankMatch && longValue(intent.get("bankAccountId"))
                != longValue(reconciliation.get("bankAccountId"))) {
            conflict("VIETQR_INTENT_BANK_ACCOUNT_MISMATCH");
        }
        if (requireWithinTolerance) {
            BigDecimal tolerance = decimal(requiredMap(
                    mapper.findVietQrConfig(), "VIETQR_CONFIG_UNAVAILABLE").get("toleranceVnd"));
            BigDecimal delta = decimal(reconciliation.get("receivedVnd"))
                    .subtract(decimal(intent.get("payableVnd"))).abs();
            if (delta.compareTo(tolerance) > 0) {
                conflict("VIETQR_INTENT_AMOUNT_MISMATCH");
            }
        }
    }

    private boolean booleanValue(Object value) {
        if (value instanceof Boolean bool) return bool;
        if (value instanceof Number number) return number.intValue() != 0;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private LocalDateTime localDateTime(Object value) {
        if (value instanceof LocalDateTime localDateTime) return localDateTime;
        if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
        return null;
    }

    private Map<String, Object> fxSnapshot(Map<String, Object> config) {
        BigDecimal base = decimal(config.get("baseRateVndPerUsdt"));
        BigDecimal spread = decimal(config.get("buySpreadPct"));
        Map<String, Object> response = new LinkedHashMap<>(config);
        response.put("quoteRateVndPerUsdt", VietnamPaymentPolicy.quoteRate(base, spread));
        response.put("quoteDerived", true);
        response.put("source", "nx_finance_fx_quote_config");
        response.put("asOf", LocalDateTime.now(clock));
        return response;
    }

    private Map<String, Object> fxAuditValues(Map<String, Object> values) {
        return Map.of(
                "baseRateVndPerUsdt", decimal(values.get("baseRateVndPerUsdt")),
                "buySpreadPct", decimal(values.get("buySpreadPct")),
                "lockWindowMinutes", intValue(values.get("lockWindowMinutes")),
                "version", longValue(values.get("version")));
    }

    private void validateFx(FxQuoteUpdateRequest request) {
        if (request == null) {
            validation("FX_QUOTE_REQUEST_REQUIRED");
        }
        requireIntegerRange(request.baseRateVndPerUsdt(), BigDecimal.valueOf(20_000),
                BigDecimal.valueOf(35_000), "FX_BASE_RATE_OUT_OF_RANGE");
        requireRange(request.buySpreadPct(), BigDecimal.ZERO, BigDecimal.valueOf(3),
                "FX_SPREAD_OUT_OF_RANGE");
        requireIntRange(request.lockWindowMinutes(), 5, 120, "FX_LOCK_WINDOW_OUT_OF_RANGE");
    }

    private void validateVietQrConfig(VietQrConfigUpdateRequest request) {
        if (request == null || !StringUtils.hasText(request.rotationStrategy())) {
            validation("VIETQR_CONFIG_REQUEST_INVALID");
        }
        requireIntegerRange(request.toleranceVnd(), BigDecimal.ZERO, BigDecimal.valueOf(5_000),
                "VIETQR_TOLERANCE_OUT_OF_RANGE");
        requireIntRange(request.graceMinutes(), 0, 60, "VIETQR_GRACE_OUT_OF_RANGE");
        requireIntegerRange(request.perTxLimitUsd(), BigDecimal.valueOf(100), BigDecimal.valueOf(10_000),
                "VIETQR_TX_LIMIT_OUT_OF_RANGE");
        requireIntRange(request.trc20Confirmations(), 1, 64, "TRC20_CONFIRMATIONS_OUT_OF_RANGE");
        requireIntRange(request.erc20Confirmations(), 1, 64, "ERC20_CONFIRMATIONS_OUT_OF_RANGE");
        requireIntRange(request.bep20Confirmations(), 1, 64, "BEP20_CONFIRMATIONS_OUT_OF_RANGE");
        if (!ROTATION_STRATEGIES.contains(request.rotationStrategy().trim().toUpperCase(Locale.ROOT))) {
            validation("VIETQR_ROTATION_STRATEGY_INVALID");
        }
    }

    private void validateBankAccount(VietQrBankAccountCreateRequest request) {
        if (request == null || !StringUtils.hasText(request.bankCode())
                || !request.bankCode().trim().matches("[A-Za-z0-9_-]{2,16}")
                || !StringUtils.hasText(request.bankName()) || request.bankName().trim().length() > 80
                || !StringUtils.hasText(request.accountHolder()) || request.accountHolder().trim().length() > 120
                || !StringUtils.hasText(request.accountNumber())
                || !request.accountNumber().trim().matches("[0-9]{6,34}")) {
            validation("VIETQR_BANK_ACCOUNT_INVALID");
        }
        requireIntegerRange(request.dailyCapVnd(), BigDecimal.valueOf(1_000_000),
                BigDecimal.valueOf(10_000_000_000L), "VIETQR_DAILY_CAP_OUT_OF_RANGE");
    }

    private BigDecimal reconciliationAmount(
            Map<String, Object> row, Map<String, Object> canonicalIntent,
            String viewType) {
        BigDecimal received = decimal(row.get("receivedVnd"));
        BigDecimal rate = decimal(canonicalIntent.get("lockedFxRateVndPerUsdt"));
        if (received.signum() <= 0 || rate.signum() <= 0) {
            conflict("VIETQR_RECONCILIATION_AMOUNT_INVALID");
        }
        BigDecimal amount = received.divide(rate, 6, RoundingMode.HALF_UP);
        if ("MISMATCH".equals(viewType)) {
            BigDecimal requestedUsdt = decimal(canonicalIntent.get("requestedUsdt"));
            BigDecimal currentLimit = decimal(requiredMap(
                    mapper.findVietQrConfig(), "VIETQR_CONFIG_UNAVAILABLE")
                    .get("perTxLimitUsd"));
            if (requestedUsdt.signum() <= 0 || currentLimit.signum() <= 0
                    || amount.compareTo(requestedUsdt.max(currentLimit)) > 0) {
                conflict("VIETQR_RECEIPT_CREDIT_LIMIT_EXCEEDED");
            }
        }
        return amount;
    }

    private String normalizeView(String view) {
        if (!StringUtils.hasText(view) || "all".equalsIgnoreCase(view.trim())) {
            return null;
        }
        String normalized = view.trim().toUpperCase(Locale.ROOT);
        if (!VIEW_TYPES.contains(normalized)) {
            validation("VIETQR_VIEW_INVALID");
        }
        return normalized;
    }

    private String normalizeAction(String action) {
        String normalized = clean(action).toUpperCase(Locale.ROOT).replace('-', '_');
        if (!Set.of("MATCH_CREDIT", "WRITE_OFF", "RETURN").contains(normalized)) {
            validation("VIETQR_RECONCILIATION_ACTION_INVALID");
        }
        return normalized;
    }

    private void validateMutation(String key, Long expectedVersion, String reason) {
        requireKeyAndReason(key, reason);
        if (expectedVersion == null || expectedVersion < 0) {
            validation("EXPECTED_VERSION_REQUIRED");
        }
    }

    private void requireKeyAndReason(String key, String reason) {
        if (!StringUtils.hasText(key)) {
            throw new BizException(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(),
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 8) {
            validation("OPERATION_REASON_TOO_SHORT");
        }
        if (reason.trim().length() > 200) {
            validation("OPERATION_REASON_TOO_LONG");
        }
    }

    private void validateReceiptUploadEvidence(String evidenceRef) {
        String value = clean(evidenceRef);
        if (!value.matches("media:vqr_[0-9a-f]{32}")) {
            validation("VIETQR_RECEIPT_UPLOAD_EVIDENCE_REQUIRED");
        }
        receiptEvidenceService.validateReferenceSyntax(evidenceRef);
    }

    private void validateReceiptRegistration(
            String idempotencyKey, VietQrReceiptRegistrationRequest request) {
        requireKeyAndReason(idempotencyKey, request == null ? null : request.reason());
        if (request == null || request.bankAccountId() == null
                || request.bankAccountId() <= 0) {
            validation("VIETQR_BANK_ACCOUNT_ID_REQUIRED");
        }
        validateReceiptUploadEvidence(request.evidenceRef());
        String paymentReference = clean(request.paymentReference());
        if (paymentReference.length() < 6 || paymentReference.length() > 128
                || !paymentReference.matches("[A-Za-z0-9][A-Za-z0-9._:/-]*")) {
            validation("VIETQR_PAYMENT_REFERENCE_INVALID");
        }
        String memoCode = clean(request.memoCode());
        if (StringUtils.hasText(memoCode)
                && (memoCode.length() > 32
                || !memoCode.matches("[A-Za-z0-9][A-Za-z0-9_-]*"))) {
            validation("VIETQR_MEMO_CODE_INVALID");
        }
        requireIntegerRange(
                request.receivedVnd(), BigDecimal.ONE,
                BigDecimal.valueOf(10_000_000_000L),
                "VIETQR_RECEIVED_AMOUNT_INVALID");
        if (request.receivedAt() == null) {
            validation("VIETQR_RECEIVED_AT_REQUIRED");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime receivedAt = receiptBusinessTime(request);
        if (receivedAt.isAfter(now.plusMinutes(5))
                || receivedAt.isBefore(now.minus(90, ChronoUnit.DAYS))
                || request.receivedAt().atZoneSameInstant(VIETNAM_BANK_ZONE)
                        .toLocalDate()
                        .isAfter(LocalDate.now(clock.withZone(VIETNAM_BANK_ZONE)))) {
            validation("VIETQR_RECEIVED_AT_INVALID");
        }
    }

    private LocalDateTime receiptBusinessTime(VietQrReceiptRegistrationRequest request) {
        return request.receivedAt()
                .atZoneSameInstant(clock.getZone())
                .toLocalDateTime();
    }

    private void requireIntegerRange(BigDecimal value, BigDecimal min, BigDecimal max, String error) {
        requireSafeDecimal(value, error);
        requireRange(value, min, max, error);
        if (value.stripTrailingZeros().scale() > 0) {
            validation(error);
        }
    }

    private void requireRange(BigDecimal value, BigDecimal min, BigDecimal max, String error) {
        requireSafeDecimal(value, error);
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
            validation(error);
        }
    }

    private void requireSafeDecimal(BigDecimal value, String error) {
        if (value == null || value.scale() < -20 || value.scale() > 20
                || value.precision() > 32) {
            validation(error);
        }
    }

    private void requireIntRange(Integer value, int min, int max, String error) {
        if (value == null || value < min || value > max) {
            validation(error);
        }
    }

    private void requiredAudit(String action, String resourceType, String resourceId,
                               String actor, String reason, String key, Map<String, Object> detailValues) {
        Map<String, Object> detail = new LinkedHashMap<>(detailValues);
        detail.put("reason", reason.trim());
        detail.put("idempotencyKey", key);
        audit.recordRequired(AuditLogWriteRequest.builder()
                .action(action).resourceType(resourceType).resourceId(resourceId)
                .actorUsername(actor).riskLevel("HIGH").detail(detail).build());
    }

    private String operator(String requested) {
        String actor = AdminActorResolver.resolve(requested);
        return StringUtils.hasText(actor) ? actor.trim() : "system";
    }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private Map<String, Object> requiredMap(Map<String, Object> value, String error) {
        return requiredMap(value, error, 500);
    }

    private Map<String, Object> requiredMap(Map<String, Object> value, String error, int code) {
        if (value == null || value.isEmpty()) {
            throw new BizException(code, error);
        }
        return new LinkedHashMap<>(value);
    }

    private <T> List<T> safeList(List<T> value) {
        return value == null ? List.of() : value;
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(String.valueOf(value));
    }

    private BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(2, RoundingMode.HALF_UP);
    }

    private long longValue(Object value) {
        return value instanceof Number number ? number.longValue() : Long.parseLong(String.valueOf(value));
    }

    private int intValue(Object value) {
        return value instanceof Number number ? number.intValue() : Integer.parseInt(String.valueOf(value));
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private String last4(String value) {
        return value.substring(Math.max(0, value.length() - 4));
    }

    private void requireId(Long id, String error) {
        if (id == null || id <= 0) {
            validation(error);
        }
    }

    private void validation(String error) {
        throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), error);
    }

    private void conflict(String error) {
        throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), error);
    }
}

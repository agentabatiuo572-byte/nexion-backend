package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.finance.dto.FxQuoteUpdateRequest;
import ffdd.opsconsole.finance.dto.VietQrBankAccountCommandRequest;
import ffdd.opsconsole.finance.dto.VietQrBankAccountCreateRequest;
import ffdd.opsconsole.finance.dto.VietQrConfigUpdateRequest;
import ffdd.opsconsole.finance.dto.VietQrReconciliationCommandRequest;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
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
    private final VietnamPaymentMapper mapper;
    private final AuditLogService audit;
    private final AdminIdempotencyService idempotency;
    private final FinanceSensitiveDataCipher sensitiveDataCipher;
    private final Clock clock;

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> vietQrOverview(String view, Integer pageNum, Integer pageSize) {
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
        String normalizedAction = normalizeAction(action);
        String requestHash = hash(id + ":" + normalizedAction + ":" + request.expectedVersion() + ":"
                + request.userId() + ":" + clean(request.intentNo()) + ":" + request.reason().trim());
        @SuppressWarnings({"rawtypes", "unchecked"})
        ApiResult<Map<String, Object>> result = (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "D1_VIETQR_RECONCILIATION_" + normalizedAction,
                idempotencyKey, requestHash, ApiResult.class,
                () -> doReconcile(id, normalizedAction, idempotencyKey, request));
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
                        if (mapper.insertVietQrBankAccount(
                                request.bankCode().trim().toUpperCase(Locale.ROOT),
                                request.bankName().trim(), request.accountHolder().trim(),
                                sensitiveDataCipher.encrypt(accountNumber), hash(accountNumber), last4(accountNumber),
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
        String viewType = text(row.get("viewType"));
        Long userId = row.get("userId") instanceof Number number ? number.longValue() : request.userId();
        String intentNo = StringUtils.hasText(text(row.get("intentNo")))
                ? text(row.get("intentNo")) : clean(request.intentNo());
        boolean credit;
        if ("MATCH_CREDIT".equals(action)) {
            if (!Set.of("ORPHAN", "LATE").contains(viewType)) {
                conflict("VIETQR_MATCH_CREDIT_NOT_ALLOWED");
            }
            userId = request.userId() == null ? userId : request.userId();
            intentNo = StringUtils.hasText(request.intentNo()) ? request.intentNo().trim() : intentNo;
            if (userId == null || !StringUtils.hasText(intentNo)) {
                validation("VIETQR_MATCH_TARGET_REQUIRED");
            }
            credit = true;
        } else if ("WRITE_OFF".equals(action)) {
            if (!"MISMATCH".equals(viewType) || userId == null) {
                conflict("VIETQR_WRITE_OFF_NOT_ALLOWED");
            }
            credit = true;
        } else {
            if (!Set.of("ORPHAN", "MISMATCH", "LATE").contains(viewType)) {
                conflict("VIETQR_RETURN_NOT_ALLOWED");
            }
            credit = false;
        }

        BigDecimal amount = credit ? reconciliationAmount(row) : BigDecimal.ZERO.setScale(6);
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
                    userId, amount, balanceAfter, request.reason().trim()) != 1) {
                throw new IllegalStateException("VIETQR_LEDGER_WRITE_FAILED");
            }
        }
        String nextStatus = credit ? "CREDITED" : "RETURNED";
        String nextView = credit ? "MATCHED" : viewType;
        if (mapper.completeVietQrReconciliation(id, request.expectedVersion(), nextStatus, nextView,
                userId, intentNo, amount, request.reason().trim()) != 1) {
            conflict("VIETQR_RECONCILIATION_VERSION_CONFLICT");
        }
        requiredAudit("VIETQR_RECONCILIATION_" + action, "VIETQR_RECONCILIATION",
                String.valueOf(id), operator(request.operator()), request.reason(), idempotencyKey,
                Map.of("beforeStatus", "OPEN", "afterStatus", nextStatus,
                        "amountUsdt", amount, "viewType", viewType));
        return ApiResult.ok(Map.of(
                "id", id, "status", nextStatus, "viewType", nextView,
                "creditedUsdt", amount, "version", request.expectedVersion() + 1));
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

    private BigDecimal reconciliationAmount(Map<String, Object> row) {
        BigDecimal received = decimal(row.get("receivedVnd"));
        BigDecimal rate = decimal(row.get("lockedFxRateVndPerUsdt"));
        if (received.signum() <= 0 || rate.signum() <= 0) {
            conflict("VIETQR_RECONCILIATION_AMOUNT_INVALID");
        }
        return received.divide(rate, 6, RoundingMode.HALF_UP);
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

    private void requireIntegerRange(BigDecimal value, BigDecimal min, BigDecimal max, String error) {
        requireRange(value, min, max, error);
        if (value.stripTrailingZeros().scale() > 0) {
            validation(error);
        }
    }

    private void requireRange(BigDecimal value, BigDecimal min, BigDecimal max, String error) {
        if (value == null || value.compareTo(min) < 0 || value.compareTo(max) > 0) {
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

package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.content.facade.SupportPaymentAttributionFacade;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.finance.dto.FxQuoteUpdateRequest;
import ffdd.opsconsole.finance.dto.VietQrBankAccountCommandRequest;
import ffdd.opsconsole.finance.dto.VietQrBankAccountCreateRequest;
import ffdd.opsconsole.finance.dto.VietQrConfigUpdateRequest;
import ffdd.opsconsole.finance.dto.VietQrReconciliationCommandRequest;
import ffdd.opsconsole.finance.dto.VietQrReceiptRegistrationRequest;
import ffdd.opsconsole.finance.mapper.AppVietQrIntentMapper;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
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
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class OpsVietnamPaymentService {
    private static final Set<String> VIEW_TYPES = Set.of("INFLIGHT", "MATCHED", "ORPHAN", "MISMATCH", "LATE");
    private static final Set<String> ROTATION_STRATEGIES = Set.of("ROUND_ROBIN", "REMAINING_CAPACITY");
    @lombok.NonNull
    private final SupportPaymentAttributionFacade paymentAttribution;
    private final VietnamPaymentMapper mapper;
    private final AuditLogService audit;
    private final AdminIdempotencyService idempotency;
    private final FinanceSensitiveDataCipher sensitiveDataCipher;
    private final AppVietQrIntentMapper appIntentMapper;
    private final EventOutboxService outboxService;
    private final VietQrReceiptEvidenceService receiptEvidenceService;
    private final PayoutVndConfigService payoutVnd;
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

    public ApiResult<Map<String, Object>> reconcile(
            Long id, String action, String idempotencyKey, VietQrReconciliationCommandRequest request) {
        return ApiResult.fail(410, "VIETQR_MANUAL_TOPUP_RETIRED");
    }

    public ApiResult<Map<String, Object>> registerVietQrReceipt(
            String idempotencyKey, VietQrReceiptRegistrationRequest request) {
        return ApiResult.fail(410, "VIETQR_MANUAL_TOPUP_RETIRED");
    }

    public ApiResult<Map<String, Object>> createBankAccount(
            String idempotencyKey, VietQrBankAccountCreateRequest request) {
        return ApiResult.fail(410, "VIETQR_MANUAL_TOPUP_RETIRED");
    }

    public ApiResult<Map<String, Object>> updateBankAccount(
            Long id, String idempotencyKey, VietQrBankAccountCommandRequest request) {
        return ApiResult.fail(410, "VIETQR_MANUAL_TOPUP_RETIRED");
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateVietQrConfig(
            String idempotencyKey, VietQrConfigUpdateRequest request) {
        validateMutation(idempotencyKey, request == null ? null : request.expectedVersion(),
                request == null ? null : request.reason());
        validateVietQrConfig(request);
        String rotation = request.rotationStrategy().trim().toUpperCase(Locale.ROOT);
        Map<String, Object> before = requiredMap(mapper.findVietQrConfig(), "VIETQR_CONFIG_UNAVAILABLE");
        // Older clients echo the full configuration when changing the HDPay limit.
        if (longValue(before.get("version")) == request.expectedVersion()
                && (decimal(before.get("toleranceVnd")).compareTo(request.toleranceVnd()) != 0
                    || intValue(before.get("graceMinutes")) != request.graceMinutes()
                    || !rotation.equalsIgnoreCase(String.valueOf(before.get("rotationStrategy"))))) {
            return ApiResult.fail(410, "VIETQR_MANUAL_TOPUP_RETIRED");
        }
        String requestHash = hash(request.toString());
        @SuppressWarnings({"rawtypes", "unchecked"})
        ApiResult<Map<String, Object>> result = (ApiResult<Map<String, Object>>) (ApiResult) idempotency.execute(
                "D1_VIETQR_CONFIG_UPDATE", idempotencyKey, requestHash, ApiResult.class, () -> {
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
        Map<String, Object> approval = mapper.findLatestFxApproval();
        if (approval != null) approval.put("applied", "approved".equalsIgnoreCase(String.valueOf(approval.get("status"))));
        response.put("approval", approval);
        return ApiResult.ok(response);
    }

    @Transactional(readOnly = true)
    public ApiResult<Map<String, Object>> previewFxQuote(FxQuoteUpdateRequest request) {
        validateMutation("preview", request == null ? null : request.expectedVersion(),
                request == null ? null : request.reason());
        validateFx(request);
        Map<String, Object> before = requiredMap(mapper.findFxQuoteConfig(), "FX_QUOTE_CONFIG_UNAVAILABLE");
        if (longValue(before.get("version")) != request.expectedVersion()) conflict("FX_QUOTE_VERSION_CONFLICT");
        boolean baseIncrease = decimal(before.get("baseRateVndPerUsdt")).compareTo(request.baseRateVndPerUsdt()) < 0;
        boolean lowerQuote = VietnamPaymentPolicy.quoteRate(decimal(before.get("baseRateVndPerUsdt")),
                decimal(before.get("buySpreadPct"))).compareTo(VietnamPaymentPolicy.quoteRate(
                        request.baseRateVndPerUsdt(), request.buySpreadPct())) > 0;
        if (!baseIncrease && !lowerQuote) return ApiResult.fail(422, "D6_FX_QUOTE_NOT_APPROVAL_DIRECTION");
        if (baseIncrease && !payoutVnd.coverageHealthy()) return ApiResult.fail(409, "D6_TREASURY_COVERAGE_BLOCKED");
        Map<String, Object> next = new LinkedHashMap<>();
        next.put("version", request.expectedVersion() + 1);
        next.put("baseRateVndPerUsdt", request.baseRateVndPerUsdt());
        next.put("buySpreadPct", request.buySpreadPct());
        next.put("lockWindowMinutes", request.lockWindowMinutes());
        return ApiResult.ok(Map.of(
                "before", fxAuditValues(before).toString(), "after", fxAuditValues(next).toString(),
                "current", fxSnapshot(before), "proposed", fxSnapshot(next),
                "approvalRequired", true, "baseRateIncrease", baseIncrease,
                "rechargeQuoteDecrease", lowerQuote, "fundingCheckRequired", baseIncrease,
                "fundingCheckPassed", true));
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
                    boolean baseIncrease = decimal(before.get("baseRateVndPerUsdt")).compareTo(request.baseRateVndPerUsdt()) < 0;
                    boolean lowerQuote = VietnamPaymentPolicy.quoteRate(decimal(before.get("baseRateVndPerUsdt")),
                            decimal(before.get("buySpreadPct"))).compareTo(VietnamPaymentPolicy.quoteRate(
                                    request.baseRateVndPerUsdt(), request.buySpreadPct())) > 0;
                    if (baseIncrease || lowerQuote) {
                        if (!A2ReplayContext.isReplaying() || A2ReplayContext.operationId() == null) {
                            throw new BizException(409, "D6_FX_QUOTE_APPROVAL_REQUIRED");
                        }
                        if (baseIncrease && !payoutVnd.coverageHealthy()) {
                            throw new BizException(409, "D6_TREASURY_COVERAGE_BLOCKED");
                        }
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
                    response.put("applied", true);
                    response.put("operationId", A2ReplayContext.operationId());
                    return ApiResult.ok(response);
                });
        return result;
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
        if (request.buySpreadPct().stripTrailingZeros().scale() > 2) validation("FX_SPREAD_OUT_OF_RANGE");
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

    private void validation(String error) {
        throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), error);
    }

    private void conflict(String error) {
        throw new BizException(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), error);
    }
}

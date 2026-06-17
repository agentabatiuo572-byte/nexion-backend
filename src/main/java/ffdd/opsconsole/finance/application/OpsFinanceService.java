package ffdd.opsconsole.finance.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.util.StringUtils;

@ApplicationService
public class OpsFinanceService {
    private static final Set<String> REVIEWABLE = Set.of("REVIEWING", "DELAYED");
    private static final Set<String> REJECTABLE = Set.of("REVIEWING", "DELAYED", "FROZEN", "PENDING_CHAIN", "CHAIN_SUBMITTED", "DEAD");
    private static final Set<String> FINAL_STATUSES = Set.of("SUCCESS", "FAILED", "REJECTED");

    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final WithdrawalOrderRepository withdrawalRepository;
    private final AuditLogService auditLogService;

    public OpsFinanceService(
            PlatformConfigFacade configFacade,
            TreasuryCoverageFacade coverageFacade,
            WithdrawalOrderRepository withdrawalRepository,
            AuditLogService auditLogService) {
        this.configFacade = configFacade;
        this.coverageFacade = coverageFacade;
        this.withdrawalRepository = withdrawalRepository;
        this.auditLogService = auditLogService;
    }

    public ApiResult<Map<String, Object>> withdrawalParams() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        BigDecimal maxBalanceRatio = configDecimal("withdrawal.max_balance_pct", new BigDecimal("0.80"));
        BigDecimal feeRate = configDecimal("withdrawal.fee_rate", new BigDecimal("0.02"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dailyLimitCount", configDecimal("withdrawal.daily_count_limit", BigDecimal.ONE).intValue());
        response.put("maxBalancePct", percent(maxBalanceRatio));
        response.put("maxBalanceRatio", maxBalanceRatio);
        response.put("feeRatePct", percent(feeRate));
        response.put("feeRate", feeRate);
        response.put("minUsdt", configDecimal("withdrawal.min_usdt", new BigDecimal("20")));
        response.put("trc20Enabled", configBoolean("withdrawal.trc20.enabled", true));
        response.put("erc20Enabled", configBoolean("withdrawal.erc20.enabled", true));
        response.put("coverageRatio", coverage.coverageRatio());
        response.put("redlinePct", coverage.redlinePct());
        response.put("sources", List.of("nx_config_item: withdrawal.*", "B1 treasury coverage facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateWithdrawalParam(String idempotencyKey, WithdrawalParamUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = normalizeParamKey(request.key());
        BigDecimal oldValue = currentParamValue(key);
        BigDecimal newValue = normalizeParamValue(key, request.value());
        if (loosensWithdrawalControl(key, oldValue, newValue)) {
            TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
            if (coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
                return ApiResult.fail(
                        OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                        OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
            }
        }
        String configKey = configKey(key);
        configFacade.upsertAdminValue(configKey, newValue.toPlainString(), "NUMBER", "wallet", "D5 withdrawal parameter");
        audit("D5_WITHDRAWAL_PARAM_CHANGED", "WITHDRAWAL_PARAM", configKey, request.operator(), Map.of(
                "key", key,
                "configKey", configKey,
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = withdrawalParams().getData();
        response.put("updated", Map.of("key", key, "configKey", configKey, "oldValue", oldValue, "newValue", newValue));
        return ApiResult.ok(response);
    }

    public ApiResult<WithdrawalOrderView> withdrawalDetail(String withdrawalNo) {
        if (!StringUtils.hasText(withdrawalNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "WITHDRAWAL_NO_REQUIRED");
        }
        return withdrawalRepository.findByWithdrawalNo(withdrawalNo.trim())
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "WITHDRAWAL_NOT_FOUND"));
    }

    public ApiResult<WithdrawalOrderView> reviewWithdrawal(
            String withdrawalNo,
            String idempotencyKey,
            WithdrawalReviewRequest request) {
        ApiResult<WithdrawalOrderView> guard = requireWithdrawalCommand(withdrawalNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        WithdrawalOrderView order = withdrawalRepository.findByWithdrawalNo(withdrawalNo.trim()).orElse(null);
        if (order == null) {
            return ApiResult.fail(404, "WITHDRAWAL_NOT_FOUND");
        }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        String newStatus = nextReviewStatus(order.status(), action);
        if (newStatus == null) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        String failureReason = "REJECTED".equals(newStatus) ? request.reason().trim() : null;
        withdrawalRepository.updateStatus(order.withdrawalNo(), newStatus, failureReason);
        WithdrawalOrderView updated = withdrawalRepository.findByWithdrawalNo(order.withdrawalNo())
                .orElse(new WithdrawalOrderView(
                        order.id(), order.userId(), order.withdrawalNo(), order.asset(), order.chain(), order.amount(), order.fee(),
                        order.targetAddress(), order.riskDecisionId(), order.chainTxHash(), newStatus, order.chainSubmittedAt(),
                        order.completedAt(), order.failedAt(), failureReason, order.chainBroadcastAttempts(), order.nextBroadcastAt(),
                        order.lastBroadcastError(), order.broadcastDeadAt(), order.createdAt(), order.updatedAt()));
        audit("D5_WITHDRAWAL_REVIEW_" + action, "WITHDRAWAL", order.withdrawalNo(), request.operator(), Map.of(
                "fromStatus", order.status(),
                "toStatus", newStatus,
                "asset", order.asset(),
                "amount", order.amount(),
                "fee", order.fee(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason)) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<WithdrawalOrderView> requireWithdrawalCommand(
            String withdrawalNo,
            String idempotencyKey,
            WithdrawalReviewRequest request) {
        if (!StringUtils.hasText(withdrawalNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "WITHDRAWAL_NO_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || !StringUtils.hasText(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!StringUtils.hasText(request.action())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ACTION_REQUIRED");
        }
        return null;
    }

    private String nextReviewStatus(String status, String action) {
        String current = status == null ? "" : status.trim().toUpperCase(Locale.ROOT);
        return switch (action) {
            case "APPROVE" -> REVIEWABLE.contains(current) ? "PENDING_CHAIN" : null;
            case "DELAY" -> REVIEWABLE.contains(current) ? "DELAYED" : null;
            case "FREEZE" -> FINAL_STATUSES.contains(current) ? null : "FROZEN";
            case "UNFREEZE" -> "FROZEN".equals(current) ? "REVIEWING" : null;
            case "REJECT" -> REJECTABLE.contains(current) ? "REJECTED" : null;
            default -> null;
        };
    }

    private String normalizeParamKey(String key) {
        String normalized = key == null ? "" : key.trim();
        return switch (normalized) {
            case "dailyLimitCount", "balanceMaxRatio", "networkFee" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported withdrawal parameter");
        };
    }

    private String configKey(String key) {
        return switch (key) {
            case "dailyLimitCount" -> "withdrawal.daily_count_limit";
            case "balanceMaxRatio" -> "withdrawal.max_balance_pct";
            case "networkFee" -> "withdrawal.fee_rate";
            default -> throw new IllegalArgumentException("Unsupported withdrawal parameter");
        };
    }

    private BigDecimal currentParamValue(String key) {
        return switch (key) {
            case "dailyLimitCount" -> configDecimal("withdrawal.daily_count_limit", BigDecimal.ONE);
            case "balanceMaxRatio" -> configDecimal("withdrawal.max_balance_pct", new BigDecimal("0.80"));
            case "networkFee" -> configDecimal("withdrawal.fee_rate", new BigDecimal("0.02"));
            default -> throw new IllegalArgumentException("Unsupported withdrawal parameter");
        };
    }

    private BigDecimal normalizeParamValue(String key, String raw) {
        BigDecimal value = parseDecimal(raw);
        if ("dailyLimitCount".equals(key)) {
            int count = value.setScale(0, RoundingMode.DOWN).intValue();
            if (count < 1 || count > 10) {
                throw new IllegalArgumentException("Daily withdrawal count must be 1-10");
            }
            return BigDecimal.valueOf(count);
        }
        if ("balanceMaxRatio".equals(key)) {
            BigDecimal ratio = value.compareTo(BigDecimal.ONE) > 0
                    ? value.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                    : value;
            if (ratio.compareTo(new BigDecimal("0.50")) < 0 || ratio.compareTo(BigDecimal.ONE) > 0) {
                throw new IllegalArgumentException("Withdrawal balance ratio must be 50%-100%");
            }
            return ratio.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        }
        if ("networkFee".equals(key)) {
            BigDecimal rate = value.compareTo(BigDecimal.ONE) > 0
                    ? value.divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP)
                    : value;
            if (rate.compareTo(BigDecimal.ZERO) < 0 || rate.compareTo(new BigDecimal("0.05")) > 0) {
                throw new IllegalArgumentException("Withdrawal fee rate must be 0%-5%");
            }
            return rate.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
        }
        throw new IllegalArgumentException("Unsupported withdrawal parameter");
    }

    private boolean loosensWithdrawalControl(String key, BigDecimal oldValue, BigDecimal newValue) {
        return switch (key) {
            case "dailyLimitCount", "balanceMaxRatio" -> newValue.compareTo(oldValue) > 0;
            case "networkFee" -> newValue.compareTo(oldValue) < 0;
            default -> false;
        };
    }

    private BigDecimal parseDecimal(String raw) {
        if (!StringUtils.hasText(raw)) {
            throw new IllegalArgumentException("value is required");
        }
        try {
            return new BigDecimal(raw.trim().replace("%", "").replace(",", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("value is invalid", ex);
        }
    }

    private BigDecimal configDecimal(String key, BigDecimal fallback) {
        return configFacade.activeValue(key)
                .map(value -> {
                    try {
                        return new BigDecimal(value.trim());
                    } catch (RuntimeException ex) {
                        return fallback;
                    }
                })
                .orElse(fallback);
    }

    private boolean configBoolean(String key, boolean fallback) {
        return configFacade.activeValue(key)
                .map(value -> "true".equalsIgnoreCase(value) || "1".equals(value))
                .orElse(fallback);
    }

    private BigDecimal percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private void audit(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : null)
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }
}

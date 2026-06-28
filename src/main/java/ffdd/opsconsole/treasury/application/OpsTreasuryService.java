package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.dto.TreasuryInjectionRequest;
import ffdd.opsconsole.treasury.dto.TreasuryLedgerAdjustmentRequest;
import ffdd.opsconsole.treasury.dto.TreasuryLedgerQueryRequest;
import ffdd.opsconsole.treasury.dto.TreasuryScopeRequest;
import ffdd.opsconsole.treasury.dto.TreasuryThresholdRequest;
import ffdd.opsconsole.user.domain.UserSeedRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsTreasuryService {
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;
    private static final String RESERVE_CONFIG_KEY = "wallet.dual-ledger.reserve-usd";
    private static final String NEX_USD_RATE_CONFIG_KEY = "wallet.dual-ledger.nex-usd-rate";
    private static final String REDLINE_CONFIG_KEY = "wallet.dual-ledger.redline-pct";
    private static final String HEALTHY_CONFIG_KEY = "wallet.dual-ledger.healthy-pct";
    private static final String RUN_RISK_CONFIG_KEY = "wallet.dual-ledger.run-risk-pct";
    private static final String SCOPE_CONFIG_KEY = "wallet.dual-ledger.scope";
    private static final List<String> D_SEED_USER_KEYS = List.of(
            "usr_77D4", "usr_31E8", "usr_2231", "usr_55B1", "usr_8807");

    private final TreasuryLedgerRepository ledgerRepository;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final TreasuryDualLedgerProperties dualLedgerProperties;
    private final UserSeedRepository userSeedRepository;

    public ApiResult<Map<String, Object>> overview(int days) {
        ensureD3FallbackSeedData();
        int normalizedDays = normalizeDays(days);
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime since = now.minusDays(normalizedDays - 1L).toLocalDate().atStartOfDay();
        Map<String, Object> response = section(
                "service", "nexion-backend",
                "domain", "B",
                "days", normalizedDays,
                "startAt", since,
                "endAt", now);
        response.put("deposits", section(
                "total", ledgerRepository.countDeposits(since, null),
                "success", ledgerRepository.countDeposits(since, "SUCCESS"),
                "pending", ledgerRepository.countDeposits(since, "PENDING"),
                "dead", ledgerRepository.countDeposits(since, "DEAD")));
        response.put("withdrawals", section(
                "total", ledgerRepository.countWithdrawals(since, null),
                "pendingChain", ledgerRepository.countWithdrawals(since, "PENDING_CHAIN"),
                "chainSubmitted", ledgerRepository.countWithdrawals(since, "CHAIN_SUBMITTED"),
                "success", ledgerRepository.countWithdrawals(since, "SUCCESS"),
                "failed", ledgerRepository.countWithdrawals(since, "FAILED"),
                "dead", ledgerRepository.countWithdrawals(since, "DEAD")));
        response.put("exchanges", section(
                "total", ledgerRepository.countExchanges(since, null),
                "completed", ledgerRepository.countExchanges(since, "COMPLETED"),
                "reviewing", ledgerRepository.countExchanges(since, "REVIEWING"),
                "rejected", ledgerRepository.countExchanges(since, "REJECTED")));
        response.put("ledger", section(
                "total", ledgerRepository.countLedgers(since, null),
                "credits", ledgerRepository.countLedgers(since, "IN"),
                "debits", ledgerRepository.countLedgers(since, "OUT")));
        response.put("dualLedger", dualLedger().getData());
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> dualLedger() {
        ensureD3FallbackSeedData();
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime current24hStart = now.minusHours(24);
        LocalDateTime prev24hStart = now.minusHours(48);
        BigDecimal reserveUsd = configDecimal(RESERVE_CONFIG_KEY, dualLedgerProperties.getReserveUsd());
        BigDecimal nexUsdRate = configDecimal(NEX_USD_RATE_CONFIG_KEY, dualLedgerProperties.getNexUsdRate());
        BigDecimal redlinePct = configDecimal(REDLINE_CONFIG_KEY, dualLedgerProperties.getRedlinePct());
        BigDecimal healthyPct = configDecimal(HEALTHY_CONFIG_KEY, dualLedgerProperties.getHealthyPct());
        BigDecimal runRiskPct = configDecimal(RUN_RISK_CONFIG_KEY, dualLedgerProperties.getRunRiskPct());
        String scope = configValue(SCOPE_CONFIG_KEY, "all active liabilities");

        BigDecimal queueBacklogUsd = safe(ledgerRepository.sumActiveWithdrawalQueueUsdt());
        BigDecimal userBalanceUsd = safe(ledgerRepository.sumUsdtAvailable());
        BigDecimal pendingWithdrawUsd = safe(ledgerRepository.sumPendingWithdraw());
        BigDecimal stakingPrincipalUsd = safe(ledgerRepository.sumActiveStakingPrincipalUsdt());
        BigDecimal stakingInterestUsd = safe(ledgerRepository.sumActiveStakingInterestUsdt());
        BigDecimal nexLiabilityUsd = safe(ledgerRepository.sumNexAvailable())
                .add(safe(ledgerRepository.sumActiveNexLocked()))
                .add(safe(ledgerRepository.sumActiveNexReward()))
                .multiply(nexUsdRate);
        BigDecimal commissionCoolingUsd = safe(ledgerRepository.sumPendingCommissionUsdt());

        List<Map<String, Object>> accounts = List.of(
                account("balance", "withdrawable USDT balance", userBalanceUsd, "nx_user_wallet.usdt_available"),
                account("stake_principal", "USDT staking principal", stakingPrincipalUsd, "nx_staking_position.amount_usdt"),
                account("stake_interest", "accrued staking interest", stakingInterestUsd, "nx_staking_position.estimated_interest_usdt"),
                account("nex_payable", "NEX payable", nexLiabilityUsd, "NEX balances and active lock orders"),
                account("withdraw_queue", "active withdrawal queue", queueBacklogUsd, "nx_withdrawal_order active queue"),
                account("commission_cooling", "commission cooling balance", commissionCoolingUsd, "nx_wallet_ledger pending commission"),
                account("pending_withdraw", "wallet pending withdraw", pendingWithdrawUsd, "nx_user_wallet.pending_withdraw"));
        BigDecimal liabilitiesUsd = accounts.stream()
                .map(account -> (BigDecimal) account.get("amount"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal coverageRatio = pct(reserveUsd, liabilitiesUsd);
        BigDecimal netFlow24hUsd = safe(ledgerRepository.sumNetUsdtFlowBetween(current24hStart, now));
        BigDecimal prevNetFlow24hUsd = safe(ledgerRepository.sumNetUsdtFlowBetween(prev24hStart, current24hStart));
        BigDecimal avgRiskScore = safe(ledgerRepository.avgActiveWithdrawalQueueRiskScore()).setScale(0, RoundingMode.HALF_UP);

        Map<String, Object> response = section(
                "service", "nexion-backend",
                "domain", "B",
                "generatedAt", now,
                "sources", List.of("nx_user_wallet", "nx_wallet_ledger", "nx_withdrawal_order", "nx_staking_position", "nx_nex_lock_order", "nx_config_item"));
        response.put("snapshot", section(
                "reserveUsd", money(reserveUsd),
                "liabilitiesUsd", money(liabilitiesUsd),
                "coverageRatio", pctScale(coverageRatio),
                "redlinePct", pctScale(redlinePct),
                "healthyPct", pctScale(healthyPct),
                "runRiskPct", pctScale(runRiskPct),
                "redlineBreached", coverageRatio.compareTo(redlinePct) < 0,
                "netFlow24hUsd", money(netFlow24hUsd),
                "queueBacklogCount", ledgerRepository.countActiveWithdrawalQueue(),
                "queueBacklogUsd", money(queueBacklogUsd),
                "avgRiskScore", avgRiskScore.longValue(),
                "coverageSeries", coverageSeries(now, reserveUsd, liabilitiesUsd),
                "scope", scope));
        response.put("accounts", accounts.stream().map(this::scaleAccount).toList());
        response.put("maturity7d", maturity7d(queueBacklogUsd, stakingInterestUsd));
        response.put("prev", section(
                "reserveUsd", money(reserveUsd.subtract(prevNetFlow24hUsd)),
                "netFlow24hUsd", money(prevNetFlow24hUsd)));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> createInjection(String idempotencyKey, TreasuryInjectionRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.amount() == null || request.amount().compareTo(BigDecimal.ZERO) <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "AMOUNT_MUST_BE_POSITIVE");
        }
        if (!StringUtils.hasText(request.voucherNo())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "VOUCHER_NO_REQUIRED");
        }
        BigDecimal oldReserve = configDecimal(RESERVE_CONFIG_KEY, dualLedgerProperties.getReserveUsd());
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        BigDecimal newReserve = oldReserve.add(amount).setScale(2, RoundingMode.HALF_UP);
        configFacade.upsertAdminValue(
                RESERVE_CONFIG_KEY,
                newReserve.toPlainString(),
                "NUMBER",
                "wallet",
                "B1 treasury reserve injection");
        audit("B1_TREASURY_RESERVE_INJECTION", "TREASURY_RESERVE", request.voucherNo(), request.operator(), section(
                "voucherNo", request.voucherNo().trim(),
                "amount", amount,
                "oldReserveUsd", oldReserve,
                "newReserveUsd", newReserve,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = dualLedger().getData();
        response.put("injection", section(
                "voucherNo", request.voucherNo().trim(),
                "amount", amount,
                "oldReserveUsd", oldReserve,
                "newReserveUsd", newReserve));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateScope(String idempotencyKey, TreasuryScopeRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!StringUtils.hasText(request.scope())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SCOPE_REQUIRED");
        }
        String scope = request.scope().trim();
        configFacade.upsertAdminValue(SCOPE_CONFIG_KEY, scope, "STRING", "wallet", "B1 dual ledger scope");
        audit("B1_DUAL_LEDGER_SCOPE_CHANGED", "TREASURY_SCOPE", SCOPE_CONFIG_KEY, request.operator(), section(
                "scope", scope,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = dualLedger().getData();
        response.put("scopeUpdate", section("scope", scope));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> updateThresholds(String idempotencyKey, TreasuryThresholdRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        boolean hasRedline = request.redlinePct() != null;
        boolean hasHealthy = request.healthyPct() != null;
        boolean hasRunRisk = request.runRiskPct() != null;
        if (!hasRedline && !hasHealthy && !hasRunRisk) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "THRESHOLD_REQUIRED");
        }

        BigDecimal redline = hasRedline
                ? threshold(request.redlinePct(), "redlinePct", BigDecimal.ZERO, BigDecimal.valueOf(200))
                : configDecimal(REDLINE_CONFIG_KEY, dualLedgerProperties.getRedlinePct());
        BigDecimal healthy = hasHealthy
                ? threshold(request.healthyPct(), "healthyPct", BigDecimal.ZERO, BigDecimal.valueOf(250))
                : configDecimal(HEALTHY_CONFIG_KEY, dualLedgerProperties.getHealthyPct());
        BigDecimal runRisk = hasRunRisk
                ? threshold(request.runRiskPct(), "runRiskPct", BigDecimal.ZERO, BigDecimal.valueOf(100))
                : configDecimal(RUN_RISK_CONFIG_KEY, dualLedgerProperties.getRunRiskPct());
        if (redline.compareTo(healthy) > 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REDLINE_MUST_NOT_EXCEED_HEALTHY");
        }

        Map<String, Object> changed = new LinkedHashMap<>();
        if (hasRedline) {
            configFacade.upsertAdminValue(REDLINE_CONFIG_KEY, redline.toPlainString(), "NUMBER", "wallet", "B1 coverage redline threshold");
            changed.put("redlinePct", redline);
        }
        if (hasHealthy) {
            configFacade.upsertAdminValue(HEALTHY_CONFIG_KEY, healthy.toPlainString(), "NUMBER", "wallet", "B1 coverage healthy threshold");
            changed.put("healthyPct", healthy);
        }
        if (hasRunRisk) {
            configFacade.upsertAdminValue(RUN_RISK_CONFIG_KEY, runRisk.toPlainString(), "NUMBER", "wallet", "B1 run-risk threshold");
            changed.put("runRiskPct", runRisk);
        }

        audit("B1_DUAL_LEDGER_THRESHOLDS_CHANGED", "DUAL_LEDGER_THRESHOLDS", "B1", request.operator(), section(
                "changed", changed,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        Map<String, Object> response = dualLedger().getData();
        response.put("thresholdUpdate", changed);
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<TreasuryLedgerBillView>> ledgerBills(TreasuryLedgerQueryRequest request) {
        ensureD4FallbackSeedData();
        int pageNum = clamp(request == null || request.pageNum() == null ? 1 : request.pageNum(), 1, 10_000);
        int pageSize = clamp(request == null || request.pageSize() == null ? 20 : request.pageSize(), 1, 100);
        String type = normalizeLedgerType(request == null ? null : request.type());
        Long userId = request == null ? null : request.userId();
        String keyword = trimToNull(request == null ? null : request.keyword());
        long total = ledgerRepository.countLedgerBills(type, userId, keyword);
        int offset = (pageNum - 1) * pageSize;
        List<TreasuryLedgerBillView> rows = ledgerRepository.pageLedgerBills(type, userId, keyword, pageSize, offset);
        return ApiResult.ok(new PageResult<>(total, pageNum, pageSize, rows));
    }

    public ApiResult<Map<String, Object>> userLedger(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        ensureD4FallbackSeedData();
        List<TreasuryLedgerBillView> rows = ledgerRepository.userLedgerRows(userId, 50);
        String userNo = rows.stream()
                .map(TreasuryLedgerBillView::userNo)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(formatUserNo(userId));
        String nickname = rows.stream()
                .map(TreasuryLedgerBillView::nickname)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse(null);
        Map<String, BigDecimal> sums = new LinkedHashMap<>();
        for (TreasuryLedgerBillView row : rows) {
            BigDecimal signed = isCreditDirection(row.direction()) ? safe(row.amount()) : safe(row.amount()).negate();
            sums.merge(row.asset(), signed, BigDecimal::add);
        }
        Map<String, Object> response = section(
                "userId", userId,
                "userNo", userNo,
                "nickname", nickname,
                "rows", rows,
                "sums", sums,
                "currentUsdtBalance", ledgerRepository.currentUserBalance(userId, "USDT").orElse(BigDecimal.ZERO),
                "currentNexBalance", ledgerRepository.currentUserBalance(userId, "NEX").orElse(BigDecimal.ZERO),
                "sources", List.of("nx_wallet_ledger"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> createLedgerAdjustment(String idempotencyKey, TreasuryLedgerAdjustmentRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.userId() == null || request.userId() <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        String asset;
        String direction;
        BigDecimal amount;
        try {
            asset = normalizeAsset(request.asset());
            direction = normalizeAdjustmentDirection(request.direction());
            amount = positiveAmount(request.amount());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        String adjustmentNo = "ADJ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        String relatedBizNo = trimToNull(request.relatedBizNo());
        String operator = trimToNull(request.operator());
        if (operator == null) {
            return ApiResult.fail(OpsErrorCode.OPERATOR_REQUIRED.httpStatus(), OpsErrorCode.OPERATOR_REQUIRED.name());
        }
        ledgerRepository.createLedgerAdjustment(
                adjustmentNo,
                request.userId(),
                asset,
                direction,
                amount,
                relatedBizNo,
                request.reason().trim(),
                operator);
        audit("D4_LEDGER_ADJUSTMENT_CREATED", "WALLET_ASSET_ADJUSTMENT", adjustmentNo, operator, section(
                "userId", request.userId(),
                "asset", asset,
                "direction", direction,
                "amount", amount,
                "relatedBizNo", relatedBizNo,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(section(
                "adjustmentNo", adjustmentNo,
                "userId", request.userId(),
                "asset", asset,
                "direction", direction,
                "amount", amount,
                "status", "PENDING_REVIEW",
                "ledgerPosting", "deferred-to-review",
                "relatedBizNo", relatedBizNo));
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

    private void audit(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : null)
                .riskLevel("HIGH")
                .result("SUCCESS")
                .detail(detail)
                .build());
    }

    private void ensureD3FallbackSeedData() {
        ensureD3ConfigDefaults();
        if (treasuryLiabilitiesPresent()) {
            return;
        }
        ledgerRepository.seedD4FallbackData(seedUserIds());
    }

    private void ensureD4FallbackSeedData() {
        ensureD3ConfigDefaults();
        if (ledgerRepository.countLedgerBills(null, null, null) > 0) {
            return;
        }
        ledgerRepository.seedD4FallbackData(seedUserIds());
    }

    private boolean treasuryLiabilitiesPresent() {
        return safe(ledgerRepository.sumUsdtAvailable()).compareTo(BigDecimal.ZERO) > 0
                || safe(ledgerRepository.sumPendingWithdraw()).compareTo(BigDecimal.ZERO) > 0
                || safe(ledgerRepository.sumNexAvailable()).compareTo(BigDecimal.ZERO) > 0
                || safe(ledgerRepository.sumActiveWithdrawalQueueUsdt()).compareTo(BigDecimal.ZERO) > 0
                || ledgerRepository.countActiveWithdrawalQueue() > 0
                || safe(ledgerRepository.sumPendingCommissionUsdt()).compareTo(BigDecimal.ZERO) > 0
                || ledgerRepository.countLedgerBills(null, null, null) > 0;
    }

    private Map<String, Long> seedUserIds() {
        boolean missing = D_SEED_USER_KEYS.stream()
                .anyMatch(key -> userSeedRepository.findUserIdByLookupKey(key).isEmpty());
        if (missing) {
            userSeedRepository.upsertAccountActionSeeds();
            userSeedRepository.upsertKycLedgerSeeds();
        }
        Map<String, Long> ids = new LinkedHashMap<>();
        for (String key : D_SEED_USER_KEYS) {
            userSeedRepository.findUserIdByLookupKey(key).ifPresent(id -> ids.put(key, id));
        }
        return ids;
    }

    private void ensureD3ConfigDefaults() {
        seedConfigIfAbsent(RESERVE_CONFIG_KEY, money(dualLedgerProperties.getReserveUsd()).toPlainString(), "NUMBER", "wallet", "D3 treasury reserve");
        seedConfigIfAbsent(NEX_USD_RATE_CONFIG_KEY, safe(dualLedgerProperties.getNexUsdRate()).toPlainString(), "NUMBER", "wallet", "D3 NEX USD rate");
        seedConfigIfAbsent(REDLINE_CONFIG_KEY, safe(dualLedgerProperties.getRedlinePct()).toPlainString(), "NUMBER", "wallet", "D3 coverage redline");
        seedConfigIfAbsent(HEALTHY_CONFIG_KEY, safe(dualLedgerProperties.getHealthyPct()).toPlainString(), "NUMBER", "wallet", "D3 healthy coverage");
        seedConfigIfAbsent(RUN_RISK_CONFIG_KEY, safe(dualLedgerProperties.getRunRiskPct()).toPlainString(), "NUMBER", "wallet", "D3 run risk threshold");
        seedConfigIfAbsent(SCOPE_CONFIG_KEY, "all active liabilities", "STRING", "wallet", "D3 dual-ledger scope");
    }

    private void seedConfigIfAbsent(String key, String value, String type, String group, String remark) {
        if (configFacade.activeValue(key).filter(StringUtils::hasText).isPresent()) {
            return;
        }
        configFacade.upsertAdminValue(key, value, type, group, remark);
    }

    private int normalizeDays(int days) {
        if (days < 1) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private String normalizeLedgerType(String value) {
        String normalized = trimToNull(value);
        if (normalized == null || "all".equalsIgnoreCase(normalized)) {
            return null;
        }
        return normalized.toUpperCase(Locale.ROOT);
    }

    private boolean isCreditDirection(String direction) {
        return "IN".equalsIgnoreCase(direction) || "CREDIT".equalsIgnoreCase(direction);
    }

    private String formatUserNo(Long userId) {
        return "U%08d".formatted(userId == null ? 0 : userId);
    }

    private String normalizeAsset(String value) {
        String normalized = requireText(value, "ASSET_REQUIRED").toUpperCase(Locale.ROOT);
        if (normalized.contains("POINT")) {
            throw new IllegalArgumentException("Points system is sunset");
        }
        if (!List.of("USDT", "NEX").contains(normalized)) {
            throw new IllegalArgumentException("Unsupported asset");
        }
        return normalized;
    }

    private String normalizeAdjustmentDirection(String value) {
        String normalized = requireText(value, "DIRECTION_REQUIRED").toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "IN", "CREDIT" -> "CREDIT";
            case "OUT", "DEBIT" -> "DEBIT";
            default -> throw new IllegalArgumentException("Unsupported adjustment direction");
        };
    }

    private BigDecimal positiveAmount(BigDecimal value) {
        if (value == null || value.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Amount must be positive");
        }
        return value.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private BigDecimal threshold(BigDecimal value, String fieldName, BigDecimal min, BigDecimal max) {
        BigDecimal normalized = safe(value).setScale(1, RoundingMode.HALF_UP);
        if (normalized.compareTo(min) <= 0 || normalized.compareTo(max) > 0) {
            throw new IllegalArgumentException(fieldName + " is out of range");
        }
        return normalized;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private BigDecimal configDecimal(String key, BigDecimal fallback) {
        return configFacade.activeValue(key)
                .map(value -> parseDecimal(value, fallback))
                .orElse(safe(fallback));
    }

    private String configValue(String key, String fallback) {
        return configFacade.activeValue(key).filter(StringUtils::hasText).orElse(fallback);
    }

    private BigDecimal parseDecimal(String value, BigDecimal fallback) {
        try {
            return new BigDecimal(value.trim());
        } catch (RuntimeException ex) {
            return safe(fallback);
        }
    }

    private BigDecimal pct(BigDecimal numerator, BigDecimal denominator) {
        if (denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return numerator.compareTo(BigDecimal.ZERO) > 0 ? BigDecimal.valueOf(100) : BigDecimal.ZERO;
        }
        return numerator.multiply(BigDecimal.valueOf(100)).divide(denominator, 6, RoundingMode.HALF_UP);
    }

    private List<BigDecimal> coverageSeries(LocalDateTime now, BigDecimal reserveUsd, BigDecimal liabilitiesUsd) {
        List<BigDecimal> series = new ArrayList<>();
        LocalDateTime cursor = now.minusHours(24);
        BigDecimal runningReserve = reserveUsd;
        for (int i = 0; i < 8; i++) {
            LocalDateTime next = cursor.plusHours(3);
            runningReserve = runningReserve.add(safe(ledgerRepository.sumNetUsdtFlowBetween(cursor, next)));
            series.add(pctScale(pct(runningReserve, liabilitiesUsd)));
            cursor = next;
        }
        return series;
    }

    private List<Map<String, Object>> maturity7d(BigDecimal queueBacklogUsd, BigDecimal stakingInterestUsd) {
        List<Map<String, Object>> rows = new ArrayList<>();
        BigDecimal withdrawDaily = safe(queueBacklogUsd).divide(BigDecimal.valueOf(7), 6, RoundingMode.HALF_UP);
        BigDecimal interestDaily = safe(stakingInterestUsd).divide(BigDecimal.valueOf(7), 6, RoundingMode.HALF_UP);
        for (int i = 0; i < 7; i++) {
            rows.add(section(
                    "day", "D+" + (i + 1),
                    "withdrawUsd", money(withdrawDaily),
                    "interestUsd", money(interestDaily)));
        }
        return rows;
    }

    private Map<String, Object> account(String key, String label, BigDecimal amount, String source) {
        return section("key", key, "label", label, "amount", safe(amount), "source", source);
    }

    private Map<String, Object> scaleAccount(Map<String, Object> account) {
        Map<String, Object> scaled = new LinkedHashMap<>(account);
        scaled.put("amount", money((BigDecimal) account.get("amount")));
        return scaled;
    }

    private BigDecimal money(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal pctScale(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private Map<String, Object> section(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Object value = pairs[i + 1];
            if (value != null) {
                detail.put(String.valueOf(pairs[i]), value);
            }
        }
        return detail;
    }
}

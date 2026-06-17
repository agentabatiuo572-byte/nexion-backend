package ffdd.opsconsole.treasury.application;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.dto.TreasuryInjectionRequest;
import ffdd.opsconsole.treasury.dto.TreasuryScopeRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.util.StringUtils;

@ApplicationService
public class OpsTreasuryService {
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;
    private static final String RESERVE_CONFIG_KEY = "wallet.dual-ledger.reserve-usd";
    private static final String NEX_USD_RATE_CONFIG_KEY = "wallet.dual-ledger.nex-usd-rate";
    private static final String REDLINE_CONFIG_KEY = "wallet.dual-ledger.redline-pct";
    private static final String HEALTHY_CONFIG_KEY = "wallet.dual-ledger.healthy-pct";
    private static final String SCOPE_CONFIG_KEY = "wallet.dual-ledger.scope";

    private final TreasuryLedgerRepository ledgerRepository;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final Clock clock;
    private final BigDecimal fallbackReserveUsd;
    private final BigDecimal fallbackNexUsdRate;
    private final BigDecimal fallbackRedlinePct;
    private final BigDecimal fallbackHealthyPct;

    public OpsTreasuryService(
            TreasuryLedgerRepository ledgerRepository,
            PlatformConfigFacade configFacade,
            AuditLogService auditLogService,
            @Value("${nexion.wallet.dual-ledger.reserve-usd:5000}") BigDecimal fallbackReserveUsd,
            @Value("${nexion.wallet.dual-ledger.nex-usd-rate:0.17}") BigDecimal fallbackNexUsdRate,
            @Value("${nexion.wallet.dual-ledger.redline-pct:85}") BigDecimal fallbackRedlinePct,
            @Value("${nexion.wallet.dual-ledger.healthy-pct:100}") BigDecimal fallbackHealthyPct) {
        this(
                ledgerRepository,
                configFacade,
                auditLogService,
                Clock.systemDefaultZone(),
                fallbackReserveUsd,
                fallbackNexUsdRate,
                fallbackRedlinePct,
                fallbackHealthyPct);
    }

    OpsTreasuryService(
            TreasuryLedgerRepository ledgerRepository,
            PlatformConfigFacade configFacade,
            AuditLogService auditLogService,
            Clock clock,
            BigDecimal fallbackReserveUsd,
            BigDecimal fallbackNexUsdRate,
            BigDecimal fallbackRedlinePct,
            BigDecimal fallbackHealthyPct) {
        this.ledgerRepository = ledgerRepository;
        this.configFacade = configFacade;
        this.auditLogService = auditLogService;
        this.clock = clock;
        this.fallbackReserveUsd = safe(fallbackReserveUsd);
        this.fallbackNexUsdRate = safe(fallbackNexUsdRate);
        this.fallbackRedlinePct = safe(fallbackRedlinePct);
        this.fallbackHealthyPct = safe(fallbackHealthyPct);
    }

    public ApiResult<Map<String, Object>> overview(int days) {
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
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime current24hStart = now.minusHours(24);
        LocalDateTime prev24hStart = now.minusHours(48);
        BigDecimal reserveUsd = configDecimal(RESERVE_CONFIG_KEY, fallbackReserveUsd);
        BigDecimal nexUsdRate = configDecimal(NEX_USD_RATE_CONFIG_KEY, fallbackNexUsdRate);
        BigDecimal redlinePct = configDecimal(REDLINE_CONFIG_KEY, fallbackRedlinePct);
        BigDecimal healthyPct = configDecimal(HEALTHY_CONFIG_KEY, fallbackHealthyPct);
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
        BigDecimal oldReserve = configDecimal(RESERVE_CONFIG_KEY, fallbackReserveUsd);
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

    private int normalizeDays(int days) {
        if (days < 1) {
            return DEFAULT_DAYS;
        }
        return Math.min(days, MAX_DAYS);
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

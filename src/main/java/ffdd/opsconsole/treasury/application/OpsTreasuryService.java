package ffdd.opsconsole.treasury.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.emergency.domain.KillSwitchState;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.RiskTamperSignalFacade;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.dto.TreasuryAlertAckRequest;
import ffdd.opsconsole.treasury.dto.TreasuryForecastConfigRequest;
import ffdd.opsconsole.treasury.dto.TreasuryInjectionRequest;
import ffdd.opsconsole.treasury.dto.TreasuryLedgerQueryRequest;
import ffdd.opsconsole.treasury.dto.TreasuryScopeRequest;
import ffdd.opsconsole.treasury.dto.TreasuryThresholdRequest;
import ffdd.opsconsole.treasury.dto.BankRunThresholdRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;

@ApplicationService
@RequiredArgsConstructor
public class OpsTreasuryService {
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;
    private static final String REDLINE_CONFIG_KEY = "wallet.dual-ledger.redline-pct";
    private static final String HEALTHY_CONFIG_KEY = "wallet.dual-ledger.healthy-pct";
    private static final String RUN_RISK_CONFIG_KEY = "wallet.dual-ledger.run-risk-pct";
    private static final String BANK_RUN_YELLOW_CONFIG_KEY = BankRunThresholdPolicy.YELLOW_CONFIG_KEY;
    private static final String SCOPE_CONFIG_KEY = "wallet.dual-ledger.scope";
    private static final String D3_FORECAST_CONFIG_KEY = "treasury.d3.forecast-config";
    private static final String D3_FORECAST_CONFIG_PENDING_KEY = "treasury.d3.forecast-config.pending";
    private static final String D3_FORECAST_CONFIG_PENDING_EFFECTIVE_AT_KEY = "treasury.d3.forecast-config.pending-effective-at";
    private static final String D3_FORECAST_CONFIG_VERSION_KEY = "treasury.d3.forecast-config.version";
    private static final String D3_FORECAST_CONFIG_ACTIVE_VERSION_KEY = "treasury.d3.forecast-config.active-version";
    private static final String D3_FORECAST_CONFIG_PENDING_VERSION_KEY = "treasury.d3.forecast-config.pending-version";
    private static final String D3_WITHDRAW_COOLDOWN_DAYS_KEY = "growth.phase.withdraw_cooldown_days";
    private static final String D3_WITHDRAW_COOLDOWN_DAYS_LEGACY_KEY = "wallet.withdrawal.cooldown_days";
    private static final String D3_FORECAST_CONFIG_IDEMPOTENCY_SCOPE = "D3_FORECAST_CONFIG_UPDATE";
    private static final String D3_RESERVE_INJECTION_IDEMPOTENCY_SCOPE = "D3_RESERVE_INJECTION";
    private static final List<String> D4_BILL_TYPE_ORDER = List.of(
            "swap", "topup", "withdraw", "earning", "commission", "refund", "bonus");
    private static final Set<String> D4_BILL_TYPES = Set.copyOf(D4_BILL_TYPE_ORDER);
    private static final List<String> D4_ASSET_ORDER = List.of("USDT", "NEX");
    private static final int D4_MAX_AUDIT_ROWS = 100_000;
    private static final List<String> D3_RESERVE_CATEGORIES = List.of("usdt", "otherLiquid");
    private static final List<String> D3_LIABILITY_CATEGORIES = List.of(
            "withdrawable_balance", "usdt_staking_principal", "staking_interest", "genesis_daily_emission",
            "nex_v2_future", "withdrawal_queue", "commission_cooling", "lock_other");
    private static final String B_ALERT_COVERAGE_ID = "coverage-redline";
    private static final String B_ALERT_COVERAGE_ACK_KEY = "wallet.dual-ledger.alert.coverage-redline.ack";
    private static final String B_ALERT_ACK_IDEMPOTENCY_SCOPE = "TREASURY_B_ALERT_ACK";
    private static final String B_SCOPE_IDEMPOTENCY_SCOPE = "TREASURY_B_SCOPE_UPDATE";
    private static final String B_THRESHOLD_IDEMPOTENCY_SCOPE = "TREASURY_B_THRESHOLD_UPDATE";
    private static final String B_BANK_RUN_THRESHOLD_IDEMPOTENCY_SCOPE = "TREASURY_B_BANKRUN_THRESHOLD";
    private static final int B5_K5_RECENT_ALERT_LIMIT = 5;
    private static final List<GateSeed> B_RISK_GATE_SEEDS = List.of(
            new GateSeed("withdraw", "提现闸"),
            new GateSeed("exchange", "兑换闸"),
            new GateSeed("staking", "算力质押闸"),
            new GateSeed("genesis", "Genesis 闸"),
            new GateSeed("trial", "试用闸"));
    private final TreasuryLedgerRepository ledgerRepository;
    private final PlatformConfigFacade configFacade;
    private final EmergencyControlRepository emergencyRepository;
    private final RiskTamperSignalFacade riskTamperSignalFacade;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final EventOutboxService eventOutboxService;
    private final Clock clock;
    private final TreasuryDualLedgerProperties dualLedgerProperties;
    private final ObjectMapper objectMapper;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

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

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> dualLedger() {
        ensureD3FallbackSeedData();
        return ApiResult.ok(dualLedgerSnapshot());
    }

    /** B1 canonical coverage read reused by L3; no BI-side recomputation. */
    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings("unchecked")
    public ApiResult<Map<String, Object>> coverage() {
        ensureD3FallbackSeedData();
        Map<String, Object> dualLedger = dualLedgerSnapshot();
        Map<String, Object> snapshot = (Map<String, Object>) dualLedger.getOrDefault("snapshot", Map.of());
        BigDecimal reserve = money(decimal(snapshot.get("reserveUsd")));
        BigDecimal liabilities = money(decimal(snapshot.get("liabilitiesUsd")));
        List<BigDecimal> ratios = (List<BigDecimal>) snapshot.getOrDefault("coverageSeries", List.of());
        LocalDateTime asOf = LocalDateTime.now(clock);
        List<Map<String, Object>> series = new ArrayList<>();
        for (int index = 0; index < ratios.size(); index++) {
            int hoursAgo = (ratios.size() - index - 1) * 3;
            series.add(section(
                    "period", asOf.minusHours(hoursAgo).toString(),
                    "coverageRatio", pctScale(ratios.get(index))));
        }
        return ApiResult.ok(section(
                "reserveTotalUsdt", reserve,
                "liabilityTotalUsdt", liabilities,
                "coverageRatio", pctScale(decimal(snapshot.get("coverageRatio"))),
                "netExposureUsdt", money(reserve.subtract(liabilities)),
                "redLine", pctScale(decimal(snapshot.get("redlinePct"))),
                "yellowLine", pctScale(decimal(snapshot.get("healthyPct"))),
                "redlineBreached", Boolean.TRUE.equals(snapshot.get("redlineBreached")),
                "series", series,
                "breaches", List.of(),
                "asOf", asOf,
                "source", "B1 双账本"));
    }

    /** D3 canonical reserve source. Coverage is intentionally absent: B1 owns that decision. */
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> reserve() {
        LocalDateTime now = LocalDateTime.now(clock);
        BigDecimal ledgerReserve = safe(ledgerRepository.currentReserveUsd());
        BigDecimal lockedPrincipal = safe(ledgerRepository.sumActiveStakingPrincipalUsdt());
        BigDecimal injected = safe(ledgerRepository.injectedCumulativeUsd());
        BigDecimal reserveTotal = ledgerReserve.subtract(lockedPrincipal).max(BigDecimal.ZERO);
        Map<String, Object> response = section(
                "usdtReserveUsdt", money(reserveTotal),
                "otherLiquidUsdt", money(BigDecimal.ZERO),
                "injectedCumulativeUsdt", money(injected),
                "lockedStakingPrincipalDeductedUsdt", money(lockedPrincipal),
                "reserveTotalUsdt", money(reserveTotal),
                "asOf", now,
                "waterLevel", waterLevel(reserveTotal));
        response.put("sources", List.of("nx_treasury_reserve_ledger", "nx_staking_position.amount_usdt"));
        return ApiResult.ok(response);
    }

    /** D3 canonical eight-category liability breakdown used as B1/B2 input. */
    public ApiResult<Map<String, Object>> liabilities(boolean breakdown) {
        BigDecimal nexRate = ledgerRepository.latestNexUsdtPrice().map(this::safe).orElse(BigDecimal.ZERO);
        List<Map<String, Object>> rows = List.of(
                liability("withdrawable_balance", "可提余额", ledgerRepository.sumUsdtAvailable(), "nx_user_wallet.usdt_available"),
                liability("usdt_staking_principal", "USDT staking 本金", ledgerRepository.sumActiveStakingPrincipalUsdt(), "nx_staking_position.amount_usdt"),
                liability("staking_interest", "staking 应付利息", ledgerRepository.sumActiveStakingInterestUsdt(), "nx_staking_position.estimated_interest_usdt"),
                liability("genesis_daily_emission", "Genesis 排放承诺", ledgerRepository.genesisDailyLiabilityUsd(), "nx_genesis_holding × nx_genesis_series.daily_dividend_rate_pct"),
                liability("nex_v2_future", "NEX v2 未来兑付", safe(ledgerRepository.sumActiveNexLocked()).add(safe(ledgerRepository.sumActiveNexReward())).multiply(nexRate), "nx_nex_lock_order active maturity"),
                liability("withdrawal_queue", "待提现 queue", ledgerRepository.sumActiveWithdrawalQueueUsdt(), "nx_withdrawal_order active queue"),
                liability("commission_cooling", "佣金冷却未解锁", ledgerRepository.sumPendingCommissionUsdt(), "nx_wallet_ledger pending commission"),
                liability("lock_other", "锁仓本息其他", ledgerRepository.legacyLockOtherLiabilityUsd(), "nx_treasury_legacy_lock_liability active principal + accrued interest"));
        BigDecimal total = rows.stream().map(row -> (BigDecimal) row.get("amountUsdt")).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> withShare = rows.stream().map(row -> {
            Map<String, Object> next = new LinkedHashMap<>(row);
            next.put("share", total.signum() == 0 ? BigDecimal.ZERO.setScale(4) :
                    ((BigDecimal) row.get("amountUsdt")).divide(total, 6, RoundingMode.HALF_UP).setScale(4, RoundingMode.HALF_UP));
            return next;
        }).toList();
        Map<String, Object> response = section(
                "totalUsdt", money(total),
                "hardLiabilityCategoryCount", 8,
                "trialShadowIncluded", false,
                "asOf", LocalDateTime.now(clock));
        if (breakdown) {
            response.put("breakdown", withShare);
        }
        response.put("sources", List.of("nx_user_wallet", "nx_staking_position", "nx_nex_lock_order", "nx_withdrawal_order", "nx_wallet_ledger", "nx_genesis_holding", "nx_treasury_legacy_lock_liability"));
        return ApiResult.ok(response);
    }

    @SuppressWarnings("unchecked")
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> maturityForecast(String window) {
        int days = d3WindowDays(window, Set.of(7, 30));
        LocalDate start = LocalDateTime.now(clock).toLocalDate();
        LocalDateTime startAt = start.atStartOfDay();
        Map<String, Object> config = forecastConfigSnapshot();
        String interestMode = String.valueOf(config.get("stakingInterestMode"));
        Map<String, Map<String, Object>> buckets = new LinkedHashMap<>();
        for (Map<String, Object> row : ledgerRepository.maturityBuckets(
                startAt, startAt.plusDays(days), d3WithdrawCooldownDays(), interestMode)) {
            buckets.put(String.valueOf(row.get("day")), row);
        }
        Map<String, BigDecimal> trialBuckets = new LinkedHashMap<>();
        if (Boolean.TRUE.equals(config.get("trialStressEnabled"))) {
            for (Map<String, Object> row : ledgerRepository.trialStressBuckets(startAt, startAt.plusDays(days))) {
                trialBuckets.put(String.valueOf(row.get("day")), money(decimal(row.get("amountUsdt"))));
            }
        }
        boolean withdrawalIncluded = categoryEnabled(config, "liabilityCategories", "withdrawal_queue");
        boolean interestIncluded = categoryEnabled(config, "liabilityCategories", "staking_interest");
        boolean genesisIncluded = Boolean.TRUE.equals(config.get("genesisIncluded"))
                && categoryEnabled(config, "liabilityCategories", "genesis_daily_emission");
        BigDecimal genesisDaily = genesisIncluded ? safe(ledgerRepository.genesisDailyLiabilityUsd()) : BigDecimal.ZERO;
        List<Map<String, Object>> daily = new ArrayList<>();
        List<Map<String, Object>> cumulative = new ArrayList<>();
        BigDecimal running = BigDecimal.ZERO;
        for (int i = 0; i < days; i++) {
            String date = start.plusDays(i).toString();
            Map<String, Object> bucket = buckets.getOrDefault(date, Map.of());
            BigDecimal withdraw = withdrawalIncluded ? money(decimal(bucket.get("withdrawUsd"))) : BigDecimal.ZERO.setScale(2);
            BigDecimal interest = interestIncluded ? money(decimal(bucket.get("interestUsd"))) : BigDecimal.ZERO.setScale(2);
            BigDecimal trialStress = trialBuckets.getOrDefault(date, BigDecimal.ZERO.setScale(2));
            BigDecimal total = withdraw.add(interest).add(genesisDaily).add(trialStress);
            running = running.add(total);
            daily.add(section("date", date, "withdrawDueUsdt", withdraw, "interestDueUsdt", interest,
                    "genesisDividendUsdt", money(genesisDaily), "trialShadowStressUsdt", money(trialStress),
                    "totalDueUsdt", money(total)));
            cumulative.add(section("date", date, "amountUsdt", money(running)));
        }
        BigDecimal reserveTotal = configuredReserveTotal(config);
        BigDecimal average = days == 0 ? BigDecimal.ZERO : running.divide(BigDecimal.valueOf(days), 6, RoundingMode.HALF_UP);
        return ApiResult.ok(section(
                "window", days + "d",
                "daily", daily,
                "cumulative", cumulative,
                "cumulativeUsdt", money(running),
                "reserveCoverDays", reserveCoverDays(reserveTotal, average),
                "farLiabilityExcluded", !Boolean.TRUE.equals(config.get("includeFarLiabilities")),
                "farLiabilityNote", "NEX v2 到期额超出预测窗口(24 月锁期)，不在图内",
                "trialStressIncluded", Boolean.TRUE.equals(config.get("trialStressEnabled")),
                "stakingInterestMode", interestMode,
                "withdrawCooldownDays", d3WithdrawCooldownDays(),
                "appliedReserveCategories", config.get("reserveCategories"),
                "appliedLiabilityCategories", config.get("liabilityCategories"),
                "asOf", LocalDateTime.now(clock)));
    }

    @SuppressWarnings("unchecked")
    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> netExposure(String window) {
        int days = d3WindowDays(window, Set.of(7, 30, 90));
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDate today = now.toLocalDate();
        Map<String, Object> config = forecastConfigSnapshot();
        BigDecimal reserveTotal = configuredReserveTotal(config);
        List<Map<String, Object>> liabilityRows = (List<Map<String, Object>>) liabilities(true).getData().get("breakdown");
        BigDecimal liabilityTotal = liabilityRows.stream()
                .filter(row -> categoryEnabled(config, "liabilityCategories", String.valueOf(row.get("category"))))
                .filter(row -> Boolean.TRUE.equals(config.get("genesisIncluded"))
                        || !"genesis_daily_emission".equals(row.get("category")))
                .filter(row -> Boolean.TRUE.equals(config.get("includeFarLiabilities"))
                        || !"nex_v2_future".equals(row.get("category")))
                .map(row -> (BigDecimal) row.get("amountUsdt"))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal currentNet = reserveTotal.subtract(liabilityTotal);
        List<Map<String, Object>> series = new ArrayList<>();
        BigDecimal flowAfterDay = BigDecimal.ZERO;
        for (int offset = 0; offset < days; offset++) {
            LocalDate day = today.minusDays(offset);
            if (offset == 1) {
                flowAfterDay = flowAfterDay.add(safe(ledgerRepository.sumNetUsdtFlowBetween(today.atStartOfDay(), now)));
            } else if (offset > 1) {
                LocalDate laterDay = day.plusDays(1);
                flowAfterDay = flowAfterDay.add(safe(ledgerRepository.sumNetUsdtFlowBetween(
                        laterDay.atStartOfDay(), laterDay.plusDays(1).atStartOfDay())));
            }
            BigDecimal historicalReserve = reserveTotal.subtract(flowAfterDay);
            BigDecimal net = historicalReserve.subtract(liabilityTotal);
            series.add(0, section("date", day.toString(), "reserveUsdt", money(historicalReserve),
                    "liabilitiesUsdt", money(liabilityTotal), "netExposureUsdt", money(net), "negative", net.signum() < 0));
        }
        return ApiResult.ok(section("window", days + "d", "series", series,
                "appliedReserveCategories", config.get("reserveCategories"),
                "appliedLiabilityCategories", config.get("liabilityCategories"),
                "farLiabilitiesIncluded", config.get("includeFarLiabilities"),
                "historyMethod", "current reserve rolled backward by cumulative canonical ledger flow; active liability snapshot held constant",
                "currentNetExposureUsdt", money(currentNet),
                "asOf", LocalDateTime.now(clock)));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> forecastConfig() {
        Map<String, Object> response = new LinkedHashMap<>(forecastConfigSnapshot());
        response.put("version", configVersion(D3_FORECAST_CONFIG_VERSION_KEY));
        response.put("effectiveVersion", configVersion(D3_FORECAST_CONFIG_ACTIVE_VERSION_KEY));
        response.put("effectiveRule", "NEXT_UTC_DAY_00:00");
        pendingForecastConfig().ifPresent(pending -> {
            response.put("pendingConfig", pending.config());
            response.put("pendingEffectiveAt", pending.effectiveAt());
            response.put("pendingVersion", configVersion(D3_FORECAST_CONFIG_PENDING_VERSION_KEY));
        });
        return ApiResult.ok(response);
    }

    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<Map<String, Object>> updateForecastConfig(String idempotencyKey, TreasuryForecastConfigRequest request) {
        requireD3Command(idempotencyKey, request == null ? null : request.reason());
        if (request == null || request.expectedVersion() == null || request.expectedVersion() < 0) {
            throw new BizException(400, "D3_EXPECTED_VERSION_REQUIRED");
        }
        String operator = AdminActorResolver.resolve(request.operator());
        String reason = request.reason().trim();
        String requestHash = structuredRequestHash(section(
                "reserveCategories", request.reserveCategories(),
                "liabilityCategories", request.liabilityCategories(),
                "forecastWindow", request.forecastWindow(),
                "genesisIncluded", request.genesisIncluded(),
                "includeFarLiabilities", request.includeFarLiabilities(),
                "stakingInterestMode", request.stakingInterestMode(),
                "trialStressEnabled", request.trialStressEnabled(),
                "expectedVersion", request.expectedVersion(),
                "reason", reason,
                "operator", operator), "D3_FORECAST_CONFIG_HASH_FAILED");
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                D3_FORECAST_CONFIG_IDEMPOTENCY_SCOPE, idempotencyKey.trim(), requestHash, ApiResult.class,
                () -> updateForecastConfigNew(request, idempotencyKey.trim(), reason, operator));
    }

    private ApiResult<Map<String, Object>> updateForecastConfigNew(
            TreasuryForecastConfigRequest request, String idempotencyKey, String reason, String operator) {
        promoteDueForecastConfig();
        long currentVersion = configFacade.activeValueForUpdate(D3_FORECAST_CONFIG_VERSION_KEY)
                .map(this::parseConfigVersion)
                .orElse(0L);
        if (request.expectedVersion() != currentVersion) {
            throw new BizException(409, "D3_FORECAST_CONFIG_VERSION_CONFLICT");
        }
        Map<String, Object> before = forecastConfigCandidateSnapshot();
        Map<String, Object> after = validateForecastConfig(request, before);
        long version = currentVersion + 1L;
        Instant effectiveAt = LocalDate.now(clock.withZone(ZoneOffset.UTC))
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
        try {
            configFacade.upsertAdminValue(D3_FORECAST_CONFIG_PENDING_KEY, objectMapper.writeValueAsString(after), "JSON", "treasury", "D3 pending structured forecast configuration");
        } catch (JsonProcessingException ex) {
            throw new BizException(500, "D3_FORECAST_CONFIG_SERIALIZATION_FAILED");
        }
        configFacade.upsertAdminValue(D3_FORECAST_CONFIG_PENDING_EFFECTIVE_AT_KEY, effectiveAt.toString(), "STRING", "treasury", "D3 pending forecast configuration UTC effective time");
        configFacade.upsertAdminValue(D3_FORECAST_CONFIG_PENDING_VERSION_KEY, String.valueOf(version), "NUMBER", "treasury", "D3 pending forecast configuration version");
        configFacade.upsertAdminValue(D3_FORECAST_CONFIG_VERSION_KEY, String.valueOf(version), "NUMBER", "treasury", "D3 forecast configuration version");
        Map<String, Object> delta = forecastDeltaPreview(before, after);
        auditRequired("D3_FORECAST_CONFIG_CHANGED", "TREASURY_FORECAST_CONFIG", "D3", operator, section(
                "before", before, "after", after, "reason", reason, "forecastDeltaPreview", delta,
                "effectiveAt", effectiveAt, "idempotencyKey", idempotencyKey, "version", version));
        eventOutboxService.publish("TREASURY_CONFIG", "D3", "admin.treasury_forecast_config_changed", section(
                "operator", operator, "reason", reason, "version", version, "effectiveAt", effectiveAt));
        return ApiResult.ok(section("before", before, "after", after, "forecastDeltaPreview", delta,
                "effectiveAt", effectiveAt, "version", version));
    }

    @Transactional(rollbackFor = Exception.class)
    public byte[] reconciliationCsv() {
        Map<String, Object> reserve = reserve().getData();
        Map<String, Object> liabilities = liabilities(false).getData();
        String csv = "item,amount_usdt,as_of\r\n"
                + "reserve," + reserve.get("reserveTotalUsdt") + "," + reserve.get("asOf") + "\r\n"
                + "liabilities," + liabilities.get("totalUsdt") + "," + liabilities.get("asOf") + "\r\n"
                + "net_exposure," + ((BigDecimal) reserve.get("reserveTotalUsdt")).subtract((BigDecimal) liabilities.get("totalUsdt")) + "," + reserve.get("asOf") + "\r\n";
        auditRequired("D3_RECONCILIATION_EXPORTED", "TREASURY_EXPORT", "reconciliation", AdminActorResolver.resolve(null), section("scope", "reserve-liabilities"));
        return ("\ufeff" + csv).getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    @Transactional(rollbackFor = Exception.class)
    public byte[] liabilitiesCsv() {
        List<Map<String, Object>> rows = (List<Map<String, Object>>) liabilities(true).getData().get("breakdown");
        StringBuilder csv = new StringBuilder("\ufeffcategory,label,amount_usdt,share,source\r\n");
        for (Map<String, Object> row : rows) {
            csv.append(csvCell(row.get("category"))).append(',').append(csvCell(row.get("label"))).append(',')
                    .append(row.get("amountUsdt")).append(',').append(row.get("share")).append(',')
                    .append(csvCell(row.get("source"))).append("\r\n");
        }
        auditRequired("D3_LIABILITIES_EXPORTED", "TREASURY_EXPORT", "liabilities", AdminActorResolver.resolve(null), section("categoryCount", rows.size()));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private Map<String, Object> dualLedgerSnapshot() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime current24hStart = now.minusHours(24);
        LocalDateTime prev24hStart = now.minusHours(48);
        BigDecimal reserveUsd = (BigDecimal) reserve().getData().get("reserveTotalUsdt");
        BigDecimal nexUsdRate = ledgerRepository.latestNexUsdtPrice().map(this::safe).orElse(BigDecimal.ZERO);
        BigDecimal redlinePct = safetyThresholdDecimal(REDLINE_CONFIG_KEY, dualLedgerProperties.getRedlinePct());
        BigDecimal healthyPct = safetyThresholdDecimal(HEALTHY_CONFIG_KEY, dualLedgerProperties.getHealthyPct());
        BigDecimal runRiskPct = safetyThresholdDecimal(RUN_RISK_CONFIG_KEY, dualLedgerProperties.getRunRiskPct());
        String scope = configValue(SCOPE_CONFIG_KEY, "all active liabilities");

        BigDecimal queueBacklogUsd = safe(ledgerRepository.sumActiveWithdrawalQueueUsdt());
        BigDecimal nexLiabilityUnits = safe(ledgerRepository.sumActiveNexLocked())
                .add(safe(ledgerRepository.sumActiveNexReward()));
        boolean valuationReliable = nexLiabilityUnits.signum() == 0 || nexUsdRate.signum() > 0;
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> d3LiabilityRows = (List<Map<String, Object>>) liabilities(true).getData().get("breakdown");
        List<Map<String, Object>> accounts = d3LiabilityRows.stream()
                .map(row -> account(
                        String.valueOf(row.get("category")),
                        String.valueOf(row.get("label")),
                        decimal(row.get("amountUsdt")),
                        String.valueOf(row.get("source"))))
                .toList();
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
                "sources", List.of(
                        "nx_treasury_reserve_ledger",
                        "nx_price_index:NEX_USDT",
                        "nx_user_wallet",
                        "nx_wallet_ledger",
                        "nx_withdrawal_order",
                        "nx_staking_position",
                        "nx_nex_lock_order",
                        "nx_genesis_holding",
                        "nx_treasury_legacy_lock_liability",
                        "nx_config_item:wallet.dual-ledger.safety-thresholds",
                        "H1 growth rhythm facade"));
        response.put("h1Rhythm", bRhythmSummary());
        response.put("snapshot", section(
                "reserveUsd", money(reserveUsd),
                "liabilitiesUsd", money(liabilitiesUsd),
                "nexUsdRate", nexUsdRate,
                "valuationReliable", valuationReliable,
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
        response.put("maturity7d", maturity7d());
        response.put("prev", section(
                "reserveUsd", money(reserveUsd.subtract(prevNetFlow24hUsd)),
                "netFlow24hUsd", money(prevNetFlow24hUsd),
                "queueBacklogCount", ledgerRepository.countActiveWithdrawalQueue(),
                "avgRiskScore", avgRiskScore.longValue()));
        return response;
    }

    private Map<String, Object> bRhythmSummary() {
        Map<String, Object> summary = new LinkedHashMap<>(GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy).summary());
        String currentPhase = String.valueOf(summary.getOrDefault("currentPhase", ""));
        summary.put("currentPhaseName", StringUtils.hasText(currentPhase) ? currentPhase : "未配置");
        return summary;
    }

    public ApiResult<Map<String, Object>> bDomainDashboard() {
        Map<String, Object> dualLedger = dualLedgerSnapshot();
        List<Map<String, Object>> warnings = new ArrayList<>();
        Map<String, Object> response = section(
                "service", "nexion-backend",
                "domain", "B",
                "generatedAt", LocalDateTime.now(clock),
                "sources", List.of(
                        "nx_user_wallet",
                        "nx_wallet_ledger",
                        "nx_withdrawal_order",
                        "nx_staking_position",
                        "nx_deposit_order",
                        "nx_exchange_order",
                        "nx_emergency_control_setting:killswitch.*",
                        "nx_emergency_control_setting:emergency.killswitch.*"));
        response.put("warnings", warnings);
        response.put("dualLedger", dualLedger);
        response.put("alerts", section(
                "coverageRedlineAcked", Boolean.parseBoolean(configFacade.activeValue(B_ALERT_COVERAGE_ACK_KEY).orElse("false")),
                "sources", List.of("nx_config_item:" + B_ALERT_COVERAGE_ACK_KEY)));
        response.put("liquidity", liquidityDashboard(dualLedger, warnings));
        response.put("funnel", funnelDashboard(warnings));
        response.put("rhythm", rhythmDashboard(dualLedger, warnings));
        response.put("riskRadar", riskRadarDashboard(dualLedger, warnings));
        return ApiResult.ok(response);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<Map<String, Object>> acknowledgeBDomainAlert(String alertId, String idempotencyKey, TreasuryAlertAckRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedAlertId = alertId == null ? "" : alertId.trim().toLowerCase(Locale.ROOT);
        if (!B_ALERT_COVERAGE_ID.equals(normalizedAlertId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "B_ALERT_NOT_SUPPORTED");
        }
        String operator = trimToNull(request.operator());
        if (operator == null) {
            return ApiResult.fail(OpsErrorCode.OPERATOR_REQUIRED.httpStatus(), OpsErrorCode.OPERATOR_REQUIRED.name());
        }
        String reason = request.reason().trim();
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                B_ALERT_ACK_IDEMPOTENCY_SCOPE,
                idempotencyKey,
                bAlertAckRequestHash(normalizedAlertId, reason, operator),
                ApiResult.class,
                () -> acknowledgeBDomainAlertNew(normalizedAlertId, idempotencyKey.trim(), reason, operator));
    }

    private ApiResult<Map<String, Object>> acknowledgeBDomainAlertNew(
            String normalizedAlertId,
            String idempotencyKey,
            String reason,
            String operator) {
        Map<String, Object> snapshot = dualLedgerSnapshot();
        @SuppressWarnings("unchecked")
        Map<String, Object> values = (Map<String, Object>) snapshot.getOrDefault("snapshot", Map.of());
        BigDecimal coverageRatio = decimal(values.get("coverageRatio"));
        BigDecimal healthyPct = decimal(values.get("healthyPct"));
        if (coverageRatio.compareTo(healthyPct) >= 0) {
            return ApiResult.fail(409, "B_ALERT_NOT_ACTIVE");
        }
        configFacade.upsertAdminValue(
                B_ALERT_COVERAGE_ACK_KEY,
                "true",
                "BOOLEAN",
                "treasury_b",
                "B1 coverage redline alert acknowledgement");
        audit("B1_COVERAGE_ALERT_ACKED", "TREASURY_ALERT", normalizedAlertId, null, section(
                "alertId", normalizedAlertId,
                "reason", reason,
                "operator", operator,
                "idempotencyKey", idempotencyKey));
        Map<String, Object> response = bDomainDashboard().getData();
        response.put("alertAck", section("alertId", normalizedAlertId, "acknowledged", true));
        return ApiResult.ok(response);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> createInjection(String idempotencyKey, TreasuryInjectionRequest request) {
        requireD3Command(idempotencyKey, request == null ? null : request.reason());
        BigDecimal amount = request.amount() == null ? BigDecimal.ZERO : request.amount().setScale(2, RoundingMode.HALF_UP);
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException(400, "AMOUNT_MUST_BE_POSITIVE");
        }
        if (!StringUtils.hasText(request.voucherNo())) {
            throw new BizException(400, "VOUCHER_NO_REQUIRED");
        }
        String voucherNo = request.voucherNo().trim();
        if (!voucherNo.matches("[A-Za-z0-9][A-Za-z0-9:_./-]{5,95}") || voucherNo.matches("(?i)^D3-\\d+$")) {
            throw new BizException(400, "VOUCHER_NO_INVALID");
        }
        String reason = request.reason().trim();
        String operator = AdminActorResolver.resolve(request.operator());
        if (!StringUtils.hasText(operator)) {
            throw new BizException(400, OpsErrorCode.OPERATOR_REQUIRED.name());
        }
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                D3_RESERVE_INJECTION_IDEMPOTENCY_SCOPE,
                idempotencyKey,
                reserveInjectionRequestHash(amount, voucherNo, reason, operator),
                ApiResult.class,
                () -> createInjectionNew(idempotencyKey.trim(), amount, voucherNo, reason, operator));
    }

    private ApiResult<Map<String, Object>> createInjectionNew(
            String idempotencyKey,
            BigDecimal amount,
            String voucherNo,
            String reason,
            String operator) {
        if (ledgerRepository.reserveVoucherExists(voucherNo)) {
            throw new BizException(409, "VOUCHER_ALREADY_REGISTERED");
        }
        BigDecimal oldReserve = safe(ledgerRepository.currentReserveUsd()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal newReserve = oldReserve.add(amount).setScale(2, RoundingMode.HALF_UP);
        try {
            ledgerRepository.recordReserveInjection(voucherNo, amount, reason, operator, idempotencyKey);
        } catch (DuplicateKeyException race) {
            throw new BizException(409, "VOUCHER_ALREADY_REGISTERED");
        }
        auditRequired("D3_TREASURY_RESERVE_INJECTION", "TREASURY_RESERVE", voucherNo, operator, section(
                "voucherNo", voucherNo,
                "amount", amount,
                "oldReserveUsd", oldReserve,
                "newReserveUsd", newReserve,
                "reason", reason,
                "idempotencyKey", idempotencyKey));
        eventOutboxService.publish("TREASURY_RESERVE", voucherNo, "admin.treasury_reserve_injected", section(
                "voucherNo", voucherNo, "amountUsdt", amount, "operator", operator, "reason", reason));
        Map<String, Object> response = new LinkedHashMap<>(reserve().getData());
        response.put("injection", section(
                "voucherNo", voucherNo,
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
        String reason = request.reason().trim();
        String operator = trimToNull(request.operator());
        if (operator == null) {
            return ApiResult.fail(OpsErrorCode.OPERATOR_REQUIRED.httpStatus(), OpsErrorCode.OPERATOR_REQUIRED.name());
        }
        String requestHash = structuredRequestHash(section(
                "scope", scope,
                "reason", reason,
                "operator", operator), "B_SCOPE_UPDATE_HASH_FAILED");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ApiResult<Map<String, Object>> result = (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                B_SCOPE_IDEMPOTENCY_SCOPE,
                idempotencyKey,
                requestHash,
                ApiResult.class,
                () -> updateScopeNew(idempotencyKey.trim(), scope, reason, operator));
        return result;
    }

    private ApiResult<Map<String, Object>> updateScopeNew(
            String idempotencyKey,
            String scope,
            String reason,
            String operator) {
        configFacade.upsertAdminValue(SCOPE_CONFIG_KEY, scope, "STRING", "wallet", "B1 dual ledger scope");
        audit("B1_DUAL_LEDGER_SCOPE_CHANGED", "TREASURY_SCOPE", SCOPE_CONFIG_KEY, operator, section(
                "scope", scope,
                "reason", reason,
                "idempotencyKey", idempotencyKey));
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
                ? threshold(request.redlinePct(), "redlinePct", new BigDecimal("79.9"), BigDecimal.valueOf(150))
                : safetyThresholdDecimal(REDLINE_CONFIG_KEY, dualLedgerProperties.getRedlinePct());
        BigDecimal healthy = hasHealthy
                ? threshold(request.healthyPct(), "healthyPct", new BigDecimal("99.9"), BigDecimal.valueOf(200))
                : safetyThresholdDecimal(HEALTHY_CONFIG_KEY, dualLedgerProperties.getHealthyPct());
        BigDecimal runRisk = hasRunRisk
                ? threshold(request.runRiskPct(), "runRiskPct", BigDecimal.ZERO, BigDecimal.valueOf(100))
                : safetyThresholdDecimal(RUN_RISK_CONFIG_KEY, dualLedgerProperties.getRunRiskPct());
        if (redline.compareTo(healthy) >= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REDLINE_MUST_BE_BELOW_HEALTHY");
        }

        String reason = request.reason().trim();
        String operator = trimToNull(request.operator());
        if (operator == null) {
            return ApiResult.fail(OpsErrorCode.OPERATOR_REQUIRED.httpStatus(), OpsErrorCode.OPERATOR_REQUIRED.name());
        }
        String requestHash = structuredRequestHash(section(
                "redlinePct", hasRedline ? redline : null,
                "healthyPct", hasHealthy ? healthy : null,
                "runRiskPct", hasRunRisk ? runRisk : null,
                "reason", reason,
                "operator", operator), "B_THRESHOLD_UPDATE_HASH_FAILED");
        @SuppressWarnings({"unchecked", "rawtypes"})
        ApiResult<Map<String, Object>> result = (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                B_THRESHOLD_IDEMPOTENCY_SCOPE,
                idempotencyKey,
                requestHash,
                ApiResult.class,
                () -> updateThresholdsNew(
                        idempotencyKey.trim(), redline, healthy, runRisk,
                        hasRedline, hasHealthy, hasRunRisk, reason, operator));
        return result;
    }

    private ApiResult<Map<String, Object>> updateThresholdsNew(
            String idempotencyKey,
            BigDecimal redline,
            BigDecimal healthy,
            BigDecimal runRisk,
            boolean hasRedline,
            boolean hasHealthy,
            boolean hasRunRisk,
            String reason,
            String operator) {
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

        audit("B1_DUAL_LEDGER_THRESHOLDS_CHANGED", "DUAL_LEDGER_THRESHOLDS", "B1", operator, section(
                "changed", changed,
                "reason", reason,
                "idempotencyKey", idempotencyKey));
        Map<String, Object> response = dualLedger().getData();
        response.put("thresholdUpdate", changed);
        return ApiResult.ok(response);
    }

    @Transactional
    @SuppressWarnings({"unchecked", "rawtypes"})
    public ApiResult<Map<String, Object>> updateBankRunThresholds(
            String idempotencyKey,
            BankRunThresholdRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(
                idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        boolean hasYellow = StringUtils.hasText(request.yellowPct());
        boolean hasRedline = StringUtils.hasText(request.redlinePct());
        if (!hasYellow && !hasRedline) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "BANKRUN_THRESHOLD_REQUIRED");
        }
        BankRunThresholdPolicy.Bands currentBands = BankRunThresholdPolicy.resolve(configFacade);
        BigDecimal yellow = hasYellow
                ? bankRunThreshold(request.yellowPct(), "yellowPct", new BigDecimal("5"), new BigDecimal("50"))
                : currentBands.yellowPct();
        BigDecimal redline = hasRedline
                ? bankRunThreshold(request.redlinePct(), "redlinePct", new BigDecimal("10"), new BigDecimal("80"))
                : currentBands.redlinePct();
        if (redline.compareTo(yellow) <= 0) {
            return ApiResult.fail(
                    OpsErrorCode.VALIDATION_FAILED.httpStatus(), "BANKRUN_REDLINE_MUST_EXCEED_YELLOW");
        }
        String operator = AdminActorResolver.resolve(request.operator());
        if (!StringUtils.hasText(operator)) {
            return ApiResult.fail(OpsErrorCode.OPERATOR_REQUIRED.httpStatus(), OpsErrorCode.OPERATOR_REQUIRED.name());
        }
        String reason = request.reason().trim();
        String requestHash = structuredRequestHash(section(
                "yellowPct", yellow,
                "redlinePct", redline,
                "changeYellow", hasYellow,
                "changeRedline", hasRedline,
                "reason", reason,
                "operator", operator), "B5_BANKRUN_THRESHOLD_HASH_FAILED");
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                B_BANK_RUN_THRESHOLD_IDEMPOTENCY_SCOPE,
                idempotencyKey,
                requestHash,
                ApiResult.class,
                () -> updateBankRunThresholdsNew(
                        idempotencyKey.trim(), yellow, redline, hasYellow, hasRedline, reason, operator));
    }

    private ApiResult<Map<String, Object>> updateBankRunThresholdsNew(
            String idempotencyKey,
            BigDecimal yellow,
            BigDecimal redline,
            boolean hasYellow,
            boolean hasRedline,
            String reason,
            String operator) {
        if (hasYellow) {
            configFacade.upsertAdminValue(
                    BANK_RUN_YELLOW_CONFIG_KEY, yellow.stripTrailingZeros().toPlainString(),
                    "NUMBER", "risk", "B5 bank-run warning threshold");
        }
        if (hasRedline) {
            configFacade.upsertAdminValue(
                    TreasuryEmergencySignalFacadeAdapter.BANK_RUN_REDLINE_CONFIG_KEY,
                    redline.stripTrailingZeros().toPlainString(),
                    "NUMBER", "risk", "B5 bank-run automatic stop threshold used by J1 R1");
        }
        audit("B5_BANKRUN_THRESHOLDS_CHANGED", "RISK_THRESHOLD", "bankrun", operator, section(
                "yellowPct", yellow,
                "redlinePct", redline,
                "reason", reason,
                "idempotencyKey", idempotencyKey,
                "linkedDomain", "J1:R1"));
        return bDomainDashboard();
    }

    public ApiResult<PageResult<TreasuryLedgerBillView>> ledgerBills(TreasuryLedgerQueryRequest request) {
        ensureD4FallbackSeedData();
        int pageNum = clamp(request == null || request.pageNum() == null ? 1 : request.pageNum(), 1, 10_000);
        int pageSize = clamp(request == null || request.pageSize() == null ? 20 : request.pageSize(), 1, 100);
        String type = normalizeLedgerType(request == null ? null : request.type());
        Long userId = request == null ? null : request.userId();
        if (userId != null && userId <= 0) {
            throw new BizException(400, "USER_ID_INVALID");
        }
        String keyword = trimToNull(request == null ? null : request.keyword());
        String bizNo = trimToNull(request == null ? null : request.bizNo());
        String status = normalizeD4Status(request == null ? null : request.status());
        LocalDateTime from = parseD4Time(request == null ? null : request.from(), false);
        LocalDateTime to = parseD4Time(request == null ? null : request.to(), true);
        requireD4TimeRange(from, to);
        long total = ledgerRepository.countLedgerBills(type, userId, keyword, bizNo, status, from, to);
        int offset = (pageNum - 1) * pageSize;
        List<TreasuryLedgerBillView> rows = ledgerRepository.pageLedgerBills(
                type, userId, keyword, bizNo, status, from, to, pageSize, offset);
        return ApiResult.ok(new PageResult<>(total, pageNum, pageSize, rows));
    }

    public ApiResult<Map<String, Object>> userLedger(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        ensureD4FallbackSeedData();
        long total = ledgerRepository.countLedgerBills(null, userId, null, null, null, null, null);
        if (total > D4_MAX_AUDIT_ROWS) {
            return ApiResult.fail(422, "D4_USER_LEDGER_TOO_LARGE_REFINE_RANGE");
        }
        List<TreasuryLedgerBillView> rows = ledgerRepository.userLedgerRows(userId, D4_MAX_AUDIT_ROWS);
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
        Map<String, BigDecimal> categorySums = new LinkedHashMap<>();
        for (String billType : D4_BILL_TYPE_ORDER) {
            for (String asset : D4_ASSET_ORDER) {
                categorySums.put(billType + ":" + asset, BigDecimal.ZERO);
            }
        }
        for (TreasuryLedgerBillView row : rows) {
            BigDecimal signed = isCreditDirection(row.direction()) ? safe(row.amount()) : safe(row.amount()).negate();
            sums.merge(row.asset(), signed, BigDecimal::add);
            categorySums.merge(row.billType() + ":" + row.asset(), signed, BigDecimal::add);
        }
        Map<String, Object> response = section(
                "userId", userId,
                "userNo", userNo,
                "nickname", nickname,
                "rows", rows,
                "total", total,
                "sums", sums,
                "categorySums", categorySums,
                "currentUsdtBalance", ledgerRepository.actualUserBalance(userId, "USDT").orElse(BigDecimal.ZERO),
                "currentNexBalance", ledgerRepository.actualUserBalance(userId, "NEX").orElse(BigDecimal.ZERO),
                "sources", List.of("nx_wallet_ledger", "nx_user_wallet"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> runningBalance(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(400, "USER_ID_REQUIRED");
        }
        long total = ledgerRepository.countLedgerBills(null, userId, null, null, null, null, null);
        if (total > D4_MAX_AUDIT_ROWS) {
            return ApiResult.fail(422, "D4_RUNNING_BALANCE_TOO_LARGE_REFINE_RANGE");
        }
        List<TreasuryLedgerBillView> ordered = ledgerRepository.userLedgerRows(userId, D4_MAX_AUDIT_ROWS).stream()
                .sorted(java.util.Comparator.comparing(TreasuryLedgerBillView::createdAt)
                        .thenComparing(TreasuryLedgerBillView::id))
                .toList();
        Map<String, BigDecimal> previous = new LinkedHashMap<>();
        Map<String, BigDecimal> latestLedger = new LinkedHashMap<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        int breakCount = 0;
        for (TreasuryLedgerBillView row : ordered) {
            BigDecimal signed = isCreditDirection(row.direction()) ? safe(row.amount()) : safe(row.amount()).negate();
            BigDecimal expected = previous.containsKey(row.asset())
                    ? previous.get(row.asset()).add(signed)
                    : safe(row.balanceAfter());
            BigDecimal difference = safe(row.balanceAfter()).subtract(expected);
            boolean broken = difference.compareTo(BigDecimal.ZERO) != 0;
            if (broken) breakCount++;
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bill", row);
            item.put("expectedBalanceAfter", expected);
            item.put("difference", difference);
            item.put("breakDetected", broken);
            rows.add(item);
            previous.put(row.asset(), safe(row.balanceAfter()));
            latestLedger.put(row.asset(), safe(row.balanceAfter()));
        }
        Map<String, BigDecimal> reconciliation = new LinkedHashMap<>();
        for (String asset : List.of("USDT", "NEX")) {
            BigDecimal wallet = ledgerRepository.actualUserBalance(userId, asset).orElse(BigDecimal.ZERO);
            BigDecimal difference = wallet.subtract(latestLedger.getOrDefault(asset, BigDecimal.ZERO));
            reconciliation.put(asset, difference);
            if (difference.compareTo(BigDecimal.ZERO) != 0) breakCount++;
        }
        return ApiResult.ok(section(
                "userId", userId,
                "total", total,
                "rows", rows,
                "breakCount", breakCount,
                "reconciliation", reconciliation,
                "balanced", breakCount == 0,
                "sources", List.of("nx_wallet_ledger", "nx_user_wallet")));
    }

    public byte[] ledgerBillsCsv(TreasuryLedgerQueryRequest request, String reason) {
        String exportReason = trimToNull(reason);
        if (exportReason == null || exportReason.length() < 8 || exportReason.length() > 200) {
            throw new BizException(422, "D4_EXPORT_REASON_LENGTH_INVALID");
        }
        String type = normalizeLedgerType(request == null ? null : request.type());
        Long userId = request == null ? null : request.userId();
        if (userId != null && userId <= 0) throw new BizException(400, "USER_ID_INVALID");
        String keyword = trimToNull(request == null ? null : request.keyword());
        String bizNo = trimToNull(request == null ? null : request.bizNo());
        String status = normalizeD4Status(request == null ? null : request.status());
        LocalDateTime from = parseD4Time(request == null ? null : request.from(), false);
        LocalDateTime to = parseD4Time(request == null ? null : request.to(), true);
        requireD4TimeRange(from, to);
        long total = ledgerRepository.countLedgerBills(type, userId, keyword, bizNo, status, from, to);
        if (total > D4_MAX_AUDIT_ROWS) throw new BizException(422, "D4_EXPORT_TOO_LARGE_REFINE_RANGE");
        List<TreasuryLedgerBillView> rows = ledgerRepository.pageLedgerBills(
                type, userId, keyword, bizNo, status, from, to, D4_MAX_AUDIT_ROWS, 0);
        StringBuilder csv = new StringBuilder("\uFEFFbill_id,user_masked,bill_type,subtype,asset,direction,amount,balance_after,status,ref,created_at\r\n");
        for (TreasuryLedgerBillView row : rows) {
            csv.append(csvCell(row.id())).append(',')
                    .append(csvCell(maskUserNo(row.userNo()))).append(',')
                    .append(csvCell(row.billType())).append(',')
                    .append(csvCell(row.subtype())).append(',')
                    .append(csvCell(row.asset())).append(',')
                    .append(csvCell(row.direction())).append(',')
                    .append(csvCell(row.amount())).append(',')
                    .append(csvCell(row.balanceAfter())).append(',')
                    .append(csvCell(row.status())).append(',')
                    .append(csvCell(row.bizNo())).append(',')
                    .append(csvCell(row.createdAt())).append("\r\n");
        }
        auditRequired("admin.report_exported", "D4_BILLS", type == null ? "all" : type,
                AdminActorResolver.resolve(null), section(
                        "exportType", "BILL_CSV",
                        "scope", userId == null ? "platform" : "user",
                        "userId", userId == null ? "" : userId,
                        "billType", type == null ? "all" : type,
                        "fields", "bill_id,user_masked,bill_type,subtype,asset,direction,amount,balance_after,status,ref,created_at",
                        "rowCount", rows.size(),
                        "containsPii", true,
                        "maskingPolicy", "MASKED",
                        "format", "CSV",
                        "reason", exportReason,
                        "masking", "forced"));
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> liquidityDashboard(Map<String, Object> dualLedger, List<Map<String, Object>> warnings) {
        Map<String, Object> snapshot = (Map<String, Object>) dualLedger.getOrDefault("snapshot", Map.of());
        BigDecimal liabilitiesUsd = decimal(snapshot.get("liabilitiesUsd"));
        List<Map<String, Object>> accounts = ((List<Map<String, Object>>) dualLedger.getOrDefault("accounts", List.of()))
                .stream()
                .map(account -> {
                    BigDecimal amount = decimal(account.get("amount"));
                    Map<String, Object> row = new LinkedHashMap<>(account);
                    row.put("pc", pctScale(pct(amount, liabilitiesUsd)));
                    return row;
                })
                .toList();
        Map<String, Object> maturity = maturityForecast("7d").getData();
        List<Map<String, Object>> runway = ((List<Map<String, Object>>) maturity.getOrDefault("daily", List.of()))
                .stream()
                .map(row -> section(
                        "day", String.valueOf(row.get("date")),
                        "valueWan", wan(decimal(row.get("totalDueUsdt"))),
                        "source", "D3 /api/admin/treasury/maturity-forecast"))
                .toList();
        Map<String, Object> exposure = netExposure("7d").getData();
        List<Map<String, Object>> flow = ((List<Map<String, Object>>) exposure.getOrDefault("series", List.of()))
                .stream()
                .map(row -> section("label", String.valueOf(row.get("date")),
                        "valueWan", wan(decimal(row.get("netExposureUsdt")))))
                .toList();
        BigDecimal runwayTotalWan = runway.stream()
                .map(row -> decimal(row.get("valueWan")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> response = section(
                "coverage", snapshot,
                "liabilities", accounts,
                "runway", runway,
                "runwayTotalWan", runwayTotalWan,
                "flow", flow,
                "sources", List.of("D3 liabilities", "D3 maturity-forecast", "D3 net-exposure"));
        return response;
    }

    private Map<String, Object> funnelDashboard(List<Map<String, Object>> warnings) {
        LocalDateTime since = LocalDateTime.now(clock).minusDays(30);
        List<Map<String, Object>> stages = funnelStagesFromOrders(since);
        List<Map<String, Object>> transitions = funnelTransitions(stages);
        List<BigDecimal> cohort = netFlowSeries(8);
        List<Map<String, Object>> channels = funnelChannelsFromOrders(since);
        List<BigDecimal> daily = dailyDepositSeries(8);
        BigDecimal dailyTarget = daily.isEmpty()
                ? BigDecimal.ZERO
                : daily.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(daily.size()), 2, RoundingMode.HALF_UP);
        BigDecimal first = stages.isEmpty() ? BigDecimal.ZERO : decimal(stages.get(0).get("ct"));
        BigDecimal last = stages.isEmpty() ? BigDecimal.ZERO : decimal(stages.get(stages.size() - 1).get("ct"));
        Map<String, Object> bottleneck = transitions.stream()
                .filter(row -> Boolean.TRUE.equals(row.get("bad")))
                .findFirst()
                .orElse(transitions.isEmpty() ? Map.of() : transitions.get(0));
        return section(
                "stages", stages,
                "transitions", transitions,
                "cohort", cohort,
                "channels", channels,
                "daily", daily,
                "dailyTarget", dailyTarget,
                "overallConversionPct", pctScale(pct(last, first)),
                "bottleneck", bottleneck,
                "sources", List.of(
                        "nx_deposit_order",
                        "nx_exchange_order",
                        "nx_withdrawal_order",
                        "nx_wallet_ledger"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rhythmDashboard(Map<String, Object> dualLedger, List<Map<String, Object>> warnings) {
        List<Map<String, Object>> phaseNodes = rhythmPhaseNodesFromH1(dualLedger);
        List<BigDecimal> inflow = netFlowSeries(8);
        List<Map<String, Object>> budget = liabilityBudgetFromDualLedger(dualLedger);
        List<BigDecimal> ratio = coverageRatioSeries(dualLedger);
        BigDecimal healthyRatio = decimal(((Map<String, Object>) dualLedger.getOrDefault("snapshot", Map.of())).get("healthyPct"));
        Map<String, Object> h1Rhythm = (Map<String, Object>) dualLedger.getOrDefault("h1Rhythm", Map.of());
        BigDecimal currentRatio = ratio.isEmpty() ? BigDecimal.ZERO : ratio.get(ratio.size() - 1);
        return section(
                "h1", h1Rhythm,
                "phaseNodes", phaseNodes,
                "inflowWan", inflow,
                "budget", budget,
                "ratio", ratio,
                "healthyRatio", healthyRatio,
                "currentRatio", currentRatio,
                "suggestion", currentRatio.compareTo(healthyRatio) >= 0 ? "维持扩张" : "切入收紧",
                "sources", List.of(
                        "nx_config_item:H1.rhythm.*",
                        "B1 dualLedger",
                        "nx_wallet_ledger"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> riskRadarDashboard(Map<String, Object> dualLedger, List<Map<String, Object>> warnings) {
        Map<String, Object> snapshot = (Map<String, Object>) dualLedger.getOrDefault("snapshot", Map.of());
        List<Map<String, Object>> feed = new ArrayList<>();
        LocalDateTime since = LocalDateTime.now(clock).minusDays(7);
        List<BigDecimal> pressure = ledgerRepository.riskPressureSeries(since);
        List<Map<String, Object>> rules = ledgerRepository.riskRuleBuckets(since);
        List<Map<String, Object>> decisionSeverity = ledgerRepository.riskSeverityBuckets(since);
        List<Map<String, Object>> volume = ledgerRepository.riskVolumeBuckets(since);
        Map<String, Object> k4 = ledgerRepository.currentK4RiskScoreSnapshot();
        String k4ModelVersion = String.valueOf(k4.getOrDefault("modelVersion", "")).trim();
        Integer k4BandLowMax = k4Threshold(k4.get("bandLowMax"));
        Integer k4BandHighMin = k4Threshold(k4.get("bandHighMin"));
        Integer k4AutoEscalateScore = k4Threshold(k4.get("autoEscalateScore"));
        boolean k4ThresholdsValid = k4BandLowMax != null && k4BandHighMin != null
                && k4AutoEscalateScore != null && k4BandLowMax < k4BandHighMin;
        boolean k4ScoringAvailable = StringUtils.hasText(k4ModelVersion) && k4ThresholdsValid;
        long k4AutoEscalated = decimal(k4.get("autoEscalated")).longValue();
        long k4HighRisk = decimal(k4.get("highRisk")).longValue();
        long k4MediumRisk = decimal(k4.get("mediumRisk")).longValue();
        long k4LowRisk = decimal(k4.get("lowRisk")).longValue();
        long k4FlaggedAccounts = decimal(k4.get("flaggedAccounts")).longValue();
        long k4ActiveOverrides = decimal(k4.get("activeOverrides")).longValue();
        long k4StaleScoreUsers = decimal(k4.get("staleScoreUsers")).longValue();
        List<Map<String, Object>> severity = k4ScoringAvailable ? List.of(
                section("nm", "高风险", "v", k4HighRisk, "c", "var(--danger)"),
                section("nm", "中风险", "v", k4MediumRisk, "c", "var(--warning)"),
                section("nm", "低风险", "v", k4LowRisk, "c", "var(--success)")) : List.of();
        BigDecimal pressureTightPct = decimal(snapshot.get("runRiskPct"));
        BigDecimal reserveUsd = decimal(snapshot.get("reserveUsd"));
        BigDecimal withdrawalRequests24h = safe(ledgerRepository.sumWithdrawalRequested24hUsdt());
        BigDecimal bankRunRatio = pctScale(pct(withdrawalRequests24h, reserveUsd));
        BankRunThresholdPolicy.Bands bankRunBands = BankRunThresholdPolicy.resolve(configFacade);
        BigDecimal bankRunYellowPct = bankRunBands.yellowPct();
        BigDecimal bankRunRedlinePct = bankRunBands.redlinePct();
        feed.add(dynamicCoverageFeed(snapshot, bankRunRatio, bankRunYellowPct, bankRunRedlinePct));
        if (!k4ScoringAvailable) {
            feed.add(section(
                    "sev", "p1",
                    "t", "K4 风险评分暂不可用 · 异常账户停止推测",
                    "m", "K4 服务端权威评分",
                    "href", "/risk/scoring"));
        } else if (k4AutoEscalated > 0) {
            feed.add(section(
                    "sev", "p0",
                    "t", "K4 自动升级线 " + k4AutoEscalateScore + " · " + k4AutoEscalated
                            + " 个账户 · 当前模型 " + k4ModelVersion,
                    "m", "活动模型阈值与新鲜有效分（含人工覆盖）",
                    "href", "/risk/scoring"));
        } else if (k4HighRisk > 0) {
            feed.add(section(
                    "sev", "p1",
                    "t", "K4 高风险 " + k4HighRisk + " 个账户 · 当前模型 " + k4ModelVersion,
                    "m", "含活动人工覆盖后的服务端有效分",
                    "href", "/risk/scoring"));
        } else if (k4MediumRisk > 0) {
            feed.add(section(
                    "sev", "p2",
                    "t", "K4 中风险 " + k4MediumRisk + " 个账户 · 当前模型 " + k4ModelVersion,
                    "m", "含活动人工覆盖后的服务端有效分",
                    "href", "/risk/scoring"));
        }
        if (k4ScoringAvailable && k4StaleScoreUsers > 0) {
            feed.add(section(
                    "sev", "p2",
                    "t", "K4 有 " + k4StaleScoreUsers + " 个账户等待当前模型重算",
                    "m", "陈旧模型分未计入 B5 异常账户与风险分布",
                    "href", "/risk/scoring"));
        }
        feed.addAll(k5RecentAlertFeed(since));
        RiskTamperSignalFacade.TamperRadarSnapshot tamperRadar = riskTamperSignalFacade.tamperRadarSnapshot(since);
        if (tamperRadar.signalCount() > 0) {
            feed.add(section(
                    "sev", "p1",
                    "t", "篡改拦截 " + tamperRadar.signalCount() + " 起 · 涉及 " + tamperRadar.accountCount() + " 个账户",
                    "m", "J3 服务器拦截事件 · 最近 " + tamperRadar.latestAt(),
                    "href", "/emergency/tamper"));
        }
        List<Map<String, Object>> gates = riskGates();
        long tripped = gates.stream().filter(gate -> Boolean.FALSE.equals(gate.get("on"))).count();
        return section(
                "gates", gates,
                "trippedGateCount", tripped,
                "feed", feed,
                "pressureSeries", pressure,
                "pressureTightPct", pressureTightPct,
                "currentPressurePct", pressure.isEmpty() ? BigDecimal.ZERO : pressure.get(pressure.size() - 1),
                "rules", rules,
                "flaggedAccounts", k4ScoringAvailable ? k4FlaggedAccounts : 0L,
                "severity", severity,
                "decisionSeverity", decisionSeverity,
                "k4ScoringAvailable", k4ScoringAvailable,
                "k4ModelVersion", k4ScoringAvailable ? k4ModelVersion : null,
                "k4BandLowMax", k4ScoringAvailable ? k4BandLowMax : null,
                "k4BandHighMin", k4ScoringAvailable ? k4BandHighMin : null,
                "k4AutoEscalateScore", k4ScoringAvailable ? k4AutoEscalateScore : null,
                "k4AutoEscalatedAccounts", k4ScoringAvailable ? k4AutoEscalated : 0L,
                "k4ActiveOverrides", k4ScoringAvailable ? k4ActiveOverrides : 0L,
                "k4StaleScoreUsers", k4ScoringAvailable ? k4StaleScoreUsers : 0L,
                "volume", volume,
                "bankRunRatio", bankRunRatio,
                "bankRunYellowPct", bankRunYellowPct,
                "bankRunRedlinePct", bankRunRedlinePct,
                "sources", List.of(
                        "nx_emergency_control_setting:killswitch.*",
                        "nx_emergency_control_setting:emergency.killswitch.*",
                        "B1 dualLedger",
                        "nx_config_item:" + BANK_RUN_YELLOW_CONFIG_KEY,
                        "nx_config_item:" + TreasuryEmergencySignalFacadeAdapter.BANK_RUN_REDLINE_CONFIG_KEY,
                        "nx_withdrawal_order",
                        "nx_risk_decision",
                        "nx_admin_risk_score_user:current-model",
                        "nx_admin_risk_score_user:fresh-current-model",
                        "nx_admin_risk_score_model:active-thresholds",
                        "nx_admin_risk_score_override:active",
                        "nx_admin_risk_kyc_alert:active-recent",
                        "nx_risk_signal:TAMPER_DETECTED"));
    }

    private List<Map<String, Object>> k5RecentAlertFeed(LocalDateTime since) {
        return ledgerRepository.recentK5KycAlerts(since, B5_K5_RECENT_ALERT_LIMIT).stream()
                .filter(row -> decimal(row.get("isDeleted")).signum() == 0)
                .filter(row -> isK5FeedAlert(String.valueOf(row.getOrDefault("eventKey", ""))))
                .filter(row -> StringUtils.hasText(String.valueOf(row.getOrDefault("title", ""))))
                .limit(B5_K5_RECENT_ALERT_LIMIT)
                .map(row -> {
                    String tone = String.valueOf(row.getOrDefault("tone", "")).trim().toLowerCase(Locale.ROOT);
                    String severity = "bad".equals(tone) ? "p1" : "warn".equals(tone) ? "p2" : "p3";
                    String severityLabel = "p1".equals(severity) ? "高风险" : "p2".equals(severity) ? "中风险" : "提示";
                    String body = String.valueOf(row.getOrDefault("body", "")).trim();
                    String timeText = String.valueOf(row.getOrDefault("timeText", "")).trim();
                    return section(
                            "sev", severity,
                            "severityLabel", severityLabel,
                            "t", String.valueOf(row.get("title")).trim(),
                            "m", String.join(" · ", java.util.stream.Stream.of("K5", severityLabel, body, timeText)
                                    .filter(StringUtils::hasText).toList()),
                            "domain", "K5",
                            "eventKey", String.valueOf(row.get("eventKey")),
                            "route", "/risk/kyc-review",
                            "href", "/risk/kyc-review");
                })
                .toList();
    }

    private boolean isK5FeedAlert(String eventKey) {
        return eventKey.startsWith("threshold-hit:")
                || eventKey.startsWith("sla-breach:")
                || eventKey.startsWith("large-withdraw-burst:");
    }

    private List<Map<String, Object>> riskGates() {
        List<Map<String, Object>> gates = new ArrayList<>();
        for (GateSeed seed : B_RISK_GATE_SEEDS) {
            String key = seed.key();
            String configKey = "killswitch." + key;
            String legacyKey = legacyKillSwitchKey(key);
            Optional<String> primary = controlValue(configKey).filter(StringUtils::hasText);
            Optional<String> legacy = controlValue(legacyKey).filter(StringUtils::hasText);
            boolean on = KillSwitchState.enabled(primary, legacy);
            boolean configuredPresent = primary.isPresent() || legacy.isPresent();
            String sourceKey = primary.isPresent() ? configKey : legacy.isPresent() ? legacyKey : configKey;
            String state = configuredPresent ? (on ? "on" : "off") : "default_on";
            gates.add(section("nm", seed.name(), "dom", key, "on", on, "state", state, "configKey", sourceKey));
        }
        return gates;
    }

    private String legacyKillSwitchKey(String key) {
        return Set.of("staking", "genesis").contains(key)
                ? "J.killswitch." + key
                : "emergency.killswitch." + key;
    }

    private Optional<String> controlValue(String key) {
        return emergencyRepository.settingValue(key);
    }

    private Map<String, Object> dynamicCoverageFeed(
            Map<String, Object> snapshot,
            BigDecimal bankRun,
            BigDecimal bankRunYellowPct,
            BigDecimal bankRunRedlinePct) {
        BigDecimal coverage = decimal(snapshot.get("coverageRatio"));
        String severity = bankRun.compareTo(bankRunRedlinePct) >= 0
                ? "p0"
                : bankRun.compareTo(bankRunYellowPct) >= 0 ? "p1" : "p2";
        return section(
                "sev", severity,
                "t", "挤兑比率 " + bankRun.stripTrailingZeros().toPlainString() + "% · 覆盖率 " + coverage.stripTrailingZeros().toPlainString() + "%",
                "m", "B1 双账本 · 实时",
                "href", "/overview/dual-ledger");
    }

    private List<Map<String, Object>> flowRows(List<BigDecimal> flow) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < flow.size(); i++) {
            rows.add(section("label", "W" + (i + 1), "valueWan", safe(flow.get(i))));
        }
        return rows;
    }

    private List<Map<String, Object>> liquidityRunwayFromLedger(Map<String, Object> snapshot) {
        List<Map<String, Object>> rows = new ArrayList<>();
        rows.add(section("day", "withdraw_queue", "valueWan", wan(decimal(snapshot.get("queueBacklogUsd"))), "source", "nx_withdrawal_order"));
        rows.add(section("day", "liabilities", "valueWan", wan(decimal(snapshot.get("liabilitiesUsd"))), "source", "B1 dualLedger liabilities"));
        rows.add(section("day", "reserve", "valueWan", wan(decimal(snapshot.get("reserveUsd"))), "source", "nx_treasury_reserve_ledger"));
        return rows.stream()
                .filter(row -> decimal(row.get("valueWan")).compareTo(BigDecimal.ZERO) > 0)
                .toList();
    }

    private List<BigDecimal> netFlowSeries(int points) {
        LocalDateTime now = LocalDateTime.now(clock);
        List<BigDecimal> flow = new ArrayList<>();
        for (int i = points - 1; i >= 0; i--) {
            LocalDateTime start = now.minusDays(i + 1L);
            LocalDateTime end = now.minusDays(i);
            flow.add(wan(safe(ledgerRepository.sumNetUsdtFlowBetween(start, end))));
        }
        return flow;
    }

    private List<Map<String, Object>> funnelStagesFromOrders(LocalDateTime since) {
        List<Map<String, Object>> stages = new ArrayList<>(List.of(
                funnelStage("deposit", "入金", ledgerRepository.countDeposits(since, null), "nx_deposit_order", "var(--brand)"),
                funnelStage("exchange", "兑换", ledgerRepository.countExchanges(since, null), "nx_exchange_order", "var(--cyan)"),
                funnelStage("withdraw", "提现", ledgerRepository.countWithdrawals(since, null), "nx_withdrawal_order", "var(--warning)"),
                funnelStage("ledger", "账本流水", ledgerRepository.countLedgers(since, null), "nx_wallet_ledger", "var(--success)")));
        for (int i = 0; i < stages.size(); i++) {
            Object previousCount = i == 0 ? stages.get(i).get("ct") : stages.get(i - 1).get("ct");
            stages.get(i).put("prevCount", previousCount);
        }
        return stages;
    }

    private Map<String, Object> funnelStage(String key, String name, long count, String source, String color) {
        return section("key", key, "nm", name, "ct", count, "lc", source, "color", color);
    }

    private List<Map<String, Object>> funnelTransitions(List<Map<String, Object>> stages) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 1; i < stages.size(); i++) {
            Map<String, Object> from = stages.get(i - 1);
            Map<String, Object> to = stages.get(i);
            BigDecimal fromCount = decimal(from.get("ct"));
            BigDecimal toCount = decimal(to.get("ct"));
            BigDecimal conversion = pctScale(pct(toCount, fromCount));
            rows.add(section(
                    "from", from.get("key"),
                    "to", to.get("key"),
                    "a", from.get("nm") + "→" + to.get("nm"),
                    "v", conversion + "%",
                    "flow", fromCount.toPlainString() + " → " + toCount.toPlainString(),
                    "bad", fromCount.compareTo(BigDecimal.ZERO) > 0 && conversion.compareTo(new BigDecimal("30")) < 0));
        }
        return rows;
    }

    private List<Map<String, Object>> funnelChannelsFromOrders(LocalDateTime since) {
        return List.of(
                section("nm", "入金订单", "v", ledgerRepository.countDeposits(since, null), "c", "var(--brand)", "source", "nx_deposit_order"),
                section("nm", "兑换订单", "v", ledgerRepository.countExchanges(since, null), "c", "var(--cyan)", "source", "nx_exchange_order"),
                section("nm", "提现订单", "v", ledgerRepository.countWithdrawals(since, null), "c", "var(--warning)", "source", "nx_withdrawal_order"),
                section("nm", "账本流水", "v", ledgerRepository.countLedgers(since, null), "c", "var(--success)", "source", "nx_wallet_ledger"));
    }

    private List<BigDecimal> dailyDepositSeries(int points) {
        return netFlowSeries(points);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> rhythmPhaseNodesFromH1(Map<String, Object> dualLedger) {
        Map<String, Object> h1Rhythm = (Map<String, Object>) dualLedger.getOrDefault("h1Rhythm", Map.of());
        String currentPhase = String.valueOf(h1Rhythm.getOrDefault("currentPhase", ""));
        if (!StringUtils.hasText(currentPhase)) {
            return List.of();
        }
        return List.of(section(
                "code", currentPhase,
                "name", currentPhase,
                "intensity", decimal(h1Rhythm.get("phaseProgressPct")),
                "source", "H1 growth rhythm facade"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> liabilityBudgetFromDualLedger(Map<String, Object> dualLedger) {
        List<Map<String, Object>> accounts = (List<Map<String, Object>>) dualLedger.getOrDefault("accounts", List.of());
        return accounts.stream()
                .map(account -> section(
                        "nm", account.get("label"),
                        "v", wan(decimal(account.get("amount"))),
                        "source", account.get("source")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private List<BigDecimal> coverageRatioSeries(Map<String, Object> dualLedger) {
        Map<String, Object> snapshot = (Map<String, Object>) dualLedger.getOrDefault("snapshot", Map.of());
        Object series = snapshot.get("coverageSeries");
        if (series instanceof List<?> list) {
            return list.stream().map(this::decimal).toList();
        }
        BigDecimal coverage = decimal(snapshot.get("coverageRatio"));
        return coverage.compareTo(BigDecimal.ZERO) > 0 ? List.of(coverage) : List.of();
    }

    private BigDecimal wan(BigDecimal value) {
        return money(safe(value)).divide(new BigDecimal("10000"), 2, RoundingMode.HALF_UP);
    }

    private BigDecimal decimal(Object value) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        String text = String.valueOf(value)
                .trim()
                .replace("%", "")
                .replace(",", "")
                .replace("$", "");
        if (!StringUtils.hasText(text)) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(text);
        } catch (RuntimeException ex) {
            return BigDecimal.ZERO;
        }
    }

    private Integer k4Threshold(Object value) {
        if (value == null) return null;
        try {
            int threshold = decimal(value).intValueExact();
            return threshold >= 0 && threshold <= 100 ? threshold : null;
        } catch (ArithmeticException invalid) {
            return null;
        }
    }

    private String bAlertAckRequestHash(String normalizedAlertId, String reason, String operator) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(objectMapper.writeValueAsString(section(
                    "alertId", normalizedAlertId,
                    "reason", reason,
                    "operator", operator)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (JsonProcessingException ex) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "B_ALERT_ACK_HASH_FAILED");
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(500, "SHA256_UNAVAILABLE");
        }
    }

    private String reserveInjectionRequestHash(BigDecimal amount, String voucherNo, String reason, String operator) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(objectMapper.writeValueAsString(section(
                    "amount", amount,
                    "voucherNo", voucherNo,
                    "reason", reason,
                    "operator", operator)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (JsonProcessingException ex) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "B_RESERVE_INJECTION_HASH_FAILED");
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(500, "SHA256_UNAVAILABLE");
        }
    }

    private String structuredRequestHash(Map<String, Object> payload, String errorCode) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(objectMapper.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest.digest());
        } catch (JsonProcessingException ex) {
            throw new BizException(OpsErrorCode.VALIDATION_FAILED.httpStatus(), errorCode);
        } catch (NoSuchAlgorithmException ex) {
            throw new BizException(500, "SHA256_UNAVAILABLE");
        }
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

    private void requireD3Command(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            throw new BizException(400, OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        int length = StringUtils.hasText(reason) ? reason.trim().length() : 0;
        if (length < 8 || length > 200) {
            throw new BizException(400, OpsErrorCode.REASON_REQUIRED.name());
        }
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

    private void auditRequired(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
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

    private Map<String, Object> liability(String category, String label, BigDecimal amount, String source) {
        return section("category", category, "label", label, "amountUsdt", money(safe(amount)), "source", source);
    }

    private Map<String, Object> waterLevel(BigDecimal reserveTotal) {
        Map<String, Object> config = forecastConfigSnapshot();
        BigDecimal due = forecastTotal(config, 7);
        BigDecimal configuredReserve = categoryEnabled(config, "reserveCategories", "usdt")
                ? safe(reserveTotal)
                : BigDecimal.ZERO;
        BigDecimal dailyAverage = due.divide(BigDecimal.valueOf(7), 6, RoundingMode.HALF_UP);
        BigDecimal coverDays = reserveCoverDays(configuredReserve, dailyAverage);
        String tier;
        String color;
        String action;
        String notification;
        if (coverDays.compareTo(BigDecimal.valueOf(30)) >= 0) {
            tier = "NORMAL"; color = "green"; action = "常规运营，无需联动"; notification = "并入日报";
        } else if (coverDays.compareTo(BigDecimal.valueOf(15)) >= 0) {
            tier = "WATCH"; color = "cyan"; action = "加密监控到期曲线与净敞口"; notification = "财务日报标注";
        } else if (coverDays.compareTo(BigDecimal.valueOf(7)) >= 0) {
            tier = "WARNING"; color = "yellow"; action = "建议到 D5 收紧节奏并在 D2 延迟大额"; notification = "实时告警和值班响应";
        } else {
            tier = "DANGER"; color = "red"; action = "建议评估 J1 提现熔断并升级超管"; notification = "立即升级超管并通知 B5";
        }
        return section("tier", tier, "color", color, "reserveCoverDays", coverDays,
                "dailyAverageDueUsdt", money(dailyAverage), "suggestedAction", action, "notification", notification,
                "thresholds", List.of(
                        section("tier", "NORMAL", "condition", ">=30d"),
                        section("tier", "WATCH", "condition", "15-30d"),
                        section("tier", "WARNING", "condition", "7-15d"),
                        section("tier", "DANGER", "condition", "<7d")));
    }

    private BigDecimal reserveCoverDays(BigDecimal reserveTotal, BigDecimal dailyAverage) {
        if (dailyAverage == null || dailyAverage.signum() <= 0) {
            return reserveTotal != null && reserveTotal.signum() > 0 ? new BigDecimal("999.00") : BigDecimal.ZERO.setScale(2);
        }
        return safe(reserveTotal).divide(dailyAverage, 2, RoundingMode.HALF_UP);
    }

    private int d3WindowDays(String window, Set<Integer> allowed) {
        String normalized = StringUtils.hasText(window) ? window.trim().toLowerCase(Locale.ROOT) : "7d";
        int days;
        try {
            days = Integer.parseInt(normalized.endsWith("d") ? normalized.substring(0, normalized.length() - 1) : normalized);
        } catch (RuntimeException ex) {
            throw new BizException(400, "D3_WINDOW_INVALID");
        }
        if (!allowed.contains(days)) {
            throw new BizException(400, "D3_WINDOW_INVALID");
        }
        return days;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> forecastConfigSnapshot() {
        promoteDueForecastConfig();
        return activeForecastConfigSnapshot();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> activeForecastConfigSnapshot() {
        Map<String, Object> defaults = new LinkedHashMap<>();
        Map<String, Boolean> reserves = new LinkedHashMap<>();
        D3_RESERVE_CATEGORIES.forEach(key -> reserves.put(key, true));
        defaults.put("reserveCategories", reserves);
        Map<String, Boolean> liabilities = new LinkedHashMap<>();
        for (String key : D3_LIABILITY_CATEGORIES) {
            liabilities.put(key, true);
        }
        defaults.put("liabilityCategories", liabilities);
        defaults.put("forecastWindow", "7d");
        defaults.put("genesisIncluded", true);
        defaults.put("includeFarLiabilities", false);
        defaults.put("stakingInterestMode", "LINEAR");
        defaults.put("trialStressEnabled", false);
        return configFacade.activeValue(D3_FORECAST_CONFIG_KEY).map(raw -> {
            try {
                Map<String, Object> parsed = objectMapper.readValue(raw, LinkedHashMap.class);
                validateStoredForecastConfig(parsed);
                defaults.putAll(parsed);
            } catch (JsonProcessingException | RuntimeException ignored) {
                // Fail closed to the documented conservative defaults; never coerce an absent liability to zero.
            }
            return defaults;
        }).orElse(defaults);
    }

    private Map<String, Object> forecastConfigCandidateSnapshot() {
        return pendingForecastConfig()
                .filter(pending -> clock.instant().isBefore(pending.effectiveAt()))
                .<Map<String, Object>>map(pending -> new LinkedHashMap<>(pending.config()))
                .orElseGet(this::activeForecastConfigSnapshot);
    }

    private void promoteDueForecastConfig() {
        String effectiveRaw = configFacade.activeValueForUpdate(D3_FORECAST_CONFIG_PENDING_EFFECTIVE_AT_KEY)
                .filter(StringUtils::hasText)
                .orElse(null);
        if (!StringUtils.hasText(effectiveRaw)) {
            return;
        }
        Instant effectiveAt;
        try {
            effectiveAt = Instant.parse(effectiveRaw);
        } catch (RuntimeException invalid) {
            return;
        }
        if (clock.instant().isBefore(effectiveAt)) {
            return;
        }
        PendingForecastConfig pending = pendingForecastConfig().orElse(null);
        if (pending == null) {
            return;
        }
        long pendingVersion = configVersion(D3_FORECAST_CONFIG_PENDING_VERSION_KEY);
        long activeVersion = configVersion(D3_FORECAST_CONFIG_ACTIVE_VERSION_KEY);
        if (pendingVersion <= 0) {
            pendingVersion = Math.max(configVersion(D3_FORECAST_CONFIG_VERSION_KEY), activeVersion + 1L);
        }
        try {
            String activeJson = objectMapper.writeValueAsString(pending.config());
            configFacade.upsertAdminValue(D3_FORECAST_CONFIG_KEY, activeJson, "JSON", "treasury", "D3 active structured forecast configuration");
            configFacade.upsertAdminValue(D3_FORECAST_CONFIG_ACTIVE_VERSION_KEY, String.valueOf(pendingVersion), "NUMBER", "treasury", "D3 active forecast configuration version");
            configFacade.upsertAdminValue(
                    D3_FORECAST_CONFIG_KEY + ".snapshot." + pendingVersion,
                    objectMapper.writeValueAsString(section(
                            "version", pendingVersion,
                            "effectiveAt", effectiveAt.toString(),
                            "promotedAt", clock.instant().toString(),
                            "config", pending.config())),
                    "JSON", "treasury-history", "D3 immutable effective forecast configuration snapshot");
        } catch (JsonProcessingException ex) {
            throw new BizException(500, "D3_FORECAST_CONFIG_PROMOTION_FAILED");
        }
        configFacade.upsertAdminValue(D3_FORECAST_CONFIG_PENDING_KEY, "", "JSON", "treasury", "D3 pending structured forecast configuration");
        configFacade.upsertAdminValue(D3_FORECAST_CONFIG_PENDING_EFFECTIVE_AT_KEY, "", "STRING", "treasury", "D3 pending forecast configuration UTC effective time");
        configFacade.upsertAdminValue(D3_FORECAST_CONFIG_PENDING_VERSION_KEY, "", "NUMBER", "treasury", "D3 pending forecast configuration version");
    }

    @SuppressWarnings("unchecked")
    private Optional<PendingForecastConfig> pendingForecastConfig() {
        Optional<String> raw = configFacade.activeValue(D3_FORECAST_CONFIG_PENDING_KEY);
        Optional<String> effectiveRaw = configFacade.activeValue(D3_FORECAST_CONFIG_PENDING_EFFECTIVE_AT_KEY);
        if (raw.isEmpty() || effectiveRaw.isEmpty() || !StringUtils.hasText(raw.get()) || !StringUtils.hasText(effectiveRaw.get())) {
            return Optional.empty();
        }
        try {
            Map<String, Object> parsed = objectMapper.readValue(raw.get(), LinkedHashMap.class);
            Instant effectiveAt = Instant.parse(effectiveRaw.get());
            validateStoredForecastConfig(parsed);
            return Optional.of(new PendingForecastConfig(parsed, effectiveAt));
        } catch (JsonProcessingException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private void validateStoredForecastConfig(Map<String, Object> config) {
        d3WindowDays(String.valueOf(config.get("forecastWindow")), Set.of(7, 30, 90));
        String mode = String.valueOf(config.get("stakingInterestMode")).toUpperCase(Locale.ROOT);
        if (!Set.of("LINEAR", "AT_MATURITY").contains(mode)
                || !(config.get("genesisIncluded") instanceof Boolean)
                || !(config.get("includeFarLiabilities") instanceof Boolean)
                || !(config.get("trialStressEnabled") instanceof Boolean)
                || !(config.get("reserveCategories") instanceof Map<?, ?> reserveCategories)
                || !(config.get("liabilityCategories") instanceof Map<?, ?> liabilityCategories)
                || !reserveCategories.keySet().equals(Set.copyOf(D3_RESERVE_CATEGORIES))
                || !liabilityCategories.keySet().equals(Set.copyOf(D3_LIABILITY_CATEGORIES))
                || reserveCategories.values().stream().anyMatch(value -> !(value instanceof Boolean))
                || liabilityCategories.values().stream().anyMatch(value -> !(value instanceof Boolean))) {
            throw new IllegalArgumentException("D3_FORECAST_CONFIG_INVALID");
        }
    }

    private record PendingForecastConfig(Map<String, Object> config, Instant effectiveAt) {}

    private long configVersion(String key) {
        return configFacade.activeValue(key).map(this::parseConfigVersion).orElse(0L);
    }

    private long parseConfigVersion(String value) {
        try {
            return Long.parseLong(value == null ? "0" : value.trim());
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private Map<String, Object> validateForecastConfig(TreasuryForecastConfigRequest request, Map<String, Object> before) {
        if (request == null) {
            throw new BizException(400, "D3_FORECAST_CONFIG_REQUIRED");
        }
        String window = StringUtils.hasText(request.forecastWindow()) ? request.forecastWindow().trim().toLowerCase(Locale.ROOT) : String.valueOf(before.get("forecastWindow"));
        d3WindowDays(window, Set.of(7, 30, 90));
        String mode = StringUtils.hasText(request.stakingInterestMode()) ? request.stakingInterestMode().trim().toUpperCase(Locale.ROOT) : String.valueOf(before.get("stakingInterestMode"));
        if (!Set.of("LINEAR", "AT_MATURITY").contains(mode)) {
            throw new BizException(400, "D3_STAKING_INTEREST_MODE_INVALID");
        }
        Map<String, Object> after = new LinkedHashMap<>(before);
        if (request.reserveCategories() != null) after.put("reserveCategories",
                validatedCategorySwitches(request.reserveCategories(), D3_RESERVE_CATEGORIES, "D3_RESERVE_CATEGORIES_INVALID"));
        if (request.liabilityCategories() != null) after.put("liabilityCategories",
                validatedCategorySwitches(request.liabilityCategories(), D3_LIABILITY_CATEGORIES, "D3_LIABILITY_CATEGORIES_INVALID"));
        after.put("forecastWindow", window);
        after.put("genesisIncluded", request.genesisIncluded() == null ? before.get("genesisIncluded") : request.genesisIncluded());
        after.put("includeFarLiabilities", request.includeFarLiabilities() == null ? before.get("includeFarLiabilities") : request.includeFarLiabilities());
        after.put("stakingInterestMode", mode);
        after.put("trialStressEnabled", request.trialStressEnabled() == null ? before.get("trialStressEnabled") : request.trialStressEnabled());
        return after;
    }

    private Map<String, Boolean> validatedCategorySwitches(
            Map<String, Boolean> values, List<String> requiredKeys, String errorCode) {
        if (!values.keySet().equals(Set.copyOf(requiredKeys))
                || values.values().stream().anyMatch(java.util.Objects::isNull)) {
            throw new BizException(400, errorCode);
        }
        Map<String, Boolean> ordered = new LinkedHashMap<>();
        requiredKeys.forEach(key -> ordered.put(key, values.get(key)));
        return ordered;
    }

    private Map<String, Object> forecastDeltaPreview(Map<String, Object> before, Map<String, Object> after) {
        BigDecimal genesisDaily = safe(ledgerRepository.genesisDailyLiabilityUsd());
        boolean beforeGenesis = Boolean.TRUE.equals(before.get("genesisIncluded"));
        boolean afterGenesis = Boolean.TRUE.equals(after.get("genesisIncluded"));
        BigDecimal beforeDaily = beforeGenesis ? genesisDaily : BigDecimal.ZERO;
        BigDecimal afterDaily = afterGenesis ? genesisDaily : BigDecimal.ZERO;
        BigDecimal before7d = forecastTotal(before, 7);
        BigDecimal after7d = forecastTotal(after, 7);
        BigDecimal before30d = forecastTotal(before, 30);
        BigDecimal after30d = forecastTotal(after, 30);
        return section("beforeDailyGenesisUsdt", money(beforeDaily), "afterDailyGenesisUsdt", money(afterDaily),
                "dailyDeltaUsdt", money(afterDaily.subtract(beforeDaily)),
                "before7dUsdt", money(before7d), "after7dUsdt", money(after7d),
                "delta7dUsdt", money(after7d.subtract(before7d)),
                "before30dUsdt", money(before30d), "after30dUsdt", money(after30d),
                "delta30dUsdt", money(after30d.subtract(before30d)),
                "reserveCategoryChanges", changedCategoryCount(before, after, "reserveCategories"),
                "liabilityCategoryChanges", changedCategoryCount(before, after, "liabilityCategories"),
                "note", "科目纳入开关下一日批 UTC 00:00 生效且不追溯历史快照");
    }

    private BigDecimal forecastTotal(Map<String, Object> config, int days) {
        LocalDateTime startAt = LocalDateTime.now(clock).toLocalDate().atStartOfDay();
        LocalDateTime endAt = startAt.plusDays(days);
        boolean withdrawalIncluded = categoryEnabled(config, "liabilityCategories", "withdrawal_queue");
        boolean interestIncluded = categoryEnabled(config, "liabilityCategories", "staking_interest");
        BigDecimal due = ledgerRepository.maturityBuckets(
                        startAt, endAt, d3WithdrawCooldownDays(), String.valueOf(config.get("stakingInterestMode")))
                .stream()
                .map(row -> (withdrawalIncluded ? decimal(row.get("withdrawUsd")) : BigDecimal.ZERO)
                        .add(interestIncluded ? decimal(row.get("interestUsd")) : BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (Boolean.TRUE.equals(config.get("genesisIncluded"))
                && categoryEnabled(config, "liabilityCategories", "genesis_daily_emission")) {
            due = due.add(safe(ledgerRepository.genesisDailyLiabilityUsd()).multiply(BigDecimal.valueOf(days)));
        }
        if (Boolean.TRUE.equals(config.get("trialStressEnabled"))) {
            due = due.add(ledgerRepository.trialStressBuckets(startAt, endAt).stream()
                    .map(row -> decimal(row.get("amountUsdt")))
                    .reduce(BigDecimal.ZERO, BigDecimal::add));
        }
        return money(due);
    }

    private BigDecimal configuredReserveTotal(Map<String, Object> config) {
        Map<String, Object> canonical = reserve().getData();
        BigDecimal total = categoryEnabled(config, "reserveCategories", "usdt")
                ? decimal(canonical.get("reserveTotalUsdt"))
                : BigDecimal.ZERO;
        if (categoryEnabled(config, "reserveCategories", "otherLiquid")) {
            total = total.add(decimal(canonical.get("otherLiquidUsdt")));
        }
        return money(total);
    }

    private int d3WithdrawCooldownDays() {
        String raw = configFacade.activeValue(D3_WITHDRAW_COOLDOWN_DAYS_KEY)
                .filter(StringUtils::hasText)
                .or(() -> configFacade.activeValue(D3_WITHDRAW_COOLDOWN_DAYS_LEGACY_KEY).filter(StringUtils::hasText))
                .orElse("30");
        try {
            int value = new BigDecimal(raw.trim()).setScale(0, RoundingMode.UNNECESSARY).intValueExact();
            return Math.max(1, Math.min(value, 365));
        } catch (RuntimeException ignored) {
            return 30;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean categoryEnabled(Map<String, Object> config, String group, String category) {
        Object value = config.get(group);
        if (!(value instanceof Map<?, ?> categories)) {
            return true;
        }
        Object enabled = categories.get(category);
        return enabled == null || Boolean.TRUE.equals(enabled);
    }

    private int changedCategoryCount(Map<String, Object> before, Map<String, Object> after, String group) {
        Object beforeValue = before.get(group);
        Object afterValue = after.get(group);
        if (!(beforeValue instanceof Map<?, ?> beforeMap) || !(afterValue instanceof Map<?, ?> afterMap)) {
            return 0;
        }
        Set<Object> keys = new java.util.HashSet<>(beforeMap.keySet());
        keys.addAll(afterMap.keySet());
        return (int) keys.stream().filter(key -> !java.util.Objects.equals(beforeMap.get(key), afterMap.get(key))).count();
    }

    private String csvCell(Object value) {
        String normalized = value == null ? "" : String.valueOf(value).replace("\r", " ").replace("\n", " ");
        String stripped = normalized.stripLeading();
        if (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0)) >= 0) normalized = "'" + normalized;
        return "\"" + normalized.replace("\"", "\"\"") + "\"";
    }

    private void ensureD3FallbackSeedData() {
    }

    private void ensureD4FallbackSeedData() {
        // D4 ledger bills are business records. Empty tables must remain empty until real posting flows create rows.
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

    private void ensureD3ConfigDefaults() {
        seedConfigIfAbsent(REDLINE_CONFIG_KEY, safe(dualLedgerProperties.getRedlinePct()).toPlainString(), "NUMBER", "wallet", "D3 coverage redline");
        seedConfigIfAbsent(HEALTHY_CONFIG_KEY, safe(dualLedgerProperties.getHealthyPct()).toPlainString(), "NUMBER", "wallet", "D3 healthy coverage");
        seedConfigIfAbsent(RUN_RISK_CONFIG_KEY, safe(dualLedgerProperties.getRunRiskPct()).toPlainString(), "NUMBER", "wallet", "D3 run risk threshold");
        seedConfigIfAbsent(SCOPE_CONFIG_KEY, "all active liabilities", "STRING", "wallet", "D3 dual-ledger scope");
    }

    private void seedConfigIfAbsent(String key, String value, String type, String group, String remark) {
        // Intentionally empty: read paths must not seed treasury configuration.
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
        String canonical = switch (normalized.trim().toLowerCase(Locale.ROOT)) {
            case "swap", "exchange", "convert" -> "swap";
            case "topup", "deposit", "card_topup", "recharge" -> "topup";
            case "withdraw", "withdrawal", "payout" -> "withdraw";
            case "earning", "earnings", "adjustment", "reward" -> "earning";
            case "commission", "team_commission" -> "commission";
            case "refund", "chargeback", "chargeback_recovery", "reversal" -> "refund";
            case "bonus", "trial_bonus" -> "bonus";
            default -> null;
        };
        if (canonical == null || !D4_BILL_TYPES.contains(canonical)) {
            throw new BizException(400, "D4_BILL_TYPE_INVALID");
        }
        return canonical;
    }

    private String normalizeD4Status(String value) {
        String normalized = trimToNull(value);
        if (normalized == null || "all".equalsIgnoreCase(normalized)) return null;
        String status = normalized.toUpperCase(Locale.ROOT);
        if (!status.matches("[A-Z][A-Z0-9_]{1,31}")) {
            throw new BizException(400, "D4_BILL_STATUS_INVALID");
        }
        return status;
    }

    private LocalDateTime parseD4Time(String value, boolean upperExclusive) {
        String normalized = trimToNull(value);
        if (normalized == null) return null;
        try {
            if (normalized.length() == 10) {
                LocalDateTime start = LocalDate.parse(normalized).atStartOfDay();
                return upperExclusive ? start.plusDays(1) : start;
            }
            return LocalDateTime.parse(normalized);
        } catch (DateTimeParseException ex) {
            throw new BizException(400, "D4_TIME_INVALID");
        }
    }

    private void requireD4TimeRange(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && !from.isBefore(to)) {
            throw new BizException(400, "D4_TIME_RANGE_INVALID");
        }
    }

    private String maskUserNo(String value) {
        String normalized = trimToNull(value);
        if (normalized == null) return "**";
        if (normalized.length() <= 2) return "**";
        return normalized.substring(0, 1) + "*".repeat(Math.max(2, normalized.length() - 3))
                + normalized.substring(normalized.length() - 2);
    }

    private boolean isCreditDirection(String direction) {
        return "IN".equalsIgnoreCase(direction) || "CREDIT".equalsIgnoreCase(direction);
    }

    private String formatUserNo(Long userId) {
        return "U%08d".formatted(userId == null ? 0 : userId);
    }

    private BigDecimal threshold(BigDecimal value, String fieldName, BigDecimal min, BigDecimal max) {
        BigDecimal normalized = safe(value).setScale(1, RoundingMode.HALF_UP);
        if (normalized.compareTo(min) <= 0 || normalized.compareTo(max) > 0) {
            throw new IllegalArgumentException(fieldName + " is out of range");
        }
        return normalized;
    }

    private BigDecimal bankRunThreshold(String value, String fieldName, BigDecimal min, BigDecimal max) {
        try {
            BigDecimal normalized = new BigDecimal(value.trim()).setScale(1, RoundingMode.HALF_UP);
            if (normalized.compareTo(min) < 0 || normalized.compareTo(max) > 0) {
                throw new NumberFormatException("out of range");
            }
            return normalized;
        } catch (RuntimeException ex) {
            throw new BizException(
                    OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                    "BANKRUN_" + fieldName.toUpperCase(Locale.ROOT) + "_OUT_OF_RANGE");
        }
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
                .orElse(BigDecimal.ZERO);
    }

    private BigDecimal requiredConfigDecimal(String key) {
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .map(value -> {
                    try {
                        return new BigDecimal(value.trim());
                    } catch (RuntimeException ex) {
                        throw new IllegalStateException("INVALID_CONFIG:" + key, ex);
                    }
                })
                .orElseThrow(() -> new IllegalStateException("CONFIG_NOT_FOUND:" + key));
    }

    private String requiredConfigValue(String key) {
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new IllegalStateException("CONFIG_NOT_FOUND:" + key));
    }

    private BigDecimal safetyThresholdDecimal(String key, BigDecimal fallback) {
        return configFacade.activeValue(key)
                .map(value -> parseDecimalWithFallback(value, fallback))
                .orElseGet(() -> safe(fallback));
    }

    private String configValue(String key, String fallback) {
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .orElse(fallback);
    }

    private BigDecimal parseDecimal(String value, BigDecimal fallback) {
        try {
            return new BigDecimal(value.trim());
        } catch (RuntimeException ex) {
            return BigDecimal.ZERO;
        }
    }

    private BigDecimal parseDecimalWithFallback(String value, BigDecimal fallback) {
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
        for (int i = 0; i < 8; i++) {
            LocalDateTime point = now.minusHours((7L - i) * 3L);
            BigDecimal flowAfterPoint = safe(ledgerRepository.sumNetUsdtFlowBetween(point, now));
            BigDecimal historicalReserve = reserveUsd.subtract(flowAfterPoint).max(BigDecimal.ZERO);
            series.add(pctScale(pct(historicalReserve, liabilitiesUsd)));
        }
        return series;
    }

    private List<Map<String, Object>> maturity7d() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> daily = (List<Map<String, Object>>) maturityForecast("7d").getData().get("daily");
        return daily.stream()
                .map(row -> section(
                        "day", String.valueOf(row.get("date")),
                        "withdrawUsd", money(decimal(row.get("withdrawDueUsdt"))),
                        "interestUsd", money(decimal(row.get("interestDueUsdt"))),
                        "genesisUsd", money(decimal(row.get("genesisDividendUsdt"))),
                        "trialStressUsd", money(decimal(row.get("trialShadowStressUsdt")))))
                .toList();
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

    private record GateSeed(String key, String name) {
    }
}

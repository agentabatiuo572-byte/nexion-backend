package ffdd.opsconsole.treasury.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.dto.TreasuryAlertAckRequest;
import ffdd.opsconsole.treasury.dto.TreasuryInjectionRequest;
import ffdd.opsconsole.treasury.dto.TreasuryLedgerAdjustmentRequest;
import ffdd.opsconsole.treasury.dto.TreasuryLedgerQueryRequest;
import ffdd.opsconsole.treasury.dto.TreasuryScopeRequest;
import ffdd.opsconsole.treasury.dto.TreasuryThresholdRequest;
import ffdd.opsconsole.user.domain.UserSeedRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
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
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {
    };
    private static final TypeReference<List<BigDecimal>> DECIMAL_LIST_TYPE = new TypeReference<>() {
    };
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;
    private static final String RESERVE_CONFIG_KEY = "wallet.dual-ledger.reserve-usd";
    private static final String NEX_USD_RATE_CONFIG_KEY = "wallet.dual-ledger.nex-usd-rate";
    private static final String REDLINE_CONFIG_KEY = "wallet.dual-ledger.redline-pct";
    private static final String HEALTHY_CONFIG_KEY = "wallet.dual-ledger.healthy-pct";
    private static final String RUN_RISK_CONFIG_KEY = "wallet.dual-ledger.run-risk-pct";
    private static final String SCOPE_CONFIG_KEY = "wallet.dual-ledger.scope";
    private static final String B_LIQUIDITY_RUNWAY_KEY = "treasury.b.liquidity.runway";
    private static final String B_LIQUIDITY_FLOW_KEY = "treasury.b.liquidity.flow";
    private static final String B_FUNNEL_STAGES_KEY = "treasury.b.funnel.stages";
    private static final String B_FUNNEL_TRANSITIONS_KEY = "treasury.b.funnel.transitions";
    private static final String B_FUNNEL_COHORT_KEY = "treasury.b.funnel.cohort";
    private static final String B_FUNNEL_CHANNELS_KEY = "treasury.b.funnel.channels";
    private static final String B_FUNNEL_DAILY_KEY = "treasury.b.funnel.daily";
    private static final String B_FUNNEL_DAILY_TARGET_KEY = "treasury.b.funnel.daily-target";
    private static final String B_RHYTHM_PHASES_KEY = "treasury.b.rhythm.phases";
    private static final String B_RHYTHM_INFLOW_KEY = "treasury.b.rhythm.inflow";
    private static final String B_RHYTHM_BUDGET_KEY = "treasury.b.rhythm.budget";
    private static final String B_RHYTHM_RATIO_KEY = "treasury.b.rhythm.ratio";
    private static final String B_RHYTHM_HEALTHY_RATIO_KEY = "treasury.b.rhythm.healthy-ratio";
    private static final String B_RISK_FEED_KEY = "treasury.b.risk.feed";
    private static final String B_RISK_PRESSURE_KEY = "treasury.b.risk.pressure";
    private static final String B_RISK_PRESSURE_TIGHT_KEY = "treasury.b.risk.pressure-tight-pct";
    private static final String B_RISK_RULES_KEY = "treasury.b.risk.rules";
    private static final String B_RISK_SEVERITY_KEY = "treasury.b.risk.severity";
    private static final String B_RISK_VOLUME_KEY = "treasury.b.risk.volume";
    private static final String B_ALERT_COVERAGE_ID = "coverage-redline";
    private static final String B_ALERT_COVERAGE_ACK_KEY = "treasury.b.alert.coverage-redline.ack";
    private static final String B_ALERT_ACK_IDEMPOTENCY_SCOPE = "TREASURY_B_ALERT_ACK";
    private static final List<GateSeed> B_RISK_GATE_SEEDS = List.of(
            new GateSeed("withdraw", "提现闸"),
            new GateSeed("exchange", "兑换闸"),
            new GateSeed("staking", "算力质押闸"),
            new GateSeed("genesis", "Genesis 闸"),
            new GateSeed("trial", "试用闸"));
    private static final List<String> D_SEED_USER_KEYS = List.of(
            "usr_77D4", "usr_31E8", "usr_2231", "usr_55B1", "usr_8807");

    private final TreasuryLedgerRepository ledgerRepository;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final Clock clock;
    private final TreasuryDualLedgerProperties dualLedgerProperties;
    private final UserSeedRepository userSeedRepository;
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

    public ApiResult<Map<String, Object>> dualLedger() {
        ensureD3FallbackSeedData();
        return ApiResult.ok(dualLedgerSnapshot());
    }

    private Map<String, Object> dualLedgerSnapshot() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime current24hStart = now.minusHours(24);
        LocalDateTime prev24hStart = now.minusHours(48);
        BigDecimal reserveUsd = configDecimal(RESERVE_CONFIG_KEY, dualLedgerProperties.getReserveUsd());
        BigDecimal nexUsdRate = configDecimal(NEX_USD_RATE_CONFIG_KEY, dualLedgerProperties.getNexUsdRate());
        BigDecimal redlinePct = safetyThresholdDecimal(REDLINE_CONFIG_KEY, dualLedgerProperties.getRedlinePct());
        BigDecimal healthyPct = safetyThresholdDecimal(HEALTHY_CONFIG_KEY, dualLedgerProperties.getHealthyPct());
        BigDecimal runRiskPct = safetyThresholdDecimal(RUN_RISK_CONFIG_KEY, dualLedgerProperties.getRunRiskPct());
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
                "sources", List.of("nx_user_wallet", "nx_wallet_ledger", "nx_withdrawal_order", "nx_staking_position", "nx_nex_lock_order", "nx_config_item", "H1 growth rhythm facade"));
        response.put("h1Rhythm", GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy).summary());
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
                "netFlow24hUsd", money(prevNetFlow24hUsd),
                "queueBacklogCount", ledgerRepository.countActiveWithdrawalQueue(),
                "avgRiskScore", avgRiskScore.longValue()));
        return response;
    }

    public ApiResult<Map<String, Object>> bDomainDashboard() {
        Map<String, Object> dualLedger = dualLedgerSnapshot();
        List<Map<String, Object>> warnings = new ArrayList<>();
        Map<String, Object> response = section(
                "service", "nexion-backend",
                "domain", "B",
                "generatedAt", LocalDateTime.now(clock),
                "sources", List.of(
                        "nx_config_item:treasury.b.*",
                        "nx_user_wallet",
                        "nx_wallet_ledger",
                        "nx_withdrawal_order",
                        "nx_staking_position",
                        "nx_config_item:killswitch.*",
                        "nx_config_item:emergency.killswitch.*"));
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
                : safetyThresholdDecimal(REDLINE_CONFIG_KEY, dualLedgerProperties.getRedlinePct());
        BigDecimal healthy = hasHealthy
                ? threshold(request.healthyPct(), "healthyPct", BigDecimal.ZERO, BigDecimal.valueOf(250))
                : safetyThresholdDecimal(HEALTHY_CONFIG_KEY, dualLedgerProperties.getHealthyPct());
        BigDecimal runRisk = hasRunRisk
                ? threshold(request.runRiskPct(), "runRiskPct", BigDecimal.ZERO, BigDecimal.valueOf(100))
                : safetyThresholdDecimal(RUN_RISK_CONFIG_KEY, dualLedgerProperties.getRunRiskPct());
        if (redline.compareTo(healthy) >= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REDLINE_MUST_BE_BELOW_HEALTHY");
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
        if (isCreditDirection(direction) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "B1_COVERAGE_REDLINE_BREACHED");
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

    @SuppressWarnings("unchecked")
    private boolean coverageBelowRedline() {
        Map<String, Object> snapshot = (Map<String, Object>) dualLedgerSnapshot().getOrDefault("snapshot", Map.of());
        return decimal(snapshot.get("coverageRatio")).compareTo(decimal(snapshot.get("redlinePct"))) < 0;
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
        List<Map<String, Object>> runway = readJsonRows(B_LIQUIDITY_RUNWAY_KEY, defaultLiquidityRunway(), "B2 liquidity runway seed", warnings);
        List<BigDecimal> flow = readDecimalList(B_LIQUIDITY_FLOW_KEY, defaultLiquidityFlow(), "B2 liquidity flow seed", warnings);
        BigDecimal runwayTotalWan = runway.stream()
                .map(row -> decimal(row.get("valueWan")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> response = section(
                "coverage", snapshot,
                "liabilities", accounts,
                "runway", runway,
                "runwayTotalWan", runwayTotalWan,
                "flow", flowRows(flow),
                "sources", List.of("nx_config_item:" + B_LIQUIDITY_RUNWAY_KEY, "nx_config_item:" + B_LIQUIDITY_FLOW_KEY, "B1 dualLedger"));
        return response;
    }

    private Map<String, Object> funnelDashboard(List<Map<String, Object>> warnings) {
        List<Map<String, Object>> stages = readJsonRows(B_FUNNEL_STAGES_KEY, defaultFunnelStages(), "B3 funnel stage seed", warnings);
        List<Map<String, Object>> transitions = readJsonRows(B_FUNNEL_TRANSITIONS_KEY, defaultFunnelTransitions(), "B3 funnel transition seed", warnings);
        List<BigDecimal> cohort = readDecimalList(B_FUNNEL_COHORT_KEY, defaultFunnelCohort(), "B3 funnel cohort seed", warnings);
        List<Map<String, Object>> channels = readJsonRows(B_FUNNEL_CHANNELS_KEY, defaultFunnelChannels(), "B3 funnel channel seed", warnings);
        List<BigDecimal> daily = readDecimalList(B_FUNNEL_DAILY_KEY, defaultFunnelDaily(), "B3 funnel daily seed", warnings);
        BigDecimal dailyTarget = readDecimalSeed(B_FUNNEL_DAILY_TARGET_KEY, new BigDecimal("18"), "B3 funnel daily target seed", warnings);
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
                        "nx_config_item:" + B_FUNNEL_STAGES_KEY,
                        "nx_config_item:" + B_FUNNEL_TRANSITIONS_KEY,
                        "nx_config_item:" + B_FUNNEL_COHORT_KEY,
                        "nx_config_item:" + B_FUNNEL_CHANNELS_KEY,
                        "nx_config_item:" + B_FUNNEL_DAILY_KEY,
                        "nx_config_item:" + B_FUNNEL_DAILY_TARGET_KEY));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> rhythmDashboard(Map<String, Object> dualLedger, List<Map<String, Object>> warnings) {
        List<Map<String, Object>> phaseNodes = readJsonRows(B_RHYTHM_PHASES_KEY, defaultRhythmPhases(), "B4 rhythm phase seed", warnings);
        List<BigDecimal> inflow = readDecimalList(B_RHYTHM_INFLOW_KEY, defaultRhythmInflow(), "B4 rhythm inflow seed", warnings);
        List<Map<String, Object>> budget = readJsonRows(B_RHYTHM_BUDGET_KEY, defaultRhythmBudget(), "B4 rhythm budget seed", warnings);
        List<BigDecimal> ratio = readDecimalList(B_RHYTHM_RATIO_KEY, defaultRhythmRatio(), "B4 rhythm ratio seed", warnings);
        BigDecimal healthyRatio = readDecimalSeed(B_RHYTHM_HEALTHY_RATIO_KEY, new BigDecimal("1.2"), "B4 rhythm healthy ratio seed", warnings);
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
                        "nx_config_item:" + B_RHYTHM_PHASES_KEY,
                        "nx_config_item:" + B_RHYTHM_INFLOW_KEY,
                        "nx_config_item:" + B_RHYTHM_BUDGET_KEY,
                        "nx_config_item:" + B_RHYTHM_RATIO_KEY,
                        "nx_config_item:" + B_RHYTHM_HEALTHY_RATIO_KEY));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> riskRadarDashboard(Map<String, Object> dualLedger, List<Map<String, Object>> warnings) {
        Map<String, Object> snapshot = (Map<String, Object>) dualLedger.getOrDefault("snapshot", Map.of());
        List<Map<String, Object>> configuredFeed = readJsonRows(B_RISK_FEED_KEY, defaultRiskFeed(), "B5 risk feed seed", warnings);
        List<Map<String, Object>> feed = new ArrayList<>();
        feed.add(dynamicCoverageFeed(snapshot));
        feed.addAll(configuredFeed);
        List<BigDecimal> pressure = readDecimalList(B_RISK_PRESSURE_KEY, defaultRiskPressure(), "B5 risk pressure seed", warnings);
        List<Map<String, Object>> rules = readJsonRows(B_RISK_RULES_KEY, defaultRiskRules(), "B5 risk rule seed", warnings);
        List<Map<String, Object>> severity = readJsonRows(B_RISK_SEVERITY_KEY, defaultRiskSeverity(), "B5 risk severity seed", warnings);
        List<Map<String, Object>> volume = readJsonRows(B_RISK_VOLUME_KEY, defaultRiskVolume(), "B5 risk volume seed", warnings);
        BigDecimal pressureTightPct = readDecimalSeed(B_RISK_PRESSURE_TIGHT_KEY, new BigDecimal("70"), "B5 risk pressure threshold seed", warnings);
        BigDecimal reserveUsd = decimal(snapshot.get("reserveUsd"));
        BigDecimal queueBacklogUsd = decimal(snapshot.get("queueBacklogUsd"));
        BigDecimal bankRunRatio = pctScale(pct(queueBacklogUsd, reserveUsd));
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
                "flaggedAccounts", rules.stream().map(row -> decimal(row.get("ct"))).reduce(BigDecimal.ZERO, BigDecimal::add),
                "severity", severity,
                "volume", volume,
                "bankRunRatio", bankRunRatio,
                "sources", List.of(
                        "nx_config_item:killswitch.*",
                        "nx_config_item:emergency.killswitch.*",
                        "nx_config_item:" + B_RISK_FEED_KEY,
                        "nx_config_item:" + B_RISK_PRESSURE_KEY,
                        "nx_config_item:" + B_RISK_PRESSURE_TIGHT_KEY,
                        "nx_config_item:" + B_RISK_RULES_KEY,
                        "nx_config_item:" + B_RISK_SEVERITY_KEY,
                        "nx_config_item:" + B_RISK_VOLUME_KEY,
                        "B1 dualLedger"));
    }

    private List<Map<String, Object>> riskGates() {
        List<Map<String, Object>> gates = new ArrayList<>();
        for (GateSeed seed : B_RISK_GATE_SEEDS) {
            String key = seed.key();
            String configKey = "killswitch." + key;
            String emergencyKey = "emergency.killswitch." + key;
            String sourceKey = null;
            String configured = configFacade.activeValue(configKey).filter(StringUtils::hasText).orElse(null);
            if (StringUtils.hasText(configured)) {
                sourceKey = configKey;
            }
            if (!StringUtils.hasText(configured)) {
                configured = configFacade.activeValue(emergencyKey).filter(StringUtils::hasText).orElse(null);
                if (StringUtils.hasText(configured)) {
                    sourceKey = emergencyKey;
                }
            }
            boolean configuredPresent = StringUtils.hasText(configured);
            if (!configuredPresent && !readTimeSeedPolicy.enabled()) {
                continue;
            }
            boolean on = !configuredPresent || enabledFromConfig(configured);
            String state = configuredPresent ? (on ? "on" : "off") : "missing";
            gates.add(section("nm", seed.name(), "dom", key, "on", on, "state", state, "configKey", sourceKey));
        }
        return gates;
    }

    private Map<String, Object> dynamicCoverageFeed(Map<String, Object> snapshot) {
        BigDecimal reserve = decimal(snapshot.get("reserveUsd"));
        BigDecimal queue = decimal(snapshot.get("queueBacklogUsd"));
        BigDecimal bankRun = pctScale(pct(queue, reserve));
        BigDecimal coverage = decimal(snapshot.get("coverageRatio"));
        String severity = bankRun.compareTo(new BigDecimal("40")) >= 0 ? "p0" : bankRun.compareTo(new BigDecimal("20")) >= 0 ? "p1" : "p2";
        return section(
                "sev", severity,
                "t", "挤兑比率 " + bankRun.stripTrailingZeros().toPlainString() + "% · 覆盖率 " + coverage.stripTrailingZeros().toPlainString() + "%",
                "m", "B1 双账本 · 实时",
                "href", "/overview/dual-ledger");
    }

    private List<Map<String, Object>> readJsonRows(String key, List<Map<String, Object>> fallback, String remark,
                                                   List<Map<String, Object>> warnings) {
        seedJsonIfAbsent(key, fallback, remark);
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .map(raw -> {
                    try {
                        return objectMapper.readValue(raw, LIST_MAP_TYPE);
                    } catch (JsonProcessingException ex) {
                        warnConfig(warnings, key, "B_CONFIG_JSON_INVALID", "Configured JSON is invalid; existing value was not overwritten.");
                        return List.<Map<String, Object>>of();
                    }
                })
                .orElseGet(() -> readTimeSeedPolicy.enabled() ? fallback : List.of());
    }

    private List<BigDecimal> readDecimalList(String key, List<BigDecimal> fallback, String remark,
                                             List<Map<String, Object>> warnings) {
        seedJsonIfAbsent(key, fallback, remark);
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .map(raw -> {
                    try {
                        return objectMapper.readValue(raw, DECIMAL_LIST_TYPE);
                    } catch (JsonProcessingException ex) {
                        warnConfig(warnings, key, "B_CONFIG_JSON_INVALID", "Configured numeric array is invalid; existing value was not overwritten.");
                        return List.<BigDecimal>of();
                    }
                })
                .orElseGet(() -> readTimeSeedPolicy.enabled() ? fallback : List.of());
    }

    private BigDecimal readDecimalSeed(String key, BigDecimal fallback, String remark, List<Map<String, Object>> warnings) {
        seedConfigIfAbsent(key, safe(fallback).toPlainString(), "NUMBER", "treasury_b", remark);
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .map(raw -> {
                    try {
                        return new BigDecimal(raw.trim());
                    } catch (RuntimeException ex) {
                        warnConfig(warnings, key, "B_CONFIG_NUMBER_INVALID", "Configured number is invalid; existing value was not overwritten.");
                        return readTimeSeedPolicy.enabled() ? safe(fallback) : BigDecimal.ZERO;
                    }
                })
                .orElseGet(() -> readTimeSeedPolicy.enabled() ? safe(fallback) : BigDecimal.ZERO);
    }

    private void warnConfig(List<Map<String, Object>> warnings, String key, String code, String message) {
        warnings.add(section("key", key, "code", code, "message", message));
    }

    private void seedJsonIfAbsent(String key, Object fallback, String remark) {
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        if (configFacade.activeValue(key).filter(StringUtils::hasText).isPresent()) {
            return;
        }
        seedJson(key, fallback, remark);
    }

    private void seedJson(String key, Object value, String remark) {
        try {
            configFacade.upsertAdminValue(key, objectMapper.writeValueAsString(value), "JSON", "treasury_b", remark);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to seed treasury B config: " + key, ex);
        }
    }

    private List<Map<String, Object>> defaultLiquidityRunway() {
        return List.of(
                section("day", "D+1", "valueWan", new BigDecimal("38")),
                section("day", "D+2", "valueWan", new BigDecimal("52")),
                section("day", "D+3", "valueWan", new BigDecimal("47")),
                section("day", "D+4", "valueWan", new BigDecimal("61")),
                section("day", "D+5", "valueWan", new BigDecimal("55")),
                section("day", "D+6", "valueWan", new BigDecimal("73")),
                section("day", "D+7", "valueWan", new BigDecimal("49")));
    }

    private List<BigDecimal> defaultLiquidityFlow() {
        return decimalList("4", "6", "8", "9", "10", "11", "11", "12");
    }

    private List<Map<String, Object>> flowRows(List<BigDecimal> flow) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < flow.size(); i++) {
            rows.add(section("label", "W" + (i + 1), "valueWan", safe(flow.get(i))));
        }
        return rows;
    }

    private List<Map<String, Object>> defaultFunnelStages() {
        return List.of(
                section("key", "reg", "nm", "注册", "ct", 1240, "lc", "L1", "color", "var(--brand)"),
                section("key", "bind", "nm", "绑卡", "ct", 769, "lc", "L2", "conv", "62.0%", "color", "color-mix(in srgb, var(--brand) 78%, #fff)"),
                section("key", "buy", "nm", "首购", "ct", 223, "lc", "L3→L4", "conv", "29.0%", "bad", true, "color", "var(--cyan)"),
                section("key", "rebuy", "nm", "复购", "ct", 78, "lc", "L5", "conv", "35.0%", "color", "color-mix(in srgb, var(--cyan) 70%, #fff)"),
                section("key", "cash", "nm", "提现", "ct", 41, "lc", "L5", "conv", "52.6%", "color", "var(--success)"));
    }

    private List<Map<String, Object>> defaultFunnelTransitions() {
        return List.of(
                section("from", "reg", "to", "bind", "a", "注册→绑卡", "v", "62.0%", "flow", "1,240 → 769", "note", "$1 KYC express", "noteKind", "muted"),
                section("from", "bind", "to", "buy", "a", "绑卡→首购", "v", "29.0%", "vColor", "var(--brand-2)", "flow", "769 → 223", "note", "环比 -2.4pt", "noteKind", "dn", "bad", true),
                section("from", "buy", "to", "rebuy", "a", "首购→复购", "v", "35.0%", "flow", "223 → 78", "note", "达标 +1.1pt", "noteKind", "up"),
                section("from", "rebuy", "to", "cash", "a", "复购→提现", "v", "52.6%", "flow", "78 → 41", "note", "高价值留存样本"),
                section("from", "reg", "to", "cash", "a", "整体转化", "v", "3.3%", "flow", "1,240 → 41", "note", "L1-L5 全链路"));
    }

    private List<BigDecimal> defaultFunnelCohort() {
        return decimalList("100", "86", "74", "68", "61", "57", "54", "52");
    }

    private List<Map<String, Object>> defaultFunnelChannels() {
        return List.of(
                section("nm", "Affiliate", "v", 41, "c", "var(--brand)", "q", "高 ROAS"),
                section("nm", "KOL", "v", 26, "c", "var(--cyan)", "q", "首购偏低"),
                section("nm", "Organic", "v", 18, "c", "var(--success)", "q", "复购稳定"),
                section("nm", "Referral", "v", 15, "c", "var(--warning)", "q", "返佣敏感"));
    }

    private List<BigDecimal> defaultFunnelDaily() {
        return decimalList("17.2", "16.8", "18.1", "19.0", "18.4", "17.6", "18.2", "18.0");
    }

    private List<Map<String, Object>> defaultRhythmPhases() {
        return List.of(
                section("code", "P1", "name", "拉新", "intensity", 55),
                section("code", "P2", "name", "成长", "intensity", 78),
                section("code", "P3", "name", "扩张", "intensity", 92),
                section("code", "P4", "name", "稳态", "intensity", 70),
                section("code", "P5", "name", "收紧", "intensity", 40),
                section("code", "P6", "name", "收场", "intensity", 18));
    }

    private List<BigDecimal> defaultRhythmInflow() {
        return decimalList("62", "84", "118", "142", "168", "190", "205", "198");
    }

    private List<Map<String, Object>> defaultRhythmBudget() {
        return List.of(
                section("nm", "获客", "v", 38, "c", "var(--brand)"),
                section("nm", "奖励", "v", 27, "c", "var(--cyan)"),
                section("nm", "安全", "v", 20, "c", "var(--warning)"),
                section("nm", "储备", "v", 15, "c", "var(--success)"));
    }

    private List<BigDecimal> defaultRhythmRatio() {
        return decimalList("1.62", "1.58", "1.55", "1.53", "1.51", "1.49", "1.45", "1.42");
    }

    private List<Map<String, Object>> defaultRiskFeed() {
        return List.of(
                section("sev", "p1", "t", "套利检测命中 cluster-k2-018 · 触发提现延迟", "m", "K2 套利检测 · 6m 前", "href", "/risk/arbitrage"),
                section("sev", "p2", "t", "24h 资金净流入保持正向 · 扩张节奏允许", "m", "B2 流动性 · 14m 前", "href", "/overview/liquidity"),
                section("sev", "p1", "t", "提现队列中存在 KYC 复审样本", "m", "D2/K5 · 18m 前", "href", "/finance/withdrawals"),
                section("sev", "p2", "t", "B5 规则压力低于收紧阈值", "m", "B5 风险雷达 · 28m 前", "href", "/overview/risk-radar"),
                section("sev", "p2", "t", "风险评分均值低于人工覆盖线", "m", "K4 风险评分 · 31m 前", "href", "/risk/risk-score"));
    }

    private List<BigDecimal> defaultRiskPressure() {
        return decimalList("9", "12", "18", "24", "28", "30", "31", "32");
    }

    private List<Map<String, Object>> defaultRiskRules() {
        return List.of(
                section("nm", "多账户", "ct", 9, "sev", "P1", "dom", "K1"),
                section("nm", "套利", "ct", 4, "sev", "P1", "dom", "K2"),
                section("nm", "提现规则", "ct", 7, "sev", "P2", "dom", "K3"),
                section("nm", "KYC 复审", "ct", 5, "sev", "P1", "dom", "K5"));
    }

    private List<Map<String, Object>> defaultRiskSeverity() {
        return List.of(
                section("nm", "P0", "v", 1, "c", "var(--danger)"),
                section("nm", "P1", "v", 11, "c", "var(--warning)"),
                section("nm", "P2", "v", 23, "c", "var(--cyan)"),
                section("nm", "P3", "v", 37, "c", "var(--success)"));
    }

    private List<Map<String, Object>> defaultRiskVolume() {
        return List.of(
                section("label", "D-6", "count", 3),
                section("label", "D-5", "count", 4),
                section("label", "D-4", "count", 6),
                section("label", "D-3", "count", 5),
                section("label", "D-2", "count", 7),
                section("label", "D-1", "count", 6),
                section("label", "今日", "count", 9));
    }

    private List<BigDecimal> decimalList(String... values) {
        List<BigDecimal> rows = new ArrayList<>();
        for (String value : values) {
            rows.add(new BigDecimal(value));
        }
        return rows;
    }

    private boolean enabledFromConfig(String value) {
        if (!StringUtils.hasText(value)) {
            return true;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return !List.of("0", "false", "off", "disabled", "disable", "closed", "close").contains(normalized);
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
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        ensureD3ConfigDefaults();
        if (treasuryLiabilitiesPresent()) {
            return;
        }
        ledgerRepository.seedD4FallbackData(seedUserIds());
    }

    private void ensureD4FallbackSeedData() {
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
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
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
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
                .orElseGet(() -> readTimeSeedPolicy.enabled() ? safe(fallback) : BigDecimal.ZERO);
    }

    private BigDecimal safetyThresholdDecimal(String key, BigDecimal fallback) {
        return configFacade.activeValue(key)
                .map(value -> parseDecimalWithFallback(value, fallback))
                .orElseGet(() -> safe(fallback));
    }

    private String configValue(String key, String fallback) {
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .orElseGet(() -> readTimeSeedPolicy.enabled() ? fallback : "");
    }

    private BigDecimal parseDecimal(String value, BigDecimal fallback) {
        try {
            return new BigDecimal(value.trim());
        } catch (RuntimeException ex) {
            return readTimeSeedPolicy.enabled() ? safe(fallback) : BigDecimal.ZERO;
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

    private record GateSeed(String key, String name) {
    }
}

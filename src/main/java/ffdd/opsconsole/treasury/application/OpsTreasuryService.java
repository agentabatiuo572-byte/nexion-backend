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
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsTreasuryService {
    private static final int DEFAULT_DAYS = 7;
    private static final int MAX_DAYS = 90;
    private static final String REDLINE_CONFIG_KEY = "wallet.dual-ledger.redline-pct";
    private static final String HEALTHY_CONFIG_KEY = "wallet.dual-ledger.healthy-pct";
    private static final String RUN_RISK_CONFIG_KEY = "wallet.dual-ledger.run-risk-pct";
    private static final String SCOPE_CONFIG_KEY = "wallet.dual-ledger.scope";
    private static final String B_ALERT_COVERAGE_ID = "coverage-redline";
    private static final String B_ALERT_COVERAGE_ACK_KEY = "wallet.dual-ledger.alert.coverage-redline.ack";
    private static final String B_ALERT_ACK_IDEMPOTENCY_SCOPE = "TREASURY_B_ALERT_ACK";
    private static final String B_RESERVE_INJECTION_IDEMPOTENCY_SCOPE = "TREASURY_B_RESERVE_INJECTION";
    private static final List<GateSeed> B_RISK_GATE_SEEDS = List.of(
            new GateSeed("withdraw", "提现闸"),
            new GateSeed("exchange", "兑换闸"),
            new GateSeed("staking", "算力质押闸"),
            new GateSeed("genesis", "Genesis 闸"),
            new GateSeed("trial", "试用闸"));
    private final TreasuryLedgerRepository ledgerRepository;
    private final PlatformConfigFacade configFacade;
    private final EmergencyControlRepository emergencyRepository;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
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

    public ApiResult<Map<String, Object>> dualLedger() {
        ensureD3FallbackSeedData();
        return ApiResult.ok(dualLedgerSnapshot());
    }

    private Map<String, Object> dualLedgerSnapshot() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime current24hStart = now.minusHours(24);
        LocalDateTime prev24hStart = now.minusHours(48);
        BigDecimal reserveUsd = safe(ledgerRepository.currentReserveUsd());
        BigDecimal nexUsdRate = ledgerRepository.latestNexUsdtPrice().map(this::safe).orElse(BigDecimal.ZERO);
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
                "sources", List.of(
                        "nx_treasury_reserve_ledger",
                        "nx_price_index:NEX_USDT",
                        "nx_user_wallet",
                        "nx_wallet_ledger",
                        "nx_withdrawal_order",
                        "nx_staking_position",
                        "nx_nex_lock_order",
                        "nx_config_item:wallet.dual-ledger.safety-thresholds",
                        "H1 growth rhythm facade"));
        response.put("h1Rhythm", bRhythmSummary());
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
        String voucherNo = request.voucherNo().trim();
        BigDecimal amount = request.amount().setScale(2, RoundingMode.HALF_UP);
        String reason = request.reason().trim();
        String operator = trimToNull(request.operator());
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                B_RESERVE_INJECTION_IDEMPOTENCY_SCOPE,
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
        BigDecimal oldReserve = safe(ledgerRepository.currentReserveUsd()).setScale(2, RoundingMode.HALF_UP);
        BigDecimal newReserve = oldReserve.add(amount).setScale(2, RoundingMode.HALF_UP);
        ledgerRepository.recordReserveInjection(voucherNo, amount, reason, operator, idempotencyKey);
        audit("B1_TREASURY_RESERVE_INJECTION", "TREASURY_RESERVE", voucherNo, operator, section(
                "voucherNo", voucherNo,
                "amount", amount,
                "oldReserveUsd", oldReserve,
                "newReserveUsd", newReserve,
                "reason", reason,
                "idempotencyKey", idempotencyKey));
        Map<String, Object> response = dualLedger().getData();
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
        List<Map<String, Object>> runway = liquidityRunwayFromLedger(snapshot);
        List<BigDecimal> flow = netFlowSeries(8);
        BigDecimal runwayTotalWan = runway.stream()
                .map(row -> decimal(row.get("valueWan")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        Map<String, Object> response = section(
                "coverage", snapshot,
                "liabilities", accounts,
                "runway", runway,
                "runwayTotalWan", runwayTotalWan,
                "flow", flowRows(flow),
                "sources", List.of("B1 dualLedger", "nx_wallet_ledger", "nx_withdrawal_order", "nx_staking_position", "nx_nex_lock_order"));
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
        feed.add(dynamicCoverageFeed(snapshot));
        LocalDateTime since = LocalDateTime.now(clock).minusDays(7);
        List<BigDecimal> pressure = ledgerRepository.riskPressureSeries(since);
        List<Map<String, Object>> rules = ledgerRepository.riskRuleBuckets(since);
        List<Map<String, Object>> severity = ledgerRepository.riskSeverityBuckets(since);
        List<Map<String, Object>> volume = ledgerRepository.riskVolumeBuckets(since);
        BigDecimal pressureTightPct = decimal(snapshot.get("runRiskPct"));
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
                        "nx_emergency_control_setting:killswitch.*",
                        "nx_emergency_control_setting:emergency.killswitch.*",
                        "B1 dualLedger",
                        "nx_withdrawal_order",
                        "nx_risk_decision"));
    }

    private List<Map<String, Object>> riskGates() {
        List<Map<String, Object>> gates = new ArrayList<>();
        for (GateSeed seed : B_RISK_GATE_SEEDS) {
            String key = seed.key();
            String configKey = "killswitch." + key;
            String emergencyKey = "emergency.killswitch." + key;
            String sourceKey = null;
            String configured = controlValue(configKey).filter(StringUtils::hasText).orElse(null);
            if (StringUtils.hasText(configured)) {
                sourceKey = configKey;
            }
            if (!StringUtils.hasText(configured)) {
                configured = controlValue(emergencyKey).filter(StringUtils::hasText).orElse(null);
                if (StringUtils.hasText(configured)) {
                    sourceKey = emergencyKey;
                }
            }
            boolean configuredPresent = StringUtils.hasText(configured);
            if (!configuredPresent) {
                continue;
            }
            boolean on = !configuredPresent || enabledFromConfig(configured);
            String state = configuredPresent ? (on ? "on" : "off") : "missing";
            gates.add(section("nm", seed.name(), "dom", key, "on", on, "state", state, "configKey", sourceKey));
        }
        return gates;
    }

    private Optional<String> controlValue(String key) {
        return emergencyRepository.settingValue(key);
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

    private List<Map<String, Object>> maturity7d() {
        LocalDateTime startAt = LocalDateTime.now(clock).toLocalDate().atStartOfDay();
        LocalDateTime endAt = startAt.plusDays(8);
        return ledgerRepository.maturityBuckets(startAt, endAt).stream()
                .map(row -> section(
                        "day", String.valueOf(row.get("day")),
                        "withdrawUsd", money(decimal(row.get("withdrawUsd"))),
                        "interestUsd", money(decimal(row.get("interestUsd")))))
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

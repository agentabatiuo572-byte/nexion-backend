package ffdd.opsconsole.finance.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.domain.DepositAggregateView;
import ffdd.opsconsole.finance.domain.DepositBinRiskView;
import ffdd.opsconsole.finance.domain.DepositCardRiskParamView;
import ffdd.opsconsole.finance.domain.DepositChannelView;
import ffdd.opsconsole.finance.domain.DepositChargebackView;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.domain.DepositOpsRepository;
import ffdd.opsconsole.finance.domain.DepositReconciliationRowView;
import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.TopupCommandRequest;
import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalQueryRequest;
import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.user.domain.UserSeedRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsFinanceService {
    private static final String TOPUP_CONFIG_GROUP = "finance-topup";
    private static final BigDecimal CARD_FEE_BUFFER_RATE = new BigDecimal("0.035");
    private static final Set<String> CONFIRMED_DEPOSIT_STATUSES = Set.of("CONFIRMED", "CREDITED", "SUCCESS");
    private static final Map<String, List<String>> TOPUP_FLOW_STATUSES = Map.of(
            "pending", List.of("CREATED", "PENDING", "CONFIRMING", "WAITING_CONFIRMATION"),
            "confirmed", List.of("CONFIRMED", "CREDITED", "SUCCESS"),
            "abnormal", List.of("FAILED", "EXPIRED", "REJECTED", "ABNORMAL"));
    private static final List<TopupChannelDef> TOPUP_CHANNELS = List.of(
            new TopupChannelDef("USDT-TRC20", "trc20", "1 USDT fixed", "$10", true),
            new TopupChannelDef("USDT-ERC20", "erc20", "5 USDT fixed", "$10", true),
            new TopupChannelDef("BTC", "btc", "0.5%", "$20", true),
            new TopupChannelDef("ETH", "eth", "0.5%", "$20", true),
            new TopupChannelDef("Card", "card", "3.5% -> fee buffer", "$10", true));
    private static final List<TopupCardParamDef> TOPUP_CARD_PARAMS = List.of(
            new TopupCardParamDef("threeDsThreshold", "3DS strong auth threshold", "$50", "Card payments >= $50 require issuer verification"),
            new TopupCardParamDef("cardRetryLimit", "Same-card 24h retry limit", "5", "Failed attempts over the limit become BIN lock candidates"),
            new TopupCardParamDef("cardLockHours", "Card lock hours", "24", "Server-side lock duration, 1-72h"));
    private static final Set<String> REVIEWABLE = Set.of("REVIEWING", "DELAYED");
    private static final Set<String> REJECTABLE = Set.of("REVIEWING", "DELAYED", "FROZEN", "PENDING_CHAIN", "CHAIN_SUBMITTED", "DEAD");
    private static final Set<String> FINAL_STATUSES = Set.of("SUCCESS", "FAILED", "REJECTED");
    private static final String WITHDRAW_KILLSWITCH_KEY = "killswitch.withdraw";
    private static final String WITHDRAW_LEGACY_KILLSWITCH_KEY = "emergency.killswitch.withdraw";
    private static final String WITHDRAW_DISCLOSURE_GATE_KEY = "disclosure.gate.withdraw";
    private static final int HIGH_RISK_SCORE = 70;
    private static final List<String> D_SEED_USER_KEYS = List.of(
            "usr_77D4", "usr_31E8", "usr_2231", "usr_55B1", "usr_8807");

    private final PlatformConfigFacade configFacade;
    private final TreasuryCoverageFacade coverageFacade;
    private final WithdrawalOrderRepository withdrawalRepository;
    private final DepositOpsRepository depositOpsRepository;
    private final UserSeedRepository userSeedRepository;
    private final RiskKycReviewFacade riskKycReviewFacade;
    private final AuditLogService auditLogService;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public ApiResult<Map<String, Object>> topupOverview() {
        ensureD1FallbackSeedData();
        List<DepositChannelView> channels = topupChannels();
        List<DepositReconciliationRowView> reconciliation = reconciliationRows();
        BigDecimal ledgerTotal = reconciliation.stream()
                .map(DepositReconciliationRowView::ledgerAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        long ledgerCount = reconciliation.stream()
                .mapToLong(row -> row.ledgerCount() == null ? 0L : row.ledgerCount())
                .sum();
        List<DepositReconciliationRowView> diffRows = reconciliation.stream()
                .filter(row -> row.diff() != null && !Boolean.TRUE.equals(row.reconciled()))
                .toList();
        BigDecimal diffAmount = diffRows.stream()
                .map(DepositReconciliationRowView::diffAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal feeBuffer = configDecimal("finance.topup.fee_buffer_usd",
                depositOpsRepository.cardPaidAmountToday().multiply(CARD_FEE_BUFFER_RATE));
        List<DepositBinRiskView> bins = binRiskRows();
        long lockedBins = bins.stream().filter(row -> Boolean.TRUE.equals(row.locked())).count();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("channels", channels);
        String primaryPsp = configValue("finance.topup.psp.primary", "Checkout.com");
        response.put("primaryPsp", primaryPsp);
        response.put("backupPsp", StringUtils.hasText(primaryPsp)
                ? ("Checkout.com".equals(primaryPsp) ? "Stripe" : "Checkout.com")
                : "");
        response.put("cardParams", topupCardParams());
        response.put("reconciliation", reconciliation);
        response.put("ledgerTotal", ledgerTotal);
        response.put("ledgerCount", ledgerCount);
        response.put("diffCount", diffRows.size());
        response.put("diffAmount", diffAmount);
        response.put("feeBufferUsd", feeBuffer);
        response.put("bins", bins);
        response.put("binLockedCount", lockedBins);
        response.put("chargebacks", chargebacks());
        response.put("sources", List.of("nx_deposit_order", "nx_payment_record", "nx_config_item: finance.topup.*"));
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<DepositFlowView>> topupFlows(String status, Long userId, String keyword, Integer pageNum, Integer pageSize) {
        ensureD1FallbackSeedData();
        int normalizedPageNum = clamp(pageNum == null ? 1 : pageNum, 1, 10_000);
        int normalizedPageSize = clamp(pageSize == null ? 20 : pageSize, 1, 100);
        List<String> statuses = TOPUP_FLOW_STATUSES.getOrDefault(normalizeFlowStatus(status), List.of());
        return ApiResult.ok(depositOpsRepository.pageFlows(statuses, userId, trimToNull(keyword), normalizedPageNum, normalizedPageSize));
    }

    public ApiResult<Map<String, Object>> updateTopupChannelEnabled(String channelCode, String idempotencyKey, TopupCommandRequest request) {
        TopupChannelDef channel = topupChannel(channelCode);
        if (channel == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TOPUP_CHANNEL_NOT_FOUND");
        }
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.enabled() == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ENABLED_REQUIRED");
        }
        String configKey = channelConfigKey(channel.code(), "enabled");
        configFacade.upsertAdminValue(configKey, String.valueOf(request.enabled()), "BOOLEAN", TOPUP_CONFIG_GROUP, "D1 topup channel enabled");
        audit("D1_TOPUP_CHANNEL_STATUS_CHANGED", "TOPUP_CHANNEL", channel.id(), request.operator(), Map.of(
                "channel", channel.id(),
                "enabled", request.enabled(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return topupOverview();
    }

    public ApiResult<Map<String, Object>> updateTopupChannelFee(String channelCode, String idempotencyKey, TopupCommandRequest request) {
        return updateTopupChannelText(channelCode, "fee", "D1_TOPUP_CHANNEL_FEE_CHANGED", idempotencyKey, request);
    }

    public ApiResult<Map<String, Object>> updateTopupChannelMinAmount(String channelCode, String idempotencyKey, TopupCommandRequest request) {
        return updateTopupChannelText(channelCode, "min_amount", "D1_TOPUP_CHANNEL_MIN_CHANGED", idempotencyKey, request);
    }

    public ApiResult<Map<String, Object>> switchTopupPsp(String idempotencyKey, TopupCommandRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String next = requireText(request.value(), "PSP_REQUIRED");
        if (!Set.of("Checkout.com", "Stripe").contains(next)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "UNSUPPORTED_PSP");
        }
        String current = configValue("finance.topup.psp.primary", "Checkout.com");
        if (current.equals(next)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        configFacade.upsertAdminValue("finance.topup.psp.primary", next, "STRING", TOPUP_CONFIG_GROUP, "D1 primary card PSP");
        audit("D1_TOPUP_PSP_SWITCHED", "TOPUP_PSP", next, request.operator(), Map.of(
                "from", current,
                "to", next,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return topupOverview();
    }

    public ApiResult<Map<String, Object>> updateTopupCardRiskParam(String key, String idempotencyKey, TopupCommandRequest request) {
        TopupCardParamDef param = TOPUP_CARD_PARAMS.stream()
                .filter(candidate -> candidate.key().equals(key))
                .findFirst()
                .orElse(null);
        if (param == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CARD_PARAM_NOT_FOUND");
        }
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String value = requireText(request.value(), "VALUE_REQUIRED");
        configFacade.upsertAdminValue(cardParamConfigKey(param.key()), value, "STRING", TOPUP_CONFIG_GROUP, "D1 card risk parameter");
        audit("D1_TOPUP_CARD_RISK_PARAM_CHANGED", "TOPUP_CARD_PARAM", param.key(), request.operator(), Map.of(
                "key", param.key(),
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return topupOverview();
    }

    public ApiResult<Map<String, Object>> writeoffTopupReconciliation(String channelCode, String idempotencyKey, TopupCommandRequest request) {
        TopupChannelDef channel = topupChannel(channelCode);
        if (channel == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TOPUP_CHANNEL_NOT_FOUND");
        }
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String configKey = reconciliationConfigKey(channel.code());
        if ("RECONCILED".equalsIgnoreCase(configValue(configKey, ""))) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        configFacade.upsertAdminValue(configKey, "RECONCILED", "STRING", TOPUP_CONFIG_GROUP, "D1 reconciliation writeoff");
        audit("D1_TOPUP_RECONCILIATION_WRITEOFF", "TOPUP_RECONCILIATION", channel.id(), request.operator(), Map.of(
                "channel", channel.id(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return topupOverview();
    }

    public ApiResult<Map<String, Object>> createTopupBinLock(String idempotencyKey, TopupCommandRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String segment = requireText(request.value(), "BIN_SEGMENT_REQUIRED");
        return setTopupBinLock(segment, idempotencyKey, new TopupCommandRequest(null, true, request.reason(), request.operator()));
    }

    public ApiResult<Map<String, Object>> setTopupBinLock(String segment, String idempotencyKey, TopupCommandRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.enabled() == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "LOCK_STATE_REQUIRED");
        }
        String normalizedSegment = requireText(segment, "BIN_SEGMENT_REQUIRED");
        String configKey = binConfigKey(normalizedSegment);
        configFacade.upsertAdminValue(configKey, String.valueOf(request.enabled()), "BOOLEAN", TOPUP_CONFIG_GROUP, "D1 BIN lock");
        audit(request.enabled() ? "D1_TOPUP_BIN_LOCKED" : "D1_TOPUP_BIN_UNLOCKED", "TOPUP_BIN_LOCK", normalizedSegment, request.operator(), Map.of(
                "segment", normalizedSegment,
                "locked", request.enabled(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return topupOverview();
    }

    public ApiResult<Map<String, Object>> refundTopupChargeback(String caseNo, String idempotencyKey, TopupCommandRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedCaseNo = requireText(caseNo, "CHARGEBACK_CASE_REQUIRED");
        DepositChargebackView chargeback = depositOpsRepository.findChargeback(normalizedCaseNo).orElse(null);
        if (chargeback == null) {
            return ApiResult.fail(404, "CHARGEBACK_NOT_FOUND");
        }
        if ("CHARGEBACK_REFUNDED".equalsIgnoreCase(chargeback.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        int updated = depositOpsRepository.markChargebackRefunded(normalizedCaseNo, request.reason().trim());
        if (updated == 0) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        audit("D1_TOPUP_CHARGEBACK_REFUNDED", "TOPUP_CHARGEBACK", normalizedCaseNo, request.operator(), Map.of(
                "caseNo", normalizedCaseNo,
                "userId", chargeback.userId(),
                "amount", chargeback.amount(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return topupOverview();
    }

    public ApiResult<Map<String, Object>> withdrawalParams() {
        ensureD5ConfigDefaults();
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        GrowthRhythmSnapshot rhythm = GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy);
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
        response.put("h1Rhythm", rhythm.summary());
        response.put("withdrawNexGate", Map.of(
                "sourceDomain", "H1",
                "minBalanceNex", rhythm.withdrawNexMinBalance(),
                "holdDays", rhythm.withdrawNexHoldDays()));
        response.put("coverageRatio", coverage.coverageRatio());
        response.put("redlinePct", coverage.redlinePct());
        response.put("sources", List.of("nx_config_item: withdrawal.*", "B1 treasury coverage facade", "H1 growth rhythm facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<WithdrawalOrderView>> withdrawals(WithdrawalQueryRequest request) {
        ensureD2FallbackSeedData();
        int pageNum = clamp(request == null || request.pageNum() == null ? 1 : request.pageNum(), 1, 10_000);
        int pageSize = clamp(request == null || request.pageSize() == null ? 20 : request.pageSize(), 1, 100);
        String status = request == null ? null : trimToNull(request.status());
        String keyword = request == null ? null : trimToNull(request.keyword());
        Long userId = request == null ? null : request.userId();
        BigDecimal minAmount = request == null || request.minAmount() == null || request.minAmount().compareTo(BigDecimal.ZERO) < 0
                ? null
                : request.minAmount();
        BigDecimal maxAmount = request == null || request.maxAmount() == null || request.maxAmount().compareTo(BigDecimal.ZERO) < 0
                ? null
                : request.maxAmount();
        if (minAmount != null && maxAmount != null && minAmount.compareTo(maxAmount) > 0) {
            BigDecimal originalMin = minAmount;
            minAmount = maxAmount;
            maxAmount = originalMin;
        }
        Integer minRiskScore = request == null || request.minRiskScore() == null
                ? null
                : clamp(request.minRiskScore(), 0, 100);
        return ApiResult.ok(withdrawalRepository.page(status, userId, keyword, minAmount, maxAmount, minRiskScore, pageNum, pageSize));
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
        ensureD2FallbackSeedData();
        return withdrawalRepository.findByWithdrawalNo(withdrawalNo.trim())
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "WITHDRAWAL_NOT_FOUND"));
    }

    @Transactional
    public ApiResult<WithdrawalOrderView> reviewWithdrawal(
            String withdrawalNo,
            String idempotencyKey,
            WithdrawalReviewRequest request) {
        ApiResult<WithdrawalOrderView> guard = requireWithdrawalCommand(withdrawalNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        ensureD2FallbackSeedData();
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
        int dailyLimitCount = withdrawalDailyLimitCount();
        if ("APPROVE".equals(action)) {
            KycReviewTriggerResult k5Review = riskKycReviewFacade.triggerLargeWithdrawalReview(
                    order.userNo(),
                    order.amount(),
                    order.kycStatus(),
                    order.withdrawalNo(),
                    request.operator(),
                    request.reason().trim());
            if (k5Review.requiresReview()) {
                String holdReason = StringUtils.hasText(k5Review.ticketId())
                        ? "K5_REVIEW:" + k5Review.ticketId()
                        : k5Review.reason();
                withdrawalRepository.updateStatus(order.withdrawalNo(), "FROZEN", holdReason);
                auditWithdrawalReviewBlockedByK5(order, dailyLimitCount, idempotencyKey, request, k5Review);
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "WITHDRAWAL_K5_REVIEW_REQUIRED");
            }
            String blockedReason = approvalBlockReason(order, dailyLimitCount);
            if (blockedReason != null) {
                auditWithdrawalReviewBlocked(order, action, blockedReason, dailyLimitCount, idempotencyKey, request);
                int httpStatus = OpsErrorCode.COVERAGE_BELOW_REDLINE.name().equals(blockedReason)
                        ? OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus()
                        : OpsErrorCode.VALIDATION_FAILED.httpStatus();
                return ApiResult.fail(httpStatus, blockedReason);
            }
        }
        String failureReason = "REJECTED".equals(newStatus) ? request.reason().trim() : null;
        withdrawalRepository.updateStatus(order.withdrawalNo(), newStatus, failureReason);
        WithdrawalOrderView updated = withdrawalRepository.findByWithdrawalNo(order.withdrawalNo())
                .orElse(new WithdrawalOrderView(
                        order.id(), order.userId(), order.withdrawalNo(), order.asset(), order.chain(), order.amount(), order.fee(),
                        order.targetAddress(), order.riskDecisionId(), order.chainTxHash(), newStatus, order.chainSubmittedAt(),
                        order.completedAt(), order.failedAt(), failureReason, order.chainBroadcastAttempts(), order.nextBroadcastAt(),
                        order.lastBroadcastError(), order.broadcastDeadAt(), order.createdAt(), order.updatedAt(),
                        order.userNo(), order.nickname(), order.phoneMasked(), order.kycStatus(), order.userStatus(),
                        order.riskScore(), order.hitRules(), order.riskReason(), order.withdrawalCount24h(), order.statusHistory(), order.auditTrail()));
        Map<String, Object> detail = withdrawalReviewAuditDetail(
                order, newStatus, dailyLimitCount, request.reason().trim(), idempotencyKey.trim());
        audit("D2_WITHDRAWAL_REVIEW_" + action, "WITHDRAWAL", order.withdrawalNo(), request.operator(), detail);
        return ApiResult.ok(updated);
    }

    private ApiResult<Map<String, Object>> updateTopupChannelText(
            String channelCode,
            String field,
            String auditAction,
            String idempotencyKey,
            TopupCommandRequest request) {
        TopupChannelDef channel = topupChannel(channelCode);
        if (channel == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "TOPUP_CHANNEL_NOT_FOUND");
        }
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String value = requireText(request.value(), "VALUE_REQUIRED");
        String configKey = channelConfigKey(channel.code(), field);
        configFacade.upsertAdminValue(configKey, value, "STRING", TOPUP_CONFIG_GROUP, "D1 topup channel " + field);
        audit(auditAction, "TOPUP_CHANNEL", channel.id(), request.operator(), Map.of(
                "channel", channel.id(),
                "field", field,
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return topupOverview();
    }

    private List<DepositChannelView> topupChannels() {
        ensureD1ConfigDefaults();
        return TOPUP_CHANNELS.stream()
                .filter(channel -> readTimeSeedPolicy.enabled() || hasAnyTopupChannelConfig(channel))
                .map(channel -> new DepositChannelView(
                        channel.id(),
                        channel.code(),
                        configValue(channelConfigKey(channel.code(), "fee"), channel.defaultFee()),
                        configValue(channelConfigKey(channel.code(), "min_amount"), channel.defaultMinAmount()),
                        configBoolean(channelConfigKey(channel.code(), "enabled"), channel.defaultEnabled())))
                .toList();
    }

    private List<DepositCardRiskParamView> topupCardParams() {
        ensureD1ConfigDefaults();
        return TOPUP_CARD_PARAMS.stream()
                .filter(param -> readTimeSeedPolicy.enabled()
                        || configFacade.activeValue(cardParamConfigKey(param.key())).filter(StringUtils::hasText).isPresent())
                .map(param -> new DepositCardRiskParamView(
                        param.key(),
                        param.name(),
                        configValue(cardParamConfigKey(param.key()), param.defaultValue()),
                        param.note()))
                .toList();
    }

    private boolean hasAnyTopupChannelConfig(TopupChannelDef channel) {
        return configFacade.activeValue(channelConfigKey(channel.code(), "fee")).filter(StringUtils::hasText).isPresent()
                || configFacade.activeValue(channelConfigKey(channel.code(), "min_amount")).filter(StringUtils::hasText).isPresent()
                || configFacade.activeValue(channelConfigKey(channel.code(), "enabled")).filter(StringUtils::hasText).isPresent();
    }

    private List<DepositReconciliationRowView> reconciliationRows() {
        Map<String, DepositAggregateView> aggregates = new HashMap<>();
        for (DepositAggregateView aggregate : depositOpsRepository.aggregateToday()) {
            aggregates.put(normalizeChannelCode(aggregate.channel()), aggregate);
        }
        BigDecimal cardPaidAmount = depositOpsRepository.cardPaidAmountToday();
        long cardPaidCount = depositOpsRepository.cardPaidCountToday();
        if (cardPaidCount > 0 || cardPaidAmount.compareTo(BigDecimal.ZERO) > 0) {
            aggregates.put("card", new DepositAggregateView("Card", cardPaidCount, cardPaidAmount, cardPaidCount, cardPaidAmount));
        }
        List<DepositReconciliationRowView> rows = new ArrayList<>();
        if (!readTimeSeedPolicy.enabled()) {
            for (Map.Entry<String, DepositAggregateView> entry : aggregates.entrySet()) {
                String channelCode = entry.getKey();
                TopupChannelDef channel = topupChannel(channelCode);
                rows.add(reconciliationRow(channel == null ? channelCode : channel.id(), channelCode, entry.getValue()));
            }
            return rows;
        }
        for (TopupChannelDef channel : TOPUP_CHANNELS) {
            DepositAggregateView aggregate = aggregates.getOrDefault(channel.code(),
                    new DepositAggregateView(channel.id(), 0L, BigDecimal.ZERO, 0L, BigDecimal.ZERO));
            rows.add(reconciliationRow(channel.id(), channel.code(), aggregate));
        }
        return rows;
    }

    private DepositReconciliationRowView reconciliationRow(String channelId, String channelCode, DepositAggregateView aggregate) {
        BigDecimal providerAmount = safe(aggregate.providerAmount());
        BigDecimal ledgerAmount = safe(aggregate.ledgerAmount());
        BigDecimal diffAmount = providerAmount.subtract(ledgerAmount);
        boolean reconciled = "RECONCILED".equalsIgnoreCase(configValue(reconciliationConfigKey(channelCode), ""));
        String diff = diffText(diffAmount, aggregate.providerCount(), aggregate.ledgerCount(), reconciled);
        return new DepositReconciliationRowView(
                channelId,
                aggregate.providerCount() == null ? 0L : aggregate.providerCount(),
                providerAmount,
                aggregate.ledgerCount() == null ? 0L : aggregate.ledgerCount(),
                ledgerAmount,
                diffAmount,
                diff,
                reconciled);
    }

    private String diffText(BigDecimal diffAmount, Long providerCount, Long ledgerCount, boolean reconciled) {
        if (reconciled) {
            return "已核销";
        }
        long diffCount = (providerCount == null ? 0L : providerCount) - (ledgerCount == null ? 0L : ledgerCount);
        if (diffAmount.compareTo(BigDecimal.ZERO) == 0 && diffCount == 0) {
            return null;
        }
        String amount = diffAmount.abs().setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
        if (diffCount != 0) {
            return "单边挂账 $" + amount;
        }
        return diffAmount.compareTo(BigDecimal.ZERO) > 0 ? "支付商侧多 $" + amount : "平台侧多 $" + amount;
    }

    private List<DepositBinRiskView> binRiskRows() {
        int threshold = parseInteger(configValue("finance.topup.card.cardRetryLimit", "5"), 5);
        Map<String, String> values = configFacade.activeValuesByGroup(TOPUP_CONFIG_GROUP);
        List<DepositBinRiskView> rows = new ArrayList<>();
        for (DepositBinRiskView row : depositOpsRepository.failedPaymentRiskRows(threshold)) {
            String configValue = values.get(binConfigKey(row.segment()));
            boolean locked = configValue == null ? Boolean.TRUE.equals(row.locked()) : configBooleanValue(configValue);
            rows.add(new DepositBinRiskView(row.segment(), row.meta(), row.fails24h(), locked, locked ? "锁定中" : row.note(), false));
        }
        values.entrySet().stream()
                .filter(entry -> entry.getKey().startsWith("finance.topup.bin.") && entry.getKey().endsWith(".locked"))
                .filter(entry -> configBooleanValue(entry.getValue()))
                .map(entry -> binSegmentFromKey(entry.getKey()))
                .filter(StringUtils::hasText)
                .sorted()
                .forEach(segment -> rows.add(new DepositBinRiskView(segment, "manual lock", 0L, true, "手动锁定", true)));
        return rows.stream()
                .sorted(Comparator.comparing((DepositBinRiskView row) -> !Boolean.TRUE.equals(row.locked()))
                        .thenComparing(row -> row.segment() == null ? "" : row.segment()))
                .toList();
    }

    private List<DepositChargebackView> chargebacks() {
        return depositOpsRepository.chargebacks();
    }

    private void ensureD1FallbackSeedData() {
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        ensureD1ConfigDefaults();
        if (hasD1LiveData()) {
            return;
        }
        depositOpsRepository.seedD1FallbackData(seedUserIds());
    }

    private boolean hasD1LiveData() {
        return !depositOpsRepository.aggregateToday().isEmpty()
                || depositOpsRepository.cardPaidCountToday() > 0
                || !depositOpsRepository.chargebacks().isEmpty()
                || !depositOpsRepository.failedPaymentRiskRows(1).isEmpty();
    }

    private void ensureD2FallbackSeedData() {
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        PageResult<WithdrawalOrderView> firstPage = withdrawalRepository.page(null, null, null, null, null, null, 1, 1);
        if (firstPage.getTotal() == 0) {
            withdrawalRepository.seedD2FallbackData(seedUserIds());
            return;
        }
        if (withdrawalRepository.countD2ActionableWithdrawals() == 0 && withdrawalRepository.countD2SeedWithdrawals() > 0) {
            withdrawalRepository.seedD2FallbackData(seedUserIds());
        }
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

    private void ensureD1ConfigDefaults() {
        seedConfigIfAbsent("finance.topup.psp.primary", "Checkout.com", "STRING", TOPUP_CONFIG_GROUP, "D1 primary card PSP");
        seedConfigIfAbsent("finance.topup.fee_buffer_usd", "18.90", "NUMBER", TOPUP_CONFIG_GROUP, "D1 card fee buffer");
        for (TopupChannelDef channel : TOPUP_CHANNELS) {
            seedConfigIfAbsent(channelConfigKey(channel.code(), "fee"), channel.defaultFee(), "STRING", TOPUP_CONFIG_GROUP, "D1 topup channel fee");
            seedConfigIfAbsent(channelConfigKey(channel.code(), "min_amount"), channel.defaultMinAmount(), "STRING", TOPUP_CONFIG_GROUP, "D1 topup channel min amount");
            seedConfigIfAbsent(channelConfigKey(channel.code(), "enabled"), String.valueOf(channel.defaultEnabled()), "BOOLEAN", TOPUP_CONFIG_GROUP, "D1 topup channel enabled");
        }
        for (TopupCardParamDef param : TOPUP_CARD_PARAMS) {
            seedConfigIfAbsent(cardParamConfigKey(param.key()), param.defaultValue(), "STRING", TOPUP_CONFIG_GROUP, "D1 card risk parameter");
        }
    }

    private void ensureD5ConfigDefaults() {
        seedConfigIfAbsent("withdrawal.daily_count_limit", "1", "NUMBER", "wallet", "D5 withdrawal daily count");
        seedConfigIfAbsent("withdrawal.max_balance_pct", "0.80", "NUMBER", "wallet", "D5 withdrawal balance cap");
        seedConfigIfAbsent("withdrawal.fee_rate", "0.02", "NUMBER", "wallet", "D5 withdrawal fee rate");
        seedConfigIfAbsent("withdrawal.min_usdt", "20", "NUMBER", "wallet", "D5 minimum withdrawal USDT");
        seedConfigIfAbsent("withdrawal.trc20.enabled", "true", "BOOLEAN", "wallet", "D5 TRC20 withdrawal enabled");
        seedConfigIfAbsent("withdrawal.erc20.enabled", "true", "BOOLEAN", "wallet", "D5 ERC20 withdrawal enabled");
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

    private TopupChannelDef topupChannel(String channelCode) {
        String normalized = normalizeChannelCode(channelCode);
        return TOPUP_CHANNELS.stream()
                .filter(channel -> channel.code().equals(normalized) || normalizeChannelCode(channel.id()).equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private String normalizeFlowStatus(String status) {
        if (!StringUtils.hasText(status)) {
            return "confirmed";
        }
        return status.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeChannelCode(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT).replace("_", "-");
        if (normalized.contains("trc20")) {
            return "trc20";
        }
        if (normalized.contains("erc20")) {
            return "erc20";
        }
        if (normalized.contains("card") || normalized.contains("checkout") || normalized.contains("stripe")) {
            return "card";
        }
        if (normalized.contains("btc")) {
            return "btc";
        }
        if (normalized.contains("eth")) {
            return "eth";
        }
        return normalized.replaceAll("[^a-z0-9-]", "-");
    }

    private String channelConfigKey(String channelCode, String field) {
        return "finance.topup.channel." + normalizeChannelCode(channelCode) + "." + field;
    }

    private String cardParamConfigKey(String key) {
        return "finance.topup.card." + key;
    }

    private String reconciliationConfigKey(String channelCode) {
        return "finance.topup.reconciliation." + normalizeChannelCode(channelCode) + ".status";
    }

    private String binConfigKey(String segment) {
        return "finance.topup.bin." + sanitizeKey(segment) + ".locked";
    }

    private String binSegmentFromKey(String key) {
        return key.replace("finance.topup.bin.", "").replace(".locked", "");
    }

    private String sanitizeKey(String value) {
        return requireText(value, "KEY_REQUIRED")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("-+", "-");
    }

    private String configValue(String key, String fallback) {
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .orElseGet(() -> readTimeSeedPolicy.enabled() ? fallback : "");
    }

    private boolean configBooleanValue(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "locked".equalsIgnoreCase(value) || "enabled".equalsIgnoreCase(value);
    }

    private int parseInteger(String value, int fallback) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9-]", ""));
        } catch (RuntimeException ex) {
            return readTimeSeedPolicy.enabled() ? fallback : 0;
        }
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private record TopupChannelDef(String id, String code, String defaultFee, String defaultMinAmount, boolean defaultEnabled) {
    }

    private record TopupCardParamDef(String key, String name, String defaultValue, String note) {
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

    private int withdrawalDailyLimitCount() {
        ensureD5ConfigDefaults();
        int count = configDecimal("withdrawal.daily_count_limit", BigDecimal.ONE)
                .setScale(0, RoundingMode.DOWN)
                .intValue();
        if (count <= 0) {
            return 0;
        }
        return clamp(count, 1, 10);
    }

    private boolean exceedsDailyLimit(WithdrawalOrderView order, int dailyLimitCount) {
        Integer count24h = order.withdrawalCount24h();
        return dailyLimitCount > 0 && count24h != null && count24h > dailyLimitCount;
    }

    private String approvalBlockReason(WithdrawalOrderView order, int dailyLimitCount) {
        if (!withdrawGateEnabled()) {
            return "WITHDRAWAL_KILL_SWITCH_DISABLED";
        }
        if (withdrawDisclosureGateActive()) {
            return "WITHDRAWAL_DISCLOSURE_REACK_REQUIRED";
        }
        if (coverageBelowRedline()) {
            return OpsErrorCode.COVERAGE_BELOW_REDLINE.name();
        }
        if (exceedsDailyLimit(order, dailyLimitCount)) {
            return "WITHDRAWAL_DAILY_LIMIT_EXCEEDED";
        }
        if (!isApprovedKyc(order.kycStatus())) {
            return "WITHDRAWAL_KYC_NOT_APPROVED";
        }
        if (!"ACTIVE".equalsIgnoreCase(trimToEmpty(order.userStatus()))) {
            return "WITHDRAWAL_USER_STATUS_BLOCKED";
        }
        if (hasBlockingRisk(order)) {
            return "WITHDRAWAL_RISK_HIT_BLOCKED";
        }
        return null;
    }

    private void auditWithdrawalReviewBlocked(
            WithdrawalOrderView order,
            String action,
            String blockedReason,
            int dailyLimitCount,
            String idempotencyKey,
            WithdrawalReviewRequest request) {
        Map<String, Object> detail = withdrawalReviewAuditDetail(
                order, order.status(), dailyLimitCount, request.reason().trim(), idempotencyKey.trim());
        detail.put("requestedAction", action);
        detail.put("blockedReason", blockedReason);
        detail.put("statusUnchanged", true);
        if (OpsErrorCode.COVERAGE_BELOW_REDLINE.name().equals(blockedReason)) {
            TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
            detail.put("coverageRatio", coverage.coverageRatio());
            detail.put("redlinePct", coverage.redlinePct());
        }
        audit("D2_WITHDRAWAL_REVIEW_BLOCKED", "WITHDRAWAL", order.withdrawalNo(), request.operator(), "BLOCKED", detail);
    }

    private boolean withdrawGateEnabled() {
        return configFacade.activeValue(WITHDRAW_KILLSWITCH_KEY)
                .or(() -> configFacade.activeValue(WITHDRAW_LEGACY_KILLSWITCH_KEY))
                .map(this::parseSwitchEnabled)
                .orElse(readTimeSeedPolicy.enabled());
    }

    private boolean withdrawDisclosureGateActive() {
        return configFacade.activeValue(WITHDRAW_DISCLOSURE_GATE_KEY)
                .map(this::parseDisclosureGateActive)
                .orElse(false);
    }

    private boolean coverageBelowRedline() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        return coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0;
    }

    private boolean parseSwitchEnabled(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "enabled", "enable", "on", "true", "1" -> true;
            case "disabled", "disable", "off", "false", "0" -> false;
            default -> readTimeSeedPolicy.enabled();
        };
    }

    private boolean parseDisclosureGateActive(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "enabled", "enable", "on", "true", "1", "blocked", "required" -> true;
            default -> false;
        };
    }

    private void auditWithdrawalReviewBlockedByK5(
            WithdrawalOrderView order,
            int dailyLimitCount,
            String idempotencyKey,
            WithdrawalReviewRequest request,
            KycReviewTriggerResult review) {
        Map<String, Object> detail = withdrawalReviewAuditDetail(
                order, "FROZEN", dailyLimitCount, request.reason().trim(), idempotencyKey.trim());
        detail.put("requestedAction", "APPROVE");
        detail.put("blockedReason", "WITHDRAWAL_K5_REVIEW_REQUIRED");
        detail.put("k5TicketId", review.ticketId());
        detail.put("k5Created", review.created());
        detail.put("k5Reason", review.reason());
        audit("D2_WITHDRAWAL_K5_REVIEW_REQUIRED", "WITHDRAWAL", order.withdrawalNo(), request.operator(), "BLOCKED", detail);
    }

    private Map<String, Object> withdrawalReviewAuditDetail(
            WithdrawalOrderView order,
            String toStatus,
            int dailyLimitCount,
            String reason,
            String idempotencyKey) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromStatus", order.status());
        detail.put("toStatus", toStatus);
        detail.put("asset", order.asset());
        detail.put("amount", order.amount());
        detail.put("fee", order.fee());
        detail.put("kycStatus", order.kycStatus());
        detail.put("userStatus", order.userStatus());
        detail.put("riskScore", order.riskScore());
        detail.put("hitRules", order.hitRules());
        detail.put("riskReason", order.riskReason());
        detail.put("withdrawalCount24h", order.withdrawalCount24h());
        detail.put("dailyLimitCount", dailyLimitCount);
        detail.put("reason", reason);
        detail.put("idempotencyKey", idempotencyKey);
        return detail;
    }

    private boolean isApprovedKyc(String status) {
        String normalized = trimToEmpty(status).toUpperCase(Locale.ROOT);
        return "VERIFIED".equals(normalized) || "APPROVED".equals(normalized);
    }

    private boolean hasBlockingRisk(WithdrawalOrderView order) {
        Integer riskScore = order.riskScore();
        if (riskScore != null && riskScore >= HIGH_RISK_SCORE) {
            return true;
        }
        String hitRules = trimToEmpty(order.hitRules());
        String riskReason = trimToEmpty(order.riskReason());
        Set<String> placeholders = Set.of("[]", "{}", "NULL", "NONE", "-", "—");
        return (StringUtils.hasText(hitRules) && !placeholders.contains(hitRules.toUpperCase(Locale.ROOT)))
                || (StringUtils.hasText(riskReason) && !placeholders.contains(riskReason.toUpperCase(Locale.ROOT)));
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
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

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private BigDecimal configDecimal(String key, BigDecimal fallback) {
        return configFacade.activeValue(key)
                .map(value -> {
                    try {
                        return new BigDecimal(value.trim());
                    } catch (RuntimeException ex) {
                        return readTimeSeedPolicy.enabled() ? fallback : BigDecimal.ZERO;
                    }
                })
                .orElseGet(() -> readTimeSeedPolicy.enabled() ? fallback : BigDecimal.ZERO);
    }

    private boolean configBoolean(String key, boolean fallback) {
        return configFacade.activeValue(key)
                .map(value -> "true".equalsIgnoreCase(value) || "1".equals(value))
                .orElseGet(() -> readTimeSeedPolicy.enabled() && fallback);
    }

    private BigDecimal percent(BigDecimal ratio) {
        return ratio.multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros();
    }

    private void audit(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        audit(action, resourceType, resourceId, operator, "SUCCESS", detail);
    }

    private void audit(
            String action,
            String resourceType,
            String resourceId,
            String operator,
            String result,
            Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(StringUtils.hasText(operator) ? operator.trim() : null)
                .result(result)
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }
}

package ffdd.opsconsole.finance.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.content.facade.RiskDisclosureGateFacade;
import ffdd.opsconsole.emergency.domain.EmergencyControlRepository;
import ffdd.opsconsole.finance.domain.DepositAggregateView;
import ffdd.opsconsole.finance.domain.DepositBinRiskView;
import ffdd.opsconsole.finance.domain.DepositCardRiskParamView;
import ffdd.opsconsole.finance.domain.DepositChannelView;
import ffdd.opsconsole.finance.domain.DepositChargebackView;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.domain.DepositOpsRepository;
import ffdd.opsconsole.finance.domain.DepositReconciliationRowView;
import ffdd.opsconsole.finance.domain.TopupChargebackRecoveryCommand;
import ffdd.opsconsole.finance.domain.TopupChargebackRecoveryResult;
import ffdd.opsconsole.finance.domain.WithdrawalOrderRepository;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.TopupCommandRequest;
import ffdd.opsconsole.finance.dto.WithdrawalParamUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalLimitsUpdateRequest;
import ffdd.opsconsole.finance.dto.WithdrawalQueryRequest;
import ffdd.opsconsole.finance.dto.WithdrawalReviewRequest;
import ffdd.opsconsole.finance.dto.WithdrawalBatchReviewRequest;
import ffdd.opsconsole.growth.facade.GrowthRhythmSnapshot;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerRepository;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsFinanceService implements ffdd.opsconsole.platform.domain.AuditReplayable {
    private static final String TOPUP_CONFIG_GROUP = "finance-topup";
    private static final Set<String> CONFIRMED_DEPOSIT_STATUSES = Set.of("CONFIRMED", "CREDITED", "SUCCESS");
    private static final Map<String, List<String>> TOPUP_FLOW_STATUSES = Map.of(
            "pending", List.of("CREATED", "PENDING", "CONFIRMING", "WAITING_CONFIRMATION"),
            "confirmed", List.of("CONFIRMED", "CREDITED", "SUCCESS"),
            "abnormal", List.of("FAILED", "DECLINED", "EXPIRED", "REJECTED", "ABNORMAL",
                    "CHARGEBACK", "DISPUTED", "CHARGEBACK_REVIEW", "CHARGEBACK_REFUNDED",
                    "CHARGEBACK_RECOVERED", "CHARGEBACK_PARTIAL"));
    private static final List<TopupChannelDef> TOPUP_CHANNELS = List.of(
            new TopupChannelDef("USDT-TRC20", "trc20", new BigDecimal("1"), "USDT_FIXED", new BigDecimal("10"), true),
            new TopupChannelDef("USDT-ERC20", "erc20", new BigDecimal("5"), "USDT_FIXED", new BigDecimal("10"), true),
            new TopupChannelDef("BTC", "btc", new BigDecimal("0.5"), "PERCENT", new BigDecimal("20"), true),
            new TopupChannelDef("ETH", "eth", new BigDecimal("0.5"), "PERCENT", new BigDecimal("20"), true),
            new TopupChannelDef("银行卡", "card", new BigDecimal("3.5"), "PERCENT", new BigDecimal("10"), true));
    private static final List<TopupCardParamDef> TOPUP_CARD_PARAMS = List.of(
            new TopupCardParamDef("threeDsThreshold", "3DS 强认证金额门槛", new BigDecimal("50"), "USD", BigDecimal.ZERO, new BigDecimal("500"), "达到门槛的银行卡支付必须通过发卡行验证"),
            new TopupCardParamDef("cardRetryLimit", "同卡 24 小时失败次数上限", new BigDecimal("5"), "COUNT", new BigDecimal("3"), new BigDecimal("10"), "达到上限后，系统自动锁定对应 BIN、IP 与设备"),
            new TopupCardParamDef("cardLockHours", "银行卡风控锁定时长", new BigDecimal("24"), "HOUR", BigDecimal.ONE, new BigDecimal("72"), "自动和手动锁定的有效时长（1 至 72 小时）"));
    private static final Set<String> REVIEWABLE = Set.of("REVIEW_PENDING", "REVIEWING");
    private static final Set<String> REJECTABLE = Set.of(
            "REVIEW_PENDING", "REVIEWING", "FROZEN", "REVIEW_PASSED", "PENDING_CHAIN",
            "PROCESSING", "SENT", "CHAIN_SUBMITTED", "TX_ORPHANED", "DEAD");
    private static final Set<String> FINAL_STATUSES = Set.of(
            "CONFIRMED", "SUCCESS", "TX_FAILED", "FAILED", "REVIEW_REJECTED", "REJECTED", "REFUNDED");
    private static final Set<String> D2_REASON_CODES = Set.of(
            "RISK_HIT", "ADDRESS_RISK", "DATA_MISMATCH", "USER_CANCELLED", "OTHER");
    private static final Set<String> D2_SORT_FIELDS = Set.of("createdAt", "amount", "riskScore", "status");
    private static final String WITHDRAW_KILLSWITCH_KEY = "killswitch.withdraw";
    private static final String WITHDRAW_LEGACY_KILLSWITCH_KEY = "emergency.killswitch.withdraw";
    private static final String WITHDRAW_DISCLOSURE_GATE_KEY = "disclosure.gate.withdraw";
    private static final String WITHDRAW_GEO_EMERGENCY_KEY = "emergency.geo.j4.block.required";
    private static final String WITHDRAW_GEO_ENDPOINT_KEY = "withdraw";
    private static final Pattern FIRST_DECIMAL = Pattern.compile("(\\d+(?:\\.\\d+)?)");
    private final PlatformConfigFacade configFacade;
    private final EmergencyControlRepository emergencyRepository;
    private final TreasuryCoverageFacade coverageFacade;
    private final WithdrawalOrderRepository withdrawalRepository;
    private final DepositOpsRepository depositOpsRepository;
    private final RiskKycReviewFacade riskKycReviewFacade;
    private final RiskOpsRepository riskOpsRepository;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper;
    private final RiskDisclosureGateFacade disclosureGateFacade;
    private final TreasuryLedgerRepository treasuryLedgerRepository;
    private final EventOutboxService eventOutboxService;
    private final AdminOperatorRoleResolver operatorRoleResolver;

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
        BigDecimal feeBuffer = depositOpsRepository.feeBufferBalance();
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
        long feeEvidenceAnomalies = depositOpsRepository.feeEvidenceAnomalyCount();
        long treasuryReserveAnomalies = depositOpsRepository.treasuryReserveAnomalyCount();
        long historicalBackfillAnomalies = depositOpsRepository.historicalBackfillAnomalyCount();
        response.put("feeBufferComplete", feeEvidenceAnomalies == 0);
        response.put("treasuryReserveComplete", treasuryReserveAnomalies == 0);
        response.put("historicalBackfillComplete", historicalBackfillAnomalies == 0);
        response.put("feeEvidenceAnomalyCount", feeEvidenceAnomalies);
        response.put("treasuryReserveAnomalyCount", treasuryReserveAnomalies);
        response.put("historicalBackfillAnomalyCount", historicalBackfillAnomalies);
        response.put("bins", bins);
        response.put("binLockedCount", lockedBins);
        response.put("chargebacks", chargebacks());
        response.put("sources", List.of(
                "nx_deposit_order",
                "nx_payment_record",
                "nx_topup_provider_statement",
                "nx_wallet_ledger",
                "nx_topup_fee_buffer_account/nx_topup_fee_buffer_ledger",
                "nx_topup_chargeback_recovery",
                "nx_topup_risk_lock",
                "nx_deposit_reconciliation_writeoff",
                "nx_config_item: finance.topup.params"));
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
        String actor = AdminActorResolver.resolve(request.operator());
        if (!StringUtils.hasText(request.expectedValue())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EXPECTED_VALUE_REQUIRED");
        }
        return executeD1("D1_TOPUP_CHANNEL_ENABLED_" + channel.code().toUpperCase(Locale.ROOT), idempotencyKey, request, () -> {
            String configKey = channelConfigKey(channel.code(), "enabled");
            String before = configValueForUpdate(configKey, String.valueOf(channel.defaultEnabled()));
            if (!sameBoolean(before, request.expectedValue())) {
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "CONFIG_VERSION_CONFLICT");
            }
            configFacade.upsertAdminValue(configKey, String.valueOf(request.enabled()), "BOOLEAN", TOPUP_CONFIG_GROUP, "D1 topup channel enabled");
            auditRequired("D1_TOPUP_CHANNEL_STATUS_CHANGED", "TOPUP_CHANNEL", channel.id(), actor, Map.of(
                    "channel", channel.id(), "before", before, "enabled", request.enabled(), "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            return topupOverview();
        });
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
        if (!StringUtils.hasText(request.value())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "PSP_REQUIRED");
        }
        String next = request.value().trim();
        if (!Set.of("Checkout.com", "Stripe").contains(next)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "UNSUPPORTED_PSP");
        }
        if (!StringUtils.hasText(request.expectedValue())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EXPECTED_VALUE_REQUIRED");
        }
        String actor = AdminActorResolver.resolve(request.operator());
        return executeD1("D1_TOPUP_PSP_SWITCH", idempotencyKey, request, () -> {
            String current = configValueForUpdate("finance.topup.psp.primary", "Checkout.com");
            if (!current.equals(request.expectedValue().trim())) {
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "CONFIG_VERSION_CONFLICT");
            }
            if (current.equals(next)) {
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
            }
            configFacade.upsertAdminValue("finance.topup.psp.primary", next, "STRING", TOPUP_CONFIG_GROUP, "D1 primary card PSP");
            auditRequired("D1_TOPUP_PSP_SWITCHED", "TOPUP_PSP", next, actor, Map.of(
                    "from", current, "to", next, "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            return topupOverview();
        });
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
        CanonicalNumeric value;
        try {
            value = normalizeCardRiskValue(param.key(), request);
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        String actor = AdminActorResolver.resolve(request.operator());
        if (!StringUtils.hasText(request.expectedValue())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EXPECTED_VALUE_REQUIRED");
        }
        return executeD1("D1_TOPUP_CARD_RISK_" + param.key().toUpperCase(Locale.ROOT), idempotencyKey, request, () -> {
            String configKey = cardParamConfigKey(param.key());
            String before = configValueForUpdate(configKey, param.defaultValue().toPlainString());
            if (!sameDecimal(before, request.expectedValue())) {
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "CONFIG_VERSION_CONFLICT");
            }
            configFacade.upsertAdminValue(configKey, value.value(), "NUMBER", TOPUP_CONFIG_GROUP, "D1 card risk parameter");
            configFacade.upsertAdminValue(configKey + ".unit", value.unit(), "STRING", TOPUP_CONFIG_GROUP, "D1 card risk parameter unit");
            auditRequired("D1_TOPUP_CARD_RISK_PARAM_CHANGED", "TOPUP_CARD_PARAM", param.key(), actor, Map.of(
                    "key", param.key(), "before", before, "value", value.value(), "unit", value.unit(),
                    "reason", request.reason().trim(), "idempotencyKey", idempotencyKey.trim()));
            return topupOverview();
        });
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
        String method = request.method() == null ? "" : request.method().trim().toUpperCase(Locale.ROOT);
        if (!"CONFIRM_EXCEPTION".equals(method)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RECONCILIATION_METHOD_INVALID");
        }
        String evidenceRef = trimToNull(request.evidenceRef());
        if (evidenceRef == null || evidenceRef.length() > 128) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RECONCILIATION_EVIDENCE_REQUIRED");
        }
        LocalDate reconcileDate = LocalDate.now();
        if (depositOpsRepository.hasReconciliationWriteoff(channel.code(), reconcileDate)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        DepositReconciliationRowView row = reconciliationRows().stream()
                .filter(candidate -> channel.id().equalsIgnoreCase(candidate.channel()))
                .findFirst().orElse(null);
        if (row == null || (safe(row.diffAmount()).compareTo(BigDecimal.ZERO) == 0
                && java.util.Objects.equals(row.providerCount(), row.ledgerCount()))) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "RECONCILIATION_HAS_NO_DIFFERENCE");
        }
        String actor = AdminActorResolver.resolve(request.operator());
        return executeD1("D1_TOPUP_RECONCILIATION_" + channel.code().toUpperCase(Locale.ROOT), idempotencyKey, request, () -> {
            depositOpsRepository.writeoffReconciliation(
                    channel.code(), reconcileDate, method, evidenceRef, actor, request.reason().trim(), idempotencyKey.trim());
            auditRequired("D1_TOPUP_RECONCILIATION_WRITEOFF", "TOPUP_RECONCILIATION", channel.id(), actor, Map.of(
                    "channel", channel.id(), "reconcileDate", reconcileDate.toString(), "method", method,
                    "evidenceRef", evidenceRef, "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
            return topupOverview();
        });
    }

    public ApiResult<Map<String, Object>> createTopupBinLock(String idempotencyKey, TopupCommandRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!StringUtils.hasText(request.value())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "BIN_SEGMENT_REQUIRED");
        }
        String segment = request.value().trim();
        if (!segment.matches("\\d{6,8}")) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "BIN_SEGMENT_INVALID");
        }
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
        RiskTarget target;
        try {
            target = normalizeRiskTarget(segment);
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        int lockHours = parseInteger(configValue("finance.topup.card.cardLockHours", "24"), 24);
        String actor = AdminActorResolver.resolve(request.operator());
        return executeD1("D1_TOPUP_RISK_LOCK_" + target.type() + "_" + sanitizeKey(target.value()), idempotencyKey, request, () -> {
            depositOpsRepository.setRiskLock(
                    target.type(), target.value(), request.enabled(), lockHours, request.reason().trim(), actor);
            auditRequired(request.enabled() ? "D1_TOPUP_RISK_LOCKED" : "D1_TOPUP_RISK_UNLOCKED",
                    "TOPUP_RISK_LOCK", target.type() + ":" + target.value(), actor, Map.of(
                            "targetType", target.type(), "targetValue", target.value(), "locked", request.enabled(),
                            "lockHours", lockHours, "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
            return topupOverview();
        });
    }

    public ApiResult<Map<String, Object>> refundTopupChargeback(String caseNo, String idempotencyKey, TopupCommandRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!Boolean.TRUE.equals(request.evidenceConfirmed()) || !StringUtils.hasText(request.evidenceRef())
                || request.evidenceRef().trim().length() > 128) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CHARGEBACK_EVIDENCE_REQUIRED");
        }
        if (!StringUtils.hasText(caseNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CHARGEBACK_CASE_REQUIRED");
        }
        String normalizedCaseNo = caseNo.trim();
        String actor = AdminActorResolver.resolve(request.operator());
        return executeD1("D1_TOPUP_CHARGEBACK_" + normalizedCaseNo.toUpperCase(Locale.ROOT), idempotencyKey, request, () -> {
            DepositChargebackView chargeback = depositOpsRepository.findChargeback(normalizedCaseNo).orElse(null);
            if (chargeback == null) {
                return ApiResult.fail(404, "CHARGEBACK_NOT_FOUND");
            }
            if (Set.of("CHARGEBACK_RECOVERED", "CHARGEBACK_PARTIAL")
                    .contains(chargeback.status().toUpperCase(Locale.ROOT))) {
                return ApiResult.fail(
                        OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                        OpsErrorCode.INVALID_STATE_TRANSITION.name());
            }
            if (!"已入账".equals(chargeback.enteredStatus())) {
                return ApiResult.fail(
                        OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                        "CHARGEBACK_LEDGER_ENTRY_NOT_FOUND");
            }
            BigDecimal requiredFee = safe(chargeback.feeBufferRequired()).setScale(6, RoundingMode.HALF_UP);
            TopupChargebackRecoveryResult recovery = depositOpsRepository.recoverChargeback(
                    new TopupChargebackRecoveryCommand(
                            normalizedCaseNo, chargeback.userId(), safe(chargeback.amount()), requiredFee,
                            request.evidenceRef().trim(), request.reason().trim(), actor, idempotencyKey.trim()));
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("caseNo", normalizedCaseNo);
            detail.put("userId", chargeback.userId());
            detail.put("amount", chargeback.amount());
            detail.put("recoveredAmount", recovery.recoveredAmount());
            detail.put("walletShortfall", recovery.walletShortfall());
            detail.put("feeBufferDeducted", recovery.feeBufferDeducted());
            detail.put("feeBufferShortfall", recovery.feeBufferShortfall());
            detail.put("recoveryStatus", recovery.status());
            detail.put("ledgerBizNo", recovery.ledgerBizNo());
            detail.put("riskSignalNo", recovery.riskSignalNo());
            detail.put("evidenceRef", request.evidenceRef().trim());
            detail.put("reason", request.reason().trim());
            detail.put("idempotencyKey", idempotencyKey.trim());
            auditRequired("D1_TOPUP_CHARGEBACK_RECOVERED", "TOPUP_CHARGEBACK", normalizedCaseNo, actor, detail);
            return topupOverview();
        });
    }

    public ApiResult<Map<String, Object>> withdrawalParams() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        GrowthRhythmSnapshot rhythm = GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy);
        BigDecimal dailyLimitCount = configDecimal("withdrawal.daily_count_limit", BigDecimal.ZERO);
        BigDecimal maxBalanceRatio = configDecimal("withdrawal.max_balance_pct", BigDecimal.ZERO);
        BigDecimal feeRate = configDecimal("withdrawal.fee_rate", BigDecimal.ZERO);
        BigDecimal nexFeeOffsetRate = configDecimal(
                "withdrawal.nex_fee_offset_rate", new BigDecimal("0.40"));
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dailyLimitCount", dailyLimitCount.intValue());
        response.put("maxBalancePct", percent(maxBalanceRatio));
        response.put("maxBalanceRatio", maxBalanceRatio);
        response.put("feeRatePct", percent(feeRate));
        response.put("feeRate", feeRate);
        response.put("nexFeeOffsetRate", nexFeeOffsetRate);
        response.put("minUsdt", configDecimal("withdrawal.min_usdt", BigDecimal.ZERO));
        response.put("trc20Enabled", optionalConfigBoolean("withdrawal.trc20.enabled"));
        response.put("erc20Enabled", optionalConfigBoolean("withdrawal.erc20.enabled"));
        response.put("h1Rhythm", rhythm.summary());
        response.put("h1WithdrawRules", Map.of(
                "sourceDomain", "H1",
                "penaltyFeeRate", rhythm.withdrawPenaltyFeeRate(),
                "cooldownDays", rhythm.withdrawCooldownDays()));
        response.put("coverageRatio", coverage.coverageRatio());
        response.put("redlinePct", coverage.redlinePct());
        response.put("sources", List.of("nx_config_item: withdrawal.*", "B1 treasury coverage facade", "H1 growth rhythm facade"));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> withdrawalLimits() {
        try {
            return ApiResult.ok(withdrawalLimitsData());
        } catch (IllegalStateException ex) {
            return ApiResult.fail(503, "D5_CONFIG_UNAVAILABLE");
        }
    }

    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> updateWithdrawalLimits(
            String idempotencyKey,
            WithdrawalLimitsUpdateRequest request) {
        if (request != null && request.hasPhaseFields()) {
            return new ApiResult<>(OpsErrorCode.PHASE_PARAM_READONLY.httpStatus(),
                    OpsErrorCode.PHASE_PARAM_READONLY.name(),
                    Map.of("redirect", "/admin/phase/h1"));
        }
        ApiResult<Map<String, Object>> guard = requireCommand(
                idempotencyKey, request == null ? null : request.getReason());
        if (guard != null) {
            return guard;
        }
        if (request.getExpectedVersion() == null || request.getExpectedVersion() < 0) {
            return ApiResult.fail(400, "EXPECTED_VERSION_REQUIRED");
        }
        Set<String> changedFields = request.changedD5Fields();
        if (changedFields.isEmpty()) {
            return ApiResult.fail(400, "D5_CHANGE_REQUIRED");
        }
        ApiResult<Map<String, Object>> validation = validateWithdrawalLimitsRequest(request, changedFields);
        if (validation != null) {
            return validation;
        }
        String actor = AdminActorResolver.resolve(request.getOperator());
        String requestHash = sha256(String.join("|",
                "D5_WITHDRAWAL_LIMITS_UPDATE",
                String.valueOf(request.getExpectedVersion()),
                changedFields.stream().sorted().toList().toString(),
                String.valueOf(request.getDailyLimitCount()),
                String.valueOf(request.getBalanceMaxRatio()),
                String.valueOf(request.getNetworkFeeRatio()),
                String.valueOf(request.getNetworkFeeMin()),
                String.valueOf(request.getNetworkFeeMax()),
                String.valueOf(request.getNexFeeOffsetRate()),
                request.getReason().trim(), trimToEmpty(actor)));
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                "D5_WITHDRAWAL_LIMITS_UPDATE",
                idempotencyKey.trim(),
                requestHash,
                ApiResult.class,
                () -> updateWithdrawalLimitsNew(idempotencyKey.trim(), request, changedFields, actor));
    }

    private ApiResult<Map<String, Object>> updateWithdrawalLimitsNew(
            String idempotencyKey,
            WithdrawalLimitsUpdateRequest request,
            Set<String> changedFields,
            String actor) {
        long currentVersion;
        try {
            currentVersion = configFacade.activeValueForUpdate("withdrawal.d5.version")
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(Long::parseLong)
                    .orElseThrow(() -> new IllegalStateException("D5_VERSION_MISSING"));
        } catch (RuntimeException ex) {
            return ApiResult.fail(503, "D5_CONFIG_UNAVAILABLE");
        }
        if (currentVersion != request.getExpectedVersion()) {
            return ApiResult.fail(409, "CONFIG_VERSION_CONFLICT");
        }

        Map<String, BigDecimal> before;
        try {
            before = currentD5OwnedValues();
        } catch (IllegalStateException ex) {
            return ApiResult.fail(503, "D5_CONFIG_UNAVAILABLE");
        }
        BigDecimal nextFeeMin = changedFields.contains("networkFeeMin")
                ? request.getNetworkFeeMin() : before.get("networkFeeMin");
        BigDecimal nextFeeMax = changedFields.contains("networkFeeMax")
                ? request.getNetworkFeeMax() : before.get("networkFeeMax");
        if (nextFeeMin.compareTo(nextFeeMax) > 0) {
            return ApiResult.fail(400, "NETWORK_FEE_RANGE_INVALID");
        }
        boolean amplifies = changedFields.stream().anyMatch(field ->
                loosensWithdrawalLimit(field, before.get(field), requestedWithdrawalLimitValue(field, request)));
        if (amplifies) {
            TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
            if (!coverage.reliable()) {
                return ApiResult.fail(422, "COVERAGE_UNAVAILABLE");
            }
            if (coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0) {
                return new ApiResult<>(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(),
                        OpsErrorCode.COVERAGE_BELOW_REDLINE.name(),
                        Map.of("coverageRatio", coverage.coverageRatio(), "redlinePct", coverage.redlinePct()));
            }
        }

        String reason = request.getReason().trim();
        String role = operatorRoleResolver.resolve();
        for (String field : changedFields.stream().sorted().toList()) {
            BigDecimal nextValue = requestedWithdrawalLimitValue(field, request);
            String configKey = canonicalD5ConfigKey(field);
            configFacade.upsertAdminValue(configKey, nextValue.toPlainString(), "NUMBER", "wallet",
                    "D5 canonical withdrawal limit");
            configFacade.upsertAdminValue(walletD5ConfigKey(field), nextValue.toPlainString(), "NUMBER", "wallet",
                    "D5 withdrawal limit mirror");
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("field", field);
            detail.put("before", before.get(field));
            detail.put("after", nextValue);
            detail.put("operator", trimToEmpty(actor));
            detail.put("role", role);
            detail.put("reason", reason);
            detail.put("versionBefore", currentVersion);
            detail.put("versionAfter", currentVersion + 1);
            detail.put("idempotencyKey", idempotencyKey);
            auditRequired("D5_WITHDRAWAL_PARAM_CHANGED", "WITHDRAWAL_PARAM", configKey, actor, detail);
            eventOutboxService.publish("WITHDRAWAL_PARAM", configKey, "admin.withdraw_limit_changed", Map.of(
                    "field", field,
                    "before", before.get(field),
                    "after", nextValue,
                    "operator", trimToEmpty(actor),
                    "reason", reason));
        }
        configFacade.upsertAdminValue("withdrawal.d5.version", String.valueOf(currentVersion + 1),
                "NUMBER", "wallet", "D5 aggregate config version");
        Map<String, Object> response = withdrawalLimitsData();
        response.put("updatedFields", changedFields.stream().sorted().toList());
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<WithdrawalOrderView>> withdrawals(WithdrawalQueryRequest request) {
        ensureD2FallbackSeedData();
        int pageNum = clamp(request == null || request.pageNum() == null ? 1 : request.pageNum(), 1, 10_000);
        int pageSize = clamp(request == null || request.pageSize() == null ? 20 : request.pageSize(), 1, 100);
        String status = request == null ? null : trimToNull(request.status());
        if (status != null) {
            status = D2WithdrawalStateMachine.canonical(status);
        }
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
        String ipSegment = request == null ? null : normalizeIpSegment(request.ipSegment());
        String sortBy = request == null || !D2_SORT_FIELDS.contains(trimToEmpty(request.sortBy()))
                ? "createdAt" : request.sortBy().trim();
        String sortDirection = "asc".equalsIgnoreCase(request == null ? null : request.sortDirection())
                ? "asc" : "desc";
        return ApiResult.ok(withdrawalRepository.page(
                status, userId, keyword, minAmount, maxAmount, minRiskScore,
                ipSegment, sortBy, sortDirection, pageNum, pageSize));
    }

    @Transactional(rollbackFor = Exception.class)
    @SuppressWarnings({"rawtypes", "unchecked"})
    public ApiResult<Map<String, Object>> updateWithdrawalParam(String idempotencyKey, WithdrawalParamUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String key = normalizeParamKey(request.key());
        BigDecimal newValue = normalizeParamValue(key, request.value());
        String actor = AdminActorResolver.resolve(request.operator());
        String reason = request.reason().trim();
        String requestHash = sha256(String.join("|",
                "D5_WITHDRAWAL_PARAM_UPDATE",
                key,
                newValue.toPlainString(),
                reason,
                trimToEmpty(actor)));
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                "D5_WITHDRAWAL_PARAM_UPDATE",
                idempotencyKey.trim(),
                requestHash,
                ApiResult.class,
                () -> updateWithdrawalParamNew(idempotencyKey.trim(), key, newValue, reason, actor));
    }

    private ApiResult<Map<String, Object>> updateWithdrawalParamNew(
            String idempotencyKey,
            String key,
            BigDecimal newValue,
            String reason,
            String actor) {
        BigDecimal oldValue = currentParamValue(key);
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
        mirrorWalletWithdrawalConfig(key, newValue);
        auditRequired("D5_WITHDRAWAL_PARAM_CHANGED", "WITHDRAWAL_PARAM", configKey, actor, Map.of(
                "key", key,
                "configKey", configKey,
                "oldValue", oldValue,
                "newValue", newValue,
                "reason", reason,
                "idempotencyKey", idempotencyKey));
        eventOutboxService.publish("WITHDRAWAL_PARAM", configKey, "admin.withdraw_limit_changed", Map.of(
                "field", key,
                "before", oldValue,
                "after", newValue,
                "operator", trimToEmpty(actor),
                "reason", reason));
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

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<WithdrawalOrderView> reviewWithdrawal(
            String withdrawalNo,
            String idempotencyKey,
            WithdrawalReviewRequest request) {
        ApiResult<WithdrawalOrderView> guard = requireWithdrawalCommand(withdrawalNo, idempotencyKey, request);
        if (guard != null) {
            return guard;
        }
        if (!A2ReplayContext.isReplaying()) {
            if (lockMapper.countActiveByTarget("D", "withdrawal", withdrawalNo.trim()) > 0) {
                return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
            }
        }
        ensureD2FallbackSeedData();
        return executeD2Review(withdrawalNo.trim(), idempotencyKey.trim(), request);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> reviewWithdrawalsBatch(
            String idempotencyKey,
            WithdrawalBatchReviewRequest request) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (request == null || request.withdrawalIds() == null || request.withdrawalIds().isEmpty()
                || request.withdrawalIds().size() > 100 || !StringUtils.hasText(request.action())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "WITHDRAWAL_BATCH_INVALID");
        }
        if (!validReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVE", "REJECT", "DELAY", "FREEZE").contains(action)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "WITHDRAWAL_BATCH_ACTION_INVALID");
        }
        @SuppressWarnings({"rawtypes", "unchecked"})
        ApiResult<Map<String, Object>> result = (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                "D2_BATCH_" + action,
                idempotencyKey.trim(),
                sha256("D2_BATCH|" + action + "|" + String.join(",", request.withdrawalIds()) + "|" + request.reason().trim()),
                ApiResult.class,
                () -> executeD2Batch(idempotencyKey.trim(), request, action));
        return result;
    }

    private ApiResult<Map<String, Object>> executeD2Batch(
            String idempotencyKey,
            WithdrawalBatchReviewRequest request,
            String action) {
        List<String> accepted = new java.util.ArrayList<>();
        List<String> rejected = new java.util.ArrayList<>();
        List<Map<String, Object>> conflicts = new java.util.ArrayList<>();
        for (String rawId : request.withdrawalIds().stream().filter(StringUtils::hasText).distinct().toList()) {
            String withdrawalId = rawId.trim();
            WithdrawalOrderView order = withdrawalRepository.findByWithdrawalNo(withdrawalId).orElse(null);
            if (order == null) {
                conflicts.add(Map.of("withdrawalId", withdrawalId, "reason", "WITHDRAWAL_NOT_FOUND"));
                continue;
            }
            if ("APPROVE".equals(action) && order.amount() != null
                    && order.amount().compareTo(new BigDecimal("1000")) >= 0) {
                rejected.add(withdrawalId);
                continue;
            }
            ApiResult<WithdrawalOrderView> item = executeD2Review(
                    withdrawalId,
                    sha256("D2_ITEM|" + idempotencyKey + "|" + withdrawalId),
                    request.toSingleRequest());
            if (item.getCode() == 0) {
                accepted.add(withdrawalId);
            } else {
                conflicts.add(Map.of("withdrawalId", withdrawalId, "reason", item.getMessage()));
            }
        }
        String batchId = "D2B-" + sha256(idempotencyKey).substring(0, 16).toUpperCase(Locale.ROOT);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("batchId", batchId);
        data.put("accepted", accepted);
        data.put("rejected", rejected);
        data.put("conflicts", conflicts);
        data.put("reason", rejected.isEmpty() ? "" : "LARGE_AMOUNT_REQUIRES_SINGLE_MANUAL_REVIEW");
        auditRequired("D2_WITHDRAWAL_BATCH_" + action, "WITHDRAWAL_BATCH", batchId,
                AdminActorResolver.resolve(request.operator()), Map.of(
                        "accepted", accepted, "rejected", rejected, "conflicts", conflicts,
                        "reason", request.reason().trim(), "idempotencyKey", idempotencyKey));
        return ApiResult.ok(data);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<WithdrawalOrderView> executeD2Review(
            String withdrawalNo,
            String idempotencyKey,
            WithdrawalReviewRequest request) {
        return (ApiResult<WithdrawalOrderView>) (ApiResult) idempotencyService.execute(
                "D2_REVIEW_" + withdrawalNo,
                idempotencyKey,
                d2RequestHash(withdrawalNo, request),
                ApiResult.class,
                () -> executeD2ReviewOnce(withdrawalNo, idempotencyKey, request));
    }

    private ApiResult<WithdrawalOrderView> executeD2ReviewOnce(
            String withdrawalNo,
            String idempotencyKey,
            WithdrawalReviewRequest request) {
        WithdrawalOrderView order = withdrawalRepository.findByWithdrawalNo(withdrawalNo).orElse(null);
        if (order == null) {
            return ApiResult.fail(404, "WITHDRAWAL_NOT_FOUND");
        }
        if ("FROZEN".equalsIgnoreCase(order.status())
                && StringUtils.hasText(order.failureReason())
                && order.failureReason().trim().startsWith("K5_REVIEW:")) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "WITHDRAWAL_K5_REVIEW_REQUIRED");
        }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        if ("APPROVE".equals(action) && order.riskScore() == null) {
            return ApiResult.fail(503, "K4_RISK_SCORE_UNAVAILABLE");
        }
        ApiResult<WithdrawalOrderView> formGuard = validateD2ActionFields(action, request, order);
        if (formGuard != null) {
            return formGuard;
        }
        if (!isD2RoleAllowed(action, order.amount())) {
            return ApiResult.fail(403, "WITHDRAWAL_ROLE_NOT_ALLOWED");
        }
        if (withdrawalRepository.isFrozenByUserStatus(order.withdrawalNo())) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    "WITHDRAWAL_FROZEN_BY_C2_USER_STATUS");
        }
        String authenticatedOperator = AdminActorResolver.resolve(request.operator());
        String newStatus = D2WithdrawalStateMachine.next(order.status(), action);
        if (newStatus == null) {
            return ApiResult.fail(
                    OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        int dailyLimitCount = withdrawalDailyLimitCount();
        if ("APPROVE".equals(action)) {
            ApiResult<Void> disclosureGate = disclosureGateFacade.checkUserGate(
                    order.userId(), "withdraw", order.withdrawalNo());
            if (disclosureGate.getCode() != 0) {
                return ApiResult.fail(disclosureGate.getCode(), disclosureGate.getMessage());
            }
            KycReviewTriggerResult k5Review = riskKycReviewFacade.triggerLargeWithdrawalReview(
                    order.userNo(),
                    order.amount(),
                    order.kycStatus(),
                    order.withdrawalNo(),
                    authenticatedOperator,
                    request.reason().trim());
            if (k5Review.requiresReview()) {
                if (!StringUtils.hasText(k5Review.ticketId()) || !withdrawalRepository.freezeForK5Review(
                        order.withdrawalNo(), order.status(), k5Review.ticketId())) {
                    throw new IllegalStateException("D2_K5_HOLD_CONCURRENT_UPDATE");
                }
                auditWithdrawalReviewBlockedByK5(
                        order, dailyLimitCount, idempotencyKey, request, k5Review, authenticatedOperator);
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
        String failureReason = d2FailureReason(action, request);
        LocalDateTime reviewAt = Set.of("DELAY", "FREEZE").contains(action)
                ? parseD2ReviewAt(request.reviewAt()) : null;
        String owner = Set.of("DELAY", "FREEZE").contains(action) ? request.owner().trim() : null;
        String period = "FREEZE".equals(action) ? request.period().trim().toUpperCase(Locale.ROOT) : null;
        String previousStatus = "FREEZE".equals(action) ? D2WithdrawalStateMachine.canonical(order.status()) : null;
        if ("REJECT".equals(action)) {
            if (!withdrawalRepository.transitionStatusWithLifecycle(
                    order.withdrawalNo(), order.status(), D2WithdrawalStateMachine.REVIEW_REJECTED,
                    failureReason, null, null, null, null)) {
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "WITHDRAWAL_STATE_CONFLICT");
            }
            auditRequired("D2_WITHDRAWAL_REVIEW_REJECTED", "WITHDRAWAL", order.withdrawalNo(), authenticatedOperator,
                    Map.of("from", D2WithdrawalStateMachine.canonical(order.status()),
                            "to", D2WithdrawalStateMachine.REVIEW_REJECTED,
                            "reasonCode", request.reasonCode().trim(), "reason", request.reason().trim()));
            publishD2Event(order, "withdraw.rejected", D2WithdrawalStateMachine.REVIEW_REJECTED, request.reason().trim(), request);
            treasuryLedgerRepository.refundWithdrawal(
                    order.withdrawalNo(), order.userId(), order.amount(), order.asset(),
                    order.nexBurned(), request.reason().trim());
            if (!withdrawalRepository.transitionStatusWithLifecycle(
                    order.withdrawalNo(), D2WithdrawalStateMachine.REVIEW_REJECTED, D2WithdrawalStateMachine.REFUNDED,
                    failureReason, null, null, null, null)) {
                throw new IllegalStateException("D2_REJECT_REFUND_STATE_CONFLICT");
            }
            publishD2Event(order, "withdraw.refunded", D2WithdrawalStateMachine.REFUNDED, request.reason().trim(), request);
            newStatus = D2WithdrawalStateMachine.REFUNDED;
        } else if (!withdrawalRepository.transitionStatusWithLifecycle(
                order.withdrawalNo(), order.status(), newStatus, failureReason,
                reviewAt, owner, period, previousStatus)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "WITHDRAWAL_STATE_CONFLICT");
        }
        if ("APPROVE".equals(action)) {
            treasuryLedgerRepository.recordWithdrawalReserve(
                    order.withdrawalNo(), order.amount(), request.reason().trim(), authenticatedOperator, idempotencyKey);
        }
        if ("REFUND".equals(action)) {
            treasuryLedgerRepository.refundWithdrawal(
                    order.withdrawalNo(), order.userId(), order.amount(), order.asset(),
                    order.nexBurned(), request.reason().trim());
        }
        WithdrawalOrderView updated = withdrawalRepository.findByWithdrawalNo(order.withdrawalNo())
                .orElse(null);
        if (updated == null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "WITHDRAWAL_RELOAD_FAILED");
        }
        Map<String, Object> detail = withdrawalReviewAuditDetail(
                order, newStatus, dailyLimitCount, request.reason().trim(), idempotencyKey.trim());
        detail.put("reasonCode", trimToEmpty(request.reasonCode()));
        detail.put("holdDays", request.holdDays());
        detail.put("period", trimToEmpty(request.period()));
        detail.put("owner", trimToEmpty(request.owner()));
        detail.put("reviewAt", trimToEmpty(request.reviewAt()));
        auditRequired("D2_WITHDRAWAL_REVIEW_" + action, "WITHDRAWAL", order.withdrawalNo(), authenticatedOperator, detail);
        if (!"REJECT".equals(action)) {
            publishD2Event(order, "withdraw." + d2EventAction(action), newStatus, request.reason().trim(), request);
        }
        return ApiResult.ok(updated);
    }

    /** Releases due lifecycles. H1 low-risk fast-track is re-evaluated, never blindly restored. */
    @Transactional(rollbackFor = Exception.class)
    public int releaseExpiredD2Lifecycles(LocalDateTime now) {
        LocalDateTime effectiveNow = now == null ? LocalDateTime.now() : now;
        int released = 0;
        for (String withdrawalNo : withdrawalRepository.findExpiredLifecycleNos(effectiveNow)) {
            WithdrawalOrderView order = withdrawalRepository.findByWithdrawalNo(withdrawalNo).orElse(null);
            if (order == null || !Set.of(D2WithdrawalStateMachine.EXTENDED_HOLD, D2WithdrawalStateMachine.FROZEN)
                    .contains(D2WithdrawalStateMachine.canonical(order.status()))) {
                continue;
            }
            boolean h1FastTrack = "H1_PHASE_COOLDOWN".equals(order.lifecycleOwner())
                    && D2WithdrawalStateMachine.REVIEW_PASSED.equals(
                            D2WithdrawalStateMachine.canonical(order.previousStatus()));
            D2ExpiryDecision decision = h1FastTrack
                    ? h1FastTrackDecision(order, effectiveNow)
                    : new D2ExpiryDecision(D2WithdrawalStateMachine.REVIEW_PENDING,
                            "scheduled-review-due", "withdraw.review_due", null);
            boolean transitioned;
            if (D2WithdrawalStateMachine.EXTENDED_HOLD.equals(decision.status())) {
                transitioned = withdrawalRepository.transitionStatusWithLifecycle(
                        order.withdrawalNo(), order.status(), decision.status(), decision.reason(),
                        decision.nextReviewAt(), "K3_EXPIRY_DELAY", "K3_DELAY_ONCE",
                        D2WithdrawalStateMachine.REVIEW_PENDING);
            } else {
                transitioned = withdrawalRepository.releaseExpiredLifecycle(
                        order.withdrawalNo(), order.status(), decision.status(),
                        D2WithdrawalStateMachine.REVIEW_PASSED.equals(decision.status()) ? null : decision.reason(),
                        effectiveNow);
            }
            if (!transitioned) {
                continue;
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("from", D2WithdrawalStateMachine.canonical(order.status()));
            detail.put("to", decision.status());
            detail.put("owner", trimToEmpty(order.lifecycleOwner()));
            detail.put("reviewAt", order.holdUntil());
            detail.put("period", trimToEmpty(order.freezePeriod()));
            detail.put("cause", decision.reason());
            if (decision.nextReviewAt() != null) detail.put("nextReviewAt", decision.nextReviewAt());
            String auditAction = switch (decision.status()) {
                case D2WithdrawalStateMachine.REVIEW_PASSED -> "D2_WITHDRAWAL_COOLDOWN_AUTO_APPROVED";
                case D2WithdrawalStateMachine.FROZEN -> "D2_WITHDRAWAL_COOLDOWN_FROZEN";
                case D2WithdrawalStateMachine.EXTENDED_HOLD -> "D2_WITHDRAWAL_COOLDOWN_DELAYED";
                default -> "D2_WITHDRAWAL_REVIEW_DUE";
            };
            auditRequired(auditAction, "WITHDRAWAL", order.withdrawalNo(), "system:d2-scheduler", detail);
            publishD2LifecycleEvent(order, decision.eventName(), decision.status(), decision.reason(),
                    decision.nextReviewAt() == null ? null : 7,
                    decision.nextReviewAt() == null ? null : "K3_EXPIRY_DELAY",
                    decision.nextReviewAt(), decision.nextReviewAt() == null ? null : "K3_DELAY_ONCE",
                    "system:d2-scheduler");
            released++;
        }
        return released;
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
        CanonicalNumeric value;
        try {
            value = normalizeChannelValue(field, request);
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        String actor = AdminActorResolver.resolve(request.operator());
        String scope = auditAction.replace("_CHANGED", "_") + channel.code().toUpperCase(Locale.ROOT);
        if (!StringUtils.hasText(request.expectedValue())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "EXPECTED_VALUE_REQUIRED");
        }
        return executeD1(scope, idempotencyKey, request, () -> {
            String configKey = channelConfigKey(channel.code(), field);
            BigDecimal fallback = "fee".equals(field) ? channel.defaultFeeValue() : channel.defaultMinAmountValue();
            String before = configValueForUpdate(configKey, fallback.toPlainString());
            if (!sameDecimal(before, request.expectedValue())) {
                return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "CONFIG_VERSION_CONFLICT");
            }
            configFacade.upsertAdminValue(configKey, value.value(), "NUMBER", TOPUP_CONFIG_GROUP, "D1 topup channel " + field);
            configFacade.upsertAdminValue(configKey + "_unit", value.unit(), "STRING", TOPUP_CONFIG_GROUP, "D1 topup channel " + field + " unit");
            auditRequired(auditAction, "TOPUP_CHANNEL", channel.id(), actor, Map.of(
                    "channel", channel.id(), "field", field, "before", before, "value", value.value(), "unit", value.unit(),
                    "reason", request.reason().trim(), "idempotencyKey", idempotencyKey.trim()));
            return topupOverview();
        });
    }

    private List<DepositChannelView> topupChannels() {
        // 五条充值渠道是固定目录;不再按 DB 是否配置过滤,fee/min_amount/enabled 空时落回 seed 默认。
        return TOPUP_CHANNELS.stream()
                .map(channel -> {
                    BigDecimal feeValue = configNumericValue(channelConfigKey(channel.code(), "fee"), channel.defaultFeeValue());
                    String feeUnit = configValue(channelConfigKey(channel.code(), "fee_unit"), channel.defaultFeeUnit());
                    BigDecimal minValue = configNumericValue(channelConfigKey(channel.code(), "min_amount"), channel.defaultMinAmountValue());
                    return new DepositChannelView(
                            channel.id(), channel.code(), formatFee(feeValue, feeUnit), "$" + minValue.stripTrailingZeros().toPlainString(),
                            configBoolean(channelConfigKey(channel.code(), "enabled"), channel.defaultEnabled()),
                            feeValue, feeUnit, minValue, "USD");
                })
                .toList();
    }

    private List<DepositCardRiskParamView> topupCardParams() {
        // 三条刷卡风控参数是固定目录;不再按 DB 是否配置过滤,value 空时落回 seed defaultValue。
        return TOPUP_CARD_PARAMS.stream()
                .map(param -> {
                    BigDecimal numeric = configNumericValue(cardParamConfigKey(param.key()), param.defaultValue());
                    String unit = configValue(cardParamConfigKey(param.key()) + ".unit", param.unit());
                    return new DepositCardRiskParamView(
                            param.key(), param.name(), formatCardParam(numeric, unit), param.note(),
                            numeric, unit, param.minValue(), param.maxValue());
                })
                .toList();
    }

    private List<DepositReconciliationRowView> reconciliationRows() {
        Map<String, DepositAggregateView> aggregates = new HashMap<>();
        for (DepositAggregateView aggregate : depositOpsRepository.aggregateToday()) {
            String canonicalChannel = normalizeChannelCode(aggregate.channel());
            aggregates.merge(canonicalChannel, aggregate,
                    (left, right) -> mergeDepositAggregate(canonicalChannel, left, right));
        }
        return aggregates.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> {
                    String channelCode = entry.getKey();
                    TopupChannelDef channel = topupChannel(channelCode);
                    return reconciliationRow(channel == null ? channelCode : channel.id(), channelCode, entry.getValue());
                })
                .toList();
    }

    private DepositAggregateView mergeDepositAggregate(
            String canonicalChannel, DepositAggregateView left, DepositAggregateView right) {
        return new DepositAggregateView(
                canonicalChannel,
                (left.providerCount() == null ? 0L : left.providerCount())
                        + (right.providerCount() == null ? 0L : right.providerCount()),
                safe(left.providerAmount()).add(safe(right.providerAmount())),
                (left.ledgerCount() == null ? 0L : left.ledgerCount())
                        + (right.ledgerCount() == null ? 0L : right.ledgerCount()),
                safe(left.ledgerAmount()).add(safe(right.ledgerAmount())));
    }

    private DepositReconciliationRowView reconciliationRow(String channelId, String channelCode, DepositAggregateView aggregate) {
        BigDecimal providerAmount = safe(aggregate.providerAmount());
        BigDecimal ledgerAmount = safe(aggregate.ledgerAmount());
        BigDecimal diffAmount = providerAmount.subtract(ledgerAmount);
        boolean reconciled = depositOpsRepository.hasReconciliationWriteoff(channelCode, LocalDate.now());
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
        return depositOpsRepository.failedPaymentRiskRows(threshold).stream()
                .sorted(Comparator.comparing((DepositBinRiskView row) -> !Boolean.TRUE.equals(row.locked()))
                        .thenComparing(row -> row.segment() == null ? "" : row.segment()))
                .toList();
    }

    private List<DepositChargebackView> chargebacks() {
        return depositOpsRepository.chargebacks();
    }

    private void ensureD1FallbackSeedData() {
    }

    private void ensureD2FallbackSeedData() {
        // D2 withdrawals are business orders. Empty tables must remain empty until real orders are created.
    }

    private void ensureD5ConfigDefaults() {
    }

    private void syncWalletWithdrawalMirrors() {
        configFacade.upsertAdminValue("wallet.withdrawal.daily_count_limit",
                requiredConfigDecimal("withdrawal.daily_count_limit").toPlainString(),
                "NUMBER", "wallet", "D5 withdrawal daily count mirror");
        configFacade.upsertAdminValue("wallet.withdrawal.max_balance_pct",
                requiredConfigDecimal("withdrawal.max_balance_pct").toPlainString(),
                "NUMBER", "wallet", "D5 withdrawal balance cap mirror");
        configFacade.upsertAdminValue("wallet.withdrawal.fee_rate",
                requiredConfigDecimal("withdrawal.fee_rate").toPlainString(),
                "NUMBER", "wallet", "D5 withdrawal fee rate mirror");
        configFacade.upsertAdminValue("wallet.withdrawal.nex_fee_offset_rate",
                configDecimal("withdrawal.nex_fee_offset_rate", new BigDecimal("0.40")).toPlainString(),
                "NUMBER", "wallet", "D5 NEX fee offset mirror");
        configFacade.upsertAdminValue("wallet.withdrawal.min_usdt",
                requiredConfigDecimal("withdrawal.min_usdt").toPlainString(),
                "NUMBER", "wallet", "D5 minimum withdrawal USDT mirror");
        configFacade.upsertAdminValue("wallet.withdrawal.trc20.enabled",
                String.valueOf(requiredConfigBoolean("withdrawal.trc20.enabled")),
                "BOOLEAN", "wallet", "D5 TRC20 withdrawal enabled mirror");
        configFacade.upsertAdminValue("wallet.withdrawal.erc20.enabled",
                String.valueOf(requiredConfigBoolean("withdrawal.erc20.enabled")),
                "BOOLEAN", "wallet", "D5 ERC20 withdrawal enabled mirror");
    }

    private void ensureBaseConfigIfAbsent(String key, String value, String type, String group, String remark) {
        if (configFacade.activeValue(key).filter(StringUtils::hasText).isPresent()) {
            return;
        }
        configFacade.upsertAdminValue(key, value, type, group, remark);
    }

    private void seedConfigIfAbsent(String key, String value, String type, String group, String remark) {
        // Intentionally empty: read paths must not seed finance configuration.
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
            return "";
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

    private String sanitizeKey(String value) {
        return requireText(value, "KEY_REQUIRED")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9._-]", "-")
                .replaceAll("-+", "-");
    }

    private RiskTarget normalizeRiskTarget(String raw) {
        String value = requireText(raw, "RISK_TARGET_REQUIRED");
        String type = "BIN";
        int separator = value.indexOf(':');
        if (separator > 0) {
            type = value.substring(0, separator).trim().toUpperCase(Locale.ROOT);
            value = value.substring(separator + 1).trim();
        }
        boolean valid = switch (type) {
            case "BIN" -> value.matches("\\d{6,8}");
            case "IP" -> value.matches("[0-9a-fA-F:.]{3,45}");
            case "DEVICE" -> value.matches("[A-Za-z0-9._:-]{8,128}");
            default -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("RISK_TARGET_INVALID");
        }
        return new RiskTarget(type, value);
    }

    private String configValue(String key, String fallback) {
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .orElse(fallback);
    }

    private String configValueForUpdate(String key, String fallback) {
        return configFacade.activeValueForUpdate(key).filter(StringUtils::hasText).orElse(fallback);
    }

    private boolean sameBoolean(String current, String expected) {
        return Boolean.parseBoolean(current.trim()) == Boolean.parseBoolean(expected.trim())
                && Set.of("true", "false").contains(expected.trim().toLowerCase(Locale.ROOT));
    }

    private boolean sameDecimal(String current, String expected) {
        try {
            return new BigDecimal(current.trim()).compareTo(new BigDecimal(expected.trim())) == 0;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private BigDecimal configNumericValue(String key, BigDecimal fallback) {
        String raw = configValue(key, fallback.toPlainString());
        Matcher matcher = FIRST_DECIMAL.matcher(raw.replace(",", ""));
        if (!matcher.find()) {
            return fallback;
        }
        try {
            return new BigDecimal(matcher.group(1));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String formatFee(BigDecimal value, String unit) {
        String number = value.stripTrailingZeros().toPlainString();
        return "PERCENT".equalsIgnoreCase(unit) ? number + "%" : number + " USDT 固定手续费";
    }

    private String formatCardParam(BigDecimal value, String unit) {
        String number = value.stripTrailingZeros().toPlainString();
        return switch (unit == null ? "" : unit.toUpperCase(Locale.ROOT)) {
            case "USD" -> "$" + number;
            case "COUNT" -> number + " 次";
            case "HOUR" -> number + " 小时";
            default -> number;
        };
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<Map<String, Object>> executeD1(
            String scope,
            String idempotencyKey,
            TopupCommandRequest request,
            Supplier<ApiResult<Map<String, Object>>> action) {
        Supplier rawAction = action;
        return (ApiResult<Map<String, Object>>) (ApiResult) idempotencyService.execute(
                scope,
                idempotencyKey.trim(),
                d1RequestHash(scope, request),
                ApiResult.class,
                rawAction);
    }

    private String d1RequestHash(String scope, TopupCommandRequest request) {
        String canonical = scope + "|" + String.valueOf(request.value()) + "|" + String.valueOf(request.enabled())
                + "|" + String.valueOf(request.numericValue()) + "|" + String.valueOf(request.unit())
                + "|" + String.valueOf(request.method()) + "|" + String.valueOf(request.evidenceRef())
                + "|" + String.valueOf(request.evidenceConfirmed()) + "|" + String.valueOf(request.expectedValue())
                + "|" + request.reason().trim();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256_NOT_AVAILABLE", ex);
        }
    }

    private boolean configBooleanValue(String value) {
        return "true".equalsIgnoreCase(value) || "1".equals(value) || "locked".equalsIgnoreCase(value) || "enabled".equalsIgnoreCase(value);
    }

    private int parseInteger(String value, int fallback) {
        try {
            return Integer.parseInt(value.replaceAll("[^0-9-]", ""));
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private CanonicalNumeric normalizeChannelValue(String field, TopupCommandRequest request) {
        if (request == null || request.numericValue() == null || !StringUtils.hasText(request.unit())) {
            throw new IllegalArgumentException("TOPUP_STRUCTURED_VALUE_REQUIRED");
        }
        BigDecimal amount = request.numericValue();
        String unit = request.unit().trim().toUpperCase(Locale.ROOT);
        if ("min_amount".equals(field)) {
            if (!"USD".equals(unit)) {
                throw new IllegalArgumentException("TOPUP_MIN_AMOUNT_UNIT_INVALID");
            }
            if (amount.compareTo(BigDecimal.ZERO) < 0 || amount.compareTo(new BigDecimal("100000")) > 0) {
                throw new IllegalArgumentException("TOPUP_MIN_AMOUNT_OUT_OF_RANGE");
            }
            return new CanonicalNumeric(amount.stripTrailingZeros().toPlainString(), unit);
        }
        if (!Set.of("PERCENT", "USDT_FIXED").contains(unit)) {
            throw new IllegalArgumentException("TOPUP_FEE_UNIT_INVALID");
        }
        BigDecimal max = "PERCENT".equals(unit) ? new BigDecimal("10") : new BigDecimal("100");
        if (amount.compareTo(BigDecimal.ZERO) < 0 || amount.compareTo(max) > 0) {
            throw new IllegalArgumentException("TOPUP_FEE_OUT_OF_RANGE");
        }
        return new CanonicalNumeric(amount.stripTrailingZeros().toPlainString(), unit);
    }

    private CanonicalNumeric normalizeCardRiskValue(String key, TopupCommandRequest request) {
        if (request == null || request.numericValue() == null || !StringUtils.hasText(request.unit())) {
            throw new IllegalArgumentException("CARD_PARAM_STRUCTURED_VALUE_REQUIRED");
        }
        BigDecimal amount = request.numericValue();
        String unit = request.unit().trim().toUpperCase(Locale.ROOT);
        BigDecimal min;
        BigDecimal max;
        boolean integer;
        String expectedUnit;
        switch (key) {
            case "threeDsThreshold" -> {
                min = BigDecimal.ZERO;
                max = new BigDecimal("500");
                integer = false;
                expectedUnit = "USD";
            }
            case "cardRetryLimit" -> {
                min = new BigDecimal("3");
                max = new BigDecimal("10");
                integer = true;
                expectedUnit = "COUNT";
            }
            case "cardLockHours" -> {
                min = BigDecimal.ONE;
                max = new BigDecimal("72");
                integer = true;
                expectedUnit = "HOUR";
            }
            default -> throw new IllegalArgumentException("CARD_PARAM_NOT_FOUND");
        }
        if (!expectedUnit.equals(unit)) {
            throw new IllegalArgumentException("CARD_PARAM_UNIT_INVALID");
        }
        if (amount.compareTo(min) < 0 || amount.compareTo(max) > 0 || (integer && amount.stripTrailingZeros().scale() > 0)) {
            throw new IllegalArgumentException("CARD_PARAM_OUT_OF_RANGE");
        }
        return new CanonicalNumeric(amount.stripTrailingZeros().toPlainString(), unit);
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

    private record TopupChannelDef(String id, String code, BigDecimal defaultFeeValue, String defaultFeeUnit,
                                   BigDecimal defaultMinAmountValue, boolean defaultEnabled) {
    }

    private record TopupCardParamDef(String key, String name, BigDecimal defaultValue, String unit,
                                     BigDecimal minValue, BigDecimal maxValue, String note) {
    }

    private record CanonicalNumeric(String value, String unit) {
    }

    private record RiskTarget(String type, String value) {
    }

    private ApiResult<Map<String, Object>> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 8 || reason.trim().length() > 200) {
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
        if (request == null || !validReason(request.reason())) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        if (!StringUtils.hasText(request.action())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "ACTION_REQUIRED");
        }
        return null;
    }

    private boolean validReason(String reason) {
        return StringUtils.hasText(reason) && reason.trim().length() >= 8 && reason.trim().length() <= 200;
    }

    private ApiResult<WithdrawalOrderView> validateD2ActionFields(
            String action, WithdrawalReviewRequest request, WithdrawalOrderView order) {
        if ("REJECT".equals(action) && !D2_REASON_CODES.contains(
                trimToEmpty(request.reasonCode()).toUpperCase(Locale.ROOT))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "WITHDRAWAL_REASON_CODE_REQUIRED");
        }
        if ("DELAY".equals(action)) {
            if (request.holdDays() == null || request.holdDays() < 1 || request.holdDays() > 45
                    || !StringUtils.hasText(request.owner()) || !validReviewAt(request.reviewAt(), request.holdDays(), 1)) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "WITHDRAWAL_DELAY_LIFECYCLE_REQUIRED");
            }
        }
        if ("FREEZE".equals(action)) {
            String period = trimToEmpty(request.period()).toUpperCase(Locale.ROOT);
            int expectedDays = switch (period) {
                case "7D" -> 7;
                case "14D" -> 14;
                case "30D" -> 30;
                case "45D" -> 45;
                case "LONG_TERM" -> 0;
                default -> -1;
            };
            boolean reviewValid = expectedDays == 0
                    ? validReviewAt(request.reviewAt(), 365, 2)
                    : validReviewAt(request.reviewAt(), expectedDays, 1);
            if (expectedDays < 0 || !StringUtils.hasText(request.owner()) || !reviewValid) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "WITHDRAWAL_FREEZE_LIFECYCLE_REQUIRED");
            }
        }
        if ("APPROVE".equals(action) && order.amount() != null
                && order.amount().compareTo(new BigDecimal("1000")) >= 0
                && !Boolean.TRUE.equals(request.addressVerified())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "WITHDRAWAL_ADDRESS_CONFIRMATION_REQUIRED");
        }
        if ("REFUND".equals(action) && !Boolean.TRUE.equals(request.fundsVerified())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "WITHDRAWAL_FUNDS_CONFIRMATION_REQUIRED");
        }
        return null;
    }

    private boolean isD2RoleAllowed(String action, BigDecimal amount) {
        String role = trimToEmpty(operatorRoleResolver.resolveCode()).toUpperCase(Locale.ROOT);
        if (!StringUtils.hasText(role)) {
            // Endpoint authorization remains the first gate. Blank role is only expected for direct-construction
            // tests/system replay; authenticated production admins resolve through AdminOperatorRoleResolver.
            return true;
        }
        if ("SUPER_ADMIN".equals(role)) {
            return true;
        }
        boolean lead = Set.of("FINANCE_LEAD", "RISK_LEAD").contains(role);
        if ("REFUND".equals(action) || "FREEZE".equals(action) || "UNFREEZE".equals(action)) {
            return lead;
        }
        if ("APPROVE".equals(action) && amount != null && amount.compareTo(new BigDecimal("1000")) >= 0) {
            return "FINANCE_LEAD".equals(role);
        }
        return true;
    }

    private String d2FailureReason(String action, WithdrawalReviewRequest request) {
        if (!Set.of("REJECT", "REFUND", "DELAY", "FREEZE").contains(action)) {
            return null;
        }
        StringBuilder value = new StringBuilder(request.reason().trim());
        if (StringUtils.hasText(request.reasonCode())) value.append(" | code=").append(request.reasonCode().trim());
        if (request.holdDays() != null) value.append(" | holdDays=").append(request.holdDays());
        if (StringUtils.hasText(request.period())) value.append(" | period=").append(request.period().trim());
        if (StringUtils.hasText(request.owner())) value.append(" | owner=").append(request.owner().trim());
        if (StringUtils.hasText(request.reviewAt())) value.append(" | reviewAt=").append(request.reviewAt().trim());
        return value.length() <= 512 ? value.toString() : value.substring(0, 512);
    }

    private String d2EventAction(String action) {
        return switch (action) {
            case "APPROVE" -> "approved";
            case "REJECT" -> "rejected";
            case "DELAY" -> "delayed";
            case "FREEZE" -> "frozen";
            case "UNFREEZE" -> "unfrozen";
            case "REFUND" -> "refunded";
            default -> "updated";
        };
    }

    private String d2RequestHash(String withdrawalNo, WithdrawalReviewRequest request) {
        return sha256(String.join("|",
                "D2", withdrawalNo, trimToEmpty(request.action()), trimToEmpty(request.reason()),
                trimToEmpty(request.reasonCode()), String.valueOf(request.holdDays()), trimToEmpty(request.period()),
                trimToEmpty(request.owner()), trimToEmpty(request.reviewAt()), String.valueOf(request.fundsVerified()),
                String.valueOf(request.addressVerified())));
    }

    private void publishD2Event(
            WithdrawalOrderView order, String eventName, String state, String reason, WithdrawalReviewRequest request) {
        Integer holdDays = request == null ? null : request.holdDays();
        String owner = request == null ? null : request.owner();
        LocalDateTime reviewAt = request == null ? null : parseD2ReviewAt(request.reviewAt());
        String period = request == null ? null : request.period();
        String operator = request == null ? null : AdminActorResolver.resolve(request.operator());
        publishD2LifecycleEvent(order, eventName, state, reason, holdDays, owner, reviewAt, period, operator);
    }

    private void publishD2LifecycleEvent(
            WithdrawalOrderView order,
            String eventName,
            String state,
            String reason,
            Integer holdDays,
            String owner,
            LocalDateTime reviewAt,
            String period,
            String operator) {
        if ("withdraw.approved".equals(eventName)) {
            eventOutboxService.publish("WITHDRAWAL", order.withdrawalNo(), eventName,
                    canonicalApprovedPayload(order, reason, operator));
            return;
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("withdrawal_id", order.withdrawalNo());
        payload.put("amount", order.amount());
        payload.put("currency", order.asset());
        payload.put("state", state);
        payload.put("reason", reason);
        payload.put("address_hash", sha256(trimToEmpty(order.targetAddress())));
        if (order.riskScore() == null) {
            payload.put("risk_score_status", "UNAVAILABLE");
        } else {
            payload.put("risk_score", order.riskScore());
        }
        if (holdDays != null) payload.put("hold_days", holdDays);
        if (StringUtils.hasText(owner)) payload.put("lifecycle_owner", owner.trim());
        if (reviewAt != null) payload.put("review_at", reviewAt);
        if (StringUtils.hasText(period)) payload.put("freeze_period", period.trim().toUpperCase(Locale.ROOT));
        if (StringUtils.hasText(operator)) payload.put("operator", operator.trim());
        eventOutboxService.publish("WITHDRAWAL", order.withdrawalNo(), eventName, payload);
    }

    Map<String, Object> canonicalApprovedPayload(
            WithdrawalOrderView order, String reason, String operator) {
        if (order.riskScore() == null || !StringUtils.hasText(operator)) {
            throw new IllegalStateException("WITHDRAW_APPROVED_CANONICAL_FACTS_UNAVAILABLE");
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("withdrawal_id", order.withdrawalNo());
        payload.put("amount", order.amount());
        payload.put("currency", order.asset());
        payload.put("state", D2WithdrawalStateMachine.REVIEW_PASSED);
        payload.put("reason", reason);
        payload.put("address_hash", sha256(trimToEmpty(order.targetAddress())));
        payload.put("risk_score", order.riskScore());
        payload.put("operator", operator.trim());
        return payload;
    }

    private D2ExpiryDecision h1FastTrackDecision(WithdrawalOrderView order, LocalDateTime now) {
        String k3Route = trimToEmpty(order.k3RiskRoute()).toLowerCase(Locale.ROOT);
        if (!Set.of("pass", "delay", "manual", "freeze").contains(k3Route)) {
            return new D2ExpiryDecision(D2WithdrawalStateMachine.REVIEW_PENDING,
                    "K3_ROUTE_UNAVAILABLE", "withdraw.review_due", null);
        }
        if ("freeze".equals(k3Route)) {
            return new D2ExpiryDecision(D2WithdrawalStateMachine.FROZEN,
                    "K3_ROUTE:freeze", "withdraw.frozen", null);
        }
        if ("delay".equals(k3Route)) {
            // One bounded D2 hold preserves K3 authority; previousStatus is changed so this rule cannot loop forever.
            return new D2ExpiryDecision(D2WithdrawalStateMachine.EXTENDED_HOLD,
                    "K3_ROUTE:delay", "withdraw.delayed", now.plusDays(7));
        }
        if ("manual".equals(k3Route)) {
            return new D2ExpiryDecision(D2WithdrawalStateMachine.REVIEW_PENDING,
                    "K3_ROUTE:manual", "withdraw.review_due", null);
        }
        KycReviewTriggerResult k5Review = riskKycReviewFacade.triggerLargeWithdrawalReview(
                order.userNo(), order.amount(), order.kycStatus(), order.withdrawalNo(),
                "system:d2-scheduler", "H1 cooldown expiry server-canonical re-evaluation");
        if (k5Review == null) {
            return new D2ExpiryDecision(D2WithdrawalStateMachine.REVIEW_PENDING,
                    "K5_WITHDRAWAL_REVIEW_UNAVAILABLE", "withdraw.review_due", null);
        }
        if (k5Review.requiresReview()) {
            return new D2ExpiryDecision(D2WithdrawalStateMachine.FROZEN,
                    "K5_REVIEW:" + trimToEmpty(k5Review.ticketId()), "withdraw.frozen", null);
        }
        if (order.riskScore() == null || order.k4BandLowMax() == null
                || order.k4BandHighMin() == null || order.k4AutoEscalateScore() == null
                || order.k4BandLowMax() < 0
                || order.k4BandLowMax() >= order.k4BandHighMin()
                || order.k4BandHighMin() > 100
                || order.k4AutoEscalateScore() < order.k4BandHighMin()
                || order.k4AutoEscalateScore() > 100) {
            return new D2ExpiryDecision(D2WithdrawalStateMachine.REVIEW_PENDING,
                    "K4_RISK_SCORE_UNAVAILABLE", "withdraw.review_due", null);
        }
        if (order.riskScore() >= order.k4AutoEscalateScore()
                || !"LOW".equalsIgnoreCase(trimToEmpty(order.routingPriority()))) {
            return new D2ExpiryDecision(D2WithdrawalStateMachine.REVIEW_PENDING,
                    "K4_FAST_TRACK_NO_LONGER_ELIGIBLE", "withdraw.review_due", null);
        }
        String gateReason = approvalBlockReason(order, withdrawalDailyLimitCount());
        if (gateReason != null) {
            return new D2ExpiryDecision(D2WithdrawalStateMachine.REVIEW_PENDING,
                    gateReason, "withdraw.review_due", null);
        }
        return new D2ExpiryDecision(D2WithdrawalStateMachine.REVIEW_PASSED,
                "H1_COOLDOWN_FAST_TRACK_APPROVED", "withdraw.approved", null);
    }

    private record D2ExpiryDecision(String status, String reason, String eventName, LocalDateTime nextReviewAt) { }

    private String normalizeIpSegment(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim().replace("*", "");
        return value.length() > 64 ? value.substring(0, 64) : value;
    }

    private boolean validReviewAt(String raw, int expectedDays, int toleranceDays) {
        LocalDateTime reviewAt = parseD2ReviewAt(raw);
        if (reviewAt == null || !reviewAt.isAfter(LocalDateTime.now())) {
            return false;
        }
        long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), reviewAt.toLocalDate());
        if (expectedDays == 365) {
            return days >= 1 && days <= 365;
        }
        return Math.abs(days - expectedDays) <= toleranceDays;
    }

    private LocalDateTime parseD2ReviewAt(String raw) {
        if (!StringUtils.hasText(raw)) {
            return null;
        }
        String value = raw.trim();
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            try {
                return LocalDate.parse(value).atStartOfDay();
            } catch (DateTimeParseException invalid) {
                return null;
            }
        }
    }

    private String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256_NOT_AVAILABLE", ex);
        }
    }

    private int withdrawalDailyLimitCount() {
        int count = configDecimal("withdrawal.daily_count_limit", BigDecimal.ZERO)
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
        if (withdrawGeoEmergencyBlocked() || withdrawGeoEndpointBlocked(order)) {
            return "WITHDRAWAL_GEO_BLOCKED";
        }
        if (order.holdUntil() != null && order.holdUntil().isAfter(LocalDateTime.now())) {
            return "WITHDRAWAL_COOLDOWN_ACTIVE";
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
        return ffdd.opsconsole.emergency.domain.KillSwitchState.enabled(
                controlValue(WITHDRAW_KILLSWITCH_KEY),
                controlValue(WITHDRAW_LEGACY_KILLSWITCH_KEY));
    }

    private boolean withdrawDisclosureGateActive() {
        return controlValue(WITHDRAW_DISCLOSURE_GATE_KEY)
                .map(this::parseDisclosureGateActive)
                .orElse(false);
    }

    private boolean withdrawGeoEmergencyBlocked() {
        return controlValue(WITHDRAW_GEO_EMERGENCY_KEY)
                .map(this::parseDisclosureGateActive)
                .orElse(false);
    }

    private boolean withdrawGeoEndpointBlocked(WithdrawalOrderView order) {
        if (order == null || order.userId() == null) {
            return false;
        }
        Set<String> blockedCountries = withdrawEndpointBlockedCountries();
        if (blockedCountries.isEmpty()) {
            return false;
        }
        return withdrawalRepository.findUserCountryCode(order.userId())
                .map(country -> blockedCountries.contains(country.trim().toUpperCase(Locale.ROOT)))
                .orElse(false);
    }

    private Set<String> withdrawEndpointBlockedCountries() {
        return emergencyRepository.geoEndpointPolicies().stream()
                .filter(row -> WITHDRAW_GEO_ENDPOINT_KEY.equalsIgnoreCase(String.valueOf(row.getOrDefault("endpointKey", "")).trim()))
                .map(row -> String.valueOf(row.getOrDefault("countryCode", "")).trim().toUpperCase(Locale.ROOT))
                .filter(StringUtils::hasText)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private Optional<String> controlValue(String key) {
        return emergencyRepository.settingValue(key);
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
            default -> false;
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
            KycReviewTriggerResult review,
            String authenticatedOperator) {
        Map<String, Object> detail = withdrawalReviewAuditDetail(
                order, "FROZEN", dailyLimitCount, request.reason().trim(), idempotencyKey.trim());
        detail.put("requestedAction", "APPROVE");
        detail.put("blockedReason", "WITHDRAWAL_K5_REVIEW_REQUIRED");
        detail.put("k5TicketId", review.ticketId());
        detail.put("k5Created", review.created());
        detail.put("k5Reason", review.reason());
        audit("D2_WITHDRAWAL_K5_REVIEW_REQUIRED", "WITHDRAWAL", order.withdrawalNo(), authenticatedOperator, "BLOCKED", detail);
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

    private Map<String, Object> withdrawalLimitsData() {
        Map<String, BigDecimal> owned = currentD5OwnedValues();
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        GrowthRhythmSnapshot rhythm = GrowthRhythmSnapshot.from(configFacade, readTimeSeedPolicy);
        if (!StringUtils.hasText(rhythm.currentPhase()) || rhythm.currentMonth() < 1) {
            throw new IllegalStateException("H1_PHASE_UNAVAILABLE");
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("version", requiredConfigDecimal("withdrawal.d5.version").longValueExact());
        response.put("dailyLimitCount", owned.get("dailyLimitCount").intValueExact());
        response.put("balanceMaxRatio", owned.get("balanceMaxRatio"));
        response.put("networkFeeRatio", owned.get("networkFeeRatio"));
        response.put("networkFeeMin", owned.get("networkFeeMin"));
        response.put("networkFeeMax", owned.get("networkFeeMax"));
        response.put("nexFeeOffsetRate", owned.get("nexFeeOffsetRate"));
        response.put("cooldownDays", requiredConfigDecimal("growth.phase.withdraw_cooldown_days").intValueExact());
        response.put("penaltyFeeRate", requiredConfigDecimal("growth.phase.withdraw_penalty_fee_rate"));
        response.put("complianceHoldEnabled", requiredConfigBoolean("growth.phase.compliance_hold_enabled"));
        response.put("currentPhase", rhythm.currentPhase());
        response.put("currentMonth", rhythm.currentMonth());
        response.put("coverageRatio", coverage.coverageRatio());
        response.put("redlinePct", coverage.redlinePct());
        response.put("coverageReliable", coverage.reliable());
        response.put("sourceByField", Map.ofEntries(
                Map.entry("dailyLimitCount", "d5"),
                Map.entry("balanceMaxRatio", "d5"),
                Map.entry("networkFeeRatio", "d5"),
                Map.entry("networkFeeMin", "d5"),
                Map.entry("networkFeeMax", "d5"),
                Map.entry("nexFeeOffsetRate", "d5"),
                Map.entry("cooldownDays", "phase-h1"),
                Map.entry("penaltyFeeRate", "phase-h1"),
                Map.entry("complianceHoldEnabled", "phase-h1")));
        return response;
    }

    private Map<String, BigDecimal> currentD5OwnedValues() {
        Map<String, BigDecimal> values = new LinkedHashMap<>();
        values.put("dailyLimitCount", requiredConfigDecimal("withdrawal.daily_count_limit"));
        values.put("balanceMaxRatio", requiredConfigDecimal("withdrawal.max_balance_pct"));
        values.put("networkFeeRatio", requiredConfigDecimal("withdrawal.fee_rate"));
        values.put("networkFeeMin", requiredConfigDecimal("withdrawal.fee_min_usdt"));
        values.put("networkFeeMax", requiredConfigDecimal("withdrawal.fee_max_usdt"));
        values.put("nexFeeOffsetRate", requiredConfigDecimal("withdrawal.nex_fee_offset_rate"));
        return values;
    }

    private ApiResult<Map<String, Object>> validateWithdrawalLimitsRequest(
            WithdrawalLimitsUpdateRequest request,
            Set<String> changedFields) {
        if (changedFields.contains("dailyLimitCount")
                && (request.getDailyLimitCount() == null
                || request.getDailyLimitCount() < 1 || request.getDailyLimitCount() > 10)) {
            return ApiResult.fail(400, "DAILY_LIMIT_COUNT_INVALID");
        }
        if (changedFields.contains("balanceMaxRatio")
                && !inRange(request.getBalanceMaxRatio(), new BigDecimal("0.50"), BigDecimal.ONE)) {
            return ApiResult.fail(400, "BALANCE_MAX_RATIO_INVALID");
        }
        if (changedFields.contains("networkFeeRatio")
                && !inRange(request.getNetworkFeeRatio(), BigDecimal.ZERO, new BigDecimal("0.05"))) {
            return ApiResult.fail(400, "NETWORK_FEE_RATIO_INVALID");
        }
        if (changedFields.contains("networkFeeMin")
                && !inRange(request.getNetworkFeeMin(), BigDecimal.ZERO, new BigDecimal("1000000"))) {
            return ApiResult.fail(400, "NETWORK_FEE_MIN_INVALID");
        }
        if (changedFields.contains("networkFeeMax")
                && !inRange(request.getNetworkFeeMax(), BigDecimal.ZERO, new BigDecimal("1000000"))) {
            return ApiResult.fail(400, "NETWORK_FEE_MAX_INVALID");
        }
        if (changedFields.contains("nexFeeOffsetRate")
                && !inRange(request.getNexFeeOffsetRate(), new BigDecimal("0.000001"),
                new BigDecimal("999999999999.999999"))) {
            return ApiResult.fail(400, "NEX_FEE_OFFSET_RATE_INVALID");
        }
        return null;
    }

    private boolean inRange(BigDecimal value, BigDecimal min, BigDecimal max) {
        return value != null && value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }

    private BigDecimal requestedWithdrawalLimitValue(String field, WithdrawalLimitsUpdateRequest request) {
        return switch (field) {
            case "dailyLimitCount" -> BigDecimal.valueOf(request.getDailyLimitCount());
            case "balanceMaxRatio" -> request.getBalanceMaxRatio();
            case "networkFeeRatio" -> request.getNetworkFeeRatio();
            case "networkFeeMin" -> request.getNetworkFeeMin();
            case "networkFeeMax" -> request.getNetworkFeeMax();
            case "nexFeeOffsetRate" -> request.getNexFeeOffsetRate();
            default -> throw new IllegalArgumentException("Unsupported D5 field");
        };
    }

    private boolean loosensWithdrawalLimit(String field, BigDecimal before, BigDecimal after) {
        return switch (field) {
            case "dailyLimitCount", "balanceMaxRatio", "nexFeeOffsetRate" -> after.compareTo(before) > 0;
            case "networkFeeRatio", "networkFeeMin", "networkFeeMax" -> after.compareTo(before) < 0;
            default -> false;
        };
    }

    private String canonicalD5ConfigKey(String field) {
        return switch (field) {
            case "dailyLimitCount" -> "withdrawal.daily_count_limit";
            case "balanceMaxRatio" -> "withdrawal.max_balance_pct";
            case "networkFeeRatio" -> "withdrawal.fee_rate";
            case "networkFeeMin" -> "withdrawal.fee_min_usdt";
            case "networkFeeMax" -> "withdrawal.fee_max_usdt";
            case "nexFeeOffsetRate" -> "withdrawal.nex_fee_offset_rate";
            default -> throw new IllegalArgumentException("Unsupported D5 field");
        };
    }

    private String walletD5ConfigKey(String field) {
        return switch (field) {
            case "dailyLimitCount" -> "wallet.withdrawal.daily_count_limit";
            case "balanceMaxRatio" -> "wallet.withdrawal.max_balance_pct";
            case "networkFeeRatio" -> "wallet.withdrawal.fee_rate";
            case "networkFeeMin" -> "wallet.withdrawal.fee_min_usdt";
            case "networkFeeMax" -> "wallet.withdrawal.fee_max_usdt";
            case "nexFeeOffsetRate" -> "wallet.withdrawal.nex_fee_offset_rate";
            default -> throw new IllegalArgumentException("Unsupported D5 field");
        };
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String normalizeParamKey(String key) {
        String normalized = key == null ? "" : key.trim();
        return switch (normalized) {
            case "dailyLimitCount", "balanceMaxRatio", "networkFee", "nexFeeOffsetRate" -> normalized;
            default -> throw new IllegalArgumentException("Unsupported withdrawal parameter");
        };
    }

    private String configKey(String key) {
        return switch (key) {
            case "dailyLimitCount" -> "withdrawal.daily_count_limit";
            case "balanceMaxRatio" -> "withdrawal.max_balance_pct";
            case "networkFee" -> "withdrawal.fee_rate";
            case "nexFeeOffsetRate" -> "withdrawal.nex_fee_offset_rate";
            default -> throw new IllegalArgumentException("Unsupported withdrawal parameter");
        };
    }

    private void mirrorWalletWithdrawalConfig(String key, BigDecimal value) {
        String walletKey = switch (key) {
            case "dailyLimitCount" -> "wallet.withdrawal.daily_count_limit";
            case "balanceMaxRatio" -> "wallet.withdrawal.max_balance_pct";
            case "networkFee" -> "wallet.withdrawal.fee_rate";
            case "nexFeeOffsetRate" -> "wallet.withdrawal.nex_fee_offset_rate";
            default -> throw new IllegalArgumentException("Unsupported withdrawal parameter");
        };
        configFacade.upsertAdminValue(walletKey, value.toPlainString(), "NUMBER", "wallet", "D5 withdrawal parameter mirror");
    }

    private BigDecimal currentParamValue(String key) {
        return switch (key) {
            case "dailyLimitCount" -> configDecimal("withdrawal.daily_count_limit", BigDecimal.ONE);
            case "balanceMaxRatio" -> configDecimal("withdrawal.max_balance_pct", new BigDecimal("0.80"));
            case "networkFee" -> configDecimal("withdrawal.fee_rate", new BigDecimal("0.02"));
            case "nexFeeOffsetRate" -> configDecimal("withdrawal.nex_fee_offset_rate", new BigDecimal("0.40"));
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
        if ("nexFeeOffsetRate".equals(key)) {
            BigDecimal normalized = value.setScale(6, RoundingMode.HALF_UP);
            if (normalized.compareTo(BigDecimal.ZERO) <= 0
                    || normalized.compareTo(new BigDecimal("999999999999.999999")) > 0) {
                throw new IllegalArgumentException("NEX fee offset rate must be positive and fit DECIMAL(18,6)");
            }
            return normalized.stripTrailingZeros();
        }
        throw new IllegalArgumentException("Unsupported withdrawal parameter");
    }

    private boolean loosensWithdrawalControl(String key, BigDecimal oldValue, BigDecimal newValue) {
        return switch (key) {
            case "dailyLimitCount", "balanceMaxRatio" -> newValue.compareTo(oldValue) > 0;
            case "networkFee" -> newValue.compareTo(oldValue) < 0;
            case "nexFeeOffsetRate" -> newValue.compareTo(oldValue) > 0;
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
                        return fallback;
                    }
                })
                .orElse(fallback);
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

    private boolean requiredConfigBoolean(String key) {
        String value = configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .orElseThrow(() -> new IllegalStateException("CONFIG_NOT_FOUND:" + key));
        if ("true".equalsIgnoreCase(value) || "1".equals(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value) || "0".equals(value)) {
            return false;
        }
        throw new IllegalStateException("INVALID_CONFIG:" + key);
    }

    private boolean optionalConfigBoolean(String key) {
        return configFacade.activeValue(key)
                .filter(StringUtils::hasText)
                .map(value -> "true".equalsIgnoreCase(value) || "1".equals(value))
                .orElse(false);
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

    private void auditRequired(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
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

    @Override
    public String domain() {
        return "D";
    }

    @Override
    public ApiResult<?> replay(AuditReplayCommand cmd, AuditReplayContext ctx) {
        Map<String, Object> p = cmd.params() == null ? Map.of() : cmd.params();
        String withdrawalNo = String.valueOf(p.get("withdrawalNo"));
        String action;
        switch (cmd.op()) {
            case "d2_withdraw_approve" -> action = "APPROVE";
            case "d2_withdraw_unfreeze" -> action = "UNFREEZE";
            default -> {
                return ApiResult.fail(422, "UNKNOWN_REPLAY_OP:" + cmd.op());
            }
        }
        WithdrawalReviewRequest req = new WithdrawalReviewRequest(action, ctx.operator(), ctx.reason());
        return reviewWithdrawal(withdrawalNo, ctx.idempotencyKey(), req);
    }
}

package ffdd.opsconsole.risk.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.SuperAdminAuthorization;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalKycReviewFacade;
import ffdd.opsconsole.market.facade.MarketExchangeKycReviewFacade;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.domain.KycReviewTicketContext;
import ffdd.opsconsole.risk.domain.RiskArbitrageParamView;
import ffdd.opsconsole.risk.domain.RiskArbitrageRowView;
import ffdd.opsconsole.risk.domain.RiskArbitrageStatView;
import ffdd.opsconsole.risk.domain.RiskArbitrageViewGroup;
import ffdd.opsconsole.risk.domain.RiskCaseView;
import ffdd.opsconsole.risk.domain.RiskOpsRepository;
import ffdd.opsconsole.risk.domain.RiskRouteCountView;
import ffdd.opsconsole.risk.domain.RiskRuleDimensionView;
import ffdd.opsconsole.risk.domain.RiskRuleHitView;
import ffdd.opsconsole.risk.domain.RiskRuleView;
import ffdd.opsconsole.risk.domain.RiskScoreConfigView;
import ffdd.opsconsole.risk.domain.RiskScoreDimensionView;
import ffdd.opsconsole.risk.domain.RiskScoreDistributionView;
import ffdd.opsconsole.risk.domain.RiskScoreOverrideView;
import ffdd.opsconsole.risk.domain.RiskScoreModelView;
import ffdd.opsconsole.risk.domain.RiskScoreRawInput;
import ffdd.opsconsole.risk.domain.RiskScoreUserSearchView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.risk.domain.RiskScoringSourceCatalog;
import ffdd.opsconsole.risk.domain.RiskWithdrawCandidateView;
import ffdd.opsconsole.risk.dto.RiskArbitrageActionRequest;
import ffdd.opsconsole.risk.dto.RiskArbitrageParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.dto.RiskClusterStatusRequest;
import ffdd.opsconsole.risk.dto.RiskClusterReviewRequest;
import ffdd.opsconsole.risk.dto.RiskDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskIpWhitelistRequest;
import ffdd.opsconsole.risk.dto.RiskKycManualReviewRequest;
import ffdd.opsconsole.risk.dto.RiskKycAlertSubscriptionRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskRuleConditionRequest;
import ffdd.opsconsole.risk.dto.RiskRuleCreateRequest;
import ffdd.opsconsole.risk.dto.RiskRuleDryRunRequest;
import ffdd.opsconsole.risk.dto.RiskRuleHitQueryRequest;
import ffdd.opsconsole.risk.dto.RiskRuleOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskRuleStatusRequest;
import ffdd.opsconsole.risk.dto.RiskScoreCommandRequest;
import ffdd.opsconsole.risk.dto.RiskScoreBatchCommandRequest;
import ffdd.opsconsole.risk.dto.RiskScoreOverrideRequest;
import ffdd.opsconsole.risk.dto.RiskScoringModelDraftRequest;
import ffdd.opsconsole.risk.dto.RiskScoringModelPublishRequest;
import ffdd.opsconsole.risk.dto.RiskScoringModelRestoreRequest;
import ffdd.opsconsole.risk.dto.RiskScoringOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskScoringBandRequest;
import ffdd.opsconsole.risk.dto.RiskScoringEscalateRequest;
import ffdd.opsconsole.risk.dto.RiskScoringSourceRequest;
import ffdd.opsconsole.risk.dto.RiskScoringWeightsRequest;
import ffdd.opsconsole.risk.dto.RiskSignalRequest;
import ffdd.opsconsole.user.facade.UserKycStatusFacade;
import ffdd.opsconsole.user.facade.UserAccountControlFacade;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContextHolder;

@ApplicationService
@RequiredArgsConstructor
public class OpsRiskService implements ffdd.opsconsole.platform.domain.AuditReplayable {
    private static final Set<String> FINAL_DECISIONS = Set.of("ALLOW", "BLOCK", "REJECT", "DENY");
    private static final Set<String> MANUAL_DECISIONS = Set.of("ALLOW", "BLOCK", "HOLD");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> RULE_ACTIONS = Set.of("pass", "delay", "freeze", "manual");
    private static final Set<String> K3_CONFIGURABLE_ACTIONS = Set.of("delay", "freeze", "manual");
    private static final Set<String> RULE_STATES = Set.of("draft", "active", "paused", "archived");
    private static final Map<String, Set<String>> K3_RULE_TRANSITIONS = Map.of(
            "draft", Set.of("active"),
            "active", Set.of("paused"),
            "paused", Set.of("active", "archived"),
            "archived", Set.of());
    private static final Map<String, Integer> K3_ACTION_SEVERITY = Map.of(
            "pass", 0, "delay", 1, "manual", 2, "freeze", 3);
    private static final Set<String> CLUSTER_STATES = Set.of("detected", "flagged", "frozen", "released", "cleared");
    private static final Set<String> CLUSTER_LAYERS = Set.of("ip", "device", "payment");
    private static final Set<String> K1_CLUSTER_SORTS = Set.of("strength_desc", "account_desc");
    private static final Map<String, int[]> K1_INTEGER_PARAMS = Map.of(
            "maxSignupPerIp24h", new int[]{1, 10},
            "maxAccountsPerDevice", new int[]{1, 5},
            "maxAccountsPerPaymentInstrument", new int[]{1, 5});
    private static final Set<String> K1_DECIMAL_PARAMS = Set.of("clusterFreezeSuggestThreshold");
    private static final Map<String, Set<String>> K1_CLUSTER_TRANSITIONS = Map.of(
            "detected", Set.of("flagged", "cleared"),
            "flagged", Set.of("frozen", "released"),
            "frozen", Set.of("released"),
            "released", Set.of(),
            "cleared", Set.of());
    private static final Map<String, String> K1_STATUS_AUTHORITIES = Map.of(
            "flagged", "risk_k1_cluster_flag",
            "frozen", "risk_k1_cluster_freeze",
            "released", "risk_k1_cluster_release",
            "cleared", "risk_k1_cluster_cleared");
    private static final Set<String> KYC_REVIEW_DECISIONS = Set.of("passed", "rejected");
    private static final Set<String> KYC_REJECT_REASON_CODES = Set.of(
            "KYC_MATERIAL_INVALID", "IDENTITY_MISMATCH", "SANCTIONS_LIST_MATCH", "OTHER");
    private static final List<String> K4_DIMENSION_KEYS = List.of(
            "multiAccount", "arbitrage", "kycStatus",
            "withdrawVelocity", "accountAge", "anomalyBehavior");
    private static final Pattern K2_TRIAL_THRESHOLD = Pattern.compile("^(>=|>)\\s*(\\d+)\\s*次\\s*/\\s*(\\d+)\\s*天$");
    private static final Pattern K2_WELCOME_GIFT_THRESHOLD = Pattern.compile("^(>=|>)\\s*(\\d+)\\s*笔\\s*/\\s*(实体|账户簇|手机号|设备)$");
    private static final Pattern K2_LEADERBOARD_THRESHOLD = Pattern.compile("^(>=|>)\\s*(\\d+)\\s*x\\s*(基线|上周期|7日均值|同层级均值)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern K3_AMOUNT_RULE = Pattern.compile("^单笔\\s*(>=|>)\\s*\\$?([\\d,]+)$");
    private static final Pattern K3_VELOCITY_RULE = Pattern.compile("^24h\\s*(>=|>)\\s*(\\d+)\\s*笔\\s*或\\s*(>=|>)\\s*\\$?([\\d,]+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern K3_NEW_ACCOUNT_RULE = Pattern.compile("^注册\\s*(<|<=)\\s*(\\d+)\\s*天$");
    private static final Pattern K5_AMOUNT_LINE = Pattern.compile("^(>=|>)\\s*\\$?([\\d,]+)$");
    private static final Pattern K5_CUMULATIVE_LINE = Pattern.compile("^\\$?([\\d,]+)$");
    private static final Pattern K5_SLA_DAYS = Pattern.compile("^(\\d+)$");
    private static final Pattern K5_SCORE_LINE = Pattern.compile("^(>=|>)\\s*(\\d+)$");
    private static final Set<String> K5_TICKET_FILTERS = Set.of(
            "all", "大额提现", "大额兑换", "累计过线", "手动触发", "风险分触发", "overdue");
    private static final Map<String, String> OTP_CANONICAL_CONFIG_KEYS = Map.of(
            "otpGate.resendSeconds", "auth.risk.otp_cooldown_seconds",
            "otpGate.captchaAfterSends", "auth.risk.otp_max_24h",
            "otpGate.otpTtlSeconds", "auth.risk.otp_ttl_minutes",
            "otpGate.maxVerifyAttempts", "auth.risk.otp_max_verify_attempts",
            "otpGate.captchaTicketTtlSeconds", "auth.risk.captcha_ticket_ttl_seconds");
    private static final Set<String> RETIRED_K2_PARAM_KEYS = Set.of(
            "rewardRisk.lockMode", "rewardRisk.usdtAmount", "rewardRisk.nexAmount");
    private static final Map<String, K2ActionSpec> K2_ACTIONS = Map.of(
            "mark", new K2ActionSpec("flag", "已标记套利", "K2_ARBITRAGE_MARKED", "risk_k2_row_flag"),
            "block-gift", new K2ActionSpec("blockgift", "新人礼已拦截", "K2_WELCOME_GIFT_BLOCKED", "risk_k2_row_blockgift"),
            "board-flag", new K2ActionSpec("boardflag", "已标记刷榜", "K2_LEADERBOARD_FLAGGED", "risk_k2_row_boardflag"),
            "freeze-cluster", new K2ActionSpec("freeze", "已联动 K1 冻结", "K2_CLUSTER_FREEZE_LINKED", "risk_k2_row_freeze"));
    private static final List<K2ViewSpec> K2_VIEWS = List.of(
            new K2ViewSpec("trial", "试用循环", "· 试用循环养号 · 服务器端复活计数,不信客户端状态",
                    List.of("实体 / 簇", "30 天循环次数", "关联账户", "累计套取试用收益"),
                    "循环次数是服务器记的开试用、取消、再开的轮数。层数命中 = 多账户 / 绑上级 / 试用循环。"),
            new K2ViewSpec("tradein", "换新套利", "· 高频下架置换 · 礼金/返佣叠加行为证据后再判定闭环",
                    List.of("账户 / 实体", "设备链", "观察窗口", "高频下架置换", "礼金/返佣叠加"),
                    "置换抵扣阶梯归 E3；K2 只根据高频下架置换与礼金/返佣叠加证据判定异常，不使用持有月或旧残值闸门。"),
            new K2ViewSpec("gift", "新人礼刷取", "· 新人礼刷取 · 看领完礼就走的闭环行为",
                    List.of("簇", "实体", "已发 / 已拦", "涉及金额", "闭环特征"),
                    "K1 看同一实体重复领；K2 加看行为闭环。拦截只停发后续,不动已入账资产。"),
            new K2ViewSpec("board", "排行榜刷榜", "· 排行榜刷榜 · 邀请/佣金增速异常的冲榜账户",
                    List.of("账户", "本期累计佣金", "增速(对基线)", "直推增长", "关联簇"),
                    "这里只标记并发信号给 F8 和风险雷达；取消参榜资格、从奖池剔除由 F8 执行。"));
    private static final Map<String, DimensionMeta> K3_DIMENSION_META = Map.ofEntries(
            Map.entry("largeAmountUsdt", new DimensionMeta("largeAmountUsdt", "金额", "由 nx_admin_risk_withdraw_rule 配置", "card", 0)),
            Map.entry("amount", new DimensionMeta("largeAmountUsdt", "金额", "由 nx_admin_risk_withdraw_rule 配置", "card", 0)),
            Map.entry("金额", new DimensionMeta("largeAmountUsdt", "金额", "由 nx_admin_risk_withdraw_rule 配置", "card", 0)),
            Map.entry("velocity24h", new DimensionMeta("velocity24h", "速度", "由 nx_admin_risk_withdraw_rule 配置", "wave", 1)),
            Map.entry("velocity", new DimensionMeta("velocity24h", "速度", "由 nx_admin_risk_withdraw_rule 配置", "wave", 1)),
            Map.entry("速度", new DimensionMeta("velocity24h", "速度", "由 nx_admin_risk_withdraw_rule 配置", "wave", 1)),
            Map.entry("newAccountProtectDays", new DimensionMeta("newAccountProtectDays", "新账户", "由 nx_admin_risk_withdraw_rule 配置", "user", 2)),
            Map.entry("accountAge", new DimensionMeta("newAccountProtectDays", "新账户", "由 nx_admin_risk_withdraw_rule 配置", "user", 2)),
            Map.entry("新账户", new DimensionMeta("newAccountProtectDays", "新账户", "由 nx_admin_risk_withdraw_rule 配置", "user", 2)),
            Map.entry("addressReputationSource", new DimensionMeta("addressReputationSource", "地址信誉", "由 nx_admin_risk_withdraw_rule 配置", "shield", 3)),
            Map.entry("addressReputation", new DimensionMeta("addressReputationSource", "地址信誉", "由 nx_admin_risk_withdraw_rule 配置", "shield", 3)),
            Map.entry("地址信誉", new DimensionMeta("addressReputationSource", "地址信誉", "由 nx_admin_risk_withdraw_rule 配置", "shield", 3)));

    private final RiskOpsRepository riskRepository;
    private final UserKycStatusFacade userKycStatusFacade;
    private final FinanceWithdrawalKycReviewFacade financeWithdrawalKycReviewFacade;
    private final MarketExchangeKycReviewFacade marketExchangeKycReviewFacade;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper;
    private final AdminIdempotencyService idempotencyService;
    private final SuperAdminAuthorization superAdminAuthorization;
    private final UserAccountControlFacade userAccountControlFacade;
    private final EventOutboxService eventOutboxService;
    private final ChainAddressReputationGateway chainAddressReputationGateway;
    /** Single K4-to-K5 closure used by every score mutation path. */
    private final K4KycReviewTriggerService k4KycReviewTriggerService;
    private final K4RiskScorer k4RiskScorer = new K4RiskScorer();
    private final K3WithdrawalRuleEvaluator k3WithdrawalRuleEvaluator = new K3WithdrawalRuleEvaluator();

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>(riskRepository.overview());
        response.put("domain", "K");
        response.put("capabilities", List.of("RiskCase", "FraudSignal", "DeviceFingerprint", "DecisionEvidence", "WithdrawRule", "ArbitrageSignal", "RiskScoringModel"));
        response.put("decisionStates", List.of("REVIEWING", "FINALIZED"));
        response.put("redlines", List.of("finalized cases cannot be re-decided", "manual decisions require reason and A2 audit"));
        response.put("sources", List.of("nx_risk_decision", "nx_risk_signal", "nx_admin_risk_withdraw_rule", "nx_admin_risk_score_dimension"));
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<RiskCaseView>> cases(RiskCaseQueryRequest request) {
        return ApiResult.ok(riskRepository.pageCases(request));
    }

    public ApiResult<RiskCaseView> detail(String caseNo) {
        if (!StringUtils.hasText(caseNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RISK_CASE_NO_REQUIRED");
        }
        return riskRepository.findByCaseNo(caseNo.trim())
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "RISK_CASE_NOT_FOUND"));
    }

    public ApiResult<RiskCaseView> decide(String caseNo, String idempotencyKey, RiskDecisionRequest request) {
        ApiResult<RiskCaseView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (!StringUtils.hasText(caseNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RISK_CASE_NO_REQUIRED");
        }
        RiskCaseView before = riskRepository.findByCaseNo(caseNo.trim()).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "RISK_CASE_NOT_FOUND");
        }
        if (isFinal(before)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        String decision = normalizeDecision(request.decision());
        riskRepository.updateDecision(caseNo.trim(), decision, request.reason().trim(), operator(request.operator()));
        RiskCaseView updated = riskRepository.findByCaseNo(caseNo.trim())
                .orElse(null);
        if (updated == null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "RISK_CASE_RELOAD_FAILED");
        }
        audit("K_RISK_CASE_DECIDED", "RISK_CASE", before.caseNo(), before.userId(), operator(request.operator()), Map.of(
                "fromDecision", before.decision(),
                "toDecision", decision,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> recordSignal(String idempotencyKey, RiskSignalRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.userId() == null || request.userId() <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        String signalType = requireText(request.signalType(), "SIGNAL_TYPE_REQUIRED").toUpperCase(Locale.ROOT);
        String severity = normalizeSeverity(request.severity());
        String signalNo = "SIG-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        String evidence = sanitizeEvidence(request.evidence());
        riskRepository.recordSignal(signalNo, request.userId(), signalType, severity, evidence, operator(request.operator()));
        RiskCaseView manualCase = null;
        if (isKycReviewSignal(signalType)) {
            String caseNo = nextRiskCaseNo("KYC");
            manualCase = riskRepository.createManualReviewCase(
                    caseNo,
                    request.userId(),
                    "KYC_REVIEW",
                    "USER-" + request.userId(),
                    request.reason().trim(),
                    riskScore(severity),
                    signalType,
                    evidence,
                    operator(request.operator()));
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("signalNo", signalNo);
        response.put("userId", request.userId());
        response.put("signalType", signalType);
        response.put("severity", severity);
        response.put("status", manualCase == null ? "RECORDED" : "REVIEW_CREATED");
        if (manualCase != null) {
            response.put("caseNo", manualCase.caseNo());
            response.put("riskScore", manualCase.riskScore());
        }
        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("signalType", signalType);
        auditDetail.put("severity", severity);
        auditDetail.put("reason", request.reason().trim());
        auditDetail.put("idempotencyKey", idempotencyKey.trim());
        if (manualCase != null) {
            auditDetail.put("manualReviewCaseNo", manualCase.caseNo());
        }
        audit("K_RISK_SIGNAL_RECORDED", "RISK_SIGNAL", signalNo, request.userId(), operator(request.operator()), auditDetail);
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> multiAccountOverview(Integer clusterPageNum, Integer clusterPageSize, String clusterLayer,
                                                               Integer whitelistPageNum, Integer whitelistPageSize) {
        return multiAccountOverview(clusterPageNum, clusterPageSize, clusterLayer, null, "strength_desc",
                whitelistPageNum, whitelistPageSize);
    }

    public ApiResult<Map<String, Object>> multiAccountOverview(
            Integer clusterPageNum, Integer clusterPageSize, String clusterLayer,
            String clusterStatus, String clusterSort,
            Integer whitelistPageNum, Integer whitelistPageSize) {
        String normalizedStatus = trimmed(clusterStatus).toLowerCase(Locale.ROOT);
        String normalizedSort = trimmed(clusterSort).toLowerCase(Locale.ROOT);
        String normalizedLayer = normalizeClusterLayer(clusterLayer);
        if (StringUtils.hasText(clusterLayer) && normalizedLayer == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_CLUSTER_LAYER_FILTER_INVALID");
        }
        if (StringUtils.hasText(clusterStatus) && !CLUSTER_STATES.contains(normalizedStatus)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_CLUSTER_STATUS_FILTER_INVALID");
        }
        if (!StringUtils.hasText(normalizedSort)) normalizedSort = "strength_desc";
        if (!K1_CLUSTER_SORTS.contains(normalizedSort)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_CLUSTER_SORT_INVALID");
        }
        return ApiResult.ok(riskRepository.multiAccountOverview(
                normalizePageNum(clusterPageNum),
                normalizeLimit(clusterPageSize, 5, 50),
                normalizedLayer,
                StringUtils.hasText(normalizedStatus) ? normalizedStatus : null,
                normalizedSort,
                normalizePageNum(whitelistPageNum),
                normalizeLimit(whitelistPageSize, 5, 50)));
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateMultiAccountParam(String key, String idempotencyKey, RiskParamUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireK1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey = trimmed(key);
        String value = normalizeK1Param(normalizedKey, request.value());
        if (!StringUtils.hasText(value)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_PARAM_VALUE_INVALID");
        }
        String actor = authenticatedOperator();
        String normalizedValue = value;
        return idempotentK1("K1_PARAM:" + normalizedKey, idempotencyKey,
                requestHash(normalizedKey, normalizedValue, request.reason()), () -> {
                    String beforeValue = riskRepository.multiAccountParamValue(normalizedKey)
                            .orElseThrow(() -> new IllegalStateException("K1_PARAM_NOT_FOUND"));
                    Map<String, Object> updated = riskRepository.updateMultiAccountParam(normalizedKey, normalizedValue);
                    String afterValue = riskRepository.multiAccountParamValue(normalizedKey)
                            .orElseThrow(() -> new IllegalStateException("K1_PARAM_WRITE_NOT_VISIBLE"));
                    auditRequired("K1_MULTI_ACCOUNT_PARAM_CHANGED", "RISK_MULTI_ACCOUNT_PARAM", normalizedKey, actor, Map.of(
                            "key", normalizedKey,
                            "value", normalizedValue,
                            "before", k1ParamSnapshot(normalizedKey, beforeValue),
                            "after", k1ParamSnapshot(normalizedKey, afterValue),
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
                    return ApiResult.ok(updated);
                });
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateMultiAccountClusterStatus(String clusterId, String idempotencyKey, RiskClusterStatusRequest request) {
        ApiResult<Map<String, Object>> approvalGuard = delegatedDirectExecutionGuard(
                "k1_cluster_status", "RISK_MULTI_ACCOUNT_CLUSTER", clusterId);
        if (approvalGuard != null) {
            return approvalGuard;
        }
        ApiResult<Map<String, Object>> guard = requireK1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedCluster = trimmed(clusterId);
        String status = trimmed(request.status()).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(normalizedCluster) || !CLUSTER_STATES.contains(status)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_CLUSTER_STATUS_INVALID");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_CLUSTER_EXPECTED_VERSION_REQUIRED");
        }
        ApiResult<Map<String, Object>> permissionGuard = requireK1StatusAuthority(status);
        if (permissionGuard != null) {
            return permissionGuard;
        }
        String actor = authenticatedOperator();
        return idempotentK1("K1_CLUSTER_STATUS:" + normalizedCluster, idempotencyKey,
                requestHash(normalizedCluster, status, String.valueOf(request.expectedVersion()), request.reason()), () -> {
                    if (!A2ReplayContext.isReplaying()
                            && lockMapper.countActiveByTarget("K", "cluster", normalizedCluster) > 0) {
                        return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
                    }
                    RiskOpsRepository.MultiAccountClusterState before = riskRepository.multiAccountClusterState(normalizedCluster).orElse(null);
                    if (before == null) {
                        return ApiResult.fail(404, "K1_CLUSTER_NOT_FOUND");
                    }
                    if (before.version() != request.expectedVersion()) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "K1_CLUSTER_CONCURRENT_UPDATE");
                    }
                    if (!K1_CLUSTER_TRANSITIONS.getOrDefault(before.status(), Set.of()).contains(status)) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
                    }
                    if (!riskRepository.updateMultiAccountClusterStatus(
                            normalizedCluster, before.status(), request.expectedVersion(), status, request.reason().trim(), actor)) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "K1_CLUSTER_CONCURRENT_UPDATE");
                    }
                    int accountsFrozen = "frozen".equals(status)
                            ? userAccountControlFacade.freezeActiveUsersByUserNos(
                                    before.affectedUserIds(), request.reason().trim(), actor, normalizedCluster)
                            : 0;
                    int accountsRestored = "released".equals(status)
                            ? userAccountControlFacade.restoreUsersFrozenBySource(
                                    before.affectedUserIds(), request.reason().trim(), actor, normalizedCluster)
                            : 0;
                    auditRequired("K1_CLUSTER_STATUS_CHANGED", "RISK_MULTI_ACCOUNT_CLUSTER", normalizedCluster, actor, Map.of(
                            "clusterId", normalizedCluster,
                            "fromStatus", before.status(),
                            "status", status,
                            "accountsFrozen", accountsFrozen,
                            "accountsRestored", accountsRestored,
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
                    return multiAccountOverview(1, 5, null, 1, 5);
                });
    }

    @Transactional
    public ApiResult<Map<String, Object>> upsertIpWhitelist(String idempotencyKey, RiskIpWhitelistRequest request) {
        ApiResult<Map<String, Object>> guard = requireK1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String cidr = normalizeIpv4Cidr(request.cidr());
        String note = trimmed(request.note());
        String expireText = trimmed(request.expireText());
        if (!StringUtils.hasText(cidr) || note.length() < 2 || note.length() > 200 || !futureDate(expireText)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_WHITELIST_INVALID");
        }
        String actor = authenticatedOperator();
        return idempotentK1("K1_WHITELIST_UPSERT:" + cidr, idempotencyKey,
                requestHash(cidr, note, expireText, request.reason()), () -> {
                    RiskOpsRepository.IpWhitelistState before = riskRepository.ipWhitelistState(cidr).orElse(null);
                    riskRepository.upsertIpWhitelist(cidr, note, actor, expireText);
                    RiskOpsRepository.IpWhitelistState after = riskRepository.ipWhitelistState(cidr)
                            .orElseThrow(() -> new IllegalStateException("K1_WHITELIST_WRITE_NOT_VISIBLE"));
                    auditRequired("K1_IP_WHITELIST_UPSERTED", "RISK_IP_WHITELIST", cidr, actor, Map.of(
                            "cidr", cidr,
                            "note", note,
                            "expireText", expireText,
                            "before", k1WhitelistSnapshot(cidr, before),
                            "after", k1WhitelistSnapshot(cidr, after),
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
                    return multiAccountOverview(1, 5, null, 1, 5);
                });
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateMultiAccountClusterReviewNote(
            String clusterId, String idempotencyKey, RiskClusterReviewRequest request) {
        ApiResult<Map<String, Object>> guard = requireK1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) return guard;
        String normalizedCluster = trimmed(clusterId);
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_CLUSTER_EXPECTED_VERSION_REQUIRED");
        }
        ApiResult<Map<String, Object>> authorityGuard = requireK1Authority("risk_k1_write");
        if (authorityGuard != null) return authorityGuard;
        String actor = authenticatedOperator();
        return idempotentK1("K1_CLUSTER_REVIEW:" + normalizedCluster, idempotencyKey,
                requestHash(normalizedCluster, String.valueOf(request.expectedVersion()), request.reason()), () -> {
                    RiskOpsRepository.MultiAccountClusterState before = riskRepository.multiAccountClusterState(normalizedCluster).orElse(null);
                    if (before == null) return ApiResult.fail(404, "K1_CLUSTER_NOT_FOUND");
                    if (before.version() != request.expectedVersion()) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "K1_CLUSTER_CONCURRENT_UPDATE");
                    }
                    if (!riskRepository.updateMultiAccountClusterReviewNote(
                            normalizedCluster, request.expectedVersion(), request.reason().trim(), actor)) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "K1_CLUSTER_CONCURRENT_UPDATE");
                    }
                    auditRequired("K1_CLUSTER_REVIEW_NOTED", "RISK_MULTI_ACCOUNT_CLUSTER", normalizedCluster, actor, Map.of(
                            "clusterId", normalizedCluster, "status", before.status(),
                            "reason", request.reason().trim(), "idempotencyKey", idempotencyKey.trim()));
                    return multiAccountOverview(1, 5, null, 1, 5);
                });
    }

    @Transactional
    public ApiResult<Map<String, Object>> disableIpWhitelist(String idempotencyKey, RiskIpWhitelistRequest request) {
        ApiResult<Map<String, Object>> guard = requireK1Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String cidr = normalizeIpv4Cidr(request.cidr());
        if (!StringUtils.hasText(cidr)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_WHITELIST_CIDR_REQUIRED");
        }
        String actor = authenticatedOperator();
        return idempotentK1("K1_WHITELIST_DISABLE:" + cidr, idempotencyKey,
                requestHash(cidr, request.reason()), () -> {
                    RiskOpsRepository.IpWhitelistState before = riskRepository.ipWhitelistState(cidr).orElse(null);
                    if (!riskRepository.disableIpWhitelist(cidr, actor)) {
                        return ApiResult.fail(404, "K1_WHITELIST_NOT_FOUND");
                    }
                    RiskOpsRepository.IpWhitelistState after = riskRepository.ipWhitelistState(cidr)
                            .orElseThrow(() -> new IllegalStateException("K1_WHITELIST_DISABLE_NOT_VISIBLE"));
                    auditRequired("K1_IP_WHITELIST_DISABLED", "RISK_IP_WHITELIST", cidr, actor, Map.of(
                            "cidr", cidr,
                            "before", k1WhitelistSnapshot(cidr, before),
                            "after", k1WhitelistSnapshot(cidr, after),
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
                    return multiAccountOverview(1, 5, null, 1, 5);
                });
    }

    public ApiResult<Map<String, Object>> withdrawRuleOverview() {
        return withdrawRuleOverview(null);
    }

    public ApiResult<Map<String, Object>> withdrawRuleOverview(RiskRuleOverviewQueryRequest request) {
        String hitAction = request == null ? null : request.hitAction();
        if (!validRuleActionFilter(hitAction)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_ACTION_INVALID");
        }
        int rulePageNum = normalizePageNum(request == null ? null : request.rulePageNum());
        int rulePageSize = normalizeLimit(request == null ? null : request.rulePageSize(), 5, 50);
        int hitPageNum = normalizePageNum(request == null ? null : request.hitPageNum());
        int hitPageSize = normalizeLimit(request == null ? null : request.hitPageSize(), 10, 100);
        List<RiskRuleView> allRules = riskRepository.withdrawRules();
        PageResult<RiskRuleView> rules = riskRepository.pageWithdrawRules(rulePageNum, rulePageSize);
        List<RiskRouteCountView> routeCounts = riskRepository.withdrawRouteCounts();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dimensions", dimensions(allRules.stream()
                .filter(rule -> "active".equalsIgnoreCase(rule.state()))
                .toList()));
        response.put("rules", rules);
        response.put("routeCounts", routeCounts);
        response.put("routeTotal", routeCounts.stream().mapToLong(r -> r.count() == null ? 0L : r.count()).sum());
        response.put("hits", riskRepository.pageWithdrawRuleHits(hitAction, hitPageNum, hitPageSize));
        response.put("stateMachine", List.of("draft", "active", "paused", "archived"));
        response.put("redlines", List.of("archived rules are terminal", "rule changes require reason and A2 audit", "rule relaxation triggers B1 coverage precheck"));
        return ApiResult.ok(response);
    }

    public ApiResult<PageResult<RiskRuleHitView>> withdrawRuleHits(RiskRuleHitQueryRequest request) {
        String action = request == null ? null : request.action();
        if (!validRuleActionFilter(action)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_ACTION_INVALID");
        }
        int pageNum = normalizePageNum(request == null ? null : request.pageNum());
        int pageSize = normalizeLimit(request == null ? null : request.pageSize(), normalizeLimit(request == null ? null : request.limit(), 20, 200), 200);
        return ApiResult.ok(riskRepository.pageWithdrawRuleHits(action, pageNum, pageSize));
    }

    @Transactional
    public ApiResult<RiskRuleView> createWithdrawRule(String idempotencyKey, RiskRuleCreateRequest request) {
        ApiResult<RiskRuleView> guard = requireK3Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_REQUIRED_FIELDS_MISSING");
        }
        String dimension = trimmed(request.dimension());
        String condition = trimmed(request.conditionText());
        if (!StringUtils.hasText(dimension) || !StringUtils.hasText(condition)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_REQUIRED_FIELDS_MISSING");
        }
        if (condition.length() > 1000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_CONDITION_TOO_LONG");
        }
        condition = normalizeWithdrawRuleCondition(dimension, condition);
        if (!StringUtils.hasText(condition)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_CONDITION_INVALID");
        }
        String action = normalizeK3ConfigurableAction(request.action());
        if (action == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_ACTION_INVALID");
        }
        Integer priority = normalizeK3Priority(request.priority());
        if (priority == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K3_RULE_PRIORITY_INVALID");
        }
        ApiResult<RiskRuleView> permissionGuard = requireK3Authority("risk_k3_rule_create");
        if (permissionGuard != null) return permissionGuard;
        String ruleKey = dimension + ":" + condition;
        String normalizedCondition = condition;
        String actor = authenticatedK3Operator();
        return idempotentCommand("K3_RULE_CREATE", idempotencyKey,
                requestHash(dimension, normalizedCondition, action, String.valueOf(priority), request.reason()), () -> {
                    if (k3PriorityConflicts(null, dimension, priority)) {
                        return rejectK3Write("CREATE", ruleKey, actor,
                                OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K3_RULE_PRIORITY_CONFLICT",
                                request.reason(), idempotencyKey);
                    }
                    if (!A2ReplayContext.isReplaying()
                            && lockMapper.countActiveByTarget("K", "rule", ruleKey) > 0) {
                        return rejectK3Write("CREATE", ruleKey, actor, 409, "OBJECT_LOCKED_BY_A2",
                                request.reason(), idempotencyKey);
                    }
                    String ruleId = nextRuleId();
                    RiskRuleView created = riskRepository.createWithdrawRule(
                            ruleId, dimension, normalizedCondition, action, "draft", priority, actor);
                    auditRequired("K3_WITHDRAW_RULE_CREATED", "WITHDRAW_RULE", created.ruleId(), actor, Map.of(
                            "ruleId", created.ruleId(),
                            "dimension", created.dimension(),
                            "action", created.action(),
                            "priority", created.priority(),
                            "after", k3RuleSnapshot(created),
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
                    return ApiResult.ok(created);
                });
    }

    @Transactional
    public ApiResult<RiskRuleView> updateWithdrawRuleState(String ruleId, String idempotencyKey, RiskRuleStatusRequest request) {
        ApiResult<RiskRuleView> guard = requireK3Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_REQUIRED_FIELDS_MISSING");
        }
        String normalized = trimmed(ruleId);
        if (!StringUtils.hasText(normalized)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_ID_REQUIRED");
        }
        String state = normalizeRuleState(request.state());
        if (state == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_STATE_INVALID");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K3_RULE_EXPECTED_VERSION_REQUIRED");
        }
        String requiredAuthority = "archived".equals(state) ? "risk_k3_rule_archive" : "risk_k3_rule_toggle";
        ApiResult<RiskRuleView> permissionGuard = requireK3Authority(requiredAuthority);
        if (permissionGuard != null) return permissionGuard;
        String actor = authenticatedK3Operator();
        return idempotentCommand("K3_RULE_STATE:" + normalized, idempotencyKey,
                requestHash(normalized, state, String.valueOf(request.expectedVersion()), request.reason()), () -> {
                    if (!A2ReplayContext.isReplaying()
                            && lockMapper.countActiveByTarget("K", "rule", normalized) > 0) {
                        return rejectK3Write("STATE_CHANGE", normalized, actor, 409, "OBJECT_LOCKED_BY_A2",
                                request.reason(), idempotencyKey);
                    }
                    RiskRuleView before = riskRepository.findWithdrawRule(normalized).orElse(null);
                    if (before == null) {
                        return rejectK3Write("STATE_CHANGE", normalized, actor, 404, "RULE_NOT_FOUND",
                                request.reason(), idempotencyKey);
                    }
                    if (!request.expectedVersion().equals(before.version())) {
                        return rejectK3Write("STATE_CHANGE", normalized, actor, 409, "K3_RULE_CONCURRENT_UPDATE",
                                request.reason(), idempotencyKey);
                    }
                    if (!K3_RULE_TRANSITIONS.getOrDefault(trimmed(before.state()).toLowerCase(Locale.ROOT), Set.of()).contains(state)) {
                        return rejectK3Write("STATE_CHANGE", normalized, actor,
                                OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "K3_RULE_TRANSITION_INVALID",
                                request.reason(), idempotencyKey);
                    }
                    RiskRuleView updated = riskRepository.updateWithdrawRuleState(
                            normalized, request.expectedVersion(), state).orElse(null);
                    if (updated == null) {
                        return rejectK3Write("STATE_CHANGE", normalized, actor, 409, "K3_RULE_CONCURRENT_UPDATE",
                                request.reason(), idempotencyKey);
                    }
                    auditRequired("K3_WITHDRAW_RULE_STATE_CHANGED", "WITHDRAW_RULE", normalized, actor, Map.of(
                            "ruleId", normalized,
                            "before", k3RuleSnapshot(before),
                            "after", k3RuleSnapshot(updated),
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
                    return ApiResult.ok(updated);
                });
    }

    @Transactional
    public ApiResult<RiskRuleView> updateWithdrawRuleCondition(String ruleId, String idempotencyKey, RiskRuleConditionRequest request) {
        ApiResult<RiskRuleView> guard = requireK3Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_CONDITION_REQUIRED");
        }
        String normalized = trimmed(ruleId);
        String condition = trimmed(request.conditionText());
        if (!StringUtils.hasText(normalized) || !StringUtils.hasText(condition)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_CONDITION_REQUIRED");
        }
        if (condition.length() > 1000) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_CONDITION_TOO_LONG");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K3_RULE_EXPECTED_VERSION_REQUIRED");
        }
        ApiResult<RiskRuleView> permissionGuard = requireK3Authority("risk_k3_write");
        if (permissionGuard != null) return permissionGuard;
        String actor = authenticatedK3Operator();
        RiskRuleView current = riskRepository.findWithdrawRule(normalized).orElse(null);
        if (current == null) {
            return rejectK3Write("CONFIG_CHANGE", normalized, actor, 404, "RULE_NOT_FOUND",
                    request.reason(), idempotencyKey);
        }
        condition = normalizeWithdrawRuleCondition(current.dimension(), condition);
        if (!StringUtils.hasText(condition)) {
            return rejectK3Write("CONFIG_CHANGE", normalized, actor,
                    OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_CONDITION_INVALID",
                    request.reason(), idempotencyKey);
        }
        String action = request.action() == null ? current.action() : normalizeK3ConfigurableAction(request.action());
        Integer priority = request.priority() == null ? current.priority() : normalizeK3Priority(request.priority());
        if (action == null) {
            return rejectK3Write("CONFIG_CHANGE", normalized, actor,
                    OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_ACTION_INVALID",
                    request.reason(), idempotencyKey);
        }
        if (priority == null) {
            return rejectK3Write("CONFIG_CHANGE", normalized, actor,
                    OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K3_RULE_PRIORITY_INVALID",
                    request.reason(), idempotencyKey);
        }
        String normalizedCondition = condition;
        return idempotentCommand("K3_RULE_CONFIG:" + normalized, idempotencyKey,
                requestHash(normalized, normalizedCondition, action, String.valueOf(priority),
                        String.valueOf(request.expectedVersion()), request.reason()), () -> {
                    if (!A2ReplayContext.isReplaying()
                            && lockMapper.countActiveByTarget("K", "rule", normalized) > 0) {
                        return rejectK3Write("CONFIG_CHANGE", normalized, actor, 409, "OBJECT_LOCKED_BY_A2",
                                request.reason(), idempotencyKey);
                    }
                    RiskRuleView before = riskRepository.findWithdrawRule(normalized).orElse(null);
                    if (before == null) {
                        return rejectK3Write("CONFIG_CHANGE", normalized, actor, 404, "RULE_NOT_FOUND",
                                request.reason(), idempotencyKey);
                    }
                    if ("archived".equalsIgnoreCase(before.state())) {
                        return rejectK3Write("CONFIG_CHANGE", normalized, actor,
                                OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "K3_RULE_TRANSITION_INVALID",
                                request.reason(), idempotencyKey);
                    }
                    if (!request.expectedVersion().equals(before.version())) {
                        return rejectK3Write("CONFIG_CHANGE", normalized, actor, 409, "K3_RULE_CONCURRENT_UPDATE",
                                request.reason(), idempotencyKey);
                    }
                    if (k3PriorityConflicts(normalized, before.dimension(), priority)) {
                        return rejectK3Write("CONFIG_CHANGE", normalized, actor,
                                OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K3_RULE_PRIORITY_CONFLICT",
                                request.reason(), idempotencyKey);
                    }
                    RiskRuleView updated = riskRepository.updateWithdrawRuleConfiguration(
                            normalized, request.expectedVersion(), normalizedCondition, action, priority).orElse(null);
                    if (updated == null) {
                        return rejectK3Write("CONFIG_CHANGE", normalized, actor, 409, "K3_RULE_CONCURRENT_UPDATE",
                                request.reason(), idempotencyKey);
                    }
                    auditRequired("K3_WITHDRAW_RULE_CONDITION_CHANGED", "WITHDRAW_RULE", normalized, actor, Map.of(
                            "ruleId", normalized,
                            "before", k3RuleSnapshot(before),
                            "after", k3RuleSnapshot(updated),
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
                    return ApiResult.ok(updated);
                });
    }

    @Transactional
    public ApiResult<Map<String, Object>> dryRunWithdrawRules(String idempotencyKey, RiskRuleDryRunRequest request) {
        ApiResult<Map<String, Object>> guard = requireK3Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<Map<String, Object>> permissionGuard = requireK3Authority("risk_k3_write");
        if (permissionGuard != null) return permissionGuard;
        String actor = authenticatedK3Operator();
        return idempotentCommand("K3_RULE_DRY_RUN", idempotencyKey,
                requestHash(request.reason()), () -> {
                    String batchNo = "K3-DRY-" + UUID.randomUUID().toString().replace("-", "")
                            .substring(0, 10).toUpperCase(Locale.ROOT);
                    List<RiskRuleView> activeRules = riskRepository.withdrawRules().stream()
                            .filter(rule -> "active".equalsIgnoreCase(trimmed(rule.state())))
                            .toList();
                    final boolean requiresThirdParty;
                    try {
                        requiresThirdParty = k3WithdrawalRuleEvaluator.requiresThirdParty(activeRules);
                    } catch (RuntimeException invalidRules) {
                        return ApiResult.fail(503, "K3_DRY_RUN_RULES_UNAVAILABLE");
                    }
                    List<RiskWithdrawCandidateView> candidates = activeRules.isEmpty()
                            ? List.of() : riskRepository.withdrawRuleCandidates(requiresThirdParty ? 50 : 200);
                    int hitCount = 0;
                    Map<String, Integer> hitCountsByRule = new LinkedHashMap<>();
                    Map<String, Integer> routeCountMap = new LinkedHashMap<>();
                    Map<String, String> primaryRuleIds = new LinkedHashMap<>();
                    Map<String, BigDecimal> thirdPartyScores = new LinkedHashMap<>();
                    long providerDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
                    for (RiskWithdrawCandidateView candidate : candidates) {
                        BigDecimal thirdPartyScore = null;
                        if (requiresThirdParty) {
                            if (System.nanoTime() >= providerDeadline) {
                                return ApiResult.fail(503, "K3_DRY_RUN_ADDRESS_REPUTATION_TIMEOUT");
                            }
                            String scoreKey = addressReputationCacheKey(
                                    candidate.chain(), candidate.targetAddress());
                            try {
                                thirdPartyScore = thirdPartyScores.get(scoreKey);
                                if (thirdPartyScore == null) {
                                    thirdPartyScore = chainAddressReputationGateway.score(
                                            candidate.chain(), candidate.targetAddress());
                                    thirdPartyScores.put(scoreKey, thirdPartyScore);
                                }
                            } catch (RuntimeException unavailable) {
                                return ApiResult.fail(503, "K3_DRY_RUN_ADDRESS_REPUTATION_UNAVAILABLE");
                            }
                        }
                        final ffdd.opsconsole.risk.facade.WithdrawalRiskDecision decision;
                        try {
                            decision = k3WithdrawalRuleEvaluator.evaluate(activeRules,
                                    new ffdd.opsconsole.risk.facade.WithdrawalRiskContext(
                                            null, candidate.withdrawalNo(), candidate.userNo(), candidate.amount(),
                                            candidate.withdrawalCount24h(), candidate.withdrawalSum24h(),
                                            candidate.accountAgeDays(), candidate.addressReputation(),
                                            candidate.chain(), candidate.targetAddress(), thirdPartyScore));
                        } catch (RuntimeException unavailable) {
                            return ApiResult.fail(503, "K3_DRY_RUN_ADDRESS_REPUTATION_UNAVAILABLE");
                        }
                        List<RiskRuleView> matches = decision.matchedRules();
                        hitCount += matches.size();
                        matches.forEach(rule -> hitCountsByRule.merge(rule.ruleId(), 1, Integer::sum));
                        routeCountMap.merge(decision.action(), 1, Integer::sum);
                        if (decision.primaryRuleId() != null) {
                            primaryRuleIds.put(candidate.withdrawalNo(), decision.primaryRuleId());
                        }
                    }
                    List<RiskRouteCountView> routeCounts = List.of(
                            k3DryRunRoute("pass", "通过", routeCountMap, "#22c55e"),
                            k3DryRunRoute("delay", "延迟", routeCountMap, "#f59e0b"),
                            k3DryRunRoute("manual", "人工审核", routeCountMap, "#3b82f6"),
                            k3DryRunRoute("freeze", "冻结", routeCountMap, "#ef4444"));
                    LocalDateTime completedAt = LocalDateTime.now();
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("batchNo", batchNo);
                    response.put("status", "COMPLETED");
                    response.put("sampleWindowDays", 30);
                    response.put("evaluatedWithdrawals", candidates.size());
                    response.put("activeRules", activeRules.size());
                    response.put("hitCount", hitCount);
                    response.put("hitCountsByRule", hitCountsByRule);
                    response.put("routeCounts", routeCounts);
                    response.put("primaryRuleIdsByWithdrawal", primaryRuleIds);
                    response.put("completedAt", completedAt);
                    auditRequired("K3_WITHDRAW_RULE_DRY_RUN_COMPLETED", "WITHDRAW_RULE_DRY_RUN", batchNo, actor, Map.of(
                            "batchNo", batchNo,
                            "evaluatedWithdrawals", candidates.size(),
                            "activeRules", activeRules.size(),
                            "hitCount", hitCount,
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
                    return ApiResult.ok(response);
                });
    }

    private boolean withdrawRuleMatches(RiskRuleView rule, RiskWithdrawCandidateView candidate) {
        String dimension = dimensionMeta(rule.dimension()).ruleKey();
        String condition = trimmed(rule.conditionText()).toLowerCase(Locale.ROOT);
        if ("largeAmountUsdt".equals(dimension)) {
            BigDecimal threshold = firstDecimal(condition);
            return threshold != null && candidate.amount() != null
                    && compareRuleValue(candidate.amount(), threshold, condition);
        }
        if ("velocity24h".equals(dimension)) {
            return withdrawVelocityRuleMatches(
                    condition, candidate.withdrawalCount24h(), candidate.withdrawalSum24h());
        }
        if ("newAccountProtectDays".equals(dimension)) {
            BigDecimal threshold = firstDecimal(condition);
            return threshold != null && candidate.accountAgeDays() != null
                    && compareRuleValue(BigDecimal.valueOf(candidate.accountAgeDays()), threshold, condition);
        }
        if ("addressReputationSource".equals(dimension)) {
            String reputation = trimmed(candidate.addressReputation()).toLowerCase(Locale.ROOT);
            return Set.of("low", "blacklist", "blacklisted").contains(reputation);
        }
        return false;
    }

    private boolean withdrawVelocityRuleMatches(String condition, Integer count24h, BigDecimal withdrawalSum24h) {
        Matcher matcher = K3_VELOCITY_RULE.matcher(trimmed(condition).replace(",", ""));
        if (matcher.matches()) {
            boolean countMatched = count24h != null
                    && compareRuleValue(BigDecimal.valueOf(count24h), new BigDecimal(matcher.group(2)), matcher.group(1));
            boolean amountMatched = withdrawalSum24h != null
                    && compareRuleValue(withdrawalSum24h, new BigDecimal(matcher.group(4)), matcher.group(3));
            return countMatched || amountMatched;
        }
        BigDecimal threshold = firstDecimal(condition);
        return threshold != null && count24h != null
                && compareRuleValue(BigDecimal.valueOf(count24h), threshold, condition);
    }

    private Comparator<RiskRuleView> k3RuleStrengthComparator() {
        return Comparator
                .comparingInt((RiskRuleView rule) -> K3_ACTION_SEVERITY.getOrDefault(rule.action(), 0))
                .thenComparingInt(rule -> rule.priority() == null ? 0 : rule.priority())
                .thenComparing(RiskRuleView::ruleId);
    }

    private RiskRouteCountView k3DryRunRoute(
            String routeKey, String label, Map<String, Integer> routeCounts, String color) {
        return new RiskRouteCountView(routeKey, label, routeCounts.getOrDefault(routeKey, 0).longValue(), color);
    }

    private BigDecimal firstDecimal(String value) {
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(trimmed(value).replace(",", ""));
        return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
    }

    private boolean compareRuleValue(BigDecimal actual, BigDecimal threshold, String condition) {
        String text = trimmed(condition);
        if (text.contains("<=" ) || text.contains("≤")) {
            return actual.compareTo(threshold) <= 0;
        }
        if (text.contains("<")) {
            return actual.compareTo(threshold) < 0;
        }
        if (text.contains(">=") || text.contains("≥")) {
            return actual.compareTo(threshold) >= 0;
        }
        if (text.contains(">")) return actual.compareTo(threshold) > 0;
        return actual.compareTo(threshold) >= 0;
    }

    @Transactional
    public ApiResult<Map<String, Object>> arbitrageOverview() {
        List<RiskArbitrageParamView> currentParams = riskRepository.arbitrageParams();
        int[] trialBoundary = trialCycleBoundary(currentParams);
        riskRepository.refreshE3TradeinArbitrageProjection();
        List<RiskOpsRepository.TrialCycleDetection> trialDetections =
                riskRepository.refreshTrialCycleArbitrageProjection(trialBoundary[0], trialBoundary[1]);
        emitTrialCycleSignals(trialDetections, trialBoundary[1]);
        List<RiskArbitrageRowView> rows = riskRepository.arbitrageRows();
        Map<String, List<RiskArbitrageRowView>> byView = rows.stream()
                .collect(Collectors.groupingBy(RiskArbitrageRowView::viewKey, LinkedHashMap::new, Collectors.toList()));
        List<RiskArbitrageViewGroup> views = K2_VIEWS.stream()
                .map(spec -> new RiskArbitrageViewGroup(
                        spec.key(),
                        spec.label(),
                        spec.sub(),
                        spec.head(),
                        spec.note(),
                        byView.getOrDefault(spec.key(), List.of())))
                .toList();
        Map<String, Object> response = new LinkedHashMap<>();
        List<RiskArbitrageParamView> params = currentParams.stream()
                .filter(param -> !"minHoldingMonths".equals(param.key()))
                .filter(param -> !RETIRED_K2_PARAM_KEYS.contains(param.key()))
                .map(this::withCanonicalOtpValue)
                .toList();
        response.put("stats", riskRepository.arbitrageStats());
        response.put("params", params);
        response.put("views", views);
        response.put("redlines", List.of("K2 only marks and emits signals", "account freezing is linked to K1 action chain", "welcome gift blocking never touches settled assets"));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<RiskArbitrageParamView> updateArbitrageParam(String key, String idempotencyKey, RiskArbitrageParamUpdateRequest request) {
        ApiResult<RiskArbitrageParamView> guard = requireK2Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey = trimmed(key);
        String value = trimmed(request.value());
        if (!StringUtils.hasText(normalizedKey) || !StringUtils.hasText(value)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_PARAM_VALUE_REQUIRED");
        }
        ApiResult<RiskArbitrageParamView> permissionGuard = requireK2ActionAuthority("risk_k2_write");
        if (permissionGuard != null) return permissionGuard;
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_PARAM_EXPECTED_VERSION_REQUIRED");
        }
        if (value.length() > 128) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_PARAM_VALUE_TOO_LONG");
        }
        value = normalizeArbitrageParam(normalizedKey, value);
        if (!StringUtils.hasText(value)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_PARAM_VALUE_INVALID");
        }
        String normalizedValue = value;
        String actor = authenticatedOperator();
        return idempotentCommand("K2_PARAM:" + normalizedKey, idempotencyKey,
                requestHash(normalizedKey, normalizedValue, String.valueOf(request.expectedVersion()), request.reason()), () -> {
                    RiskArbitrageParamView before = riskRepository.arbitrageParams().stream()
                            .filter(param -> normalizedKey.equals(param.key()))
                            .findFirst().orElse(null);
                    if (before == null) return ApiResult.fail(404, "K2_PARAM_NOT_FOUND");
                    // Capture the canonical value before either persistence layer is mutated.
                    // Otherwise OTP-backed parameters would re-read the newly written config
                    // and make the audit before/after snapshots identical.
                    RiskArbitrageParamView beforeCanonical = withCanonicalOtpValue(before);
                    if (before.version() != request.expectedVersion()) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "K2_PARAM_CONCURRENT_UPDATE");
                    }
                    RiskArbitrageParamView updated = riskRepository.updateArbitrageParam(
                            normalizedKey, request.expectedVersion(), normalizedValue).orElse(null);
                    if (updated == null) throw new BizException(409, "K2_PARAM_CONCURRENT_UPDATE");
                    persistCanonicalOtpValue(normalizedKey, normalizedValue);
                    RiskArbitrageParamView after = withCanonicalOtpValue(updated);
                    auditRequired("K2_PARAM_CHANGED", "RISK_ARBITRAGE_PARAM", normalizedKey, actor, Map.of(
                            "key", normalizedKey,
                            "before", k2ParamSnapshot(beforeCanonical),
                            "after", k2ParamSnapshot(after),
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
                    return ApiResult.ok(after);
                });
    }

    @Transactional
    public ApiResult<RiskArbitrageRowView> executeArbitrageAction(String rowId, String action, String idempotencyKey, RiskArbitrageActionRequest request) {
        ApiResult<RiskArbitrageRowView> guard = requireK2Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedRowId = trimmed(rowId);
        String normalizedAction = trimmed(action).toLowerCase(Locale.ROOT);
        K2ActionSpec spec = K2_ACTIONS.get(normalizedAction);
        if (!StringUtils.hasText(normalizedRowId) || spec == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_ACTION_INVALID");
        }
        if ("freeze-cluster".equals(normalizedAction)) {
            ApiResult<RiskArbitrageRowView> approvalGuard = delegatedDirectExecutionGuard(
                    "k2_row_freeze", "RISK_ARBITRAGE_ROW", normalizedRowId);
            if (approvalGuard != null) return approvalGuard;
        }
        ApiResult<RiskArbitrageRowView> permissionGuard = requireK2ActionAuthority(spec.requiredAuthority());
        if (permissionGuard != null) return permissionGuard;
        if (request.expectedVersion() == null || request.expectedVersion() < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_ROW_EXPECTED_VERSION_REQUIRED");
        }
        String actor = authenticatedOperator();
        return idempotentCommand("K2_ACTION:" + normalizedRowId, idempotencyKey,
                requestHash(normalizedRowId, normalizedAction, String.valueOf(request.expectedVersion()),
                        String.valueOf(request.clusterExpectedVersion()), request.reason()), () -> {
                    RiskArbitrageRowView before = riskRepository.findArbitrageRow(normalizedRowId).orElse(null);
                    if (before == null) return ApiResult.fail(404, "K2_ROW_NOT_FOUND");
                    if (!request.expectedVersion().equals(before.version())) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "K2_ROW_CONCURRENT_UPDATE");
                    }
                    if (StringUtils.hasText(before.disposition()) || !before.actions().contains(spec.requiredRowAction())) {
                        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
                    }
                    if (!A2ReplayContext.isReplaying()
                            && lockMapper.countActiveByTarget("K", "arbitrage_row", normalizedRowId) > 0) {
                        return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
                    }
                    List<Long> subjectUserIds = riskRepository.arbitrageSubjectUserIds(normalizedRowId);
                    if (subjectUserIds.isEmpty()) {
                        return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_SIGNAL_SUBJECT_NOT_RESOLVED");
                    }
                    boolean linkedClusterFrozen = false;
                    if ("freeze-cluster".equals(normalizedAction)) {
                        String clusterId = trimmed(before.clusterId());
                        if (!StringUtils.hasText(clusterId)) {
                            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_CLUSTER_ID_REQUIRED");
                        }
                        if (request.clusterExpectedVersion() == null || request.clusterExpectedVersion() < 0) {
                            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_CLUSTER_EXPECTED_VERSION_REQUIRED");
                        }
                        if (!A2ReplayContext.isReplaying()
                                && lockMapper.countActiveByTarget("K", "cluster", clusterId) > 0) {
                            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
                        }
                        RiskOpsRepository.MultiAccountClusterState clusterBefore =
                                riskRepository.multiAccountClusterState(clusterId).orElse(null);
                        if (clusterBefore == null) return ApiResult.fail(404, "K1_CLUSTER_NOT_FOUND");
                        if (clusterBefore.version() != request.clusterExpectedVersion()) {
                            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "K1_CLUSTER_CONCURRENT_UPDATE");
                        }
                        if (!K1_CLUSTER_TRANSITIONS.getOrDefault(clusterBefore.status(), Set.of()).contains("frozen")) {
                            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "K1_CLUSTER_FLAG_REQUIRED");
                        }
                        if (!riskRepository.updateMultiAccountClusterStatus(
                                clusterId, clusterBefore.status(), clusterBefore.version(), "frozen",
                                request.reason().trim(), actor)) {
                            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "K1_CLUSTER_CONCURRENT_UPDATE");
                        }
                        int accountsFrozen = userAccountControlFacade.freezeActiveUsersByUserNos(
                                clusterBefore.affectedUserIds(), request.reason().trim(), actor, clusterId);
                        linkedClusterFrozen = true;
                        auditRequired("K1_CLUSTER_STATUS_CHANGED_BY_K2", "RISK_MULTI_ACCOUNT_CLUSTER", clusterId, actor, Map.of(
                                "clusterId", clusterId,
                                "before", Map.of("status", clusterBefore.status(), "version", clusterBefore.version()),
                                "after", Map.of("status", "frozen", "version", clusterBefore.version() + 1),
                                "accountsFrozen", accountsFrozen,
                                "sourceRowId", normalizedRowId,
                                "reason", request.reason().trim(),
                                "idempotencyKey", idempotencyKey.trim()));
                    }
                    RiskArbitrageRowView updated = riskRepository.updateArbitrageDisposition(
                            normalizedRowId, request.expectedVersion(), spec.disposition()).orElse(null);
                    if (updated == null) throw new BizException(409, "K2_ROW_CONCURRENT_UPDATE");
                    emitArbitrageActionSignal(before, normalizedAction, subjectUserIds, actor);
                    auditRequired(spec.auditAction(), "RISK_ARBITRAGE_ROW", normalizedRowId, actor, Map.of(
                            "rowId", normalizedRowId,
                            "action", normalizedAction,
                            "before", k2RowSnapshot(before),
                            "after", k2RowSnapshot(updated),
                            "linkedClusterFrozen", linkedClusterFrozen,
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
                    return ApiResult.ok(updated);
                });
    }

    private int[] trialCycleBoundary(List<RiskArbitrageParamView> params) {
        String configured = params.stream()
                .filter(param -> "trialCycleThreshold".equals(param.key()))
                .map(RiskArbitrageParamView::value)
                .findFirst()
                .orElse(">= 3 次 / 30 天");
        Matcher matcher = K2_TRIAL_THRESHOLD.matcher(configured.trim());
        if (!matcher.matches()) return new int[]{3, 30};
        int count = Integer.parseInt(matcher.group(2));
        if (">".equals(matcher.group(1))) count++;
        return new int[]{Math.max(1, Math.min(count, 100)),
                Math.max(1, Math.min(Integer.parseInt(matcher.group(3)), 365))};
    }

    private void emitTrialCycleSignals(List<RiskOpsRepository.TrialCycleDetection> detections, int windowDays) {
        for (RiskOpsRepository.TrialCycleDetection detection : detections) {
            String signalNo = "K2-TC-" + requestHash(String.valueOf(detection.userId())).substring(0, 40);
            String evidence = "row=" + detection.rowId() + ";cycles=" + detection.cycleCount()
                    + ";windowDays=" + windowDays + ";source=A4:trial.started";
            if (!riskRepository.recordSignalIfAbsent(signalNo, detection.userId(),
                    "risk.trial_cycle_detected", "HIGH", evidence, "K2_PROJECTION")) {
                continue;
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("row_id", detection.rowId());
            payload.put("cycle_count", detection.cycleCount());
            payload.put("window_days", windowDays);
            payload.put("severity", "HIGH");
            payload.put("subject_user_ids", List.of(detection.userId()));
            if (StringUtils.hasText(detection.clusterId())) payload.put("cluster_id", detection.clusterId());
            eventOutboxService.publish("RISK_ARBITRAGE_ROW", detection.rowId(),
                    "risk.trial_cycle_detected", payload);
        }
    }

    private void emitArbitrageActionSignal(
            RiskArbitrageRowView row, String action, List<Long> subjectUserIds, String actor) {
        String signalType = "board-flag".equals(action)
                ? "risk.leaderboard_velocity_flagged"
                : "risk.arbitrage_suspected";
        String severity = "freeze-cluster".equals(action) ? "CRITICAL" : "HIGH";
        for (Long userId : subjectUserIds) {
            String signalNo = "K2-" + requestHash(row.rowId(), action, String.valueOf(userId)).substring(0, 40);
            riskRepository.recordSignalIfAbsent(signalNo, userId, signalType, severity,
                    "row=" + row.rowId() + ";view=" + row.viewKey() + ";action=" + action,
                    actor);
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("row_id", row.rowId());
        payload.put("view_key", row.viewKey());
        payload.put("action", action);
        payload.put("severity", severity);
        payload.put("subject_user_ids", subjectUserIds);
        if (StringUtils.hasText(row.clusterId())) payload.put("cluster_id", row.clusterId());
        eventOutboxService.publish("RISK_ARBITRAGE_ROW", row.rowId(), signalType, payload);
    }

    public ApiResult<Map<String, Object>> scoringOverview() {
        return scoringOverview(new RiskScoringOverviewQueryRequest(null, null));
    }

    public ApiResult<Map<String, Object>> scoringOverview(RiskScoringOverviewQueryRequest request) {
        List<RiskScoreDistributionView> distribution = riskRepository.scoringDistribution();
        RiskScoreModelView activeModel = riskRepository.activeScoringModel().orElse(null);
        int overridePageNum = normalizePageNum(request == null ? null : request.overridePageNum());
        int overridePageSize = normalizeLimit(request == null ? null : request.overridePageSize(), 5, 50);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("model", activeModel);
        response.put("draft", riskRepository.draftScoringModel().orElse(null));
        response.put("modelHistory", riskRepository.scoringModels());
        response.put("dimensions", riskRepository.scoringDimensions());
        response.put("config", riskRepository.scoringConfig());
        response.put("distribution", distribution);
        response.put("totalUsers", distribution.stream().mapToLong(v -> v.count() == null ? 0L : v.count()).sum());
        response.put("recomputePending", activeModel == null
                ? 0L : riskRepository.countScoreUsersNeedingProjection(activeModel.version()));
        response.put("overrides", riskRepository.pageScoreOverrides(overridePageNum, overridePageSize));
        response.put("overrideActive", riskRepository.countActiveScoreOverrides());
        response.put("redlines", List.of(
                "API weight ratios must sum to 1 +/- 0.001; the UI displays canonical percentage points",
                "model changes require platform admin reason",
                "single-user override must be reversible"));
        return ApiResult.ok(response);
    }

    public ApiResult<RiskScoreUserView> scoreUser(String userNo) {
        String normalized = trimmed(userNo);
        if (!StringUtils.hasText(normalized)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SCORE_USER_REQUIRED");
        }
        return riskRepository.findScoreUser(normalized)
                .map(this::withK4ScoreHistory)
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "SCORE_USER_NOT_FOUND"));
    }

    public ApiResult<RiskScoreUserView> currentScoreUser(String userNo) {
        String normalized = trimmed(userNo);
        if (!StringUtils.hasText(normalized)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SCORE_USER_REQUIRED");
        }
        return riskRepository.findCurrentScoreUser(normalized)
                .map(this::withK4ScoreHistory)
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(503, "K4_RISK_SCORE_UNAVAILABLE"));
    }

    public ApiResult<List<RiskScoreUserSearchView>> searchScoreUsers(String keyword, Integer limit) {
        int normalizedLimit = normalizeLimit(limit, 8, 20);
        return ApiResult.ok(riskRepository.searchScoreUsers(trimmed(keyword), normalizedLimit));
    }

    /** @deprecated K4 model changes must be saved as a versioned draft and explicitly published. */
    @Deprecated
    public ApiResult<Map<String, Object>> updateScoringWeights(String idempotencyKey, RiskScoringWeightsRequest request) {
        return rejectK4Write("legacyWeights", "active", authenticatedK4Operator(), 409,
                "K4_MODEL_DRAFT_REQUIRED", request == null ? null : request.reason(), idempotencyKey);
    }

    public ApiResult<Map<String, Object>> updateScoringSource(String idempotencyKey, RiskScoringSourceRequest request) {
        return rejectK4Write("legacySource", "active", authenticatedK4Operator(), 409,
                "K4_MODEL_DRAFT_REQUIRED", request == null ? null : request.reason(), idempotencyKey);
    }

    public ApiResult<Map<String, Object>> updateScoringBand(String idempotencyKey, RiskScoringBandRequest request) {
        return rejectK4Write("legacyBand", "active", authenticatedK4Operator(), 409,
                "K4_MODEL_DRAFT_REQUIRED", request == null ? null : request.reason(), idempotencyKey);
    }

    public ApiResult<Map<String, Object>> updateScoringEscalate(String idempotencyKey, RiskScoringEscalateRequest request) {
        return rejectK4Write("legacyEscalate", "active", authenticatedK4Operator(), 409,
                "K4_MODEL_DRAFT_REQUIRED", request == null ? null : request.reason(), idempotencyKey);
    }

    @Transactional
    public ApiResult<Map<String, Object>> saveScoringModelDraft(
            String idempotencyKey, RiskScoringModelDraftRequest request) {
        String actor = authenticatedK4Operator();
        ApiResult<Map<String, Object>> guard = validateK4Draft(idempotencyKey, request);
        if (guard != null) {
            return rejectK4Write("saveDraft", "draft", actor, guard.getCode(), guard.getMessage(),
                    request == null ? null : request.reason(), idempotencyKey);
        }
        return executeK4Write("saveDraft", "draft", actor, request.reason(), idempotencyKey, () ->
                idempotentCommand("K4_MODEL_DRAFT", idempotencyKey,
                requestHash(k4DraftHash(request)), () -> {
                    if (!A2ReplayContext.isReplaying()
                            && lockMapper.countActiveByTarget("K", "score_model", "draft") > 0) {
                        return rejectK4Write("saveDraft", "draft", actor, 409,
                                "OBJECT_LOCKED_BY_A2", request.reason(), idempotencyKey);
                    }
                    RiskScoreModelView before = riskRepository.draftScoringModel()
                            .orElseGet(() -> riskRepository.activeScoringModel().orElse(null));
                    RiskScoreModelView saved = riskRepository.saveScoringModelDraft(
                            request.expectedVersion(), request, actor).orElse(null);
                    if (saved == null) {
                        return rejectK4Write("saveDraft", "draft", actor, 409,
                                "K4_MODEL_CONCURRENT_UPDATE", request.reason(), idempotencyKey);
                    }
                    auditRequired("K4_MODEL_DRAFT_SAVED", "RISK_SCORE_MODEL", String.valueOf(saved.version()), actor,
                            k4AuditDetail(before, saved, request.reason(), idempotencyKey));
                    return scoringOverview();
                }));
    }

    @Transactional
    public ApiResult<Map<String, Object>> publishScoringModel(
            String idempotencyKey, RiskScoringModelPublishRequest request) {
        String actor = authenticatedK4Operator();
        ApiResult<Map<String, Object>> guard = requireK4Command(
                idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectK4Write("publish", "draft", actor, guard.getCode(), guard.getMessage(),
                    request == null ? null : request.reason(), idempotencyKey);
        }
        if (request == null || request.expectedVersion() == null) {
            return rejectK4Write("publish", "draft", actor, OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                    "K4_MODEL_VERSION_REQUIRED", request == null ? null : request.reason(), idempotencyKey);
        }
        if (!A2ReplayContext.isReplaying()
                && !superAdminAuthorization.isSuperAdmin(SecurityContextHolder.getContext().getAuthentication())) {
            return rejectK4Write("publish", "draft", actor, OpsErrorCode.FORBIDDEN.httpStatus(),
                    OpsErrorCode.FORBIDDEN.name(), request.reason(), idempotencyKey);
        }
        return executeK4Write("publish", "draft", actor, request.reason(), idempotencyKey, () ->
                idempotentCommand("K4_MODEL_PUBLISH", idempotencyKey,
                requestHash(String.valueOf(request.expectedVersion()), request.reason()), () -> {
                    if (!A2ReplayContext.isReplaying()
                            && lockMapper.countActiveByTarget("K", "score_model", "draft") > 0) {
                        return rejectK4Write("publish", "draft", actor, 409,
                                "OBJECT_LOCKED_BY_A2", request.reason(), idempotencyKey);
                    }
                    RiskScoreModelView candidate = riskRepository.draftScoringModel().orElse(null);
                    if (candidate != null && !validK4ModelSnapshot(candidate)) {
                        return rejectK4Write("publish", "draft", actor, 422,
                                "K4_MODEL_SNAPSHOT_INVALID", request.reason(), idempotencyKey);
                    }
                    RiskScoreModelView before = riskRepository.activeScoringModel().orElse(null);
                    RiskScoreModelView published = riskRepository.publishScoringModel(
                            request.expectedVersion(), request.reason(), actor).orElse(null);
                    if (published == null) {
                        return rejectK4Write("publish", "draft", actor, 409,
                                "K4_MODEL_CONCURRENT_UPDATE", request.reason(), idempotencyKey);
                    }
                    recomputeK4Scores(
                            published,
                            riskRepository.scoreUserNosNeedingProjection(
                                    published.version(), K4ScoreBackfillInitializer.CHUNK_SIZE),
                            request.reason().trim(),
                            actor,
                            idempotencyKey.trim());
                    auditRequired("K4_MODEL_PUBLISHED", "RISK_SCORE_MODEL", String.valueOf(published.version()), actor,
                            k4AuditDetail(before, published, request.reason(), idempotencyKey));
                    return scoringOverview();
                }));
    }

    @Transactional
    public ApiResult<Map<String, Object>> restoreScoringModelDraft(
            String idempotencyKey, RiskScoringModelRestoreRequest request) {
        String actor = authenticatedK4Operator();
        ApiResult<Map<String, Object>> guard = requireK4Command(
                idempotencyKey, request == null ? null : request.reason());
        if (guard != null || request == null || request.modelVersion() == null || request.expectedVersion() == null) {
            String code = guard == null ? "K4_MODEL_RESTORE_INVALID" : guard.getMessage();
            int status = guard == null ? OpsErrorCode.VALIDATION_FAILED.httpStatus() : guard.getCode();
            return rejectK4Write("restoreDraft", "model-history", actor, status, code,
                    request == null ? null : request.reason(), idempotencyKey);
        }
        RiskScoreModelView historical = riskRepository.scoringModel(request.modelVersion()).orElse(null);
        if (historical == null || "draft".equals(historical.state())) {
            return rejectK4Write("restoreDraft", String.valueOf(request.modelVersion()), actor, 404,
                    "K4_MODEL_HISTORY_NOT_FOUND", request.reason(), idempotencyKey);
        }
        if (!validK4ModelSnapshot(historical)) {
            return rejectK4Write("restoreDraft", String.valueOf(request.modelVersion()), actor, 422,
                    "K4_MODEL_SNAPSHOT_INVALID", request.reason(), idempotencyKey);
        }
        return executeK4Write("restoreDraft", String.valueOf(request.modelVersion()), actor,
                request.reason(), idempotencyKey, () -> idempotentCommand("K4_MODEL_RESTORE_DRAFT", idempotencyKey,
                requestHash(String.valueOf(request.modelVersion()), String.valueOf(request.expectedVersion()), request.reason()), () -> {
                    RiskScoringModelDraftRequest restored = new RiskScoringModelDraftRequest(
                            request.expectedVersion(), historical.weights(), historical.inputSources(), historical.scoreMappings(),
                            historical.bandLowMax(), historical.bandHighMin(), historical.autoEscalateScore(),
                            request.reason(), actor);
                    RiskScoreModelView saved = riskRepository.saveScoringModelDraft(
                            request.expectedVersion(), restored, actor).orElse(null);
                    if (saved == null) {
                        return rejectK4Write("restoreDraft", String.valueOf(request.modelVersion()), actor, 409,
                                "K4_MODEL_CONCURRENT_UPDATE", request.reason(), idempotencyKey);
                    }
                    auditRequired("K4_MODEL_HISTORY_RESTORED_TO_DRAFT", "RISK_SCORE_MODEL",
                            String.valueOf(saved.version()), actor,
                            k4AuditDetail(historical, saved, request.reason(), idempotencyKey));
                    return scoringOverview();
                }));
    }

    @Transactional
    public ApiResult<Map<String, Object>> recomputeScores(
            String idempotencyKey, RiskScoreBatchCommandRequest request) {
        String actor = authenticatedK4Operator();
        ApiResult<Map<String, Object>> guard = requireK4Command(
                idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectK4Write("batchRecompute", "batch", actor, guard.getCode(), guard.getMessage(),
                    request == null ? null : request.reason(), idempotencyKey);
        }
        if (request.expectedModelVersion() == null || request.expectedModelVersion() < 1) {
            return rejectK4Write("batchRecompute", "batch", actor, 422,
                    "K4_MODEL_VERSION_REQUIRED", request.reason(), idempotencyKey);
        }
        List<String> requested = request.userNos() == null ? List.of() : request.userNos().stream()
                .map(this::trimmed).filter(StringUtils::hasText).distinct().toList();
        if (requested.size() > 1000) {
            return rejectK4Write("batchRecompute", "batch", actor, 422,
                    "K4_SCORE_BATCH_TOO_LARGE", request.reason(), idempotencyKey);
        }
        List<String> targets = requested.isEmpty() ? riskRepository.scoreUserNos() : requested;
        if (targets.size() > 1000) {
            return rejectK4Write("batchRecompute", "batch", actor, 422,
                    "K4_SCORE_BATCH_TOO_LARGE", request.reason(), idempotencyKey);
        }
        return executeK4Write("batchRecompute", "batch", actor, request.reason(), idempotencyKey, () ->
                idempotentCommand("K4_SCORE_BATCH_RECOMPUTE", idempotencyKey,
                requestHash(String.join(",", targets), String.valueOf(request.expectedModelVersion()), request.reason()), () -> {
                    RiskScoreModelView model = riskRepository.activeScoringModel().orElse(null);
                    if (model == null) {
                        return rejectK4Write("batchRecompute", "batch", actor, 409,
                                "K4_ACTIVE_MODEL_REQUIRED", request.reason(), idempotencyKey);
                    }
                    if (!java.util.Objects.equals(model.version(), request.expectedModelVersion())) {
                        return rejectK4Write("batchRecompute", "batch", actor, 409,
                                "K4_MODEL_VERSION_CONFLICT", request.reason(), idempotencyKey);
                    }
                    List<Map<String, Object>> results = new java.util.ArrayList<>();
                    for (String userNo : targets) {
                        RiskScoreUserView current = riskRepository.findScoreUser(userNo).orElse(null);
                        RiskScoreRawInput input = riskRepository.scoringInput(userNo).orElse(null);
                        if (current == null || input == null) {
                            throw new BizException(404, "SCORE_USER_NOT_FOUND:" + userNo);
                        }
                        K4RiskScorer.ScoreResult score = k4RiskScorer.score(input, model);
                        RiskScoreUserView updated = riskRepository.recomputeScore(
                                userNo, current.rowVersion(), model, score.score(), score.contributions()).orElse(null);
                        if (updated == null) throw new BizException(409, "K4_SCORE_CONCURRENT_UPDATE:" + userNo);
                        K4ScoreEventPublisher.publishScoreUpdated(eventOutboxService, current, updated);
                        k4KycReviewTriggerService.triggerIfThresholdReached(
                                current,
                                updated,
                                K4KycReviewTriggerService.SOURCE_BATCH_RECOMPUTE,
                                request.reason().trim(),
                                actor,
                                idempotencyKey.trim() + ":" + userNo);
                        results.add(Map.of("userNo", userNo, "before", current.effectiveScore(),
                                "after", updated.effectiveScore(), "modelVersion", model.version()));
                    }
                    auditRequired("K4_SCORE_BATCH_RECOMPUTED", "RISK_SCORE_USER_BATCH", "batch", actor, Map.of(
                            "users", results,
                            "count", results.size(),
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
                    return ApiResult.ok(Map.of("count", results.size(), "users", results));
                }));
    }

    @Transactional
    public ApiResult<RiskScoreUserView> overrideScore(String userNo, String idempotencyKey, RiskScoreOverrideRequest request) {
        String actor = authenticatedK4Operator();
        String normalized = trimmed(userNo);
        ApiResult<RiskScoreUserView> guard = requireK4Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectK4Write("override", normalized, actor, guard.getCode(), guard.getMessage(),
                    request == null ? null : request.reason(), idempotencyKey);
        }
        Integer score = request.score();
        if (!StringUtils.hasText(normalized) || request.expectedVersion() == null
                || score == null || score < 0 || score > 100) {
            return rejectK4Write("override", normalized, actor, OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                    "SCORE_OVERRIDE_INVALID", request.reason(), idempotencyKey);
        }
        return executeK4Write("override", normalized, actor, request.reason(), idempotencyKey, () ->
                idempotentCommand("K4_SCORE_OVERRIDE:" + normalized, idempotencyKey,
                requestHash(normalized, String.valueOf(request.expectedVersion()), String.valueOf(score), request.reason()), () -> {
                    if (!A2ReplayContext.isReplaying()
                            && lockMapper.countActiveByTarget("K", "score_user", normalized) > 0) {
                        return rejectK4Write("override", normalized, actor, 409,
                                "OBJECT_LOCKED_BY_A2", request.reason(), idempotencyKey);
                    }
                    RiskScoreUserView before = riskRepository.findScoreUser(normalized).orElse(null);
                    if (before == null) {
                        return rejectK4Write("override", normalized, actor, 404,
                                "SCORE_USER_NOT_FOUND", request.reason(), idempotencyKey);
                    }
                    RiskScoreOverrideView updated = riskRepository.overrideScore(
                            normalized, request.expectedVersion(), score, request.reason().trim(), actor).orElse(null);
                    if (updated == null) {
                        return rejectK4Write("override", normalized, actor, 409,
                                "K4_SCORE_CONCURRENT_UPDATE", request.reason(), idempotencyKey);
                    }
                    RiskScoreUserView scoreUser = riskRepository.findScoreUser(normalized).orElseThrow();
                    auditRequired("K4_SCORE_OVERRIDDEN", "RISK_SCORE_USER", normalized, actor,
                            k4AuditDetail(before, scoreUser, request.reason(), idempotencyKey), "MEDIUM");
                    K4ScoreEventPublisher.publishScoreOverridden(
                            eventOutboxService, scoreUser, request.reason().trim(), actor);
                    k4KycReviewTriggerService.triggerIfThresholdReached(
                            before,
                            scoreUser,
                            K4KycReviewTriggerService.SOURCE_SCORE_OVERRIDE,
                            request.reason().trim(),
                            actor,
                            idempotencyKey.trim());
                    return ApiResult.ok(scoreUser);
                }));
    }

    @Transactional
    public ApiResult<RiskScoreUserView> recomputeScore(String userNo, String idempotencyKey, RiskScoreCommandRequest request) {
        String actor = authenticatedK4Operator();
        String normalized = trimmed(userNo);
        ApiResult<RiskScoreUserView> guard = requireK4Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return rejectK4Write("recompute", normalized, actor, guard.getCode(), guard.getMessage(),
                    request == null ? null : request.reason(), idempotencyKey);
        }
        if (!StringUtils.hasText(normalized) || request.expectedVersion() == null) {
            return rejectK4Write("recompute", normalized, actor, OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                    "SCORE_USER_REQUIRED", request.reason(), idempotencyKey);
        }
        return executeK4Write("recompute", normalized, actor, request.reason(), idempotencyKey, () ->
                idempotentCommand("K4_SCORE_RECOMPUTE:" + normalized, idempotencyKey,
                requestHash(normalized, String.valueOf(request.expectedVersion()), request.reason()), () -> {
                    if (!A2ReplayContext.isReplaying()
                            && lockMapper.countActiveByTarget("K", "score_user", normalized) > 0) {
                        return rejectK4Write("recompute", normalized, actor, 409,
                                "OBJECT_LOCKED_BY_A2", request.reason(), idempotencyKey);
                    }
                    RiskScoreUserView before = riskRepository.findScoreUser(normalized).orElse(null);
                    RiskScoreRawInput input = riskRepository.scoringInput(normalized).orElse(null);
                    RiskScoreModelView model = riskRepository.activeScoringModel().orElse(null);
                    if (before == null || input == null) {
                        return rejectK4Write("recompute", normalized, actor, 404,
                                "SCORE_USER_NOT_FOUND", request.reason(), idempotencyKey);
                    }
                    if (model == null) {
                        return rejectK4Write("recompute", normalized, actor, 409,
                                "K4_ACTIVE_MODEL_REQUIRED", request.reason(), idempotencyKey);
                    }
                    K4RiskScorer.ScoreResult result = k4RiskScorer.score(input, model);
                    RiskScoreUserView updated = riskRepository.recomputeScore(
                            normalized, request.expectedVersion(), model, result.score(), result.contributions()).orElse(null);
                    if (updated == null) {
                        return rejectK4Write("recompute", normalized, actor, 409,
                                "K4_SCORE_CONCURRENT_UPDATE", request.reason(), idempotencyKey);
                    }
                    auditRequired("K4_SCORE_RECOMPUTED", "RISK_SCORE_USER", normalized, actor,
                            k4AuditDetail(before, updated, request.reason(), idempotencyKey));
                    K4ScoreEventPublisher.publishScoreUpdated(eventOutboxService, before, updated);
                    k4KycReviewTriggerService.triggerIfThresholdReached(
                            before,
                            updated,
                            K4KycReviewTriggerService.SOURCE_SCORE_RECOMPUTE,
                            request.reason().trim(),
                            actor,
                            idempotencyKey.trim());
                    return ApiResult.ok(updated);
                }));
    }

    public ApiResult<Map<String, Object>> kycReviewOverview() {
        return kycReviewOverview(null);
    }

    public ApiResult<Map<String, Object>> kycReviewOverview(RiskKycReviewOverviewQueryRequest request) {
        int ticketPageNum = normalizePageNum(request == null ? null : request.ticketPageNum());
        int ticketPageSize = normalizeLimit(request == null ? null : request.ticketPageSize(), 5, 50);
        String ticketFilter = normalizeKycTicketFilter(request == null ? null : request.ticketFilter());
        Map<String, Object> overview = new LinkedHashMap<>(riskRepository.kycReviewOverview(ticketPageNum, ticketPageSize, ticketFilter));
        Map<String, Object> subscription = riskRepository.kycAlertSubscription(authenticatedOperator());
        overview.put("subscription", subscription);
        overview.put("alerts", riskRepository.kycAlerts(stringList(subscription.get("alertTypes"))));
        return ApiResult.ok(overview);
    }

    public ApiResult<List<Map<String, Object>>> kycReviewUsers(String keyword, Integer limit) {
        int normalizedLimit = normalizeLimit(limit, 20, 50);
        return ApiResult.ok(userKycStatusFacade.reviewCandidates(keyword, normalizedLimit));
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateKycReviewParam(
            String key, String idempotencyKey, RiskKycReviewParamUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<Map<String, Object>> reasonGuard = requireK5Reason(request.reason());
        if (reasonGuard != null) return reasonGuard;
        ApiResult<Map<String, Object>> permission = requireK5Authority("risk_k5_write");
        if (permission != null) return permission;
        String normalizedKey = trimmed(key);
        String value = trimmed(request.value());
        if (!StringUtils.hasText(normalizedKey) || !StringUtils.hasText(value) || request.expectedVersion() == null
                || request.expectedVersion() < 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K5_PARAM_VALUE_REQUIRED");
        }
        value = normalizeK5Param(normalizedKey, value);
        if (!StringUtils.hasText(value)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K5_PARAM_VALUE_INVALID");
        }
        String actor = authenticatedOperator();
        String normalizedValue = value;
        return idempotentCommand("K5_PARAM:" + normalizedKey, idempotencyKey,
                requestHash(normalizedKey, normalizedValue, String.valueOf(request.expectedVersion()), request.reason()), () -> {
                    Map<String, Object> updated = riskRepository
                            .updateKycReviewParam(normalizedKey, normalizedValue, request.expectedVersion()).orElse(null);
                    if (updated == null) return ApiResult.fail(409, "K5_PARAM_VERSION_CONFLICT");
                    int scoreTriggeredTickets = "reviewTriggerScore".equals(normalizedKey)
                            ? synchronizeK4KycReviewTriggerThreshold(
                                    request.reason().trim(), actor, idempotencyKey.trim())
                            : 0;
                    updated.put("subscription", riskRepository.kycAlertSubscription(actor));
                    updated.put("scoreTriggeredTickets", scoreTriggeredTickets);
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("key", normalizedKey);
                    detail.put("value", normalizedValue);
                    detail.put("expectedVersion", request.expectedVersion());
                    detail.put("reason", request.reason().trim());
                    detail.put("idempotencyKey", idempotencyKey.trim());
                    detail.put("slaRecomputedTickets", updated.getOrDefault("slaRecomputedTickets", 0));
                    detail.put("scoreTriggeredTickets", scoreTriggeredTickets);
                    auditRequired("K5_KYC_REVIEW_PARAM_CHANGED", "RISK_KYC_REVIEW_PARAM", normalizedKey, actor, detail);
                    return ApiResult.ok(updated);
                });
    }

    private int synchronizeK4KycReviewTriggerThreshold(
            String reason, String operator, String idempotencyKey) {
        int threshold = riskRepository.kycReviewTriggerScore();
        int triggered = 0;
        while (true) {
            List<String> userNos = riskRepository.scoreUserNosNeedingKycTriggerThresholdSync(
                    threshold, K4ScoreBackfillInitializer.CHUNK_SIZE);
            if (userNos.isEmpty()) {
                return triggered;
            }
            for (String userNo : userNos) {
                RiskScoreUserView current = riskRepository.findCurrentScoreUser(userNo)
                        .orElseThrow(() -> new BizException(409, "K4_CURRENT_SCORE_REQUIRED_FOR_K5_THRESHOLD_SYNC"));
                if (k4KycReviewTriggerService.triggerIfThresholdReached(
                        null,
                        current,
                        K4KycReviewTriggerService.SOURCE_REVIEW_THRESHOLD_CHANGE,
                        reason,
                        operator,
                        idempotencyKey + ":threshold:" + userNo)) {
                    triggered++;
                }
            }
        }
    }

    @Transactional
    public ApiResult<Map<String, Object>> decideKycReviewTicket(String ticketId, String idempotencyKey, RiskKycReviewDecisionRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<Map<String, Object>> reasonGuard = requireK5Reason(request.reason());
        if (reasonGuard != null) return reasonGuard;
        String normalizedTicket = trimmed(ticketId);
        String decision = trimmed(request.decision()).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(normalizedTicket) || !KYC_REVIEW_DECISIONS.contains(decision)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K5_REVIEW_DECISION_INVALID");
        }
        if ("rejected".equals(decision)
                && (!StringUtils.hasText(request.reasonCode())
                || !KYC_REJECT_REASON_CODES.contains(request.reasonCode().trim()))) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K5_REVIEW_REJECT_REASON_CODE_INVALID");
        }
        String actor = authenticatedOperator();
        ApiResult<Map<String, Object>> permission = requireK5Authority(
                "passed".equals(decision) ? "risk_k5_ticket_pass" : "risk_k5_ticket_reject");
        if (permission != null) {
            return rejectK5Decision(normalizedTicket, actor, permission.getCode(), permission.getMessage(),
                    request.reason(), idempotencyKey);
        }
        Long requestedVersion = request.expectedVersion();
        String reasonCode = StringUtils.hasText(request.reasonCode())
                ? request.reasonCode().trim() : "KYC_REVIEW_PASSED";
        String hash = requestHash(normalizedTicket, decision, String.valueOf(requestedVersion), reasonCode, request.reason());
        return executeK5Decision(normalizedTicket, actor, request.reason(), idempotencyKey, () ->
                idempotentCommand("K5_DECISION:" + normalizedTicket, idempotencyKey, hash, () -> {
                    KycReviewTicketContext ticket = riskRepository.findKycReviewTicket(normalizedTicket).orElse(null);
                    if (ticket == null) {
                        return rejectK5Decision(normalizedTicket, actor, 404, "K5_REVIEW_TICKET_NOT_FOUND",
                                request.reason(), idempotencyKey);
                    }
                    if (!A2ReplayContext.isReplaying()
                            && lockMapper.countActiveByTarget("K", "ticket", normalizedTicket) > 0) {
                        return rejectK5Decision(normalizedTicket, actor, 409, "OBJECT_LOCKED_BY_A2",
                                request.reason(), idempotencyKey);
                    }
                    if (!"in-review".equals(ticket.status())) {
                        return rejectK5Decision(normalizedTicket, actor, 409, "K5_REVIEW_TICKET_NOT_REVIEWABLE",
                                request.reason(), idempotencyKey);
                    }
                    Long expectedVersion = requestedVersion;
                    if (expectedVersion == null && A2ReplayContext.isReplaying()) expectedVersion = ticket.version();
                    if (expectedVersion == null || expectedVersion < 0 || ticket.version() != expectedVersion) {
                        return rejectK5Decision(normalizedTicket, actor, 409, "K5_REVIEW_TICKET_VERSION_CONFLICT",
                                request.reason(), idempotencyKey);
                    }
                    if (!userKycStatusFacade.userExists(ticket.userNo())) {
                        return rejectK5Decision(normalizedTicket, actor, 404, "K5_REVIEW_USER_NOT_FOUND",
                                request.reason(), idempotencyKey);
                    }
                    long version = expectedVersion;
                    if (!riskRepository.updateKycReviewTicketStatus(normalizedTicket, decision, version,
                            reasonCode, request.reason().trim(), actor)) {
                        return rejectK5Decision(normalizedTicket, actor, 409, "K5_REVIEW_TICKET_VERSION_CONFLICT",
                                request.reason(), idempotencyKey);
                    }
                    Map<String, Object> downstream = applyKycReviewDecision(ticket, decision, request.reason().trim(), actor);
                    Map<String, Object> detail = new LinkedHashMap<>();
                    detail.put("ticketId", normalizedTicket);
                    detail.put("userNo", ticket.userNo());
                    detail.put("decision", decision);
                    detail.put("reasonCode", reasonCode);
                    detail.put("expectedVersion", version);
                    detail.put("reason", request.reason().trim());
                    detail.put("idempotencyKey", idempotencyKey.trim());
                    detail.put("downstream", downstream);
                    auditRequired("K5_KYC_REVIEW_DECIDED", "RISK_KYC_REVIEW_TICKET", normalizedTicket, actor, detail);
                    return kycReviewOverview();
                }));
    }

    @Transactional
    public ApiResult<Map<String, Object>> createManualKycReviewTicket(String idempotencyKey, RiskKycManualReviewRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<Map<String, Object>> reasonGuard = requireK5Reason(request.reason());
        if (reasonGuard != null) return reasonGuard;
        String userNo = trimmed(request.userNo());
        if (!StringUtils.hasText(userNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K5_REVIEW_USER_REQUIRED");
        }
        ApiResult<Map<String, Object>> permission = requireK5Authority("risk_k5_ticket_manual");
        if (permission != null) return permission;
        if (!userKycStatusFacade.userExists(userNo)) {
            return ApiResult.fail(404, "K5_REVIEW_USER_NOT_FOUND");
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("K", "user", userNo) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String actor = authenticatedOperator();
        return idempotentCommand("K5_MANUAL:" + userNo, idempotencyKey,
                requestHash(userNo, request.reason()), () -> {
                    KycReviewTicketContext open = riskRepository.findOpenKycReviewTicketByUserForUpdate(userNo).orElse(null);
                    String ticketId;
                    String action;
                    boolean merged;
                    if (open != null) {
                        if (!riskRepository.mergeOpenKycReviewTicket(open.ticketId(), open.version(), request.reason().trim(), actor)) {
                            return ApiResult.fail(409, "K5_REVIEW_TICKET_VERSION_CONFLICT");
                        }
                        ticketId = open.ticketId();
                        action = "K5_KYC_REVIEW_MANUAL_MERGED";
                        merged = true;
                    } else {
                        ticketId = "KR-M-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
                        try {
                            riskRepository.createManualKycReviewTicket(ticketId, userNo, request.reason().trim(), actor);
                            action = "K5_KYC_REVIEW_MANUAL_CREATED";
                            merged = false;
                        } catch (DuplicateKeyException race) {
                            KycReviewTicketContext winner = riskRepository.findOpenKycReviewTicketByUserForUpdate(userNo).orElse(null);
                            if (winner == null || !riskRepository.mergeOpenKycReviewTicket(
                                    winner.ticketId(), winner.version(), request.reason().trim(), actor)) {
                                return ApiResult.fail(409, "K5_REVIEW_TICKET_CONCURRENT_TRIGGER");
                            }
                            ticketId = winner.ticketId();
                            action = "K5_KYC_REVIEW_MANUAL_MERGED";
                            merged = true;
                        }
                    }
                    auditRequired(action, "RISK_KYC_REVIEW_TICKET", ticketId, actor, Map.of(
                            "ticketId", ticketId, "userNo", userNo, "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
                    ApiResult<Map<String, Object>> result = kycReviewOverview();
                    result.getData().put("manualResult", Map.of(
                            "ticketId", ticketId,
                            "userNo", userNo,
                            "merged", merged));
                    return result;
                });
    }

    @Transactional
    public ApiResult<Map<String, Object>> updateKycAlertSubscription(
            String idempotencyKey, RiskKycAlertSubscriptionRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) return guard;
        ApiResult<Map<String, Object>> reasonGuard = requireK5Reason(request.reason());
        if (reasonGuard != null) return reasonGuard;
        ApiResult<Map<String, Object>> permission = requireK5Authority("risk_k5_write");
        if (permission != null) return permission;
        if (request.expectedVersion() == null || request.expectedVersion() < 0
                || request.alertTypes() == null || request.channels() == null
                || request.alertTypes().isEmpty() || request.channels().isEmpty()
                || !Set.of("sla-breach", "threshold-hit", "large-withdraw-burst").containsAll(request.alertTypes())
                || !Set.of("in-app").containsAll(request.channels())) {
            return ApiResult.fail(422, "K5_ALERT_SUBSCRIPTION_INVALID");
        }
        List<String> alertTypes = request.alertTypes().stream().distinct().sorted().toList();
        List<String> channels = request.channels().stream().distinct().sorted().toList();
        String actor = authenticatedOperator();
        return idempotentCommand("K5_ALERT_SUBSCRIPTION:" + actor, idempotencyKey,
                requestHash(String.join(",", alertTypes), String.join(",", channels),
                        String.valueOf(request.expectedVersion()), request.reason()), () -> {
                    Map<String, Object> subscription = riskRepository.updateKycAlertSubscription(
                            actor, alertTypes, channels, request.expectedVersion()).orElse(null);
                    if (subscription == null) return ApiResult.fail(409, "K5_ALERT_SUBSCRIPTION_VERSION_CONFLICT");
                    auditRequired("K5_ALERT_SUBSCRIPTION_CHANGED", "RISK_KYC_ALERT_SUBSCRIPTION", actor, actor, Map.of(
                            "alertTypes", alertTypes, "channels", channels, "expectedVersion", request.expectedVersion(),
                            "reason", request.reason().trim(), "idempotencyKey", idempotencyKey.trim()));
                    return kycReviewOverview();
                });
    }

    private Map<String, Object> applyKycReviewDecision(KycReviewTicketContext ticket, String decision, String reason, String operator) {
        Map<String, Object> downstream = new LinkedHashMap<>();
        String kycStatus = "passed".equals(decision) ? "APPROVED" : "REJECTED";
        boolean c4Updated = userKycStatusFacade.updateKycStatusByUserNo(
                ticket.userNo(), kycStatus, reason, operator, ticket.ticketId());
        if (!c4Updated) {
            throw new IllegalStateException("K5_C4_KYC_UPDATE_FAILED");
        }
        downstream.put("c4KycUpdated", true);
        Map<String, RiskOpsRepository.KycReviewSource> uniqueSources = new LinkedHashMap<>();
        for (RiskOpsRepository.KycReviewSource source : riskRepository.kycReviewSources(ticket.ticketId())) {
            addKycReviewSource(uniqueSources, source.sourceDomain(), source.sourceNo());
        }
        // Backward compatibility for tickets created before the normalized source table existed.
        addKycReviewSource(uniqueSources,
                ticketInfoValue(ticket.infoJson(), "sourceDomain"),
                ticketInfoValue(ticket.infoJson(), "sourceNo"));

        List<Map<String, Object>> sourceUpdates = new ArrayList<>();
        for (RiskOpsRepository.KycReviewSource source : uniqueSources.values()) {
            String sourceDomain = source.sourceDomain();
            String sourceNo = source.sourceNo();
            boolean sourceUpdated = false;
            if ("D2".equalsIgnoreCase(sourceDomain)) {
                sourceUpdated = "passed".equals(decision)
                        ? financeWithdrawalKycReviewFacade.releaseWithdrawalReview(
                                sourceNo, ticket.ticketId(), reason, operator)
                        : financeWithdrawalKycReviewFacade.rejectWithdrawalReview(
                                sourceNo, ticket.ticketId(), reason, operator);
            } else if ("G2".equalsIgnoreCase(sourceDomain)) {
                sourceUpdated = "passed".equals(decision)
                        ? marketExchangeKycReviewFacade.releaseExchangeReview(sourceNo, reason, operator)
                        : marketExchangeKycReviewFacade.rejectExchangeReview(sourceNo, reason, operator);
            }
            if (("D2".equalsIgnoreCase(sourceDomain) || "G2".equalsIgnoreCase(sourceDomain)) && !sourceUpdated) {
                throw new IllegalStateException("K5_SOURCE_STATE_UPDATE_FAILED");
            }
            sourceUpdates.add(Map.of(
                    "sourceDomain", sourceDomain,
                    "sourceNo", sourceNo,
                    "updated", sourceUpdated));
        }
        downstream.put("sourceUpdates", sourceUpdates);
        downstream.put("sourceUpdatedCount", sourceUpdates.stream()
                .filter(update -> Boolean.TRUE.equals(update.get("updated"))).count());
        downstream.put("sourceUpdated", sourceUpdates.stream()
                .anyMatch(update -> Boolean.TRUE.equals(update.get("updated"))));
        if (sourceUpdates.size() == 1) {
            downstream.put("sourceDomain", sourceUpdates.get(0).get("sourceDomain"));
            downstream.put("sourceNo", sourceUpdates.get(0).get("sourceNo"));
        }
        return downstream;
    }

    private void addKycReviewSource(
            Map<String, RiskOpsRepository.KycReviewSource> sources, String sourceDomain, String sourceNo) {
        if (!StringUtils.hasText(sourceDomain) || !StringUtils.hasText(sourceNo)) return;
        String domain = sourceDomain.trim().toUpperCase(Locale.ROOT);
        String number = sourceNo.trim();
        sources.putIfAbsent(domain + ":" + number, new RiskOpsRepository.KycReviewSource(domain, number));
    }

    private String ticketInfoValue(String infoJson, String key) {
        if (!StringUtils.hasText(infoJson) || !StringUtils.hasText(key)) {
            return null;
        }
        Pattern pattern = Pattern.compile("\\[\\s*\"" + Pattern.quote(key) + "\"\\s*,\\s*\"([^\"]*)\"");
        Matcher matcher = pattern.matcher(infoJson);
        return matcher.find() ? matcher.group(1) : null;
    }

    private <T> ApiResult<T> requireCommand(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        if (!StringUtils.hasText(reason) || reason.trim().length() < 6) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private <T> ApiResult<T> requireK5Reason(String reason) {
        int length = reason == null ? 0 : reason.trim().length();
        if (length < 8 || length > 200) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K5_REASON_LENGTH_INVALID");
        }
        return null;
    }

    private <T> ApiResult<T> requireK1Command(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        int length = StringUtils.hasText(reason) ? reason.trim().length() : 0;
        if (length < 8 || length > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private <T> ApiResult<T> requireK2Command(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        int length = StringUtils.hasText(reason) ? reason.trim().length() : 0;
        if (length < 8 || length > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private <T> ApiResult<T> requireK3Command(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        int length = StringUtils.hasText(reason) ? reason.trim().length() : 0;
        if (length < 8 || length > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private <T> ApiResult<T> requireK4Command(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(),
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        int length = StringUtils.hasText(reason) ? reason.trim().length() : 0;
        if (length < 8 || length > 200) {
            return ApiResult.fail(OpsErrorCode.REASON_REQUIRED.httpStatus(), OpsErrorCode.REASON_REQUIRED.name());
        }
        return null;
    }

    private ApiResult<Map<String, Object>> validateK4Draft(
            String idempotencyKey, RiskScoringModelDraftRequest request) {
        ApiResult<Map<String, Object>> guard = requireK4Command(
                idempotencyKey, request == null ? null : request.reason());
        if (guard != null) return guard;
        if (request == null || request.expectedVersion() == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K4_MODEL_VERSION_REQUIRED");
        }
        Map<String, Integer> weightPercentages = request.weightPercentages();
        if (!weightPercentages.keySet().equals(Set.copyOf(K4_DIMENSION_KEYS))
                || weightPercentages.values().stream().anyMatch(value -> value < 0 || value > 100)
                || weightPercentages.values().stream().mapToInt(Integer::intValue).sum() != 100) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K4_MODEL_WEIGHTS_INVALID");
        }
        if (request.inputSources() == null
                || !request.inputSources().keySet().equals(Set.copyOf(K4_DIMENSION_KEYS))
                || request.inputSources().values().stream().anyMatch(java.util.Objects::isNull)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K4_MODEL_SOURCES_INVALID");
        }
        if (!validK4Mappings(request.scoreMappings())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K4_MODEL_MAPPINGS_INVALID");
        }
        if (request.lowMax() == null || request.highMin() == null
                || request.lowMax() < 0 || request.highMin() > 100 || request.lowMax() >= request.highMin()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K4_MODEL_BANDS_INVALID");
        }
        if (!validK4EscalationThreshold(request.highMin(), request.autoEscalateScore())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K4_MODEL_ESCALATE_INVALID");
        }
        return null;
    }

    private String k4DraftHash(RiskScoringModelDraftRequest request) {
        List<String> values = new java.util.ArrayList<>();
        values.add(String.valueOf(request.expectedVersion()));
        Map<String, Integer> weights = request.weightPercentages();
        K4_DIMENSION_KEYS.forEach(key -> values.add(key + "=" + weights.get(key)));
        K4_DIMENSION_KEYS.forEach(key -> values.add(key + "=" + request.inputSources().get(key)));
        K4RiskScorer.DEFAULT_MAPPINGS.keySet().stream().sorted()
                .forEach(key -> values.add(key + "=" + request.scoreMappings().get(key)));
        values.add(String.valueOf(request.lowMax()));
        values.add(String.valueOf(request.highMin()));
        values.add(String.valueOf(request.autoEscalateScore()));
        values.add(request.reason());
        return String.join("|", values);
    }

    private boolean validK4Mappings(Map<String, Integer> mappings) {
        if (mappings == null || !mappings.keySet().equals(K4RiskScorer.DEFAULT_MAPPINGS.keySet())
                || mappings.values().stream().anyMatch(java.util.Objects::isNull)) {
            return false;
        }
        for (Map.Entry<String, Integer> entry : mappings.entrySet()) {
            int value = entry.getValue();
            if (entry.getKey().endsWith("Score") && (value < 0 || value > 100)) return false;
            if ("withdraw.largeAmountUsd".equals(entry.getKey()) && (value < 1 || value > 1_000_000)) return false;
            if ("withdraw.baselineMultiplierPct".equals(entry.getKey()) && (value < 100 || value > 1_000)) return false;
            if (Set.of("multiAccount.mediumMin", "multiAccount.highMin",
                            "withdraw.highFrequency24h").contains(entry.getKey())
                    && (value < 1 || value > 100)) return false;
            if ("arbitrage.repeatMin".equals(entry.getKey()) && (value < 2 || value > 100)) return false;
            if (Set.of("account.newDays", "account.matureDays").contains(entry.getKey())
                    && (value < 1 || value > 10_000)) return false;
        }
        return mappings.get("multiAccount.mediumMin") < mappings.get("multiAccount.highMin")
                && mappings.get("account.newDays") < mappings.get("account.matureDays")
                && nonDecreasing(mappings, "multiAccount.mediumScore", "multiAccount.highScore", "multiAccount.fraudScore")
                && nonDecreasing(mappings, "arbitrage.singleScore", "arbitrage.repeatScore", "arbitrage.severeScore")
                && nonDecreasing(mappings, "kyc.reviewScore", "kyc.pendingScore", "kyc.rejectedScore", "kyc.sanctionedScore")
                && nonDecreasing(mappings, "withdraw.baselineScore", "withdraw.highScore")
                && nonDecreasing(mappings, "account.middleScore", "account.newLargeScore")
                && nonDecreasing(mappings, "anomaly.lowScore", "anomaly.tamperScore");
    }

    private boolean validK4ModelSnapshot(RiskScoreModelView model) {
        return model != null
                && model.weights() != null
                && model.weights().keySet().equals(Set.copyOf(K4_DIMENSION_KEYS))
                && model.weights().values().stream().noneMatch(java.util.Objects::isNull)
                && model.weights().values().stream().allMatch(value -> value >= 0 && value <= 100)
                && model.weights().values().stream().mapToInt(Integer::intValue).sum() == 100
                && model.inputSources() != null
                && model.inputSources().keySet().equals(Set.copyOf(K4_DIMENSION_KEYS))
                && model.inputSources().values().stream().noneMatch(java.util.Objects::isNull)
                && validK4Mappings(model.scoreMappings())
                && model.bandLowMax() != null && model.bandHighMin() != null
                && model.bandLowMax() >= 0 && model.bandHighMin() <= 100
                && model.bandLowMax() < model.bandHighMin()
                && validK4EscalationThreshold(model.bandHighMin(), model.autoEscalateScore());
    }

    private boolean validK4EscalationThreshold(Integer bandHighMin, Integer autoEscalateScore) {
        return bandHighMin != null
                && autoEscalateScore != null
                && autoEscalateScore >= 70
                && autoEscalateScore <= 100
                && autoEscalateScore >= bandHighMin;
    }

    private boolean nonDecreasing(Map<String, Integer> mappings, String... keys) {
        for (int index = 1; index < keys.length; index++) {
            if (mappings.get(keys[index - 1]) > mappings.get(keys[index])) return false;
        }
        return true;
    }

    private String authenticatedK4Operator() {
        return authenticatedK3Operator();
    }

    private RiskScoreUserView withK4ScoreHistory(RiskScoreUserView user) {
        return new RiskScoreUserView(
                user.userNo(), user.modelScore(), user.effectiveScore(), user.overridden(),
                user.bandLabel(), user.bandTone(), user.modelVersion(), user.rowVersion(),
                user.asOf(), user.updatedText(), user.contributions(),
                riskRepository.scoreHistory(user.userNo(), 20));
    }

    private void recomputeK4Scores(
            RiskScoreModelView model,
            List<String> scoreUsers,
            String reason,
            String operator,
            String idempotencyKey) {
        for (String userNo : scoreUsers) {
            RiskScoreUserView current = riskRepository.findScoreUser(userNo)
                    .orElseThrow(() -> new BizException(409, "K4_SCORE_USER_MISSING_DURING_PUBLISH"));
            RiskScoreRawInput input = riskRepository.scoringInput(userNo)
                    .orElseThrow(() -> new BizException(409, "K4_SCORE_INPUT_MISSING_DURING_PUBLISH"));
            K4RiskScorer.ScoreResult score = k4RiskScorer.score(input, model);
            RiskScoreUserView updated = riskRepository.refreshScoreProjection(
                    userNo, current.rowVersion(), model, score.score(), score.contributions()).orElse(null);
            if (updated == null) {
                throw new BizException(409, "K4_SCORE_CONCURRENT_UPDATE_DURING_PUBLISH");
            }
            K4ScoreEventPublisher.publishScoreUpdated(eventOutboxService, current, updated);
            k4KycReviewTriggerService.triggerIfThresholdReached(
                    current,
                    updated,
                    K4KycReviewTriggerService.SOURCE_MODEL_PUBLISH,
                    reason,
                    operator,
                    idempotencyKey + ":" + userNo);
        }
    }

    private String normalizeK1Param(String key, String rawValue) {
        String value = trimmed(rawValue);
        if ("linkWeight".equals(key)) {
            return normalizeLinkWeight(value);
        }
        int[] range = K1_INTEGER_PARAMS.get(key);
        if (range != null) {
            if (!value.matches("\\d+")) {
                return null;
            }
            try {
                int parsed = Integer.parseInt(value);
                return parsed >= range[0] && parsed <= range[1] ? String.valueOf(parsed) : null;
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        if (K1_DECIMAL_PARAMS.contains(key)) {
            try {
                BigDecimal parsed = new BigDecimal(value);
                if (parsed.compareTo(BigDecimal.ZERO) < 0 || parsed.compareTo(BigDecimal.ONE) > 0) {
                    return null;
                }
                return parsed.stripTrailingZeros().toPlainString();
            } catch (NumberFormatException ex) {
                return null;
            }
        }
        return null;
    }

    private ApiResult<Map<String, Object>> requireK1StatusAuthority(String targetStatus) {
        if (A2ReplayContext.isReplaying()) {
            return null;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String required = K1_STATUS_AUTHORITIES.get(targetStatus);
        boolean allowed = required != null && authentication.getAuthorities().stream()
                .anyMatch(authority -> required.equals(authority.getAuthority()));
        return allowed ? null : ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), OpsErrorCode.FORBIDDEN.name());
    }

    private ApiResult<Map<String, Object>> requireK1Authority(String required) {
        if (A2ReplayContext.isReplaying()) return null;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return null;
        return authentication.getAuthorities().stream().anyMatch(authority -> required.equals(authority.getAuthority()))
                ? null : ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), OpsErrorCode.FORBIDDEN.name());
    }

    private <T> ApiResult<T> requireK2ActionAuthority(String required) {
        if (A2ReplayContext.isReplaying()) return null;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), OpsErrorCode.FORBIDDEN.name());
        }
        return authentication.getAuthorities().stream().anyMatch(authority -> required.equals(authority.getAuthority()))
                ? null : ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), OpsErrorCode.FORBIDDEN.name());
    }

    private <T> ApiResult<T> requireK5Authority(String required) {
        return A2ReplayContext.isReplaying() ? null : requireK2ActionAuthority(required);
    }

    private <T> ApiResult<T> requireK3Authority(String required) {
        if (A2ReplayContext.isReplaying()) return null;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), OpsErrorCode.FORBIDDEN.name());
        }
        return authentication.getAuthorities().stream().anyMatch(authority -> required.equals(authority.getAuthority()))
                ? null : ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), OpsErrorCode.FORBIDDEN.name());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private ApiResult<Map<String, Object>> idempotentK1(
            String scope, String idempotencyKey, String requestHash,
            java.util.function.Supplier<ApiResult<Map<String, Object>>> action) {
        return idempotentCommand(scope, idempotencyKey, requestHash, action);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private <T> ApiResult<T> idempotentCommand(
            String scope, String idempotencyKey, String requestHash,
            java.util.function.Supplier<ApiResult<T>> action) {
        return (ApiResult<T>) idempotencyService.execute(
                scope, idempotencyKey.trim(), requestHash, ApiResult.class, (java.util.function.Supplier) action);
    }

    private String requestHash(String... values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                digest.update((value == null ? "<null>" : value).getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof java.util.Collection<?> values)) return List.of();
        return values.stream().filter(java.util.Objects::nonNull).map(String::valueOf).toList();
    }

    private String normalizeIpv4Cidr(String value) {
        String[] parts = trimmed(value).split("/", -1);
        if (parts.length != 2) {
            return null;
        }
        int prefix;
        try {
            prefix = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ex) {
            return null;
        }
        if (prefix < 0 || prefix > 32) {
            return null;
        }
        String[] octets = parts[0].split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        long address = 0;
        for (String octet : octets) {
            if (!octet.matches("0|[1-9]\\d{0,2}")) {
                return null;
            }
            int parsed = Integer.parseInt(octet);
            if (parsed > 255) return null;
            address = (address << 8) | parsed;
        }
        long mask = prefix == 0 ? 0L : (0xffffffffL << (32 - prefix)) & 0xffffffffL;
        long network = address & mask;
        return ((network >>> 24) & 255) + "." + ((network >>> 16) & 255) + "."
                + ((network >>> 8) & 255) + "." + (network & 255) + "/" + prefix;
    }

    private boolean futureDate(String value) {
        try {
            return LocalDate.parse(value).isAfter(LocalDate.now());
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    private String authenticatedOperator() {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.isAuthenticated() && StringUtils.hasText(authentication.getName())
                ? authentication.getName().trim()
                : "system";
    }

    private String authenticatedK3Operator() {
        String actor = AdminActorResolver.resolve("system");
        return StringUtils.hasText(actor) ? actor : "system";
    }

    private boolean isFinal(RiskCaseView riskCase) {
        return "FINALIZED".equalsIgnoreCase(riskCase.status())
                || FINAL_DECISIONS.contains(normalizeText(riskCase.decision()));
    }

    private String normalizeDecision(String decision) {
        String normalized = requireText(decision, "RISK_DECISION_REQUIRED").toUpperCase(Locale.ROOT);
        if (!MANUAL_DECISIONS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported K manual risk decision");
        }
        return normalized;
    }

    private String normalizeSeverity(String severity) {
        String normalized = requireText(severity, "SEVERITY_REQUIRED").toUpperCase(Locale.ROOT);
        if (!SEVERITIES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported K risk severity");
        }
        return normalized;
    }

    private String normalizeRuleAction(String action) {
        String normalized = trimmed(action).toLowerCase(Locale.ROOT);
        return RULE_ACTIONS.contains(normalized) ? normalized : null;
    }

    private String normalizeK3ConfigurableAction(String action) {
        String normalized = trimmed(action).toLowerCase(Locale.ROOT);
        return K3_CONFIGURABLE_ACTIONS.contains(normalized) ? normalized : null;
    }

    private Integer normalizeK3Priority(Integer priority) {
        return priority != null && priority >= 1 && priority <= 100 ? priority : null;
    }

    private boolean k3PriorityConflicts(String excludedRuleId, String dimension, int priority) {
        String canonicalDimension = dimensionMeta(dimension).ruleKey();
        return riskRepository.withdrawRules().stream()
                .filter(rule -> excludedRuleId == null || !excludedRuleId.equals(rule.ruleId()))
                .filter(rule -> !"archived".equalsIgnoreCase(trimmed(rule.state())))
                .filter(rule -> canonicalDimension.equals(dimensionMeta(rule.dimension()).ruleKey()))
                .anyMatch(rule -> rule.priority() != null && rule.priority() == priority);
    }

    private String normalizeRuleState(String state) {
        String normalized = trimmed(state).toLowerCase(Locale.ROOT);
        return RULE_STATES.contains(normalized) ? normalized : null;
    }

    private String sanitizeEvidence(String evidence) {
        if (!StringUtils.hasText(evidence)) {
            return "{}";
        }
        return evidence.length() > 4000 ? evidence.substring(0, 4000) : evidence.trim();
    }

    private int normalizeLimit(Integer limit, int fallback, int max) {
        int value = limit == null ? fallback : limit;
        if (value < 1) {
            return fallback;
        }
        return Math.min(value, max);
    }

    private int normalizePageNum(Integer pageNum) {
        return pageNum == null || pageNum < 1 ? 1 : pageNum;
    }

    private boolean validRuleActionFilter(String action) {
        return !StringUtils.hasText(action) || "all".equalsIgnoreCase(action) || RULE_ACTIONS.contains(action.trim().toLowerCase(Locale.ROOT));
    }

    private String normalizeClusterLayer(String layer) {
        String normalized = trimmed(layer).toLowerCase(Locale.ROOT);
        return CLUSTER_LAYERS.contains(normalized) ? normalized : null;
    }

    private String normalizeKycTicketFilter(String filter) {
        String normalized = trimmed(filter);
        return K5_TICKET_FILTERS.contains(normalized) && !"all".equals(normalized) ? normalized : null;
    }

    private String normalizeLinkWeight(String value) {
        Double device = parseNamedWeight(value, "设备");
        Double payment = parseNamedWeight(value, "支付");
        Double ip = parseNamedWeight(value, "IP");
        if (device == null || payment == null || ip == null) {
            return null;
        }
        if (!validWeight(device) || !validWeight(payment) || !validWeight(ip)) {
            return null;
        }
        if (Math.abs(device + payment + ip - 1.0d) > 0.001d) {
            return null;
        }
        return String.format(Locale.ROOT, "设备 %.2f · 支付 %.2f · IP %.2f", device, payment, ip);
    }

    private Double parseNamedWeight(String value, String label) {
        String text = trimmed(value);
        int labelIndex = text.indexOf(label);
        if (labelIndex < 0) {
            return null;
        }
        String suffix = text.substring(labelIndex + label.length()).trim();
        StringBuilder number = new StringBuilder();
        for (int i = 0; i < suffix.length(); i += 1) {
            char ch = suffix.charAt(i);
            if ((ch >= '0' && ch <= '9') || ch == '.') {
                number.append(ch);
                continue;
            }
            if (!number.isEmpty()) {
                break;
            }
        }
        if (number.isEmpty()) {
            return null;
        }
        try {
            return Double.parseDouble(number.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private boolean validWeight(double value) {
        return value >= 0d && value <= 1d;
    }

    private String normalizeArbitrageParam(String key, String value) {
        return switch (key) {
            case "trialCycleThreshold" -> normalizeTrialCycleThreshold(value);
            case "welcomeGiftAnomalyThreshold" -> normalizeWelcomeGiftThreshold(value);
            case "leaderboardVelocityMultiplier" -> normalizeLeaderboardThreshold(value);
            case "otpGate.resendSeconds" -> normalizeBoundedInteger(value, 30, 300);
            case "otpGate.captchaAfterSends" -> normalizeBoundedInteger(value, 1, 10);
            case "otpGate.otpTtlSeconds" -> normalizeOtpTtlSeconds(value);
            case "otpGate.maxVerifyAttempts" -> normalizeBoundedInteger(value, 1, 10);
            case "otpGate.captchaTicketTtlSeconds" -> normalizeBoundedInteger(value, 30, 600);
            default -> "";
        };
    }

    private String normalizeBoundedInteger(String value, int min, int max) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed >= min && parsed <= max ? String.valueOf(parsed) : "";
        } catch (NumberFormatException ex) {
            return "";
        }
    }

    private String normalizeOtpTtlSeconds(String value) {
        String normalized = normalizeBoundedInteger(value, 60, 900);
        if (!StringUtils.hasText(normalized)) {
            return "";
        }
        int seconds = Integer.parseInt(normalized);
        return seconds % 60 == 0 ? normalized : "";
    }

    private RiskArbitrageParamView withCanonicalOtpValue(RiskArbitrageParamView param) {
        String configKey = OTP_CANONICAL_CONFIG_KEYS.get(param.key());
        if (configKey == null) {
            return param;
        }
        Optional<String> configured = configFacade.activeValue(configKey).filter(StringUtils::hasText);
        if (configured.isEmpty()) {
            return param;
        }
        String value = configured.get();
        if ("otpGate.otpTtlSeconds".equals(param.key())) {
            try {
                value = normalizeBoundedInteger(String.valueOf(Integer.parseInt(value) * 60), 60, 900);
            } catch (NumberFormatException ex) {
                return param;
            }
        }
        return new RiskArbitrageParamView(param.key(), param.name(), value, param.sub(), param.note(), param.version());
    }

    private void persistCanonicalOtpValue(String key, String value) {
        String configKey = OTP_CANONICAL_CONFIG_KEYS.get(key);
        if (configKey == null) {
            return;
        }
        String canonicalValue = "otpGate.otpTtlSeconds".equals(key)
                ? String.valueOf(Integer.parseInt(value) / 60)
                : value;
        configFacade.upsertAdminValue(configKey, canonicalValue, "NUMBER", "auth-risk", "K2 OTP gate canonical configuration");
    }

    private String normalizeWithdrawRuleCondition(String dimension, String value) {
        String condition = stripRuleActionSuffix(trimmed(value)).replace("，", ",");
        return switch (trimmed(dimension)) {
            case "金额", "largeAmountUsdt", "amount" -> normalizeK3AmountRule(condition);
            case "速度", "velocity24h", "velocity" -> normalizeK3VelocityRule(condition);
            case "新账户", "newAccountProtectDays", "accountAge" -> normalizeK3NewAccountRule(condition);
            case "地址信誉", "addressReputationSource", "addressReputation" -> normalizeK3AddressReputationRule(condition);
            default -> "";
        };
    }

    private String stripRuleActionSuffix(String value) {
        int idx = value.indexOf("->");
        if (idx < 0) {
            idx = value.indexOf("→");
        }
        return idx >= 0 ? value.substring(0, idx).trim() : value.trim();
    }

    private String normalizeK3AmountRule(String value) {
        Matcher matcher = K3_AMOUNT_RULE.matcher(value);
        if (!matcher.matches()) {
            return "";
        }
        int amount = parseMoney(matcher.group(2), -1);
        if (amount < 100 || amount > 50_000) {
            return "";
        }
        return "单笔 " + matcher.group(1) + " $" + formatMoney(amount);
    }

    private String normalizeK3VelocityRule(String value) {
        Matcher matcher = K3_VELOCITY_RULE.matcher(value);
        if (!matcher.matches()) {
            return "";
        }
        int count = parseInt(matcher.group(2), -1);
        int amount = parseMoney(matcher.group(4), -1);
        if (count < 1 || count > 20 || amount < 500 || amount > 50_000) {
            return "";
        }
        return "24h " + matcher.group(1) + " " + count + " 笔 或 " + matcher.group(3) + " $" + formatMoney(amount);
    }

    private String normalizeK3NewAccountRule(String value) {
        Matcher matcher = K3_NEW_ACCOUNT_RULE.matcher(value);
        if (!matcher.matches()) {
            return "";
        }
        int days = parseInt(matcher.group(2), -1);
        if (days < 0 || days > 30) {
            return "";
        }
        return "注册 " + matcher.group(1) + " " + days + " 天";
    }

    private String normalizeK3AddressReputationRule(String value) {
        return K3AddressReputationRuleConfig.parse(value)
                .map(K3AddressReputationRuleConfig::canonicalCondition)
                .orElse("");
    }

    private String normalizeK5Param(String key, String value) {
        return switch (key) {
            case "largeWithdrawReviewUsdt" -> normalizeK5AmountLine(value, 100, 50_000);
            case "cumulativeKycThresholdUsdt" -> normalizeK5CumulativeLine(value);
            case "reviewSlaDays" -> normalizeK5SlaDays(value);
            case "reviewTriggerScore" -> normalizeK5ScoreLine(value);
            default -> "";
        };
    }

    private String normalizeK5AmountLine(String value, int min, int max) {
        Matcher matcher = K5_AMOUNT_LINE.matcher(trimmed(value));
        if (!matcher.matches()) {
            return "";
        }
        int amount = parseMoney(matcher.group(2), -1);
        if (amount < min || amount > max) {
            return "";
        }
        return matcher.group(1) + " $" + formatMoney(amount);
    }

    private String normalizeK5CumulativeLine(String value) {
        Matcher matcher = K5_CUMULATIVE_LINE.matcher(trimmed(value));
        if (!matcher.matches()) {
            return "";
        }
        int amount = parseMoney(matcher.group(1), -1);
        if (amount < 50 || amount > 1_000) {
            return "";
        }
        return "$" + formatMoney(amount);
    }

    private String normalizeK5SlaDays(String value) {
        Matcher matcher = K5_SLA_DAYS.matcher(trimmed(value));
        if (!matcher.matches()) {
            return "";
        }
        int days = parseInt(matcher.group(1), -1);
        if (days < 1 || days > 15) {
            return "";
        }
        return String.valueOf(days);
    }

    private String normalizeK5ScoreLine(String value) {
        Matcher matcher = K5_SCORE_LINE.matcher(trimmed(value));
        if (!matcher.matches()) {
            return "";
        }
        int score = parseInt(matcher.group(2), -1);
        if (score < 70 || score > 100) {
            return "";
        }
        return matcher.group(1) + " " + score;
    }

    private String normalizeTrialCycleThreshold(String value) {
        Matcher matcher = K2_TRIAL_THRESHOLD.matcher(value);
        if (!matcher.matches()) {
            return "";
        }
        int count = parseInt(matcher.group(2), -1);
        int days = parseInt(matcher.group(3), -1);
        if (count < 2 || count > 10 || days < 7 || days > 60) {
            return "";
        }
        return matcher.group(1) + " " + count + " 次 / " + days + " 天";
    }

    private String normalizeWelcomeGiftThreshold(String value) {
        Matcher matcher = K2_WELCOME_GIFT_THRESHOLD.matcher(value);
        if (!matcher.matches()) {
            return "";
        }
        int count = parseInt(matcher.group(2), -1);
        if (count < 1 || count > 5) {
            return "";
        }
        return matcher.group(1) + " " + count + " 笔 / " + matcher.group(3);
    }

    private String normalizeLeaderboardThreshold(String value) {
        Matcher matcher = K2_LEADERBOARD_THRESHOLD.matcher(value.replace("X", "x"));
        if (!matcher.matches()) {
            return "";
        }
        int multiplier = parseInt(matcher.group(2), -1);
        if (multiplier < 2 || multiplier > 20) {
            return "";
        }
        return matcher.group(1) + " " + multiplier + "x " + matcher.group(3);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private int parseMoney(String value, int fallback) {
        try {
            return Integer.parseInt(value.replace(",", ""));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String formatMoney(int value) {
        return String.format(Locale.US, "%,d", value);
    }

    private boolean isKycReviewSignal(String signalType) {
        return "KYC_REVIEW_MANUAL".equals(signalType) || signalType.startsWith("KYC_REVIEW_");
    }

    private String nextRiskCaseNo(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private int riskScore(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 95;
            case "HIGH" -> 88;
            case "MEDIUM" -> 72;
            default -> 55;
        };
    }

    private String normalizeText(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trimmed(String value) {
        return value == null ? "" : value.trim();
    }

    private String addressReputationCacheKey(String chain, String address) {
        String normalizedChain = trimmed(chain).toUpperCase(Locale.ROOT);
        String normalizedAddress = trimmed(address);
        if (("USDT-ERC20".equals(normalizedChain) || "ERC20".equals(normalizedChain))
                && normalizedAddress.matches("^0x[0-9a-fA-F]{40}$")) {
            normalizedAddress = normalizedAddress.toLowerCase(Locale.ROOT);
        }
        return normalizedChain + "|" + normalizedAddress;
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String operator(String operator) {
        return StringUtils.hasText(operator) ? operator.trim() : "system";
    }

    private List<RiskRuleDimensionView> dimensions(List<RiskRuleView> rules) {
        return rules.stream()
                .map(rule -> {
                    DimensionMeta meta = dimensionMeta(rule.dimension());
                    return new RiskRuleDimensionView(
                            meta.ruleKey(),
                            rule.ruleId(),
                            meta.name(),
                            rule.conditionText(),
                            "",
                            meta.note(),
                            rule.action(),
                            meta.note(),
                            meta.icon(),
                            rule.priority(),
                            rule.version());
                })
                .sorted(Comparator
                        .comparingInt((RiskRuleDimensionView v) -> dimensionMeta(v.ruleKey()).sortOrder())
                        .thenComparing(RiskRuleDimensionView::ruleId))
                .toList();
    }

    private DimensionMeta dimensionMeta(String dimension) {
        String normalized = trimmed(dimension);
        return K3_DIMENSION_META.getOrDefault(normalized, new DimensionMeta(
                normalized,
                StringUtils.hasText(normalized) ? normalized : "自定义规则",
                "由 nx_admin_risk_withdraw_rule 配置",
                "card",
                9));
    }

    private String configuredParamValue(List<RiskArbitrageParamView> params, String key) {
        return params.stream()
                .filter(param -> key.equals(param.key()))
                .map(RiskArbitrageParamView::value)
                .filter(StringUtils::hasText)
                .findFirst()
                .orElse("");
    }

    private String nextRuleId() {
        return "WR-C" + UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
    }

    private void audit(String action, String resourceType, String resourceId, Long userId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(operator)
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private void auditRequired(String action, String resourceType, String resourceId, String operator, Map<String, Object> detail) {
        auditRequired(action, resourceType, resourceId, operator, detail, "HIGH");
    }

    private void auditRequired(
            String action, String resourceType, String resourceId, String operator,
            Map<String, Object> detail, String riskLevel) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator)
                .result("SUCCESS")
                .riskLevel(riskLevel)
                .detail(detail)
                .build());
    }

    private <T> ApiResult<T> rejectK3Write(
            String operation,
            String resourceId,
            String actor,
            int status,
            String reasonCode,
            String requestReason,
            String idempotencyKey) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", operation);
        detail.put("reasonCode", reasonCode);
        detail.put("requestReason", trimmed(requestReason));
        detail.put("idempotencyKey", trimmed(idempotencyKey));
        detail.put("businessDataChanged", false);
        auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action("K3_WITHDRAW_RULE_WRITE_REJECTED")
                .resourceType("WITHDRAW_RULE")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(actor)
                .result("REJECTED")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
        return ApiResult.fail(status, reasonCode);
    }

    private <T> ApiResult<T> rejectK4Write(
            String operation,
            String resourceId,
            String actor,
            int status,
            String reasonCode,
            String requestReason,
            String idempotencyKey) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", operation);
        detail.put("reasonCode", reasonCode);
        detail.put("requestReason", trimmed(requestReason));
        detail.put("idempotencyKey", trimmed(idempotencyKey));
        detail.put("businessDataChanged", false);
        boolean userScoped = "override".equals(operation) || "recompute".equals(operation)
                || "batchRecompute".equals(operation);
        String resourceType = "batchRecompute".equals(operation)
                ? "RISK_SCORE_USER_BATCH" : userScoped ? "RISK_SCORE_USER" : "RISK_SCORE_MODEL";
        auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action("K4_RISK_SCORING_WRITE_REJECTED")
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(actor)
                .result("REJECTED")
                .riskLevel("override".equals(operation) ? "MEDIUM" : "HIGH")
                .detail(detail)
                .build());
        return ApiResult.fail(status, reasonCode);
    }

    private <T> ApiResult<T> rejectK5Decision(
            String ticketId,
            String actor,
            int status,
            String reasonCode,
            String requestReason,
            String idempotencyKey) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operation", "decideTicket");
        detail.put("reasonCode", reasonCode);
        detail.put("requestReason", trimmed(requestReason));
        detail.put("idempotencyKey", trimmed(idempotencyKey));
        detail.put("businessDataChanged", false);
        auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action("K5_KYC_REVIEW_DECISION_REJECTED")
                .resourceType("RISK_KYC_REVIEW_TICKET")
                .resourceId(ticketId)
                .bizNo(ticketId)
                .actorType("ADMIN")
                .actorUsername(actor)
                .result("REJECTED")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
        return ApiResult.fail(status, reasonCode);
    }

    private <T> ApiResult<T> executeK5Decision(
            String ticketId,
            String actor,
            String requestReason,
            String idempotencyKey,
            Supplier<ApiResult<T>> command) {
        try {
            return command.get();
        } catch (RuntimeException failure) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("operation", "decideTicket");
            detail.put("failureType", failure.getClass().getSimpleName());
            detail.put("requestReason", trimmed(requestReason));
            detail.put("idempotencyKey", trimmed(idempotencyKey));
            detail.put("businessDataChanged", false);
            try {
                auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                        .action("K5_KYC_REVIEW_DECISION_FAILED")
                        .resourceType("RISK_KYC_REVIEW_TICKET")
                        .resourceId(ticketId)
                        .bizNo(ticketId)
                        .actorType("ADMIN")
                        .actorUsername(actor)
                        .result("FAILED")
                        .riskLevel("HIGH")
                        .detail(detail)
                        .build());
            } catch (RuntimeException auditFailure) {
                failure.addSuppressed(auditFailure);
            }
            throw failure;
        }
    }

    private <T> ApiResult<T> executeK4Write(
            String operation,
            String resourceId,
            String actor,
            String requestReason,
            String idempotencyKey,
            Supplier<ApiResult<T>> command) {
        try {
            return command.get();
        } catch (RuntimeException failure) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("operation", operation);
            detail.put("failureType", failure.getClass().getSimpleName());
            detail.put("requestReason", trimmed(requestReason));
            detail.put("idempotencyKey", trimmed(idempotencyKey));
            detail.put("businessDataChanged", false);
            boolean userScoped = "override".equals(operation) || "recompute".equals(operation)
                    || "batchRecompute".equals(operation);
            String resourceType = "batchRecompute".equals(operation)
                    ? "RISK_SCORE_USER_BATCH" : userScoped ? "RISK_SCORE_USER" : "RISK_SCORE_MODEL";
            try {
                auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                        .action("K4_RISK_SCORING_WRITE_FAILED")
                        .resourceType(resourceType)
                        .resourceId(resourceId)
                        .bizNo(resourceId)
                        .actorType("ADMIN")
                        .actorUsername(actor)
                        .result("FAILED")
                        .riskLevel("override".equals(operation) ? "MEDIUM" : "HIGH")
                        .detail(detail)
                        .build());
            } catch (RuntimeException ignored) {
                failure.addSuppressed(ignored);
            }
            throw failure;
        }
    }

    private static Map<String, Object> k4AuditDetail(
            Object before, Object after, String reason, String idempotencyKey) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("before", before);
        detail.put("after", after);
        detail.put("reason", reason == null ? "" : reason.trim());
        detail.put("idempotencyKey", idempotencyKey == null ? "" : idempotencyKey.trim());
        return detail;
    }

    private static Map<String, Object> k1ParamSnapshot(String key, String value) {
        return Map.of("exists", true, "key", key, "value", value);
    }

    private static Map<String, Object> k3RuleSnapshot(RiskRuleView rule) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("ruleId", rule.ruleId());
        snapshot.put("dimension", rule.dimension());
        snapshot.put("conditionText", rule.conditionText());
        snapshot.put("action", rule.action());
        snapshot.put("state", rule.state());
        snapshot.put("priority", rule.priority());
        snapshot.put("version", rule.version());
        return snapshot;
    }

    private static Map<String, Object> k2ParamSnapshot(RiskArbitrageParamView param) {
        return Map.of(
                "key", param.key(),
                "name", param.name(),
                "value", param.value(),
                "sub", param.sub(),
                "note", param.note(),
                "version", param.version());
    }

    private static Map<String, Object> k2RowSnapshot(RiskArbitrageRowView row) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("rowId", row.rowId());
        snapshot.put("viewKey", row.viewKey());
        snapshot.put("clusterId", row.clusterId() == null ? "" : row.clusterId());
        snapshot.put("cells", row.cells());
        snapshot.put("level", row.level());
        snapshot.put("actions", row.actions());
        snapshot.put("disposition", row.disposition() == null ? "" : row.disposition());
        snapshot.put("version", row.version());
        snapshot.put("clusterStatus", row.clusterStatus() == null ? "" : row.clusterStatus());
        snapshot.put("clusterVersion", row.clusterVersion() == null ? -1L : row.clusterVersion());
        return snapshot;
    }

    private static Map<String, Object> k1WhitelistSnapshot(
            String cidr, RiskOpsRepository.IpWhitelistState state) {
        if (state == null) return Map.of("exists", false, "cidr", cidr);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("exists", true);
        snapshot.put("cidr", state.cidr());
        snapshot.put("note", state.note());
        snapshot.put("expireText", state.expireText());
        snapshot.put("active", state.active());
        snapshot.put("operator", state.operator());
        return snapshot;
    }

    private record DimensionMeta(
            String ruleKey,
            String name,
            String note,
            String icon,
            int sortOrder
    ) {
    }

    private record K2ActionSpec(
            String requiredRowAction,
            String disposition,
            String auditAction,
            String requiredAuthority
    ) {
    }

    private record K2ViewSpec(
            String key,
            String label,
            String sub,
            List<String> head,
            String note
    ) {
    }

    @Override
    public String domain() {
        return "K";
    }

    private <T> ApiResult<T> delegatedDirectExecutionGuard(
            String operation, String resourceType, String resourceId) {
        if (A2ReplayContext.isReplaying()) {
            return null;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(Collectors.toSet());
        if (!authorities.contains("platform_a2_proposal_create")
                || authorities.contains("platform_a2_operation_approve")) {
            return null;
        }
        String safeResourceId = resourceId == null ? "" : resourceId;
        auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action("A2_DIRECT_EXECUTION_REJECTED")
                .resourceType(resourceType)
                .resourceId(safeResourceId)
                .bizNo(safeResourceId)
                .actorType("ADMIN")
                .actorUsername(String.valueOf(authentication.getName()))
                .result("REJECTED")
                .riskLevel("HIGH")
                .detail(Map.of(
                        "operation", operation,
                        "target", safeResourceId,
                        "reason", "A2_PROPOSAL_REQUIRED",
                        "businessDataChanged", false))
                .build());
        return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "A2_PROPOSAL_REQUIRED");
    }

    @Override
    @Transactional
    public ApiResult<?> replay(AuditReplayCommand cmd, AuditReplayContext ctx) {
        Map<String, Object> p = cmd.params() == null ? Map.of() : cmd.params();
        String operator = ctx.operator();
        String reason = ctx.reason();
        String idem = ctx.idempotencyKey();
        switch (cmd.op()) {
            case "k1_cluster_freeze", "k1_cluster_release", "k1_cluster_cleared", "k1_cluster_flag" -> {
                String status = switch (cmd.op()) {
                    case "k1_cluster_freeze" -> "frozen";
                    case "k1_cluster_release" -> "released";
                    case "k1_cluster_cleared" -> "cleared";
                    default -> "flagged";
                };
                RiskClusterStatusRequest req = new RiskClusterStatusRequest(
                        status, reason, operator, longVal(p, "expectedVersion"));
                return updateMultiAccountClusterStatus(str(p, "clusterId"), idem, req);
            }
            case "k2_row_flag", "k2_row_blockgift", "k2_row_boardflag", "k2_row_freeze" -> {
                String action = switch (cmd.op()) {
                    case "k2_row_flag" -> "mark";
                    case "k2_row_blockgift" -> "block-gift";
                    case "k2_row_boardflag" -> "board-flag";
                    default -> "freeze-cluster";
                };
                RiskArbitrageActionRequest req = new RiskArbitrageActionRequest(
                        reason, operator, longVal(p, "expectedVersion"), longVal(p, "clusterExpectedVersion"));
                return executeArbitrageAction(str(p, "rowId"), action, idem, req);
            }
            case "k3_rule_create" -> {
                RiskRuleCreateRequest req = new RiskRuleCreateRequest(
                        str(p, "dimension"),
                        str(p, "conditionText"),
                        str(p, "action"),
                        reason, operator);
                return createWithdrawRule(idem, req);
            }
            case "k3_rule_toggle" -> {
                RiskRuleStatusRequest req = new RiskRuleStatusRequest(str(p, "state"), reason, operator);
                return updateWithdrawRuleState(str(p, "ruleId"), idem, req);
            }
            case "k3_rule_archive" -> {
                RiskRuleStatusRequest req = new RiskRuleStatusRequest("archived", reason, operator);
                return updateWithdrawRuleState(str(p, "ruleId"), idem, req);
            }
            case "k4_user_override" -> {
                RiskScoreOverrideRequest req = new RiskScoreOverrideRequest(intVal(p, "score"), reason, operator);
                return overrideScore(str(p, "userNo"), idem, req);
            }
            case "k4_user_recompute" -> {
                RiskScoreCommandRequest req = new RiskScoreCommandRequest(reason, operator);
                return recomputeScore(str(p, "userNo"), idem, req);
            }
            case "k5_ticket_pass", "k5_ticket_reject" -> {
                String decision = "k5_ticket_pass".equals(cmd.op()) ? "passed" : "rejected";
                RiskKycReviewDecisionRequest req = new RiskKycReviewDecisionRequest(decision, reason, operator);
                return decideKycReviewTicket(str(p, "ticketId"), idem, req);
            }
            case "k5_ticket_manual" -> {
                RiskKycManualReviewRequest req = new RiskKycManualReviewRequest(str(p, "userNo"), reason, operator);
                return createManualKycReviewTicket(idem, req);
            }
            default -> {
                return ApiResult.fail(422, "UNKNOWN_REPLAY_OP:" + cmd.op());
            }
        }
    }

    /** 从 replay params 取字符串,null 安全。 */
    private static String str(Map<String, Object> params, String key) {
        Object v = params.get(key);
        return v == null ? null : String.valueOf(v).trim();
    }

    /** 从 replay params 取 Integer,null 安全(缺失返回 null,由 DTO 默认逻辑兜底)。 */
    private static Integer intVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.intValue();
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static Long longVal(Map<String, Object> params, String key) {
        Object value = params.get(key);
        if (value instanceof Number number) return number.longValue();
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}

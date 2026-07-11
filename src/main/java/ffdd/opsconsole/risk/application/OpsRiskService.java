package ffdd.opsconsole.risk.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
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
import ffdd.opsconsole.risk.domain.RiskScoreUserSearchView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.risk.domain.RiskScoringSourceCatalog;
import ffdd.opsconsole.risk.domain.RiskWithdrawCandidateView;
import ffdd.opsconsole.risk.dto.RiskArbitrageActionRequest;
import ffdd.opsconsole.risk.dto.RiskArbitrageParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.risk.dto.RiskClusterStatusRequest;
import ffdd.opsconsole.risk.dto.RiskDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskIpWhitelistRequest;
import ffdd.opsconsole.risk.dto.RiskKycManualReviewRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewDecisionRequest;
import ffdd.opsconsole.risk.dto.RiskKycReviewOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskParamUpdateRequest;
import ffdd.opsconsole.risk.dto.RiskRuleConditionRequest;
import ffdd.opsconsole.risk.dto.RiskRuleCreateRequest;
import ffdd.opsconsole.risk.dto.RiskRuleDryRunRequest;
import ffdd.opsconsole.risk.dto.RiskRuleHitQueryRequest;
import ffdd.opsconsole.risk.dto.RiskRuleOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskRuleStatusRequest;
import ffdd.opsconsole.risk.dto.RiskScoreCommandRequest;
import ffdd.opsconsole.risk.dto.RiskScoreOverrideRequest;
import ffdd.opsconsole.risk.dto.RiskScoringOverviewQueryRequest;
import ffdd.opsconsole.risk.dto.RiskScoringBandRequest;
import ffdd.opsconsole.risk.dto.RiskScoringEscalateRequest;
import ffdd.opsconsole.risk.dto.RiskScoringSourceRequest;
import ffdd.opsconsole.risk.dto.RiskScoringWeightsRequest;
import ffdd.opsconsole.risk.dto.RiskSignalRequest;
import ffdd.opsconsole.user.facade.UserKycStatusFacade;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.util.StringUtils;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class OpsRiskService implements ffdd.opsconsole.platform.domain.AuditReplayable {
    private static final Set<String> FINAL_DECISIONS = Set.of("ALLOW", "BLOCK", "REJECT", "DENY");
    private static final Set<String> MANUAL_DECISIONS = Set.of("ALLOW", "BLOCK", "HOLD");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> RULE_ACTIONS = Set.of("pass", "delay", "freeze", "manual");
    private static final Set<String> RULE_STATES = Set.of("draft", "active", "paused", "archived");
    private static final Set<String> CLUSTER_STATES = Set.of("detected", "flagged", "frozen", "released", "cleared");
    private static final Set<String> CLUSTER_LAYERS = Set.of("ip", "device", "payment");
    private static final Set<String> KYC_REVIEW_DECISIONS = Set.of("passed", "rejected");
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
    private static final Set<String> K3_ADDRESS_REPUTATION_RULES = Set.of(
            "黑名单 / 低信誉地址", "内部黑名单 + 链上信誉", "内部黑名单", "链上信誉", "第三方链上信誉", "内部 + 第三方信誉");
    private static final Set<String> K5_TICKET_FILTERS = Set.of("all", "大额提现", "大额兑换", "累计过线", "overdue");
    private static final Map<String, String> OTP_CANONICAL_CONFIG_KEYS = Map.of(
            "otpGate.resendSeconds", "auth.risk.otp_cooldown_seconds",
            "otpGate.captchaAfterSends", "auth.risk.otp_max_24h",
            "otpGate.otpTtlSeconds", "auth.risk.otp_ttl_minutes",
            "otpGate.maxVerifyAttempts", "auth.risk.otp_max_verify_attempts",
            "otpGate.captchaTicketTtlSeconds", "auth.risk.captcha_ticket_ttl_seconds");
    private static final Map<String, K2ActionSpec> K2_ACTIONS = Map.of(
            "mark", new K2ActionSpec("flag", "已标记套利", "K2_ARBITRAGE_MARKED"),
            "block-gift", new K2ActionSpec("blockgift", "新人礼已拦截", "K2_WELCOME_GIFT_BLOCKED"),
            "board-flag", new K2ActionSpec("boardflag", "已标记刷榜", "K2_LEADERBOARD_FLAGGED"),
            "freeze-cluster", new K2ActionSpec("freeze", "已联动 K1 冻结", "K2_CLUSTER_FREEZE_LINKED"));
    private static final List<K2ViewSpec> K2_VIEWS = List.of(
            new K2ViewSpec("trial", "试用循环", "· 试用循环养号 · 服务器端复活计数,不信客户端状态",
                    List.of("实体 / 簇", "30 天循环次数", "关联账户", "累计套取试用收益", "层数命中", "判定", "动作"),
                    "循环次数是服务器记的开试用、取消、再开的轮数。层数命中 = 多账户 / 绑上级 / 试用循环。"),
            new K2ViewSpec("tradein", "换新套利", "· 以旧换新套利 · 没满最短持有月就想换新,残值已被服务器算成 $0 拦下",
                    List.of("账户", "设备", "购入时间", "持有月数 / 门槛", "残值拦截", "层数命中", "动作"),
                    "最短持有月数由 E3 配置,这里只读；服务器守卫已把不满门槛的残值算成 $0。"),
            new K2ViewSpec("gift", "新人礼刷取", "· 新人礼刷取 · 看领完礼就走的闭环行为",
                    List.of("簇", "实体", "已发 / 已拦", "涉及金额", "闭环特征", "层数命中", "动作"),
                    "K1 看同一实体重复领；K2 加看行为闭环。拦截只停发后续,不动已入账资产。"),
            new K2ViewSpec("board", "排行榜刷榜", "· 排行榜刷榜 · 邀请/佣金增速异常的冲榜账户",
                    List.of("账户", "本期累计佣金", "增速(对基线)", "直推增长", "关联簇", "判定", "动作"),
                    "这里只标记并发信号给 F8 和风险雷达；取消参榜资格、从奖池剔除由 F8 执行。"));
    private static final Map<String, DimensionMeta> K3_DIMENSION_META = Map.of(
            "largeAmountUsdt", new DimensionMeta("largeAmountUsdt", "金额", "由 nx_admin_risk_withdraw_rule 配置", "card", 0),
            "金额", new DimensionMeta("largeAmountUsdt", "金额", "由 nx_admin_risk_withdraw_rule 配置", "card", 0),
            "velocity24h", new DimensionMeta("velocity24h", "速度", "由 nx_admin_risk_withdraw_rule 配置", "wave", 1),
            "速度", new DimensionMeta("velocity24h", "速度", "由 nx_admin_risk_withdraw_rule 配置", "wave", 1),
            "newAccountProtectDays", new DimensionMeta("newAccountProtectDays", "新账户", "由 nx_admin_risk_withdraw_rule 配置", "user", 2),
            "新账户", new DimensionMeta("newAccountProtectDays", "新账户", "由 nx_admin_risk_withdraw_rule 配置", "user", 2),
            "addressReputationSource", new DimensionMeta("addressReputationSource", "地址信誉", "由 nx_admin_risk_withdraw_rule 配置", "shield", 3),
            "地址信誉", new DimensionMeta("addressReputationSource", "地址信誉", "由 nx_admin_risk_withdraw_rule 配置", "shield", 3));

    private final RiskOpsRepository riskRepository;
    private final UserKycStatusFacade userKycStatusFacade;
    private final FinanceWithdrawalKycReviewFacade financeWithdrawalKycReviewFacade;
    private final MarketExchangeKycReviewFacade marketExchangeKycReviewFacade;
    private final PlatformConfigFacade configFacade;
    private final AuditLogService auditLogService;
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper;

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
        return ApiResult.ok(riskRepository.multiAccountOverview(
                normalizePageNum(clusterPageNum),
                normalizeLimit(clusterPageSize, 5, 50),
                normalizeClusterLayer(clusterLayer),
                normalizePageNum(whitelistPageNum),
                normalizeLimit(whitelistPageSize, 5, 50)));
    }

    public ApiResult<Map<String, Object>> updateMultiAccountParam(String key, String idempotencyKey, RiskParamUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey = trimmed(key);
        String value = trimmed(request.value());
        if (!StringUtils.hasText(normalizedKey) || !StringUtils.hasText(value)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_PARAM_VALUE_REQUIRED");
        }
        if ("linkWeight".equals(normalizedKey)) {
            value = normalizeLinkWeight(value);
            if (!StringUtils.hasText(value)) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_LINK_WEIGHT_INVALID");
            }
        }
        Map<String, Object> updated = riskRepository.updateMultiAccountParam(normalizedKey, value);
        audit("K1_MULTI_ACCOUNT_PARAM_CHANGED", "RISK_MULTI_ACCOUNT_PARAM", normalizedKey, null, operator(request.operator()), Map.of(
                "key", normalizedKey,
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> updateMultiAccountClusterStatus(String clusterId, String idempotencyKey, RiskClusterStatusRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedCluster = trimmed(clusterId);
        String status = trimmed(request.status()).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(normalizedCluster) || !CLUSTER_STATES.contains(status)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_CLUSTER_STATUS_INVALID");
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("K", "cluster", normalizedCluster) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        if (!riskRepository.updateMultiAccountClusterStatus(normalizedCluster, status, request.reason().trim(), operator(request.operator()))) {
            return ApiResult.fail(404, "K1_CLUSTER_NOT_FOUND");
        }
        audit("K1_CLUSTER_STATUS_CHANGED", "RISK_MULTI_ACCOUNT_CLUSTER", normalizedCluster, null, operator(request.operator()), Map.of(
                "clusterId", normalizedCluster,
                "status", status,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return multiAccountOverview(1, 5, null, 1, 5);
    }

    public ApiResult<Map<String, Object>> upsertIpWhitelist(String idempotencyKey, RiskIpWhitelistRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String cidr = trimmed(request.cidr());
        if (!StringUtils.hasText(cidr)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_WHITELIST_CIDR_REQUIRED");
        }
        String note = StringUtils.hasText(request.note()) ? request.note().trim() : request.reason().trim();
        String expireText = StringUtils.hasText(request.expireText()) ? request.expireText().trim() : "2026-12-31";
        riskRepository.upsertIpWhitelist(cidr, note, operator(request.operator()), expireText);
        audit("K1_IP_WHITELIST_UPSERTED", "RISK_IP_WHITELIST", cidr, null, operator(request.operator()), Map.of(
                "cidr", cidr,
                "note", note,
                "expireText", expireText,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return multiAccountOverview(1, 5, null, 1, 5);
    }

    public ApiResult<Map<String, Object>> disableIpWhitelist(String idempotencyKey, RiskIpWhitelistRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String cidr = trimmed(request.cidr());
        if (!StringUtils.hasText(cidr)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K1_WHITELIST_CIDR_REQUIRED");
        }
        if (!riskRepository.disableIpWhitelist(cidr, operator(request.operator()))) {
            return ApiResult.fail(404, "K1_WHITELIST_NOT_FOUND");
        }
        audit("K1_IP_WHITELIST_DISABLED", "RISK_IP_WHITELIST", cidr, null, operator(request.operator()), Map.of(
                "cidr", cidr,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return multiAccountOverview(1, 5, null, 1, 5);
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
        response.put("dimensions", dimensions(allRules));
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

    public ApiResult<RiskRuleView> createWithdrawRule(String idempotencyKey, RiskRuleCreateRequest request) {
        ApiResult<RiskRuleView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
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
        String action = normalizeRuleAction(request.action());
        if (action == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_ACTION_INVALID");
        }
        String ruleKey = dimension + ":" + condition;
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("K", "rule", ruleKey) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String ruleId = nextRuleId();
        RiskRuleView created = riskRepository.createWithdrawRule(ruleId, dimension, condition, action, "draft", operator(request.operator()));
        audit("K3_WITHDRAW_RULE_CREATED", "WITHDRAW_RULE", created.ruleId(), null, operator(request.operator()), Map.of(
                "ruleId", created.ruleId(),
                "dimension", created.dimension(),
                "action", created.action(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(created);
    }

    public ApiResult<RiskRuleView> updateWithdrawRuleState(String ruleId, String idempotencyKey, RiskRuleStatusRequest request) {
        ApiResult<RiskRuleView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalized = trimmed(ruleId);
        if (!StringUtils.hasText(normalized)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_ID_REQUIRED");
        }
        String state = normalizeRuleState(request.state());
        if (state == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_STATE_INVALID");
        }
        RiskRuleView before = riskRepository.findWithdrawRule(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "RULE_NOT_FOUND");
        }
        if ("archived".equals(before.state()) && !"archived".equals(state)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("K", "rule", normalized) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        if (state.equals(before.state())) {
            return ApiResult.ok(before);
        }
        RiskRuleView updated = riskRepository.updateWithdrawRuleState(normalized, state).orElse(null);
        if (updated == null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "WITHDRAW_RULE_RELOAD_FAILED");
        }
        audit("K3_WITHDRAW_RULE_STATE_CHANGED", "WITHDRAW_RULE", normalized, null, operator(request.operator()), Map.of(
                "ruleId", normalized,
                "fromState", before.state(),
                "toState", updated.state(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<RiskRuleView> updateWithdrawRuleCondition(String ruleId, String idempotencyKey, RiskRuleConditionRequest request) {
        ApiResult<RiskRuleView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
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
        RiskRuleView before = riskRepository.findWithdrawRule(normalized).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "RULE_NOT_FOUND");
        }
        if ("archived".equals(before.state())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        condition = normalizeWithdrawRuleCondition(before.dimension(), condition);
        if (!StringUtils.hasText(condition)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "RULE_CONDITION_INVALID");
        }
        RiskRuleView updated = riskRepository.updateWithdrawRuleCondition(normalized, condition).orElse(null);
        if (updated == null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "WITHDRAW_RULE_RELOAD_FAILED");
        }
        audit("K3_WITHDRAW_RULE_CONDITION_CHANGED", "WITHDRAW_RULE", normalized, null, operator(request.operator()), Map.of(
                "ruleId", normalized,
                "fromCondition", before.conditionText(),
                "toCondition", updated.conditionText(),
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> dryRunWithdrawRules(String idempotencyKey, RiskRuleDryRunRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String batchNo = "K3-DRY-" + UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
        List<RiskRuleView> activeRules = riskRepository.withdrawRules().stream()
                .filter(rule -> "active".equalsIgnoreCase(trimmed(rule.state())))
                .toList();
        List<RiskWithdrawCandidateView> candidates = activeRules.isEmpty()
                ? List.of()
                : riskRepository.withdrawRuleCandidates(200);
        int hitCount = 0;
        Map<String, Integer> hitCountsByRule = new LinkedHashMap<>();
        for (RiskWithdrawCandidateView candidate : candidates) {
            for (RiskRuleView rule : activeRules) {
                if (!withdrawRuleMatches(rule, candidate)) {
                    continue;
                }
                riskRepository.recordWithdrawRuleHit(candidate.withdrawalNo(), candidate.userNo(), candidate.amount(), rule);
                hitCount++;
                hitCountsByRule.merge(rule.ruleId(), 1, Integer::sum);
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("batchNo", batchNo);
        response.put("status", "STARTED");
        response.put("sampleWindowDays", 30);
        response.put("evaluatedWithdrawals", candidates.size());
        response.put("activeRules", activeRules.size());
        response.put("hitCount", hitCount);
        response.put("hitCountsByRule", hitCountsByRule);
        response.put("routeCounts", riskRepository.withdrawRouteCounts());
        response.put("startedAt", LocalDateTime.now());
        audit("K3_WITHDRAW_RULE_DRY_RUN_STARTED", "WITHDRAW_RULE_DRY_RUN", batchNo, null, operator(request.operator()), Map.of(
                "batchNo", batchNo,
                "evaluatedWithdrawals", candidates.size(),
                "activeRules", activeRules.size(),
                "hitCount", hitCount,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(response);
    }

    private boolean withdrawRuleMatches(RiskRuleView rule, RiskWithdrawCandidateView candidate) {
        String dimension = trimmed(rule.dimension()).toLowerCase(Locale.ROOT);
        String condition = trimmed(rule.conditionText()).toLowerCase(Locale.ROOT);
        boolean velocityRule = condition.contains("24h") || condition.contains("24小时") || dimension.contains("速度")
                || dimension.contains("velocity") || dimension.contains("count");
        boolean amountRule = condition.contains("$") || condition.contains("usdt") || condition.contains("单笔")
                || condition.contains("single") || condition.contains("amount") || dimension.contains("金额")
                || dimension.contains("amount");
        boolean accountRule = condition.contains("注册") || condition.contains("account") || dimension.contains("新账户")
                || dimension.contains("账户");
        boolean addressRule = condition.contains("地址") || condition.contains("blacklist") || condition.contains("reputation")
                || dimension.contains("地址") || dimension.contains("信誉");
        if (velocityRule) {
            if (withdrawVelocityRuleMatches(condition, candidate.withdrawalCount24h(), candidate.amount())) {
                return true;
            }
            if (condition.contains("或") || condition.contains(" or ")) {
                return false;
            }
        }
        if (amountRule) {
            BigDecimal threshold = firstDecimal(condition);
            return threshold != null && candidate.amount() != null
                    && compareRuleValue(candidate.amount(), threshold, condition);
        }
        if (accountRule || addressRule) {
            String signals = trimmed(candidate.existingSignals()).toLowerCase(Locale.ROOT);
            return StringUtils.hasText(signals)
                    && ((StringUtils.hasText(dimension) && signals.contains(dimension))
                    || (StringUtils.hasText(condition) && signals.contains(condition))
                    || signals.contains("account")
                    || signals.contains("address")
                    || signals.contains("账户")
                    || signals.contains("地址"));
        }
        return false;
    }

    private boolean withdrawVelocityRuleMatches(String condition, Integer count24h, BigDecimal amount) {
        Matcher matcher = K3_VELOCITY_RULE.matcher(trimmed(condition).replace(",", ""));
        if (matcher.matches()) {
            boolean countMatched = count24h != null
                    && compareRuleValue(BigDecimal.valueOf(count24h), new BigDecimal(matcher.group(2)), matcher.group(1));
            boolean amountMatched = amount != null
                    && compareRuleValue(amount, new BigDecimal(matcher.group(4)), matcher.group(3));
            return countMatched || amountMatched;
        }
        BigDecimal threshold = firstDecimal(condition);
        return threshold != null && count24h != null
                && compareRuleValue(BigDecimal.valueOf(count24h), threshold, condition);
    }

    private BigDecimal firstDecimal(String value) {
        Matcher matcher = Pattern.compile("(\\d+(?:\\.\\d+)?)").matcher(trimmed(value).replace(",", ""));
        return matcher.find() ? new BigDecimal(matcher.group(1)) : null;
    }

    private boolean compareRuleValue(BigDecimal actual, BigDecimal threshold, String condition) {
        String text = trimmed(condition);
        if (text.contains("<=") || text.contains("≤")) {
            return actual.compareTo(threshold) <= 0;
        }
        if (text.contains("<")) {
            return actual.compareTo(threshold) < 0;
        }
        if (text.contains(">") || text.contains(">=") || text.contains("≥")) {
            return actual.compareTo(threshold) >= 0;
        }
        return actual.compareTo(threshold) >= 0;
    }

    public ApiResult<Map<String, Object>> arbitrageOverview() {
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
        List<RiskArbitrageParamView> params = riskRepository.arbitrageParams().stream()
                .map(this::withCanonicalOtpValue)
                .toList();
        response.put("stats", riskRepository.arbitrageStats());
        response.put("params", params);
        response.put("views", views);
        response.put("minHoldingMonths", configuredParamValue(params, "minHoldingMonths"));
        response.put("redlines", List.of("K2 only marks and emits signals", "account freezing is linked to K1 action chain", "welcome gift blocking never touches settled assets"));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<RiskArbitrageParamView> updateArbitrageParam(String key, String idempotencyKey, RiskArbitrageParamUpdateRequest request) {
        ApiResult<RiskArbitrageParamView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey = trimmed(key);
        String value = trimmed(request.value());
        if (!StringUtils.hasText(normalizedKey) || !StringUtils.hasText(value)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_PARAM_VALUE_REQUIRED");
        }
        if (value.length() > 128) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_PARAM_VALUE_TOO_LONG");
        }
        value = normalizeArbitrageParam(normalizedKey, value);
        if (!StringUtils.hasText(value)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_PARAM_VALUE_INVALID");
        }
        RiskArbitrageParamView updated = riskRepository.updateArbitrageParam(normalizedKey, value).orElse(null);
        if (updated == null) {
            return ApiResult.fail(404, "K2_PARAM_NOT_FOUND");
        }
        persistCanonicalOtpValue(normalizedKey, value);
        audit("K2_PARAM_CHANGED", "RISK_ARBITRAGE_PARAM", normalizedKey, null, operator(request.operator()), Map.of(
                "key", normalizedKey,
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(withCanonicalOtpValue(updated));
    }

    public ApiResult<RiskArbitrageRowView> executeArbitrageAction(String rowId, String action, String idempotencyKey, RiskArbitrageActionRequest request) {
        ApiResult<RiskArbitrageRowView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedRowId = trimmed(rowId);
        String normalizedAction = trimmed(action).toLowerCase(Locale.ROOT);
        K2ActionSpec spec = K2_ACTIONS.get(normalizedAction);
        if (!StringUtils.hasText(normalizedRowId) || spec == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_ACTION_INVALID");
        }
        RiskArbitrageRowView before = riskRepository.findArbitrageRow(normalizedRowId).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "K2_ROW_NOT_FOUND");
        }
        if (StringUtils.hasText(before.disposition())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (!before.actions().contains(spec.requiredRowAction())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("K", "arbitrage_row", normalizedRowId) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        if (!A2ReplayContext.isReplaying()
                && "freeze-cluster".equals(normalizedAction)
                && StringUtils.hasText(before.clusterId())
                && lockMapper.countActiveByTarget("K", "cluster", before.clusterId()) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        boolean linkedClusterFrozen = false;
        if ("freeze-cluster".equals(normalizedAction)) {
            String clusterId = trimmed(before.clusterId());
            if (!StringUtils.hasText(clusterId)) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K2_CLUSTER_ID_REQUIRED");
            }
            if (!riskRepository.updateMultiAccountClusterStatus(clusterId, "frozen", request.reason().trim(), operator(request.operator()))) {
                return ApiResult.fail(404, "K1_CLUSTER_NOT_FOUND");
            }
            linkedClusterFrozen = true;
            audit("K1_CLUSTER_STATUS_CHANGED_BY_K2", "RISK_MULTI_ACCOUNT_CLUSTER", clusterId, null, operator(request.operator()), Map.of(
                    "clusterId", clusterId,
                    "status", "frozen",
                    "sourceRowId", normalizedRowId,
                    "reason", request.reason().trim(),
                    "idempotencyKey", idempotencyKey.trim()));
        }
        RiskArbitrageRowView updated = riskRepository.updateArbitrageDisposition(normalizedRowId, spec.disposition()).orElse(before);
        audit(spec.auditAction(), "RISK_ARBITRAGE_ROW", normalizedRowId, null, operator(request.operator()), Map.of(
                "rowId", normalizedRowId,
                "viewKey", before.viewKey(),
                "action", normalizedAction,
                "clusterId", before.clusterId() == null ? "" : before.clusterId(),
                "linkedClusterFrozen", linkedClusterFrozen,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> scoringOverview() {
        return scoringOverview(new RiskScoringOverviewQueryRequest(null, null));
    }

    public ApiResult<Map<String, Object>> scoringOverview(RiskScoringOverviewQueryRequest request) {
        List<RiskScoreDistributionView> distribution = riskRepository.scoringDistribution();
        int overridePageNum = normalizePageNum(request == null ? null : request.overridePageNum());
        int overridePageSize = normalizeLimit(request == null ? null : request.overridePageSize(), 5, 50);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("dimensions", riskRepository.scoringDimensions());
        response.put("config", riskRepository.scoringConfig());
        response.put("distribution", distribution);
        response.put("totalUsers", distribution.stream().mapToLong(v -> v.count() == null ? 0L : v.count()).sum());
        response.put("overrides", riskRepository.pageScoreOverrides(overridePageNum, overridePageSize));
        response.put("overrideActive", riskRepository.countActiveScoreOverrides());
        response.put("redlines", List.of("weights must sum to 100", "model changes require platform admin reason", "single-user override must be reversible"));
        return ApiResult.ok(response);
    }

    public ApiResult<RiskScoreUserView> scoreUser(String userNo) {
        String normalized = trimmed(userNo);
        if (!StringUtils.hasText(normalized)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SCORE_USER_REQUIRED");
        }
        return riskRepository.findScoreUser(normalized)
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "SCORE_USER_NOT_FOUND"));
    }

    public ApiResult<List<RiskScoreUserSearchView>> searchScoreUsers(String keyword, Integer limit) {
        int normalizedLimit = normalizeLimit(limit, 8, 20);
        return ApiResult.ok(riskRepository.searchScoreUsers(trimmed(keyword), normalizedLimit));
    }

    public ApiResult<Map<String, Object>> updateScoringWeights(String idempotencyKey, RiskScoringWeightsRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        Map<String, Integer> weights = request.weights();
        if (weights == null || weights.isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SCORE_WEIGHTS_REQUIRED");
        }
        Set<String> knownKeys = riskRepository.scoringDimensions().stream()
                .map(RiskScoreDimensionView::dimKey)
                .collect(Collectors.toSet());
        if (!knownKeys.equals(weights.keySet())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SCORE_WEIGHT_KEYS_INVALID");
        }
        int total = weights.values().stream().mapToInt(v -> v == null ? -1 : v).sum();
        boolean invalid = weights.values().stream().anyMatch(v -> v == null || v < 0 || v > 100);
        if (invalid || total != 100) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SCORE_WEIGHTS_MUST_SUM_100");
        }
        List<RiskScoreDimensionView> updated = riskRepository.updateScoringWeights(new LinkedHashMap<>(weights));
        audit("K4_SCORE_WEIGHTS_CHANGED", "RISK_SCORE_MODEL", "weights", null, operator(request.operator()), Map.of(
                "weights", updated,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return scoringOverview();
    }

    public ApiResult<Map<String, Object>> updateScoringSource(String idempotencyKey, RiskScoringSourceRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String inputSource = trimmed(request.inputSource());
        if (!RiskScoringSourceCatalog.contains(inputSource)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SCORE_SOURCE_INVALID");
        }
        riskRepository.updateScoringConfig("inputSource", inputSource);
        audit("K4_SCORE_SOURCE_TOGGLED", "RISK_SCORE_MODEL", "source", null, operator(request.operator()), Map.of(
                "inputSource", inputSource,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return scoringOverview();
    }

    public ApiResult<Map<String, Object>> updateScoringBand(String idempotencyKey, RiskScoringBandRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        Integer lowMax = request.lowMax();
        Integer highMin = request.highMin();
        if (lowMax == null || highMin == null || lowMax < 0 || highMin > 100 || lowMax >= highMin) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SCORE_BAND_INVALID");
        }
        riskRepository.updateScoringConfig("bandLowMax", String.valueOf(lowMax));
        riskRepository.updateScoringConfig("bandHighMin", String.valueOf(highMin));
        audit("K4_SCORE_BAND_CHANGED", "RISK_SCORE_MODEL", "band", null, operator(request.operator()), Map.of(
                "lowMax", lowMax,
                "highMin", highMin,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return scoringOverview();
    }

    public ApiResult<Map<String, Object>> updateScoringEscalate(String idempotencyKey, RiskScoringEscalateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        Integer score = request.score();
        if (score == null || score < 70 || score > 100) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SCORE_ESCALATE_INVALID");
        }
        riskRepository.updateScoringConfig("autoEscalateScore", String.valueOf(score));
        audit("K4_SCORE_ESCALATE_CHANGED", "RISK_SCORE_MODEL", "escalate", null, operator(request.operator()), Map.of(
                "score", score,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return scoringOverview();
    }

    public ApiResult<RiskScoreUserView> overrideScore(String userNo, String idempotencyKey, RiskScoreOverrideRequest request) {
        ApiResult<RiskScoreUserView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        Integer score = request.score();
        String normalized = trimmed(userNo);
        if (!StringUtils.hasText(normalized) || score == null || score < 0 || score > 100) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SCORE_OVERRIDE_INVALID");
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("K", "score_user", normalized) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        RiskScoreOverrideView updated = riskRepository.overrideScore(normalized, score, request.reason().trim(), operator(request.operator())).orElse(null);
        if (updated == null) {
            return ApiResult.fail(404, "SCORE_USER_NOT_FOUND");
        }
        audit("K4_SCORE_OVERRIDDEN", "RISK_SCORE_USER", normalized, null, operator(request.operator()), Map.of(
                "userNo", normalized,
                "overrideScore", score,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        RiskScoreUserView scoreUser = riskRepository.findScoreUser(normalized).orElse(null);
        triggerKycReviewIfScoreCrossed(scoreUser, request.reason().trim(), operator(request.operator()), idempotencyKey.trim());
        return scoreUser == null ? scoreUser(normalized) : ApiResult.ok(scoreUser);
    }

    public ApiResult<RiskScoreUserView> recomputeScore(String userNo, String idempotencyKey, RiskScoreCommandRequest request) {
        ApiResult<RiskScoreUserView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalized = trimmed(userNo);
        if (!StringUtils.hasText(normalized)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "SCORE_USER_REQUIRED");
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("K", "score_user", normalized) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        RiskScoreUserView updated = riskRepository.recomputeScore(normalized).orElse(null);
        if (updated == null) {
            return ApiResult.fail(404, "SCORE_USER_NOT_FOUND");
        }
        audit("K4_SCORE_RECOMPUTED", "RISK_SCORE_USER", normalized, null, operator(request.operator()), Map.of(
                "userNo", normalized,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> kycReviewOverview() {
        return kycReviewOverview(null);
    }

    public ApiResult<Map<String, Object>> kycReviewOverview(RiskKycReviewOverviewQueryRequest request) {
        int ticketPageNum = normalizePageNum(request == null ? null : request.ticketPageNum());
        int ticketPageSize = normalizeLimit(request == null ? null : request.ticketPageSize(), 5, 50);
        String ticketFilter = normalizeKycTicketFilter(request == null ? null : request.ticketFilter());
        return ApiResult.ok(riskRepository.kycReviewOverview(ticketPageNum, ticketPageSize, ticketFilter));
    }

    public ApiResult<Map<String, Object>> updateKycReviewParam(String key, String idempotencyKey, RiskParamUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey = trimmed(key);
        String value = trimmed(request.value());
        if (!StringUtils.hasText(normalizedKey) || !StringUtils.hasText(value)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K5_PARAM_VALUE_REQUIRED");
        }
        value = normalizeK5Param(normalizedKey, value);
        if (!StringUtils.hasText(value)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K5_PARAM_VALUE_INVALID");
        }
        Map<String, Object> updated = riskRepository.updateKycReviewParam(normalizedKey, value);
        audit("K5_KYC_REVIEW_PARAM_CHANGED", "RISK_KYC_REVIEW_PARAM", normalizedKey, null, operator(request.operator()), Map.of(
                "key", normalizedKey,
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> decideKycReviewTicket(String ticketId, String idempotencyKey, RiskKycReviewDecisionRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedTicket = trimmed(ticketId);
        String decision = trimmed(request.decision()).toLowerCase(Locale.ROOT);
        if (!StringUtils.hasText(normalizedTicket) || !KYC_REVIEW_DECISIONS.contains(decision)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K5_REVIEW_DECISION_INVALID");
        }
        KycReviewTicketContext ticket = riskRepository.findKycReviewTicket(normalizedTicket).orElse(null);
        if (ticket == null) {
            return ApiResult.fail(404, "K5_REVIEW_TICKET_NOT_FOUND");
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("K", "ticket", normalizedTicket) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        if (!riskRepository.updateKycReviewTicketStatus(normalizedTicket, decision, request.reason().trim(), operator(request.operator()))) {
            return ApiResult.fail(404, "K5_REVIEW_TICKET_NOT_FOUND");
        }
        Map<String, Object> downstream = applyKycReviewDecision(ticket, decision, request.reason().trim(), operator(request.operator()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("ticketId", normalizedTicket);
        detail.put("userNo", ticket.userNo());
        detail.put("decision", decision);
        detail.put("kycStatus", "passed".equals(decision) ? "APPROVED" : "REJECTED");
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("downstream", downstream);
        audit("K5_KYC_REVIEW_DECIDED", "RISK_KYC_REVIEW_TICKET", normalizedTicket, null, operator(request.operator()), detail);
        return kycReviewOverview();
    }

    public ApiResult<Map<String, Object>> createManualKycReviewTicket(String idempotencyKey, RiskKycManualReviewRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String userNo = trimmed(request.userNo());
        if (!StringUtils.hasText(userNo)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "K5_REVIEW_USER_REQUIRED");
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("K", "user", userNo) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String ticketId = "KR-M-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        riskRepository.createManualKycReviewTicket(ticketId, userNo, request.reason().trim(), operator(request.operator()));
        audit("K5_KYC_REVIEW_MANUAL_CREATED", "RISK_KYC_REVIEW_TICKET", ticketId, null, operator(request.operator()), Map.of(
                "ticketId", ticketId,
                "userNo", userNo,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return kycReviewOverview();
    }

    private void triggerKycReviewIfScoreCrossed(RiskScoreUserView user, String reason, String operator, String idempotencyKey) {
        if (user == null || user.effectiveScore() == null) {
            return;
        }
        int threshold = riskRepository.kycReviewTriggerScore();
        if (threshold < 70 || threshold > 100 || user.effectiveScore() < threshold) {
            return;
        }
        if (riskRepository.hasOpenKycReviewTicket(user.userNo())) {
            return;
        }
        String ticketId = "KR-K4-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8).toUpperCase(Locale.ROOT);
        riskRepository.createScoreTriggeredKycReviewTicket(ticketId, user.userNo(), user.effectiveScore(), threshold, reason, operator);
        audit("K5_KYC_REVIEW_TRIGGERED_BY_SCORE", "RISK_KYC_REVIEW_TICKET", ticketId, null, operator, Map.of(
                "ticketId", ticketId,
                "userNo", user.userNo(),
                "effectiveScore", user.effectiveScore(),
                "threshold", threshold,
                "source", "K4_SCORE_OVERRIDE",
                "reason", reason,
                "idempotencyKey", idempotencyKey));
    }

    private Map<String, Object> applyKycReviewDecision(KycReviewTicketContext ticket, String decision, String reason, String operator) {
        Map<String, Object> downstream = new LinkedHashMap<>();
        String kycStatus = "passed".equals(decision) ? "APPROVED" : "REJECTED";
        downstream.put("c4KycUpdated", userKycStatusFacade.updateKycStatusByUserNo(ticket.userNo(), kycStatus, reason, operator));
        String sourceDomain = ticketInfoValue(ticket.infoJson(), "sourceDomain");
        String sourceNo = ticketInfoValue(ticket.infoJson(), "sourceNo");
        downstream.put("sourceDomain", sourceDomain);
        downstream.put("sourceNo", sourceNo);
        if (!StringUtils.hasText(sourceDomain) || !StringUtils.hasText(sourceNo)) {
            downstream.put("sourceUpdated", false);
            return downstream;
        }
        boolean sourceUpdated = false;
        if ("D2".equalsIgnoreCase(sourceDomain)) {
            sourceUpdated = "passed".equals(decision)
                    ? financeWithdrawalKycReviewFacade.releaseWithdrawalReview(sourceNo, reason, operator)
                    : financeWithdrawalKycReviewFacade.rejectWithdrawalReview(sourceNo, reason, operator);
        } else if ("G2".equalsIgnoreCase(sourceDomain)) {
            sourceUpdated = "passed".equals(decision)
                    ? marketExchangeKycReviewFacade.releaseExchangeReview(sourceNo, reason, operator)
                    : marketExchangeKycReviewFacade.rejectExchangeReview(sourceNo, reason, operator);
        }
        downstream.put("sourceUpdated", sourceUpdated);
        return downstream;
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
            case "rewardRisk.lockMode" -> Set.of("risk_bucket", "direct").contains(value) ? value : "";
            case "rewardRisk.usdtAmount" -> normalizeBoundedInteger(value, 0, 10_000);
            case "rewardRisk.nexAmount" -> normalizeBoundedInteger(value, 0, 1_000_000);
            case "otpGate.resendSeconds" -> normalizeBoundedInteger(value, 30, 300);
            case "otpGate.captchaAfterSends" -> normalizeBoundedInteger(value, 1, 10);
            case "otpGate.otpTtlSeconds" -> normalizeOtpTtlSeconds(value);
            case "otpGate.maxVerifyAttempts" -> normalizeBoundedInteger(value, 1, 10);
            case "otpGate.captchaTicketTtlSeconds" -> normalizeBoundedInteger(value, 30, 600);
            default -> value;
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
        return new RiskArbitrageParamView(param.key(), param.name(), value, param.sub(), param.note());
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
            case "金额", "largeAmountUsdt" -> normalizeK3AmountRule(condition);
            case "速度", "velocity24h" -> normalizeK3VelocityRule(condition);
            case "新账户", "newAccountProtectDays" -> normalizeK3NewAccountRule(condition);
            case "地址信誉", "addressReputationSource" -> normalizeK3AddressReputationRule(condition);
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
        String normalized = value
                .replace("／", "/")
                .replace("＋", "+")
                .replaceAll("\\s*/\\s*", " / ")
                .replaceAll("\\s*\\+\\s*", " + ")
                .trim();
        return K3_ADDRESS_REPUTATION_RULES.contains(normalized) ? normalized : "";
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
                            meta.icon());
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
            String auditAction
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

    @Override
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
                RiskClusterStatusRequest req = new RiskClusterStatusRequest(status, reason, operator);
                return updateMultiAccountClusterStatus(str(p, "clusterId"), idem, req);
            }
            case "k2_row_flag", "k2_row_blockgift", "k2_row_boardflag", "k2_row_freeze" -> {
                String action = switch (cmd.op()) {
                    case "k2_row_flag" -> "mark";
                    case "k2_row_blockgift" -> "block-gift";
                    case "k2_row_boardflag" -> "board-flag";
                    default -> "freeze-cluster";
                };
                RiskArbitrageActionRequest req = new RiskArbitrageActionRequest(reason, operator);
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
}

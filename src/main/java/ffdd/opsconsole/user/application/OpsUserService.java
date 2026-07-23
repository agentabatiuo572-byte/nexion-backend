package ffdd.opsconsole.user.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.shared.security.JwtTokenProvider;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.bi.facade.BiKycRegulatoryExportFacade;
import ffdd.opsconsole.bi.facade.KycRegulatoryExportJob;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.domain.AuditReplayCommand;
import ffdd.opsconsole.platform.domain.AuditReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.RiskUserStateFacade;
import ffdd.opsconsole.risk.facade.KycReviewTriggerResult;
import ffdd.opsconsole.risk.facade.RiskKycReviewFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.user.domain.UserAccountActionOverview;
import ffdd.opsconsole.user.domain.UserAccountActionContext;
import ffdd.opsconsole.user.domain.UserAccountListEntryView;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentDetail;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentView;
import ffdd.opsconsole.user.domain.UserCredentialParamView;
import ffdd.opsconsole.user.domain.UserImpersonationSessionView;
import ffdd.opsconsole.user.domain.UserKycKeyValue;
import ffdd.opsconsole.user.domain.UserKycLedgerRow;
import ffdd.opsconsole.user.domain.UserKycOverview;
import ffdd.opsconsole.user.domain.UserKycRecord;
import ffdd.opsconsole.user.domain.UserKycStatusHistoryView;
import ffdd.opsconsole.user.domain.UserKycReverificationView;
import ffdd.opsconsole.user.domain.UserKycStats;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserProfileExportFile;
import ffdd.opsconsole.user.domain.UserRegistrationRiskK1GuardView;
import ffdd.opsconsole.user.domain.UserRegistrationRiskOverview;
import ffdd.opsconsole.user.domain.UserRegistrationRiskParamView;
import ffdd.opsconsole.user.domain.UserRegistrationRiskStats;
import ffdd.opsconsole.user.domain.UserReadonlyDeviceView;
import ffdd.opsconsole.user.domain.UserSecurityOverview;
import ffdd.opsconsole.user.domain.UserSecurityStats;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.domain.UserSecurityUserRow;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.dto.UserAccountListRemoveRequest;
import ffdd.opsconsole.user.dto.UserAccountListUpsertRequest;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentRequest;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentQueryRequest;
import ffdd.opsconsole.user.dto.UserAssetAdjustmentReviewRequest;
import ffdd.opsconsole.user.dto.UserCredentialParamUpdateRequest;
import ffdd.opsconsole.user.dto.UserImpersonationTerminateRequest;
import ffdd.opsconsole.user.dto.UserKycExportRequest;
import ffdd.opsconsole.user.dto.UserKycNetworkUpdateRequest;
import ffdd.opsconsole.user.dto.UserKycReviewTriggerRequest;
import ffdd.opsconsole.user.dto.UserKycReverificationRequest;
import ffdd.opsconsole.user.dto.UserKycStatusUpdateRequest;
import ffdd.opsconsole.user.dto.UserImpersonationRequest;
import ffdd.opsconsole.user.dto.UserProfileExportRequest;
import ffdd.opsconsole.user.dto.UserQueryRequest;
import ffdd.opsconsole.user.dto.UserRegistrationRiskParamUpdateRequest;
import ffdd.opsconsole.user.dto.UserSecurityActionRequest;
import ffdd.opsconsole.user.dto.UserSessionRevokeAllRequest;
import ffdd.opsconsole.user.dto.UserSessionRevokeRequest;
import ffdd.opsconsole.user.dto.UserStatusUpdateRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsUserService implements ffdd.opsconsole.platform.domain.AuditReplayable {
    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "FROZEN", "BANNED", "RESTRICTED");
    private static final Set<String> ASSETS = Set.of("USDT", "NEX");
    private static final Set<String> DIRECTIONS = Set.of("CREDIT", "DEBIT");
    private static final Set<String> ADJUSTMENT_STATUSES = Set.of("PENDING", "PENDING_REVIEW", "APPROVED", "REJECTED", "SUSPENDED");
    private static final Set<String> REVIEWABLE_ADJUSTMENT_STATUSES = Set.of("PENDING", "PENDING_REVIEW", "SUSPENDED");
    private static final Set<String> C3_BASE_EXECUTOR_ROLES = Set.of("SUPER_ADMIN", "FINANCE", "SUPPORT");
    private static final Set<String> C3_LARGE_EXECUTOR_ROLES = Set.of("SUPER_ADMIN", "FINANCE_LEAD");
    private static final Set<String> C3_REVIEWER_ROLES = Set.of("SUPER_ADMIN", "FINANCE_LEAD");
    private static final Set<String> C3_REVERSAL_ROLES = Set.of("SUPER_ADMIN", "FINANCE_LEAD");
    private static final Set<String> C3_REASON_CODES = Set.of(
            "SUPPORT_COMPENSATION", "SYSTEM_CORRECTION", "CAMPAIGN_REISSUE", "DISPUTE_RETURN", "REVERSAL", "OPS_USER_ADJUSTMENT");
    private static final BigDecimal C3_LARGE_THRESHOLD_USD = new BigDecimal("500");
    private static final BigDecimal C3_MAX_AMOUNT_USD = new BigDecimal("10000");
    private static final String C3_NEX_PRICE_KEY = "wallet.exchange.nex_usdt_price";
    private static final Set<String> KYC_STATUSES = Set.of("APPROVED", "PENDING", "NONE", "REJECTED");
    private static final Set<String> ACCOUNT_LIST_KINDS = Set.of("ALLOW", "BLOCK");
    private static final Set<String> C2_FREEZE_REASON_CODES = Set.of(
            "RISK_HIT", "AML_REVIEW", "USER_APPEAL", "JUDICIAL_ASSISTANCE", "OTHER");
    private static final Set<String> C2_IMPERSONATION_REASON_CODES = Set.of(
            "USER_ISSUE_REPRO", "DISPLAY_ANOMALY", "OTHER");
    private static final Set<Integer> C2_IMPERSONATION_TTLS = Set.of(5, 10, 15, 30);
    private static final Set<String> ALLOWED_KYC_NETWORKS = Set.of("TRC20", "ERC20", "BTC", "ETH", "BSC", "SOL", "POLYGON");
    private static final String AUTH_CONFIG_GROUP = "auth";
    private static final String KYC_CONFIG_GROUP = "kyc";
    private static final String KYC_NETWORK_WHITELIST_KEY = "kyc.network_whitelist";
    private static final String DEFAULT_KYC_NETWORK_WHITELIST = "TRC20 / ERC20 / BTC / ETH";
    private static final String CAPTCHA_OFF_WINDOW_KEY = RegistrationRiskCaptchaWindow.CONFIG_KEY;
    private static final String C6_CONFIG_VERSION_KEY = RegistrationRiskCaptchaWindow.VERSION_KEY;
    private static final String K1_REJECT_CODE = "MULTI_ACCOUNT_PARAM_BELONGS_TO_K1";
    private static final String K1_PATH = "/risk/multi-account";
    private static final String DEFAULT_SECURITY_LOOKUP_KEY = "usr_2231";
    private static final String RESET_REQUIRED_PREFIX = "RESET_REQUIRED$";
    private static final Set<String> C5_KYC_ACTIONS = Set.of(
            "DISABLE_2FA", "PASSWORD_RESET", "UNLOCK_SHORT", "UNLOCK_LONG");
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
    private static final DateTimeFormatter KYC_PAIRED_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final Set<String> K1_REGISTRATION_RISK_PARAM_KEYS = Set.of(
            "maxSignupPerIp24h",
            "maxAccountsPerDevice",
            "maxAccountsPerPaymentInstrument");
    private static final Set<String> K2_OTP_PARAM_KEYS = Set.of("otpTtl", "otpCooldown", "otpMax24h");
    private static final List<CredentialParamDefinition> CREDENTIAL_PARAM_DEFINITIONS = List.of(
            new CredentialParamDefinition(
                    "accessTtl",
                    "访问令牌有效期",
                    "auth.session.access_ttl_hours",
                    "小时",
                    1,
                    24,
                    4,
                    false,
                    "影响后台与用户侧访问令牌过期时间"),
            new CredentialParamDefinition(
                    "refreshTtl",
                    "刷新令牌有效期",
                    "auth.session.refresh_ttl_days",
                    "天",
                    7,
                    90,
                    30,
                    false,
                    "影响长期登录刷新窗口"),
            new CredentialParamDefinition(
                    "sessionIdle",
                    "会话空闲有效期",
                    "auth.session.idle_ttl_days",
                    "天",
                    7,
                    90,
                    30,
                    false,
                    "影响无操作会话保留时间"),
            new CredentialParamDefinition(
                    "stepUpDays",
                    "敏感操作二次验证有效期",
                    "auth.session.step_up_days",
                    "天",
                    1,
                    30,
                    7,
                    true,
                    "只读展示，敏感操作仍由后端强制二次验证"));
    private static final List<RegistrationRiskParamDefinition> REGISTRATION_RISK_PARAM_DEFINITIONS = List.of(
            new RegistrationRiskParamDefinition(
                    "otp",
                    "otpTtl",
                    "有效期",
                    "过期作废，客户端只做格式校验，真值在服务器",
                    "auth.risk.otp_ttl_minutes",
                    "分钟",
                    1,
                    15,
                    5,
                    null,
                    null,
                    0,
                    0,
                    0,
                    "范围 1-15 分钟，新发验证码按新值执行"),
            new RegistrationRiskParamDefinition(
                    "otp",
                    "otpCooldown",
                    "重发冷却",
                    "防短信轰炸的第一道闸",
                    "auth.risk.otp_cooldown_seconds",
                    "秒",
                    30,
                    300,
                    60,
                    null,
                    null,
                    0,
                    0,
                    0,
                    "范围 30-300 秒，服务端实时执行"),
            new RegistrationRiskParamDefinition(
                    "otp",
                    "otpMax24h",
                    "同号 24h 上限",
                    "超过就要先过人机验证才发",
                    "auth.risk.otp_max_24h",
                    "次",
                    1,
                    10,
                    3,
                    null,
                    null,
                    0,
                    0,
                    0,
                    "范围 1-10 次，同时作为 CAPTCHA 触发阈值"),
            new RegistrationRiskParamDefinition(
                    "lock",
                    "lockShort",
                    "短锁",
                    "密码或两步验证连错触发，锁定期间一切登录和验证码都拒绝",
                    "auth.risk.login_lock_threshold",
                    "次",
                    3,
                    10,
                    5,
                    "auth.risk.lock_duration_minutes",
                    "分钟",
                    5,
                    60,
                    15,
                    "次数 3-10，时长 5-60 分钟"),
            new RegistrationRiskParamDefinition(
                    "lock",
                    "lockLong",
                    "长锁",
                    "连错升级，触发后强制走密码重置",
                    "auth.risk.login_long_lock_threshold",
                    "次",
                    5,
                    20,
                    10,
                    "auth.risk.long_lock_duration_hours",
                    "小时",
                    12,
                    48,
                    24,
                    "次数 5-20，时长 12-48 小时"));

    private final UserOpsRepository userRepository;
    private final TreasuryCoverageFacade coverageFacade;
    private final PlatformConfigFacade configFacade;
    private final FinanceWithdrawalControlFacade financeWithdrawalControlFacade;
    private final RiskUserStateFacade riskUserStateFacade;
    private final AuditLogService auditLogService;
    private final AdminIdempotencyService idempotencyService;
    private final AdminOperatorRoleResolver roleResolver;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;
    private final ffdd.opsconsole.platform.mapper.AuditObjectLockMapper lockMapper;
    private final EventOutboxService outboxService;
    private final JwtTokenProvider tokenProvider;
    private final RiskKycReviewFacade riskKycReviewFacade;
    private final BiKycRegulatoryExportFacade biKycExportFacade;
    private final Clock clock;

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>(userRepository.overview());
        response.put("domain", "C");
        response.put("capabilities", List.of("UserProfile", "KycReview", "AccountSecurity", "ManualAssetAdjustment"));
        response.put("sunsetCompatibility", List.of("Premium history is read-only", "NEX v2 maturity is historical", "Points adjustments are rejected"));
        response.put("sources", List.of("nx_user", "nx_user_session", "nx_wallet_asset_adjustment"));
        return ApiResult.ok(response);
    }

    public ApiResult<List<UserAccountView>> profiles(UserQueryRequest request) {
        int limit = normalizeLimit(request == null ? null : request.limit(), 50, 100);
        return ApiResult.ok(userRepository.search(
                request == null ? null : request.keyword(),
                request == null ? null : request.status(),
                request == null ? null : request.kycStatus(),
                limit));
    }

    public ApiResult<PageResult<UserAccountView>> profilePage(UserQueryRequest request) {
        String validationError = validateProfileQuery(request);
        if (validationError != null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), validationError);
        }
        PageResult<UserAccountView> page = userRepository.pageProfiles(request);
        requiredAudit(
                "ADMIN.USER_PROFILE_SEARCHED",
                "USER_PROFILE_SEARCH",
                "C1",
                null,
                Map.of(
                        "filterHash", filterHash(request),
                        "resultCount", page == null ? 0 : page.getTotal(),
                        "role", text(roleResolver.resolveCode())));
        return ApiResult.ok(page);
    }

    public UserProfileExportFile exportProfileExcel(String idempotencyKey, UserProfileExportRequest request) {
        String normalizedKey = requireText(idempotencyKey, "IDEMPOTENCY_KEY_REQUIRED");
        UserQueryRequest query = exportQuery(request, 1, 200);
        String validationError = validateProfileQuery(query);
        if (validationError != null) {
            throw new IllegalArgumentException(validationError);
        }
        return idempotencyService.execute(
                "C1_USER_LIST_EXPORT",
                normalizedKey,
                filterHash(query),
                UserProfileExportFile.class,
                () -> buildProfileExport(normalizedKey, request));
    }

    private UserProfileExportFile buildProfileExport(String idempotencyKey, UserProfileExportRequest request) {
        int pageSize = 200;
        UserQueryRequest firstQuery = exportQuery(request, 1, pageSize);
        PageResult<UserAccountView> first = userRepository.pageProfiles(firstQuery);
        List<UserAccountView> rows = new ArrayList<>();
        if (first.getRecords() != null) {
            rows.addAll(first.getRecords());
        }
        long total = Math.max(first.getTotal(), rows.size());
        int totalPages = (int) Math.min(25, Math.max(1, (total + pageSize - 1) / pageSize));
        for (int pageNum = 2; pageNum <= totalPages; pageNum += 1) {
            PageResult<UserAccountView> page = userRepository.pageProfiles(exportQuery(request, pageNum, pageSize));
            if (page.getRecords() != null) {
                rows.addAll(page.getRecords());
            }
        }
        LocalDateTime createdAt = LocalDateTime.now(clock);
        String jobNo = "C1-USER-EXP-"
                + DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS").format(createdAt)
                + "-"
                + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        String role = text(roleResolver.resolveCode()).trim().toUpperCase(Locale.ROOT);
        String exportFilterHash = filterHash(exportQuery(request, 1, pageSize));
        requiredAudit(
                "ADMIN.USER_LIST_EXPORTED",
                "USER_PROFILE_EXPORT",
                jobNo,
                null,
                Map.of(
                        "jobNo", jobNo,
                        "rowCount", rows.size(),
                        "filterHash", exportFilterHash,
                        "idempotencyKey", idempotencyKey,
                        "masked", true));
        outboxService.publish("USER_PROFILE_EXPORT", jobNo, "ADMIN_USER_LIST_EXPORTED", Map.of(
                "filter_hash", exportFilterHash,
                "row_count", rows.size(),
                "exporter_operator", AdminActorResolver.resolve("SYSTEM"),
                "exporter_role", role,
                "occurred_at", Instant.now().toString()));
        return new UserProfileExportFile(
                jobNo + ".csv",
                profileExportCsv(rows, role),
                rows.size());
    }

    private byte[] profileExportCsv(List<UserAccountView> rows, String role) {
        StringBuilder csv = new StringBuilder(4096 + rows.size() * 320);
        boolean full = Set.of("SUPER_ADMIN", "RISK", "AUDITOR").contains(role);
        if (!full) {
            csv.append('\ufeff')
                    .append("用户编码,昵称,手机号(脱敏),国家/地区,生命周期,V-Rank,KYC,状态,注册时间,最近登录\r\n");
            for (UserAccountView row : rows) {
                csv.append(csvCell(row.userNo())).append(',')
                        .append(csvCell(row.nickname())).append(',')
                        .append(csvCell(row.phoneMasked())).append(',')
                        .append(csvCell(row.countryCode())).append(',')
                        .append(csvCell(row.userLevel())).append(',')
                        .append(csvCell(row.vRank())).append(',')
                        .append(csvCell(row.kycStatus())).append(',')
                        .append(csvCell(row.status())).append(',')
                        .append(csvCell(dateText(row.registeredAt()))).append(',')
                        .append(csvCell(dateText(row.lastLoginAt()))).append("\r\n");
            }
            return csv.toString().getBytes(StandardCharsets.UTF_8);
        }
        csv.append('\ufeff')
                .append("用户编码,昵称,手机号(脱敏),国家/地区,生命周期,V-Rank,KYC,状态,风险分,风险等级,设备数,活跃设备数,USDT余额,NEX余额,注册时间,最近登录\r\n");
        for (UserAccountView row : rows) {
            csv.append(csvCell(row.userNo())).append(',')
                    .append(csvCell(row.nickname())).append(',')
                    .append(csvCell(row.phoneMasked())).append(',')
                    .append(csvCell(row.countryCode())).append(',')
                    .append(csvCell(row.userLevel())).append(',')
                    .append(csvCell(row.vRank())).append(',')
                    .append(csvCell(row.kycStatus())).append(',')
                    .append(csvCell(row.status())).append(',')
                    .append(csvCell(row.riskScore())).append(',')
                    .append(csvCell(row.riskBand())).append(',')
                    .append(csvCell(row.deviceCount())).append(',')
                    .append(csvCell(row.activeDeviceCount())).append(',')
                    .append(csvCell(row.walletUsdt())).append(',')
                    .append(csvCell(row.walletNex())).append(',')
                    .append(csvCell(dateText(row.registeredAt()))).append(',')
                    .append(csvCell(dateText(row.lastLoginAt()))).append("\r\n");
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String csvCell(Object value) {
        String raw = value instanceof BigDecimal decimal ? decimal.toPlainString() : text(value);
        if (raw.startsWith("=") || raw.startsWith("+") || raw.startsWith("-") || raw.startsWith("@")) {
            raw = "'" + raw;
        }
        return "\"" + raw.replace("\"", "\"\"") + "\"";
    }

    private String dateText(LocalDateTime value) {
        return value == null ? "—" : value.toString().replace('T', ' ');
    }

    private UserQueryRequest exportQuery(UserProfileExportRequest request, int pageNum, int pageSize) {
        return new UserQueryRequest(
                request == null ? null : request.keyword(),
                request == null ? null : request.status(),
                request == null ? null : request.kycStatus(),
                request == null ? null : request.riskMin(),
                pageNum,
                pageSize,
                null,
                request == null ? null : request.userId(),
                request == null ? null : request.phoneHash(),
                request == null ? null : request.phoneMasked(),
                request == null ? null : request.tier(),
                request == null ? null : request.vRank(),
                request == null ? null : request.referralCode(),
                request == null ? null : request.depositMin(),
                request == null ? null : request.depositMax(),
                request == null ? null : request.walletUsdtMin(),
                request == null ? null : request.walletUsdtMax(),
                request == null ? null : request.walletNexMin(),
                request == null ? null : request.walletNexMax(),
                request == null ? null : request.riskBand(),
                request == null ? null : request.joinedFrom(),
                request == null ? null : request.joinedTo());
    }

    private String validateProfileQuery(UserQueryRequest request) {
        if (request == null) {
            return null;
        }
        if (looksLikeRawPhone(request.keyword())) {
            return "C1_RAW_PHONE_SEARCH_FORBIDDEN";
        }
        if (StringUtils.hasText(request.phoneHash())
                && !request.phoneHash().trim().matches("(?i)[0-9a-f]{64}")) {
            return "C1_PHONE_HASH_INVALID";
        }
        if (StringUtils.hasText(request.phoneMasked())
                && !request.phoneMasked().trim().matches("[0-9]{3}\\*{4}[0-9]{4}")) {
            return "C1_PHONE_MASK_INVALID";
        }
        if (request.userId() != null && request.userId() <= 0) {
            return "C1_USER_ID_INVALID";
        }
        if (request.pageNum() != null && request.pageNum() < 1) {
            return "C1_PAGE_NUM_INVALID";
        }
        if (request.pageSize() != null && (request.pageSize() < 1 || request.pageSize() > 200)) {
            return "C1_PAGE_SIZE_INVALID";
        }
        if (request.pageNum() != null
                && request.pageSize() != null
                && ((long) request.pageNum() - 1L) * request.pageSize() > Integer.MAX_VALUE) {
            return "C1_PAGE_NUM_INVALID";
        }
        if (invalidRange(request.depositMin(), request.depositMax())
                || invalidRange(request.walletUsdtMin(), request.walletUsdtMax())
                || invalidRange(request.walletNexMin(), request.walletNexMax())) {
            return "C1_AMOUNT_RANGE_INVALID";
        }
        if (StringUtils.hasText(request.riskBand())
                && !Set.of("LOW", "MEDIUM", "HIGH").contains(request.riskBand().trim().toUpperCase(Locale.ROOT))) {
            return "C1_RISK_BAND_INVALID";
        }
        try {
            LocalDate joinedFrom = StringUtils.hasText(request.joinedFrom()) ? LocalDate.parse(request.joinedFrom().trim()) : null;
            LocalDate joinedTo = StringUtils.hasText(request.joinedTo()) ? LocalDate.parse(request.joinedTo().trim()) : null;
            if (joinedFrom != null && joinedTo != null && joinedFrom.isAfter(joinedTo)) {
                return "C1_JOINED_RANGE_INVALID";
            }
        } catch (DateTimeParseException ex) {
            return "C1_JOINED_DATE_INVALID";
        }
        return null;
    }

    private boolean invalidRange(BigDecimal minimum, BigDecimal maximum) {
        return (minimum != null && minimum.signum() < 0)
                || (maximum != null && maximum.signum() < 0)
                || (minimum != null && maximum != null && minimum.compareTo(maximum) > 0);
    }

    private boolean looksLikeRawPhone(String value) {
        if (!StringUtils.hasText(value) || !value.trim().matches("[+0-9()\\s-]+")) {
            return false;
        }
        int digits = value.replaceAll("[^0-9]", "").length();
        return digits >= 10 && digits <= 15;
    }

    private String filterHash(Object value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(String.valueOf(value).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA256_NOT_AVAILABLE", ex);
        }
    }

    public ApiResult<UserKycOverview> kycOverview(String status, Integer limit) {
        return kycOverview(status, 1, limit, limit);
    }

    public ApiResult<UserKycOverview> kycOverview(String status, Integer pageNum, Integer pageSize, Integer limit) {
        String normalizedStatus;
        try {
            normalizedStatus = normalizeOptionalKycStatus(status);
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        int normalizedPageNum = normalizePageNum(pageNum);
        int normalizedPageSize = pageSize == null ? normalizeLimit(limit, 20, 100) : normalizeLimit(pageSize, 20, 100);
        PageResult<UserKycRecord> recordPage = userRepository.pageKycRecords(normalizedStatus, normalizedPageNum, normalizedPageSize);
        long total = userRepository.pageKycRecords(null, 1, 1).getTotal();
        long verified = userRepository.pageKycRecords("APPROVED", 1, 1).getTotal();
        long unverified = userRepository.pageKycRecords("NONE", 1, 1).getTotal();
        long inReview = userRepository.pageKycRecords("PENDING", 1, 1).getTotal();
        long rejected = userRepository.pageKycRecords("REJECTED", 1, 1).getTotal();
        String pct = total == 0 ? "0.0" : BigDecimal.valueOf(verified)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, java.math.RoundingMode.HALF_UP)
                .toPlainString();
        UserKycOverview overview = new UserKycOverview(
                new UserKycStats(total, verified, unverified, inReview, rejected, pct, 1),
                kycNetworkWhitelist(),
                recordPage.getRecords().stream().map(record -> kycLedgerRow(record, List.of())).toList(),
                List.of("KYC authority ledger", "wallet pairing authority", "governed network allow-list", "required audit trail"),
                List.of("APPROVED opens withdrawal/exchange gates and is blocked when B1 coverage is below redline",
                        "K5 review creation never rewrites the current KYC status"));
        return ApiResult.ok(overview);
    }

    public ApiResult<UserKycLedgerRow> kycDetail(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        return userRepository.findKycRecord(userId)
                .map(record -> ApiResult.ok(kycLedgerRow(record, userRepository.kycStatusHistory(userId, 50))))
                .orElseGet(() -> ApiResult.fail(404, "KYC_PROFILE_NOT_FOUND"));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<UserKycLedgerRow> verifyKyc(Long userId, String idempotencyKey, UserKycStatusUpdateRequest request) {
        return changeKycStatus(userId, idempotencyKey, request, "APPROVED", "C4_MANUAL_VERIFY");
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<UserKycLedgerRow> revokeKyc(Long userId, String idempotencyKey, UserKycStatusUpdateRequest request) {
        return changeKycStatus(userId, idempotencyKey, request, "NONE", "C4_MANUAL_REVOKE");
    }

    /** Retained only for binary compatibility; the ambiguous status mutation route is retired. */
    @Deprecated
    public ApiResult<UserKycLedgerRow> updateKycStatus(Long userId, String idempotencyKey, UserKycStatusUpdateRequest request) {
        return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "C4_LEGACY_STATUS_ROUTE_RETIRED");
    }

    private ApiResult<UserKycLedgerRow> changeKycStatus(
            Long userId, String idempotencyKey, UserKycStatusUpdateRequest request,
            String nextStatus, String source) {
        ApiResult<UserKycLedgerRow> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            c4RejectedAudit(userId, idempotencyKey, request, null, nextStatus, guard.getMessage());
            return guard;
        }
        String expectedState;
        String reasonCode;
        String evidenceRef;
        try {
            expectedState = normalizeKycStatus(request.expectedState());
            reasonCode = requireKycReasonCode(request.reasonCode());
            evidenceRef = requireKycEvidence(request.evidenceRef());
            requireKycReason(request.reason());
        } catch (IllegalArgumentException ex) {
            c4RejectedAudit(userId, idempotencyKey, request, null, nextStatus, ex.getMessage());
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("userId", userId);
        fingerprint.put("expectedState", expectedState);
        fingerprint.put("nextStatus", nextStatus);
        fingerprint.put("reasonCode", reasonCode);
        fingerprint.put("reason", request.reason().trim());
        fingerprint.put("evidenceRef", evidenceRef);
        try {
            return idempotentC2("C4_KYC_STATUS:" + userId, idempotencyKey, fingerprint,
                    () -> doChangeKycStatus(userId, idempotencyKey.trim(), request, expectedState,
                            nextStatus, reasonCode, evidenceRef, source));
        } catch (RuntimeException ex) {
            c4RejectedAudit(userId, idempotencyKey, request, null, nextStatus,
                    "C4_PERSISTENCE_FAILED:" + ex.getClass().getSimpleName());
            throw ex;
        }
    }

    private ApiResult<UserKycLedgerRow> doChangeKycStatus(
            Long userId, String idempotencyKey, UserKycStatusUpdateRequest request,
            String expectedState, String nextStatus, String reasonCode, String evidenceRef, String source) {
        UserKycRecord before = userRepository.findKycRecord(userId).orElse(null);
        if (before == null) {
            c4RejectedAudit(userId, idempotencyKey, request, null, nextStatus, "KYC_PROFILE_NOT_FOUND");
            return ApiResult.fail(404, "KYC_PROFILE_NOT_FOUND");
        }
        String currentStatus = normalizeKycStatus(before.status());
        if (!currentStatus.equals(expectedState)) {
            c4RejectedAudit(userId, idempotencyKey, request, currentStatus, nextStatus, "KYC_EXPECTED_STATE_MISMATCH");
            return ApiResult.fail(409, "KYC_EXPECTED_STATE_MISMATCH");
        }
        if (currentStatus.equals(nextStatus)) {
            c4RejectedAudit(userId, idempotencyKey, request, currentStatus, nextStatus,
                    OpsErrorCode.INVALID_STATE_TRANSITION.name());
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if ("APPROVED".equals(nextStatus) && coverageBelowRedline()) {
            c4RejectedAudit(userId, idempotencyKey, request, currentStatus, nextStatus,
                    OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        String actor = operator(request.operator());
        if (!userRepository.transitionKycStatus(userId, currentStatus, before.version(), nextStatus,
                reasonCode, request.reason().trim(), evidenceRef, source, actor, idempotencyKey, null)) {
            c4RejectedAudit(userId, idempotencyKey, request, currentStatus, nextStatus, "KYC_CONCURRENTLY_CHANGED");
            return ApiResult.fail(409, "KYC_CONCURRENTLY_CHANGED");
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromStatus", currentStatus);
        detail.put("toStatus", nextStatus);
        detail.put("reasonCode", reasonCode);
        detail.put("reason", request.reason().trim());
        detail.put("evidenceRef", evidenceRef);
        detail.put("idempotencyKey", idempotencyKey);
        detail.put("source", source);
        c2RequiredAudit("C4_KYC_STATUS_CHANGED", "USER_KYC", String.valueOf(userId), userId, actor, detail);
        outboxService.publish("USER_KYC", String.valueOf(userId), "admin.kyc_status_changed", Map.of(
                "targetUserId", userId,
                "fromStatus", currentStatus,
                "toStatus", nextStatus,
                "reasonCode", reasonCode,
                "evidenceRef", evidenceRef,
                "operator", actor,
                "source", source,
                "occurredAt", Instant.now().toString()));
        UserKycRecord updated = userRepository.findKycRecord(userId).orElseThrow();
        return ApiResult.ok(kycLedgerRow(updated, userRepository.kycStatusHistory(userId, 50)));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> updateKycNetworkWhitelist(
            String idempotencyKey,
            UserKycNetworkUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            c4CommandFailureAudit("C4_KYC_NETWORK_WHITELIST_REJECTED", "USER_KYC_CONFIG",
                    KYC_NETWORK_WHITELIST_KEY, null, request == null ? null : request.operator(),
                    idempotencyKey, null, null, request == null ? null : request.reason(), guard.getMessage(), "REJECTED");
            return guard;
        }
        try {
            requireKycReason(request.reason());
        } catch (IllegalArgumentException ex) {
            c4CommandFailureAudit("C4_KYC_NETWORK_WHITELIST_REJECTED", "USER_KYC_CONFIG",
                    KYC_NETWORK_WHITELIST_KEY, null, request.operator(), idempotencyKey,
                    null, null, request.reason(), ex.getMessage(), "REJECTED");
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        String value;
        try {
            value = normalizeKycNetworkWhitelist(request.value());
        } catch (IllegalArgumentException ex) {
            c4CommandFailureAudit("C4_KYC_NETWORK_WHITELIST_REJECTED", "USER_KYC_CONFIG",
                    KYC_NETWORK_WHITELIST_KEY, null, request.operator(), idempotencyKey,
                    null, null, request.reason(), ex.getMessage(), "REJECTED");
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        String normalizedValue = value;
        try {
            return idempotentC2("C4_NETWORK_WHITELIST", idempotencyKey,
                    normalizedValue + "|" + request.reason().trim(), () -> {
                        configFacade.upsertAdminValue(KYC_NETWORK_WHITELIST_KEY, normalizedValue, "STRING", KYC_CONFIG_GROUP, request.reason().trim());
                        c2RequiredAudit("C4_KYC_NETWORK_WHITELIST_UPDATED", "USER_KYC_CONFIG", KYC_NETWORK_WHITELIST_KEY,
                                null, request.operator(), Map.of("value", normalizedValue, "reason", request.reason().trim(),
                                        "idempotencyKey", idempotencyKey.trim()));
                        return ApiResult.ok(Map.of("key", KYC_NETWORK_WHITELIST_KEY, "value", normalizedValue));
                    });
        } catch (RuntimeException ex) {
            c4CommandFailureAudit("C4_KYC_NETWORK_WHITELIST_FAILED", "USER_KYC_CONFIG",
                    KYC_NETWORK_WHITELIST_KEY, null, request.operator(), idempotencyKey,
                    null, null, request.reason(), "C4_PERSISTENCE_FAILED:" + ex.getClass().getSimpleName(), "FAILED");
            throw ex;
        }
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> triggerKycReview(
            Long userId, String idempotencyKey, UserKycReviewTriggerRequest request) {
        ApiResult<Map<String, Object>> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            c4CommandFailureAudit("C4_K5_REVIEW_TRIGGER_REJECTED", "RISK_KYC_REVIEW_TICKET",
                    userId == null ? "UNKNOWN" : String.valueOf(userId), userId,
                    request == null ? null : request.operator(), idempotencyKey,
                    request == null ? null : request.reasonCode(), request == null ? null : request.evidenceRef(),
                    request == null ? null : request.reason(), guard.getMessage(), "REJECTED");
            return guard;
        }
        String reasonCode;
        String evidenceRef;
        try {
            reasonCode = requireKycReasonCode(request.reasonCode());
            evidenceRef = requireKycEvidence(request.evidenceRef());
            requireKycReason(request.reason());
        } catch (IllegalArgumentException ex) {
            c4CommandFailureAudit("C4_K5_REVIEW_TRIGGER_REJECTED", "RISK_KYC_REVIEW_TICKET",
                    String.valueOf(userId), userId, request.operator(), idempotencyKey,
                    request.reasonCode(), request.evidenceRef(), request.reason(), ex.getMessage(), "REJECTED");
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        try {
            return idempotentC2("C4_K5_TRIGGER:" + userId, idempotencyKey,
                    userId + "|" + reasonCode + "|" + request.reason().trim() + "|" + evidenceRef,
                    () -> doTriggerKycReview(userId, idempotencyKey.trim(), request, reasonCode, evidenceRef));
        } catch (RuntimeException ex) {
            c4CommandFailureAudit("C4_K5_REVIEW_TRIGGER_FAILED", "RISK_KYC_REVIEW_TICKET",
                    String.valueOf(userId), userId, request.operator(), idempotencyKey,
                    reasonCode, evidenceRef, request.reason(), "C4_PERSISTENCE_FAILED:" + ex.getClass().getSimpleName(), "FAILED");
            throw ex;
        }
    }

    private ApiResult<Map<String, Object>> doTriggerKycReview(
            Long userId, String idempotencyKey, UserKycReviewTriggerRequest request,
            String reasonCode, String evidenceRef) {
        UserKycRecord record = userRepository.findKycRecord(userId).orElse(null);
        if (record == null) {
            c4CommandFailureAudit("C4_K5_REVIEW_TRIGGER_REJECTED", "RISK_KYC_REVIEW_TICKET",
                    String.valueOf(userId), userId, request.operator(), idempotencyKey,
                    reasonCode, evidenceRef, request.reason(), "KYC_PROFILE_NOT_FOUND", "REJECTED");
            return ApiResult.fail(404, "KYC_PROFILE_NOT_FOUND");
        }
        String actor = operator(request.operator());
        KycReviewTriggerResult result = riskKycReviewFacade.triggerManualReview(record.userNo(), actor, request.reason().trim());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("ticketId", result.ticketId());
        response.put("created", result.created());
        response.put("status", result.created() ? "CREATED" : "MERGED");
        response.put("kycStatus", record.status());
        response.put("message", result.reason());
        Map<String, Object> detail = new LinkedHashMap<>(response);
        detail.put("reasonCode", reasonCode);
        detail.put("reason", request.reason().trim());
        detail.put("evidenceRef", evidenceRef);
        detail.put("idempotencyKey", idempotencyKey);
        c2RequiredAudit("C4_K5_REVIEW_TRIGGERED", "RISK_KYC_REVIEW_TICKET", result.ticketId(), userId, actor, detail);
        outboxService.publish("RISK_KYC_REVIEW_TICKET", result.ticketId(), "risk.kyc_review_triggered", Map.of(
                "targetUserId", userId,
                "ticketId", result.ticketId(),
                "created", result.created(),
                "operator", actor,
                "source", "C4",
                "occurredAt", Instant.now().toString()));
        return ApiResult.ok(response);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> createKycExport(
            String idempotencyKey,
            UserKycExportRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            c4CommandFailureAudit("C4_KYC_MASKED_EXPORT_REJECTED", "USER_KYC_EXPORT", "PENDING",
                    null, request == null ? null : request.operator(), idempotencyKey, null, null,
                    request == null ? null : request.reason(), guard.getMessage(), "REJECTED");
            return guard;
        }
        try {
            requireKycReason(request.reason());
        } catch (IllegalArgumentException ex) {
            c4CommandFailureAudit("C4_KYC_MASKED_EXPORT_REJECTED", "USER_KYC_EXPORT", "PENDING",
                    null, request.operator(), idempotencyKey, null, null, request.reason(), ex.getMessage(), "REJECTED");
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        String scope = StringUtils.hasText(request.scope()) ? request.scope().trim().toUpperCase(Locale.ROOT) : "MASKED_LEDGER";
        if (containsRawJsonOrUrl(scope)) {
            c4CommandFailureAudit("C4_KYC_MASKED_EXPORT_REJECTED", "USER_KYC_EXPORT", "PENDING",
                    null, request.operator(), idempotencyKey, null, null, request.reason(), "C4_EXPORT_SCOPE_INVALID", "REJECTED");
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "C4_EXPORT_SCOPE_INVALID");
        }
        try {
            return idempotentC2("C4_KYC_EXPORT", idempotencyKey,
                    scope + "|" + request.reason().trim(), () -> doCreateKycExport(idempotencyKey.trim(), request, scope));
        } catch (RuntimeException ex) {
            c4CommandFailureAudit("C4_KYC_MASKED_EXPORT_FAILED", "USER_KYC_EXPORT", "PENDING",
                    null, request.operator(), idempotencyKey, null, null, request.reason(),
                    "C4_PERSISTENCE_FAILED:" + ex.getClass().getSimpleName(), "FAILED");
            throw ex;
        }
    }

    private ApiResult<Map<String, Object>> doCreateKycExport(
            String idempotencyKey, UserKycExportRequest request, String scope) {
        String jobNo = "KYC-EXP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        List<UserKycRecord> records = allKycRecords();
        KycRegulatoryExportJob job = biKycExportFacade.create(jobNo, scope, records.size(), kycCsv(records), request.reason().trim());
        String actor = operator(request.operator());
        Map<String, Object> response = kycExportMap(job);
        c2RequiredAudit("C4_KYC_MASKED_EXPORT_CREATED", "USER_KYC_EXPORT", jobNo, null, actor, Map.of(
                "scope", scope, "masked", true, "rowCount", records.size(),
                "reason", request.reason().trim(), "idempotencyKey", idempotencyKey));
        outboxService.publish("USER_KYC_EXPORT", jobNo, "admin.kyc_export_created", Map.of(
                "jobNo", jobNo,
                "scope", scope,
                "rowCount", records.size(),
                "masked", true,
                "operator", actor,
                "source", "C4",
                "occurredAt", Instant.now().toString()));
        return ApiResult.ok(response);
    }

    public ApiResult<List<Map<String, Object>>> kycExports(Integer limit) {
        return ApiResult.ok(biKycExportFacade.recent(normalizeLimit(limit, 10, 50)).stream()
                .map(this::kycExportMap).toList());
    }

    public byte[] downloadKycExport(String jobNo) {
        if (!StringUtils.hasText(jobNo) || !jobNo.matches("KYC-EXP-[A-Z0-9]{12}")) {
            c4CommandFailureAudit("C4_KYC_MASKED_EXPORT_DOWNLOAD_REJECTED", "USER_KYC_EXPORT",
                    StringUtils.hasText(jobNo) ? jobNo : "UNKNOWN", null, null, null,
                    null, null, null, "C4_EXPORT_JOB_INVALID", "REJECTED");
            throw new IllegalArgumentException("C4_EXPORT_JOB_INVALID");
        }
        String csv;
        try {
            csv = biKycExportFacade.downloadCsv(jobNo.trim()).orElse(null);
        } catch (RuntimeException ex) {
            c4CommandFailureAudit("C4_KYC_MASKED_EXPORT_DOWNLOAD_FAILED", "USER_KYC_EXPORT",
                    jobNo.trim(), null, null, null, null, null, null,
                    "C4_EXPORT_DOWNLOAD_FAILED:" + ex.getClass().getSimpleName(), "FAILED");
            throw ex;
        }
        if (csv == null) {
            c4CommandFailureAudit("C4_KYC_MASKED_EXPORT_DOWNLOAD_REJECTED", "USER_KYC_EXPORT",
                    jobNo.trim(), null, null, null, null, null, null,
                    "C4_EXPORT_JOB_NOT_FOUND", "REJECTED");
            throw new IllegalArgumentException("C4_EXPORT_JOB_NOT_FOUND");
        }
        c2RequiredAudit("C4_KYC_MASKED_EXPORT_DOWNLOADED", "USER_KYC_EXPORT", jobNo.trim(), null,
                operator(null), Map.of("jobNo", jobNo.trim(), "masked", true));
        return ("\uFEFF" + csv).getBytes(StandardCharsets.UTF_8);
    }

    public ApiResult<UserAccountView> profile(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        return userRepository.findById(userId)
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "USER_NOT_FOUND"));
    }

    public ApiResult<List<UserSessionView>> sessions(Long userId, Integer limit) {
        return ApiResult.ok(userRepository.sessions(userId, normalizeLimit(limit, 100, 200), sessionIdleDays()));
    }

    public ApiResult<PageResult<UserSessionView>> sessionPage(Long userId, Integer pageNum, Integer pageSize, Integer limit) {
        int normalizedPageSize = pageSize == null ? normalizeLimit(limit, 20, 200) : normalizeLimit(pageSize, 20, 200);
        PageResult<UserSessionView> page = userRepository.pageSessions(
                userId, normalizePageNum(pageNum), normalizedPageSize, sessionIdleDays());
        return ApiResult.ok(page);
    }

    public ApiResult<UserSecurityOverview> securityOverview(
            String userKey,
            Long userId,
            Integer pageNum,
            Integer pageSize,
            Integer limit) {
        if (userId != null && userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        Long selectedUserId = resolveSecurityUserId(userKey, userId);
        if (selectedUserId == null) {
            if (StringUtils.hasText(userKey) || userId != null) {
                return ApiResult.fail(404, "USER_NOT_FOUND");
            }
            return ApiResult.ok(emptySecurityOverview(pageNum, pageSize, limit));
        }
        UserSecurityOverview overview = loadSecurityOverview(selectedUserId, pageNum, pageSize, limit);
        return ApiResult.ok(overview);
    }

    private Long resolveSecurityUserId(String userKey, Long userId) {
        if (userId != null) {
            return userId;
        }
        if (!StringUtils.hasText(userKey)) {
            return null;
        }
        String lookupKey = userKey.trim();
        return userRepository.findUserIdByLookupKey(lookupKey).orElse(null);
    }

    private UserSecurityOverview emptySecurityOverview(Integer pageNum, Integer pageSize, Integer limit) {
        int normalizedPageNum = normalizePageNum(pageNum);
        int normalizedPageSize = pageSize == null ? normalizeLimit(limit, 10, 100) : normalizeLimit(pageSize, 10, 100);
        List<UserSecurityUserRow> lockedUsers = lockedSecurityUsers();
        return new UserSecurityOverview(
                securityStats(userRepository.search(null, null, null, 200), lockedUsers),
                CREDENTIAL_PARAM_DEFINITIONS.stream().map(this::credentialParamView).toList(),
                null,
                new PageResult<>(0L, normalizedPageNum, normalizedPageSize, List.of()),
                0L,
                List.of(),
                lockedUsers,
                List.of("nx_user", "nx_user_security", "nx_user_session", "nx_config_item:auth.session.*"),
                List.of("C5 writes require Idempotency-Key and Confirm-with-Reason",
                        "2FA disable and password reset require secondary identity verification",
                        "Passwords are never visible to operators; reset only invalidates the old hash"));
    }

    private UserSecurityOverview loadSecurityOverview(Long selectedUserId, Integer pageNum, Integer pageSize, Integer limit) {
        int normalizedPageNum = normalizePageNum(pageNum);
        int normalizedPageSize = pageSize == null ? normalizeLimit(limit, 10, 100) : normalizeLimit(pageSize, 10, 100);
        int idleDays = sessionIdleDays();
        PageResult<UserSessionView> sessionPage = userRepository.pageSessions(
                selectedUserId, normalizedPageNum, normalizedPageSize, idleDays);
        List<UserAccountView> accounts = userRepository.search(null, null, null, 200);
        UserSecurityUserRow selectedUser = userRepository.findById(selectedUserId)
                .map(this::securityUserRow)
                .orElse(null);
        List<UserSecurityUserRow> lockedUsers = lockedSecurityUsers();
        int rememberDays = boundedConfigInt("auth.session.step_up_days", 7, 1, 30);
        return new UserSecurityOverview(
                securityStats(accounts, lockedUsers),
                CREDENTIAL_PARAM_DEFINITIONS.stream().map(this::credentialParamView).toList(),
                selectedUser,
                sessionPage,
                userRepository.countActiveSessions(selectedUserId, idleDays),
                userRepository.availableC5KycReverifications(selectedUserId, rememberDays),
                lockedUsers,
                List.of("nx_user", "nx_user_security", "nx_user_session", "nx_config_item:auth.session.*"),
                List.of("C5 writes require Idempotency-Key and Confirm-with-Reason",
                        "2FA disable and password reset require secondary identity verification",
                        "Passwords are never visible to operators; reset only invalidates the old hash"));
    }

    private List<UserSecurityUserRow> lockedSecurityUsers() {
        int shortLockThreshold = Math.max(configInt("auth.risk.login_lock_threshold", 5), 1);
        int longLockThreshold = Math.max(configInt("auth.risk.login_long_lock_threshold", 10), shortLockThreshold + 1);
        int shortLockMinutes = Math.max(configInt("auth.risk.lock_duration_minutes", 15), 1);
        int longLockHours = Math.max(configInt("auth.risk.long_lock_duration_hours", 24), 1);
        return userRepository.lockedSecurityUsers(shortLockThreshold, longLockThreshold, shortLockMinutes, longLockHours, 5);
    }

    private UserSecurityStats securityStats(List<UserAccountView> accounts, List<UserSecurityUserRow> lockedUsers) {
        Map<String, Object> canonicalOverview = userRepository.overview();
        long total = numberValue(canonicalOverview.get("totalUsers"));
        long twoFactorEnabled = numberValue(canonicalOverview.get("twoFactorEnabledUsers"));
        double twoFactorRate = total == 0 ? 0D : (twoFactorEnabled * 100D / total);
        int longThreshold = configInt("auth.risk.login_long_lock_threshold", 10);
        long lockedLong = userRepository.countActiveLongLocks(longThreshold);
        long lockedShort = userRepository.countActiveShortLocks(longThreshold);
        if (lockedLong == 0 && lockedShort == 0 && !lockedUsers.isEmpty()) {
            lockedLong = lockedUsers.stream().filter(row -> "LONG".equals(row.lockKind())).count();
            lockedShort = lockedUsers.stream().filter(row -> "SHORT".equals(row.lockKind())).count();
        }
        return new UserSecurityStats(
                userRepository.countActiveSessions(null, sessionIdleDays()),
                String.format(Locale.ROOT, "%.1f", twoFactorRate),
                lockedShort,
                lockedLong,
                userRepository.countRefreshTokenReuseToday());
    }

    private UserSecurityUserRow securityUserRow(UserAccountView account) {
        UserSecurityStatusView status = account.id() == null ? null : loadSecurityStatus(account.id());
        boolean twoFactorEnabled = status == null ? Boolean.TRUE.equals(account.twoFactorEnabled()) : status.twoFactorEnabled();
        int loginFailCount = status == null ? 0 : status.loginFailCount();
        boolean passwordResetRequired = status != null && status.passwordResetRequired();
        int longLockThreshold = configInt("auth.risk.login_long_lock_threshold", 10);
        boolean longLock = passwordResetRequired || (longLockThreshold > 0 && loginFailCount >= longLockThreshold);
        boolean locked = status != null && (status.locked() || longLock);
        String lockKind = !locked ? "NONE" : longLock ? "LONG" : "SHORT";
        String lockLabel = switch (lockKind) {
            case "LONG" -> configInt("auth.risk.long_lock_duration_hours", 24) + " 小时长锁";
            case "SHORT" -> (status == null ? 15 : Math.max(status.lockDurationMinutes(), 1)) + " 分钟短锁";
            default -> "未锁定";
        };
        String lockReason = switch (lockKind) {
            case "LONG" -> "连续失败达到长锁阈值或已挂强制重置";
            case "SHORT" -> "连续登录/两步验证失败达到短锁阈值";
            default -> "当前无锁定";
        };
        String lockLeft = switch (lockKind) {
            case "LONG" -> configInt("auth.risk.long_lock_duration_hours", 24) + " 小时内";
            case "SHORT" -> (status == null ? 15 : Math.max(status.lockDurationMinutes(), 1)) + " 分钟内";
            default -> "—";
        };
        return new UserSecurityUserRow(
                account.id(),
                account.userNo(),
                account.nickname(),
                twoFactorEnabled,
                loginFailCount,
                locked,
                passwordResetRequired,
                lockKind,
                lockLabel,
                lockReason,
                lockLeft);
    }

    public ApiResult<UserAccountActionOverview> accountActionOverview() {
        return ApiResult.ok(loadAccountActionOverview());
    }

    public ApiResult<UserAccountView> accountActionAccount(String userKey) {
        String lookupKey = userKey == null ? "" : userKey.trim();
        if (lookupKey.isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_KEY_REQUIRED");
        }
        Long userId = userRepository.findUserIdByLookupKey(lookupKey).orElse(null);
        if (userId == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        UserAccountView account = userRepository.findById(userId).orElse(null);
        return account == null ? ApiResult.fail(404, "USER_NOT_FOUND") : ApiResult.ok(account);
    }

    public ApiResult<UserAccountActionContext> accountActionContext(String userKey) {
        String lookupKey = userKey == null ? "" : userKey.trim();
        if (lookupKey.isEmpty()) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_KEY_REQUIRED");
        }
        Long userId = userRepository.findUserIdByLookupKey(lookupKey).orElse(null);
        if (userId == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        UserAccountView account = userRepository.findById(userId).orElse(null);
        if (account == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        PageResult<UserSessionView> sessionPage = userRepository.pageSessions(userId, 1, 100, sessionIdleDays());
        List<UserImpersonationSessionView> impersonations = userRepository.impersonations(userId, 100);
        long totalImpersonations = userRepository.countImpersonations(userId);
        return ApiResult.ok(new UserAccountActionContext(
                account,
                userRepository.findAccountList(userId).orElse(null),
                sessionPage.getRecords(),
                impersonations,
                userRepository.findAccountControlFact(userId).orElse(null),
                sessionPage.getTotal(),
                userRepository.countActiveSessions(userId, sessionIdleDays()),
                totalImpersonations,
                sessionPage.getTotal() > sessionPage.getRecords().size(),
                totalImpersonations > impersonations.size()));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> impersonationReadonlyView(Long userId, String sessionNo, String page) {
        if (userId == null || userId <= 0 || !StringUtils.hasText(sessionNo)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "IMPERSONATION_CONTEXT_INVALID");
        }
        String normalizedPage = StringUtils.hasText(page) ? page.trim().toUpperCase(Locale.ROOT) : "HOME";
        if (!Set.of("HOME", "WALLET", "DEVICES", "PROFILE").contains(normalizedPage)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "IMPERSONATION_PAGE_INVALID");
        }
        UserImpersonationSessionView session = userRepository.findImpersonation(sessionNo.trim()).orElse(null);
        if (session == null || !Objects.equals(session.userId(), userId)
                || !"ACTIVE".equalsIgnoreCase(session.status())
                || session.expiresAt() == null || !session.expiresAt().isAfter(LocalDateTime.now())) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "IMPERSONATION_SESSION_INACTIVE");
        }
        UserAccountView account = userRepository.findById(userId).orElse(null);
        if (account == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        UserAccountListEntryView accountList = userRepository.findAccountList(userId)
                .filter(entry -> "ACTIVE".equalsIgnoreCase(entry.status()))
                .filter(entry -> entry.expiresAt() == null || entry.expiresAt().isAfter(LocalDateTime.now()))
                .orElse(null);
        long activeSessions = userRepository.countActiveSessions(userId, sessionIdleDays());
        List<UserReadonlyDeviceView> devices = "DEVICES".equals(normalizedPage)
                ? userRepository.readonlyDevices(userId, 20)
                : List.of();
        Map<String, Object> screen = buildImpersonationScreen(
                normalizedPage, account, accountList, activeSessions, devices);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("claim", "impersonate_readonly");
        response.put("writePolicy", "DENY");
        response.put("sessionNo", session.sessionNo());
        response.put("expiresAt", session.expiresAt());
        response.put("pages", List.of("HOME", "WALLET", "DEVICES", "PROFILE"));
        response.put("currentPage", normalizedPage);
        Map<String, Object> user = new LinkedHashMap<>();
        user.put("userNo", account.userNo());
        user.put("nickname", account.nickname());
        user.put("status", account.status());
        user.put("kycStatus", account.kycStatus());
        response.put("user", user);
        response.put("screen", screen);
        Map<String, Object> detail = Map.of(
                "page", normalizedPage,
                "writePolicy", "DENY",
                "claim", "impersonate_readonly");
        c2TrustedSessionActorAudit(
                "C2_USER_IMPERSONATION_PAGE_VIEWED",
                "USER_IMPERSONATION",
                session.sessionNo(),
                userId,
                session.operator(),
                detail);
        outboxService.publish("USER_IMPERSONATION", session.sessionNo(), "C2_USER_IMPERSONATION_PAGE_VIEWED", detail);
        return ApiResult.ok(response);
    }

    private Map<String, Object> buildImpersonationScreen(
            String page,
            UserAccountView account,
            UserAccountListEntryView accountList,
            long activeSessions,
            List<UserReadonlyDeviceView> devices) {
        Map<String, Object> screen = new LinkedHashMap<>();
        switch (page) {
            case "HOME" -> {
                screen.put("template", "H5_HOME");
                screen.put("title", "首页");
                screen.put("greeting", "你好，" + account.nickname());
                screen.put("accountBanner", Map.of(
                        "status", account.status(), "kycStatus", account.kycStatus(),
                        "accountList", accountList == null ? "NONE" : accountList.kind()));
                screen.put("assetSummary", List.of(
                        Map.of("symbol", "USDT", "available", account.walletUsdt()),
                        Map.of("symbol", "NEX", "available", account.walletNex())));
                screen.put("deviceSummary", Map.of("total", account.deviceCount(), "active", account.activeDeviceCount()));
                screen.put("entries", List.of("钱包", "设备", "我的"));
            }
            case "WALLET" -> {
                screen.put("template", "H5_WALLET");
                screen.put("title", "钱包");
                screen.put("assets", List.of(
                        Map.of("symbol", "USDT", "available", account.walletUsdt()),
                        Map.of("symbol", "NEX", "available", account.walletNex())));
                screen.put("accountStatus", account.status());
                screen.put("actions", Map.of("deposit", false, "withdraw", false, "transfer", false));
                screen.put("readOnlyHint", "模拟登录只读模式，充值、提现和转账均不可操作");
            }
            case "DEVICES" -> {
                screen.put("template", "H5_DEVICES");
                screen.put("title", "设备");
                screen.put("total", account.deviceCount());
                screen.put("active", account.activeDeviceCount());
                screen.put("devices", devices);
                screen.put("shown", devices.size());
                screen.put("truncated", account.deviceCount() != null && account.deviceCount() > devices.size());
                screen.put("actions", Map.of("purchase", false, "activate", false, "deactivate", false));
            }
            case "PROFILE" -> {
                screen.put("template", "H5_PROFILE");
                screen.put("title", "我的");
                screen.put("userNo", account.userNo());
                screen.put("nickname", account.nickname());
                screen.put("accountStatus", account.status());
                screen.put("kycStatus", account.kycStatus());
                screen.put("userLevel", account.userLevel());
                screen.put("vRank", account.vRank());
                screen.put("twoFactorEnabled", account.twoFactorEnabled());
                screen.put("activeSessions", activeSessions);
                screen.put("actions", Map.of("editProfile", false, "securitySettings", false, "logout", false));
            }
            default -> throw new IllegalArgumentException("IMPERSONATION_PAGE_INVALID");
        }
        return screen;
    }

    private UserAccountActionOverview loadAccountActionOverview() {
        Map<String, Object> baseOverview = userRepository.overview();
        List<UserAccountView> accounts = userRepository.search(null, null, null, 50);
        List<UserAccountListEntryView> accountLists = userRepository.accountLists(null, 100);
        List<UserSessionView> sessions = userRepository.sessions(null, 200, sessionIdleDays());
        List<UserImpersonationSessionView> impersonations = userRepository.impersonations(50);
        var controlFacts = userRepository.accountControlFacts(100);
        long trustListCount = baseOverview.containsKey("trustListCount")
                ? numberValue(baseOverview.get("trustListCount"))
                : accountLists.stream().filter(entry -> "ACTIVE".equalsIgnoreCase(entry.status()))
                        .filter(entry -> "ALLOW".equalsIgnoreCase(entry.kind())).count();
        long blockedListCount = baseOverview.containsKey("blockedListCount")
                ? numberValue(baseOverview.get("blockedListCount"))
                : accountLists.stream().filter(entry -> "ACTIVE".equalsIgnoreCase(entry.status()))
                        .filter(entry -> "BLOCK".equalsIgnoreCase(entry.kind())).count();
        long activeImpersonations = baseOverview.containsKey("activeImpersonations")
                ? numberValue(baseOverview.get("activeImpersonations"))
                : impersonations.stream().filter(session -> "ACTIVE".equalsIgnoreCase(session.status())).count();
        return new UserAccountActionOverview(
                accounts,
                accountLists,
                sessions,
                impersonations,
                controlFacts,
                numberValue(baseOverview.get("frozenUsers")),
                numberValue(baseOverview.get("activeSessions")),
                trustListCount,
                blockedListCount,
                activeImpersonations,
                numberValue(baseOverview.get("totalUsers")),
                numberValue(baseOverview.get("totalAccountLists")),
                numberValue(baseOverview.get("totalSessions")),
                numberValue(baseOverview.get("totalImpersonations")),
                List.of("nx_user", "nx_user_session", "nx_account_list", "nx_user_impersonation_session", "nx_audit_log"),
                List.of("C2 writes are atomic, audited, outboxed and replay-safe for 24 hours", "Only ACTIVE→FROZEN and FROZEN→ACTIVE are accepted"));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<UserAccountListEntryView> upsertAccountList(String idempotencyKey, UserAccountListUpsertRequest request) {
        Long targetUserId = request == null ? null : request.userId();
        ApiResult<UserAccountListEntryView> approvalGuard = delegatedDirectExecutionGuard(
                "c2_blocklist_upsert", "USER_ACCOUNT_LIST", String.valueOf(targetUserId), targetUserId);
        if (approvalGuard != null) {
            return approvalGuard;
        }
        ApiResult<UserAccountListEntryView> guard = requireC2UserCommand(
                request == null ? null : request.userId(),
                idempotencyKey,
                request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<UserAccountListEntryView> reasonGuard = requirePlainReason(request.reason(), "ACCOUNT_LIST_REASON_REJECTED");
        if (reasonGuard != null) {
            return reasonGuard;
        }
        String kind;
        LocalDateTime expiresAt;
        try {
            kind = normalizeAccountListKind(request.kind());
            expiresAt = parseOptionalExpiry(request.expiresAt());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        ApiResult<UserAccountListEntryView> authorityGuard = requireC2Authority("user_c2_blocklist_add");
        if (authorityGuard != null) {
            return authorityGuard;
        }
        String normalizedKind = kind;
        LocalDateTime normalizedExpiry = expiresAt;
        return idempotentC2(
                "C2_ACCOUNT_LIST_UPSERT:" + request.userId(),
                idempotencyKey,
                request.userId() + "|" + normalizedKind + "|" + String.valueOf(normalizedExpiry) + "|" + request.reason().trim(),
                () -> doUpsertAccountList(idempotencyKey, request, normalizedKind, normalizedExpiry));
    }

    private ApiResult<UserAccountListEntryView> doUpsertAccountList(
            String idempotencyKey,
            UserAccountListUpsertRequest request,
            String kind,
            LocalDateTime expiresAt) {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("C", "accountlist", String.valueOf(request.userId())) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        UserAccountView user = userRepository.findById(request.userId()).orElse(null);
        if (user == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        UserAccountListEntryView existing = userRepository.findAccountList(request.userId()).orElse(null);
        if (existing != null
                && "ACTIVE".equalsIgnoreCase(existing.status())
                && kind.equalsIgnoreCase(existing.kind())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        String reason = request.reason().trim();
        String operator = operator(request.operator());
        userRepository.upsertAccountList(request.userId(), kind, reason, operator, expiresAt);
        boolean sessionsRevoked = "BLOCK".equals(kind);
        if (sessionsRevoked) {
            userRepository.revokeUserSessions(request.userId(), reason);
        }
        UserAccountListEntryView updated = userRepository.findAccountList(request.userId())
                .orElse(new UserAccountListEntryView(
                        request.userId(),
                        user.userNo(),
                        user.nickname(),
                        kind,
                        reason,
                        "ACTIVE",
                        expiresAt,
                        operator,
                        LocalDateTime.now(),
                        null,
                        null,
                        null));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("userId", request.userId());
        detail.put("kind", kind);
        detail.put("reason", reason);
        detail.put("idempotencyKey", idempotencyKey.trim());
        detail.put("expiresAt", expiresAt);
        detail.put("sessionsRevoked", sessionsRevoked);
        c2RequiredAudit("C2_ACCOUNT_LIST_UPSERTED", "USER_ACCOUNT_LIST", String.valueOf(request.userId()), request.userId(), operator, detail);
        outboxService.publish("USER_ACCOUNT_LIST", String.valueOf(request.userId()), "C2_ACCOUNT_LIST_UPSERTED", detail);
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<UserAccountListEntryView> removeAccountList(Long userId, String idempotencyKey, UserAccountListRemoveRequest request) {
        ApiResult<UserAccountListEntryView> approvalGuard = delegatedDirectExecutionGuard(
                "c2_blocklist_remove", "USER_ACCOUNT_LIST", String.valueOf(userId), userId);
        if (approvalGuard != null) {
            return approvalGuard;
        }
        ApiResult<UserAccountListEntryView> guard = requireC2UserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<UserAccountListEntryView> reasonGuard = requirePlainReason(request.reason(), "ACCOUNT_LIST_REASON_REJECTED");
        if (reasonGuard != null) {
            return reasonGuard;
        }
        ApiResult<UserAccountListEntryView> authorityGuard = requireC2Authority("user_c2_blocklist_add");
        if (authorityGuard != null) {
            return authorityGuard;
        }
        return idempotentC2(
                "C2_ACCOUNT_LIST_REMOVE:" + userId,
                idempotencyKey,
                userId + "|" + request.reason().trim(),
                () -> doRemoveAccountList(userId, idempotencyKey, request));
    }

    private ApiResult<UserAccountListEntryView> doRemoveAccountList(
            Long userId,
            String idempotencyKey,
            UserAccountListRemoveRequest request) {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("C", "accountlist", String.valueOf(userId)) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        UserAccountListEntryView existing = userRepository.findAccountList(userId).orElse(null);
        if (existing == null) {
            return ApiResult.fail(404, "ACCOUNT_LIST_ENTRY_NOT_FOUND");
        }
        if (!"ACTIVE".equalsIgnoreCase(existing.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        String reason = request.reason().trim();
        String operator = operator(request.operator());
        userRepository.removeAccountList(userId, reason, operator);
        UserAccountListEntryView updated = userRepository.findAccountList(userId)
                .orElse(new UserAccountListEntryView(
                        existing.userId(),
                        existing.userNo(),
                        existing.nickname(),
                        existing.kind(),
                        existing.reason(),
                        "REMOVED",
                        existing.expiresAt(),
                        existing.createdBy(),
                        existing.createdAt(),
                        operator,
                        reason,
                        LocalDateTime.now()));
        Map<String, Object> detail = Map.of(
                "kind", existing.kind(),
                "reason", reason,
                "idempotencyKey", idempotencyKey.trim());
        c2RequiredAudit("C2_ACCOUNT_LIST_REMOVED", "USER_ACCOUNT_LIST", String.valueOf(userId), userId, operator, detail);
        outboxService.publish("USER_ACCOUNT_LIST", String.valueOf(userId), "C2_ACCOUNT_LIST_REMOVED", detail);
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<UserImpersonationSessionView> terminateImpersonation(
            String sessionNo,
            String idempotencyKey,
            UserImpersonationTerminateRequest request) {
        ApiResult<UserImpersonationSessionView> approvalGuard = delegatedDirectExecutionGuard(
                "c2_impersonate_terminate", "USER_IMPERSONATION", sessionNo, null);
        if (approvalGuard != null) {
            return approvalGuard;
        }
        ApiResult<UserImpersonationSessionView> guard = requireC2Command(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<UserImpersonationSessionView> reasonGuard = requirePlainReason(request.reason(), "IMPERSONATION_REASON_REJECTED");
        if (reasonGuard != null) {
            return reasonGuard;
        }
        String normalizedSessionNo;
        try {
            normalizedSessionNo = requireText(sessionNo, "IMPERSONATION_SESSION_NO_REQUIRED");
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        ApiResult<UserImpersonationSessionView> authorityGuard = requireC2Authority("user_c2_impersonate_terminate");
        if (authorityGuard != null) {
            return authorityGuard;
        }
        return idempotentC2(
                "C2_IMPERSONATION_TERMINATE:" + normalizedSessionNo,
                idempotencyKey,
                normalizedSessionNo + "|" + request.reason().trim(),
                () -> doTerminateImpersonation(normalizedSessionNo, idempotencyKey, request));
    }

    private ApiResult<UserImpersonationSessionView> doTerminateImpersonation(
            String normalizedSessionNo,
            String idempotencyKey,
            UserImpersonationTerminateRequest request) {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("C", "impersonation", normalizedSessionNo) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        UserImpersonationSessionView existing = userRepository.findImpersonation(normalizedSessionNo).orElse(null);
        if (existing == null) {
            return ApiResult.fail(404, "IMPERSONATION_SESSION_NOT_FOUND");
        }
        if (!"ACTIVE".equalsIgnoreCase(existing.status())
                || (existing.expiresAt() != null && !existing.expiresAt().isAfter(LocalDateTime.now()))) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        String reason = request.reason().trim();
        String operator = operator(request.operator());
        if (!userRepository.terminateActiveImpersonation(normalizedSessionNo, reason, operator)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(),
                    "IMPERSONATION_SESSION_CONCURRENTLY_CHANGED");
        }
        UserImpersonationSessionView updated = userRepository.findImpersonation(normalizedSessionNo)
                .orElse(new UserImpersonationSessionView(
                        existing.sessionNo(),
                        existing.userId(),
                        existing.userNo(),
                        existing.nickname(),
                        "TERMINATED",
                        existing.ttlMinutes(),
                        existing.operator(),
                        existing.reason(),
                        existing.expiresAt(),
                        existing.createdAt(),
                        LocalDateTime.now(),
                        operator,
                        reason,
                        0L));
        LocalDateTime sessionEnd = updated.endedAt() == null ? LocalDateTime.now() : updated.endedAt();
        LocalDateTime sessionStart = existing.createdAt() == null
                ? sessionEnd.minusMinutes(existing.ttlMinutes())
                : existing.createdAt();
        long durationSec = Math.max(0L, Duration.between(sessionStart, sessionEnd).getSeconds());
        Map<String, Object> detail = Map.of(
                "reason", reason,
                "idempotencyKey", idempotencyKey.trim(),
                "endType", "TERMINATED",
                "durationSec", durationSec);
        c2RequiredAudit("C2_USER_IMPERSONATION_TERMINATED", "USER_IMPERSONATION", normalizedSessionNo, existing.userId(), operator, detail);
        outboxService.publish("USER_IMPERSONATION", normalizedSessionNo, "admin.user_impersonation_ended", Map.of(
                "userId", existing.userId(),
                "targetUserId", existing.userId(),
                "operator", operator,
                "reason", reason,
                "ttlMinutes", existing.ttlMinutes(),
                "sessionStart", sessionStart.toString(),
                "sessionEnd", sessionEnd.toString(),
                "durationSec", durationSec,
                "endType", "TERMINATED",
                "occurredAt", sessionEnd.toString()));
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> revokeUserSessions(Long userId, String idempotencyKey, UserSessionRevokeAllRequest request) {
        ApiResult<Map<String, Object>> approvalGuard = delegatedDirectExecutionGuard(
                "c2_session_revoke_all", "USER_SESSION", String.valueOf(userId), userId);
        if (approvalGuard != null) {
            return approvalGuard;
        }
        ApiResult<Map<String, Object>> guard = requireC2UserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<Map<String, Object>> reasonGuard = requirePlainReason(request.reason(), "SESSION_REVOKE_REASON_REJECTED");
        if (reasonGuard != null) {
            return reasonGuard;
        }
        ApiResult<Map<String, Object>> authorityGuard = requireC2Authority("user_c2_session_revoke_all");
        if (authorityGuard != null) {
            return authorityGuard;
        }
        return idempotentC2(
                "C2_USER_SESSIONS_REVOKE:" + userId,
                idempotencyKey,
                userId + "|" + request.reason().trim(),
                () -> doRevokeUserSessions(userId, idempotencyKey, request));
    }

    private ApiResult<Map<String, Object>> doRevokeUserSessions(
            Long userId,
            String idempotencyKey,
            UserSessionRevokeAllRequest request) {
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("C", "user", String.valueOf(userId)) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        if (userRepository.findById(userId).isEmpty()) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        long activeSessionCount = userRepository.countActiveSessions(userId, sessionIdleDays());
        String reason = request.reason().trim();
        String operator = operator(request.operator());
        userRepository.revokeUserSessions(userId, reason);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", userId);
        response.put("revokedCount", activeSessionCount);
        response.put("status", "REVOKED");
        Map<String, Object> detail = Map.of(
                "revokedCount", activeSessionCount,
                "reason", reason,
                "idempotencyKey", idempotencyKey.trim());
        c2RequiredAudit("C2_USER_SESSIONS_REVOKED", "USER_SESSION", String.valueOf(userId), userId, operator, detail);
        outboxService.publish("USER_SESSION", String.valueOf(userId), "C2_USER_SESSIONS_REVOKED", detail);
        return ApiResult.ok(response);
    }

    public ApiResult<UserSecurityStatusView> securityStatus(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        UserSecurityStatusView status = loadSecurityStatus(userId);
        return status == null ? ApiResult.fail(404, "USER_NOT_FOUND") : ApiResult.ok(status);
    }

    public ApiResult<List<UserCredentialParamView>> credentialParams() {
        return ApiResult.ok(CREDENTIAL_PARAM_DEFINITIONS.stream()
                .map(this::credentialParamView)
                .toList());
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<UserCredentialParamView> updateCredentialParam(
            String paramKey,
            String idempotencyKey,
            UserCredentialParamUpdateRequest request) {
        ApiResult<UserCredentialParamView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        CredentialParamDefinition definition = credentialParamDefinition(paramKey);
        if (definition == null) {
            return ApiResult.fail(404, "CREDENTIAL_PARAM_NOT_FOUND");
        }
        if (definition.readOnly()) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        int nextValue;
        try {
            nextValue = normalizeCredentialParamValue(definition, request.value());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        Map<String, Object> fingerprint = Map.of(
                "paramKey", definition.key(),
                "value", nextValue,
                "reason", request.reason().trim());
        return idempotentC2("C5_CREDENTIAL_PARAM:" + definition.key(), idempotencyKey, fingerprint, () -> {
            configFacade.upsertAdminValue(
                    definition.configKey(),
                    String.valueOf(nextValue),
                    "NUMBER",
                    AUTH_CONFIG_GROUP,
                    request.reason().trim());
            UserCredentialParamView updated = credentialParamView(definition, nextValue);
            c5RequiredAudit("C5_CREDENTIAL_PARAM_UPDATED", "AUTH_CONFIG", definition.key(), null,
                    operator(request.operator()), Map.of(
                            "paramKey", definition.key(),
                            "configKey", definition.configKey(),
                            "value", nextValue,
                            "reason", request.reason().trim(),
                            "idempotencyKey", idempotencyKey.trim()));
            return ApiResult.ok(updated);
        });
    }

    public ApiResult<UserRegistrationRiskOverview> registrationRiskOverview() {
        RegistrationRiskCaptchaWindow.State captchaState = RegistrationRiskCaptchaWindow.state(
                configFacade.activeValue(CAPTCHA_OFF_WINDOW_KEY).orElse(""),
                clock);
        long lockedShort = userRepository.countRegistrationLoginLocksToday("SHORT");
        long lockedLong = userRepository.countRegistrationLoginLocksToday("LONG");
        long configVersion = configLong(C6_CONFIG_VERSION_KEY, 0L);
        UserRegistrationRiskOverview overview = new UserRegistrationRiskOverview(
                new UserRegistrationRiskStats(
                        userRepository.countRegistrationOtpToday(),
                        userRepository.countRegistrationCaptchaTriggeredToday(),
                        lockedShort,
                        lockedLong,
                        lockedShort + lockedLong,
                        userRepository.countRegistrationStuffingClusters7d(),
                        captchaState.disabled(),
                        captchaState.restoreAt() == null ? "" : captchaState.restoreAt().toString(),
                        captchaState.remainingSeconds()),
                REGISTRATION_RISK_PARAM_DEFINITIONS.stream()
                        .map(definition -> registrationRiskParamView(definition, configVersion))
                        .toList(),
                registrationRiskK1Guards(),
                configVersion,
                K1_REJECT_CODE,
                K1_PATH,
                List.of(
                        "nx_user_otp_challenge",
                        "nx_event_outbox:auth.captcha_required",
                        "nx_event_outbox:auth.login_locked",
                        "nx_admin_risk_multi_account_cluster",
                        "nx_config_item:auth.risk.*"),
                List.of("C6 writes require Idempotency-Key and Confirm-with-Reason",
                        "K1 multi-account params are rejected here with 422 " + K1_REJECT_CODE,
                        "CAPTCHA off is whitelist-only, absolute-deadline bounded, and automatically restored"));
        return ApiResult.ok(overview);
    }

    private void ensureRegistrationRiskConfigSeeds() {
    }

    private void ensureAdminConfigSeed(String configKey, String configValue, String valueType) {
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        if (configFacade.activeValue(configKey).filter(StringUtils::hasText).isPresent()) {
            return;
        }
        configFacade.upsertAdminValue(
                configKey,
                configValue,
                valueType,
                AUTH_CONFIG_GROUP,
                "C6 registration risk bootstrap");
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<UserRegistrationRiskParamView> updateRegistrationRiskParam(
            String paramKey,
            String idempotencyKey,
            UserRegistrationRiskParamUpdateRequest request) {
        ApiResult<UserRegistrationRiskParamView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String normalizedKey;
        try {
            normalizedKey = requireText(paramKey, "REGISTRATION_RISK_PARAM_REQUIRED");
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        if (K1_REGISTRATION_RISK_PARAM_KEYS.contains(normalizedKey)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), K1_REJECT_CODE);
        }
        if (K2_OTP_PARAM_KEYS.contains(normalizedKey)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "OTP_CONFIG_BELONGS_TO_K2");
        }
        if (request.expectedVersion() == null || request.expectedVersion() < 0L) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "C6_EXPECTED_VERSION_REQUIRED");
        }
        RegistrationRiskParamDefinition definition = "captchaOff".equals(normalizedKey)
                ? null
                : registrationRiskParamDefinition(normalizedKey);
        if (!"captchaOff".equals(normalizedKey) && definition == null) {
            return ApiResult.fail(404, "REGISTRATION_RISK_PARAM_NOT_FOUND");
        }
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("paramKey", normalizedKey);
        fingerprint.put("value", request.value() == null ? "" : request.value().trim());
        fingerprint.put("reason", request.reason().trim());
        fingerprint.put("expectedVersion", request.expectedVersion());
        return idempotentC2("C6_REGISTRATION_RISK_PARAM:" + normalizedKey, idempotencyKey, fingerprint,
                () -> "captchaOff".equals(normalizedKey)
                        ? updateCaptchaOffWindow(idempotencyKey, request)
                        : updateRegistrationRiskParamLocked(definition, idempotencyKey, request));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> requestC5KycReverification(
            Long userId, String idempotencyKey, UserKycReverificationRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(
                idempotencyKey, request == null ? null : request.reason());
        if (guard != null) return guard;
        if (userId == null || userId <= 0 || request == null || !StringUtils.hasText(request.action())) {
            return ApiResult.fail(422, "C5_KYC_REVERIFICATION_INPUT_REQUIRED");
        }
        String action = request.action().trim().toUpperCase(Locale.ROOT);
        if (!C5_KYC_ACTIONS.contains(action)) {
            return ApiResult.fail(422, "C5_KYC_REVERIFICATION_ACTION_INVALID");
        }
        String requiredAuthority = switch (action) {
            case "DISABLE_2FA" -> "user_c5_2fa_disable";
            case "PASSWORD_RESET" -> "user_c5_password_reset";
            case "UNLOCK_LONG" -> "user_c5_unlock_long";
            default -> "user_c5_unlock_short";
        };
        ApiResult<Map<String, Object>> authority = requireC5Authority(requiredAuthority);
        if (authority != null) return authority;
        UserKycRecord record = userRepository.findKycRecord(userId).orElse(null);
        if (record == null || !"APPROVED".equalsIgnoreCase(record.status())) {
            return ApiResult.fail(422, "KYC_REVERIFY_REQUIRED");
        }
        return idempotentC2("C5_KYC_REVERIFY_REQUEST:" + userId + ":" + action,
                idempotencyKey, userId + "|" + action + "|" + request.reason().trim(), () -> {
                    String actor = operator(request.operator());
                    KycReviewTriggerResult result = riskKycReviewFacade.triggerC5IdentityReview(
                            record.userNo(), action, actor, request.reason().trim());
                    Map<String, Object> response = new LinkedHashMap<>();
                    response.put("ticketId", result.ticketId());
                    response.put("action", action);
                    response.put("status", result.created() ? "WAITING_K5_REVIEW" : "MERGED_WITH_K5_REVIEW");
                    response.put("created", result.created());
                    c5RequiredAudit("C5_KYC_REVERIFICATION_REQUESTED", "RISK_KYC_REVIEW_TICKET",
                            result.ticketId(), userId, actor, Map.of(
                                    "action", action,
                                    "reason", request.reason().trim(),
                                    "idempotencyKey", idempotencyKey.trim()));
                    return ApiResult.ok(response);
                });
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<UserSecurityStatusView> disableTwoFactor(
            Long userId, String idempotencyKey, UserSecurityActionRequest request) {
        ApiResult<UserSecurityStatusView> guard = requireC5HighRiskCommand(
                userId, idempotencyKey, request, "DISABLE_2FA");
        if (guard != null) {
            return guard;
        }
        return idempotentC2(
                "C5_TWO_FACTOR_DISABLE:" + userId,
                idempotencyKey,
                c5Fingerprint(userId, request),
                () -> doDisableTwoFactor(userId, idempotencyKey.trim(), request));
    }

    private ApiResult<UserSecurityStatusView> doDisableTwoFactor(
            Long userId, String idempotencyKey, UserSecurityActionRequest request) {
        UserSecurityStatusView before = loadSecurityStatus(userId);
        if (before == null) {
            return c5Rejected(userId, idempotencyKey, request, "C5_TWO_FACTOR_DISABLE_REJECTED", 404, "USER_NOT_FOUND");
        }
        if (!before.twoFactorEnabled()) {
            return c5Rejected(userId, idempotencyKey, request, "C5_TWO_FACTOR_DISABLE_REJECTED", 409,
                    "C5_ACTION_STATE_CHANGED");
        }
        if (!consumeC5KycReverification(userId, "DISABLE_2FA", idempotencyKey, request)
                || !userRepository.disableTwoFactor(userId)) {
            throw new IllegalStateException("C5_KYC_REVERIFICATION_CONSUME_OR_STATE_FAILED");
        }
        UserSecurityStatusView updated = loadSecurityStatus(userId);
        String actor = operator(request.operator());
        Map<String, Object> detail = c5AuditDetail(request, idempotencyKey);
        detail.put("fromTwoFactorEnabled", true);
        detail.put("toTwoFactorEnabled", false);
        c5RequiredAudit("C5_TWO_FACTOR_DISABLED", "USER_SECURITY", String.valueOf(userId), userId, actor, detail);
        publishC5Event("admin.2fa_disabled", userId, actor, request.reason().trim(), detail);
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<UserSecurityStatusView> requestPasswordReset(
            Long userId, String idempotencyKey, UserSecurityActionRequest request) {
        ApiResult<UserSecurityStatusView> guard = requireC5HighRiskCommand(
                userId, idempotencyKey, request, "PASSWORD_RESET");
        if (guard != null) {
            return guard;
        }
        return idempotentC2(
                "C5_PASSWORD_RESET:" + userId,
                idempotencyKey,
                c5Fingerprint(userId, request),
                () -> doRequestPasswordReset(userId, idempotencyKey.trim(), request));
    }

    private ApiResult<UserSecurityStatusView> doRequestPasswordReset(
            Long userId, String idempotencyKey, UserSecurityActionRequest request) {
        UserSecurityStatusView before = loadSecurityStatus(userId);
        if (before == null) {
            return c5Rejected(userId, idempotencyKey, request, "C5_PASSWORD_RESET_REJECTED", 404, "USER_NOT_FOUND");
        }
        if (before.passwordResetRequired()) {
            return c5Rejected(userId, idempotencyKey, request, "C5_PASSWORD_RESET_REJECTED", 409,
                    "C5_ACTION_STATE_CHANGED");
        }
        if (!consumeC5KycReverification(userId, "PASSWORD_RESET", idempotencyKey, request)
                || !userRepository.markPasswordResetRequired(userId, RESET_REQUIRED_PREFIX + idempotencyKey)) {
            throw new IllegalStateException("C5_KYC_REVERIFICATION_CONSUME_OR_STATE_FAILED");
        }
        userRepository.revokeUserSessions(userId, request.reason().trim());
        UserSecurityStatusView updated = loadSecurityStatus(userId);
        String actor = operator(request.operator());
        Map<String, Object> detail = c5AuditDetail(request, idempotencyKey);
        detail.put("passwordResetRequired", true);
        detail.put("sessionsRevoked", true);
        c5RequiredAudit("C5_PASSWORD_RESET_REQUESTED", "USER_SECURITY", String.valueOf(userId), userId, actor, detail);
        publishC5Event("admin.password_reset_requested", userId, actor, request.reason().trim(), detail);
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<UserSecurityStatusView> unlockSecurity(
            Long userId, String idempotencyKey, UserSecurityActionRequest request) {
        String action = request != null && "LONG".equalsIgnoreCase(request.lockKind())
                ? "UNLOCK_LONG" : "UNLOCK_SHORT";
        ApiResult<UserSecurityStatusView> guard = requireC5HighRiskCommand(
                userId, idempotencyKey, request, action);
        if (guard != null) {
            return guard;
        }
        return idempotentC2(
                "C5_USER_UNLOCK:" + userId,
                idempotencyKey,
                c5Fingerprint(userId, request),
                () -> doUnlockSecurity(userId, idempotencyKey.trim(), request));
    }

    private ApiResult<UserSecurityStatusView> doUnlockSecurity(
            Long userId, String idempotencyKey, UserSecurityActionRequest request) {
        UserSecurityStatusView before = loadSecurityStatus(userId);
        if (before == null) {
            return c5Rejected(userId, idempotencyKey, request, "C5_USER_UNLOCK_REJECTED", 404, "USER_NOT_FOUND");
        }
        if (!before.locked()) {
            return c5Rejected(userId, idempotencyKey, request, "C5_USER_UNLOCK_REJECTED", 409,
                    "C5_ACTION_STATE_CHANGED");
        }
        int longThreshold = configInt("auth.risk.login_long_lock_threshold", 10);
        String serverLockKind = before.loginFailCount() >= longThreshold ? "LONG" : "SHORT";
        String requestedLockKind = StringUtils.hasText(request.lockKind())
                ? request.lockKind().trim().toUpperCase(Locale.ROOT) : "";
        if (!serverLockKind.equals(requestedLockKind)) {
            return c5Rejected(userId, idempotencyKey, request, "C5_USER_UNLOCK_REJECTED", 409,
                    "C5_LOCK_KIND_CHANGED");
        }
        ApiResult<UserSecurityStatusView> authorityGuard = requireC5UnlockAuthority(serverLockKind);
        if (authorityGuard != null) {
            return c5Rejected(userId, idempotencyKey, request, "C5_USER_UNLOCK_REJECTED",
                    authorityGuard.getCode(), authorityGuard.getMessage());
        }
        if (!consumeC5KycReverification(userId, "UNLOCK_" + serverLockKind, idempotencyKey, request)
                || !userRepository.resetLoginFailures(userId)) {
            throw new IllegalStateException("C5_KYC_REVERIFICATION_CONSUME_OR_STATE_FAILED");
        }
        UserSecurityStatusView updated = loadSecurityStatus(userId);
        String actor = operator(request.operator());
        Map<String, Object> detail = c5AuditDetail(request, idempotencyKey);
        detail.put("lockKind", serverLockKind);
        detail.put("fromLoginFailCount", before.loginFailCount());
        detail.put("toLoginFailCount", 0);
        c5RequiredAudit("C5_USER_UNLOCKED", "USER_SECURITY", String.valueOf(userId), userId, actor, detail);
        publishC5Event("admin.user_unlocked", userId, actor, request.reason().trim(), detail);
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<UserAccountView> updateStatus(Long userId, String idempotencyKey, UserStatusUpdateRequest request) {
        String operation = request != null && "ACTIVE".equalsIgnoreCase(request.status())
                ? "c2_account_unfreeze" : "c2_account_freeze";
        ApiResult<UserAccountView> approvalGuard = delegatedDirectExecutionGuard(
                operation, "USER_ACCOUNT", String.valueOf(userId), userId);
        if (approvalGuard != null) {
            return approvalGuard;
        }
        ApiResult<UserAccountView> guard = requireC2UserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String nextStatus;
        String reasonCode = null;
        try {
            nextStatus = normalizeUserStatus(request.status());
            if ("FROZEN".equals(nextStatus)) {
                reasonCode = normalizeC2ReasonCode(
                        request.reasonCode(), C2_FREEZE_REASON_CODES, "C2_FREEZE_REASON_CODE_REQUIRED");
            }
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        String requiredAuthority = "ACTIVE".equals(nextStatus)
                ? "user_c2_account_unfreeze" : "user_c2_account_freeze";
        ApiResult<UserAccountView> authorityGuard = requireC2Authority(requiredAuthority);
        if (authorityGuard != null) {
            return authorityGuard;
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("C", "user", String.valueOf(userId)) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        String finalReasonCode = reasonCode;
        return idempotentC2(
                "C2_USER_STATUS:" + userId,
                idempotencyKey,
                userId + "|" + nextStatus + "|" + String.valueOf(finalReasonCode) + "|" + request.reason().trim(),
                () -> doUpdateStatus(userId, idempotencyKey, request, nextStatus, finalReasonCode));
    }

    private ApiResult<UserAccountView> doUpdateStatus(
            Long userId,
            String idempotencyKey,
            UserStatusUpdateRequest request,
            String nextStatus,
            String reasonCode) {
        UserAccountView before = userRepository.findById(userId).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        boolean allowedTransition = ("ACTIVE".equalsIgnoreCase(before.status()) && "FROZEN".equals(nextStatus))
                || ("FROZEN".equalsIgnoreCase(before.status()) && "ACTIVE".equals(nextStatus));
        if (!allowedTransition) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "C2_STATUS_TRANSITION_NOT_ALLOWED");
        }
        String actor = operator(request.operator());
        if ("ACTIVE".equals(nextStatus)
                && userRepository.isFrozenBySource(userId, "K1_MULTI_ACCOUNT_CLUSTER")) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "USER_FROZEN_BY_K1_CLUSTER");
        }
        boolean transitioned = "FROZEN".equals(nextStatus)
                ? userRepository.freezeUserStatusWithSource(
                        userId, before.status(), request.reason().trim(), actor, "C2_DIRECT", idempotencyKey.trim())
                : userRepository.transitionUserStatus(userId, before.status(), nextStatus, request.reason().trim());
        if (!transitioned) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "C2_STATUS_CONCURRENTLY_CHANGED");
        }
        boolean sessionsRevoked = "FROZEN".equals(nextStatus);
        int withdrawalsFrozen = 0;
        int withdrawalsRestored = 0;
        boolean riskSignalRecorded = false;
        if (sessionsRevoked) {
            userRepository.revokeUserSessions(userId, request.reason().trim());
            withdrawalsFrozen = financeWithdrawalControlFacade.freezePendingWithdrawalsForUser(
                    userId,
                    request.reason().trim(),
                    actor);
            riskUserStateFacade.recordUserFrozen(userId, before.userNo(), request.reason().trim(), actor);
            riskSignalRecorded = true;
        } else {
            withdrawalsRestored = financeWithdrawalControlFacade.restoreWithdrawalsFrozenByUserStatus(
                    userId,
                    request.reason().trim(),
                    actor);
        }
        UserAccountView updated = userRepository.findById(userId)
                .orElse(new UserAccountView(
                        before.id(), before.userNo(), before.nickname(), before.phoneMasked(), before.countryCode(), nextStatus,
                        before.kycStatus(), before.userLevel(), before.vRank(), before.twoFactorEnabled(), before.walletUsdt(),
                        before.walletNex(), before.riskScore(), before.riskBand(), before.deviceCount(), before.activeDeviceCount(),
                        before.registeredAt(), before.lastLoginAt()));
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("fromStatus", before.status());
        detail.put("toStatus", nextStatus);
        detail.put("sessionsRevoked", sessionsRevoked);
        detail.put("withdrawalsFrozen", withdrawalsFrozen);
        detail.put("withdrawalsRestored", withdrawalsRestored);
        detail.put("riskSignalRecorded", riskSignalRecorded);
        detail.put("reasonCode", reasonCode == null ? "UNFREEZE" : reasonCode);
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", idempotencyKey.trim());
        c2RequiredAudit("C2_USER_STATUS_CHANGED", "USER", String.valueOf(userId), userId, actor, detail);
        String statusEvent = "FROZEN".equals(nextStatus) ? "admin.user_frozen" : "admin.user_unfrozen";
        LocalDateTime occurredAt = LocalDateTime.now();
        outboxService.publish("USER", String.valueOf(userId), statusEvent, Map.of(
                "userId", userId,
                "targetUserId", userId,
                "operator", actor,
                "reason", request.reason().trim(),
                "occurredAt", occurredAt.toString()));
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<UserSessionView> revokeSession(
            String refreshTokenId, String idempotencyKey, UserSessionRevokeRequest request) {
        if (!StringUtils.hasText(refreshTokenId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REFRESH_TOKEN_ID_REQUIRED");
        }
        ApiResult<UserSessionView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.reason().trim().length() > 200 || containsRawJsonOrUrl(request.reason())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "C5_REASON_LENGTH_OR_FORMAT_INVALID");
        }
        String normalizedRefreshTokenId = refreshTokenId.trim();
        return idempotentC2(
                "C5_SESSION_REVOKE_ONE:" + normalizedRefreshTokenId,
                idempotencyKey,
                normalizedRefreshTokenId + "|" + request.reason().trim(),
                () -> doRevokeSession(normalizedRefreshTokenId, idempotencyKey.trim(), request));
    }

    private ApiResult<UserSessionView> doRevokeSession(
            String refreshTokenId, String idempotencyKey, UserSessionRevokeRequest request) {
        UserSessionView session = userRepository.findSession(refreshTokenId).orElse(null);
        if (session == null) {
            return ApiResult.fail(404, "SESSION_NOT_FOUND");
        }
        if ("REVOKED".equalsIgnoreCase(session.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if (!userRepository.revokeSession(refreshTokenId, request.reason().trim())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "C5_ACTION_STATE_CHANGED");
        }
        UserSessionView updated = userRepository.findSession(refreshTokenId)
                .orElse(new UserSessionView(
                        session.userId(), session.refreshTokenId(), session.deviceName(), session.clientIpMasked(), "REVOKED",
                        session.issuedAt(), session.expiresAt(), LocalDateTime.now()));
        String actor = operator(request.operator());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("status", "REVOKED");
        detail.put("scope", "current");
        detail.put("operator", actor);
        detail.put("role", normalizeRole(roleResolver.resolveCode()));
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", idempotencyKey);
        c5RequiredAudit("C5_SESSION_REVOKED", "USER_SESSION", refreshTokenId, session.userId(), actor, detail);
        publishC5Event("admin.session_revoked", session.userId(), actor, request.reason().trim(), detail);
        return ApiResult.ok(updated);
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> revokeAllSecuritySessions(
            Long userId, String idempotencyKey, UserSessionRevokeAllRequest request) {
        ApiResult<Map<String, Object>> guard = requireUserCommand(
                userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (request.reason().trim().length() > 200 || containsRawJsonOrUrl(request.reason())) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "C5_REASON_LENGTH_OR_FORMAT_INVALID");
        }
        return idempotentC2(
                "C5_SESSION_REVOKE_ALL:" + userId,
                idempotencyKey,
                userId + "|" + request.reason().trim(),
                () -> doRevokeAllSecuritySessions(userId, idempotencyKey.trim(), request));
    }

    private ApiResult<Map<String, Object>> doRevokeAllSecuritySessions(
            Long userId, String idempotencyKey, UserSessionRevokeAllRequest request) {
        if (userRepository.findById(userId).isEmpty()) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        long activeBefore = userRepository.countActiveSessions(userId, sessionIdleDays());
        if (activeBefore <= 0) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "C5_NO_ACTIVE_SESSION");
        }
        userRepository.revokeUserSessions(userId, request.reason().trim());
        String actor = operator(request.operator());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("revokedSessionCount", activeBefore);
        detail.put("scope", "all");
        detail.put("operator", actor);
        detail.put("role", normalizeRole(roleResolver.resolveCode()));
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", idempotencyKey);
        c5RequiredAudit("C5_ALL_SESSIONS_REVOKED", "USER_SESSION", String.valueOf(userId), userId, actor, detail);
        publishC5Event("admin.session_revoked", userId, actor, request.reason().trim(), detail);
        return ApiResult.ok(Map.of("userId", userId, "revokedSessionCount", activeBefore));
    }

    @Transactional(rollbackFor = Exception.class)
    public ApiResult<Map<String, Object>> startImpersonation(Long userId, String idempotencyKey, UserImpersonationRequest request) {
        ApiResult<Map<String, Object>> approvalGuard = delegatedDirectExecutionGuard(
                "c2_impersonate_start", "USER_IMPERSONATION", String.valueOf(userId), userId);
        if (approvalGuard != null) {
            return approvalGuard;
        }
        ApiResult<Map<String, Object>> guard = requireC2UserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<Map<String, Object>> authorityGuard = requireC2Authority("user_c2_impersonate_start");
        if (authorityGuard != null) {
            return authorityGuard;
        }
        UserAccountView user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        if (!"ACTIVE".equalsIgnoreCase(user.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "IMPERSONATION_REQUIRES_ACTIVE_ACCOUNT");
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("C", "user", String.valueOf(userId)) > 0) {
            return ApiResult.fail(409, "OBJECT_LOCKED_BY_A2");
        }
        int ttlMinutes = request.ttlMinutes() == null ? 15 : request.ttlMinutes();
        if (!C2_IMPERSONATION_TTLS.contains(ttlMinutes)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "IMPERSONATION_TTL_UNSUPPORTED");
        }
        String reasonCode;
        try {
            reasonCode = normalizeC2ReasonCode(
                    request.reasonCode(), C2_IMPERSONATION_REASON_CODES, "C2_IMPERSONATION_REASON_CODE_REQUIRED");
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        return idempotentC2(
                "C2_IMPERSONATION_START:" + userId,
                idempotencyKey,
                userId + "|" + ttlMinutes + "|" + reasonCode + "|" + request.reason().trim(),
                () -> doStartImpersonation(userId, user.userNo(), idempotencyKey, request, ttlMinutes, reasonCode));
    }

    private ApiResult<Map<String, Object>> doStartImpersonation(
            Long userId,
            String userNo,
            String idempotencyKey,
            UserImpersonationRequest request,
            int ttlMinutes,
            String reasonCode) {
        userRepository.lockUser(userId);
        UserAccountView lockedUser = userRepository.findById(userId).orElse(null);
        if (lockedUser == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        if (!"ACTIVE".equalsIgnoreCase(lockedUser.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "IMPERSONATION_REQUIRES_ACTIVE_ACCOUNT");
        }
        if (userRepository.hasActiveImpersonation(userId)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "IMPERSONATION_SESSION_ALREADY_ACTIVE");
        }
        String sessionNo = "IMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        LocalDateTime sessionStart = LocalDateTime.now();
        LocalDateTime expiresAt = sessionStart.plusMinutes(ttlMinutes);
        String operator = operator(request.operator());
        userRepository.recordImpersonationSession(sessionNo, userId, ttlMinutes, operator, request.reason().trim(), expiresAt);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionNo", sessionNo);
        response.put("userId", userId);
        response.put("status", "ACTIVE");
        response.put("ttlMinutes", ttlMinutes);
        response.put("expiresAt", expiresAt);
        response.put("claim", "impersonate_readonly");
        response.put("writePolicy", "DENY");
        response.put("boundary", "server-issued impersonation identity is read-only and expires with this session");
        response.put("accessToken", tokenProvider.createImpersonationToken(userId, userNo, sessionNo, ttlMinutes));
        Map<String, Object> detail = Map.of(
                "ttlMinutes", ttlMinutes,
                "reasonCode", reasonCode,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim());
        c2RequiredAudit("C2_USER_IMPERSONATION_STARTED", "USER_IMPERSONATION", sessionNo, userId, operator, detail);
        outboxService.publish("USER_IMPERSONATION", sessionNo, "admin.user_impersonation_started", Map.of(
                "userId", userId,
                "targetUserId", userId,
                "operator", operator,
                "reason", request.reason().trim(),
                "ttlMinutes", ttlMinutes,
                "sessionStart", sessionStart.toString(),
                "occurredAt", sessionStart.toString()));
        return ApiResult.ok(response);
    }

    @Transactional
    public ApiResult<Map<String, Object>> createAssetAdjustment(
            Long userId,
            String idempotencyKey,
            UserAssetAdjustmentRequest request) {
        String actor = operator(request == null ? null : request.operator());
        ApiResult<Map<String, Object>> guard = requireC3Command(userId, idempotencyKey, request);
        if (guard != null) {
            c3FailureAudit("C3_ASSET_ADJUSTMENT_REJECTED", String.valueOf(userId), userId, actor,
                    guard.getMessage(), idempotencyKey);
            return guard;
        }
        Map<String, Object> fingerprint = c3Fingerprint(userId, request, null);
        try {
            return idempotentC3("C3_ASSET_ADJUSTMENT_CREATE", idempotencyKey, fingerprint,
                    () -> executeAssetAdjustment(userId, idempotencyKey, request, false));
        } catch (DuplicateKeyException ex) {
            c3FailureAudit("C3_ASSET_ADJUSTMENT_REJECTED", String.valueOf(userId), userId, actor,
                    "C3_ALREADY_REVERSED", idempotencyKey);
            throw new BizException(409, "C3_ALREADY_REVERSED");
        } catch (RuntimeException ex) {
            c3FailureAudit("C3_ASSET_ADJUSTMENT_FAILED", String.valueOf(userId), userId, actor,
                    text(ex.getMessage()), idempotencyKey);
            throw ex;
        }
    }

    @Transactional
    public ApiResult<Map<String, Object>> requestLargeAssetAdjustment(
            Long userId,
            String idempotencyKey,
            UserAssetAdjustmentRequest request) {
        String actor = operator(request == null ? null : request.operator());
        ApiResult<Map<String, Object>> guard = requireC3Command(userId, idempotencyKey, request);
        if (guard != null) {
            c3FailureAudit("C3_LARGE_ADJUSTMENT_REQUEST_REJECTED", String.valueOf(userId), userId, actor,
                    guard.getMessage(), idempotencyKey);
            return guard;
        }
        try {
            return idempotentC3("C3_LARGE_ADJUSTMENT_REQUEST", idempotencyKey, c3Fingerprint(userId, request, null), () -> {
                UserAccountView account = userRepository.findById(userId).orElse(null);
                if (account == null) {
                    return c3Reject(404, "USER_NOT_FOUND", String.valueOf(userId), userId, actor, idempotencyKey);
                }
                String asset;
                String direction;
                BigDecimal amount;
                String reasonCode;
                String evidenceRef;
                try {
                    asset = normalizeAsset(request.asset());
                    direction = normalizeDirection(request.direction());
                    amount = positiveAmount(request.amount());
                    reasonCode = normalizeC3ReasonCode(request.reasonCode(), request.referenceType(), request.referenceId());
                    evidenceRef = requireC3Evidence(request.evidenceRef());
                } catch (IllegalArgumentException ex) {
                    return c3Reject(400, ex.getMessage(), String.valueOf(userId), userId, actor, idempotencyKey);
                }
                TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
                BigDecimal nexRate = c3NexUsdRate(coverage);
                if ("NEX".equals(asset) && nexRate.signum() <= 0) {
                    return c3Reject(422, "C3_NEX_PRICE_UNAVAILABLE", String.valueOf(userId), userId, actor, idempotencyKey);
                }
                BigDecimal amountUsd = ("USDT".equals(asset) ? amount : amount.multiply(nexRate))
                        .setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
                if (amount.scale() > 6 || amountUsd.compareTo(C3_MAX_AMOUNT_USD) > 0) {
                    return c3Reject(400, "C3_AMOUNT_EXCEEDS_LIMIT", String.valueOf(userId), userId, actor, idempotencyKey);
                }
                if (amountUsd.compareTo(C3_LARGE_THRESHOLD_USD) <= 0) {
                    return c3Reject(400, "C3_LARGE_REQUEST_THRESHOLD_NOT_REACHED", String.valueOf(userId), userId, actor, idempotencyKey);
                }
                if (!"SUPPORT".equals(normalizeRole(roleResolver.resolveCode()))) {
                    return c3Reject(403, "C3_LARGE_REQUEST_SUPPORT_ONLY", String.valueOf(userId), userId, actor, idempotencyKey);
                }
                String requestNo = "REQ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
                userRepository.createAssetAdjustment(
                        requestNo, userId, asset, direction, amount, amountUsd, reasonCode, request.reason().trim(),
                        evidenceRef, idempotencyKey.trim(), null, actor);
                c3RequiredAudit("C3_LARGE_ADJUSTMENT_REQUESTED", requestNo, userId, actor, Map.of(
                        "asset", asset,
                        "direction", direction,
                        "amount", amount,
                        "amountUsd", amountUsd,
                        "reasonCode", reasonCode,
                        "reason", request.reason().trim(),
                        "evidenceRef", evidenceRef,
                        "idempotencyKey", idempotencyKey.trim()));
                return ApiResult.ok(Map.of(
                        "requestNo", requestNo,
                        "adjustmentNo", requestNo,
                        "status", "PENDING_REVIEW",
                        "amountUsd", amountUsd));
            });
        } catch (RuntimeException ex) {
            c3FailureAudit("C3_LARGE_ADJUSTMENT_REQUEST_FAILED", String.valueOf(userId), userId, actor,
                    text(ex.getMessage()), idempotencyKey);
            throw ex;
        }
    }

    private ApiResult<Map<String, Object>> executeAssetAdjustment(
            Long userId,
            String idempotencyKey,
            UserAssetAdjustmentRequest request,
            boolean reversalFlow) {
        UserAccountView account = userRepository.findById(userId).orElse(null);
        if (account == null) {
            return c3Reject(404, "USER_NOT_FOUND", String.valueOf(userId), userId, operator(request.operator()), idempotencyKey);
        }
        String asset;
        String direction;
        BigDecimal amount;
        String reasonCode;
        String evidenceRef;
        try {
            asset = normalizeAsset(request.asset());
            direction = normalizeDirection(request.direction());
            amount = positiveAmount(request.amount());
            if (amount.scale() > 6) {
                return c3Reject(400, "C3_AMOUNT_EXCEEDS_LIMIT", String.valueOf(userId), userId,
                        operator(request.operator()), idempotencyKey);
            }
            reasonCode = normalizeC3ReasonCode(request.reasonCode(), request.referenceType(), request.referenceId());
            evidenceRef = requireC3Evidence(request.evidenceRef());
        } catch (IllegalArgumentException ex) {
            return c3Reject(400, ex.getMessage(), String.valueOf(userId), userId,
                    operator(request.operator()), idempotencyKey);
        }
        if (!reversalFlow && StringUtils.hasText(request.reversalOf())) {
            return c3Reject(400, "C3_REVERSAL_ENDPOINT_REQUIRED", String.valueOf(userId), userId,
                    operator(request.operator()), idempotencyKey);
        }

        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        UserAssetAdjustmentView reversalOriginal = reversalFlow && StringUtils.hasText(request.reversalOf())
                ? userRepository.findAssetAdjustment(request.reversalOf().trim()).orElse(null)
                : null;
        BigDecimal nexUsdRate = c3NexUsdRate(coverage);
        if ("NEX".equals(asset) && nexUsdRate.signum() <= 0
                && (reversalOriginal == null || reversalOriginal.amountUsd() == null)) {
            return c3Reject(422, "C3_NEX_PRICE_UNAVAILABLE", String.valueOf(userId), userId,
                    operator(request.operator()), idempotencyKey);
        }
        BigDecimal amountUsd = reversalOriginal != null && reversalOriginal.amountUsd() != null
                ? reversalOriginal.amountUsd()
                : "USDT".equals(asset) ? amount : amount.multiply(nexUsdRate);
        amountUsd = amountUsd.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
        if (amountUsd.compareTo(C3_MAX_AMOUNT_USD) > 0) {
            return c3Reject(400, "C3_AMOUNT_EXCEEDS_LIMIT", String.valueOf(userId), userId,
                    operator(request.operator()), idempotencyKey);
        }

        String role = normalizeRole(roleResolver.resolveCode());
        Set<String> allowedRoles = reversalFlow
                ? C3_REVERSAL_ROLES
                : amountUsd.compareTo(C3_LARGE_THRESHOLD_USD) > 0 ? C3_LARGE_EXECUTOR_ROLES : C3_BASE_EXECUTOR_ROLES;
        if (!allowedRoles.contains(role)) {
            String code = amountUsd.compareTo(C3_LARGE_THRESHOLD_USD) > 0
                    ? "C3_LARGE_ADJUSTMENT_FORBIDDEN"
                    : "C3_ADJUSTMENT_FORBIDDEN";
            return c3Reject(403, code, String.valueOf(userId), userId, operator(request.operator()), idempotencyKey);
        }
        BigDecimal projectedCoverage = projectedCoverageRatio(coverage, direction, amountUsd);
        if (!reversalFlow && "CREDIT".equals(direction)
                && (!coverage.reliable() || projectedCoverage.compareTo(coverage.redlinePct()) < 0)) {
            return c3Reject(422,
                    coverage.reliable() ? OpsErrorCode.COVERAGE_BELOW_REDLINE.name() : "C3_COVERAGE_UNRELIABLE",
                    String.valueOf(userId), userId, operator(request.operator()), idempotencyKey);
        }

        String adjustmentNo = "ADJ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        String actor = operator(request.operator());
        String reason = request.reason().trim();
        String reversalOf = StringUtils.hasText(request.reversalOf()) ? request.reversalOf().trim() : null;
        userRepository.createAssetAdjustment(
                adjustmentNo, userId, asset, direction, amount, amountUsd, reasonCode, reason,
                evidenceRef, idempotencyKey.trim(), reversalOf, actor);
        UserAssetAdjustmentView pending = userRepository.findAssetAdjustment(adjustmentNo)
                .orElseThrow(() -> new BizException(409, "C3_ADJUSTMENT_CREATE_STATE_LOST"));
        Long ledgerId = userRepository.approveAssetAdjustmentAndPostLedger(pending, actor, reason);
        UserAssetAdjustmentView approved = userRepository.findAssetAdjustment(adjustmentNo)
                .orElseThrow(() -> new BizException(409, "C3_ADJUSTMENT_RELOAD_FAILED"));

        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("asset", asset);
        auditDetail.put("direction", direction);
        auditDetail.put("amount", amount);
        auditDetail.put("amountUsd", amountUsd);
        auditDetail.put("balanceAfter", approved.balanceAfter());
        auditDetail.put("reasonCode", reasonCode);
        auditDetail.put("reason", reason);
        auditDetail.put("evidenceRef", evidenceRef);
        auditDetail.put("idempotencyKey", idempotencyKey.trim());
        auditDetail.put("ledgerId", ledgerId);
        auditDetail.put("projectedCoverageRatio", projectedCoverage);
        if (reversalOf != null) {
            auditDetail.put("reversalOf", reversalOf);
        }
        c3RequiredAudit(reversalFlow ? "C3_ASSET_ADJUSTMENT_REVERSED" : "C3_ASSET_ADJUSTMENT_EXECUTED",
                adjustmentNo, userId, actor, auditDetail);
        publishC3Events(approved, actor, reasonCode);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("adjustmentNo", adjustmentNo);
        response.put("userId", userId);
        response.put("asset", asset);
        response.put("direction", direction);
        response.put("amount", amount);
        response.put("amountUsd", amountUsd);
        response.put("reasonCode", reasonCode);
        response.put("status", "APPROVED");
        response.put("ledgerId", ledgerId);
        response.put("balanceAfter", approved.balanceAfter());
        response.put("projectedCoverageRatio", projectedCoverage);
        if (reversalOf != null) {
            response.put("reversalOf", reversalOf);
        }
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> assetAdjustmentOverview() {
        ensureAssetAdjustmentSeeds();
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("coverage", coverage);
        overview.put("pending", countAssetAdjustments("PENDING_REVIEW"));
        overview.put("approved", countAssetAdjustments("APPROVED"));
        overview.put("rejected", countAssetAdjustments("REJECTED"));
        overview.put("suspended", countAssetAdjustments("SUSPENDED"));
        overview.put("redline", coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0);
        overview.put("singleCreditReviewCapUsd", 500);
        overview.put("maxAdjustmentAmount", C3_MAX_AMOUNT_USD);
        overview.put("nexUsdRate", c3NexUsdRate(coverage));
        overview.put("sources", List.of("wallet asset adjustments", "user accounts", "treasury coverage", "required audit", "event outbox"));
        overview.put("sunsetCompatibility", List.of("Points adjustments are rejected", "Premium and NEX v2 are read-only historical concepts"));
        return ApiResult.ok(overview);
    }

    public ApiResult<PageResult<UserAssetAdjustmentView>> assetAdjustments(UserAssetAdjustmentQueryRequest request) {
        try {
            ensureAssetAdjustmentSeeds();
            String status = normalizeOptionalAdjustmentStatus(request == null ? null : request.status());
            String asset = normalizeOptionalAsset(request == null ? null : request.asset());
            return ApiResult.ok(userRepository.pageAssetAdjustments(new UserAssetAdjustmentQueryRequest(
                    status,
                    asset,
                    request == null ? null : request.userId(),
                    request == null ? null : request.keyword(),
                    request == null ? null : request.pageNum(),
                    request == null ? null : request.pageSize(),
                    request == null ? null : request.historyOnly())));
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
    }

    public ApiResult<PageResult<UserAccountView>> assetAdjustmentAccounts(UserQueryRequest request) {
        String validationError = validateProfileQuery(request);
        if (validationError != null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), validationError);
        }
        return ApiResult.ok(userRepository.pageProfiles(request));
    }

    public ApiResult<UserAssetAdjustmentDetail> assetAdjustmentDetail(String adjustmentNo) {
        ensureAssetAdjustmentSeeds();
        String normalizedNo;
        try {
            normalizedNo = requireText(adjustmentNo, "ADJUSTMENT_NO_REQUIRED");
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        return userRepository.findAssetAdjustment(normalizedNo)
                .map(this::assetAdjustmentDetail)
                .map(ApiResult::ok)
                .orElseGet(() -> ApiResult.fail(404, "ASSET_ADJUSTMENT_NOT_FOUND"));
    }

    public ApiResult<Map<String, Object>> assetAdjustmentContext(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(400, "USER_ID_REQUIRED");
        }
        UserAccountView account = userRepository.findById(userId).orElse(null);
        if (account == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("account", account);
        context.put("pendingWithdraw", userRepository.findWalletPendingWithdraw(userId));
        context.put("coverage", coverage);
        context.put("nexUsdRate", c3NexUsdRate(coverage));
        context.put("largeThresholdUsd", C3_LARGE_THRESHOLD_USD);
        context.put("maxAdjustmentAmount", C3_MAX_AMOUNT_USD);
        return ApiResult.ok(context);
    }

    @Transactional
    public ApiResult<Map<String, Object>> reverseAssetAdjustment(
            String adjustmentNo,
            String idempotencyKey,
            UserAssetAdjustmentReviewRequest request) {
        ApiResult<UserAssetAdjustmentDetail> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        String actor = operator(request == null ? null : request.operator());
        if (guard != null) {
            c3FailureAudit("C3_ASSET_ADJUSTMENT_REVERSAL_REJECTED", text(adjustmentNo), null, actor,
                    guard.getMessage(), idempotencyKey);
            return ApiResult.fail(guard.getCode(), guard.getMessage());
        }
        String normalizedNo;
        try {
            normalizedNo = requireText(adjustmentNo, "ADJUSTMENT_NO_REQUIRED");
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(400, ex.getMessage());
        }
        UserAssetAdjustmentView original = userRepository.findAssetAdjustment(normalizedNo).orElse(null);
        if (original == null) {
            return ApiResult.fail(404, "ASSET_ADJUSTMENT_NOT_FOUND");
        }
        if (!"APPROVED".equalsIgnoreCase(original.status()) || StringUtils.hasText(original.reversalOf())) {
            return c3Reject(409, "C3_ONLY_ORIGINAL_APPROVED_ADJUSTMENT_REVERSIBLE", normalizedNo,
                    original.userId(), actor, idempotencyKey);
        }
        if (userRepository.assetAdjustmentHasReversal(normalizedNo)) {
            return c3Reject(409, "C3_ALREADY_REVERSED", normalizedNo, original.userId(), actor, idempotencyKey);
        }
        UserAssetAdjustmentRequest reversal = new UserAssetAdjustmentRequest(
                original.asset(),
                "CREDIT".equalsIgnoreCase(original.direction()) ? "DEBIT" : "CREDIT",
                original.amount().toPlainString(),
                "REVERSAL",
                request.reason().trim(),
                "original-adjustment:" + normalizedNo,
                normalizedNo,
                actor,
                null,
                null);
        Map<String, Object> fingerprint = c3Fingerprint(original.userId(), reversal, normalizedNo);
        try {
            return idempotentC3("C3_ASSET_ADJUSTMENT_REVERSE", idempotencyKey, fingerprint,
                    () -> executeAssetAdjustment(original.userId(), idempotencyKey, reversal, true));
        } catch (DuplicateKeyException ex) {
            c3FailureAudit("C3_ASSET_ADJUSTMENT_REVERSAL_REJECTED", normalizedNo, original.userId(), actor,
                    "C3_ALREADY_REVERSED", idempotencyKey);
            throw new BizException(409, "C3_ALREADY_REVERSED");
        } catch (RuntimeException ex) {
            c3FailureAudit("C3_ASSET_ADJUSTMENT_REVERSAL_FAILED", normalizedNo, original.userId(), actor,
                    text(ex.getMessage()), idempotencyKey);
            throw ex;
        }
    }

    @Transactional
    public ApiResult<UserAssetAdjustmentDetail> approveAssetAdjustment(
            String adjustmentNo,
            String idempotencyKey,
            UserAssetAdjustmentReviewRequest request) {
        return reviewAssetAdjustmentIdempotent(adjustmentNo, "APPROVED", idempotencyKey, request);
    }

    @Transactional
    public ApiResult<UserAssetAdjustmentDetail> rejectAssetAdjustment(
            String adjustmentNo,
            String idempotencyKey,
            UserAssetAdjustmentReviewRequest request) {
        return reviewAssetAdjustmentIdempotent(adjustmentNo, "REJECTED", idempotencyKey, request);
    }

    private ApiResult<UserAssetAdjustmentDetail> reviewAssetAdjustmentIdempotent(
            String adjustmentNo,
            String nextStatus,
            String idempotencyKey,
            UserAssetAdjustmentReviewRequest request) {
        String actor = operator(request == null ? null : request.operator());
        ApiResult<UserAssetAdjustmentDetail> guard = requireCommand(
                idempotencyKey,
                request == null ? null : request.reason());
        if (guard != null) {
            c3FailureAudit("C3_ASSET_ADJUSTMENT_REVIEW_REJECTED", text(adjustmentNo), null, actor,
                    guard.getMessage(), idempotencyKey);
            return guard;
        }
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("adjustmentNo", text(adjustmentNo).trim());
        fingerprint.put("nextStatus", nextStatus);
        fingerprint.put("reason", text(request.reason()).trim());
        try {
            return idempotentC3(
                    "C3_ASSET_ADJUSTMENT_" + nextStatus,
                    idempotencyKey,
                    fingerprint,
                    () -> reviewAssetAdjustment(adjustmentNo, nextStatus, idempotencyKey, request));
        } catch (RuntimeException ex) {
            c3FailureAudit("C3_ASSET_ADJUSTMENT_REVIEW_FAILED", text(adjustmentNo), null, actor,
                    text(ex.getMessage()), idempotencyKey);
            throw ex;
        }
    }

    private ApiResult<UserAssetAdjustmentDetail> reviewAssetAdjustment(
            String adjustmentNo,
            String nextStatus,
            String idempotencyKey,
            UserAssetAdjustmentReviewRequest request) {
        ApiResult<UserAssetAdjustmentDetail> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String operator = operator(request.operator());
        if (!C3_REVIEWER_ROLES.contains(normalizeRole(roleResolver.resolveCode()))) {
            return c3Reject(403, "C3_ADJUSTMENT_REVIEW_FORBIDDEN", text(adjustmentNo), null,
                    operator, idempotencyKey);
        }
        String normalizedNo;
        try {
            normalizedNo = requireText(adjustmentNo, "ADJUSTMENT_NO_REQUIRED");
        } catch (IllegalArgumentException ex) {
            return c3Reject(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage(), text(adjustmentNo), null,
                    operator, idempotencyKey);
        }
        if (!A2ReplayContext.isReplaying()
                && lockMapper.countActiveByTarget("C", "adjustment", normalizedNo) > 0) {
            return c3Reject(409, "OBJECT_LOCKED_BY_A2", normalizedNo, null, operator, idempotencyKey);
        }
        UserAssetAdjustmentView before = userRepository.findAssetAdjustment(normalizedNo).orElse(null);
        if (before == null) {
            return c3Reject(404, "ASSET_ADJUSTMENT_NOT_FOUND", normalizedNo, null, operator, idempotencyKey);
        }
        if (StringUtils.hasText(before.maker()) && before.maker().trim().equalsIgnoreCase(operator)) {
            return c3Reject(409, "C3_MAKER_CANNOT_REVIEW", normalizedNo, before.userId(), operator, idempotencyKey);
        }
        String currentStatus = normalizeAdjustmentStatus(before.status());
        if (!REVIEWABLE_ADJUSTMENT_STATUSES.contains(currentStatus)) {
            return c3Reject(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name(),
                    normalizedNo, before.userId(), operator, idempotencyKey);
        }
        String reason = request.reason().trim();
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        BigDecimal amountUsd = before.amountUsd() == null ? before.amount() : before.amountUsd();
        if ("APPROVED".equals(nextStatus) && before.credit()
                && (!coverage.reliable() || projectedCoverageRatio(coverage, before.direction(), amountUsd)
                        .compareTo(coverage.redlinePct()) < 0)) {
            if (!userRepository.reviewAssetAdjustment(normalizedNo, "REJECTED", operator, "COVERAGE_BELOW_REDLINE:" + reason)) {
                return c3Reject(409, "ASSET_ADJUSTMENT_REVIEW_RACE_LOST", normalizedNo,
                        before.userId(), operator, idempotencyKey);
            }
            c3FailureAudit("C3_ASSET_ADJUSTMENT_REJECTED_BY_COVERAGE", normalizedNo, before.userId(), operator,
                    OpsErrorCode.COVERAGE_BELOW_REDLINE.name(), idempotencyKey);
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        Long ledgerId = null;
        if ("APPROVED".equals(nextStatus)) {
            ledgerId = userRepository.approveAssetAdjustmentAndPostLedger(before, operator, reason);
        } else {
            if (!userRepository.reviewAssetAdjustment(normalizedNo, nextStatus, operator, reason)) {
                return c3Reject(409, "ASSET_ADJUSTMENT_REVIEW_RACE_LOST", normalizedNo,
                        before.userId(), operator, idempotencyKey);
            }
        }
        UserAssetAdjustmentView updated = userRepository.findAssetAdjustment(normalizedNo).orElse(null);
        if (updated == null) {
            return c3Reject(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "ASSET_ADJUSTMENT_RELOAD_FAILED",
                    normalizedNo, before.userId(), operator, idempotencyKey);
        }
        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("fromStatus", currentStatus);
        auditDetail.put("toStatus", nextStatus);
        auditDetail.put("asset", before.asset());
        auditDetail.put("direction", before.direction());
        auditDetail.put("amount", before.amount());
        auditDetail.put("reason", reason);
        auditDetail.put("idempotencyKey", idempotencyKey.trim());
        if (ledgerId != null) {
            auditDetail.put("ledgerId", ledgerId);
        }
        c3RequiredAudit("APPROVED".equals(nextStatus) ? "C3_ASSET_ADJUSTMENT_APPROVED" : "C3_ASSET_ADJUSTMENT_REJECTED",
                normalizedNo, before.userId(), operator, auditDetail);
        if ("APPROVED".equals(nextStatus)) {
            publishC3Events(updated, operator, updated.reasonCode());
        }
        return ApiResult.ok(assetAdjustmentDetail(updated));
    }

    private long numberValue(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private <T> ApiResult<T> requirePlainReason(String reason, String message) {
        return containsRawJsonOrUrl(reason)
                ? ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), message)
                : null;
    }

    private <T> ApiResult<T> requireUserCommand(Long userId, String idempotencyKey, String reason) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        return requireCommand(idempotencyKey, reason);
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

    private <T> ApiResult<T> requireC2UserCommand(Long userId, String idempotencyKey, String reason) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        return requireC2Command(idempotencyKey, reason);
    }

    private <T> ApiResult<T> requireC2Command(String idempotencyKey, String reason) {
        if (!StringUtils.hasText(idempotencyKey)) {
            return ApiResult.fail(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        int length = StringUtils.hasText(reason) ? reason.trim().length() : 0;
        if (length < 8 || length > 200) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "C2_REASON_LENGTH_INVALID");
        }
        return null;
    }

    private String normalizeC2ReasonCode(String value, Set<String> supported, String missingMessage) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(missingMessage);
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "风控命中" -> "RISK_HIT";
            case "反洗钱审查" -> "AML_REVIEW";
            case "用户申诉" -> "USER_APPEAL";
            case "司法协查" -> "JUDICIAL_ASSISTANCE";
            case "用户报障复现" -> "USER_ISSUE_REPRO";
            case "排查显示异常" -> "DISPLAY_ANOMALY";
            case "其他" -> "OTHER";
            default -> normalized;
        };
        if (!supported.contains(normalized)) {
            throw new IllegalArgumentException("C2_REASON_CODE_UNSUPPORTED");
        }
        return normalized;
    }

    private <T> ApiResult<T> requireC3Command(
            Long userId,
            String idempotencyKey,
            UserAssetAdjustmentRequest request) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(400, "USER_ID_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey) || idempotencyKey.trim().length() > 128) {
            return ApiResult.fail(400, "IDEMPOTENCY_KEY_REQUIRED");
        }
        if (request == null) {
            return ApiResult.fail(400, "C3_REQUEST_REQUIRED");
        }
        int reasonLength = StringUtils.hasText(request.reason()) ? request.reason().trim().length() : 0;
        if (reasonLength < 8 || reasonLength > 200) {
            return ApiResult.fail(400, "C3_REASON_LENGTH_INVALID");
        }
        if (containsRawJsonOrUrl(request.reason())) {
            return ApiResult.fail(400, "C3_REASON_PLAIN_TEXT_REQUIRED");
        }
        if (!StringUtils.hasText(request.reasonCode())
                && !(StringUtils.hasText(request.referenceType()) && StringUtils.hasText(request.referenceId()))) {
            return ApiResult.fail(400, "C3_REASON_CODE_REQUIRED");
        }
        if (!StringUtils.hasText(request.evidenceRef())) {
            return ApiResult.fail(400, "C3_EVIDENCE_REQUIRED");
        }
        return null;
    }

    private Map<String, Object> c3Fingerprint(
            Long userId,
            UserAssetAdjustmentRequest request,
            String originalAdjustmentNo) {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("userId", userId);
        fingerprint.put("asset", text(request.asset()).trim().toUpperCase(Locale.ROOT));
        fingerprint.put("direction", text(request.direction()).trim().toUpperCase(Locale.ROOT));
        fingerprint.put("amount", text(request.amount()).trim());
        fingerprint.put("reasonCode", text(request.reasonCode()).trim().toUpperCase(Locale.ROOT));
        fingerprint.put("reason", text(request.reason()).trim());
        fingerprint.put("evidenceRef", text(request.evidenceRef()).trim());
        fingerprint.put("originalAdjustmentNo", text(originalAdjustmentNo).trim());
        return fingerprint;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> ApiResult<T> idempotentC3(
            String scope,
            String idempotencyKey,
            Object requestFingerprint,
            Supplier<ApiResult<T>> action) {
        return (ApiResult<T>) idempotencyService.execute(
                scope,
                idempotencyKey.trim(),
                filterHash(requestFingerprint),
                ApiResult.class,
                (Supplier) action);
    }

    private String normalizeRole(String value) {
        return StringUtils.hasText(value) ? value.trim().toUpperCase(Locale.ROOT) : "UNASSIGNED";
    }

    private String normalizeC3ReasonCode(String value, String referenceType, String referenceId) {
        if (!StringUtils.hasText(value)
                && StringUtils.hasText(referenceType)
                && StringUtils.hasText(referenceId)) {
            return "OPS_USER_ADJUSTMENT";
        }
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("C3_REASON_CODE_REQUIRED");
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "客服补偿" -> "SUPPORT_COMPENSATION";
            case "系统纠错" -> "SYSTEM_CORRECTION";
            case "活动补发" -> "CAMPAIGN_REISSUE";
            case "争议退回" -> "DISPUTE_RETURN";
            case "冲正" -> "REVERSAL";
            case "运营调账" -> "OPS_USER_ADJUSTMENT";
            default -> normalized;
        };
        if (!C3_REASON_CODES.contains(normalized)) {
            throw new IllegalArgumentException("C3_REASON_CODE_UNSUPPORTED");
        }
        return normalized;
    }

    private String requireC3Evidence(String value) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("C3_EVIDENCE_REQUIRED");
        }
        String normalized = value.trim();
        if (normalized.length() < 3 || normalized.length() > 255 || normalized.startsWith("{") || normalized.startsWith("[")) {
            throw new IllegalArgumentException("C3_EVIDENCE_INVALID");
        }
        return normalized;
    }

    private BigDecimal c3NexUsdRate(TreasuryCoverageSnapshot coverage) {
        return configFacade.activeValue(C3_NEX_PRICE_KEY)
                .filter(StringUtils::hasText)
                .map(String::trim)
                .map(value -> {
                    try {
                        return new BigDecimal(value);
                    } catch (NumberFormatException ex) {
                        return BigDecimal.ZERO;
                    }
                })
                .filter(value -> value.signum() > 0)
                .orElseGet(() -> coverage != null && coverage.nexUsdRate() != null
                        ? coverage.nexUsdRate()
                        : BigDecimal.ZERO);
    }

    private BigDecimal projectedCoverageRatio(
            TreasuryCoverageSnapshot coverage,
            String direction,
            BigDecimal amountUsd) {
        if (coverage == null || coverage.coverageRatio() == null) {
            return BigDecimal.ZERO;
        }
        BigDecimal reserve = coverage.reserveUsd();
        BigDecimal liabilities = coverage.liabilitiesUsd();
        if (reserve == null || liabilities == null || reserve.signum() <= 0 || liabilities.signum() <= 0) {
            return coverage.coverageRatio();
        }
        BigDecimal projectedLiabilities = "CREDIT".equalsIgnoreCase(direction)
                ? liabilities.add(amountUsd)
                : liabilities.subtract(amountUsd).max(BigDecimal.ZERO);
        if (projectedLiabilities.signum() == 0) {
            return new BigDecimal("999999");
        }
        return reserve.multiply(new BigDecimal("100"))
                .divide(projectedLiabilities, 6, RoundingMode.HALF_UP);
    }

    private <T> ApiResult<T> c3Reject(
            int status,
            String message,
            String resourceId,
            Long userId,
            String actor,
            String idempotencyKey) {
        c3FailureAudit("C3_ASSET_ADJUSTMENT_REJECTED", resourceId, userId, actor, message, idempotencyKey);
        return ApiResult.fail(status, message);
    }

    private void publishC3Events(UserAssetAdjustmentView adjustment, String actor, String reasonCode) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetUserId", adjustment.userId());
        payload.put("adjustmentNo", adjustment.adjustmentNo());
        payload.put("billId", adjustment.ledgerId());
        payload.put("asset", adjustment.asset());
        payload.put("direction", adjustment.direction());
        payload.put("amount", adjustment.amount());
        payload.put("amountUsd", adjustment.amountUsd());
        payload.put("balanceAfter", adjustment.balanceAfter());
        payload.put("reasonCode", reasonCode);
        payload.put("operator", operator(actor));
        payload.put("occurredAt", Instant.now().toString());
        if (StringUtils.hasText(adjustment.reversalOf())) {
            payload.put("reversalOf", adjustment.reversalOf());
        }
        outboxService.publish("USER_ASSET_ADJUSTMENT", adjustment.adjustmentNo(), "admin.balance_adjusted", payload);
        outboxService.publish("WALLET_LEDGER", String.valueOf(adjustment.ledgerId()), "admin.bill_adjusted", payload);
    }

    private void c3RequiredAudit(
            String action,
            String resourceId,
            Long userId,
            String actor,
            Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("USER_ASSET_ADJUSTMENT")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(operator(actor))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private void c3FailureAudit(
            String action,
            String resourceId,
            Long userId,
            String actor,
            String failureReason,
            String idempotencyKey) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("failureReason", text(failureReason));
        detail.put("idempotencyKey", text(idempotencyKey));
        auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("USER_ASSET_ADJUSTMENT")
                .resourceId(text(resourceId))
                .bizNo(text(resourceId))
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(operator(actor))
                .result("REJECTED")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private <T> ApiResult<T> idempotentC2(
            String scope,
            String idempotencyKey,
            Object requestFingerprint,
            Supplier<ApiResult<T>> action) {
        return (ApiResult<T>) idempotencyService.execute(
                scope,
                idempotencyKey.trim(),
                filterHash(requestFingerprint),
                ApiResult.class,
                (Supplier) action);
    }

    private <T> ApiResult<T> requireC2Authority(String authority) {
        if (A2ReplayContext.isReplaying()) {
            return null;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        boolean allowed = authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
        return allowed ? null : ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "C2_ACTION_FORBIDDEN:" + authority);
    }

    private <T> ApiResult<T> requireC5HighRiskCommand(
            Long userId, String idempotencyKey, UserSecurityActionRequest request, String action) {
        if (userId == null || userId <= 0) {
            return c5Rejected(userId, idempotencyKey, request, "C5_HIGH_RISK_ACTION_REJECTED",
                    OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        if (!StringUtils.hasText(idempotencyKey)) {
            return c5Rejected(userId, idempotencyKey, request, "C5_HIGH_RISK_ACTION_REJECTED",
                    OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus(), OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
        }
        int reasonLength = request != null && StringUtils.hasText(request.reason()) ? request.reason().trim().length() : 0;
        if (reasonLength < 8 || reasonLength > 200 || containsRawJsonOrUrl(request.reason())) {
            return c5Rejected(userId, idempotencyKey, request, "C5_HIGH_RISK_ACTION_REJECTED",
                    OpsErrorCode.VALIDATION_FAILED.httpStatus(), "C5_REASON_LENGTH_OR_FORMAT_INVALID");
        }
        UserAccountView user = userRepository.findById(userId).orElse(null);
        if (user == null) {
            return c5Rejected(userId, idempotencyKey, request, "C5_HIGH_RISK_ACTION_REJECTED", 404, "USER_NOT_FOUND");
        }
        String evidenceError = c5KycEvidenceError(user, request, userId, action, idempotencyKey);
        if (evidenceError != null) {
            return c5Rejected(userId, idempotencyKey, request, "C5_HIGH_RISK_ACTION_REJECTED",
                    OpsErrorCode.VALIDATION_FAILED.httpStatus(), evidenceError);
        }
        return null;
    }

    private String c5KycEvidenceError(
            UserAccountView user, UserSecurityActionRequest request, Long userId,
            String action, String idempotencyKey) {
        if (!"APPROVED".equalsIgnoreCase(user.kycStatus()) || request == null
                || !Boolean.TRUE.equals(request.identityConfirmed())) {
            return "KYC_REVERIFY_REQUIRED";
        }
        String ticket = request.kycVerificationTicket();
        if (!StringUtils.hasText(ticket) || ticket.trim().length() < 3 || ticket.trim().length() > 96
                || containsRawJsonOrUrl(ticket)
                || !ticket.trim().matches("[A-Za-z0-9._:/-]+")) {
            return "KYC_REVERIFY_REQUIRED";
        }
        int rememberDays = boundedConfigInt("auth.session.step_up_days", 7, 1, 30);
        if (!C5_KYC_ACTIONS.contains(action)
                || !userRepository.canUseC5KycReverification(
                        userId, ticket.trim(), action, rememberDays, idempotencyKey.trim())) {
            return "KYC_REVERIFY_REQUIRED";
        }
        return null;
    }

    private boolean consumeC5KycReverification(
            Long userId, String action, String idempotencyKey, UserSecurityActionRequest request) {
        return userRepository.consumeC5KycReverification(
                userId,
                request.kycVerificationTicket().trim(),
                action,
                idempotencyKey.trim(),
                operator(request.operator()));
    }

    private <T> ApiResult<T> requireC5UnlockAuthority(String lockKind) {
        String role = normalizeRole(roleResolver.resolveCode());
        Set<String> allowedRoles = "LONG".equals(lockKind)
                ? Set.of("SUPER_ADMIN", "RISK")
                : Set.of("SUPER_ADMIN", "RISK", "SUPPORT");
        String authority = "LONG".equals(lockKind) ? "user_c5_unlock_long" : "user_c5_unlock_short";
        if (!allowedRoles.contains(role)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "C5_UNLOCK_ROLE_FORBIDDEN_" + lockKind);
        }
        if (A2ReplayContext.isReplaying()) {
            return null;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        boolean allowed = authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
        return allowed ? null : ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "C5_ACTION_FORBIDDEN");
    }

    private <T> ApiResult<T> requireC5Authority(String authority) {
        if (A2ReplayContext.isReplaying()) return null;
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) return null;
        boolean allowed = authentication.getAuthorities().stream()
                .anyMatch(granted -> authority.equals(granted.getAuthority()));
        return allowed ? null : ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "C5_ACTION_FORBIDDEN");
    }

    private Map<String, Object> c5Fingerprint(Long userId, UserSecurityActionRequest request) {
        Map<String, Object> fingerprint = new LinkedHashMap<>();
        fingerprint.put("userId", userId);
        fingerprint.put("reason", request.reason().trim());
        fingerprint.put("kycVerificationTicket", request.kycVerificationTicket().trim());
        fingerprint.put("identityConfirmed", request.identityConfirmed());
        fingerprint.put("lockKind", request.lockKind());
        return fingerprint;
    }

    private Map<String, Object> c5AuditDetail(UserSecurityActionRequest request, String idempotencyKey) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operator", operator(request.operator()));
        detail.put("role", normalizeRole(roleResolver.resolveCode()));
        detail.put("kycVerificationChannel", "K5_INDEPENDENT_REVIEW");
        detail.put("kycVerificationTicket", request.kycVerificationTicket().trim());
        detail.put("kycVerifiedAt", "SERVER_VERIFIED");
        detail.put("kycVerificationResult", "PASSED");
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", idempotencyKey.trim());
        return detail;
    }

    private void c5RequiredAudit(
            String action, String resourceType, String resourceId, Long userId,
            String actor, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(operator(actor))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private <T> ApiResult<T> c5Rejected(
            Long userId, String idempotencyKey, UserSecurityActionRequest request,
            String action, int status, String failureReason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("operator", operator(request == null ? null : request.operator()));
        detail.put("role", normalizeRole(roleResolver.resolveCode()));
        detail.put("kycVerificationChannel", request == null ? null : request.kycVerificationChannel());
        detail.put("kycVerificationTicket", request == null ? null : request.kycVerificationTicket());
        detail.put("kycVerifiedAt", request == null ? null : request.kycVerifiedAt());
        detail.put("lockKind", request == null ? null : request.lockKind());
        detail.put("reason", request == null ? null : request.reason());
        detail.put("idempotencyKey", idempotencyKey);
        detail.put("failureReason", failureReason);
        auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("USER_SECURITY")
                .resourceId(userId == null ? "UNKNOWN" : String.valueOf(userId))
                .bizNo(userId == null ? null : String.valueOf(userId))
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(operator(request == null ? null : request.operator()))
                .result("REJECTED")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
        return ApiResult.fail(status, failureReason);
    }

    private void publishC5Event(
            String eventType, Long userId, String actor, String reason, Map<String, Object> auditDetail) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("targetUserId", userId);
        payload.put("operator", actor);
        payload.put("role", normalizeRole(roleResolver.resolveCode()));
        payload.put("reason", reason);
        if (auditDetail.containsKey("kycVerificationResult")) {
            payload.put("kycVerificationResult", auditDetail.get("kycVerificationResult"));
        }
        if (auditDetail.containsKey("lockKind")) {
            payload.put("lockKind", auditDetail.get("lockKind"));
        }
        if (auditDetail.containsKey("scope")) {
            payload.put("scope", auditDetail.get("scope"));
        }
        payload.put("occurredAt", LocalDateTime.now().toString());
        outboxService.publish("USER_SECURITY", String.valueOf(userId), eventType, payload);
    }

    private String normalizeAccountListKind(String kind) {
        String normalized = requireText(kind, "ACCOUNT_LIST_KIND_REQUIRED").toUpperCase(Locale.ROOT);
        normalized = switch (normalized) {
            case "TRUST", "TRUSTED", "ALLOW", "ALLOWLIST", "WHITE", "WHITELIST" -> "ALLOW";
            case "BAN", "BANNED", "BLOCK", "BLOCKLIST", "BLACK", "BLACKLIST" -> "BLOCK";
            default -> normalized;
        };
        if (!ACCOUNT_LIST_KINDS.contains(normalized)) {
            throw new IllegalArgumentException("ACCOUNT_LIST_KIND_UNSUPPORTED");
        }
        return normalized;
    }

    private LocalDateTime parseOptionalExpiry(String expiresAt) {
        if (!StringUtils.hasText(expiresAt)
                || "LONG_TERM".equalsIgnoreCase(expiresAt.trim())
                || "PERMANENT".equalsIgnoreCase(expiresAt.trim())
                || "长期".equals(expiresAt.trim())) {
            return null;
        }
        String value = expiresAt.trim();
        try {
            LocalDateTime parsed = value.matches("\\d{4}-\\d{2}-\\d{2}")
                    ? LocalDate.parse(value).atTime(23, 59, 59)
                    : LocalDateTime.parse(value);
            if (!parsed.isAfter(LocalDateTime.now())) {
                throw new IllegalArgumentException("ACCOUNT_LIST_EXPIRES_AT_NOT_FUTURE");
            }
            return parsed;
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("ACCOUNT_LIST_EXPIRES_AT_INVALID", ex);
        }
    }

    private <T> ApiResult<T> delegatedDirectExecutionGuard(
            String operation, String resourceType, String resourceId, Long userId) {
        if (A2ReplayContext.isReplaying()) {
            return null;
        }
        if (!Set.of("c2_account_freeze", "c2_account_unfreeze").contains(operation)) {
            return null;
        }
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        Set<String> authorities = authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority())
                .collect(java.util.stream.Collectors.toSet());
        if (!"FINANCE".equalsIgnoreCase(text(roleResolver.resolveCode()))
                || !authorities.contains("platform_a2_proposal_create")
                || authorities.contains("platform_a2_operation_approve")) {
            return null;
        }
        String safeResourceId = resourceId == null ? "" : resourceId;
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("A2_DIRECT_EXECUTION_REJECTED")
                .resourceType(resourceType)
                .resourceId(safeResourceId)
                .bizNo(safeResourceId)
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(operator(String.valueOf(authentication.getName())))
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

    private String normalizeUserStatus(String status) {
        String normalized = requireText(status, "USER_STATUS_REQUIRED").toUpperCase(Locale.ROOT);
        if (!USER_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported C user status");
        }
        return normalized;
    }

    private String normalizeAsset(String asset) {
        String normalized = requireText(asset, "ASSET_REQUIRED").toUpperCase(Locale.ROOT);
        if (normalized.contains("POINT")) {
            throw new IllegalArgumentException("ASSET_UNSUPPORTED");
        }
        if (!ASSETS.contains(normalized)) {
            throw new IllegalArgumentException("ASSET_UNSUPPORTED");
        }
        return normalized;
    }

    private String normalizeOptionalAsset(String asset) {
        return StringUtils.hasText(asset) ? normalizeAsset(asset) : null;
    }

    private String normalizeOptionalAdjustmentStatus(String status) {
        return StringUtils.hasText(status) ? normalizeAdjustmentStatus(status) : null;
    }

    private String normalizeAdjustmentStatus(String status) {
        String normalized = requireText(status, "ADJUSTMENT_STATUS_REQUIRED").toUpperCase(Locale.ROOT);
        if (!ADJUSTMENT_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("ASSET_ADJUSTMENT_STATUS_UNSUPPORTED");
        }
        return normalized;
    }

    private String normalizeDirection(String direction) {
        String normalized = requireText(direction, "DIRECTION_REQUIRED").toUpperCase(Locale.ROOT);
        if (!DIRECTIONS.contains(normalized)) {
            throw new IllegalArgumentException("DIRECTION_UNSUPPORTED");
        }
        return normalized;
    }

    private String adjustmentReasonCode(String referenceType, String referenceId) {
        if (!StringUtils.hasText(referenceType) && !StringUtils.hasText(referenceId)) {
            return "OPS_USER_ADJUSTMENT";
        }
        if (!StringUtils.hasText(referenceType) || !StringUtils.hasText(referenceId)) {
            throw new IllegalArgumentException("ADJUSTMENT_REFERENCE_REQUIRED");
        }
        String type = referenceType.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        String id = referenceId.trim();
        if (!type.matches("[A-Z0-9_]{1,24}") || !id.matches("[A-Za-z0-9._:-]{1,64}")) {
            throw new IllegalArgumentException("ADJUSTMENT_REFERENCE_INVALID");
        }
        return type + ":" + id;
    }

    private BigDecimal positiveAmount(String raw) {
        BigDecimal amount;
        try {
            amount = new BigDecimal(requireText(raw, "AMOUNT_REQUIRED").replace(",", ""));
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("AMOUNT_INVALID", ex);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("AMOUNT_POSITIVE_REQUIRED");
        }
        return amount.stripTrailingZeros();
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

    private boolean coverageBelowRedline() {
        TreasuryCoverageSnapshot coverage = coverageFacade.snapshot();
        return coverage.coverageRatio().compareTo(coverage.redlinePct()) < 0;
    }

    private long countAssetAdjustments(String status) {
        return userRepository.pageAssetAdjustments(new UserAssetAdjustmentQueryRequest(status, null, null, null, 1, 1, null)).getTotal();
    }

    private void ensureAssetAdjustmentSeeds() {
        // Business authority is MySQL business tables; empty tables stay empty until an operator creates data.
    }

    private UserAssetAdjustmentDetail assetAdjustmentDetail(UserAssetAdjustmentView adjustment) {
        UserAccountView account = userRepository.findById(adjustment.userId()).orElse(null);
        List<String> reviewTrail = List.of(
                "创建人:" + text(adjustment.maker()),
                "创建时间:" + text(adjustment.createdAt()),
                "当前状态:" + text(adjustment.statusLabel()),
                "复核人:" + text(adjustment.checker()),
                "复核时间:" + text(adjustment.reviewedAt()),
                "复核理由:" + text(adjustment.reviewReason()));
        List<String> redlines = adjustment.credit()
                ? List.of("入账前校验资金覆盖率红线", "当前仅支持 USDT 与 NEX")
                : List.of("扣减需要确认理由并保留完整审计记录");
        return new UserAssetAdjustmentDetail(
                adjustment,
                account,
                coverageFacade.snapshot(),
                reviewTrail,
                redlines,
                List.of("wallet asset adjustments", "user accounts", "required audit", "treasury coverage"));
    }

    private UserKycLedgerRow kycLedgerRow(UserKycRecord record, List<UserKycStatusHistoryView> historyRows) {
        String backendStatus = normalizeKycStatus(record.status());
        String displayStatus = displayKycStatus(backendStatus);
        String walletAddress = record.pairedAddress();
        boolean walletPaired = StringUtils.hasText(walletAddress);
        String pairedAddress = walletPaired ? maskWalletAddress(walletAddress) : "未绑定";
        String network = StringUtils.hasText(record.network()) ? record.network() : "—";
        String pairedAt = walletPaired ? formatKycPairedAt(record.pairedAt()) : "—";
        String triggerSource = displayKycSource(record.triggerSource());
        List<UserKycKeyValue> info = List.of(
                new UserKycKeyValue("当前状态", labelKycStatus(backendStatus)),
                new UserKycKeyValue("账户状态", labelKycAccountStatus(record.accountStatus())),
                new UserKycKeyValue("国家/地区", text(record.countryCode())),
                new UserKycKeyValue("用户等级", text(record.userLevel())),
                new UserKycKeyValue("钱包已配对", walletPaired ? "是" : "否"),
                new UserKycKeyValue("配对地址", pairedAddress),
                new UserKycKeyValue("网络", network),
                new UserKycKeyValue("台账来源", "KYC 权威台账"));
        List<String> history = historyRows == null ? List.of() : historyRows.stream()
                .map(this::displayKycHistory)
                .toList();
        return new UserKycLedgerRow(
                record.userId(),
                record.userNo(),
                record.nickname(),
                record.phoneMasked(),
                record.countryCode(),
                displayStatus,
                backendStatus,
                labelKycStatus(backendStatus),
                toneKycStatus(backendStatus),
                pairedAddress,
                network,
                pairedAt,
                triggerSource,
                info,
                history);
    }

    private String maskWalletAddress(String walletAddress) {
        String trimmed = walletAddress == null ? "" : walletAddress.trim();
        if (trimmed.length() <= 8) {
            return "****";
        }
        return trimmed.substring(0, 4) + "****" + trimmed.substring(trimmed.length() - 2);
    }

    private String formatKycPairedAt(LocalDateTime pairedAt) {
        return pairedAt == null ? "—" : pairedAt.format(KYC_PAIRED_DATE_FORMATTER);
    }

    private String displayKycSource(String source) {
        if (!StringUtils.hasText(source)) return "—";
        return switch (source.trim().toUpperCase(Locale.ROOT)) {
            case "C4_MANUAL_VERIFY" -> "C4 人工核验";
            case "C4_MANUAL_REVOKE" -> "C4 人工撤销";
            case "K5_REVIEW_DECISION" -> "K5 复审裁决";
            case "USER_SUBMITTED" -> "用户主动验证";
            case "D2_FIRST_WITHDRAWAL" -> "D2 首次提现";
            case "G2_EXCHANGE_THRESHOLD" -> "G2 累计兑换过线";
            case "LEGACY_MIGRATION" -> "历史状态迁入";
            default -> source.trim();
        };
    }

    private String labelKycAccountStatus(String status) {
        if (!StringUtils.hasText(status)) return "状态待核对";
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "ACTIVE" -> "正常";
            case "FROZEN" -> "已冻结";
            case "BANNED" -> "已封禁";
            case "RESTRICTED" -> "受限";
            default -> "状态待核对";
        };
    }

    private String displayKycHistory(UserKycStatusHistoryView row) {
        String at = row.createdAt() == null ? "时间未知" : row.createdAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String transition = StringUtils.hasText(row.beforeStatus())
                ? labelKycStatus(normalizeKycStatus(row.beforeStatus())) + " → " + labelKycStatus(normalizeKycStatus(row.afterStatus()))
                : "初始状态 · " + labelKycStatus(normalizeKycStatus(row.afterStatus()));
        String ticket = StringUtils.hasText(row.ticketId()) ? " · 工单 " + row.ticketId() : "";
        String reasonCode = switch (text(row.reasonCode()).toUpperCase(Locale.ROOT)) {
            case "LEGACY_MIGRATION" -> "历史状态迁入";
            case "MANUAL_VERIFICATION" -> "人工核验";
            case "COMPLIANCE_CORRECTION" -> "合规纠正";
            case "USER_APPEAL" -> "用户申诉";
            case "RISK_ESCALATION" -> "风险升级";
            case "K5_DECISION" -> "K5 复审裁决";
            case "OTHER" -> "其他原因";
            default -> "已记录原因";
        };
        String reason = "LEGACY_MIGRATION".equalsIgnoreCase(text(row.reasonCode()))
                ? "历史状态迁入，仅保留可核对的原始状态，不补造审核事实"
                : text(row.reason());
        return at + " · " + transition + " · " + displayKycSource(row.source())
                + " · " + reasonCode + " · " + reason + ticket;
    }

    private String requireKycReasonCode(String value) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("C4_REASON_CODE_REQUIRED");
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("MANUAL_VERIFICATION", "COMPLIANCE_CORRECTION", "USER_APPEAL", "RISK_ESCALATION", "K5_DECISION", "OTHER").contains(normalized)) {
            throw new IllegalArgumentException("C4_REASON_CODE_UNSUPPORTED");
        }
        return normalized;
    }

    private String requireKycEvidence(String value) {
        if (!StringUtils.hasText(value)) throw new IllegalArgumentException("C4_EVIDENCE_REQUIRED");
        String normalized = value.trim();
        if (normalized.length() < 3 || normalized.length() > 255 || containsRawJsonOrUrl(normalized)) {
            throw new IllegalArgumentException("C4_EVIDENCE_INVALID");
        }
        return normalized;
    }

    private void requireKycReason(String value) {
        int length = StringUtils.hasText(value) ? value.trim().length() : 0;
        if (length < 8 || length > 200) throw new IllegalArgumentException("C4_REASON_LENGTH_INVALID");
        if (containsRawJsonOrUrl(value)) throw new IllegalArgumentException("C4_REASON_INVALID");
    }

    private List<UserKycRecord> allKycRecords() {
        List<UserKycRecord> records = new ArrayList<>();
        int page = 1;
        while (true) {
            PageResult<UserKycRecord> slice = userRepository.pageKycRecords(null, page, 200);
            records.addAll(slice.getRecords());
            if (records.size() >= slice.getTotal() || slice.getRecords().isEmpty()) break;
            page++;
        }
        return records;
    }

    private String kycCsv(List<UserKycRecord> records) {
        StringBuilder csv = new StringBuilder("user_no,status,network,paired_at,trigger_source\n");
        for (UserKycRecord row : records) {
            csv.append(kycCsvCell(row.userNo())).append(',')
                    .append(kycCsvCell(row.status())).append(',')
                    .append(kycCsvCell(row.network())).append(',')
                    .append(kycCsvCell(row.pairedAt())).append(',')
                    .append(kycCsvCell(row.triggerSource())).append('\n');
        }
        return csv.toString();
    }

    private String kycCsvCell(Object value) {
        String raw = value == null ? "" : String.valueOf(value);
        if (raw.startsWith("=") || raw.startsWith("+") || raw.startsWith("-") || raw.startsWith("@")) raw = "'" + raw;
        return '"' + raw.replace("\"", "\"\"") + '"';
    }

    private Map<String, Object> kycExportMap(KycRegulatoryExportJob job) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("jobNo", job.jobNo());
        value.put("status", job.status());
        value.put("scope", job.scope());
        value.put("rowCount", job.rowCount());
        value.put("masked", job.masked());
        value.put("downloadPath", job.downloadPath());
        value.put("createdAt", job.createdAt());
        return value;
    }

    private String normalizeOptionalKycStatus(String status) {
        return StringUtils.hasText(status) ? normalizeKycStatus(status) : null;
    }

    private String normalizeKycStatus(String status) {
        String normalized = StringUtils.hasText(status) ? status.trim().toUpperCase(Locale.ROOT) : "PENDING";
        normalized = switch (normalized) {
            case "VERIFIED", "APPROVE", "APPROVED", "PASSED", "PASS" -> "APPROVED";
            case "REVIEW", "IN_REVIEW", "PENDING", "WAITING" -> "PENDING";
            case "NONE", "UNVERIFIED", "NOT_VERIFIED", "NO" -> "NONE";
            case "REJECT", "REJECTED", "DENIED" -> "REJECTED";
            default -> normalized;
        };
        if (!KYC_STATUSES.contains(normalized)) {
            throw new IllegalArgumentException("KYC_STATUS_UNSUPPORTED");
        }
        return normalized;
    }

    private String displayKycStatus(String backendStatus) {
        return switch (backendStatus) {
            case "APPROVED" -> "verified";
            case "PENDING" -> "review";
            case "REJECTED" -> "rejected";
            default -> "none";
        };
    }

    private String labelKycStatus(String backendStatus) {
        return switch (backendStatus) {
            case "APPROVED" -> "已验证";
            case "PENDING" -> "复审中";
            case "REJECTED" -> "已拒绝";
            default -> "未验证";
        };
    }

    private String toneKycStatus(String backendStatus) {
        return switch (backendStatus) {
            case "APPROVED" -> "ok";
            case "PENDING" -> "warn";
            case "REJECTED" -> "bad";
            default -> "dim";
        };
    }

    private String kycNetworkWhitelist() {
        return configFacade.activeValue(KYC_NETWORK_WHITELIST_KEY)
                .filter(StringUtils::hasText)
                .orElse(DEFAULT_KYC_NETWORK_WHITELIST);
    }

    private String normalizeKycNetworkWhitelist(String value) {
        String raw = requireText(value, "KYC_NETWORK_WHITELIST_REQUIRED");
        if (containsRawJsonOrUrl(raw)) {
            throw new IllegalArgumentException("KYC_NETWORK_WHITELIST_REJECTED");
        }
        List<String> networks = Pattern.compile("[,/\\s]+")
                .splitAsStream(raw.replace("|", " "))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .map(token -> token.toUpperCase(Locale.ROOT))
                .distinct()
                .toList();
        if (networks.isEmpty()) {
            throw new IllegalArgumentException("KYC_NETWORK_WHITELIST_REQUIRED");
        }
        if (!ALLOWED_KYC_NETWORKS.containsAll(networks)) {
            throw new IllegalArgumentException("KYC_NETWORK_UNSUPPORTED");
        }
        return String.join(" / ", networks);
    }

    private boolean containsRawJsonOrUrl(String raw) {
        String value = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT);
        return value.startsWith("{")
                || value.startsWith("[")
                || value.contains("http://")
                || value.contains("https://")
                || value.contains("://");
    }

    private String text(Object value) {
        return value == null || !StringUtils.hasText(String.valueOf(value)) ? "—" : String.valueOf(value);
    }

    private UserSecurityStatusView loadSecurityStatus(Long userId) {
        return userRepository.securityStatus(userId)
                .map(raw -> {
                    int lockThreshold = configInt("auth.risk.login_lock_threshold", 5);
                    int lockDurationMinutes = configInt("auth.risk.lock_duration_minutes", 15);
                    return new UserSecurityStatusView(
                            raw.userId(),
                            raw.twoFactorEnabled(),
                            raw.loginFailCount(),
                            raw.locked(),
                            raw.passwordResetRequired(),
                            lockThreshold,
                            lockDurationMinutes);
                })
                .orElse(null);
    }

    private UserCredentialParamView credentialParamView(CredentialParamDefinition definition) {
        int value = boundedConfigInt(definition.configKey(), definition.fallback(), definition.min(), definition.max());
        return credentialParamView(definition, value);
    }

    private UserCredentialParamView credentialParamView(CredentialParamDefinition definition, int value) {
        return new UserCredentialParamView(
                definition.key(),
                definition.name(),
                credentialDisplayValue(value, definition.unit()),
                definition.unit(),
                definition.min(),
                definition.max(),
                definition.readOnly(),
                definition.note(),
                definition.configKey());
    }

    private UserRegistrationRiskParamView registrationRiskParamView(
            RegistrationRiskParamDefinition definition,
            long version) {
        int[] values = new int[] {
                boundedConfigInt(definition.configKey(), definition.fallback(), definition.min(), definition.max()),
                definition.composite()
                        ? boundedConfigInt(
                                definition.secondaryConfigKey(),
                                definition.secondaryFallback(),
                                definition.secondaryMin(),
                                definition.secondaryMax())
                        : 0
        };
        return registrationRiskParamView(definition, values, version);
    }

    private UserRegistrationRiskParamView registrationRiskParamView(
            RegistrationRiskParamDefinition definition,
            int[] values,
            long version) {
        return new UserRegistrationRiskParamView(
                definition.group(),
                definition.key(),
                definition.name(),
                definition.sub(),
                registrationRiskDisplayValue(definition, values),
                definition.composite() ? definition.unit() + "/" + definition.secondaryUnit() : definition.unit(),
                definition.min(),
                definition.max(),
                definition.secondaryMin(),
                definition.secondaryMax(),
                definition.secondaryUnit(),
                version,
                "otp".equals(definition.group()),
                definition.note(),
                definition.composite()
                        ? definition.configKey() + "," + definition.secondaryConfigKey()
                        : definition.configKey());
    }

    private List<UserRegistrationRiskK1GuardView> registrationRiskK1Guards() {
        return List.of(
                new UserRegistrationRiskK1GuardView("同 IP 24h 注册上限", "maxSignupPerIp24h", K1_REJECT_CODE, K1_PATH),
                new UserRegistrationRiskK1GuardView("同设备绑定账户上限", "maxAccountsPerDevice", K1_REJECT_CODE, K1_PATH),
                new UserRegistrationRiskK1GuardView("同支付工具绑定上限", "maxAccountsPerPaymentInstrument", K1_REJECT_CODE, K1_PATH));
    }

    private ApiResult<UserRegistrationRiskParamView> updateCaptchaOffWindow(
            String idempotencyKey,
            UserRegistrationRiskParamUpdateRequest request) {
        long currentVersion = lockC6ConfigVersion();
        if (currentVersion != request.expectedVersion()) {
            return ApiResult.fail(409, "C6_CONFIG_VERSION_CONFLICT");
        }
        configFacade.activeValueForUpdate(CAPTCHA_OFF_WINDOW_KEY);
        String value = request.value() == null ? "" : request.value().trim();
        String persistedDeadline = "";
        if (StringUtils.hasText(value)) {
            try {
                persistedDeadline = RegistrationRiskCaptchaWindow.deadlineForSelection(value, clock).toString();
            } catch (IllegalArgumentException ex) {
                return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
            }
        }
        configFacade.upsertAdminValue(CAPTCHA_OFF_WINDOW_KEY, persistedDeadline, "STRING", AUTH_CONFIG_GROUP, request.reason().trim());
        long nextVersion = incrementC6ConfigVersion(currentVersion, request.reason());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("paramKey", "captchaOff");
        detail.put("configKey", CAPTCHA_OFF_WINDOW_KEY);
        detail.put("restoreSelection", value);
        detail.put("restoreAt", persistedDeadline);
        detail.put("beforeVersion", currentVersion);
        detail.put("afterVersion", nextVersion);
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", idempotencyKey.trim());
        c6RequiredAudit(
                StringUtils.hasText(persistedDeadline) ? "C6_CAPTCHA_TEMPORARILY_DISABLED" : "C6_CAPTCHA_RESTORED",
                "captchaOff", request.operator(), detail);
        return ApiResult.ok(new UserRegistrationRiskParamView(
                "captcha",
                "captchaOff",
                "全局开关",
                "仅限服务商故障等紧急维护时关，关闭必须填恢复时限",
                persistedDeadline,
                "恢复时限",
                0,
                0,
                0,
                0,
                "",
                nextVersion,
                false,
                "空值表示开启，非空为服务端绝对恢复时刻；到期自动恢复",
                CAPTCHA_OFF_WINDOW_KEY));
    }

    private ApiResult<UserRegistrationRiskParamView> updateRegistrationRiskParamLocked(
            RegistrationRiskParamDefinition definition,
            String idempotencyKey,
            UserRegistrationRiskParamUpdateRequest request) {
        long currentVersion = lockC6ConfigVersion();
        if (currentVersion != request.expectedVersion()) {
            return ApiResult.fail(409, "C6_CONFIG_VERSION_CONFLICT");
        }
        configFacade.activeValueForUpdate(definition.configKey());
        if (definition.composite()) configFacade.activeValueForUpdate(definition.secondaryConfigKey());
        configFacade.activeValueForUpdate("auth.risk.login_lock_threshold");
        configFacade.activeValueForUpdate("auth.risk.login_long_lock_threshold");

        int[] values;
        try {
            values = normalizeRegistrationRiskParamValues(definition, request.value());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        int shortThreshold = "lockShort".equals(definition.key())
                ? values[0]
                : boundedConfigInt("auth.risk.login_lock_threshold", 5, 3, 10);
        int longThreshold = "lockLong".equals(definition.key())
                ? values[0]
                : boundedConfigInt("auth.risk.login_long_lock_threshold", 10, 5, 20);
        if (longThreshold <= shortThreshold) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(),
                    "C6_LONG_THRESHOLD_MUST_EXCEED_SHORT_THRESHOLD");
        }

        configFacade.upsertAdminValue(
                definition.configKey(), String.valueOf(values[0]), "NUMBER", AUTH_CONFIG_GROUP, request.reason().trim());
        if (definition.composite()) {
            configFacade.upsertAdminValue(
                    definition.secondaryConfigKey(), String.valueOf(values[1]), "NUMBER", AUTH_CONFIG_GROUP, request.reason().trim());
        }
        long nextVersion = incrementC6ConfigVersion(currentVersion, request.reason());
        UserRegistrationRiskParamView updated = registrationRiskParamView(definition, values, nextVersion);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("paramKey", definition.key());
        detail.put("configKey", definition.configKey());
        detail.put("value", values[0]);
        if (definition.composite()) {
            detail.put("secondaryConfigKey", definition.secondaryConfigKey());
            detail.put("secondaryValue", values[1]);
        }
        detail.put("beforeVersion", currentVersion);
        detail.put("afterVersion", nextVersion);
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", idempotencyKey.trim());
        c6RequiredAudit("C6_REGISTRATION_RISK_PARAM_UPDATED", definition.key(), request.operator(), detail);
        return ApiResult.ok(updated);
    }

    private long lockC6ConfigVersion() {
        return parseLong(configFacade.activeValueForUpdate(C6_CONFIG_VERSION_KEY).orElse("0"), 0L);
    }

    private long incrementC6ConfigVersion(long currentVersion, String reason) {
        long nextVersion = Math.addExact(currentVersion, 1L);
        configFacade.upsertAdminValue(
                C6_CONFIG_VERSION_KEY, String.valueOf(nextVersion), "NUMBER", AUTH_CONFIG_GROUP, reason.trim());
        return nextVersion;
    }

    private long parseLong(String value, long fallback) {
        try {
            return Long.parseLong(value == null ? "" : value.trim());
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private void c6RequiredAudit(String action, String resourceId, String actor, Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType("AUTH_CONFIG")
                .resourceId(resourceId)
                .bizNo(resourceId)
                .actorType("ADMIN")
                .actorUsername(operator(actor))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private CredentialParamDefinition credentialParamDefinition(String paramKey) {
        if (!StringUtils.hasText(paramKey)) {
            return null;
        }
        String normalized = paramKey.trim();
        return CREDENTIAL_PARAM_DEFINITIONS.stream()
                .filter(definition -> definition.key().equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private RegistrationRiskParamDefinition registrationRiskParamDefinition(String paramKey) {
        if (!StringUtils.hasText(paramKey)) {
            return null;
        }
        String normalized = paramKey.trim();
        return REGISTRATION_RISK_PARAM_DEFINITIONS.stream()
                .filter(definition -> definition.key().equals(normalized))
                .findFirst()
                .orElse(null);
    }

    private int normalizeCredentialParamValue(CredentialParamDefinition definition, String rawValue) {
        String raw = requireText(rawValue, "CREDENTIAL_PARAM_VALUE_REQUIRED");
        Matcher matcher = NUMBER_PATTERN.matcher(raw);
        if (!matcher.find()) {
            throw new IllegalArgumentException("CREDENTIAL_PARAM_VALUE_INVALID");
        }
        int value = Integer.parseInt(matcher.group());
        if (value < definition.min() || value > definition.max()) {
            throw new IllegalArgumentException("CREDENTIAL_PARAM_VALUE_OUT_OF_RANGE");
        }
        return value;
    }

    private int[] normalizeRegistrationRiskParamValues(RegistrationRiskParamDefinition definition, String rawValue) {
        String raw = requireText(rawValue, "REGISTRATION_RISK_PARAM_VALUE_REQUIRED");
        Pattern valuePattern = definition.composite()
                ? Pattern.compile("^(\\d+)\\s*次\\s*/\\s*(\\d+)\\s*" + Pattern.quote(definition.secondaryUnit()) + "\\s*$")
                : Pattern.compile("^(\\d+)\\s*" + Pattern.quote(definition.unit()) + "\\s*$");
        Matcher matcher = valuePattern.matcher(raw);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("REGISTRATION_RISK_PARAM_VALUE_INVALID");
        }
        int value = Integer.parseInt(matcher.group(1));
        if (value < definition.min() || value > definition.max()) {
            throw new IllegalArgumentException("REGISTRATION_RISK_PARAM_VALUE_OUT_OF_RANGE");
        }
        if (!definition.composite()) {
            return new int[] {value, 0};
        }
        int secondaryValue = Integer.parseInt(matcher.group(2));
        if (secondaryValue < definition.secondaryMin() || secondaryValue > definition.secondaryMax()) {
            throw new IllegalArgumentException("REGISTRATION_RISK_PARAM_VALUE_OUT_OF_RANGE");
        }
        return new int[] {value, secondaryValue};
    }

    private int boundedConfigInt(String configKey, int fallback, int min, int max) {
        int value = configInt(configKey, fallback);
        return value < min || value > max ? fallback : value;
    }

    private int sessionIdleDays() {
        return boundedConfigInt("auth.session.idle_ttl_days", 30, 7, 90);
    }

    private int configInt(String configKey, int fallback) {
        try {
            return configFacade.activeValue(configKey)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .orElse(fallback);
        } catch (RuntimeException ex) {
            return fallback;
        }
    }

    private long configLong(String configKey, long fallback) {
        try {
            return configFacade.activeValue(configKey)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(Long::parseLong)
                    .orElse(fallback);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private String credentialDisplayValue(int value, String unit) {
        return value + " " + unit;
    }

    private String registrationRiskDisplayValue(RegistrationRiskParamDefinition definition, int[] values) {
        if (!definition.composite()) {
            return values[0] + " " + definition.unit();
        }
        return values[0] + " " + definition.unit() + " / " + values[1] + " " + definition.secondaryUnit();
    }

    private String requireText(String value, String message) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException(message);
        }
        return value.trim();
    }

    private String operator(String operator) {
        String resolved = AdminActorResolver.resolve(StringUtils.hasText(operator) ? operator.trim() : "system");
        return StringUtils.hasText(resolved) ? resolved : "system";
    }

    private void audit(String action, String resourceType, String resourceId, Long userId, String operator, Map<String, Object> detail) {
        auditLogService.record(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private void requiredAudit(
            String action,
            String resourceType,
            String resourceId,
            Long userId,
            Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .userId(userId)
                .actorType("ADMIN")
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private void c2RequiredAudit(
            String action,
            String resourceType,
            String resourceId,
            Long userId,
            String operator,
            Map<String, Object> detail) {
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(operator(operator))
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private void c4RejectedAudit(
            Long userId,
            String idempotencyKey,
            UserKycStatusUpdateRequest request,
            String currentState,
            String nextState,
            String failureReason) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("userId", userId);
        detail.put("expectedState", request == null ? null : request.expectedState());
        detail.put("currentState", currentState);
        detail.put("nextState", nextState);
        detail.put("reasonCode", request == null ? null : request.reasonCode());
        detail.put("evidenceRef", request == null ? null : request.evidenceRef());
        detail.put("idempotencyKey", idempotencyKey);
        detail.put("failureReason", failureReason);
        auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action("C4_KYC_STATUS_CHANGE_REJECTED")
                .resourceType("USER_KYC")
                .resourceId(userId == null ? "UNKNOWN" : String.valueOf(userId))
                .bizNo(userId == null ? null : String.valueOf(userId))
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(operator(request == null ? null : request.operator()))
                .result("REJECTED")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private void c4CommandFailureAudit(
            String action,
            String resourceType,
            String resourceId,
            Long userId,
            String actor,
            String idempotencyKey,
            String reasonCode,
            String evidenceRef,
            String reason,
            String failureReason,
            String result) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("userId", userId);
        detail.put("reasonCode", reasonCode);
        detail.put("evidenceRef", evidenceRef);
        detail.put("reason", reason);
        detail.put("idempotencyKey", idempotencyKey);
        detail.put("failureReason", failureReason);
        auditLogService.recordRequiredInNewTransaction(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(StringUtils.hasText(resourceId) ? resourceId : "UNKNOWN")
                .bizNo(StringUtils.hasText(resourceId) ? resourceId : null)
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(operator(actor))
                .result("FAILED".equals(result) ? "FAILED" : "REJECTED")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    /**
     * Impersonation page requests run under the target user's read-only JWT. The originating
     * administrator is therefore taken from the already-validated server-side session row,
     * rather than resolving the current target-user security context again.
     */
    private void c2TrustedSessionActorAudit(
            String action,
            String resourceType,
            String resourceId,
            Long userId,
            String trustedSessionActor,
            Map<String, Object> detail) {
        String actor = StringUtils.hasText(trustedSessionActor) ? trustedSessionActor.trim() : "system";
        auditLogService.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action(action)
                .resourceType(resourceType)
                .resourceId(resourceId)
                .bizNo(resourceId)
                .userId(userId)
                .actorType("ADMIN")
                .actorUsername(actor)
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(detail)
                .build());
    }

    private record CredentialParamDefinition(
            String key,
            String name,
            String configKey,
            String unit,
            int min,
            int max,
            int fallback,
            boolean readOnly,
            String note) {
    }

    private record RegistrationRiskParamDefinition(
            String group,
            String key,
            String name,
            String sub,
            String configKey,
            String unit,
            int min,
            int max,
            int fallback,
            String secondaryConfigKey,
            String secondaryUnit,
            int secondaryMin,
            int secondaryMax,
            int secondaryFallback,
            String note) {
        private boolean composite() {
            return StringUtils.hasText(secondaryConfigKey);
        }
    }

    @Override
    public String domain() {
        return "C";
    }

    @Override
    public ApiResult<?> replay(AuditReplayCommand cmd, AuditReplayContext ctx) {
        Map<String, Object> p = cmd.params() == null ? Map.of() : cmd.params();
        String operator = ctx.operator();
        String reason = ctx.reason();
        String idem = ctx.idempotencyKey();
        switch (cmd.op()) {
            case "c4_kyc_status_change" -> {
                UserKycStatusUpdateRequest req = new UserKycStatusUpdateRequest(str(p, "status"), reason, operator);
                return updateKycStatus(longVal(p, "userId"), idem, req);
            }
            case "c5_2fa_disable" -> {
                UserSecurityActionRequest req = new UserSecurityActionRequest(reason, operator);
                return disableTwoFactor(longVal(p, "userId"), idem, req);
            }
            case "c5_password_reset" -> {
                UserSecurityActionRequest req = new UserSecurityActionRequest(reason, operator);
                return requestPasswordReset(longVal(p, "userId"), idem, req);
            }
            case "c5_user_unlock" -> {
                UserSecurityActionRequest req = new UserSecurityActionRequest(reason, operator);
                return unlockSecurity(longVal(p, "userId"), idem, req);
            }
            case "c2_account_freeze", "c2_account_unfreeze" -> {
                String status = "c2_account_freeze".equals(cmd.op()) ? "FROZEN" : "ACTIVE";
                UserStatusUpdateRequest req = new UserStatusUpdateRequest(status, str(p, "reasonCode"), reason, operator);
                return updateStatus(longVal(p, "userId"), idem, req);
            }
            case "c2_session_revoke_all" -> {
                UserSessionRevokeAllRequest req = new UserSessionRevokeAllRequest(reason, operator);
                return revokeUserSessions(longVal(p, "userId"), idem, req);
            }
            case "c5_session_revoke_one" -> {
                UserSessionRevokeRequest req = new UserSessionRevokeRequest(reason, operator);
                return revokeSession(str(p, "refreshTokenId"), idem, req);
            }
            case "c2_impersonate_terminate" -> {
                UserImpersonationTerminateRequest req = new UserImpersonationTerminateRequest(reason, operator);
                return terminateImpersonation(str(p, "sessionNo"), idem, req);
            }
            case "c2_impersonate_start" -> {
                UserImpersonationRequest req = new UserImpersonationRequest(
                        intVal(p, "ttlMinutes"), str(p, "reasonCode"), reason, operator);
                return startImpersonation(longVal(p, "userId"), idem, req);
            }
            case "c3_adjust_approve" -> {
                UserAssetAdjustmentReviewRequest req = new UserAssetAdjustmentReviewRequest(reason, operator);
                return approveAssetAdjustment(str(p, "adjustmentNo"), idem, req);
            }
            case "c3_adjust_reject" -> {
                UserAssetAdjustmentReviewRequest req = new UserAssetAdjustmentReviewRequest(reason, operator);
                return rejectAssetAdjustment(str(p, "adjustmentNo"), idem, req);
            }
            case "c3_adjust_create" -> {
                UserAssetAdjustmentRequest req = new UserAssetAdjustmentRequest(
                        str(p, "asset"),
                        str(p, "direction"),
                        str(p, "amount"),
                        reason, operator,
                        str(p, "referenceType"),
                        str(p, "referenceId"));
                return createAssetAdjustment(longVal(p, "userId"), idem, req);
            }
            case "c2_blocklist_upsert" -> {
                UserAccountListUpsertRequest req = new UserAccountListUpsertRequest(
                        longVal(p, "userId"),
                        str(p, "kind"),
                        reason, operator,
                        str(p, "expiresAt"));
                return upsertAccountList(idem, req);
            }
            case "c2_blocklist_remove" -> {
                UserAccountListRemoveRequest req = new UserAccountListRemoveRequest(reason, operator);
                return removeAccountList(longVal(p, "userId"), idem, req);
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

    /** 从 replay params 取 Long,null 安全(支持 Number 与数字字符串)。 */
    private static Long longVal(Map<String, Object> params, String key) {
        Object v = params.get(key);
        if (v == null) return null;
        if (v instanceof Number n) return n.longValue();
        try {
            return Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException ex) {
            return null;
        }
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

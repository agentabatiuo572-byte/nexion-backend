package ffdd.opsconsole.user.application;


import lombok.RequiredArgsConstructor;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.finance.facade.FinanceWithdrawalControlFacade;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.facade.RiskUserStateFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import ffdd.opsconsole.user.domain.UserAccountActionOverview;
import ffdd.opsconsole.user.domain.UserAccountListEntryView;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentDetail;
import ffdd.opsconsole.user.domain.UserAssetAdjustmentView;
import ffdd.opsconsole.user.domain.UserCredentialParamView;
import ffdd.opsconsole.user.domain.UserImpersonationSessionView;
import ffdd.opsconsole.user.domain.UserKycKeyValue;
import ffdd.opsconsole.user.domain.UserKycLedgerRow;
import ffdd.opsconsole.user.domain.UserKycOverview;
import ffdd.opsconsole.user.domain.UserKycStats;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserProfileExportFile;
import ffdd.opsconsole.user.domain.UserRegistrationRiskK1GuardView;
import ffdd.opsconsole.user.domain.UserRegistrationRiskOverview;
import ffdd.opsconsole.user.domain.UserRegistrationRiskParamView;
import ffdd.opsconsole.user.domain.UserRegistrationRiskStats;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@ApplicationService
@RequiredArgsConstructor
public class OpsUserService {
    private static final Set<String> USER_STATUSES = Set.of("ACTIVE", "FROZEN", "BANNED", "RESTRICTED");
    private static final Set<String> ASSETS = Set.of("USDT", "NEX");
    private static final Set<String> DIRECTIONS = Set.of("CREDIT", "DEBIT");
    private static final Set<String> ADJUSTMENT_STATUSES = Set.of("PENDING", "PENDING_REVIEW", "APPROVED", "REJECTED", "SUSPENDED");
    private static final Set<String> REVIEWABLE_ADJUSTMENT_STATUSES = Set.of("PENDING", "PENDING_REVIEW", "SUSPENDED");
    private static final Set<String> KYC_STATUSES = Set.of("APPROVED", "PENDING", "NONE", "REJECTED");
    private static final Set<String> ACCOUNT_LIST_KINDS = Set.of("ALLOW", "BLOCK");
    private static final Set<String> ALLOWED_KYC_NETWORKS = Set.of("TRC20", "ERC20", "BTC", "ETH", "BSC", "SOL", "POLYGON");
    private static final String AUTH_CONFIG_GROUP = "auth";
    private static final String KYC_CONFIG_GROUP = "kyc";
    private static final String KYC_NETWORK_WHITELIST_KEY = "kyc.network_whitelist";
    private static final String DEFAULT_KYC_NETWORK_WHITELIST = "TRC20 / ERC20 / BTC / ETH";
    private static final String CAPTCHA_OFF_WINDOW_KEY = "auth.risk.captcha_off_window";
    private static final String K1_REJECT_CODE = "MULTI_ACCOUNT_PARAM_BELONGS_TO_K1";
    private static final String K1_PATH = "/risk/multi-account";
    private static final List<String> ACCOUNT_ACTION_SEED_LOOKUP_KEYS = List.of(
            "usr_8807", "usr_6201", "usr_2231", "usr_55B1",
            "usr_31E8", "usr_4410", "usr_0099", "usr_90F0");
    private static final List<String> KYC_LEDGER_SEED_LOOKUP_KEYS = List.of(
            "usr_77D4", "usr_31E8", "usr_2231", "usr_55B1", "usr_90F0");
    private static final List<String> ASSET_ADJUSTMENT_SEED_NOS = List.of(
            "ADJ-7741", "ADJ-3188", "ADJ-0029", "ADJ-1182",
            "ADJ-1183", "ADJ-1179", "ADJ-1175");
    private static final String DEFAULT_SECURITY_LOOKUP_KEY = "usr_2231";
    private static final List<String> SECURITY_SESSION_SEED_LOOKUP_KEYS = List.of("usr_2231", "usr_8807", "usr_3315");
    private static final String RESET_REQUIRED_PREFIX = "RESET_REQUIRED$";
    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\d+");
    private static final DateTimeFormatter KYC_PAIRED_DATE_FORMATTER = DateTimeFormatter.ofPattern("M/d");
    private static final Set<String> K1_REGISTRATION_RISK_PARAM_KEYS = Set.of(
            "maxSignupPerIp24h",
            "maxAccountsPerDevice",
            "maxAccountsPerPaymentInstrument");
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
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    public ApiResult<Map<String, Object>> overview() {
        Map<String, Object> response = new LinkedHashMap<>(userRepository.overview());
        response.put("domain", "C");
        response.put("capabilities", List.of("UserProfile", "KycReview", "AccountSecurity", "ManualAssetAdjustment"));
        response.put("sunsetCompatibility", List.of("Premium history is read-only", "NEX v2 maturity is historical", "Points adjustments are rejected"));
        response.put("sources", List.of("nx_user", "nx_user_session", "nx_wallet_asset_adjustment"));
        return ApiResult.ok(response);
    }

    public ApiResult<List<UserAccountView>> profiles(UserQueryRequest request) {
        ensureC1ProfileSeeds();
        int limit = normalizeLimit(request == null ? null : request.limit(), 50, 100);
        return ApiResult.ok(userRepository.search(
                request == null ? null : request.keyword(),
                request == null ? null : request.status(),
                request == null ? null : request.kycStatus(),
                limit));
    }

    public ApiResult<PageResult<UserAccountView>> profilePage(UserQueryRequest request) {
        ensureC1ProfileSeeds();
        return ApiResult.ok(userRepository.pageProfiles(request));
    }

    public UserProfileExportFile exportProfileExcel(String idempotencyKey, UserProfileExportRequest request) {
        ensureC1ProfileSeeds();
        String normalizedKey = requireText(idempotencyKey, "IDEMPOTENCY_KEY_REQUIRED");
        String reason = requireText(request == null ? null : request.reason(), "REASON_REQUIRED");
        String operator = operator(request == null ? null : request.operator());
        int pageSize = 100;
        UserQueryRequest firstQuery = new UserQueryRequest(
                request == null ? null : request.keyword(),
                request == null ? null : request.status(),
                request == null ? null : request.kycStatus(),
                request == null ? null : request.riskMin(),
                1,
                pageSize,
                null);
        PageResult<UserAccountView> first = userRepository.pageProfiles(firstQuery);
        List<UserAccountView> rows = new ArrayList<>();
        if (first.getRecords() != null) {
            rows.addAll(first.getRecords());
        }
        long total = Math.max(first.getTotal(), rows.size());
        int totalPages = (int) Math.min(50, Math.max(1, (total + pageSize - 1) / pageSize));
        for (int pageNum = 2; pageNum <= totalPages; pageNum += 1) {
            PageResult<UserAccountView> page = userRepository.pageProfiles(new UserQueryRequest(
                    request == null ? null : request.keyword(),
                    request == null ? null : request.status(),
                    request == null ? null : request.kycStatus(),
                    request == null ? null : request.riskMin(),
                    pageNum,
                    pageSize,
                    null));
            if (page.getRecords() != null) {
                rows.addAll(page.getRecords());
            }
        }
        LocalDateTime createdAt = LocalDateTime.now();
        String jobNo = "C1-USER-EXP-" + DateTimeFormatter.ofPattern("yyyyMMddHHmmss").format(createdAt);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("jobNo", jobNo);
        detail.put("rowCount", rows.size());
        detail.put("keyword", text(request == null ? null : request.keyword()));
        detail.put("status", text(request == null ? null : request.status()));
        detail.put("kycStatus", text(request == null ? null : request.kycStatus()));
        detail.put("riskMin", text(request == null ? null : request.riskMin()));
        detail.put("reason", reason);
        detail.put("idempotencyKey", normalizedKey);
        audit("C1_USER_PROFILE_MASKED_EXPORTED", "USER_PROFILE_EXPORT", jobNo, null, operator, detail);
        return new UserProfileExportFile(
                jobNo + ".xls",
                profileExportWorkbook(jobNo, normalizedKey, createdAt, reason, rows),
                rows.size());
    }

    private void ensureC1ProfileSeeds() {
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        boolean seedUsersMissing = ACCOUNT_ACTION_SEED_LOOKUP_KEYS.stream()
                .anyMatch(key -> userRepository.findUserIdByLookupKey(key).isEmpty());
        if (seedUsersMissing) {
            userRepository.upsertAccountActionSeeds();
        }
    }

    private byte[] profileExportWorkbook(
            String jobNo,
            String idempotencyKey,
            LocalDateTime createdAt,
            String reason,
            List<UserAccountView> rows) {
        StringBuilder html = new StringBuilder(8192 + rows.size() * 640);
        html.append('\ufeff')
                .append("<html><head><meta charset=\"UTF-8\"></head><body>")
                .append("<table border=\"1\">")
                .append("<tr><th colspan=\"16\">C1 脱敏用户名单导出</th></tr>")
                .append("<tr><td>导出单号</td><td colspan=\"15\">").append(excelText(jobNo)).append("</td></tr>")
                .append("<tr><td>导出时间</td><td colspan=\"15\">").append(excelText(dateText(createdAt))).append("</td></tr>")
                .append("<tr><td>幂等键</td><td colspan=\"15\">").append(excelText(idempotencyKey)).append("</td></tr>")
                .append("<tr><td>导出原因</td><td colspan=\"15\">").append(excelText(reason)).append("</td></tr>")
                .append("<tr>")
                .append(th("用户编码"))
                .append(th("昵称"))
                .append(th("手机号(脱敏)"))
                .append(th("国家/地区"))
                .append(th("生命周期"))
                .append(th("V-Rank"))
                .append(th("KYC"))
                .append(th("状态"))
                .append(th("风险分"))
                .append(th("风险等级"))
                .append(th("设备数"))
                .append(th("活跃设备数"))
                .append(th("USDT余额"))
                .append(th("NEX余额"))
                .append(th("注册时间"))
                .append(th("最近登录"))
                .append("</tr>");
        for (UserAccountView row : rows) {
            html.append("<tr>")
                    .append(td(row.userNo()))
                    .append(td(row.nickname()))
                    .append(td(row.phoneMasked()))
                    .append(td(row.countryCode()))
                    .append(td(row.userLevel()))
                    .append(td(row.vRank()))
                    .append(td(row.kycStatus()))
                    .append(td(row.status()))
                    .append(td(row.riskScore()))
                    .append(td(row.riskBand()))
                    .append(td(row.deviceCount()))
                    .append(td(row.activeDeviceCount()))
                    .append(td(row.walletUsdt()))
                    .append(td(row.walletNex()))
                    .append(td(dateText(row.registeredAt())))
                    .append(td(dateText(row.lastLoginAt())))
                    .append("</tr>");
        }
        html.append("</table></body></html>");
        return html.toString().getBytes(StandardCharsets.UTF_8);
    }

    private String th(String value) {
        return "<th>" + excelText(value) + "</th>";
    }

    private String td(Object value) {
        return "<td style=\"mso-number-format:'\\@';\">" + excelText(value) + "</td>";
    }

    private String excelText(Object value) {
        String raw = value instanceof BigDecimal decimal ? decimal.toPlainString() : text(value);
        if (raw.startsWith("=") || raw.startsWith("+") || raw.startsWith("-") || raw.startsWith("@")) {
            raw = "'" + raw;
        }
        return raw.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private String dateText(LocalDateTime value) {
        return value == null ? "—" : value.toString().replace('T', ' ');
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
        ensureKycLedgerSeeds();
        PageResult<UserAccountView> accountPage = userRepository.pageProfiles(new UserQueryRequest(
                null,
                null,
                normalizedStatus,
                null,
                normalizedPageNum,
                normalizedPageSize,
                null));
        long total = countKyc(null);
        long verified = countKyc("APPROVED");
        long unverified = countKyc("NONE");
        long inReview = countKyc("PENDING");
        long rejected = countKyc("REJECTED");
        String pct = total == 0 ? "0.0" : BigDecimal.valueOf(verified)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(total), 1, java.math.RoundingMode.HALF_UP)
                .toPlainString();
        UserKycOverview overview = new UserKycOverview(
                new UserKycStats(total, verified, unverified, inReview, rejected, pct, 1),
                kycNetworkWhitelist(),
                accountPage.getRecords().stream().map(this::kycLedgerRow).toList(),
                List.of("nx_user.kyc_status", "nx_user_profile.wallet_address", "nx_config_item:" + KYC_NETWORK_WHITELIST_KEY, "nx_audit_log"),
                List.of("APPROVED opens withdrawal/exchange gates and is blocked when B1 coverage is below redline"));
        return ApiResult.ok(overview);
    }

    public ApiResult<UserKycLedgerRow> updateKycStatus(
            Long userId,
            String idempotencyKey,
            UserKycStatusUpdateRequest request) {
        ApiResult<UserKycLedgerRow> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        UserAccountView before = userRepository.findById(userId).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        String nextStatus;
        try {
            nextStatus = normalizeKycStatus(request.status());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        String currentStatus = normalizeKycStatus(before.kycStatus());
        if (nextStatus.equals(currentStatus)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        if ("APPROVED".equals(nextStatus) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        userRepository.updateKycStatus(userId, nextStatus, request.reason().trim());
        UserAccountView updated = userRepository.findById(userId)
                .orElse(new UserAccountView(
                        before.id(), before.userNo(), before.nickname(), before.phoneMasked(), before.countryCode(), before.status(),
                        nextStatus, before.userLevel(), before.vRank(), before.twoFactorEnabled(), before.walletUsdt(), before.walletNex(),
                        before.riskScore(), before.riskBand(), before.deviceCount(), before.activeDeviceCount(),
                        before.registeredAt(), before.lastLoginAt()));
        audit("C4_KYC_STATUS_CHANGED", "USER_KYC", String.valueOf(userId), userId, request.operator(), Map.of(
                "fromStatus", currentStatus,
                "toStatus", nextStatus,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim(),
                "event", "admin.kyc_status_changed"));
        return ApiResult.ok(kycLedgerRow(updated));
    }

    public ApiResult<Map<String, Object>> updateKycNetworkWhitelist(
            String idempotencyKey,
            UserKycNetworkUpdateRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String value;
        try {
            value = normalizeKycNetworkWhitelist(request.value());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        configFacade.upsertAdminValue(KYC_NETWORK_WHITELIST_KEY, value, "STRING", KYC_CONFIG_GROUP, request.reason().trim());
        audit("C4_KYC_NETWORK_WHITELIST_UPDATED", "USER_KYC_CONFIG", KYC_NETWORK_WHITELIST_KEY, null, request.operator(), Map.of(
                "value", value,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(Map.of("key", KYC_NETWORK_WHITELIST_KEY, "value", value));
    }

    public ApiResult<Map<String, Object>> createKycExport(
            String idempotencyKey,
            UserKycExportRequest request) {
        ApiResult<Map<String, Object>> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        String scope = StringUtils.hasText(request.scope()) ? request.scope().trim() : "MASKED_LEDGER";
        if (containsRawJsonOrUrl(scope)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "C4_EXPORT_SCOPE_INVALID");
        }
        String jobNo = "KYC-EXP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase(Locale.ROOT);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jobNo", jobNo);
        response.put("status", "QUEUED");
        response.put("scope", scope);
        response.put("masked", true);
        response.put("downloadPath", "L5/regulatory-export");
        audit("C4_KYC_MASKED_EXPORT_CREATED", "USER_KYC_EXPORT", jobNo, null, request.operator(), Map.of(
                "scope", scope,
                "masked", true,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(response);
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
        return ApiResult.ok(userRepository.sessions(userId, normalizeLimit(limit, 100, 200)));
    }

    public ApiResult<PageResult<UserSessionView>> sessionPage(Long userId, Integer pageNum, Integer pageSize, Integer limit) {
        int normalizedPageSize = pageSize == null ? normalizeLimit(limit, 20, 200) : normalizeLimit(pageSize, 20, 200);
        PageResult<UserSessionView> page = userRepository.pageSessions(userId, normalizePageNum(pageNum), normalizedPageSize);
        if (page.getTotal() == 0 && readTimeSeedPolicy.enabled()) {
            userRepository.upsertSecuritySessionSeeds();
            page = userRepository.pageSessions(userId, normalizePageNum(pageNum), normalizedPageSize);
        }
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
        ensureSecuritySessionSeeds();
        Long selectedUserId = resolveSecurityUserId(userKey, userId);
        if (selectedUserId == null) {
            if (StringUtils.hasText(userKey) || userId != null) {
                return ApiResult.fail(404, "USER_NOT_FOUND");
            }
            return ApiResult.ok(emptySecurityOverview(pageNum, pageSize, limit));
        }
        UserSecurityOverview overview = loadSecurityOverview(selectedUserId, pageNum, pageSize, limit);
        if (securityOverviewSeedsMissing(overview) && readTimeSeedPolicy.enabled()) {
            userRepository.upsertSecuritySessionSeeds();
            overview = loadSecurityOverview(selectedUserId, pageNum, pageSize, limit);
        }
        return ApiResult.ok(overview);
    }

    private void ensureSecuritySessionSeeds() {
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        if (securitySeedUsersMissing()) {
            userRepository.upsertSecuritySessionSeeds();
        }
    }

    private boolean securitySeedUsersMissing() {
        return SECURITY_SESSION_SEED_LOOKUP_KEYS.stream()
                .anyMatch(lookupKey -> userRepository.findUserIdByLookupKey(lookupKey).isEmpty());
    }

    private Long resolveSecurityUserId(String userKey, Long userId) {
        if (userId != null) {
            return userId;
        }
        if (!StringUtils.hasText(userKey)) {
            return userRepository.search(null, null, null, 1).stream()
                    .map(UserAccountView::id)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse(null);
        }
        String lookupKey = userKey.trim();
        return userRepository.findUserIdByLookupKey(lookupKey).orElse(null);
    }

    private UserSecurityOverview emptySecurityOverview(Integer pageNum, Integer pageSize, Integer limit) {
        int normalizedPageNum = normalizePageNum(pageNum);
        int normalizedPageSize = pageSize == null ? normalizeLimit(limit, 10, 100) : normalizeLimit(pageSize, 10, 100);
        return new UserSecurityOverview(
                securityStats(List.of(), List.of()),
                CREDENTIAL_PARAM_DEFINITIONS.stream().map(this::credentialParamView).toList(),
                null,
                new PageResult<>(0L, normalizedPageNum, normalizedPageSize, List.of()),
                List.of(),
                List.of("nx_user", "nx_user_security", "nx_user_session", "nx_config_item:auth.session.*"),
                List.of("C5 writes require Idempotency-Key and Confirm-with-Reason",
                        "2FA disable and password reset require secondary identity verification",
                        "Passwords are never visible to operators; reset only invalidates the old hash"));
    }

    private boolean securityOverviewSeedsMissing(UserSecurityOverview overview) {
        return overview.selectedUser() == null
                || overview.sessions().getRecords().isEmpty()
                || overview.lockedUsers().isEmpty()
                || securitySeedUsersMissing();
    }

    private UserSecurityOverview loadSecurityOverview(Long selectedUserId, Integer pageNum, Integer pageSize, Integer limit) {
        int normalizedPageNum = normalizePageNum(pageNum);
        int normalizedPageSize = pageSize == null ? normalizeLimit(limit, 10, 100) : normalizeLimit(pageSize, 10, 100);
        PageResult<UserSessionView> sessionPage = userRepository.pageSessions(selectedUserId, normalizedPageNum, normalizedPageSize);
        List<UserAccountView> accounts = userRepository.search(null, null, null, 200);
        UserSecurityUserRow selectedUser = userRepository.findById(selectedUserId)
                .map(this::securityUserRow)
                .orElse(null);
        List<UserSecurityUserRow> lockedUsers = lockedSecurityUsers();
        return new UserSecurityOverview(
                securityStats(accounts, lockedUsers),
                CREDENTIAL_PARAM_DEFINITIONS.stream().map(this::credentialParamView).toList(),
                selectedUser,
                sessionPage,
                lockedUsers,
                List.of("nx_user", "nx_user_security", "nx_user_session", "nx_config_item:auth.session.*"),
                List.of("C5 writes require Idempotency-Key and Confirm-with-Reason",
                        "2FA disable and password reset require secondary identity verification",
                        "Passwords are never visible to operators; reset only invalidates the old hash"));
    }

    private List<UserSecurityUserRow> lockedSecurityUsers() {
        int shortLockThreshold = Math.max(configInt("auth.risk.login_lock_threshold", 5), 1);
        int longLockThreshold = Math.max(configInt("auth.risk.login_long_lock_threshold", 10), shortLockThreshold);
        int shortLockMinutes = Math.max(configInt("auth.risk.lock_duration_minutes", 30), 1);
        int longLockHours = Math.max(configInt("auth.risk.long_lock_duration_hours", 24), 1);
        return userRepository.lockedSecurityUsers(shortLockThreshold, longLockThreshold, shortLockMinutes, longLockHours, 20);
    }

    private UserSecurityStats securityStats(List<UserAccountView> accounts, List<UserSecurityUserRow> lockedUsers) {
        long total = accounts.size();
        long twoFactorEnabled = accounts.stream()
                .filter(account -> Boolean.TRUE.equals(account.twoFactorEnabled()))
                .count();
        double twoFactorRate = total == 0 ? 0D : (twoFactorEnabled * 100D / total);
        long lockedLong = lockedUsers.stream().filter(row -> "LONG".equals(row.lockKind())).count();
        long lockedShort = lockedUsers.stream().filter(row -> "SHORT".equals(row.lockKind())).count();
        return new UserSecurityStats(
                numberValue(userRepository.overview().get("activeSessions")),
                String.format(Locale.ROOT, "%.1f", twoFactorRate),
                lockedShort,
                lockedLong,
                configLong("auth.session.token_reuse_today", 3L));
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
        UserAccountActionOverview overview = loadAccountActionOverview();
        if (accountActionSeedsMissing(overview) && readTimeSeedPolicy.enabled()) {
            userRepository.upsertAccountActionSeeds();
            overview = loadAccountActionOverview();
        }
        return ApiResult.ok(overview);
    }

    private boolean accountActionSeedsMissing(UserAccountActionOverview overview) {
        boolean seedUsersMissing = ACCOUNT_ACTION_SEED_LOOKUP_KEYS.stream()
                .anyMatch(key -> userRepository.findUserIdByLookupKey(key).isEmpty());
        return seedUsersMissing
                || overview.sessions().isEmpty()
                || overview.accountLists().isEmpty()
                || overview.impersonations().isEmpty();
    }

    private UserAccountActionOverview loadAccountActionOverview() {
        Map<String, Object> baseOverview = userRepository.overview();
        List<UserAccountView> accounts = userRepository.search(null, null, null, 50);
        List<UserAccountListEntryView> accountLists = userRepository.accountLists(null, 100);
        List<UserSessionView> sessions = userRepository.sessions(null, 200);
        List<UserImpersonationSessionView> impersonations = userRepository.impersonations(50);
        long trustListCount = accountLists.stream()
                .filter(entry -> "ACTIVE".equalsIgnoreCase(entry.status()))
                .filter(entry -> "ALLOW".equalsIgnoreCase(entry.kind()))
                .count();
        long blockedListCount = accountLists.stream()
                .filter(entry -> "ACTIVE".equalsIgnoreCase(entry.status()))
                .filter(entry -> "BLOCK".equalsIgnoreCase(entry.kind()))
                .count();
        long activeImpersonations = impersonations.stream()
                .filter(session -> "ACTIVE".equalsIgnoreCase(session.status()))
                .count();
        return new UserAccountActionOverview(
                accounts,
                accountLists,
                sessions,
                impersonations,
                numberValue(baseOverview.get("frozenUsers")),
                numberValue(baseOverview.get("activeSessions")),
                trustListCount,
                blockedListCount,
                activeImpersonations,
                List.of("nx_user", "nx_user_session", "nx_account_list", "nx_user_impersonation_session", "nx_audit_log"),
                List.of("C2 writes require Idempotency-Key and Confirm-with-Reason", "Repeated state transitions return 409"));
    }

    public ApiResult<UserAccountListEntryView> upsertAccountList(String idempotencyKey, UserAccountListUpsertRequest request) {
        ApiResult<UserAccountListEntryView> guard = requireUserCommand(
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
        UserAccountView user = userRepository.findById(request.userId()).orElse(null);
        if (user == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        String kind;
        LocalDateTime expiresAt;
        try {
            kind = normalizeAccountListKind(request.kind());
            expiresAt = parseOptionalExpiry(request.expiresAt());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
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
        audit("C2_ACCOUNT_LIST_UPSERTED", "USER_ACCOUNT_LIST", String.valueOf(request.userId()), request.userId(), operator, detail);
        return ApiResult.ok(updated);
    }

    public ApiResult<UserAccountListEntryView> removeAccountList(Long userId, String idempotencyKey, UserAccountListRemoveRequest request) {
        ApiResult<UserAccountListEntryView> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<UserAccountListEntryView> reasonGuard = requirePlainReason(request.reason(), "ACCOUNT_LIST_REASON_REJECTED");
        if (reasonGuard != null) {
            return reasonGuard;
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
        audit("C2_ACCOUNT_LIST_REMOVED", "USER_ACCOUNT_LIST", String.valueOf(userId), userId, operator, Map.of(
                "kind", existing.kind(),
                "reason", reason,
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<UserImpersonationSessionView> terminateImpersonation(
            String sessionNo,
            String idempotencyKey,
            UserImpersonationTerminateRequest request) {
        ApiResult<UserImpersonationSessionView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
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
        userRepository.terminateImpersonation(normalizedSessionNo, reason, operator);
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
        audit("C2_USER_IMPERSONATION_TERMINATED", "USER_IMPERSONATION", normalizedSessionNo, existing.userId(), operator, Map.of(
                "reason", reason,
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> revokeUserSessions(Long userId, String idempotencyKey, UserSessionRevokeAllRequest request) {
        ApiResult<Map<String, Object>> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        ApiResult<Map<String, Object>> reasonGuard = requirePlainReason(request.reason(), "SESSION_REVOKE_REASON_REJECTED");
        if (reasonGuard != null) {
            return reasonGuard;
        }
        if (userRepository.findById(userId).isEmpty() && readTimeSeedPolicy.enabled()) {
            userRepository.upsertSecuritySessionSeeds();
        }
        if (userRepository.findById(userId).isEmpty()) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        List<UserSessionView> activeSessions = userRepository.sessions(userId, 200).stream()
                .filter(session -> "ACTIVE".equalsIgnoreCase(session.status()))
                .toList();
        String reason = request.reason().trim();
        String operator = operator(request.operator());
        userRepository.revokeUserSessions(userId, reason);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("userId", userId);
        response.put("revokedCount", activeSessions.size());
        response.put("status", "REVOKED");
        audit("C2_USER_SESSIONS_REVOKED", "USER_SESSION", String.valueOf(userId), userId, operator, Map.of(
                "revokedCount", activeSessions.size(),
                "reason", reason,
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(response);
    }

    public ApiResult<UserSecurityStatusView> securityStatus(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        UserSecurityStatusView status = loadSecurityStatus(userId);
        if (status == null && readTimeSeedPolicy.enabled()) {
            userRepository.upsertSecuritySessionSeeds();
            status = loadSecurityStatus(userId);
        }
        return status == null ? ApiResult.fail(404, "USER_NOT_FOUND") : ApiResult.ok(status);
    }

    public ApiResult<List<UserCredentialParamView>> credentialParams() {
        return ApiResult.ok(CREDENTIAL_PARAM_DEFINITIONS.stream()
                .map(this::credentialParamView)
                .toList());
    }

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
        configFacade.upsertAdminValue(
                definition.configKey(),
                String.valueOf(nextValue),
                "NUMBER",
                AUTH_CONFIG_GROUP,
                request.reason().trim());
        UserCredentialParamView updated = credentialParamView(definition, nextValue);
        audit("C5_CREDENTIAL_PARAM_UPDATED", "AUTH_CONFIG", definition.key(), null, request.operator(), Map.of(
                "paramKey", definition.key(),
                "configKey", definition.configKey(),
                "value", nextValue,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<UserRegistrationRiskOverview> registrationRiskOverview() {
        ensureRegistrationRiskConfigSeeds();
        String captchaRestoreWindow = configFacade.activeValue(CAPTCHA_OFF_WINDOW_KEY)
                .filter(StringUtils::hasText)
                .orElse("");
        long lockedShort = configLong("auth.risk.locked_short_count", 198L);
        long lockedLong = configLong("auth.risk.locked_long_count", 16L);
        UserRegistrationRiskOverview overview = new UserRegistrationRiskOverview(
                new UserRegistrationRiskStats(
                        configLong("auth.risk.otp_sent_today", 31_240L),
                        configLong("auth.risk.captcha_triggered_today", 412L),
                        lockedShort,
                        lockedLong,
                        lockedShort + lockedLong,
                        configLong("auth.risk.stuffing_clusters_7d", 38L),
                        StringUtils.hasText(captchaRestoreWindow),
                        captchaRestoreWindow),
                REGISTRATION_RISK_PARAM_DEFINITIONS.stream()
                        .map(this::registrationRiskParamView)
                        .toList(),
                registrationRiskK1Guards(),
                K1_REJECT_CODE,
                K1_PATH,
                List.of("nx_config_item:auth.risk.*", "nx_user_security", "nx_audit_log", "K1 facade boundary"),
                List.of("C6 writes require Idempotency-Key and Confirm-with-Reason",
                        "K1 multi-account params are rejected here with 422 " + K1_REJECT_CODE,
                        "CAPTCHA off window must be explicit and audited"));
        return ApiResult.ok(overview);
    }

    private void ensureRegistrationRiskConfigSeeds() {
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        REGISTRATION_RISK_PARAM_DEFINITIONS.forEach(definition -> {
            ensureAdminConfigSeed(definition.configKey(), String.valueOf(definition.fallback()), "NUMBER");
            if (definition.composite()) {
                ensureAdminConfigSeed(
                        definition.secondaryConfigKey(),
                        String.valueOf(definition.secondaryFallback()),
                        "NUMBER");
            }
        });
        ensureAdminConfigSeed("auth.risk.otp_sent_today", "31240", "NUMBER");
        ensureAdminConfigSeed("auth.risk.captcha_triggered_today", "412", "NUMBER");
        ensureAdminConfigSeed("auth.risk.locked_short_count", "198", "NUMBER");
        ensureAdminConfigSeed("auth.risk.locked_long_count", "16", "NUMBER");
        ensureAdminConfigSeed("auth.risk.stuffing_clusters_7d", "38", "NUMBER");
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
        if ("captchaOff".equals(normalizedKey)) {
            return updateCaptchaOffWindow(idempotencyKey, request);
        }

        RegistrationRiskParamDefinition definition = registrationRiskParamDefinition(normalizedKey);
        if (definition == null) {
            return ApiResult.fail(404, "REGISTRATION_RISK_PARAM_NOT_FOUND");
        }

        int[] values;
        try {
            values = normalizeRegistrationRiskParamValues(definition, request.value());
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        configFacade.upsertAdminValue(
                definition.configKey(),
                String.valueOf(values[0]),
                "NUMBER",
                AUTH_CONFIG_GROUP,
                request.reason().trim());
        if (definition.composite()) {
            configFacade.upsertAdminValue(
                    definition.secondaryConfigKey(),
                    String.valueOf(values[1]),
                    "NUMBER",
                    AUTH_CONFIG_GROUP,
                    request.reason().trim());
        }

        UserRegistrationRiskParamView updated = registrationRiskParamView(definition, values);
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("paramKey", definition.key());
        detail.put("configKey", definition.configKey());
        detail.put("value", values[0]);
        if (definition.composite()) {
            detail.put("secondaryConfigKey", definition.secondaryConfigKey());
            detail.put("secondaryValue", values[1]);
        }
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", idempotencyKey.trim());
        audit("C6_REGISTRATION_RISK_PARAM_UPDATED", "AUTH_CONFIG", definition.key(), null, request.operator(), detail);
        return ApiResult.ok(updated);
    }

    public ApiResult<UserSecurityStatusView> disableTwoFactor(Long userId, String idempotencyKey, UserSecurityActionRequest request) {
        ApiResult<UserSecurityStatusView> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        UserSecurityStatusView before = loadSecurityStatus(userId);
        if (before == null && readTimeSeedPolicy.enabled()) {
            userRepository.upsertSecuritySessionSeeds();
            before = loadSecurityStatus(userId);
        }
        if (before == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        userRepository.disableTwoFactor(userId);
        UserSecurityStatusView updated = loadSecurityStatus(userId);
        audit("C5_TWO_FACTOR_DISABLED", "USER_SECURITY", String.valueOf(userId), userId, request.operator(), Map.of(
                "fromTwoFactorEnabled", before.twoFactorEnabled(),
                "toTwoFactorEnabled", false,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<UserSecurityStatusView> requestPasswordReset(Long userId, String idempotencyKey, UserSecurityActionRequest request) {
        ApiResult<UserSecurityStatusView> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (loadSecurityStatus(userId) == null && readTimeSeedPolicy.enabled()) {
            userRepository.upsertSecuritySessionSeeds();
        }
        if (loadSecurityStatus(userId) == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        String resetMarker = RESET_REQUIRED_PREFIX + UUID.randomUUID().toString().replace("-", "");
        userRepository.markPasswordResetRequired(userId, resetMarker);
        userRepository.revokeUserSessions(userId, request.reason().trim());
        UserSecurityStatusView updated = loadSecurityStatus(userId);
        audit("C5_PASSWORD_RESET_REQUESTED", "USER_SECURITY", String.valueOf(userId), userId, request.operator(), Map.of(
                "passwordResetRequired", true,
                "sessionsRevoked", true,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<UserSecurityStatusView> unlockSecurity(Long userId, String idempotencyKey, UserSecurityActionRequest request) {
        ApiResult<UserSecurityStatusView> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        UserSecurityStatusView before = loadSecurityStatus(userId);
        if (before == null && readTimeSeedPolicy.enabled()) {
            userRepository.upsertSecuritySessionSeeds();
            before = loadSecurityStatus(userId);
        }
        if (before == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        userRepository.resetLoginFailures(userId);
        UserSecurityStatusView updated = loadSecurityStatus(userId);
        audit("C5_USER_UNLOCKED", "USER_SECURITY", String.valueOf(userId), userId, request.operator(), Map.of(
                "fromLoginFailCount", before.loginFailCount(),
                "toLoginFailCount", 0,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<UserAccountView> updateStatus(Long userId, String idempotencyKey, UserStatusUpdateRequest request) {
        ApiResult<UserAccountView> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        UserAccountView before = userRepository.findById(userId).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        String nextStatus = normalizeUserStatus(request.status());
        if (nextStatus.equalsIgnoreCase(before.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        userRepository.updateUserStatus(userId, nextStatus, request.reason().trim());
        boolean sessionsRevoked = "FROZEN".equals(nextStatus);
        int withdrawalsFrozen = 0;
        boolean riskSignalRecorded = false;
        if (sessionsRevoked) {
            userRepository.revokeUserSessions(userId, request.reason().trim());
            withdrawalsFrozen = financeWithdrawalControlFacade.freezePendingWithdrawalsForUser(
                    userId,
                    request.reason().trim(),
                    request.operator());
            riskUserStateFacade.recordUserFrozen(userId, before.userNo(), request.reason().trim(), request.operator());
            riskSignalRecorded = true;
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
        detail.put("riskSignalRecorded", riskSignalRecorded);
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", idempotencyKey.trim());
        audit("C2_USER_STATUS_CHANGED", "USER", String.valueOf(userId), userId, request.operator(), detail);
        return ApiResult.ok(updated);
    }

    public ApiResult<UserSessionView> revokeSession(String refreshTokenId, String idempotencyKey, UserSessionRevokeRequest request) {
        if (!StringUtils.hasText(refreshTokenId)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "REFRESH_TOKEN_ID_REQUIRED");
        }
        ApiResult<UserSessionView> guard = requireCommand(idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        UserSessionView session = userRepository.findSession(refreshTokenId.trim()).orElse(null);
        if (session == null && readTimeSeedPolicy.enabled()) {
            userRepository.upsertSecuritySessionSeeds();
            session = userRepository.findSession(refreshTokenId.trim()).orElse(null);
        }
        if (session == null) {
            return ApiResult.fail(404, "SESSION_NOT_FOUND");
        }
        if ("REVOKED".equalsIgnoreCase(session.status())) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        userRepository.revokeSession(refreshTokenId.trim(), request.reason().trim());
        UserSessionView updated = userRepository.findSession(refreshTokenId.trim())
                .orElse(new UserSessionView(
                        session.userId(), session.refreshTokenId(), session.deviceName(), session.clientIpMasked(), "REVOKED",
                        session.issuedAt(), session.expiresAt(), LocalDateTime.now()));
        audit("C3_USER_SESSION_REVOKED", "USER_SESSION", refreshTokenId.trim(), session.userId(), request.operator(), Map.of(
                "status", "REVOKED",
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(updated);
    }

    public ApiResult<Map<String, Object>> startImpersonation(Long userId, String idempotencyKey, UserImpersonationRequest request) {
        ApiResult<Map<String, Object>> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (userRepository.findById(userId).isEmpty()) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        int ttlMinutes = request.ttlMinutes() == null ? 15 : request.ttlMinutes();
        if (ttlMinutes < 5 || ttlMinutes > 60) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "IMPERSONATION_TTL_OUT_OF_RANGE");
        }
        String sessionNo = "IMP-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(ttlMinutes);
        String operator = operator(request.operator());
        userRepository.recordImpersonationSession(sessionNo, userId, ttlMinutes, operator, request.reason().trim(), expiresAt);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("sessionNo", sessionNo);
        response.put("userId", userId);
        response.put("status", "ACTIVE");
        response.put("ttlMinutes", ttlMinutes);
        response.put("expiresAt", expiresAt);
        response.put("boundary", "admin impersonation is audited and does not expose credentials");
        audit("C2_USER_IMPERSONATION_STARTED", "USER_IMPERSONATION", sessionNo, userId, operator, Map.of(
                "ttlMinutes", ttlMinutes,
                "reason", request.reason().trim(),
                "idempotencyKey", idempotencyKey.trim()));
        return ApiResult.ok(response);
    }

    public ApiResult<Map<String, Object>> createAssetAdjustment(
            Long userId,
            String idempotencyKey,
            UserAssetAdjustmentRequest request) {
        ApiResult<Map<String, Object>> guard = requireUserCommand(userId, idempotencyKey, request == null ? null : request.reason());
        if (guard != null) {
            return guard;
        }
        if (userRepository.findById(userId).isEmpty()) {
            return ApiResult.fail(404, "USER_NOT_FOUND");
        }
        String asset = normalizeAsset(request.asset());
        String direction = normalizeDirection(request.direction());
        BigDecimal amount = positiveAmount(request.amount());
        if ("CREDIT".equals(direction) && coverageBelowRedline()) {
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        String adjustmentNo = "ADJ-" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase(Locale.ROOT);
        String operator = operator(request.operator());
        String reasonCode = adjustmentReasonCode(request.referenceType(), request.referenceId());
        userRepository.createAssetAdjustment(adjustmentNo, userId, asset, direction, amount, reasonCode, request.reason().trim(), operator);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("adjustmentNo", adjustmentNo);
        response.put("userId", userId);
        response.put("asset", asset);
        response.put("direction", direction);
        response.put("amount", amount);
        response.put("reasonCode", reasonCode);
        response.put("status", "PENDING_REVIEW");
        response.put("ledgerPosting", "deferred-to-D-domain-review");
        Map<String, Object> auditDetail = new LinkedHashMap<>();
        auditDetail.put("asset", asset);
        auditDetail.put("direction", direction);
        auditDetail.put("amount", amount);
        auditDetail.put("reasonCode", reasonCode);
        if (StringUtils.hasText(request.referenceType())) {
            auditDetail.put("referenceType", request.referenceType().trim());
        }
        if (StringUtils.hasText(request.referenceId())) {
            auditDetail.put("referenceId", request.referenceId().trim());
        }
        auditDetail.put("reason", request.reason().trim());
        auditDetail.put("idempotencyKey", idempotencyKey.trim());
        auditDetail.put("ledgerPosting", "deferred-to-D-domain-review");
        audit("C3_MANUAL_ASSET_ADJUSTMENT_CREATED", "WALLET_ASSET_ADJUSTMENT", adjustmentNo, userId, operator, auditDetail);
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
        overview.put("sources", List.of("nx_wallet_asset_adjustment", "nx_user", "nx_treasury_coverage_snapshot", "nx_audit_log"));
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

    @Transactional
    public ApiResult<UserAssetAdjustmentDetail> approveAssetAdjustment(
            String adjustmentNo,
            String idempotencyKey,
            UserAssetAdjustmentReviewRequest request) {
        return reviewAssetAdjustment(adjustmentNo, "APPROVED", idempotencyKey, request);
    }

    public ApiResult<UserAssetAdjustmentDetail> rejectAssetAdjustment(
            String adjustmentNo,
            String idempotencyKey,
            UserAssetAdjustmentReviewRequest request) {
        return reviewAssetAdjustment(adjustmentNo, "REJECTED", idempotencyKey, request);
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
        String normalizedNo;
        try {
            normalizedNo = requireText(adjustmentNo, "ADJUSTMENT_NO_REQUIRED");
        } catch (IllegalArgumentException ex) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), ex.getMessage());
        }
        UserAssetAdjustmentView before = userRepository.findAssetAdjustment(normalizedNo).orElse(null);
        if (before == null) {
            return ApiResult.fail(404, "ASSET_ADJUSTMENT_NOT_FOUND");
        }
        String currentStatus = normalizeAdjustmentStatus(before.status());
        if (!REVIEWABLE_ADJUSTMENT_STATUSES.contains(currentStatus)) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), OpsErrorCode.INVALID_STATE_TRANSITION.name());
        }
        String operator = operator(request.operator());
        String reason = request.reason().trim();
        if ("APPROVED".equals(nextStatus) && before.credit() && coverageBelowRedline()) {
            userRepository.reviewAssetAdjustment(normalizedNo, "SUSPENDED", operator, reason);
            audit("C3_ASSET_ADJUSTMENT_SUSPENDED", "WALLET_ASSET_ADJUSTMENT", normalizedNo, before.userId(), operator, Map.of(
                    "fromStatus", currentStatus,
                    "toStatus", "SUSPENDED",
                    "reason", reason,
                    "idempotencyKey", idempotencyKey.trim(),
                    "redline", "B1_COVERAGE_BELOW_REDLINE"));
            return ApiResult.fail(OpsErrorCode.COVERAGE_BELOW_REDLINE.httpStatus(), OpsErrorCode.COVERAGE_BELOW_REDLINE.name());
        }
        Long ledgerId = null;
        if ("APPROVED".equals(nextStatus)) {
            ledgerId = userRepository.approveAssetAdjustmentAndPostLedger(before, operator, reason);
        } else {
            userRepository.reviewAssetAdjustment(normalizedNo, nextStatus, operator, reason);
        }
        UserAssetAdjustmentView updated = userRepository.findAssetAdjustment(normalizedNo).orElse(null);
        if (updated == null) {
            return ApiResult.fail(OpsErrorCode.INVALID_STATE_TRANSITION.httpStatus(), "ASSET_ADJUSTMENT_RELOAD_FAILED");
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
        audit("APPROVED".equals(nextStatus) ? "C3_ASSET_ADJUSTMENT_APPROVED" : "C3_ASSET_ADJUSTMENT_REJECTED",
                "WALLET_ASSET_ADJUSTMENT",
                normalizedNo,
                before.userId(),
                operator,
                auditDetail);
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
            if (value.matches("\\d{4}-\\d{2}-\\d{2}")) {
                return LocalDate.parse(value).atTime(23, 59, 59);
            }
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException("ACCOUNT_LIST_EXPIRES_AT_INVALID", ex);
        }
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
            throw new IllegalArgumentException("Points system is sunset");
        }
        if (!ASSETS.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported asset for C manual adjustment");
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
            throw new IllegalArgumentException("Unsupported adjustment direction");
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
            throw new IllegalArgumentException("Invalid adjustment amount", ex);
        }
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Adjustment amount must be positive");
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
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        boolean missingSeed = ASSET_ADJUSTMENT_SEED_NOS.stream()
                .anyMatch(adjustmentNo -> userRepository.findAssetAdjustment(adjustmentNo).isEmpty());
        if (missingSeed) {
            userRepository.upsertAssetAdjustmentSeeds();
        }
    }

    private void ensureKycLedgerSeeds() {
        if (!readTimeSeedPolicy.enabled()) {
            return;
        }
        boolean missingSeed = KYC_LEDGER_SEED_LOOKUP_KEYS.stream()
                .anyMatch(lookupKey -> userRepository.findUserIdByLookupKey(lookupKey).isEmpty());
        if (missingSeed) {
            userRepository.upsertKycLedgerSeeds();
        }
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
                ? List.of("B1 coverage redline is checked before credit approval", "Points/Premium/NEX v2 are not active adjustment targets")
                : List.of("Debit adjustment requires Confirm-with-Reason and audit trail");
        return new UserAssetAdjustmentDetail(
                adjustment,
                account,
                coverageFacade.snapshot(),
                reviewTrail,
                redlines,
                List.of("nx_wallet_asset_adjustment", "nx_user", "nx_audit_log", "TreasuryCoverageFacade"));
    }

    private long countKyc(String kycStatus) {
        if (!StringUtils.hasText(kycStatus)) {
            Object total = userRepository.overview().get("totalUsers");
            return total instanceof Number number ? number.longValue() : 0L;
        }
        return userRepository.countByKycStatus(kycStatus);
    }

    private UserKycLedgerRow kycLedgerRow(UserAccountView account) {
        String backendStatus = normalizeKycStatus(account.kycStatus());
        String displayStatus = displayKycStatus(backendStatus);
        String walletAddress = account.id() == null
                ? null
                : userRepository.findWalletAddressByUserId(account.id()).orElse(null);
        boolean walletPaired = StringUtils.hasText(walletAddress);
        String pairedAddress = walletPaired ? maskWalletAddress(walletAddress) : "未绑定";
        String network = walletPaired ? inferWalletNetwork(walletAddress) : "—";
        String pairedAt = walletPaired ? formatKycPairedAt(account.registeredAt()) : "—";
        String triggerSource = switch (backendStatus) {
            case "APPROVED" -> walletPaired ? "首次提现 / 主动验证" : "已完成实名";
            case "PENDING" -> "K5 / 人工复审";
            case "REJECTED" -> "复审驳回";
            default -> "首次提现 / 累计兑换过线前";
        };
        List<UserKycKeyValue> info = List.of(
                new UserKycKeyValue("当前状态", labelKycStatus(backendStatus)),
                new UserKycKeyValue("账户状态", text(account.status())),
                new UserKycKeyValue("国家/地区", text(account.countryCode())),
                new UserKycKeyValue("用户等级", text(account.userLevel())),
                new UserKycKeyValue("钱包已配对", walletPaired ? "是" : "否"),
                new UserKycKeyValue("配对地址", pairedAddress),
                new UserKycKeyValue("网络", network),
                new UserKycKeyValue("地址来源", walletPaired ? "nx_user_profile.wallet_address" : "未绑定,不由前端伪造"));
        List<String> history = List.of(
                "当前 KYC 状态来自 nx_user.kyc_status = " + backendStatus,
                "配对地址来自 nx_user_profile.wallet_address = " + (walletPaired ? "已脱敏展示" : "未绑定"),
                account.lastLoginAt() == null ? "最近登录:—" : "最近登录:" + account.lastLoginAt(),
                account.registeredAt() == null ? "注册时间:—" : "注册时间:" + account.registeredAt());
        return new UserKycLedgerRow(
                account.id(),
                account.userNo(),
                account.nickname(),
                account.phoneMasked(),
                account.countryCode(),
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

    private String inferWalletNetwork(String walletAddress) {
        String trimmed = walletAddress == null ? "" : walletAddress.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("bc1")) {
            return "BTC";
        }
        if (trimmed.startsWith("T")) {
            return "TRC20";
        }
        if (lower.startsWith("0x")) {
            return "ERC20";
        }
        return "UNKNOWN";
    }

    private String formatKycPairedAt(LocalDateTime registeredAt) {
        return registeredAt == null ? "—" : registeredAt.format(KYC_PAIRED_DATE_FORMATTER);
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
                    int lockDurationMinutes = configInt("auth.risk.lock_duration_minutes", 30);
                    return new UserSecurityStatusView(
                            raw.userId(),
                            raw.twoFactorEnabled(),
                            raw.loginFailCount(),
                            lockThreshold > 0 && raw.loginFailCount() >= lockThreshold,
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

    private UserRegistrationRiskParamView registrationRiskParamView(RegistrationRiskParamDefinition definition) {
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
        return registrationRiskParamView(definition, values);
    }

    private UserRegistrationRiskParamView registrationRiskParamView(
            RegistrationRiskParamDefinition definition,
            int[] values) {
        return new UserRegistrationRiskParamView(
                definition.group(),
                definition.key(),
                definition.name(),
                definition.sub(),
                registrationRiskDisplayValue(definition, values),
                definition.composite() ? definition.unit() + "/" + definition.secondaryUnit() : definition.unit(),
                definition.min(),
                definition.max(),
                false,
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
        String value = request.value() == null ? "" : request.value().trim();
        if (containsRawJsonOrUrl(value)) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "CAPTCHA_RESTORE_WINDOW_REJECTED");
        }
        configFacade.upsertAdminValue(CAPTCHA_OFF_WINDOW_KEY, value, "STRING", AUTH_CONFIG_GROUP, request.reason().trim());
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("paramKey", "captchaOff");
        detail.put("configKey", CAPTCHA_OFF_WINDOW_KEY);
        detail.put("value", value);
        detail.put("reason", request.reason().trim());
        detail.put("idempotencyKey", idempotencyKey.trim());
        audit(
                StringUtils.hasText(value) ? "C6_CAPTCHA_TEMPORARILY_DISABLED" : "C6_CAPTCHA_RESTORED",
                "AUTH_CONFIG",
                "captchaOff",
                null,
                request.operator(),
                detail);
        return ApiResult.ok(new UserRegistrationRiskParamView(
                "captcha",
                "captchaOff",
                "全局开关",
                "仅限服务商故障等紧急维护时关，关闭必须填恢复时限",
                value,
                "恢复时限",
                0,
                0,
                false,
                "空值表示开启，非空表示临时关闭并记录恢复时限",
                CAPTCHA_OFF_WINDOW_KEY));
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
        List<Integer> numbers = NUMBER_PATTERN.matcher(raw)
                .results()
                .map(match -> Integer.parseInt(match.group()))
                .toList();
        if (numbers.isEmpty() || (definition.composite() && numbers.size() < 2)) {
            throw new IllegalArgumentException("REGISTRATION_RISK_PARAM_VALUE_INVALID");
        }
        int value = numbers.get(0);
        if (value < definition.min() || value > definition.max()) {
            throw new IllegalArgumentException("REGISTRATION_RISK_PARAM_VALUE_OUT_OF_RANGE");
        }
        if (!definition.composite()) {
            return new int[] {value, 0};
        }
        int secondaryValue = numbers.get(1);
        if (secondaryValue < definition.secondaryMin() || secondaryValue > definition.secondaryMax()) {
            throw new IllegalArgumentException("REGISTRATION_RISK_PARAM_VALUE_OUT_OF_RANGE");
        }
        return new int[] {value, secondaryValue};
    }

    private int boundedConfigInt(String configKey, int fallback, int min, int max) {
        int value = configInt(configKey, fallback);
        return value < min || value > max ? (readTimeSeedPolicy.enabled() ? fallback : value) : value;
    }

    private int configInt(String configKey, int fallback) {
        try {
            return configFacade.activeValue(configKey)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(Integer::parseInt)
                    .orElseGet(() -> readTimeSeedPolicy.enabled() ? fallback : 0);
        } catch (NumberFormatException ex) {
            return readTimeSeedPolicy.enabled() ? fallback : 0;
        }
    }

    private long configLong(String configKey, long fallback) {
        try {
            return configFacade.activeValue(configKey)
                    .filter(StringUtils::hasText)
                    .map(String::trim)
                    .map(Long::parseLong)
                    .orElseGet(() -> readTimeSeedPolicy.enabled() ? fallback : 0L);
        } catch (NumberFormatException ex) {
            return readTimeSeedPolicy.enabled() ? fallback : 0L;
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
        return StringUtils.hasText(operator) ? operator.trim() : "system";
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
}

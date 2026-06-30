package ffdd.opsconsole.user.application;

import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.common.boundary.ApplicationService;
import ffdd.opsconsole.device.application.OpsDeviceService;
import ffdd.opsconsole.device.domain.DeviceOpsView;
import ffdd.opsconsole.device.domain.DeviceOrderView;
import ffdd.opsconsole.device.dto.DeviceOrderQueryRequest;
import ffdd.opsconsole.finance.application.OpsFinanceService;
import ffdd.opsconsole.finance.domain.DepositFlowView;
import ffdd.opsconsole.finance.domain.WithdrawalOrderView;
import ffdd.opsconsole.finance.dto.WithdrawalQueryRequest;
import ffdd.opsconsole.risk.application.OpsRiskService;
import ffdd.opsconsole.risk.domain.RiskCaseView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserNotificationView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.domain.UserTeamMemberView;
import ffdd.opsconsole.user.domain.User360Seed;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.transaction.annotation.Transactional;

@ApplicationService
@RequiredArgsConstructor
public class OpsUser360Service {
    private static final List<String> SOURCES = List.of(
            "nx_user",
            "nx_user_session",
            "nx_deposit_order",
            "nx_withdrawal_order",
            "nx_wallet_ledger",
            "nx_user_device",
            "nx_device_order",
            "nx_risk_decision",
            "nx_admin_risk_score_user",
            "nx_team_member",
            "nx_notification",
            "nx_audit_log");

    private final OpsUserService userService;
    private final OpsFinanceService financeService;
    private final OpsTreasuryService treasuryService;
    private final OpsDeviceService deviceService;
    private final OpsRiskService riskService;
    private final AuditLogService auditLogService;
    private final UserOpsRepository userRepository;
    private final OpsReadTimeSeedPolicy readTimeSeedPolicy;

    @Transactional
    public ApiResult<Map<String, Object>> detail(String userKey) {
        String lookupKey = trim(userKey);
        if (lookupKey == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        Long existingUserId = userRepository.findUserIdByLookupKey(lookupKey).orElse(null);
        if (existingUserId != null) {
            return detail(existingUserId);
        }
        User360Seed seed = C1_SEEDS.get(seedIndexKey(lookupKey));
        if (seed == null) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_NOT_FOUND");
        }
        Long seededUserId = userRepository.findUserIdByLookupKey(seed.referralCode()).orElse(null);
        if (seededUserId != null && !seedNeedsRepair(seededUserId, seed)) {
            return detail(seededUserId);
        }
        if (!readTimeSeedPolicy.enabled()) {
            return seededUserId == null
                    ? ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_NOT_FOUND")
                    : detail(seededUserId);
        }
        userRepository.upsertUser360Seed(seed);
        seededUserId = userRepository.findUserIdByLookupKey(seed.referralCode()).orElse(null);
        if (seededUserId == null) {
            return ApiResult.fail(OpsErrorCode.INTERNAL_ERROR.httpStatus(), "USER_SEED_FAILED");
        }
        return detail(seededUserId);
    }

    private boolean seedNeedsRepair(Long userId, User360Seed seed) {
        int expectedTeamSize = Math.max(0, Math.min(seed.teamSize() == null ? 0 : seed.teamSize(), 50));
        return userRepository.countTeamMembers(userId) != expectedTeamSize;
    }

    public ApiResult<Map<String, Object>> detail(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        ApiResult<UserAccountView> profileResult = userService.profile(userId);
        if (profileResult.getCode() != 0 || profileResult.getData() == null) {
            return ApiResult.fail(profileResult.getCode(), profileResult.getMessage());
        }

        UserAccountView profile = profileResult.getData();
        UserSecurityStatusView security = dataOrNull(userService.securityStatus(userId));
        List<UserSessionView> sessions = listOrEmpty(dataOrNull(userService.sessions(userId, 50)));
        PageResult<DepositFlowView> deposits = pageOrEmpty(dataOrNull(financeService.topupFlows("confirmed", userId, null, 1, 30)), 1, 30);
        PageResult<WithdrawalOrderView> withdrawals = pageOrEmpty(dataOrNull(
                financeService.withdrawals(new WithdrawalQueryRequest(null, userId, null, 1, 30))), 1, 30);
        Map<String, Object> ledger = mapOrEmpty(dataOrNull(treasuryService.userLedger(userId)));
        List<DeviceOpsView> devices = listOrEmpty(dataOrNull(deviceService.userDevices(userId, 200)));
        List<DeviceOrderView> orders = pageOrEmpty(dataOrNull(
                        deviceService.orders(new DeviceOrderQueryRequest(null, profile.userNo(), 1L, 50L))), 1, 50)
                .getRecords()
                .stream()
                .filter(order -> sameText(order.userNo(), profile.userNo()))
                .toList();
        List<RiskCaseView> riskCases = pageOrEmpty(dataOrNull(
                        riskService.cases(new RiskCaseQueryRequest(userId, null, null, 1, 20, null))), 1, 20)
                .getRecords();
        RiskScoreUserView riskScore = dataOrNull(riskService.scoreUser(profile.userNo()));
        List<AuditLogRecord> audit = auditLogs(userId);

        Map<String, Object> risk = risk(profile, riskScore, riskCases);
        Map<String, Object> deviceSection = devices(devices);
        Map<String, Object> depositSection = deposits(deposits.getRecords());
        Map<String, Object> withdrawalSection = withdrawals(withdrawals.getRecords());
        Map<String, Object> teamSection = team(userId);
        Map<String, Object> notificationSection = notifications(userId);
        List<TreasuryLedgerBillView> ledgerRows = ledgerRows(ledger);
        Map<String, Object> earningsSection = earnings(ledgerRows, deviceSection);
        Map<String, Object> referralSection = referral(teamSection, ledgerRows);
        Map<String, Object> vrankSection = vrank(profile, teamSection);
        Map<String, Object> financialSection = financial(profile, ledger, ledgerRows);
        Map<String, Object> engagementSection = engagement(notificationSection, audit);
        Map<String, Object> commerceSection = commerce(orders);
        Map<String, Object> accountSection = account(profile, security, sessions, risk);
        Map<String, Object> hub = section(
                "deposit", depositSection,
                "withdrawal", withdrawalSection,
                "devices", deviceSection,
                "earnings", earningsSection,
                "referral", referralSection,
                "vrank", vrankSection,
                "financial", financialSection,
                "engagement", engagementSection,
                "commerce", commerceSection,
                "account", accountSection,
                "notification", notificationSection);

        Map<String, Object> response = section(
                "profile", profile,
                "security", security,
                "sessions", sessions,
                "deposits", depositSection,
                "withdrawals", withdrawalSection,
                "ledger", ledger,
                "devices", deviceSection,
                "orders", section("total", orders.size(), "records", orders),
                "risk", risk,
                "team", teamSection,
                "notifications", notificationSection,
                "earnings", earningsSection,
                "referral", referralSection,
                "vrank", vrankSection,
                "financial", financialSection,
                "engagement", engagementSection,
                "commerce", commerceSection,
                "account", accountSection,
                "hub", hub,
                "audit", audit,
                "summary", summary(profile, security, sessions, depositSection, withdrawalSection, deviceSection, risk, teamSection),
                "sources", SOURCES,
                "redlines", List.of(
                        "server-canonical user 360",
                        "write actions require Idempotency-Key, reason and A2 audit",
                        "media URLs must come from nx_admin media upload APIs",
                        "sunset products are historical compatibility only"));
        return ApiResult.ok(response);
    }

    private Map<String, Object> summary(
            UserAccountView profile,
            UserSecurityStatusView security,
            List<UserSessionView> sessions,
            Map<String, Object> deposits,
            Map<String, Object> withdrawals,
            Map<String, Object> devices,
            Map<String, Object> risk,
            Map<String, Object> team) {
        return section(
                "userId", profile.id(),
                "userNo", profile.userNo(),
                "status", profile.status(),
                "kycStatus", profile.kycStatus(),
                "walletUsdt", money(profile.walletUsdt()),
                "walletNex", money(profile.walletNex()),
                "twoFactorEnabled", security != null && security.twoFactorEnabled(),
                "locked", security != null && security.locked(),
                "passwordResetRequired", security != null && security.passwordResetRequired(),
                "activeSessionCount", (int) sessions.stream().filter(session -> "ACTIVE".equalsIgnoreCase(session.status())).count(),
                "depositedUsd", deposits.get("confirmedUsd"),
                "withdrawnUsd", withdrawals.get("completedUsd"),
                "withdrawRequestedUsd", withdrawals.get("requestedUsd"),
                "deviceCount", devices.get("total"),
                "activeDeviceCount", devices.get("activeCount"),
                "onlineDeviceCount", devices.get("onlineCount"),
                "dailyUsdt", devices.get("dailyUsdt"),
                "teamSize", team.get("teamSize"),
                "riskScore", risk.get("effectiveScore"),
                "riskBand", risk.get("bandLabel"));
    }

    private Map<String, Object> team(Long userId) {
        List<UserTeamMemberView> rows = userRepository.teamMembers(userId, 20);
        return section(
                "teamSize", userRepository.countTeamMembers(userId),
                "directCount", userRepository.countDirectTeamMembers(userId),
                "teamVolumeUsd", money(userRepository.sumTeamVolume(userId)),
                "records", rows,
                "sourceStatus", "READY");
    }

    private Map<String, Object> notifications(Long userId) {
        List<UserNotificationView> rows = userRepository.notifications(userId, 20);
        return section(
                "total", rows.size(),
                "records", rows,
                "unreadCount", userRepository.countUnreadNotifications(userId),
                "pendingPushCount", userRepository.countPendingNotifications(userId),
                "failedPushCount", userRepository.countFailedNotifications(userId),
                "sourceStatus", "READY");
    }

    private Map<String, Object> deposits(List<DepositFlowView> rows) {
        BigDecimal confirmedUsd = rows.stream()
                .map(row -> firstAmount(row.providerReceived(), row.amount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return section(
                "total", rows.size(),
                "records", rows,
                "confirmedUsd", money(confirmedUsd),
                "sourceStatus", "READY");
    }

    private Map<String, Object> withdrawals(List<WithdrawalOrderView> rows) {
        BigDecimal completedUsd = rows.stream()
                .filter(row -> "SUCCESS".equalsIgnoreCase(row.status()) || "COMPLETED".equalsIgnoreCase(row.status()))
                .map(row -> safe(row.amount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal requestedUsd = rows.stream()
                .map(row -> safe(row.amount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return section(
                "total", rows.size(),
                "records", rows,
                "completedUsd", money(completedUsd),
                "requestedUsd", money(requestedUsd),
                "sourceStatus", "READY");
    }

    private Map<String, Object> devices(List<DeviceOpsView> rows) {
        long activeCount = rows.stream().filter(this::isActiveDevice).count();
        long onlineCount = rows.stream().filter(this::isOnlineDevice).count();
        BigDecimal dailyUsdt = rows.stream()
                .map(row -> safe(row.dailyUsdt()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal dailyNex = rows.stream()
                .map(row -> safe(row.dailyNex()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return section(
                "total", rows.size(),
                "records", rows,
                "activeCount", (int) activeCount,
                "onlineCount", (int) onlineCount,
                "dailyUsdt", money(dailyUsdt),
                "dailyNex", money(dailyNex),
                "sourceStatus", "READY");
    }

    private Map<String, Object> earnings(List<TreasuryLedgerBillView> ledgerRows, Map<String, Object> deviceSection) {
        List<TreasuryLedgerBillView> earningRows = ledgerRows.stream()
                .filter(row -> direction(row, "IN"))
                .filter(row -> ledgerKind(row, "EARNING", "REWARD", "COMMISSION", "MINING", "YIELD", "STAKING"))
                .toList();
        return section(
                "total", earningRows.size(),
                "records", earningRows,
                "totalUsdt", money(sumAsset(earningRows, "USDT")),
                "totalNex", money(sumAsset(earningRows, "NEX")),
                "deviceDailyUsdt", deviceSection.get("dailyUsdt"),
                "deviceDailyNex", deviceSection.get("dailyNex"),
                "sources", List.of("nx_wallet_ledger", "nx_user_device"),
                "sourceStatus", "READY");
    }

    private Map<String, Object> referral(Map<String, Object> teamSection, List<TreasuryLedgerBillView> ledgerRows) {
        List<TreasuryLedgerBillView> commissionRows = ledgerRows.stream()
                .filter(row -> direction(row, "IN"))
                .filter(row -> ledgerKind(row, "REFERRAL", "TEAM", "COMMISSION"))
                .toList();
        return section(
                "teamSize", teamSection.get("teamSize"),
                "directCount", teamSection.get("directCount"),
                "teamVolumeUsd", teamSection.get("teamVolumeUsd"),
                "members", teamSection.get("records"),
                "commissionRows", commissionRows,
                "commissionUsdt", money(sumAsset(commissionRows, "USDT")),
                "commissionNex", money(sumAsset(commissionRows, "NEX")),
                "sources", List.of("nx_team_member", "nx_wallet_ledger"),
                "sourceStatus", "READY");
    }

    private Map<String, Object> vrank(UserAccountView profile, Map<String, Object> teamSection) {
        return section(
                "currentRank", profile.vRank(),
                "userLevel", profile.userLevel(),
                "teamSize", teamSection.get("teamSize"),
                "directCount", teamSection.get("directCount"),
                "teamVolumeUsd", teamSection.get("teamVolumeUsd"),
                "promotionSignals", section(
                        "kycStatus", profile.kycStatus(),
                        "accountStatus", profile.status(),
                        "teamSize", teamSection.get("teamSize"),
                        "directCount", teamSection.get("directCount"),
                        "teamVolumeUsd", teamSection.get("teamVolumeUsd")),
                "sourceStatus", "READY");
    }

    private Map<String, Object> financial(
            UserAccountView profile,
            Map<String, Object> ledger,
            List<TreasuryLedgerBillView> ledgerRows) {
        List<TreasuryLedgerBillView> exchangeRows = ledgerRows.stream()
                .filter(row -> ledgerKind(row, "EXCHANGE", "SWAP", "CONVERT"))
                .toList();
        List<TreasuryLedgerBillView> stakingRows = ledgerRows.stream()
                .filter(row -> ledgerKind(row, "STAKING", "STAKE", "YIELD"))
                .toList();
        List<TreasuryLedgerBillView> genesisRows = ledgerRows.stream()
                .filter(row -> ledgerKind(row, "GENESIS"))
                .toList();
        return section(
                "wallet", section(
                        "usdt", money(amountValue(ledger.get("currentUsdtBalance"), profile.walletUsdt())),
                        "nex", money(amountValue(ledger.get("currentNexBalance"), profile.walletNex()))),
                "assetSums", ledger.get("sums"),
                "exchangeRows", exchangeRows,
                "stakingLedgerRows", stakingRows,
                "genesisLedgerRows", genesisRows,
                "sources", List.of("nx_wallet_ledger"),
                "sourceStatus", "READY");
    }

    private Map<String, Object> engagement(Map<String, Object> notificationSection, List<AuditLogRecord> audit) {
        List<AuditLogRecord> activityAudit = audit.stream()
                .filter(record -> record.getAction() != null)
                .filter(record -> text(record.getAction()).contains("CHECK")
                        || text(record.getAction()).contains("QUEST")
                        || text(record.getAction()).contains("MILESTONE")
                        || text(record.getAction()).contains("REWARD"))
                .toList();
        return section(
                "notifications", notificationSection.get("records"),
                "activityAudit", activityAudit,
                "checkInRewardAsset", "NEX",
                "sunsetCompatibility", List.of("legacy points records are read-only migration context"),
                "sourceStatus", "READY");
    }

    private Map<String, Object> commerce(List<DeviceOrderView> orders) {
        long activeOrders = orders.stream()
                .filter(order -> List.of("ACTIVE", "PAID", "CREATED").contains(text(order.state())))
                .count();
        return section(
                "total", orders.size(),
                "orders", orders,
                "activeOrderCount", activeOrders,
                "receiptRows", orders,
                "trialRows", List.of(),
                "cartRows", List.of(),
                "sources", List.of("nx_device_order"),
                "sourceStatus", "READY");
    }

    private Map<String, Object> account(
            UserAccountView profile,
            UserSecurityStatusView security,
            List<UserSessionView> sessions,
            Map<String, Object> risk) {
        return section(
                "profile", profile,
                "security", security,
                "sessions", sessions,
                "compliance", section(
                        "kycStatus", profile.kycStatus(),
                        "riskScore", risk.get("effectiveScore"),
                        "riskBand", risk.get("bandLabel"),
                        "flags", risk.get("flags")),
                "sourceStatus", "READY");
    }

    private Map<String, Object> risk(UserAccountView profile, RiskScoreUserView score, List<RiskCaseView> cases) {
        int effectiveScore = score != null && score.effectiveScore() != null
                ? score.effectiveScore()
                : fallbackRiskScore(profile, cases);
        String bandLabel = score != null && score.bandLabel() != null
                ? score.bandLabel()
                : riskBand(effectiveScore);
        List<String> flags = new ArrayList<>();
        if (!"ACTIVE".equalsIgnoreCase(profile.status())) {
            flags.add(profile.status());
        }
        if (!List.of("VERIFIED", "APPROVED", "PASSED").contains(text(profile.kycStatus()))) {
            flags.add("KYC_" + text(profile.kycStatus()));
        }
        long openCases = cases.stream().filter(riskCase -> !"FINALIZED".equalsIgnoreCase(riskCase.status())).count();
        if (openCases > 0) {
            flags.add("OPEN_RISK_CASES:" + openCases);
        }
        return section(
                "score", score,
                "cases", cases,
                "effectiveScore", effectiveScore,
                "bandLabel", bandLabel,
                "flags", flags,
                "openCaseCount", openCases);
    }

    private List<AuditLogRecord> auditLogs(Long userId) {
        AuditLogQueryRequest request = new AuditLogQueryRequest();
        request.setUserId(userId);
        request.setLimit(20);
        return auditLogService.list(request);
    }

    private List<TreasuryLedgerBillView> ledgerRows(Map<String, Object> ledger) {
        Object rows = ledger.get("rows");
        if (!(rows instanceof List<?> values)) {
            return List.of();
        }
        return values.stream()
                .filter(TreasuryLedgerBillView.class::isInstance)
                .map(TreasuryLedgerBillView.class::cast)
                .toList();
    }

    private boolean direction(TreasuryLedgerBillView row, String direction) {
        return text(row.direction()).equals(text(direction));
    }

    private boolean ledgerKind(TreasuryLedgerBillView row, String... keywords) {
        String bizType = text(row.bizType());
        String remark = text(row.remark());
        for (String keyword : keywords) {
            String normalized = text(keyword);
            if (bizType.contains(normalized) || remark.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    private BigDecimal sumAsset(List<TreasuryLedgerBillView> rows, String asset) {
        return rows.stream()
                .filter(row -> text(row.asset()).equals(text(asset)))
                .map(row -> safe(row.amount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal amountValue(Object value, BigDecimal fallback) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return BigDecimal.valueOf(number.doubleValue());
        }
        return safe(fallback);
    }

    private int fallbackRiskScore(UserAccountView profile, List<RiskCaseView> cases) {
        int statusScore = switch (text(profile.status())) {
            case "BANNED", "RESTRICTED" -> 88;
            case "FROZEN" -> 76;
            default -> 20;
        };
        int kycScore = List.of("VERIFIED", "APPROVED", "PASSED").contains(text(profile.kycStatus())) ? 0 : 25;
        int caseScore = cases.stream()
                .map(RiskCaseView::riskScore)
                .filter(score -> score != null)
                .mapToInt(Integer::intValue)
                .max()
                .orElse(0);
        return Math.max(Math.min(100, statusScore + kycScore), caseScore);
    }

    private String riskBand(int score) {
        if (score >= 70) {
            return "高风险";
        }
        if (score >= 40) {
            return "中风险";
        }
        return "低风险";
    }

    private boolean isActiveDevice(DeviceOpsView device) {
        String status = text(device.status());
        return List.of("ONLINE", "OFFLINE", "ACTIVE", "RUNNING").contains(status);
    }

    private boolean isOnlineDevice(DeviceOpsView device) {
        return "ONLINE".equals(text(device.status())) || "ONLINE".equals(text(device.runtimeStatus()));
    }

    private <T> T dataOrNull(ApiResult<T> result) {
        if (result == null || result.getCode() != 0) {
            return null;
        }
        return result.getData();
    }

    private <T> List<T> listOrEmpty(List<T> rows) {
        return rows == null ? List.of() : rows;
    }

    private <T> PageResult<T> pageOrEmpty(PageResult<T> page, long pageNum, long pageSize) {
        if (page == null) {
            return new PageResult<>(0, pageNum, pageSize, List.of());
        }
        if (page.getRecords() == null) {
            return new PageResult<>(page.getTotal(), page.getPageNum(), page.getPageSize(), List.of());
        }
        return page;
    }

    private Map<String, Object> mapOrEmpty(Map<String, Object> value) {
        return value == null ? new LinkedHashMap<>() : value;
    }

    private BigDecimal firstAmount(BigDecimal first, BigDecimal fallback) {
        return first == null ? safe(fallback) : safe(first);
    }

    private BigDecimal money(BigDecimal value) {
        return safe(value).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal safe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private boolean sameText(String left, String right) {
        return text(left).equals(text(right));
    }

    private String text(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String trim(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private static String seedIndexKey(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT).replace("-", "");
    }

    private static Map<String, User360Seed> c1Seeds() {
        Map<String, User360Seed> seeds = new LinkedHashMap<>();
        registerSeed(seeds, seed("usr_84F2", "NX-8821", "Marcus Lee", "4150008821", "marcus.lee.seed@nexion.local",
                "VERIFIED", "L4", "V3", "ACTIVE", "US",
                "8420.00", "12400.00", "1120.00", "4980.00", "12400.00", "3980.00", "26.00", "160.00", 2, 8, 72, true));
        registerSeed(seeds, seed("usr_19C7", "NX-1190", "Aisha Khan", "4150001190", "aisha.khan.seed@nexion.local",
                "VERIFIED", "L5", "V6", "ACTIVE", "AE",
                "24100.00", "86300.00", "800.00", "18120.00", "42000.00", "9800.00", "54.00", "520.00", 5, 31, 18, true));
        registerSeed(seeds, seed("usr_55B1", "NX-5512", "Diego Torres", "4150005512", "diego.torres.seed@nexion.local",
                "PENDING", "L3", "V1", "FROZEN", "MX",
                "1240.00", "2150.00", "420.00", "760.00", "5000.00", "2400.00", "8.00", "55.00", 1, 2, 91, false));
        registerSeed(seeds, seed("usr_02A9", "NX-0029", "Yuki Tanaka", "4150000029", "yuki.tanaka.seed@nexion.local",
                "VERIFIED", "L4", "V2", "ACTIVE", "JP",
                "5630.00", "9800.00", "300.00", "3260.00", "15000.00", "6100.00", "22.00", "140.00", 2, 12, 54, true));
        registerSeed(seeds, seed("usr_77D4", "NX-7741", "Omar Farouk", "4150007741", "omar.farouk.seed@nexion.local",
                "VERIFIED", "L2", "V0", "ACTIVE", "EG",
                "310.00", "540.00", "0.00", "120.00", "900.00", "240.00", "4.00", "20.00", 1, 1, 11, false));
        registerSeed(seeds, seed("usr_31E8", "NX-3188", "Lena Brandt", "4150003188", "lena.brandt.seed@nexion.local",
                "VERIFIED", "L5", "V8", "ACTIVE", "DE",
                "51200.00", "154000.00", "2400.00", "33600.00", "86000.00", "34800.00", "72.00", "760.00", 6, 42, 68, true));
        registerSeed(seeds, seed("usr_90F0", "NX-9001", "Sara Lindqvist", "4150009001", "sara.lindqvist.seed@nexion.local",
                "VERIFIED", "L3", "V1", "ACTIVE", "SE",
                "890.00", "1320.00", "0.00", "420.00", "2400.00", "900.00", "6.00", "35.00", 1, 3, 9, false));
        return Map.copyOf(seeds);
    }

    private static void registerSeed(Map<String, User360Seed> seeds, User360Seed seed) {
        seeds.put(seedIndexKey(seed.lookupKey()), seed);
        seeds.put(seedIndexKey(seed.referralCode()), seed);
    }

    private static User360Seed seed(
            String lookupKey,
            String referralCode,
            String nickname,
            String phone,
            String email,
            String kycStatus,
            String userLevel,
            String vRank,
            String accountStatus,
            String region,
            String walletUsdt,
            String walletNex,
            String pendingWithdraw,
            String lifetimeEarned,
            String depositedUsd,
            String withdrawnUsd,
            String dailyUsdt,
            String dailyNex,
            int deviceCount,
            int teamSize,
            int riskScore,
            boolean twoFactorEnabled) {
        return new User360Seed(
                lookupKey,
                referralCode,
                nickname,
                "+1",
                phone,
                email,
                kycStatus,
                userLevel,
                vRank,
                accountStatus,
                "en-US",
                region,
                decimal(walletUsdt),
                decimal(walletNex),
                decimal(pendingWithdraw),
                decimal(lifetimeEarned),
                decimal(depositedUsd),
                decimal(withdrawnUsd),
                decimal(dailyUsdt),
                decimal(dailyNex),
                deviceCount,
                teamSize,
                riskScore,
                twoFactorEnabled);
    }

    private static BigDecimal decimal(String value) {
        return new BigDecimal(value);
    }

    private Map<String, Object> section(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            detail.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return detail;
    }

    private static final Map<String, User360Seed> C1_SEEDS = c1Seeds();
}

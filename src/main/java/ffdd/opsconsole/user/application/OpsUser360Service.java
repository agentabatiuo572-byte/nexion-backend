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
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import ffdd.opsconsole.shared.security.AdminActorResolver;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import ffdd.opsconsole.treasury.domain.TreasuryLedgerBillView;
import ffdd.opsconsole.user.domain.UserAccountView;
import ffdd.opsconsole.user.domain.UserNotificationView;
import ffdd.opsconsole.user.domain.UserOpsRepository;
import ffdd.opsconsole.user.domain.UserSecurityStatusView;
import ffdd.opsconsole.user.domain.UserSessionView;
import ffdd.opsconsole.user.domain.UserTeamMemberView;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
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
    private final AdminOperatorRoleResolver roleResolver;
    private final EventOutboxService outboxService;

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
        return ApiResult.fail(404, "USER_NOT_FOUND");
    }

    public ApiResult<Map<String, Object>> detail(Long userId) {
        if (userId == null || userId <= 0) {
            return ApiResult.fail(OpsErrorCode.VALIDATION_FAILED.httpStatus(), "USER_ID_REQUIRED");
        }
        String role = text(roleResolver.resolveCode());
        if (!Set.of("SUPER_ADMIN", "FINANCE", "RISK", "GROWTH", "SUPPORT", "AUDITOR").contains(role)) {
            return ApiResult.fail(OpsErrorCode.FORBIDDEN.httpStatus(), "C1_ROLE_SCOPE_UNMAPPED");
        }
        boolean full = Set.of("SUPER_ADMIN", "RISK", "AUDITOR").contains(role);
        boolean canFinance = full || Set.of("FINANCE", "SUPPORT").contains(role);
        boolean canGrowth = full || "GROWTH".equals(role);
        boolean canDevices = full || "SUPPORT".equals(role);
        boolean canCommerce = full || Set.of("GROWTH", "SUPPORT").contains(role);
        ApiResult<UserAccountView> profileResult = userService.profile(userId);
        if (profileResult.getCode() != 0 || profileResult.getData() == null) {
            return ApiResult.fail(profileResult.getCode(), profileResult.getMessage());
        }

        UserAccountView profile = profileResult.getData();
        Read<UserSecurityStatusView> securityRead = full
                ? read(() -> userService.securityStatus(userId))
                : Read.hiddenRead();
        UserSecurityStatusView security = securityRead.value();
        Read<List<UserSessionView>> sessionRead = full
                ? read(() -> userService.sessions(userId, 50))
                : Read.hiddenRead();
        List<Map<String, Object>> sessions = sanitizeSessions(listOrEmpty(sessionRead.value()));
        Read<PageResult<DepositFlowView>> depositRead = canFinance
                ? read(() -> financeService.topupFlows("confirmed", userId, null, 1, 30))
                : Read.hiddenRead();
        PageResult<DepositFlowView> deposits = pageOrEmpty(depositRead.value(), 1, 30);
        Read<PageResult<WithdrawalOrderView>> withdrawalRead = canFinance
                ? read(() -> financeService.withdrawals(new WithdrawalQueryRequest(null, userId, null, 1, 30)))
                : Read.hiddenRead();
        PageResult<WithdrawalOrderView> withdrawals = pageOrEmpty(withdrawalRead.value(), 1, 30);
        Read<Map<String, Object>> ledgerRead = canFinance
                ? read(() -> treasuryService.userLedger(userId))
                : Read.hiddenRead();
        Map<String, Object> ledger = mapOrEmpty(ledgerRead.value());
        Read<List<DeviceOpsView>> deviceRead = canDevices
                ? read(() -> deviceService.userDevices(userId, 200))
                : Read.hiddenRead();
        List<DeviceOpsView> devices = listOrEmpty(deviceRead.value());
        Read<PageResult<DeviceOrderView>> orderRead = canCommerce
                ? read(() -> deviceService.orders(new DeviceOrderQueryRequest(null, profile.userNo(), 1L, 50L)))
                : Read.hiddenRead();
        List<DeviceOrderView> orders = pageOrEmpty(orderRead.value(), 1, 50)
                .getRecords()
                .stream()
                .filter(order -> sameText(order.userNo(), profile.userNo()))
                .toList();
        Read<PageResult<RiskCaseView>> riskCaseRead = full
                ? read(() -> riskService.cases(new RiskCaseQueryRequest(userId, null, null, 1, 20, null)))
                : Read.hiddenRead();
        List<RiskCaseView> riskCases = pageOrEmpty(riskCaseRead.value(), 1, 20)
                .getRecords();
        Read<RiskScoreUserView> riskScoreRead = full
                ? read(() -> riskService.currentScoreUser(profile.userNo()))
                : Read.hiddenRead();
        RiskScoreUserView riskScore = riskScoreRead.value();
        List<AuditLogRecord> audit = full ? safeList(() -> auditLogs(userId)) : List.of();

        Map<String, Object> risk = risk(profile, riskScore, riskCases);
        risk.put("sourceStatus", riskScoreRead.hidden()
                ? "HIDDEN"
                : riskScoreRead.ready() && riskScore != null ? "READY" : "UNAVAILABLE");
        Map<String, Object> deviceSection = devices(devices);
        sourceStatus(deviceSection, deviceRead);
        Map<String, Object> depositSection = deposits(deposits.getRecords());
        sourceStatus(depositSection, depositRead);
        Map<String, Object> withdrawalSection = withdrawals(withdrawals.getRecords());
        sourceStatus(withdrawalSection, withdrawalRead);
        Map<String, Object> teamSection = canGrowth
                ? safeSection(() -> team(userId))
                : hiddenSection();
        Map<String, Object> notificationSection = canGrowth
                ? safeSection(() -> notifications(userId))
                : hiddenSection();
        List<TreasuryLedgerBillView> ledgerRows = ledgerRows(ledger);
        Map<String, Object> earningsSection = earnings(ledgerRows, deviceSection);
        Map<String, Object> referralSection = referral(teamSection, ledgerRows);
        Map<String, Object> vrankSection = vrank(profile, teamSection);
        Map<String, Object> financialSection = financial(profile, ledger, ledgerRows);
        Map<String, Object> engagementSection = engagement(notificationSection, audit);
        Map<String, Object> commerceSection = commerce(orders);
        sourceStatus(commerceSection, orderRead);
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
        trimForRole(response, role, full, canFinance, canGrowth, canDevices, canCommerce);
        List<String> cardsViewed = response.keySet().stream()
                .filter(key -> !Set.of("sources", "redlines", "hub").contains(key))
                .toList();
        auditLogService.recordRequired(AuditLogWriteRequest.builder()
                .action("ADMIN.USER_PROFILE_VIEWED")
                .resourceType("USER_PROFILE")
                .resourceId(profile.userNo())
                .userId(userId)
                .actorType("ADMIN")
                .result("SUCCESS")
                .riskLevel("HIGH")
                .detail(Map.of(
                        "role", role,
                        "cardsViewed", cardsViewed))
                .build());
        outboxService.publish("USER_PROFILE", profile.userNo(), "ADMIN_USER_PROFILE_VIEWED", Map.of(
                "target_user_id", userId,
                "viewer_operator", AdminActorResolver.resolve("SYSTEM"),
                "viewer_role", role,
                "cards_viewed", cardsViewed,
                "occurred_at", Instant.now().toString()));
        return ApiResult.ok(response);
    }

    private <T> Read<T> read(Supplier<ApiResult<T>> supplier) {
        try {
            ApiResult<T> result = supplier.get();
            if (result == null || result.getCode() != 0) {
                return Read.error();
            }
            return Read.ready(result.getData());
        } catch (RuntimeException ex) {
            return Read.error();
        }
    }

    private <T> List<T> safeList(Supplier<List<T>> supplier) {
        try {
            List<T> values = supplier.get();
            return values == null ? List.of() : values;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private Map<String, Object> safeSection(Supplier<Map<String, Object>> supplier) {
        try {
            Map<String, Object> value = supplier.get();
            return value == null ? errorSection() : value;
        } catch (RuntimeException ex) {
            return errorSection();
        }
    }

    private Map<String, Object> errorSection() {
        return section("total", 0, "records", List.of(), "sourceStatus", "ERROR");
    }

    private Map<String, Object> hiddenSection() {
        return section("total", 0, "records", List.of(), "sourceStatus", "HIDDEN");
    }

    private void sourceStatus(Map<String, Object> section, Read<?> read) {
        if (section != null && read != null && !read.ready()) {
            section.put("sourceStatus", read.hidden() ? "HIDDEN" : "ERROR");
        }
    }

    private List<Map<String, Object>> sanitizeSessions(List<UserSessionView> sessions) {
        return sessions.stream()
                .map(value -> section(
                        "deviceName", value.deviceName(),
                        "clientIpMasked", value.clientIpMasked(),
                        "status", value.status(),
                        "issuedAt", value.issuedAt(),
                        "lastActiveAt", value.lastActiveAt(),
                        "expiresAt", value.expiresAt(),
                        "revokedAt", value.revokedAt()))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private void trimForRole(
            Map<String, Object> response,
            String role,
            boolean full,
            boolean canFinance,
            boolean canGrowth,
            boolean canDevices,
            boolean canCommerce) {
        UserAccountView profile = (UserAccountView) response.get("profile");
        Map<String, Object> projectedProfile = section(
                "id", profile.id(),
                "userNo", profile.userNo(),
                "nickname", profile.nickname(),
                "phoneMasked", profile.phoneMasked(),
                "countryCode", profile.countryCode(),
                "status", profile.status(),
                "kycStatus", profile.kycStatus(),
                "userLevel", profile.userLevel(),
                "vRank", profile.vRank(),
                "registeredAt", profile.registeredAt(),
                "lastLoginAt", profile.lastLoginAt());
        if (canFinance) {
            projectedProfile.put("walletUsdt", profile.walletUsdt());
            projectedProfile.put("walletNex", profile.walletNex());
        }
        if (full) {
            projectedProfile.put("twoFactorEnabled", profile.twoFactorEnabled());
            projectedProfile.put("riskScore", profile.riskScore());
            projectedProfile.put("riskBand", profile.riskBand());
            projectedProfile.put("deviceCount", profile.deviceCount());
            projectedProfile.put("activeDeviceCount", profile.activeDeviceCount());
        } else if ("SUPPORT".equals(role)) {
            projectedProfile.put("riskBand", profile.riskBand());
        }
        response.put("profile", projectedProfile);

        if (!full) {
            response.remove("security");
            response.remove("sessions");
            response.remove("audit");
            response.put("account", section(
                    "status", profile.status(),
                    "kycStatus", profile.kycStatus(),
                    "riskBand", "SUPPORT".equals(role) ? profile.riskBand() : null,
                    "sourceStatus", "READY"));
            if ("SUPPORT".equals(role)) {
                response.put("risk", section("bandLabel", profile.riskBand(), "sourceStatus", "READY"));
            } else {
                response.remove("risk");
            }
        }
        if (!canFinance) {
            remove(response, "deposits", "withdrawals", "ledger", "financial");
        }
        if (!canGrowth) {
            remove(response, "team", "notifications", "referral", "vrank", "engagement");
        }
        if (!canDevices) {
            response.remove("devices");
        }
        if (!canCommerce) {
            response.remove("orders");
            response.remove("commerce");
        }
        if (!full) {
            response.remove("earnings");
        }

        Object summaryValue = response.get("summary");
        if (summaryValue instanceof Map<?, ?> rawSummary) {
            Map<String, Object> summary = (Map<String, Object>) rawSummary;
            if (!full) {
                remove(summary, "twoFactorEnabled", "locked", "passwordResetRequired", "activeSessionCount", "riskScore");
            }
            if (!canFinance) {
                remove(summary, "walletUsdt", "walletNex", "depositedUsd", "withdrawnUsd", "withdrawRequestedUsd");
            }
            if (!canDevices) {
                remove(summary, "deviceCount", "activeDeviceCount", "onlineDeviceCount", "dailyUsdt", "dailyNex");
            }
            if (!canGrowth) {
                remove(summary, "teamSize", "directTeamSize", "teamVolumeUsd", "unreadNotificationCount");
            }
        }

        Map<String, Object> hub = new LinkedHashMap<>();
        Map<String, String> hubKeys = Map.ofEntries(
                Map.entry("deposit", "deposits"),
                Map.entry("withdrawal", "withdrawals"),
                Map.entry("devices", "devices"),
                Map.entry("earnings", "earnings"),
                Map.entry("referral", "referral"),
                Map.entry("vrank", "vrank"),
                Map.entry("financial", "financial"),
                Map.entry("engagement", "engagement"),
                Map.entry("commerce", "commerce"),
                Map.entry("account", "account"),
                Map.entry("notification", "notifications"));
        for (Map.Entry<String, String> entry : hubKeys.entrySet()) {
            String key = entry.getKey();
            String responseKey = entry.getValue();
            if (response.containsKey(responseKey)) {
                hub.put(key, response.get(responseKey));
            }
        }
        response.put("hub", hub);
    }

    private void remove(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            map.remove(key);
        }
    }

    private record Read<T>(T value, boolean ready, boolean hidden) {
        private static <T> Read<T> ready(T value) {
            return new Read<>(value, true, false);
        }

        private static <T> Read<T> error() {
            return new Read<>(null, false, false);
        }

        private static <T> Read<T> hiddenRead() {
            return new Read<>(null, false, true);
        }
    }

    private Map<String, Object> summary(
            UserAccountView profile,
            UserSecurityStatusView security,
            List<Map<String, Object>> sessions,
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
                "activeSessionCount", (int) sessions.stream().filter(session -> "ACTIVE".equalsIgnoreCase(String.valueOf(session.get("status")))).count(),
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
            List<Map<String, Object>> sessions,
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
        Integer effectiveScore = score == null ? null : score.effectiveScore();
        String bandLabel = score == null ? null : score.bandLabel();
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

    private Map<String, Object> section(Object... pairs) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            detail.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return detail;
    }
}

package ffdd.opsconsole.user.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.common.api.OpsErrorCode;
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
import ffdd.opsconsole.risk.domain.RiskScoreContributionView;
import ffdd.opsconsole.risk.domain.RiskScoreUserView;
import ffdd.opsconsole.risk.dto.RiskCaseQueryRequest;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.api.PageResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
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
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class OpsUser360ServiceTest {
    private final OpsUserService userService = mock(OpsUserService.class);
    private final OpsFinanceService financeService = mock(OpsFinanceService.class);
    private final OpsTreasuryService treasuryService = mock(OpsTreasuryService.class);
    private final OpsDeviceService deviceService = mock(OpsDeviceService.class);
    private final OpsRiskService riskService = mock(OpsRiskService.class);
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final UserOpsRepository userRepository = mock(UserOpsRepository.class);
    private final OpsUser360Service service = new OpsUser360Service(
            userService,
            financeService,
            treasuryService,
            deviceService,
            riskService,
            auditLogService,
            userRepository);

    @Test
    void detailRejectsInvalidUserIdWith422() {
        ApiResult<Map<String, Object>> result = service.detail(0L);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.VALIDATION_FAILED.httpStatus());
        assertThat(result.getMessage()).isEqualTo("USER_ID_REQUIRED");
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailByLookupKeySeedsMissingC1UserThenAggregatesByDatabaseId() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 24, 12, 0);
        UserAccountView profile = new UserAccountView(
                88421L,
                "U00088421",
                "Marcus Lee",
                "415****8821",
                "+1",
                "ACTIVE",
                "VERIFIED",
                "L4",
                "V3",
                true,
                new BigDecimal("8420.00"),
                new BigDecimal("12400.00"),
                72,
                "高风险",
                2L,
                2L,
                now.minusDays(104),
                now.minusMinutes(15));

        when(userRepository.findUserIdByLookupKey("usr_84F2")).thenReturn(Optional.empty());
        when(userRepository.findUserIdByLookupKey("NX-8821")).thenReturn(Optional.empty(), Optional.of(88421L));
        when(userService.profile(88421L)).thenReturn(ApiResult.ok(profile));
        when(riskService.scoreUser("U00088421")).thenReturn(ApiResult.ok(new RiskScoreUserView(
                "U00088421",
                72,
                72,
                false,
                "高风险",
                "danger",
                "c1-seed",
                "刚刚",
                List.of())));
        when(userRepository.teamMembers(88421L, 20)).thenReturn(List.of());
        when(userRepository.notifications(88421L, 20)).thenReturn(List.of());
        when(auditLogService.list(org.mockito.ArgumentMatchers.any(AuditLogQueryRequest.class))).thenReturn(List.of());

        ApiResult<Map<String, Object>> result = service.detail("usr_84F2");

        assertThat(result.getCode()).isZero();
        Map<String, Object> summary = (Map<String, Object>) result.getData().get("summary");
        assertThat(summary)
                .containsEntry("userId", 88421L)
                .containsEntry("userNo", "U00088421")
                .containsEntry("riskScore", 72);
        verify(userRepository).upsertUser360Seed(argThat((User360Seed seed) ->
                "usr_84F2".equals(seed.lookupKey())
                        && "NX-8821".equals(seed.referralCode())
                        && "Marcus Lee".equals(seed.nickname())));
        verify(userService).profile(88421L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailByLookupKeyReusesExistingSeedUserWithoutReinserting() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 24, 12, 0);
        UserAccountView profile = new UserAccountView(
                88421L,
                "U00088421",
                "Marcus Lee",
                "415****8821",
                "+1",
                "ACTIVE",
                "VERIFIED",
                "L4",
                "V3",
                true,
                new BigDecimal("8420.00"),
                new BigDecimal("12400.00"),
                72,
                "高风险",
                2L,
                2L,
                now.minusDays(104),
                now.minusMinutes(15));

        when(userRepository.findUserIdByLookupKey("usr_84F2")).thenReturn(Optional.empty());
        when(userRepository.findUserIdByLookupKey("NX-8821")).thenReturn(Optional.of(88421L));
        when(userRepository.countTeamMembers(88421L)).thenReturn(8L);
        when(userService.profile(88421L)).thenReturn(ApiResult.ok(profile));
        when(riskService.scoreUser("U00088421")).thenReturn(ApiResult.ok(new RiskScoreUserView(
                "U00088421",
                72,
                72,
                false,
                "高风险",
                "danger",
                "c1-seed",
                "刚刚",
                List.of())));
        when(userRepository.teamMembers(88421L, 20)).thenReturn(List.of());
        when(userRepository.notifications(88421L, 20)).thenReturn(List.of());
        when(auditLogService.list(org.mockito.ArgumentMatchers.any(AuditLogQueryRequest.class))).thenReturn(List.of());

        ApiResult<Map<String, Object>> result = service.detail("usr_84F2");

        assertThat(result.getCode()).isZero();
        Map<String, Object> summary = (Map<String, Object>) result.getData().get("summary");
        assertThat(summary)
                .containsEntry("userId", 88421L)
                .containsEntry("teamSize", 8L);
        verify(userRepository, never()).upsertUser360Seed(any(User360Seed.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void detailAggregatesRealDomainViewsWithoutMockData() {
        LocalDateTime now = LocalDateTime.of(2026, 6, 17, 10, 0);
        UserAccountView profile = new UserAccountView(
                2231L,
                "U00002231",
                "Alice",
                "138****2231",
                "86",
                "ACTIVE",
                "VERIFIED",
                "L3",
                "V2",
                true,
                new BigDecimal("108.50"),
                new BigDecimal("3500"),
                61,
                "高风险",
                1L,
                1L,
                now.minusDays(90),
                now.minusHours(2));
        UserSecurityStatusView security = new UserSecurityStatusView(2231L, true, 1, false, false, 5, 30);
        UserSessionView session = new UserSessionView(
                2231L,
                "rt-1",
                "Chrome / Windows",
                "10.0.0.*",
                "ACTIVE",
                now.minusHours(5),
                now.plusDays(7),
                null);
        DepositFlowView deposit = new DepositFlowView(
                1L,
                2231L,
                "DEP-1",
                "USDT-TRC20",
                "USDT",
                new BigDecimal("200"),
                new BigDecimal("199.50"),
                "chain-proof",
                "CONFIRMED",
                "已确认",
                now.minusDays(5),
                now.minusDays(5).plusMinutes(10),
                now.minusDays(5).plusMinutes(11));
        WithdrawalOrderView withdrawal = new WithdrawalOrderView(
                10L,
                2231L,
                "WD-1",
                "USDT",
                "TRC20",
                new BigDecimal("50"),
                new BigDecimal("1"),
                "T***",
                99L,
                "0xabc",
                "SUCCESS",
                now.minusDays(2),
                now.minusDays(2).plusMinutes(5),
                null,
                null,
                1,
                null,
                null,
                null,
                now.minusDays(2),
                now.minusDays(2).plusMinutes(5));
        TreasuryLedgerBillView ledger = new TreasuryLedgerBillView(
                100L,
                2231L,
                "DEP-1",
                "DEPOSIT",
                "USDT",
                "IN",
                new BigDecimal("199.50"),
                new BigDecimal("108.50"),
                "SUCCESS",
                "充值入账",
                now.minusDays(5),
                now.minusDays(5));
        DeviceOpsView ownDevice = new DeviceOpsView(
                700L,
                2231L,
                "U00002231",
                "Stella Miner",
                "MINER-1",
                "A100 节点",
                "Pro",
                "a100-pro",
                "ONLINE",
                "SG-1",
                new BigDecimal("92"),
                new BigDecimal("4.25"),
                new BigDecimal("25"),
                now.minusMinutes(5),
                now.minusDays(20),
                null,
                0,
                "ONLINE",
                new BigDecimal("86"),
                new BigDecimal("72"),
                new BigDecimal("240"),
                null,
                "TASK-1",
                now.minusMinutes(1));
        DeviceOpsView otherDevice = new DeviceOpsView(
                701L,
                9999L,
                "U00009999",
                "Other Miner",
                "MINER-OTHER",
                "Other",
                "Lite",
                "lite",
                "ONLINE",
                "SG-1",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                now,
                now,
                null,
                0,
                "ONLINE",
                BigDecimal.ONE,
                BigDecimal.ONE,
                BigDecimal.ONE,
                null,
                null,
                now);
        DeviceOrderView order = new DeviceOrderView(
                "ORD-1",
                "U00002231",
                "a100-pro",
                "A100 节点",
                new BigDecimal("129"),
                "active",
                "SG-1",
                "20 天",
                now.minusDays(20),
                now.minusDays(1));
        RiskCaseView riskCase = new RiskCaseView(
                "RISK-1",
                2231L,
                "WITHDRAWAL",
                "WD-1",
                "CN",
                "L3",
                "HOLD",
                "大额提现",
                72,
                "WR-01",
                "REVIEWING",
                null,
                null,
                now.minusDays(1));
        RiskScoreUserView score = new RiskScoreUserView(
                "U00002231",
                61,
                72,
                false,
                "高风险",
                "danger",
                "v2026.06",
                "刚刚",
                List.of(new RiskScoreContributionView("提现", "WR-01", 25)));
        UserTeamMemberView teamMember = new UserTeamMemberView(
                3001L,
                "U00003001",
                "Bob",
                "V1",
                1,
                new BigDecimal("18800.00"),
                now.minusDays(35));
        UserNotificationView notification = new UserNotificationView(
                "seed:notice-2231",
                "EARNING",
                "收益完成",
                "+0.018 USDT 已入账",
                false,
                "PENDING",
                1,
                now.plusMinutes(5),
                null,
                null,
                now.minusMinutes(10));
        AuditLogRecord audit = new AuditLogRecord();
        audit.setAction("C2_USER_STATUS_CHANGED");
        audit.setUserId(2231L);
        audit.setCreatedAt(now);

        when(userService.profile(2231L)).thenReturn(ApiResult.ok(profile));
        when(userService.securityStatus(2231L)).thenReturn(ApiResult.ok(security));
        when(userService.sessions(2231L, 50)).thenReturn(ApiResult.ok(List.of(session)));
        when(financeService.topupFlows("confirmed", 2231L, null, 1, 30))
                .thenReturn(ApiResult.ok(new PageResult<>(1, 1, 30, List.of(deposit))));
        when(financeService.withdrawals(new WithdrawalQueryRequest(null, 2231L, null, 1, 30)))
                .thenReturn(ApiResult.ok(new PageResult<>(1, 1, 30, List.of(withdrawal))));
        when(treasuryService.userLedger(2231L)).thenReturn(ApiResult.ok(Map.of(
                "rows", List.of(ledger),
                "currentUsdtBalance", new BigDecimal("108.50"),
                "currentNexBalance", new BigDecimal("3500"),
                "sources", List.of("nx_wallet_ledger"))));
        when(deviceService.userDevices(2231L, 200))
                .thenReturn(ApiResult.ok(List.of(ownDevice)));
        when(deviceService.orders(new DeviceOrderQueryRequest(null, "U00002231", 1L, 50L)))
                .thenReturn(ApiResult.ok(new PageResult<>(1, 1, 50, List.of(order))));
        when(riskService.cases(new RiskCaseQueryRequest(2231L, null, null, 1, 20, null)))
                .thenReturn(ApiResult.ok(new PageResult<>(1, 1, 20, List.of(riskCase))));
        when(riskService.scoreUser("U00002231")).thenReturn(ApiResult.ok(score));
        when(userRepository.teamMembers(2231L, 20)).thenReturn(List.of(teamMember));
        when(userRepository.countTeamMembers(2231L)).thenReturn(3L);
        when(userRepository.countDirectTeamMembers(2231L)).thenReturn(1L);
        when(userRepository.sumTeamVolume(2231L)).thenReturn(new BigDecimal("18800.00"));
        when(userRepository.notifications(2231L, 20)).thenReturn(List.of(notification));
        when(userRepository.countUnreadNotifications(2231L)).thenReturn(1L);
        when(userRepository.countPendingNotifications(2231L)).thenReturn(1L);
        when(userRepository.countFailedNotifications(2231L)).thenReturn(0L);
        when(auditLogService.list(org.mockito.ArgumentMatchers.any(AuditLogQueryRequest.class))).thenReturn(List.of(audit));

        ApiResult<Map<String, Object>> result = service.detail(2231L);

        assertThat(result.getCode()).isZero();
        Map<String, Object> data = result.getData();
        Map<String, Object> summary = (Map<String, Object>) data.get("summary");
        Map<String, Object> devices = (Map<String, Object>) data.get("devices");
        Map<String, Object> deposits = (Map<String, Object>) data.get("deposits");
        Map<String, Object> risk = (Map<String, Object>) data.get("risk");
        Map<String, Object> team = (Map<String, Object>) data.get("team");
        Map<String, Object> notifications = (Map<String, Object>) data.get("notifications");
        Map<String, Object> earnings = (Map<String, Object>) data.get("earnings");
        Map<String, Object> referral = (Map<String, Object>) data.get("referral");
        Map<String, Object> vrank = (Map<String, Object>) data.get("vrank");
        Map<String, Object> financial = (Map<String, Object>) data.get("financial");
        Map<String, Object> engagement = (Map<String, Object>) data.get("engagement");
        Map<String, Object> commerce = (Map<String, Object>) data.get("commerce");
        Map<String, Object> account = (Map<String, Object>) data.get("account");
        Map<String, Object> hub = (Map<String, Object>) data.get("hub");

        assertThat(summary)
                .containsEntry("userId", 2231L)
                .containsEntry("deviceCount", 1)
                .containsEntry("activeSessionCount", 1)
                .containsEntry("riskScore", 72)
                .containsEntry("teamSize", 3L);
        assertThat(summary.get("depositedUsd")).isEqualTo(new BigDecimal("199.50"));
        assertThat(summary.get("withdrawnUsd")).isEqualTo(new BigDecimal("50.00"));
        assertThat(summary.get("dailyUsdt")).isEqualTo(new BigDecimal("4.25"));
        assertThat(team)
                .containsEntry("directCount", 1L)
                .containsEntry("teamSize", 3L)
                .containsEntry("teamVolumeUsd", new BigDecimal("18800.00"))
                .containsEntry("sourceStatus", "READY");
        assertThat((List<UserTeamMemberView>) team.get("records")).containsExactly(teamMember);
        assertThat(notifications)
                .containsEntry("total", 1)
                .containsEntry("unreadCount", 1L)
                .containsEntry("pendingPushCount", 1L)
                .containsEntry("failedPushCount", 0L)
                .containsEntry("sourceStatus", "READY");
        assertThat((List<UserNotificationView>) notifications.get("records")).containsExactly(notification);
        assertThat((List<DeviceOpsView>) devices.get("records")).containsExactly(ownDevice);
        assertThat((List<DepositFlowView>) deposits.get("records")).containsExactly(deposit);
        assertThat(risk).containsEntry("bandLabel", "高风险");
        assertThat(earnings)
                .containsEntry("deviceDailyUsdt", new BigDecimal("4.25"))
                .containsEntry("deviceDailyNex", new BigDecimal("25.00"))
                .containsEntry("sourceStatus", "READY");
        assertThat(referral)
                .containsEntry("teamSize", 3L)
                .containsEntry("directCount", 1L)
                .containsEntry("sourceStatus", "READY");
        assertThat(vrank)
                .containsEntry("currentRank", "V2")
                .containsEntry("userLevel", "L3")
                .containsEntry("sourceStatus", "READY");
        assertThat((Map<String, Object>) financial.get("wallet"))
                .containsEntry("usdt", new BigDecimal("108.50"))
                .containsEntry("nex", new BigDecimal("3500.00"));
        assertThat(financial).containsEntry("sourceStatus", "READY");
        assertThat(engagement)
                .containsEntry("checkInRewardAsset", "NEX")
                .containsEntry("sourceStatus", "READY");
        assertThat(commerce)
                .containsEntry("total", 1)
                .containsEntry("activeOrderCount", 1L)
                .containsEntry("sourceStatus", "READY");
        assertThat(account).containsEntry("sourceStatus", "READY");
        assertThat(hub.keySet())
                .contains("deposit", "withdrawal", "devices", "earnings", "referral", "vrank",
                        "financial", "engagement", "commerce", "account", "notification");
        assertThat((List<String>) data.get("sources"))
                .contains("nx_user", "nx_user_session", "nx_deposit_order", "nx_withdrawal_order", "nx_wallet_ledger", "nx_user_device", "nx_device_order", "nx_risk_decision", "nx_team_member", "nx_notification", "nx_audit_log");
        assertThat(data.toString()).doesNotContain("mock").doesNotContain("localStorage");
    }
}

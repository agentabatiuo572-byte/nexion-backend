package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;

import ffdd.opsconsole.growth.domain.AppReferralRewardView;
import ffdd.opsconsole.growth.domain.ReferralRewardPublicConfigView;
import ffdd.opsconsole.growth.mapper.ReferralRewardMapper;
import ffdd.opsconsole.shared.exception.BizException;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppReferralRewardServiceTest {
    private final ReferralRewardMapper mapper = mock(ReferralRewardMapper.class);
    private final OpsReferralRewardService config = mock(OpsReferralRewardService.class);
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-08-11T13:00:00Z"), ZoneId.of("Asia/Shanghai"));
    private final MockEnvironment environment = productionEnvironment();
    private final AppReferralRewardService service = new AppReferralRewardService(mapper, config, clock, environment);

    private static MockEnvironment productionEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    @BeforeEach
    void setUp() {
        environment.setActiveProfiles("prod");
        environment.setProperty("NEXION_ACCEPTANCE_RUN_ID", "");
        when(config.publicConfig()).thenReturn(new ReferralRewardPublicConfigView(
                new ReferralRewardPublicConfigView.WelcomeGift("risk_bucket", BigDecimal.ZERO, BigDecimal.ZERO),
                new ReferralRewardPublicConfigView.InviterReward(new BigDecimal("20.000000")),
                7, BigDecimal.ONE, BigDecimal.ONE, Instant.parse("2026-07-17T00:00:00Z"), List.of()));
        when(mapper.appReferralAccount(11L)).thenReturn(
                new ReferralRewardMapper.AppReferralAccount("NEX-ABC", 0, new BigDecimal("120")));
        when(mapper.appInvitedCount(org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class), org.mockito.ArgumentMatchers.eq("PRODUCTION")))
                .thenReturn(2L);
        when(mapper.appPendingCount(org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class), org.mockito.ArgumentMatchers.eq("PRODUCTION")))
                .thenReturn(1L);
        when(mapper.appPositiveSettlementCount(11L, "PRODUCTION")).thenReturn(1L);
        when(mapper.appSettlementCount(11L, "PRODUCTION")).thenReturn(1L);
        when(mapper.appVerifiedRewardSummary(11L, "H8_REFERRAL", "PRODUCTION"))
                .thenReturn(new ReferralRewardMapper.AppReferralLedgerSummary(1L, new BigDecimal("20")));
        when(mapper.appRecentVerifiedRewards(11L, "H8_REFERRAL", "PRODUCTION", 20)).thenReturn(List.of(
                new ReferralRewardMapper.AppReferralLedgerRow("REF-1", new BigDecimal("20"), "SUCCESS",
                        new BigDecimal("120"), "withdrawable", "PRODUCTION",
                        LocalDateTime.of(2026, 8, 11, 1, 2))));
    }

    @Test
    void returnsOnlyCurrentUsersVerifiedLedgerFactsAndClampsLimit() {
        AppReferralRewardView view = service.snapshot(11L, 999).getData();

        assertThat(view.referralCode()).isEqualTo("NEX-ABC");
        assertThat(view.invitedCount()).isEqualTo(2);
        assertThat(view.pendingCount()).isEqualTo(1);
        assertThat(view.settledCount()).isEqualTo(1);
        assertThat(view.lifetimeInviterNex()).isEqualByComparingTo("20");
        assertThat(view.walletNexAvailable()).isEqualByComparingTo("120");
        assertThat(view.limit()).isEqualTo(20);
        assertThat(view.source()).isEqualTo("ledger");
        assertThat(view.sourceEnvironment()).isEqualTo("PRODUCTION");
        assertThat(view.runId()).isNull();
        assertThat(view.recentRewards()).singleElement().satisfies(row -> {
            assertThat(row.amountNex()).isEqualByComparingTo("20");
            assertThat(row.balanceAfter()).isEqualByComparingTo("120");
            assertThat(row.ledgerStatus()).isEqualTo("SUCCESS");
        });
        verify(mapper).appRecentVerifiedRewards(11L, "H8_REFERRAL", "PRODUCTION", 20);
    }

    @Test
    void mapsShanghaiDatabaseLocalSettlementTimeToTheCorrectInstantAcrossUtcDayBoundary() {
        LocalDateTime databaseLocalTime = LocalDateTime.of(2026, 8, 11, 20, 31, 19);
        when(mapper.appRecentVerifiedRewards(11L, "H8_REFERRAL", "PRODUCTION", 10)).thenReturn(List.of(
                new ReferralRewardMapper.AppReferralLedgerRow("REF-DAY-BOUNDARY", new BigDecimal("20"), "SUCCESS",
                        new BigDecimal("120"), "withdrawable", "PRODUCTION", databaseLocalTime)));

        AppReferralRewardView view = service.snapshot(11L, 10).getData();

        assertThat(view.refreshedAt()).isEqualTo(clock.instant());
        assertThat(view.recentRewards()).singleElement().satisfies(row ->
                assertThat(row.settledAt()).isEqualTo(Instant.parse("2026-08-11T12:31:19Z")));
    }

    @Test
    void failsClosedWhenSettlementHasNoMatchingWalletAndReleaseFacts() {
        when(mapper.appVerifiedRewardSummary(11L, "H8_REFERRAL", "PRODUCTION"))
                .thenReturn(new ReferralRewardMapper.AppReferralLedgerSummary(0L, BigDecimal.ZERO));

        assertThatThrownBy(() -> service.snapshot(11L, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_REWARD_LEDGER_INCONSISTENT");
    }

    @Test
    void sandboxUsesExplicitMockSourceAndCannotReadProductionFacts() {
        environment.setActiveProfiles("dev");
        environment.setProperty("NEXION_ACCEPTANCE_RUN_ID", "RUN-APP-20260812");
        when(mapper.appReferralAccount(11L)).thenReturn(
                new ReferralRewardMapper.AppReferralAccount("NEX-SBX", 1, new BigDecimal("999")));
        when(mapper.appSandboxInvitedCount(org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class), org.mockito.ArgumentMatchers.eq("RUN-APP-20260812")))
                .thenReturn(0L);
        when(mapper.appSandboxPendingCount(org.mockito.ArgumentMatchers.eq(11L),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class), org.mockito.ArgumentMatchers.eq("RUN-APP-20260812")))
                .thenReturn(0L);
        when(mapper.appSandboxPositiveSettlementCount(11L, "RUN-APP-20260812")).thenReturn(1L);
        when(mapper.appSandboxSettlementCount(11L, "RUN-APP-20260812")).thenReturn(1L);
        when(mapper.appVerifiedSandboxRewardSummary(11L, "RUN-APP-20260812"))
                .thenReturn(new ReferralRewardMapper.AppReferralLedgerSummary(1L, new BigDecimal("20")));
        when(mapper.appRecentVerifiedSandboxRewards(11L, "RUN-APP-20260812", 10)).thenReturn(List.of(
                new ReferralRewardMapper.AppReferralLedgerRow("SBX-REF-1", new BigDecimal("20"), "SUCCESS",
                        new BigDecimal("20"), "withdrawable", "SANDBOX",
                        LocalDateTime.of(2026, 8, 11, 1, 2))));

        AppReferralRewardView view = service.snapshot(11L, 10).getData();

        assertThat(view.source()).isEqualTo("mock");
        assertThat(view.sourceEnvironment()).isEqualTo("SANDBOX");
        assertThat(view.runId()).isEqualTo("RUN-APP-20260812");
        assertThat(view.lifetimeInviterNex()).isEqualByComparingTo("20");
        assertThat(view.walletNexAvailable()).isEqualByComparingTo("20");
        assertThat(view.recentRewards()).singleElement().satisfies(row ->
                assertThat(row.balanceAfter()).isEqualByComparingTo("20"));
        verify(mapper).appRecentVerifiedSandboxRewards(11L, "RUN-APP-20260812", 10);
    }

    @Test
    void sandboxFailsClosedWithoutAnIsolatedProfileOrCurrentRunId() {
        when(mapper.appReferralAccount(11L)).thenReturn(
                new ReferralRewardMapper.AppReferralAccount("NEX-SBX", 1, BigDecimal.ZERO));

        assertThatThrownBy(() -> service.snapshot(11L, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_PRODUCTION_SANDBOX_ACCOUNT_FORBIDDEN");
        environment.setActiveProfiles("dev");
        assertThatThrownBy(() -> service.snapshot(11L, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_SANDBOX_RUN_ID_REQUIRED");
    }

    @Test
    void strictSandboxProfileRejectsProductionAccountBeforeAnyProductionProjectionRead() {
        environment.setActiveProfiles("dev");
        environment.setProperty("NEXION_ACCEPTANCE_RUN_ID", "RUN-APP-20260812");
        when(mapper.appReferralAccount(11L)).thenReturn(
                new ReferralRewardMapper.AppReferralAccount("NEX-PROD", 0, new BigDecimal("120")));

        assertThatThrownBy(() -> service.snapshot(11L, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_SANDBOX_ACCOUNT_REQUIRED");

        verify(mapper, never()).appInvitedCount(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyString());
        verify(mapper, never()).appVerifiedRewardSummary(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void productionAndDefaultProfilesRejectSandboxAccountBeforeAnySandboxProjectionRead() {
        when(mapper.appReferralAccount(11L)).thenReturn(
                new ReferralRewardMapper.AppReferralAccount("NEX-SBX", 1, BigDecimal.ZERO));

        assertThatThrownBy(() -> service.snapshot(11L, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_PRODUCTION_SANDBOX_ACCOUNT_FORBIDDEN");
        environment.setActiveProfiles();
        assertThatThrownBy(() -> service.snapshot(11L, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_PRODUCTION_SANDBOX_ACCOUNT_FORBIDDEN");

        verify(mapper, never()).appSandboxInvitedCount(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyString());
        verify(mapper, never()).appVerifiedSandboxRewardSummary(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void missingOrOppositeEnvironmentWalletFailsClosedBeforeProjection() {
        when(mapper.appReferralAccount(11L)).thenReturn(null);

        assertThatThrownBy(() -> service.snapshot(11L, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_REWARD_ACCOUNT_ENVIRONMENT_INCONSISTENT");
    }

    @Test
    void unknownOrMixedRuntimeFailsClosedBeforeProductionProjectionRead() {
        environment.setActiveProfiles("dev", "prod");

        assertThatThrownBy(() -> service.snapshot(11L, 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("H8_REWARD_RUNTIME_PROFILE_UNSUPPORTED");
        verify(mapper, never()).appInvitedCount(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(LocalDateTime.class), org.mockito.ArgumentMatchers.anyString());
        verify(mapper, never()).appVerifiedRewardSummary(org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    }
}

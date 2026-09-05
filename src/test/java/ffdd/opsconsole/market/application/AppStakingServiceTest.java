package ffdd.opsconsole.market.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.facade.RiskDisclosureGateFacade;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.market.mapper.AppStakingMapper;
import ffdd.opsconsole.market.mapper.MarketSandboxMapper;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.outbox.EventOutboxService;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import org.springframework.mock.env.MockEnvironment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class AppStakingServiceTest {
    private final AppStakingMapper mapper = mock(AppStakingMapper.class);
    private final RiskDisclosureGateFacade disclosureGate = mock(RiskDisclosureGateFacade.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final EventOutboxService outbox = mock(EventOutboxService.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final EarningsReleaseService earningsReleaseService = mock(EarningsReleaseService.class);
    private final MarketSandboxMapper sandboxMapper = mock(MarketSandboxMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-22T03:00:00Z"), ZoneOffset.UTC);
    private final MockEnvironment environment = new MockEnvironment();
    private final AppStakingService service = new AppStakingService(
            mapper, disclosureGate, config, idempotency, outbox, audit, earningsReleaseService, clock, environment,
            sandboxMapper);

    @BeforeEach
    @SuppressWarnings({"rawtypes", "unchecked"})
    void setUp() {
        environment.setActiveProfiles("dev");
        when(config.activeValue(anyString())).thenReturn(Optional.empty());
        when(mapper.controlValue("killswitch.staking")).thenReturn("enabled");
        when(mapper.listCanonicalProducts()).thenReturn(List.of(product()));
        when(mapper.lockProductByTier("usdt30d")).thenReturn(product());
        when(mapper.lockActiveUser(42L)).thenReturn(42L);
        when(mapper.lockWalletBalance(42L)).thenReturn(new BigDecimal("1000"));
        when(mapper.walletBalance(42L)).thenReturn(new BigDecimal("900"));
        when(mapper.listUserPositions(42L, 0L, 50)).thenReturn(List.of());
        when(mapper.debitWallet(42L, new BigDecimal("100.000000"))).thenReturn(1);
        when(mapper.insertPosition(any())).thenReturn(1);
        when(mapper.insertLedger(any())).thenReturn(1);
        when(mapper.userAttribution(42L)).thenReturn(new AppStakingMapper.UserAttribution("P1", 3, "2026-W30"));
        when(disclosureGate.checkUserGate(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq("staking"), anyString())).thenReturn(ApiResult.ok(null));
        when(idempotency.execute(anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> ((Supplier) invocation.getArgument(4)).get());
        when(sandboxMapper.insertAccountIfAbsent(anyString(), anyString(), any())).thenReturn(1);
        when(sandboxMapper.lockAccount(anyString(), anyString(), any())).thenReturn(
                new MarketSandboxMapper.AccountRow(new BigDecimal("1000.000000"), 0L));
        when(sandboxMapper.account(anyString(), anyString(), any())).thenReturn(
                new MarketSandboxMapper.AccountRow(new BigDecimal("900.000000"), 1L));
        when(sandboxMapper.listPositions(anyString(), anyString(), any())).thenReturn(List.of());
        when(sandboxMapper.findIdempotency(anyString(), anyString(), any(), anyString(), anyString())).thenReturn(null);
        when(sandboxMapper.insertIdempotency(any())).thenReturn(1);
        when(sandboxMapper.updateWallet(anyString(), anyString(), any(), any(), any())).thenReturn(1);
        when(sandboxMapper.insertPosition(any())).thenReturn(1);
    }

    @Test
    void publicPoolsUseActiveProductAsTheDefaultSaleTruth() {
        ApiResult<java.util.Map<String, Object>> result = service.pools();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION").containsEntry("runId", "");
        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> pools = (List<java.util.Map<String, Object>>) result.getData().get("pools");
        assertThat(pools).singleElement().satisfies(pool -> assertThat(pool)
                .containsEntry("tierKey", "usdt30d")
                .containsEntry("enabled", true)
                .containsEntry("killed", false));
    }

    @Test
    void missingStakingKillSwitchFailsClosed() {
        when(mapper.controlValue("killswitch.staking")).thenReturn(null);
        when(mapper.controlValue("J.killswitch.staking")).thenReturn(null);

        ApiResult<java.util.Map<String, Object>> result = service.pools();

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> pools = (List<java.util.Map<String, Object>>) result.getData().get("pools");
        assertThat(pools).singleElement().satisfies(pool -> assertThat(pool).containsEntry("enabled", false));
    }

    @Test
    void localSandboxUsesRunScopedPersistentStateWithoutCanonicalWrites() {
        environment.setActiveProfiles("test");
        environment.setProperty("nexion.commerce.acceptance-run-id", "RUN-STAKING-TEST-001");

        ApiResult<java.util.Map<String, Object>> result = service.open(
                42L, "sandbox-open", new AppStakingService.OpenRequest("usdt30d", new BigDecimal("100")));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "RUN-STAKING-TEST-001");
        verifyNoInteractions(mapper, disclosureGate, config, idempotency, outbox, audit, earningsReleaseService);
        verify(sandboxMapper).insertAccountIfAbsent("staking", "RUN-STAKING-TEST-001", 42L);
        verify(sandboxMapper).updateWallet(eq("staking"), eq("RUN-STAKING-TEST-001"), eq(42L), any(), any());
    }

    @Test
    void sandboxPositionsHonorRequestedPagination() {
        environment.setActiveProfiles("test");
        environment.setProperty("nexion.commerce.acceptance-run-id", "RUN-STAKING-PAGE-001");
        LocalDateTime lockedAt = LocalDateTime.now(clock).minusDays(1);
        when(sandboxMapper.listPositions("staking", "RUN-STAKING-PAGE-001", 42L)).thenReturn(List.of(
                new MarketSandboxMapper.PositionRow(1L, "STK-SBX-ONE", "STAKING-USDT-30D", "Stake",
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, 30, lockedAt,
                        lockedAt.plusDays(30), BigDecimal.ONE, "ACTIVE", 0L),
                new MarketSandboxMapper.PositionRow(2L, "STK-SBX-TWO", "STAKING-USDT-30D", "Stake",
                        BigDecimal.TEN, BigDecimal.ONE, BigDecimal.ONE, 30, lockedAt,
                        lockedAt.plusDays(30), BigDecimal.ONE, "ACTIVE", 0L)));

        Map<String,Object> data = service.positions(42L, 2, 1).getData();

        assertThat(data.get("positionsPage")).isEqualTo(Map.of("total", 2, "pageNum", 2, "pageSize", 1));
        assertThat((List<?>) data.get("positions")).hasSize(1);
    }

    @Test
    void productionProfileExposesCanonicalProvenance() {
        for (String profile : List.of("prod")) {
            environment.setActiveProfiles(profile);

            ApiResult<java.util.Map<String, Object>> result = service.pools();

            assertThat(result.getData()).containsEntry("serverCanonical", true)
                    .containsEntry("sourceEnvironment", "PRODUCTION").containsEntry("runId", "");
        }
    }

    @Test
    void developmentPublicPoolsReadTheSamePcManagedCanonicalProductsWithoutAcceptanceRunId() {
        environment.setActiveProfiles("dev");

        ApiResult<java.util.Map<String, Object>> result = service.pools();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .containsEntry("source", "nx_staking_product + nx_config_item + nx_emergency_control_setting");
        verify(mapper).listCanonicalProducts();
        verifyNoInteractions(sandboxMapper);
    }

    @Test
    void unknownOrMixedRuntimeFailsClosedBeforeReadingCanonicalStakingTables() {
        for (String[] profiles : List.of(new String[]{"unknown"}, new String[]{"dev", "prod"})) {
            environment.setActiveProfiles(profiles);

            assertThatThrownBy(() -> service.positions(42L))
                    .isInstanceOf(BizException.class)
                    .hasMessage("STAKING_PROFILE_INVALID")
                    .satisfies(error -> assertThat(((BizException) error).getCode()).isEqualTo(503));
        }

        verifyNoInteractions(mapper, disclosureGate, config, idempotency, outbox, audit, earningsReleaseService);
    }

    @Test
    void openingStakeDebitsWalletPersistsLockedSnapshotAndPublishesEvent() {
        ApiResult<java.util.Map<String, Object>> result = service.open(
                42L, "open-1", new AppStakingService.OpenRequest("usdt30d", new BigDecimal("100")));

        assertThat(result.getCode()).isZero();
        verify(disclosureGate).checkUserGate(42L, "staking", "open-1");
        verify(mapper).debitWallet(42L, new BigDecimal("100.000000"));
        verify(mapper).insertPosition(any(AppStakingMapper.PositionWrite.class));
        verify(mapper).insertLedger(any(AppStakingMapper.LedgerWrite.class));
        verify(outbox).publishUserEvent(anyString(), anyString(), org.mockito.ArgumentMatchers.eq("staking.opened"),
                org.mockito.ArgumentMatchers.eq(42L), anyString(), org.mockito.ArgumentMatchers.anyInt(), anyString(), any());
        verify(audit).recordRequiredForTrustedActor(any());
    }

    @Test
    void sameKeyCanSucceedAfterDisclosureIsAcknowledgedWithoutDoubleDebit() {
        when(disclosureGate.checkUserGate(42L, "staking", "open-after-ack"))
                .thenReturn(ApiResult.fail(409, "RISK_DISCLOSURE_ACK_REQUIRED"), ApiResult.ok(null));

        ApiResult<java.util.Map<String, Object>> blocked = service.open(
                42L, "open-after-ack", new AppStakingService.OpenRequest("usdt30d", new BigDecimal("100")));

        assertThat(blocked.getCode()).isEqualTo(409);
        assertThat(blocked.getMessage()).isEqualTo("RISK_DISCLOSURE_ACK_REQUIRED");
        verify(mapper, never()).debitWallet(any(), any());
        verify(mapper, never()).insertPosition(any());
        verify(mapper, never()).insertLedger(any());

        ApiResult<java.util.Map<String, Object>> opened = service.open(
                42L, "open-after-ack", new AppStakingService.OpenRequest("usdt30d", new BigDecimal("100")));

        assertThat(opened.getCode()).isZero();

        verify(disclosureGate, org.mockito.Mockito.times(2)).checkUserGate(42L, "staking", "open-after-ack");
        verify(mapper).debitWallet(42L, new BigDecimal("100.000000"));
        verify(mapper).insertPosition(any());
        verify(mapper).insertLedger(any());
        verify(outbox).publishUserEvent(anyString(), anyString(), anyString(), any(), anyString(), any(), anyString(), any());
        verify(audit).recordRequiredForTrustedActor(any());
    }

    @Test
    void acknowledgedDisclosureIsPerUserAndDoesNotTurnIntoAGlobalStop() {
        when(mapper.controlValue("disclosure.gate.staking")).thenReturn("true");
        when(disclosureGate.checkUserGate(42L, "staking", "open-acknowledged")).thenReturn(ApiResult.ok(null));

        ApiResult<java.util.Map<String, Object>> pools = service.pools();
        ApiResult<java.util.Map<String, Object>> opened = service.open(
                42L, "open-acknowledged", new AppStakingService.OpenRequest("usdt30d", new BigDecimal("100")));

        @SuppressWarnings("unchecked")
        List<java.util.Map<String, Object>> rows = (List<java.util.Map<String, Object>>) pools.getData().get("pools");
        assertThat(rows).singleElement().satisfies(pool -> assertThat(pool).containsEntry("enabled", true));
        assertThat(opened.getCode()).isZero();
        verify(disclosureGate).checkUserGate(42L, "staking", "open-acknowledged");
        verify(mapper).debitWallet(42L, new BigDecimal("100.000000"));
    }

    @Test
    void killedPoolFailsClosedBeforeWalletMutation() {
        when(config.activeValue("G.staking.usdt30d.killed")).thenReturn(Optional.of("true"));

        assertThatThrownBy(() -> service.open(
                42L, "open-killed", new AppStakingService.OpenRequest("usdt30d", new BigDecimal("100"))))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("STAKING_POOL_KILLED");
    }

    @Test
    void maturedPrincipalCreditsDirectlyButInterestUsesTheCanonicalReleaseBuckets() {
        LocalDateTime unlockedAt = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusMinutes(1);
        AppStakingMapper.PositionRow active = new AppStakingMapper.PositionRow(
                9L, 42L, "STK-9", 2L, "USDT_30D", "USDT 30D",
                new BigDecimal("100"), new BigDecimal("1200"), new BigDecimal("500"), 30,
                unlockedAt.minusDays(30), unlockedAt, new BigDecimal("2.500000"),
                "ACTIVE", null, null);
        AppStakingMapper.PositionRow claimed = new AppStakingMapper.PositionRow(
                9L, 42L, "STK-9", 2L, "USDT_30D", "USDT 30D",
                new BigDecimal("100"), new BigDecimal("1200"), new BigDecimal("500"), 30,
                unlockedAt.minusDays(30), unlockedAt, new BigDecimal("2.500000"),
                "CLAIMED", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC), null);
        when(mapper.lockUserPosition(42L, "STK-9")).thenReturn(active);
        when(mapper.markClaimed(any(), any(), any())).thenReturn(1);
        when(mapper.creditWallet(42L, new BigDecimal("100.000000"))).thenReturn(1);
        when(mapper.listUserPositions(42L, 0L, 50)).thenReturn(List.of(claimed));
        when(mapper.walletBalance(42L)).thenReturn(new BigDecimal("1002.500000"));

        assertThat(service.claim(42L, "STK-9", "claim-9").getCode()).isZero();

        verify(mapper).creditWallet(42L, new BigDecimal("100.000000"));
        verify(earningsReleaseService).creditReward(42L, "staking_interest", "STK-9", "USDT",
                new BigDecimal("2.500000"), "G1-STAKING-INTEREST-9");
    }

    private AppStakingMapper.ProductRow product() {
        return new AppStakingMapper.ProductRow(
                2L, "USDT_30D", "USDT 30D", "USDT", 30,
                new BigDecimal("1200"), new BigDecimal("500"), new BigDecimal("20"), "ACTIVE");
    }
}

package ffdd.opsconsole.growth.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.device.mapper.DeviceOpsMapper;
import ffdd.opsconsole.growth.dto.GrowthPublicStatsUpdateRequest;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.user.mapper.UserOpsMapper;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class GrowthPublicStatsServiceTest {
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final UserOpsMapper users = mock(UserOpsMapper.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final DeviceOpsMapper deviceOps = mock(DeviceOpsMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-07T10:15:30Z"), ZoneOffset.UTC);
    private final MockEnvironment environment = new MockEnvironment();
    private final GrowthPublicStatsService service = new GrowthPublicStatsService(
            config, users, deviceOps, audit, new ObjectMapper(), clock, environment, Optional.empty());

    @BeforeEach
    void setUp() {
        clearInvocations(config, users, audit);
        when(config.activeValue("growth.public_stats.version")).thenReturn(Optional.of("3"));
        when(config.activeValue("growth.public_stats.values")).thenReturn(Optional.of("""
                {"fleetDevices":12000,"onlineRatePct":80,"onlineJitter":20,
                 "registeredUsersBase":5000,"registeredUsersMonthlyGrowthPct":5,
                 "registeredUsersAnchorAt":1786097730000,"effectiveAt":1786097730000,"virtualUserCount":100,
                 "hashratePercentileTable":[{"tops":10,"cumPct":40},{"tops":20,"cumPct":100}]}
                """));
        when(config.activeValue("dailyUsdtPerBaseline")).thenReturn(Optional.of("0.06"));
        when(users.countUsers()).thenReturn(4321L);
    }

    @Test
    void exposesServerCanonicalOverviewAndDerivedFacts() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("version", 3L)
                .containsEntry("realUserCount", 4321L)
                .containsEntry("publishedDailyUsdPerDevice", new BigDecimal("0.06"))
                .containsKey("defaults")
                .containsKey("effectiveAt");
        assertThat((Map<String, Object>) result.getData().get("values"))
                .containsEntry("fleetDevices", 12000);
        assertThat((Map<String, Object>) result.getData().get("defaults"))
                .containsEntry("registeredUsersAnchorAt", 1786097730000L);
    }

    @Test
    void networkPulseUsesConfiguredMembersAndTheSameCurrentHomeDisplayBatch() {
        HomeDisplayService display = mock(HomeDisplayService.class);
        long now = clock.millis();
        when(display.current()).thenReturn(Map.of("startAt", now - 1000, "serverNow", now,
                "online", new long[]{28000, 28002, 28005}));
        GrowthPublicStatsService withDisplay = new GrowthPublicStatsService(
                config, users, deviceOps, audit, new ObjectMapper(), clock, environment, Optional.of(display));
        Map<String, Object> projection = withDisplay.publicProjection().getData();
        assertThat((Map<String, Object>) projection.get("networkPulse"))
                .containsEntry("registeredUsers", 5000L).containsEntry("onlineDevices", 28002L);
        assertThat(projection).containsEntry("realUserCount", 4321L).doesNotContainKey("values");
        verify(display).current();

        Clock monthLater = Clock.offset(clock, java.time.Duration.ofDays(30));
        GrowthPublicStatsService later = new GrowthPublicStatsService(
                config, users, deviceOps, audit, new ObjectMapper(), monthLater, environment, Optional.of(display));
        assertThat((Map<String, Object>) later.publicProjection().getData().get("networkPulse"))
                .containsEntry("registeredUsers", 5250L);
    }

    @Test
    void unavailableHomeDisplayDoesNotInventOnlineCountsOrBreakTheOtherProjection() {
        HomeDisplayService display = mock(HomeDisplayService.class);
        when(display.current()).thenThrow(new IllegalStateException("cache unavailable"));
        GrowthPublicStatsService withDisplay = new GrowthPublicStatsService(
                config, users, deviceOps, audit, new ObjectMapper(), clock, environment, Optional.of(display));
        ApiResult<Map<String, Object>> projection = withDisplay.publicProjection();
        assertThat(projection.getCode()).isZero();
        assertThat((Map<String, Object>) projection.getData().get("networkPulse"))
                .containsEntry("registeredUsers", 5000L).containsEntry("onlineDevices", null);
        assertThat(projection.getData()).containsKey("verified");
    }

    @Test
    void publicProjectionCarriesExactProductionProvenance() {
        ApiResult<Map<String, Object>> result = service.publicProjection();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("serverCanonical", true)
                .containsEntry("source", "server:nx_config_item,nx_user")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .doesNotContainKeys("values", "effectiveAt");
        assertThat((Map<String, Object>) result.getData().get("provenance"))
                .containsEntry("verified", "SERVER_AGGREGATE")
                .doesNotContainKey("values");
    }

    @Test
    void sandboxProjectionIsBoundToTheAcceptanceRun() {
        stubSandbox("home-public-stats-20260819", 12000);
        MockEnvironment sandbox = new MockEnvironment()
                .withProperty("NEXION_ACCEPTANCE_RUN_ID", "home-public-stats-20260819");
        sandbox.setActiveProfiles("test");
        GrowthPublicStatsService sandboxService = new GrowthPublicStatsService(
                config, users, deviceOps, audit, new ObjectMapper(), clock, sandbox, Optional.empty());

        ApiResult<Map<String, Object>> result = sandboxService.publicProjection();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("serverCanonical", true)
                .containsEntry("source", "mock")
                .containsEntry("sourceEnvironment", "SANDBOX")
                .containsEntry("runId", "home-public-stats-20260819")
                .containsEntry("realUserCount", 0L);
        verify(config, never()).activeValue("growth.public_stats.values");
        verify(users, never()).countUsers();
    }

    @Test
    void developmentProfileReadsCanonicalPcConfigurationWithoutAcceptanceRun() {
        MockEnvironment development = new MockEnvironment();
        development.setActiveProfiles("dev");
        GrowthPublicStatsService developmentService = new GrowthPublicStatsService(
                config, users, deviceOps, audit, new ObjectMapper(), clock, development, Optional.empty());

        ApiResult<Map<String, Object>> result = developmentService.publicProjection();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("source", "server:nx_config_item,nx_user")
                .containsEntry("sourceEnvironment", "PRODUCTION")
                .containsEntry("runId", "")
                .containsEntry("realUserCount", 4321L);
        verify(config).activeValue("growth.public_stats.values");
        verify(users).countUsers();
    }

    @Test
    void sandboxRunsReadOnlyTheirNamespacedSnapshots() {
        stubSandbox("home-public-stats-run-a", 12000);
        stubSandbox("home-public-stats-run-b", 31000);

        ApiResult<Map<String, Object>> runA = sandboxService("home-public-stats-run-a").publicProjection();
        ApiResult<Map<String, Object>> runB = sandboxService("home-public-stats-run-b").publicProjection();

        assertThat(runA.getData()).doesNotContainKeys("values", "effectiveAt");
        assertThat(runB.getData()).doesNotContainKeys("values", "effectiveAt");
        assertThat((Map<String, Object>) sandboxService("home-public-stats-run-a").overview().getData().get("values"))
                .containsEntry("fleetDevices", 12000);
        assertThat((Map<String, Object>) sandboxService("home-public-stats-run-b").overview().getData().get("values"))
                .containsEntry("fleetDevices", 31000);
        verify(config, never()).activeValue("growth.public_stats.values");
        verify(config, never()).activeValue("growth.public_stats.version");
        verify(users, never()).countUsers();
    }

    @Test
    void sandboxUpdateWritesOnlyTheCurrentRunSnapshot() {
        String runId = "home-public-stats-update-run";
        stubSandbox(runId, 12000);
        when(config.activeValueForUpdate("h9.sb." + runId + ".v")).thenReturn(Optional.of("3"));

        HomeDisplayService publicDisplay = mock(HomeDisplayService.class);
        GrowthPublicStatsService scoped = sandboxService(runId);
        org.springframework.test.util.ReflectionTestUtils.setField(scoped, "homeDisplay", Optional.of(publicDisplay));
        ApiResult<Map<String, Object>> result = scoped.update(validRequest(3L), "h9-sandbox-save");
        org.mockito.Mockito.verifyNoInteractions(publicDisplay);

        assertThat(result.getCode()).isZero();
        verify(config).upsertAdminValue(
                org.mockito.ArgumentMatchers.eq("h9.sb." + runId + ".data"),
                anyString(), org.mockito.ArgumentMatchers.eq("JSON"),
                org.mockito.ArgumentMatchers.eq("growth_sandbox"), anyString());
        verify(config).upsertAdminValue(
                "h9.sb." + runId + ".v", "4", "NUMBER", "growth_sandbox",
                "H9 public stats aggregate version");
        verify(config, never()).upsertAdminValue(
                org.mockito.ArgumentMatchers.eq("growth.public_stats.values"),
                anyString(), anyString(), anyString(), anyString());
        verify(config, never()).upsertAdminValue(
                org.mockito.ArgumentMatchers.eq("growth.public_stats.version"),
                anyString(), anyString(), anyString(), anyString());
        verify(users, never()).countUsers();
    }

    @Test
    void sandboxProjectionFailsClosedWhenTheRunHasNoSnapshot() {
        ApiResult<Map<String, Object>> result = sandboxService("home-public-stats-missing").publicProjection();

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("H9_CONFIG_UNAVAILABLE");
        verify(config, never()).activeValue("growth.public_stats.values");
        verify(users, never()).countUsers();
    }

    @Test
    void sandboxProjectionFailsClosedWithoutAValidRun() {
        MockEnvironment sandbox = new MockEnvironment();
        sandbox.setActiveProfiles("test");
        GrowthPublicStatsService sandboxService = new GrowthPublicStatsService(
                config, users, deviceOps, audit, new ObjectMapper(), clock, sandbox, Optional.empty());

        ApiResult<Map<String, Object>> result = sandboxService.publicProjection();

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("H9_SANDBOX_RUN_ID_REQUIRED");
        verify(config, never()).activeValue(anyString());
        verify(users, never()).countUsers();
    }

    @Test
    void publicProjectionFailsClosedBeforeReadingOnMixedProfiles() {
        MockEnvironment mixed = new MockEnvironment();
        mixed.setActiveProfiles("dev", "prod");
        GrowthPublicStatsService mixedService = new GrowthPublicStatsService(
                config, users, deviceOps, audit, new ObjectMapper(), clock, mixed, Optional.empty());

        ApiResult<Map<String, Object>> result = mixedService.publicProjection();

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("H9_PROFILE_INVALID");
        verify(config, never()).activeValue(anyString());
        verify(users, never()).countUsers();
    }

    @Test
    void rejectsStaleVersionWithoutWritingAnyConfig() {
        when(config.activeValueForUpdate("growth.public_stats.version")).thenReturn(Optional.of("4"));

        ApiResult<Map<String, Object>> result = service.update(validRequest(3L), "h9-stale");

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("H9_CONFIG_VERSION_CONFLICT");
        verify(config, org.mockito.Mockito.never()).upsertAdminValue(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void savesWholeSetAndResetsAnchorWhenRegisteredBaseChanges() {
        when(config.activeValueForUpdate("growth.public_stats.version")).thenReturn(Optional.of("3"));

        ApiResult<Map<String, Object>> result = service.update(validRequest(3L), "h9-save");

        assertThat(result.getCode()).isZero();
        assertThat((Map<String, Object>) result.getData().get("values"))
                .containsEntry("registeredUsersAnchorAt", clock.millis());
        verify(config).upsertAdminValue(
                "growth.public_stats.version", "4", "NUMBER", "growth", "H9 public stats aggregate version");
        verify(audit).recordRequired(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void everySuccessfulAggregateChangeAdvancesEffectiveAtWithoutMovingTheUserGrowthAnchor() {
        when(config.activeValueForUpdate("growth.public_stats.version")).thenReturn(Optional.of("3"));
        GrowthPublicStatsUpdateRequest request = new GrowthPublicStatsUpdateRequest(
                13000, new BigDecimal("80"), 20, 5000L, new BigDecimal("5"), 100L,
                List.of(
                        new GrowthPublicStatsUpdateRequest.PercentileBand(new BigDecimal("10"), new BigDecimal("40")),
                        new GrowthPublicStatsUpdateRequest.PercentileBand(new BigDecimal("20"), new BigDecimal("100"))),
                3L, "change fleet without changing user base", "superadmin");

        ApiResult<Map<String, Object>> result = service.update(request, "h9-effective-at");

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("effectiveAt", "2026-08-07T10:15:30Z");
        assertThat((Map<String, Object>) result.getData().get("values"))
                .containsEntry("registeredUsersAnchorAt", 1786097730000L)
                .containsEntry("effectiveAt", clock.millis());
    }

    @Test
    void rejectsNonMonotonicPercentileTableAtomically() {
        GrowthPublicStatsUpdateRequest request = new GrowthPublicStatsUpdateRequest(
                12000, new BigDecimal("80"), 20, 6000L, new BigDecimal("5"), 100L,
                List.of(
                        new GrowthPublicStatsUpdateRequest.PercentileBand(new BigDecimal("20"), new BigDecimal("40")),
                        new GrowthPublicStatsUpdateRequest.PercentileBand(new BigDecimal("10"), new BigDecimal("100"))),
                3L, "invalid percentile order", "superadmin");

        ApiResult<Map<String, Object>> result = service.update(request, "h9-invalid");

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("H9_PERCENTILE_TABLE_INVALID");
    }

    @Test
    void acceptsFrontendLegalPercentileTableWithZeroTopFlatPercentAndPartialTail() {
        when(config.activeValueForUpdate("growth.public_stats.version")).thenReturn(Optional.of("3"));
        GrowthPublicStatsUpdateRequest request = new GrowthPublicStatsUpdateRequest(
                12000, new BigDecimal("80"), 20, 6000L, new BigDecimal("5"), 100L,
                List.of(
                        new GrowthPublicStatsUpdateRequest.PercentileBand(BigDecimal.ZERO, new BigDecimal("40")),
                        new GrowthPublicStatsUpdateRequest.PercentileBand(new BigDecimal("20"), new BigDecimal("40")),
                        new GrowthPublicStatsUpdateRequest.PercentileBand(new BigDecimal("60"), new BigDecimal("96"))),
                3L, "frontend legal percentile table", "superadmin");

        ApiResult<Map<String, Object>> result = service.update(request, "h9-parity");

        assertThat(result.getCode()).isZero();
    }

    @Test
    void readsPreviouslyStoredFrontendLegalPercentileTableWithoutFailingClosed() {
        when(config.activeValue("growth.public_stats.values")).thenReturn(Optional.of("""
                {"fleetDevices":12000,"onlineRatePct":80,"onlineJitter":20,
                 "registeredUsersBase":5000,"registeredUsersMonthlyGrowthPct":5,
                 "registeredUsersAnchorAt":1786097730000,"effectiveAt":1786097730000,"virtualUserCount":100,
                 "hashratePercentileTable":[{"tops":0,"cumPct":40},{"tops":20,"cumPct":40},{"tops":60,"cumPct":96}]}
                """));

        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
    }

    @Test
    void rejectsInvertedRangesAndSubCentDisplayAmountsBeforeWriting() {
        GrowthPublicStatsUpdateRequest base = validRequest(3L);
        for (BigDecimal paidStep : List.of(new BigDecimal("5.51"), new BigDecimal("1.101"), new BigDecimal("-1"))) {
            GrowthPublicStatsUpdateRequest request = new GrowthPublicStatsUpdateRequest(base.fleetDevices(), base.onlineRatePct(),
                    base.onlineJitter(), base.registeredUsersBase(), base.registeredUsersMonthlyGrowthPct(), base.virtualUserCount(),
                    base.hashratePercentileTable(), 3L, base.reason(), base.operator(), 28432L, 1, 3,
                    new BigDecimal("130532"), paidStep, new BigDecimal("5.50"), true);
            assertThat(service.update(request, "invalid-home-display").getCode()).isEqualTo(422);
        }
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void memberEstimateStaysUnpublishedUntilTickingIsConfigured() {
        Map<String, Object> projection = service.publicProjection().getData();

        assertThat(projection).doesNotContainKeys(
                "registeredUsersBase", "registeredUsersAnchorAt",
                "registeredUsersTickIntervalSec", "registeredUsersTickStep");
    }

    @Test
    void tickedMemberEstimateIsPublishedAndMatchesTheAppFormula() {
        when(config.activeValue("growth.public_stats.values")).thenReturn(Optional.of("""
                {"fleetDevices":12000,"onlineRatePct":80,"onlineJitter":20,
                 "registeredUsersBase":5000,"registeredUsersMonthlyGrowthPct":5,
                 "registeredUsersAnchorAt":%d,"effectiveAt":1786097730000,"virtualUserCount":100,
                 "registeredUsersTickIntervalSec":10,"registeredUsersTickStep":3,
                 "hashratePercentileTable":[{"tops":10,"cumPct":40},{"tops":20,"cumPct":100}]}
                """.formatted(clock.millis() - 95_000)));

        Map<String, Object> projection = service.publicProjection().getData();

        assertThat(projection)
                .containsEntry("registeredUsersBase", 5000)
                .containsEntry("registeredUsersAnchorAt", clock.millis() - 95_000)
                .containsEntry("registeredUsersTickIntervalSec", 10)
                .containsEntry("registeredUsersTickStep", 3)
                .doesNotContainKeys("values", "registeredUsersMonthlyGrowthPct", "fleetDevices");
        // 95 s at one tick per 10 s → 9 ticks × 3 on top of the start value.
        assertThat((Map<String, Object>) projection.get("networkPulse")).containsEntry("registeredUsers", 5027L);
    }

    @Test
    void savingTickCadenceRestartsTheEstimateFromTheStartValue() {
        when(config.activeValueForUpdate("growth.public_stats.version")).thenReturn(Optional.of("3"));

        ApiResult<Map<String, Object>> result = service.update(tickRequest(5000L, 60, 2L), "h9-tick-save");

        assertThat(result.getCode()).isZero();
        assertThat((Map<String, Object>) result.getData().get("values"))
                .containsEntry("registeredUsersBase", 5000L)
                .containsEntry("registeredUsersTickIntervalSec", 60)
                .containsEntry("registeredUsersTickStep", 2L)
                .containsEntry("registeredUsersAnchorAt", clock.millis());
    }

    @Test
    void olderClientsWithoutTickFieldsKeepTheStoredCadenceAndAnchor() {
        when(config.activeValueForUpdate("growth.public_stats.version")).thenReturn(Optional.of("3"));
        when(config.activeValue("growth.public_stats.values")).thenReturn(Optional.of("""
                {"fleetDevices":12000,"onlineRatePct":80,"onlineJitter":20,
                 "registeredUsersBase":5000,"registeredUsersMonthlyGrowthPct":5,
                 "registeredUsersAnchorAt":1786097730000,"effectiveAt":1786097730000,"virtualUserCount":100,
                 "registeredUsersTickIntervalSec":10,"registeredUsersTickStep":3,
                 "hashratePercentileTable":[{"tops":10,"cumPct":40},{"tops":20,"cumPct":100}]}
                """));
        GrowthPublicStatsUpdateRequest legacy = new GrowthPublicStatsUpdateRequest(
                13000, new BigDecimal("80"), 20, 5000L, new BigDecimal("5"), 100L,
                validRequest(3L).hashratePercentileTable(), 3L, "change fleet from an older console", "superadmin");

        ApiResult<Map<String, Object>> result = service.update(legacy, "h9-legacy-client");

        assertThat(result.getCode()).isZero();
        assertThat((Map<String, Object>) result.getData().get("values"))
                .containsEntry("registeredUsersTickIntervalSec", 10)
                .containsEntry("registeredUsersTickStep", 3)
                .containsEntry("registeredUsersAnchorAt", 1786097730000L);
    }

    @Test
    void rejectsHalfOrOutOfRangeTickPairsBeforeWriting() {
        when(config.activeValueForUpdate("growth.public_stats.version")).thenReturn(Optional.of("3"));
        for (GrowthPublicStatsUpdateRequest request : List.of(
                tickRequest(5000L, 0, 1L), tickRequest(5000L, 86_401, 1L),
                tickRequest(5000L, 10, -1L), tickRequest(5000L, 10, 100_000_001L),
                tickRequest(5000L, 10, null), tickRequest(5000L, null, 1L))) {
            ApiResult<Map<String, Object>> result = service.update(request, "h9-tick-invalid");
            assertThat(result.getCode()).isEqualTo(422);
            assertThat(result.getMessage()).isEqualTo("H9_MEMBER_TICK_INVALID");
        }
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private GrowthPublicStatsUpdateRequest tickRequest(long base, Integer intervalSec, Long step) {
        GrowthPublicStatsUpdateRequest valid = validRequest(3L);
        return new GrowthPublicStatsUpdateRequest(valid.fleetDevices(), valid.onlineRatePct(), valid.onlineJitter(),
                base, valid.registeredUsersMonthlyGrowthPct(), valid.virtualUserCount(), valid.hashratePercentileTable(),
                3L, "configure member tick cadence", "superadmin",
                null, null, null, null, null, null, null, intervalSec, step);
    }

    private GrowthPublicStatsUpdateRequest validRequest(long version) {
        return new GrowthPublicStatsUpdateRequest(
                12000, new BigDecimal("80"), 20, 6000L, new BigDecimal("5"), 100L,
                List.of(
                        new GrowthPublicStatsUpdateRequest.PercentileBand(new BigDecimal("10"), new BigDecimal("40")),
                        new GrowthPublicStatsUpdateRequest.PercentileBand(new BigDecimal("20"), new BigDecimal("100"))),
                version, "update public stats", "superadmin");
    }

    private GrowthPublicStatsService sandboxService(String runId) {
        MockEnvironment sandbox = new MockEnvironment().withProperty("NEXION_ACCEPTANCE_RUN_ID", runId);
        sandbox.setActiveProfiles("test");
        return new GrowthPublicStatsService(config, users, deviceOps, audit, new ObjectMapper(), clock, sandbox, Optional.empty());
    }

    private void stubSandbox(String runId, int fleetDevices) {
        when(config.activeValue("h9.sb." + runId + ".v")).thenReturn(Optional.of("3"));
        when(config.activeValue("h9.sb." + runId + ".data")).thenReturn(Optional.of("""
                {"fleetDevices":%d,"onlineRatePct":80,"onlineJitter":20,
                 "registeredUsersBase":5000,"registeredUsersMonthlyGrowthPct":5,
                 "registeredUsersAnchorAt":1786097730000,"effectiveAt":1786097730000,"virtualUserCount":100,
                 "hashratePercentileTable":[{"tops":10,"cumPct":40},{"tops":20,"cumPct":100}]}
                """.formatted(fleetDevices)));
    }
}

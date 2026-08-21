package ffdd.opsconsole.team.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.seed.OpsReadTimeSeedPolicy;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.AppBinaryCommissionEventRow;
import ffdd.opsconsole.team.mapper.BinaryCommissionSettlementMapper.PaidOrderVolumeCandidate;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

class AppBinaryProjectionServiceTest {
    private BinaryCommissionSettlementMapper mapper;
    private PlatformConfigFacade config;
    private TreasuryCoverageFacade coverage;
    private AppBinaryProjectionService service;

    @BeforeEach
    void setUp() {
        mapper = mock(BinaryCommissionSettlementMapper.class);
        config = mock(PlatformConfigFacade.class);
        coverage = mock(TreasuryCoverageFacade.class);
        service = new AppBinaryProjectionService(
                mapper, config, mock(OpsReadTimeSeedPolicy.class), coverage,
                new MockEnvironment()
                        .withProperty("spring.profiles.active", "dev")
                        .withProperty("NEXION_ACCEPTANCE_RUN_ID", "binary-run-20260819"));
        seed("team.ui.F.binary.threshold", "1000");
        seed("team.ui.F.binary.matchRate", "13%");
        seed("team.ui.F.binary.paused", "false");
        seed("team.ui.F.binary.spillover", "已启用");
        seed("team.ui.F.binary.settlePeriod", "每月");
        seed("team.ui.F.binary.residualPolicy", "每次对碰清零");
        seed("team.ui.F.binary.gvResetCron", "每月 1 日 00:00 UTC");
        seed("H1.rhythm.totalMonths", "12");
        seed("H1.rhythm.currentMonth", "1");
        seed("H1.rhythm.phaseProgressPct", "50");
        seed("growth.phase.current", "P1");
        seed("growth.phase.month.1.newUserBonusMultiplier", "1");
        seed("growth.phase.month.1.inviteRewardMultiplier", "1");
        seed("growth.phase.month.1.reinvestMultiplier", "1");
        seed("growth.phase.month.1.withdrawPenaltyFeeRate", "0.1");
        seed("growth.phase.month.1.withdrawCooldownDays", "30");
        seed("growth.phase.month.1.binaryDailyCap", "5000");
        seed("growth.phase.month.1.questBonusMultiplier", "1");
        seed("growth.phase.month.1.complianceHoldEnabled", "0");
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("150"), new BigDecimal("120"), true));
    }

    @Test
    void projectsOnlyServerPaidOrdersAssignmentsH1AndCommissionEvents() {
        when(mapper.listPaidOrderCandidates(eq(41L), any(), any())).thenReturn(List.of(
                new PaidOrderVolumeCandidate(
                        "A-1", 51L, 51L, "A", new BigDecimal("1000"),
                        LocalDateTime.now().minusHours(2), 1),
                new PaidOrderVolumeCandidate(
                        "B-1", 52L, 52L, "B", new BigDecimal("2000"),
                        LocalDateTime.now().minusHours(1), 1)));
        when(mapper.countDirectMembers(41L)).thenReturn(2);
        when(mapper.countAssignmentsByLeg(41L, "A")).thenReturn(1);
        when(mapper.countAssignmentsByLeg(41L, "B")).thenReturn(1);
        when(mapper.countAutoPlacedMembers(41L)).thenReturn(1);
        when(mapper.listRecentBinaryCommissionEvents(41L, 20)).thenReturn(List.of(
                new AppBinaryCommissionEventRow(
                        88L, new BigDecimal("130"), "COOLING",
                        LocalDateTime.now().minusHours(1), LocalDateTime.now().plusDays(30))));

        Map<String, Object> result = service.snapshot(41L);

        assertThat(result.get("source")).isEqualTo("server");
        assertThat(result.get("serverCanonical")).isEqualTo(true);
        assertThat(result.get("sourceEnvironment")).isEqualTo("SANDBOX");
        assertThat(result.get("runId")).isEqualTo("binary-run-20260819");
        assertThat((BigDecimal) result.get("trackA")).isEqualByComparingTo("1000");
        assertThat((BigDecimal) result.get("trackB")).isEqualByComparingTo("2000");
        assertThat((BigDecimal) result.get("matchRate")).isEqualByComparingTo("0.13");
        assertThat((BigDecimal) result.get("estimatedAmountUsdt")).isEqualByComparingTo("130");
        assertThat(result.get("residualPolicy")).isEqualTo("perPairClear");
        assertThat((List<?>) result.get("recentMatches")).hasSize(1);
    }

    @Test
    void ambiguousPaidOrderMappingFailsClosed() {
        when(mapper.listPaidOrderCandidates(eq(41L), any(), any())).thenReturn(List.of(
                new PaidOrderVolumeCandidate(
                        "AMBIGUOUS", 51L, 51L, "A", new BigDecimal("1000"),
                        LocalDateTime.now(), 2)));

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.snapshot(41L))
                .hasMessage("F3_APP_VOLUME_SOURCE_AMBIGUOUS");
    }

    private void seed(String key, String value) {
        when(config.activeValue(key)).thenReturn(Optional.of(value));
    }
}

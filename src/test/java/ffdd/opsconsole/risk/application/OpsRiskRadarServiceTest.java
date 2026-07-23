package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.dto.B5ThresholdPreviewRequest;
import ffdd.opsconsole.risk.mapper.B5RiskRadarMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.shared.security.AdminOperatorRoleResolver;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OpsRiskRadarServiceTest {
    private final B5RiskRadarMapper mapper = mock(B5RiskRadarMapper.class);
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final OpsRiskRadarService service = new OpsRiskRadarService(
            mapper,
            config,
            coverage,
            mock(AdminIdempotencyService.class),
            mock(AuditLogService.class),
            mock(AdminOperatorRoleResolver.class),
            Clock.fixed(Instant.parse("2026-07-23T04:00:00Z"), ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(mapper.moneySnapshot()).thenReturn(Map.of(
                "withdraw24hUsdt", new BigDecimal("200"),
                "reserveUsdt", new BigDecimal("1000"),
                "payoutUsdt", new BigDecimal("60"),
                "commissionUsdt", new BigDecimal("10"),
                "grossInflowUsdt", new BigDecimal("100")));
        when(mapper.withdrawalBacklog()).thenReturn(List.of(
                Map.of("state", "submitted", "count", 2, "amountUsdt", 100, "overSlaCount", 1),
                Map.of("state", "review-passed", "count", 1, "amountUsdt", 50, "overSlaCount", 0),
                Map.of("state", "processing", "count", 3, "amountUsdt", 75, "overSlaCount", 0)));
        when(mapper.abnormalAccountCategories()).thenReturn(List.of(
                Map.of("category", "multi-account", "label", "反多账户命中", "count", 2),
                Map.of("category", "arbitrage", "label", "套利可疑", "count", 1)));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("120"), new BigDecimal("100"), true,
                new BigDecimal("1000"), new BigDecimal("833.33"), BigDecimal.ONE));
        when(config.activeValue("risk.bankrun-yellow-pct")).thenReturn(Optional.of("20"));
        when(config.activeValue("risk.bankrun-red-pct")).thenReturn(Optional.of("40"));
        when(config.activeValue("risk.bankrun-threshold-version")).thenReturn(Optional.of("3"));
        for (String gate : List.of("withdraw", "staking", "genesis", "exchange", "trial")) {
            when(config.activeValue("killswitch." + gate)).thenReturn(Optional.of("enabled"));
            when(config.activeValue("J.killswitch." + gate)).thenReturn(Optional.empty());
        }
        when(config.activeValue("risk.alert-subscription.channels")).thenReturn(Optional.of("inApp,email"));
        when(config.activeValue("risk.alert-subscription.webhook-url")).thenReturn(Optional.of(""));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesTheFiveCanonicalDimensionsWithoutGeoBlock() {
        Map<String, Object> radar = service.radar().getData();

        assertThat(radar).containsKeys(
                "bankrun", "abnormalAccounts", "withdrawBacklog", "killSwitches", "coverage");
        Map<String, Object> bankrun = (Map<String, Object>) radar.get("bankrun");
        assertThat(bankrun).containsEntry("pressureRatio", new BigDecimal("0.7"))
                .containsEntry("pressureRedLine", new BigDecimal("0.7"))
                .containsEntry("version", 3L);
        List<?> gates = (List<?>) radar.get("killSwitches");
        assertThat(gates).hasSize(5);
        assertThat(gates.toString()).doesNotContain("geo-block");
        Map<?, ?> backlog = (Map<?, ?>) radar.get("withdrawBacklog");
        assertThat(backlog.get("byState").toString())
                .contains("submitted", "review-passed", "processing", "slaHours=48");
    }

    @Test
    void failsClosedWhenB1CoverageIsUnavailable() {
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                BigDecimal.ZERO, BigDecimal.ZERO, false));

        assertThatThrownBy(service::radar)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B5_COVERAGE_SOURCE_UNAVAILABLE");
    }

    @Test
    void thresholdPreviewRejectsNonIncreasingBands() {
        assertThatThrownBy(() -> service.preview(new B5ThresholdPreviewRequest("40", "40", 3L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("BANKRUN_REDLINE_MUST_EXCEED_YELLOW");
    }

    @Test
    void thresholdPreviewRejectsStaleVersion() {
        assertThatThrownBy(() -> service.preview(new B5ThresholdPreviewRequest("20", "45", 2L)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B5_THRESHOLD_VERSION_CONFLICT");
    }
}

package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

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
        when(mapper.abnormalAccountCount()).thenReturn(3L);
        when(mapper.killSwitchStates()).thenReturn(List.of(
                Map.of("gateKey", "withdraw", "settingValue", "enabled"),
                Map.of("gateKey", "staking", "settingValue", "enabled"),
                Map.of("gateKey", "genesis", "settingValue", "enabled"),
                Map.of("gateKey", "exchange", "settingValue", "enabled"),
                Map.of("gateKey", "trial", "settingValue", "enabled")));
        when(mapper.recentSignals(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(Map.of(
                        "signalNo", "SIG-20260808-001",
                        "level", "P1",
                        "signalType", "risk.multi_account_flagged",
                        "userId", 42L,
                        "handlingStatus", "open",
                        "handlingVersion", 0L,
                        "deliveryStatus", "NOT_QUEUED",
                        "createdAt", "2026-07-23T03:55:00")));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("120"), new BigDecimal("100"), true,
                new BigDecimal("1000"), new BigDecimal("833.33"), BigDecimal.ONE));
        when(config.activeValue("risk.bankrun-yellow-pct")).thenReturn(Optional.of("20"));
        when(config.activeValue("risk.bankrun-red-pct")).thenReturn(Optional.of("40"));
        when(config.activeValue("risk.bankrun-threshold-version")).thenReturn(Optional.of("3"));
        when(config.activeValue("risk.alert-subscription.channels")).thenReturn(Optional.of("inApp,email"));
        when(config.activeValue("risk.alert-subscription.webhook-url")).thenReturn(Optional.of(""));
        when(config.activeValue("risk.alert-subscription.version")).thenReturn(Optional.of("0"));
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
    @SuppressWarnings("unchecked")
    void exposesUnroundedRatioInputsSoTheClientCanVerifySubCentRatios() {
        when(mapper.moneySnapshot()).thenReturn(Map.of(
                "withdraw24hUsdt", new BigDecimal("1.004"),
                "reserveUsdt", new BigDecimal("3.004"),
                "payoutUsdt", new BigDecimal("0.504"),
                "commissionUsdt", new BigDecimal("0.500"),
                "grossInflowUsdt", new BigDecimal("3.004")));

        Map<String, Object> radar = service.radar().getData();
        Map<String, Object> bankrun = (Map<String, Object>) radar.get("bankrun");

        assertThat(bankrun)
                .containsEntry("ratio24h", new BigDecimal("0.3342"))
                .containsEntry("ratioWithdraw24hUsdt", new BigDecimal("1.004"))
                .containsEntry("ratioReserveUsdt", new BigDecimal("3.004"));
        Map<String, Object> coverageView = (Map<String, Object>) radar.get("coverage");
        assertThat(coverageView)
                .containsEntry("ratioReserveUsdt", new BigDecimal("1000"))
                .containsEntry("ratioLiabilitiesUsdt", new BigDecimal("833.33"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void marksCoverageUncomputableWhenAuthoritativeLiabilityDenominatorIsZero() {
        for (BigDecimal reserve : List.of(BigDecimal.ZERO, BigDecimal.ONE)) {
            when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                    BigDecimal.valueOf(100), BigDecimal.valueOf(100), true,
                    reserve, BigDecimal.ZERO, BigDecimal.ONE, reserve, BigDecimal.ZERO));

            Map<String, Object> radar = service.radar().getData();
            Map<String, Object> coverageView = (Map<String, Object>) radar.get("coverage");

            assertThat(coverageView).containsEntry("ratio", null)
                    .containsEntry("light", "unavailable");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void neverLabelsAZeroReserveBankrunRatioAsHealthy() {
        for (BigDecimal withdrawal : List.of(BigDecimal.ZERO, BigDecimal.ONE)) {
            when(mapper.moneySnapshot()).thenReturn(Map.of(
                    "withdraw24hUsdt", withdrawal,
                    "reserveUsdt", BigDecimal.ZERO,
                    "payoutUsdt", BigDecimal.ZERO,
                    "commissionUsdt", BigDecimal.ZERO,
                    "grossInflowUsdt", BigDecimal.ONE));

            Map<String, Object> bankrun = (Map<String, Object>) service.radar().getData().get("bankrun");

            assertThat(bankrun).containsEntry("ratio24h", null)
                    .containsEntry("ratioCalculable", false)
                    .containsEntry("light", withdrawal.signum() > 0 ? "red" : "unavailable");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void restoresServerLabeledPressureSeverityAndSevenDayAlertHistory() {
        when(mapper.pressureWindows(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.stream.IntStream.range(0, 8)
                        .mapToObj(index -> Map.<String, Object>of("label", "D" + index, "ratio", new BigDecimal("0.10")))
                        .toList());
        when(mapper.alertSeverity(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        Map.of("level", "P0", "count", 1L),
                        Map.of("level", "P1", "count", 2L),
                        Map.of("level", "P2", "count", 3L),
                        Map.of("level", "P3", "count", 4L)));
        when(mapper.alertVolume(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.stream.IntStream.range(0, 7)
                        .mapToObj(index -> Map.<String, Object>of("label", "D" + index, "count", (long) index))
                        .toList());

        Map<String, Object> radar = service.radar().getData();

        assertThat((List<Map<String, Object>>) radar.get("pressureHistory")).hasSize(8);
        assertThat((List<Map<String, Object>>) radar.get("alertSeverity"))
                .extracting(row -> row.get("level"))
                .containsExactly("P0", "P1", "P2", "P3");
        assertThat((List<Map<String, Object>>) radar.get("alertVolume")).hasSize(7);
        assertThat(((List<Map<String, Object>>) radar.get("recentAlerts")).get(0))
                .containsEntry("signalNo", "SIG-20260808-001")
                .containsEntry("level", "P1")
                .containsEntry("message", "反多账户命中")
                .containsEntry("target", "/risk/multi-account")
                .containsEntry("handlingStatusAvailable", true)
                .containsEntry("handlingStatus", "open")
                .containsEntry("handlingVersion", 0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void keepsPressureUnavailableWhenGrossInflowDenominatorIsZero() {
        when(mapper.moneySnapshot()).thenReturn(Map.of(
                "withdraw24hUsdt", BigDecimal.ZERO,
                "reserveUsdt", new BigDecimal("1000"),
                "payoutUsdt", new BigDecimal("25"),
                "commissionUsdt", new BigDecimal("5"),
                "grossInflowUsdt", BigDecimal.ZERO));
        when(mapper.pressureWindows(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.stream.IntStream.range(0, 8)
                        // MyBatis result maps omit an alias whose SQL value is NULL.
                        .mapToObj(index -> Map.<String, Object>of("label", "D" + index))
                        .toList());
        when(mapper.alertSeverity(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(List.of(
                        Map.of("level", "P0", "count", 0L),
                        Map.of("level", "P1", "count", 0L),
                        Map.of("level", "P2", "count", 0L),
                        Map.of("level", "P3", "count", 0L)));
        when(mapper.alertVolume(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.stream.IntStream.range(0, 7)
                        .mapToObj(index -> Map.<String, Object>of("label", "D" + index, "count", 0L))
                        .toList());

        Map<String, Object> radar = service.radar().getData();
        Map<String, Object> bankrun = (Map<String, Object>) radar.get("bankrun");
        List<Map<String, Object>> history = (List<Map<String, Object>>) radar.get("pressureHistory");

        assertThat(bankrun)
                .containsEntry("pressureCalculable", false)
                .containsEntry("pressureRatio", null)
                .containsEntry("pressureLight", "red");
        assertThat(history).allSatisfy(row -> assertThat(row).containsEntry("ratio", null));
    }

    @Test
    @SuppressWarnings("unchecked")
    void usesTheCanonicalJ1FailClosedDefaultWhenAGateRowIsMissing() {
        when(mapper.killSwitchStates()).thenReturn(List.of(
                Map.of("gateKey", "withdraw", "settingValue", "enabled"),
                Map.of("gateKey", "staking"),
                Map.of("gateKey", "genesis", "settingValue", "enabled"),
                Map.of("gateKey", "exchange", "settingValue", "disabled"),
                Map.of("gateKey", "trial", "settingValue", "enabled")));

        List<Map<String, Object>> gates =
                (List<Map<String, Object>>) service.radar().getData().get("killSwitches");

        Map<String, Object> staking = gates.stream()
                .filter(gate -> "staking".equals(gate.get("key")))
                .findFirst()
                .orElseThrow();
        Map<String, Object> exchange = gates.stream()
                .filter(gate -> "exchange".equals(gate.get("key")))
                .findFirst()
                .orElseThrow();
        assertThat(staking).containsEntry("enabled", false).containsEntry("defaulted", true);
        assertThat(exchange).containsEntry("enabled", false).containsEntry("defaulted", false);
    }

    @Test
    void rejectsAnInvalidExplicitJ1GateValue() {
        when(mapper.killSwitchStates()).thenReturn(List.of(
                Map.of("gateKey", "withdraw", "settingValue", "enabled"),
                Map.of("gateKey", "staking", "settingValue", "unknown"),
                Map.of("gateKey", "genesis", "settingValue", "enabled"),
                Map.of("gateKey", "exchange", "settingValue", "enabled"),
                Map.of("gateKey", "trial", "settingValue", "enabled")));

        assertThatThrownBy(service::radar)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B5_KILL_SWITCH_SOURCE_INVALID");
    }

    @Test
    void rejectsAnIncompleteJ1GateProjection() {
        when(mapper.killSwitchStates()).thenReturn(List.of(
                Map.of("gateKey", "withdraw", "settingValue", "enabled"),
                Map.of("gateKey", "staking"),
                Map.of("gateKey", "genesis", "settingValue", "enabled"),
                Map.of("gateKey", "exchange", "settingValue", "enabled")));

        assertThatThrownBy(service::radar)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B5_KILL_SWITCH_SOURCE_UNAVAILABLE");
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
    void failsClosedWhenRiskSeverityOrWithdrawalStatusIsUnknown() {
        when(mapper.unknownSeverityCount(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(1L);
        assertThatThrownBy(service::radar)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B5_UNKNOWN_SEVERITY");

        when(mapper.unknownSeverityCount(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenReturn(0L);
        when(mapper.unknownWithdrawalStatusCount()).thenReturn(1L);
        assertThatThrownBy(service::radar)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B5_UNKNOWN_WITHDRAWAL_STATUS");
    }

    @Test
    void failsClosedOnMissingNegativeOrFractionalSourceNumbers() {
        when(mapper.abnormalAccountCount()).thenReturn(-1L);
        assertThatThrownBy(service::radar)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B5_SOURCE_COUNT_INVALID");

        when(mapper.abnormalAccountCount()).thenReturn(3L);
        when(mapper.withdrawalBacklog()).thenReturn(List.of(
                Map.of("state", "submitted", "count", new BigDecimal("1.5"), "amountUsdt", 10, "overSlaCount", 0),
                Map.of("state", "review-passed", "count", 0, "amountUsdt", 0, "overSlaCount", 0),
                Map.of("state", "processing", "count", 0, "amountUsdt", 0, "overSlaCount", 0)));
        assertThatThrownBy(service::radar)
                .isInstanceOf(BizException.class)
                .hasMessageContaining("B5_SOURCE_COUNT_INVALID");
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

    @Test
    void inboxQueriesAreScopedToTheAuthenticatedSubscriber() {
        try {
            UsernamePasswordAuthenticationToken alice = new UsernamePasswordAuthenticationToken(
                    "principal-a", "n/a", List.of());
            alice.setDetails(Map.of("username", "risk-alice"));
            SecurityContextHolder.getContext().setAuthentication(alice);
            when(mapper.subscriberInbox("risk-alice", 100)).thenReturn(List.of(Map.of("id", 71L)));

            assertThat(service.alertInbox().getData()).containsExactly(Map.of("id", 71L));
            verify(mapper).subscriberInbox("risk-alice", 100);

            UsernamePasswordAuthenticationToken bob = new UsernamePasswordAuthenticationToken(
                    "principal-b", "n/a", List.of());
            bob.setDetails(Map.of("username", "risk-bob"));
            SecurityContextHolder.getContext().setAuthentication(bob);
            service.alertInbox();
            verify(mapper).subscriberInbox("risk-bob", 100);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }
}

package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import ffdd.opsconsole.finance.dto.PayoutVndChannelUpdateRequest;
import ffdd.opsconsole.finance.dto.PayoutVndConfigUpdateRequest;
import ffdd.opsconsole.finance.mapper.VietnamPaymentMapper;
import ffdd.opsconsole.platform.application.A2ReplayContext;
import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PayoutVndConfigServiceTest {
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final VietnamPaymentMapper vietnam = mock(VietnamPaymentMapper.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final PayoutVndProviderProperties providerProperties = new PayoutVndProviderProperties();
    private final ffdd.opsconsole.finance.hdpay.HdPayPayoutReadiness payoutReadiness = mock(ffdd.opsconsole.finance.hdpay.HdPayPayoutReadiness.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-08T08:00:00Z"), ZoneOffset.UTC);
    private final PayoutVndConfigService service = new PayoutVndConfigService(
            config, vietnam, coverage, audit, new ObjectMapper(), clock, providerProperties, payoutReadiness);

    @BeforeEach
    void setUp() {
        when(payoutReadiness.ready()).thenReturn(true);
        when(config.activeValue(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("4"));
        when(config.activeValue(PayoutVndConfigService.VALUES_KEY)).thenReturn(Optional.of(values(false)));
        when(config.activeValue("finance.payout_vnd.provider_ready")).thenReturn(Optional.of("false"));
        when(vietnam.findFxQuoteConfig()).thenReturn(Map.of(
                "baseRateVndPerUsdt", new BigDecimal("26000"),
                "buySpreadPct", new BigDecimal("1.50"),
                "version", 7L));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("125"), new BigDecimal("100"), true,
                new BigDecimal("1250"), new BigDecimal("1000"), BigDecimal.ONE,
                new BigDecimal("1250"), new BigDecimal("1000")));
    }

    @Test void configuredFlagAloneCannotEnableActualPayout() {
        when(config.activeValue("finance.payout_vnd.provider_ready")).thenReturn(Optional.of("true"));
        when(payoutReadiness.ready()).thenReturn(false);
        assertThat(service.overview().getData()).containsEntry("providerReady", false).containsEntry("payoutConfigured", false);
    }

    @Test void sharedReadyConfigurationIgnoresLegacyDisabledFlags() {
        assertThat(service.overview().getData()).containsEntry("providerReady",true).containsEntry("channelEnabled",true);
        verify(config, never()).activeValue("finance.payout_vnd.provider_ready");
    }

    @Test
    void overviewCombinesD6SingleSourceWithServerCanonicalD7Aggregate() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("version", 4L)
                .containsEntry("baseRateVndPerUsdt", new BigDecimal("26000"))
                .containsEntry("buySpreadPct", new BigDecimal("1.50"))
                .containsEntry("channelEnabled", true)
                .containsEntry("providerReady", true)
                .containsEntry("providerStatusAvailable", true)
                .containsEntry("sandboxAvailable", false)
                .containsKeys("defaults", "effectiveAt", "sources");
    }

    @Test
    void missingOrMalformedServerConfigFailsClosed() {
        when(config.activeValue(PayoutVndConfigService.VALUES_KEY)).thenReturn(Optional.of("{}"));

        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isEqualTo(503);
        assertThat(result.getMessage()).isEqualTo("D7_CONFIG_INVALID");
    }

    @Test
    void staleVersionRejectsWithoutPartialWriteOrAudit() {
        when(config.activeValueForUpdate(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("5"));

        ApiResult<Map<String, Object>> result = service.update(validUpdate(4L));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("D7_CONFIG_VERSION_CONFLICT");
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void invalidFeeAndLimitRelationshipFailsClosed() {
        PayoutVndConfigUpdateRequest invalid = new PayoutVndConfigUpdateRequest(
                new BigDecimal("1.5"), 10, new BigDecimal("2"), new BigDecimal("1"),
                new BigDecimal("20"), new BigDecimal("10"), new BigDecimal("20"),
                new BigDecimal("5000"), 4L, "invalid fee relationship", false);

        ApiResult<Map<String, Object>> result = service.update(invalid);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("D7_FEE_LIMIT_RELATION_INVALID");
    }

    @Test
    void valuesThatBypassTheUiStepContractAreRejectedByTheServer() {
        PayoutVndConfigUpdateRequest invalidStep = new PayoutVndConfigUpdateRequest(
                new BigDecimal("1.5"), 10, new BigDecimal("2.01"), new BigDecimal("1.01"),
                new BigDecimal("0.01"), new BigDecimal("25.01"), new BigDecimal("20.01"),
                new BigDecimal("5000.01"), 4L, "reject values outside declared steps", false);

        ApiResult<Map<String, Object>> result = service.update(invalidStep);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("D7_CONFIG_VALUES_OUT_OF_RANGE");
    }

    @Test void legacyEnableEndpointIsRetiredWithoutWritingConfiguration() {
        var result = service.updateChannel(new PayoutVndChannelUpdateRequest(true, 4L, "legacy enable"));
        assertThat(result.getCode()).isEqualTo(410);
        assertThat(result.getMessage()).isEqualTo("D7_CHANNEL_USES_SHARED_HDPAY_CONFIG");
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test void legacyDisableEndpointCannotRecreateAnIndependentSwitch() {
        var result = service.updateChannel(new PayoutVndChannelUpdateRequest(false, 4L, "legacy disable"));
        assertThat(result.getCode()).isEqualTo(410);
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void previewAndD6SharedGateStillBlockCoverageBelowRedline() {
        when(config.activeValueForUpdate(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("4"));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("90"), new BigDecimal("100"), true,
                new BigDecimal("900"), new BigDecimal("1000"), BigDecimal.ONE));

        ApiResult<Map<String, Object>> result = service.previewUpdate(amplifyingUpdate());
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("D7_TREASURY_COVERAGE_BLOCKED");
        assertThat(service.coverageHealthy()).isFalse();
        assertThat(service.update(amplifyingUpdate()).getMessage()).isEqualTo("D7_PAYOUT_VND_CONFIG_APPROVAL_REQUIRED");
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void replayRejectsConfirmedCoverageBreachUsingOneExactSnapshot() {
        when(config.activeValueForUpdate(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("4"));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("100.00"), new BigDecimal("100"), true,
                new BigDecimal("999.96"), new BigDecimal("1000"), BigDecimal.ONE,
                new BigDecimal("999.96"), new BigDecimal("1000"), null, true));

        A2ReplayContext.enterReplay("WO-D7-1");
        try {
            assertThatThrownBy(() -> service.replayUpdate(amplifyingUpdate()))
                    .isInstanceOfSatisfying(PayoutVndConfigService.ConfirmedCoverageBreach.class,
                            failure -> assertThat(failure.getCode()).isEqualTo(409))
                    .hasMessage("D7_TREASURY_COVERAGE_BLOCKED");
        } finally {
            A2ReplayContext.exitReplay();
        }
        verify(coverage).snapshot();
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
        verify(audit, never()).recordRequired(any());
    }

    @Test
    void replayKeepsUnavailableCoverageRecoverable() {
        when(config.activeValueForUpdate(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("4"));
        var healthy = coverage.snapshot();
        var low = new BigDecimal("90");
        var line = new BigDecimal("100");
        when(coverage.snapshot())
                .thenReturn(null,
                        new TreasuryCoverageSnapshot(low, line, false, low, line, BigDecimal.ONE),
                        new TreasuryCoverageSnapshot(null, line, true, low, line, BigDecimal.ONE),
                        new TreasuryCoverageSnapshot(low, null, true, low, line, BigDecimal.ONE),
                        new TreasuryCoverageSnapshot(low, line, true, low, line, BigDecimal.ONE, low, BigDecimal.ZERO),
                        new TreasuryCoverageSnapshot(low, line, true, low, line, BigDecimal.ONE, low.negate(), line),
                        new TreasuryCoverageSnapshot(low, line, true, low, line, BigDecimal.ONE, null, line),
                        new TreasuryCoverageSnapshot(low, line, true, low, line, BigDecimal.ONE, low, null))
                .thenThrow(new IllegalStateException("coverage unavailable"))
                .thenReturn(healthy);

        A2ReplayContext.enterReplay("WO-D7-2");
        try {
            var request = amplifyingUpdate();
            for (int attempt = 0; attempt < 9; attempt++) {
                var blocked = service.replayUpdate(request);
                assertThat(blocked.getCode()).isEqualTo(409);
                assertThat(blocked.getMessage()).isEqualTo("D7_TREASURY_COVERAGE_BLOCKED");
            }
            verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
            verify(audit, never()).recordRequired(any());

            var recovered = service.replayUpdate(request);
            assertThat(recovered.getCode()).isZero();
            assertThat(recovered.getData()).containsEntry("version", 5L).containsEntry("quoteTtlMinWithdraw", 11);
        } finally {
            A2ReplayContext.exitReplay();
        }
        verify(config).upsertAdminValue(
                PayoutVndConfigService.VERSION_KEY, "5", "NUMBER", "finance", "D7 payout VND config version");
        verify(audit).recordRequired(any());
    }

    @Test void obsoleteProviderFlagFailuresCannotBlockSharedConfiguration() {
        when(config.activeValue("finance.payout_vnd.provider_ready")).thenThrow(new IllegalStateException("retired"));
        assertThat(service.overview().getData()).containsEntry("providerReady",true).containsEntry("channelEnabled",true);
        verify(config, never()).activeValue("finance.payout_vnd.provider_ready");
    }

    @Test void missingLegacyFlagsAreNotRequiredButSharedConfigurationStillIs() {
        when(config.activeValue(PayoutVndConfigService.VALUES_KEY)).thenReturn(Optional.of(values(false).replace(",\"channelEnabled\":false", "")));
        assertThat(service.overview().getData()).containsEntry("providerReady",true);
        when(payoutReadiness.ready()).thenReturn(false);
        assertThat(service.overview().getData()).containsEntry("providerReady",false).containsEntry("channelEnabled",false);
    }

    @Test
    void validWholeAggregateUpdateAdvancesVersionAndRequiresAudit() {
        when(config.activeValueForUpdate(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("4"));

        ApiResult<Map<String, Object>> result = service.update(validUpdate(4L));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("version", 5L);
        verify(config).upsertAdminValue(
                PayoutVndConfigService.VERSION_KEY, "5", "NUMBER", "finance", "D7 payout VND config version");
        verify(audit).recordRequired(any());
    }

    private PayoutVndConfigUpdateRequest validUpdate(long version) {
        return new PayoutVndConfigUpdateRequest(
                new BigDecimal("1.75"), 9, new BigDecimal("1.5"), new BigDecimal("1.2"),
                new BigDecimal("2"), new BigDecimal("30"), new BigDecimal("30"),
                new BigDecimal("4500"), version, "tighten payout parameters", false);
    }

    private PayoutVndConfigUpdateRequest amplifyingUpdate() {
        return new PayoutVndConfigUpdateRequest(
                new BigDecimal("1.5"), 11, new BigDecimal("2"), BigDecimal.ONE,
                BigDecimal.ONE, new BigDecimal("25"), new BigDecimal("20"), new BigDecimal("5000"),
                4L, "extend payout quote lifetime", false);
    }

    private String values(boolean channelEnabled) {
        return """
                {"sellSpreadPct":1.5,"quoteTtlMinWithdraw":10,"requoteTolerancePct":2,
                 "feeRatePct":1,"feeMinUsd":1,"feeMaxUsd":25,"minAmountUsd":20,
                 "maxAmountUsd":5000,"channelEnabled":%s,"effectiveAt":1786176000000,
                 "lastUpdatedBy":"migration"}
                """.formatted(channelEnabled);
    }
}

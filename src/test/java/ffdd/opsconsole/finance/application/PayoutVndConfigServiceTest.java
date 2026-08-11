package ffdd.opsconsole.finance.application;

import static org.assertj.core.api.Assertions.assertThat;
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
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-08T08:00:00Z"), ZoneOffset.UTC);
    private final PayoutVndConfigService service = new PayoutVndConfigService(
            config, vietnam, coverage, audit, new ObjectMapper(), clock, providerProperties);

    @BeforeEach
    void setUp() {
        when(config.activeValue(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("4"));
        when(config.activeValue(PayoutVndConfigService.VALUES_KEY)).thenReturn(Optional.of(values(false)));
        when(config.activeValue(PayoutVndConfigService.PROVIDER_READY_KEY)).thenReturn(Optional.of("false"));
        when(vietnam.findFxQuoteConfig()).thenReturn(Map.of(
                "baseRateVndPerUsdt", new BigDecimal("26000"),
                "buySpreadPct", new BigDecimal("1.50"),
                "version", 7L));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("125"), new BigDecimal("100"), true,
                new BigDecimal("1250"), new BigDecimal("1000"), BigDecimal.ONE,
                new BigDecimal("1250"), new BigDecimal("1000")));
    }

    @Test
    void overviewCombinesD6SingleSourceWithServerCanonicalD7Aggregate() {
        ApiResult<Map<String, Object>> result = service.overview();

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("version", 4L)
                .containsEntry("baseRateVndPerUsdt", new BigDecimal("26000"))
                .containsEntry("buySpreadPct", new BigDecimal("1.50"))
                .containsEntry("channelEnabled", false)
                .containsEntry("providerReady", false)
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

    @Test
    void providerUnavailableAlwaysRejectsChannelEnable() {
        when(config.activeValueForUpdate(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("4"));

        ApiResult<Map<String, Object>> result = service.updateChannel(
                new PayoutVndChannelUpdateRequest(true, 4L, "enable real payout channel"));

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("D7_PROVIDER_NOT_READY");
        verify(config, never()).upsertAdminValue(anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void disablingChannelRemainsAvailableWhenCoverageIsUnreliable() {
        when(config.activeValue(PayoutVndConfigService.VALUES_KEY)).thenReturn(Optional.of(values(true)));
        when(config.activeValueForUpdate(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("4"));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("0"), new BigDecimal("100"), false));

        ApiResult<Map<String, Object>> result = service.updateChannel(
                new PayoutVndChannelUpdateRequest(false, 4L, "stop payout channel now"));

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("channelEnabled", false);
        verify(audit).recordRequired(any());
    }

    @Test
    void amplifyingConfigurationRejectsUnreliableOrBelowRedlineCoverage() {
        when(config.activeValueForUpdate(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("4"));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("90"), new BigDecimal("100"), true));
        PayoutVndConfigUpdateRequest amplifying = new PayoutVndConfigUpdateRequest(
                new BigDecimal("1.0"), 20, new BigDecimal("3"), new BigDecimal("0.5"),
                new BigDecimal("0.5"), new BigDecimal("20"), new BigDecimal("10"),
                new BigDecimal("6000"), 4L, "amplify payout capacity", false);

        ApiResult<Map<String, Object>> result = service.update(amplifying);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("D7_TREASURY_COVERAGE_BLOCKED");
    }

    @Test
    void amplifyingConfigurationRejectsZeroLiabilityCoverageInsteadOfTreatingItAsHealthy() {
        when(config.activeValueForUpdate(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("4"));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("100"), new BigDecimal("70"), true,
                new BigDecimal("1000"), BigDecimal.ZERO, BigDecimal.ONE,
                new BigDecimal("1000"), BigDecimal.ZERO));
        PayoutVndConfigUpdateRequest amplifying = new PayoutVndConfigUpdateRequest(
                new BigDecimal("1.0"), 20, new BigDecimal("3"), new BigDecimal("0.5"),
                new BigDecimal("0.5"), new BigDecimal("20"), new BigDecimal("10"),
                new BigDecimal("6000"), 4L, "zero denominator must block payout", false);

        ApiResult<Map<String, Object>> result = service.update(amplifying);

        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("D7_TREASURY_COVERAGE_BLOCKED");
    }

    @Test
    void providerStatusReadFailureStillExposesAndAllowsStopLoss() {
        when(config.activeValue(PayoutVndConfigService.PROVIDER_READY_KEY))
                .thenThrow(new IllegalStateException("provider config store unavailable"));
        when(config.activeValue(PayoutVndConfigService.VALUES_KEY)).thenReturn(Optional.of(values(true)));
        when(config.activeValueForUpdate(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("4"));

        ApiResult<Map<String, Object>> overview = service.overview();
        ApiResult<Map<String, Object>> disabled = service.updateChannel(
                new PayoutVndChannelUpdateRequest(false, 4L, "stop payout during provider outage"));

        assertThat(overview.getCode()).isZero();
        assertThat(overview.getData())
                .containsEntry("providerReady", false)
                .containsEntry("providerStatusAvailable", false)
                .containsEntry("channelEnabled", true);
        assertThat(disabled.getCode()).isZero();
        assertThat(disabled.getData()).containsEntry("channelEnabled", false);
    }

    @Test
    void unavailableProviderStatusFailsClosedForChannelEnable() {
        when(config.activeValue(PayoutVndConfigService.PROVIDER_READY_KEY)).thenReturn(Optional.empty());
        when(config.activeValueForUpdate(PayoutVndConfigService.VERSION_KEY)).thenReturn(Optional.of("4"));

        ApiResult<Map<String, Object>> enabled = service.updateChannel(
                new PayoutVndChannelUpdateRequest(true, 4L, "enable must fail during provider outage"));

        assertThat(enabled.getCode()).isEqualTo(409);
        assertThat(enabled.getMessage()).isEqualTo("D7_PROVIDER_NOT_READY");
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

    private String values(boolean channelEnabled) {
        return """
                {"sellSpreadPct":1.5,"quoteTtlMinWithdraw":10,"requoteTolerancePct":2,
                 "feeRatePct":1,"feeMinUsd":1,"feeMaxUsd":25,"minAmountUsd":20,
                 "maxAmountUsd":5000,"channelEnabled":%s,"effectiveAt":1786176000000,
                 "lastUpdatedBy":"migration"}
                """.formatted(channelEnabled);
    }
}

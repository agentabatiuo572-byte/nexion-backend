package ffdd.opsconsole.risk.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.platform.facade.PlatformConfigFacade;
import ffdd.opsconsole.risk.dto.RiskReleaseParamUpdateRequest;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageFacade;
import ffdd.opsconsole.treasury.facade.TreasuryCoverageSnapshot;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RiskReleaseParamsServiceTest {
    private final PlatformConfigFacade config = mock(PlatformConfigFacade.class);
    private final TreasuryCoverageFacade coverage = mock(TreasuryCoverageFacade.class);
    private final RiskReleaseParamsService service = new RiskReleaseParamsService(config,
            mock(AdminIdempotencyService.class), mock(AuditLogService.class), coverage, false);

    @BeforeEach
    void config() {
        Map<String, String> values = Map.of(
                "freePhoneSlotsPerCluster", "1", "duplicateAccountPendingFrom", "2",
                "duplicateAccountFreezeFrom", "3", "pendingReleaseHours", "24",
                "appAttestationReleaseHours", "1", "releaseMode", "attest_or_manual",
                "freeSlotRequiresBinding", "true");
        when(config.activeValue(anyString())).thenAnswer(invocation -> {
            String key = invocation.getArgument(0);
            if (key.endsWith("version")) return Optional.of("4");
            return values.entrySet().stream().filter(entry -> key.endsWith(entry.getKey()))
                    .map(Map.Entry::getValue).findFirst();
        });
        when(config.activeValueForUpdate("risk.k1.release.version")).thenReturn(Optional.of("4"));
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("120"), new BigDecimal("105"), true));
    }

    @Test
    void staleReleaseParamSnapshotCannotSilentlyOverwriteCurrentAggregate() {
        assertThatThrownBy(() -> service.updateOnce("pendingReleaseHours", "48",
                new RiskReleaseParamUpdateRequest("48", 3L,
                        "stale operator snapshot must fail", "superadmin")))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        org.assertj.core.api.Assertions.assertThat(ex.getMessage())
                                .isEqualTo("K1_RELEASE_VERSION_CONFLICT"));
    }

    @Test
    void unsignedJanusCarrierForcesManualOnlyAndRejectsFakeAutomaticMode() {
        assertThat(service.rows()).filteredOn(row -> "releaseMode".equals(row.get("key")))
                .singleElement().satisfies(row -> assertThat(row)
                        .containsEntry("value", "manual_only").containsEntry("adjustable", false));
        assertThat(service.manualOnly()).isTrue();
        assertThatThrownBy(() -> service.update("releaseMode", "k1-attest-hold",
                new RiskReleaseParamUpdateRequest("attest_or_manual", 4L,
                        "carrier trust is not signed", "superadmin")))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("K1_TRUSTED_ATTESTATION_HOLD"));
    }

    @Test
    void looseningReleaseParamsFailsClosedBelowB1CoverageRedline() {
        when(coverage.snapshot()).thenReturn(new TreasuryCoverageSnapshot(
                new BigDecimal("100"), new BigDecimal("105"), true));

        assertThatThrownBy(() -> service.updateOnce("duplicateAccountPendingFrom", "3",
                new RiskReleaseParamUpdateRequest("3", 4L,
                        "loosening requires coverage gate", "superadmin")))
                .isInstanceOfSatisfying(BizException.class, ex ->
                        assertThat(ex.getMessage()).isEqualTo("COVERAGE_BELOW_REDLINE"));
    }
}

package ffdd.opsconsole.finance.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.finance.mapper.EarningsReleaseMapper;
import ffdd.opsconsole.risk.application.RiskReleaseParamsService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.util.List;

class JanusAppliedProofReleaseTest {
    private final EarningsReleaseMapper mapper = mock(EarningsReleaseMapper.class);
    private final RiskReleaseParamsService params = mock(RiskReleaseParamsService.class);
    private final EarningsReleaseService service = new EarningsReleaseService(
            mapper, params, mock(AdminIdempotencyService.class), mock(AuditLogService.class),
            mock(FundsSandboxProfileGuard.class));

    @Test
    void consumesPersistedJanusProofExactlyOnceBeforeCreditingOnlineTime() {
        EarningsReleaseService.TrustedAttestationProof proof =
                new EarningsReleaseService.TrustedAttestationProof(
                        "proof-01", 42L, "device-1", 7L, "ACTIVATED",
                        "JANUS_PRODUCTION_EXECUTOR", "a".repeat(64));
        when(params.manualOnly()).thenReturn(false);
        when(params.freeSlotRequiresBinding()).thenReturn(true);
        when(mapper.proofIdentityMatches(42L,"JANUS_PRODUCTION_EXECUTOR")).thenReturn(1);
        when(mapper.trustedDeviceBinding(42L, "device-1")).thenReturn(1);
        when(mapper.consumeAppliedProof("proof-01", 42L, "device-1", 7L,
                "JANUS_PRODUCTION_EXECUTOR", "a".repeat(64))).thenReturn(1, 0);
        when(mapper.recordAttestation(42L, "device-1", "PRODUCTION")).thenReturn(1);
        when(mapper.attestedSeconds(42L, "PRODUCTION")).thenReturn(0L);

        service.recordTrustedAttestation(proof);
        service.recordTrustedAttestation(proof);

        verify(mapper).recordAttestation(42L, "device-1", "PRODUCTION");
    }

    @Test
    void missingPersistedProofCannotMintTrustedOnlineTime() {
        EarningsReleaseService.TrustedAttestationProof proof =
                new EarningsReleaseService.TrustedAttestationProof(
                        "proof-missing", 42L, "device-1", 8L, "ACTIVATED",
                        "JANUS_PRODUCTION_EXECUTOR", "b".repeat(64));
        when(params.manualOnly()).thenReturn(false);
        when(params.freeSlotRequiresBinding()).thenReturn(false);
        when(mapper.proofIdentityMatches(42L,"JANUS_PRODUCTION_EXECUTOR")).thenReturn(1);
        when(mapper.consumeAppliedProof(anyString(), any(), anyString(), any(), anyString(), anyString()))
                .thenReturn(0);

        service.recordTrustedAttestation(proof);

        verify(mapper, never()).recordAttestation(any(), anyString(), anyString());
    }

    @Test
    void sandboxProofCanNeverAccumulateK1TimeOrReleaseAnyEarnings() {
        var proof = new EarningsReleaseService.TrustedAttestationProof(
                "proof-sandbox",42L,"sandbox-device-1",9L,"ACTIVATED",
                "JANUS_SANDBOX_EXECUTOR","c".repeat(64));
        service.recordTrustedAttestation(proof);

        verify(mapper,never()).recordAttestation(any(),anyString(),anyString());
        verify(mapper,never()).consumeAppliedProof(anyString(),any(),anyString(),any(),anyString(),anyString());
        verify(mapper,never()).release("prod-entry","attest");
        verify(mapper,never()).releaseFromJanusProof(anyString(),anyString());
    }

    @Test
    void productionProofCanReleaseProductionEntryThroughSqlCas() {
        var proof = new EarningsReleaseService.TrustedAttestationProof(
                "proof-production",42L,"device-1",10L,"ACTIVATED",
                "JANUS_PRODUCTION_EXECUTOR","d".repeat(64));
        when(params.manualOnly()).thenReturn(false);
        when(params.freeSlotRequiresBinding()).thenReturn(false);
        when(mapper.proofIdentityMatches(42L,"JANUS_PRODUCTION_EXECUTOR")).thenReturn(1);
        when(params.attestationHours()).thenReturn(1);
        when(params.freeSlots()).thenReturn(1);
        when(params.releaseWindowHours()).thenReturn(24);
        when(mapper.consumeAppliedProof(anyString(),any(),anyString(),any(),anyString(),anyString())).thenReturn(1);
        when(mapper.recordAttestation(42L,"device-1","PRODUCTION")).thenReturn(1);
        when(mapper.attestedSeconds(42L,"PRODUCTION")).thenReturn(3600L);
        when(mapper.protectedEntries(42L,"PRODUCTION")).thenReturn(List.of(new EarningsReleaseMapper.ProtectedEntry(
                "prod-entry",42L,"USER:42","USDT",BigDecimal.ONE,"pending_review")));
        when(mapper.lockUserScope(42L)).thenReturn(42L);
        when(mapper.releaseFromJanusProof("prod-entry","JANUS_PRODUCTION_EXECUTOR")).thenReturn(1);

        service.recordTrustedAttestation(proof);

        verify(mapper).releaseFromJanusProof("prod-entry","JANUS_PRODUCTION_EXECUTOR");
    }
}

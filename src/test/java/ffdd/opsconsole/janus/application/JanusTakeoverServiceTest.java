package ffdd.opsconsole.janus.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.janus.domain.JanusRemoteTargetRepository;
import ffdd.opsconsole.finance.application.EarningsReleaseService;
import ffdd.opsconsole.janus.dto.JanusTakeoverProgressRequest;
import ffdd.opsconsole.janus.mapper.JanusTakeoverMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import ffdd.opsconsole.janus.domain.JanusRemoteTargetView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JanusTakeoverServiceTest {
    private final JanusTakeoverMapper mapper = mock(JanusTakeoverMapper.class);
    private final JanusRemoteTargetRepository targets = mock(JanusRemoteTargetRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final JanusAppliedProofVerifier proofVerifier = mock(JanusAppliedProofVerifier.class);
    private final EarningsReleaseService earnings = mock(EarningsReleaseService.class);
    private final JanusTakeoverService service = new JanusTakeoverService(mapper, targets, audit, idempotency, proofVerifier, earnings);
    private Map<String,Object> row;

    @BeforeEach
    void setUp() {
        row = new LinkedHashMap<>();
        row.put("sid", "SID-1"); row.put("phase", "LOADING"); row.put("commandId", "cmd-1");
        row.put("commandType", "ACTIVATE"); row.put("commandVersion", 3L); row.put("rowVersion", 7L);
        row.put("expectedTargetId", "approved"); row.put("expectedTargetVersion", 2);
        row.put("expectedTargetCatalogVersion", 9L); row.put("reconciliationId", null); row.put("reconciledAt", null);
        when(mapper.owns(5L, "SID-1", "device-1")).thenReturn(1);
        when(mapper.findForUpdate("SID-1")).thenReturn(row);
        when(mapper.find("SID-1")).thenReturn(row);
    }

    @Test
    void loadingCannotSkipHandoffPhasesAndClaimSuccess() {
        ApiResult<Map<String,Object>> result = service.progress(5L, "SID-1", progress("SUCCEEDED", true));
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("K6_TAKEOVER_ILLEGAL_PHASE_TRANSITION");
        verify(mapper, never()).progress(any(), anyLong(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void succeededRequiresExactApprovedTargetAndReceipt() {
        row.put("phase", "HANDOFF_ACKED");
        JanusTakeoverProgressRequest missingReceipt = new JanusTakeoverProgressRequest(
                "device-1", "cmd-1", 3L, "e".repeat(64), 1L, "SUCCEEDED", "approved", 2, 9L, 3L, "app-1", null,
                "cmd-1", 3L, 1L, null, null, null, null, null, null, null, null, null);
        ApiResult<Map<String,Object>> result = service.progress(5L, "SID-1", missingReceipt);
        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("K6_TAKEOVER_SUCCESS_EVIDENCE_REQUIRED");
        verify(mapper, never()).progress(any(), anyLong(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void userOwnedDeviceCannotAdvanceWithForgedReceipt() {
        row.put("phase", "HANDOFF_ACKED");
        JanusTakeoverProgressRequest forged = progress("SUCCEEDED", true);
        when(proofVerifier.verify(5L, "SID-1", row, forged)).thenReturn(
                JanusAppliedProofVerifier.Verification.rejected("JANUS_APPLIED_PROOF_UNTRUSTED"));

        ApiResult<Map<String,Object>> result = service.progress(5L, "SID-1", forged);

        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("JANUS_APPLIED_PROOF_UNTRUSTED");
        assertThat(row).containsEntry("phase", "HANDOFF_ACKED");
        verify(mapper, never()).progress(any(), anyLong(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
        verify(earnings, never()).recordTrustedAttestation(any(EarningsReleaseService.TrustedAttestationProof.class));
    }

    @Test
    void trustedAppliedProofAdvancesAndFeedsK1ExactlyAfterCas() {
        row.put("phase", "HANDOFF_ACKED");
        JanusTakeoverProgressRequest applied = progress("SUCCEEDED", true);
        when(proofVerifier.verify(5L,"SID-1",row,applied)).thenReturn(
                new JanusAppliedProofVerifier.Verification(true,false,null,"JAP-1","a".repeat(64),"PRODUCTION"));
        when(mapper.progress(any(),anyLong(),any(),anyLong(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any(),any())).thenReturn(1);

        ApiResult<Map<String,Object>> result=service.progress(5L,"SID-1",applied);

        assertThat(result.getCode()).isZero();
        verify(earnings).recordTrustedAttestation(any(EarningsReleaseService.TrustedAttestationProof.class));
    }

    @Test
    void claimedCommandRemainsDeliverableAfterIntermediateProgress() {
        row.put("phase","HANDOFF_MERGING");
        when(targets.find("approved",2)).thenReturn(Optional.of(new JanusRemoteTargetView(
                9L,"approved",2,"ACTIVE","approved","https://approved.example/path",
                "https://approved.example","ADMIN","owner",1,2,"admin","reason long enough",
                "impact long enough",0,0,0,0)));
        ApiResult<Map<String,Object>> result=service.pending(5L,"SID-1","device-1");
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("hasCommand",true);
    }

    @Test
    void staleCommandIsRejectedBeforeStateTransition() {
        JanusTakeoverProgressRequest stale = new JanusTakeoverProgressRequest(
                "device-1", "old", 2L, "e".repeat(64), 1L, "FAILED", null, null, null, null, "app-1", null,
                null, null, null, "DELIVERY", "delivery", "not delivered", null, null, null, null, null, null);
        ApiResult<Map<String,Object>> result = service.progress(5L, "SID-1", stale);
        assertThat(result.getCode()).isEqualTo(409);
        assertThat(result.getMessage()).isEqualTo("K6_TAKEOVER_STALE_COMMAND");
    }

    @Test
    void readAppliedNeverCreatesAReconciliationCommand() {
        ApiResult<Map<String,Object>> result = service.applied("SID-1", null);
        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("fresh", false);
        verify(mapper, never()).requestReconciliation(any(), anyLong(), any());
        verify(mapper, never()).findForUpdate(any());
    }

    @Test
    void reconciliationDriftIsProofVerifiedAndMovedToAuditableFailureHold() {
        row.put("phase", "SUCCEEDED");
        row.put("reconciliationId", "reconcile-1");
        JanusTakeoverProgressRequest drift = new JanusTakeoverProgressRequest(
                "device-1", "cmd-1", 3L, "e".repeat(64), 1L, "REVOKED", "none", 0, 0L, 3L, "app-1", "receipt-drift",
                "cmd-1", 3L, 1L, null, null, null, "reconcile-1", "PRODUCTION", "executor-1", "a".repeat(32),
                System.currentTimeMillis(), "b".repeat(64));
        when(proofVerifier.verify(5L, "SID-1", row, drift)).thenReturn(
                new JanusAppliedProofVerifier.Verification(true, false, null, "JAP-DRIFT",
                        "c".repeat(64), "PRODUCTION"));
        when(mapper.reconcileDrift(eq("SID-1"), eq("reconcile-1"), eq("cmd-1"), eq(3L),
                eq("none"), eq(0), eq(0L), eq(3L), eq("app-1"), eq("receipt-drift"))).thenReturn(1);

        ApiResult<Map<String,Object>> result = service.progress(5L, "SID-1", drift);

        assertThat(result.getCode()).isZero();
        verify(mapper).reconcileDrift(eq("SID-1"), eq("reconcile-1"), eq("cmd-1"), eq(3L),
                eq("none"), eq(0), eq(0L), eq(3L), eq("app-1"), eq("receipt-drift"));
        verify(mapper, never()).reconcile(any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any());
        verify(earnings, never()).recordTrustedAttestation(any(EarningsReleaseService.TrustedAttestationProof.class));
    }

    @Test
    void stoppedReconciliationIsPersistedAsManualReviewHoldWithoutAppliedProof() {
        row.put("phase", "SUCCEEDED");
        row.put("reconciliationId", "reconcile-1");
        JanusTakeoverProgressRequest hold = new JanusTakeoverProgressRequest(
                "device-1", "cmd-1", 3L, "e".repeat(64), 1L, "FAILED", null, null, null, null,
                "app-1", null, null, null, null, "JANUS_FOREGROUND_ABORTED_HOLD", "contract",
                "Executor stopped before terminal acceptance", "reconcile-1", null, null, null, null, null);
        when(mapper.reconcileHold("SID-1", "reconcile-1", "cmd-1", 3L)).thenReturn(1);

        ApiResult<Map<String,Object>> result = service.progress(5L, "SID-1", hold);

        assertThat(result.getCode()).isZero();
        verify(mapper).reconcileHold("SID-1", "reconcile-1", "cmd-1", 3L);
        verify(proofVerifier, never()).verify(anyLong(), any(), any(), any());
        verify(mapper, never()).reconcile(any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any());
        verify(mapper, never()).reconcileDrift(any(), any(), any(), anyLong(), any(), any(), any(), any(), any(), any());
    }

    private JanusTakeoverProgressRequest progress(String phase, boolean evidence) {
        return new JanusTakeoverProgressRequest(
                "device-1", "cmd-1", 3L, "e".repeat(64), 1L, phase,
                evidence ? "approved" : null, evidence ? 2 : null, evidence ? 9L : null,
                evidence ? 3L : null, evidence ? "app-1" : null, evidence ? "receipt-1" : null,
                evidence ? "cmd-1" : null, evidence ? 3L : null, evidence ? 1L : null,
                null, null, null, null, null, null, null, null, null);
    }
}

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
import ffdd.opsconsole.janus.dto.JanusTakeoverProgressRequest;
import ffdd.opsconsole.janus.mapper.JanusTakeoverMapper;
import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JanusTakeoverServiceTest {
    private final JanusTakeoverMapper mapper = mock(JanusTakeoverMapper.class);
    private final JanusRemoteTargetRepository targets = mock(JanusRemoteTargetRepository.class);
    private final AuditLogService audit = mock(AuditLogService.class);
    private final AdminIdempotencyService idempotency = mock(AdminIdempotencyService.class);
    private final JanusTakeoverService service = new JanusTakeoverService(mapper, targets, audit, idempotency);
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
                "device-1", "cmd-1", 3L, "SUCCEEDED", "approved", 2, 9L, 3L, "app-1", null,
                null, null, null, null);
        ApiResult<Map<String,Object>> result = service.progress(5L, "SID-1", missingReceipt);
        assertThat(result.getCode()).isEqualTo(422);
        assertThat(result.getMessage()).isEqualTo("K6_TAKEOVER_SUCCESS_EVIDENCE_REQUIRED");
        verify(mapper, never()).progress(any(), anyLong(), any(), anyLong(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void staleCommandIsRejectedBeforeStateTransition() {
        JanusTakeoverProgressRequest stale = new JanusTakeoverProgressRequest(
                "device-1", "old", 2L, "FAILED", null, null, null, null, "app-1", null,
                "DELIVERY", "delivery", "not delivered", null);
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

    private JanusTakeoverProgressRequest progress(String phase, boolean evidence) {
        return new JanusTakeoverProgressRequest(
                "device-1", "cmd-1", 3L, phase,
                evidence ? "approved" : null, evidence ? 2 : null, evidence ? 9L : null,
                evidence ? 3L : null, evidence ? "app-1" : null, evidence ? "receipt-1" : null,
                null, null, null, null);
    }
}

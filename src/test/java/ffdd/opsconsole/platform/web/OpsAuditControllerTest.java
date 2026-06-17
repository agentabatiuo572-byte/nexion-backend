package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.shared.audit.AuditLogQueryRequest;
import ffdd.opsconsole.shared.audit.AuditLogRecord;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.audit.AuditLogWriteRequest;
import ffdd.opsconsole.shared.audit.AuditStatsBucket;
import ffdd.opsconsole.shared.audit.AuditStatsQueryRequest;
import ffdd.opsconsole.shared.audit.AuditStatsSummaryResponse;
import ffdd.opsconsole.common.api.OpsErrorCode;
import ffdd.opsconsole.platform.dto.AuditExportRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OpsAuditControllerTest {
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final OpsAuditController controller = new OpsAuditController(auditLogService);

    @Test
    void logsDelegatesToA2AuditService() {
        AuditLogRecord record = new AuditLogRecord();
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of(record));

        ApiResult<List<AuditLogRecord>> result = controller.logs(new AuditLogQueryRequest());

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsExactly(record);
        verify(auditLogService).list(any(AuditLogQueryRequest.class));
    }

    @Test
    void traceLookupCapsLimitAtTwoHundred() {
        when(auditLogService.list(any(AuditLogQueryRequest.class))).thenReturn(List.of());

        controller.byTrace("trace-001");

        ArgumentCaptor<AuditLogQueryRequest> captor = ArgumentCaptor.forClass(AuditLogQueryRequest.class);
        verify(auditLogService).list(captor.capture());
        assertThat(captor.getValue().getTraceId()).isEqualTo("trace-001");
        assertThat(captor.getValue().getLimit()).isEqualTo(200);
    }

    @Test
    void exportRequiresReason() {
        AuditExportRequest request = new AuditExportRequest(null, Map.of("action", "LOGIN"));

        ApiResult<Map<String, Object>> result = controller.export("idem-1", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.REASON_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.REASON_REQUIRED.name());
    }

    @Test
    void exportRequiresIdempotencyKey() {
        AuditExportRequest request = new AuditExportRequest("incident review", Map.of("action", "LOGIN"));

        ApiResult<Map<String, Object>> result = controller.export(" ", request);

        assertThat(result.getCode()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.httpStatus());
        assertThat(result.getMessage()).isEqualTo(OpsErrorCode.IDEMPOTENCY_KEY_REQUIRED.name());
    }

    @Test
    void exportWritesA2AuditRecordWithReasonAndIdempotencyKey() {
        AuditExportRequest request = new AuditExportRequest("incident review", Map.of("action", "LOGIN"));

        ApiResult<Map<String, Object>> result = controller.export("idem-1", request);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData())
                .containsEntry("status", "CREATED")
                .containsEntry("idempotencyKey", "idem-1");

        ArgumentCaptor<AuditLogWriteRequest> captor = ArgumentCaptor.forClass(AuditLogWriteRequest.class);
        verify(auditLogService).record(captor.capture());
        assertThat(captor.getValue().getAction()).isEqualTo("A2_AUDIT_EXPORTED");
        assertThat(captor.getValue().getResourceType()).isEqualTo("A2_AUDIT_EXPORT");
        assertThat(captor.getValue().getRiskLevel()).isEqualTo("MEDIUM");
        assertThat(detailMap(captor.getValue().getDetail()))
                .containsEntry("reason", "incident review")
                .containsEntry("idempotencyKey", "idem-1");
    }

    @Test
    void statsEndpointsDelegateToAuditService() {
        AuditStatsSummaryResponse summary = new AuditStatsSummaryResponse();
        when(auditLogService.summary(any(AuditStatsQueryRequest.class))).thenReturn(summary);
        when(auditLogService.topActions(any(AuditStatsQueryRequest.class)))
                .thenReturn(List.of(new AuditStatsBucket("LOGIN", 3L)));
        when(auditLogService.topServices(any(AuditStatsQueryRequest.class)))
                .thenReturn(List.of(new AuditStatsBucket("nexion-backend", 2L)));
        when(auditLogService.topUsers(any(AuditStatsQueryRequest.class)))
                .thenReturn(List.of(new AuditStatsBucket("1001", 1L)));

        assertThat(controller.summary(new AuditStatsQueryRequest()).getData()).isSameAs(summary);
        assertThat(controller.actions(new AuditStatsQueryRequest()).getData()).hasSize(1);
        assertThat(controller.services(new AuditStatsQueryRequest()).getData()).hasSize(1);
        assertThat(controller.users(new AuditStatsQueryRequest()).getData()).hasSize(1);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> detailMap(Object detail) {
        return (Map<String, Object>) detail;
    }
}

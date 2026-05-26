package ffdd.system.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.common.api.ApiResult;
import ffdd.common.audit.AuditLogService;
import ffdd.common.audit.AuditStatsBucket;
import ffdd.common.audit.AuditStatsQueryRequest;
import ffdd.common.audit.AuditStatsSummaryResponse;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class SystemOpsControllerTest {
    private final AuditLogService auditLogService = mock(AuditLogService.class);
    private final SystemOpsController controller = new SystemOpsController(auditLogService);

    @Test
    void dashboardAggregatesAuditStatsWithBoundedDays() {
        AuditStatsSummaryResponse summary = new AuditStatsSummaryResponse();
        summary.setStartAt(LocalDateTime.parse("2026-05-01T00:00:00"));
        summary.setEndAt(LocalDateTime.parse("2026-05-08T00:00:00"));
        summary.setTotal(12L);
        when(auditLogService.summary(any(AuditStatsQueryRequest.class))).thenReturn(summary);
        when(auditLogService.topActions(any(AuditStatsQueryRequest.class)))
                .thenReturn(List.of(new AuditStatsBucket("PAYMENT_CALLBACK", 5L)));
        when(auditLogService.topServices(any(AuditStatsQueryRequest.class)))
                .thenReturn(List.of(new AuditStatsBucket("nexion-commerce-service", 5L)));
        when(auditLogService.topUsers(any(AuditStatsQueryRequest.class)))
                .thenReturn(List.of(new AuditStatsBucket("1001", 3L)));

        ApiResult<Map<String, Object>> result = controller.dashboard(200);

        assertThat(result.getCode()).isZero();
        assertThat(result.getData()).containsEntry("service", "nexion-system-service");
        assertThat(result.getData()).containsKeys("audit", "modules", "routes");
        Map<String, Object> audit = (Map<String, Object>) result.getData().get("audit");
        assertThat(audit).containsEntry("summary", summary);
        ArgumentCaptor<AuditStatsQueryRequest> query = ArgumentCaptor.forClass(AuditStatsQueryRequest.class);
        verify(auditLogService).summary(query.capture());
        assertThat(query.getValue().getDays()).isEqualTo(90);
    }
}

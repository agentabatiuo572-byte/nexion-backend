package ffdd.opsconsole.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

class AuditLogServiceTest {
    private final AuditLogMapper auditLogMapper = org.mockito.Mockito.mock(AuditLogMapper.class);
    private final AuditLogSanitizer sanitizer = new AuditLogSanitizer(new com.fasterxml.jackson.databind.ObjectMapper());
    private final ApplicationNameProperties applicationNameProperties = applicationNameProperties();
    private final AuditProperties auditProperties = new AuditProperties();
    private final AuditLogService service = new AuditLogService(
            auditLogMapper, sanitizer, applicationNameProperties, auditProperties);

    private ApplicationNameProperties applicationNameProperties() {
        ApplicationNameProperties properties = new ApplicationNameProperties();
        properties.setName("nexion-test-service");
        return properties;
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void recordWritesTraceAndSanitizedDetails() {
        MDC.put(AuditTraceContext.MDC_TRACE_ID, "trace-123");

        service.record(AuditLogWriteRequest.builder()
                .action("PAYMENT_RECONCILE")
                .resourceType("PAYMENT")
                .resourceId("PAY-1")
                .bizNo("PAY-1")
                .userId(1001L)
                .riskLevel("HIGH")
                .detail(Map.of("provider", "MOCK", "signature", "secret-signature"))
                .build());

        ArgumentCaptor<AuditLogMapper.AuditLogWrite> params = ArgumentCaptor.forClass(AuditLogMapper.AuditLogWrite.class);
        verify(auditLogMapper).insertAuditLog(params.capture());
        assertThat(params.getValue().traceId()).isEqualTo("trace-123");
        assertThat(params.getValue().serviceName()).isEqualTo("nexion-test-service");
        assertThat(params.getValue().action()).isEqualTo("PAYMENT_RECONCILE");
        assertThat(params.getValue().resourceType()).isEqualTo("PAYMENT");
        assertThat(params.getValue().resourceId()).isEqualTo("PAY-1");
        assertThat(params.getValue().bizNo()).isEqualTo("PAY-1");
        assertThat(params.getValue().userId()).isEqualTo(1001L);
        assertThat(params.getValue().result()).isEqualTo("SUCCESS");
        assertThat(params.getValue().riskLevel()).isEqualTo("HIGH");
        assertThat(params.getValue().detailJson())
                .contains("\"signature\":\"[REDACTED]\"")
                .doesNotContain("secret-signature");
    }

    @Test
    void listClampsLimitAndUsesOptionalFilters() {
        when(auditLogMapper.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        AuditLogQueryRequest query = new AuditLogQueryRequest();
        query.setTraceId("trace-123");
        query.setAction("PAYMENT_RECONCILE");
        query.setUserId(1001L);
        query.setLimit(1000);

        List<AuditLogRecord> records = service.list(query);

        assertThat(records).isEmpty();
        verify(auditLogMapper).list(
                eq("trace-123"),
                isNull(),
                eq("PAYMENT_RECONCILE"),
                isNull(),
                isNull(),
                isNull(),
                eq(1001L),
                isNull(),
                isNull(),
                isNull(),
                eq(200));
    }

    @Test
    void summaryUsesTimeWindowAndSafeParameterizedFilters() {
        when(auditLogMapper.countStats(any(), any(), any(), any(), any(), any(), any(), any())).thenReturn(5L);
        when(auditLogMapper.groupByResult(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());
        when(auditLogMapper.groupByRiskLevel(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of());

        AuditStatsQueryRequest query = new AuditStatsQueryRequest();
        query.setStartAt(LocalDateTime.parse("2026-05-01T00:00:00"));
        query.setEndAt(LocalDateTime.parse("2026-05-03T00:00:00"));
        query.setServiceName("nexion-backend");
        query.setAction("PAYMENT_CALLBACK");
        query.setRiskLevel("HIGH");
        query.setResult("SUCCESS");

        AuditStatsSummaryResponse response = service.summary(query);

        assertThat(response.getTotal()).isEqualTo(5L);
        assertThat(response.getStartAt()).isEqualTo(LocalDateTime.parse("2026-05-01T00:00:00"));
        assertThat(response.getEndAt()).isEqualTo(LocalDateTime.parse("2026-05-03T00:00:00"));
        verify(auditLogMapper).countStats(
                eq(LocalDateTime.parse("2026-05-01T00:00:00")),
                eq(LocalDateTime.parse("2026-05-03T00:00:00")),
                eq("nexion-backend"),
                eq("PAYMENT_CALLBACK"),
                eq("HIGH"),
                eq("SUCCESS"),
                isNull(),
                isNull());
    }

    @Test
    void topActionsCapsLimitAndUsesWhitelistedDimension() {
        when(auditLogMapper.topActions(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(new AuditStatsBucket("PAYMENT_CALLBACK", 7L)));

        AuditStatsQueryRequest query = new AuditStatsQueryRequest();
        query.setStartAt(LocalDateTime.parse("2026-05-01T00:00:00"));
        query.setEndAt(LocalDateTime.parse("2026-05-02T00:00:00"));
        query.setLimit(1000);

        List<AuditStatsBucket> buckets = service.topActions(query);

        assertThat(buckets).extracting(AuditStatsBucket::getKey).containsExactly("PAYMENT_CALLBACK");
        verify(auditLogMapper).topActions(
                eq(LocalDateTime.parse("2026-05-01T00:00:00")),
                eq(LocalDateTime.parse("2026-05-02T00:00:00")),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(50));
    }

    @Test
    void topUsersExcludesNullUsersAndCapsLimit() {
        when(auditLogMapper.topUsers(any(), any(), any(), any(), any(), any(), any(), any(), anyInt()))
                .thenReturn(List.of(new AuditStatsBucket("1001", 3L)));

        AuditStatsQueryRequest query = new AuditStatsQueryRequest();
        query.setStartAt(LocalDateTime.parse("2026-05-01T00:00:00"));
        query.setEndAt(LocalDateTime.parse("2026-05-02T00:00:00"));
        query.setLimit(0);

        service.topUsers(query);

        verify(auditLogMapper).topUsers(
                eq(LocalDateTime.parse("2026-05-01T00:00:00")),
                eq(LocalDateTime.parse("2026-05-02T00:00:00")),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                isNull(),
                eq(10));
    }
}

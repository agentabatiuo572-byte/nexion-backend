package ffdd.opsconsole.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

class AuditLogServiceTest {
    private final NamedParameterJdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(NamedParameterJdbcTemplate.class);
    private final AuditLogSanitizer sanitizer = new AuditLogSanitizer(new com.fasterxml.jackson.databind.ObjectMapper());
    private final AuditLogService service = new AuditLogService(jdbcTemplate, sanitizer, "nexion-test-service", true, false);

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

        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate).update(anyString(), params.capture());
        assertThat(params.getValue())
                .containsEntry("traceId", "trace-123")
                .containsEntry("serviceName", "nexion-test-service")
                .containsEntry("action", "PAYMENT_RECONCILE")
                .containsEntry("resourceType", "PAYMENT")
                .containsEntry("resourceId", "PAY-1")
                .containsEntry("bizNo", "PAY-1")
                .containsEntry("userId", 1001L)
                .containsEntry("result", "SUCCESS")
                .containsEntry("riskLevel", "HIGH");
        assertThat((String) params.getValue().get("detailJson"))
                .contains("\"signature\":\"[REDACTED]\"")
                .doesNotContain("secret-signature");
    }

    @Test
    void listClampsLimitAndUsesOptionalFilters() {
        when(jdbcTemplate.query(anyString(), anyMap(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        AuditLogQueryRequest query = new AuditLogQueryRequest();
        query.setTraceId("trace-123");
        query.setAction("PAYMENT_RECONCILE");
        query.setUserId(1001L);
        query.setLimit(1000);

        List<AuditLogRecord> records = service.list(query);

        assertThat(records).isEmpty();
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate).query(sql.capture(), params.capture(), any(org.springframework.jdbc.core.RowMapper.class));
        assertThat(sql.getValue()).contains("trace_id = :traceId", "action = :action", "user_id = :userId");
        assertThat(params.getValue()).containsEntry("limit", 200);
    }

    @Test
    void summaryUsesTimeWindowAndSafeParameterizedFilters() {
        when(jdbcTemplate.queryForObject(anyString(), anyMap(), eq(Long.class))).thenReturn(5L);
        when(jdbcTemplate.query(anyString(), anyMap(), any(org.springframework.jdbc.core.RowMapper.class)))
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
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate).queryForObject(sql.capture(), params.capture(), eq(Long.class));
        assertThat(sql.getValue())
                .contains("created_at >= :startAt")
                .contains("created_at < :endAt")
                .contains("service_name = :serviceName")
                .contains("action = :action")
                .contains("risk_level = :riskLevel")
                .contains("result = :result");
        assertThat(params.getValue())
                .containsEntry("serviceName", "nexion-backend")
                .containsEntry("action", "PAYMENT_CALLBACK")
                .containsEntry("riskLevel", "HIGH")
                .containsEntry("result", "SUCCESS");
    }

    @Test
    void topActionsCapsLimitAndUsesWhitelistedDimension() {
        when(jdbcTemplate.query(anyString(), anyMap(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(new AuditStatsBucket("PAYMENT_CALLBACK", 7L)));

        AuditStatsQueryRequest query = new AuditStatsQueryRequest();
        query.setStartAt(LocalDateTime.parse("2026-05-01T00:00:00"));
        query.setEndAt(LocalDateTime.parse("2026-05-02T00:00:00"));
        query.setLimit(1000);

        List<AuditStatsBucket> buckets = service.topActions(query);

        assertThat(buckets).extracting(AuditStatsBucket::getKey).containsExactly("PAYMENT_CALLBACK");
        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate).query(sql.capture(), params.capture(), any(org.springframework.jdbc.core.RowMapper.class));
        assertThat(sql.getValue()).contains("COALESCE(action, 'UNKNOWN') AS bucket_key");
        assertThat(sql.getValue()).contains("GROUP BY COALESCE(action, 'UNKNOWN')");
        assertThat(params.getValue()).containsEntry("limit", 50);
    }

    @Test
    void topUsersExcludesNullUsersAndCapsLimit() {
        when(jdbcTemplate.query(anyString(), anyMap(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(new AuditStatsBucket("1001", 3L)));

        AuditStatsQueryRequest query = new AuditStatsQueryRequest();
        query.setStartAt(LocalDateTime.parse("2026-05-01T00:00:00"));
        query.setEndAt(LocalDateTime.parse("2026-05-02T00:00:00"));
        query.setLimit(0);

        service.topUsers(query);

        ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> params = ArgumentCaptor.forClass(Map.class);
        verify(jdbcTemplate).query(sql.capture(), params.capture(), any(org.springframework.jdbc.core.RowMapper.class));
        assertThat(sql.getValue()).contains("user_id IS NOT NULL");
        assertThat(sql.getValue()).contains("GROUP BY CAST(user_id AS CHAR)");
        assertThat(params.getValue()).containsEntry("limit", 10);
    }
}

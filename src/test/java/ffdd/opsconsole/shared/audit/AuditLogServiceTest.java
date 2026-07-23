package ffdd.opsconsole.shared.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doThrow;

import ffdd.opsconsole.auth.mapper.AdminMapper;
import ffdd.opsconsole.shared.audit.mapper.AuditLogMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.apache.ibatis.annotations.Select;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

class AuditLogServiceTest {
    private final AuditLogMapper auditLogMapper = org.mockito.Mockito.mock(AuditLogMapper.class);
    private final AuditLogSanitizer sanitizer = new AuditLogSanitizer(new com.fasterxml.jackson.databind.ObjectMapper());
    private final ApplicationNameProperties applicationNameProperties = applicationNameProperties();
    private final AuditProperties auditProperties = new AuditProperties();
    private final AdminMapper adminMapper = org.mockito.Mockito.mock(AdminMapper.class);
    private final AuditLogService service = new AuditLogService(
            auditLogMapper, sanitizer, applicationNameProperties, auditProperties, adminMapper);

    private ApplicationNameProperties applicationNameProperties() {
        ApplicationNameProperties properties = new ApplicationNameProperties();
        properties.setName("nexion-test-service");
        return properties;
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        SecurityContextHolder.clearContext();
        RequestContextHolder.resetRequestAttributes();
    }

    @Test
    void authenticatedAdminIdentityOverridesCallerSuppliedActorUsername() {
        UsernamePasswordAuthenticationToken authentication =
                new UsernamePasswordAuthenticationToken("1", null, List.of());
        authentication.setDetails(Map.of("username", "superadmin"));
        SecurityContextHolder.getContext().setAuthentication(authentication);

        service.recordRequired(AuditLogWriteRequest.builder()
                .action("A1_OPERATOR_DISABLED")
                .resourceType("A1_ADMIN_ACCOUNT")
                .resourceId("210")
                .actorId(999L)
                .actorType("ADMIN")
                .actorUsername("spoofed-browser-actor")
                .build());

        ArgumentCaptor<AuditLogMapper.AuditLogWrite> params = ArgumentCaptor.forClass(AuditLogMapper.AuditLogWrite.class);
        verify(auditLogMapper).insertAuditLog(params.capture());
        assertThat(params.getValue().actorId()).isEqualTo(1L);
        assertThat(params.getValue().actorUsername()).isEqualTo("superadmin");
    }

    @Test
    void trustedSessionActorOverridesTheTargetUserImpersonationSecurityContext() {
        UsernamePasswordAuthenticationToken impersonation =
                new UsernamePasswordAuthenticationToken("52", null, List.of());
        impersonation.setDetails(Map.of("username", "U00000052", "subjectType", "IMPERSONATION"));
        SecurityContextHolder.getContext().setAuthentication(impersonation);
        when(adminMapper.findIdByUsername("superadmin")).thenReturn(1L);

        service.recordRequiredForTrustedActor(AuditLogWriteRequest.builder()
                .action("C2_USER_IMPERSONATION_PAGE_VIEWED")
                .resourceType("USER_IMPERSONATION")
                .resourceId("IMP-TRUSTED")
                .userId(52L)
                .actorType("ADMIN")
                .actorUsername("superadmin")
                .build());

        ArgumentCaptor<AuditLogMapper.AuditLogWrite> params = ArgumentCaptor.forClass(AuditLogMapper.AuditLogWrite.class);
        verify(auditLogMapper).insertAuditLog(params.capture());
        assertThat(params.getValue().actorId()).isEqualTo(1L);
        assertThat(params.getValue().actorUsername()).isEqualTo("superadmin");
    }

    @Test
    void exportCountSqlUsesEveryFilterThatTheExportListAccepts() throws Exception {
        Select annotation = AuditLogMapper.class
                .getMethod("countFiltered", AuditLogQueryRequest.class)
                .getAnnotation(Select.class);
        String sql = String.join("\n", annotation.value());

        for (String field : List.of(
                "q.traceId", "q.serviceName", "q.resourceType", "q.resourceId", "q.bizNo",
                "q.userId", "q.actorId", "q.result", "q.riskLevel")) {
            assertThat(sql).contains(field);
        }
    }

    @Test
    void listCountAndAggregateUseOneCanonicalMutuallyExclusiveDomainExpression() {
        for (String methodName : List.of("list", "countFiltered", "aggregateFiltered")) {
            java.lang.reflect.Method method = Arrays.stream(AuditLogMapper.class.getMethods())
                    .filter(candidate -> candidate.getName().equals(methodName))
                    .findFirst()
                    .orElseThrow();
            String sql = String.join("\n", method.getAnnotation(Select.class).value());
            assertThat(sql)
                    .as(methodName)
                    .contains("CASE", "$.sourceDomain", "$.domain", "THEN LEFT", "USER|账户|余额", "ELSE 'A'")
                    .doesNotContain("REGEXP CONCAT('^'");
            assertThat(sql.indexOf("$.sourceDomain")).isLessThan(sql.indexOf("$.domain"));
        }
    }

    @Test
    void auditClientIpIgnoresCallerControlledForwardingHeaders() {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("POST", "/api/admin/emergency/geo-block/countries/AQ");
        servletRequest.setRemoteAddr("10.0.0.8");
        servletRequest.addHeader("X-Forwarded-For", "203.0.113.77");
        servletRequest.addHeader("X-Real-IP", "203.0.113.78");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(servletRequest));

        service.record(AuditLogWriteRequest.builder()
                .action("J2_GEO_COUNTRY_STATUS_CHANGED")
                .resourceType("GEO_COUNTRY")
                .resourceId("AQ")
                .build());

        ArgumentCaptor<AuditLogMapper.AuditLogWrite> params = ArgumentCaptor.forClass(AuditLogMapper.AuditLogWrite.class);
        verify(auditLogMapper).insertAuditLog(params.capture());
        assertThat(params.getValue().clientIp()).isEqualTo("10.0.0.8");
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
    void recordRequiredPropagatesWriteFailureEvenWhenNormalAuditIsFailOpen() {
        doThrow(new IllegalStateException("audit unavailable"))
                .when(auditLogMapper).insertAuditLog(any());

        assertThatThrownBy(() -> service.recordRequired(AuditLogWriteRequest.builder()
                .action("I1_COPY_DRAFT_VERSION_DELETED")
                .resourceType("CONTENT_COPY")
                .resourceId("home.hero")
                .build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("audit unavailable");
    }

    @Test
    void listClampsLimitAndUsesOptionalFilters() {
        when(auditLogMapper.list(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), anyInt()))
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
                isNull(),
                isNull(),
                isNull(),
                isNull(),
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

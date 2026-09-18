package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import ffdd.opsconsole.bi.domain.BiReportRepository;
import ffdd.opsconsole.shared.audit.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ffdd.opsconsole.bi.web.OpsBiExportController;
import ffdd.opsconsole.shared.api.ApiResult;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.core.context.SecurityContextHolder;

class OpsBiExportQueryServiceTest {
    private final BiReportRepository reports = mock(BiReportRepository.class);
    private final AuditLogService audits = mock(AuditLogService.class);
    private final OpsBiExportQueryService service = new OpsBiExportQueryService(reports, audits);

    @Test void existingL5AuthorityStillGuardsAuditEndpoint() {
        try (var context = new AnnotationConfigApplicationContext(SecurityFixture.class)) {
            var controller = context.getBean(OpsBiExportController.class);
            var queries = context.getBean(OpsBiExportQueryService.class);
            when(queries.exportAudits(null, null, null, 10)).thenReturn(ApiResult.ok(List.of()));
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("operator", "unused", "bi_l4_write"));
            assertThatThrownBy(() -> controller.audit(null, null, null, 10)).isInstanceOf(AccessDeniedException.class);
            verifyNoInteractions(queries);
            SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("operator", "unused", "bi_l5_read"));
            assertThat(controller.audit(null, null, null, 10).getData()).isEmpty();
            verify(queries).exportAudits(null, null, null, 10);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    @Configuration @EnableMethodSecurity
    static class SecurityFixture {
        @Bean OpsBiExportQueryService queries() { return mock(OpsBiExportQueryService.class); }
        @Bean OpsBiService bi() { return mock(OpsBiService.class); }
        @Bean OpsBiExportController controller(OpsBiService bi, OpsBiExportQueryService queries) {
            return new OpsBiExportController(bi, queries);
        }
    }

    @Test void mergesVisibleHeadsInCanonicalAuditOrderAndAppliesOneLimit() {
        AuditLogRecord report = row(8, "ADMIN.REPORT_EXPORTED", "{\"rowCount\":1}");
        AuditLogRecord c1 = row(9, "ADMIN.USER_LIST_EXPORTED", "{\"rowCount\":3,\"masked\":true,\"filterHash\":\"" + "a".repeat(64) + "\"}");
        when(audits.list(any())).thenAnswer(call -> "ADMIN.REPORT_EXPORTED".equals(((AuditLogQueryRequest) call.getArgument(0)).getAction())
                ? List.of(report) : List.of(c1));
        LocalDateTime from = LocalDateTime.of(2026, 9, 1, 0, 0), to = from.plusDays(1);
        var result = service.exportAudits("operator", from, to, 1).getData();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("rows", 3L).containsEntry("pii", true).containsEntry("mask", "masked");
        assertThat(result.get(0).get("what")).isEqualTo("用户列表 / 检索条件摘要 " + "a".repeat(12));
        verifyNoInteractions(reports);
        ArgumentCaptor<AuditLogQueryRequest> requests = ArgumentCaptor.forClass(AuditLogQueryRequest.class);
        verify(audits, times(2)).list(requests.capture());
        assertThat(requests.getAllValues()).allSatisfy(q -> {
            assertThat(q.getOperator()).isEqualTo("operator");
            assertThat(q.getStartTime()).isEqualTo(from); assertThat(q.getEndTime()).isEqualTo(to);
            assertThat(q.getLimit()).isEqualTo(1);
        });
    }

    @Test void c1DoesNotExposeArbitraryDetailsOrClaimAnonymization() {
        when(audits.list(any())).thenReturn(List.of(row(3, "ADMIN.USER_LIST_EXPORTED",
                "{\"filterHash\":\"raw-phone-secret\",\"reason\":\"private reason\",\"masked\":false}")));
        var result = service.exportAudits(null, null, null, 20).getData();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("pii", true).containsEntry("mask", "—");
        assertThat(result.toString()).doesNotContain("raw-phone", "private reason");
    }

    @Test void existingReportProjectionRemainsAvailableAndUnrelatedActionsDoNotLeak() {
        when(reports.findReport(any())).thenReturn(Optional.empty());
        when(audits.list(any())).thenReturn(List.of(row(5, "ADMIN.REPORT_EXPORTED",
                "{\"exportType\":\"REGULATORY\",\"scope\":\"VN\",\"rowCount\":7,\"maskingPolicy\":\"MASKED\",\"containsPii\":false}"),
                row(6, "ADMIN.REPORT_EXPORTED_SECRET", "{}")));
        var result = service.exportAudits(null, null, null, 20).getData();
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).containsEntry("what", "监管报告 / VN").containsEntry("rows", 7L).containsEntry("pii", false);
    }

    private AuditLogRecord row(long id, String action, String detail) {
        AuditLogRecord row = new AuditLogRecord();
        row.setId(id); row.setAction(action); row.setDetailJson(detail);
        row.setActorUsername("operator"); row.setResourceId("r-" + id);
        row.setCreatedAt(LocalDateTime.of(2026, 9, 1, 12, 0));
        return row;
    }
}

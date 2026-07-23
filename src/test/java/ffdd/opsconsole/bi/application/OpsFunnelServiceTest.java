package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.bi.dto.B3FunnelViewRequest;
import ffdd.opsconsole.bi.mapper.BiReportMapper;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

@ExtendWith(MockitoExtension.class)
class OpsFunnelServiceTest {
    @Mock
    private BiReportMapper mapper;
    @Mock
    private AuditLogService auditLogService;

    private OpsFunnelService service;

    @BeforeEach
    void setUp() {
        service = new OpsFunnelService(mapper, auditLogService);
        TestingAuthenticationToken authentication = new TestingAuthenticationToken("9001", null);
        authentication.setAuthenticated(true);
        SecurityContextHolder.getContext().setAuthentication(authentication);
    }

    @AfterEach
    void clear() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void rejectsInvalidFiltersBeforeQueryingFacts() {
        assertThatThrownBy(() -> service.overview("2026-W99", "P3", "direct"))
                .isInstanceOf(BizException.class)
                .hasMessage("B3_COHORT_INVALID");
        assertThatThrownBy(() -> service.overview("2026-W20", "P9", "direct"))
                .isInstanceOf(BizException.class)
                .hasMessage("B3_PHASE_INVALID");
        assertThatThrownBy(() -> service.overview("2026-W20", "P3", "../secret"))
                .isInstanceOf(BizException.class)
                .hasMessage("B3_REF_INVALID");
        verify(mapper, never()).selectB3EventFacts();
    }

    @Test
    void exportsOnlyAggregateRowsAndRequiresAudit() {
        when(mapper.selectB3EventFacts()).thenReturn(List.of(
                event("auth.register_completed", "u1", LocalDateTime.now().minusDays(10))));

        OpsFunnelService.FunnelCsvFile file = service.export(null, null, null);

        String csv = new String(file.body(), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).contains("distinct_users", "\"注册\"");
        assertThat(csv).doesNotContain("u1");
        verify(auditLogService).recordRequired(any());
    }

    @Test
    void returnsConflictInsteadOfOverwritingAnotherSavedView() {
        when(mapper.findB3View(9001L, "周报")).thenReturn(Map.of(
                "name", "周报",
                "cohort", "2026-W20",
                "phase", "P2",
                "ref", "direct",
                "granularity", "WEEK",
                "comparison", "PREVIOUS"));

        assertThatThrownBy(() -> service.saveView(new B3FunnelViewRequest(
                "周报", "2026-W21", "P2", "direct", "WEEK", "PREVIOUS")))
                .isInstanceOf(BizException.class)
                .hasMessage("B3_VIEW_NAME_CONFLICT");
        verify(mapper, never()).insertB3View(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    private static Map<String, Object> event(String name, String actor, LocalDateTime at) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("eventName", name);
        row.put("actorId", actor);
        row.put("eventTs", at);
        row.put("phase", "P3");
        row.put("refCode", "direct");
        return row;
    }
}

package ffdd.opsconsole.bi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.bi.dto.B3FunnelViewRequest;
import ffdd.opsconsole.bi.domain.B3FunnelAnalytics;
import ffdd.opsconsole.bi.mapper.BiReportMapper;
import ffdd.opsconsole.platform.application.A4RuntimePolicyService;
import ffdd.opsconsole.shared.audit.AuditLogService;
import ffdd.opsconsole.shared.exception.BizException;
import ffdd.opsconsole.shared.idempotency.AdminIdempotencyService;
import java.time.LocalDateTime;
import java.util.ArrayList;
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
    @Mock
    private AdminIdempotencyService idempotencyService;
    @Mock
    private A4RuntimePolicyService a4RuntimePolicyService;

    private OpsFunnelService service;

    @BeforeEach
    void setUp() {
        lenient().when(a4RuntimePolicyService.day0Seconds()).thenReturn(90);
        service = new OpsFunnelService(mapper, auditLogService, idempotencyService, a4RuntimePolicyService);
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
        when(idempotencyService.execute(
                anyString(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> invocation.<java.util.function.Supplier<?>>getArgument(4).get());
        when(mapper.findB3View(9001L, "周报")).thenReturn(Map.of(
                "name", "周报",
                "cohort", "2026-W20",
                "phase", "P2",
                "ref", "direct",
                "granularity", "WEEK",
                "comparison", "PREVIOUS"));

        assertThatThrownBy(() -> service.saveView("b3-test-conflict", new B3FunnelViewRequest(
                "周报", "2026-W21", "P2", "direct", "WEEK", "PREVIOUS")))
                .isInstanceOf(BizException.class)
                .hasMessage("B3_VIEW_NAME_CONFLICT");
        verify(mapper, never()).insertB3View(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewKeepsOnlyTheLatestThirteenRegistrationCohorts() {
        List<Map<String, Object>> facts = new ArrayList<>();
        LocalDateTime first = LocalDateTime.now().minusWeeks(13);
        for (int index = 0; index < 14; index++) {
            LocalDateTime at = first.plusWeeks(index);
            facts.add(event("auth.register_completed", "u" + index, at));
            facts.add(event("checkout.completed", "u" + index, at.plusHours(1)));
        }
        when(mapper.selectB3EventFacts()).thenReturn(facts);

        List<Map<String, Object>> all = (List<Map<String, Object>>) B3FunnelAnalytics
                .calculate(facts, null, null, null).get("trend");
        List<Map<String, Object>> actual = (List<Map<String, Object>>) service
                .overview(null, null, null).getData().get("trend");

        assertThat(all).hasSize(14);
        assertThat(actual).containsExactlyElementsOf(all.subList(1, 14));
    }

    @Test
    @SuppressWarnings("unchecked")
    void overviewConsumesTheCurrentA4Day0Window() {
        when(a4RuntimePolicyService.day0Seconds()).thenReturn(120);
        when(mapper.selectB3EventFacts()).thenReturn(List.of(
                event("auth.register_completed", "u1", LocalDateTime.now().minusMinutes(3)),
                event("device.first_yield_received", "u1", LocalDateTime.now().minusMinutes(1))));

        Map<String, Object> result = service.overview(null, null, null).getData();
        Map<String, Object> aux = (Map<String, Object>) result.get("auxMetrics");
        assertThat(aux).containsEntry("day0WindowSeconds", 120);
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

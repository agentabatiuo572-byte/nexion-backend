package ffdd.opsconsole.content.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.content.application.LearningAcceptanceSandboxGate;
import ffdd.opsconsole.content.domain.LearningSandboxObservationWindow;
import ffdd.opsconsole.content.mapper.AppLearningMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.test.util.ReflectionTestUtils;

class OpsLearningAcceptanceObservationControllerTest {
    private final LearningAcceptanceSandboxGate gate = mock(LearningAcceptanceSandboxGate.class);
    private final AppLearningMapper mapper = mock(AppLearningMapper.class);
    private final OpsLearningAcceptanceObservationController controller =
            new OpsLearningAcceptanceObservationController(gate, mapper);

    @Test
    void observationUsesTheSeededI7ReadAuthority() throws Exception {
        PreAuthorize policy = OpsLearningAcceptanceObservationController.class
                .getMethod("observation", String.class)
                .getAnnotation(PreAuthorize.class);

        assertThat(policy.value()).isEqualTo("hasAuthority('content_i7_read')");
    }

    @Test
    void anyProductionLearningDownstreamWriteIsReturnedAsAViolation() {
        LocalDateTime fromAt = LocalDateTime.of(2026, 8, 12, 10, 0);
        LocalDateTime toAt = fromAt.plusMinutes(5);
        ReflectionTestUtils.setField(controller, "configuredRunId", "run-001");
        when(mapper.sandboxObservationWindow("run-001"))
                .thenReturn(new LearningSandboxObservationWindow(1, fromAt, toAt));
        when(mapper.productionLearningEventDelta("run-001", fromAt, toAt)).thenReturn(1);
        when(mapper.productionLearningAdminIdempotencyDelta("run-001", fromAt, toAt)).thenReturn(0);
        when(mapper.productionLearningCatalogVersionDelta("run-001", fromAt, toAt)).thenReturn(0);
        when(mapper.productionLearningCatalogAdminIdempotencyDelta("run-001", fromAt, toAt)).thenReturn(0);
        when(mapper.productionLearningCatalogAuditDelta("run-001", fromAt, toAt)).thenReturn(0);
        when(mapper.productionLearningCatalogOutboxDelta("run-001", fromAt, toAt)).thenReturn(0);
        when(mapper.sandboxObservationProgress("run-001")).thenReturn(List.of());
        when(mapper.sandboxObservationRewards("run-001")).thenReturn(List.of());
        when(mapper.sandboxObservationIdempotency("run-001")).thenReturn(List.of());

        Map<String, Object> data = controller.observation("run-001").getData();
        Map<?, ?> productionDelta = (Map<?, ?>) data.get("productionDelta");

        assertThat(productionDelta.get("status")).isEqualTo("VIOLATION");
        assertThat(productionDelta.get("event")).isEqualTo(1);
        assertThat(productionDelta.get("earningsRelease")).isEqualTo(0);
        assertThat(productionDelta.get("walletLedger")).isEqualTo(0);
        verify(mapper).productionLearningEarningsReleaseDelta(eq("run-001"), eq(fromAt), eq(toAt));
        verify(mapper).productionLearningWalletLedgerDelta(eq("run-001"), eq(fromAt), eq(toAt));
        verify(mapper).productionLearningCatalogVersionDelta(eq("run-001"), eq(fromAt), eq(toAt));
        verify(mapper).productionLearningCatalogAdminIdempotencyDelta(eq("run-001"), eq(fromAt), eq(toAt));
        verify(mapper).productionLearningCatalogAuditDelta(eq("run-001"), eq(fromAt), eq(toAt));
        verify(mapper).productionLearningCatalogOutboxDelta(eq("run-001"), eq(fromAt), eq(toAt));
    }
}

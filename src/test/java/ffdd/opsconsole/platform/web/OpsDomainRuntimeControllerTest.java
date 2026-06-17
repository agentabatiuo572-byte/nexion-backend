package ffdd.opsconsole.platform.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.platform.application.OpsDomainRuntimeService;
import ffdd.opsconsole.platform.dto.OpsDomainCommandValidationRequest;
import ffdd.opsconsole.platform.dto.OpsDomainCommandValidationResponse;
import ffdd.opsconsole.platform.dto.OpsDomainRuntimeContract;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpsDomainRuntimeControllerTest {
    private final OpsDomainRuntimeService runtimeService = mock(OpsDomainRuntimeService.class);
    private final OpsDomainRuntimeController controller = new OpsDomainRuntimeController(runtimeService);

    @Test
    void contractsDelegatesToRuntimeService() {
        when(runtimeService.contracts()).thenReturn(ApiResult.ok(List.of()));

        assertThat(controller.contracts().getCode()).isZero();

        verify(runtimeService).contracts();
    }

    @Test
    void domainContractDelegatesWithPathDomain() {
        OpsDomainRuntimeContract contract = new OpsDomainRuntimeContract(
                "B", "Dual ledger cockpit", "/api/admin/treasury", "ffdd.opsconsole.treasury",
                List.of(), List.of(), List.of("Idempotency-Key", "reason"), true, List.of(), List.of(), List.of(), "MIGRATING_TO_MONOLITH");
        when(runtimeService.contract("treasury")).thenReturn(ApiResult.ok(contract));

        assertThat(controller.contract("treasury").getData()).isSameAs(contract);

        verify(runtimeService).contract("treasury");
    }

    @Test
    void validateDelegatesWithIdempotencyHeader() {
        OpsDomainCommandValidationRequest request =
                new OpsDomainCommandValidationRequest("UPDATE_COVERAGE", "ReserveCoverage", null, null, 1.06D, 1.05D, "ok");
        OpsDomainCommandValidationResponse response =
                new OpsDomainCommandValidationResponse(true, "B", "UPDATE_COVERAGE", List.of("Idempotency-Key"), "B_OPS_COMMAND_ACCEPTED");
        when(runtimeService.validateCommand("treasury", "idem-1", request)).thenReturn(ApiResult.ok(response));

        assertThat(controller.validateCommand("treasury", "idem-1", request).getData()).isSameAs(response);

        verify(runtimeService).validateCommand("treasury", "idem-1", request);
    }
}

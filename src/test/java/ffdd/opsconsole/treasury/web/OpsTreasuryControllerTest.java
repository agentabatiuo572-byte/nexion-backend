package ffdd.opsconsole.treasury.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ffdd.opsconsole.shared.api.ApiResult;
import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import ffdd.opsconsole.treasury.dto.TreasuryInjectionRequest;
import ffdd.opsconsole.treasury.dto.TreasuryScopeRequest;
import java.math.BigDecimal;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OpsTreasuryControllerTest {
    private final OpsTreasuryService treasuryService = mock(OpsTreasuryService.class);
    private final OpsTreasuryController controller = new OpsTreasuryController(treasuryService);

    @Test
    void overviewDelegatesToTreasuryService() {
        when(treasuryService.overview(7)).thenReturn(ApiResult.ok(Map.of("domain", "B")));

        ApiResult<Map<String, Object>> result = controller.overview(7);

        assertThat(result.getData()).containsEntry("domain", "B");
        verify(treasuryService).overview(7);
    }

    @Test
    void injectionDelegatesWithIdempotencyHeader() {
        TreasuryInjectionRequest request =
                new TreasuryInjectionRequest(new BigDecimal("100"), "V-1", "top-up", "superadmin");
        when(treasuryService.createInjection("idem-1", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.createInjection("idem-1", request).getData()).containsEntry("ok", true);

        verify(treasuryService).createInjection("idem-1", request);
    }

    @Test
    void scopeDelegatesWithIdempotencyHeader() {
        TreasuryScopeRequest request = new TreasuryScopeRequest("active liabilities", "scope change", "superadmin");
        when(treasuryService.updateScope("idem-2", request)).thenReturn(ApiResult.ok(Map.of("ok", true)));

        assertThat(controller.updateScope("idem-2", request).getData()).containsEntry("ok", true);

        verify(treasuryService).updateScope("idem-2", request);
    }
}

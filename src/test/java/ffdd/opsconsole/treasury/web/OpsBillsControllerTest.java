package ffdd.opsconsole.treasury.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.treasury.application.OpsTreasuryService;
import org.junit.jupiter.api.Test;

class OpsBillsControllerTest {

    @Test
    void userDrillDownForwardsTheVisibleDateRange() {
        OpsTreasuryService service = mock(OpsTreasuryService.class);
        OpsBillsController controller = new OpsBillsController(service);

        controller.userLedger(52L, "2026-07-25", "2026-07-26");
        controller.runningBalance(52L, "2026-07-25", "2026-07-26");

        verify(service).userLedger(52L, "2026-07-25", "2026-07-26");
        verify(service).runningBalance(52L, "2026-07-25", "2026-07-26");
    }
}

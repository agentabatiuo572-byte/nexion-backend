package ffdd.opsconsole.janus.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import ffdd.opsconsole.janus.application.OpsJanusService;
import ffdd.opsconsole.janus.dto.JanusDeviceQueryRequest;
import ffdd.opsconsole.janus.dto.JanusStatusChangeRequest;
import org.junit.jupiter.api.Test;

class OpsJanusControllerTest {
    private final OpsJanusService service = mock(OpsJanusService.class);
    private final OpsJanusController controller = new OpsJanusController(service);

    @Test
    void deviceQueueDelegatesServerPaginationAndFilters() {
        JanusDeviceQueryRequest query = new JanusDeviceQueryRequest("sid", "OBSERVING", "high", "official", null, 2, 25);
        controller.devices(query);
        verify(service).devices(query);
    }

    @Test
    void statusMutationPassesIdempotencyKeyAndOptimisticVersion() {
        JanusStatusChangeRequest request = new JanusStatusChangeRequest(
                "HIT", "现场复核", "人工复核证据完整", "immediate", null, null, "standard", 7L);
        controller.updateStatus("SID-1", "idem-1", request);
        verify(service).updateStatus("SID-1", "idem-1", request);
    }
}

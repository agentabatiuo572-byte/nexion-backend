package ffdd.opsconsole.device.dto;

import java.time.LocalDateTime;
import java.util.List;

public record AppTaskAssignmentsResponse(
        LocalDateTime serverNow,
        List<AppTaskDeviceState> devices,
        String source,
        String sourceEnvironment,
        String runId,
        boolean serverCanonical) {
}

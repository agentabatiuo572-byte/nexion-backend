package ffdd.opsconsole.device.dto;

import java.util.Map;

public record ComputeConfigBatchResponse(
        Map<String, String> values,
        String updatedAt) {
}

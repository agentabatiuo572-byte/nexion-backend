package ffdd.opsconsole.device.dto;

import java.util.Map;

public record ComputeConfigBatchUpdateRequest(
        Map<String, String> values,
        String reason,
        String operator) {
}

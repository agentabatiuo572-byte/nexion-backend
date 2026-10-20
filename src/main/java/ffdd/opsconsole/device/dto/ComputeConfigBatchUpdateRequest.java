package ffdd.opsconsole.device.dto;

import java.util.Map;

public record ComputeConfigBatchUpdateRequest(
        Map<String, String> values,
        String reason,
        String operator,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=ffdd.opsconsole.shared.api.StrictVersionDeserializer.class) Long expectedPolicyVersion) {
    public ComputeConfigBatchUpdateRequest(Map<String,String> values, String reason, String operator) {
        this(values, reason, operator, null);
    }
}

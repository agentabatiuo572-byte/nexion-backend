package ffdd.opsconsole.device.dto;
public record ComputeConfigParamUpdateRequest(String value, String reason, String operator,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=ffdd.opsconsole.shared.api.StrictVersionDeserializer.class) Long expectedPolicyVersion) {
    public ComputeConfigParamUpdateRequest(String value, String reason, String operator) {
        this(value, reason, operator, null);
    }
}

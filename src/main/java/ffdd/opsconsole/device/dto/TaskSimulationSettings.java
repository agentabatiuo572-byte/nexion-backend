package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record TaskSimulationSettings(
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=DeviceTaskUpsertRequest.StrictSchedulingInteger.class)
        Integer durationMinutes, BigDecimal baseReward, String asset, Boolean enabled,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long expectedVersion) {
    public static final class StrictVersion extends com.fasterxml.jackson.databind.JsonDeserializer<Long> {
        @Override public Long deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if(!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Long.class,"SIMULATED_POLICY_VERSION_REQUIRED");
            return parser.getLongValue();
        }
    }
}

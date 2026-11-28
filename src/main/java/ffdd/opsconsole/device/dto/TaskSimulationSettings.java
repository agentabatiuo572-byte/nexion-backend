package ffdd.opsconsole.device.dto;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import ffdd.opsconsole.shared.exception.BizException;

/** PHONE tasks carry duration and lifecycle only. Calibration rates determine their rewards. */
public record TaskSimulationSettings(
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=DeviceTaskUpsertRequest.StrictSchedulingInteger.class)
        Integer durationSeconds, Boolean enabled,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictVersion.class) Long expectedVersion) {
    @JsonAnySetter
    public void rejectRetiredReward(String key, Object value) {
        if ("baseReward".equals(key) || "asset".equals(key))
            throw new BizException(422, "PHONE_TASK_REWARD_FIELDS_FORBIDDEN");
    }

    public static final class StrictVersion extends com.fasterxml.jackson.databind.JsonDeserializer<Long> {
        @Override public Long deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if(!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Long.class,"SIMULATED_POLICY_VERSION_REQUIRED");
            return parser.getLongValue();
        }
    }
}

package ffdd.opsconsole.device.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import java.io.IOException;
import java.math.BigDecimal;

/**
 * Single-action update for the E2 server-canonical task pricing surface.
 * Exactly one of queueSaturation or a taskClass patch is submitted by the UI.
 */
public record E2TaskPricingUpdateRequest(
        String taskClass,
        BigDecimal minReward,
        BigDecimal maxReward,
        @JsonDeserialize(using = StrictVram.class) Integer minVram,
        Boolean enabled,
        BigDecimal queueSaturation,
        String reason,
        String operator,
        String expectedRevision) {

    public E2TaskPricingUpdateRequest(
            String taskClass, BigDecimal minReward, BigDecimal maxReward, Integer minVram,
            Boolean enabled, BigDecimal queueSaturation, String reason, String operator) {
        this(taskClass, minReward, maxReward, minVram, enabled, queueSaturation, reason, operator, null);
    }

    public static final class StrictVram extends JsonDeserializer<Integer> {
        @Override
        public Integer deserialize(JsonParser parser, DeserializationContext context) throws IOException {
            if (!parser.hasToken(JsonToken.VALUE_NUMBER_INT)) {
                return context.reportInputMismatch(Integer.class, "TASK_MIN_VRAM_INVALID");
            }
            return parser.getIntValue();
        }
    }
}

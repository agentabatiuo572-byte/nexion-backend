package ffdd.opsconsole.device.dto;

import java.math.BigDecimal;

public record DeviceTaskUpsertRequest(
        String name,
        BigDecimal price,
        String unit,
        String requirement,
        BigDecimal saturation,
        String status,
        String taskClass,
        String model,
        BigDecimal minReward,
        BigDecimal maxReward,
        String minVram,
        String killInit,
        String reason,
        String operator,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictSchedulingInteger.class) Integer selectionWeight,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictSchedulingInteger.class) Integer expectedDurationMinSec,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictSchedulingInteger.class) Integer expectedDurationMaxSec,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=StrictSchedulingInteger.class) Integer executionTimeoutSec,
        String runtimeKey,
        String validatorKey,
        String workloadConfigJson, TaskSimulationSettings simulation,String poolCode) {
    public boolean hasSchedulingSettings() {
        return selectionWeight != null || expectedDurationMinSec != null || expectedDurationMaxSec != null
                || executionTimeoutSec != null || runtimeKey != null || validatorKey != null || workloadConfigJson != null;
    }

    public static final class StrictSchedulingInteger extends com.fasterxml.jackson.databind.JsonDeserializer<Integer> {
        @Override public Integer deserialize(com.fasterxml.jackson.core.JsonParser parser,
                com.fasterxml.jackson.databind.DeserializationContext context) throws java.io.IOException {
            if (!parser.hasToken(com.fasterxml.jackson.core.JsonToken.VALUE_NUMBER_INT))
                return context.reportInputMismatch(Integer.class,"TASK_SCHEDULING_VALUE_INVALID");
            return parser.getIntValue();
        }
    }
}

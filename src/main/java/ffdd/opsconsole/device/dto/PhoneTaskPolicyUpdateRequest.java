package ffdd.opsconsole.device.dto;

import java.util.List;

public record PhoneTaskPolicyUpdateRequest(List<String> allowedTaskTypeIds,
        @com.fasterxml.jackson.databind.annotation.JsonDeserialize(using=TaskSimulationSettings.StrictVersion.class) Long expectedVersion,
        String reason,String operator,List<String> allowedTaskClasses) { }
